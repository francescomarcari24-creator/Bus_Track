package com.example.bustrack.gtfs

/**
 * Decodifica le entità HTML che il feed di Roma Mobilità lascia nei testi degli avvisi
 * (es. "nell&#039;area" → "nell'area"). Kotlin puro: testabile senza Android.
 * Le sequenze non riconosciute restano invariate.
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

    private fun codePoint(cp: Int?): String? =
        if (cp != null && cp > 0 && Character.isValidCodePoint(cp)) String(Character.toChars(cp)) else null
}
