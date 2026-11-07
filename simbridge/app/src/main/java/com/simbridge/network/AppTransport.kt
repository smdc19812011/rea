package com.simbridge.network

import com.simbridge.data.CallCommand
import com.simbridge.data.SignalingMessage
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Holds the active [Transport] for this session.
 *
 * Before starting a session call [use] with the chosen transport.
 * [LocalSignaling] reads from here so it works over both TCP and Bluetooth
 * without knowing which one is in use.
 */
object AppTransport {

    @Volatile private var _current: Transport? = null

    val current: Transport? get() = _current

    fun use(t: Transport) {
        // Disconnect the old one (no-op if same object)
        if (_current !== t) _current?.disconnect()
        _current = t
    }

    fun clear() {
        _current?.disconnect()
        _current = null
    }

    // ---- delegate to current transport ----

    val messages: SharedFlow<WireMessage>
        get() = _current?.messages ?: _emptyMessages

    val isConnected: StateFlow<Boolean>
        get() = _current?.isConnected ?: _notConnected

    fun send(msg: WireMessage) { _current?.send(msg) }

    fun disconnect() { clear() }

    fun isBluetooth(): Boolean = _current?.kind == TransportKind.BLUETOOTH

    // Stubs returned when no transport is active
    private val _emptyMessages = MutableSharedFlow<WireMessage>()
    private val _notConnected = MutableStateFlow(false)
}
