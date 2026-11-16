package app.switchboard.net

import app.switchboard.data.ToolCall
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.net.ServerSocket

/** End-to-end against the real runner.py shipped in assets (needs python3 on the test machine). */
class RunnerTest {
    companion object {
        private lateinit var proc: Process
        private lateinit var workspace: File
        private var port = 0
        private const val TOKEN = "test-token"

        @BeforeClass @JvmStatic fun start() {
            port = ServerSocket(0).use { it.localPort }
            workspace = createTempDirectory()
            val script = File("src/main/assets/runner.py").absolutePath
            proc = ProcessBuilder("python3", script, "--port", "$port", "--token", TOKEN, "--workspace", workspace.path).redirectErrorStream(true).start()
            val ready = proc.inputStream.bufferedReader().readLine()
            assertTrue("runner did not start: $ready", ready.orEmpty().contains("ready"))
            Thread { proc.inputStream.copyTo(java.io.OutputStream.nullOutputStream()) }.apply { isDaemon = true }.start()
        }

        @AfterClass @JvmStatic fun stop() { proc.destroy(); workspace.deleteRecursively() }

        private fun createTempDirectory(): File = File.createTempFile("runner-ws", "").apply { delete(); mkdirs() }
    }

    private val runner get() = Runner("http://127.0.0.1:$port", TOKEN, unrestricted = false)

    @Test fun writeRunReadDownload() = runBlocking {
        assertTrue(runner.execute(ToolCall("1", "write_file", """{"path":"src/hello.txt","content":"hi there"}""")).text.startsWith("Wrote 8 bytes"))
        val ran = runner.execute(ToolCall("2", "run_command", """{"command":"cat src/hello.txt && echo && echo done"}"""))
        assertEquals("Exit code 0.\nhi there\ndone", ran.text); assertTrue(!ran.isError)
        assertEquals("hi there", runner.execute(ToolCall("3", "read_file", """{"path":"src/hello.txt"}""")).text)
        val dest = File(workspace, "copy.bin"); runner.download("src/hello.txt", dest)
        assertEquals("hi there", dest.readText())
        assertTrue(runner.info().str("workspace")!!.endsWith(workspace.name))
    }

    @Test fun failuresAndTimeoutsAreReported() = runBlocking {
        val fail = runner.execute(ToolCall("4", "run_command", """{"command":"echo oops >&2; exit 3"}"""))
        assertEquals("Exit code 3.\noops", fail.text); assertTrue(fail.isError)
        val slow = runner.execute(ToolCall("5", "run_command", """{"command":"sleep 30","timeout_sec":1}"""))
        assertTrue(slow.text, slow.text.startsWith("Timed out") && slow.isError)
    }

    @Test fun wrongTokenIsRejected() = runBlocking {
        val r = Runner("http://127.0.0.1:$port", "nope", false).execute(ToolCall("6", "read_file", """{"path":"x"}"""))
        assertTrue(r.text, r.isError && r.text.contains("rejected the token"))
    }

    @Test fun remotePlainHttpBlockedUnlessUnrestricted() = runBlocking {
        val r = Runner("http://example.invalid:8765", TOKEN, false).execute(ToolCall("7", "read_file", """{"path":"x"}"""))
        assertTrue(r.text, r.isError && r.text.contains("plain http://"))
    }

    @Test fun sendFileToolIsOfferedAndBinaryDownloadsIntact() = runBlocking {
        assertTrue(Runner.tools.any { it.name == Runner.SEND_FILE && it.schema.toString().contains("\"required\":[\"path\"]") })
        val bytes = ByteArray(300_000) { (it * 31 % 251).toByte() }
        File(workspace, "out/app.apk").apply { parentFile!!.mkdirs(); writeBytes(bytes) }
        val dest = File(workspace, "received.apk"); runner.download("out/app.apk", dest)
        assertTrue(dest.readBytes().contentEquals(bytes))
        assertEquals("out/app.apk", Runner.describe(ToolCall("8", Runner.SEND_FILE, """{"path":"out/app.apk","caption":"here"}""")))
    }

    @Test fun stopAbortsALongCommand() = runBlocking {
        val job = launch { runner.execute(ToolCall("9", "run_command", """{"command":"sleep 30","timeout_sec":60}""")) }
        kotlinx.coroutines.delay(500)
        val t0 = System.currentTimeMillis()
        job.cancel(); job.join()
        assertTrue("cancel took ${System.currentTimeMillis() - t0} ms", System.currentTimeMillis() - t0 < 1500)
    }
}
