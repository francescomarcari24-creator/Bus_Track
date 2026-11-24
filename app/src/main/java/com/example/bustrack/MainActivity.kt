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
    }

    companion object {
        /** Id della fermata da aprire all'avvio (dal widget). */
        const val EXTRA_OPEN_STOP_ID = "open_stop_id"
    }
}

/** Fermata da aprire chiesta da fuori (widget); BusTrackApp la apre e la azzera. */
object PendingStopOpen {
    val stopId = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
}
