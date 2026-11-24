package com.example.bustrack.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Button
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.example.bustrack.favorites.Favorite
import androidx.compose.ui.graphics.Color
import com.example.bustrack.ui.theme.CardCorner
import com.example.bustrack.ui.theme.LiveTheme
import androidx.compose.ui.res.painterResource
import com.example.bustrack.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.bustrack.gtfs.Arrival
import com.example.bustrack.gtfs.ServiceAlert
import com.example.bustrack.gtfs.Stop
import com.example.bustrack.gtfs.VehiclePosition
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val ROME = ZoneId.of("Europe/Rome")
internal val HHMM = DateTimeFormatter.ofPattern("HH:mm").withZone(ROME)

/** Mostra una linea (per numero) sulla scheda Mappa, con il suo tracciato (usato dalla schermata della linea). */
internal val LocalShowLineOnMap = staticCompositionLocalOf<(label: String, directionId: Int, headsign: String?) -> Unit> { { _, _, _ -> } }

/** Torna alla scheda precedente (come il gesto indietro tra le schede). */
internal val LocalTabBack = staticCompositionLocalOf<() -> Unit> { {} }

/** Apre la pagina di una fermata nella scheda Home (usato dalla mappa delle fermate). */
internal val LocalOpenStop = staticCompositionLocalOf<(Stop) -> Unit> { {} }

/** Apre l'elenco fermate di una linea nella scheda Home, dalla mappa (numero, route_id e direzione se noti). */
internal val LocalOpenLineFromMap = staticCompositionLocalOf<(label: String, routeId: String?, directionId: Int?) -> Unit> { { _, _, _ -> } }

/**
 * Posizione standard dei pulsanti "indietro": senza margine interno a sinistra, così la freccia
 * parte dallo stesso punto della prima lettera del titolo sotto (nome della fermata, linea, ...).
 */
internal val BackButtonPadding = PaddingValues(start = 0.dp, top = 8.dp, end = 12.dp, bottom = 8.dp)

/** Contenuto di un pulsante "indietro": freccia Material (si specchia con la lingua RTL) e destinazione. */
@Composable
internal fun BackLabel(text: String) {
    Icon(
        Icons.AutoMirrored.Rounded.ArrowBack,
        contentDescription = "Indietro",
        // Il disegno della freccia ha un margine interno: si sposta a sinistra perché la punta
        // combaci con il bordo della prima lettera del titolo.
        modifier = Modifier.size(20.dp).offset(x = (-4).dp),
    )
    Spacer(Modifier.width(4.dp))
    Text(text)
}

