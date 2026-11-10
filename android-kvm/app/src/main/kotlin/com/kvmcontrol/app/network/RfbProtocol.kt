package com.kvmcontrol.app.network

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * Minimal RFB (VNC) protocol implementation — enough to authenticate,
 * negotiate, and send keyboard/pointer events, plus receive framebuffer
 * updates to detect remote desktop dimensions and state.
 */
object RfbProtocol {

    // RFB message type constants
    const val MSG_SET_PIXEL_FORMAT: Byte = 0
    const val MSG_SET_ENCODINGS: Byte = 2
    const val MSG_FRAMEBUFFER_UPDATE_REQUEST: Byte = 3
    const val MSG_KEY_EVENT: Byte = 4
    const val MSG_POINTER_EVENT: Byte = 5
    const val MSG_CLIENT_CUT_TEXT: Byte = 6

    // Server message types
    const val SERVER_FRAMEBUFFER_UPDATE = 0
    const val SERVER_SET_COLOUR_MAP_ENTRIES = 1
    const val SERVER_BELL = 2
    const val SERVER_SERVER_CUT_TEXT = 3

    // Security types
    const val SECURITY_NONE = 1
    const val SECURITY_VNC_AUTH = 2

    data class ServerInfo(
        val width: Int,
        val height: Int,
        val name: String,
        val protocolVersion: String,
    )

    @Throws(IOException::class)
    fun handshake(socket: Socket, password: String): ServerInfo {
        val input = DataInputStream(socket.getInputStream())
        val output = DataOutputStream(socket.getOutputStream())

        // Protocol version exchange
        val versionBytes = ByteArray(12)
        input.readFully(versionBytes)
        val serverVersion = String(versionBytes)
        output.write("RFB 003.008\n".toByteArray(Charsets.US_ASCII))
        output.flush()

        // Security type negotiation
        val numSecTypes = input.readUnsignedByte()
        if (numSecTypes == 0) {
            val reasonLen = input.readInt()
            val reason = ByteArray(reasonLen)
            input.readFully(reason)
            throw IOException("Server refused: ${String(reason)}")
        }
        val secTypes = ByteArray(numSecTypes)
        input.readFully(secTypes)

        val chosen = when {
            password.isEmpty() && SECURITY_NONE.toByte() in secTypes -> SECURITY_NONE
            SECURITY_VNC_AUTH.toByte() in secTypes -> SECURITY_VNC_AUTH
            SECURITY_NONE.toByte() in secTypes -> SECURITY_NONE
            else -> throw IOException("No supported security type")
        }
        output.writeByte(chosen)
        output.flush()

        if (chosen == SECURITY_VNC_AUTH) {
            val challenge = ByteArray(16)
            input.readFully(challenge)
            val response = Des.encrypt(password.padEnd(8, '\u0000').substring(0, 8).toByteArray(), challenge)
            output.write(response)
            output.flush()
        }

        // Security result
        val secResult = input.readInt()
        if (secResult != 0) {
            val reasonLen = input.readInt()
            val reason = ByteArray(reasonLen)
            input.readFully(reason)
            throw IOException("Authentication failed: ${String(reason)}")
        }

        // Client init (share = 1)
        output.writeByte(1)
        output.flush()

        // Server init
        val fbWidth = input.readUnsignedShort()
        val fbHeight = input.readUnsignedShort()

        // Skip pixel format (16 bytes)
        val pixelFormat = ByteArray(16)
        input.readFully(pixelFormat)

        // Desktop name
        val nameLen = input.readInt()
        val nameBytes = ByteArray(nameLen)
        input.readFully(nameBytes)
        val name = String(nameBytes, Charsets.UTF_8)

        return ServerInfo(fbWidth, fbHeight, name, serverVersion)
    }

    @Throws(IOException::class)
    fun sendKeyEvent(output: DataOutputStream, down: Boolean, keySym: Int) {
        output.writeByte(MSG_KEY_EVENT.toInt())
        output.writeByte(if (down) 1 else 0)
        output.writeShort(0) // padding
        output.writeInt(keySym)
        output.flush()
    }

    @Throws(IOException::class)
    fun sendPointerEvent(output: DataOutputStream, buttonMask: Int, x: Int, y: Int) {
        output.writeByte(MSG_POINTER_EVENT.toInt())
        output.writeByte(buttonMask)
        output.writeShort(x)
        output.writeShort(y)
        output.flush()
    }

