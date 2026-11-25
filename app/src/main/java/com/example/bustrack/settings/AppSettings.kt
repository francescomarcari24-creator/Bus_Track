package com.example.bustrack.settings

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Impostazioni dell'app (schermata Impostazioni), salvate nelle preferenze "settings" e lette
 * da chi ne ha bisogno: fermate vicine, avviso di arrivo, aggiornamento degli orari.
 */
class AppSettings private constructor(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _nearbyRadiusM = MutableStateFlow(prefs.getInt(KEY_RADIUS, DEFAULT_RADIUS_M))
    /** Raggio della ricerca delle fermate vicine, in metri. */
    val nearbyRadiusM: StateFlow<Int> = _nearbyRadiusM.asStateFlow()

    private val _watchThresholds = MutableStateFlow(
        prefs.getString(KEY_THRESHOLDS, null)?.split(',')?.mapNotNull { it.toIntOrNull() }?.sorted()
            ?.takeIf { it.isNotEmpty() } ?: DEFAULT_THRESHOLDS
    )
    /** Minuti di preavviso proposti per l'avviso di arrivo. */
    val watchThresholds: StateFlow<List<Int>> = _watchThresholds.asStateFlow()

    private val _wifiOnly = MutableStateFlow(prefs.getBoolean(KEY_WIFI_ONLY, true))
    /** Nuovi orari (48 MB) solo in Wi-Fi, salvo dati in scadenza. */
    val gtfsWifiOnly: StateFlow<Boolean> = _wifiOnly.asStateFlow()

    private val _favoriteAlerts = MutableStateFlow(prefs.getBoolean(KEY_FAVORITE_ALERTS, true))
    /** Notifiche dei nuovi avvisi su linee e fermate preferite. */
    val favoriteAlerts: StateFlow<Boolean> = _favoriteAlerts.asStateFlow()

    private val _routeOptions = MutableStateFlow(
        com.example.bustrack.routing.PlanOptions(
            lessWalking = prefs.getBoolean(KEY_ROUTE_LESS_WALK, false),
            lessTransfers = prefs.getBoolean(KEY_ROUTE_LESS_TRANSFERS, false),
            noMetro = prefs.getBoolean(KEY_ROUTE_NO_METRO, false),
            noTram = prefs.getBoolean(KEY_ROUTE_NO_TRAM, false),
        )
    )
    /** Preferenze sul percorso della scheda Nav. */
    val routeOptions: StateFlow<com.example.bustrack.routing.PlanOptions> = _routeOptions.asStateFlow()

    fun setRouteOptions(value: com.example.bustrack.routing.PlanOptions) {
        _routeOptions.value = value
        prefs.edit()
            .putBoolean(KEY_ROUTE_LESS_WALK, value.lessWalking)
            .putBoolean(KEY_ROUTE_LESS_TRANSFERS, value.lessTransfers)
            .putBoolean(KEY_ROUTE_NO_METRO, value.noMetro)
            .putBoolean(KEY_ROUTE_NO_TRAM, value.noTram)
            .apply()
    }

    fun setFavoriteAlerts(value: Boolean) {
        _favoriteAlerts.value = value
        prefs.edit().putBoolean(KEY_FAVORITE_ALERTS, value).apply()
    }

    fun setNearbyRadius(meters: Int) {
        _nearbyRadiusM.value = meters
        prefs.edit().putInt(KEY_RADIUS, meters).apply()
    }

    /** Attiva o disattiva una soglia; almeno una resta sempre attiva. */
    fun toggleThreshold(minutes: Int) {
        val current = _watchThresholds.value
        val next = if (minutes in current) current - minutes else (current + minutes).sorted()
        if (next.isEmpty()) return
        _watchThresholds.value = next
        prefs.edit().putString(KEY_THRESHOLDS, next.joinToString(",")).apply()
    }

    fun setGtfsWifiOnly(value: Boolean) {
        _wifiOnly.value = value
        prefs.edit().putBoolean(KEY_WIFI_ONLY, value).apply()
    }

    companion object {
        private const val KEY_ROUTE_LESS_WALK = "route_less_walk"
        private const val KEY_ROUTE_LESS_TRANSFERS = "route_less_transfers"
        private const val KEY_ROUTE_NO_METRO = "route_no_metro"
        private const val KEY_ROUTE_NO_TRAM = "route_no_tram"
        const val DEFAULT_RADIUS_M = 1000
        val RADIUS_OPTIONS = listOf(500, 1000, 1500, 2000)
        val DEFAULT_THRESHOLDS = listOf(1, 3, 5, 10)
        val THRESHOLD_OPTIONS = listOf(1, 2, 3, 5, 7, 10, 15)
        private const val KEY_RADIUS = "nearby_radius_m"
        private const val KEY_THRESHOLDS = "watch_thresholds"
        private const val KEY_WIFI_ONLY = "gtfs_wifi_only"
        private const val KEY_FAVORITE_ALERTS = "favorite_alerts"

        @Volatile private var instance: AppSettings? = null
        fun get(context: Context): AppSettings =
            instance ?: synchronized(this) { instance ?: AppSettings(context.applicationContext).also { instance = it } }

        /** "500 m", "1 km", "1,5 km". */
        fun formatDistance(meters: Int): String =
            if (meters < 1000) "$meters m"
            else (meters / 1000.0).let { km -> if (km % 1.0 == 0.0) "${km.toInt()} km" else "%.1f km".format(java.util.Locale.ITALY, km) }
    }
}
