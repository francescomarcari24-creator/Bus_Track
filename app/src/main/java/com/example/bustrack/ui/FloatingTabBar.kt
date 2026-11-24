package com.example.bustrack.ui

import androidx.compose.foundation.background
import androidx.compose.ui.zIndex
import kotlinx.coroutines.delay
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.CompositionLocalProvider
import kotlin.math.floor
import kotlin.math.ceil
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.key
import androidx.compose.runtime.derivedStateOf
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

/**
 * true se la scheda è sullo schermo: quella attuale, una che sta scorrendo dentro, o quella
 * che si scopre sotto durante il gesto indietro. Le schede nascoste restano composte ma non
 * devono lavorare (la mappa smette di disegnare).
 */
val LocalTabOnScreen = staticCompositionLocalOf { true }

@Composable
fun bottomBarInset(): Dp {
    val system = with(LocalDensity.current) { WindowInsets.navigationBars.getBottom(this).toDp() }
    return BarHeight + BarBottomMargin + system + 8.dp
}

/**
 * Posizione (frazionaria) dell'indicatore della barra: 0 = prima sezione, 1 = seconda, ecc.
 * È condivisa con il contenuto, che si muove insieme all'indicatore.
 */
class TabPosition(initial: Int) {
    val anim = Animatable(initial.toFloat())
    var dragging by mutableStateOf(false)
    val value: Float get() = anim.value
}

@Composable
fun rememberTabPosition(selected: Int): TabPosition {
    val position = remember { TabPosition(selected) }
    LaunchedEffect(selected) {
        if (!position.dragging) position.anim.animateTo(selected.toFloat())
    }
    return position
}

/**
 * Le schede affiancate: segue `position`, quindi durante il trascinamento sulla barra la scheda
 * si muove insieme al dito. Sono composte solo le schede visibili (al massimo due).
 */
@Composable
fun TabPager(
    position: TabPosition,
    count: Int,
    current: Int,
    gesture: BackGestureState,
    previewTab: Int?,
    content: @Composable (Int) -> Unit,
) {
    val first by remember { derivedStateOf { floor(position.value).toInt().coerceIn(0, count - 1) } }
    val last by remember { derivedStateOf { ceil(position.value).toInt().coerceIn(0, count - 1) } }
    // Le schede restano composte una volta create (fuori schermo e "in pausa"): cambiare scheda
    // non deve ricomporle da zero né ricreare la mappa, che è ciò che faceva scattare lo scorrimento.
    val ready = remember { mutableStateListOf<Int>() }
    LaunchedEffect(Unit) {
        ready.add(first)
        delay(600) // a riposo, dopo la prima schermata: prepara anche le altre
        for (i in 0 until count) if (i !in ready) { ready.add(i); delay(250) }
    }
    Box(Modifier.fillMaxSize()) {
        for (i in 0 until count) {
            if (i !in ready && i !in first..last && i != previewTab) continue
            key(i) {
                val active = i in first..last
                // Durante il back predittivo la scheda precedente si scopre sotto quella attuale:
                // è la stessa già composta (niente seconda mappa da ricaricare).
                val isPreview = i == previewTab && gesture.showing
                val isExiting = i == current && gesture.showing
                Box(
                    Modifier
                        .fillMaxSize()
                        .zIndex(if (isExiting) 1f else 0f)
                        .graphicsLayer {
                            if (isPreview) {
                                alpha = 1f
                            } else {
                                translationX = (i - position.value) * size.width
                                // Le schede lontane non si disegnano.
                                alpha = if (active) 1f else 0f
                                if (isExiting) applyBackExit(gesture)
                            }
                        }
                        .background(MaterialTheme.colorScheme.background)
                        .then(if (isPreview) Modifier.backPreviewScrim(gesture) else Modifier)
                ) {
                    // Scheda nascosta o in anteprima: nessun aggiornamento periodico né finestre di dialogo.
                    CompositionLocalProvider(
                        LocalInBackPreview provides (!active || isPreview),
                        LocalTabOnScreen provides (active || isPreview),
                    ) { content(i) }
                }
            }
        }
    }
}

