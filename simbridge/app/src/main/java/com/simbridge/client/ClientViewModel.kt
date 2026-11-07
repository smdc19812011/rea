package com.simbridge.client

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.simbridge.data.CallCommand
import com.simbridge.data.CallState
import com.simbridge.data.SignalingMessage
import com.simbridge.network.AppTransport
import com.simbridge.network.BluetoothAudioStreamer
import com.simbridge.network.LocalSignaling
import com.simbridge.webrtc.WebRTCManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.webrtc.IceCandidate
import org.webrtc.PeerConnection
import org.webrtc.SessionDescription

private const val TAG = "ClientViewModel"

class ClientViewModel : ViewModel() {

    private val _callState = MutableStateFlow(CallState.IDLE)
    val callState: StateFlow<CallState> = _callState

    private val _remoteNumber = MutableStateFlow("")
    val remoteNumber: StateFlow<String> = _remoteNumber

    private val signaling = LocalSignaling()
    private var commandJob: Job? = null
    private var signalingJob: Job? = null
    private var webRTC: WebRTCManager? = null
    private var btAudio: BluetoothAudioStreamer? = null
    private var isMuted = false
    val muted get() = isMuted

    fun startListening(context: Context) {
        commandJob = signaling.observeCallCommand(viewModelScope) { cmd ->
            handleHostCommand(context, cmd)
        }
    }

    // ---- outgoing ----

    fun makeCall(number: String) {
        if (_callState.value != CallState.IDLE) return
        _remoteNumber.value = number
        _callState.value = CallState.RINGING_OUTGOING
        signaling.sendCallCommand(CallCommand(
            type = "MAKE_CALL", number = number,
            timestamp = System.currentTimeMillis()
        ))
    }

    // ---- incoming ----

    fun answerIncoming() {
        if (_callState.value != CallState.RINGING_INCOMING) return
        signaling.sendCallCommand(CallCommand(
            type = "ANSWER_CALL", timestamp = System.currentTimeMillis()
        ))
    }

    fun rejectIncoming() {
        signaling.sendCallCommand(CallCommand(
            type = "REJECT_CALL", timestamp = System.currentTimeMillis()
        ))
        _callState.value = CallState.IDLE
    }

    fun hangUp() {
        signaling.sendCallCommand(CallCommand(
            type = "END_CALL", timestamp = System.currentTimeMillis()
        ))
        _callState.value = CallState.ENDED
        teardownAudio()
        _callState.value = CallState.IDLE
    }

    fun toggleMute() {
        isMuted = !isMuted
        webRTC?.setMuted(isMuted)
        btAudio?.setMuted(isMuted)
    }

    // ---- host command handler ----

    private fun handleHostCommand(context: Context, cmd: CallCommand) {
        Log.d(TAG, "Host command: ${cmd.type}")
        when (cmd.type) {
            "INCOMING_CALL" -> {
                _remoteNumber.value = cmd.number
                _callState.value = CallState.RINGING_INCOMING
            }
            "CALL_ACTIVE" -> {
                _callState.value = CallState.ACTIVE
                if (AppTransport.isBluetooth()) {
                    startBluetoothAudio()
                } else {
                    connectWebRTC(context)
                }
            }
            "CALL_ENDED" -> {
                teardownAudio()
                _callState.value = CallState.IDLE
            }
        }
    }

    // ---- audio ----

    private fun startBluetoothAudio() {
        if (btAudio != null) return
        btAudio = BluetoothAudioStreamer().also { it.start() }
    }

    private fun connectWebRTC(context: Context) {
        if (webRTC != null) return

        webRTC = WebRTCManager(
            context = context,
            onLocalSdp = { sdp ->
                val msgType = if (sdp.type == SessionDescription.Type.OFFER) "OFFER" else "ANSWER"
                signaling.sendSignaling(toHost = true, SignalingMessage(type = msgType, sdp = sdp.description))
            },
            onIceCandidate = { candidate ->
                signaling.sendSignaling(toHost = true, SignalingMessage(
                    type = "ICE_CANDIDATE",
                    candidate = candidate.sdp,
                    sdpMid = candidate.sdpMid ?: "",
                    sdpMLineIndex = candidate.sdpMLineIndex
                ))
            },
            onConnectionStateChange = { state ->
                if (state == PeerConnection.IceConnectionState.FAILED) teardownAudio()
            }
        ).also { it.init() }

        webRTC?.createPeerConnection()

        signalingJob = signaling.observeSignaling(viewModelScope, isHost = false) { msg ->
            when (msg.type) {
                "OFFER" -> {
                    webRTC?.setRemoteDescription(
                        SessionDescription(SessionDescription.Type.OFFER, msg.sdp)
                    )
                    webRTC?.createAnswer()
                }
                "ICE_CANDIDATE" -> webRTC?.addIceCandidate(
                    IceCandidate(msg.sdpMid, msg.sdpMLineIndex, msg.candidate)
                )
            }
        }
    }

    private fun teardownAudio() {
        signalingJob?.cancel(); signalingJob = null
        webRTC?.dispose(); webRTC = null
        btAudio?.dispose(); btAudio = null
    }

    override fun onCleared() {
        commandJob?.cancel()
        teardownAudio()
        super.onCleared()
    }
}
