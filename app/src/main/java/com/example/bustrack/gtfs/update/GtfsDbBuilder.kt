package com.example.bustrack.gtfs.update

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteStatement
import android.util.Log
import java.io.File
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.zip.ZipFile

/**
 * Costruisce sul telefono il database dell'app dallo zip GTFS di Roma Mobilità: è la traduzione
 * di tools/build_gtfs_db.py (stesso schema, versione [SCHEMA_VERSION]), ma senza tenere in memoria
 * stop_times.txt (5 milioni di righe): le righe di una corsa sono consecutive nel file, quindi si
 * elabora una corsa alla volta.
 */
internal class GtfsDbBuilder(private val zipFile: File, private val sourceMd5: String) {

    /** Avanzamento per il log: fase corrente. */
    var onProgress: (String) -> Unit = {}

    fun build(out: File) {
        out.delete()
        File(out.path + "-journal").delete()
        val started = System.nanoTime()
        val db = SQLiteDatabase.openOrCreateDatabase(out, null)
        try {
            db.rawQuery("PRAGMA journal_mode = OFF", null).close()
            db.execSQL("PRAGMA synchronous = OFF")
            createSchema(db)
            ZipFile(zipFile).use { zip -> fill(db, zip) }
            db.execSQL("CREATE INDEX stops_name ON stops(name COLLATE NOCASE)")
            db.execSQL("CREATE INDEX trips_profile ON trips(profile_id)")
            onProgress("compattazione")
            db.execSQL("VACUUM")
        } finally {
            db.close()
        }
        Log.i(TAG, "Database costruito in ${(System.nanoTime() - started) / 1_000_000_000} s (${out.length() / 1_000_000} MB)")
    }

    private fun createSchema(db: SQLiteDatabase) {
        listOf(
            "CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT NOT NULL) WITHOUT ROWID",
            """CREATE TABLE routes (route_id TEXT PRIMARY KEY, short_name TEXT, long_name TEXT,
                route_type INTEGER NOT NULL, color TEXT, text_color TEXT, agency_name TEXT) WITHOUT ROWID""",
            """CREATE TABLE stops (stop_id TEXT PRIMARY KEY, code TEXT, name TEXT NOT NULL,
                lat REAL NOT NULL, lon REAL NOT NULL) WITHOUT ROWID""",
            """CREATE TABLE trips (trip_id TEXT PRIMARY KEY, route_id TEXT NOT NULL, headsign TEXT,
                direction_id INTEGER, service_id TEXT NOT NULL, profile_id INTEGER, start_sec INTEGER) WITHOUT ROWID""",
            """CREATE TABLE profile_stops (stop_id TEXT NOT NULL, profile_id INTEGER NOT NULL, idx INTEGER NOT NULL,
                offset_sec INTEGER NOT NULL, PRIMARY KEY (stop_id, profile_id, idx)) WITHOUT ROWID""",
            """CREATE TABLE route_patterns (route_id TEXT NOT NULL, direction_id INTEGER NOT NULL, headsign TEXT,
                trip_count INTEGER NOT NULL, stop_ids TEXT NOT NULL, PRIMARY KEY (route_id, direction_id)) WITHOUT ROWID""",
            """CREATE TABLE route_shapes (route_id TEXT NOT NULL, direction_id INTEGER NOT NULL, shape_id TEXT NOT NULL,
                trip_count INTEGER NOT NULL, is_main INTEGER NOT NULL, polyline TEXT NOT NULL,
                PRIMARY KEY (route_id, direction_id, shape_id)) WITHOUT ROWID""",
            """CREATE TABLE calendar_dates (date INTEGER NOT NULL, service_id TEXT NOT NULL,
                PRIMARY KEY (date, service_id)) WITHOUT ROWID""",
            """CREATE TABLE stop_routes (stop_id TEXT NOT NULL, route_id TEXT NOT NULL,
                PRIMARY KEY (stop_id, route_id)) WITHOUT ROWID""",
        ).forEach(db::execSQL)
    }

    private class TripInfo(
        val routeId: String,
        val headsign: String?,
        val direction: Int?,
        val serviceId: String,
        val shapeId: String?,
        var done: Boolean = false,
    )

    /** Sequenza di interi confrontabile per contenuto (chiave di profili e percorsi). */
    private class IntKey(val values: IntArray) {
        private val hash = values.contentHashCode()
        override fun hashCode() = hash
        override fun equals(other: Any?) = other is IntKey && other.values.contentEquals(values)
    }

