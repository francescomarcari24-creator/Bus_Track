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
        enableEdgeToEdge()
        // Controllo giornaliero di nuovi orari e fermate di Roma Mobilità (se già pianificato non cambia nulla).
        GtfsUpdateWorker.schedule(this)
        setContent {
            BusTrackTheme {
                BusTrackApp()
            }
        }
    }
}
