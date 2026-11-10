package com.kvmcontrol.app.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import java.io.FileOutputStream

private const val TAG = "HidUserService"

/**
 * Privileged UserService launched by Shizuku in an elevated process (ADB or root).
 *
 * This service runs with the privileges of the Shizuku server — either `shell` (ADB)
 * or `root` — which means it can open /dev/hidg0 and /dev/hidg1 directly, even though
 * the main app process cannot.
 *
 * The main app binds this service via Shizuku.bindUserService() and calls it via the
 * IHidBridge AIDL interface.
 *
 * Lifecycle: created on first bind, destroyed when the main app calls destroy() or
 * unbinds. Shizuku keeps it alive as long as bindings exist.
 */
class HidUserService : Service() {

    private var kbdOut: FileOutputStream? = null
    private var mouseOut: FileOutputStream? = null

    private val binder = object : IHidBridge.Stub() {

        override fun isAvailable(): Boolean {
            return try {
                ensureOpen()
                kbdOut != null && mouseOut != null
            } catch (e: Exception) {
                Log.w(TAG, "isAvailable: ${e.message}")
                false
            }
        }

        override fun sendKeyboardReport(report: ByteArray): Boolean {
            if (report.size < 8) return false
            return try {
                ensureOpen()
                kbdOut?.write(report, 0, 8)
                kbdOut?.flush()
                true
            } catch (e: Exception) {
                Log.e(TAG, "sendKeyboardReport: ${e.message}")
                false
            }
        }

        override fun sendMouseReport(report: ByteArray): Boolean {
            if (report.size < 4) return false
            return try {
                ensureOpen()
                mouseOut?.write(report, 0, 4)
                mouseOut?.flush()
                true
            } catch (e: Exception) {
                Log.e(TAG, "sendMouseReport: ${e.message}")
                false
            }
        }

        override fun releaseKeys(): Boolean {
            return sendKeyboardReport(ByteArray(8))
        }

        override fun destroy() {
            closeDevices()
            stopSelf()
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        closeDevices()
        super.onDestroy()
    }

    private fun ensureOpen() {
        if (kbdOut == null) {
            kbdOut = FileOutputStream("/dev/hidg0")
            Log.i(TAG, "Opened /dev/hidg0")
        }
        if (mouseOut == null) {
            mouseOut = FileOutputStream("/dev/hidg1")
            Log.i(TAG, "Opened /dev/hidg1")
        }
    }

    private fun closeDevices() {
        try { kbdOut?.close() } catch (_: Exception) {}
        try { mouseOut?.close() } catch (_: Exception) {}
        kbdOut = null
        mouseOut = null
    }
}
