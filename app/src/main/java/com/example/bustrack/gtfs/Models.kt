package com.example.bustrack.gtfs

/** Dati statici (dal GTFS statico, vedi tools/build_gtfs_db.py). */
data class Route(
    val id: String,
    val shortName: String?,
    val longName: String?,
    val type: Int,
    val agencyName: String?,
) {
    val label: String get() = shortName ?: longName ?: id
}

data class Stop(
    val id: String,
    val code: String?,
    val name: String,
    val lat: Double,
    val lon: Double,
)

data class Trip(
    val id: String,
    val routeId: String,
    val headsign: String?,
    val directionId: Int?,
)

/** Percorso tipico di una linea in una direzione (dal GTFS statico). */
data class RoutePattern(
    val routeId: String,
    val directionId: Int,
    val headsign: String?,
    val stopIds: List<String>,
    /** Nome della prima fermata, per descrivere la direzione ("da ..."). */
    val fromStopName: String?,
)

/** Partenza programmata dal capolinea (prima fermata del percorso). */
data class Departure(
    val tripId: String,
    /** Epoch secondi. */
    val time: Long,
    val headsign: String?,
)

/** Tracciato di una linea in una direzione: coordinate in due array paralleli (lat, lon). */
class RouteShape(
    val routeId: String,
    val directionId: Int,
    val lat: DoubleArray,
    val lon: DoubleArray,
)

/** Stato del mezzo rispetto a [VehiclePosition.currentStopId] (GTFS-RT VehicleStopStatus). */
enum class VehicleStopStatus { INCOMING_AT, STOPPED_AT, IN_TRANSIT_TO }

/** Arrivo a una fermata: previsione live (TripUpdates) oppure orario programmato. */
data class Arrival(
    val tripId: String,
    val routeId: String?,
    val routeLabel: String,
    val headsign: String?,
    val stopId: String,
    /** Orario previsto, epoch secondi. */
    val time: Long,
    /** Ritardo in secondi rispetto all'orario programmato (null se non fornito). */
    val delaySeconds: Int?,
    val vehicleId: String?,
    /** true = previsione dal feed live, false = orario programmato dal GTFS statico. */
    val isRealtime: Boolean = true,
)

data class StopArrivals(
    val arrivals: List<Arrival>,
    /** false se il feed live non era raggiungibile: si mostrano solo orari programmati. */
    val realtimeAvailable: Boolean,
    /** Timestamp del feed live usato (epoch secondi), per agganciare l'aggiornamento alla centrale. */
    val feedTimestamp: Long? = null,
)

/** Posizione di un mezzo, dal feed VehiclePositions. */
data class VehiclePosition(
    val vehicleId: String,
    val label: String?,
    val tripId: String?,
    val routeId: String?,
    val routeLabel: String,
    val headsign: String?,
    val directionId: Int?,
    val lat: Double,
    val lon: Double,
    val bearing: Float?,
    val currentStopId: String?,
    val currentStopName: String?,
    val currentStatus: VehicleStopStatus?,
    /** Epoch secondi dell'ultima posizione. */
    val timestamp: Long?,
)

/** Avviso di servizio, dal feed ServiceAlerts. */
data class ServiceAlert(
    val id: String,
    val header: String,
    val description: String?,
    val routeLabels: List<String>,
    val stopIds: List<String>,
    /** Periodi attivi (epoch secondi); null = aperto. */
    val activePeriods: List<Pair<Long?, Long?>>,
) {
    fun isActiveAt(epochSeconds: Long): Boolean =
        activePeriods.isEmpty() || activePeriods.any { (start, end) ->
            (start == null || epochSeconds >= start) && (end == null || epochSeconds <= end)
        }
}

data class RealtimeSnapshot<T>(
    /** Timestamp dell'header del feed, epoch secondi. */
    val feedTimestamp: Long,
    val items: List<T>,
)
