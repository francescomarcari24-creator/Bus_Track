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
class GtfsStaticDb private constructor(private val db: SQLiteDatabase) : StaticLookup {

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
                SELECT t.trip_id, t.route_id, t.headsign, t.start_sec + ps.offset_sec AS sec
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

        /** Da chiamare fuori dal main thread: la prima volta copia ~7 MB. */
        fun open(context: Context): GtfsStaticDb {
            val file = File(context.noBackupFilesDir, ASSET_NAME)
            val appUpdatedAt = context.packageManager
                .getPackageInfo(context.packageName, 0).lastUpdateTime
            if (!file.exists() || file.lastModified() < appUpdatedAt) {
                val tmp = File(file.parentFile, "$ASSET_NAME.tmp")
                context.assets.open(ASSET_NAME).use { input ->
                    tmp.outputStream().use { input.copyTo(it) }
                }
                check(tmp.renameTo(file) || (file.delete() && tmp.renameTo(file))) {
                    "Impossibile installare $ASSET_NAME"
                }
            }
            val db = SQLiteDatabase.openDatabase(
                file.path,
                null,
                SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
            )
            return GtfsStaticDb(db)
        }
    }
}
