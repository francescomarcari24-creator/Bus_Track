package com.example.bustrack

import com.example.bustrack.gtfs.FeedTiming
import com.example.bustrack.gtfs.nextFeedDelayMs
import org.junit.Assert.assertEquals
import org.junit.Test

class FeedTimingTest {
    private val ts = 1_795_528_289L // feed delle 14:51:29
    private val tsMs = ts * 1000

    @Test
    fun waitsForTheNextFeedPlusPublishingLag() {
        // Scaricato 3 s dopo il suo timestamp: il prossimo esce a +30 s, pubblicato a +35 s.
        assertEquals(32_000L, nextFeedDelayMs(ts, tsMs + 3_000))
        // Scaricato tardi (a +20 s): mancano 15 s.
        assertEquals(15_000L, nextFeedDelayMs(ts, tsMs + 20_000))
    }

    @Test
    fun retriesShortlyWhenTheExpectedFeedIsLate() {
        // A +36 s c'è ancora il feed vecchio (la centrale ha tardato): riprova dopo 5 s.
        assertEquals(FeedTiming.RETRY_MS, nextFeedDelayMs(ts, tsMs + 36_000))
    }

    @Test
    fun fallsBackToThePeriodWhenTheFeedIsStuck() {
        // Feed fermo da oltre un periodo dopo quello atteso: si torna a ogni 30 s.
        assertEquals(30_000L, nextFeedDelayMs(ts, tsMs + 70_000))
    }

    @Test
    fun fetchesImmediatelyWithoutTimestamp() {
        assertEquals(0L, nextFeedDelayMs(null, tsMs))
        assertEquals(0L, nextFeedDelayMs(0, tsMs))
    }

    @Test
    fun alertsFollowTheFiveMinuteRhythm() {
        val alertsTs = 1_795_528_201L // 14:50:01
        val delay = nextFeedDelayMs(
            alertsTs, alertsTs * 1000 + 10_000,
            periodS = FeedTiming.ALERTS_PERIOD_S, lagMs = FeedTiming.ALERTS_LAG_MS,
        )
        assertEquals(305_000L, delay) // prossimo alle 14:55:01, letto alle 14:55:16
    }
}
