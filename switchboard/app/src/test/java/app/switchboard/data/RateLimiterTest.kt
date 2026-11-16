package app.switchboard.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.time.ZoneId
import java.time.ZonedDateTime

class RateLimiterTest {
    private val manila = ZoneId.of("Asia/Manila")
    private var now = ZonedDateTime.of(2026, 11, 15, 23, 59, 0, 0, manila).toInstant().toEpochMilli()
    private fun limiter(file: File? = null) = RateLimiter(file) { now }

    @Test fun perMinuteBlocksThenOpensAfterOldestExpires() {
        val l = limiter(); val lim = RateLimiter.Limits(2, 0, DailyReset.MIDNIGHT, manila)
        assertNull(l.tryAcquire(lim)); now += 10_000; assertNull(l.tryAcquire(lim))
        val w = l.tryAcquire(lim)!!
        assertEquals(RateLimiter.Scope.MINUTE, w.scope)
        assertEquals(50_001L, w.untilMs - now) // first request + 60 s
        now = w.untilMs; assertNull(l.tryAcquire(lim))
    }

    @Test fun dailyMidnightResetUsesSelectedZone() {
        val l = limiter(); val lim = RateLimiter.Limits(0, 1, DailyReset.MIDNIGHT, manila)
        assertNull(l.tryAcquire(lim))
        val w = l.tryAcquire(lim)!!
        assertEquals(RateLimiter.Scope.DAY, w.scope)
        assertEquals(ZonedDateTime.of(2026, 11, 16, 0, 0, 0, 0, manila).toInstant().toEpochMilli(), w.untilMs)
        now = w.untilMs; assertNull(l.tryAcquire(lim)) // new day in Manila
        // Same moment in New York is still Nov 15 there, so that zone's day already holds both requests.
        assertEquals(2, l.usage(lim.copy(zone = ZoneId.of("America/New_York"))).today)
    }

    @Test fun rollingDayAndPersistenceAcrossRestarts() {
        val f = File.createTempFile("times", ".csv").apply { delete() }
        val lim = RateLimiter.Limits(0, 2, DailyReset.ROLLING, manila)
        val first = now
        limiter(f).tryAcquire(lim); now += 3_600_000; limiter(f).tryAcquire(lim)
        val w = limiter(f).tryAcquire(lim)!! // fresh instance reads the saved times
        assertEquals(first + RateLimiter.DAY_MS, w.untilMs)
        f.delete()
    }

    @Test fun zeroMeansUnlimited() {
        val l = limiter(); val lim = RateLimiter.Limits(0, 0, DailyReset.MIDNIGHT, manila)
        repeat(500) { assertNull(l.tryAcquire(lim)) }
    }
}

class PerModelLimitTest {
    @org.junit.Test fun eachModelHasItsOwnCountAndPersists() {
        var now = 1_800_000_000_000L
        val f = java.io.File.createTempFile("limits", ".tsv").apply { delete() }
        val zone = java.time.ZoneId.of("UTC")
        val a = RateLimiter.Limits(1, 0, DailyReset.MIDNIGHT, zone)
        val l = RateLimiter(f) { now }
        org.junit.Assert.assertNull(l.tryAcquire(RateLimiter.key("p", "model-a"), a))
        org.junit.Assert.assertNotNull(l.tryAcquire(RateLimiter.key("p", "model-a"), a)) // model-a is at its limit…
        org.junit.Assert.assertNull(l.tryAcquire(RateLimiter.key("p", "model-b"), a))     // …model-b still has its own allowance
        org.junit.Assert.assertTrue(l.isBlocked(RateLimiter.key("p", "model-a"), a))
        val reopened = RateLimiter(f) { now }                                              // counts survive a restart
        org.junit.Assert.assertEquals(1, reopened.usage(RateLimiter.key("p", "model-b"), a).lastMinute)
        now += 61_000
        org.junit.Assert.assertNull(reopened.tryAcquire(RateLimiter.key("p", "model-a"), a))
        f.delete()
    }
}
