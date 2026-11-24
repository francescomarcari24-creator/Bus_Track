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
    /** true = tracciato principale (il più frequente); false = variante del percorso. */
    val main: Boolean = true,
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
    /** service_id GTFS della corsa (solo per gli orari programmati). */
    val serviceId: String? = null,
)

/** Gruppo di servizio di un service_id di Roma: la parte prima del trattino ("0#57-4926" → "0#57"). */
fun serviceGroup(serviceId: String): String = serviceId.substringBefore('-')

/**
 * Toglie gli orari programmati "fantasma". Il GTFS di Roma a volte pubblica per la stessa linea
 * due servizi alternativi (es. linea 8, novembre 2026: `0#35` e `0#57`), ma i mezzi ne effettuano
 * uno solo. Se una linea ha corse live ([liveGroupsByRoute]: linea → gruppi di servizio delle sue
 * corse live), si tengono solo i programmati dei gruppi effettivamente in servizio; le linee senza
 * dati live restano com'erano.
 */
fun dropUntrackedSchedules(scheduled: List<Arrival>, liveGroupsByRoute: Map<String, Set<String>>): List<Arrival> =
    scheduled.filter { a ->
        val groups = a.routeId?.let { liveGroupsByRoute[it] }
        val service = a.serviceId
        groups.isNullOrEmpty() || service == null || serviceGroup(service) in groups
    }

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
    /** true se l'avviso cita almeno una di queste linee (per numero) o fermate (per id). */
    fun concerns(routeLabels: Collection<String>, stopIds: Collection<String>): Boolean =
        routeLabels.any { it in this.routeLabels } || stopIds.any { it in this.stopIds }

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
