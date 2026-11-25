package com.example.bustrack.routing

import com.example.bustrack.gtfs.distanceMeters

/** Un tratto di un viaggio pronto da mostrare. Orari in epoch secondi. */
sealed interface JourneyLeg {
    val departure: Long
    val arrival: Long

    /** A piedi lungo [steps] (archi del grafo pedonale); [toStopId]/[fromStopId] se finisce/parte da una palina. */
    data class Walk(
        override val departure: Long,
        override val arrival: Long,
        val meters: Int,
        val steps: List<WalkStep>,
        val fromStopId: String?,
        val toStopId: String?,
    ) : JourneyLeg

    /** In mezzo, dalla palina [fromStopId] a [toStopId] ([stopCount] fermate dopo quella di salita). */
    data class Ride(
        override val departure: Long,
        override val arrival: Long,
        val tripId: String,
        val routeId: String,
        val headsign: String?,
        val fromStopId: String,
        val toStopId: String,
        val stopCount: Int,
        /** Fermate percorse, da quella di salita a quella di discesa comprese. */
        val stopIds: List<String>,
        /** Orario (epoch secondi) a ognuna di [stopIds]. */
        val stopTimes: List<Long> = emptyList(),
    ) : JourneyLeg
}

/** Preferenze dell'utente sul percorso. */
data class PlanOptions(
    val lessWalking: Boolean = false,
    val lessTransfers: Boolean = false,
    val noMetro: Boolean = false,
    val noTram: Boolean = false,
)

/**
 * Dati live per il calcolo: ritardo attuale delle corse tracciate (trip_id → secondi) e trip_id di
 * tutte le corse presenti nel feed (per riconoscere le corse fantasma).
 */
class LiveInfo(val delays: Map<String, Int>, val liveTripIds: Set<String>)

data class PlannedJourney(val legs: List<JourneyLeg>, val rides: Int) {
    val departure: Long get() = legs.first().departure
    val arrival: Long get() = legs.last().arrival
    val walkMeters: Int get() = legs.filterIsInstance<JourneyLeg.Walk>().sumOf { it.meters }
}

/**
 * Pianificatore da un punto A a un punto B: tratti a piedi sulla rete pedonale vera, mezzi con
 * RAPTOR. [stopLat]/[stopLon] sono le coordinate delle fermate negli indici di [day].
 */
