package app.switchboard.net

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.File
import java.io.OutputStream
import java.math.BigInteger
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPrivateCrtKey
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64

/**
 * Minimal ADB client over TCP (the classic `adb connect host:5555` transport): authentication with an RSA key kept on
 * the phone, shell commands, pushing files, and installing APKs. Pure JVM code, unit-tested against a mock device.
 *
 * Android 11+ "Wireless debugging" (pair with code, TLS) isn't supported here; those devices answer with STLS and get a
 * clear message. They work with `adb tcpip 5555` once, or with the adb from the Termux toolbox.
 */
class AdbClient(private val keyFile: File, private val name: String = "switchboard@android") {
    private companion object {
        const val CNXN = 0x4e584e43; const val AUTH = 0x48545541; const val OPEN = 0x4e45504f; const val OKAY = 0x59414b4f
        const val CLSE = 0x45534c43; const val WRTE = 0x45545257; const val STLS = 0x534c5453
        const val VERSION = 0x01000000; const val MAX_DATA = 256 * 1024
        const val AUTH_TOKEN = 1; const val AUTH_SIGNATURE = 2; const val AUTH_RSAPUBLICKEY = 3
        /** DER prefix of a SHA-1 DigestInfo; adb signs the 20-byte token as if it were a SHA-1 hash. */
        val SHA1_DIGEST_INFO = byteArrayOf(0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14)
    }

    private class Msg(val cmd: Int, val arg0: Int, val arg1: Int, val data: ByteArray)

    class AdbException(msg: String) : Exception(msg)

    private var socket: Socket? = null
    private lateinit var input: DataInputStream
    private lateinit var output: OutputStream
    private var maxData = 4096
    private var nextLocalId = 1
    var banner: String = ""; private set

    val isConnected get() = socket?.isConnected == true && socket?.isClosed == false

    // ---------- keys ----------

