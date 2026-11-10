package com.kvmcontrol.app.service

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.util.Log
import com.kvmcontrol.app.data.ConnectionState
import com.kvmcontrol.app.data.KeyEvent
import com.kvmcontrol.app.data.MouseEvent
import com.kvmcontrol.app.util.HidDescriptors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.Executors

private const val TAG = "BluetoothHidService"

class BluetoothHidService(private val context: Context) {

    private val bluetoothManager = context.getSystemService(BluetoothManager::class.java)
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter

    private var hidDevice: BluetoothHidDevice? = null
    private var connectedDevice: BluetoothDevice? = null
    private val executor = Executors.newSingleThreadExecutor()

    private val _state = MutableStateFlow(ConnectionState.DISCONNECTED)
    val state: StateFlow<ConnectionState> = _state

    private val _connectedDeviceName = MutableStateFlow("")
    val connectedDeviceName: StateFlow<String> = _connectedDeviceName

    private val sdpSettings = BluetoothHidDeviceAppSdpSettings(
        "AndroidKVM",
        "Android KVM Controller",
        "KVMControl",
        BluetoothHidDevice.SUBCLASS1_COMBO,
        HidDescriptors.COMBINED,
    )

    private val hidCallback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            Log.d(TAG, "HID app status: registered=$registered")
            if (registered && pluggedDevice != null) {
                connectedDevice = pluggedDevice
                _connectedDeviceName.value = pluggedDevice.name ?: pluggedDevice.address
                _state.value = ConnectionState.CONNECTED
            } else if (!registered) {
                connectedDevice = null
                _state.value = ConnectionState.DISCONNECTED
            }
        }

        override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
            Log.d(TAG, "HID connection state: $state for ${device.address}")
            when (state) {
                BluetoothProfile.STATE_CONNECTED -> {
                    connectedDevice = device
                    _connectedDeviceName.value = device.name ?: device.address
                    _state.value = ConnectionState.CONNECTED
                }
                BluetoothProfile.STATE_CONNECTING -> _state.value = ConnectionState.CONNECTING
                BluetoothProfile.STATE_DISCONNECTED -> {
                    connectedDevice = null
                    _state.value = ConnectionState.DISCONNECTED
                }
            }
        }

        override fun onGetReport(device: BluetoothDevice, type: Byte, id: Byte, bufferSize: Int) {
            hidDevice?.replyReport(device, type, id, ByteArray(0))
        }

        override fun onSetReport(device: BluetoothDevice, type: Byte, id: Byte, data: ByteArray) {}

        override fun onInterruptData(device: BluetoothDevice, reportId: Byte, data: ByteArray) {}
    }

    fun isAvailable(): Boolean = bluetoothAdapter != null && bluetoothAdapter.isEnabled

    fun register(onRegistered: (Boolean) -> Unit) {
        if (!isAvailable()) {
            onRegistered(false)
            return
        }
        _state.value = ConnectionState.CONNECTING
        bluetoothAdapter!!.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                if (profile == BluetoothProfile.HID_DEVICE) {
                    hidDevice = proxy as BluetoothHidDevice
                    hidDevice!!.registerApp(sdpSettings, null, null, executor, hidCallback)
                    onRegistered(true)
                }
            }
            override fun onServiceDisconnected(profile: Int) {
                hidDevice = null
                _state.value = ConnectionState.DISCONNECTED
            }
        }, BluetoothProfile.HID_DEVICE)
    }

    fun unregister() {
        hidDevice?.unregisterApp()
        hidDevice = null
        connectedDevice = null
        _state.value = ConnectionState.DISCONNECTED
    }

    fun sendKeyEvent(event: KeyEvent): Boolean {
        val device = connectedDevice ?: return false
        val hid = hidDevice ?: return false
        val report = HidDescriptors.buildKeyboardReport(event.modifier, *event.keys.toByteArray())
        return hid.sendReport(device, 0, report)
    }

    fun sendKeyRelease(): Boolean {
        val device = connectedDevice ?: return false
        val hid = hidDevice ?: return false
        return hid.sendReport(device, 0, ByteArray(8))
    }

    fun sendMouseEvent(event: MouseEvent): Boolean {
        val device = connectedDevice ?: return false
        val hid = hidDevice ?: return false
        val report = HidDescriptors.buildMouseReport(event.buttons, event.dx, event.dy, event.wheel)
        return hid.sendReport(device, 1, report)
    }

    private fun List<Byte>.toByteArray(): ByteArray = ByteArray(size) { this[it] }
}
