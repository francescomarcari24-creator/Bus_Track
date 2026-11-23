package com.example.bustrack

import com.example.bustrack.gtfs.HtmlEntities
import org.junit.Assert.assertEquals
import org.junit.Test

class HtmlEntitiesTest {
    @Test
    fun decodesNumericAndNamedEntities() {
        assertEquals("nell'area", HtmlEntities.decode("nell&#039;area"))
        assertEquals("città è", HtmlEntities.decode("citt&agrave; &egrave;"))
        assertEquals("A & B", HtmlEntities.decode("A &amp; B"))
        assertEquals("€ 5", HtmlEntities.decode("&#x20AC; 5"))
        assertEquals("\"ok\"", HtmlEntities.decode("&quot;ok&quot;"))
    }

    @Test
    fun leavesUnknownOrPlainTextUntouched() {
        assertEquals("Via Roma 5", HtmlEntities.decode("Via Roma 5"))
        assertEquals("R&S e &foo; e &#0; e & solo", HtmlEntities.decode("R&S e &foo; e &#0; e & solo"))
        // Una sola passata: "&amp;#039;" diventa "&#039;", non l'apostrofo.
        assertEquals("&#039;", HtmlEntities.decode("&amp;#039;"))
    }
}
