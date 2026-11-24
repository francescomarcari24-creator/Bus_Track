package com.example.bustrack.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.RectF
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.bustrack.gtfs.Route
import com.example.bustrack.gtfs.RouteShape
import com.example.bustrack.gtfs.Stop
import com.example.bustrack.gtfs.VehiclePosition
import com.example.bustrack.gtfs.bearingDegrees
import com.example.bustrack.gtfs.distanceMeters
import com.example.bustrack.ui.theme.LiveTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.LocationComponentOptions
import org.maplibre.android.location.OnCameraTrackingChangedListener
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonOptions
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import java.time.Instant

/**
 * Stile vettoriale OpenFreeMap (dati OpenStreetMap): gratuito, senza API key,
 * pensato anche per l'uso da app. Attribuzione mostrata da MapLibre.
 */
private const val STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"
/** Stile scuro di OpenFreeMap, usato col tema scuro del telefono (stessi caratteri di "liberty"). */
private const val STYLE_URL_DARK = "https://tiles.openfreemap.org/styles/dark"
/** Alone di testi e bordi con la mappa scura (con quella chiara è bianco). */
private const val DARK_HALO = 0xFF121212.toInt()
private val ROME_CENTER = LatLng(41.9028, 12.4964)
private const val SOURCE_ID = "vehicles"
private const val CIRCLE_LAYER = "vehicles-circle"
private const val LABEL_LAYER = "vehicles-label"
/** Freccia nel pallino del mezzo, orientata verso il suo capolinea. */
private const val ARROW_LAYER = "vehicles-arrow"
private const val ARROW_IMAGE = "vehicle-arrow"
/** Sotto questa distanza dal capolinea la freccia non si mostra (direzione instabile). */
private const val ARROW_MIN_METERS = 100.0
private const val ROUTE_SOURCE = "route"
private const val ROUTE_OTHER_LAYER = "route-other"
private const val ROUTE_CASING_LAYER = "route-casing"
private const val ROUTE_ARROWS_LAYER = "route-arrows"
private const val ROUTE_LINE_LAYER = "route-line"
/** Varianti del percorso: tratteggiate, sotto il tracciato principale. */
private const val ROUTE_VARIANT_LAYER = "route-variant"
private const val STOPS_SOURCE = "stops"
private const val STOP_CLUSTER_LAYER = "stops-cluster"
private const val STOP_COUNT_LAYER = "stops-cluster-count"
private const val STOP_POINT_LAYER = "stops-point"
private const val STOP_LABEL_LAYER = "stops-label"
/** Fermate vicine all'utente: sorgente a parte, non raggruppata, disegnata sopra le altre. */
private const val STOPS_NEAR_SOURCE = "stops-near"
private const val STOP_NEAR_LAYER = "stops-near-point"
private const val STOP_NEAR_LABEL_LAYER = "stops-near-label"

private val VEHICLE_LAYERS = listOf(CIRCLE_LAYER, ARROW_LAYER, LABEL_LAYER)
private val STOP_LAYERS = listOf(
    STOP_CLUSTER_LAYER, STOP_COUNT_LAYER, STOP_POINT_LAYER, STOP_LABEL_LAYER, STOP_NEAR_LAYER, STOP_NEAR_LABEL_LAYER,
)

/**
 * Mappa con due livelli, alternativi tra loro:
 * - **bus** ([showStops] = false): un punto verde con il numero di linea per ogni mezzo live;
 * - **fermate** ([showStops] = true): le fermate del GTFS, raggruppate in cerchi con il numero a
 *   bassa scala e singole (con il nome da vicino) zoomando. Sono dati statici, quindi in colore tema.
 * Tocca un elemento per i dettagli; da una fermata si apre la pagina con gli arrivi.
 */
