package com.example.bustrack

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.bustrack.gtfs.update.GtfsUpdateWorker

/**
 * Solo nella build debug: forza l'aggiornamento del GTFS statico per le prove.
 * `adb shell am broadcast -a com.example.bustrack.DEBUG_UPDATE_GTFS -p com.example.bustrack --ez force true`
 */
class DebugGtfsUpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        GtfsUpdateWorker.runNow(context, force = intent.getBooleanExtra("force", false))
    }
}
