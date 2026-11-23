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
    val inPreview = LocalInBackPreview.current
    val progress = remember { Animatable(0f) }
    var swipeEdge by remember { mutableIntStateOf(BackEventCompat.EDGE_LEFT) }
    var gestureActive by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    PredictiveBackHandler(enabled = enabled && !inPreview) { events ->
        gestureActive = true
        try {
            events.collect { event ->
                swipeEdge = event.swipeEdge
                progress.snapTo(event.progress)
            }
            // Gesto confermato: porta la schermata fuori e poi cambia pagina.
            progress.animateTo(1f, tween(durationMillis = 200, easing = FastOutSlowInEasing))
            onBack()
            progress.snapTo(0f)
        } catch (e: CancellationException) {
            // Gesto annullato: la schermata rientra (in un'altra coroutine, questa è cancellata).
            scope.launch { progress.animateTo(0f, tween(durationMillis = 200)) }
            throw e
        } finally {
            gestureActive = false
        }
    }

    val showPrevious = enabled && (gestureActive || progress.value > 0f)
    Box(modifier.fillMaxSize()) {
        if (showPrevious) {
            val p = progress.value
            // L'anteprima resta ferma e a tutto schermo (uno spostamento lascerebbe scoperto un bordo).
            Box(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .drawWithContent {
                        drawContent()
                        // Velo scuro che si dissolve man mano che la pagina sopra se ne va.
                        drawRect(Color.Black.copy(alpha = 0.25f * (1f - p)))
                    }
            ) {
                CompositionLocalProvider(LocalInBackPreview provides true) { previous() }
            }
        }
        val p = progress.value
        val direction = if (swipeEdge == BackEventCompat.EDGE_RIGHT) -1f else 1f
        val corner = if (p > 0f) RoundedCornerShape(lerp(0f, 28f, (p * 4f).coerceAtMost(1f)).dp) else RectangleShape
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationX = direction * size.width * p
                    val scale = lerp(1f, 0.9f, (p * 2f).coerceAtMost(1f))
                    scaleX = scale
                    scaleY = scale
                    shadowElevation = if (p > 0f) 16.dp.toPx() else 0f
                    shape = corner
                    clip = p > 0f
                }
                .then(if (p > 0f) Modifier.clip(corner) else Modifier)
                .background(MaterialTheme.colorScheme.background)
        ) {
            content()
        }
    }
}