@Composable
fun VehicleMap(
    vehicles: List<VehiclePosition>,
    showStops: Boolean,
    stops: List<Stop>,
    visibleStopIds: Set<String>?,
    lineShapes: List<RouteShape>,
    highlightDirection: Int?,
    onOpenStop: (Stop) -> Unit,
    loadRoutes: suspend (Stop) -> List<Route>,
    onOpenLine: (VehiclePosition) -> Unit,
    /** Capolinea per linea e direzione (chiave [terminusKey]): la freccia del pallino punta lì. */
    termini: Map<String, Stop>,
    /** Fermate vicine all'utente (entro 1 km), evidenziate nel livello fermate. */
    nearStopIds: Set<String>,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    // Tutti i mezzi sulla mappa sono tracciati live: verde. Le fermate sono dati statici: colore tema.
    val dotColor = LiveTheme.colors.live.toArgb()
    val stopColor = MaterialTheme.colorScheme.primary.toArgb()
    val onStopColor = MaterialTheme.colorScheme.onPrimary.toArgb()
    val nearColor = MaterialTheme.colorScheme.tertiary.toArgb()
    // Mappa scura col tema scuro: di sera una mappa chiara abbaglia. Gli aloni dei testi e il
    // bordo del tracciato seguono lo sfondo (bianchi sulla chiara, quasi neri sulla scura).
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    val halo = if (dark) DARK_HALO else android.graphics.Color.WHITE
    var style by remember { mutableStateOf<Style?>(null) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedStopId by rememberSaveable { mutableStateOf<String?>(null) }
    val mapView = remember { createMapView(context) }
    val memory = LocalScrollMemory.current
    val inPreview = LocalInBackPreview.current
    val barInsetPx = with(androidx.compose.ui.platform.LocalDensity.current) { LocalBottomBarInset.current.toPx() }
    // Il listener dei tocchi si registra una volta sola: legge sempre la modalità corrente.
    val showStopsNow by rememberUpdatedState(showStops)
    var mapRef by remember { mutableStateOf<MapLibreMap?>(null) }

    // Posizione dell'utente: permesso chiesto una volta per sessione aprendo la mappa
    // (non nell'anteprima del gesto indietro) e di nuovo toccando il tasto "la mia posizione".
    var hasLocation by remember { mutableStateOf(context.hasLocationPermission()) }
    var following by remember { mutableStateOf(false) }
    var centerWhenReady by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result -> hasLocation = result.values.any { it } || context.hasLocationPermission() }
    val requestLocation = {
        permissionLauncher.launch(
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        )
    }
    // Le schede sono composte tutte insieme: si chiede quando la mappa diventa la scheda visibile.
    LaunchedEffect(inPreview) {
        if (!hasLocation && !inPreview && !memory.locationAsked) {
            memory.locationAsked = true
            requestLocation()
        }
    }

    LaunchedEffect(mapView) {
        mapView.getMapAsync { map ->
            // Riprende l'ultima inquadratura (tornando sulla scheda o nell'anteprima del gesto indietro).
            map.cameraPosition = memory.mapCamera
                ?: CameraPosition.Builder().target(ROME_CENTER).zoom(11.5).build()
            map.addOnCameraIdleListener { if (!inPreview) memory.mapCamera = map.cameraPosition }
            map.uiSettings.isRotateGesturesEnabled = false
            // Logo e attribuzione OpenStreetMap restano visibili sopra la barra flottante.
            val bottomPx = (barInsetPx + 8 * context.resources.displayMetrics.density).toInt()
            val logoPx = (8 * context.resources.displayMetrics.density).toInt()
            map.uiSettings.setLogoMargins(logoPx, 0, 0, bottomPx)
            map.uiSettings.setAttributionMargins(logoPx * 12, 0, 0, bottomPx)
            mapRef = map
            // Toccando o trascinando la mappa si smette di seguire la posizione (lo fa MapLibre).
            map.locationComponent.addOnCameraTrackingChangedListener(object : OnCameraTrackingChangedListener {
                override fun onCameraTrackingDismissed() { following = false }
                override fun onCameraTrackingChanged(currentMode: Int) { following = currentMode != CameraMode.NONE }
            })
            map.addOnMapClickListener { latLng ->
                if (showStopsNow) {
                    val hit = map.stopFeatureAt(latLng)
                    when {
                        hit == null -> selectedStopId = null
                        hit.hasProperty("point_count") -> {
                            // Gruppo di fermate: zoom sul gruppo per scomporlo.
                            (hit.geometry() as? Point)?.let { p ->
                                map.animateCamera(
                                    CameraUpdateFactory.newLatLngZoom(
                                        LatLng(p.latitude(), p.longitude()),
                                        map.cameraPosition.zoom + 2.0,
                                    ),
                                    400,
                                )
                            }
                        }
                        else -> selectedStopId = hit.getStringProperty("id")
                    }
                } else {
                    selectedId = map.vehicleAt(latLng)
                }
                true
            }
        }
    }

    // Stile della mappa (chiaro o scuro secondo il tema) e livelli dell'app sopra di esso.
    // Cambiando stile i livelli vanno ricreati: gli effetti che dipendono da `style` ripopolano i dati.
    LaunchedEffect(mapRef, dark) {
        val map = mapRef ?: return@LaunchedEffect
        style = null
        map.setStyle(Style.Builder().fromUri(if (dark) STYLE_URL_DARK else STYLE_URL)) { loaded ->
            // Il tracciato sta sotto a tutto il resto (fermate e mezzi).
            addRouteLayers(loaded, stopColor, halo)
            addStopLayers(loaded, stopColor, onStopColor, nearColor, halo)
            loaded.addSource(GeoJsonSource(SOURCE_ID))
            loaded.addLayer(
                CircleLayer(CIRCLE_LAYER, SOURCE_ID).withProperties(
                    // Abbastanza grande da contenere la freccia del capolinea.
                    PropertyFactory.circleRadius(7f),
                    PropertyFactory.circleColor(dotColor),
                    PropertyFactory.circleStrokeColor(android.graphics.Color.WHITE),
                    PropertyFactory.circleStrokeWidth(2f),
                )
            )
            // Freccia bianca nel pallino, ruotata verso il capolinea (proprietà "bearing", in gradi).
            loaded.addImage(ARROW_IMAGE, arrowBitmap(context.resources.displayMetrics.density))
            loaded.addLayer(
                SymbolLayer(ARROW_LAYER, SOURCE_ID)
                    .withFilter(Expression.has("bearing"))
                    .withProperties(
                        PropertyFactory.iconImage(ARROW_IMAGE),
                        PropertyFactory.iconRotate(Expression.get("bearing")),
                        PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
                        PropertyFactory.iconAllowOverlap(true),
                        PropertyFactory.iconIgnorePlacement(true),
                    )
            )
            loaded.addLayer(
                SymbolLayer(LABEL_LAYER, SOURCE_ID).withProperties(
                    PropertyFactory.textField(Expression.get("label")),
                    PropertyFactory.textFont(arrayOf("Noto Sans Bold")),
                    PropertyFactory.textSize(12f),
                    PropertyFactory.textOffset(arrayOf(0f, -1.4f)),
                    PropertyFactory.textColor(dotColor),
                    PropertyFactory.textHaloColor(halo),
                    PropertyFactory.textHaloWidth(2f),
                    // Le etichette sovrapposte vengono nascoste: zoomando ricompaiono.
                    PropertyFactory.textAllowOverlap(false),
                )
            )
            style = loaded
        }
    }

    // Pallino della posizione (con cerchio di precisione) appena ci sono stile e permesso.
    LaunchedEffect(style, mapRef, hasLocation) {
        val loaded = style ?: return@LaunchedEffect
        val m = mapRef ?: return@LaunchedEffect
        if (!hasLocation) return@LaunchedEffect
        m.enableUserLocation(context, loaded)
        if (centerWhenReady && !inPreview) {
            centerWhenReady = false
            m.followUser()
        }
    }

    // Un solo livello alla volta: mostra quello scelto e nasconde l'altro; chiude le schede aperte.
    LaunchedEffect(style, showStops) {
        val loaded = style ?: return@LaunchedEffect
        val vehicleVisibility = PropertyFactory.visibility(if (showStops) Property.NONE else Property.VISIBLE)
        val stopVisibility = PropertyFactory.visibility(if (showStops) Property.VISIBLE else Property.NONE)
        VEHICLE_LAYERS.forEach { loaded.getLayer(it)?.setProperties(vehicleVisibility) }
        STOP_LAYERS.forEach { loaded.getLayer(it)?.setProperties(stopVisibility) }
        selectedId = null
        selectedStopId = null
    }

    // Aggiorna i punti a ogni nuovo snapshot del feed.
    LaunchedEffect(style, vehicles, termini) {
        val source = style?.getSourceAs<GeoJsonSource>(SOURCE_ID) ?: return@LaunchedEffect
        source.setGeoJson(
            FeatureCollection.fromFeatures(
                vehicles.map { v ->
                    Feature.fromGeometry(Point.fromLngLat(v.lon, v.lat)).apply {
                        addStringProperty("id", v.vehicleId)
                        addStringProperty("label", v.routeLabel)
                        val terminus = v.routeId?.let { r -> v.directionId?.let { d -> termini[terminusKey(r, d)] } }
                        if (terminus != null &&
                            distanceMeters(v.lat, v.lon, terminus.lat, terminus.lon) >= ARROW_MIN_METERS
                        ) {
                            addNumberProperty("bearing", bearingDegrees(v.lat, v.lon, terminus.lat, terminus.lon))
                        }
                    }
                }
            )
        )
    }

    // Tracciato della linea filtrata (una linea per direzione). Quando arriva una linea nuova
    // adatta l'inquadratura al percorso; tornando sulla scheda mantiene quella dell'utente.
    LaunchedEffect(style, lineShapes, highlightDirection) {
        val loaded = style ?: return@LaunchedEffect
        val source = loaded.getSourceAs<GeoJsonSource>(ROUTE_SOURCE) ?: return@LaunchedEffect
        // Se la direzione scelta non ha un tracciato (es. linea circolare) si evidenziano tutte.
        val chosen = highlightDirection?.takeIf { d -> lineShapes.any { it.directionId == d } }
        source.setGeoJson(
            FeatureCollection.fromFeatures(
                lineShapes.map { shape ->
                    val isChosen = chosen != null && shape.directionId == chosen
                    Feature.fromGeometry(
                        LineString.fromLngLats(shape.lat.indices.map { Point.fromLngLat(shape.lon[it], shape.lat[it]) })
                    ).apply {
                        addBooleanProperty("hl", chosen == null || isChosen)
                        addBooleanProperty("variant", !shape.main)
                        addBooleanProperty("arrows", isChosen && shape.main)
                    }
                }
            )
        )
        val key = lineShapes.joinToString("|") { "${it.routeId}:${it.directionId}" } + "#$chosen"
        if (lineShapes.isNotEmpty() && !inPreview && memory.fittedLineKey != key) {
            memory.fittedLineKey = key
            // Con una direzione scelta ci si inquadra su quella.
            val fitShapes = lineShapes.filter { it.main && (chosen == null || it.directionId == chosen) }
            val bounds = LatLngBounds.Builder().apply {
                fitShapes.forEach { s -> s.lat.indices.forEach { include(LatLng(s.lat[it], s.lon[it])) } }
            }.build()
            val pad = (48 * context.resources.displayMetrics.density).toInt()
            mapView.getMapAsync { map ->
                // Il fondo tiene conto della barra flottante.
                map.easeCamera(CameraUpdateFactory.newLatLngBounds(bounds, pad, pad, pad, pad + barInsetPx.toInt()), 600)
            }
        } else if (lineShapes.isEmpty()) {
            memory.fittedLineKey = null
        }
    }

    // Fermate: costruisce i punti fuori dal thread principale (sono migliaia) e solo se servono.
    LaunchedEffect(style, stops, visibleStopIds, showStops, nearStopIds) {
        val source = style?.getSourceAs<GeoJsonSource>(STOPS_SOURCE) ?: return@LaunchedEffect
        val nearSource = style?.getSourceAs<GeoJsonSource>(STOPS_NEAR_SOURCE) ?: return@LaunchedEffect
        if (!showStops) return@LaunchedEffect
        // Le fermate vicine vanno nella loro sorgente (mai raggruppate), le altre in quella a gruppi.
        val (near, others) = withContext(Dispatchers.Default) {
            val feature = { s: Stop ->
                Feature.fromGeometry(Point.fromLngLat(s.lon, s.lat)).apply {
                    addStringProperty("id", s.id)
                    addStringProperty("name", s.name)
                }
            }
            val (n, o) = stops.asSequence()
                .filter { visibleStopIds == null || it.id in visibleStopIds }
                .partition { it.id in nearStopIds }
            FeatureCollection.fromFeatures(n.map(feature)) to FeatureCollection.fromFeatures(o.map(feature))
        }
        source.setGeoJson(others)
        nearSource.setGeoJson(near)
    }

    // Ciclo di vita della mappa: segue quello dell'app, ma solo finché la scheda Mappa è sullo
    // schermo. Nascosta (Home, Avvisi) va in pausa e smette di disegnare: con la TextureView ogni
    // fotogramma della mappa obbliga a ricomporre tutta la finestra, quindi lasciarla attiva in
    // sottofondo teneva l'app a ~120 fotogrammi al secondo anche da ferma. Non viene distrutta:
    // tornando sulla scheda riparte da dov'era, senza ricaricare stile e dati.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val onScreen = LocalTabOnScreen.current
    var appState by remember { mutableStateOf(lifecycle.currentState) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> appState = event.targetState }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val mapLifecycle = remember(mapView) { MapViewLifecycle(mapView) }
    val target = if (onScreen) appState else minOf(appState, Lifecycle.State.CREATED)
    LaunchedEffect(mapLifecycle, target) { mapLifecycle.moveTo(target) }
    DisposableEffect(mapLifecycle) {
        onDispose {
            mapLifecycle.moveTo(Lifecycle.State.CREATED)
            mapView.onDestroy()
        }
    }

    // Con una scheda aperta, la back gesture la chiude invece di cambiare schermata.
    BackHandler(enabled = (selectedId != null || selectedStopId != null) && !inPreview) {
        selectedId = null
        selectedStopId = null
    }

    // Angoli arrotondati come gli altri riquadri dell'app, anche in basso (la TextureView rispetta il ritaglio).
    Box(modifier.clip(com.example.bustrack.ui.theme.CardCorner)) {
        AndroidView(factory = { mapView })
        // Riporta la mappa sulla posizione dell'utente (e la segue finché non si sposta la mappa).
        SmallFloatingActionButton(
            onClick = {
                val m = mapRef
                when {
                    !hasLocation -> { centerWhenReady = true; requestLocation() }
                    m != null && m.locationComponent.isLocationComponentActivated -> m.followUser()
                    else -> centerWhenReady = true
                }
            },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = if (following) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
        ) {
            Icon(MyLocationIcon, contentDescription = "La mia posizione")
        }
        // Le schede stanno sopra la barra flottante.
        val cardModifier = Modifier.align(Alignment.BottomCenter).padding(12.dp).padding(bottom = LocalBottomBarInset.current)
        if (showStops) {
            stops.firstOrNull { it.id == selectedStopId }?.let { StopCard(it, loadRoutes, onOpenStop, cardModifier) }
        } else {
            vehicles.firstOrNull { it.vehicleId == selectedId }?.let { VehicleCard(it, onOpenLine, cardModifier) }
        }
    }
}

