package com.example.bustrack.ui

import android.content.Context
import android.graphics.RectF
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.toArgb
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
import com.example.bustrack.ui.theme.LiveTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
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
private val ROME_CENTER = LatLng(41.9028, 12.4964)
private const val SOURCE_ID = "vehicles"
private const val CIRCLE_LAYER = "vehicles-circle"
private const val LABEL_LAYER = "vehicles-label"
private const val ROUTE_SOURCE = "route"
private const val ROUTE_OTHER_LAYER = "route-other"
private const val ROUTE_CASING_LAYER = "route-casing"
private const val ROUTE_ARROWS_LAYER = "route-arrows"
private const val ROUTE_LINE_LAYER = "route-line"
private const val STOPS_SOURCE = "stops"
private const val STOP_CLUSTER_LAYER = "stops-cluster"
private const val STOP_COUNT_LAYER = "stops-cluster-count"
private const val STOP_POINT_LAYER = "stops-point"
private const val STOP_LABEL_LAYER = "stops-label"

private val VEHICLE_LAYERS = listOf(CIRCLE_LAYER, LABEL_LAYER)
private val STOP_LAYERS = listOf(STOP_CLUSTER_LAYER, STOP_COUNT_LAYER, STOP_POINT_LAYER, STOP_LABEL_LAYER)

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
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    // Tutti i mezzi sulla mappa sono tracciati live: verde. Le fermate sono dati statici: colore tema.
    val dotColor = LiveTheme.colors.live.toArgb()
    val stopColor = MaterialTheme.colorScheme.primary.toArgb()
    val onStopColor = MaterialTheme.colorScheme.onPrimary.toArgb()
    var style by remember { mutableStateOf<Style?>(null) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedStopId by rememberSaveable { mutableStateOf<String?>(null) }
    val mapView = remember { createMapView(context) }
    val memory = LocalScrollMemory.current
    val inPreview = LocalInBackPreview.current
    val barInsetPx = with(androidx.compose.ui.platform.LocalDensity.current) { LocalBottomBarInset.current.toPx() }
    // Il listener dei tocchi si registra una volta sola: legge sempre la modalità corrente.
    val showStopsNow by rememberUpdatedState(showStops)

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
            map.setStyle(Style.Builder().fromUri(STYLE_URL)) { loaded ->
                // Il tracciato sta sotto a tutto il resto (fermate e mezzi).
                addRouteLayers(loaded, stopColor)
                addStopLayers(loaded, stopColor, onStopColor)
                loaded.addSource(GeoJsonSource(SOURCE_ID))
                loaded.addLayer(
                    CircleLayer(CIRCLE_LAYER, SOURCE_ID).withProperties(
                        PropertyFactory.circleRadius(6f),
                        PropertyFactory.circleColor(dotColor),
                        PropertyFactory.circleStrokeColor(android.graphics.Color.WHITE),
                        PropertyFactory.circleStrokeWidth(2f),
                    )
                )
                loaded.addLayer(
                    SymbolLayer(LABEL_LAYER, SOURCE_ID).withProperties(
                        PropertyFactory.textField(Expression.get("label")),
                        PropertyFactory.textFont(arrayOf("Noto Sans Bold")),
                        PropertyFactory.textSize(12f),
                        PropertyFactory.textOffset(arrayOf(0f, -1.3f)),
                        PropertyFactory.textColor(dotColor),
                        PropertyFactory.textHaloColor(android.graphics.Color.WHITE),
                        PropertyFactory.textHaloWidth(2f),
                        // Le etichette sovrapposte vengono nascoste: zoomando ricompaiono.
                        PropertyFactory.textAllowOverlap(false),
                    )
                )
                style = loaded
            }
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
    LaunchedEffect(style, vehicles) {
        val source = style?.getSourceAs<GeoJsonSource>(SOURCE_ID) ?: return@LaunchedEffect
        source.setGeoJson(
            FeatureCollection.fromFeatures(
                vehicles.map { v ->
                    Feature.fromGeometry(Point.fromLngLat(v.lon, v.lat)).apply {
                        addStringProperty("id", v.vehicleId)
                        addStringProperty("label", v.routeLabel)
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
                        addBooleanProperty("arrows", isChosen)
                    }
                }
            )
        )
        val key = lineShapes.joinToString("|") { "${it.routeId}:${it.directionId}" } + "#$chosen"
        if (lineShapes.isNotEmpty() && !inPreview && memory.fittedLineKey != key) {
            memory.fittedLineKey = key
            // Con una direzione scelta ci si inquadra su quella.
            val fitShapes = lineShapes.filter { chosen == null || it.directionId == chosen }
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
    LaunchedEffect(style, stops, visibleStopIds, showStops) {
        val source = style?.getSourceAs<GeoJsonSource>(STOPS_SOURCE) ?: return@LaunchedEffect
        if (!showStops) return@LaunchedEffect
        val collection = withContext(Dispatchers.Default) {
            FeatureCollection.fromFeatures(
                stops.asSequence()
                    .filter { visibleStopIds == null || it.id in visibleStopIds }
                    .map { s ->
                        Feature.fromGeometry(Point.fromLngLat(s.lon, s.lat)).apply {
                            addStringProperty("id", s.id)
                            addStringProperty("name", s.name)
                        }
                    }
                    .toList()
            )
        }
        source.setGeoJson(collection)
    }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) mapView.onPause()
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) mapView.onStop()
            mapView.onDestroy()
        }
    }

    // Con una scheda aperta, la back gesture la chiude invece di cambiare schermata.
    BackHandler(enabled = (selectedId != null || selectedStopId != null) && !inPreview) {
        selectedId = null
        selectedStopId = null
    }

    Box(modifier.clipToBounds()) {
        AndroidView(factory = { mapView })
        // Le schede stanno sopra la barra flottante.
        val cardModifier = Modifier.align(Alignment.BottomCenter).padding(12.dp).padding(bottom = LocalBottomBarInset.current)
        if (showStops) {
            stops.firstOrNull { it.id == selectedStopId }?.let { StopCard(it, loadRoutes, onOpenStop, cardModifier) }
        } else {
            vehicles.firstOrNull { it.vehicleId == selectedId }?.let { VehicleCard(it, cardModifier) }
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
private fun addRouteLayers(style: Style, color: Int) {
    style.addSource(GeoJsonSource(ROUTE_SOURCE))
    val white = android.graphics.Color.WHITE
    val highlighted = Expression.eq(Expression.get("hl"), Expression.literal(true))
    val width = Expression.interpolate(
        Expression.linear(), Expression.zoom(),
        Expression.stop(10, 3f), Expression.stop(14, 5.5f), Expression.stop(18, 9f),
    )
    val thinWidth = Expression.interpolate(
        Expression.linear(), Expression.zoom(),
        Expression.stop(10, 2f), Expression.stop(14, 3.5f), Expression.stop(18, 6f),
    )
    style.addLayer(
        LineLayer(ROUTE_OTHER_LAYER, ROUTE_SOURCE).withFilter(Expression.not(highlighted)).withProperties(
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
                PropertyFactory.textColor(white),
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
private fun addStopLayers(style: Style, color: Int, onColor: Int) {
    val white = android.graphics.Color.WHITE
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
private fun VehicleCard(v: VehiclePosition, modifier: Modifier) {
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
        }
    }
}

private fun MapLibreMap.vehicleAt(latLng: LatLng): String? {
    val p = projection.toScreenLocation(latLng)
    val r = 24f
    return queryRenderedFeatures(RectF(p.x - r, p.y - r, p.x + r, p.y + r), CIRCLE_LAYER, LABEL_LAYER)
        .firstOrNull()?.getStringProperty("id")
}

/** Gruppo o fermata sotto il dito (i gruppi hanno la proprietà `point_count`). */
private fun MapLibreMap.stopFeatureAt(latLng: LatLng): Feature? {
    val p = projection.toScreenLocation(latLng)
    val r = 28f
    return queryRenderedFeatures(RectF(p.x - r, p.y - r, p.x + r, p.y + r), STOP_CLUSTER_LAYER, STOP_POINT_LAYER)
        .firstOrNull()
}

private fun createMapView(context: Context): MapView {
    MapLibre.getInstance(context)
    // TextureView invece di SurfaceView: segue spostamenti e trasformazioni di Compose senza scatti
    // (la scheda mappa scorre insieme alla barra).
    val options = MapLibreMapOptions.createFromAttributes(context).textureMode(true)
    return MapView(context, options).apply { onCreate(null) }
}
