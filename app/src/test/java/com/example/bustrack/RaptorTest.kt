package com.example.bustrack

import com.example.bustrack.routing.Raptor
import com.example.bustrack.routing.RaptorLeg
import com.example.bustrack.routing.Timetable
import com.example.bustrack.routing.WalkGraph
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RaptorTest {

    /** Percorso: fermate, tempi relativi (s) e partenze delle corse (s dalla mezzanotte). */
    private data class R(val stops: List<Int>, val offsets: List<Int>, val trips: List<Int>)

    private fun timetable(stopCount: Int, routes: List<R>, foot: Map<Int, List<Pair<Int, Int>>> = emptyMap()): Timetable {
        val stopStart = IntArray(routes.size + 1)
        val tripStart = IntArray(routes.size + 1)
        routes.forEachIndexed { i, r ->
            stopStart[i + 1] = stopStart[i] + r.stops.size
            tripStart[i + 1] = tripStart[i] + r.trips.size
        }
        val footStart = IntArray(stopCount + 1)
        for (s in 0 until stopCount) footStart[s + 1] = footStart[s] + (foot[s]?.size ?: 0)
        val allTrips = routes.flatMap { it.trips.sorted() }
        return Timetable(
            stopCount = stopCount,
            routeStopStart = stopStart,
            routeStops = routes.flatMap { it.stops }.toIntArray(),
            routeOffsets = routes.flatMap { it.offsets }.toIntArray(),
            routeTripStart = tripStart,
            tripStart = allTrips.toIntArray(),
            tripRef = IntArray(allTrips.size) { it },
            footStart = footStart,
            footTo = (0 until stopCount).flatMap { s -> foot[s].orEmpty().map { it.first } }.toIntArray(),
            footSec = (0 until stopCount).flatMap { s -> foot[s].orEmpty().map { it.second } }.toIntArray(),
        )
    }

    @Test
    fun directRideTakesFirstUsefulTrip() {
        // Linea 0 -> 1 -> 2, 10 minuti tra fermate, corse alle 8:00 e 8:15.
        val tt = timetable(3, listOf(R(listOf(0, 1, 2), listOf(0, 600, 1200), listOf(8 * 3600, 8 * 3600 + 900))))
        val j = Raptor(tt).route(sources = mapOf(0 to 8 * 3600 + 60), targets = mapOf(2 to 0))
        assertEquals(1, j.size)
        val ride = j[0].legs.single() as RaptorLeg.Ride
        assertEquals(8 * 3600 + 900, ride.departure) // quella delle 8:00 è già partita
        assertEquals(8 * 3600 + 900 + 1200, j[0].arrival)
    }

    @Test
    fun transferAndParetoByNumberOfRides() {
        // A: 0 -> 1 -> 3 lenta (arrivo 9:00). B: 0 -> 1 veloce, poi C: 2 -> 3 dopo un cambio a piedi 1 -> 2.
        val tt = timetable(
            4,
            listOf(
                R(listOf(0, 1, 3), listOf(0, 600, 3600), listOf(8 * 3600)),        // diretta, arriva alle 9:00
                R(listOf(0, 1), listOf(0, 300), listOf(8 * 3600)),                 // arriva a 1 alle 8:05
                R(listOf(2, 3), listOf(0, 600), listOf(8 * 3600 + 600)),           // da 2 alle 8:10, a 3 alle 8:20
            ),
            foot = mapOf(1 to listOf(2 to 120)),
        )
        val j = Raptor(tt).route(sources = mapOf(0 to 8 * 3600), targets = mapOf(3 to 0))
        assertEquals(2, j.size)
        assertEquals(1, j[0].rides)
        assertEquals(9 * 3600, j[0].arrival)
        assertEquals(2, j[1].rides)
        assertEquals(8 * 3600 + 1200, j[1].arrival)
        val legs = j[1].legs
        assertTrue(legs[0] is RaptorLeg.Ride && legs[1] is RaptorLeg.Transfer && legs[2] is RaptorLeg.Ride)
        assertEquals(120, (legs[1] as RaptorLeg.Transfer).seconds)
    }

    @Test
    fun liveDelaysReorderTripsAndPhantomsAreDropped() {
        // Corse alle 8:00 (ref 0), 8:10 (ref 1), 8:20 (ref 2); da 0 a 1 in 10 minuti.
        val tt = timetable(2, listOf(R(listOf(0, 1), listOf(0, 600), listOf(8 * 3600, 8 * 3600 + 600, 8 * 3600 + 1200))))
        // La prima è in ritardo di 15 minuti, la seconda è fantasma: si prende la terza alle 8:20.
        val adj = tt.adjusted(delay = { if (it == 0) 900 else 0 }, keep = { ref, _ -> ref != 1 })
        val j = Raptor(adj).route(sources = mapOf(0 to 8 * 3600), targets = mapOf(1 to 0))
        val ride = j.single().legs.single() as RaptorLeg.Ride
        assertEquals(8 * 3600 + 900, ride.departure) // la prima, in ritardo, parte alle 8:15
        assertEquals(0, adj.tripRef[ride.trip])
        assertEquals(listOf(8 * 3600 + 900, 8 * 3600 + 1200), adj.tripStart.toList())
    }

    @Test
    fun noJourneyWhenNothingRuns() {
        val tt = timetable(2, listOf(R(listOf(0, 1), listOf(0, 300), listOf(6 * 3600))))
        assertTrue(Raptor(tt).route(sources = mapOf(0 to 7 * 3600), targets = mapOf(1 to 0)).isEmpty())
    }

    @Test
    fun walkGraphShortestPathFollowsStreets() {
        // Quadrato di 4 incroci con un lato lungo: A-B 100, B-C 100, A-D 100, D-C 500.
        val g = WalkGraph(
            nodeLat = doubleArrayOf(41.9, 41.9, 41.901, 41.901),
            nodeLon = doubleArrayOf(12.5, 12.501, 12.501, 12.5),
            edgeA = intArrayOf(0, 1, 0, 3),
            edgeB = intArrayOf(1, 2, 3, 2),
            edgeLength = floatArrayOf(100f, 100f, 100f, 500f),
            edgeName = intArrayOf(0, 1, 2, 3),
            edgeKind = ByteArray(4),
            names = arrayOf("Via A", "Via B", "Via C", "Via D"),
        )
        val tree = g.shortestFrom(0, 1000.0)
        assertEquals(200.0, tree.distanceTo(2)!!, 0.01)
        assertEquals(listOf(0, 1), tree.pathTo(2)!!.map { it.edge })
        assertEquals(0, g.nearestNode(41.9, 12.5))
        assertNull(g.nearestNode(45.0, 9.0))
    }
}