/**
 * Tracciato della linea. Ogni direzione è una feature con le proprietà `hl` (direzione evidenziata,
 * o tutte se nessuna è stata scelta) e `arrows` (solo la direzione scelta):
 * - direzione evidenziata: linea piena con bordo bianco e frecce ">" nel verso di marcia;
 * - altra direzione: linea sottile e attenuata, senza bordo.
 * Colore tema (dato statico, non verde).
 */
private fun addRouteLayers(style: Style, color: Int, halo: Int) {
    style.addSource(GeoJsonSource(ROUTE_SOURCE))
    val white = halo
    val isVariant = Expression.eq(Expression.get("variant"), Expression.literal(true))
    val highlightedOnly = Expression.eq(Expression.get("hl"), Expression.literal(true))
    // I livelli pieni disegnano solo i tracciati principali; le varianti hanno il loro livello.
    val highlighted = Expression.all(highlightedOnly, Expression.not(isVariant))
    val dimmed = Expression.all(Expression.not(highlightedOnly), Expression.not(isVariant))
    val width = Expression.interpolate(
        Expression.linear(), Expression.zoom(),
        Expression.stop(10, 3f), Expression.stop(14, 5.5f), Expression.stop(18, 9f),
    )
    val thinWidth = Expression.interpolate(
        Expression.linear(), Expression.zoom(),
        Expression.stop(10, 2f), Expression.stop(14, 3.5f), Expression.stop(18, 6f),
    )
    style.addLayer(
        LineLayer(ROUTE_VARIANT_LAYER, ROUTE_SOURCE).withFilter(isVariant).withProperties(
            PropertyFactory.lineColor(color),
            PropertyFactory.lineWidth(thinWidth),
            // Varianti della direzione evidenziata ben visibili, quelle dell'altra appena accennate.
            PropertyFactory.lineOpacity(
                Expression.switchCase(highlightedOnly, Expression.literal(0.75f), Expression.literal(0.25f))
            ),
            PropertyFactory.lineDasharray(arrayOf(1.5f, 1.5f)),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
        )
    )
    style.addLayer(
        LineLayer(ROUTE_OTHER_LAYER, ROUTE_SOURCE).withFilter(dimmed).withProperties(
            PropertyFactory.lineColor(color),
            PropertyFactory.lineWidth(thinWidth),
            PropertyFactory.lineOpacity(0.4f),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
        )
    )
    style.addLayer(
        LineLayer(ROUTE_CASING_LAYER, ROUTE_SOURCE).withFilter(highlighted).withProperties(
            PropertyFactory.lineColor(white),
            PropertyFactory.lineWidth(Expression.sum(width, Expression.literal(3f))),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            PropertyFactory.lineOpacity(0.9f),
        )
    )
    style.addLayer(
        LineLayer(ROUTE_LINE_LAYER, ROUTE_SOURCE).withFilter(highlighted).withProperties(
            PropertyFactory.lineColor(color),
            PropertyFactory.lineWidth(width),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
        )
    )
    // Frecce lungo la linea: il testo ">" ruotato secondo la geometria, che segue l'ordine delle
    // fermate, quindi indica il verso di marcia. Solo quando una direzione è stata scelta.
    style.addLayer(
        SymbolLayer(ROUTE_ARROWS_LAYER, ROUTE_SOURCE)
            .withFilter(Expression.eq(Expression.get("arrows"), Expression.literal(true)))
            .withProperties(
                PropertyFactory.symbolPlacement(Property.SYMBOL_PLACEMENT_LINE),
                PropertyFactory.symbolSpacing(48f),
                PropertyFactory.textField(">"),
                PropertyFactory.textFont(arrayOf("Noto Sans Bold")),
                PropertyFactory.textSize(20f),
                // Frecce sempre bianche sul colore della linea, anche con la mappa scura.
                PropertyFactory.textColor(android.graphics.Color.WHITE),
                PropertyFactory.textHaloColor(color),
                PropertyFactory.textHaloWidth(1.6f),
                // Permette di seguire anche le curve strette (default 45°) e toglie il margine di collisione.
                PropertyFactory.textMaxAngle(120f),
                PropertyFactory.textPadding(0f),
                PropertyFactory.textKeepUpright(false),
                PropertyFactory.textRotationAlignment(Property.TEXT_ROTATION_ALIGNMENT_MAP),
                PropertyFactory.textAllowOverlap(true),
                PropertyFactory.textIgnorePlacement(true),
            )
    )
}

