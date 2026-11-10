package com.kvmcontrol.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Presentation
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.Binder
import android.os.IBinder
import android.view.Display
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.kvmcontrol.app.R
import com.kvmcontrol.app.data.KvmStats
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

private const val CHANNEL_ID = "cover_screen"
private const val NOTIFICATION_ID = 1002

/**
 * Displays KVM stats on a secondary display (flip phone cover screen).
 * Detects external/secondary displays via DisplayManager and uses
 * Android's Presentation API to render a compact stats overlay.
 */
class CoverScreenService : Service() {

    inner class LocalBinder : Binder() {
        fun updateStats(stats: KvmStats) = this@CoverScreenService.updateStats(stats)
        fun start(statsFlow: Flow<KvmStats>) = this@CoverScreenService.observeStats(statsFlow)
    }

    private val binder = LocalBinder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var coverPresentation: KvmStatsPresentation? = null
    private var displayManager: DisplayManager? = null

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = bindCoverDisplay()
        override fun onDisplayRemoved(displayId: Int) = releaseCoverDisplay()
        override fun onDisplayChanged(displayId: Int) {}
    }

    override fun onCreate() {
        super.onCreate()
        displayManager = getSystemService(DisplayManager::class.java)
        displayManager?.registerDisplayListener(displayListener, null)
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        bindCoverDisplay()
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onDestroy() {
        displayManager?.unregisterDisplayListener(displayListener)
        releaseCoverDisplay()
        super.onDestroy()
    }

    private fun bindCoverDisplay() {
        val dm = displayManager ?: return
        val displays = dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
        val coverDisplay = displays.firstOrNull() ?: return
        releaseCoverDisplay()
        coverPresentation = KvmStatsPresentation(this, coverDisplay).also { it.show() }
    }

    private fun releaseCoverDisplay() {
        coverPresentation?.dismiss()
        coverPresentation = null
    }

    fun updateStats(stats: KvmStats) {
        coverPresentation?.updateStats(stats)
    }

    private fun observeStats(flow: Flow<KvmStats>) {
        flow.onEach { updateStats(it) }.launchIn(scope)
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_cover),
            NotificationManager.IMPORTANCE_MIN,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.cover_screen_title))
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
}

class KvmStatsPresentation(context: Context, display: Display) : Presentation(context, display) {

    private lateinit var tvCpu: TextView
    private lateinit var tvRam: TextView
    private lateinit var tvNetwork: TextView
    private lateinit var tvLatency: TextView
    private lateinit var tvStatus: TextView

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        window?.setType(WindowManager.LayoutParams.TYPE_PRESENTATION)

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
            setBackgroundColor(0xFF0A0A0A.toInt())
        }

        fun makeLabel(text: String) = TextView(context).apply {
            this.text = text
            textSize = 10f
            setTextColor(0xFF888888.toInt())
        }

        fun makeValue() = TextView(context).apply {
            textSize = 14f
            setTextColor(0xFFEEEEEE.toInt())
        }

        val titleView = TextView(context).apply {
            text = "KVM Stats"
            textSize = 16f
            setTextColor(0xFF4CAF50.toInt())
        }

        tvCpu = makeValue()
        tvRam = makeValue()
        tvNetwork = makeValue()
        tvLatency = makeValue()
        tvStatus = makeValue()

        root.addView(titleView)
        root.addView(makeLabel("CPU")); root.addView(tvCpu)
        root.addView(makeLabel("RAM")); root.addView(tvRam)
        root.addView(makeLabel("Network")); root.addView(tvNetwork)
        root.addView(makeLabel("Latency")); root.addView(tvLatency)
        root.addView(makeLabel("Status")); root.addView(tvStatus)

        setContentView(root)
    }

    fun updateStats(stats: KvmStats) {
        tvCpu.text = "%.1f%%".format(stats.cpuPercent)
        tvRam.text = "${stats.ramUsedMb}/${stats.ramTotalMb} MB (%.0f%%)".format(stats.ramPercent)
        tvNetwork.text = "↓ %.1f KB/s  ↑ %.1f KB/s".format(stats.networkRxKbps, stats.networkTxKbps)
        tvLatency.text = if (stats.latencyMs >= 0) "${stats.latencyMs} ms" else "—"
        tvStatus.text = stats.connectionState.name
    }
}