@Composable
fun BusTrackApp(vm: TransitViewModel = viewModel()) {
    // Cronologia delle schede visitate: la back gesture torna a quella precedente.
    // Ogni scheda compare una sola volta (riaprirla la sposta in cima), quindi al massimo
    // tre passi indietro prima di uscire dall'app.
    var history by rememberSaveable { mutableStateOf(listOf(0)) }
    // L'ultima scelta "Preferiti / Vicine" della Home resta anche chiudendo e riaprendo l'app.
    val uiPrefs = LocalContext.current.getSharedPreferences("ui", android.content.Context.MODE_PRIVATE)
    val scrollMemory = remember {
        ScrollMemory().apply { homeShowNearby = uiPrefs.getBoolean(PREF_HOME_SHOW_NEARBY, false) }
    }
    LaunchedEffect(scrollMemory) {
        snapshotFlow { scrollMemory.homeShowNearby }.collect {
            uiPrefs.edit().putBoolean(PREF_HOME_SHOW_NEARBY, it).apply()
        }
    }
    val tab = history.last()
    val tabPosition = rememberTabPosition(tab)
    fun openTab(i: Int) {
        if (i != history.last()) history = history.filter { it != i } + i
    }
    // Dalla mappa: apre la pagina di una fermata nella scheda Home. Premendo indietro si torna
    // alla Home e poi alla mappa (la cronologia delle schede la ricorda).
    val openStop = remember(vm) {
        { stop: Stop ->
            // La fermata si apre nella scheda Home ma indietro riporta alla mappa (vedi pageStack).
            vm.openStopFromMap(stop)
            openTab(0)
        }
    }
    // Dalla mappa: apre l'elenco fermate della linea nella scheda Home; indietro torna alla mappa.
    val openLineFromMap = remember(vm) {
        { label: String, routeId: String?, directionId: Int? -> vm.openLineFromMap(label, routeId, directionId) }
    }
    // Si passa alla Home solo a linea aperta: l'eventuale popup della direzione compare sopra la
    // mappa e, se annullato, si resta lì. Ogni nuova apertura della linea ha un openId diverso.
    val stopsState by vm.stops.collectAsStateWithLifecycle()
    val lineFromMapId = stopsState.line?.openId?.takeIf { stopsState.lineFromMap }
    LaunchedEffect(lineFromMapId) { if (lineFromMapId != null) openTab(0) }
    // Dalla linea: apre la scheda Mappa (livello bus) filtrata su quella linea, con inquadratura sul tracciato.
    val showLineOnMap = remember(vm) {
        { label: String, directionId: Int, headsign: String? ->
            scrollMemory.mapLineChoice = MapLineChoice(label, directionId, headsign)
            scrollMemory.vehiclesFilter = label
            scrollMemory.vehiclesShowMap = true
            scrollMemory.mapShowStops = false
            scrollMemory.fittedLineKey = null
            openTab(1)
        }
    }
    // Indietro di una scheda (usato dal gesto e dal pulsante "← Mappa" di una fermata aperta dalla mappa).
    val tabBack = remember(vm) {
        {
            history = history.dropLast(1)
        }
    }
    // Una fermata aperta dalla mappa si chiude solo a scheda cambiata del tutto, non durante lo scorrimento:
    // altrimenti, mentre la scheda esce, si vedrebbe già la Home al posto dei dettagli.
    LaunchedEffect(tab) {
        if (tab != 0) {
            snapshotFlow { tabPosition.value }.first { kotlin.math.abs(it - tab) < 0.001f }
            vm.closeStopIfFromMap()
        }
    }
    // Back predittivo tra le schede: a fine animazione la nuova scheda è già al suo posto (niente secondo scorrimento).
    val gestureScope = rememberCoroutineScope()
    val tabGesture = rememberBackGesture(enabled = history.size > 1) {
        tabBack()
        gestureScope.launch { tabPosition.anim.snapTo(history.last().toFloat()) }
    }
    val barInset = bottomBarInset()
    CompositionLocalProvider(
        LocalScrollMemory provides scrollMemory,
        LocalBottomBarInset provides barInset,
        LocalOpenStop provides openStop,
        LocalOpenLineFromMap provides openLineFromMap,
        LocalShowLineOnMap provides showLineOnMap,
        LocalTabBack provides tabBack,
    ) {
        // Solo la barra di stato in alto: in basso il contenuto arriva fino al bordo
        // e la barra flottante gli sta sopra.
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            contentWindowInsets = WindowInsets.statusBars,
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                // Indietro tra le schede: la scheda precedente si scopre sotto (vedi TabPager).
                // I gestori interni (es. pagine dentro Home) sono composti dopo e hanno la precedenza.
                TabPager(
                    position = tabPosition,
                    count = 3,
                    current = tab,
                    gesture = tabGesture,
                    previewTab = history.getOrNull(history.size - 2),
                ) { t -> TabContent(t, vm) }
                FloatingTabBar(
                    labels = listOf("Home", "Mappa", "Avvisi"),
                    selected = tab,
                    onSelect = { i ->
                        // "Home" riporta sempre alla schermata iniziale (preferiti + ricerca).
                        if (i == 0) vm.goToStopsHome()
                        openTab(i)
                    },
                    position = tabPosition,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }
    }
}

@Composable
private fun TabContent(tab: Int, vm: TransitViewModel) {
    when (tab) {
        // Tutte le schede arrivano fino al bordo dello schermo: la barra flottante sta sopra.
        // Le liste hanno uno spazio finale (LocalBottomBarInset) per portare l'ultima voce sopra la barra.
        0 -> StopsScreen(vm)
        1 -> VehiclesScreen(vm)
        else -> AlertsScreen(vm)
    }
}

/** Pagine della scheda Fermate, dalla più esterna alla più interna. */
private enum class StopsPage { Home, Line, Timetable, Stop }

