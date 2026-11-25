package com.example.bustrack.ui

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.bustrack.favorites.Favorite
import com.example.bustrack.favorites.FavoritesStore
import com.example.bustrack.gtfs.Route
import com.example.bustrack.gtfs.RoutePattern
import com.example.bustrack.gtfs.ServiceAlert
import com.example.bustrack.gtfs.Stop
import com.example.bustrack.gtfs.StopArrivals
import com.example.bustrack.gtfs.TransitRepository
import com.example.bustrack.gtfs.VehiclePosition
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

sealed interface Load<out T> {
    data object Idle : Load<Nothing>
    data object Loading : Load<Nothing>
    data class Ready<T>(
        val value: T,
        /** Ora del download (orologio di sistema), da mostrare all'utente. */
        val fetchedAt: Long = System.currentTimeMillis(),
        /** Stesso istante su orologio monotono: per calcolare l'età dei dati senza salti d'ora. */
        val fetchedAtElapsed: Long = SystemClock.elapsedRealtime(),
        /** Timestamp del feed live della centrale (epoch secondi), se il dato viene da un feed. */
        val feedTimestamp: Long? = null,
    ) : Load<T>
    data class Failed(val message: String) : Load<Nothing>
}

/** Linea aperta dalla ricerca: fermate della direzione scelta e mezzi live su di essa. */
data class LineState(
    val route: Route,
    val patterns: List<RoutePattern>,
    val pattern: RoutePattern,
    val stops: List<Stop> = emptyList(),
    /** Mezzi della linea in questa direzione (null = non ancora caricati). */
    val vehicles: Load<List<VehiclePosition>> = Load.Idle,
    /** Identifica questa apertura: riaprire la linea riparte da capo (scorrimento compreso). */
    val openId: Long = SystemClock.elapsedRealtimeNanos(),
)

/** Orari di partenza dal capolinea per la linea aperta. */
data class TimetableState(
    val route: Route,
    val pattern: RoutePattern,
    val date: java.time.LocalDate,
    val departures: Load<List<com.example.bustrack.gtfs.Departure>> = Load.Loading,
)

/** Linea preferita risolta coi dati statici, per la home. */
data class FavoriteLine(val route: Route, val pattern: RoutePattern)

/** Preferiti pronti da mostrare nella schermata iniziale. */
data class FavoritesState(
    val lines: List<FavoriteLine> = emptyList(),
    val stops: List<Stop> = emptyList(),
    val loaded: Boolean = false,
)

/** Chiave di linea e direzione per [TransitViewModel.termini]. */
internal fun terminusKey(routeId: String, directionId: Int) = "$routeId:$directionId"

/** Fermata vicina all'utente, con la distanza e le linee che ci passano. */
data class NearbyStop(val stop: Stop, val meters: Int, val routes: List<Route>)

/** Fermate vicine nella schermata iniziale. */
sealed interface NearbyState {
    data object Idle : NearbyState
    /** Permesso di posizione non concesso. */
    data object NoPermission : NearbyState
    /** Localizzazione del telefono spenta. */
    data object LocationOff : NearbyState
    data object Locating : NearbyState
    /** Nessuna posizione ricevuta in tempo. */
    data object NoFix : NearbyState
    data class Ready(
        val stops: List<NearbyStop>,
        /** Raggio usato per la ricerca, in metri (vedi impostazioni). */
        val radiusM: Int,
        /** Precisione della posizione in metri (null se sconosciuta). */
        val accuracy: Int?,
        val fetchedAt: Long = System.currentTimeMillis(),
        val fetchedAtElapsed: Long = SystemClock.elapsedRealtime(),
    ) : NearbyState
}

/** Popup di scelta della direzione, prima di aprire la linea. */
data class DirectionChoice(
    val route: Route,
    /** Direzioni proposte nel popup. */
    val patterns: List<RoutePattern>,
    /** Tutte le direzioni della linea (per poter poi passare all'altra); di norma coincidono con [patterns]. */
    val allPatterns: List<RoutePattern> = patterns,
    /** Fermata da cui è stata toccata la linea: indietro dalla linea ritorna lì. */
    val fromStop: Stop? = null,
)

