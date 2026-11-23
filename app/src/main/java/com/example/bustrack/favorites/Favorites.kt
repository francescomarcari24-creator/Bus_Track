package com.example.bustrack.favorites

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Elemento preferito, salvato solo tramite id del GTFS statico (i nomi si rileggono dal database). */
sealed interface Favorite {
    /** Fermata, per stop_id. */
    data class StopFav(val stopId: String) : Favorite

    /** Linea in una direzione precisa: dalla home si apre direttamente quel verso. */
    data class LineFav(val routeId: String, val directionId: Int) : Favorite

    companion object {
        private const val SEP = '|'

        fun encode(f: Favorite): String = when (f) {
            is StopFav -> "stop$SEP${f.stopId}"
            is LineFav -> "line$SEP${f.directionId}$SEP${f.routeId}"
        }

        /** null per righe non riconosciute (es. salvate da una versione futura). */
        fun decode(raw: String): Favorite? {
            val parts = raw.split(SEP, limit = 3)
            return when (parts.firstOrNull()) {
                "stop" -> parts.getOrNull(1)?.takeIf { it.isNotEmpty() }?.let(::StopFav)
                "line" -> {
                    val direction = parts.getOrNull(1)?.toIntOrNull()
                    val route = parts.getOrNull(2)?.takeIf { it.isNotEmpty() }
                    if (direction != null && route != null) LineFav(route, direction) else null
                }
                else -> null
            }
        }
    }
}

/**
 * Preferiti salvati sul dispositivo, nell'ordine in cui sono stati aggiunti.
 * Il route_id va per ultimo nella chiave perché è l'unico campo che potrebbe contenere il separatore.
 */
class FavoritesStore(context: Context) {
    private val prefs = context.getSharedPreferences("favorites", Context.MODE_PRIVATE)
    private val _items = MutableStateFlow(load())
    val items: StateFlow<List<Favorite>> = _items.asStateFlow()

    fun contains(f: Favorite): Boolean = f in _items.value

    /** Aggiunge o rimuove; restituisce true se ora è tra i preferiti. */
    fun toggle(f: Favorite): Boolean {
        val current = _items.value
        val updated = if (f in current) current - f else current + f
        save(updated)
        _items.value = updated
        return f in updated
    }

    private fun load(): List<Favorite> =
        prefs.getString(KEY, null).orEmpty()
            .lineSequence()
            .filter { it.isNotBlank() }
            .mapNotNull(Favorite::decode)
            .distinct()
            .toList()

    private fun save(items: List<Favorite>) {
        prefs.edit().putString(KEY, items.joinToString("\n", transform = Favorite::encode)).apply()
    }

    private companion object {
        const val KEY = "items"
    }
}
