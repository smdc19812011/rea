package com.simbridge.data

import com.google.firebase.database.IgnoreExtraProperties

@IgnoreExtraProperties
data class CallCommand(
    val type: String = "",   // INCOMING_CALL | MAKE_CALL | ANSWER_CALL | REJECT_CALL | END_CALL | CALL_ACTIVE | CALL_ENDED
    val number: String = "",
    val sessionId: String = "",
    val timestamp: Long = 0L
)

@IgnoreExtraProperties
data class SignalingMessage(
    val type: String = "",          // OFFER | ANSWER | ICE_CANDIDATE
    val sdp: String = "",
    val candidate: String = "",
    val sdpMid: String = "",
    val sdpMLineIndex: Int = 0
)

enum class AppMode { NONE, HOST, CLIENT }

enum class CallState { IDLE, RINGING_INCOMING, RINGING_OUTGOING, ACTIVE, ENDED }