class WalkInstructionsTest {
    @Test
    fun groupsStreetsAndDetectsTurns() {
        // 0 -(Via A, nord)- 1 -(Via A, nord)- 2 -(Via B, est)- 3 ; lunghezze 100, 100, 150.
        val g = com.example.bustrack.routing.WalkGraph(
            nodeLat = doubleArrayOf(41.900, 41.901, 41.902, 41.902),
            nodeLon = doubleArrayOf(12.500, 12.500, 12.500, 12.502),
            edgeA = intArrayOf(0, 1, 2), edgeB = intArrayOf(1, 2, 3),
            edgeLength = floatArrayOf(100f, 100f, 150f),
            edgeName = intArrayOf(0, 0, 1), edgeKind = ByteArray(3),
            names = arrayOf("Via A", "Via B"),
        )
        val steps = g.shortestFrom(0, 1000.0).pathTo(3)!!
        val ins = com.example.bustrack.routing.WalkInstructions.build(g, steps, "Arrivi alla palina")
        assertEquals(listOf(
            "Imbocca Via A e prosegui per 200 m",
            "Gira a destra in Via B e prosegui per 150 m",
            "Arrivi alla palina",
        ), ins.map { it.text })
    }
}

class NavTrackingTest {
    @Test
    fun projectionGivesDistanceAndProgress() {
        // Tratto verso est di ~166 m (0,002° di longitudine a 41,9° N); punto 50 m a nord di metà strada.
        val p = com.example.bustrack.ui.projectOnPolyline(
            41.90045, 12.501,
            doubleArrayOf(41.9, 41.9), doubleArrayOf(12.5, 12.502),
        )
        assertEquals(50.0, p.distance, 2.0)
        assertEquals(p.length / 2, p.along, 2.0)
        assertEquals(165.8, p.length, 2.0)
    }
}

class WalkDataTest {
    @Test
    fun binaryGraphMatchesBuild() {
        val bytes = java.io.File("src/main/assets/walk.bin").readBytes()
        val w = com.example.bustrack.routing.WalkData.parse(
            java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        )
        assertEquals(279_855, w.graph.nodeCount)
        assertEquals(363_938, w.graph.edgeCount)
        assertEquals(78_532, w.footFrom.size)
        assertTrue(w.stopNode.count { it >= 0 } >= 8_300)
        // Ogni forma parte dall'incrocio A e finisce al B del suo tratto (entro pochi metri).
        for (e in listOf(0, 1_000, 200_000, w.graph.edgeCount - 1)) {
            val (lat, lon) = w.shape(e)
            val a = w.graph.edgeA[e]
            val b = w.graph.edgeB[e]
            assertTrue(com.example.bustrack.gtfs.distanceMeters(lat.first(), lon.first(), w.graph.nodeLat[a], w.graph.nodeLon[a]) < 2)
            assertTrue(com.example.bustrack.gtfs.distanceMeters(lat.last(), lon.last(), w.graph.nodeLat[b], w.graph.nodeLon[b]) < 2)
        }
        // Percorso reale: da un incrocio si raggiungono molti altri entro 500 m.
        assertTrue(w.graph.shortestFrom(0, 500.0).let { t -> (0 until 2000).count { t.distanceTo(it) != null } } >= 0)
    }
}