class RoutePlanner(
    private val graph: WalkGraph,
    private val day: DayTimetable,
    private val stopLat: DoubleArray,
    private val stopLon: DoubleArray,
    /** Mezzanotte (epoch secondi) del giorno degli orari. */
    private val dayStartEpoch: Long,
    /** Incrocio più vicino a ogni fermata (-1 se nessuno) e distanza, pre-calcolati nel grafo. */
    private val stopNode: IntArray,
    private val stopNodeGap: DoubleArray,
) {
    companion object {
        /** Distanza massima a piedi verso le prime fermate e dalle ultime alla meta (~16 minuti). */
        const val ACCESS_MAX_M = 1_200.0
        /** Fino a questa distanza si propone anche di andare tutto a piedi. */
        const val WALK_ONLY_MAX_M = 2_500.0
        /** Distanza massima di un punto (partenza/arrivo/fermata) dalla rete pedonale. */
        const val SNAP_MAX_M = 400.0
    }

    private val raptor = Raptor(day.timetable)

    sealed interface Result {
        data class Journeys(val journeys: List<PlannedJourney>) : Result
        /** Partenza o arrivo troppo lontani dalle strade percorribili a piedi. */
        data object OffNetwork : Result
        /** Nessun mezzo utile e meta troppo lontana per andarci a piedi. */
        data object NoRoute : Result
    }

    fun plan(
        fromLat: Double,
        fromLon: Double,
        toLat: Double,
        toLon: Double,
        departEpoch: Long,
        options: PlanOptions = PlanOptions(),
        live: LiveInfo? = null,
    ): Result {
        // Meno tratti a piedi: fermate più vicine, cambi a piedi brevi, tutto a piedi solo se vicino.
        val accessMax = if (options.lessWalking) 600.0 else ACCESS_MAX_M
        val walkOnlyMax = if (options.lessWalking) 1_000.0 else WALK_ONLY_MAX_M
        val maxFootSec = if (options.lessWalking) (250 / WALK_SPEED_MPS).toInt() else Int.MAX_VALUE
        val allowed = if (options.noMetro || options.noTram) {
            BooleanArray(day.routeType.size) { r ->
                val t = day.routeType[r]
                !(options.noMetro && t == 1) && !(options.noTram && t == 0)
            }
        } else null
        val fromNode = graph.nearestNode(fromLat, fromLon, SNAP_MAX_M) ?: return Result.OffNetwork
        val toNode = graph.nearestNode(toLat, toLon, SNAP_MAX_M) ?: return Result.OffNetwork
        val fromGap = distanceMeters(fromLat, fromLon, graph.nodeLat[fromNode], graph.nodeLon[fromNode])
        val toGap = distanceMeters(toLat, toLon, graph.nodeLat[toNode], graph.nodeLon[toNode])
        val now = (departEpoch - dayStartEpoch).toInt()
        val tt = live?.let { adjustedTimetable(it, now) } ?: day.timetable
        val raptor = if (live == null) this.raptor else Raptor(tt)

        val fromTree = graph.shortestFrom(fromNode, maxOf(accessMax, walkOnlyMax))
        val toTree = graph.shortestFrom(toNode, accessMax)

        // Fermate raggiungibili a piedi dalla partenza, e da cui si raggiunge a piedi la meta.
        val sources = HashMap<Int, Int>()
        val targets = HashMap<Int, Int>()
        for (s in stopNode.indices) {
            val n = stopNode[s]
            if (n < 0) continue
            fromTree.distanceTo(n)?.let { d ->
                val m = d + fromGap + stopNodeGap[s]
                if (m <= accessMax) sources[s] = now + (m / WALK_SPEED_MPS).toInt()
            }
            toTree.distanceTo(n)?.let { d ->
                val m = d + toGap + stopNodeGap[s]
                if (m <= accessMax) targets[s] = (m / WALK_SPEED_MPS).toInt()
            }
        }

        val journeys = ArrayList<PlannedJourney>()
        // Tutto a piedi, se la meta è abbastanza vicina.
        fromTree.distanceTo(toNode)?.let { d ->
            val m = d + fromGap + toGap
            if (m <= walkOnlyMax) {
                val sec = (m / WALK_SPEED_MPS).toLong()
                journeys += PlannedJourney(
                    listOf(JourneyLeg.Walk(departEpoch, departEpoch + sec, m.toInt(), fromTree.pathTo(toNode).orEmpty(), null, null)),
                    rides = 0,
                )
            }
        }

        if (sources.isNotEmpty() && targets.isNotEmpty()) {
            val maxRides = if (options.lessTransfers) 3 else 5
            for (j in raptor.route(sources, targets, maxRides = maxRides, maxFootSec = maxFootSec, routeAllowed = allowed)) {
                journeys += toJourney(tt, j, fromTree, toTree, fromGap, toGap, departEpoch) ?: continue
            }
        }
        if (journeys.isEmpty()) return Result.NoRoute
        // Si scartano i viaggi peggiori di un altro per arrivo, mezzi e metri a piedi insieme; gli
        // altri si ordinano per un "costo" che segue le preferenze: ogni cambio vale 2 minuti (10 con
        // "meno cambi"), con "meno tratti a piedi" ogni minuto a piedi conta il doppio.
        val kept = journeys.filter { j ->
            journeys.none { o ->
                o !== j && o.arrival <= j.arrival && o.rides <= j.rides && o.walkMeters <= j.walkMeters &&
                    (o.arrival < j.arrival || o.rides < j.rides || o.walkMeters < j.walkMeters)
            }
        }
        val transferCost = if (options.lessTransfers) 600 else 120
        val walkWeight = if (options.lessWalking) 1.0 else 0.0
        fun cost(j: PlannedJourney) =
            j.arrival + transferCost * (j.rides - 1).coerceAtLeast(0) + walkWeight * j.walkMeters / WALK_SPEED_MPS
        return Result.Journeys(kept.sortedBy(::cost))
    }

    /**
     * Orari del giorno corretti con i dati live:
     * - ritardi: ogni corsa tracciata parte spostata del suo ritardo attuale;
     * - corse fantasma: per le linee con corse nel feed si tengono solo le corse dei gruppi di
     *   servizio effettivamente in servizio (come negli arrivi, vedi dropUntrackedSchedules), ma
     *   solo vicino all'ora della partenza: più in là il servizio può cambiare.
     */
    private fun adjustedTimetable(live: LiveInfo, now: Int): Timetable {
        val delays = HashMap<Int, Int>()
        for ((tripId, d) in live.delays) day.tripIndex[tripId]?.let { delays[it] = d }
        val groups = HashMap<String, MutableSet<String>>()
        for (tripId in live.liveTripIds) {
            val t = day.trips[day.tripIndex[tripId] ?: continue]
            val service = t.serviceId ?: continue
            groups.getOrPut(t.routeId) { HashSet() } += com.example.bustrack.gtfs.serviceGroup(service)
        }
        return day.timetable.adjusted(
            delay = { delays[it] ?: 0 },
            keep = { ref, start ->
                val t = day.trips[ref]
                val g = groups[t.routeId]
                g.isNullOrEmpty() || t.serviceId == null || start < now - 3 * 3600 || start > now + 2 * 3600 ||
                    com.example.bustrack.gtfs.serviceGroup(t.serviceId) in g
            },
        )
    }

    private fun toJourney(
        tt: Timetable,
        j: RaptorJourney,
        fromTree: WalkTree,
        toTree: WalkTree,
        fromGap: Double,
        toGap: Double,
        departEpoch: Long,
    ): PlannedJourney? {
        val legs = ArrayList<JourneyLeg>()
        val firstRide = j.legs.firstOrNull { it is RaptorLeg.Ride } as RaptorLeg.Ride? ?: return null

        // A piedi dalla partenza alla prima palina: si parte giusto in tempo per la prima corsa.
        val accessPath = fromTree.pathTo(stopNode[j.firstStop]).orEmpty()
        val accessM = (fromTree.distanceTo(stopNode[j.firstStop]) ?: 0.0) + fromGap + stopNodeGap[j.firstStop]
        val accessSec = (accessM / WALK_SPEED_MPS).toLong()
        val firstDep = dayStartEpoch + firstRide.departure
        val leave = maxOf(departEpoch, firstDep - accessSec)
        legs += JourneyLeg.Walk(leave, leave + accessSec, accessM.toInt(), accessPath, null, day.stopIds[j.firstStop])

        for (leg in j.legs) {
            when (leg) {
                is RaptorLeg.Ride -> {
                    val trip = day.trips[tt.tripRef[leg.trip]]
                    legs += JourneyLeg.Ride(
                        departure = dayStartEpoch + leg.departure,
                        arrival = dayStartEpoch + leg.arrival,
                        tripId = trip.tripId,
                        routeId = trip.routeId,
                        headsign = trip.headsign,
                        fromStopId = day.stopIds[leg.fromStop],
                        toStopId = day.stopIds[leg.toStop],
                        stopCount = leg.alightPos - leg.boardPos,
                        stopIds = (leg.boardPos..leg.alightPos).map { day.stopIds[tt.stopAt(leg.route, it)] },
                        stopTimes = (leg.boardPos..leg.alightPos).map {
                            dayStartEpoch + tt.tripStart[leg.trip] + tt.offsetAt(leg.route, it)
                        },
                    )
                }
                is RaptorLeg.Transfer -> {
                    val start = legs.last().arrival
                    val a = stopNode[leg.fromStop]
                    val b = stopNode[leg.toStop]
                    val path = if (a >= 0 && b >= 0) graph.shortestFrom(a, 800.0).pathTo(b).orEmpty() else emptyList()
                    legs += JourneyLeg.Walk(
                        start, start + leg.seconds, (leg.seconds * WALK_SPEED_MPS).toInt(), path,
                        day.stopIds[leg.fromStop], day.stopIds[leg.toStop],
                    )
                }
            }
        }

        // Dall'ultima palina alla meta (l'albero parte dalla meta: il percorso va rovesciato).
        val egressNode = stopNode[j.lastStop]
        val egressPath = toTree.pathTo(egressNode).orEmpty().reversed().map { WalkStep(it.edge, it.toNode, it.fromNode) }
        val egressM = (toTree.distanceTo(egressNode) ?: 0.0) + toGap + stopNodeGap[j.lastStop]
        val start = legs.last().arrival
        legs += JourneyLeg.Walk(start, start + (egressM / WALK_SPEED_MPS).toLong(), egressM.toInt(), egressPath, day.stopIds[j.lastStop], null)
        return PlannedJourney(legs, j.rides)
    }
}
