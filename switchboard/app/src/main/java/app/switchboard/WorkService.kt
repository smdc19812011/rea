package app.switchboard

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager

/**
 * Foreground service held while a reply or tool run is in progress, so Android keeps the app's network
 * and CPU alive when it's in the background (e.g. while the user watches Termux or locks the screen).
 */
class WorkService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Working in the background", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        val n: Notification = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, CHANNEL) else @Suppress("DEPRECATION") Notification.Builder(this))
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Switchboard is working")
            .setContentText(intent?.getStringExtra(EXTRA_TEXT) ?: "Waiting for the model…")
            .setContentIntent(open).setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(ID, n)
        if (wakeLock == null) wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "switchboard:work").apply { setReferenceCounted(false); acquire(3 * 3_600_000L) }
        return START_NOT_STICKY
    }

    override fun onDestroy() { wakeLock?.release(); wakeLock = null; super.onDestroy() }

    companion object {
        private const val CHANNEL = "work"
        private const val ID = 1
        private const val EXTRA_TEXT = "text"

        fun start(ctx: Context, text: String? = null) = runCatching {
            val i = Intent(ctx, WorkService::class.java).putExtra(EXTRA_TEXT, text)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i) else ctx.startService(i)
        } // best effort: the request still runs if Android refuses the service

        fun stop(ctx: Context) { ctx.stopService(Intent(ctx, WorkService::class.java)) }
    }
}
