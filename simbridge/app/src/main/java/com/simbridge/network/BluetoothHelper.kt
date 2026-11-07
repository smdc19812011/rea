package com.simbridge.network

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build

/**
 * Thin wrapper around Bluetooth device discovery and adapter access.
 *
 * Required manifest permissions:
 *  - BLUETOOTH / BLUETOOTH_ADMIN (API ≤ 30)
 *  - BLUETOOTH_CONNECT (API 31+)
 *  - BLUETOOTH_SCAN (API 31+)
 *  - BLUETOOTH_ADVERTISE (API 31+, for HOST discoverability)
 */
class BluetoothHelper(private val context: Context) {

    val adapter: BluetoothAdapter? by lazy {
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    }

    fun isAvailable() = adapter != null
    fun isEnabled() = adapter?.isEnabled == true

    /** Returns devices that are already paired with this phone. */
    @Suppress("MissingPermission")
    fun getBondedDevices(): List<BluetoothDevice> =
        adapter?.bondedDevices?.toList() ?: emptyList()

    /**
     * Starts Bluetooth device discovery and returns the [BroadcastReceiver] that must be
     * unregistered when discovery is no longer needed.
     */
    @Suppress("MissingPermission")
    fun startDiscovery(onDevice: (BluetoothDevice) -> Unit): BroadcastReceiver {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action == BluetoothDevice.ACTION_FOUND) {
                    val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= 33) {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    }
                    device?.let { onDevice(it) }
                }
            }
        }
        context.registerReceiver(receiver, IntentFilter(BluetoothDevice.ACTION_FOUND))
        adapter?.startDiscovery()
        return receiver
    }

    @Suppress("MissingPermission")
    fun stopDiscovery(receiver: BroadcastReceiver) {
        adapter?.cancelDiscovery()
        try { context.unregisterReceiver(receiver) } catch (_: Exception) {}
    }

    /** Launches the system dialog to make this device discoverable for [durationSec] seconds. */
    fun makeDiscoverableIntent(durationSec: Int = 300): Intent =
        Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
            .putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, durationSec)

    @Suppress("MissingPermission")
    fun deviceName(): String = adapter?.name ?: "Unknown"
}
