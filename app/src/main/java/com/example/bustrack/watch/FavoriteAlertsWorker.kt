package com.example.bustrack.watch

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.bustrack.MainActivity
import com.example.bustrack.R
import com.example.bustrack.favorites.Favorite
import com.example.bustrack.favorites.FavoritesStore
import com.example.bustrack.gtfs.ServiceAlert
import com.example.bustrack.gtfs.TransitRepository
import java.util.concurrent.TimeUnit

/**
 * Ogni 15 minuti (il minimo di Android) controlla gli avvisi di Roma Mobilità e notifica quelli
 * **nuovi** che riguardano linee o fermate preferite. Gli id già visti restano salvati; al primo
 * controllo si registrano gli avvisi in corso senza notificarli (si vedono nella scheda Avvisi).
 */
class FavoriteAlertsWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val favorites = FavoritesStore(applicationContext).items.value
        if (favorites.isEmpty()) return Result.success()
        val repo = TransitRepository(applicationContext)
        val labels = favorites.filterIsInstance<Favorite.LineFav>()
            .mapNotNullTo(HashSet()) { runCatching { repo.route(it.routeId)?.label }.getOrNull() }
        val stops = favorites.filterIsInstance<Favorite.StopFav>().mapTo(HashSet()) { it.stopId }
        val now = System.currentTimeMillis() / 1000
        val alerts = runCatching { repo.serviceAlerts().items }.getOrElse { return Result.retry() }
            .filter { it.isActiveAt(now) && it.concerns(labels, stops) }

        val prefs = applicationContext.getSharedPreferences("favorite_alerts", Context.MODE_PRIVATE)
        val firstRun = !prefs.contains(KEY_SEEN)
        val seen = prefs.getStringSet(KEY_SEEN, emptySet()).orEmpty()
        val fresh = alerts.filter { it.id !in seen }
        if (!firstRun && fresh.isNotEmpty()) notify(fresh, labels)
        // Si tengono solo gli id ancora attivi: un avviso che sparisce e torna viene notificato di nuovo.
        prefs.edit().putStringSet(KEY_SEEN, alerts.mapTo(HashSet()) { it.id }).apply()
        Log.i(TAG, "Avvisi sui preferiti: ${alerts.size} attivi, ${if (firstRun) 0 else fresh.size} nuovi")
        return Result.success()
    }

    private fun notify(fresh: List<ServiceAlert>, favLabels: Set<String>) {
        if (applicationContext.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Avvisi sulle tue linee", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Nuovi avvisi di Roma Mobilità che riguardano linee e fermate preferite"
            }
        )
        val open = PendingIntent.getActivity(
            applicationContext, 2,
            Intent(applicationContext, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_OPEN_ALERTS, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        fresh.take(MAX_NOTIFICATIONS).forEach { alert ->
            val lines = alert.routeLabels.filter { it in favLabels }
            val title = if (lines.isNotEmpty()) "Avviso per " + lines.joinToString(", ") { "il $it" } else "Avviso per una fermata preferita"
            val n = Notification.Builder(applicationContext, CHANNEL)
                .setSmallIcon(R.drawable.ic_bus_notification)
                .setContentTitle(title)
                .setContentText(alert.header)
                .setStyle(Notification.BigTextStyle().bigText(alert.header + (alert.description?.let { "\n\n$it" } ?: "")))
                .setContentIntent(open)
                .setAutoCancel(true)
                .setGroup(GROUP)
                .build()
            nm.notify(NOTIFICATION_BASE + (alert.id.hashCode() and 0xFFFF), n)
        }
    }

    companion object {
        private const val TAG = "BusTrack"
        private const val CHANNEL = "favorite_alerts"
        private const val GROUP = "favorite_alerts"
        private const val KEY_SEEN = "seen_ids"
        private const val NAME = "favorite-alerts"
        private const val NOTIFICATION_BASE = 2_000_000
        private const val MAX_NOTIFICATIONS = 5

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<FavoriteAlertsWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(NAME)
        }
    }
}