/**
 * Livello fermate: sorgente con raggruppamento nativo di MapLibre (cluster), cerchi con il numero
 * per i gruppi, punti per le singole fermate e il nome da zoom 15. Parte nascosto.
 */
private fun addStopLayers(style: Style, color: Int, onColor: Int, nearColor: Int, halo: Int) {
    val white = halo
    style.addSource(
        GeoJsonSource(
            STOPS_SOURCE,
            GeoJsonOptions().withCluster(true).withClusterMaxZoom(14).withClusterRadius(50),
        )
    )
    val isCluster = Expression.has("point_count")
    style.addLayer(
        CircleLayer(STOP_CLUSTER_LAYER, STOPS_SOURCE).withFilter(isCluster).withProperties(
            PropertyFactory.circleColor(color),
            PropertyFactory.circleOpacity(0.85f),
            PropertyFactory.circleStrokeColor(white),
            PropertyFactory.circleStrokeWidth(2f),
            // Più grande dove ci sono più fermate.
            PropertyFactory.circleRadius(
                Expression.step(
                    Expression.get("point_count"), Expression.literal(14f),
                    Expression.stop(10, 17f), Expression.stop(50, 21f), Expression.stop(200, 26f),
                )
            ),
            PropertyFactory.visibility(Property.NONE),
        )
    )
    style.addLayer(
        SymbolLayer(STOP_COUNT_LAYER, STOPS_SOURCE).withFilter(isCluster).withProperties(
            PropertyFactory.textField(Expression.get("point_count_abbreviated")),
            PropertyFactory.textFont(arrayOf("Noto Sans Bold")),
            PropertyFactory.textSize(12f),
            PropertyFactory.textColor(onColor),
            PropertyFactory.textAllowOverlap(true),
            PropertyFactory.visibility(Property.NONE),
        )
    )
    style.addLayer(
        CircleLayer(STOP_POINT_LAYER, STOPS_SOURCE).withFilter(Expression.not(isCluster)).withProperties(
            PropertyFactory.circleColor(color),
            PropertyFactory.circleStrokeColor(white),
            PropertyFactory.circleStrokeWidth(1.5f),
            PropertyFactory.circleRadius(
                Expression.interpolate(
                    Expression.linear(), Expression.zoom(),
                    Expression.stop(12, 3.5f), Expression.stop(16, 6f), Expression.stop(18, 8f),
                )
            ),
            PropertyFactory.visibility(Property.NONE),
        )
    )
    val labels = SymbolLayer(STOP_LABEL_LAYER, STOPS_SOURCE).withFilter(Expression.not(isCluster)).withProperties(
        PropertyFactory.textField(Expression.get("name")),
        PropertyFactory.textFont(arrayOf("Noto Sans Regular")),
        PropertyFactory.textSize(11f),
        PropertyFactory.textOffset(arrayOf(0f, 1.2f)),
        PropertyFactory.textAnchor(Property.TEXT_ANCHOR_TOP),
        PropertyFactory.textMaxWidth(8f),
        PropertyFactory.textColor(color),
        PropertyFactory.textHaloColor(white),
        PropertyFactory.textHaloWidth(1.5f),
        PropertyFactory.textAllowOverlap(false),
        PropertyFactory.visibility(Property.NONE),
    )
    labels.minZoom = 15f
    style.addLayer(labels)

    // Fermate vicine all'utente: pallino più grande con anello colorato e nome visibile prima.
    style.addSource(GeoJsonSource(STOPS_NEAR_SOURCE))
    style.addLayer(
        CircleLayer(STOP_NEAR_LAYER, STOPS_NEAR_SOURCE).withProperties(
            PropertyFactory.circleColor(color),
            PropertyFactory.circleStrokeColor(nearColor),
            PropertyFactory.circleStrokeWidth(3f),
            PropertyFactory.circleRadius(
                Expression.interpolate(
                    Expression.linear(), Expression.zoom(),
                    Expression.stop(12, 5.5f), Expression.stop(16, 8f), Expression.stop(18, 10f),
                )
            ),
            PropertyFactory.visibility(Property.NONE),
        )
    )
    val nearLabels = SymbolLayer(STOP_NEAR_LABEL_LAYER, STOPS_NEAR_SOURCE).withProperties(
        PropertyFactory.textField(Expression.get("name")),
        PropertyFactory.textFont(arrayOf("Noto Sans Bold")),
        PropertyFactory.textSize(11f),
        PropertyFactory.textOffset(arrayOf(0f, 1.3f)),
        PropertyFactory.textAnchor(Property.TEXT_ANCHOR_TOP),
        PropertyFactory.textMaxWidth(8f),
        PropertyFactory.textColor(nearColor),
        PropertyFactory.textHaloColor(white),
        PropertyFactory.textHaloWidth(1.5f),
        PropertyFactory.textAllowOverlap(false),
        PropertyFactory.visibility(Property.NONE),
    )
    nearLabels.minZoom = 14f
    style.addLayer(nearLabels)
}

