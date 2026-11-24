package com.example.bustrack.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.example.bustrack.MainActivity
import com.example.bustrack.R
import com.example.bustrack.gtfs.TransitRepository
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Widget "prossimi passaggi" di una fermata preferita: nome, prossimi 4 arrivi (linea, destinazione,
 * orario; verde se live) e ora dell'aggiornamento. Android aggiorna i widget al massimo ogni
 * 15 minuti (vedi [StopWidgetWorker]), quindi si mostra l'orario di arrivo e non i minuti mancanti,
 * che invecchierebbero; il tasto ↻ aggiorna subito. Toccandolo si apre l'app su quella fermata.
 */
class StopWidget : GlanceAppWidget() {

    override val stateDefinition = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent { GlanceTheme { Content(currentState()) } }
    }

    @Composable
    private fun Content(prefs: Preferences) {
        val stopId = prefs[KEY_STOP_ID]
        val stopName = prefs[KEY_STOP_NAME] ?: "Fermata"
        val rows = prefs[KEY_ROWS].orEmpty().lines().filter { it.isNotBlank() }.map { it.split('\t') }
        val updatedAt = prefs[KEY_UPDATED_AT]
        val error = prefs[KEY_ERROR]
        val open = stopId?.let {
            actionStartActivity(
                Intent(androidx.glance.LocalContext.current, MainActivity::class.java)
                    .putExtra(MainActivity.EXTRA_OPEN_STOP_ID, it)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
        }
        Column(
            GlanceModifier
                .fillMaxSize()
                .appWidgetBackground()
                .cornerRadius(24.dp)
                .background(GlanceTheme.colors.widgetBackground)
                .padding(horizontal = 14.dp, vertical = 10.dp)
                .let { if (open != null) it.clickable(open) else it },
        ) {
            Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stopName,
                    maxLines = 1,
                    style = TextStyle(fontWeight = FontWeight.Bold, fontSize = 15.sp, color = GlanceTheme.colors.onSurface),
                    modifier = GlanceModifier.defaultWeight(),
                )
                Image(
                    ImageProvider(R.drawable.ic_refresh),
                    contentDescription = "Aggiorna",
                    colorFilter = androidx.glance.ColorFilter.tint(GlanceTheme.colors.primary),
                    modifier = GlanceModifier.size(32.dp).padding(4.dp).clickable(actionRunCallback<RefreshAction>()),
                )
            }
            Spacer(GlanceModifier.height(4.dp))
            when {
                stopId == null -> Message("Fermata non scelta: rimuovi e aggiungi di nuovo il widget.")
                rows.isEmpty() && error != null -> Message(error)
                rows.isEmpty() && updatedAt == null -> Message("Carico i passaggi…")
                rows.isEmpty() -> Message("Nessun passaggio nelle prossime 2 ore")
                else -> rows.take(MAX_ROWS).forEach { ArrivalRow(it) }
            }
            if (updatedAt != null) {
                Spacer(GlanceModifier.defaultWeight())
                Text(
                    "aggiornato alle " + HHMM.format(Instant.ofEpochMilli(updatedAt)) + (error?.let { " · non aggiornato" } ?: ""),
                    style = TextStyle(fontSize = 11.sp, color = GlanceTheme.colors.onSurfaceVariant),
                )
            }
        }
    }

    @Composable
    private fun Message(text: String) {
        Text(text, style = TextStyle(fontSize = 13.sp, color = GlanceTheme.colors.onSurfaceVariant))
    }

    /** Riga "badge linea · destinazione · orario"; [cells] = etichetta, destinazione, epoch secondi, live (1/0). */
    @Composable
    private fun ArrivalRow(cells: List<String>) {
        val label = cells.getOrNull(0).orEmpty()
        val headsign = cells.getOrNull(1).orEmpty().ifEmpty { "—" }
        val time = cells.getOrNull(2)?.toLongOrNull()
        val live = cells.getOrNull(3) == "1"
        Row(GlanceModifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                GlanceModifier
                    .cornerRadius(6.dp)
                    .background(if (live) LIVE else GlanceTheme.colors.primary)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
                Text(
                    label,
                    maxLines = 1,
                    style = TextStyle(
                        fontWeight = FontWeight.Bold, fontSize = 13.sp,
                        color = if (live) ON_LIVE else GlanceTheme.colors.onPrimary,
                    ),
                )
            }
            Spacer(GlanceModifier.width(8.dp))
            Text(
                headsign,
                maxLines = 1,
                style = TextStyle(fontSize = 13.sp, color = GlanceTheme.colors.onSurface),
                modifier = GlanceModifier.defaultWeight(),
            )
            Spacer(GlanceModifier.width(8.dp))
            Text(
                time?.let { HHMM.format(Instant.ofEpochSecond(it)) }.orEmpty(),
                style = TextStyle(
                    fontWeight = if (live) FontWeight.Bold else FontWeight.Normal, fontSize = 14.sp,
                    color = if (live) LIVE_TEXT else GlanceTheme.colors.onSurfaceVariant,
                ),
            )
        }
    }

    companion object {
        val KEY_STOP_ID = stringPreferencesKey("stop_id")
        val KEY_STOP_NAME = stringPreferencesKey("stop_name")
        /** Passaggi, una riga ciascuno: etichetta \t destinazione \t epoch secondi \t live. */
        val KEY_ROWS = stringPreferencesKey("rows")
        val KEY_UPDATED_AT = longPreferencesKey("updated_at")
        val KEY_ERROR = stringPreferencesKey("error")
        private const val MAX_ROWS = 4
        private val HHMM = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.of("Europe/Rome"))

        // Stessi verdi "live" dell'app (theme/LiveColors.kt), chiari e scuri.
        private val LIVE = ColorProvider(day = Color(0xFF17803A), night = Color(0xFF5FD889))
        private val ON_LIVE = ColorProvider(day = Color.White, night = Color(0xFF00391A))
        private val LIVE_TEXT = ColorProvider(day = Color(0xFF17803A), night = Color(0xFF5FD889))

        /** Scarica i passaggi e aggiorna un widget (o tutti, se [only] è null). */
        suspend fun refresh(context: Context, only: GlanceId? = null) {
            val manager = GlanceAppWidgetManager(context)
            val ids = only?.let { listOf(it) } ?: manager.getGlanceIds(StopWidget::class.java)
            if (ids.isEmpty()) return
            val repo = TransitRepository(context.applicationContext)
            for (id in ids) {
                val prefs = getAppWidgetState(context, PreferencesGlanceStateDefinition, id)
                val stopId = prefs[KEY_STOP_ID] ?: continue
                val result = runCatching { repo.arrivalsAt(stopId) }
                updateAppWidgetState(context, PreferencesGlanceStateDefinition, id) { p ->
                    p.toMutablePreferences().apply {
                        result.onSuccess { r ->
                            this[KEY_ROWS] = r.arrivals.take(MAX_ROWS).joinToString("\n") { a ->
                                listOf(a.routeLabel, a.headsign.orEmpty().replace('\t', ' '), a.time, if (a.isRealtime) 1 else 0)
                                    .joinToString("\t")
                            }
                            this[KEY_UPDATED_AT] = System.currentTimeMillis()
                            remove(KEY_ERROR)
                        }.onFailure {
                            this[KEY_ERROR] = "Passaggi non disponibili"
                        }
                    }
                }
                StopWidget().update(context, id)
            }
        }
    }
}

/** Tasto ↻ del widget: aggiorna subito quel widget. */
class RefreshAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        StopWidget.refresh(context, glanceId)
    }
}

class StopWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = StopWidget()

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        StopWidgetWorker.schedule(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        StopWidgetWorker.cancel(context)
    }
}
