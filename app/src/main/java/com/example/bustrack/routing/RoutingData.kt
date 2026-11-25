package com.example.bustrack.routing

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.File
import java.time.LocalDate

/** Velocità a piedi usata per tutti i tratti a piedi: 4,5 km/h. */
const val WALK_SPEED_MPS = 1.25

private const val TAG = "BusTrack"

/** Incrocio della rete più vicino a ogni fermata, negli indici di [day] (-1 se nessuno), e distanza. */
fun stopNodes(walk: WalkData, day: DayTimetable): Pair<IntArray, DoubleArray> {
    val node = IntArray(day.stopIds.size) { -1 }
    val gap = DoubleArray(day.stopIds.size)
    walk.stopIds.forEachIndexed { k, id ->
        val i = day.stopIndex[id] ?: return@forEachIndexed
        node[i] = walk.stopNode[k]
        gap[i] = walk.stopGap[k].toDouble()
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
    fun load(gtfs: SQLiteDatabase, walk: WalkData, date: LocalDate): DayTimetable {
        val started = System.nanoTime()
        val stopIds = gtfs.rawQuery("SELECT stop_id FROM stops", null).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }.toTypedArray()
        val stopIndex = indexOf(stopIds)

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
        for (k in walk.footFrom.indices) {
            val f = stopIndex[walk.stopIds[walk.footFrom[k]]] ?: continue
            val t = stopIndex[walk.stopIds[walk.footTo[k]]] ?: continue
            footLists[f] += t to (walk.footMeters[k] / WALK_SPEED_MPS).toInt()
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

    internal fun indexOf(stopIds: Array<String>): Map<String, Int> =
        HashMap<String, Int>(stopIds.size * 2).apply { stopIds.forEachIndexed { i, s -> put(s, i) } }
}

/**
 * Orari del giorno già pronti, salvati in un file alla prima preparazione: per il resto della
 * giornata il motore si prepara in una frazione di secondo invece di rileggere tutto il GTFS.
 * Il nome del file contiene giorno e versione dei dati (GTFS e grafo): se cambiano si ricostruisce.
 */
object TimetableCache {
    private const val FORMAT = 2
    private const val PREFIX = "timetable-"

    private fun file(context: Context, date: LocalDate, dataKey: String) =
        File(context.cacheDir, "$PREFIX$date-${dataKey.hashCode().toUInt()}-v$FORMAT.bin")

    fun load(context: Context, date: LocalDate, dataKey: String): DayTimetable? {
        val f = file(context, date, dataKey)
        if (!f.exists()) return null
        return runCatching {
            // Tutto il file in memoria e letto a blocchi: molto più veloce che numero per numero.
            read(java.nio.ByteBuffer.wrap(f.readBytes()), date)
        }.onFailure { Log.e(TAG, "Orari salvati illeggibili", it); f.delete() }.getOrNull()
    }

    fun save(context: Context, day: DayTimetable, dataKey: String) {
        val f = file(context, day.date, dataKey)
        // Si tengono solo gli orari di oggi.
        context.cacheDir.listFiles { x -> x.name.startsWith(PREFIX) && x.name != f.name }?.forEach { it.delete() }
        val tmp = File(f.path + ".tmp")
        runCatching {
            DataOutputStream(BufferedOutputStream(tmp.outputStream(), 1 shl 16)).use { write(it, day) }
            check(tmp.renameTo(f))
        }.onFailure { Log.e(TAG, "Salvataggio degli orari", it); tmp.delete() }
    }

    private fun write(o: DataOutputStream, d: DayTimetable) {
        fun ints(a: IntArray) {
            o.writeInt(a.size)
            val buf = java.nio.ByteBuffer.allocate(4 * a.size)
            buf.asIntBuffer().put(a)
            o.write(buf.array())
        }
        fun str(s: String) {
            val bytes = s.toByteArray(Charsets.UTF_8)
            o.writeShort(bytes.size)
            o.write(bytes)
        }
        val t = d.timetable
        o.writeInt(d.stopIds.size); d.stopIds.forEach(::str)
        ints(t.routeStopStart); ints(t.routeStops); ints(t.routeOffsets)
        ints(t.routeTripStart); ints(t.tripStart); ints(t.tripRef)
        ints(t.footStart); ints(t.footTo); ints(t.footSec)
        ints(d.routeType)
        o.writeInt(d.trips.size)
        d.trips.forEach { tr ->
            str(tr.tripId); str(tr.routeId); str(tr.headsign ?: ""); str(tr.serviceId ?: "")
        }
    }

    private fun read(b: java.nio.ByteBuffer, date: LocalDate): DayTimetable {
        fun ints(): IntArray {
            val n = b.int
            return IntArray(n).also { b.asIntBuffer().get(it); b.position(b.position() + 4 * n) }
        }
        val raw = b.array()
        fun str(): String {
            val len = b.short.toInt() and 0xFFFF
            val s = String(raw, b.position(), len, Charsets.UTF_8)
            b.position(b.position() + len)
            return s
        }
        val stopIds = Array(b.int) { str() }
        val routeStopStart = ints(); val routeStops = ints(); val routeOffsets = ints()
        val routeTripStart = ints(); val tripStart = ints(); val tripRef = ints()
        val footStart = ints(); val footTo = ints(); val footSec = ints()
        val routeType = ints()
        val trips = Array(b.int) {
            TripInfo(str(), str(), str().ifEmpty { null }, str().ifEmpty { null })
        }
        val tt = Timetable(stopIds.size, routeStopStart, routeStops, routeOffsets, routeTripStart, tripStart, tripRef, footStart, footTo, footSec)
        return DayTimetable(date, tt, stopIds, TimetableLoader.indexOf(stopIds), trips, routeType)
    }
}
