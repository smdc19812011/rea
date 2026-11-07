package com.simbridge.network

import com.simbridge.data.CallCommand
import com.simbridge.data.SignalingMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Call command and WebRTC signaling layer.
 *
 * All messages are routed through [AppTransport], which holds whichever
 * [Transport] is active for this session (TCP or Bluetooth).
 * Observers return a [Job]; cancel it to stop listening.
 */
class LocalSignaling {

    // ---- call commands ----

    fun sendCallCommand(command: CallCommand) {
        AppTransport.send(WireMessage(type = "CALL_CMD", callCmd = command))
    }

    fun observeCallCommand(scope: CoroutineScope, onCommand: (CallCommand) -> Unit): Job =
        scope.launch {
            AppTransport.messages.collect { msg ->
                if (msg.type == "CALL_CMD") msg.callCmd?.let { onCommand(it) }
            }
        }

    // ---- WebRTC signaling (not used when transport is Bluetooth) ----

    /**
     * @param toHost true = CLIENT sending OFFER/ICE to HOST; false = HOST sending ANSWER/ICE to CLIENT
     */
    fun sendSignaling(toHost: Boolean, msg: SignalingMessage) {
        val type = if (toHost) "SIG_TO_HOST" else "SIG_TO_CLIENT"
        AppTransport.send(WireMessage(type = type, signal = msg))
    }

    /**
     * @param isHost true = HOST reading its own queue ("SIG_TO_HOST")
     */
    fun observeSignaling(scope: CoroutineScope, isHost: Boolean, onMessage: (SignalingMessage) -> Unit): Job {
        val type = if (isHost) "SIG_TO_HOST" else "SIG_TO_CLIENT"
        return scope.launch {
            AppTransport.messages.collect { msg ->
                if (msg.type == type) msg.signal?.let { onMessage(it) }
            }
        }
    }
}
