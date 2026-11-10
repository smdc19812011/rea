package com.kvmcontrol.app.ui.viewmodel

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kvmcontrol.app.data.ConnectionConfig
import com.kvmcontrol.app.data.ConnectionState
import com.kvmcontrol.app.data.KeyEvent
import com.kvmcontrol.app.data.KvmDevice
import com.kvmcontrol.app.data.KvmStats
import com.kvmcontrol.app.data.MouseEvent
import com.kvmcontrol.app.service.CoverScreenService
import com.kvmcontrol.app.service.KvmConnectionManager
import com.kvmcontrol.app.service.KvmConnectionService
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class UiState(
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    val stats: KvmStats = KvmStats(),
    val savedDevices: List<KvmDevice> = emptyList(),
    val activeDevice: KvmDevice? = null,
    val error: String? = null,
    val isCoverScreenEnabled: Boolean = false,
    val isBluetoothAvailable: Boolean = false,
    val isUsbHidAvailable: Boolean = false,
    val isUsbBridgeAvailable: Boolean = false,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private var manager: KvmConnectionManager? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val localBinder = binder as KvmConnectionService.LocalBinder
            manager = localBinder.getManager()
            observeManager()
        }
        override fun onServiceDisconnected(name: ComponentName) {
            manager = null
        }
    }

    init {
        val intent = Intent(app, KvmConnectionService::class.java)
        app.startForegroundService(intent)
        app.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun observeManager() {
        val m = manager ?: return
        viewModelScope.launch {
            m.connectionState.collect { state ->
                _uiState.update { it.copy(connectionState = state) }
            }
        }
        viewModelScope.launch {
            m.stats.collect { stats ->
                _uiState.update { it.copy(stats = stats) }
            }
        }
        _uiState.update { it.copy(
            isBluetoothAvailable = m.bluetoothHid.isAvailable(),
            isUsbHidAvailable = m.usbHid.isAvailable(),
            isUsbBridgeAvailable = m.usbBridge.isAvailable(),
        )}
    }

    fun connect(device: KvmDevice, password: String = "") {
        clearError()
        _uiState.update { it.copy(activeDevice = device) }
        manager?.connect(ConnectionConfig(device = device, password = password))
    }

    fun disconnect() {
        manager?.disconnect()
        _uiState.update { it.copy(activeDevice = null) }
    }

    fun sendKey(event: KeyEvent) = manager?.sendKey(event)

    fun sendMouse(event: MouseEvent) = manager?.sendMouse(event)

    fun addDevice(device: KvmDevice) {
        _uiState.update { it.copy(savedDevices = it.savedDevices + device) }
    }

    fun removeDevice(device: KvmDevice) {
        _uiState.update { it.copy(savedDevices = it.savedDevices.filter { d -> d.id != device.id }) }
    }

    fun toggleCoverScreen(enabled: Boolean) {
        _uiState.update { it.copy(isCoverScreenEnabled = enabled) }
        val app = getApplication<Application>()
        val intent = Intent(app, CoverScreenService::class.java)
        if (enabled) app.startForegroundService(intent) else app.stopService(intent)
    }

    fun clearError() = _uiState.update { it.copy(error = null) }

    override fun onCleared() {
        getApplication<Application>().unbindService(serviceConnection)
        super.onCleared()
    }
}
