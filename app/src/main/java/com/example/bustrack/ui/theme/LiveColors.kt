package com.example.bustrack.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Verde riservato a tutto ciò che arriva dal tracciamento live (GTFS-Realtime).
 * È fisso e non segue i colori dinamici di Android: deve restare riconoscibile ovunque.
 * Gli orari programmati e i dati statici usano invece i colori normali del tema.
 */
@Immutable
data class LiveColors(
    /** Testo, pallini e icone live su sfondo normale. */
    val live: Color,
    /** Testo sopra [live] (es. badge della linea). */
    val onLive: Color,
    /** Sfondo per evidenziare righe e schede live. */
    val liveContainer: Color,
    /** Testo sopra [liveContainer]. */
    val onLiveContainer: Color,
)

// Contrasto verificato ≥ 4.5:1 per il testo nelle combinazioni d'uso.
val LightLiveColors = LiveColors(
    live = Color(0xFF17803A),
    onLive = Color.White,
    liveContainer = Color(0xFFD3F3DC),
    onLiveContainer = Color(0xFF0A4A20),
)

val DarkLiveColors = LiveColors(
    live = Color(0xFF5FD889),
    onLive = Color(0xFF00391A),
    liveContainer = Color(0xFF14522B),
    onLiveContainer = Color(0xFFC9F5D6),
)

val LocalLiveColors = staticCompositionLocalOf { LightLiveColors }

/** Accesso comodo: `LiveTheme.colors.live`. */
object LiveTheme {
    val colors: LiveColors
        @Composable @ReadOnlyComposable get() = LocalLiveColors.current
}
