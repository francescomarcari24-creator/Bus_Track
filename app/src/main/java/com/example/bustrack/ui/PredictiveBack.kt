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

/** Aspetta [n] fotogrammi (per far partire un'animazione dopo un fotogramma pesante). */
suspend fun awaitFrames(n: Int) {
    repeat(n) { androidx.compose.runtime.withFrameNanos { } }
}

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
 * Pila di pagine con gesto indietro predittivo e animazioni di apertura/chiusura. Ogni pagina è un
 * elemento stabile (chiave = la pagina): aprendone una nuova le altre restano costruite, tornando
 * indietro quella sotto è già pronta. Così niente pagine ricostruite da zero durante le animazioni
 * (erano la causa di micro-scatti e di elementi che lampeggiavano, es. la pillola in Home).
 * Solo la pagina in cima è "viva"; le altre hanno [LocalInBackPreview] = true e non si disegnano,
 * tranne quella subito sotto mentre un'animazione la scopre.
 */
@Composable
fun <T : Any> PageStack(
    pages: List<T>,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    page: @Composable (T) -> Unit,
) {
    val enabled = pages.size > 1
    val gesture = rememberBackGesture(enabled, onBack)
    val scope = rememberCoroutineScope()
    val currentOnBack by rememberUpdatedState(onBack)
    val inPreview = LocalInBackPreview.current
    // Apertura: la pagina nuova parte fuori schermo già dal primo fotogramma (vedi settledDepth).
    val enter = remember { Animatable(1f) }
    val depth = pages.size
    var settledDepth by remember { mutableIntStateOf(depth) }
    val opening = depth > settledDepth && enabled && !inPreview
    androidx.compose.runtime.LaunchedEffect(depth) {
        if (depth > settledDepth && enabled && !inPreview) {
            enter.snapTo(0f)
            settledDepth = depth
            // Il primo fotogramma costruisce e disegna la pagina nuova (pesante, ~50 ms): lo si lascia
            // passare a pagina ancora fuori schermo e si parte dopo, così lo scorrimento non salta.
            awaitFrames(2)
            enter.animateTo(1f, tween(durationMillis = TransitViewModel.PAGE_OPEN_MS.toInt(), easing = FastOutSlowInEasing))
        } else {
            settledDepth = depth
            enter.snapTo(1f)
        }
    }
    val enterProgress = { if (opening) 0f else enter.value }
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
    val background = MaterialTheme.colorScheme.background
    val parentOnScreen = LocalTabOnScreen.current
    Box(modifier.fillMaxSize()) {
        pages.forEachIndexed { i, p ->
            androidx.compose.runtime.key(p) {
                val isTop = i == pages.lastIndex
                val isBelow = i == pages.lastIndex - 1
                Box(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            val entering = opening || enter.value < 1f
                            when {
                                isTop -> {
                                    applyBackExit(gesture)
                                    if (entering) {
                                        translationX += (1f - enterProgress()) * size.width
                                        shadowElevation = 16.dp.toPx()
                                    }
                                }
                                // Quella sotto si vede solo mentre un'animazione la scopre.
                                isBelow && (gesture.showing || entering) -> {
                                    alpha = 1f
                                    if (entering) translationX = -0.2f * size.width * enterProgress()
                                }
                                // Fuori schermo, non solo trasparente: così non riceve i tocchi caduti su
                                // zone vuote della pagina sopra.
                                else -> {
                                    alpha = 0f
                                    translationX = 3f * size.width
                                }
                            }
                        }
                        .background(background)
                        .then(
                            if (isBelow) Modifier.drawWithContent {
                                drawContent()
                                val entering = opening || enter.value < 1f
                                val shade = if (entering) 0.25f * enterProgress() else 0.25f * (1f - gesture.progress.value)
                                drawRect(Color.Black.copy(alpha = shade))
                            } else Modifier
                        )
                ) {
                    CompositionLocalProvider(
                        LocalInBackPreview provides (!isTop || inPreview),
                        // Le pagine sotto non sono sullo schermo: le loro rotelline restano ferme
                        // (girando farebbero ridisegnare l'app di continuo).
                        LocalTabOnScreen provides (isTop && parentOnScreen),
                        LocalAnimatedBack provides if (isTop && enabled) animatedBack else null,
                    ) { page(p) }
                }
            }
        }
    }
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
    /** Numero di pagine aperte: quando cresce, la nuova pagina entra da destra sopra la precedente. */
    depth: Int = 0,
    content: @Composable () -> Unit,
) {
    val gesture = rememberBackGesture(enabled, onBack)
    // Apertura di una pagina: scorre da destra a sinistra (1 = al suo posto), speculare al back.
    val enter = remember { Animatable(1f) }
    // Profondità per cui l'animazione è già partita. Se quella attuale è maggiore la pagina è appena
    // stata aperta e va considerata fuori schermo già da questo fotogramma: l'effetto sotto parte
    // solo dopo, e senza questo la nuova pagina compariva un istante al suo posto prima di scorrere.
    var settledDepth by remember { mutableIntStateOf(depth) }
    val inPreview = LocalInBackPreview.current
    val opening = depth > settledDepth && enabled && !inPreview
    androidx.compose.runtime.LaunchedEffect(depth) {
        if (depth > settledDepth && enabled && !inPreview) {
            enter.snapTo(0f)
            settledDepth = depth
            // Stessa durata dell'attesa prima del caricamento dei dati (TransitViewModel.PAGE_OPEN_MS).
            enter.animateTo(1f, tween(durationMillis = TransitViewModel.PAGE_OPEN_MS.toInt(), easing = FastOutSlowInEasing))
        } else {
            settledDepth = depth
            enter.snapTo(1f)
        }
    }
    val enterProgress = { if (opening) 0f else enter.value }
    val entering = opening || enter.value < 1f
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
        if (enabled && (gesture.showing || entering)) {
            // L'anteprima resta ferma e a tutto schermo durante il back (uno spostamento lascerebbe
            // scoperto un bordo); in apertura si sposta un poco a sinistra e si scurisce.
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { if (entering) translationX = -0.2f * size.width * enterProgress() }
                    .background(MaterialTheme.colorScheme.background)
                    .then(
                        if (entering) Modifier.drawWithContent {
                            drawContent()
                            drawRect(Color.Black.copy(alpha = 0.25f * enterProgress()))
                        } else Modifier.backPreviewScrim(gesture)
                    )
            ) {
                CompositionLocalProvider(LocalInBackPreview provides true) { previous() }
            }
        }
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    applyBackExit(gesture)
                    if (entering) {
                        translationX += (1f - enterProgress()) * size.width
                        shadowElevation = 16.dp.toPx()
                    }
                }
                .background(MaterialTheme.colorScheme.background)
        ) {
            CompositionLocalProvider(LocalAnimatedBack provides if (enabled) animatedBack else null) { content() }
        }
    }
}
