package com.example.bustrack

import com.example.bustrack.gtfs.HtmlEntities
import org.junit.Assert.assertEquals
import org.junit.Test

class HtmlEntitiesTest {
    /** Carattere di controllo C1 (U+0080–U+009F), scritto con il codice per non averlo invisibile nel sorgente. */
    private fun c1(code: Int): Char = code.toChar()

    @Test
    fun decodesNumericAndNamedEntities() {
        assertEquals("nell'area", HtmlEntities.decode("nell&#039;area"))
        assertEquals("città è", HtmlEntities.decode("citt&agrave; &egrave;"))
        assertEquals("A & B", HtmlEntities.decode("A &amp; B"))
        assertEquals("€ 5", HtmlEntities.decode("&#x20AC; 5"))
        assertEquals("\"ok\"", HtmlEntities.decode("&quot;ok&quot;"))
    }

    @Test
    fun fixesWindows1252ControlCharacters() {
        // Come arrivano dal feed: C1 al posto di ’ “ ”.
        assertEquals("nell’area", HtmlEntities.clean("nell${c1(0x92)}area"))
        assertEquals("“Corri al Tiburtino”", HtmlEntities.clean("${c1(0x93)}Corri al Tiburtino${c1(0x94)}"))
        // Anche come entità numerica (HTML5): &#146; è ’, &#147;/&#148; sono “ ”.
        assertEquals("l’ingresso “ok”", HtmlEntities.clean("l&#146;ingresso &#147;ok&#148;"))
        // Testo normale e accenti restano intatti; i C1 non definiti in Windows-1252 non si alterano.
        assertEquals("città è", HtmlEntities.clean("città è"))
        assertEquals("a${c1(0x81)}b", HtmlEntities.clean("a${c1(0x81)}b"))
    }

    @Test
    fun leavesUnknownOrPlainTextUntouched() {
        assertEquals("Via Roma 5", HtmlEntities.decode("Via Roma 5"))
        assertEquals("R&S e &foo; e &#0; e & solo", HtmlEntities.decode("R&S e &foo; e &#0; e & solo"))
        // Una sola passata: "&amp;#039;" diventa "&#039;", non l'apostrofo.
        assertEquals("&#039;", HtmlEntities.decode("&amp;#039;"))
    }
}
