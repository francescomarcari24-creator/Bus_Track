package com.example.bustrack

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.bustrack.gtfs.update.GtfsUpdateWorker
import com.example.bustrack.ui.BusTrackApp
import com.example.bustrack.ui.theme.BusTrackTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Aperta dal widget: mostra gli arrivi di quella fermata (vedi BusTrackApp).
        if (savedInstanceState == null) intent?.getStringExtra(EXTRA_OPEN_STOP_ID)?.let { PendingStopOpen.stopId.value = it }
        // Aperta da una notifica di avviso: scheda Avvisi.
        if (savedInstanceState == null && intent?.getBooleanExtra(EXTRA_OPEN_ALERTS, false) == true) PendingStopOpen.alerts.value = true
        // Aperta dalla notifica della navigazione: scheda Nav.
        if (savedInstanceState == null && intent?.getBooleanExtra(EXTRA_OPEN_NAV, false) == true) PendingStopOpen.nav.value = true
        // Aperta da una scorciatoia dell'icona ("Vai a casa", "Vai a lavoro"): percorsi verso lì.
        if (savedInstanceState == null) intent?.getStringExtra(EXTRA_NAV_TO)?.let { PendingStopOpen.navTo.value = it }
        // Scorciatoie dell'icona allineate a Casa e Lavoro impostati.
        com.example.bustrack.settings.AppSettings.get(this).let { s ->
            com.example.bustrack.navigation.NavShortcuts.update(this, s.homePlace.value, s.workPlace.value)
        }
        // Notifiche dei nuovi avvisi sulle linee e fermate preferite (se attive nelle impostazioni).
        if (com.example.bustrack.settings.AppSettings.get(this).favoriteAlerts.value) {
            com.example.bustrack.watch.FavoriteAlertsWorker.schedule(this)
        }
        enableEdgeToEdge()
        // Controllo giornaliero di nuovi orari e fermate di Roma Mobilità (se già pianificato non cambia nulla).
        GtfsUpdateWorker.schedule(this)
        setContent {
            BusTrackTheme {
                BusTrackApp()
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // Uscendo dall'app (es. verso la schermata principale) i widget si aggiornano subito.
        com.example.bustrack.widget.StopWidgetWorker.runNowIfAny(this)
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(EXTRA_OPEN_STOP_ID)?.let { PendingStopOpen.stopId.value = it }
        if (intent.getBooleanExtra(EXTRA_OPEN_ALERTS, false)) PendingStopOpen.alerts.value = true
        if (intent.getBooleanExtra(EXTRA_OPEN_NAV, false)) PendingStopOpen.nav.value = true
        intent.getStringExtra(EXTRA_NAV_TO)?.let { PendingStopOpen.navTo.value = it }
    }

    companion object {
        /** Id della fermata da aprire all'avvio (dal widget). */
        const val EXTRA_OPEN_STOP_ID = "open_stop_id"
        /** Apre la scheda Avvisi (dalle notifiche degli avvisi sui preferiti). */
        const val EXTRA_OPEN_ALERTS = "open_alerts"
        /** Apre la scheda Nav (dalla notifica della navigazione). */
        const val EXTRA_OPEN_NAV = "open_nav"
        /** Naviga verso Casa ("home") o Lavoro ("work"), dalle scorciatoie dell'icona. */
        const val EXTRA_NAV_TO = "nav_to"
    }
}

/** Pagine da aprire chieste da fuori (widget, notifiche); BusTrackApp le apre e le azzera. */
object PendingStopOpen {
    val stopId = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val alerts = kotlinx.coroutines.flow.MutableStateFlow(false)
    val nav = kotlinx.coroutines.flow.MutableStateFlow(false)
    val navTo = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
}
