package com.example.bustrack.routing

/**
 * Orari di un giorno organizzati per RAPTOR, in array compatti.
 *
 * - Un "percorso" RAPTOR è un profilo GTFS (vedi tools/build_gtfs_db.py): sequenza di fermate con
 *   gli stessi tempi relativi. Le sue corse quindi non si sorpassano e basta ordinarle per partenza.
 * - Gli orari sono in secondi dalla mezzanotte del giorno della ricerca (le corse del giorno prima
 *   che finiscono dopo mezzanotte hanno partenza negativa).
 */
class Timetable(
    val stopCount: Int,
    /** Fermate del percorso r: routeStops[routeStopStart[r] until routeStopStart[r + 1]]. */
    val routeStopStart: IntArray,
    val routeStops: IntArray,
    /** Secondi dalla partenza della corsa per ogni fermata del percorso (stesse posizioni di routeStops). */
    val routeOffsets: IntArray,
    /** Corse del percorso r: tripStart[routeTripStart[r] until routeTripStart[r + 1]], in ordine. */
    val routeTripStart: IntArray,
    val tripStart: IntArray,
    /** Indice della corsa nei metadati (id, linea, capolinea) di chi ha costruito la tabella. */
    val tripRef: IntArray,
    /** Cambi a piedi: dalla fermata s verso footTo[footStart[s] until footStart[s + 1]], in footSec secondi. */
    val footStart: IntArray,
    val footTo: IntArray,
    val footSec: IntArray,
) {
    val routeCount get() = routeStopStart.size - 1

    // Per ogni fermata i percorsi che la servono e in che posizione.
    val stopRouteStart: IntArray
    val stopRoute: IntArray
    val stopRoutePos: IntArray

    init {
        val count = IntArray(stopCount + 1)
        for (i in routeStops.indices) count[routeStops[i] + 1]++
        for (s in 0 until stopCount) count[s + 1] += count[s]
        stopRouteStart = count.copyOf()
        stopRoute = IntArray(routeStops.size)
        stopRoutePos = IntArray(routeStops.size)
        val fill = count.copyOf()
        for (r in 0 until routeCount) {
            for (i in routeStopStart[r] until routeStopStart[r + 1]) {
                val s = routeStops[i]
                stopRoute[fill[s]] = r
                stopRoutePos[fill[s]] = i - routeStopStart[r]
                fill[s]++
            }
        }
    }

    /**
     * Copia con gli orari corretti dai dati live: ogni corsa parte [delay] secondi dopo (il ritardo
     * vale per tutta la corsa), quelle per cui [keep] è falso si tolgono (corse fantasma). Le corse
     * di ogni percorso si riordinano: con i ritardi una può essere superata dalla successiva.
     */
    fun adjusted(delay: (ref: Int) -> Int, keep: (ref: Int, start: Int) -> Boolean): Timetable {
        val newRouteTripStart = IntArray(routeCount + 1)
        val starts = IntArray(tripStart.size)
        val refs = IntArray(tripStart.size)
        var n = 0
        for (r in 0 until routeCount) {
            val items = (routeTripStart[r] until routeTripStart[r + 1])
                .filter { keep(tripRef[it], tripStart[it]) }
                .map { (tripStart[it] + delay(tripRef[it])) to tripRef[it] }
                .sortedBy { it.first }
            for ((s, ref) in items) {
                starts[n] = s
                refs[n] = ref
                n++
            }
            newRouteTripStart[r + 1] = n
        }
        return Timetable(
            stopCount, routeStopStart, routeStops, routeOffsets, newRouteTripStart,
            starts.copyOf(n), refs.copyOf(n), footStart, footTo, footSec,
        )
    }

    /** Copia con altre durate dei cambi a piedi (es. quelle senza scale), stessi cambi e corse. */
    fun withFootSec(sec: IntArray): Timetable = Timetable(
        stopCount, routeStopStart, routeStops, routeOffsets, routeTripStart, tripStart, tripRef,
        footStart, footTo, sec,
    )

    fun stopAt(route: Int, pos: Int) = routeStops[routeStopStart[route] + pos]
    fun offsetAt(route: Int, pos: Int) = routeOffsets[routeStopStart[route] + pos]
    fun routeLength(route: Int) = routeStopStart[route + 1] - routeStopStart[route]
}

/** Un tratto di un viaggio trovato da [Raptor]. */
sealed interface RaptorLeg {
    /** In mezzo, dalla fermata [fromStop] (posizione [boardPos]) a [toStop] (posizione [alightPos]). */
    data class Ride(
        val route: Int,
        val trip: Int,
        val fromStop: Int,
        val toStop: Int,
        val boardPos: Int,
        val alightPos: Int,
        val departure: Int,
        val arrival: Int,
    ) : RaptorLeg

