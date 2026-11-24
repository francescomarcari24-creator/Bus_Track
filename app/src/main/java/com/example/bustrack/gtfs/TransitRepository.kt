package com.example.bustrack.gtfs

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/** Feed GTFS-Realtime pubblici di Roma Mobilità (aggiornati ogni ~30 s). */
object RomeFeeds {
    private const val BASE = "https://romamobilita.it/wp-content/uploads/shared/"
    const val TRIP_UPDATES = BASE + "rome_rtgtfs_trip_updates_feed.pb"
    const val VEHICLE_POSITIONS = BASE + "rome_rtgtfs_vehicle_positions_feed.pb"
    const val SERVICE_ALERTS = BASE + "rome_rtgtfs_service_alerts_feed.pb"
}

private const val TAG = "BusTrack"

class TransitRepository(private val context: Context) {

    private val openMutex = Mutex()
    @Volatile private var staticDb: GtfsStaticDb? = null
    @Volatile private var parser: GtfsRealtimeParser? = null

    suspend fun static(): GtfsStaticDb = staticDb ?: openMutex.withLock {
        staticDb ?: withContext(Dispatchers.IO) {
            GtfsStaticDb.open(context).also {
                parser = GtfsRealtimeParser(it)
                staticDb = it
            }
        }
    }

    private suspend fun parser(): GtfsRealtimeParser {
        static()
        return parser!!
    }

    suspend fun searchStops(query: String): List<Stop> =
        withContext(Dispatchers.IO) { static().searchStops(query) }

    suspend fun route(routeId: String): Route? =
        withContext(Dispatchers.IO) { static().route(routeId) }

    suspend fun departures(pattern: RoutePattern, date: java.time.LocalDate): List<Departure> =
        withContext(Dispatchers.IO) {
            val first = pattern.stopIds.firstOrNull() ?: return@withContext emptyList()
            static().departuresFromFirstStop(pattern.routeId, pattern.directionId, first, date)
        }

    suspend fun searchRoutes(query: String): List<Route> =
        withContext(Dispatchers.IO) { static().searchRoutes(query) }

    suspend fun routePatterns(routeId: String): List<RoutePattern> =
        withContext(Dispatchers.IO) { static().routePatterns(routeId) }

    suspend fun stopsInOrder(stopIds: List<String>): List<Stop> =
        withContext(Dispatchers.IO) { static().stopsInOrder(stopIds) }

    @Volatile private var allStopsCache: List<Stop>? = null

    /** Tutte le fermate, lette una volta sola. */
    suspend fun allStops(): List<Stop> = allStopsCache ?: withContext(Dispatchers.IO) {
        static().allStops().also { allStopsCache = it }
    }

    suspend fun routeShapes(label: String): List<RouteShape> =
        withContext(Dispatchers.IO) { static().routeShapes(label) }

    suspend fun stopIdsForLine(label: String): Set<String> =
        withContext(Dispatchers.IO) { static().stopIdsForLine(label) }

    suspend fun routesForStop(stopId: String): List<Route> =
        withContext(Dispatchers.IO) { static().routesForStop(stopId) }

    /**
     * Prossimi arrivi alla fermata: previsioni live dove disponibili, altrimenti
     * orari programmati. Le corse presenti nel feed live sostituiscono il loro orario
     * programmato; se il feed non risponde si mostra solo l'orario programmato.
     */
    suspend fun arrivalsAt(
        stopId: String,
        nowEpochSeconds: Long = System.currentTimeMillis() / 1000,
        windowSeconds: Long = 2 * 3600,
    ): StopArrivals {
        val p = parser()
        val db = static()
        return withContext(Dispatchers.IO) {
            val snapshot = runCatching { fetch(RomeFeeds.TRIP_UPDATES) { p.parseTripUpdates(it) } }
            val live = snapshot.map { s ->
                s.items.filter { it.stopId == stopId && it.time >= nowEpochSeconds - 60 }
            }
            val liveArrivals = live.getOrDefault(emptyList())
            val liveTrips = liveArrivals.mapTo(HashSet()) { it.tripId }
            // Orario programmato delle corse live (previsione meno ritardo), per scartare
            // i duplicati dell'orario che hanno un trip_id diverso da quello del feed live.
            val liveScheduled = liveArrivals.mapNotNull { a ->
                a.delaySeconds?.let { Triple(a.routeId, a.headsign, a.time - it) }
            }
            val scheduled = db.scheduledArrivals(stopId, nowEpochSeconds - 60, nowEpochSeconds + windowSeconds)
                .filter { s ->
                    s.tripId !in liveTrips && liveScheduled.none { (route, headsign, time) ->
                        route == s.routeId && headsign == s.headsign && kotlin.math.abs(time - s.time) < 60
                    }
                }
            StopArrivals(
                arrivals = (liveArrivals + scheduled).sortedBy { it.time },
                realtimeAvailable = live.isSuccess,
                feedTimestamp = snapshot.getOrNull()?.feedTimestamp,
            )
        }
    }

    suspend fun vehiclePositions(): RealtimeSnapshot<VehiclePosition> {
        val p = parser()
        return withContext(Dispatchers.IO) { fetch(RomeFeeds.VEHICLE_POSITIONS) { p.parseVehiclePositions(it) } }
    }

    suspend fun serviceAlerts(): RealtimeSnapshot<ServiceAlert> {
        val p = parser()
        return withContext(Dispatchers.IO) { fetch(RomeFeeds.SERVICE_ALERTS) { p.parseServiceAlerts(it) } }
    }

    private fun <T> fetch(url: String, parse: (InputStream) -> T): T {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("Accept", "application/x-protobuf, application/octet-stream")
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) throw IOException("HTTP $code da $url")
            val started = System.nanoTime()
            return conn.inputStream.buffered().use(parse).also {
                Log.d(TAG, "Scaricato ${url.substringAfterLast('/')} in ${(System.nanoTime() - started) / 1_000_000} ms")
            }
        } finally {
            conn.disconnect()
        }
    }
}
