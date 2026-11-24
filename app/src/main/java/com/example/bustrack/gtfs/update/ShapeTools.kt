package com.example.bustrack.gtfs.update

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.round

/** Tracciati: semplificazione e codifica, identiche a quelle di tools/build_gtfs_db.py. */
internal object ShapeTools {

    /** Tolleranza della semplificazione, in metri. */
    const val TOLERANCE_M = 3.0

    /** Douglas-Peucker iterativo su (lat, lon), tolleranza in metri. Restituisce gli indici tenuti. */
    fun simplify(lat: DoubleArray, lon: DoubleArray, toleranceM: Double = TOLERANCE_M): IntArray {
        val n = lat.size
        if (n < 3) return IntArray(n) { it }
        val lat0 = Math.toRadians(lat.average())
        val kx = 111320.0 * cos(lat0)
        val ky = 110574.0
        val x = DoubleArray(n) { lon[it] * kx }
        val y = DoubleArray(n) { lat[it] * ky }
        val keep = BooleanArray(n)
        keep[0] = true
        keep[n - 1] = true
        val stack = ArrayDeque<Pair<Int, Int>>()
        stack.addLast(0 to n - 1)
        while (stack.isNotEmpty()) {
            val (a, b) = stack.removeLast()
            val dx = x[b] - x[a]
            val dy = y[b] - y[a]
            val norm = hypot(dx, dy)
            var far = -1
            var farD = 0.0
            for (i in a + 1 until b) {
                val d = if (norm == 0.0) hypot(x[i] - x[a], y[i] - y[a])
                else abs(dx * (y[a] - y[i]) - (x[a] - x[i]) * dy) / norm
                if (d > farD) { far = i; farD = d }
            }
            if (farD > toleranceM) {
                keep[far] = true
                stack.addLast(a to far)
                stack.addLast(far to b)
            }
        }
        return (0 until n).filter { keep[it] }.toIntArray()
    }

    /** Polilinea codificata di Google (5 decimali); arrotondamento come `round` di Python. */
    fun encode(lat: DoubleArray, lon: DoubleArray, indices: IntArray): String {
        val out = StringBuilder()
        var prevLat = 0L
        var prevLon = 0L
        for (i in indices) {
            val iLat = round(lat[i] * 1e5).toLong()
            val iLon = round(lon[i] * 1e5).toLong()
            writeValue(out, iLat - prevLat)
            writeValue(out, iLon - prevLon)
            prevLat = iLat
            prevLon = iLon
        }
        return out.toString()
    }

    private fun writeValue(out: StringBuilder, delta: Long) {
        var v = if (delta < 0) (delta shl 1).inv() else delta shl 1
        while (v >= 0x20) {
            out.append(((0x20L or (v and 0x1F)) + 63).toInt().toChar())
            v = v shr 5
        }
        out.append((v + 63).toInt().toChar())
    }
}
