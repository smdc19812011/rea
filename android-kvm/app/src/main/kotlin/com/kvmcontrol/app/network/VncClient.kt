package com.kvmcontrol.app.network

import android.util.Log
import com.kvmcontrol.app.data.ConnectionState
import com.kvmcontrol.app.data.KvmStats
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket

private const val TAG = "VncClient"
private const val CONNECT_TIMEOUT_MS = 5000
private const val SOCKET_TIMEOUT_MS = 10000

class VncClient {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var socket: Socket? = null
    private var output: DataOutputStream? = null
    private var pingJob: Job? = null

    private val _state = MutableStateFlow(ConnectionState.DISCONNECTED)
    val state: StateFlow<ConnectionState> = _state

    private val _serverInfo = MutableStateFlow<RfbProtocol.ServerInfo?>(null)
    val serverInfo: StateFlow<RfbProtocol.ServerInfo?> = _serverInfo

    private val _latencyMs = MutableStateFlow(-1L)
    val latencyMs: StateFlow<Long> = _latencyMs

    fun connect(host: String, port: Int, password: String, onError: (String) -> Unit) {
        scope.launch {
            _state.value = ConnectionState.CONNECTING
            try {
                val s = Socket()
                s.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
                s.soTimeout = SOCKET_TIMEOUT_MS
                socket = s
                output = DataOutputStream(s.getOutputStream())

                val info = RfbProtocol.handshake(s, password)
                _serverInfo.value = info

                // Request raw encodings (simplest — no compression)
                RfbProtocol.setEncodings(output!!, intArrayOf(0, -223))
                RfbProtocol.requestFramebufferUpdate(output!!, false, 0, 0, info.width, info.height)

                _state.value = ConnectionState.CONNECTED
                startPing(host, port)
                Log.i(TAG, "Connected to ${info.name} (${info.width}x${info.height})")
            } catch (e: Exception) {
                Log.e(TAG, "Connect failed: ${e.message}")
                _state.value = ConnectionState.ERROR
                onError(e.message ?: "Unknown error")
                disconnect()
            }
        }
    }

    fun disconnect() {
        pingJob?.cancel()
        try {
            output?.close()
            socket?.close()
        } catch (_: Exception) {}
        socket = null
        output = null
        _state.value = ConnectionState.DISCONNECTED
        _serverInfo.value = null
        _latencyMs.value = -1L
    }

    fun sendKeyDown(keySym: Int) {
        scope.launch { output?.let { RfbProtocol.sendKeyEvent(it, true, keySym) } }
    }

    fun sendKeyUp(keySym: Int) {
        scope.launch { output?.let { RfbProtocol.sendKeyEvent(it, false, keySym) } }
    }

    fun sendPointer(buttonMask: Int, x: Int, y: Int) {
        scope.launch { output?.let { RfbProtocol.sendPointerEvent(it, buttonMask, x, y) } }
    }

    fun requestScreenUpdate() {
        val info = _serverInfo.value ?: return
        scope.launch {
            output?.let {
                RfbProtocol.requestFramebufferUpdate(it, true, 0, 0, info.width, info.height)
            }
        }
    }

    private fun startPing(host: String, port: Int) {
        pingJob = scope.launch {
            while (isActive && _state.value == ConnectionState.CONNECTED) {
                delay(5000L)
                _latencyMs.value = measureLatency(host, port)
            }
        }
    }

    private suspend fun measureLatency(host: String, port: Int): Long = withContext(Dispatchers.IO) {
        return@withContext try {
            val start = System.currentTimeMillis()
            Socket().use { it.connect(InetSocketAddress(host, port), 2000) }
            System.currentTimeMillis() - start
        } catch (_: Exception) {
            -1L
        }
    }

    fun isConnected(): Boolean = _state.value == ConnectionState.CONNECTED
}
