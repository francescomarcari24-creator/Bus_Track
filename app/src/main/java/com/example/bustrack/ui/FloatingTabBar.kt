package com.example.bustrack.ui

import androidx.compose.foundation.background
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import androidx.compose.ui.unit.IntOffset
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.animation.core.Animatable
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.getValue
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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

/**
 * Barra di navigazione a pillola, flottante sopra il contenuto, solo testo.
 * L'indicatore segue il dito mentre si scorre e, al rilascio, si assesta sulla sezione più vicina.
 */
@Composable
fun FloatingTabBar(labels: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val currentOnSelect by rememberUpdatedState(onSelect)
    val scope = rememberCoroutineScope()
    val indicator = remember { Animatable(selected.toFloat()) }
    var dragging by remember { mutableStateOf(false) }
    var rowWidth by remember { mutableIntStateOf(0) }
    val count = labels.size

    LaunchedEffect(selected) {
        if (!dragging) indicator.animateTo(selected.toFloat())
    }
    // Sezione evidenziata nel testo: quella sotto l'indicatore mentre si trascina.
    val highlighted = if (dragging) indicator.value.roundToInt().coerceIn(0, count - 1) else selected

    fun moveTo(x: Float) {
        if (rowWidth <= 0 || count == 0) return
        val target = (x / rowWidth * count - 0.5f).coerceIn(0f, (count - 1).toFloat())
        scope.launch { indicator.snapTo(target) }
    }

    Surface(
        modifier = modifier
            .navigationBarsPadding()
            .padding(start = BarSideMargin, end = BarSideMargin, bottom = BarBottomMargin)
            .fillMaxWidth()
            .height(BarHeight),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 8.dp,
        tonalElevation = 3.dp,
    ) {
        Box(
            Modifier
                .padding(4.dp)
                .onSizeChanged { rowWidth = it.width }
                .pointerInput(count) {
                    fun settle() {
                        val target = indicator.value.roundToInt().coerceIn(0, count - 1)
                        dragging = false
                        scope.launch { indicator.animateTo(target.toFloat()) }
                        currentOnSelect(target)
                    }
                    detectHorizontalDragGestures(
                        onDragStart = { dragging = true; moveTo(it.x) },
                        onDragEnd = { settle() },
                        onDragCancel = { settle() },
                        onHorizontalDrag = { change, _ ->
                            change.consume()
                            moveTo(change.position.x)
                        },
                    )
                },
        ) {
            if (count > 0 && rowWidth > 0) {
                Box(
                    Modifier
                        .fillMaxHeight()
                        .width(with(LocalDensity.current) { (rowWidth / count).toDp() })
                        .offset { IntOffset((indicator.value * rowWidth / count).roundToInt(), 0) }
                        .background(MaterialTheme.colorScheme.secondaryContainer, CircleShape)
                )
            }
            Row(Modifier.fillMaxSize().selectableGroup(), verticalAlignment = Alignment.CenterVertically) {
                labels.forEachIndexed { i, label ->
                    val isSelected = i == highlighted
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .selectable(
                                selected = i == selected,
                                role = Role.Tab,
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = { onSelect(i) },
                            ),
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
}
