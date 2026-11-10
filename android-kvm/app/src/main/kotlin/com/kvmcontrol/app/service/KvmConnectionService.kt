package com.kvmcontrol.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.kvmcontrol.app.MainActivity
import com.kvmcontrol.app.R
import com.kvmcontrol.app.data.ConnectionConfig
import com.kvmcontrol.app.data.ConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

private const val CHANNEL_ID = "kvm_connection"
private const val NOTIFICATION_ID = 1001

class KvmConnectionService : Service() {

    inner class LocalBinder : Binder() {
        fun getManager(): KvmConnectionManager = manager
    }

    private val binder = LocalBinder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    lateinit var manager: KvmConnectionManager
        private set

    override fun onCreate() {
        super.onCreate()
        manager = KvmConnectionManager(this)
        createNotificationChannel()

        manager.connectionState.onEach { state ->
            updateNotification(state, manager.deviceName.value)
        }.launchIn(scope)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification(ConnectionState.CONNECTING, "")
        startForeground(NOTIFICATION_ID, notification)
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onDestroy() {
        manager.disconnect()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_kvm),
            NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(state: ConnectionState, deviceName: String): Notification {
        val tapIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val title = when (state) {
            ConnectionState.CONNECTED -> getString(R.string.notification_title_connected)
            else -> getString(R.string.notification_title_connecting)
        }
        val text = if (state == ConnectionState.CONNECTED && deviceName.isNotEmpty())
            getString(R.string.notification_text_connected, deviceName)
        else state.name

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentIntent(tapIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(state: ConnectionState, deviceName: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(state, deviceName))
    }
}
