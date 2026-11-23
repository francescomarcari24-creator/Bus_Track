package com.example.bustrack

import com.example.bustrack.gtfs.GtfsRealtimeParser
import com.example.bustrack.gtfs.Route
import com.example.bustrack.gtfs.StaticLookup
import com.example.bustrack.gtfs.Stop
import com.example.bustrack.gtfs.Trip
import com.example.bustrack.gtfs.VehicleStopStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Verifica il parsing sugli snapshot reali dei feed di Roma (src/test/resources/gtfs-rt). */
class GtfsRealtimeParserTest {

    private val lookup = object : StaticLookup {
        override fun route(routeId: String) =
            if (routeId == "105") Route("105", "105", null, 3, "Atac") else null
        override fun trip(tripId: String) =
            if (tripId == "0#575-13") Trip(tripId, "105", "PARCO DI CENTOCELLE (MC)", 0) else null
        override fun stop(stopId: String) =
            if (stopId == "82697") Stop("82697", "82697", "P.LE PRENESTINO", 41.8907, 12.526183) else null
    }
    private val parser = GtfsRealtimeParser(lookup)

    private fun fixture(name: String) =
        requireNotNull(javaClass.getResourceAsStream("/gtfs-rt/$name")) { "manca $name" }

    @Test
    fun tripUpdates_areEnrichedWithStaticData() {
        val snapshot = fixture("rome_rtgtfs_trip_updates_feed.pb").use(parser::parseTripUpdates)
        assertTrue(snapshot.feedTimestamp > 0)
        assertTrue(snapshot.items.size > 1000)
        val arrival = snapshot.items.first { it.tripId == "0#575-13" && it.stopId == "82697" }
        assertEquals("105", arrival.routeLabel)
        assertEquals("PARCO DI CENTOCELLE (MC)", arrival.headsign)
        assertTrue(arrival.time > snapshot.feedTimestamp - 3600)
    }

    @Test
    fun vehiclePositions_haveCoordinatesInRomeArea() {
        val snapshot = fixture("rome_rtgtfs_vehicle_positions_feed.pb").use(parser::parseVehiclePositions)
        assertTrue(snapshot.items.size > 100)
        snapshot.items.forEach {
            assertTrue("lat ${it.lat}", it.lat in 41.0..43.0)
            assertTrue("lon ${it.lon}", it.lon in 11.5..13.5)
        }
        val v = snapshot.items.first { it.tripId == "0#575-13" }
        assertEquals("105", v.routeLabel)
        assertEquals("P.LE PRENESTINO", v.currentStopName)
        assertEquals(0, v.directionId)
        assertEquals(VehicleStopStatus.IN_TRANSIT_TO, v.currentStatus)
    }

    @Test
    fun serviceAlerts_haveText() {
        val snapshot = fixture("rome_rtgtfs_service_alerts_feed.pb").use(parser::parseServiceAlerts)
        assertTrue(snapshot.items.size > 50)
        assertTrue(snapshot.items.all { it.header.isNotBlank() })
        assertTrue(snapshot.items.any { it.isActiveAt(snapshot.feedTimestamp) })
        // Lo snapshot contiene "&#039;": nessuna entità HTML deve arrivare all'utente.
        val texts = snapshot.items.flatMap { listOfNotNull(it.header, it.description) }
        assertTrue(texts.none { "&#" in it })
        assertTrue(texts.any { "'" in it })
        // Nessun carattere di controllo C1 (riquadri vuoti nel font): virgolette e apostrofi
        // Windows-1252 del feed devono essere diventati ’ “ ”.
        assertTrue(texts.none { text -> text.any { it.code in 0x80..0x9F } })
        assertTrue(snapshot.items.any { it.header == "“Corri al Tiburtino”" })
        assertTrue(texts.any { "nell’area" in it })
    }
}
