package com.example.bustrack.routing

import com.example.bustrack.gtfs.distanceMeters
import java.util.PriorityQueue

/**
 * Rete pedonale in memoria (grafo di tools/build_walk_graph.py): nodi = incroci, archi = tratti
 * di strada tra due incroci. Strutture compatte (array) per caricare ~280.000 incroci e ~364.000
 * archi senza oggetti per elemento. La forma dei tratti non sta qui: si legge solo per i tratti
 * del percorso scelto (vedi [EdgeShapes]).
 *
 * Indipendente da Android: costruita da [WalkData] sul telefono e a mano nei test.
 */
class WalkGraph(
    val nodeLat: DoubleArray,
    val nodeLon: DoubleArray,
    val edgeA: IntArray,
    val edgeB: IntArray,
    /** Lunghezza in metri. */
    val edgeLength: FloatArray,
    val edgeName: IntArray,
    val edgeKind: ByteArray,
    val names: Array<String>,
) {
    val nodeCount get() = nodeLat.size
    val edgeCount get() = edgeA.size

    // Adiacenza in forma compatta (CSR): per il nodo n, gli archi sono adjEdge[adjStart[n] until adjStart[n+1]].
    private val adjStart = IntArray(nodeCount + 1)
    private val adjEdge: IntArray

    init {
        for (e in 0 until edgeCount) {
            adjStart[edgeA[e] + 1]++
            adjStart[edgeB[e] + 1]++
        }
        for (n in 0 until nodeCount) adjStart[n + 1] += adjStart[n]
        adjEdge = IntArray(adjStart[nodeCount])
        val fill = adjStart.copyOf()
        for (e in 0 until edgeCount) {
            adjEdge[fill[edgeA[e]]++] = e
            adjEdge[fill[edgeB[e]]++] = e
        }
    }

    // Griglia spaziale degli incroci per l'aggancio dei punti (celle di ~200 m).
    private val cell = 0.002
    private val grid: Map<Long, IntArray> = buildMap<Long, MutableList<Int>> {
        for (n in 0 until nodeCount) getOrPut(key(nodeLat[n], nodeLon[n])) { mutableListOf() }.add(n)
    }.mapValues { it.value.toIntArray() }

    private fun key(lat: Double, lon: Double): Long = key((lat / cell).toInt(), (lon / cell).toInt())
    private fun key(x: Int, y: Int): Long = (x.toLong() shl 32) or (y.toLong() and 0xFFFFFFFFL)

    fun other(edge: Int, node: Int) = if (edgeA[edge] == node) edgeB[edge] else edgeA[edge]

    /**
     * Incrocio più vicino al punto (entro [maxMeters]); null se non ce n'è. Si usano gli incroci e
     * non un punto qualsiasi del tratto: il primo passo a piedi è "raggiungi la strada più vicina".
     */
    fun nearestNode(lat: Double, lon: Double, maxMeters: Double = 400.0): Int? {
        val cx = (lat / cell).toInt()
        val cy = (lon / cell).toInt()
        val rings = (maxMeters / 200.0).toInt() + 1
        var best = -1
        var bestD = Double.MAX_VALUE
        for (dx in -rings..rings) for (dy in -rings..rings) {
            val ids = grid[key(cx + dx, cy + dy)] ?: continue
            for (n in ids) {
                val d = distanceMeters(lat, lon, nodeLat[n], nodeLon[n])
                if (d < bestD) { bestD = d; best = n }
            }
        }
        return if (best >= 0 && bestD <= maxMeters) best else null
    }

    /**
     * Dijkstra dal nodo [source] fino a [maxMeters]: distanze (metri) e, per ricostruire i
     * percorsi, l'arco con cui si arriva a ogni nodo. I nodi non raggiunti hanno distanza infinita.
     */
    /**
     * Percorsi più brevi da [source] fino a [maxMeters] metri a piedi. Con [stairsPenalty] > 1 le
     * scale "pesano" di più (es. 15 = come una strada lunga 15 volte): si evitano quando c'è
     * un'alternativa ragionevole, ma restano possibili se non c'è altro modo. Le distanze del
     * risultato sono sempre i metri veri.
     */
    fun shortestFrom(source: Int, maxMeters: Double, stairsPenalty: Double = 1.0): WalkTree {
        val cost = HashMap<Int, Double>()
        val meters = HashMap<Int, Double>()
        val via = HashMap<Int, Int>()
        val queue = PriorityQueue<Pair<Double, Int>>(compareBy { it.first })
        cost[source] = 0.0
        meters[source] = 0.0
        queue.add(0.0 to source)
        while (queue.isNotEmpty()) {
            val (c, n) = queue.poll()!!
            if (c > (cost[n] ?: Double.MAX_VALUE)) continue
            val dn = meters[n]!!
            for (i in adjStart[n] until adjStart[n + 1]) {
                val e = adjEdge[i]
                val m = other(e, n)
                val len = edgeLength[e].toDouble()
                val nd = dn + len
                val nc = c + if (edgeKind[e] == KIND_STEPS) len * stairsPenalty else len
                if (nd <= maxMeters && nc < (cost[m] ?: Double.MAX_VALUE)) {
                    cost[m] = nc
                    meters[m] = nd
                    via[m] = e
                    queue.add(nc to m)
                }
            }
        }
        return WalkTree(this, source, meters, via)
    }

    companion object {
        /** Tipo di tratto "scale" (vedi KIND in tools/build_walk_graph.py). */
        const val KIND_STEPS: Byte = 1
    }
}

/** Risultato di [WalkGraph.shortestFrom]: distanze dal nodo di partenza e percorsi. */
class WalkTree(
    private val graph: WalkGraph,
    val source: Int,
    private val dist: Map<Int, Double>,
    private val via: Map<Int, Int>,
) {
    fun distanceTo(node: Int): Double? = dist[node]

    /** Archi dal nodo di partenza a [node], nell'ordine di percorrenza (vuoto se coincidono). */
    fun pathTo(node: Int): List<WalkStep>? {
        if (node !in dist) return null
        val steps = ArrayList<WalkStep>()
        var n = node
        while (n != source) {
            val e = via[n] ?: return null
            val from = graph.other(e, n)
            steps += WalkStep(edge = e, fromNode = from, toNode = n)
            n = from
        }
        steps.reverse()
        return steps
    }
}

/** Un tratto percorso: l'arco [edge] dal nodo [fromNode] al nodo [toNode]. */
data class WalkStep(val edge: Int, val fromNode: Int, val toNode: Int)
