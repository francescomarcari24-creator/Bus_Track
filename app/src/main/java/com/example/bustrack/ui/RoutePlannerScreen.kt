package com.example.bustrack.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.bustrack.gtfs.VehiclePosition
import com.example.bustrack.routing.Turn
import com.example.bustrack.routing.WalkInstruction
import com.example.bustrack.ui.theme.LiveTheme
import kotlinx.coroutines.delay
import java.util.Locale
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import com.example.bustrack.ui.theme.CardCorner
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Indici delle schede della barra in basso. */
object Tabs {
    const val HOME = 0
    const val MAP = 1
    const val ROUTE = 2
    const val ALERTS = 3
    val LABELS = listOf("Home", "Mappa", "Nav", "Avvisi")
}

/**
 * Pillola "Termina navigazione": alta come gli slider, stile delle pillole dell'app con accenti
 * rossi (icona di stop e testo nel colore d'errore del tema).
 */
@Composable
internal fun StopNavigationPill(onClick: () -> Unit, label: String = "Termina navigazione") {
    androidx.compose.material3.Surface(
        onClick = onClick,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 8.dp,
        tonalElevation = 3.dp,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.6f)),
        modifier = Modifier.height(48.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 18.dp)) {
            Box(Modifier.size(12.dp).background(MaterialTheme.colorScheme.error, RoundedCornerShape(3.dp)))
            Spacer(Modifier.width(10.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/** Altezza dei campi (come OutlinedTextField) e del tasto della partenza. */
private val FieldHeight = 56.dp
private val ButtonSize = 56.dp
private val RowGap = 8.dp

/**
 * Scheda "Nav": navigazione da A a B. In basso, sopra la barra delle schede, il campo dell'arrivo e
 * accanto un tasto: toccandolo il campo della partenza sale dal tasto e poi si apre in larghezza;
 * richiudendolo rientra nel tasto e la partenza torna la posizione attuale.
 * Nel riquadro sopra: i suggerimenti mentre si scrive, poi i percorsi trovati; toccandone uno parte
 * la navigazione, con l'elenco dei passi (strade, paline, mezzi, attese) aggiornato con i dati live.
 */
@Composable
fun RoutePlannerScreen(vm: TransitViewModel) {
    val nav = vm.navigation
    val state by nav.state.collectAsStateWithLifecycle()
    val memory = LocalScrollMemory.current
    var from by memory::routeFrom
    var to by memory::routeTo
    var fromOpen by memory::routeFromOpen
    val density = LocalDensity.current
    val focusManager = LocalFocusManager.current
    // Con la tastiera aperta i campi salgono sopra di lei (altrimenti stanno sopra la barra).
    val imeBottom = with(density) { WindowInsets.ime.getBottom(this).toDp() }
    val bottom = maxOf(LocalBottomBarInset.current, imeBottom + 8.dp)
    val onScreen = LocalTabOnScreen.current
    val inPreview = LocalInBackPreview.current
    val active = state.active

    // Grafo e orari si caricano appena si apre la scheda: la prima ricerca è poi immediata.
    LaunchedEffect(onScreen) { if (onScreen) nav.prepareEngine() }
    // Navigazione in corso e scheda visibile: orari live e posizioni dei mezzi a ogni nuovo feed.
    LaunchedEffect(active, onScreen, inPreview) {
        if (active == null || !onScreen || inPreview) return@LaunchedEffect
        while (true) {
            vm.refreshVehicles()
            val ts = nav.refreshLive()
            delay(TransitViewModel.liveDelayMs(ts))
        }
    }
    val vehicles = (vm.vehicles.collectAsStateWithLifecycle().value as? Load.Ready)?.value.orEmpty()

    var focused by remember { mutableStateOf<PlaceField?>(null) }
    val pickOnMap = LocalPickOnMap.current
    // Notifica della navigazione: il permesso si chiede la prima volta che se ne avvia una.
    val context = androidx.compose.ui.platform.LocalContext.current
    val notificationPermission = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { }
    val options by nav.options.collectAsStateWithLifecycle()
    var optionsOpen by remember { mutableStateOf(false) }
    val pick: (PlaceField, Place?) -> Unit = { field, place ->
        when {
            field == PlaceField.TO -> to = place?.name.orEmpty()
            place == null -> { from = ""; fromOpen = false }
            else -> from = place.name
        }
        focusManager.clearFocus()
        nav.choose(field, place)
    }

    // Il riquadro si ferma sopra i campi (anche sopra quello della partenza, quando è aperto); in
    // navigazione i campi spariscono e il riquadro scende fin sopra la barra.
    val panelBottom by animateDpAsState(
        when {
            active != null -> bottom + 8.dp
            fromOpen -> 12.dp + FieldHeight + RowGap + FieldHeight + bottom
            else -> 12.dp + FieldHeight + bottom
        },
        label = "panelBottom",
    )
    // Stessi margini e titolo della scheda Avvisi (senza barra di ricerca).
    Box(Modifier.fillMaxSize().padding(start = 16.dp, top = 16.dp, end = 16.dp)) {
        Column(Modifier.fillMaxSize()) {
            // Riga alta quanto gli slider: titolo a sinistra, "Termina navigazione" (solo durante una
            // navigazione) e a destra, dove nella Home c'è il tasto delle impostazioni, le preferenze.
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(48.dp)) {
                // In navigazione il titolo si accorcia: lascia spazio a "Termina navigazione".
                Text(if (active != null) "Nav" else "Navigazione", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                if (active != null) {
                    StopNavigationPill(onClick = nav::stopNavigation)
                    Spacer(Modifier.width(12.dp))
                }
                RouteOptionsButton(
                    open = optionsOpen,
                    active = options != com.example.bustrack.routing.PlanOptions(),
                    onClick = { focusManager.clearFocus(); optionsOpen = !optionsOpen },
                )
            }
            Spacer(Modifier.height(12.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(bottom = panelBottom)
                    .clip(CardCorner)
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f), CardCorner),
            ) {
                val query = when (focused) {
                    PlaceField.FROM -> from
                    PlaceField.TO -> to
                    null -> ""
                }
                when {
                    active != null -> NavigationSteps(active, state.live, vehicles, state.progress, state.reroute)
                    focused != null ->
                        Suggestions(
                            field = focused!!,
                            places = if (state.suggestField == focused && query.trim().length >= 2) state.suggestions else emptyList(),
                            searching = state.searchingPlaces && query.trim().length >= 2,
                            showMyLocation = focused == PlaceField.FROM,
                            queryBlank = query.trim().length < 2,
                            onPick = { place -> pick(focused!!, place) },
                            onPickOnMap = if (focused == PlaceField.TO) {
                                { focusManager.clearFocus(); pickOnMap() }
                            } else null,
                        )
                    else -> PlanResults(
                        status = state.plan,
                        hasDestination = state.destination != null,
                        onReplan = nav::plan,
                        onStart = { j ->
                            focusManager.clearFocus()
                            nav.startNavigation(j)
                            if (context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
                                android.content.pm.PackageManager.PERMISSION_GRANTED
                            ) {
                                notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                            }
                        },
                    )
                }
            }
        }
        // Campi in basso (nascosti durante la navigazione).
        AnimatedVisibility(
            visible = active == null,
            enter = fadeIn() + slideInVertically { it / 2 },
            exit = fadeOut() + slideOutVertically { it / 2 },
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = bottom),
        ) {
            DestinationAndOrigin(
                to = to,
                onToChange = { to = it; nav.query(PlaceField.TO, it) },
                from = from,
                onFromChange = { from = it; nav.query(PlaceField.FROM, it) },
                fromOpen = fromOpen,
                onToggleFrom = {
                    if (fromOpen) {
                        // Richiusa: partenza = posizione attuale.
                        pick(PlaceField.FROM, null)
                    } else {
                        fromOpen = true
                    }
                },
                onFromFocus = { f -> if (f) focused = PlaceField.FROM else if (focused == PlaceField.FROM) focused = null },
                onToFocus = { f -> if (f) focused = PlaceField.TO else if (focused == PlaceField.TO) focused = null },
            )
        }
        // Ricerche recenti: pannello che sale dal campo selezionato (vuoto), come quello che scende
        // dalla barra di ricerca della Home; toccando una voce la si sceglie.
        val recents by nav.recents.items.collectAsStateWithLifecycle()
        val fieldText = when (focused) {
            PlaceField.FROM -> from
            PlaceField.TO -> to
            null -> ""
        }
        NavRecentsPanel(
            visible = active == null && focused != null && fieldText.isBlank() && recents.isNotEmpty() && !inPreview,
            items = recents,
            onPick = { place -> pick(focused ?: PlaceField.TO, place) },
            onClear = nav.recents::clear,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = panelBottom - 4.dp),
        )
        // Preferenze: nascono dal tasto in alto a destra (sopra a tutto il resto).
        RouteOptionsPanel(
            open = optionsOpen,
            options = options,
            onChange = nav::setOptions,
            onClose = { optionsOpen = false },
        )
    }
}