    /** A piedi tra due fermate vicine. */
    data class Transfer(val fromStop: Int, val toStop: Int, val seconds: Int) : RaptorLeg
}

/**
 * Viaggio: dalla fermata [firstStop] (raggiunta a piedi alle [startTime]) alla fermata [lastStop],
 * poi a piedi fino alla meta; arrivo alla meta alle [arrival], con [rides] mezzi.
 */
data class RaptorJourney(
    val firstStop: Int,
    val lastStop: Int,
    val legs: List<RaptorLeg>,
    val arrival: Int,
    val rides: Int,
)

/**
 * RAPTOR (Round-bAsed Public Transit Optimized Router, Delling et al. 2012): al giro k trova gli
 * arrivi più presto con k mezzi, scorrendo i percorsi toccati nel giro prima e poi i cambi a piedi.
 * Restituisce i viaggi non dominati per (orario d'arrivo, numero di mezzi).
 */
class Raptor(private val tt: Timetable) {

    private companion object {
        const val INF = Int.MAX_VALUE / 2
        // Tipo di etichetta per la ricostruzione del viaggio.
        const val NONE = 0
        const val SOURCE = 1
        const val RIDE = 2
        const val WALK = 3
    }

    /**
     * [sources]: fermata -> orario a cui ci si arriva a piedi dalla partenza.
     * [targets]: fermata -> secondi a piedi da lì alla meta.
     * [minTransferSec]: tempo minimo per cambiare mezzo alla stessa fermata.
     */
    fun route(
        sources: Map<Int, Int>,
        targets: Map<Int, Int>,
        maxRides: Int = 5,
        minTransferSec: Int = 60,
        /** Cambi a piedi più lunghi di così non si considerano. */
        maxFootSec: Int = Int.MAX_VALUE,
        /** Percorsi utilizzabili (null = tutti): per escludere metro o tram. */
        routeAllowed: BooleanArray? = null,
    ): List<RaptorJourney> {
        val n = tt.stopCount
        val best = IntArray(n) { INF }
        // Etichette per giro: arrivo, tipo e dati per ricostruire.
        val arr = Array(maxRides + 1) { IntArray(n) { INF } }
        val kind = Array(maxRides + 1) { ByteArray(n) }
        val pRoute = Array(maxRides + 1) { IntArray(n) }
        val pTrip = Array(maxRides + 1) { IntArray(n) }
        val pBoard = Array(maxRides + 1) { IntArray(n) }
        val pAlight = Array(maxRides + 1) { IntArray(n) }
        val pFrom = Array(maxRides + 1) { IntArray(n) }
        val pDep = Array(maxRides + 1) { IntArray(n) }

        var marked = BooleanArray(n)
        for ((s, t) in sources) {
            if (t < arr[0][s]) {
                arr[0][s] = t
                best[s] = t
                kind[0][s] = SOURCE.toByte()
                marked[s] = true
            }
        }
        fun targetBound(): Int {
            var b = INF
            for ((s, w) in targets) if (best[s] < INF) b = minOf(b, best[s] + w)
            return b
        }

        val journeys = ArrayList<RaptorJourney>()
        var bestDest = INF
        val routeFrom = IntArray(tt.routeCount) { -1 }

        for (k in 1..maxRides) {
            // Gli arrivi del giro prima valgono anche in questo (etichette "ereditate", tipo NONE).
            System.arraycopy(arr[k - 1], 0, arr[k], 0, n)
            // Percorsi da esaminare: quelli che passano da fermate migliorate nel giro precedente.
            val touched = ArrayList<Int>()
            for (s in 0 until n) {
                if (!marked[s]) continue
                for (i in tt.stopRouteStart[s] until tt.stopRouteStart[s + 1]) {
                    val r = tt.stopRoute[i]
                    if (routeAllowed != null && !routeAllowed[r]) continue
                    val pos = tt.stopRoutePos[i]
                    if (routeFrom[r] == -1) touched += r
                    if (routeFrom[r] == -1 || pos < routeFrom[r]) routeFrom[r] = pos
                }
            }
            val newMarked = BooleanArray(n)
            val bound = targetBound()
            val prev = arr[k - 1]
            for (r in touched) {
                val start = routeFrom[r]
                routeFrom[r] = -1
                var trip = -1
                var boardPos = -1
                val tripFrom = tt.routeTripStart[r]
                val tripTo = tt.routeTripStart[r + 1]
                for (pos in start until tt.routeLength(r)) {
                    val s = tt.stopAt(r, pos)
                    val off = tt.offsetAt(r, pos)
                    // Scendere qui migliora l'arrivo?
                    if (trip >= 0) {
                        val a = tt.tripStart[trip] + off
                        if (a < best[s] && a < bound) {
                            arr[k][s] = a
                            best[s] = a
                            kind[k][s] = RIDE.toByte()
                            pRoute[k][s] = r
                            pTrip[k][s] = trip
                            pBoard[k][s] = boardPos
                            pAlight[k][s] = pos
                            pDep[k][s] = tt.tripStart[trip] + tt.offsetAt(r, boardPos)
                            newMarked[s] = true
                        }
                    }
                    // Si può salire qui su una corsa più presto?
                    val ready = prev[s]
                    if (ready < INF) {
                        val earliest = ready + if (k > 1 && kind[k - 1][s] == RIDE.toByte()) minTransferSec else 0
                        val currentDep = if (trip >= 0) tt.tripStart[trip] + off else INF
                        if (earliest < currentDep) {
                            // Prima corsa con partenza da qui >= earliest (ricerca binaria sulle partenze).
                            var lo = tripFrom
                            var hi = tripTo
                            while (lo < hi) {
                                val mid = (lo + hi) ushr 1
                                if (tt.tripStart[mid] + off >= earliest) hi = mid else lo = mid + 1
                            }
                            if (lo < tripTo && (trip < 0 || lo < trip)) {
                                trip = lo
                                boardPos = pos
                            }
                        }
                    }
                }
            }
            // Cambi a piedi dalle fermate raggiunte in mezzo in questo giro.
            val walkMarked = ArrayList<Int>()
            for (s in 0 until n) {
                if (!newMarked[s] || kind[k][s] != RIDE.toByte()) continue
                for (i in tt.footStart[s] until tt.footStart[s + 1]) {
                    if (tt.footSec[i] > maxFootSec) continue
                    val q = tt.footTo[i]
                    val a = arr[k][s] + tt.footSec[i]
                    if (a < best[q] && a < arr[k][q]) {
                        arr[k][q] = a
                        best[q] = a
                        kind[k][q] = WALK.toByte()
                        pFrom[k][q] = s
                        walkMarked += q
                    }
                }
            }
            for (q in walkMarked) newMarked[q] = true

            // Miglior arrivo alla meta con k mezzi: si tiene solo se batte quelli con meno mezzi.
            var bestStop = -1
            var bestArr = INF
            for ((s, w) in targets) {
                if (arr[k][s] < INF && arr[k][s] + w < bestArr) {
                    bestArr = arr[k][s] + w
                    bestStop = s
                }
            }
            if (bestStop >= 0 && bestArr < bestDest) {
                bestDest = bestArr
                reconstruct(k, bestStop, arr, kind, pRoute, pTrip, pBoard, pAlight, pFrom, pDep)?.let {
                    journeys += it.copy(arrival = bestArr)
                }
            }
            marked = newMarked
            if (newMarked.none { it }) break
        }
        return journeys
    }