@Composable
private fun StopCard(stop: Stop, loadRoutes: suspend (Stop) -> List<Route>, onOpen: (Stop) -> Unit, modifier: Modifier) {
    var routes by remember(stop.id) { mutableStateOf<List<Route>?>(null) }
    LaunchedEffect(stop.id) { routes = runCatching { loadRoutes(stop) }.getOrDefault(emptyList()) }
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(stop.name, style = MaterialTheme.typography.titleMedium)
            Text("Palina ${stop.code ?: stop.id}", style = MaterialTheme.typography.bodySmall)
            routes?.takeIf { it.isNotEmpty() }?.let { list ->
                Text(
                    "Linee: " + list.joinToString(" · ") { it.label },
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            TextButton(onClick = { onOpen(stop) }) { Text("Apri fermata e arrivi →") }
        }
    }
}

@Composable
private fun VehicleCard(v: VehiclePosition, onOpenLine: (VehiclePosition) -> Unit, modifier: Modifier) {
    Card(
        modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = LiveTheme.colors.liveContainer,
            contentColor = LiveTheme.colors.onLiveContainer,
        ),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                "Linea ${v.routeLabel}" + (v.headsign?.let { " → $it" } ?: ""),
                style = MaterialTheme.typography.titleMedium,
            )
            listOfNotNull(
                v.currentStopName?.let { "Presso $it" },
                v.label?.let { "Vettura $it" },
                v.timestamp?.let { "Posizione delle " + HHMM.format(Instant.ofEpochSecond(it)) },
            ).forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
            // Elenco fermate della linea, nella direzione di questo mezzo.
            TextButton(
                onClick = { onOpenLine(v) },
                colors = ButtonDefaults.textButtonColors(contentColor = LiveTheme.colors.onLiveContainer),
            ) { Text("Fermate della linea →") }
        }
    }
}

