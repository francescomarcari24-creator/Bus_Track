package com.example.bustrack.watch

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.SystemClock
import com.example.bustrack.MainActivity
import com.example.bustrack.R
import com.example.bustrack.gtfs.Arrival
import com.example.bustrack.gtfs.TransitRepository
import com.example.bustrack.ui.TransitViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Passaggio seguito: una corsa a una fermata, con la soglia in minuti per l'avviso. */
data class ArrivalWatch(
    val stopId: String,
    val stopName: String,
    val tripId: String,
    val routeLabel: String,
    val headsign: String?,
    val thresholdMinutes: Int,
    /** Ultimi minuti mancanti noti (null finché non arriva il primo aggiornamento). */
    val minutesLeft: Long? = null,
    val isRealtime: Boolean = false,
) {
    val title: String get() = "$routeLabel" + (headsign?.let { " → $it" } ?: "")
}

/**
 * Servizio in primo piano che segue un passaggio a una fermata anche ad app chiusa: notifica fissa
 * con i minuti mancanti, aggiornata appena la centrale pubblica nuovi dati (vedi FeedTiming), e un
 * avviso sonoro quando il mezzo è entro la soglia scelta; poi si ferma da solo. Segue un solo
 * passaggio alla volta, al massimo per 2 ore.
 */
class ArrivalWatchService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    private val repo by lazy { TransitRepository(applicationContext) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopWatching()
            return START_NOT_STICKY
        }
        val watch = intent?.toWatch() ?: run { stopWatching(); return START_NOT_STICKY }
        createChannels(this)
        startForeground(
            ONGOING_ID, ongoingNotification(watch),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        _current.value = watch
        job?.cancel()
        job = scope.launch { follow(watch) }
        return START_NOT_STICKY
    }

    private suspend fun follow(initial: ArrivalWatch) {
        var watch = initial
        val startedAt = SystemClock.elapsedRealtime()
        var feedTs: Long? = null
        var misses = 0
        while (scope.isActive) {
            val result = runCatching { repo.arrivalsAt(watch.stopId) }.getOrNull()
            result?.feedTimestamp?.let { feedTs = it }
            val arrival: Arrival? = result?.arrivals?.firstOrNull { it.tripId == watch.tripId }
            val now = System.currentTimeMillis() / 1000
            if (arrival == null) {
                // Corsa sparita dagli arrivi: già passata (o soppressa). Si tollera qualche buco del feed.
                if (result != null && ++misses >= 3) {
                    notifyDone(watch, "Il ${watch.title} non risulta più in arrivo a ${watch.stopName}.")
                    break
                }
            } else {
                misses = 0
                val minutes = ((arrival.time - now) / 60).coerceAtLeast(0)
                watch = watch.copy(minutesLeft = minutes, isRealtime = arrival.isRealtime)
                _current.value = watch
                if (minutes <= watch.thresholdMinutes) {
                    val text = if (minutes == 0L) "è in arrivo ora" else "arriva tra $minutes min"
                    notifyDone(watch, "Il ${watch.title} $text a ${watch.stopName}.", alert = true)
                    break
                }
                getSystemService(NotificationManager::class.java).notify(ONGOING_ID, ongoingNotification(watch))
            }
            if (SystemClock.elapsedRealtime() - startedAt > MAX_MS) {
                notifyDone(watch, "Smesso di seguire il ${watch.title}: passate 2 ore.")
                break
            }
            delay(TransitViewModel.liveDelayMs(feedTs))
        }
        stopWatching()
    }

    private fun notifyDone(watch: ArrivalWatch, text: String, alert: Boolean = false) {
        val n = Notification.Builder(this, if (alert) CHANNEL_ALERT else CHANNEL_ONGOING)
            .setSmallIcon(R.drawable.ic_bus_notification)
            .setContentTitle(if (alert) "Il tuo bus sta arrivando" else watch.title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(openAppIntent())
            .setAutoCancel(true)
            .setCategory(if (alert) Notification.CATEGORY_ALARM else Notification.CATEGORY_STATUS)
            .build()
        getSystemService(NotificationManager::class.java).notify(DONE_ID, n)
    }

    private fun ongoingNotification(watch: ArrivalWatch): Notification {
        val left = watch.minutesLeft?.let { m ->
            (if (m == 0L) "in arrivo" else "$m min") + if (watch.isRealtime) " (live)" else " (programmato)"
        } ?: "cerco il passaggio…"
        return Notification.Builder(this, CHANNEL_ONGOING)
            .setSmallIcon(R.drawable.ic_bus_notification)
            .setContentTitle("${watch.title}: $left")
            .setContentText("A ${watch.stopName} · avviso a ${watch.thresholdMinutes} min")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_STATUS)
            .setContentIntent(openAppIntent())
            .addAction(
                Notification.Action.Builder(
                    null, "Annulla",
                    PendingIntent.getService(
                        this, 1, Intent(this, ArrivalWatchService::class.java).setAction(ACTION_STOP),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    ),
                ).build()
            )
            .build()
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun stopWatching() {
        job?.cancel()
        _current.value = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        _current.value = null
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ONGOING = "arrival_watch"
        private const val CHANNEL_ALERT = "arrival_alert"
        private const val ONGOING_ID = 1001
        private const val DONE_ID = 1002
        private const val MAX_MS = 2 * 60 * 60 * 1000L
        private const val ACTION_STOP = "stop"

        private val _current = MutableStateFlow<ArrivalWatch?>(null)
        /** Passaggio seguito in questo momento (null = nessuno), per mostrarlo nell'app. */
        val current: StateFlow<ArrivalWatch?> = _current

        fun start(context: Context, watch: ArrivalWatch) {
            val intent = Intent(context, ArrivalWatchService::class.java)
                .putExtra("stopId", watch.stopId)
                .putExtra("stopName", watch.stopName)
                .putExtra("tripId", watch.tripId)
                .putExtra("routeLabel", watch.routeLabel)
                .putExtra("headsign", watch.headsign)
                .putExtra("threshold", watch.thresholdMinutes)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, ArrivalWatchService::class.java).setAction(ACTION_STOP))
        }

        private fun Intent.toWatch(): ArrivalWatch? {
            return ArrivalWatch(
                stopId = getStringExtra("stopId") ?: return null,
                stopName = getStringExtra("stopName") ?: return null,
                tripId = getStringExtra("tripId") ?: return null,
                routeLabel = getStringExtra("routeLabel") ?: return null,
                headsign = getStringExtra("headsign"),
                thresholdMinutes = getIntExtra("threshold", 3),
            )
        }

        private fun createChannels(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ONGOING, "Bus seguito", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Minuti mancanti al passaggio che stai seguendo"
                }
            )
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ALERT, "Bus in arrivo", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Avviso quando il bus seguito è vicino alla fermata"
                }
            )
        }
    }
}
