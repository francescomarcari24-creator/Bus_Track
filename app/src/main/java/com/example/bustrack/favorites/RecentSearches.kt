package com.example.bustrack.favorites

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Linea o fermata aperta dalla ricerca della Home, per riaprirla dal pannello "Ricerche recenti". */
sealed interface RecentItem {
    val id: String

    /** [label] = numero della linea, [subtitle] = nome lungo o gestore. */
    data class Line(override val id: String, val label: String, val subtitle: String?) : RecentItem

    /** [name] = nome della fermata, [code] = numero di palina. */
    data class Stop(override val id: String, val name: String, val code: String?) : RecentItem
}

/** Ultime [MAX] linee e fermate aperte dalla ricerca, dalla più recente; salvate sul dispositivo. */
class RecentSearches private constructor(context: Context) {
    private val prefs = context.getSharedPreferences("recent_searches", Context.MODE_PRIVATE)
    private val _items = MutableStateFlow(load())
    val items: StateFlow<List<RecentItem>> = _items.asStateFlow()

    fun add(item: RecentItem) {
        val updated = (listOf(item) + _items.value.filterNot { it::class == item::class && it.id == item.id }).take(MAX)
        _items.value = updated
        save(updated)
    }

    fun clear() {
        _items.value = emptyList()
        save(emptyList())
    }

    private fun load(): List<RecentItem> =
        prefs.getString(KEY, null).orEmpty().lineSequence().mapNotNull { line ->
            val p = line.split('\t')
            when (p.getOrNull(0)) {
                "L" -> RecentItem.Line(p.getOrNull(1) ?: return@mapNotNull null, p.getOrNull(2).orEmpty(), p.getOrNull(3)?.ifEmpty { null })
                "S" -> RecentItem.Stop(p.getOrNull(1) ?: return@mapNotNull null, p.getOrNull(2).orEmpty(), p.getOrNull(3)?.ifEmpty { null })
                else -> null
            }
        }.toList()

    private fun save(items: List<RecentItem>) {
        fun clean(s: String?) = s.orEmpty().replace('\t', ' ').replace('\n', ' ')
        prefs.edit().putString(
            KEY,
            items.joinToString("\n") {
                when (it) {
                    is RecentItem.Line -> "L\t${clean(it.id)}\t${clean(it.label)}\t${clean(it.subtitle)}"
                    is RecentItem.Stop -> "S\t${clean(it.id)}\t${clean(it.name)}\t${clean(it.code)}"
                }
            },
        ).apply()
    }

    companion object {
        const val MAX = 8
        private const val KEY = "items"

        @Volatile private var instance: RecentSearches? = null
        fun get(context: Context): RecentSearches =
            instance ?: synchronized(this) { instance ?: RecentSearches(context.applicationContext).also { instance = it } }
    }
}
