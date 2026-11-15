package app.switchboard.data

import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/** How the daily allowance resets. */
enum class DailyReset { MIDNIGHT, ROLLING }

/**
 * App-wide request limiter shared by every chat. Request times are persisted so limits survive restarts.
 * A limit of 0 means unlimited.
 */
class RateLimiter(private val file: File?, private val clock: () -> Long = System::currentTimeMillis) {
    data class Limits(val perMinute: Int, val perDay: Int, val dailyReset: DailyReset, val zone: ZoneId)
    enum class Scope { MINUTE, DAY }
    /** A request is blocked by [scope] until [untilMs]. */
    data class Wait(val scope: Scope, val untilMs: Long)
    data class Usage(val lastMinute: Int, val today: Int)

    private val times = ArrayDeque<Long>()

    init {
        file?.takeIf { it.exists() }?.readText()?.split(',')?.mapNotNull { it.trim().toLongOrNull() }?.sorted()?.let(times::addAll)
    }

    /** Records a request and returns null if it may go now; otherwise returns how long to wait (nothing recorded). */
    @Synchronized
    fun tryAcquire(l: Limits): Wait? {
        val now = clock()
        prune(now)
        val dayStart = dayWindowStart(now, l)
        if (l.perDay > 0) {
            val inDay = times.filter { it >= dayStart }
            if (inDay.size >= l.perDay) {
                val until = when (l.dailyReset) {
                    DailyReset.MIDNIGHT -> nextMidnight(now, l.zone)
                    DailyReset.ROLLING -> inDay[inDay.size - l.perDay] + DAY_MS
                }
                return Wait(Scope.DAY, until)
            }
        }
        if (l.perMinute > 0) {
            val inMinute = times.filter { it > now - MINUTE_MS }
            if (inMinute.size >= l.perMinute) return Wait(Scope.MINUTE, inMinute[inMinute.size - l.perMinute] + MINUTE_MS + 1)
        }
        times.addLast(now)
        save()
        return null
    }

    @Synchronized
    fun usage(l: Limits): Usage {
        val now = clock()
        val dayStart = dayWindowStart(now, l)
        return Usage(times.count { it > now - MINUTE_MS }, times.count { it >= dayStart })
    }

    private fun dayWindowStart(now: Long, l: Limits) = when (l.dailyReset) {
        DailyReset.MIDNIGHT -> ZonedDateTime.ofInstant(Instant.ofEpochMilli(now), l.zone).toLocalDate().atStartOfDay(l.zone).toInstant().toEpochMilli()
        DailyReset.ROLLING -> now - DAY_MS + 1
    }

    private fun nextMidnight(now: Long, zone: ZoneId) =
        ZonedDateTime.ofInstant(Instant.ofEpochMilli(now), zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

    /** Keeps ~26 h of history: enough for a rolling day or a calendar day that's 25 h long because of daylight saving. */
    private fun prune(now: Long) { while (times.isNotEmpty() && times.first() < now - 26 * 3_600_000L) times.removeFirst() }

    private fun save() {
        val f = file ?: return
        runCatching { File(f.parentFile, f.name + ".tmp").apply { writeText(times.joinToString(",")) }.renameTo(f) }
    }

    companion object {
        const val MINUTE_MS = 60_000L
        const val DAY_MS = 24 * 3_600_000L
    }
}
