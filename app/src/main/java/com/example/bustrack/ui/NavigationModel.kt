package com.example.bustrack.ui

import android.app.Application
import android.location.Address
import android.location.Geocoder
import android.location.Location
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
        /** Tutte le fermate del tratto, dalla salita alla discesa, e i loro orari (epoch secondi). */
        val stops: List<Stop> = listOf(from, to),
        val stopTimes: LongArray = longArrayOf(departure, arrival),
    ) : LegView
}

fun LegView.lat(): DoubleArray = when (this) { is LegView.Walk -> lat; is LegView.Ride -> lat }
fun LegView.lon(): DoubleArray = when (this) { is LegView.Walk -> lon; is LegView.Ride -> lon }

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
    /** Dove si è nel percorso, dalla posizione (null finché non arriva una posizione). */
    val progress: NavProgress? = null,
    /** Ultimo ricalcolo automatico del percorso durante il viaggio. */
    val reroute: RerouteNotice? = null,
    /** Fermata presso cui si trova il mezzo di ogni corsa della navigazione (trip_id → nome), dal feed. */
    val vehicleAt: Map<String, String> = emptyMap(),
)

/**
 * Tratto in corso ([leg], indice in [JourneyView.legs]); se a piedi, metri che mancano alla sua fine;
 * se in mezzo, indice in [LegView.Ride.stops] della fermata più vicina (non si torna indietro).
 */
data class NavProgress(val leg: Int, val remainingMeters: Int?, val rideStop: Int? = null)