/** Tasto delle preferenze sul percorso (come quello delle impostazioni della Home). */
@Composable
private fun RouteOptionsButton(open: Boolean, active: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.size(OptionsButtonSize),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 8.dp,
        tonalElevation = 3.dp,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                if (open) Icons.Rounded.Close else TuneIcon,
                contentDescription = if (open) "Chiudi le preferenze" else "Preferenze sul percorso",
                // Con qualche preferenza attiva l'icona prende il colore del tema.
                tint = if (active && !open) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private val OptionsButtonSize = 48.dp

/**
 * Preferenze sul percorso, con la stessa animazione del campo della partenza: la forma nasce dal
 * tasto (tondo, in alto a destra), prima scende sotto di lui e poi si apre in larghezza e in altezza
 * fino a diventare il riquadro; chiudendo fa il contrario. Tocco fuori o gesto indietro: si chiude.
 */
@Composable
private fun RouteOptionsPanel(
    open: Boolean,
    options: com.example.bustrack.routing.PlanOptions,
    onChange: (com.example.bustrack.routing.PlanOptions) -> Unit,
    onClose: () -> Unit,
) {
    val drop = remember { Animatable(0f) }
    val grow = remember { Animatable(0f) }
    val spec = spring<Float>(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow)
    LaunchedEffect(open) {
        if (open) {
            drop.animateTo(1f, spec)
            grow.animateTo(1f, spec)
        } else {
            grow.animateTo(0f, spec)
            drop.animateTo(0f, spec)
        }
    }
    androidx.activity.compose.BackHandler(enabled = open, onBack = onClose)
    if (drop.value <= 0f && grow.value <= 0f) return
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val fullW = constraints.maxWidth.toFloat()
        val buttonPx = with(density) { OptionsButtonSize.toPx() }
        val gapPx = with(density) { RowGap.toPx() }
        var contentH by remember { mutableStateOf(with(density) { 320.dp.toPx() }) }
        // Tocco fuori dal riquadro: si chiude.
        if (open) {
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) { detectTapGestures { onClose() } }
            )
        }
        val d = drop.value
        val g = grow.value
        val w = lerp(buttonPx, fullW, g)
        val h = lerp(buttonPx, contentH, g)
        val x = lerp(fullW - buttonPx, 0f, g)
        val y = lerp(0f, buttonPx + gapPx, d)
        val radius = with(density) { minOf(h / 2, 28.dp.toPx()).toDp() }
        val shape = RoundedCornerShape(radius)
        // Il contenuto compare a forma quasi aperta (deformato sarebbe illeggibile).
        val contentAlpha = ((g - 0.6f) / 0.4f).coerceIn(0f, 1f)
        Box(
            Modifier
                .offset { IntOffset(x.roundToInt(), y.roundToInt()) }
                .size(with(density) { w.toDp() }, with(density) { h.toDp() })
                .shadow(8.dp, shape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh, shape)
                .clip(shape)
                // I tocchi sul riquadro non arrivano allo strato che lo chiude.
                .pointerInput(Unit) { detectTapGestures { } },
        ) {
            Column(
                Modifier
                    .wrapContentSize(Alignment.TopStart, unbounded = true)
                    .width(with(density) { fullW.toDp() })
                    .onSizeChanged { contentH = it.height.toFloat() }
                    .graphicsLayer { alpha = contentAlpha }
                    .padding(vertical = 8.dp),
            ) {
                Text(
                    "Preferenze sul percorso",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
                )
                OptionRow("Meno tratti a piedi", "Fermate più vicine e cambi a piedi brevi", options.lessWalking) {
                    onChange(options.copy(lessWalking = it))
                }
                OptionRow("Meno cambi", "Preferisce i percorsi con meno mezzi", options.lessTransfers) {
                    onChange(options.copy(lessTransfers = it))
                }
                OptionRow("Niente metro", "Esclude le linee della metropolitana", options.noMetro) {
                    onChange(options.copy(noMetro = it))
                }
                OptionRow("Niente tram", "Esclude le linee del tram", options.noTram) {
                    onChange(options.copy(noTram = it))
                }
            }
        }
    }
}

