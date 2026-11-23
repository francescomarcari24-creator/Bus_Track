package com.example.bustrack.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

private val BarHeight = 48.dp
private val BarBottomMargin = 12.dp
private val BarSideMargin = 32.dp

/**
 * Spazio occupato in basso dalla barra flottante (barra + margine + barra di sistema),
 * per non far finire sotto di essa contenuti importanti (fine delle liste, schede, logo mappa).
 */
val LocalBottomBarInset = staticCompositionLocalOf { 0.dp }

@Composable
fun bottomBarInset(): Dp {
    val system = with(LocalDensity.current) { WindowInsets.navigationBars.getBottom(this).toDp() }
    return BarHeight + BarBottomMargin + system + 8.dp
}

/** Barra di navigazione flottante sopra il contenuto, solo testo, con sfondo trasparente. */
@Composable
fun FloatingTabBar(labels: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier
            .navigationBarsPadding()
            .padding(start = BarSideMargin, end = BarSideMargin, bottom = BarBottomMargin)
            .fillMaxWidth()
            .height(BarHeight),
        shape = CircleShape,
        // Sfondo trasparente: niente colore, ombra né sovrapposizione tonale.
        color = Color.Transparent,
        shadowElevation = 0.dp,
        tonalElevation = 0.dp,
    ) {
        Row(Modifier.padding(4.dp).selectableGroup(), verticalAlignment = Alignment.CenterVertically) {
            labels.forEachIndexed { i, label ->
                val isSelected = i == selected
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .then(
                            if (isSelected) Modifier.background(MaterialTheme.colorScheme.secondaryContainer, CircleShape)
                            else Modifier
                        )
                        .selectable(selected = isSelected, role = Role.Tab, onClick = { onSelect(i) }),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        color = if (isSelected) MaterialTheme.colorScheme.onSecondaryContainer
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