    @Throws(IOException::class)
    fun requestFramebufferUpdate(output: DataOutputStream, incremental: Boolean, x: Int, y: Int, w: Int, h: Int) {
        output.writeByte(MSG_FRAMEBUFFER_UPDATE_REQUEST.toInt())
        output.writeByte(if (incremental) 1 else 0)
        output.writeShort(x)
        output.writeShort(y)
        output.writeShort(w)
        output.writeShort(h)
        output.flush()
    }

    @Throws(IOException::class)
    fun setEncodings(output: DataOutputStream, encodings: IntArray) {
        output.writeByte(MSG_SET_ENCODINGS.toInt())
        output.writeByte(0) // padding
        output.writeShort(encodings.size)
        for (enc in encodings) output.writeInt(enc)
        output.flush()
    }
}

/** Minimal DES for VNC authentication challenge/response. */
object Des {
    private val PC1 = intArrayOf(
        57,49,41,33,25,17,9,1,58,50,42,34,26,18,10,2,59,51,43,35,27,19,11,3,60,52,44,36,
        63,55,47,39,31,23,15,7,62,54,46,38,30,22,14,6,61,53,45,37,29,21,13,5,28,20,12,4
    )
    private val PC2 = intArrayOf(
        14,17,11,24,1,5,3,28,15,6,21,10,23,19,12,4,26,8,16,7,27,20,13,2,
        41,52,31,37,47,55,30,40,51,45,33,48,44,49,39,56,34,53,46,42,50,36,29,32
    )
    private val IP = intArrayOf(
        58,50,42,34,26,18,10,2,60,52,44,36,28,20,12,4,62,54,46,38,30,22,14,6,
        64,56,48,40,32,24,16,8,57,49,41,33,25,17,9,1,59,51,43,35,27,19,11,3,
        61,53,45,37,29,21,13,5,63,55,47,39,31,23,15,7
    )
    private val FP = IntArray(64).also { fp ->
        for (i in IP.indices) fp[IP[i] - 1] = i
    }
    private val E = intArrayOf(
        32,1,2,3,4,5,4,5,6,7,8,9,8,9,10,11,12,13,12,13,14,15,16,17,
        16,17,18,19,20,21,20,21,22,23,24,25,24,25,26,27,28,29,28,29,30,31,32,1
    )
    private val P = intArrayOf(16,7,20,21,29,12,28,17,1,15,23,26,5,18,31,10,2,8,24,14,32,27,3,9,19,13,30,6,22,11,4,25)
    private val S = arrayOf(
        intArrayOf(14,4,13,1,2,15,11,8,3,10,6,12,5,9,0,7,0,15,7,4,14,2,13,1,10,6,12,11,9,5,3,8,4,1,14,8,13,6,2,11,15,12,9,7,3,10,5,0,15,12,8,2,4,9,1,7,5,11,3,14,10,0,6,13),
        intArrayOf(15,1,8,14,6,11,3,4,9,7,2,13,12,0,5,10,3,13,4,7,15,2,8,14,12,0,1,10,6,9,11,5,0,14,7,11,10,4,13,1,5,8,12,6,9,3,2,15,13,8,10,1,3,15,4,2,11,6,7,12,0,5,14,9),
        intArrayOf(10,0,9,14,6,3,15,5,1,13,12,7,11,4,2,8,13,7,0,9,3,4,6,10,2,8,5,14,12,11,15,1,13,6,4,9,8,15,3,0,11,1,2,12,5,10,14,7,1,10,13,0,6,9,8,7,4,15,14,3,11,5,2,12),
        intArrayOf(7,13,14,3,0,6,9,10,1,2,8,5,11,12,4,15,13,8,11,5,6,15,0,3,4,7,2,12,1,10,14,9,10,6,9,0,12,11,7,13,15,1,3,14,5,2,8,4,3,15,0,6,10,1,13,8,9,4,5,11,12,7,2,14),
        intArrayOf(2,12,4,1,7,10,11,6,8,5,3,15,13,0,14,9,14,11,2,12,4,7,13,1,5,0,15,10,3,9,8,6,4,2,1,11,10,13,7,8,15,9,12,5,6,3,0,14,11,8,12,7,1,14,2,13,6,15,0,9,10,4,5,3),
        intArrayOf(12,1,10,15,9,2,6,8,0,13,3,4,14,7,5,11,10,15,4,2,7,12,9,5,6,1,13,14,0,11,3,8,9,14,15,5,2,8,12,3,7,0,4,10,1,13,11,6,4,3,2,12,9,5,15,10,11,14,1,7,6,0,8,13),
        intArrayOf(4,11,2,14,15,0,8,13,3,12,9,7,5,10,6,1,13,0,11,7,4,9,1,10,14,3,5,12,2,15,8,6,1,4,11,13,12,3,7,14,10,15,6,8,0,5,9,2,6,11,13,8,1,4,10,7,9,5,0,15,14,2,3,12),
        intArrayOf(13,2,8,4,6,15,11,1,10,9,3,14,5,0,12,7,1,15,13,8,10,3,7,4,12,5,6,11,0,14,9,2,7,11,4,1,9,12,14,2,0,6,10,13,15,3,5,8,2,1,14,7,4,10,8,13,15,12,9,0,3,5,6,11),
    )
    private val SHIFTS = intArrayOf(1,1,2,2,2,2,2,2,1,2,2,2,2,2,2,1)

