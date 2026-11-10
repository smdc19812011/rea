package com.kvmcontrol.app.service

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log

private const val TAG = "UsbPermHelper"
private const val ACTION_USB_PERMISSION = "com.kvmcontrol.app.USB_PERMISSION"

/**
 * Helper to request USB permission for a device without blocking the UI.
 * Android requires explicit user consent before an app can open a USB device.
 */
class UsbPermissionHelper(private val context: Context) {

    private val usbManager = context.getSystemService(UsbManager::class.java)
    private var pendingCallback: ((Boolean) -> Unit)? = null
    private var receiver: BroadcastReceiver? = null

    fun requestPermission(device: UsbDevice, onResult: (granted: Boolean) -> Unit) {
        if (usbManager.hasPermission(device)) {
            onResult(true)
            return
        }

        pendingCallback = onResult
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            PendingIntent.FLAG_MUTABLE else 0
        val pi = PendingIntent.getBroadcast(context, 0, Intent(ACTION_USB_PERMISSION), flags)

        val r = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action != ACTION_USB_PERMISSION) return
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                Log.d(TAG, "USB permission result: granted=$granted")
                context.unregisterReceiver(this)
                receiver = null
                pendingCallback?.invoke(granted)
                pendingCallback = null
            }
        }
        receiver = r

        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(r, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(r, filter)
        }

        usbManager.requestPermission(device, pi)
    }

    fun cleanup() {
        receiver?.let {
            try { context.unregisterReceiver(it) } catch (_: Exception) {}
        }
        receiver = null
        pendingCallback = null
    }
}
