package com.example.bustrack.gtfs.update

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.bustrack.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Controllo giornaliero del GTFS statico (vedi [GtfsUpdater]). Il controllo dell'MD5 è leggero;
 * download (48 MB) e ricostruzione (1–3 minuti) avvengono solo se è uscito un GTFS nuovo, in
 * primo piano con una notifica discreta, e su rete a consumo solo se i dati attuali stanno per scadere.
 */
class GtfsUpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val force = inputData.getBoolean(KEY_FORCE, false)
        try {
            runCatching { setForeground(foregroundInfo()) }
            val outcome = when (val result = GtfsUpdater(applicationContext).run(force)) {
                GtfsUpdater.Result.UpToDate -> {
                    Log.i(TAG, "GTFS già aggiornato")
                    OUTCOME_UP_TO_DATE
                }
                GtfsUpdater.Result.WaitingForWifi -> {
                    Log.i(TAG, "GTFS nuovo disponibile: si aspetta il Wi-Fi")
                    enqueueOnWifi(applicationContext)
                    OUTCOME_WAITING_WIFI
                }
                is GtfsUpdater.Result.Updated -> {
                    Log.i(TAG, "GTFS aggiornato, valido fino al ${result.feedEndDate}")
                    OUTCOME_UPDATED
                }
            }
            Result.success(workDataOf(KEY_OUTCOME to outcome))
        } catch (e: Exception) {
            Log.w(TAG, "Aggiornamento GTFS fallito", e)
            // Controllo chiesto a mano: si dice subito che non è andato. Quello automatico riprova
            // più tardi (attesa crescente), al massimo qualche volta: poi c'è il controllo del giorno dopo.
            if (!inputData.getBoolean(KEY_MANUAL, false) && runAttemptCount < 3) Result.retry()
            else Result.failure(workDataOf(KEY_OUTCOME to OUTCOME_FAILED))
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo()

    private fun foregroundInfo(): ForegroundInfo {
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Aggiornamento orari", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Download e preparazione dei nuovi orari di Roma Mobilità"
            }
        )
        val n = Notification.Builder(applicationContext, CHANNEL)
            .setSmallIcon(R.drawable.ic_bus_notification)
            .setContentTitle("Aggiorno orari e fermate")
            .setContentText("Roma Mobilità ha pubblicato nuovi dati")
            .setOngoing(true)
            .build()
        return ForegroundInfo(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    companion object {
        private const val TAG = "BusTrack"
        private const val CHANNEL = "gtfs_update"
        private const val NOTIFICATION_ID = 1101
        private const val KEY_FORCE = "force"
        private const val KEY_MANUAL = "manual"
        const val KEY_OUTCOME = "outcome"
        const val OUTCOME_UP_TO_DATE = "up_to_date"
        const val OUTCOME_WAITING_WIFI = "waiting_wifi"
        const val OUTCOME_UPDATED = "updated"
        const val OUTCOME_FAILED = "failed"
        /** Nome del controllo immediato, per seguirne l'esito (schermata Impostazioni). */
        const val NOW_NAME = "gtfs-update-now"
        private const val PERIODIC = "gtfs-update-daily"
        private const val ON_WIFI = "gtfs-update-wifi"

        /** Controllo giornaliero, da chiamare all'avvio dell'app (se già pianificato non cambia nulla). */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<GtfsUpdateWorker>(1, TimeUnit.DAYS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .setRequiresBatteryNotLow(true)
                        .setRequiresStorageNotLow(true)
                        .build()
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        /** GTFS nuovo trovato su rete a consumo: lo si scarica appena c'è il Wi-Fi. */
        private fun enqueueOnWifi(context: Context) {
            val request = OneTimeWorkRequestBuilder<GtfsUpdateWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.UNMETERED)
                        .setRequiresBatteryNotLow(true)
                        .setRequiresStorageNotLow(true)
                        .build()
                )
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(ON_WIFI, ExistingWorkPolicy.KEEP, request)
        }

        /** Aggiornamento immediato (prove): con [force] ricostruisce anche se l'MD5 non è cambiato. */
        fun runNow(context: Context, force: Boolean) {
            val request = OneTimeWorkRequestBuilder<GtfsUpdateWorker>()
                .setInputData(workDataOf(KEY_FORCE to force, KEY_MANUAL to true))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(NOW_NAME, ExistingWorkPolicy.REPLACE, request)
        }
    }
}
