package app.switchboard.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class AgoTest {
    @Test fun relativeTimesInTheChatList() {
        val z = ZoneId.of("Asia/Manila")
        val now = ZonedDateTime.of(2026, 11, 17, 14, 8, 0, 0, z).toInstant().toEpochMilli()
        assertEquals("now", ago(now - 20_000, now, z))
        assertEquals("5m", ago(now - 5 * 60_000, now, z))
        assertEquals("8h", ago(now - 8 * 3_600_000L, now, z))
        assertEquals("1d", ago(now - 30 * 3_600_000L, now, z))
        assertEquals("20 Oct", ago(ZonedDateTime.of(2026, 10, 20, 9, 0, 0, 0, z).toInstant().toEpochMilli(), now, z))
    }
}