/** Percorso ricalcolato alle [at] (epoch secondi) per il motivo [reason]. */
data class RerouteNotice(val at: Long, val reason: String)

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
            _state.update { it.copy(active = null, live = emptyMap(), progress = null, reroute = null) }
            plan()
        }
    }

    private val _state = MutableStateFlow(NavigationState(active = ActiveJourneyStore.load(app)))
    val state: StateFlow<NavigationState> = _state

    init {
        // La navigazione in corso si salva su disco a ogni cambio (avvio, ricalcolo, fine): se il
        // sistema chiude l'app, riaprendola si riprende da dove si era.
        scope.launch {
            var saved = _state.value.active
            _state.collect { s ->
                if (s.active !== saved) {
                    saved = s.active
                    withContext(Dispatchers.IO) { ActiveJourneyStore.save(app, s.active) }
                }
            }
        }
    }

    /**
     * Torna alla pagina iniziale della scheda Nav (tasto "Nav" della barra): toglie arrivo,
     * partenza scelta, percorsi e suggerimenti. Una navigazione in corso resta.
     */
    fun resetSearch() {
        suggestJob?.cancel()
        planJob?.cancel()
        _state.update {
            it.copy(
                origin = null, destination = null, plan = PlanStatus.Idle,
                suggestions = emptyList(), suggestField = null, searchingPlaces = false,
            )
        }
    }

    /** Ultimi luoghi scelti (pannello "Ricerche recenti" dei campi). */
    val recents = NavRecents.get(app)

    /**
     * Navigazione diretta verso [place] (es. una fermata aperta dalla Home): chiude un'eventuale
     * navigazione in corso, imposta l'arrivo e calcola i percorsi dalla partenza scelta.
     */
    fun navigateTo(place: Place) {
        suggestJob?.cancel()
        recents.add(place)
        _state.update {
            it.copy(
                destination = place, active = null, live = emptyMap(), progress = null, reroute = null,
                suggestions = emptyList(), suggestField = null, searchingPlaces = false,
            )
        }
        plan()
    }
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
        place?.let(recents::add)
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
                val status = compute(origin, dest) { _state.update { it.copy(plan = PlanStatus.Searching) } }
                _state.update { it.copy(plan = status) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Calcolo del percorso", e)
                fail("Errore nel calcolo del percorso.")
            }
        }
    }

    /** Percorsi da [origin] a [dest] in partenza adesso, con i dati live se raggiungibili. */
    private suspend fun compute(origin: Place, dest: Place, onSearching: () -> Unit = {}): PlanStatus {
        val now = System.currentTimeMillis() / 1000
        // Previsioni live scaricate intanto che si preparano gli orari.
        val liveJob = scope.async { withTimeoutOrNull(8_000) { runCatching { repo.tripUpdates() }.getOrNull() } }
        val planner = RoutingEngine.prepare(app)
        onSearching()
        val live = liveJob.await()?.let { snapshot -> liveInfo(snapshot.items, now) }
        val result = withContext(Dispatchers.Default) {
            planner.plan(origin.lat, origin.lon, dest.lat, dest.lon, now, options.value, live)
        }
        return when (result) {
            RoutePlanner.Result.OffNetwork -> PlanStatus.Failed("Partenza o arrivo fuori dalla zona coperta (Roma).")
            RoutePlanner.Result.NoRoute -> PlanStatus.Failed(
                if (options.value == com.example.bustrack.routing.PlanOptions()) "Nessun percorso trovato in partenza adesso."
                else "Nessun percorso trovato in partenza adesso con le preferenze scelte."
            )
            is RoutePlanner.Result.Journeys ->
                PlanStatus.Ready(withContext(Dispatchers.IO) { result.journeys.map { toView(it, origin, dest) } }, live = live != null)
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

    fun startNavigation(journey: JourneyView) {
        offRouteFixes = 0
        _state.update { it.copy(active = journey, live = emptyMap(), progress = null, reroute = null) }
    }

    fun stopNavigation() = _state.update { it.copy(active = null, live = emptyMap(), progress = null, reroute = null) }

    // ---- Posizione durante il viaggio e ricalcolo ----

    private var offRouteFixes = 0
    private var lastRerouteAt = 0L
    private var rerouting = false

    /**
     * Segue la posizione finché la coroutine è attiva (la chiama l'app mentre è in primo piano e
     * c'è una navigazione): aggiorna il tratto in corso e, se serve, ricalcola il percorso.
     */
    suspend fun track() {
        app.locationUpdates().collect(::onLocation)
    }

    /** Nuova posizione (dal servizio della navigazione). */
    fun onLocation(loc: Location) {
        val journey = _state.value.active ?: return
        val accuracy = if (loc.hasAccuracy()) loc.accuracy.toDouble() else 30.0
        // Al chiuso la precisione è spesso scarsa: fino a 200 m serve per seguire il passo in corso,
        // ma per ricalcolare il percorso (sotto) serve una posizione entro 50 m.
        if (accuracy > 200) return
        val now = System.currentTimeMillis() / 1000
        // Tratto in corso: il più vicino tra quello attuale e i due successivi (non si torna indietro,
        // e non si salta lontano solo perché la strada incrocia un tratto di molto dopo).
        val from = _state.value.progress?.leg ?: 0
        var leg = from
        var proj = projectOnPolyline(loc.latitude, loc.longitude, journey.legs[from].lat(), journey.legs[from].lon())
        for (i in from + 1..minOf(from + 2, journey.legs.lastIndex)) {
            val p = projectOnPolyline(loc.latitude, loc.longitude, journey.legs[i].lat(), journey.legs[i].lon())
            if (p.distance < proj.distance - 5) { leg = i; proj = p }
        }
        val current = journey.legs[leg]
        val remaining = if (current is LegView.Walk) (proj.length - proj.along).coerceAtLeast(0.0).toInt() else null
        val rideStop = (current as? LegView.Ride)?.let { r ->
            val start = _state.value.progress?.takeIf { it.leg == leg }?.rideStop ?: 0
            var best = start
            var bestD = Double.MAX_VALUE
            for (k in start until r.stops.size) {
                val d = distanceMeters(loc.latitude, loc.longitude, r.stops[k].lat, r.stops[k].lon)
                if (d < bestD) { bestD = d; best = k }
            }
            best
        }
        _state.update { if (it.active === journey) it.copy(progress = NavProgress(leg, remaining, rideStop)) else it }

        // Fuori strada: a piedi e lontani dal percorso per due posizioni di fila.
        val tolerance = maxOf(50.0, accuracy * 1.5)
        offRouteFixes = when {
            accuracy > 50 -> offRouteFixes // troppo imprecisa per dire che si è fuori strada
            current is LegView.Walk && proj.distance > tolerance -> offRouteFixes + 1
            else -> 0
        }
        // Mezzo perso: è partito (orario live se c'è) da più di un minuto e mezzo e si è ancora a piedi
        // o fermi alla palina di salita.
        val ride = when {
            current is LegView.Ride && proj.along < 60 -> current
            current is LegView.Walk -> journey.legs.getOrNull(leg + 1) as? LegView.Ride
            else -> null
        }
        val missed = ride != null && now > (_state.value.live["${ride.tripId}|${ride.from.id}"] ?: ride.departure) + 90
        val reason = when {
            offRouteFixes >= 2 -> "sei fuori dal percorso"
            missed -> "il ${ride!!.label} è già passato"
            else -> return
        }
        if (rerouting || now - lastRerouteAt < 45) return
        reroute(journey, loc, reason)
    }

    private fun reroute(journey: JourneyView, loc: Location, reason: String) {
        rerouting = true
        lastRerouteAt = System.currentTimeMillis() / 1000
        scope.launch {
            try {
                val origin = Place("La tua posizione", null, loc.latitude, loc.longitude, PlaceKind.MY_LOCATION)
                val best = (compute(origin, journey.destination) as? PlanStatus.Ready)?.journeys?.firstOrNull() ?: return@launch
                _state.update {
                    if (it.active === journey) {
                        it.copy(
                            active = best, live = emptyMap(), progress = null,
                            reroute = RerouteNotice(System.currentTimeMillis() / 1000, reason),
                        )
                    } else it
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Ricalcolo del percorso", e)
            } finally {
                offRouteFixes = 0
                rerouting = false
            }
        }
    }

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
        val trips = rides.mapTo(HashSet()) { it.tripId }
        val vehicleAt = runCatching { repo.vehiclePositions() }.getOrNull()?.items.orEmpty()
            .filter { it.tripId in trips }
            .mapNotNull { v -> v.currentStopName?.let { v.tripId!! to it } }
            .toMap()
        _state.update { if (it.active === journey) it.copy(live = live, vehicleAt = vehicleAt) else it }
        return snapshot.feedTimestamp
    }

    // ---- Conversione dei percorsi per la UI ----

    private fun toView(j: PlannedJourney, origin: Place, dest: Place): JourneyView {
        val gtfs = GtfsStaticDb.shared(app)
        val graph = checkNotNull(RoutingEngine.walkGraph())
        val walk = checkNotNull(RoutingEngine.walkData())
        val edges = j.legs.filterIsInstance<JourneyLeg.Walk>().flatMap { w -> w.steps.map { it.edge } }
        val shapes = edges.distinct().associateWith { walk.shape(it) }
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
                    val stops = leg.stopIds.indices.mapNotNull { k ->
                        gtfs.stop(leg.stopIds[k])?.let { it to (leg.stopTimes.getOrNull(k) ?: leg.departure) }
                    }
                    LegView.Ride(
                        leg.departure, leg.arrival, leg.tripId, leg.routeId,
                        gtfs.route(leg.routeId)?.label ?: leg.routeId, leg.headsign,
                        from, to, leg.stopCount, lat, lon,
                        stops = stops.map { it.first }.ifEmpty { listOf(from, to) },
                        stopTimes = if (stops.isEmpty()) longArrayOf(leg.departure, leg.arrival)
                        else LongArray(stops.size) { stops[it].second },
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

    /**
     * Luogo per un punto scelto sulla mappa: indirizzo (o nome del luogo) dal geocoder di Android;
     * se non risponde, "Punto sulla mappa" con le coordinate.
     */
    suspend fun describePoint(lat: Double, lon: Double): Place {
        val fallback = Place("Punto sulla mappa", "%.5f, %.5f".format(Locale.ITALY, lat, lon), lat, lon, PlaceKind.ADDRESS)
        if (!Geocoder.isPresent()) return fallback
        val address = withTimeoutOrNull(6_000) {
            suspendCancellableCoroutine<List<Address>> { cont ->
                Geocoder(app, Locale.ITALY).getFromLocation(
                    lat, lon, 1,
                    object : Geocoder.GeocodeListener {
                        override fun onGeocode(addresses: MutableList<Address>) { if (cont.isActive) cont.resume(addresses) }
                        override fun onError(errorMessage: String?) { if (cont.isActive) cont.resume(emptyList()) }
                    },
                )
            }
        }?.firstOrNull() ?: return fallback
        // Le coordinate restano quelle toccate (l'indirizzo è solo il nome da mostrare).
        return placeOf(address)?.copy(lat = lat, lon = lon) ?: fallback
    }

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

    companion object {
        private const val TAG = "BusTrack"

        @Volatile private var instance: NavigationModel? = null

        /**
         * Modello unico per tutta l'app: lo usano le schermate e il servizio della navigazione
         * (notifica e posizione anche ad app chiusa).
         */
        fun get(app: Application): NavigationModel = instance ?: synchronized(this) {
            instance ?: NavigationModel(
                app,
                CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Main.immediate),
                TransitRepository(app),
            ).also { instance = it }
        }
    }
}
