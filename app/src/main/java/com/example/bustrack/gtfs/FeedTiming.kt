package com.example.bustrack.gtfs

/**
 * Ritmo di aggiornamento dei feed GTFS-Realtime di Roma Mobilità, misurato il 2026-11-24
 * scaricando i feed ogni 5 s per 6 minuti e confrontando `header.timestamp` e `Last-Modified`:
 * - posizioni dei mezzi e arrivi previsti: un nuovo feed ogni ~30 s (28–33 s), pubblicato
 *   ~3 s dopo il suo timestamp;
 * - avvisi: ogni 5 minuti esatti (alle :00, :05, …), pubblicati ~10 s dopo.
 */
object FeedTiming {
    const val LIVE_PERIOD_S = 30L
    /** Attesa dopo il timestamp previsto del prossimo feed, perché il file sia già pubblicato. */
    const val LIVE_LAG_MS = 5_000L
    const val ALERTS_PERIOD_S = 300L
    const val ALERTS_LAG_MS = 15_000L
    /** Se il feed atteso non è ancora uscito si riprova dopo questo tempo. */
    const val RETRY_MS = 5_000L
}

/**
 * Millisecondi da attendere prima di riscaricare un feed, per prenderlo appena la centrale lo
 * aggiorna: il prossimo feed è atteso a `feedTimestamp + periodS`, più [lagMs] di pubblicazione.
 *
 * - Senza timestamp (feed mai scaricato o senza header): subito.
 * - Feed atteso già in ritardo (scaricato ancora quello vecchio): si riprova dopo [retryMs].
 * - Feed fermo da più di un periodo (centrale ferma o rete assente): si torna al ritmo del
 *   periodo, per non insistere ogni [retryMs].
 */
fun nextFeedDelayMs(
    feedTimestamp: Long?,
    nowMs: Long,
    periodS: Long = FeedTiming.LIVE_PERIOD_S,
    lagMs: Long = FeedTiming.LIVE_LAG_MS,
    retryMs: Long = FeedTiming.RETRY_MS,
): Long {
    if (feedTimestamp == null || feedTimestamp <= 0) return 0
    val periodMs = periodS * 1000
    val expectedMs = (feedTimestamp + periodS) * 1000 + lagMs
    val wait = expectedMs - nowMs
    return when {
        wait > 0 -> minOf(wait, periodMs + lagMs)
        -wait < periodMs -> retryMs
        else -> periodMs
    }
}