@Composable
private fun OptionRow(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(start = 16.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        androidx.compose.material3.Switch(checked = checked, onCheckedChange = onChange)
    }
}

private val TuneIcon: ImageVector by lazy {
    materialIcon(
        "Tune",
        "M3,17v2h6v-2L3,17zM3,5v2h10L13,5L3,5zM13,21v-2h8v-2h-8v-2h-2v6h2zM7,9v2L3,11v2h4v2h2L9,9L7,9zM21,13v-2L11,11v2h10zM15,9h2L17,7h4L21,5h-4L17,3h-2v6z",
    )
}

/** Suggerimenti per il campo in uso: (per la partenza) la posizione attuale, fermate, indirizzi e luoghi. */
@Composable
private fun Suggestions(
    field: PlaceField,
    places: List<Place>,
    searching: Boolean,
    showMyLocation: Boolean,
    queryBlank: Boolean,
    onPick: (Place?) -> Unit,
    /** "Scegli sulla mappa" (solo per la meta). */
    onPickOnMap: (() -> Unit)?,
) {
    LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
        if (onPickOnMap != null) {
            item(key = "map") {
                SuggestionRow(Icons.Rounded.LocationOn, "Scegli sulla mappa", "Tieni premuto un punto della mappa", onPickOnMap)
            }
        }
        if (showMyLocation) {
            item(key = "me") {
                SuggestionRow(MyLocationGlyph, "La tua posizione", "Parti da dove ti trovi") { onPick(null) }
            }
        }
        items(places, key = { "${it.kind}:${it.lat}:${it.lon}:${it.name}" }) { p ->
            SuggestionRow(
                icon = if (p.kind == PlaceKind.STOP) BusIcon else Icons.Rounded.Place,
                title = p.name,
                detail = p.detail,
            ) { onPick(p) }
        }
        if (searching) {
            item(key = "searching") {
                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    Spinner(Modifier.size(24.dp), strokeWidth = 2.dp)
                }
            }
        } else if (places.isEmpty() && !showMyLocation && !queryBlank) {
            item(key = "none") {
                Text(
                    "Nessun luogo trovato.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
        if (field == PlaceField.TO && places.isNotEmpty()) item(key = "hint") { Spacer(Modifier.height(4.dp)) }
    }
}

@Composable
private fun SuggestionRow(icon: ImageVector, title: String, detail: String?, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            detail?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Stato del calcolo e percorsi trovati. */
@Composable
private fun PlanResults(
    status: PlanStatus,
    hasDestination: Boolean,
    onReplan: () -> Unit,
    onStart: (JourneyView) -> Unit,
) {
    @Composable
    fun message(text: String, spinner: Boolean = false, retry: Boolean = false) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth().padding(24.dp),
        ) {
            if (spinner) {
                Spinner(Modifier.size(28.dp))
                Spacer(Modifier.height(12.dp))
            }
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (retry) TextButton(onClick = onReplan) { Text("Riprova") }
        }
    }
    when (status) {
        PlanStatus.Idle -> message(
            if (hasDestination) "Calcolo dei percorsi…"
            else "Scrivi in basso dove vuoi andare: una fermata, un indirizzo o un luogo."
        )
        PlanStatus.Locating -> message("Cerco la tua posizione…", spinner = true)
        PlanStatus.Preparing -> message("Preparo gli orari di oggi…", spinner = true)
        PlanStatus.Searching -> message("Cerco i percorsi…", spinner = true)
        is PlanStatus.Failed -> message(status.message, retry = hasDestination)
        is PlanStatus.Ready -> {
            val now = rememberNow()
            LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item(key = "head") {
                    val j = status.journeys.firstOrNull()
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                j?.let { "Da ${it.origin.name} a ${it.destination.name}" }.orEmpty(),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            // Con i dati live gli orari tengono conto dei ritardi (in verde, come gli altri dati live).
                            Text(
                                if (status.live) "Orari con i ritardi in tempo reale" else "Solo orari programmati: dati live non disponibili",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (status.live) LiveTheme.colors.live else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = onReplan) { Text("Aggiorna") }
                    }
                }
                items(status.journeys, key = { it.key }) { j -> JourneyCard(j, now) { onStart(j) } }
            }
        }
    }
}

