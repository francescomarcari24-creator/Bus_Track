package com.example.bustrack.ui

import android.app.Application
import android.location.Address
import android.location.Geocoder
import android.util.Log
import com.example.bustrack.gtfs.GtfsStaticDb
import com.example.bustrack.gtfs.Polyline
import com.example.bustrack.gtfs.Stop
import com.example.bustrack.gtfs.TransitRepository
import com.example.bustrack.gtfs.distanceMeters
import com.example.bustrack.routing.JourneyLeg
import com.example.bustrack.routing.PlannedJourney
import com.example.bustrack.routing.RoutePlanner
import com.example.bustrack.routing.RoutingEngine
import com.example.bustrack.routing.WalkDatabase
import com.example.bustrack.routing.WalkGraphLoader
import com.example.bustrack.routing.WalkInstruction
import com.example.bustrack.routing.WalkInstructions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume

enum class PlaceKind { MY_LOCATION, STOP, ADDRESS }

/** Luogo scelto come partenza o arrivo. */
data class Place(
    val name: String,
    val detail: String?,
    val lat: Double,
    val lon: Double,
    val kind: PlaceKind,
)

enum class PlaceField { FROM, TO }

/** Un tratto di un percorso pronto da mostrare (orari in epoch secondi). */
sealed interface LegView {
    val departure: Long
    val arrival: Long

    class Walk(
        override val departure: Long,
        override val arrival: Long,
        val meters: Int,
        val instructions: List<WalkInstruction>,
        /** Palina a cui porta (null = la meta). */
        val toStop: Stop?,
        val lat: DoubleArray,
        val lon: DoubleArray,
    ) : LegView

    class Ride(
        override val departure: Long,
        override val arrival: Long,
        val tripId: String,
        val routeId: String,
        val label: String,
        val headsign: String?,
        val from: Stop,
        val to: Stop,
        val stopCount: Int,
        val lat: DoubleArray,
        val lon: DoubleArray,
    ) : LegView
}

class JourneyView(val legs: List<LegView>, val rides: Int, val walkMeters: Int, val origin: Place, val destination: Place) {
    val departure: Long get() = legs.first().departure
    val arrival: Long get() = legs.last().arrival
    val key: String = legs.joinToString("|") { "${it.departure}-${(it as? LegView.Ride)?.tripId}" }
}

sealed interface PlanStatus {
    data object Idle : PlanStatus
    data object Locating : PlanStatus
    data object Preparing : PlanStatus
    data object Searching : PlanStatus
    /** [live] = orari corretti con i ritardi live e senza corse fantasma. */
    data class Ready(val journeys: List<JourneyView>, val live: Boolean = false) : PlanStatus
    data class Failed(val message: String) : PlanStatus
}

data class NavigationState(
    val suggestions: List<Place> = emptyList(),
    /** Campo a cui si riferiscono i suggerimenti. */
    val suggestField: PlaceField? = null,
    val searchingPlaces: Boolean = false,
    /** null = posizione attuale. */
    val origin: Place? = null,
    val destination: Place? = null,
    val plan: PlanStatus = PlanStatus.Idle,
    /** Navigazione in corso. */
    val active: JourneyView? = null,
    /** Orari live delle corse della navigazione, per "tripId|stopId" (epoch secondi). */
    val live: Map<String, Long> = emptyMap(),
)

/**
 * Logica della scheda Nav: suggerimenti dei luoghi (fermate dal GTFS e indirizzi dal geocoder di
 * Android, limitati all'area di Roma), calcolo dei percorsi con [RoutingEngine] e dati live della
 * navigazione in corso.
 */
