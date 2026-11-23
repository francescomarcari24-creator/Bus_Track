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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.example.bustrack.ui.theme.LiveTheme
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.bustrack.gtfs.VehiclePosition
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
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

/** Mappa con un punto e il numero di linea per ogni mezzo; tocca un mezzo per i dettagli. */
@Composable
fun VehicleMap(vehicles: List<VehiclePosition>, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    // Tutti i mezzi sulla mappa sono tracciati live: verde.
    val dotColor = LiveTheme.colors.live.toArgb()
    var style by remember { mutableStateOf<Style?>(null) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    val mapView = remember { createMapView(context) }
    val memory = LocalScrollMemory.current
    val inPreview = LocalInBackPreview.current
    val barInsetPx = with(androidx.compose.ui.platform.LocalDensity.current) { LocalBottomBarInset.current.toPx() }

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
                selectedId = map.vehicleAt(latLng)
                true
            }
        }
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

    // Con la scheda di un mezzo aperta, la back gesture la chiude invece di cambiare schermata.
    BackHandler(enabled = selectedId != null && !inPreview) { selectedId = null }

    Box(modifier.clipToBounds()) {
        AndroidView(factory = { mapView })
        vehicles.firstOrNull { it.vehicleId == selectedId }?.let { v ->
            // Sopra la barra flottante.
            VehicleCard(v, Modifier.align(Alignment.BottomCenter).padding(12.dp).padding(bottom = LocalBottomBarInset.current))
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

private fun createMapView(context: Context): MapView {
    MapLibre.getInstance(context)
    return MapView(context).apply { onCreate(null) }
}
