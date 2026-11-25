package com.example.bustrack.routing

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import java.io.File
import java.time.LocalDate

/** Velocità a piedi usata per tutti i tratti a piedi: 4,5 km/h. */
const val WALK_SPEED_MPS = 1.25

private const val TAG = "BusTrack"

/** Database del grafo pedonale (asset walk.db, da tools/build_walk_graph.py), installato come gtfs.db. */
object WalkDatabase {
    private const val ASSET = "walk.db"
    @Volatile private var db: SQLiteDatabase? = null

    fun open(context: Context): SQLiteDatabase = db ?: synchronized(this) {
        db ?: run {
            val file = File(context.noBackupFilesDir, ASSET)
            val appUpdatedAt = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
            if (!file.exists() || file.lastModified() < appUpdatedAt) {
                val tmp = File(file.path + ".tmp")
                context.assets.open(ASSET).use { input -> tmp.outputStream().use { input.copyTo(it) } }
                check(tmp.renameTo(file) || (file.delete() && tmp.renameTo(file))) { "Impossibile installare $ASSET" }
            }
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS)
                .also { db = it }
        }
    }
}

/** Carica in memoria il grafo pedonale (senza le forme dei tratti, lette solo quando servono). */
object WalkGraphLoader {
    fun load(db: SQLiteDatabase): WalkGraph {
        val started = System.nanoTime()
        val nodeCount = db.rawQuery("SELECT COUNT(*) FROM nodes", null).use { it.moveToFirst(); it.getInt(0) }
        val lat = DoubleArray(nodeCount)
        val lon = DoubleArray(nodeCount)
        db.rawQuery("SELECT id, lat, lon FROM nodes", null).use { c ->
            while (c.moveToNext()) {
                val i = c.getInt(0)
                lat[i] = c.getDouble(1)
                lon[i] = c.getDouble(2)
            }
        }
        val edgeCount = db.rawQuery("SELECT COUNT(*) FROM edges", null).use { it.moveToFirst(); it.getInt(0) }
        val a = IntArray(edgeCount)
        val b = IntArray(edgeCount)
        val len = FloatArray(edgeCount)
        val name = IntArray(edgeCount)
        val kind = ByteArray(edgeCount)
        db.rawQuery("SELECT id, a, b, length, name_id, kind FROM edges", null).use { c ->
            while (c.moveToNext()) {
                val i = c.getInt(0)
                a[i] = c.getInt(1)
                b[i] = c.getInt(2)
                len[i] = c.getFloat(3)
                name[i] = c.getInt(4)
                kind[i] = c.getInt(5).toByte()
            }
        }
        val nameCount = db.rawQuery("SELECT MAX(id) + 1 FROM names", null).use { it.moveToFirst(); it.getInt(0) }
        val names = Array(nameCount) { "" }
        db.rawQuery("SELECT id, name FROM names", null).use { c -> while (c.moveToNext()) names[c.getInt(0)] = c.getString(1) }
        Log.i(TAG, "Grafo pedonale: $nodeCount incroci, $edgeCount tratti in ${(System.nanoTime() - started) / 1_000_000} ms")
        return WalkGraph(lat, lon, a, b, len, name, kind, names)
    }

    /** Forme (polilinee codificate) dei tratti indicati. */
    fun shapes(db: SQLiteDatabase, edges: Collection<Int>): Map<Int, String> = buildMap {
        edges.distinct().chunked(500).forEach { chunk ->
            db.rawQuery(
                "SELECT id, geom FROM edges WHERE id IN (${chunk.joinToString(",")})", null,
            ).use { c -> while (c.moveToNext()) put(c.getInt(0), c.getString(1)) }
        }
    }
}

/** Incrocio della rete più vicino a ogni fermata (tabella stop_nodes del grafo), negli indici di [day]. */
fun loadStopNodes(walk: SQLiteDatabase, day: DayTimetable): Pair<IntArray, DoubleArray> {
    val node = IntArray(day.stopIds.size) { -1 }
    val gap = DoubleArray(day.stopIds.size)
    walk.rawQuery("SELECT stop_id, node, gap_m FROM stop_nodes", null).use { c ->
        while (c.moveToNext()) {
            val i = day.stopIndex[c.getString(0)] ?: continue
            node[i] = c.getInt(1)
            gap[i] = c.getDouble(2)
        }
    }
    return node to gap
}

/** Dati di una corsa per mostrarla (riferita da [Timetable.tripRef]). */
data class TripInfo(val tripId: String, val routeId: String, val headsign: String?, val serviceId: String? = null)

/** Orari di un giorno pronti per RAPTOR, con i dati per tradurre gli indici in fermate e corse. */
class DayTimetable(
    val date: LocalDate,
    val timetable: Timetable,
    val stopIds: Array<String>,
    val stopIndex: Map<String, Int>,
    val trips: Array<TripInfo>,
    /** route_type GTFS di ogni percorso RAPTOR (0 tram, 1 metro, 3 bus, ...). */
    val routeType: IntArray,
) {
    /** trip_id → indice in [trips], per agganciare i dati live. */
    val tripIndex: Map<String, Int> by lazy {
        HashMap<String, Int>(trips.size * 2).apply { trips.forEachIndexed { i, t -> put(t.tripId, i) } }
    }
}

/** Costruisce gli orari del giorno dal database GTFS e i cambi a piedi dal grafo pedonale. */
object TimetableLoader {
    fun load(gtfs: SQLiteDatabase, walk: SQLiteDatabase, date: LocalDate): DayTimetable {
        val started = System.nanoTime()
        val stopIds = gtfs.rawQuery("SELECT stop_id FROM stops", null).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }.toTypedArray()
        val stopIndex = HashMap<String, Int>(stopIds.size * 2).apply { stopIds.forEachIndexed { i, s -> put(s, i) } }

