package com.example.bustrack.ui

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.example.bustrack.gtfs.RoutePattern
import com.example.bustrack.ui.theme.CardCorner
import kotlinx.coroutines.launch

/**
 * Punto da cui far nascere la prossima finestra della direzione: i bordi (coordinate di finestra)
 * della riga toccata. Vale solo se usato subito: una linea con una sola direzione non apre la
 * finestra, e il punto non deve restare per una finestra aperta più tardi da altrove.
 */
internal object DialogOrigin {
    private var rect: Rect? = null
    private var at = 0L
    private var onShown: (() -> Unit)? = null
    private var beforeClose: (suspend () -> Unit)? = null

    /**
     * [shown]: da eseguire a finestra aperta del tutto (es. chiudere il pannello da cui nasce);
     * [returning]: da eseguire chiudendola, prima che rientri nella riga (es. riaprire quel pannello).
     */
    fun set(bounds: Rect?, shown: (() -> Unit)? = null, returning: (suspend () -> Unit)? = null) {
        rect = bounds
        onShown = shown
        beforeClose = returning
        at = SystemClock.elapsedRealtime()
    }

    private fun fresh() = SystemClock.elapsedRealtime() - at < 2_000

    fun consume(): Rect? = rect?.takeIf { fresh() }.also { rect = null }

    fun takeOnShown(): (() -> Unit)? = onShown?.takeIf { fresh() }.also { onShown = null }

    fun takeBeforeClose(): (suspend () -> Unit)? = beforeClose?.takeIf { fresh() }.also { beforeClose = null }
}

/** Clic che ricorda i bordi dell'elemento: la finestra della direzione che apre nascerà da lì. */
@Composable
internal fun Modifier.clickableAsDialogOrigin(
    onShown: (() -> Unit)? = null,
    onReturn: (suspend () -> Unit)? = null,
    /** false = nessun effetto al tocco (la finestra che nasce dalla riga è già la risposta visiva). */
    ripple: Boolean = true,
    onClick: () -> Unit,
): Modifier {
    var bounds by remember { mutableStateOf<Rect?>(null) }
    val interaction = remember { MutableInteractionSource() }
    return onGloballyPositioned { bounds = it.boundsInWindow() }
        .clickable(
            interactionSource = interaction,
            indication = if (ripple) androidx.compose.foundation.LocalIndication.current else null,
        ) { DialogOrigin.set(bounds, onShown, onReturn); onClick() }
}

/**
 * Popup per scegliere la direzione della linea. Nasce dalla riga toccata (ricerche recenti,
 * risultati della ricerca) espandendosi fino alla sua forma, con lo sfondo che si scurisce, e
 * chiudendosi rientra lì; aperto da altri punti cresce dal centro. Animazione a molla, come le
 * altre dell'app.
 */
