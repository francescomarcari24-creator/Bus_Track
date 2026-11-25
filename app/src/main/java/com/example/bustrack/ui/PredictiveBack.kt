package com.example.bustrack.ui

import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * true dentro l'anteprima della schermata precedente mostrata durante il gesto indietro:
 * lì niente gestori del back, popup o aggiornamenti automatici, è solo un'immagine "viva".
 */
val LocalInBackPreview = staticCompositionLocalOf { false }

/**
 * Indietro animato come il gesto di Android (la pagina scorre verso destra e scopre la
 * precedente), per i tasti "←" delle pagine dentro un [PredictiveBackLayer]. Null fuori da un
 * livello con una pagina precedente: lì il tasto fa il suo lavoro senza animazione.
 */
val LocalAnimatedBack = staticCompositionLocalOf<(() -> Unit)?> { null }

/** Stato del gesto indietro predittivo: avanzamento (0..1), lato da cui parte e se è in corso. */
class BackGestureState {
    val progress = Animatable(0f)
    var edge by mutableIntStateOf(BackEventCompat.EDGE_LEFT)
    var active by mutableStateOf(false)

    /** true mentre c'è qualcosa da mostrare: gesto in corso o animazione di chiusura. */
    val showing: Boolean get() = active || progress.value > 0f
}

/**
 * Registra il gestore del back predittivo e ne espone lo stato. Chi disegna ([PredictiveBackLayer],
 * [TabPager]) decide come mostrarlo. Il gestore registrato dopo ha la precedenza su quelli registrati prima.
 */
@Composable
fun rememberBackGesture(enabled: Boolean, onBack: () -> Unit): BackGestureState {
    val inPreview = LocalInBackPreview.current
    val state = remember { BackGestureState() }
    val scope = rememberCoroutineScope()
    val currentOnBack by rememberUpdatedState(onBack)
    PredictiveBackHandler(enabled = enabled && !inPreview) { events ->
        state.active = true
        try {
            events.collect { event ->
                state.edge = event.swipeEdge
                state.progress.snapTo(event.progress)
            }
            // Gesto confermato: porta la schermata fuori e poi cambia pagina.
            state.progress.animateTo(1f, tween(durationMillis = 200, easing = FastOutSlowInEasing))
            currentOnBack()
            state.progress.snapTo(0f)
        } catch (e: CancellationException) {
            // Gesto annullato: la schermata rientra (in un'altra coroutine, questa è cancellata).
            scope.launch { state.progress.animateTo(0f, tween(durationMillis = 200)) }
            throw e
        } finally {
            state.active = false
        }
    }
    return state
}

/** Trasformazione della pagina che se ne va durante il back: scorre via, si rimpicciolisce, angoli e ombra. */
fun GraphicsLayerScope.applyBackExit(gesture: BackGestureState) {
    val p = gesture.progress.value
    val direction = if (gesture.edge == BackEventCompat.EDGE_RIGHT) -1f else 1f
    translationX += direction * size.width * p
    val scale = lerp(1f, 0.9f, (p * 2f).coerceAtMost(1f))
    scaleX = scale
    scaleY = scale
    shadowElevation = if (p > 0f) 16.dp.toPx() else 0f
    shape = if (p > 0f) RoundedCornerShape(lerp(0f, 28f, (p * 4f).coerceAtMost(1f)).dp) else RectangleShape
    clip = p > 0f
}

/** Velo scuro sull'anteprima, che si dissolve man mano che la pagina sopra se ne va. */
fun Modifier.backPreviewScrim(gesture: BackGestureState): Modifier = drawWithContent {
    drawContent()
    drawRect(Color.Black.copy(alpha = 0.25f * (1f - gesture.progress.value)))
}

/**
 * Gesto indietro predittivo di Android: trascinando dal bordo la schermata attuale scorre
 * verso il lato opposto e si rimpicciolisce, scoprendo sotto l'anteprima di [previous].
 * Rilasciando si completa l'animazione e si chiama [onBack]; annullando il gesto torna al suo posto.
 * Con la navigazione a tre tasti (nessun trascinamento) esegue la stessa animazione, più rapida.
 */
@Composable
fun PredictiveBackLayer(
    enabled: Boolean,
    onBack: () -> Unit,
    previous: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val gesture = rememberBackGesture(enabled, onBack)
    val scope = rememberCoroutineScope()
    val currentOnBack by rememberUpdatedState(onBack)
    // Tasto "←": stessa animazione del gesto confermato, dal bordo sinistro (la pagina va a destra).
    val animatedBack: () -> Unit = remember(gesture) {
        {
            if (!gesture.active) {
                scope.launch {
                    gesture.active = true
                    gesture.edge = BackEventCompat.EDGE_LEFT
                    try {
                        gesture.progress.animateTo(1f, tween(durationMillis = 320, easing = FastOutSlowInEasing))
                        currentOnBack()
                        gesture.progress.snapTo(0f)
                    } finally {
                        gesture.active = false
                    }
                }
            }
        }
    }
    Box(modifier.fillMaxSize()) {
        if (enabled && gesture.showing) {
            // L'anteprima resta ferma e a tutto schermo (uno spostamento lascerebbe scoperto un bordo).
            Box(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .backPreviewScrim(gesture)
            ) {
                CompositionLocalProvider(LocalInBackPreview provides true) { previous() }
            }
        }
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { applyBackExit(gesture) }
                .background(MaterialTheme.colorScheme.background)
        ) {
            CompositionLocalProvider(LocalAnimatedBack provides if (enabled) animatedBack else null) { content() }
        }
    }
}