/** Pila delle pagine aperte, ricavata dallo stato: l'ultima è quella visibile. */
private fun StopsState.pageStack(): List<StopsPage> = if (openedFromMap && selected != null) {
    // Aperta dalla mappa: nessuna pagina "sotto"; indietro è il ritorno alla scheda precedente (la mappa).
    listOf(StopsPage.Stop)
} else buildList {
    // Linea aperta dalla mappa: la Home non sta sotto, indietro dalla linea torna alla mappa.
    if (!lineFromMap) add(StopsPage.Home)
    if (line != null) add(StopsPage.Line)
    if (timetable != null) add(StopsPage.Timetable)
    if (selected != null) add(StopsPage.Stop)
}.ifEmpty { listOf(StopsPage.Home) }

@Composable
private fun StopsScreen(vm: TransitViewModel) {
    val state by vm.stops.collectAsStateWithLifecycle()
    val favorites by vm.favorites.collectAsStateWithLifecycle()
    if (!LocalInBackPreview.current) {
        state.directionChoice?.let { choice ->
            DirectionDialog(
                choice = choice,
                onPick = { vm.openLine(choice.route, choice.allPatterns, it, choice.fromStop) },
                onDismiss = vm::dismissDirectionChoice,
            )
        }
    }
    val stack = state.pageStack()
    PredictiveBackLayer(
        enabled = stack.size > 1,
        onBack = vm::stopsBack,
        previous = { StopsPageContent(stack[stack.size - 2], state, favorites, vm) },
    ) {
        StopsPageContent(stack.last(), state, favorites, vm)
    }
}