private fun MapLibreMap.vehicleAt(latLng: LatLng): String? {
    val p = projection.toScreenLocation(latLng)
    val r = 24f
    return queryRenderedFeatures(RectF(p.x - r, p.y - r, p.x + r, p.y + r), CIRCLE_LAYER, ARROW_LAYER, LABEL_LAYER)
        .firstOrNull()?.getStringProperty("id")
}

/** Gruppo o fermata sotto il dito (i gruppi hanno la proprietà `point_count`). */
private fun MapLibreMap.stopFeatureAt(latLng: LatLng): Feature? {
    val p = projection.toScreenLocation(latLng)
    val r = 28f
    return queryRenderedFeatures(
        RectF(p.x - r, p.y - r, p.x + r, p.y + r), STOP_NEAR_LAYER, STOP_CLUSTER_LAYER, STOP_POINT_LAYER,
    )
        .firstOrNull()
}

/** Attiva il LocationComponent di MapLibre (motore di posizione di Android, senza Play Services). */
@SuppressLint("MissingPermission")
private fun MapLibreMap.enableUserLocation(context: Context, style: Style) {
    val lc = locationComponent
    if (!lc.isLocationComponentActivated) {
        lc.activateLocationComponent(
            LocationComponentActivationOptions.builder(context, style)
                .locationComponentOptions(
                    // Niente pulsazione: è un'animazione infinita che obbligava la mappa a
                    // ridisegnare a ogni fotogramma. Restano pallino e cerchio di precisione.
                    LocationComponentOptions.builder(context).pulseEnabled(false).build()
                )
                .useDefaultLocationEngine(true)
                .build()
        )
    }
    lc.isLocationComponentEnabled = true
    lc.renderMode = RenderMode.NORMAL
}

