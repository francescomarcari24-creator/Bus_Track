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
 * orari del giorno e li tiene in memoria; gli orari si ricaricano quando cambia il giorno (o il GTFS).
 * Gli orari del giorno si salvano già pronti ([TimetableCache]): riaprendo l'app lo stesso giorno
 * il motore è pronto in una frazione di secondo.
 * Chiamare [prepare] in anticipo (es. aprendo la scheda Nav) rende immediata la prima ricerca.
 */
object RoutingEngine {
    private val rome: ZoneId = ZoneId.of("Europe/Rome")
    private val mutex = Mutex()
    @Volatile private var walk: WalkData? = null
    @Volatile private var planner: RoutePlanner? = null
    @Volatile private var plannerKey: String? = null

    /** Prepara il motore per oggi (non fa nulla se è già pronto). */
    suspend fun prepare(context: Context): RoutePlanner = withContext(Dispatchers.Default) {
        mutex.withLock {
            val today = LocalDate.now(rome)
            val gtfs = GtfsStaticDb.shared(context)
            // Versione dei dati: cambia con un nuovo GTFS (aggiornato dall'app) o un nuovo grafo.
            val gtfsVersion = runCatching {
                gtfs.db.rawQuery("SELECT value FROM meta WHERE key = 'source_md5'", null).use { c ->
                    if (c.moveToFirst()) c.getString(0) else ""
                }
            }.getOrDefault("")
            val dataKey = "$gtfsVersion|walk2"
            val key = "$today|$dataKey"
            planner?.takeIf { plannerKey == key }?.let { return@withLock it }
            val started = System.nanoTime()
            val w = walk ?: WalkData.load(context).also { walk = it }
            val day = TimetableCache.load(context, today, dataKey)
                ?: TimetableLoader.load(gtfs.db, w, today).also { TimetableCache.save(context, it, dataKey) }
            val lat = DoubleArray(day.stopIds.size)
            val lon = DoubleArray(day.stopIds.size)
            gtfs.db.rawQuery("SELECT stop_id, lat, lon FROM stops", null).use { c ->
                while (c.moveToNext()) day.stopIndex[c.getString(0)]?.let { i -> lat[i] = c.getDouble(1); lon[i] = c.getDouble(2) }
            }
            val (node, gap) = stopNodes(w, day)
            RoutePlanner(w.graph, day, lat, lon, today.atStartOfDay(rome).toEpochSecond(), node, gap).also {
                planner = it
                plannerKey = key
                Log.i("BusTrack", "Motore percorsi pronto in ${(System.nanoTime() - started) / 1_000_000} ms")
            }
        }
    }

    /** Grafo pedonale (dopo [prepare]), per le indicazioni. */
    fun walkGraph(): WalkGraph? = walk?.graph

    /** Dati del grafo (dopo [prepare]), per le forme dei tratti a piedi. */
    fun walkData(): WalkData? = walk
}
