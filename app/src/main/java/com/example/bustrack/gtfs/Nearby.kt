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
