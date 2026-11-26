package com.example.bustrack.update

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Versione più recente pubblicata su GitHub ([url] = pagina della release, con l'APK e le note). */
data class AvailableUpdate(val version: String, val url: String)

/**
 * Controllo delle nuove versioni: l'app non è sul Play Store, quindi chiede alle Release di GitHub
 * qual è l'ultima versione pubblicata (al massimo ogni 12 ore, o su richiesta dalle impostazioni) e,
 * se è più nuova di quella installata, la propone nella Home. Nessun dato inviato oltre alla richiesta.
 */
object UpdateChecker {
    private const val LATEST = "https://api.github.com/repos/francescomarcari24-creator/Bus_Track/releases/latest"
    private const val INTERVAL_MS = 12 * 60 * 60 * 1000L
    private const val PREFS = "updates"

    private val _available = MutableStateFlow<AvailableUpdate?>(null)
    /** Aggiornamento da proporre (null = nessuno, o rimandato con "Più tardi"). */
    val available: StateFlow<AvailableUpdate?> = _available.asStateFlow()

    /** Versione installata (es. "1.0.0"). */
    fun installedVersion(context: Context): String =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"

    /**
     * Controlla se c'è una versione più nuova. [force] = subito (dalle impostazioni), altrimenti solo
     * se l'ultimo controllo ha più di 12 ore. Restituisce l'ultima versione pubblicata, null se non
     * raggiungibile.
     */
    suspend fun check(context: Context, force: Boolean = false): AvailableUpdate? = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val installed = installedVersion(context)
        val now = System.currentTimeMillis()
        val cached = prefs.getString("latest_version", null)?.let { v ->
            AvailableUpdate(v, prefs.getString("latest_url", null) ?: return@let null)
        }
        val latest = if (!force && cached != null && now - prefs.getLong("checked_at", 0) < INTERVAL_MS) {
            cached
        } else {
            fetch()?.also {
                prefs.edit()
                    .putString("latest_version", it.version)
                    .putString("latest_url", it.url)
                    .putLong("checked_at", now)
                    .apply()
            } ?: cached
        }
        val dismissed = prefs.getString("dismissed_version", null)
        _available.value = latest?.takeIf { isNewer(it.version, installed) && (force || it.version != dismissed) }
        latest
    }

    /** "Più tardi": non riproporre questa versione (la successiva sì). */
    fun dismiss(context: Context) {
        val v = _available.value?.version ?: return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("dismissed_version", v).apply()
        _available.value = null
    }

    private fun fetch(): AvailableUpdate? = runCatching {
        val conn = URL(LATEST).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("User-Agent", "BusTrack-Android")
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return@runCatching null
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            AvailableUpdate(json.getString("tag_name").removePrefix("v"), json.getString("html_url"))
        } finally {
            conn.disconnect()
        }
    }.onFailure { Log.w("BusTrack", "Controllo aggiornamenti non riuscito", it) }.getOrNull()

    /** "1.10.0" è più nuova di "1.9.2" (confronto numero per numero). */
    fun isNewer(candidate: String, installed: String): Boolean {
        val a = candidate.split('.', '-').map { it.toIntOrNull() ?: 0 }
        val b = installed.split('.', '-').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }
}