/** Ora attuale (epoch secondi), aggiornata ogni 15 secondi per i "tra N min". */
@Composable
private fun rememberNow(): Long {
    val now by androidx.compose.runtime.produceState(System.currentTimeMillis() / 1000) {
        while (true) {
            delay(15_000)
            value = System.currentTimeMillis() / 1000
        }
    }
    return now
}

@Composable
private fun JourneyCard(j: JourneyView, now: Long, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = CardCorner,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${hhmm(j.departure)} – ${hhmm(j.arrival)}",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                Text(duration(j.arrival - j.departure), style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.horizontalScroll(rememberScrollState()),
            ) {
                val shown = j.legs.filter { it !is LegView.Walk || it.meters >= 40 || j.rides == 0 }
                shown.forEachIndexed { i, leg ->
                    if (i > 0) {
                        Text(
                            "›",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 6.dp),
                        )
                    }
                    when (leg) {
                        is LegView.Walk -> Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(WalkIcon, contentDescription = "A piedi", modifier = Modifier.size(18.dp))
                            Text(minutes(leg.arrival - leg.departure), style = MaterialTheme.typography.labelLarge)
                        }
                        is LegView.Ride -> RouteBadge(leg.label)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            val parts = listOfNotNull(
                "Parti " + relative(j.departure, now),
                if (j.rides == 0) "solo a piedi" else when (j.rides) { 1 -> "1 mezzo"; else -> "${j.rides} mezzi" },
                "${distance(j.walkMeters)} a piedi".takeIf { j.rides > 0 },
            )
            Text(
                parts.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Elenco dei passi della navigazione: per ogni tratto a piedi le strade da percorrere, per ogni
 * mezzo la palina dove salire (con attesa e orario, live se disponibile, e dove si trova il mezzo)
 * e quella dove scendere.
 */
/**
 * Ricerche recenti della scheda Nav: pannello che sale dal campo (si apre verso l'alto, con la
 * stessa molla del pannello della Home che scende dalla barra di ricerca).
 */
@Composable
private fun NavRecentsPanel(
    visible: Boolean,
    items: List<Place>,
    onPick: (Place) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spec = spring<androidx.compose.ui.unit.IntSize>(stiffness = Spring.StiffnessMediumLow)
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = CardCorner,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 3.dp,
    ) {
        AnimatedVisibility(
            visible = visible,
            enter = androidx.compose.animation.expandVertically(spec, expandFrom = Alignment.Bottom) + fadeIn(),
            exit = androidx.compose.animation.shrinkVertically(spec, shrinkTowards = Alignment.Bottom) + fadeOut(),
        ) {
            Column(
                Modifier
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 16.dp)) {
                    Text(
                        "Ricerche recenti",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onClear) { Text("Cancella") }
                }
                items.forEach { p ->
                    SuggestionRow(
                        icon = if (p.kind == PlaceKind.STOP) BusIcon else Icons.Rounded.Place,
                        title = p.name,
                        detail = p.detail,
                    ) { onPick(p) }
                }
            }
        }
    }
}

