package com.example.bustrack.routing

import android.content.Context
import android.util.Log
import com.example.bustrack.gtfs.Polyline
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Grafo pedonale con i dati per le fermate (incrocio più vicino, cambi a piedi), letto dal file
 * binario `walk.bin` (vedi tools/walk_db_to_bin.py). Il file sta compresso nell'APK e si legge
 * direttamente in memoria: niente copia sul telefono. Le forme dei tratti restano nel blocco di
 * byte e si decodificano solo quando servono ([shape]).
 */
class WalkData(
    val graph: WalkGraph,
    /** stop_id delle fermate note al grafo, con incrocio più vicino (-1 = nessuno) e distanza. */
    val stopIds: Array<String>,
    val stopNode: IntArray,
    val stopGap: FloatArray,
    /** Cambi a piedi tra fermate (indici in [stopIds]) e loro lunghezza in metri. */
    val footFrom: IntArray,
    val footTo: IntArray,
    val footMeters: IntArray,
    private val geomStart: IntArray,
    private val geom: ByteArray,
) {
    /** Forma del tratto [edge] (dall'incrocio A al B), come array paralleli di lat e lon. */
    fun shape(edge: Int): Pair<DoubleArray, DoubleArray> =
        Polyline.decode(String(geom, geomStart[edge], geomStart[edge + 1] - geomStart[edge], Charsets.ISO_8859_1))

    companion object {
        private const val ASSET = "walk.bin"

        fun load(context: Context): WalkData {
            val started = System.nanoTime()
            // La copia del vecchio database (walk.db, fino alla versione precedente) non serve più.
            File(context.noBackupFilesDir, "walk.db").takeIf { it.exists() }?.delete()
            val bytes = context.assets.open(ASSET).use { it.readBytes() }
            return parse(ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)).also {
                Log.i(
                    "BusTrack",
                    "Grafo pedonale: ${it.graph.nodeCount} incroci, ${it.graph.edgeCount} tratti in " +
                        "${(System.nanoTime() - started) / 1_000_000} ms",
                )
            }
        }

        /** Legge il formato descritto in tools/walk_db_to_bin.py. */
        fun parse(b: ByteBuffer): WalkData {
            val magic = ByteArray(4).also { b.get(it) }
            check(String(magic, Charsets.US_ASCII) == "BTW1") { "walk.bin non valido" }
            check(b.int == 1) { "Versione di walk.bin non supportata" }
            val n = b.int
            val e = b.int
            val nameCount = b.int
            val stopCount = b.int
            val footCount = b.int

            fun ints(count: Int) = IntArray(count).also { b.asIntBuffer().get(it); b.position(b.position() + 4 * count) }
            fun floats(count: Int) = FloatArray(count).also { b.asFloatBuffer().get(it); b.position(b.position() + 4 * count) }
            fun ushorts(count: Int): IntArray {
                val raw = ShortArray(count).also { b.asShortBuffer().get(it); b.position(b.position() + 2 * count) }
                return IntArray(count) { raw[it].toInt() and 0xFFFF }
            }
            fun strings(count: Int) = Array(count) {
                val len = b.short.toInt() and 0xFFFF
                val s = String(b.array(), b.arrayOffset() + b.position(), len, Charsets.UTF_8)
                b.position(b.position() + len)
                s
            }

            val latE6 = ints(n)
            val lonE6 = ints(n)
            val a = ints(e)
            val bb = ints(e)
            val length = floats(e)
            val name = ushorts(e)
            val kind = ByteArray(e).also { b.get(it) }
            val geomStart = ints(e + 1)
            val names = strings(nameCount)
            val stopIds = strings(stopCount)
            val stopNode = ints(stopCount)
            val stopGap = floats(stopCount)
            val footFrom = ints(footCount)
            val footTo = ints(footCount)
            val footMeters = ushorts(footCount)
            val geom = ByteArray(geomStart[e]).also { b.get(it) }

            val graph = WalkGraph(
                nodeLat = DoubleArray(n) { latE6[it] / 1e6 },
                nodeLon = DoubleArray(n) { lonE6[it] / 1e6 },
                edgeA = a,
                edgeB = bb,
                edgeLength = length,
                edgeName = name,
                edgeKind = kind,
                names = names,
            )
            return WalkData(graph, stopIds, stopNode, stopGap, footFrom, footTo, footMeters, geomStart, geom)
        }
    }
}
