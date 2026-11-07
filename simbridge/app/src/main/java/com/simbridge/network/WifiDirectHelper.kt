package com.simbridge.network

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.util.Log

private const val TAG = "WifiDirectHelper"

/**
 * Thin wrapper around [WifiP2pManager].
 *
 * HOST flow  : [createGroup] → share "Connect to my hotspot, then enter 192.168.49.1"
 * CLIENT flow: [discoverPeers] → user picks a device → [connectToPeer]
 *              → [requestGroupOwnerAddress] → pass to ConnectionManager.connectToServer()
 *
 * Important: WiFi Direct requires the user to grant location permission on API ≤ 32,
 * or NEARBY_WIFI_DEVICES on API 33+.  The app requests these in MainActivity.
 */
class WifiDirectHelper(private val context: Context) {

    private val manager = context.getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
    private val channel = manager.initialize(context, context.mainLooper, null)

    private var receiver: BroadcastReceiver? = null

    var onPeersChanged: ((List<WifiP2pDevice>) -> Unit)? = null
    var onConnectionInfoAvailable: ((WifiP2pInfo) -> Unit)? = null

    // ---- lifecycle ----

    fun register() {
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }
        receiver = object : BroadcastReceiver() {
            @SuppressLint("MissingPermission")
            override fun onReceive(ctx: Context, intent: Intent) {
                when (intent.action) {
                    WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                        manager.requestPeers(channel) { peerList ->
                            onPeersChanged?.invoke(peerList.deviceList.toList())
                        }
                    }
                    WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                        manager.requestConnectionInfo(channel) { info ->
                            if (info.groupFormed) {
                                onConnectionInfoAvailable?.invoke(info)
                            }
                        }
                    }
                }
            }
        }
        context.registerReceiver(receiver, filter)
    }

    fun unregister() {
        receiver?.let { context.unregisterReceiver(it) }
        receiver = null
    }

    // ---- HOST ----

    @SuppressLint("MissingPermission")
    fun createGroup(onSuccess: () -> Unit, onFailure: (Int) -> Unit) {
        manager.createGroup(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d(TAG, "WiFi Direct group created — HOST IP is 192.168.49.1")
                onSuccess()
            }
            override fun onFailure(reason: Int) {
                Log.e(TAG, "createGroup failed: $reason")
                onFailure(reason)
            }
        })
    }

    fun removeGroup() {
        manager.removeGroup(channel, null)
    }

    // ---- CLIENT ----

    @SuppressLint("MissingPermission")
    fun discoverPeers(onSuccess: () -> Unit, onFailure: (Int) -> Unit) {
        manager.discoverPeers(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() { onSuccess() }
            override fun onFailure(reason: Int) { onFailure(reason) }
        })
    }

    @SuppressLint("MissingPermission")
    fun connectToPeer(device: WifiP2pDevice, onSuccess: () -> Unit, onFailure: (Int) -> Unit) {
        val config = WifiP2pConfig().apply { deviceAddress = device.deviceAddress }
        manager.connect(channel, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() { onSuccess() }
            override fun onFailure(reason: Int) { onFailure(reason) }
        })
    }

    fun requestConnectionInfo(onInfo: (WifiP2pInfo) -> Unit) {
        manager.requestConnectionInfo(channel) { info -> onInfo(info) }
    }

    // The group owner in WiFi Direct always gets this address on Android.
    val groupOwnerIp: String get() = "192.168.49.1"
}
