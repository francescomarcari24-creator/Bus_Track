package com.example.bustrack.gtfs

/**
 * Pulizia dei testi degli avvisi di Roma Mobilità. Kotlin puro: testabile senza Android.
 *
 * Il feed contiene due difetti:
 * 1. entità HTML (es. "nell&#039;area" → "nell'area");
 * 2. virgolette e apostrofi "tipografici" di Windows-1252 salvati come caratteri di controllo
 *    C1 (U+0092 al posto di ’, U+0093/U+0094 al posto di “ ”), che il font disegna come
 *    riquadri vuoti. Vengono rimappati ai caratteri corretti, come fanno i browser.
 *
 * Le sequenze non riconosciute restano invariate. Usare [clean] per sistemare entrambi.
 */
object HtmlEntities {
    private val ENTITY = Regex("&(#[0-9]{1,7}|#[xX][0-9a-fA-F]{1,6}|[a-zA-Z][a-zA-Z0-9]{1,7});")

    private val NAMED = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ",
        "rsquo" to "’", "lsquo" to "‘", "rdquo" to "”", "ldquo" to "“",
        "laquo" to "«", "raquo" to "»", "ndash" to "–", "mdash" to "—", "hellip" to "…",
        "euro" to "€", "deg" to "°", "middot" to "·",
        "agrave" to "à", "egrave" to "è", "eacute" to "é", "igrave" to "ì", "ograve" to "ò", "ugrave" to "ù",
        "Agrave" to "À", "Egrave" to "È", "Eacute" to "É", "Igrave" to "Ì", "Ograve" to "Ò", "Ugrave" to "Ù",
    )

    /** Caratteri di controllo C1: U+0080–U+009F. */
    private val C1_RANGE = 0x80..0x9F

    /** Windows-1252 0x80–0x9F → Unicode (i cinque byte non definiti restano fuori dalla mappa). */
    private val WINDOWS_1252_C1 = mapOf(
        0x80 to '€', 0x82 to '‚', 0x83 to 'ƒ', 0x84 to '„', 0x85 to '…',
        0x86 to '†', 0x87 to '‡', 0x88 to 'ˆ', 0x89 to '‰', 0x8A to 'Š',
        0x8B to '‹', 0x8C to 'Œ', 0x8E to 'Ž', 0x91 to '‘', 0x92 to '’',
        0x93 to '“', 0x94 to '”', 0x95 to '•', 0x96 to '–', 0x97 to '—',
        0x98 to '˜', 0x99 to '™', 0x9A to 'š', 0x9B to '›', 0x9C to 'œ',
        0x9E to 'ž', 0x9F to 'Ÿ',
    )

    /** Entità HTML + caratteri C1 Windows-1252: il testo pronto da mostrare. */
    fun clean(text: String): String = fixWindows1252(decode(text))

    /** Sostituisce i caratteri di controllo U+0080–U+009F con l'equivalente Windows-1252. */
    fun fixWindows1252(text: String): String {
        if (text.none { it.code in C1_RANGE }) return text
        return buildString(text.length) {
            for (ch in text) append(if (ch.code in C1_RANGE) WINDOWS_1252_C1[ch.code] ?: ch else ch)
        }
    }

    fun decode(text: String): String {
        if ('&' !in text) return text
        return ENTITY.replace(text) { m ->
            val body = m.groupValues[1]
            when {
                body.startsWith("#x") || body.startsWith("#X") -> codePoint(body.substring(2).toIntOrNull(16))
                body.startsWith("#") -> codePoint(body.substring(1).toIntOrNull())
                else -> NAMED[body]
            } ?: m.value
        }
    }

    private fun codePoint(cp: Int?): String? {
        if (cp == null || cp <= 0 || !Character.isValidCodePoint(cp)) return null
        // Come HTML5: "&#146;" indica l'apostrofo ’ di Windows-1252, non il carattere di controllo.
        return WINDOWS_1252_C1[cp]?.toString() ?: String(Character.toChars(cp))
    }
}