@Composable
private fun StopsPageContent(page: StopsPage, state: StopsState, favorites: FavoritesState, vm: TransitViewModel) {
    val selected = state.selected
    if (page == StopsPage.Stop && selected != null) {
        Column(Modifier.fillMaxSize().padding(start = 16.dp, top = 16.dp, end = 16.dp)) {
            val refreshing by vm.refreshing.collectAsStateWithLifecycle()
            Row(verticalAlignment = Alignment.CenterVertically) {
                val tabBack = LocalTabBack.current
                TextButton(
                    onClick = { if (state.openedFromMap) tabBack() else vm.selectStop(null) },
                    contentPadding = BackButtonPadding,
                ) {
                    BackLabel(
                        when {
                            state.openedFromMap -> "Mappa"
                            state.line != null -> "Linea ${state.line.route.label}"
                            else -> "Tutte le fermate"
                        }
                    )
                }
                Spacer(Modifier.weight(1f))
                val favorites by vm.favoriteItems.collectAsStateWithLifecycle()
                FavoriteButton(Favorite.StopFav(selected.id) in favorites) { vm.toggleFavoriteStop(selected) }
                RefreshButton(TransitViewModel.REFRESH_ARRIVALS in refreshing, vm::refreshArrivals)
            }
            Text(selected.name, style = MaterialTheme.typography.headlineSmall)
            Text("Palina ${selected.code ?: selected.id}", style = MaterialTheme.typography.bodyMedium)
            if (state.routes.isNotEmpty()) {
                Text(
                    "Linee: " + state.routes.joinToString(" · ") { it.label },
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            when (val load = state.arrivals) {
                Load.Idle, Load.Loading -> Loading()
                is Load.Failed -> ErrorText(load.message)
                is Load.Ready -> {
                    val result = load.value
                    if (!result.realtimeAvailable) {
                        Text(
                            "Dati live non disponibili: mostro gli orari programmati",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                    }
                    if (result.arrivals.isEmpty()) {
                        Text("Nessun passaggio nelle prossime 2 ore")
                    } else {
                        LazyColumn(contentPadding = PaddingValues(bottom = LocalBottomBarInset.current)) {
                            items(result.arrivals, key = { it.tripId + it.time }) { a ->
                                ArrivalRow(a, onClick = { vm.onStopRouteClick(a.routeId) })
                            }
                        }
                    }
                }
            }
        }
    } else if (page == StopsPage.Timetable && state.timetable != null) {
        TimetableScreen(state.timetable, vm)
    } else if (page == StopsPage.Line && state.line != null) {
        LineScreen(state.line, vm)
    } else {
        Column(Modifier.fillMaxSize().padding(start = 16.dp, top = 12.dp, end = 16.dp)) {
            val memory = LocalScrollMemory.current
            var showNearby by memory::homeShowNearby
            val context = LocalContext.current
            val nearby by vm.nearby.collectAsStateWithLifecycle()
            val refreshing by vm.refreshing.collectAsStateWithLifecycle()
            val permissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions()
            ) { vm.refreshNearby(manual = true) }
            val askPermission = {
                permissionLauncher.launch(
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                )
            }
            // Mostrando le fermate vicine si cerca la posizione (non più di una volta ogni 2 minuti,
            // vedi refreshNearby); il permesso si chiede solo su azione dell'utente.
            val inPreview = LocalInBackPreview.current
            LaunchedEffect(showNearby, inPreview) { if (showNearby && !inPreview) vm.refreshNearby() }
            // Pillola "Preferiti / Vicine": stesso aspetto e comportamento della barra in basso
            // (si tocca o si trascina l'indicatore, e l'elenco sotto scorre insieme a lui).
            val selectHome = { i: Int ->
                showNearby = i == 1
                if (i == 1 && !context.hasLocationPermission()) askPermission()
            }
            val homePosition = rememberTabPosition(if (showNearby) 1 else 0)
            PillSwitch(
                labels = listOf("Preferiti", "Vicine"),
                selected = if (showNearby) 1 else 0,
                onSelect = selectHome,
                position = homePosition,
                screenPadding = 16.dp,
            )
            OutlinedTextField(
                value = state.query,
                onValueChange = vm::onQueryChange,
                label = { Text("Cerca linea, fermata o numero palina") },
                singleLine = true,
                shape = CardCorner,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            )
            if (state.query.isBlank()) {
                SlidingPanes(
                    homePosition,
                    count = 2,
                    gap = 16.dp,
                    modifier = Modifier.fillMaxSize().padding(top = 12.dp),
                ) { pane ->
                    // Riquadro leggero per ciascun elenco: scorrendo si distinguono le due sezioni.
                    // Arriva fino al bordo in basso (la lista scorre sotto la barra), quindi angoli
                    // arrotondati solo in alto.
                    Box(
                        Modifier
                            .fillMaxSize()
                            .clip(HomePanelShape)
                            .background(MaterialTheme.colorScheme.surfaceContainerLow)
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f), HomePanelShape)
                    ) {
                        // Ogni elenco ha il suo scorrimento.
                        LazyColumn(
                            state = memory.list(if (pane == 1) "home-nearby" else "home"),
                            contentPadding = PaddingValues(
                                start = 8.dp,
                                end = 8.dp,
                                top = 4.dp,
                                bottom = LocalBottomBarInset.current,
                            ),
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            if (pane == 1) {
                                nearbySection(
                                    nearby,
                                    refreshing = TransitViewModel.REFRESH_NEARBY in refreshing,
                                    onRefresh = { vm.refreshNearby(manual = true) },
                                    onAskPermission = askPermission,
                                    onOpenSettings = { action ->
                                        context.startActivity(
                                            Intent(action).apply {
                                                if (action == Settings.ACTION_APPLICATION_DETAILS_SETTINGS) {
                                                    data = Uri.fromParts("package", context.packageName, null)
                                                }
                                            }
                                        )
                                    },
                                    onOpenStop = vm::selectStop,
                                )
                            } else {
                                favoritesSection(favorites, vm)
                            }
                        }
                    }
                }
            } else LazyColumn(
                state = memory.list("home-search"),
                contentPadding = PaddingValues(bottom = LocalBottomBarInset.current),
            ) {
                if (state.routeResults.isNotEmpty()) {
                    item { SectionTitle("Linee") }
                    items(state.routeResults, key = { "route-" + it.id }) { route ->
                        AppListItem(
                            leadingContent = { RouteBadge(route.label) },
                            supportingContent = {
                                Text(listOfNotNull(route.longName, route.agencyName).joinToString(" · "))
                            },
                            modifier = Modifier.clickable { vm.onRouteClick(route) },
                        ) { Text("Linea ${route.label}") }
                    }
                    if (state.results.isNotEmpty()) item { SectionTitle("Fermate") }
                }
                items(state.results, key = { it.id }) { stop ->
                    AppListItem(
                        supportingContent = { Text("Palina ${stop.code ?: stop.id}") },
                        modifier = Modifier.clickable { vm.selectStop(stop) },
                    ) { Text(stop.name) }
                }
            }
        }
    }
}

