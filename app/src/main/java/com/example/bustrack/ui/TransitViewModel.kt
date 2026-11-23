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

/** Popup di scelta della direzione, prima di aprire la linea. */
data class DirectionChoice(val route: Route, val patterns: List<RoutePattern>)

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
    val selected: Stop? = null,
    val routes: List<Route> = emptyList(),
    val arrivals: Load<StopArrivals> = Load.Idle,
)

class TransitViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = TransitRepository(app)

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

    init {
        // Ogni modifica ai preferiti ricarica nomi e percorsi dal database statico.
        viewModelScope.launch {
            favoritesStore.items.collect { items -> _favorites.value = resolveFavorites(items) }
        }
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
        if (line.isBlank()) { _mapStopIds.value = null; _mapLineShapes.value = emptyList(); return }
        mapFilterJob = viewModelScope.launch {
            _mapStopIds.value = runCatching { repo.stopIdsForLine(line) }.getOrDefault(emptySet())
            _mapLineShapes.value = runCatching { repo.routeShapes(line) }.getOrDefault(emptyList())
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

    fun dismissDirectionChoice() = _stops.update { it.copy(directionChoice = null) }

    fun openLine(route: Route, patterns: List<RoutePattern>, pattern: RoutePattern) {
        _stops.update { it.copy(directionChoice = null, line = LineState(route, patterns, pattern)) }
        startLineLoop(manual = false) {
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
            while (isActive) {
                val result = tracked(REFRESH_LINE, first) {
                    load {
                        repo.vehiclePositions().items.filter {
                            it.routeId == route.id && (it.directionId == null || it.directionId == pattern.directionId)
                        }
                    }
                }
                first = false
                _stops.update { s ->
                    val current = s.line ?: return@update s
                    if (result is Load.Failed && current.vehicles is Load.Ready) s
                    else s.copy(line = current.copy(vehicles = result))
                }
                delay(30_000)
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
        loadTimetable(TimetableState(line.route, line.pattern, java.time.LocalDate.now(ROME)))
    }

    fun setTimetableDate(date: java.time.LocalDate) {
        _stops.value.timetable?.let { loadTimetable(it.copy(date = date, departures = Load.Loading)) }
    }

    fun closeTimetable() {
        timetableJob?.cancel()
        _stops.update { it.copy(timetable = null) }
    }

    private fun loadTimetable(state: TimetableState) {
        timetableJob?.cancel()
        _stops.update { it.copy(timetable = state) }
        timetableJob = viewModelScope.launch {
            val result = load { repo.departures(state.pattern, state.date) }
            _stops.update { s -> if (s.timetable?.date == state.date) s.copy(timetable = s.timetable.copy(departures = result)) else s }
        }
    }

    fun closeLine() {
        closeTimetable()
        lineJob?.cancel()
        _stops.update { it.copy(line = null) }
    }

    fun selectStop(stop: Stop?) {
        arrivalsJob?.cancel()
        _stops.update { it.copy(selected = stop, openedFromMap = false, routes = emptyList(), arrivals = Load.Idle) }
        if (stop == null) return
        startArrivalsLoop(stop, manual = false) {
            val routes = runCatching { repo.routesForStop(stop.id) }.getOrDefault(emptyList())
            _stops.update { it.copy(routes = routes, arrivals = Load.Loading) }
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
            // I feed RT di Roma si aggiornano ogni ~30 s.
            // Se il live non risponde, arrivalsAt restituisce comunque gli orari programmati.
            while (isActive) {
                val result = tracked(REFRESH_ARRIVALS, first) { load { repo.arrivalsAt(stop.id) } }
                first = false
                _stops.update { s ->
                    // Se un refresh fallisce, mantieni gli ultimi arrivi noti.
                    if (result is Load.Failed && s.arrivals is Load.Ready) s else s.copy(arrivals = result)
                }
                delay(30_000)
            }
        }
    }

    /** [manual] = premuto il tasto aggiorna: mostra la rotellina. */
    fun refreshVehicles(manual: Boolean = false) = viewModelScope.launch {
        val previous = _vehicles.value
        if (previous !is Load.Ready) _vehicles.value = Load.Loading
        val result = tracked(REFRESH_VEHICLES, manual) {
            load { repo.vehiclePositions().items.sortedWith(compareBy({ it.routeLabel.length }, { it.routeLabel })) }
        }
        // Un refresh fallito non cancella le ultime posizioni note.
        _vehicles.value = if (result is Load.Failed && previous is Load.Ready) previous else result
    }

    fun refreshAlerts(manual: Boolean = false) = viewModelScope.launch {
        val previous = _alerts.value
        if (previous !is Load.Ready) _alerts.value = Load.Loading
        val result = tracked(REFRESH_ALERTS, manual) {
            load {
                val now = System.currentTimeMillis() / 1000
                repo.serviceAlerts().items.filter { it.isActiveAt(now) }
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
        private val ROME: java.time.ZoneId = java.time.ZoneId.of("Europe/Rome")
        const val REFRESH_ARRIVALS = "arrivals"
        const val REFRESH_LINE = "line"
        const val REFRESH_VEHICLES = "vehicles"
        const val REFRESH_ALERTS = "alerts"
    }

    private suspend fun <T> load(block: suspend () -> T): Load<T> = try {
        Load.Ready(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Load.Failed(e.message ?: e.javaClass.simpleName)
    }
}
