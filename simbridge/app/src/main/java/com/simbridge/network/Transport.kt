package com.simbridge.network

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

enum class TransportKind { TCP, BLUETOOTH }

interface Transport {
    val kind: TransportKind
    val messages: SharedFlow<WireMessage>
    val isConnected: StateFlow<Boolean>
    fun send(msg: WireMessage)
    fun disconnect()
}