@Composable
private fun ArrivalRow(a: Arrival, onClick: () -> Unit) {
    val now = System.currentTimeMillis() / 1000
    val minutes = ((a.time - now) / 60).coerceAtLeast(0)
    AppListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = { RouteBadge(a.routeLabel, live = a.isRealtime) },
        supportingContent = {
            val status = if (!a.isRealtime) {
                " · programmato"
            } else {
                a.delaySeconds?.let { d ->
                    when {
                        d >= 60 -> " · live, ritardo ${d / 60} min"
                        d <= -60 -> " · live, anticipo ${-d / 60} min"
                        else -> " · live, in orario"
                    }
                } ?: " · live"
            }
            Text(
                HHMM.format(Instant.ofEpochSecond(a.time)) + status,
                color = if (a.isRealtime) LiveTheme.colors.live else Color.Unspecified,
            )
        },
        trailingContent = {
            // Live in grassetto e verde; programmato in grigio.
            Text(
                if (minutes == 0L) "ora" else "$minutes'",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = if (a.isRealtime) FontWeight.Bold else FontWeight.Normal,
                color = if (a.isRealtime) LiveTheme.colors.live else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    ) { Text(a.headsign ?: "—", maxLines = 1, overflow = TextOverflow.Ellipsis) }
}

@Composable
private fun VehiclesScreen(vm: TransitViewModel) {
    val state by vm.vehicles.collectAsStateWithLifecycle()
    // In memoria fuori dalla scheda: tornando su Mezzi si ritrova la stessa vista e lo stesso filtro.
    val memory = LocalScrollMemory.current
    var filter by memory::vehiclesFilter
    var showMap by memory::vehiclesShowMap
    // Livello della mappa: bus (live) oppure fermate (statiche).
    var showStops by memory::mapShowStops
    val mapStops by vm.mapStops.collectAsStateWithLifecycle()
    val stopIds by vm.mapStopIds.collectAsStateWithLifecycle()
    val openStop = LocalOpenStop.current
    LaunchedEffect(showStops) { if (showStops) vm.ensureMapStops() }
    // Il filtro per linea vale per il livello attivo: mezzi della linea, oppure fermate della linea.
    // Con una linea valida si vede anche il suo tracciato, in entrambi i livelli.
    val lineShapes by vm.mapLineShapes.collectAsStateWithLifecycle()
    LaunchedEffect(filter) { vm.setMapLineFilter(filter) }
    // Direzione scelta dalla schermata della linea: vale finché il filtro resta su quella linea.
    val lineChoice = memory.mapLineChoice?.takeIf { it.label.equals(filter.trim(), ignoreCase = true) }
    // Aggiornamento automatico finché la scheda è visibile: ogni 60 s sulla mappa, ogni 30 s
    // sulla lista. Passando da una vista all'altra non si ricarica subito: si aspetta che i dati
    // abbiano raggiunto l'età prevista dal nuovo intervallo.
    val inPreview = LocalInBackPreview.current
    LaunchedEffect(showMap, showStops, inPreview) {
        // Il livello fermate è statico: niente download dei mezzi finché è attivo.
        if (inPreview || showStops) return@LaunchedEffect
        val intervalMs = if (showMap) 60_000L else 30_000L
        while (true) {
            val fetchedAt = (vm.vehicles.value as? Load.Ready)?.fetchedAtElapsed
            val age = fetchedAt?.let { android.os.SystemClock.elapsedRealtime() - it } ?: Long.MAX_VALUE
            if (age >= intervalMs) {
                vm.refreshVehicles().join()
                kotlinx.coroutines.delay(intervalMs)
            } else {
                kotlinx.coroutines.delay(intervalMs - age)
            }
        }
    }
    val vehicles = (state as? Load.Ready)?.value.orEmpty()
    val shown = if (filter.isBlank()) vehicles
    else vehicles.filter { it.routeLabel.equals(filter.trim(), ignoreCase = true) }
    // Le fermate si vedono sempre sulla mappa; l'elenco esiste solo per i bus.
    val mapVisible = showStops || showMap
    Column(Modifier.fillMaxSize()) {
        // Slider a due posizioni: bus (live) / fermate (statiche).
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp)) {
            SegmentedButton(
                selected = !showStops,
                onClick = { showStops = false },
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
            ) { Text("Bus") }
            SegmentedButton(
                selected = showStops,
                onClick = { showStops = true },
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
            ) { Text("Fermate") }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
        ) {
            OutlinedTextField(
                value = filter,
                onValueChange = { filter = it },
                label = { Text("Filtra per linea") },
                singleLine = true,
                shape = CardCorner,
                modifier = Modifier.weight(1f),
            )
            if (!showStops) {
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = { showMap = !showMap }) { Text(if (showMap) "Lista" else "Mappa") }
                val refreshing by vm.refreshing.collectAsStateWithLifecycle()
                RefreshButton(TransitViewModel.REFRESH_VEHICLES in refreshing) { vm.refreshVehicles(manual = true) }
            }
        }
        if (mapVisible) {
            // Riga di stato sopra la mappa. La mappa è sempre presente (anche a feed vuoto) e resta
            // la stessa quando si passa da bus a fermate: cambia solo il livello mostrato.
            val updatedAt = (state as? Load.Ready)?.fetchedAt?.let { HHMM.format(Instant.ofEpochMilli(it)) }
            val total = stopIds?.size ?: mapStops.size
            val direction = lineChoice?.headsign?.let { "Direzione → $it · " }.orEmpty()
            val status = direction + when {
                showStops && mapStops.isEmpty() -> "Carico le fermate…"
                showStops -> "$total fermate" + (if (filter.isBlank()) "" else " della linea ${filter.trim()}") +
                    " · tocca un gruppo per ingrandire"
                state is Load.Failed -> "Posizioni dei mezzi non disponibili"
                state !is Load.Ready -> "Carico i mezzi…"
                vehicles.isEmpty() -> "Nessun mezzo in servizio" + (updatedAt?.let { " · aggiornato alle $it" } ?: "")
                else -> "${shown.size} mezzi" + (updatedAt?.let { " · aggiornato alle $it" } ?: "")
            }
            val openLine = LocalOpenLineFromMap.current
            // Linea valida nel filtro: si può aprire l'elenco delle sue fermate.
            val lineLabel = filter.trim().takeIf { it.isNotEmpty() && (lineShapes.isNotEmpty() || !stopIds.isNullOrEmpty()) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    status,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (!showStops && state is Load.Failed) MaterialTheme.colorScheme.error else Color.Unspecified,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).weight(1f),
                )
                if (lineLabel != null) {
                    TextButton(
                        onClick = { openLine(lineLabel, null, lineChoice?.directionId) },
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        modifier = Modifier.height(32.dp),
                    ) { Text("Fermate →", style = MaterialTheme.typography.labelLarge) }
                }
            }
            VehicleMap(
                vehicles = if (showStops) emptyList() else shown,
                showStops = showStops,
                stops = mapStops,
                visibleStopIds = stopIds,
                lineShapes = lineShapes,
                highlightDirection = lineChoice?.directionId,
                onOpenStop = openStop,
                loadRoutes = vm::routesForStop,
                onOpenLine = { v -> openLine(v.routeLabel, v.routeId, v.directionId) },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            LoadContent(state, empty = "Nessun mezzo in servizio") {
                val updatedAt = (state as? Load.Ready)?.fetchedAt?.let { HHMM.format(Instant.ofEpochMilli(it)) }
                Text(
                    "${shown.size} mezzi" + (updatedAt?.let { " · aggiornato alle $it" } ?: ""),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                LazyColumn(
                    Modifier.padding(horizontal = 16.dp),
                    state = memory.list("vehicles"),
                    contentPadding = PaddingValues(bottom = LocalBottomBarInset.current),
                ) {
                    items(shown, key = { it.vehicleId }) { VehicleRow(it) }
                }
            }
        }
    }
}