@Composable
fun DirectionDialog(choice: DirectionChoice, onPick: (RoutePattern) -> Unit, onDismiss: () -> Unit) {
    val origin = remember(choice) { DialogOrigin.consume() }
    val onShown = remember(choice) { DialogOrigin.takeOnShown() }
    val beforeClose = remember(choice) { DialogOrigin.takeBeforeClose() }
    val progress = remember(choice) { Animatable(0f) }
    val scope = rememberCoroutineScope()
    var closing by remember(choice) { mutableStateOf(false) }
    val springSpec = spring<Float>(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow)
    LaunchedEffect(choice) {
        progress.animateTo(1f, springSpec)
        // Solo a finestra aperta si chiude ciò che sta dietro (pannello, tastiera): chiuderlo prima
        // spostava tutto mentre la finestra cresceva.
        onShown?.invoke()
    }
    // Chiusura: animazione al contrario, poi la finestra sparisce davvero.
    val dismiss: () -> Unit = {
        if (!closing) {
            closing = true
            scope.launch {
                // Prima ricompare ciò da cui era nata (es. il pannello dei recenti), poi rientra lì.
                beforeClose?.invoke()
                progress.animateTo(0f, spring(stiffness = Spring.StiffnessMedium))
                onDismiss()
            }
        }
    }

    Dialog(
        onDismissRequest = dismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        // Lo sfondo scuro lo disegna l'app, animato: niente oscuramento istantaneo della finestra.
        (LocalView.current.parent as? DialogWindowProvider)?.window?.setDimAmount(0f)
        val noRipple = remember { MutableInteractionSource() }
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = progress.value.coerceIn(0f, 1f) }
                .background(Color.Black.copy(alpha = 0.45f))
                .clickable(interactionSource = noRipple, indication = null, onClick = dismiss),
            contentAlignment = Alignment.Center,
        ) {
            // Bordi finali della finestra, misurati senza trasformazioni (servono per partire dalla riga).
            var finalBounds by remember { mutableStateOf<Rect?>(null) }
            Box(
                Modifier
                    .padding(horizontal = 24.dp)
                    .fillMaxWidth()
                    .onGloballyPositioned { finalBounds = it.boundsInWindow() }
            ) {
                val corner = with(androidx.compose.ui.platform.LocalDensity.current) { 28.dp.toPx() }
                Surface(
                    // Stesso colore del pannello delle ricerche recenti: sembra la riga che si allarga.
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier
                        .fillMaxWidth()
                        .graphicsLayer {
                            // Trasformazione senza deformare: la finestra ha già la sua dimensione e se ne
                            // mostra solo un rettangolo con gli angoli arrotondati, che parte dalla riga
                            // toccata (o poco più piccolo, al centro) e si allarga fino alla forma intera.
                            val p = progress.value
                            val b = finalBounds
                            val w = size.width
                            val h = size.height
                            val start = if (origin != null && b != null) {
                                Rect(origin.left - b.left, origin.top - b.top, origin.right - b.left, origin.bottom - b.top)
                            } else {
                                Rect(w * 0.075f, h * 0.075f, w * 0.925f, h * 0.925f)
                            }
                            val visible = Rect(
                                lerp(start.left, 0f, p), lerp(start.top, 0f, p),
                                lerp(start.right, w, p), lerp(start.bottom, h, p),
                            )
                            clip = true
                            shape = object : androidx.compose.ui.graphics.Shape {
                                override fun createOutline(
                                    size: androidx.compose.ui.geometry.Size,
                                    layoutDirection: androidx.compose.ui.unit.LayoutDirection,
                                    density: androidx.compose.ui.unit.Density,
                                ) = androidx.compose.ui.graphics.Outline.Rounded(
                                    androidx.compose.ui.geometry.RoundRect(
                                        visible,
                                        androidx.compose.ui.geometry.CornerRadius(minOf(corner, visible.height / 2)),
                                    )
                                )
                            }
                            if (origin == null) alpha = p.coerceIn(0f, 1f)
                        }
                        // Un tocco sulla finestra non la chiude.
                        .clickable(interactionSource = noRipple, indication = null) {},
                ) {
                    // Il contenuto compare quando la forma è quasi completa (deformato sarebbe illeggibile).
                    Column(
                        Modifier
                            .graphicsLayer { alpha = ((progress.value - 0.45f) / 0.55f).coerceIn(0f, 1f) }
                            .padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 8.dp)
                    ) {
                        Text(
                            "Linea ${choice.route.label}: scegli la direzione",
                            style = MaterialTheme.typography.headlineSmall,
                            modifier = Modifier.padding(bottom = 16.dp),
                        )
                        // Riquadro con gli stessi angoli arrotondati del resto dell'app.
                        Column(Modifier.clip(CardCorner)) {
                            choice.patterns.forEach { pattern ->
                                AppListItem(
                                    supportingContent = pattern.fromStopName?.let { from -> { Text("da $from · ${pattern.stopIds.size} fermate") } },
                                    modifier = Modifier.clickable { if (!closing) onPick(pattern) },
                                ) {
                                    Text("→ ${pattern.headsign ?: "Direzione ${pattern.directionId}"}", fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                        TextButton(onClick = dismiss, modifier = Modifier.align(Alignment.End).padding(top = 8.dp)) {
                            Text("Annulla")
                        }
                    }
                }
            }
        }
    }
}
