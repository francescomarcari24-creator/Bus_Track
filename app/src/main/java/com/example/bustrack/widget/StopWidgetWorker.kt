package com.example.bustrack.widget

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/** Aggiorna i widget delle fermate ogni 15 minuti (il minimo concesso da Android) quando c'è rete. */
class StopWidgetWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        StopWidget.refresh(applicationContext)
        return Result.success()
    }

    companion object {
        private const val NAME = "stop-widget-refresh"
        private const val NOW = "stop-widget-now"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<StopWidgetWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        /**
         * Aggiornamento subito, come lavoro di sistema: arriva in fondo anche se chi lo chiede si
         * chiude (la schermata di scelta della fermata, l'app che va in secondo piano).
         */
        fun runNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<StopWidgetWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.REPLACE, request)
        }

        /** Aggiorna i widget solo se ce n'è almeno uno sulla schermata principale. */
        fun runNowIfAny(context: Context) {
            val manager = android.appwidget.AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(android.content.ComponentName(context, StopWidgetReceiver::class.java))
            if (ids.isNotEmpty()) runNow(context)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(NAME)
        }
    }
}
