package com.example.bustrack.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.example.bustrack.ui.theme.CardCorner

/**
 * Riordino di un elenco tenendo premuta una riga e trascinandola (una alla volta).
 *
 * La riga trascinata si disegna dove sta il dito: posizione della riga all'inizio del trascinamento
 * più lo spostamento del dito, meno la posizione che ha ora nell'elenco ([displacement]). Così,
 * quando l'elenco la sposta a uno scambio, resta comunque sotto il dito senza scatti.
 */
class ReorderState(val listState: LazyListState) {
    var draggingKey by mutableStateOf<Any?>(null)
        internal set
    /** Posizione (nell'elenco visibile) della riga quando è iniziato il trascinamento. */
    internal var startOffset by mutableIntStateOf(0)
    /** Spostamento del dito dall'inizio del trascinamento. */
    internal var dragged by mutableFloatStateOf(0f)
    /** Dopo uno scambio: posizione nell'elenco che la riga deve avere prima di permetterne un altro. */
    internal var expectedIndex = -1

    /** Scorrimento dell'elenco bloccato mentre si trascina (il dito non fa scorrere l'elenco). */
    val scrollLocked: Boolean get() = draggingKey != null

    internal fun info(key: Any): LazyListItemInfo? = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key }

    /** Quanto disegnare spostata la riga trascinata rispetto a dove la mette l'elenco. */
    internal fun displacement(key: Any): Float {
        val now = info(key)?.offset ?: return 0f
        return startOffset + dragged - now
    }

    internal fun stop() {
        draggingKey = null
        dragged = 0f
        expectedIndex = -1
    }
}

/**
 * Rende la riga [key] riordinabile tenendola premuta e trascinandola (vibrazione all'inizio, riga
 * sollevata mentre si muove). Quando il centro della riga trascinata passa sopra un'altra riga
 * dello stesso gruppo ([groupKeys], nell'ordine attuale) si chiama [onMove] con le due posizioni
 * nel gruppo: chi la chiama riordina l'elenco.
 */
@Composable
fun Modifier.reorderable(
    state: ReorderState,
    key: Any,
    groupKeys: List<Any>,
    onMove: (from: Int, to: Int) -> Unit,
): Modifier {
    val haptic = LocalHapticFeedback.current
    val keys by rememberUpdatedState(groupKeys)
    val move by rememberUpdatedState(onMove)
    val dragging = state.draggingKey == key
    val lifted = if (dragging) {
        Modifier
            .zIndex(1f)
            .graphicsLayer {
                translationY = state.displacement(key)
                scaleX = 1.02f
                scaleY = 1.02f
            }
            .shadow(6.dp, CardCorner)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, CardCorner)
    } else {
        Modifier
    }
    return this
        .then(lifted)
        .pointerInput(key) {
            detectDragGesturesAfterLongPress(
                onDragStart = {
                    val start = state.info(key) ?: return@detectDragGesturesAfterLongPress
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    state.startOffset = start.offset
                    state.dragged = 0f
                    state.expectedIndex = -1
                    state.draggingKey = key
                },
                onDragEnd = { state.stop() },
                onDragCancel = { state.stop() },
                onDrag = { change, amount ->
                    change.consume()
                    if (state.draggingKey != key) return@detectDragGesturesAfterLongPress
                    state.dragged += amount.y
                    val current = state.info(key) ?: return@detectDragGesturesAfterLongPress
                    // Scambio precedente non ancora disegnato: si aspetta (altrimenti si tornerebbe indietro).
                    if (state.expectedIndex >= 0 && current.index != state.expectedIndex) return@detectDragGesturesAfterLongPress
                    state.expectedIndex = -1
                    // Si prende il posto della riga vicina (sopra o sotto, nello stesso gruppo) quando il
                    // bordo della riga trascinata supera la sua metà: salendo il bordo superiore,
                    // scendendo quello inferiore. Con righe di altezze diverse non si torna indietro da soli.
                    val from = keys.indexOf(key)
                    if (from < 0) return@detectDragGesturesAfterLongPress
                    val top = state.startOffset + state.dragged
                    val bottom = top + current.size
                    val visible = state.listState.layoutInfo.visibleItemsInfo
                    val above = keys.getOrNull(from - 1)?.let { k -> visible.firstOrNull { it.key == k } }
                    val below = keys.getOrNull(from + 1)?.let { k -> visible.firstOrNull { it.key == k } }
                    val target = when {
                        above != null && top < above.offset + above.size / 2f -> above
                        below != null && bottom > below.offset + below.size / 2f -> below
                        else -> return@detectDragGesturesAfterLongPress
                    }
                    val to = keys.indexOf(target.key)
                    // L'elenco resta dov'è: senza questo, spostando una riga sopra la prima visibile,
                    // l'elenco la "insegue" e scorre.
                    val keepIndex = state.listState.firstVisibleItemIndex
                    val keepOffset = state.listState.firstVisibleItemScrollOffset
                    state.expectedIndex = target.index
                    move(from, to)
                    state.listState.requestScrollToItem(keepIndex, keepOffset)
                },
            )
        }
}
