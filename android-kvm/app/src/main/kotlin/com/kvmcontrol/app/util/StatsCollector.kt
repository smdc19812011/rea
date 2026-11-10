package com.kvmcontrol.app.util

import android.app.ActivityManager
import android.content.Context
import android.net.TrafficStats
import com.kvmcontrol.app.data.KvmStats
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.BufferedReader
import java.io.FileReader

class StatsCollector(private val context: Context) {

    private var lastRxBytes = TrafficStats.getTotalRxBytes()
    private var lastTxBytes = TrafficStats.getTotalTxBytes()
    private var lastPollTime = System.currentTimeMillis()

    fun statsFlow(intervalMs: Long = 2000L): Flow<KvmStats> = flow {
        while (true) {
            emit(collect())
            delay(intervalMs)
        }
    }

    fun collect(): KvmStats {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)

        val ramTotalMb = memInfo.totalMem / (1024 * 1024)
        val ramUsedMb = (memInfo.totalMem - memInfo.availMem) / (1024 * 1024)

        val now = System.currentTimeMillis()
        val elapsedSec = (now - lastPollTime) / 1000f
        val rxBytes = TrafficStats.getTotalRxBytes()
        val txBytes = TrafficStats.getTotalTxBytes()

        val rxKbps = if (elapsedSec > 0 && rxBytes != TrafficStats.UNSUPPORTED.toLong())
            (rxBytes - lastRxBytes) / 1024f / elapsedSec else 0f
        val txKbps = if (elapsedSec > 0 && txBytes != TrafficStats.UNSUPPORTED.toLong())
            (txBytes - lastTxBytes) / 1024f / elapsedSec else 0f

        lastRxBytes = rxBytes
        lastTxBytes = txBytes
        lastPollTime = now

        return KvmStats(
            cpuPercent = readCpuPercent(),
            ramUsedMb = ramUsedMb,
            ramTotalMb = ramTotalMb,
            networkRxKbps = maxOf(0f, rxKbps),
            networkTxKbps = maxOf(0f, txKbps),
        )
    }

    private var lastIdleTime = 0L
    private var lastTotalTime = 0L

    private fun readCpuPercent(): Float {
        return try {
            BufferedReader(FileReader("/proc/stat")).use { reader ->
                val line = reader.readLine() ?: return 0f
                val parts = line.trim().split("\\s+".toRegex())
                if (parts.size < 8) return 0f
                val user = parts[1].toLong()
                val nice = parts[2].toLong()
                val system = parts[3].toLong()
                val idle = parts[4].toLong()
                val iowait = parts[5].toLong()
                val irq = parts[6].toLong()
                val softirq = parts[7].toLong()
                val totalIdle = idle + iowait
                val totalTime = user + nice + system + idle + iowait + irq + softirq
                val diffIdle = totalIdle - lastIdleTime
                val diffTotal = totalTime - lastTotalTime
                lastIdleTime = totalIdle
                lastTotalTime = totalTime
                if (diffTotal == 0L) 0f
                else (1f - diffIdle.toFloat() / diffTotal.toFloat()) * 100f
            }
        } catch (_: Exception) {
            0f
        }
    }
}
