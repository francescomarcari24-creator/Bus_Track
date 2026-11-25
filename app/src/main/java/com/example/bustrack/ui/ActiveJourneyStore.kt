package com.example.bustrack.ui

import android.content.Context
import android.util.Log
import com.example.bustrack.gtfs.Stop
import com.example.bustrack.routing.Turn
import com.example.bustrack.routing.WalkInstruction
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Navigazione in corso salvata su disco (JSON in noBackupFilesDir), per riprenderla se il sistema
 * chiude l'app. Non si riprende una navigazione già finita da più di 30 minuti.
 */
object ActiveJourneyStore {
    private const val FILE = "nav_active.json"
    private const val GRACE_S = 30 * 60

    fun save(context: Context, journey: JourneyView?) {
        val file = File(context.noBackupFilesDir, FILE)
        runCatching {
            if (journey == null) file.delete() else file.writeText(toJson(journey).toString())
        }.onFailure { Log.e("BusTrack", "Salvataggio della navigazione", it) }
    }

    fun load(context: Context): JourneyView? {
        val file = File(context.noBackupFilesDir, FILE)
        if (!file.exists()) return null
        return runCatching { fromJson(JSONObject(file.readText())) }
            .onFailure { Log.e("BusTrack", "Lettura della navigazione salvata", it) }
            .getOrNull()
            ?.takeIf { it.arrival + GRACE_S > System.currentTimeMillis() / 1000 }
            ?: run { file.delete(); null }
    }

    private fun toJson(j: JourneyView) = JSONObject().apply {
        put("rides", j.rides)
        put("walkMeters", j.walkMeters)
        put("origin", place(j.origin))
        put("destination", place(j.destination))
        put("legs", JSONArray().apply {
            j.legs.forEach { leg ->
                put(JSONObject().apply {
                    put("dep", leg.departure)
                    put("arr", leg.arrival)
                    put("lat", doubles(leg.lat()))
                    put("lon", doubles(leg.lon()))
                    when (leg) {
                        is LegView.Walk -> {
                            put("type", "walk")
                            put("meters", leg.meters)
                            leg.toStop?.let { put("toStop", stop(it)) }
                            put("ins", JSONArray().apply {
                                leg.instructions.forEach { i ->
                                    put(JSONObject().put("text", i.text).put("meters", i.meters).put("turn", i.turn.name))
                                }
                            })
                        }
                        is LegView.Ride -> {
                            put("type", "ride")
                            put("tripId", leg.tripId)
                            put("routeId", leg.routeId)
                            put("label", leg.label)
                            leg.headsign?.let { put("headsign", it) }
                            put("from", stop(leg.from))
                            put("to", stop(leg.to))
                            put("stopCount", leg.stopCount)
                            put("stops", JSONArray().apply { leg.stops.forEach { put(stop(it)) } })
                            put("times", JSONArray().apply { leg.stopTimes.forEach { put(it) } })
                        }
                    }
                })
            }
        })
    }

    private fun fromJson(o: JSONObject): JourneyView {
        val legs = o.getJSONArray("legs").let { arr ->
            (0 until arr.length()).map { i ->
                val l = arr.getJSONObject(i)
                val lat = doubles(l.getJSONArray("lat"))
                val lon = doubles(l.getJSONArray("lon"))
                if (l.getString("type") == "walk") {
                    val ins = l.getJSONArray("ins")
                    LegView.Walk(
                        l.getLong("dep"), l.getLong("arr"), l.getInt("meters"),
                        (0 until ins.length()).map { k ->
                            val w = ins.getJSONObject(k)
                            WalkInstruction(w.getString("text"), w.getInt("meters"), Turn.valueOf(w.getString("turn")))
                        },
                        l.optJSONObject("toStop")?.let(::stop), lat, lon,
                    )
                } else {
                    val from = stop(l.getJSONObject("from"))
                    val to = stop(l.getJSONObject("to"))
                    val stops = l.optJSONArray("stops")?.let { a -> (0 until a.length()).map { stop(a.getJSONObject(it)) } }
                    val times = l.optJSONArray("times")?.let { a -> LongArray(a.length()) { a.getLong(it) } }
                    val ok = stops != null && times != null && stops.size == times.size && stops.size >= 2
                    LegView.Ride(
                        l.getLong("dep"), l.getLong("arr"), l.getString("tripId"), l.getString("routeId"),
                        l.getString("label"), l.optString("headsign").ifEmpty { null },
                        from, to, l.getInt("stopCount"), lat, lon,
                        stops = if (ok) stops!! else listOf(from, to),
                        stopTimes = if (ok) times!! else longArrayOf(l.getLong("dep"), l.getLong("arr")),
                    )
                }
            }
        }
        return JourneyView(legs, o.getInt("rides"), o.getInt("walkMeters"), place(o.getJSONObject("origin")), place(o.getJSONObject("destination")))
    }

    private fun place(p: Place) = JSONObject().put("name", p.name).put("detail", p.detail ?: "")
        .put("lat", p.lat).put("lon", p.lon).put("kind", p.kind.name)

    private fun place(o: JSONObject) = Place(
        o.getString("name"), o.optString("detail").ifEmpty { null },
        o.getDouble("lat"), o.getDouble("lon"), PlaceKind.valueOf(o.getString("kind")),
    )

    private fun stop(s: Stop) = JSONObject().put("id", s.id).put("code", s.code ?: "").put("name", s.name)
        .put("lat", s.lat).put("lon", s.lon)

    private fun stop(o: JSONObject) = Stop(
        o.getString("id"), o.optString("code").ifEmpty { null }, o.getString("name"), o.getDouble("lat"), o.getDouble("lon"),
    )

    private fun doubles(a: DoubleArray) = JSONArray().apply { a.forEach { put(it) } }
    private fun doubles(a: JSONArray) = DoubleArray(a.length()) { a.getDouble(it) }
}
