package com.example.bustrack.navigation

import android.app.Application
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import com.example.bustrack.ui.LegView
import com.example.bustrack.ui.NavigationModel
import com.example.bustrack.ui.NavigationState
import com.example.bustrack.ui.TransitViewModel
import com.example.bustrack.ui.hasLocationPermission
import com.example.bustrack.ui.locationUpdates
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Servizio in primo piano (tipo "posizione") della navigazione: tiene viva la notifica fissa con il
 * prossimo passo, segue la posizione anche a schermo spento o in un'altra app (tratto in corso,
 * ricalcolo), aggiorna orari live e posizione del mezzo, e manda gli avvisi con vibrazione
 * ("esci ora", "scendi alla prossima"). Si ferma con la navigazione, all'arrivo o da "Termina".
 */
class NavigationService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val nav by lazy { NavigationModel.get(applicationContext as Application) }
    private var started = false
    private var lastKey: String? = null
    /** Avvisi già mandati per la navigazione in corso (chiave = navigazione + tipo + tratto). */
    private val fired = HashSet<String>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Notifica scorsa via: da Android 14 il sistema lo permette anche per i servizi in primo
        // piano, quindi la si ripubblica subito (la navigazione si chiude solo con "Termina").
        if (intent?.action == ACTION_REPOST) {
            lastKey = null
            if (started) update(nav.state.value)
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_STOP) {
            nav.stopNavigation()
            finish()
            return START_NOT_STICKY
        }
        val state = nav.state.value
        val now = System.currentTimeMillis() / 1000
        val content = NavNotification.content(state, now)
        if (content == null || !hasLocationPermission()) {
            finish()
            return START_NOT_STICKY
        }
        NavNotification.createChannels(this)
        try {
            startForeground(
                ONGOING_ID,
                NavNotification.build(this, content),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
            )
        } catch (e: Exception) {
            // Es. avvio non consentito in questo momento: la navigazione resta nell'app.
            Log.e("BusTrack", "Servizio della navigazione", e)
            finish()
            return START_NOT_STICKY
        }
        lastKey = content.key
        if (!started) {
            started = true
            // Posizione: tratto in corso e ricalcolo.
            scope.launch { applicationContext.locationUpdates().collect(nav::onLocation) }
            // Orari live e mezzo, a ogni nuovo feed della centrale.
            scope.launch {
                while (true) {
                    val ts = if (nav.state.value.active != null) nav.refreshLive() else null
                    delay(TransitViewModel.liveDelayMs(ts))
                }
            }
            // Notifica e avvisi: a ogni cambio dello stato e comunque ogni 15 s (minuti che mancano).
            val tick = MutableStateFlow(0L)
            scope.launch {
                while (true) {
                    delay(15_000)
                    tick.value = System.currentTimeMillis()
                }
            }
            scope.launch {
                combine(nav.state, tick) { s, _ -> s }.collect(::update)
            }
        }
        return START_NOT_STICKY
    }

    private fun update(s: NavigationState) {
        val journey = s.active
        if (journey == null) {
            finish()
            return
        }
        val now = System.currentTimeMillis() / 1000
        // Arrivati (ultimo tratto a piedi quasi finito) o navigazione scaduta: fine.
        val p = s.progress
        val arrived = p != null && p.leg == journey.legs.lastIndex && journey.legs.last() is LegView.Walk &&
            (p.remainingMeters ?: Int.MAX_VALUE) < 25
        if (arrived || now > journey.arrival + 30 * 60) {
            if (arrived) {
                notify(ARRIVED_ID, NavNotification.alert(this, ARRIVED_ID, "Sei arrivato", journey.destination.name))
            }
            nav.stopNavigation()
            return
        }
        alerts(s, now)
        val content = NavNotification.content(s, now) ?: return
        if (content.key == lastKey) return
        lastKey = content.key
        notify(ONGOING_ID, NavNotification.build(this, content))
    }

    /**
     * Avvisi con vibrazione, una volta sola ciascuno:
     * - "Esci ora": a piedi verso un mezzo, quando il tempo per arrivarci (più un minuto di margine)
     *   è pari a quello che manca alla partenza; senza posizione, all'ora di uscita prevista;
     * - "Scendi alla prossima": a bordo, quando la prossima fermata è quella di discesa.
     */
    private fun alerts(s: NavigationState, now: Long) {
        val journey = s.active ?: return
        val p = s.progress
        val legIdx = p?.leg ?: journey.legs.indexOfFirst { it.arrival > now }.takeIf { it >= 0 } ?: return
        val key = journey.key
        when (val leg = journey.legs[legIdx]) {
            is LegView.Walk -> {
                val ride = journey.legs.getOrNull(legIdx + 1) as? LegView.Ride ?: return
                val dep = s.live["${ride.tripId}|${ride.from.id}"] ?: ride.departure
                val remaining = p?.takeIf { it.leg == legIdx }?.remainingMeters
                val due = if (remaining != null) {
                    remaining > 60 && now >= dep - (remaining / com.example.bustrack.routing.WALK_SPEED_MPS).toLong() - 60
                } else {
                    now >= leg.departure - 30 && now < dep
                }
                if (due && fired.add("$key|leave|$legIdx")) {
                    val minutes = ((dep - now + 59) / 60).coerceAtLeast(0)
                    notify(
                        ALERT_ID,
                        NavNotification.alert(
                            this, ALERT_ID, "Esci ora",
                            "Il ${ride.label} parte da ${ride.from.name} " +
                                (if (minutes == 0L) "adesso" else "tra $minutes min") + ": incamminati verso la palina.",
                        ),
                    )
                }
            }
            is LegView.Ride -> {
                val rs = p?.takeIf { it.leg == legIdx }?.rideStop
                    ?: leg.stopTimes.indexOfLast { it <= now }.takeIf { it >= 0 } ?: return
                if (leg.stops.lastIndex - rs == 1 && fired.add("$key|alight|$legIdx")) {
                    notify(
                        ALERT_ID,
                        NavNotification.alert(this, ALERT_ID, "Scendi alla prossima", "Prossima fermata: ${leg.to.name}. Prenota la discesa."),
                    )
                }
            }
        }
    }

    private fun notify(id: Int, n: android.app.Notification) {
        runCatching { getSystemService(NotificationManager::class.java).notify(id, n) }
    }

    private fun finish() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "stop"
        const val ACTION_REPOST = "repost"
        private const val ONGOING_ID = 2001
        private const val ALERT_ID = 2002
        private const val ARRIVED_ID = 2003

        /** Avvia (o aggiorna) il servizio: serve una navigazione in corso e il permesso di posizione. */
        fun start(context: Context) {
            if (!context.hasLocationPermission()) return
            runCatching { context.startForegroundService(Intent(context, NavigationService::class.java)) }
                .onFailure { Log.e("BusTrack", "Avvio del servizio della navigazione", it) }
        }
    }
}
