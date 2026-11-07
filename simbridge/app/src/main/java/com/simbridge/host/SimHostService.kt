package com.simbridge.host

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import android.telecom.TelecomManager
import android.util.Log
import com.simbridge.MainActivity
import com.simbridge.data.CallCommand
import com.simbridge.network.AppTransport
import com.simbridge.network.BluetoothTransport
import com.simbridge.network.ConnectionManager
import com.simbridge.network.LocalSignaling
import com.simbridge.network.NsdHelper
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "SimHostService"
private const val NOTIF_CHANNEL = "simbridge_host"
private const val NOTIF_ID = 1001

/**
 * Foreground service on the HOST phone.
 *
 * Starts three servers simultaneously on onCreate:
 *  1. TCP server on port 47892 — WiFi and WiFi Direct clients connect here.
 *  2. NSD (mDNS) registration — same-WiFi clients auto-discover the HOST.
 *  3. Bluetooth RFCOMM server — Bluetooth clients connect here.
 *
 * The first CLIENT connection (TCP or BT) wins; subsequent connections are
 * accepted by the socket layer but commands are only processed for the first.
 */
class SimHostService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val signaling = LocalSignaling()
    private var commandJob: Job? = null
    private var nsdHelper: NsdHelper? = null
    private val clientActive = AtomicBoolean(false)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification("Waiting for CLIENT connection…"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startTcpServer()
        startBluetoothServer()
        return START_STICKY
    }

    override fun onDestroy() {
        nsdHelper?.unregisterService()
        commandJob?.cancel()
        AppTransport.disconnect()
        scope.cancel()
        super.onDestroy()
    }

    // ---- TCP + NSD ----

    private fun startTcpServer() {
        val nsd = NsdHelper(this).also { nsdHelper = it }

        ConnectionManager.startAsServer(
            onReady = { port ->
                nsd.registerService(port) { name -> Log.d(TAG, "NSD registered as $name") }
                Log.d(TAG, "TCP server ready — IP ${ConnectionManager.localIpAddress()} port $port")
            },
            onClientConnected = {
                if (clientActive.compareAndSet(false, true)) {
                    AppTransport.use(ConnectionManager)
                    updateNotification("CLIENT connected via WiFi — bridging calls")
                    listenForClientCommands()
                }
            },
            onError = { err ->
                Log.e(TAG, "TCP server error: $err")
                updateNotification("Server error: $err")
            }
        )
    }

    // ---- Bluetooth RFCOMM ----

    private fun startBluetoothServer() {
        val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = btManager?.adapter
        if (adapter == null || !adapter.isEnabled) {
            Log.d(TAG, "Bluetooth not available — skipping BT server")
            return
        }

        BluetoothTransport.startServer(
            adapter = adapter,
            onConnected = {
                if (clientActive.compareAndSet(false, true)) {
                    AppTransport.use(BluetoothTransport)
                    updateNotification("CLIENT connected via Bluetooth — bridging calls")
                    listenForClientCommands()
                }
            },
            onError = { err -> Log.e(TAG, "BT server error: $err") }
        )
    }

    // ---- command dispatch ----

    private fun listenForClientCommands() {
        commandJob = signaling.observeCallCommand(scope) { cmd ->
            Log.d(TAG, "Client command: ${cmd.type}")
            handleCommand(cmd)
        }
    }

    private fun handleCommand(cmd: CallCommand) {
        when (cmd.type) {
            "MAKE_CALL"   -> placeCall(cmd.number)
            "ANSWER_CALL" -> SimInCallService.instance?.answerCurrentCall()
            "REJECT_CALL" -> SimInCallService.instance?.rejectCurrentCall()
            "END_CALL"    -> SimInCallService.instance?.endCurrentCall()
        }
    }

    private fun placeCall(number: String) {
        if (number.isBlank()) return
        try {
            val telecom = getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            telecom.placeCall(Uri.fromParts("tel", number, null), null)
        } catch (e: SecurityException) {
            Log.e(TAG, "Cannot place call — missing CALL_PHONE or not default Phone app: ${e.message}")
        }
    }

    // ---- notification helpers ----

    private fun createNotificationChannel() {
        val ch = NotificationChannel(NOTIF_CHANNEL, "SimBridge Host", NotificationManager.IMPORTANCE_LOW)
            .apply { description = "HOST mode — routing calls via SIM" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, NOTIF_CHANNEL)
            .setContentTitle("SimBridge — HOST")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIF_ID, buildNotification(text))
    }
}