/**
 * Barra di navigazione a pillola, flottante sopra il contenuto, solo testo.
 * L'indicatore segue il dito mentre si scorre e, al rilascio, si assesta sulla sezione più vicina.
 */
@Composable
fun FloatingTabBar(
    labels: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    position: TabPosition,
    modifier: Modifier = Modifier,
) {
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
        PillTabs(labels, selected, onSelect, position)
    }
}

/**
 * Selettore a pillola con aspetto e comportamento identici alla barra in basso: stessa altezza,
 * colori, ombra, indicatore e larghezza (stessi margini dai bordi dello schermo; [screenPadding]
 * è il margine che il contenitore ha già). Usato in alto nella Home per "Preferiti / Vicine".
 */
@Composable
fun PillSwitch(
    labels: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    position: TabPosition,
    modifier: Modifier = Modifier,
    screenPadding: Dp = 0.dp,
) {
    Surface(
        modifier = modifier
            .padding(horizontal = (BarSideMargin - screenPadding).coerceAtLeast(0.dp))
            .fillMaxWidth()
            .height(BarHeight),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 8.dp,
        tonalElevation = 3.dp,
    ) {
        PillTabs(labels, selected, onSelect, position)
    }
}

/**
 * Contenuto della pillola: indicatore e sezioni. Si tocca una sezione oppure si trascina
 * l'indicatore, che segue il dito e al rilascio si assesta sulla sezione più vicina.
 */
@Composable
private fun PillTabs(
    labels: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    position: TabPosition,
) {
    val currentOnSelect by rememberUpdatedState(onSelect)
    val scope = rememberCoroutineScope()
    val indicator = position.anim
    var rowWidth by remember { mutableIntStateOf(0) }
    val count = labels.size

    // Sezione evidenziata nel testo: quella sotto l'indicatore mentre si trascina.
    val highlighted by remember(count, selected) {
        derivedStateOf { if (position.dragging) indicator.value.roundToInt().coerceIn(0, count - 1) else selected }
    }

    fun moveTo(x: Float) {
        if (rowWidth <= 0 || count == 0) return
        val target = (x / rowWidth * count - 0.5f).coerceIn(0f, (count - 1).toFloat())
        scope.launch { indicator.snapTo(target) }
    }

    Box(
        Modifier
            .padding(4.dp)
            .onSizeChanged { rowWidth = it.width }
            .pointerInput(count) {
                fun settle() {
                    val target = indicator.value.roundToInt().coerceIn(0, count - 1)
                    position.dragging = false
                    scope.launch { indicator.animateTo(target.toFloat()) }
                    currentOnSelect(target)
                }
                detectHorizontalDragGestures(
                    onDragStart = { position.dragging = true; moveTo(it.x) },
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

/**
 * Pannelli affiancati che seguono `position` come le schede (vedi [TabPager]): durante il
 * trascinamento sulla pillola il contenuto si muove insieme all'indicatore.
 */
@Composable
fun SlidingPanes(
    position: TabPosition,
    count: Int,
    modifier: Modifier = Modifier,
    /** Spazio tra un pannello e l'altro durante lo scorrimento. */
    gap: Dp = 0.dp,
    content: @Composable (Int) -> Unit,
) {
    val gapPx = with(LocalDensity.current) { gap.toPx() }
    val parentOnScreen = LocalTabOnScreen.current
    Box(modifier) {
        for (i in 0 until count) {
            key(i) {
                // Pannello visibile (anche in parte, durante lo scorrimento): solo allora le sue
                // animazioni girano. derivedStateOf: si ricompone al cambio, non a ogni fotogramma.
                val paneOnScreen by remember { derivedStateOf { kotlin.math.abs(i - position.value) < 1f } }
                Box(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            translationX = (i - position.value) * (size.width + gapPx)
                            // I pannelli fuori vista non si disegnano.
                            alpha = if (kotlin.math.abs(i - position.value) < 1f) 1f else 0f
                        }
                ) {
                    CompositionLocalProvider(LocalTabOnScreen provides (parentOnScreen && paneOnScreen)) { content(i) }
                }
            }
        }
    }
}