    private class PatternAcc {
        var count = 0
        val headsigns = LinkedHashMap<String?, Int>()
        val shapes = LinkedHashMap<String, Int>()
    }

    private fun fill(db: SQLiteDatabase, zip: ZipFile) {
        fun open(name: String) = CsvReader(zip.getInputStream(zip.getEntry(name) ?: error("$name mancante nello zip")))

        // --- agenzie e linee
        onProgress("linee")
        val agencies = HashMap<String, String>()
        open("agency.txt").use { r ->
            val id = r.index("agency_id"); val name = r.require("agency_name")
            while (r.next()) agencies[r.field(id)] = r.field(name)
        }
        transaction(db) {
            val st = db.compileStatement("INSERT INTO routes VALUES (?,?,?,?,?,?,?)")
            open("routes.txt").use { r ->
                val id = r.require("route_id"); val short = r.index("route_short_name"); val long = r.index("route_long_name")
                val type = r.require("route_type"); val color = r.index("route_color"); val text = r.index("route_text_color")
                val agency = r.index("agency_id")
                while (r.next()) {
                    st.bindAll(
                        r.field(id), r.fieldOrNull(short), r.fieldOrNull(long), r.int(type).toLong(),
                        r.fieldOrNull(color), r.fieldOrNull(text), agencies[r.field(agency)],
                    )
                    st.executeInsert()
                }
            }
        }

        // --- fermate (con un indice numerico per le chiavi compatte)
        onProgress("fermate")
        val stopIndex = HashMap<String, Int>()
        val stopIds = ArrayList<String>()
        transaction(db) {
            val st = db.compileStatement("INSERT INTO stops VALUES (?,?,?,?,?)")
            open("stops.txt").use { r ->
                val id = r.require("stop_id"); val code = r.index("stop_code"); val name = r.require("stop_name")
                val lat = r.require("stop_lat"); val lon = r.require("stop_lon")
                while (r.next()) {
                    val stopId = r.field(id)
                    st.bindAll(stopId, r.fieldOrNull(code), r.field(name).trim(), r.field(lat).toDouble(), r.field(lon).toDouble())
                    st.executeInsert()
                    if (stopIndex.putIfAbsent(stopId, stopIds.size) == null) stopIds += stopId
                }
            }
        }
        fun stopIdx(id: String): Int = stopIndex.getOrPut(id) { stopIds.add(id); stopIds.size - 1 }

        // --- corse
        onProgress("corse")
        val trips = HashMap<String, TripInfo>(200_000)
        open("trips.txt").use { r ->
            val id = r.require("trip_id"); val route = r.require("route_id"); val headsign = r.index("trip_headsign")
            val dir = r.index("direction_id"); val service = r.require("service_id"); val shape = r.index("shape_id")
            while (r.next()) {
                trips[r.field(id)] = TripInfo(
                    routeId = r.field(route),
                    headsign = r.fieldOrNull(headsign),
                    direction = if (r.isBlank(dir)) null else r.int(dir),
                    serviceId = r.field(service),
                    shapeId = r.fieldOrNull(shape),
                )
            }
        }
        val routeIndex = HashMap<String, Int>()
        val routeIds = ArrayList<String>()
        fun routeIdx(id: String) = routeIndex.getOrPut(id) { routeIds.add(id); routeIds.size - 1 }

        // --- orari: una corsa alla volta
        onProgress("orari")
        val profiles = HashMap<IntKey, Int>(100_000)
        val pairs = HashSet<Long>(400_000)
        val patterns = LinkedHashMap<Pair<String, Int>, LinkedHashMap<IntKey, PatternAcc>>()
        val shapeTrips = LinkedHashMap<Pair<String, Int>, LinkedHashMap<String, Int>>()
        transaction(db) {
            val tripSt = db.compileStatement("INSERT INTO trips VALUES (?,?,?,?,?,?,?)")
            val profileSt = db.compileStatement("INSERT OR IGNORE INTO profile_stops VALUES (?,?,?,?)")
            var curTrip: String? = null
            var seq = IntArray(128)
            var stops = IntArray(128)
            var secs = IntArray(128)
            var n = 0

            fun flush() {
                val tripId = curTrip ?: return
                val info = trips[tripId]
                if (info == null || n == 0) { n = 0; return }
                info.done = true
                // Di norma già in ordine di stop_sequence; se no si ordina.
                var sorted = true
                for (i in 1 until n) if (seq[i] < seq[i - 1]) { sorted = false; break }
                if (!sorted) {
                    val order = (0 until n).sortedBy { seq[it] }
                    stops = IntArray(stops.size).also { a -> order.forEachIndexed { k, i -> a[k] = stops[i] } }
                    secs = IntArray(secs.size).also { a -> order.forEachIndexed { k, i -> a[k] = secs[i] } }
                }
                val start = secs[0]
                val key = IntArray(n * 2)
                for (i in 0 until n) { key[2 * i] = stops[i]; key[2 * i + 1] = secs[i] - start }
                val profileKey = IntKey(key)
                var profileId = profiles[profileKey]
                if (profileId == null) {
                    profileId = profiles.size
                    profiles[profileKey] = profileId
                    for (i in 0 until n) {
                        profileSt.bindAll(stopIds[stops[i]], profileId.toLong(), i.toLong(), (secs[i] - start).toLong())
                        profileSt.executeInsert()
                    }
                }
                val r = routeIdx(info.routeId).toLong()
                for (i in 0 until n) pairs += (stops[i].toLong() shl 32) or r
                val routeDir = info.routeId to (info.direction ?: 0)
                val acc = patterns.getOrPut(routeDir) { LinkedHashMap() }
                    .getOrPut(IntKey(stops.copyOf(n))) { PatternAcc() }
                acc.count++
                acc.headsigns.merge(info.headsign, 1, Int::plus)
                info.shapeId?.let { shape ->
                    acc.shapes.merge(shape, 1, Int::plus)
                    shapeTrips.getOrPut(routeDir) { LinkedHashMap() }.merge(shape, 1, Int::plus)
                }
                tripSt.bindAll(tripId, info.routeId, info.headsign, info.direction?.toLong(), info.serviceId, profileId.toLong(), start.toLong())
                tripSt.executeInsert()
                n = 0
            }

            open("stop_times.txt").use { r ->
                val tripCol = r.require("trip_id"); val arrival = r.require("arrival_time"); val departure = r.require("departure_time")
                val stopCol = r.require("stop_id"); val seqCol = r.require("stop_sequence")
                var rows = 0
                while (r.next()) {
                    if (++rows % 1_000_000 == 0) onProgress("orari: ${rows / 1_000_000} milioni di righe")
                    var t = r.seconds(arrival)
                    if (t < 0) t = r.seconds(departure)
                    if (t < 0) continue
                    val tripId = r.field(tripCol)
                    if (tripId != curTrip) { flush(); curTrip = tripId }
                    if (n == seq.size) { seq = seq.copyOf(n * 2); stops = stops.copyOf(n * 2); secs = secs.copyOf(n * 2) }
                    seq[n] = r.int(seqCol); stops[n] = stopIdx(r.field(stopCol)); secs[n] = t
                    n++
                }
                flush()
            }
            // Corse senza orari: come nello script, senza profilo.
            for ((tripId, info) in trips) {
                if (info.done) continue
                tripSt.bindAll(tripId, info.routeId, info.headsign, info.direction?.toLong(), info.serviceId, null, null)
                tripSt.executeInsert()
            }
            val pairSt = db.compileStatement("INSERT INTO stop_routes VALUES (?,?)")
            for (p in pairs) {
                pairSt.bindAll(stopIds[(p ushr 32).toInt()], routeIds[(p and 0xFFFFFFFFL).toInt()])
                pairSt.executeInsert()
            }
        }
        trips.clear()
        profiles.clear()

        // --- percorso tipico per linea e direzione
        onProgress("percorsi")
        val mainShape = HashMap<Pair<String, Int>, String>()
        transaction(db) {
            val st = db.compileStatement("INSERT INTO route_patterns VALUES (?,?,?,?,?)")
            for ((routeDir, seqs) in patterns) {
                val (seqKey, acc) = seqs.entries.maxBy { it.value.count }.toPair()
                val headsign = acc.headsigns.entries.maxBy { it.value }.key
                val total = seqs.values.sumOf { it.count }
                st.bindAll(routeDir.first, routeDir.second.toLong(), headsign, total.toLong(),
                    seqKey.values.joinToString(",") { stopIds[it] })
                st.executeInsert()
                acc.shapes.entries.maxByOrNull { it.value }?.let { mainShape[routeDir] = it.key }
            }
        }
        patterns.clear()

        // --- tracciati: principale + varianti frequenti
        onProgress("tracciati")
        val toSave = LinkedHashMap<Pair<String, Int>, List<Triple<String, Int, Boolean>>>()
        for ((routeDir, counts) in shapeTrips) {
            val main = mainShape[routeDir]
            val total = counts.values.sum()
            val variants = counts.entries
                .filter { (id, c) -> id != main && c >= VARIANT_MIN_TRIPS && c >= VARIANT_MIN_SHARE * total }
                .sortedByDescending { it.value }
                .take(VARIANT_MAX)
                .map { it.key }
            toSave[routeDir] = (listOfNotNull(main) + variants).map { Triple(it, counts[it] ?: 0, it == main) }
        }
        val needed = toSave.values.flatten().mapTo(HashSet()) { it.first }
        val points = HashMap<String, MutableList<Triple<Int, Double, Double>>>()
        open("shapes.txt").use { r ->
            val id = r.require("shape_id"); val seq = r.require("shape_pt_sequence")
            val lat = r.require("shape_pt_lat"); val lon = r.require("shape_pt_lon")
            while (r.next()) {
                val shape = r.field(id)
                if (shape !in needed) continue
                points.getOrPut(shape) { ArrayList() } += Triple(r.int(seq), r.field(lat).toDouble(), r.field(lon).toDouble())
            }
        }
        transaction(db) {
            val st = db.compileStatement("INSERT INTO route_shapes VALUES (?,?,?,?,?,?)")
            val encoded = HashMap<String, String>()
            for ((routeDir, list) in toSave) {
                for ((shapeId, count, isMain) in list) {
                    val polyline = encoded.getOrPut(shapeId) {
                        val pts = points[shapeId].orEmpty().sortedBy { it.first }
                        if (pts.size < 2) "" else {
                            val la = DoubleArray(pts.size) { pts[it].second }
                            val lo = DoubleArray(pts.size) { pts[it].third }
                            ShapeTools.encode(la, lo, ShapeTools.simplify(la, lo))
                        }
                    }
                    if (polyline.isEmpty()) continue
                    st.bindAll(routeDir.first, routeDir.second.toLong(), shapeId, count.toLong(), if (isMain) 1L else 0L, polyline)
                    st.executeInsert()
                }
            }
        }

        // --- calendario
        onProgress("calendario")
        var minDate: String? = null
        var maxDate: String? = null
        transaction(db) {
            val st = db.compileStatement("INSERT OR IGNORE INTO calendar_dates VALUES (?,?)")
            open("calendar_dates.txt").use { r ->
                val date = r.require("date"); val service = r.require("service_id"); val type = r.require("exception_type")
                while (r.next()) {
                    val d = r.field(date).trim()
                    if (minDate == null || d < minDate!!) minDate = d
                    if (maxDate == null || d > maxDate!!) maxDate = d
                    if (r.field(type).trim() == "1") {
                        st.bindAll(d.toLong(), r.field(service))
                        st.executeInsert()
                    }
                }
            }
        }

        transaction(db) {
            val st = db.compileStatement("INSERT INTO meta VALUES (?,?)")
            listOf(
                "schema_version" to SCHEMA_VERSION.toString(),
                "feed_start_date" to (minDate ?: ""),
                "feed_end_date" to (maxDate ?: ""),
                "built_at" to Instant.now().truncatedTo(ChronoUnit.SECONDS).toString(),
                "source_md5" to sourceMd5,
                "built_on" to "device",
            ).forEach { (k, v) -> st.bindAll(k, v); st.executeInsert() }
        }
    }

    private inline fun transaction(db: SQLiteDatabase, block: () -> Unit) {
        db.beginTransaction()
        try {
            block()
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun SQLiteStatement.bindAll(vararg values: Any?) {
        clearBindings()
        values.forEachIndexed { i, v ->
            when (v) {
                null -> bindNull(i + 1)
                is String -> bindString(i + 1, v)
                is Long -> bindLong(i + 1, v)
                is Int -> bindLong(i + 1, v.toLong())
                is Double -> bindDouble(i + 1, v)
                else -> bindString(i + 1, v.toString())
            }
        }
    }

    companion object {
        private const val TAG = "BusTrack"
        const val SCHEMA_VERSION = 5
        // Come nello script: varianti con almeno il 2% delle corse (e 2 corse), al massimo 5.
        private const val VARIANT_MIN_SHARE = 0.02
        private const val VARIANT_MIN_TRIPS = 2
        private const val VARIANT_MAX = 5
    }
}
