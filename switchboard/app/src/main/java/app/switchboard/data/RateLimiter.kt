package app.switchboard.data

import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/** How the daily allowance resets. */
enum class DailyReset { MIDNIGHT, ROLLING }

/**
 * Request limiter with a separate count per key (one key per provider + model), shared by every chat and by
 * background tasks. Request times are persisted so limits survive restarts. A limit of 0 means unlimited.
 */
class RateLimiter(private val file: File?, private val clock: () -> Long = System::currentTimeMillis) {
    data class Limits(val perMinute: Int, val perDay: Int, val dailyReset: DailyReset, val zone: ZoneId)
    enum class Scope { MINUTE, DAY }
    /** A request is blocked by [scope] until [untilMs]. */
    data class Wait(val scope: Scope, val untilMs: Long)
    data class Usage(val lastMinute: Int, val today: Int)

    private val times = HashMap<String, ArrayDeque<Long>>()

    init { reload() }

    /** File format: one line per key, "key<TAB>t1,t2,...". */
    private fun reload() {
        val f = file?.takeIf { it.exists() } ?: return
        times.clear()
        f.readLines().forEach { line ->
            val key = line.substringBefore('\t', "")
            val list = line.substringAfter('\t').split(',').mapNotNull { it.trim().toLongOrNull() }.sorted()
            if (list.isNotEmpty()) times[key] = ArrayDeque(list)
        }
    }

    private fun save() {
        val f = file ?: return
        runCatching {
            File(f.parentFile, f.name + ".tmp").apply { writeText(times.entries.joinToString("\n") { (k, v) -> "$k\t" + v.joinToString(",") }) }.renameTo(f)
        }
    }

    fun tryAcquire(l: Limits): Wait? = tryAcquire("", l)
    fun usage(l: Limits): Usage = usage("", l)

    /** Records a request for [key] and returns null if it may go now; otherwise how long to wait (nothing recorded). */
    @Synchronized
    fun tryAcquire(key: String, l: Limits): Wait? {
        reload() // chats and background tasks share the file
        val now = clock()
        prune(now)
        val list = times.getOrPut(key) { ArrayDeque() }
        val dayStart = dayWindowStart(now, l)
        if (l.perDay > 0) {
            val inDay = list.filter { it >= dayStart }
            if (inDay.size >= l.perDay) {
                val until = when (l.dailyReset) {
                    DailyReset.MIDNIGHT -> nextMidnight(now, l.zone)
                    DailyReset.ROLLING -> inDay[inDay.size - l.perDay] + DAY_MS
                }
                return Wait(Scope.DAY, until)
            }
        }
        if (l.perMinute > 0) {
            val inMinute = list.filter { it > now - MINUTE_MS }
            if (inMinute.size >= l.perMinute) return Wait(Scope.MINUTE, inMinute[inMinute.size - l.perMinute] + MINUTE_MS + 1)
        }
        list.addLast(now)
        save()
        return null
    }

    /** Would a request for [key] be allowed right now? (Doesn't record anything.) */
    @Synchronized
    fun isBlocked(key: String, l: Limits): Boolean {
        val u = usage(key, l)
        return (l.perMinute > 0 && u.lastMinute >= l.perMinute) || (l.perDay > 0 && u.today >= l.perDay)
    }

    @Synchronized
    fun usage(key: String, l: Limits): Usage {
        reload()
        val now = clock()
        val list = times[key].orEmpty()
        val dayStart = dayWindowStart(now, l)
        return Usage(list.count { it > now - MINUTE_MS }, list.count { it >= dayStart })
    }

    private fun dayWindowStart(now: Long, l: Limits) = when (l.dailyReset) {
        DailyReset.MIDNIGHT -> ZonedDateTime.ofInstant(Instant.ofEpochMilli(now), l.zone).toLocalDate().atStartOfDay(l.zone).toInstant().toEpochMilli()
        DailyReset.ROLLING -> now - DAY_MS + 1
    }

    private fun nextMidnight(now: Long, zone: ZoneId) =
        ZonedDateTime.ofInstant(Instant.ofEpochMilli(now), zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

    /** Keeps ~26 h of history: enough for a rolling day or a calendar day that's 25 h long because of daylight saving. */
    private fun prune(now: Long) {
        times.values.forEach { l -> while (l.isNotEmpty() && l.first() < now - 26 * 3_600_000L) l.removeFirst() }
        times.entries.removeAll { it.value.isEmpty() }
    }

    companion object {
        const val MINUTE_MS = 60_000L
        const val DAY_MS = 24 * 3_600_000L
        fun key(providerId: String, model: String) = "$providerId/$model"
    }
}