    private fun key(): RSAPrivateCrtKey {
        if (!keyFile.isFile) {
            keyFile.parentFile?.mkdirs()
            keyFile.writeBytes(KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair().private.encoded)
        }
        return KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes())) as RSAPrivateCrtKey
    }

    /** Android's adb public key format: modulus words, n0inv, R^2 mod n, exponent; base64 plus a name. */
    internal fun publicKeyBlob(k: RSAPrivateCrtKey): ByteArray {
        val n = k.modulus; val words = 64
        val r32 = BigInteger.ONE.shiftLeft(32)
        val n0inv = r32.subtract(n.mod(r32).modInverse(r32)).toLong()
        val rr = BigInteger.ONE.shiftLeft(2048).modPow(BigInteger.valueOf(2), n)
        val b = ByteBuffer.allocate(4 + 4 + words * 4 * 2 + 4).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(words).putInt(n0inv.toInt())
        fun putWords(v: BigInteger) { var x = v; repeat(words) { b.putInt(x.and(BigInteger.valueOf(0xffffffffL)).toInt()); x = x.shiftRight(32) } }
        putWords(n); putWords(rr)
        b.putInt(k.publicExponent.toInt())
        return (Base64.getEncoder().encodeToString(b.array()) + " $name\u0000").toByteArray()
    }

    private fun sign(token: ByteArray): ByteArray = Signature.getInstance("NONEwithRSA").run { initSign(key()); update(SHA1_DIGEST_INFO + token); sign() }

    // ---------- transport ----------

    private fun send(cmd: Int, arg0: Int, arg1: Int, data: ByteArray = ByteArray(0)) {
        val h = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
        h.putInt(cmd).putInt(arg0).putInt(arg1).putInt(data.size).putInt(data.sumOf { it.toInt() and 0xff }).putInt(cmd.inv())
        synchronized(output) { output.write(h.array()); output.write(data); output.flush() }
    }

    private fun read(): Msg {
        val h = ByteArray(24); input.readFully(h)
        val b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN)
        val cmd = b.int; val a0 = b.int; val a1 = b.int; val len = b.int
        if (len < 0 || len > 1 shl 24) throw AdbException("Bad ADB packet (length $len)")
        val data = ByteArray(len); input.readFully(data)
        return Msg(cmd, a0, a1, data)
    }

    /**
     * Connects and authenticates. The first time, the device shows "Allow USB debugging?" for this phone's key; the call
     * waits up to [acceptTimeoutMs] for the user to tap Allow on that device.
     */
    fun connect(host: String, port: Int = 5555, acceptTimeoutMs: Int = 60_000) {
        close()
        val s = Socket().apply { connect(InetSocketAddress(host, port), 8000); soTimeout = 15_000; tcpNoDelay = true }
        socket = s; input = DataInputStream(s.getInputStream().buffered()); output = s.getOutputStream().buffered()
        send(CNXN, VERSION, MAX_DATA, "host::features=shell_v2,cmd\u0000".toByteArray())
        var sentSignature = false; var sentKey = false
        while (true) {
            val m = try { read() } catch (e: java.net.SocketTimeoutException) {
                throw AdbException(if (sentKey) "Timed out waiting for \"Allow USB debugging\" to be accepted on $host." else "No answer from $host:$port.")
            }
            when (m.cmd) {
                CNXN -> { maxData = m.arg1.coerceIn(4096, MAX_DATA); banner = String(m.data).trimEnd('\u0000'); s.soTimeout = 30_000; return }
                STLS -> { close(); throw AdbException("$host uses Android 11+ Wireless debugging (pairing code + TLS). Run `adb tcpip 5555` once from a computer, or use the adb in Termux (Toolbox > Android platform tools) with `adb pair`.") }
                AUTH -> if (m.arg0 == AUTH_TOKEN) {
                    if (!sentSignature) { send(AUTH, AUTH_SIGNATURE, 0, sign(m.data)); sentSignature = true }
                    else if (!sentKey) { send(AUTH, AUTH_RSAPUBLICKEY, 0, publicKeyBlob(key())); sentKey = true; s.soTimeout = acceptTimeoutMs }
                    else throw AdbException("$host rejected this phone's ADB key.")
                }
                else -> throw AdbException("Unexpected ADB reply 0x%08x".format(m.cmd))
            }
        }
    }

    fun close() { runCatching { socket?.close() }; socket = null }

    // ---------- streams ----------

    private inner class Stream(val local: Int, val remote: Int) {
        /** Sends data in chunks, waiting for the device's OKAY after each one (ADB flow control). */
        fun write(data: ByteArray) {
            var off = 0
            while (off < data.size) {
                val n = minOf(maxData, data.size - off)
                send(WRTE, local, remote, data.copyOfRange(off, off + n)); off += n
                while (true) {
                    val m = read()
                    if (m.cmd == OKAY && m.arg1 == local) break
                    if (m.cmd == CLSE && m.arg1 == local) throw AdbException("The device closed the stream.")
                    pending += m
                }
            }
        }
        val pending = ArrayDeque<Msg>()
        /** Next data packet for this stream, or null when the device closes it. */
        fun readData(): ByteArray? {
            while (true) {
                val m = pending.removeFirstOrNull() ?: read()
                if (m.arg1 != local) continue
                when (m.cmd) {
                    WRTE -> { send(OKAY, local, remote); return m.data }
                    CLSE -> { send(CLSE, local, remote); return null }
                }
            }
        }
    }

    private fun open(service: String): Stream {
        val local = nextLocalId++
        send(OPEN, local, 0, "$service\u0000".toByteArray())
        while (true) {
            val m = read()
            if (m.arg1 != local) continue
            when (m.cmd) {
                OKAY -> return Stream(local, m.arg0)
                CLSE -> throw AdbException("The device refused '$service'.")
            }
        }
    }

    /** Runs a shell command and returns its output (stdout and stderr together). */
    fun shell(command: String, maxBytes: Int = 200_000): String {
        val s = open("shell:$command")
        val out = ByteArrayOutputStream()
        while (true) {
            val d = s.readData() ?: break
            if (out.size() < maxBytes) out.write(d, 0, minOf(d.size, maxBytes - out.size()))
        }
        return out.toString(Charsets.UTF_8.name())
    }

    /** Copies [data] to [remotePath] on the device (sync protocol SEND/DATA/DONE). */
    fun push(data: ByteArray, remotePath: String, mode: Int = 420) {
        val s = open("sync:")
        fun header(id: String, n: Int) = id.toByteArray() + ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(n).array()
        val spec = "$remotePath,$mode".toByteArray()
        val buf = ByteArrayOutputStream().apply { write(header("SEND", spec.size)); write(spec) }
        var off = 0
        while (off < data.size) { val n = minOf(64 * 1024, data.size - off); buf.write(header("DATA", n)); buf.write(data, off, n); off += n }
        buf.write(header("DONE", (System.currentTimeMillis() / 1000).toInt()))
        s.write(buf.toByteArray())
        val reply = s.readData() ?: throw AdbException("No reply to push.")
        if (String(reply, 0, 4) != "OKAY") throw AdbException("Push failed: ${String(reply, 8.coerceAtMost(reply.size), (reply.size - 8).coerceAtLeast(0))}")
        runCatching { s.write(header("QUIT", 0)) }
    }

    /** Pushes an APK to /data/local/tmp and installs it with the package manager. */
    fun install(apk: ByteArray, name: String = "switchboard-install.apk"): String {
        val tmp = "/data/local/tmp/$name"
        push(apk, tmp)
        return shell("pm install -r -g '$tmp'; rm -f '$tmp'").trim()
    }

}
