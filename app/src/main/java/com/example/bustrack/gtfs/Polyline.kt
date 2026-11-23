package com.example.bustrack.gtfs

/** Decodifica della "encoded polyline" di Google (precisione 5 decimali), usata per i tracciati. Kotlin puro. */
object Polyline {
    /** Restituisce le coordinate come due array paralleli (lat, lon): ~126 mila punti in tutto, niente oggetti per punto. */
    fun decode(encoded: String): Pair<DoubleArray, DoubleArray> {
        val lats = ArrayList<Double>()
        val lons = ArrayList<Double>()
        var i = 0
        var lat = 0
        var lon = 0
        while (i < encoded.length) {
            val dLat = readValue(encoded, i).also { i = it.second }.first
            val dLon = readValue(encoded, i).also { i = it.second }.first
            lat += dLat
            lon += dLon
            lats += lat / 1e5
            lons += lon / 1e5
        }
        return lats.toDoubleArray() to lons.toDoubleArray()
    }

    /** Legge un intero con segno a partire da [start]; restituisce (valore, indice successivo). */
    private fun readValue(s: String, start: Int): Pair<Int, Int> {
        var i = start
        var result = 0
        var shift = 0
        while (true) {
            require(i < s.length) { "Polilinea troncata" }
            val b = s[i++].code - 63
            result = result or ((b and 0x1F) shl shift)
            shift += 5
            if (b < 0x20) break
        }
        return (if (result and 1 != 0) (result shr 1).inv() else result shr 1) to i
    }
}
