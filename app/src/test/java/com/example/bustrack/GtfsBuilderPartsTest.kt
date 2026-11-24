package com.example.bustrack

import com.example.bustrack.gtfs.update.CsvReader
import com.example.bustrack.gtfs.update.ShapeTools
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Parti del costruttore del database sul telefono, confrontate con tools/build_gtfs_db.py. */
class GtfsBuilderPartsTest {

    private fun csv(text: String) = CsvReader(text.byteInputStream(Charsets.UTF_8))

    @Test
    fun csvHandlesBomQuotesAndEmptyFields() {
        val r = csv("﻿stop_id,stop_name,stop_code\r\n1,\"Piazza, Venezia\",\n2,\"Detto \"\"il bus\"\"\",70549\n\n3,Termini,\n")
        assertEquals(listOf("stop_id", "stop_name", "stop_code"), r.header)
        assertTrue(r.next()); assertEquals("1", r.field(0)); assertEquals("Piazza, Venezia", r.field(1))
        assertEquals(null, r.fieldOrNull(2)); assertEquals(3, r.size)
        assertTrue(r.next()); assertEquals("Detto \"il bus\"", r.field(1)); assertEquals(70549, r.int(2))
        assertTrue(r.next()); assertEquals("Termini", r.field(1)) // la riga vuota è saltata
        assertFalse(r.next())
    }

    @Test
    fun csvReadsGtfsTimesPast24h() {
        val r = csv("trip_id,arrival_time,departure_time\na,14:01:08,\nb,25:10:00,25:11:00\nc,,\n")
        r.next(); assertEquals(14 * 3600 + 60 + 8, r.seconds(1)); assertEquals(-1, r.seconds(2))
        r.next(); assertEquals(25 * 3600 + 10 * 60, r.seconds(1))
        r.next(); assertEquals(-1, r.seconds(1))
    }

    @Test
    fun shapeSimplifyAndEncodeMatchThePythonScript() {
        // Primi 60 punti del primo tracciato del GTFS del 24/11/2026; risultato atteso calcolato
        // con simplify(3 m) ed encode_polyline di tools/build_gtfs_db.py.
        val lat = doubleArrayOf(41.89519,41.895137,41.895138,41.895148,41.89515,41.895155,41.89516,41.895167,41.895173,41.895205,41.89525,41.895245,41.895242,41.895233,41.895232,41.895168,41.895128,41.895103,41.89509,41.895018,41.894998,41.894992,41.894978,41.894972,41.894947,41.894915,41.894887,41.894802,41.894735,41.894675,41.894725,41.894577,41.894552,41.894503,41.894325,41.894073,41.894002,41.893935,41.893902,41.893882,41.893828,41.893768,41.893828,41.893708,41.893668,41.893568,41.893468,41.893388,41.893373,41.893333,41.893327,41.893248,41.893017,41.892987,41.89295,41.892868,41.892818,41.892797,41.892777,41.89276)
        val lon = doubleArrayOf(12.574197,12.57388,12.573835,12.573653,12.57358,12.573512,12.57344,12.573317,12.573172,12.57213,12.570918,12.570173,12.569435,12.568707,12.568653,12.566567,12.565023,12.564102,12.563577,12.56307,12.562555,12.562033,12.561505,12.5607,12.559228,12.559008,12.558865,12.558343,12.557933,12.557558,12.557222,12.556885,12.556717,12.556333,12.554938,12.552803,12.552182,12.551618,12.551197,12.551077,12.550702,12.550285,12.55018,12.549828,12.549583,12.548667,12.54782,12.547118,12.547003,12.546768,12.54668,12.546118,12.544103,12.543878,12.543405,12.542567,12.542147,12.541972,12.541902,12.541777)
        val kept = ShapeTools.simplify(lat, lon)
        assertEquals(15, kept.size)
        assertEquals("}su~Fw{vkAH~@UnQBxLZ`_@PjEHxSt@lIGbAZbAr@bKlBb\\KR^vBtDvo@", ShapeTools.encode(lat, lon, kept))
    }
}
