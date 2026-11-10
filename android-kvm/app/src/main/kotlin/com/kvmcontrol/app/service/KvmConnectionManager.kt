package com.kvmcontrol.app.service

import android.content.Context
import com.kvmcontrol.app.data.ConnectionConfig
import com.kvmcontrol.app.data.ConnectionState
import com.kvmcontrol.app.data.ConnectionType
import com.kvmcontrol.app.data.KeyEvent
import com.kvmcontrol.app.data.KvmStats
import com.kvmcontrol.app.data.MouseEvent
import com.kvmcontrol.app.network.VncClient
import com.kvmcontrol.app.util.StatsCollector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class KvmConnectionManager(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val bluetoothHid = BluetoothHidService(context)
    val usbHid = UsbHidService(context)
    val usbBridge = UsbHidBridgeService(context)
    val shizukuHid = ShizukuHidService().also { it.init() }
    val usbPermissionHelper = UsbPermissionHelper(context)
    val vncClient = VncClient()
    val statsCollector = StatsCollector(context)

    private val _stats = MutableStateFlow(KvmStats())
    val stats: StateFlow<KvmStats> = _stats

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState

    private val _deviceName = MutableStateFlow("")
    val deviceName: StateFlow<String> = _deviceName

    private var activeConfig: ConnectionConfig? = null

    init {
        // Merge connection states from all transports
        scope.launch {
            combine(
                bluetoothHid.state,
                usbHid.state,
                usbBridge.state,
                shizukuHid.state,
                vncClient.state,
            ) { bt, usb, usbBr, shiz, vnc ->
                listOf(bt, usb, usbBr, shiz, vnc)
            }.collect { states ->
                val combined = when {
                    states.any { it == ConnectionState.CONNECTED } -> ConnectionState.CONNECTED
                    states.any { it == ConnectionState.CONNECTING } -> ConnectionState.CONNECTING
                    states.any { it == ConnectionState.ERROR } -> ConnectionState.ERROR
                    else -> ConnectionState.DISCONNECTED
                }
                _connectionState.value = combined
            }
        }

        // Collect local stats + merge remote latency
        statsCollector.statsFlow().onEach { local ->
            val latency = vncClient.latencyMs.value
            val remoteInfo = vncClient.serverInfo.value
            _stats.update { local.copy(
                latencyMs = latency,
                connectionState = _connectionState.value,
                deviceName = remoteInfo?.name ?: activeConfig?.device?.name ?: "",
            )}
        }.launchIn(scope)

        bluetoothHid.connectedDeviceName.onEach { name ->
            if (name.isNotEmpty()) _deviceName.value = name
        }.launchIn(scope)
    }

    fun connect(config: ConnectionConfig) {
        activeConfig = config
        when (config.device.type) {
            ConnectionType.BLUETOOTH -> bluetoothHid.register { success ->
                if (!success) _connectionState.value = ConnectionState.ERROR
            }
            ConnectionType.USB -> {
                val opened = usbHid.open()
                if (!opened) _connectionState.value = ConnectionState.ERROR
            }
            ConnectionType.USB_BRIDGE -> {
                val dev = usbBridge.findBridgeDevice()
                if (dev == null) {
                    _connectionState.value = ConnectionState.ERROR
                    return
                }
                usbPermissionHelper.requestPermission(dev) { granted ->
                    if (granted) {
                        val opened = usbBridge.open(dev)
                        if (!opened) _connectionState.value = ConnectionState.ERROR
                    } else {
                        _connectionState.value = ConnectionState.ERROR
                    }
                }
            }
            ConnectionType.SHIZUKU_USB -> shizukuHid.connect(
                onError = { _connectionState.value = ConnectionState.ERROR },
            )
            ConnectionType.NETWORK -> vncClient.connect(
                host = config.device.address,
                port = config.device.port,
                password = config.password,
                onError = { _connectionState.value = ConnectionState.ERROR },
            )
        }
    }

    fun disconnect() {
        activeConfig?.let { config ->
            when (config.device.type) {
                ConnectionType.BLUETOOTH -> bluetoothHid.unregister()
                ConnectionType.USB -> usbHid.close()
                ConnectionType.USB_BRIDGE -> usbBridge.close()
                ConnectionType.SHIZUKU_USB -> shizukuHid.disconnect()
                ConnectionType.NETWORK -> vncClient.disconnect()
            }
        }
        activeConfig = null
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    fun sendKey(event: KeyEvent) {
        when (activeConfig?.device?.type) {
            ConnectionType.BLUETOOTH -> {
                bluetoothHid.sendKeyEvent(event)
                scope.launch { kotlinx.coroutines.delay(50); bluetoothHid.sendKeyRelease() }
            }
            ConnectionType.USB -> {
                usbHid.sendKeyEvent(event)
                scope.launch { kotlinx.coroutines.delay(50); usbHid.sendKeyRelease() }
            }
            ConnectionType.USB_BRIDGE -> {
                usbBridge.sendKeyEvent(event)
                scope.launch { kotlinx.coroutines.delay(50); usbBridge.sendKeyRelease() }
            }
            ConnectionType.SHIZUKU_USB -> {
                shizukuHid.sendKeyEvent(event)
                scope.launch { kotlinx.coroutines.delay(50); shizukuHid.sendKeyRelease() }
            }
            ConnectionType.NETWORK -> {
                for (key in event.keys) {
                    vncClient.sendKeyDown(key.toInt() and 0xFF)
                }
            }
            null -> {}
        }
    }

    fun sendMouse(event: MouseEvent) {
        when (activeConfig?.device?.type) {
            ConnectionType.BLUETOOTH -> bluetoothHid.sendMouseEvent(event)
            ConnectionType.USB -> usbHid.sendMouseEvent(event)
            ConnectionType.USB_BRIDGE -> usbBridge.sendMouseEvent(event)
            ConnectionType.SHIZUKU_USB -> shizukuHid.sendMouseEvent(event)
            ConnectionType.NETWORK -> {} // VNC pointer sent separately with absolute coords
            null -> {}
        }
    }

    fun destroy() {
        usbPermissionHelper.cleanup()
        shizukuHid.destroy()
    }
}