    fun encrypt(key: ByteArray, data: ByteArray): ByteArray {
        val subkeys = generateSubkeys(mirrorBits(key))
        val result = ByteArray(16)
        for (block in 0..1) {
            val blockData = data.copyOfRange(block * 8, block * 8 + 8)
            val encrypted = encryptBlock(blockData, subkeys)
            encrypted.copyInto(result, block * 8)
        }
        return result
    }

    private fun mirrorBits(key: ByteArray): ByteArray {
        val mirrored = ByteArray(key.size)
        for (i in key.indices) {
            var b = key[i].toInt() and 0xFF
            var m = 0
            repeat(8) { m = (m shl 1) or (b and 1); b = b ushr 1 }
            mirrored[i] = m.toByte()
        }
        return mirrored
    }

    private fun generateSubkeys(key: ByteArray): Array<LongArray> {
        val bits = ByteArray(64) { i -> ((key[i / 8].toInt() ushr (7 - i % 8)) and 1).toByte() }
        val permuted = LongArray(56) { bits[PC1[it] - 1].toLong() }
        val subkeys = Array(16) { LongArray(48) }
        val c = LongArray(28) { permuted[it] }
        val d = LongArray(28) { permuted[it + 28] }
        for (i in 0..15) {
            repeat(SHIFTS[i]) {
                val tc = c[0]; val td = d[0]
                for (j in 0..26) { c[j] = c[j + 1]; d[j] = d[j + 1] }
                c[27] = tc; d[27] = td
            }
            val cd = c + d
            subkeys[i] = LongArray(48) { cd[PC2[it] - 1] }
        }
        return subkeys
    }

    private fun encryptBlock(data: ByteArray, subkeys: Array<LongArray>): ByteArray {
        val bits = LongArray(64) { ((data[it / 8].toInt() ushr (7 - it % 8)) and 1).toLong() }
        val ip = LongArray(64) { bits[IP[it] - 1] }
        var l = ip.copyOfRange(0, 32)
        var r = ip.copyOfRange(32, 64)
        repeat(16) { i ->
            val newR = LongArray(32) { l[it] xor fFunc(r, subkeys[i])[it] }
            l = r; r = newR
        }
        val preOutput = r + l
        val fp = LongArray(64) { preOutput[FP[it]] }
        val output = ByteArray(8)
        for (i in 0..7) {
            var b = 0
            for (j in 0..7) b = (b shl 1) or fp[i * 8 + j].toInt()
            output[i] = b.toByte()
        }
        return output
    }

    private fun fFunc(r: LongArray, subkey: LongArray): LongArray {
        val expanded = LongArray(48) { r[E[it] - 1] }
        val xored = LongArray(48) { expanded[it] xor subkey[it] }
        val sOutput = LongArray(32)
        for (i in 0..7) {
            val row = ((xored[i * 6].toInt() shl 1) or xored[i * 6 + 5].toInt())
            val col = ((xored[i * 6 + 1].toInt() shl 3) or (xored[i * 6 + 2].toInt() shl 2) or (xored[i * 6 + 3].toInt() shl 1) or xored[i * 6 + 4].toInt())
            val sVal = S[i][row * 16 + col]
            for (j in 0..3) sOutput[i * 4 + j] = ((sVal ushr (3 - j)) and 1).toLong()
        }
        return LongArray(32) { sOutput[P[it] - 1] }
    }

    private operator fun LongArray.plus(other: LongArray) = LongArray(size + other.size).also {
        copyInto(it); other.copyInto(it, size)
    }
}
