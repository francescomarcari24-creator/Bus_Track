package com.example.bustrack

import com.example.bustrack.gtfs.Stop
import com.example.bustrack.gtfs.bearingDegrees
import com.example.bustrack.gtfs.distanceMeters
import com.example.bustrack.gtfs.nearestStops
import org.junit.Assert.assertEquals
import org.junit.Test

class NearbyTest {
    // Piazza Venezia e Colosseo: circa 950 m in linea d'aria.
    private val venezia = Stop("1", "100", "VENEZIA", 41.8960, 12.4823)
    private val colosseo = Stop("2", "200", "COLOSSEO", 41.8902, 12.4922)
    private val termini = Stop("3", "300", "TERMINI", 41.9010, 12.5010)

    @Test
    fun distanceIsPlausible() {
        val d = distanceMeters(venezia.lat, venezia.lon, colosseo.lat, colosseo.lon)
        assertEquals(1_000.0, d, 100.0)
        assertEquals(0.0, distanceMeters(1.0, 2.0, 1.0, 2.0), 0.001)
    }

    @Test
    fun bearingPointsTheRightWay() {
        assertEquals(0.0, bearingDegrees(41.0, 12.0, 42.0, 12.0), 0.5)    // nord
        assertEquals(90.0, bearingDegrees(41.0, 12.0, 41.0, 12.1), 0.5)   // est
        assertEquals(180.0, bearingDegrees(42.0, 12.0, 41.0, 12.0), 0.5)  // sud
        assertEquals(270.0, bearingDegrees(41.0, 12.1, 41.0, 12.0), 0.5)  // ovest
        // Da Venezia al Colosseo: verso sud-est.
        assertEquals(130.0, bearingDegrees(venezia.lat, venezia.lon, colosseo.lat, colosseo.lon), 10.0)
    }

    @Test
    fun nearestStopsAreSortedFilteredAndLimited() {
        val stops = listOf(termini, colosseo, venezia)
        // Dal Colosseo: lui stesso a 0 m, Venezia a ~1 km; Termini (~1,4 km) escluso.
        val near = nearestStops(stops, colosseo.lat, colosseo.lon, maxMeters = 1_200.0)
        assertEquals(listOf("2", "1"), near.map { it.first.id })
        assertEquals(listOf("2"), nearestStops(stops, colosseo.lat, colosseo.lon, maxMeters = 1_200.0, limit = 1).map { it.first.id })
        assertEquals(emptyList<String>(), nearestStops(stops, 45.0, 9.0).map { it.first.id })
    }
}
