package com.simbridge.network

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonParseException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.BufferedWriter
import java.io.IOException
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket

private const val TAG = "ConnectionManager"
const val DEFAULT_PORT = 47892
private const val CONNECT_TIMEOUT_MS = 8_000

/**
 * TCP [Transport] singleton.
 *
 * HOST calls [startAsServer]; CLIENT calls [connectToServer].
 * Both sides then read from [messages] and write via [send].
 *
 * Call [AppTransport.use] with this object before or immediately after
 * the connection is established so [LocalSignaling] routes through it.
 *
 * Protocol: newline-delimited JSON.  Gson escapes embedded newlines inside
 * string values, so SDP blobs are safe.
 */
object ConnectionManager : Transport {

    override val kind = TransportKind.TCP

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gson = Gson()

    private var serverSocket: ServerSocket? = null
    private var peerSocket: Socket? = null
    private var writer: BufferedWriter? = null
    private var readJob: Job? = null

    private val _messages = MutableSharedFlow<WireMessage>(extraBufferCapacity = 128)
    override val messages: SharedFlow<WireMessage> = _messages

    private val _isConnected = MutableStateFlow(false)
    override val isConnected: StateFlow<Boolean> = _isConnected

    // ---- HOST side ----

    fun startAsServer(
        onReady: (port: Int) -> Unit,
        onClientConnected: () -> Unit,
        onError: (String) -> Unit
    ) {
        scope.launch {
            try {
                val ss = ServerSocket(DEFAULT_PORT)
                serverSocket = ss
                onReady(DEFAULT_PORT)
                Log.d(TAG, "Server listening on $DEFAULT_PORT")
                val client = ss.accept()
                Log.d(TAG, "Client connected from ${client.inetAddress.hostAddress}")
                attach(client)
                withContext(Dispatchers.Main) { onClientConnected() }
            } catch (e: IOException) {
                Log.e(TAG, "Server error: ${e.message}")
                withContext(Dispatchers.Main) { onError(e.message ?: "Server error") }
            }
        }
    }

    // ---- CLIENT side ----

    fun connectToServer(
        host: String,
        port: Int = DEFAULT_PORT,
        onConnected: () -> Unit,
        onError: (String) -> Unit
    ) {
        scope.launch {
            try {
                val s = Socket()
                s.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
                Log.d(TAG, "Connected to $host:$port")
                attach(s)
                withContext(Dispatchers.Main) { onConnected() }
            } catch (e: IOException) {
                Log.e(TAG, "Connect error: ${e.message}")
                withContext(Dispatchers.Main) { onError(e.message ?: "Connection failed") }
            }
        }
    }

    // ---- shared ----

    private fun attach(socket: Socket) {
        peerSocket = socket
        writer = socket.getOutputStream().bufferedWriter()
        _isConnected.value = true
        readJob = scope.launch { readLoop(socket) }
        scope.launch {
            while (isActive && _isConnected.value) {
                delay(15_000)
                send(WireMessage(type = "PING"))
            }
        }
    }

    private suspend fun readLoop(socket: Socket) {
        val reader = socket.getInputStream().bufferedReader()
        try {
            while (true) {
                val line = reader.readLine() ?: break
                try {
                    val msg = gson.fromJson(line, WireMessage::class.java)
                    if (msg.type != "PING" && msg.type != "PONG") {
                        _messages.emit(msg)
                    } else if (msg.type == "PING") {
                        send(WireMessage(type = "PONG"))
                    }
                } catch (e: JsonParseException) {
                    Log.w(TAG, "Unparseable message: $line")
                }
            }
        } catch (e: IOException) {
            Log.d(TAG, "Read loop ended: ${e.message}")
        } finally {
            _isConnected.value = false
        }
    }

    override fun send(msg: WireMessage) {
        scope.launch {
            val w = writer ?: return@launch
            try {
                synchronized(w) {
                    w.write(gson.toJson(msg))
                    w.newLine()
                    w.flush()
                }
            } catch (e: IOException) {
                Log.e(TAG, "Send failed: ${e.message}")
                _isConnected.value = false
            }
        }
    }

    override fun disconnect() {
        readJob?.cancel()
        try { peerSocket?.close() } catch (_: IOException) {}
        try { serverSocket?.close() } catch (_: IOException) {}
        peerSocket = null
        writer = null
        _isConnected.value = false
    }

    // ---- utility ----

    fun localIpAddress(): String = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { !it.isLoopbackAddress && it is Inet4Address }
            ?.hostAddress ?: "unavailable"
    } catch (_: Exception) { "unavailable" }
}
