package com.simbridge.host

import android.os.Build
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import android.telecom.VideoProfile
import android.util.Log
import com.simbridge.data.CallCommand
import com.simbridge.data.SignalingMessage
import com.simbridge.network.AppTransport
import com.simbridge.network.BluetoothAudioStreamer
import com.simbridge.network.LocalSignaling
import com.simbridge.webrtc.WebRTCManager
import kotlinx.coroutines.*
import org.webrtc.IceCandidate
import org.webrtc.PeerConnection
import org.webrtc.SessionDescription

private const val TAG = "SimInCallService"

/**
 * Manages active phone calls on the HOST device.
 *
 * Requires this app to be the default Phone app.
 *
 * Audio bridge:
 *  - WiFi / WiFi Direct: cellular call → speaker.  WebRTC captures mic, streams to CLIENT.
 *    Hardware AEC removes loopback.
 *  - Bluetooth: same speaker trick, but PCM is streamed directly over the BT RFCOMM socket
 *    via [BluetoothAudioStreamer] because WebRTC needs IP routing (not available on RFCOMM).
 */
class SimInCallService : InCallService() {

    companion object {
        var instance: SimInCallService? = null
        var currentCall: Call? = null
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val signaling = LocalSignaling()
    private var webRTC: WebRTCManager? = null
    private var btAudio: BluetoothAudioStreamer? = null
    private var signalingJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onDestroy() {
        instance = null
        teardownAudio()
        scope.cancel()
        super.onDestroy()
    }

    override fun onCallAdded(call: Call) {
        Log.d(TAG, "onCallAdded state=${call.state}")
        currentCall = call
        call.registerCallback(callCallback)
        when (call.state) {
            Call.STATE_RINGING                -> notifyClientIncoming(call)
            Call.STATE_DIALING, Call.STATE_ACTIVE -> onCallActive()
        }
    }

    override fun onCallRemoved(call: Call) {
        Log.d(TAG, "onCallRemoved")
        call.unregisterCallback(callCallback)
        if (currentCall == call) {
            currentCall = null
            notifyClientEnded()
            teardownAudio()
        }
    }

    private val callCallback = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) {
            Log.d(TAG, "call state→$state")
            when (state) {
                Call.STATE_RINGING -> notifyClientIncoming(call)
                Call.STATE_ACTIVE  -> onCallActive()
                Call.STATE_DISCONNECTED, Call.STATE_DISCONNECTING -> {
                    notifyClientEnded()
                    teardownAudio()
                }
            }
        }
    }

    // ---- call events ----

    private fun notifyClientIncoming(call: Call) {
        val number = call.details?.handle?.schemeSpecificPart ?: "Unknown"
        Log.d(TAG, "Incoming from $number — notifying client")
        signaling.sendCallCommand(CallCommand(
            type = "INCOMING_CALL", number = number,
            timestamp = System.currentTimeMillis()
        ))
    }

    private fun notifyClientEnded() {
        signaling.sendCallCommand(CallCommand(
            type = "CALL_ENDED", timestamp = System.currentTimeMillis()
        ))
    }

    private fun onCallActive() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            setAudioRoute(CallAudioState.ROUTE_SPEAKER)
        }
        startAudioBridge()
    }

    // ---- public call controls ----

    fun answerCurrentCall() { currentCall?.answer(VideoProfile.STATE_AUDIO_ONLY) }
    fun rejectCurrentCall() { currentCall?.reject(false, null) }
    fun endCurrentCall()    { currentCall?.disconnect() }

    // ---- audio bridge ----

    private fun startAudioBridge() {
        if (AppTransport.isBluetooth()) {
            startBluetoothAudio()
        } else {
            startWebRTCBridge()
        }
    }

    private fun startBluetoothAudio() {
        if (btAudio != null) return
        btAudio = BluetoothAudioStreamer().also { it.start() }
        // Notify CLIENT that call is active so it starts its own audio streamer
        signaling.sendCallCommand(CallCommand(
            type = "CALL_ACTIVE", timestamp = System.currentTimeMillis()
        ))
    }

    private fun startWebRTCBridge() {
        if (webRTC != null) return

        webRTC = WebRTCManager(
            context = this,
            onLocalSdp = { sdp ->
                val msgType = if (sdp.type == SessionDescription.Type.OFFER) "OFFER" else "ANSWER"
                signaling.sendSignaling(toHost = false, SignalingMessage(type = msgType, sdp = sdp.description))
            },
            onIceCandidate = { candidate ->
                signaling.sendSignaling(toHost = false, SignalingMessage(
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
        webRTC?.createOffer()

        signalingJob = signaling.observeSignaling(scope, isHost = true) { msg ->
            when (msg.type) {
                "ANSWER" -> webRTC?.setRemoteDescription(
                    SessionDescription(SessionDescription.Type.ANSWER, msg.sdp)
                )
                "ICE_CANDIDATE" -> webRTC?.addIceCandidate(
                    IceCandidate(msg.sdpMid, msg.sdpMLineIndex, msg.candidate)
                )
            }
        }

        signaling.sendCallCommand(CallCommand(
            type = "CALL_ACTIVE", timestamp = System.currentTimeMillis()
        ))
    }

    private fun teardownAudio() {
        signalingJob?.cancel(); signalingJob = null
        webRTC?.dispose(); webRTC = null
        btAudio?.dispose(); btAudio = null
    }
}