@Composable
private fun VehicleRow(v: VehiclePosition) {
    AppListItem(
        leadingContent = { RouteBadge(v.routeLabel, live = true) },
        supportingContent = {
            Text(
                listOfNotNull(
                    v.currentStopName?.let { "presso $it" },
                    "%.5f, %.5f".format(v.lat, v.lon),
                    v.timestamp?.let { "agg. " + HHMM.format(Instant.ofEpochSecond(it)) },
                ).joinToString(" · "),
                maxLines = 2,
            )
        },
    ) { Text(v.headsign?.let { "→ $it" } ?: "Mezzo ${v.label ?: v.vehicleId}", maxLines = 1, overflow = TextOverflow.Ellipsis) }
}

@Composable
private fun AlertsScreen(vm: TransitViewModel) {
    val state by vm.alerts.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { if (state is Load.Idle) vm.refreshAlerts() }
    Column(Modifier.fillMaxSize().padding(start = 16.dp, top = 16.dp, end = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Avvisi attivi", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            val refreshing by vm.refreshing.collectAsStateWithLifecycle()
            RefreshButton(TransitViewModel.REFRESH_ALERTS in refreshing) { vm.refreshAlerts(manual = true) }
        }
        LoadContent(state, empty = "Nessun avviso attivo") { alerts ->
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                state = LocalScrollMemory.current.list("alerts"),
                contentPadding = PaddingValues(bottom = LocalBottomBarInset.current),
            ) {
                items(alerts, key = { it.id }) { AlertCard(it) }
            }
        }
    }
}

