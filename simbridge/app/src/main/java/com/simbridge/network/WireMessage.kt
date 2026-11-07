package com.simbridge.network

import com.simbridge.data.CallCommand
import com.simbridge.data.SignalingMessage

/**
 * Every message exchanged over the socket is serialized as a single line of JSON
 * (Gson escapes embedded newlines in strings, so SDP content is safe).
 */
data class WireMessage(
    val type: String,            // CALL_CMD | SIG_TO_HOST | SIG_TO_CLIENT | PING | PONG
    val callCmd: CallCommand? = null,
    val signal: SignalingMessage? = null
)
