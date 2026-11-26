package com.example.bustrack.widget

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
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
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.background
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
import com.example.bustrack.gtfs.distanceMeters
import com.example.bustrack.navigation.NavShortcuts
import com.example.bustrack.settings.AppSettings
import com.example.bustrack.ui.LegView
import com.example.bustrack.ui.NavigationModel
import com.example.bustrack.ui.Place
import com.example.bustrack.ui.PlaceKind
import com.example.bustrack.ui.currentLocation
import com.example.bustrack.ui.lastSavedLocation
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Widget "Verso casa": il prossimo percorso consigliato verso Casa, oppure verso Lavoro se si è
 * entro 500 m da casa (come le pillole della Home). Mostra a che ora partire e arrivare e i tratti
 * (a piedi, linee); toccandolo si apre la scheda Nav con i percorsi. Si aggiorna con i widget delle
 * fermate (ogni 15 minuti) e col tasto ↻. Ad app chiusa Android può negare la posizione: si usa
 * allora l'ultima trovata dall'app, indicandone l'ora.
 */
class NavWidget : GlanceAppWidget() {

    override val stateDefinition = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent { GlanceTheme { Content(currentState()) } }
    }

    @Composable
    private fun Content(prefs: Preferences) {
        val target = prefs[KEY_TARGET]
        val home = target != NavShortcuts.WORK
        val error = prefs[KEY_ERROR]
        val refreshing = prefs[KEY_REFRESHING_SINCE]?.let { System.currentTimeMillis() - it < 60_000 } == true
        val open = actionStartActivity(
            Intent(androidx.glance.LocalContext.current, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_NAV_TO, target ?: NavShortcuts.HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
        Column(
            GlanceModifier
                .fillMaxSize()
                .appWidgetBackground()
                .cornerRadius(24.dp)
                .background(GlanceTheme.colors.widgetBackground)
                .padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 10.dp),
        ) {
            Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Row(GlanceModifier.defaultWeight().clickable(open), verticalAlignment = Alignment.CenterVertically) {
                    Image(
                        ImageProvider(if (home) R.drawable.ic_shortcut_home else R.drawable.ic_shortcut_work),
                        contentDescription = null,
                        modifier = GlanceModifier.size(26.dp),
                    )
                    Spacer(GlanceModifier.width(8.dp))
                    Text(
                        if (target == null) "Verso casa" else if (home) "Verso casa" else "Verso il lavoro",
                        maxLines = 1,
                        style = TextStyle(fontWeight = FontWeight.Bold, fontSize = 15.sp, color = GlanceTheme.colors.onSurface),
                    )
                }
                Box(
                    GlanceModifier.size(48.dp).cornerRadius(24.dp).clickable(actionRunCallback<NavWidgetRefresh>()),
                    contentAlignment = Alignment.Center,
                ) {
                    if (refreshing) {
                        androidx.glance.appwidget.CircularProgressIndicator(
                            modifier = GlanceModifier.size(22.dp),
                            color = GlanceTheme.colors.primary,
                        )
                    } else {
                        Image(
                            ImageProvider(R.drawable.ic_refresh),
                            contentDescription = "Aggiorna",
                            colorFilter = androidx.glance.ColorFilter.tint(GlanceTheme.colors.primary),
                            modifier = GlanceModifier.size(24.dp),
                        )
                    }
                }
            }
            Column(GlanceModifier.fillMaxWidth().defaultWeight().padding(end = 10.dp).clickable(open)) {
                val dep = prefs[KEY_DEPARTURE]
                val arr = prefs[KEY_ARRIVAL]
                when {
                    error != null -> Message(error)
                    dep == null || arr == null -> Message("Calcolo il percorso…")
                    else -> {
                        Text(
                            "Parti alle ${HHMM.format(Instant.ofEpochSecond(dep))}",
                            style = TextStyle(fontWeight = FontWeight.Bold, fontSize = 18.sp, color = GlanceTheme.colors.primary),
                        )
                        Text(
                            "Arrivo alle ${HHMM.format(Instant.ofEpochSecond(arr))}",
                            style = TextStyle(fontSize = 13.sp, color = GlanceTheme.colors.onSurface),
                        )
                        Spacer(GlanceModifier.height(2.dp))
                        Text(
                            prefs[KEY_LEGS].orEmpty(),
                            maxLines = 2,
                            style = TextStyle(fontSize = 13.sp, color = GlanceTheme.colors.onSurface),
                        )
                    }
                }
                Spacer(GlanceModifier.defaultWeight())
                prefs[KEY_UPDATED_AT]?.let { at ->
                    val from = prefs[KEY_FROM_AT]?.takeIf { at - it > 10 * 60_000 }
                    Text(
                        listOfNotNull(
                            "aggiornato alle " + HHMM.format(Instant.ofEpochMilli(at)),
                            from?.let { "dalla posizione delle " + HHMM.format(Instant.ofEpochMilli(it)) },
                        ).joinToString(" · "),
                        maxLines = 1,
                        style = TextStyle(fontSize = 11.sp, color = GlanceTheme.colors.onSurfaceVariant),
                    )
                }
            }
        }
    }

    @Composable
    private fun Message(text: String) {
        Text(text, style = TextStyle(fontSize = 13.sp, color = GlanceTheme.colors.onSurfaceVariant))
    }

    companion object {
        val KEY_TARGET = stringPreferencesKey("target")
        val KEY_DEPARTURE = longPreferencesKey("departure")
        val KEY_ARRIVAL = longPreferencesKey("arrival")
        val KEY_LEGS = stringPreferencesKey("legs")
        val KEY_UPDATED_AT = longPreferencesKey("updated_at")
        val KEY_FROM_AT = longPreferencesKey("from_at")
        val KEY_ERROR = stringPreferencesKey("error")
        val KEY_REFRESHING_SINCE = longPreferencesKey("refreshing_since")
        private val HHMM = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.of("Europe/Rome"))
        /** Entro questa distanza da casa si propone il lavoro (come le pillole della Home). */
        private const val NEAR_M = 500.0

        /** Ricalcola il percorso di tutti i widget "Verso casa". */
        suspend fun refresh(context: Context) {
            val ids = GlanceAppWidgetManager(context).getGlanceIds(NavWidget::class.java)
            if (ids.isEmpty()) return
            val result = compute(context)
            for (id in ids) {
                updateAppWidgetState(context, PreferencesGlanceStateDefinition, id) { p ->
                    p.toMutablePreferences().apply {
                        remove(KEY_REFRESHING_SINCE)
                        when (result) {
                            is Result.Error -> {
                                this[KEY_ERROR] = result.message
                                result.target?.let { this[KEY_TARGET] = it }
                            }
                            is Result.Ok -> {
                                remove(KEY_ERROR)
                                this[KEY_TARGET] = result.target
                                this[KEY_DEPARTURE] = result.departure
                                this[KEY_ARRIVAL] = result.arrival
                                this[KEY_LEGS] = result.legs
                                this[KEY_FROM_AT] = result.fromAt
                                this[KEY_UPDATED_AT] = System.currentTimeMillis()
                            }
                        }
                    }
                }
                NavWidget().update(context, id)
            }
        }

        private sealed interface Result {
            data class Ok(val target: String, val departure: Long, val arrival: Long, val legs: String, val fromAt: Long) : Result
            data class Error(val message: String, val target: String? = null) : Result
        }

        private suspend fun compute(context: Context): Result {
            val settings = AppSettings.get(context)
            val home = settings.homePlace.value
            val work = settings.workPlace.value
            if (home == null && work == null) return Result.Error("Imposta Casa e Lavoro nelle impostazioni dell'app")
            // Posizione attuale se Android la concede, altrimenti l'ultima trovata dall'app.
            val fresh = runCatching { context.currentLocation(maxAgeMs = 10 * 60_000, timeoutMs = 8_000) }.getOrNull()
            val here = fresh?.let { Triple(it.latitude, it.longitude, System.currentTimeMillis()) }
                ?: context.lastSavedLocation()?.let { Triple(it.lat, it.lon, it.timeMs) }
                ?: return Result.Error("Apri l'app una volta per trovare la tua posizione")
            val (lat, lon, at) = here
            val nearHome = home != null && distanceMeters(lat, lon, home.lat, home.lon) <= NEAR_M
            val (id, place) = when {
                home != null && !nearHome -> NavShortcuts.HOME to home
                work != null -> NavShortcuts.WORK to work
                else -> return Result.Error("Sei a casa", NavShortcuts.HOME)
            }
            if (distanceMeters(lat, lon, place.lat, place.lon) <= NEAR_M) {
                return Result.Error(if (id == NavShortcuts.HOME) "Sei a casa" else "Sei al lavoro", id)
            }
            val nav = NavigationModel.get(context.applicationContext as Application)
            val origin = Place("La tua posizione", null, lat, lon, PlaceKind.MY_LOCATION)
            val dest = place.copy(
                name = if (id == NavShortcuts.HOME) "Casa" else "Lavoro",
                detail = place.name,
                kind = if (id == NavShortcuts.HOME) PlaceKind.HOME else PlaceKind.WORK,
            )
            val journey = runCatching { nav.bestJourney(origin, dest) }.getOrNull()
                ?: return Result.Error("Nessun percorso trovato adesso", id)
            val legs = journey.legs
                .filter { it !is LegView.Walk || it.meters >= 40 || journey.rides == 0 }
                .joinToString(" › ") { leg ->
                    when (leg) {
                        is LegView.Walk -> "a piedi ${((leg.arrival - leg.departure + 59) / 60).coerceAtLeast(1)} min"
                        is LegView.Ride -> leg.label
                    }
                }
            return Result.Ok(id, journey.departure, journey.arrival, legs, at)
        }
    }
}

/** Tasto ↻ del widget "Verso casa": rotellina subito, calcolo come lavoro di sistema. */
class NavWidgetRefresh : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        updateAppWidgetState(context, PreferencesGlanceStateDefinition, glanceId) { p ->
            p.toMutablePreferences().apply { this[NavWidget.KEY_REFRESHING_SINCE] = System.currentTimeMillis() }
        }
        NavWidget().update(context, glanceId)
        StopWidgetWorker.runNow(context)
    }
}

class NavWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = NavWidget()

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        StopWidgetWorker.schedule(context)
        StopWidgetWorker.runNow(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        if (!StopWidgetWorker.anyWidget(context)) StopWidgetWorker.cancel(context)
    }
}