/** Centra sulla posizione dell'utente e la segue; da lontano ingrandisce fino al livello delle strade. */
private fun MapLibreMap.followUser() {
    val zoom = cameraPosition.zoom.coerceAtLeast(15.5)
    locationComponent.setCameraMode(CameraMode.TRACKING, 600L, zoom, null, null, null)
}

/** Icona Material "my_location" (non inclusa in material-icons-core). */
private val MyLocationIcon: ImageVector by lazy {
    ImageVector.Builder("MyLocation", 24.dp, 24.dp, 24f, 24f).addPath(
        addPathNodes(
            "M12,8c-2.21,0 -4,1.79 -4,4s1.79,4 4,4 4,-1.79 4,-4 -1.79,-4 -4,-4zM20.94,11c-0.46,-4.17 -3.77,-7.48 " +
                "-7.94,-7.94L13,1h-2v2.06C6.83,3.52 3.52,6.83 3.06,11L1,11v2h2.06c0.46,4.17 3.77,7.48 7.94,7.94L11,23h2" +
                "v-2.06c4.17,-0.46 7.48,-3.77 7.94,-7.94L23,13v-2h-2.06zM12,19c-3.87,0 -7,-3.13 -7,-7s3.13,-7 7,-7 7,3.13 " +
                "7,7 -3.13,7 -7,7z"
        ),
        fill = SolidColor(androidx.compose.ui.graphics.Color.Black),
    ).build()
}

