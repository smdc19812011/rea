package com.simbridge.network

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import kotlinx.coroutines.*

private const val TAG = "BluetoothAudioStreamer"

/**
 * PCM audio bridge used when the connection transport is [BluetoothTransport].
 *
 * WebRTC cannot operate without IP routing (Bluetooth Classic RFCOMM does not provide
 * one), so this class substitutes: AudioRecord captures the mic at 16 kHz mono 16-bit,
 * sends 20 ms frames over [BluetoothTransport.sendAudio], and plays received frames
 * via AudioTrack in VOICE_CALL mode.
 *
 * Hardware AEC (AcousticEchoCanceler) is attached to the recorder session when available,
 * which removes the cellular-to-BT-to-cellular loopback on the HOST phone.
 */
class BluetoothAudioStreamer {

    companion object {
        private const val SAMPLE_RATE    = 16_000
        private const val FRAME_SAMPLES  = 320          // 20 ms @ 16 kHz
        private const val FRAME_BYTES    = FRAME_SAMPLES * 2  // 16-bit PCM
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var recorder: AudioRecord? = null
    private var player:   AudioTrack?  = null
    private var aec: AcousticEchoCanceler? = null
    private var ns:  NoiseSuppressor?  = null
    private var isMuted = false

    fun start() {
        startRecording()
        startPlayback()
    }

    private fun startRecording() {
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf, FRAME_BYTES * 8)
        )
        recorder = rec

        if (AcousticEchoCanceler.isAvailable()) {
            aec = AcousticEchoCanceler.create(rec.audioSessionId)?.also { it.enabled = true }
        }
        if (NoiseSuppressor.isAvailable()) {
            ns = NoiseSuppressor.create(rec.audioSessionId)?.also { it.enabled = true }
        }

        rec.startRecording()
        Log.d(TAG, "Recording started (session ${rec.audioSessionId})")

        scope.launch {
            val buf = ByteArray(FRAME_BYTES)
            while (isActive) {
                val read = rec.read(buf, 0, FRAME_BYTES)
                if (read > 0 && !isMuted) {
                    BluetoothTransport.sendAudio(buf.copyOf(read))
                }
            }
        }
    }

    private fun startPlayback() {
        val minBuf = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minBuf, FRAME_BYTES * 8))
            .build()
        player = track
        track.play()
        Log.d(TAG, "Playback started")

        scope.launch {
            BluetoothTransport.audioIn.collect { pcm ->
                track.write(pcm, 0, pcm.size)
            }
        }
    }

    fun setMuted(muted: Boolean) {
        isMuted = muted
    }

    fun dispose() {
        scope.cancel()
        aec?.release(); aec = null
        ns?.release();  ns  = null
        recorder?.stop(); recorder?.release(); recorder = null
        player?.stop();   player?.release();   player   = null
        Log.d(TAG, "Disposed")
    }
}