@Composable
private fun AlertCard(a: ServiceAlert) {
    var expanded by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth().clickable { expanded = !expanded }) {
        Column(Modifier.padding(12.dp)) {
            if (a.routeLabels.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    a.routeLabels.take(6).forEach { AssistChip(onClick = {}, label = { Text(it) }) }
                }
            }
            Text(a.header, style = MaterialTheme.typography.titleSmall)
            a.description?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = if (expanded) Int.MAX_VALUE else 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

/** Preferiti nella schermata iniziale (mostrati quando la ricerca è vuota). */
private fun LazyListScope.favoritesSection(favorites: FavoritesState, vm: TransitViewModel) {
    if (!favorites.loaded) return
    if (favorites.lines.isEmpty() && favorites.stops.isEmpty()) {
        item {
            Text(
                "Nessun preferito. Apri una linea o una fermata e tocca ☆ in alto per ritrovarla qui.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 24.dp),
            )
        }
        return
    }
    if (favorites.lines.isNotEmpty()) {
        item { SectionTitle("Linee preferite") }
        items(favorites.lines, key = { "fav-line-${it.route.id}-${it.pattern.directionId}" }) { fav ->
            AppListItem(
                leadingContent = { RouteBadge(fav.route.label) },
                supportingContent = fav.pattern.fromStopName?.let { from -> { Text("da $from") } },
                modifier = Modifier.clickable { vm.openFavoriteLine(fav) },
            ) { Text("→ ${fav.pattern.headsign ?: "Direzione ${fav.pattern.directionId}"}") }
        }
    }
    if (favorites.stops.isNotEmpty()) {
        item { SectionTitle("Fermate preferite") }
        items(favorites.stops, key = { "fav-stop-" + it.id }) { stop ->
            AppListItem(
                supportingContent = { Text("Palina ${stop.code ?: stop.id}") },
                modifier = Modifier.clickable { vm.selectStop(stop) },
            ) { Text(stop.name) }
        }
    }
}

/** Chiave (preferenze "ui") dell'ultima vista scelta nella Home: true = fermate vicine. */
private const val PREF_HOME_SHOW_NEARBY = "home_show_nearby"

/** Riquadro di "Preferiti" e "Vicine" nella Home: angoli della CardCorner solo in alto. */
private val HomePanelShape = androidx.compose.foundation.shape.RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)