    private fun reconstruct(
        round: Int,
        target: Int,
        arr: Array<IntArray>,
        kind: Array<ByteArray>,
        pRoute: Array<IntArray>,
        pTrip: Array<IntArray>,
        pBoard: Array<IntArray>,
        pAlight: Array<IntArray>,
        pFrom: Array<IntArray>,
        pDep: Array<IntArray>,
    ): RaptorJourney? {
        val legs = ArrayList<RaptorLeg>()
        var k = round
        var s = target
        var rides = 0
        var guard = 0
        while (guard++ < 100) {
            when (kind[k][s].toInt()) {
                SOURCE -> {
                    legs.reverse()
                    return RaptorJourney(firstStop = s, lastStop = target, legs = legs, arrival = arr[round][target], rides = rides)
                }
                WALK -> {
                    val from = pFrom[k][s]
                    legs += RaptorLeg.Transfer(from, s, arr[k][s] - arr[k][from])
                    s = from
                }
                RIDE -> {
                    val r = pRoute[k][s]
                    val board = tt.stopAt(r, pBoard[k][s])
                    legs += RaptorLeg.Ride(
                        route = r, trip = pTrip[k][s], fromStop = board, toStop = s,
                        boardPos = pBoard[k][s], alightPos = pAlight[k][s],
                        departure = pDep[k][s], arrival = arr[k][s],
                    )
                    rides++
                    s = board
                    k--
                    // L'etichetta di salita può venire da un giro precedente (fermata già raggiunta prima).
                    while (k > 0 && kind[k][s].toInt() == NONE) k--
                }
                else -> {
                    if (k == 0) return null
                    k--
                }
            }
        }
        return null
    }
}