data class StopsState(
    val query: String = "",
    val routeResults: List<Route> = emptyList(),
    val results: List<Stop> = emptyList(),
    val directionChoice: DirectionChoice? = null,
    val line: LineState? = null,
    /** Orari dal capolinea, aperti sopra la schermata della linea. */
    val timetable: TimetableState? = null,
    /** La fermata è stata aperta dalla mappa: indietro torna alla mappa, non alla Home. */
    val openedFromMap: Boolean = false,
    /** La linea è stata aperta dalla mappa: sotto non c'è la Home e indietro torna alla mappa. */
    val lineFromMap: Boolean = false,
    val selected: Stop? = null,
    /** Fermata da cui è stata aperta la linea in corso: premendo indietro dalla linea si ritorna lì. */
    val returnStop: Stop? = null,
    val returnStopFromMap: Boolean = false,
    val routes: List<Route> = emptyList(),
    val arrivals: Load<StopArrivals> = Load.Idle,
)

class TransitViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = TransitRepository(app)

    /** Scheda Nav: ricerca dei luoghi, percorsi e navigazione in corso. */
    val navigation = NavigationModel.get(app)

    private val _stops = MutableStateFlow(StopsState())
    val stops: StateFlow<StopsState> = _stops

    private val _vehicles = MutableStateFlow<Load<List<VehiclePosition>>>(Load.Idle)
    val vehicles: StateFlow<Load<List<VehiclePosition>>> = _vehicles

    private val _alerts = MutableStateFlow<Load<List<ServiceAlert>>>(Load.Idle)
    val alerts: StateFlow<Load<List<ServiceAlert>>> = _alerts

    /** Schermate con un aggiornamento manuale in corso (chiavi REFRESH_*), per la rotellina sul tasto. */
    private val _refreshing = MutableStateFlow<Set<String>>(emptySet())
    val refreshing: StateFlow<Set<String>> = _refreshing

    private val favoritesStore = FavoritesStore(app)
    /** Preferiti grezzi (id), usati per lo stato della stella. */
    val favoriteItems: StateFlow<List<Favorite>> = favoritesStore.items

    private val _favorites = MutableStateFlow(FavoritesState())
    val favorites: StateFlow<FavoritesState> = _favorites

    private val settings = com.example.bustrack.settings.AppSettings.get(app)

    init {
        // Ogni modifica ai preferiti ricarica nomi e percorsi dal database statico.
        viewModelScope.launch {
            favoritesStore.items.collect { items -> _favorites.value = resolveFavorites(items) }
        }
        // Cambiando il raggio nelle impostazioni si rifà la ricerca delle fermate vicine.
        // drop(1): il primo valore arriva subito, a costruzione non ancora finita; contano i cambi.
        viewModelScope.launch {
            settings.nearbyRadiusM.drop(1).collect { radius ->
                val current = _nearby.value
                if (current is NearbyState.Ready && current.radiusM != radius) refreshNearby(manual = true)
            }
        }
        // Rete tornata: i dati live si ricaricano subito, senza aspettare il ciclo successivo.
        com.example.bustrack.settings.Connectivity.start(app)
        viewModelScope.launch {
            var wasOnline = com.example.bustrack.settings.Connectivity.online.value
            com.example.bustrack.settings.Connectivity.online.collect { online ->
                if (online && !wasOnline) onReconnected()
                wasOnline = online
            }
        }
    }

    private fun onReconnected() {
        if (_vehicles.value !is Load.Idle) refreshVehicles()
        if (_alerts.value !is Load.Idle) refreshAlerts()
        _stops.value.selected?.let { startArrivalsLoop(it, manual = false) }
        if (_stops.value.line != null) startLineLoop(manual = false)
    }

    private suspend fun resolveFavorites(items: List<Favorite>): FavoritesState {
        val lines = items.filterIsInstance<Favorite.LineFav>().mapNotNull { fav ->
            runCatching {
                val route = repo.route(fav.routeId) ?: return@runCatching null
                val pattern = repo.routePatterns(fav.routeId).firstOrNull { it.directionId == fav.directionId }
                    ?: return@runCatching null
                FavoriteLine(route, pattern)
            }.getOrNull()
        }
        val stopIds = items.filterIsInstance<Favorite.StopFav>().map { it.stopId }
        // Elementi spariti da un nuovo GTFS statico vengono semplicemente saltati.
        val stops = runCatching { repo.stopsInOrder(stopIds) }.getOrDefault(emptyList())
        return FavoritesState(lines, stops, loaded = true)
    }

    fun toggleFavoriteStop(stop: Stop) {
        favoritesStore.toggle(Favorite.StopFav(stop.id))
    }

    fun toggleFavoriteLine(route: Route, pattern: RoutePattern) {
        favoritesStore.toggle(Favorite.LineFav(route.id, pattern.directionId))
    }

    /** Apre una linea preferita direttamente nella direzione salvata. */
    fun openFavoriteLine(fav: FavoriteLine) {
        viewModelScope.launch {
            val patterns = runCatching { repo.routePatterns(fav.route.id) }.getOrDefault(listOf(fav.pattern))
            openLine(fav.route, patterns, patterns.firstOrNull { it.directionId == fav.pattern.directionId } ?: fav.pattern)
        }
    }

    // --- Fermate vicine (schermata iniziale) ---
    private val _nearby = MutableStateFlow<NearbyState>(NearbyState.Idle)
    val nearby: StateFlow<NearbyState> = _nearby
    private var nearbyJob: Job? = null

    /**
     * Cerca le fermate entro 1 km dalla posizione attuale. Senza [manual] non fa nulla se
     * l'ultima ricerca ha meno di 2 minuti (si richiama a ogni ritorno sulla vista "Vicine").
     */
    fun refreshNearby(manual: Boolean = false) {
        val current = _nearby.value
        if (!manual && current is NearbyState.Ready &&
            SystemClock.elapsedRealtime() - current.fetchedAtElapsed < 120_000
        ) return
        if (!manual && nearbyJob?.isActive == true) return
        val context = getApplication<Application>()
        nearbyJob?.cancel()
        nearbyJob = viewModelScope.launch {
            when {
                !context.hasLocationPermission() -> { _nearby.value = NearbyState.NoPermission; return@launch }
                !context.isLocationEnabled() -> { _nearby.value = NearbyState.LocationOff; return@launch }
            }
            // Durante un aggiornamento resta visibile l'elenco precedente (con la rotellina sul tasto).
            if (_nearby.value !is NearbyState.Ready) _nearby.value = NearbyState.Locating
            tracked(REFRESH_NEARBY, manual) {
                val location = context.currentLocation()
                if (location == null) {
                    if (_nearby.value !is NearbyState.Ready) _nearby.value = NearbyState.NoFix
                    return@tracked
                }
                val all = runCatching { repo.allStops() }.getOrDefault(emptyList())
                val radius = settings.nearbyRadiusM.value
                val near = com.example.bustrack.gtfs.nearestStops(all, location.latitude, location.longitude, maxMeters = radius.toDouble())
                    .map { (stop, meters) ->
                        NearbyStop(stop, meters.toInt(), runCatching { repo.routesForStop(stop.id) }.getOrDefault(emptyList()))
                    }
                _nearby.value = NearbyState.Ready(near, radius, location.takeIf { it.hasAccuracy() }?.accuracy?.toInt())
            }
        }
    }

    // --- Livello "Fermate" della mappa ---
    private val _mapStops = MutableStateFlow<List<Stop>>(emptyList())
    /** Tutte le fermate, caricate la prima volta che si apre il livello fermate. */
    val mapStops: StateFlow<List<Stop>> = _mapStops

    private val _mapStopIds = MutableStateFlow<Set<String>?>(null)
    /** Fermate della linea filtrata (null = nessun filtro, tutte). */
    val mapStopIds: StateFlow<Set<String>?> = _mapStopIds

    private val _mapLineShapes = MutableStateFlow<List<com.example.bustrack.gtfs.RouteShape>>(emptyList())
    /** Tracciati della linea filtrata, uno per direzione (vuoto se nessuna linea corrisponde). */
    val mapLineShapes: StateFlow<List<com.example.bustrack.gtfs.RouteShape>> = _mapLineShapes

    private val _mapLinePatterns = MutableStateFlow<List<RoutePattern>>(emptyList())
    /** Direzioni della linea filtrata sulla mappa (vuoto se nessuna linea corrisponde). */
    val mapLinePatterns: StateFlow<List<RoutePattern>> = _mapLinePatterns

    private var mapStopsLoading = false
    private var mapFilterJob: Job? = null

    fun ensureMapStops() {
        if (_mapStops.value.isNotEmpty() || mapStopsLoading) return
        mapStopsLoading = true
        viewModelScope.launch {
            _mapStops.value = runCatching { repo.allStops() }.getOrDefault(emptyList())
            mapStopsLoading = false
        }
    }

    /**
     * Filtro per numero di linea sulla mappa (vuoto = nessun filtro): aggiorna le fermate della
     * linea e il suo tracciato, che si vedono in entrambi i livelli (bus e fermate).
     */
    fun setMapLineFilter(line: String) {
        mapFilterJob?.cancel()
        if (line.isBlank()) {
            _mapStopIds.value = null; _mapLineShapes.value = emptyList(); _mapLinePatterns.value = emptyList(); return
        }
        mapFilterJob = viewModelScope.launch {
            _mapStopIds.value = runCatching { repo.stopIdsForLine(line) }.getOrDefault(emptySet())
            _mapLineShapes.value = runCatching { repo.routeShapes(line) }.getOrDefault(emptyList())
            val route = runCatching { repo.searchRoutes(line) }.getOrDefault(emptyList())
                .firstOrNull { it.label.equals(line.trim(), ignoreCase = true) }
            _mapLinePatterns.value = route?.let { runCatching { repo.routePatterns(it.id) }.getOrNull() }.orEmpty()
        }
    }

    suspend fun routesForStop(stop: Stop): List<Route> = repo.routesForStop(stop.id)

    private var searchJob: Job? = null
    private var arrivalsJob: Job? = null
    private var lineJob: Job? = null

    fun onQueryChange(query: String) {
        _stops.update { it.copy(query = query) }
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(250)
            val routes = runCatching { repo.searchRoutes(query) }.getOrDefault(emptyList())
            val results = runCatching { repo.searchStops(query) }.getOrDefault(emptyList())
            _stops.update { it.copy(routeResults = routes, results = results) }
        }
    }

    /** Tocco su una linea nei risultati: popup direzione, o apertura diretta se ne ha una sola. */
    fun onRouteClick(route: Route) {
        viewModelScope.launch {
            val patterns = runCatching { repo.routePatterns(route.id) }.getOrDefault(emptyList())
            when (patterns.size) {
                0 -> Unit
                1 -> openLine(route, patterns, patterns.single())
                else -> _stops.update { it.copy(directionChoice = DirectionChoice(route, patterns)) }
            }
        }
    }

    /**
     * Tocco su una linea dalla pagina di una fermata: apre la linea con le sue fermate. Se alla fermata
     * passano mezzi in entrambe le direzioni chiede quale, altrimenti apre direttamente l'unica.
     */
    fun onStopRouteClick(routeId: String?) {
        val stop = _stops.value.selected ?: return
        if (routeId == null) return
        viewModelScope.launch {
            val route = runCatching { repo.route(routeId) }.getOrNull() ?: return@launch
            val all = runCatching { repo.routePatterns(routeId) }.getOrDefault(emptyList())
            val here = all.filter { stop.id in it.stopIds }.ifEmpty { all }
            when (here.size) {
                0 -> Unit
                1 -> openLine(route, all, here.single(), fromStop = stop)
                else -> _stops.update { it.copy(directionChoice = DirectionChoice(route, here, all, stop)) }
            }
        }
    }

    fun dismissDirectionChoice() = _stops.update {
        it.copy(directionChoice = null, lineFromMap = it.lineFromMap && it.line != null)
    }

    /**
     * Dalla mappa: apre l'elenco delle fermate della linea [label] nella scheda Home. Con una
     * direzione nota (quella evidenziata sulla mappa o quella del mezzo toccato) la apre subito,
     * altrimenti chiede quale (il popup compare sopra la mappa). Il passaggio alla scheda Home lo fa
     * la UI quando la linea è aperta (vedi BusTrackApp): annullando il popup si resta sulla mappa.
     */
    fun openLineFromMap(label: String, routeId: String?, directionId: Int?) {
        viewModelScope.launch {
            val route = routeId?.let { runCatching { repo.route(it) }.getOrNull() }
                ?: runCatching { repo.searchRoutes(label) }.getOrDefault(emptyList())
                    .firstOrNull { it.label.equals(label.trim(), ignoreCase = true) }
                ?: return@launch
            val patterns = runCatching { repo.routePatterns(route.id) }.getOrDefault(emptyList())
            if (patterns.isEmpty()) return@launch
            goToStopsHome()
            _stops.update { it.copy(lineFromMap = true) }
            val pattern = patterns.firstOrNull { it.directionId == directionId } ?: patterns.singleOrNull()
            if (pattern != null) openLine(route, patterns, pattern)
            else _stops.update { it.copy(directionChoice = DirectionChoice(route, patterns)) }
        }
    }

    fun openLine(route: Route, patterns: List<RoutePattern>, pattern: RoutePattern, fromStop: Stop? = null) {
        if (fromStop != null) arrivalsJob?.cancel()
        _stops.update {
            it.copy(
                directionChoice = null,
                line = LineState(route, patterns, pattern),
                // La linea si apre sopra la fermata: la ricordo per tornarci con "indietro".
                selected = if (fromStop != null) null else it.selected,
                returnStop = fromStop ?: it.returnStop,
                returnStopFromMap = if (fromStop != null) it.openedFromMap else it.returnStopFromMap,
                openedFromMap = if (fromStop != null) false else it.openedFromMap,
            )
        }
        startLineLoop(manual = false) {
            // Prima l'animazione di apertura della pagina, poi il caricamento (non la rallenta).
            delay(PAGE_OPEN_MS)
            val stops = runCatching { repo.stopsInOrder(pattern.stopIds) }.getOrDefault(emptyList())
            _stops.update { s -> s.copy(line = s.line?.copy(stops = stops, vehicles = Load.Loading)) }
        }
    }

    /** Aggiornamento manuale dei mezzi live sulla linea aperta. */
    fun refreshLine() {
        if (_stops.value.line != null) startLineLoop(manual = true)
    }

    private fun startLineLoop(manual: Boolean, before: suspend () -> Unit = {}) {
        val line = _stops.value.line ?: return
        val route = line.route
        val pattern = line.pattern
        lineJob?.cancel()
        lineJob = viewModelScope.launch {
            before()
            var first = manual
            var feedTs: Long? = null
            while (isActive) {
                val result = tracked(REFRESH_LINE, first) {
                    loadFeed({ repo.vehiclePositions() }) { items ->
                        items.filter {
                            it.routeId == route.id && (it.directionId == null || it.directionId == pattern.directionId)
                        }
                    }
                }
                first = false
                (result as? Load.Ready)?.feedTimestamp?.let { feedTs = it }
                _stops.update { s ->
                    val current = s.line ?: return@update s
                    if (result is Load.Failed && current.vehicles is Load.Ready) s
                    else s.copy(line = current.copy(vehicles = result))
                }
                delay(liveDelayMs(feedTs))
            }
        }
    }

    /** Passa all'altra direzione della linea aperta (o mostra il popup se sono più di due). */
    fun switchDirection() {
        val line = _stops.value.line ?: return
        val others = line.patterns.filter { it != line.pattern }
        when (others.size) {
            0 -> Unit
            1 -> openLine(line.route, line.patterns, others.single())
            else -> _stops.update { it.copy(directionChoice = DirectionChoice(line.route, line.patterns)) }
        }
    }

    /** Un passo indietro dentro la scheda Fermate: fermata → orari → linea → home. */
    fun stopsBack() {
        val s = _stops.value
        when {
            s.selected != null -> selectStop(null)
            s.timetable != null -> closeTimetable()
            s.line != null -> closeLine()
        }
    }

    /** Torna alla schermata iniziale di Fermate: chiude fermata, linea e popup e svuota la ricerca. */
    fun goToStopsHome() {
        searchJob?.cancel()
        arrivalsJob?.cancel()
        lineJob?.cancel()
        timetableJob?.cancel()
        _stops.value = StopsState()
    }

    private var timetableJob: Job? = null

    /** Apre gli orari dal capolinea della linea aperta, per oggi. */
    fun openTimetable() {
        val line = _stops.value.line ?: return
        loadTimetable(TimetableState(line.route, line.pattern, java.time.LocalDate.now(ROME)), afterOpen = true)
    }

    fun setTimetableDate(date: java.time.LocalDate) {
        _stops.value.timetable?.let { loadTimetable(it.copy(date = date, departures = Load.Loading)) }
    }

    fun closeTimetable() {
        timetableJob?.cancel()
        _stops.update { it.copy(timetable = null) }
    }

    private fun loadTimetable(state: TimetableState, afterOpen: Boolean = false) {
        timetableJob?.cancel()
        _stops.update { it.copy(timetable = state) }
        timetableJob = viewModelScope.launch {
            if (afterOpen) delay(PAGE_OPEN_MS)
            val result = load { repo.departures(state.pattern, state.date) }
            _stops.update { s -> if (s.timetable?.date == state.date) s.copy(timetable = s.timetable.copy(departures = result)) else s }
        }
    }

    fun closeLine() {
        closeTimetable()
        lineJob?.cancel()
        val back = _stops.value.returnStop
        val fromMap = _stops.value.returnStopFromMap
        // Chiudendo la linea aperta dalla mappa (senza una fermata a cui tornare) si torna alla Home normale.
        _stops.update {
            it.copy(line = null, returnStop = null, returnStopFromMap = false, lineFromMap = it.lineFromMap && back != null)
        }
        if (back != null) {
            selectStop(back)
            if (fromMap) _stops.update { it.copy(openedFromMap = true) }
        }
    }

    fun selectStop(stop: Stop?) {
        arrivalsJob?.cancel()
        _stops.update { it.copy(selected = stop, openedFromMap = false, routes = emptyList(), arrivals = Load.Idle) }
        if (stop == null) return
        startArrivalsLoop(stop, manual = false) {
            // Prima l'animazione di apertura della pagina, poi il caricamento (non la rallenta).
            delay(PAGE_OPEN_MS)
            val routes = runCatching { repo.routesForStop(stop.id) }.getOrDefault(emptyList())
            _stops.update { it.copy(routes = routes, arrivals = Load.Loading) }
        }
    }

    /** Riapre una linea dalle ricerche recenti: come toccarla nei risultati (popup della direzione). */
    fun openRouteById(routeId: String) {
        viewModelScope.launch {
            val route = runCatching { repo.route(routeId) }.getOrNull() ?: return@launch
            onRouteClick(route)
        }
    }

    /** Apre gli arrivi di una fermata dalla Home (es. toccando il widget). */
    fun openStopById(stopId: String) {
        viewModelScope.launch {
            val stop = runCatching { repo.stopsInOrder(listOf(stopId)) }.getOrNull()?.firstOrNull() ?: return@launch
            goToStopsHome()
            selectStop(stop)
        }
    }

    /** Apre la fermata dalla mappa: con indietro si torna alla mappa (con il suo tracciato). */
    fun openStopFromMap(stop: Stop) {
        goToStopsHome()
        selectStop(stop)
        _stops.update { it.copy(openedFromMap = true) }
    }

    /** Lasciando la fermata aperta dalla mappa la chiude, così smette di aggiornare gli arrivi. */
    fun closeStopIfFromMap() {
        if (_stops.value.openedFromMap) selectStop(null)
    }

    /** Aggiornamento manuale degli arrivi alla fermata aperta (riparte anche il timer dei 30 s). */
    fun refreshArrivals() {
        _stops.value.selected?.let { startArrivalsLoop(it, manual = true) }
    }

    private fun startArrivalsLoop(stop: Stop, manual: Boolean, before: suspend () -> Unit = {}) {
        arrivalsJob?.cancel()
        arrivalsJob = viewModelScope.launch {
            before()
            var first = manual
            var feedTs: Long? = null
            // Si riscarica appena la centrale pubblica il nuovo feed (vedi FeedTiming).
            // Se il live non risponde, arrivalsAt restituisce comunque gli orari programmati.
            while (isActive) {
                val result = tracked(REFRESH_ARRIVALS, first) { load { repo.arrivalsAt(stop.id) } }
                first = false
                (result as? Load.Ready)?.value?.feedTimestamp?.let { feedTs = it }
                _stops.update { s ->
                    // Se un refresh fallisce, mantieni gli ultimi arrivi noti.
                    if (result is Load.Failed && s.arrivals is Load.Ready) s else s.copy(arrivals = result)
                }
                delay(liveDelayMs(feedTs))
            }
        }
    }

    // --- Capolinea dei mezzi sulla mappa (per la freccia nel pallino) ---
    private val _termini = MutableStateFlow<Map<String, Stop>>(emptyMap())
    /** Capolinea per linea e direzione (chiave [terminusKey]), per i mezzi visti finora. */
    val termini: StateFlow<Map<String, Stop>> = _termini
    /** Linea e direzione senza capolinea utilizzabile (circolari, dati mancanti): non si ricercano. */
    private val terminiMissing = HashSet<String>()

    /** Cerca il capolinea delle coppie linea/direzione nuove: ultima fermata del percorso tipico. */
    private suspend fun loadTermini(vehicles: List<VehiclePosition>) {
        val wanted = vehicles.mapNotNull { v ->
            val route = v.routeId ?: return@mapNotNull null
            val dir = v.directionId ?: return@mapNotNull null
            terminusKey(route, dir) to (route to dir)
        }.toMap().filterKeys { it !in _termini.value && it !in terminiMissing }
        if (wanted.isEmpty()) return
        val found = HashMap(_termini.value)
        val patternsByRoute = HashMap<String, List<RoutePattern>>()
        for ((key, routeAndDir) in wanted) {
            val (route, dir) = routeAndDir
            val patterns = patternsByRoute.getOrPut(route) { runCatching { repo.routePatterns(route) }.getOrDefault(emptyList()) }
            val ids = patterns.firstOrNull { it.directionId == dir }?.stopIds.orEmpty()
            // Linea circolare: il capolinea coincide con la partenza, la freccia non direbbe nulla.
            val last = ids.lastOrNull()?.takeIf { it != ids.first() }
            val stop = last?.let { runCatching { repo.stopsInOrder(listOf(it)) }.getOrNull()?.firstOrNull() }
            if (stop != null) found[key] = stop else terminiMissing += key
        }
        _termini.value = found
    }

    /** [manual] = premuto il tasto aggiorna: mostra la rotellina. */
    fun refreshVehicles(manual: Boolean = false) = viewModelScope.launch {
        val previous = _vehicles.value
        if (previous !is Load.Ready) _vehicles.value = Load.Loading
        val result = tracked(REFRESH_VEHICLES, manual) {
            loadFeed({ repo.vehiclePositions() }) { it.sortedWith(compareBy({ v -> v.routeLabel.length }, { v -> v.routeLabel })) }
        }
        // Un refresh fallito non cancella le ultime posizioni note.
        _vehicles.value = if (result is Load.Failed && previous is Load.Ready) previous else result
        // I capolinea arrivano subito dopo: i pallini si vedono intanto, le frecce poco dopo.
        // Lavoro separato: chi attende questo aggiornamento (il ciclo della mappa) non aspetta anche i capolinea.
        if (result is Load.Ready) viewModelScope.launch { loadTermini(result.value) }
    }

    /**
     * Avvisi per le pagine di linea e fermata: li carica se mancano o hanno più di 5 minuti
     * (la scheda Avvisi, quando è aperta, li tiene aggiornati da sé).
     */
    fun ensureAlerts() {
        val current = _alerts.value
        val stale = current !is Load.Ready || SystemClock.elapsedRealtime() - current.fetchedAtElapsed > 300_000
        if (stale && current !is Load.Loading) refreshAlerts()
    }

    fun refreshAlerts(manual: Boolean = false) = viewModelScope.launch {
        val previous = _alerts.value
        if (previous !is Load.Ready) _alerts.value = Load.Loading
        val result = tracked(REFRESH_ALERTS, manual) {
            loadFeed({ repo.serviceAlerts() }) { items ->
                val now = System.currentTimeMillis() / 1000
                items.filter { it.isActiveAt(now) }
            }
        }
        _alerts.value = if (result is Load.Failed && previous is Load.Ready) previous else result
    }

    private suspend fun <T> tracked(key: String, enabled: Boolean, block: suspend () -> T): T {
        if (!enabled) return block()
        _refreshing.update { it + key }
        try {
            return block()
        } finally {
            _refreshing.update { it - key }
        }
    }

    companion object {
        /** Durata dell'animazione di apertura di una pagina (PredictiveBackLayer): i dati si caricano dopo. */
        const val PAGE_OPEN_MS = 320L
        /**
         * Attesa del prossimo download di un feed live da ~30 s: appena la centrale pubblica il
         * nuovo feed. Senza timestamp (mai scaricato con successo) si riprova dopo 10 s.
         */
        fun liveDelayMs(feedTs: Long?): Long =
            if (feedTs == null) 10_000 else com.example.bustrack.gtfs.nextFeedDelayMs(feedTs, System.currentTimeMillis())

        private val ROME: java.time.ZoneId = java.time.ZoneId.of("Europe/Rome")
        const val REFRESH_ARRIVALS = "arrivals"
        const val REFRESH_LINE = "line"
        const val REFRESH_VEHICLES = "vehicles"
        const val REFRESH_ALERTS = "alerts"
        const val REFRESH_NEARBY = "nearby"
    }

    /** Come [load], per un feed live: il risultato conserva il timestamp del feed della centrale. */
    private suspend fun <S, T> loadFeed(
        fetch: suspend () -> com.example.bustrack.gtfs.RealtimeSnapshot<S>,
        map: (List<S>) -> T,
    ): Load<T> = try {
        val snapshot = fetch()
        Load.Ready(map(snapshot.items), feedTimestamp = snapshot.feedTimestamp)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Load.Failed(e.message ?: e.javaClass.simpleName)
    }

    private suspend fun <T> load(block: suspend () -> T): Load<T> = try {
        Load.Ready(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Load.Failed(e.message ?: e.javaClass.simpleName)
    }
}
