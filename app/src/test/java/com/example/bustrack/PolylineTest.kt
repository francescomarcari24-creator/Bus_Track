package com.example.bustrack

import com.example.bustrack.gtfs.Polyline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PolylineTest {
    @Test
    fun decodesGoogleReferenceExample() {
        // Esempio ufficiale della documentazione Google (lo stesso usato per verificare l'encoder Python).
        val (lat, lon) = Polyline.decode("_p~iF~ps|U_ulLnnqC_mqNvxq`@")
        assertEquals(3, lat.size)
        assertEquals(38.5, lat[0], 1e-9); assertEquals(-120.2, lon[0], 1e-9)
        assertEquals(40.7, lat[1], 1e-9); assertEquals(-120.95, lon[1], 1e-9)
        assertEquals(43.252, lat[2], 1e-9); assertEquals(-126.453, lon[2], 1e-9)
    }

    @Test
    fun emptyAndTruncated() {
        assertEquals(0, Polyline.decode("").first.size)
        val ok = runCatching { Polyline.decode("_p~iF~ps|") }
        assertTrue(ok.isFailure)
    }
}
