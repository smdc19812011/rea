package app.switchboard.net

import app.switchboard.data.ApiKind
import app.switchboard.data.ChatMessage
import app.switchboard.data.Provider
import app.switchboard.data.Role
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

/** Regression tests for replies that "hang until force close". */
class HangTest {
    /** A server that answers, then only sends SSE keep-alive comments forever (resetting any read timeout). */
    private fun pingForever(extraEvents: List<String> = emptyList()): Int {
        val server = ServerSocket(0, 5, InetAddress.getByName("127.0.0.1"))
        thread(isDaemon = true) {
            val s = server.accept()
            s.getInputStream().bufferedReader().let { r -> while (r.readLine()?.isNotEmpty() == true) {} }
            val out = s.getOutputStream()
            out.write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\n\r\n".toByteArray())
            extraEvents.forEach { out.write("data: $it\n\n".toByteArray()) }
            out.flush()
            runCatching { repeat(600) { out.write(": keep-alive\n\n".toByteArray()); out.flush(); Thread.sleep(100) } }
            runCatching { s.close(); server.close() }
        }
        return server.localPort
    }

    private val history = listOf(ChatMessage(role = Role.USER, text = "hi"))

    @Test fun stopAbortsAStreamThatOnlySendsKeepAlives() = runBlocking {
        val port = pingForever()
        val p = Provider(name = "O", kind = ApiKind.OPENAI_COMPATIBLE, baseUrl = "http://127.0.0.1:$port/v1", model = "m")
        val job = launch { ChatClient().stream(p, history, RequestOptions()).toList() }
        delay(700)
        val t0 = System.currentTimeMillis()
        job.cancel(); job.join()
        val took = System.currentTimeMillis() - t0
        assertTrue("cancel took $took ms", took < 1500)
    }

    @Test fun reasoningIsStreamedAsThinking() = runBlocking {
        val port = pingForever(listOf(
            """{"choices":[{"delta":{"reasoning_content":"Let me think. "}}]}""",
            """{"choices":[{"delta":{"reasoning":"Okay."}}]}""",
        ))
        val p = Provider(name = "O", kind = ApiKind.OPENAI_COMPATIBLE, baseUrl = "http://127.0.0.1:$port/v1", model = "m")
        val seen = StringBuilder()
        val job = launch { ChatClient().stream(p, history, RequestOptions()).collect { if (it is StreamEvent.ThinkingDelta) seen.append(it.text) } }
        delay(800); job.cancel(); job.join()
        assertEquals("Let me think. Okay.", seen.toString())
    }
}
