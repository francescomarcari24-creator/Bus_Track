package com.example.bustrack.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import android.os.SystemClock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

internal fun Context.hasLocationPermission(): Boolean =
    checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

internal fun Context.isLocationEnabled(): Boolean =
    getSystemService(LocationManager::class.java)?.isLocationEnabled == true

/**
 * Posizione dell'utente con il motore di Android (niente Play Services, come la mappa).
 * Se c'è una posizione nota di meno di [maxAgeMs] usa quella (risposta immediata), altrimenti
 * ne chiede una nuova e aspetta al massimo [timeoutMs]; in mancanza ripiega sull'ultima nota.
 * Null senza permesso, con la localizzazione spenta o se non arriva nessuna posizione.
 */
@SuppressLint("MissingPermission") // permesso verificato all'inizio
internal suspend fun Context.currentLocation(maxAgeMs: Long = 120_000, timeoutMs: Long = 15_000): Location? {
    if (!hasLocationPermission()) return null
    val lm = getSystemService(LocationManager::class.java) ?: return null
    if (!lm.isLocationEnabled) return null
    val providers = listOf(LocationManager.FUSED_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        .filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
    val lastKnown = providers.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
        .maxByOrNull { it.elapsedRealtimeNanos }
    val ageMs = lastKnown?.let { (SystemClock.elapsedRealtimeNanos() - it.elapsedRealtimeNanos) / 1_000_000 }
    if (lastKnown != null && ageMs != null && ageMs <= maxAgeMs) return lastKnown
    val provider = providers.firstOrNull() ?: return lastKnown
    val fresh = withTimeoutOrNull(timeoutMs) {
        suspendCancellableCoroutine<Location?> { cont ->
            val signal = CancellationSignal()
            cont.invokeOnCancellation { signal.cancel() }
            lm.getCurrentLocation(provider, signal, mainExecutor) { location -> if (cont.isActive) cont.resume(location) }
        }
    }
    return fresh ?: lastKnown
}