/**
 * Elenco dei passi della navigazione: per ogni tratto a piedi le strade da percorrere, per ogni
 * mezzo la palina dove salire (con attesa e orario, live se disponibile, e dove si trova il mezzo)
 * e quella dove scendere. Con la posizione: il tratto in corso è evidenziato (e l'elenco ci scorre),
 * quelli già fatti sono attenuati; in cima l'eventuale ricalcolo del percorso.
 */
@Composable
private fun NavigationSteps(
    j: JourneyView,
    live: Map<String, Long>,
    vehicles: List<VehiclePosition>,
    progress: NavProgress?,
    reroute: RerouteNotice?,
) {
    val now = rememberNow()
    val lastRide = j.legs.lastOrNull { it is LegView.Ride } as LegView.Ride?
    val shift = lastRide?.let { r -> live["${r.tripId}|${r.to.id}"]?.minus(r.arrival) } ?: 0L
    val arrival = j.arrival + shift
    val current = progress?.leg
    // Partenza (live se nota) di ogni mezzo e attesa prima di lui.
    fun waitBefore(i: Int): Long? {
        val leg = j.legs[i] as? LegView.Ride ?: return null
        val dep = live["${leg.tripId}|${leg.from.id}"] ?: leg.departure
        val before = j.legs.getOrNull(i - 1)?.arrival ?: return null
        return (dep - before).takeIf { it >= 60 }
    }
    // Indice della prima riga di ogni tratto, per scorrere fino a quello in corso.
    val legStart = IntArray(j.legs.size).also { starts ->
        var idx = 1 // riga di intestazione
        j.legs.forEachIndexed { i, leg ->
            starts[i] = idx
            idx += when (leg) {
                is LegView.Walk -> 1 + leg.instructions.size
                is LegView.Ride -> 1 + (if (waitBefore(i) != null) 1 else 0)
            }
        }
    }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    LaunchedEffect(j, current) {
        if (current != null && current > 0) listState.animateScrollToItem(legStart[current])
    }
    fun Modifier.done(i: Int) = graphicsLayer { alpha = if (current != null && i < current) 0.45f else 1f }
    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 24.dp),
    ) {
        item(key = "head") {
            Column(Modifier.padding(bottom = 12.dp)) {
                Text("Arrivo previsto alle ${hhmm(arrival)}", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Da ${j.origin.name} a ${j.destination.name} · ${duration(arrival - j.departure)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                reroute?.let {
                    Text(
                        "Percorso ricalcolato alle ${hhmm(it.at)}: ${it.reason}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
        j.legs.forEachIndexed { i, leg ->
            val isCurrent = current == i
            when (leg) {
                is LegView.Walk -> {
                    item(key = "walk$i") {
                        StepHeader(
                            icon = WalkIcon,
                            title = "A piedi · ${minutes(leg.arrival - leg.departure)} · ${distance(leg.meters)}",
                            subtitle = if (isCurrent && progress?.remainingMeters != null) {
                                "In corso · mancano ${distance(progress.remainingMeters)}"
                            } else {
                                "${hhmm(leg.departure)} → ${hhmm(leg.arrival)}"
                            },
                            current = isCurrent,
                            modifier = Modifier.done(i),
                        )
                    }
                    leg.instructions.forEachIndexed { k, ins ->
                        item(key = "walk$i-$k") { Box(Modifier.done(i)) { InstructionRow(ins) } }
                    }
                }
                is LegView.Ride -> {
                    val liveDep = live["${leg.tripId}|${leg.from.id}"]
                    val liveArr = live["${leg.tripId}|${leg.to.id}"]
                    waitBefore(i)?.let { wait ->
                        item(key = "wait$i") {
                            StepHeader(
                                icon = ClockIcon,
                                title = "Attesa alla palina: ${minutes(wait)}",
                                subtitle = null,
                                dim = true,
                                modifier = Modifier.done(i),
                            )
                        }
                    }
                    item(key = "ride$i") {
                        Box(Modifier.done(i)) {
                            RideStep(leg, liveDep, liveArr, vehicles.firstOrNull { it.tripId == leg.tripId }, now, isCurrent)
                        }
                    }
                }
            }
        }
        item(key = "end") {
            StepHeader(
                icon = Icons.Rounded.Place,
                title = "Arrivo: ${j.destination.name}",
                subtitle = "alle ${hhmm(arrival)}",
            )
        }
    }
}

@Composable
private fun StepHeader(
    icon: ImageVector,
    title: String,
    subtitle: String?,
    dim: Boolean = false,
    current: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        StepIcon(icon, dim, current)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = if (dim) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            )
            subtitle?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Icona del passo: nel colore del tema pieno se è il tratto in corso. */
@Composable
private fun StepIcon(icon: ImageVector, dim: Boolean = false, current: Boolean = false) {
    val scheme = MaterialTheme.colorScheme
    Box(
        Modifier
            .size(36.dp)
            .background(
                when {
                    current -> scheme.primary
                    dim -> scheme.surfaceContainerHighest
                    else -> scheme.primaryContainer
                },
                CircleShape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = when {
                current -> scheme.onPrimary
                dim -> scheme.onSurfaceVariant
                else -> scheme.onPrimaryContainer
            },
            modifier = Modifier.size(20.dp),
        )
    }
}

/** Una manovra a piedi: freccia della svolta e testo, rientrati sotto l'intestazione del tratto. */
@Composable
private fun InstructionRow(ins: WalkInstruction) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Box(Modifier.width(36.dp), contentAlignment = Alignment.Center) {
            Text(
                turnGlyph(ins.turn),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            ins.text,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (ins.turn == Turn.ARRIVE) FontWeight.SemiBold else null,
            modifier = Modifier.weight(1f),
        )
    }
}

private fun turnGlyph(t: Turn) = when (t) {
    Turn.START, Turn.STRAIGHT -> "↑"
    Turn.SLIGHT_LEFT -> "↖"
    Turn.LEFT -> "←"
    Turn.SHARP_LEFT -> "↙"
    Turn.SLIGHT_RIGHT -> "↗"
    Turn.RIGHT -> "→"
    Turn.SHARP_RIGHT -> "↘"
    Turn.ARRIVE -> "•"
}

/** Tratto in mezzo: linea e direzione, salita (con attesa e posizione del mezzo), fermate, discesa. */
@Composable
private fun RideStep(
    leg: LegView.Ride,
    liveDep: Long?,
    liveArr: Long?,
    vehicle: VehiclePosition?,
    now: Long,
    current: Boolean = false,
) {
    val liveColor = LiveTheme.colors.live
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        StepIcon(BusIcon, current = current)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RouteBadge(leg.label, live = liveDep != null || vehicle != null)
                Spacer(Modifier.width(8.dp))
                Text(
                    leg.headsign?.let { "→ $it" }.orEmpty(),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "Sali a ${leg.from.name}" + (leg.from.code?.let { " (palina $it)" } ?: ""),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            TimeLine("Parte", leg.departure, liveDep, now, liveColor)
            vehicle?.currentStopName?.let {
                Text("Il mezzo è presso $it", style = MaterialTheme.typography.bodySmall, color = liveColor)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "${if (leg.stopCount == 1) "1 fermata" else "${leg.stopCount} fermate"} · " +
                    minutes((liveArr ?: leg.arrival) - (liveDep ?: leg.departure)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Scendi a ${leg.to.name}" + (leg.to.code?.let { " (palina $it)" } ?: ""),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            TimeLine("Arriva", leg.arrival, liveArr, now, liveColor)
        }
    }
}

/** "Parte alle 15:59 · tra 4 min", con l'orario live in verde quando c'è. */
@Composable
private fun TimeLine(verb: String, scheduled: Long, live: Long?, now: Long, liveColor: androidx.compose.ui.graphics.Color) {
    val t = live ?: scheduled
    val text = buildString {
        append("$verb alle ${hhmm(t)}")
        if (live != null && kotlin.math.abs(live - scheduled) >= 60) append(" (orario ${hhmm(scheduled)})")
        append(" · ").append(relative(t, now))
    }
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = if (live != null) liveColor else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private fun hhmm(epoch: Long): String = HHMM.format(java.time.Instant.ofEpochSecond(epoch))

private fun relative(t: Long, now: Long): String {
    val d = t - now
    return when {
        d < -60 -> "già passato"
        d <= 30 -> "ora"
        d < 3600 -> "tra ${(d + 59) / 60} min"
        else -> "alle ${hhmm(t)}"
    }
}

private fun minutes(seconds: Long): String = "${((seconds + 59) / 60).coerceAtLeast(1)} min"

private fun duration(seconds: Long): String {
    val m = ((seconds + 59) / 60).coerceAtLeast(1)
    return if (m < 60) "$m min" else "${m / 60} h ${m % 60} min"
}

private fun distance(meters: Int): String =
    if (meters < 1000) "${(meters / 10) * 10} m" else "%.1f km".format(Locale.ITALY, meters / 1000.0)

/** Icone Material non incluse in material-icons-core. */
private fun materialIcon(name: String, path: String): ImageVector =
    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        .addPath(addPathNodes(path), fill = SolidColor(androidx.compose.ui.graphics.Color.Black))
        .build()

internal val WalkIcon: ImageVector by lazy {
    materialIcon(
        "DirectionsWalk",
        "M13.5,5.5c1.1,0 2,-0.9 2,-2s-0.9,-2 -2,-2 -2,0.9 -2,2 0.9,2 2,2zM9.8,8.9L7,23h2.1l1.8,-8 2.1,2v6h2v-7.5l-2.1,-2 " +
            "0.6,-3C14.8,12 16.8,13 19,13v-2c-1.9,0 -3.5,-1 -4.3,-2.4l-1,-1.6c-0.4,-0.6 -1,-1 -1.7,-1 -0.3,0 -0.5,0.1 " +
            "-0.8,0.1L6,8.3V13h2V9.6l1.8,-0.7",
    )
}

internal val BusIcon: ImageVector by lazy {
    materialIcon(
        "DirectionsBus",
        "M4,16c0,0.88 0.39,1.67 1,2.22V20c0,0.55 0.45,1 1,1h1c0.55,0 1,-0.45 1,-1v-1h8v1c0,0.55 0.45,1 1,1h1c0.55,0 " +
            "1,-0.45 1,-1v-1.78c0.61,-0.55 1,-1.34 1,-2.22V6c0,-3.5 -3.58,-4 -8,-4s-8,0.5 -8,4v10zM7.5,17c-0.83,0 " +
            "-1.5,-0.67 -1.5,-1.5S6.67,14 7.5,14s1.5,0.67 1.5,1.5S8.33,17 7.5,17zM16.5,17c-0.83,0 -1.5,-0.67 -1.5,-1.5s0.67," +
            "-1.5 1.5,-1.5 1.5,0.67 1.5,1.5 -0.67,1.5 -1.5,1.5zM18,11H6V6h12v5z",
    )
}

private val ClockIcon: ImageVector by lazy {
    materialIcon(
        "Schedule",
        "M11.99,2C6.47,2 2,6.48 2,12s4.47,10 9.99,10C17.52,22 22,17.52 22,12S17.52,2 11.99,2zM12,20c-4.42,0 -8,-3.58 " +
            "-8,-8s3.58,-8 8,-8 8,3.58 8,8 -3.58,8 -8,8zM12.5,7H11v6l5.25,3.15 0.75,-1.23 -4.5,-2.67z",
    )
}

private val MyLocationGlyph: ImageVector by lazy {
    materialIcon(
        "MyLocation",
        "M12,8c-2.21,0 -4,1.79 -4,4s1.79,4 4,4 4,-1.79 4,-4 -1.79,-4 -4,-4zM20.94,11c-0.46,-4.17 -3.77,-7.48 " +
            "-7.94,-7.94L13,1h-2v2.06C6.83,3.52 3.52,6.83 3.06,11L1,11v2h2.06c0.46,4.17 3.77,7.48 7.94,7.94L11,23h2" +
            "v-2.06c4.17,-0.46 7.48,-3.77 7.94,-7.94L23,13v-2h-2.06zM12,19c-3.87,0 -7,-3.13 -7,-7s3.13,-7 7,-7 7,3.13 " +
            "7,7 -3.13,7 -7,7z",
    )
}
/**
 * Riga dell'arrivo con il tasto della partenza; il campo della partenza nasce dal tasto: prima
 * sale (da tondo, alla quota sopra la riga) e poi si apre in larghezza fino a diventare un campo.
 */
@Composable
private fun DestinationAndOrigin(
    to: String,
    onToChange: (String) -> Unit,
    from: String,
    onFromChange: (String) -> Unit,
    fromOpen: Boolean,
    onToggleFrom: () -> Unit,
    onFromFocus: (Boolean) -> Unit,
    onToFocus: (Boolean) -> Unit,
) {
    val rise = remember { Animatable(if (fromOpen) 1f else 0f) }
    val open = remember { Animatable(if (fromOpen) 1f else 0f) }
    val scope = rememberCoroutineScope()
    val spec = spring<Float>(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow)
    LaunchedEffect(fromOpen) {
        if (fromOpen) {
            // Prima sale dal tasto, poi si apre.
            rise.animateTo(1f, spec)
            open.animateTo(1f, spec)
        } else {
            // Al contrario: si richiude e poi rientra nel tasto.
            open.animateTo(0f, spec)
            rise.animateTo(0f, spec)
        }
    }

    BoxWithConstraints(Modifier.fillMaxWidth().height(FieldHeight + RowGap + FieldHeight), contentAlignment = Alignment.BottomStart) {
        val density = LocalDensity.current
        val fullW = with(density) { maxWidth.toPx() }
        val buttonPx = with(density) { ButtonSize.toPx() }
        val fieldPx = with(density) { FieldHeight.toPx() }
        val gapPx = with(density) { RowGap.toPx() }

        // Campo della partenza in movimento: forma che va dal tasto (tondo, in basso a destra) alla
        // riga sopra, larga quanto tutto.
        val shownOrigin = rise.value > 0f || open.value > 0f
        if (shownOrigin) {
            val r = rise.value
            val o = open.value
            val w = lerp(buttonPx, fullW, o)
            val h = lerp(buttonPx, fieldPx, o)
            val x = lerp(fullW - buttonPx, 0f, o)
            val yBottom = lerp(0f, fieldPx + gapPx, r) // quanto sale rispetto alla riga dell'arrivo
            // Il campo vero compare a forma quasi aperta (deformato sarebbe illeggibile) e intanto la
            // forma animata sfuma: a fine animazione resta solo il campo, identico a quello dell'arrivo.
            val fieldAlpha = ((o - 0.6f) / 0.4f).coerceIn(0f, 1f)
            val shape = RoundedCornerShape(with(density) { minOf(h / 2, with(density) { 28.dp.toPx() }).toDp() })
            Box(
                Modifier
                    .offset { IntOffset(x.roundToInt(), -(yBottom.roundToInt())) }
                    .size(with(density) { w.toDp() }, with(density) { h.toDp() })
                    .background(MaterialTheme.colorScheme.surface, shape)
                    .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 1f - fieldAlpha), shape),
            ) {
                Box(Modifier.graphicsLayer { alpha = fieldAlpha }) {
                    if (o > 0.6f) {
                        OutlinedTextField(
                            value = from,
                            onValueChange = onFromChange,
                            placeholder = { Text("Partenza: la mia posizione") },
                            leadingIcon = { Icon(Icons.Rounded.LocationOn, contentDescription = null) },
                            singleLine = true,
                            shape = CardCorner,
                            modifier = Modifier.fillMaxWidth().onFocusChanged { onFromFocus(it.isFocused) },
                        )
                    }
                }
            }
        }

        // Riga dell'arrivo con il tasto della partenza accanto.
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = to,
                onValueChange = onToChange,
                placeholder = { Text("Dove vuoi andare?") },
                leadingIcon = { Icon(Icons.Rounded.Place, contentDescription = null) },
                trailingIcon = if (to.isEmpty()) null else {
                    { IconButton(onClick = { onToChange("") }) { Icon(Icons.Rounded.Close, contentDescription = "Cancella") } }
                },
                singleLine = true,
                shape = CardCorner,
                modifier = Modifier.weight(1f).height(FieldHeight).onFocusChanged { onToFocus(it.isFocused) },
            )
            Spacer(Modifier.width(RowGap))
            Box(
                Modifier
                    .size(ButtonSize)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape)
                    .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                IconButton(onClick = { scope.launch { onToggleFrom() } }) {
                    Icon(
                        if (fromOpen) Icons.Rounded.Close else Icons.Rounded.LocationOn,
                        contentDescription = if (fromOpen) "Parti dalla tua posizione" else "Scegli il punto di partenza",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
