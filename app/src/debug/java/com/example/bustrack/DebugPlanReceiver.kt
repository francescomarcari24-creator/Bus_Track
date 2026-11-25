package com.example.bustrack

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.bustrack.gtfs.GtfsStaticDb
import com.example.bustrack.routing.JourneyLeg
import com.example.bustrack.routing.RoutePlanner
import com.example.bustrack.routing.TimetableLoader
import com.example.bustrack.routing.WalkDatabase
import com.example.bustrack.routing.WalkGraphLoader
import java.time.LocalDate
import java.time.ZoneId

/**
 * Solo debug: calcola un viaggio tra due punti e scrive tempi e risultato nel log (tag BusTrack).
 * `adb shell am broadcast -a com.example.bustrack.DEBUG_PLAN -p com.example.bustrack
 *   --ef fromLat 41.9010 --ef fromLon 12.5010 --ef toLat 41.9022 --ef toLon 12.4573`
 */
class DebugPlanReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        Thread {
            try {
                val t0 = System.nanoTime()
                val gtfs = GtfsStaticDb.shared(context)
                val planner = kotlinx.coroutines.runBlocking { com.example.bustrack.routing.RoutingEngine.prepare(context) }
                val graph = com.example.bustrack.routing.RoutingEngine.walkGraph()!!
                val rome = ZoneId.of("Europe/Rome")
                val t1 = System.nanoTime()
                val t2 = t1
                val result = planner.plan(
                    intent.getFloatExtra("fromLat", 0f).toDouble(), intent.getFloatExtra("fromLon", 0f).toDouble(),
                    intent.getFloatExtra("toLat", 0f).toDouble(), intent.getFloatExtra("toLon", 0f).toDouble(),
                    System.currentTimeMillis() / 1000,
                )
                val t3 = System.nanoTime()
                Log.i("BusTrack", "PLAN carico dati ${(t1 - t0) / 1_000_000} ms, preparo ${(t2 - t1) / 1_000_000} ms, ricerca ${(t3 - t2) / 1_000_000} ms")
                if (result !is RoutePlanner.Result.Journeys) { Log.i("BusTrack", "PLAN risultato: $result"); return@Thread }
                val hhmm = java.time.format.DateTimeFormatter.ofPattern("HH:mm").withZone(rome)
                result.journeys.forEachIndexed { i, j ->
                    Log.i("BusTrack", "PLAN #$i: parti ${hhmm.format(java.time.Instant.ofEpochSecond(j.departure))} arrivi ${hhmm.format(java.time.Instant.ofEpochSecond(j.arrival))}, mezzi ${j.rides}, a piedi ${j.walkMeters} m")
                    for (leg in j.legs) when (leg) {
                        is JourneyLeg.Walk -> Log.i("BusTrack", "PLAN    a piedi ${leg.meters} m (${leg.steps.size} tratti) ${leg.fromStopId ?: "partenza"} -> ${leg.toStopId ?: "meta"}: " +
                            leg.steps.map { graph.names[graph.edgeName[it.edge]] }.filter { it.isNotEmpty() }.distinct().take(6).joinToString(" > "))
                        is JourneyLeg.Ride -> Log.i("BusTrack", "PLAN    linea ${gtfs.route(leg.routeId)?.label} -> ${leg.headsign}: ${gtfs.stop(leg.fromStopId)?.name} ${hhmm.format(java.time.Instant.ofEpochSecond(leg.departure))} -> ${gtfs.stop(leg.toStopId)?.name} ${hhmm.format(java.time.Instant.ofEpochSecond(leg.arrival))} (${leg.stopCount} fermate)")
                    }
                }
            } catch (e: Exception) {
                Log.e("BusTrack", "PLAN errore", e)
            } finally {
                pending.finish()
            }
        }.start()
    }
}