/** Fermate vicine nella schermata iniziale (mostrate quando la ricerca è vuota). */
private fun LazyListScope.nearbySection(
    state: NearbyState,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    onAskPermission: () -> Unit,
    /** Apre una pagina delle impostazioni di sistema (azione di [Settings]). */
    onOpenSettings: (String) -> Unit,
    onOpenStop: (Stop) -> Unit,
) {
    val status = when (state) {
        NearbyState.Idle, NearbyState.Locating -> "Cerco la tua posizione…"
        NearbyState.NoPermission -> "Per vedere le fermate vicine serve l'accesso alla posizione."
        NearbyState.LocationOff -> "La localizzazione del telefono è spenta."
        NearbyState.NoFix -> "Posizione non disponibile: riprova tra poco."
        is NearbyState.Ready -> when (state.stops.size) {
            0 -> "Nessuna fermata entro 1 km"
            1 -> "1 fermata entro 1 km"
            else -> "${state.stops.size} fermate entro 1 km"
        } + " · posizione delle " + HHMM.format(Instant.ofEpochMilli(state.fetchedAt)) +
            (state.accuracy?.let { " (±$it m)" } ?: "")
    }
    item(key = "nearby-status") {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
            Text(
                status,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (state !is NearbyState.NoPermission && state !is NearbyState.LocationOff) {
                RefreshButton(refreshing, onRefresh)
            }
        }
    }
    when (state) {
        NearbyState.Idle, NearbyState.Locating -> item(key = "nearby-loading") { Loading() }
        NearbyState.NoPermission -> item(key = "nearby-permission") {
            Row {
                Button(onClick = onAskPermission) { Text("Consenti") }
                Spacer(Modifier.width(8.dp))
                // Dopo due rifiuti Android non mostra più la richiesta: resta solo l'impostazione dell'app.
                TextButton(onClick = { onOpenSettings(Settings.ACTION_APPLICATION_DETAILS_SETTINGS) }) {
                    Text("Impostazioni app")
                }
            }
        }
        NearbyState.LocationOff -> item(key = "nearby-off") {
            Button(onClick = { onOpenSettings(Settings.ACTION_LOCATION_SOURCE_SETTINGS) }) { Text("Attiva la localizzazione") }
        }
        NearbyState.NoFix -> Unit
        is NearbyState.Ready -> items(state.stops, key = { "nearby-" + it.stop.id }) { near ->
            AppListItem(
                supportingContent = {
                    Text(
                        "Palina ${near.stop.code ?: near.stop.id}" +
                            near.routes.takeIf { it.isNotEmpty() }?.let { r -> " · Linee: " + r.joinToString(" · ") { it.label } }.orEmpty(),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                trailingContent = { Text("${near.meters} m", style = MaterialTheme.typography.labelLarge) },
                modifier = Modifier.clickable { onOpenStop(near.stop) },
            ) { Text(near.stop.name) }
        }
    }
}

/** Stella per aggiungere/togliere dai preferiti: piena e dorata se già preferito. */
@Composable
internal fun FavoriteButton(isFavorite: Boolean, onClick: () -> Unit) {
    val gold = if (androidx.compose.foundation.isSystemInDarkTheme()) Color(0xFFFFC94D) else Color(0xFFB67D00)
    IconButton(onClick = onClick) {
        Icon(
            painterResource(if (isFavorite) R.drawable.ic_star else R.drawable.ic_star_border),
            contentDescription = if (isFavorite) "Rimuovi dai preferiti" else "Aggiungi ai preferiti",
            tint = if (isFavorite) gold else LocalContentColor.current,
        )
    }
}

/** Tasto "aggiorna" in alto a destra: durante l'aggiornamento mostra una rotellina. */
@Composable
internal fun RefreshButton(refreshing: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = !refreshing) {
        if (refreshing) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        } else {
            Icon(painterResource(R.drawable.ic_refresh), contentDescription = "Aggiorna")
        }
    }
}

@Composable
internal fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
    )
}

@Composable
internal fun RouteBadge(label: String, live: Boolean = false) {
    // Verde = mezzo o previsione dal tracciamento live; colore del tema = dato statico.
    val background = if (live) LiveTheme.colors.live else MaterialTheme.colorScheme.primary
    val foreground = if (live) LiveTheme.colors.onLive else MaterialTheme.colorScheme.onPrimary
    Surface(color = background, shape = MaterialTheme.shapes.small) {
        Text(
            label,
            color = foreground,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun <T> LoadContent(state: Load<List<T>>, empty: String, content: @Composable (List<T>) -> Unit) {
    when (state) {
        Load.Idle, Load.Loading -> Loading()
        is Load.Failed -> ErrorText(state.message)
        is Load.Ready -> if (state.value.isEmpty()) Text(empty, Modifier.padding(16.dp)) else content(state.value)
    }
}

@Composable
private fun Loading() {
    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun ErrorText(message: String) {
    Text(
        "Errore nel caricare i dati in tempo reale: $message",
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.padding(16.dp),
    )
}
