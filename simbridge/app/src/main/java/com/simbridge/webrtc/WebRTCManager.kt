package com.simbridge.webrtc

import android.content.Context
import android.util.Log
import org.webrtc.*

private const val TAG = "WebRTCManager"

class WebRTCManager(
    private val context: Context,
    private val onLocalSdp: (SessionDescription) -> Unit,
    private val onIceCandidate: (IceCandidate) -> Unit,
    private val onConnectionStateChange: (PeerConnection.IceConnectionState) -> Unit
) {
    private lateinit var factory: PeerConnectionFactory
    private var peerConnection: PeerConnection? = null
    private lateinit var localAudioTrack: AudioTrack
    private lateinit var audioSource: AudioSource
    private var disposed = false

    companion object {
        private val ICE_SERVERS = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer()
        )
    }

    fun init() {
        val initOpts = PeerConnectionFactory.InitializationOptions.builder(context)
            .setEnableInternalTracer(false)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(initOpts)

        val adm = JavaAudioDeviceModule.builder(context)
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
            .createAudioDeviceModule()

        factory = PeerConnectionFactory.builder()
            .setAudioDeviceModule(adm)
            .createPeerConnectionFactory()

        val audioConstraints = MediaConstraints().apply {
            mandatory += MediaConstraints.KeyValuePair("googEchoCancellation", "true")
            mandatory += MediaConstraints.KeyValuePair("googNoiseSuppression", "true")
            mandatory += MediaConstraints.KeyValuePair("googAutoGainControl", "true")
            mandatory += MediaConstraints.KeyValuePair("googHighpassFilter", "true")
        }

        audioSource = factory.createAudioSource(audioConstraints)
        localAudioTrack = factory.createAudioTrack("AUDIO_TRACK_ID", audioSource)
        localAudioTrack.setEnabled(true)
    }

    fun createPeerConnection() {
        val config = PeerConnection.RTCConfiguration(ICE_SERVERS).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            iceTransportsType = PeerConnection.IceTransportsType.ALL
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
        }

        peerConnection = factory.createPeerConnection(config, object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState) {}
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                Log.d(TAG, "ICE state: $state")
                onConnectionStateChange(state)
            }
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {}
            override fun onIceCandidate(candidate: IceCandidate) {
                this@WebRTCManager.onIceCandidate(candidate)
            }
            override fun onIceCandidatesRemoved(candidates: Array<IceCandidate>) {}
            override fun onAddStream(stream: MediaStream) {}
            override fun onRemoveStream(stream: MediaStream) {}
            override fun onDataChannel(dc: DataChannel) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: RtpReceiver, streams: Array<MediaStream>) {}
        })

        peerConnection?.addTrack(localAudioTrack)
    }

    fun createOffer() {
        val constraints = MediaConstraints().apply {
            mandatory += MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true")
            mandatory += MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false")
        }
        peerConnection?.createOffer(sdpObserver { sdp ->
            peerConnection?.setLocalDescription(noopSdpObserver(), sdp)
            onLocalSdp(sdp)
        }, constraints)
    }

    fun createAnswer() {
        val constraints = MediaConstraints().apply {
            mandatory += MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true")
        }
        peerConnection?.createAnswer(sdpObserver { sdp ->
            peerConnection?.setLocalDescription(noopSdpObserver(), sdp)
            onLocalSdp(sdp)
        }, constraints)
    }

    fun setRemoteDescription(sdp: SessionDescription) {
        peerConnection?.setRemoteDescription(noopSdpObserver(), sdp)
    }

    fun addIceCandidate(candidate: IceCandidate) {
        peerConnection?.addIceCandidate(candidate)
    }

    fun setMuted(muted: Boolean) {
        localAudioTrack.setEnabled(!muted)
    }

    fun dispose() {
        if (disposed) return
        disposed = true
        peerConnection?.close()
        peerConnection?.dispose()
        localAudioTrack.dispose()
        audioSource.dispose()
        factory.dispose()
    }

    // ---- helpers ----

    private fun sdpObserver(onSuccess: (SessionDescription) -> Unit) = object : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription) { onSuccess(sdp) }
        override fun onSetSuccess() {}
        override fun onCreateFailure(err: String?) { Log.e(TAG, "SDP create fail: $err") }
        override fun onSetFailure(err: String?) { Log.e(TAG, "SDP set fail: $err") }
    }

    private fun noopSdpObserver() = object : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(err: String?) { Log.e(TAG, "SDP create fail: $err") }
        override fun onSetFailure(err: String?) { Log.e(TAG, "SDP set fail: $err") }
    }
}
