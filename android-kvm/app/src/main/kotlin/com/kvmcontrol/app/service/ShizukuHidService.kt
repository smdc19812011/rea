package com.kvmcontrol.app.service

import android.content.ComponentName
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import com.kvmcontrol.app.data.ConnectionState
import com.kvmcontrol.app.data.KeyEvent
import com.kvmcontrol.app.data.MouseEvent
import com.kvmcontrol.app.util.HidDescriptors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import rikka.shizuku.Shizuku

private const val TAG = "ShizukuHidService"
private const val SHIZUKU_PERMISSION_CODE = 0x1312

/**
 * USB HID over Shizuku — the best of both worlds:
 *   • No permanent root required (Shizuku starts via ADB or a one-time root grant)
 *   • Full USB HID gadget speed/compatibility (native /dev/hidg0 & /dev/hidg1)
 *   • Works on stock Android — no kernel recompilation
 *
 * How it works:
 *   1. Shizuku runs a small privileged server (started once via ADB or root-once)
 *   2. This service asks Shizuku to launch HidUserService in that privileged process
 *   3. HidUserService opens /dev/hidg0 & /dev/hidg1 — it can because it runs as shell/root
 *   4. The main app sends HID reports via the IHidBridge AIDL binder
 *
 * Prerequisites on the device:
 *   • Shizuku app installed and running (shizuku.rikka.app)
 *   • USB gadget configured: Android set to USB HID mode
 *     (Settings → Developer Options → USB Configuration → HID, or via ADB:
 *      `adb shell svc usb setFunctions hid`)
 *   • /dev/hidg0 and /dev/hidg1 present (kernel USB gadget support)
 */
class ShizukuHidService {

    private var bridge: IHidBridge? = null
    private var permissionGranted = false

    private val _state = MutableStateFlow(ConnectionState.DISCONNECTED)
    val state: StateFlow<ConnectionState> = _state

    // ── Shizuku lifecycle ──────────────────────────────────────────────────

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        Log.i(TAG, "Shizuku binder received (version ${Shizuku.getVersion()})")
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        Log.w(TAG, "Shizuku binder died")
        bridge = null
        _state.value = ConnectionState.DISCONNECTED
    }

    private val permissionResultListener =
        Shizuku.OnRequestPermissionResultListener { _, grantResult ->
            permissionGranted = grantResult == android.content.pm.PackageManager.PERMISSION_GRANTED
            Log.d(TAG, "Shizuku permission: granted=$permissionGranted")
            if (permissionGranted) bindUserService()
        }

    private val userServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            bridge = IHidBridge.Stub.asInterface(binder)
            _state.value = if (bridge?.isAvailable() == true) {
                Log.i(TAG, "HidUserService connected — /dev/hidg* accessible")
                ConnectionState.CONNECTED
            } else {
                Log.w(TAG, "HidUserService connected but /dev/hidg* not accessible (need HID gadget mode)")
                ConnectionState.ERROR
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.w(TAG, "HidUserService disconnected")
            bridge = null
            _state.value = ConnectionState.DISCONNECTED
        }
    }

    private val userServiceArgs = Shizuku.UserServiceArgs(
        ComponentName("com.kvmcontrol.app", HidUserService::class.java.name)
    )
        .daemon(false)
        .processNameSuffix("hid_service")
        .debuggable(false)
        .version(1)

    // ── Public API ────────────────────────────────────────────────────────

    fun init() {
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        Shizuku.addRequestPermissionResultListener(permissionResultListener)
    }

    fun destroy() {
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
        Shizuku.removeRequestPermissionResultListener(permissionResultListener)
        unbindUserService()
    }

    /** True if Shizuku server is running (regardless of permission). */
    fun isShizukuAvailable(): Boolean = try {
        Shizuku.pingBinder()
    } catch (_: Exception) { false }

    /** True if the app has permission AND the HID gadget device files are accessible. */
    fun isAvailable(): Boolean = isShizukuAvailable() && bridge?.isAvailable() == true

    fun connect(onError: () -> Unit) {
        _state.value = ConnectionState.CONNECTING
        when {
            !isShizukuAvailable() -> {
                Log.w(TAG, "Shizuku not running")
                _state.value = ConnectionState.ERROR
                onError()
            }
            !hasPermission() -> {
                Log.d(TAG, "Requesting Shizuku permission")
                Shizuku.requestPermission(SHIZUKU_PERMISSION_CODE)
                // Result arrives in permissionResultListener → bindUserService()
            }
            else -> bindUserService()
        }
    }

    fun disconnect() {
        try { bridge?.destroy() } catch (_: Exception) {}
        unbindUserService()
        _state.value = ConnectionState.DISCONNECTED
    }

    fun sendKeyEvent(event: KeyEvent): Boolean {
        val keys = event.keys.take(6).toMutableList()
        while (keys.size < 6) keys.add(0)
        val report = byteArrayOf(event.modifier, 0, keys[0], keys[1], keys[2], keys[3], keys[4], keys[5])
        return try { bridge?.sendKeyboardReport(report) ?: false } catch (e: Exception) {
            Log.e(TAG, "sendKeyEvent: ${e.message}"); false
        }
    }

    fun sendKeyRelease(): Boolean = try {
        bridge?.releaseKeys() ?: false
    } catch (e: Exception) {
        Log.e(TAG, "sendKeyRelease: ${e.message}"); false
    }

    fun sendMouseEvent(event: MouseEvent): Boolean {
        val report = HidDescriptors.buildMouseReport(event.buttons, event.dx, event.dy, event.wheel)
        return try { bridge?.sendMouseReport(report) ?: false } catch (e: Exception) {
            Log.e(TAG, "sendMouseEvent: ${e.message}"); false
        }
    }

    // ── Internal ──────────────────────────────────────────────────────────

    private fun hasPermission(): Boolean = try {
        Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
    } catch (_: Exception) { false }

    private fun bindUserService() {
        try {
            Shizuku.bindUserService(userServiceArgs, userServiceConnection)
        } catch (e: Exception) {
            Log.e(TAG, "bindUserService failed: ${e.message}")
            _state.value = ConnectionState.ERROR
        }
    }

    private fun unbindUserService() {
        try {
            Shizuku.unbindUserService(userServiceArgs, userServiceConnection, true)
        } catch (_: Exception) {}
        bridge = null
    }
}
