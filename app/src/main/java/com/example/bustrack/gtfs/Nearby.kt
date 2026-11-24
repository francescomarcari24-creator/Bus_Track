package com.example.bustrack.gtfs

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Distanza in metri tra due punti (formula dell'emisenoverso, raggio medio terrestre). */
fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = 6_371_000.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
    return 2 * r * asin(sqrt(a))
}

/**
 * Direzione iniziale (gradi da 0 a 360, in senso orario dal nord) per andare dal primo punto al
 * secondo lungo il cerchio massimo: è l'angolo con cui ruotare una freccia che punta a nord.
 */
fun bearingDegrees(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val phi1 = Math.toRadians(lat1)
    val phi2 = Math.toRadians(lat2)
    val dLon = Math.toRadians(lon2 - lon1)
    val y = sin(dLon) * cos(phi2)
    val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(dLon)
    return (Math.toDegrees(kotlin.math.atan2(y, x)) + 360.0) % 360.0
}

/** Fermate entro [maxMeters] dal punto, dalla più vicina, al massimo [limit]; con la distanza in metri. */
fun nearestStops(
    stops: List<Stop>,
    lat: Double,
    lon: Double,
    maxMeters: Double = 1_000.0,
    limit: Int = 20,
): List<Pair<Stop, Double>> =
    stops.asSequence()
        .map { it to distanceMeters(lat, lon, it.lat, it.lon) }
        .filter { it.second <= maxMeters }
        .sortedBy { it.second }
        .take(limit)
        .toList()
