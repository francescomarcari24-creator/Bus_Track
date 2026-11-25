package com.example.bustrack.routing

import com.example.bustrack.gtfs.bearingDegrees
import kotlin.math.abs

/** Una manovra a piedi: testo da mostrare e metri del tratto che introduce. */
data class WalkInstruction(val text: String, val meters: Int, val turn: Turn)

enum class Turn { START, STRAIGHT, SLIGHT_LEFT, LEFT, SHARP_LEFT, SLIGHT_RIGHT, RIGHT, SHARP_RIGHT, ARRIVE }

/**
 * Indicazioni strada per strada da un percorso sul grafo pedonale.
 * - I tratti consecutivi sulla stessa strada (o, se senza nome, dello stesso tipo) formano
 *   un'istruzione con la lunghezza totale.
 * - La svolta tra due istruzioni si ricava dall'angolo tra l'ultima direzione e la successiva.
 * - I pezzetti più corti di [MERGE_M] (attraversamenti, raccordi) si uniscono al tratto prima.
 */
object WalkInstructions {
    private const val MERGE_M = 25.0

    private data class Group(val nameId: Int, val kind: Int, var meters: Double, val first: WalkStep, var last: WalkStep)

    fun build(graph: WalkGraph, steps: List<WalkStep>, arrival: String): List<WalkInstruction> {
        if (steps.isEmpty()) return listOf(WalkInstruction(arrival, 0, Turn.ARRIVE))
        // Raggruppa per strada.
        val groups = ArrayList<Group>()
        for (s in steps) {
            val name = graph.edgeName[s.edge]
            val kind = graph.edgeKind[s.edge].toInt()
            val len = graph.edgeLength[s.edge].toDouble()
            val g = groups.lastOrNull()
            if (g != null && sameWay(graph, g, name, kind)) {
                g.meters += len
                g.last = s
            } else {
                groups += Group(name, kind, len, s, s)
            }
        }
        // Pezzetti corti assorbiti dal tratto precedente (o dal successivo, se è il primo).
        var i = 0
        while (i < groups.size && groups.size > 1) {
            val g = groups[i]
            if (g.meters < MERGE_M) {
                if (i > 0) {
                    groups[i - 1].meters += g.meters
                    groups[i - 1].last = g.last
                    groups.removeAt(i)
                    // Il tratto dopo, se è la stessa strada del precedente, si riunisce.
                    if (i < groups.size && sameWay(graph, groups[i - 1], groups[i].nameId, groups[i].kind)) {
                        groups[i - 1].meters += groups[i].meters
                        groups[i - 1].last = groups[i].last
                        groups.removeAt(i)
                    }
                    continue
                } else {
                    groups[1] = groups[1].copy(meters = groups[1].meters + g.meters, first = g.first)
                    groups.removeAt(0)
                    continue
                }
            }
            i++
        }
        val out = ArrayList<WalkInstruction>()
        groups.forEachIndexed { idx, g ->
            val way = wayName(graph, g.nameId, g.kind)
            val m = roundMeters(g.meters)
            if (idx == 0) {
                out += WalkInstruction("Imbocca $way e prosegui per $m m", m, Turn.START)
            } else {
                val turn = turnBetween(graph, groups[idx - 1].last, g.first)
                val text = when (turn) {
                    Turn.STRAIGHT -> "Prosegui dritto su $way per $m m"
                    Turn.SLIGHT_LEFT -> "Tieni la sinistra su $way per $m m"
                    Turn.SLIGHT_RIGHT -> "Tieni la destra su $way per $m m"
                    Turn.LEFT -> "Gira a sinistra ${preposition(way)} e prosegui per $m m"
                    Turn.RIGHT -> "Gira a destra ${preposition(way)} e prosegui per $m m"
                    Turn.SHARP_LEFT -> "Svolta decisamente a sinistra ${preposition(way)} per $m m"
                    Turn.SHARP_RIGHT -> "Svolta decisamente a destra ${preposition(way)} per $m m"
                    else -> "Prosegui su $way per $m m"
                }
                out += WalkInstruction(text, m, turn)
            }
        }
        out += WalkInstruction(arrival, 0, Turn.ARRIVE)
        return out
    }

    private fun sameWay(graph: WalkGraph, g: Group, nameId: Int, kind: Int): Boolean {
        val named = graph.names[nameId].isNotEmpty()
        return if (named) g.nameId == nameId else graph.names[g.nameId].isEmpty() && g.kind == kind
    }

    /** Nome da mostrare: la strada, o per i tratti senza nome il tipo. */
    fun wayName(graph: WalkGraph, nameId: Int, kind: Int): String {
        val name = graph.names.getOrElse(nameId) { "" }
        if (name.isNotEmpty()) return name
        return when (kind) {
            1 -> "le scale"
            2 -> "il marciapiede"
            3 -> "l'area pedonale"
            4 -> "il sentiero"
            5 -> "la pista ciclabile"
            6 -> "il passaggio"
            else -> "la strada"
        }
    }

    /** "in Via Milano", "sul marciapiede", "nell'area pedonale"... */
    private fun preposition(way: String): String = when {
        way.startsWith("il ") -> "sul " + way.removePrefix("il ")
        way.startsWith("le ") -> "sulle " + way.removePrefix("le ")
        way.startsWith("la ") -> "sulla " + way.removePrefix("la ")
        way.startsWith("l'") -> "nell'" + way.removePrefix("l'")
        else -> "in $way"
    }

    private fun roundMeters(m: Double): Int = when {
        m < 100 -> ((m / 5).toInt() * 5).coerceAtLeast(5)
        else -> (m / 10).toInt() * 10
    }

    private fun turnBetween(graph: WalkGraph, before: WalkStep, after: WalkStep): Turn {
        val b1 = bearingDegrees(
            graph.nodeLat[before.fromNode], graph.nodeLon[before.fromNode],
            graph.nodeLat[before.toNode], graph.nodeLon[before.toNode],
        )
        val b2 = bearingDegrees(
            graph.nodeLat[after.fromNode], graph.nodeLon[after.fromNode],
            graph.nodeLat[after.toNode], graph.nodeLon[after.toNode],
        )
        var d = b2 - b1
        while (d > 180) d -= 360
        while (d < -180) d += 360
        return when {
            abs(d) < 25 -> Turn.STRAIGHT
            d in 25.0..60.0 -> Turn.SLIGHT_RIGHT
            d in 60.0..140.0 -> Turn.RIGHT
            d > 140 -> Turn.SHARP_RIGHT
            d in -60.0..-25.0 -> Turn.SLIGHT_LEFT
            d in -140.0..-60.0 -> Turn.LEFT
            else -> Turn.SHARP_LEFT
        }
    }
}
