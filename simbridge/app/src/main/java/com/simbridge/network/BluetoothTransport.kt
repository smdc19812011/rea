package com.simbridge.network

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.io.InputStream
import java.util.UUID

private const val TAG = "BluetoothTransport"

/**
 * RFCOMM-based [Transport].
 *
 * Uses a **single** socket with a 5-byte frame header to multiplex
 * JSON control messages and raw PCM audio:
 *
 *   [1 byte: type][4 bytes: length BE][N bytes: payload]
 *
 *   TYPE_JSON (0x01) → UTF-8 JSON [WireMessage] (call commands, WebRTC signaling)
 *   TYPE_PCM  (0x02) → raw 16-bit PCM samples (audio bridge when WebRTC is unavailable)
 *
 * When Bluetooth is in use the app sends audio via [sendAudio] and receives it
 * on [audioIn]; WebRTC is not used.
 */
object BluetoothTransport : Transport {

    private val SERVICE_UUID = UUID.fromString("4532b9a0-1234-5678-9abc-def012345678")

    private const val TYPE_JSON: Byte = 0x01
    private const val TYPE_PCM: Byte  = 0x02

    override val kind = TransportKind.BLUETOOTH

    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gson = Gson()

    private val _messages = MutableSharedFlow<WireMessage>(extraBufferCapacity = 128)
    override val messages: SharedFlow<WireMessage> = _messages

    private val _audioIn = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    val audioIn: SharedFlow<ByteArray> = _audioIn

    private val _isConnected = MutableStateFlow(false)
    override val isConnected: StateFlow<Boolean> = _isConnected

    private var socket: BluetoothSocket? = null
    private val writeMutex = Mutex()

    // ---- HOST: listen for one incoming CLIENT connection ----

    @Suppress("MissingPermission")
    fun startServer(
        adapter: BluetoothAdapter,
        onConnected: () -> Unit,
        onError: (String) -> Unit
    ) {
        scope.launch {
            try {
                val serverSocket = adapter.listenUsingRfcommWithServiceRecord("SimBridge", SERVICE_UUID)
                Log.d(TAG, "BT server listening…")
                val client = serverSocket.accept()     // blocks
                serverSocket.close()
                attach(client)
                withContext(Dispatchers.Main) { onConnected() }
            } catch (e: IOException) {
                Log.e(TAG, "BT server error: ${e.message}")
                withContext(Dispatchers.Main) { onError(e.message ?: "BT server error") }
            }
        }
    }

    // ---- CLIENT: connect to a paired device ----

    @Suppress("MissingPermission")
    fun connect(
        device: BluetoothDevice,
        onConnected: () -> Unit,
        onError: (String) -> Unit
    ) {
        scope.launch {
            try {
                val s = device.createRfcommSocketToServiceRecord(SERVICE_UUID)
                s.connect()
                attach(s)
                withContext(Dispatchers.Main) { onConnected() }
            } catch (e: IOException) {
                Log.e(TAG, "BT connect error: ${e.message}")
                withContext(Dispatchers.Main) { onError(e.message ?: "BT connection failed") }
            }
        }
    }

    // ---- shared ----

    private fun attach(s: BluetoothSocket) {
        socket = s
        _isConnected.value = true
        scope.launch { readLoop(s) }
        Log.d(TAG, "BT socket attached")
    }

    override fun send(msg: WireMessage) {
        val payload = gson.toJson(msg).toByteArray(Charsets.UTF_8)
        scope.launch { writeFrame(TYPE_JSON, payload) }
    }

    fun sendAudio(pcm: ByteArray) {
        scope.launch { writeFrame(TYPE_PCM, pcm) }
    }

    private suspend fun writeFrame(type: Byte, payload: ByteArray) {
        writeMutex.withLock {
            val os = socket?.outputStream ?: return
            try {
                val len = payload.size
                val header = ByteArray(5)
                header[0] = type
                header[1] = (len shr 24).toByte()
                header[2] = (len shr 16).toByte()
                header[3] = (len shr 8).toByte()
                header[4] = len.toByte()
                os.write(header)
                os.write(payload)
                os.flush()
            } catch (e: IOException) {
                Log.e(TAG, "BT write error: ${e.message}")
                _isConnected.value = false
            }
        }
    }

    private suspend fun readLoop(s: BluetoothSocket) {
        val input = s.inputStream
        val headerBuf = ByteArray(5)
        try {
            while (true) {
                readExact(input, headerBuf, 5)
                val type = headerBuf[0]
                val len = ((headerBuf[1].toInt() and 0xFF) shl 24) or
                          ((headerBuf[2].toInt() and 0xFF) shl 16) or
                          ((headerBuf[3].toInt() and 0xFF) shl 8) or
                           (headerBuf[4].toInt() and 0xFF)
                if (len < 0 || len > 2_000_000) {
                    Log.w(TAG, "Implausible frame length $len — dropping connection")
                    break
                }
                val payload = ByteArray(len)
                readExact(input, payload, len)

                when (type) {
                    TYPE_JSON -> {
                        try {
                            val msg = gson.fromJson(String(payload, Charsets.UTF_8), WireMessage::class.java)
                            if (msg.type == "PING") send(WireMessage("PONG"))
                            else if (msg.type != "PONG") _messages.emit(msg)
                        } catch (_: Exception) { }
                    }
                    TYPE_PCM -> _audioIn.emit(payload)
                }
            }
        } catch (_: IOException) {
        } finally {
            Log.d(TAG, "BT read loop ended")
            _isConnected.value = false
        }
    }

    private fun readExact(input: InputStream, buf: ByteArray, len: Int) {
        var read = 0
        while (read < len) {
            val r = input.read(buf, read, len - read)
            if (r < 0) throw IOException("EOF reading frame")
            read += r
        }
    }

    override fun disconnect() {
        try { socket?.close() } catch (_: IOException) {}
        socket = null
        _isConnected.value = false
        scope.cancel()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
