package app.switchboard.net

import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.net.ServerSocket
import java.nio.file.Files

class AdbClientTest {
    companion object {
        private val procs = mutableListOf<Process>()
        private var port = 0; private var tlsPort = 0
        private lateinit var out: File
        private fun start(mode: String): Int {
            val p = ServerSocket(0).use { it.localPort }
            val proc = ProcessBuilder("python3", File("src/test/resources/mock_adbd.py").absolutePath, "$p", out.path, mode).redirectErrorStream(true).start()
            assertEquals("ready", proc.inputStream.bufferedReader().readLine()); procs += proc
            return p
        }
        @BeforeClass @JvmStatic fun setup() { out = Files.createTempDirectory("adbd").toFile(); port = start("normal"); tlsPort = start("tls") }
        @AfterClass @JvmStatic fun stop() { procs.forEach { it.destroy() } }
    }

    private val key = File(Files.createTempDirectory("adbkey").toFile(), "adbkey.pk8")

    @Test fun firstConnectSendsKeyThenSignatureIsAcceptedShellAndPushWork() {
        val a = AdbClient(key)
        a.connect("127.0.0.1", port) // unknown key: signature rejected, public key sent, "Allow" accepted by the mock
        assertTrue(a.banner, a.banner.contains("MockPhone"))
        assertEquals("hello adb\n", a.shell("echo hello adb"))
        val big = ByteArray(300_000) { (it % 251).toByte() }
        a.push(big, "/sdcard/Download/blob.bin")
        assertTrue(File(out, "blob.bin").readBytes().contentEquals(big))
        a.close()
        // Second connection: the same key is now known, so the signature alone is enough (mock verifies it with RSA).
        val b = AdbClient(key)
        b.connect("127.0.0.1", port)
        assertEquals("2\n", b.shell("expr 1 + 1"))
        b.close()
    }

    @Test fun android11WirelessDebuggingExplainsWhatToDo() {
        val e = runCatching { AdbClient(key).connect("127.0.0.1", tlsPort) }.exceptionOrNull()
        assertTrue("$e", e?.message.orEmpty().contains("Wireless debugging") && e?.message.orEmpty().contains("adb tcpip 5555"))
    }
}
