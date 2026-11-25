package com.example.bustrack.routing

import android.content.Context
import android.util.Log
import com.example.bustrack.gtfs.GtfsStaticDb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId

/**
 * Motore dei percorsi condiviso dall'app: carica una volta sola (in background) grafo pedonale e
 * orari del giorno e li tiene in memoria; gli orari si ricaricano quando cambia il giorno.
 * Chiamare [prepare] in anticipo (es. aprendo la scheda Nav) rende immediata la prima ricerca.
 */
object RoutingEngine {
    private val rome: ZoneId = ZoneId.of("Europe/Rome")
    private val mutex = Mutex()
    @Volatile private var graph: WalkGraph? = null
    @Volatile private var planner: RoutePlanner? = null
    @Volatile private var plannerDay: LocalDate? = null

    /** Prepara il motore per oggi (non fa nulla se è già pronto). */
    suspend fun prepare(context: Context): RoutePlanner = withContext(Dispatchers.Default) {
        mutex.withLock {
            val today = LocalDate.now(rome)
            planner?.takeIf { plannerDay == today }?.let { return@withLock it }
            val started = System.nanoTime()
            val walkDb = WalkDatabase.open(context)
            val g = graph ?: WalkGraphLoader.load(walkDb).also { graph = it }
            val gtfs = GtfsStaticDb.shared(context)
            val day = TimetableLoader.load(gtfs.db, walkDb, today)
            val lat = DoubleArray(day.stopIds.size)
            val lon = DoubleArray(day.stopIds.size)
            gtfs.db.rawQuery("SELECT stop_id, lat, lon FROM stops", null).use { c ->
                while (c.moveToNext()) day.stopIndex[c.getString(0)]?.let { i -> lat[i] = c.getDouble(1); lon[i] = c.getDouble(2) }
            }
            val (node, gap) = loadStopNodes(walkDb, day)
            RoutePlanner(g, day, lat, lon, today.atStartOfDay(rome).toEpochSecond(), node, gap).also {
                planner = it
                plannerDay = today
                Log.i("BusTrack", "Motore percorsi pronto in ${(System.nanoTime() - started) / 1_000_000} ms")
            }
        }
    }

    /** Grafo pedonale (dopo [prepare]), per le indicazioni e le forme dei tratti. */
    fun walkGraph(): WalkGraph? = graph
}