class NavigationModel(
    private val app: Application,
    private val scope: CoroutineScope,
    private val repo: TransitRepository,
) {
    private val settings = com.example.bustrack.settings.AppSettings.get(app)
    /** Preferenze sul percorso (salvate). */
    val options: StateFlow<com.example.bustrack.routing.PlanOptions> = settings.routeOptions

    /**
     * Cambia le preferenze e, se c'è già una meta, ricalcola i percorsi. Vale anche a navigazione
     * avviata: la navigazione si chiude e si torna all'elenco dei percorsi, ricalcolato.
     */
    fun setOptions(value: com.example.bustrack.routing.PlanOptions) {
        settings.setRouteOptions(value)
        if (_state.value.destination != null) {
            _state.update { it.copy(active = null, live = emptyMap()) }
            plan()
        }
    }

    private val _state = MutableStateFlow(NavigationState())
    val state: StateFlow<NavigationState> = _state
    private var suggestJob: Job? = null
    private var planJob: Job? = null

    /** Carica in anticipo grafo pedonale e orari del giorno (non fa nulla se sono già pronti). */
    fun prepareEngine() {
        scope.launch { runCatching { RoutingEngine.prepare(app) }.onFailure { Log.e(TAG, "Motore percorsi", it) } }
    }

    /** Testo scritto in un campo: la scelta fatta prima per quel campo non vale più; nuovi suggerimenti. */
    fun query(field: PlaceField, text: String) {
        suggestJob?.cancel()
        _state.update { s ->
            when (field) {
                PlaceField.FROM -> if (s.origin?.name != text) s.copy(origin = null) else s
                PlaceField.TO -> if (s.destination?.name != text) s.copy(destination = null, plan = PlanStatus.Idle) else s
            }
        }
        val q = text.trim()
        if (q.length < 2) {
            _state.update { it.copy(suggestions = emptyList(), suggestField = field, searchingPlaces = false) }
            return
        }
        suggestJob = scope.launch {
            delay(250)
            _state.update { it.copy(suggestField = field, searchingPlaces = true) }
            val stops = runCatching { repo.searchStops(q) }.getOrDefault(emptyList()).take(5).map {
                Place(it.name, "Palina ${it.code ?: it.id}", it.lat, it.lon, PlaceKind.STOP)
            }
            _state.update { it.copy(suggestions = stops, suggestField = field) }
            // Indirizzi e luoghi dopo le fermate: arrivano più tardi e non spostano le righe già viste.
            val places = geocode(q).filter { p -> stops.none { distanceMeters(it.lat, it.lon, p.lat, p.lon) < 30 } }
            _state.update { it.copy(suggestions = stops + places, suggestField = field, searchingPlaces = false) }
        }
    }

    /** Luogo scelto da un suggerimento ([place] null = posizione attuale, solo per la partenza). */
    fun choose(field: PlaceField, place: Place?) {
        suggestJob?.cancel()
        _state.update {
            val s = it.copy(suggestions = emptyList(), suggestField = null, searchingPlaces = false)
            when (field) {
                PlaceField.FROM -> s.copy(origin = place)
                PlaceField.TO -> s.copy(destination = place)
            }
        }
        plan()
    }

    fun clearSuggestions() {
        suggestJob?.cancel()
        _state.update { it.copy(suggestions = emptyList(), suggestField = null, searchingPlaces = false) }
    }

    /** Calcola i percorsi da partenza (o posizione attuale) ad arrivo, in partenza adesso. */
    fun plan() {
        val dest = _state.value.destination ?: return
        planJob?.cancel()
        planJob = scope.launch {
            try {
                val origin = _state.value.origin ?: run {
                    _state.update { it.copy(plan = PlanStatus.Locating) }
                    val loc = app.currentLocation()
                    if (loc == null) {
                        fail("Non trovo la tua posizione: attiva la localizzazione oppure scegli la partenza.")
                        return@launch
                    }
                    Place("La tua posizione", null, loc.latitude, loc.longitude, PlaceKind.MY_LOCATION)
                }
                _state.update { it.copy(plan = PlanStatus.Preparing) }
                val now = System.currentTimeMillis() / 1000
                // Previsioni live scaricate intanto che si preparano gli orari.
                val liveJob = scope.async { withTimeoutOrNull(8_000) { runCatching { repo.tripUpdates() }.getOrNull() } }
                val planner = RoutingEngine.prepare(app)
                _state.update { it.copy(plan = PlanStatus.Searching) }
                val live = liveJob.await()?.let { snapshot -> liveInfo(snapshot.items, now) }
                val result = withContext(Dispatchers.Default) {
                    planner.plan(origin.lat, origin.lon, dest.lat, dest.lon, now, options.value, live)
                }
                val status = when (result) {
                    RoutePlanner.Result.OffNetwork -> PlanStatus.Failed("Partenza o arrivo fuori dalla zona coperta (Roma).")
                    RoutePlanner.Result.NoRoute -> PlanStatus.Failed(
                        if (options.value == com.example.bustrack.routing.PlanOptions()) "Nessun percorso trovato in partenza adesso."
                        else "Nessun percorso trovato in partenza adesso con le preferenze scelte."
                    )
                    is RoutePlanner.Result.Journeys ->
                        PlanStatus.Ready(withContext(Dispatchers.IO) { result.journeys.map { toView(it, origin, dest) } }, live = live != null)
                }
                _state.update { it.copy(plan = status) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Calcolo del percorso", e)
                fail("Errore nel calcolo del percorso.")
            }
        }
    }

    /**
     * Ritardo attuale di ogni corsa nel feed: quello della prossima fermata non ancora passata
     * (le previsioni più lontane sono meno affidabili); più l'elenco di tutte le corse del feed.
     */
    private fun liveInfo(items: List<com.example.bustrack.gtfs.Arrival>, now: Long): com.example.bustrack.routing.LiveInfo {
        val delays = HashMap<String, Int>()
        for ((tripId, list) in items.groupBy { it.tripId }) {
            val next = list.filter { it.time >= now - 60 }.minByOrNull { it.time } ?: continue
            next.delaySeconds?.let { delays[tripId] = it }
        }
        return com.example.bustrack.routing.LiveInfo(delays, items.mapTo(HashSet()) { it.tripId })
    }

    private fun fail(message: String) = _state.update { it.copy(plan = PlanStatus.Failed(message)) }

    fun startNavigation(journey: JourneyView) = _state.update { it.copy(active = journey, live = emptyMap()) }

    fun stopNavigation() = _state.update { it.copy(active = null, live = emptyMap()) }

    /**
     * Aggiorna gli orari live di salite e discese della navigazione in corso.
     * Restituisce il timestamp del feed (per agganciare il prossimo aggiornamento), null se fallito.
     */
    suspend fun refreshLive(): Long? {
        val journey = _state.value.active ?: return null
        val rides = journey.legs.filterIsInstance<LegView.Ride>()
        if (rides.isEmpty()) return null
        val snapshot = runCatching { repo.tripUpdates() }.getOrNull() ?: return null
        val wanted = rides.flatMap { listOf("${it.tripId}|${it.from.id}", "${it.tripId}|${it.to.id}") }.toSet()
        val live = snapshot.items.asSequence()
            .map { "${it.tripId}|${it.stopId}" to it.time }
            .filter { it.first in wanted }
            .toMap()
        _state.update { if (it.active === journey) it.copy(live = live) else it }
        return snapshot.feedTimestamp
    }

    // ---- Conversione dei percorsi per la UI ----

    private fun toView(j: PlannedJourney, origin: Place, dest: Place): JourneyView {
        val gtfs = GtfsStaticDb.shared(app)
        val graph = checkNotNull(RoutingEngine.walkGraph())
        val walkDb = WalkDatabase.open(app)
        val edges = j.legs.filterIsInstance<JourneyLeg.Walk>().flatMap { w -> w.steps.map { it.edge } }
        val shapes = WalkGraphLoader.shapes(walkDb, edges).mapValues { Polyline.decode(it.value) }
        val legs = j.legs.map { leg ->
            when (leg) {
                is JourneyLeg.Walk -> {
                    val fromStop = leg.fromStopId?.let(gtfs::stop)
                    val toStop = leg.toStopId?.let(gtfs::stop)
                    val lat = ArrayList<Double>()
                    val lon = ArrayList<Double>()
                    lat += fromStop?.lat ?: origin.lat
                    lon += fromStop?.lon ?: origin.lon
                    for (s in leg.steps) {
                        val (la, lo) = shapes[s.edge] ?: continue
                        val idx = if (graph.edgeA[s.edge] == s.fromNode) la.indices else la.indices.reversed()
                        for (i in idx) { lat += la[i]; lon += lo[i] }
                    }
                    lat += toStop?.lat ?: dest.lat
                    lon += toStop?.lon ?: dest.lon
                    val arrival = if (toStop != null) {
                        "Arrivi alla palina ${toStop.name}" + (toStop.code?.let { " ($it)" } ?: "")
                    } else {
                        "Sei arrivato: ${dest.name}"
                    }
                    LegView.Walk(
                        leg.departure, leg.arrival, leg.meters,
                        WalkInstructions.build(graph, leg.steps, arrival), toStop,
                        lat.toDoubleArray(), lon.toDoubleArray(),
                    )
                }
                is JourneyLeg.Ride -> {
                    val from = checkNotNull(gtfs.stop(leg.fromStopId))
                    val to = checkNotNull(gtfs.stop(leg.toStopId))
                    val (lat, lon) = rideShape(gtfs, leg)
                    LegView.Ride(
                        leg.departure, leg.arrival, leg.tripId, leg.routeId,
                        gtfs.route(leg.routeId)?.label ?: leg.routeId, leg.headsign,
                        from, to, leg.stopCount, lat, lon,
                    )
                }
            }
        }
        return JourneyView(legs, j.rides, j.walkMeters, origin, dest)
    }

    /**
     * Tracciato del tratto in mezzo: il pezzo del tracciato della linea tra la fermata di salita e
     * quella di discesa; se nessun tracciato passa vicino a entrambe, la spezzata tra le fermate.
     */
    private fun rideShape(gtfs: GtfsStaticDb, leg: JourneyLeg.Ride): Pair<DoubleArray, DoubleArray> {
        val stops = leg.stopIds.mapNotNull(gtfs::stop)
        val fallback = DoubleArray(stops.size) { stops[it].lat } to DoubleArray(stops.size) { stops[it].lon }
        if (stops.size < 2) return fallback
        val a = stops.first()
        val b = stops.last()
        var best: Pair<DoubleArray, DoubleArray>? = null
        var bestScore = 300.0
        gtfs.db.rawQuery("SELECT polyline FROM route_shapes WHERE route_id = ?", arrayOf(leg.routeId)).use { c ->
            while (c.moveToNext()) {
                val (la, lo) = Polyline.decode(c.getString(0))
                val (i1, d1) = nearest(la, lo, a, 0) ?: continue
                val (i2, d2) = nearest(la, lo, b, i1 + 1) ?: continue
                if (d1 + d2 < bestScore) {
                    bestScore = d1 + d2
                    best = (doubleArrayOf(a.lat) + la.copyOfRange(i1, i2 + 1) + doubleArrayOf(b.lat)) to
                        (doubleArrayOf(a.lon) + lo.copyOfRange(i1, i2 + 1) + doubleArrayOf(b.lon))
                }
            }
        }
        return best ?: fallback
    }

    private fun nearest(lat: DoubleArray, lon: DoubleArray, stop: Stop, from: Int): Pair<Int, Double>? {
        var bi = -1
        var bd = Double.MAX_VALUE
        for (i in from until lat.size) {
            val d = distanceMeters(stop.lat, stop.lon, lat[i], lon[i])
            if (d < bd) { bd = d; bi = i }
        }
        return if (bi >= 0) bi to bd else null
    }

    // ---- Geocoder ----

    private suspend fun geocode(query: String): List<Place> {
        if (!Geocoder.isPresent()) return emptyList()
        val addresses = withTimeoutOrNull(6_000) {
            suspendCancellableCoroutine<List<Address>> { cont ->
                // Solo nell'area di Roma: "Via Nazionale" deve essere quella di Roma.
                Geocoder(app, Locale.ITALY).getFromLocationName(
                    query, 5, 41.65, 12.20, 42.15, 12.85,
                    object : Geocoder.GeocodeListener {
                        override fun onGeocode(addresses: MutableList<Address>) { if (cont.isActive) cont.resume(addresses) }
                        override fun onError(errorMessage: String?) { if (cont.isActive) cont.resume(emptyList()) }
                    },
                )
            }
        }.orEmpty()
        return addresses.filter { it.hasLatitude() && it.hasLongitude() }
            .mapNotNull(::placeOf)
            .distinctBy { "${it.name}|${it.detail}" }
    }

    /**
     * Nome pulito di un risultato del geocoder:
     * - titolo: il nome del luogo (es. "Colosseo") se c'è, altrimenti via e civico ("Via Nazionale, 194");
     * - dettaglio: quartiere e città ("Monti, Roma"), senza CAP, provincia ("Città Metropolitana di
     *   Roma Capitale"), regione e nazione.
     * Si scartano i risultati che sono solo una città o una zona intera (inutili come meta).
     */
    private fun placeOf(a: Address): Place? {
        val street = a.thoroughfare?.let { t -> a.subThoroughfare?.let { "$t, $it" } ?: t }
        val city = a.locality ?: a.subAdminArea?.let(::cleanArea)
        val feature = a.featureName?.trim()?.takeIf { f ->
            f.isNotEmpty() && !f.first().isDigit() && f != a.thoroughfare && f != a.subThoroughfare &&
                f != a.locality && f != a.subLocality && f != a.postalCode && f != a.adminArea && f != a.subAdminArea
        }
        val title = feature ?: street ?: return null
        val detail = listOfNotNull(
            street.takeIf { feature != null },
            a.subLocality?.takeIf { it != title },
            city,
        ).distinct().joinToString(", ").ifEmpty { null }
        return Place(title, detail, a.latitude, a.longitude, PlaceKind.ADDRESS)
    }

    /** "Città Metropolitana di Roma Capitale" → "Roma". */
    private fun cleanArea(area: String): String =
        area.removePrefix("Città Metropolitana di ").removePrefix("Città metropolitana di ")
            .removePrefix("Provincia di ").removeSuffix(" Capitale").trim()

    private companion object {
        const val TAG = "BusTrack"
    }
}
