package com.example.bustrack

import com.example.bustrack.gtfs.Arrival
import com.example.bustrack.gtfs.dropUntrackedSchedules
import com.example.bustrack.gtfs.serviceGroup
import org.junit.Assert.assertEquals
import org.junit.Test

class UntrackedSchedulesTest {
    private fun scheduled(trip: String, route: String, service: String?) = Arrival(
        tripId = trip, routeId = route, routeLabel = route, headsign = "VENEZIA", stopId = "80640",
        time = 0, delaySeconds = null, vehicleId = null, isRealtime = false, serviceId = service,
    )

    @Test
    fun serviceGroupIsThePartBeforeTheDash() {
        assertEquals("0#57", serviceGroup("0#57-4926"))
        assertEquals("0#29", serviceGroup("0#29"))
    }

    @Test
    fun hidesSchedulesOfServicesWithoutLiveTrips() {
        // Linea 8: in servizio solo 0#57; 0#35 è l'orario fantasma.
        val list = listOf(
            scheduled("a", "8", "0#35-4917"),
            scheduled("b", "8", "0#57-4926"),
            scheduled("c", "64", "0#12-1"), // linea senza dati live: resta
            scheduled("d", "8", null),      // servizio sconosciuto: resta
        )
        val kept = dropUntrackedSchedules(list, mapOf("8" to setOf("0#57")))
        assertEquals(listOf("b", "c", "d"), kept.map { it.tripId })
    }

    @Test
    fun keepsEverythingWithoutLiveData() {
        val list = listOf(scheduled("a", "8", "0#35-1"), scheduled("b", "8", "0#57-1"))
        assertEquals(list, dropUntrackedSchedules(list, emptyMap()))
        assertEquals(list, dropUntrackedSchedules(list, mapOf("8" to emptySet())))
    }
}

class AlertSearchTest {
    private val alert = com.example.bustrack.gtfs.ServiceAlert(
        id = "1", header = "Deviazione in via Nazionale", description = "Per lavori il 64 non ferma a Termini",
        routeLabels = listOf("64", "C2"), stopIds = emptyList(), activePeriods = emptyList(),
    )

    @Test
    fun matchesLineNumberExactly() {
        assertEquals(true, alert.matchesSearch("64"))
        assertEquals(true, alert.matchesSearch("c2"))
        assertEquals(false, alert.matchesSearch("6"))
        assertEquals(true, alert.matchesSearch(" "))
    }

    @Test
    fun matchesTextFromThreeCharacters() {
        assertEquals(true, alert.matchesSearch("nazionale"))
        assertEquals(true, alert.matchesSearch("Termini"))
        assertEquals(false, alert.matchesSearch("Te"))
        assertEquals(false, alert.matchesSearch("Ostiense"))
    }
}
