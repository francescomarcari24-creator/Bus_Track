package com.example.bustrack.gtfs

import com.google.transit.realtime.GtfsRealtime.FeedMessage
import com.google.transit.realtime.GtfsRealtime.TranslatedString
import com.google.transit.realtime.GtfsRealtime.TripDescriptor
import com.google.transit.realtime.GtfsRealtime.TripUpdate.StopTimeUpdate.ScheduleRelationship
import java.io.InputStream

/**
 * Converte i feed GTFS-Realtime (protobuf) in modelli dell'app,
 * arricchendoli con nomi di linee, capolinea e fermate presi dal GTFS statico.
 */
class GtfsRealtimeParser(private val static: StaticLookup) {

    fun parseTripUpdates(input: InputStream): RealtimeSnapshot<Arrival> {
        val feed = FeedMessage.parseFrom(input)
        val arrivals = feed.entityList.asSequence()
            .filter { it.hasTripUpdate() && !it.isDeleted }
            .flatMap { entity ->
                val update = entity.tripUpdate
                val trip = update.trip
                val info = resolveTrip(trip)
                val vehicleId = if (update.hasVehicle()) update.vehicle.id.ifBlank { null } else null
                update.stopTimeUpdateList.asSequence()
                    .filter { it.scheduleRelationship != ScheduleRelationship.SKIPPED && it.stopId.isNotEmpty() }
                    .mapNotNull { stu ->
                        val event = when {
                            stu.hasArrival() && stu.arrival.hasTime() -> stu.arrival
                            stu.hasDeparture() && stu.departure.hasTime() -> stu.departure
                            else -> return@mapNotNull null
                        }
                        Arrival(
                            tripId = trip.tripId,
                            routeId = info.routeId,
                            routeLabel = info.routeLabel,
                            headsign = info.headsign,
                            stopId = stu.stopId,
                            time = event.time,
                            delaySeconds = if (event.hasDelay()) event.delay else null,
                            vehicleId = vehicleId,
                        )
                    }
            }
            .toList()
        return RealtimeSnapshot(feed.header.timestamp, arrivals)
    }

    fun parseVehiclePositions(input: InputStream): RealtimeSnapshot<VehiclePosition> {
        val feed = FeedMessage.parseFrom(input)
        val vehicles = feed.entityList
            .filter { it.hasVehicle() && !it.isDeleted && it.vehicle.hasPosition() }
            .map { entity ->
                val v = entity.vehicle
                val info = if (v.hasTrip()) resolveTrip(v.trip) else TripInfo(null, "?", null, null)
                val stopId = v.stopId.ifBlank { null }
                VehiclePosition(
                    vehicleId = v.vehicle.id.ifBlank { entity.id },
                    label = v.vehicle.label.ifBlank { null },
                    tripId = if (v.hasTrip()) v.trip.tripId.ifBlank { null } else null,
                    routeId = info.routeId,
                    routeLabel = info.routeLabel,
                    headsign = info.headsign,
                    directionId = info.directionId,
                    lat = v.position.latitude.toDouble(),
                    lon = v.position.longitude.toDouble(),
                    bearing = if (v.position.hasBearing()) v.position.bearing else null,
                    currentStopId = stopId,
                    currentStopName = stopId?.let { static.stop(it)?.name },
                    currentStatus = if (v.hasCurrentStatus()) VehicleStopStatus.valueOf(v.currentStatus.name) else null,
                    timestamp = if (v.hasTimestamp()) v.timestamp else null,
                )
            }
        return RealtimeSnapshot(feed.header.timestamp, vehicles)
    }

    fun parseServiceAlerts(input: InputStream): RealtimeSnapshot<ServiceAlert> {
        val feed = FeedMessage.parseFrom(input)
        val alerts = feed.entityList
            .filter { it.hasAlert() && !it.isDeleted }
            .map { entity ->
                val a = entity.alert
                ServiceAlert(
                    id = entity.id,
                    header = a.headerText.pick() ?: a.descriptionText.pick()?.take(80) ?: "Avviso",
                    description = a.descriptionText.pick(),
                    routeLabels = a.informedEntityList
                        .mapNotNull { it.routeId.ifBlank { null } }
                        .distinct()
                        .map { static.route(it)?.label ?: it },
                    stopIds = a.informedEntityList.mapNotNull { it.stopId.ifBlank { null } }.distinct(),
                    activePeriods = a.activePeriodList.map { p ->
                        (if (p.hasStart()) p.start else null) to (if (p.hasEnd()) p.end else null)
                    },
                )
            }
        return RealtimeSnapshot(feed.header.timestamp, alerts)
    }

    private data class TripInfo(
        val routeId: String?,
        val routeLabel: String,
        val headsign: String?,
        val directionId: Int?,
    )

    private fun resolveTrip(descriptor: TripDescriptor): TripInfo {
        val trip = descriptor.tripId.ifBlank { null }?.let(static::trip)
        val routeId = trip?.routeId ?: descriptor.routeId.ifBlank { null }
        val label = routeId?.let { static.route(it)?.label ?: it } ?: "?"
        val direction = trip?.directionId ?: if (descriptor.hasDirectionId()) descriptor.directionId else null
        return TripInfo(routeId, label, trip?.headsign, direction)
    }

    private fun TranslatedString.pick(): String? {
        if (translationCount == 0) return null
        val t = translationList.firstOrNull { it.language.equals("it", ignoreCase = true) }
            ?: translationList.firstOrNull { !it.hasLanguage() }
            ?: translationList.first()
        // Il feed contiene entità HTML (&#039;) e virgolette/apostrofi Windows-1252 salvati come
        // caratteri di controllo (U+0092…): le converte in caratteri normali.
        return HtmlEntities.clean(t.text).trim().ifEmpty { null }
    }
}
