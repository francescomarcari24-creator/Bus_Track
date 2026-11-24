package com.example.bustrack.gtfs.update

import java.io.BufferedReader
import java.io.Closeable
import java.io.InputStream
import java.io.InputStreamReader

/**
 * Lettore CSV per i file GTFS, pensato per i 5 milioni di righe di stop_times.txt: legge una riga
 * alla volta e ne ricorda solo i confini dei campi; le stringhe si creano solo per i campi chiesti
 * e i numeri si leggono direttamente dai caratteri. Gestisce BOM, campi tra virgolette (anche con
 * virgole, "" e a capo al loro interno) e righe vuote.
 */
internal class CsvReader(input: InputStream) : Closeable {
    private val reader = BufferedReader(InputStreamReader(input, Charsets.UTF_8), 1 shl 16)
    private var line = ""
    private var starts = IntArray(32)
    private var ends = IntArray(32)
    private var quoted = BooleanArray(32)

    /** Numero di campi della riga corrente. */
    var size = 0
        private set

    val header: List<String>

    init {
        header = if (next()) List(size) { field(it).removePrefix("﻿").trim() } else emptyList()
    }

    /** Posizione della colonna (-1 se manca). */
    fun index(name: String): Int = header.indexOf(name)

    /** Colonna obbligatoria. */
    fun require(name: String): Int = index(name).also { require(it >= 0) { "Colonna $name mancante" } }

    /** Passa alla riga successiva non vuota; false a fine file. */
    fun next(): Boolean {
        while (true) {
            var l = reader.readLine() ?: return false
            // Un campo tra virgolette può contenere un a capo: si unisce la riga seguente.
            while (l.count { it == '"' } % 2 == 1) l += "\n" + (reader.readLine() ?: break)
            if (l.isEmpty()) continue
            line = l
            split()
            return true
        }
    }

    private fun split() {
        var n = 0
        var i = 0
        val len = line.length
        while (true) {
            if (n == starts.size) grow()
            if (i < len && line[i] == '"') {
                // Campo tra virgolette: fino alla virgoletta di chiusura non raddoppiata.
                val start = i + 1
                var j = start
                while (j < len) {
                    if (line[j] == '"') {
                        if (j + 1 < len && line[j + 1] == '"') j += 2 else break
                    } else {
                        j++
                    }
                }
                starts[n] = start; ends[n] = j; quoted[n] = true
                i = j + 1
                while (i < len && line[i] != ',') i++
            } else {
                val start = i
                while (i < len && line[i] != ',') i++
                starts[n] = start; ends[n] = i; quoted[n] = false
            }
            n++
            if (i >= len) break
            i++ // salta la virgola
            if (i == len) { // riga che finisce con una virgola: ultimo campo vuoto
                if (n == starts.size) grow()
                starts[n] = len; ends[n] = len; quoted[n] = false; n++
                break
            }
        }
        size = n
    }

    private fun grow() {
        starts = starts.copyOf(starts.size * 2)
        ends = ends.copyOf(ends.size * 2)
        quoted = quoted.copyOf(quoted.size * 2)
    }

    /** Testo del campo ("" se manca). */
    fun field(i: Int): String {
        if (i < 0 || i >= size) return ""
        val s = line.substring(starts[i], ends[i])
        return if (quoted[i]) s.replace("\"\"", "\"") else s
    }

    /** Campo senza spazi ai lati, o null se vuoto (come `none_if_blank` dello script Python). */
    fun fieldOrNull(i: Int): String? = field(i).trim().ifEmpty { null }

    fun isBlank(i: Int): Boolean {
        if (i < 0 || i >= size) return true
        for (k in starts[i] until ends[i]) if (!line[k].isWhitespace()) return false
        return true
    }

    /** Intero letto dai caratteri del campo, senza creare stringhe. */
    fun int(i: Int): Int {
        var k = starts[i]
        val end = ends[i]
        while (k < end && line[k] == ' ') k++
        var neg = false
        if (k < end && line[k] == '-') { neg = true; k++ }
        var v = 0
        while (k < end) {
            val c = line[k]
            if (c !in '0'..'9') break
            v = v * 10 + (c - '0')
            k++
        }
        return if (neg) -v else v
    }

    /** Orario GTFS "H:MM:SS" (anche oltre le 24:00) in secondi dalla mezzanotte; -1 se vuoto. */
    fun seconds(i: Int): Int {
        if (isBlank(i)) return -1
        var total = 0
        var part = 0
        var k = starts[i]
        val end = ends[i]
        while (k < end) {
            val c = line[k]
            when (c) {
                in '0'..'9' -> part = part * 10 + (c - '0')
                ':' -> { total = total * 60 + part; part = 0 }
            }
            k++
        }
        return total * 60 + part
    }

    override fun close() = reader.close()
}
