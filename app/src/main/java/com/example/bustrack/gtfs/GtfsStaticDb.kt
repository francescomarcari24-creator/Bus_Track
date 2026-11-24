package com.example.bustrack.gtfs

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** Interfaccia minima sui dati statici, così il parser RT è testabile senza Android. */
interface StaticLookup {
    fun route(routeId: String): Route?
    fun trip(tripId: String): Trip?
    fun stop(stopId: String): Stop?
}

/**
 * Accesso in sola lettura al GTFS statico di Roma, preconvertito in SQLite
 * (asset `gtfs.db`, generato da tools/build_gtfs_db.py).
 *
 * Al primo avvio, e dopo ogni aggiornamento dell'app, l'asset viene copiato
 * nella cartella privata perché SQLite non può aprire file direttamente dall'APK.
 */
class GtfsStaticDb private constructor(internal val db: SQLiteDatabase) : StaticLookup {

    private val routes: Map<String, Route> by lazy { loadRoutes() }
    private val tripCache = object : LinkedHashMap<String, Trip?>(512, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Trip?>) = size > 4000
    }

    override fun route(routeId: String): Route? = routes[routeId]

    fun allRoutes(): Collection<Route> = routes.values

    override fun trip(tripId: String): Trip? = synchronized(tripCache) {
        if (tripCache.containsKey(tripId)) return tripCache[tripId]
        val trip = db.rawQuery(
            "SELECT trip_id, route_id, headsign, direction_id FROM trips WHERE trip_id = ?",
            arrayOf(tripId),
        ).use { c ->
            if (c.moveToFirst()) Trip(
                id = c.getString(0),
                routeId = c.getString(1),
                headsign = c.getStringOrNull(2),
                directionId = if (c.isNull(3)) null else c.getInt(3),
            ) else null
        }
        tripCache[tripId] = trip
        trip
    }

    override fun stop(stopId: String): Stop? =
        db.rawQuery(STOP_SELECT + " WHERE stop_id = ?", arrayOf(stopId)).use { c ->
            if (c.moveToFirst()) c.toStop() else null
        }

    /** Cerca fermate per nome o codice palina. */
    fun searchStops(query: String, limit: Int = 50): List<Stop> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val like = "%" + q.replace("%", "").replace("_", "") + "%"
        return db.rawQuery(
            "$STOP_SELECT WHERE code = ? OR stop_id = ? OR name LIKE ? " +
                "ORDER BY (code = ?) DESC, name LIMIT ?",
            arrayOf(q, q, like, q, limit.toString()),
        ).use { c -> buildList { while (c.moveToNext()) add(c.toStop()) } }
    }

    /** Tracciati (uno per direzione) delle linee con questo numero, es. "64". Vuoto se non esistono. */
    fun routeShapes(label: String): List<RouteShape> {
        val routeIds = routes.values.filter { it.label.equals(label.trim(), ignoreCase = true) }.map { it.id }
        if (routeIds.isEmpty()) return emptyList()
        val placeholders = routeIds.joinToString(",") { "?" }
        return db.rawQuery(
            "SELECT route_id, direction_id, polyline, is_main FROM route_shapes WHERE route_id IN ($placeholders) " +
                "ORDER BY route_id, direction_id, is_main DESC, trip_count DESC",
            routeIds.toTypedArray(),
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    val (lat, lon) = Polyline.decode(c.getString(2))
                    add(RouteShape(c.getString(0), c.getInt(1), lat, lon, main = c.getInt(3) == 1))
                }
            }
        }
    }

    /** Tutte le fermate (per il livello "Fermate" della mappa). */
    fun allStops(): List<Stop> =
        db.rawQuery(STOP_SELECT, null).use { c -> buildList(c.count) { while (c.moveToNext()) add(c.toStop()) } }

    /** Id delle fermate servite dalle linee con questo numero (es. "64"); vuoto se la linea non esiste. */
    fun stopIdsForLine(label: String): Set<String> {
        val routeIds = routes.values.filter { it.label.equals(label.trim(), ignoreCase = true) }.map { it.id }
        if (routeIds.isEmpty()) return emptySet()
        val placeholders = routeIds.joinToString(",") { "?" }
        return db.rawQuery(
            "SELECT DISTINCT stop_id FROM stop_routes WHERE route_id IN ($placeholders)",
            routeIds.toTypedArray(),
        ).use { c -> buildSet { while (c.moveToNext()) add(c.getString(0)) } }
    }

    /** Linee che servono la fermata secondo l'orario programmato. */
    fun routesForStop(stopId: String): List<Route> =
        db.rawQuery("SELECT route_id FROM stop_routes WHERE stop_id = ?", arrayOf(stopId)).use { c ->
            buildList { while (c.moveToNext()) routes[c.getString(0)]?.let(::add) }
        }.sortedWith(compareBy({ it.label.length }, { it.label }))

    /**
     * Passaggi programmati alla fermata tra [fromEpoch] e [toEpoch] (epoch secondi).
     *
     * Gli orari GTFS sono relativi al "giorno di servizio" e possono superare le 24:00,
     * quindi si interrogano sia il giorno corrente sia quello precedente.
     */
    fun scheduledArrivals(stopId: String, fromEpoch: Long, toEpoch: Long, limit: Int = 30): List<Arrival> {
        val firstDay = Instant.ofEpochSecond(fromEpoch).atZone(ROME).toLocalDate().minusDays(1)
        val lastDay = Instant.ofEpochSecond(toEpoch).atZone(ROME).toLocalDate()
        val result = mutableListOf<Arrival>()
        var day = firstDay
        while (!day.isAfter(lastDay)) {
            // Riferimento GTFS: "mezzogiorno meno 12 ore", corretto anche nei giorni di cambio ora.
            val base = day.atTime(LocalTime.NOON).atZone(ROME).minusHours(12).toEpochSecond()
            val dateKey = day.year * 10000 + day.monthValue * 100 + day.dayOfMonth
            db.rawQuery(
                """
                SELECT t.trip_id, t.route_id, t.headsign, t.start_sec + ps.offset_sec AS sec, t.service_id
                FROM profile_stops ps
                JOIN trips t ON t.profile_id = ps.profile_id
                JOIN calendar_dates c ON c.service_id = t.service_id AND c.date = ?
                WHERE ps.stop_id = ? AND sec BETWEEN CAST(? AS INTEGER) AND CAST(? AS INTEGER)
                ORDER BY sec LIMIT ?
                """.trimIndent(),
                arrayOf(
                    dateKey.toString(), stopId,
                    (fromEpoch - base).toString(), (toEpoch - base).toString(), limit.toString(),
                ),
            ).use { c ->
                while (c.moveToNext()) {
                    val routeId = c.getString(1)
                    result += Arrival(
                        tripId = c.getString(0),
                        routeId = routeId,
                        routeLabel = routes[routeId]?.label ?: routeId,
                        headsign = c.getStringOrNull(2),
                        stopId = stopId,
                        time = base + c.getLong(3),
                        delaySeconds = null,
                        vehicleId = null,
                        isRealtime = false,
                        serviceId = c.getString(4),
                    )
                }
            }
            day = day.plusDays(1)
        }
        // Il feed di Roma sovrappone due versioni dell'orario (prefissi "0#" e "1#") e a volte
        // servizi diversi con la stessa corsa: stessa linea, destinazione e orario = un solo passaggio.
        return result.sortedBy { it.time }
            .distinctBy { Triple(it.routeId, it.headsign, it.time) }
            .take(limit)
    }

    /** service_id delle corse indicate (quelle sconosciute mancano dalla mappa). */
    fun serviceIds(tripIds: Collection<String>): Map<String, String> = buildMap {
        tripIds.distinct().chunked(500).forEach { chunk ->
            val placeholders = chunk.joinToString(",") { "?" }
            db.rawQuery(
                "SELECT trip_id, service_id FROM trips WHERE trip_id IN ($placeholders)",
                chunk.toTypedArray(),
            ).use { c -> while (c.moveToNext()) put(c.getString(0), c.getString(1)) }
        }
    }

    /** Linee il cui numero corrisponde alla ricerca: prima l'esatta, poi quelle che iniziano così. */
    fun searchRoutes(query: String, limit: Int = 8): List<Route> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        return routes.values
            .filter { it.label.startsWith(q, ignoreCase = true) }
            .sortedWith(compareBy({ !it.label.equals(q, ignoreCase = true) }, { it.label.length }, { it.label }))
            .take(limit)
    }

    /** Percorso tipico per ciascuna direzione della linea (1 per le circolari). */
    fun routePatterns(routeId: String): List<RoutePattern> =
        db.rawQuery(
            "SELECT direction_id, headsign, stop_ids FROM route_patterns WHERE route_id = ? ORDER BY direction_id",
            arrayOf(routeId),
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    val stopIds = c.getString(2).split(',')
                    add(
                        RoutePattern(
                            routeId = routeId,
                            directionId = c.getInt(0),
                            headsign = c.getStringOrNull(1),
                            stopIds = stopIds,
                            fromStopName = stop(stopIds.first())?.name,
                        )
                    )
                }
            }
        }

    /** Fermate nell'ordine dato; gli id sconosciuti vengono saltati. */
    fun stopsInOrder(stopIds: List<String>): List<Stop> {
        if (stopIds.isEmpty()) return emptyList()
        val placeholders = stopIds.joinToString(",") { "?" }
        val byId = db.rawQuery("$STOP_SELECT WHERE stop_id IN ($placeholders)", stopIds.toTypedArray()).use { c ->
            buildMap { while (c.moveToNext()) c.toStop().let { put(it.id, it) } }
        }
        return stopIds.mapNotNull(byId::get)
    }

    /**
     * Partenze programmate della linea, nella direzione data, dalla fermata [firstStopId]
     * (il capolinea del percorso tipico) nel giorno di servizio [date].
     * Le corse di varianti che partono da un'altra fermata non sono incluse.
     */
    fun departuresFromFirstStop(routeId: String, directionId: Int, firstStopId: String, date: LocalDate): List<Departure> {
        val base = date.atTime(LocalTime.NOON).atZone(ROME).minusHours(12).toEpochSecond()
        val dateKey = date.year * 10000 + date.monthValue * 100 + date.dayOfMonth
        return db.rawQuery(
            """
            SELECT t.trip_id, t.headsign, t.start_sec + ps.offset_sec AS sec
            FROM profile_stops ps
            JOIN trips t ON t.profile_id = ps.profile_id
            JOIN calendar_dates c ON c.service_id = t.service_id AND c.date = CAST(? AS INTEGER)
            WHERE ps.stop_id = ? AND ps.idx = 0 AND t.route_id = ?
              AND COALESCE(t.direction_id, 0) = CAST(? AS INTEGER)
            ORDER BY sec
            """.trimIndent(),
            arrayOf(dateKey.toString(), firstStopId, routeId, directionId.toString()),
        ).use { c ->
            buildList {
                while (c.moveToNext()) add(Departure(c.getString(0), base + c.getLong(2), c.getStringOrNull(1)))
            }
        }
            // Versioni sovrapposte dell'orario: stessa ora e destinazione = una sola partenza.
            .distinctBy { it.time to it.headsign }
    }

    fun meta(key: String): String? =
        db.rawQuery("SELECT value FROM meta WHERE key = ?", arrayOf(key)).use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }

    private fun loadRoutes(): Map<String, Route> =
        db.rawQuery(
            "SELECT r.route_id, r.short_name, r.long_name, r.route_type, r.agency_name FROM routes r",
            null,
        ).use { c ->
            buildMap {
                while (c.moveToNext()) {
                    val route = Route(
                        id = c.getString(0),
                        shortName = c.getStringOrNull(1),
                        longName = c.getStringOrNull(2),
                        type = c.getInt(3),
                        agencyName = c.getStringOrNull(4),
                    )
                    put(route.id, route)
                }
            }
        }

    private fun android.database.Cursor.toStop() = Stop(
        id = getString(0),
        code = getStringOrNull(1),
        name = getString(2),
        lat = getDouble(3),
        lon = getDouble(4),
    )

    private fun android.database.Cursor.getStringOrNull(i: Int): String? = if (isNull(i)) null else getString(i)

    companion object {
        private const val ASSET_NAME = "gtfs.db"
        private val ROME: ZoneId = ZoneId.of("Europe/Rome")
        private const val STOP_SELECT = "SELECT stop_id, code, name, lat, lon FROM stops"

        /** Database costruito sul telefono dall'aggiornamento automatico (vedi GtfsUpdater). */
        private const val DOWNLOADED_NAME = "gtfs-downloaded.db"
        /** Versione dello schema che questa versione dell'app sa leggere. */
        const val SCHEMA_VERSION = 5

        /** Dati essenziali di un database (tabella meta). */
        data class DbInfo(
            val schemaVersion: Int,
            val feedEndDate: String,
            val sourceMd5: String?,
            val builtAt: String,
        )

        fun readInfo(file: File): DbInfo? {
            if (!file.exists()) return null
            return runCatching {
                SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                    val meta = db.rawQuery("SELECT key, value FROM meta", null).use { c ->
                        buildMap { while (c.moveToNext()) put(c.getString(0), c.getString(1)) }
                    }
                    DbInfo(
                        schemaVersion = meta["schema_version"]?.toIntOrNull() ?: 0,
                        feedEndDate = meta["feed_end_date"].orEmpty(),
                        sourceMd5 = meta["source_md5"],
                        builtAt = meta["built_at"].orEmpty(),
                    )
                }
            }.getOrNull()
        }

        @Volatile private var shared: GtfsStaticDb? = null
        private val lock = Any()

        /** Istanza condivisa da tutta l'app (schermate e servizio di avviso). Fuori dal main thread. */
        fun shared(context: Context): GtfsStaticDb =
            shared ?: synchronized(lock) { shared ?: open(context.applicationContext).also { shared = it } }

        /** Info del database che l'app sta usando (o userebbe all'apertura). */
        fun currentInfo(context: Context): DbInfo? = readInfo(chooseFile(context.applicationContext))

        /**
         * Installa un database appena costruito e fa passare l'app al nuovo: la prossima richiesta
         * lo apre; il vecchio si chiude dopo un minuto, per non interrompere letture in corso.
         */
        fun installDownloaded(context: Context, built: File) {
            val target = File(context.noBackupFilesDir, DOWNLOADED_NAME)
            val tmp = File(target.path + ".tmp")
            built.copyTo(tmp, overwrite = true)
            check(tmp.renameTo(target) || (target.delete() && tmp.renameTo(target))) {
                "Impossibile installare $DOWNLOADED_NAME"
            }
            synchronized(lock) {
                val old = shared
                shared = null
                old?.let { db ->
                    java.util.concurrent.Executors.newSingleThreadScheduledExecutor().schedule(
                        { runCatching { db.db.close() } }, 60, java.util.concurrent.TimeUnit.SECONDS,
                    )
                }
            }
        }

        /**
         * Copia l'asset se manca o se l'app è stata aggiornata; restituisce il database più
         * recente tra quello incluso nell'app e quello scaricato.
         */
        private fun chooseFile(context: Context): File {
            val asset = File(context.noBackupFilesDir, ASSET_NAME)
            val appUpdatedAt = context.packageManager
                .getPackageInfo(context.packageName, 0).lastUpdateTime
            if (!asset.exists() || asset.lastModified() < appUpdatedAt) {
                val tmp = File(asset.parentFile, "$ASSET_NAME.tmp")
                context.assets.open(ASSET_NAME).use { input ->
                    tmp.outputStream().use { input.copyTo(it) }
                }
                check(tmp.renameTo(asset) || (asset.delete() && tmp.renameTo(asset))) {
                    "Impossibile installare $ASSET_NAME"
                }
            }
            // Lo scaricato vince se questa versione dell'app sa leggerlo ed è più recente
            // (validità più lunga, o stessa validità ma costruito dopo) di quello incluso.
            val downloaded = File(context.noBackupFilesDir, DOWNLOADED_NAME)
            val d = readInfo(downloaded)?.takeIf { it.schemaVersion == SCHEMA_VERSION } ?: return asset
            val a = readInfo(asset) ?: return downloaded
            val newer = d.feedEndDate > a.feedEndDate || (d.feedEndDate == a.feedEndDate && d.builtAt > a.builtAt)
            return if (newer) downloaded else asset
        }

        /** Da chiamare fuori dal main thread: la prima volta copia ~23 MB. */
        fun open(context: Context): GtfsStaticDb {
            val file = chooseFile(context)
            val db = SQLiteDatabase.openDatabase(
                file.path,
                null,
                SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
            )
            return GtfsStaticDb(db)
        }
    }
}
