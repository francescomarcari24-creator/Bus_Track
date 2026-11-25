package com.example.bustrack.navigation

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Build
import android.text.SpannableString
import android.text.Spanned
import android.text.style.StyleSpan
import android.widget.RemoteViews
import com.example.bustrack.MainActivity
import com.example.bustrack.R
import com.example.bustrack.routing.Turn
import com.example.bustrack.ui.LegView
import com.example.bustrack.ui.NavigationState
import com.example.bustrack.ui.metroColor
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Notifica fissa della navigazione, sul modello di Citymapper:
 * - compressa: il prossimo passo ("Gira a destra in Via Milano", "Prendi il 64 · tra 4 min",
 *   "A bordo del 64: scendi a Ottaviano");
 * - estesa: le prossime indicazioni a piedi, oppure le fermate del mezzo fino a quella di discesa.
 *
 * Su Android 16 o successivi, se il sistema lo permette, è una "notifica live" promossa (pillola
 * nella barra di stato, blocco schermo, Now Bar di Samsung): lì sono ammessi solo gli stili
 * standard, quindi l'elenco è testo a righe. Altrimenti l'elenco è un layout personalizzato con i
 * pallini colorati della linea.
 */
object NavNotification {
    const val CHANNEL = "navigation"
    const val CHANNEL_ALERT = "navigation_alert"
    /** Righe massime dell'elenco esteso (il layout personalizzato ha un'altezza limitata). */
    private const val MAX_ROWS = 5
    private val HHMM = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.of("Europe/Rome"))
    private const val LIVE_GREEN = 0xFF1E8E3E.toInt()
    private const val UNTRACKED_GRAY = 0xFF8A8F98.toInt()

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Navigazione in corso", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Prossimo passo del percorso, sempre visibile durante una navigazione"
                setShowBadge(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERT, "Avvisi della navigazione", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Quando uscire per prendere il mezzo e quando scendere"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 400, 200, 400)
            }
        )
    }

    /** Su questo dispositivo si può usare la notifica live di Android 16. */
    fun canPromote(context: Context): Boolean =
        Build.VERSION.SDK_INT >= 36 &&
            context.getSystemService(NotificationManager::class.java).canPostPromotedNotifications()

    /** Riga dell'elenco esteso: simbolo (svolta o pallino), testo, orario. */
    class Row(val marker: String, val text: String, val time: String? = null, val bold: Boolean = false, val color: Int? = null)

    /** Contenuto della notifica: [countdownTo] (epoch s) mostra il conto alla rovescia, [chip] il testo breve. */
    class Content(
        val title: String,
        val text: String,
        val chip: String,
        val rows: List<Row>,
        val countdownTo: Long? = null,
        val color: Int? = null,
    ) {
        /** Per non riaggiornare la notifica se non è cambiato nulla. */
        val key: String = listOf(title, text, chip, countdownTo, color).joinToString("|") +
            rows.joinToString("|") { "${it.marker}${it.text}${it.time}${it.bold}" }
    }

    fun content(s: NavigationState, now: Long): Content? {
        val j = s.active ?: return null
        val p = s.progress
        val legIdx = p?.leg ?: j.legs.indexOfFirst { it.arrival > now }.let { if (it < 0) j.legs.lastIndex else it }
        fun dep(r: LegView.Ride) = s.live["${r.tripId}|${r.from.id}"] ?: r.departure
        fun arr(r: LegView.Ride) = s.live["${r.tripId}|${r.to.id}"] ?: r.arrival
        val lastRide = j.legs.lastOrNull { it is LegView.Ride } as LegView.Ride?
        val arrival = j.arrival + (lastRide?.let { arr(it) - it.arrival } ?: 0)
        fun colorOf(r: LegView.Ride) = metroColor(r.label)
            ?: if (r.tripId in s.vehicleAt || s.live.keys.any { it.startsWith("${r.tripId}|") }) LIVE_GREEN else UNTRACKED_GRAY

        // Fermate del mezzo da [from] alla discesa, con l'orario (spostato del ritardo live).
        fun stopRows(r: LegView.Ride, from: Int): List<Row> {
            val delay = s.live["${r.tripId}|${r.from.id}"]?.minus(r.departure) ?: 0
            val c = colorOf(r)
            val all = (from..r.stops.lastIndex).map { k ->
                Row(
                    marker = when (k) { from -> "●"; r.stops.lastIndex -> "◉"; else -> "○" },
                    text = r.stops[k].name,
                    time = HHMM.format(Instant.ofEpochSecond(r.stopTimes[k] + delay)),
                    bold = k == from || k == r.stops.lastIndex,
                    color = c,
                )
            }
            if (all.size <= MAX_ROWS) return all
            // Tragitto lungo: le prime e le ultime fermate, il centro riassunto.
            val head = all.take(2)
            val tail = all.takeLast(2)
            return head + Row("⋮", "${all.size - head.size - tail.size} fermate", color = c) + tail
        }

        fun waiting(r: LegView.Ride): Content {
            val d = dep(r)
            val minutes = ((d - now + 59) / 60).coerceAtLeast(0)
            val live = s.live.containsKey("${r.tripId}|${r.from.id}")
            return Content(
                title = "Prendi il ${r.label}" + (r.headsign?.let { " → $it" } ?: ""),
                text = "Da ${r.from.name} · parte alle ${HHMM.format(Instant.ofEpochSecond(d))}" + if (live) " (live)" else "",
                chip = if (minutes == 0L) "Ora" else "$minutes min",
                rows = listOfNotNull(s.vehicleAt[r.tripId]?.let { Row("◎", "Il mezzo è presso $it", color = LIVE_GREEN) }) +
                    stopRows(r, 0).take(MAX_ROWS - 1),
                countdownTo = d,
                color = colorOf(r),
            )
        }

        return when (val leg = j.legs[legIdx]) {
            is LegView.Walk -> {
                val next = j.legs.getOrNull(legIdx + 1) as? LegView.Ride
                val remaining = p?.takeIf { it.leg == legIdx }?.remainingMeters
                // Arrivati alla palina (o, senza posizione, passato l'orario d'arrivo): si aspetta il mezzo.
                if (next != null && ((remaining != null && remaining < 40) || (remaining == null && now >= leg.arrival))) {
                    return waiting(next)
                }
                val steps = leg.instructions.filter { it.turn != Turn.ARRIVE }
                val done = (leg.meters - (remaining ?: leg.meters)).coerceAtLeast(0)
                var acc = 0
                var idx = 0
                for (k in steps.indices) {
                    idx = k
                    if (acc + steps[k].meters > done) break
                    acc += steps[k].meters
                }
                val title = steps.getOrNull(idx)?.text ?: leg.instructions.last().text
                val text = listOfNotNull(
                    remaining?.let { "Mancano ${distance(it)}" } ?: "A piedi",
                    next?.let { "poi il ${it.label} alle ${HHMM.format(Instant.ofEpochSecond(dep(it)))}" }
                        ?: "arrivo alle ${HHMM.format(Instant.ofEpochSecond(arrival))}",
                ).joinToString(" · ")
                Content(
                    title = title,
                    text = text,
                    chip = remaining?.let(::distance) ?: "A piedi",
                    rows = steps.drop(idx + 1).take(MAX_ROWS - 1).map { Row(glyph(it.turn), it.text) } +
                        Row("•", leg.instructions.last().text, bold = true),
                )
            }
            is LegView.Ride -> {
                val rs = p?.takeIf { it.leg == legIdx }?.rideStop
                val boarded = if (rs != null) rs > 0 || now > dep(leg) + 60 else now >= dep(leg)
                if (!boarded) return waiting(leg)
                val idx = rs ?: leg.stopTimes.indexOfLast { it <= now }.coerceAtLeast(0)
                val left = leg.stops.lastIndex - idx
                Content(
                    title = "A bordo del ${leg.label}: scendi a ${leg.to.name}",
                    text = when {
                        left <= 0 -> "Scendi ora"
                        left == 1 -> "Scendi alla prossima fermata"
                        else -> "Tra $left fermate · alle ${HHMM.format(Instant.ofEpochSecond(arr(leg)))}"
                    },
                    chip = if (left <= 1) "Scendi" else "$left ferm.",
                    rows = stopRows(leg, idx),
                    color = colorOf(leg),
                )
            }
        }
    }

    /**
     * Notifica normale, compatta come quella di un messaggio (passo attuale e una riga), con la
     * freccia per espanderla e vedere i passi successivi o le fermate. La notifica "live" di
     * Android 16 ([promoted]) il sistema la mostra sempre grande: per questo non si usa.
     */
    fun build(context: Context, c: Content, promoted: Boolean = false): Notification {
        val b = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_bus_notification)
            .setContentTitle(c.title)
            .setContentText(c.text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_NAVIGATION)
            .setContentIntent(openNavIntent(context))
            // Se la si scorre via, il servizio la ripubblica: resta finché c'è la navigazione.
            .setDeleteIntent(
                PendingIntent.getService(
                    context, 3,
                    Intent(context, NavigationService::class.java).setAction(NavigationService.ACTION_REPOST),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            )
            .addAction(
                Notification.Action.Builder(
                    null, "Termina",
                    PendingIntent.getService(
                        context, 1,
                        Intent(context, NavigationService::class.java).setAction(NavigationService.ACTION_STOP),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    ),
                ).build()
            )
        c.color?.let { b.setColor(it) }
        if (c.countdownTo != null) {
            b.setShowWhen(true).setWhen(c.countdownTo * 1000).setUsesChronometer(true).setChronometerCountDown(true)
        } else {
            b.setShowWhen(false)
        }
        if (promoted && Build.VERSION.SDK_INT >= 36) {
            // Notifica live: solo stili standard; l'elenco come testo a righe.
            val lines = c.rows.joinToString("\n") { r -> "${r.marker}  ${r.text}" + (r.time?.let { "  $it" } ?: "") }
            b.setStyle(Notification.BigTextStyle().bigText(c.text + "\n" + lines))
            b.setRequestPromotedOngoing(true)
            b.setShortCriticalText(c.chip)
        } else {
            b.setStyle(Notification.DecoratedCustomViewStyle())
            b.setCustomBigContentView(bigView(context, c))
        }
        return b.build()
    }

    /** Elenco esteso con layout personalizzato: titolo, testo e righe con il simbolo colorato. */
    private fun bigView(context: Context, c: Content): RemoteViews {
        val root = RemoteViews(context.packageName, R.layout.notification_nav_big)
        root.setTextViewText(R.id.nav_title, c.title)
        root.setTextViewText(R.id.nav_text, c.text)
        root.removeAllViews(R.id.nav_rows)
        c.rows.forEach { r ->
            val row = RemoteViews(context.packageName, R.layout.notification_nav_row)
            row.setTextViewText(R.id.nav_marker, r.marker)
            r.color?.let { row.setTextColor(R.id.nav_marker, it) }
            row.setTextViewText(R.id.nav_row_text, if (r.bold) bold(r.text) else r.text)
            row.setTextViewText(R.id.nav_row_time, r.time.orEmpty())
            root.addView(R.id.nav_rows, row)
        }
        return root
    }

    private fun bold(text: String) = SpannableString(text).apply {
        setSpan(StyleSpan(Typeface.BOLD), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    /** Avviso con vibrazione (uscire per il mezzo, scendere): si chiude da solo dopo 3 minuti. */
    fun alert(context: Context, id: Int, title: String, text: String): Notification =
        Notification.Builder(context, CHANNEL_ALERT)
            .setSmallIcon(R.drawable.ic_bus_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setCategory(Notification.CATEGORY_NAVIGATION)
            .setContentIntent(openNavIntent(context))
            .setAutoCancel(true)
            .setTimeoutAfter(3 * 60 * 1000L)
            .build()

    /** Apre l'app sulla scheda Nav. */
    fun openNavIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 2,
        Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(MainActivity.EXTRA_OPEN_NAV, true),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun glyph(t: Turn) = when (t) {
        Turn.START, Turn.STRAIGHT -> "↑"
        Turn.SLIGHT_LEFT -> "↖"
        Turn.LEFT -> "←"
        Turn.SHARP_LEFT -> "↙"
        Turn.SLIGHT_RIGHT -> "↗"
        Turn.RIGHT -> "→"
        Turn.SHARP_RIGHT -> "↘"
        Turn.ARRIVE -> "•"
    }

    private fun distance(m: Int) = if (m < 1000) "${(m / 10) * 10} m" else "%.1f km".format(java.util.Locale.ITALY, m / 1000.0)
}
