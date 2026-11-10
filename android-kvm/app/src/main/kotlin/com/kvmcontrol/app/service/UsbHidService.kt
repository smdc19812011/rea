package com.kvmcontrol.app.service

import android.content.Context
import android.util.Log
import com.kvmcontrol.app.data.ConnectionState
import com.kvmcontrol.app.data.KeyEvent
import com.kvmcontrol.app.data.MouseEvent
import com.kvmcontrol.app.util.HidDescriptors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.FileOutputStream

private const val TAG = "UsbHidService"
private const val HID_KEYBOARD = "/dev/hidg0"
private const val HID_MOUSE = "/dev/hidg1"

/**
 * USB HID emulation via /dev/hidgX kernel gadget.
 * Requires kernel USB gadget support and root access.
 * Gracefully reports unavailable on non-rooted devices.
 */
class UsbHidService(private val context: Context) {

    private val _state = MutableStateFlow(ConnectionState.DISCONNECTED)
    val state: StateFlow<ConnectionState> = _state

    private var keyboardStream: FileOutputStream? = null
    private var mouseStream: FileOutputStream? = null

    fun isAvailable(): Boolean {
        return File(HID_KEYBOARD).exists() && File(HID_MOUSE).exists()
    }

    fun open(): Boolean {
        return try {
            keyboardStream = FileOutputStream(HID_KEYBOARD)
            mouseStream = FileOutputStream(HID_MOUSE)
            _state.value = ConnectionState.CONNECTED
            true
        } catch (e: Exception) {
            Log.w(TAG, "Cannot open HID gadget (root required): ${e.message}")
            _state.value = ConnectionState.ERROR
            false
        }
    }

    fun close() {
        keyboardStream?.runCatching { close() }
        mouseStream?.runCatching { close() }
        keyboardStream = null
        mouseStream = null
        _state.value = ConnectionState.DISCONNECTED
    }

    fun sendKeyEvent(event: KeyEvent): Boolean {
        val stream = keyboardStream ?: return false
        return try {
            val report = HidDescriptors.buildKeyboardReport(event.modifier, *event.keys.toByteArray())
            stream.write(report)
            stream.flush()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Key write failed: ${e.message}")
            false
        }
    }

    fun sendKeyRelease(): Boolean {
        val stream = keyboardStream ?: return false
        return try {
            stream.write(ByteArray(8))
            stream.flush()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Key release failed: ${e.message}")
            false
        }
    }

    fun sendMouseEvent(event: MouseEvent): Boolean {
        val stream = mouseStream ?: return false
        return try {
            val report = HidDescriptors.buildMouseReport(event.buttons, event.dx, event.dy, event.wheel)
            stream.write(report)
            stream.flush()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Mouse write failed: ${e.message}")
            false
        }
    }

    private fun List<Byte>.toByteArray(): ByteArray = ByteArray(size) { this[it] }
}