/** Porta la MapView (già creata con onCreate) nello stato richiesto: avviata, attiva o ferma. */
private class MapViewLifecycle(private val mapView: MapView) {
    private var state = Lifecycle.State.CREATED

    fun moveTo(target: Lifecycle.State) {
        val goal = target.coerceIn(Lifecycle.State.CREATED, Lifecycle.State.RESUMED)
        if (goal >= Lifecycle.State.STARTED && state < Lifecycle.State.STARTED) mapView.onStart()
        if (goal >= Lifecycle.State.RESUMED && state < Lifecycle.State.RESUMED) mapView.onResume()
        if (goal < Lifecycle.State.RESUMED && state >= Lifecycle.State.RESUMED) mapView.onPause()
        if (goal < Lifecycle.State.STARTED && state >= Lifecycle.State.STARTED) mapView.onStop()
        state = goal
    }
}

private fun createMapView(context: Context): MapView {
    MapLibre.getInstance(context)
    // TextureView invece di SurfaceView: segue spostamenti e trasformazioni di Compose senza scatti
    // (la scheda mappa scorre insieme alla barra).
    val options = MapLibreMapOptions.createFromAttributes(context).textureMode(true)
    return MapView(context, options).apply { onCreate(null) }
}

/**
 * Freccia di navigazione bianca che punta in alto (nord), da ruotare con `icon-rotate`:
 * punta, due alette e una tacca al centro della base, per stare dentro il pallino da 7 dp.
 */
private fun arrowBitmap(density: Float): android.graphics.Bitmap {
    val size = (10 * density).toInt().coerceAtLeast(10)
    val bitmap = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
    val s = size.toFloat()
    val path = android.graphics.Path().apply {
        moveTo(s * 0.5f, s * 0.06f)
        lineTo(s * 0.9f, s * 0.92f)
        lineTo(s * 0.5f, s * 0.7f)
        lineTo(s * 0.1f, s * 0.92f)
        close()
    }
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        style = android.graphics.Paint.Style.FILL
    }
    android.graphics.Canvas(bitmap).drawPath(path, paint)
    return bitmap
}
