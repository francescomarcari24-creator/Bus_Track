package com.example.bustrack.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.state.PreferencesGlanceStateDefinition
import com.example.bustrack.MainActivity
import com.example.bustrack.favorites.Favorite
import com.example.bustrack.favorites.FavoritesStore
import com.example.bustrack.gtfs.Stop
import com.example.bustrack.gtfs.TransitRepository
import com.example.bustrack.ui.AppListItem
import com.example.bustrack.ui.theme.BusTrackTheme
import kotlinx.coroutines.launch

/**
 * Si apre quando si aggiunge il widget: si sceglie una delle fermate preferite da mostrare.
 * Senza preferiti spiega come aggiungerne uno.
 */
class StopWidgetConfigActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val appWidgetId = intent?.extras?.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        // Se si esce senza scegliere, il widget non viene aggiunto.
        setResult(RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId))
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) { finish(); return }

        enableEdgeToEdge()
        setContent {
            BusTrackTheme {
                Surface(Modifier.fillMaxSize()) {
                    var stops by remember { mutableStateOf<List<Stop>?>(null) }
                    LaunchedEffect(Unit) {
                        val ids = FavoritesStore(applicationContext).items.value
                            .filterIsInstance<Favorite.StopFav>().map { it.stopId }
                        stops = runCatching { TransitRepository(applicationContext).stopsInOrder(ids) }.getOrDefault(emptyList())
                    }
                    val scope = rememberCoroutineScope()
                    Column(Modifier.safeDrawingPadding().padding(16.dp)) {
                        Text("Fermata del widget", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "Scegli una fermata preferita: il widget mostrerà i suoi prossimi passaggi.",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
                        )
                        val list = stops
                        when {
                            list == null -> Unit
                            list.isEmpty() -> {
                                Text(
                                    "Non hai fermate preferite. Apri una fermata nell'app e tocca ☆ in alto, " +
                                        "poi aggiungi di nuovo il widget.",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                TextButton(onClick = {
                                    startActivity(Intent(this@StopWidgetConfigActivity, MainActivity::class.java))
                                    finish()
                                }) { Text("Apri l'app") }
                            }
                            else -> LazyColumn {
                                items(list, key = { it.id }) { stop ->
                                    AppListItem(
                                        supportingContent = { Text("Palina ${stop.code ?: stop.id}") },
                                        modifier = Modifier.clickable { scope.launch { choose(appWidgetId, stop) } },
                                    ) { Text(stop.name) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private suspend fun choose(appWidgetId: Int, stop: Stop) {
        val glanceId = GlanceAppWidgetManager(this).getGlanceIdBy(appWidgetId)
        updateAppWidgetState(this, PreferencesGlanceStateDefinition, glanceId) { p ->
            p.toMutablePreferences().apply {
                this[StopWidget.KEY_STOP_ID] = stop.id
                this[StopWidget.KEY_STOP_NAME] = stop.name
                remove(StopWidget.KEY_ROWS)
                remove(StopWidget.KEY_UPDATED_AT)
                remove(StopWidget.KEY_ERROR)
            }
        }
        StopWidget().update(this, glanceId)
        StopWidgetWorker.schedule(this)
        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId))
        finish()
        // Primi passaggi subito, senza aspettare il ciclo dei 15 minuti.
        StopWidget.refresh(applicationContext, glanceId)
    }
}