        // Profili: sequenza di fermate e tempi relativi.
        val profStops = HashMap<Int, IntArray>()
        val profOffsets = HashMap<Int, IntArray>()
        run {
            var cur = -1
            val stops = ArrayList<Int>()
            val offs = ArrayList<Int>()
            fun flush() {
                if (cur >= 0) {
                    profStops[cur] = stops.toIntArray()
                    profOffsets[cur] = offs.toIntArray()
                }
                stops.clear(); offs.clear()
            }
            gtfs.rawQuery("SELECT profile_id, stop_id, offset_sec FROM profile_stops ORDER BY profile_id, idx", null).use { c ->
                while (c.moveToNext()) {
                    val p = c.getInt(0)
                    if (p != cur) { flush(); cur = p }
                    stops += stopIndex[c.getString(1)] ?: -1
                    offs += c.getInt(2)
                }
            }
            flush()
        }

        // Corse del giorno (e del giorno prima che finiscono dopo mezzanotte, con partenza negativa).
        data class T(val profile: Int, val start: Int, val ref: Int)
        val tripInfos = ArrayList<TripInfo>()
        val trips = ArrayList<T>()
        fun loadDay(day: LocalDate, shift: Int) {
            val key = day.year * 10000 + day.monthValue * 100 + day.dayOfMonth
            gtfs.rawQuery(
                """
                SELECT t.trip_id, t.route_id, t.headsign, t.profile_id, t.start_sec, t.service_id
                FROM trips t JOIN calendar_dates c ON c.service_id = t.service_id AND c.date = CAST(? AS INTEGER)
                WHERE t.profile_id IS NOT NULL
                """.trimIndent(),
                arrayOf(key.toString()),
            ).use { c ->
                while (c.moveToNext()) {
                    val profile = c.getInt(3)
                    val start = c.getInt(4) - shift
                    val offs = profOffsets[profile] ?: continue
                    if (start + offs.last() < 0) continue // corsa di ieri già finita a mezzanotte
                    trips += T(profile, start, tripInfos.size)
                    tripInfos += TripInfo(c.getString(0), c.getString(1), c.getString(2), c.getString(5))
                }
            }
        }
        loadDay(date.minusDays(1), 86_400)
        loadDay(date, 0)

        // Percorsi RAPTOR = profili con almeno una corsa oggi, corse in ordine di partenza.
        val byProfile = trips.groupBy { it.profile }
        val routeIds = byProfile.keys.filter { p -> profStops[p]?.none { it < 0 } == true }
        val routeStopStart = IntArray(routeIds.size + 1)
        val routeTripStart = IntArray(routeIds.size + 1)
        routeIds.forEachIndexed { i, p ->
            routeStopStart[i + 1] = routeStopStart[i] + profStops[p]!!.size
            routeTripStart[i + 1] = routeTripStart[i] + byProfile[p]!!.size
        }
        val routeStops = IntArray(routeStopStart.last())
        val routeOffsets = IntArray(routeStopStart.last())
        val tripStart = IntArray(routeTripStart.last())
        val tripRef = IntArray(routeTripStart.last())
        routeIds.forEachIndexed { i, p ->
            profStops[p]!!.copyInto(routeStops, routeStopStart[i])
            profOffsets[p]!!.copyInto(routeOffsets, routeStopStart[i])
            byProfile[p]!!.sortedBy { it.start }.forEachIndexed { j, t ->
                tripStart[routeTripStart[i] + j] = t.start
                tripRef[routeTripStart[i] + j] = t.ref
            }
        }

        // Cambi a piedi reali dal grafo pedonale.
        val footLists = Array(stopIds.size) { ArrayList<Pair<Int, Int>>(4) }
        walk.rawQuery("SELECT from_stop, to_stop, meters FROM footpaths", null).use { c ->
            while (c.moveToNext()) {
                val f = stopIndex[c.getString(0)] ?: continue
                val t = stopIndex[c.getString(1)] ?: continue
                footLists[f] += t to (c.getInt(2) / WALK_SPEED_MPS).toInt()
            }
        }
        val footStart = IntArray(stopIds.size + 1)
        for (s in stopIds.indices) footStart[s + 1] = footStart[s] + footLists[s].size
        val footTo = IntArray(footStart.last())
        val footSec = IntArray(footStart.last())
        for (s in stopIds.indices) footLists[s].forEachIndexed { j, (t, sec) ->
            footTo[footStart[s] + j] = t
            footSec[footStart[s] + j] = sec
        }

        val types = gtfs.rawQuery("SELECT route_id, route_type FROM routes", null).use { c ->
            buildMap { while (c.moveToNext()) put(c.getString(0), c.getInt(1)) }
        }
        val routeType = IntArray(routeIds.size) { i ->
            types[tripInfos[byProfile[routeIds[i]]!!.first().ref].routeId] ?: 3
        }

        val tt = Timetable(stopIds.size, routeStopStart, routeStops, routeOffsets, routeTripStart, tripStart, tripRef, footStart, footTo, footSec)
        Log.i(TAG, "Orari di $date: ${routeIds.size} percorsi, ${tripStart.size} corse in ${(System.nanoTime() - started) / 1_000_000} ms")
        return DayTimetable(date, tt, stopIds, stopIndex, tripInfos.toTypedArray(), routeType)
    }
}
