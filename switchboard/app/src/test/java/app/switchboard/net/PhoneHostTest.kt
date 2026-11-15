package app.switchboard.net

import app.switchboard.data.ToolCall
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PhoneHostTest {
    private val ws = File.createTempFile("phone-ws", "").apply { delete(); mkdirs() }
    private val host = PhoneHost(ws, shell = "/bin/sh") // /system/bin/sh on a real phone

    @Test fun filesAndCommandsStayInWorkspace() = runBlocking {
        assertTrue(host.execute(ToolCall("1", "write_file", """{"path":"a/b.txt","content":"hello"}""")).text.startsWith("Wrote 5 bytes"))
        val r = host.execute(ToolCall("2", "run_command", """{"command":"cat a/b.txt; echo; echo HOME=${'$'}HOME"}"""))
        assertEquals("Exit code 0.\nhello\nHOME=${ws.path}", r.text)
        assertEquals("hello", host.execute(ToolCall("3", "read_file", """{"path":"a/b.txt"}""")).text)
        val dest = File(ws, "out/copy.txt"); host.fetchFile("a/b.txt", dest); assertEquals("hello", dest.readText())
    }

    @Test fun failureAndTimeout() = runBlocking {
        val f = host.execute(ToolCall("4", "run_command", """{"command":"echo no >&2; exit 2"}"""))
        assertEquals("Exit code 2.\nno", f.text); assertTrue(f.isError)
        val t = host.execute(ToolCall("5", "run_command", """{"command":"sleep 20","timeout_sec":1}"""))
        assertTrue(t.text, t.text.startsWith("Timed out") && t.isError)
    }
}
