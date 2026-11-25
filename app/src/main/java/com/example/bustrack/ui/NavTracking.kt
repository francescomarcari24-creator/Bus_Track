package com.example.bustrack.ui

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlin.math.cos
import kotlin.math.sqrt

/** Punto proiettato su una spezzata: distanza (m), metri dall'inizio fino alla proiezione, lunghezza totale. */
data class Projection(val distance: Double, val along: Double, val length: Double)

/**
 * Proiezione di un punto su una spezzata, in metri (piano locale: per tratti di qualche km
 * l'errore è trascurabile). Spezzata vuota: distanza infinita.
 */
fun projectOnPolyline(lat: Double, lon: Double, plat: DoubleArray, plon: DoubleArray): Projection {
    if (plat.isEmpty()) return Projection(Double.MAX_VALUE, 0.0, 0.0)
    val ky = 110_540.0
    val kx = 111_320.0 * cos(Math.toRadians(lat))
    fun x(i: Int) = (plon[i] - lon) * kx
    fun y(i: Int) = (plat[i] - lat) * ky
    if (plat.size == 1) return Projection(sqrt(x(0) * x(0) + y(0) * y(0)), 0.0, 0.0)
    var best = Double.MAX_VALUE
    var bestAlong = 0.0
    var total = 0.0
    for (i in 0 until plat.size - 1) {
        val ax = x(i); val ay = y(i); val bx = x(i + 1); val by = y(i + 1)
        val dx = bx - ax; val dy = by - ay
        val seg = sqrt(dx * dx + dy * dy)
        // Il punto (0, 0) proiettato sul segmento a-b.
        val t = if (seg == 0.0) 0.0 else ((-ax) * dx + (-ay) * dy) / (seg * seg)
        val tc = t.coerceIn(0.0, 1.0)
        val px = ax + tc * dx; val py = ay + tc * dy
        val d = sqrt(px * px + py * py)
        if (d < best) {
            best = d
            bestAlong = total + tc * seg
        }
        total += seg
    }
    return Projection(best, bestAlong, total)
}

/** Aggiornamenti della posizione (ogni ~3 s) finché si raccolgono; nulla senza permesso o localizzazione. */
@SuppressLint("MissingPermission") // permesso verificato all'inizio
fun Context.locationUpdates(): Flow<Location> = callbackFlow {
    val lm = getSystemService(LocationManager::class.java)
    val provider = if (!hasLocationPermission() || lm == null) null else listOf(
        LocationManager.FUSED_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER,
    ).firstOrNull { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
    if (provider == null) {
        close()
        return@callbackFlow
    }
    val listener = LocationListener { trySend(it) }
    lm!!.requestLocationUpdates(provider, 3_000L, 3f, listener, Looper.getMainLooper())
    awaitClose { lm.removeUpdates(listener) }
}

/** Ultimi luoghi scelti nella scheda Nav (partenze e arrivi), dal più recente; salvati sul dispositivo. */
class NavRecents private constructor(context: Context) {
    private val prefs = context.getSharedPreferences("nav_recents", Context.MODE_PRIVATE)
    private val _items = kotlinx.coroutines.flow.MutableStateFlow(load())
    val items: kotlinx.coroutines.flow.StateFlow<List<Place>> = _items

    fun add(place: Place) {
        if (place.kind == PlaceKind.MY_LOCATION || place.kind == PlaceKind.HOME || place.kind == PlaceKind.WORK) return
        val updated = (listOf(place) + _items.value.filterNot { it.name == place.name && it.detail == place.detail }).take(MAX)
        _items.value = updated
        save(updated)
    }

    fun clear() {
        _items.value = emptyList()
        save(emptyList())
    }

    private fun load(): List<Place> = prefs.getString(KEY, null).orEmpty().lineSequence().mapNotNull { line ->
        val p = line.split('\t')
        if (p.size < 5) return@mapNotNull null
        Place(
            name = p[0],
            detail = p[1].ifEmpty { null },
            lat = p[2].toDoubleOrNull() ?: return@mapNotNull null,
            lon = p[3].toDoubleOrNull() ?: return@mapNotNull null,
            kind = runCatching { PlaceKind.valueOf(p[4]) }.getOrDefault(PlaceKind.ADDRESS),
        )
    }.toList()

    private fun save(items: List<Place>) {
        fun clean(s: String?) = s.orEmpty().replace('\t', ' ').replace('\n', ' ')
        prefs.edit().putString(
            KEY,
            items.joinToString("\n") { "${clean(it.name)}\t${clean(it.detail)}\t${it.lat}\t${it.lon}\t${it.kind.name}" },
        ).apply()
    }

    companion object {
        const val MAX = 8
        private const val KEY = "items"

        @Volatile private var instance: NavRecents? = null
        fun get(context: Context): NavRecents =
            instance ?: synchronized(this) { instance ?: NavRecents(context.applicationContext).also { instance = it } }
    }
}
