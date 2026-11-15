package app.switchboard.net

import app.switchboard.data.ApiKind
import app.switchboard.data.ChatMessage
import app.switchboard.data.Provider
import app.switchboard.data.Role
import app.switchboard.data.ToolCall
import java.net.ServerSocket
import kotlin.concurrent.thread
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ChatClientTest {
    private lateinit var server: ServerSocket
    private val routes = mutableMapOf<String, Pair<Int, String>>()
    private val seen = mutableMapOf<String, Pair<Map<String, String>, String>>()
    private val base get() = "http://127.0.0.1:${server.localPort}/v1"

    /** Minimal HTTP/1.1 mock: android.jar has no com.sun.net.httpserver. */
    private fun serve() = thread(isDaemon = true) {
        while (!server.isClosed) {
            val sock = runCatching { server.accept() }.getOrNull() ?: break
            sock.use { s ->
                val input = s.getInputStream().bufferedReader()
                val path = input.readLine().split(" ")[1].substringBefore('?')
                val headers = generateSequence { input.readLine()?.takeIf { it.isNotEmpty() } }
                    .associate { it.substringBefore(':').lowercase() to it.substringAfter(':').trim() }
                val len = headers["content-length"]?.toInt() ?: 0
                val body = CharArray(len).also { var r = 0; while (r < len) r += input.read(it, r, len - r) }.concatToString()
                seen[path] = headers to body
                val (code, resp) = routes[path] ?: (404 to "")
                val bytes = resp.toByteArray()
                if (code == 299) { // simulate the connection dying mid-stream: promise more bytes than we send
                    s.getOutputStream().apply { write("HTTP/1.1 200 X\r\nContent-Length: ${bytes.size + 500}\r\n\r\n".toByteArray()); write(bytes); flush() }
                    return@use
                }
                s.getOutputStream().apply {
                    write("HTTP/1.1 $code X\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray()); write(bytes); flush()
                }
            }
        }
    }

    @Before fun start() {
        server = ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))
        routes["/v1/messages"] = 200 to """
            event: message_start
            data: {"type":"message_start","message":{"id":"m"}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hel"}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"lo"}}

            event: message_stop
            data: {"type":"message_stop"}

        """.trimIndent()
        routes["/v1/chat/completions"] = 200 to "data: {\"choices\":[{\"delta\":{\"content\":\"Hi \"}}]}\n\ndata: {\"choices\":[{\"delta\":{\"content\":\"there\"}}]}\n\ndata: [DONE]\n\n"
        routes["/v1/models"] = 200 to """{"data":[{"id":"b-model"},{"id":"a-model"}]}"""
        routes["/bad/chat/completions"] = 401 to """{"error":{"message":"invalid key"}}"""
        serve()
    }

    @After fun stop() = server.close()

    private suspend fun text(f: kotlinx.coroutines.flow.Flow<StreamEvent>) =
        f.toList().filterIsInstance<StreamEvent.TextDelta>().joinToString("") { it.text }

    private val history = listOf(
        ChatMessage(role = Role.USER, text = "one"),
        ChatMessage(role = Role.ASSISTANT, text = "boom", isError = true),
        ChatMessage(role = Role.USER, text = "two"),
    )

    @Test fun anthropicStreamsAndSendsCorrectRequest() = runBlocking {
        val p = Provider(name = "A", kind = ApiKind.ANTHROPIC, baseUrl = base, apiKey = "sk-test", model = "claude-opus-5-5")
        assertEquals("Hello", text(ChatClient().stream(p, history, RequestOptions(system = "be brief", maxTokens = 1000))))
        val (h, body) = seen.getValue("/v1/messages")
        assertEquals("sk-test", h["x-api-key"]); assertEquals("2026-10-21", h["anthropic-version"])
        assertTrue(body.contains("\"system\":\"be brief\""))
        // error reply dropped, consecutive user turns merged into one
        assertTrue(body, body.contains("\"messages\":[{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"one\"},{\"type\":\"text\",\"text\":\"two\"}]}]"))
        assertTrue(!body.contains("temperature"))
    }

    @Test fun openAiCompatibleStreams() = runBlocking {
        val p = Provider(name = "O", kind = ApiKind.OPENAI_COMPATIBLE, baseUrl = base, apiKey = "or-key", model = "x/y")
        assertEquals("Hi there", text(ChatClient().stream(p, history, RequestOptions(system = "sys"))))
        val (h, body) = seen.getValue("/v1/chat/completions")
        assertEquals("Bearer or-key", h["authorization"])
        assertTrue(body.startsWith("{\"model\":\"x/y\",\"stream\":true,\"messages\":[{\"role\":\"system\",\"content\":\"sys\"}"))
    }

    @Test fun listsModelsSorted() = runBlocking {
        val p = Provider(name = "O", kind = ApiKind.OPENAI_COMPATIBLE, baseUrl = base, model = "")
        assertEquals(listOf("a-model", "b-model"), ChatClient().listModels(p))
        assertTrue(seen.getValue("/v1/models").first["authorization"] == null) // no key -> no auth header
    }

    @Test fun surfacesApiErrorMessage() = runBlocking {
        val p = Provider(name = "O", kind = ApiKind.OPENAI_COMPATIBLE, baseUrl = "http://127.0.0.1:${server.localPort}/bad", model = "m")
        val e = runCatching { ChatClient().stream(p, history, RequestOptions()).toList() }.exceptionOrNull()
        assertEquals("HTTP 401: invalid key", e?.message)
    }

    @Test fun extraBodyOnlyAppliesWhenUnrestricted() = runBlocking {
        val p = Provider(name = "O", kind = ApiKind.OPENAI_COMPATIBLE, baseUrl = base, model = "x/y")
        val extra = kotlinx.serialization.json.Json.parseToJsonElement("""{"temperature":0.3,"model":"hijack"}""") as kotlinx.serialization.json.JsonObject
        ChatClient().stream(p, history, RequestOptions(extraBody = extra)).toList()
        assertTrue(!seen.getValue("/v1/chat/completions").second.contains("temperature"))
        ChatClient().stream(p, history, RequestOptions(unrestricted = true, maxTokens = 500_000, extraBody = extra)).toList()
        val body = seen.getValue("/v1/chat/completions").second
        assertTrue(body.contains("\"temperature\":0.3") && body.contains("\"max_tokens\":500000"))
        assertTrue(body.contains("\"model\":\"x/y\"") && !body.contains("hijack"))
    }

    @Test fun plainHttpToRemoteHostNeedsUnrestricted() = runBlocking {
        val p = Provider(name = "R", kind = ApiKind.OPENAI_COMPATIBLE, baseUrl = "http://example.invalid/v1", model = "m")
        val e = runCatching { ChatClient().stream(p, history, RequestOptions()).toList() }.exceptionOrNull()
        assertTrue(e?.message.orEmpty().startsWith("Plain http://"))
        val e2 = runCatching { ChatClient().stream(p, history, RequestOptions(unrestricted = true)).toList() }.exceptionOrNull()
        assertTrue(e2 != null && !e2.message.orEmpty().startsWith("Plain http://")) // passes the gate, fails on DNS instead
    }

    @Test fun anthropicToolUseKeepsThinkingSignatureForReplay() = runBlocking {
        routes["/v1/messages"] = 200 to listOf(
            """{"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":""}}""",
            """{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"plan"}}""",
            """{"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":"SIG"}}""",
            """{"type":"content_block_stop","index":0}""",
            """{"type":"content_block_start","index":1,"content_block":{"type":"text","text":""}}""",
            """{"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"Building."}}""",
            """{"type":"content_block_stop","index":1}""",
            """{"type":"content_block_start","index":2,"content_block":{"type":"tool_use","id":"tu_1","name":"run_command","input":{}}}""",
            """{"type":"content_block_delta","index":2,"delta":{"type":"input_json_delta","partial_json":"{\"command\": \"ls"}}""",
            """{"type":"content_block_delta","index":2,"delta":{"type":"input_json_delta","partial_json":" -la\"}"}}""",
            """{"type":"content_block_stop","index":2}""",
            """{"type":"message_delta","delta":{"stop_reason":"tool_use"}}""",
        ).joinToString("") { "data: $it\n\n" }
        val p = Provider(name = "A", kind = ApiKind.ANTHROPIC, baseUrl = base, apiKey = "k", model = "m")
        val end = ChatClient().stream(p, history, RequestOptions(tools = Runner.tools)).toList().filterIsInstance<StreamEvent.TurnEnd>().single()
        assertEquals("Building.", end.text)
        assertEquals(listOf(ToolCall("tu_1", "run_command", "{\"command\":\"ls -la\"}")), end.toolCalls)
        assertTrue(end.anthropicBlocks!!.contains("\"signature\":\"SIG\"") && end.anthropicBlocks!!.contains("\"thinking\":\"plan\""))
        assertTrue(seen.getValue("/v1/messages").second.contains("\"input_schema\""))

        // Replay: blocks go back verbatim, result follows as a tool_result in a user turn.
        val replay = history + ChatMessage(role = Role.ASSISTANT, text = end.text, toolCalls = end.toolCalls, anthropicBlocks = end.anthropicBlocks) +
            ChatMessage(role = Role.TOOL, text = "file.txt", toolCallId = "tu_1")
        val body = ChatClient().anthropicBody(p, ChatClient().normalize(replay), RequestOptions()).toString()
        assertTrue(body, body.contains("\"signature\":\"SIG\"") && body.contains("{\"type\":\"tool_result\",\"tool_use_id\":\"tu_1\",\"content\":\"file.txt\"}"))
    }

    @Test fun openAiToolCallsAccumulateAndReplay() = runBlocking {
        routes["/v1/chat/completions"] = 200 to listOf(
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_9","type":"function","function":{"name":"write_file","arguments":""}}]}}]}""",
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"path\":\"a.txt\","}}]}}]}""",
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\"content\":\"hi\"}"}}]},"finish_reason":"tool_calls"}]}""",
        ).joinToString("") { "data: $it\n\n" } + "data: [DONE]\n\n"
        val p = Provider(name = "O", kind = ApiKind.OPENAI_COMPATIBLE, baseUrl = base, model = "m")
        val end = ChatClient().stream(p, history, RequestOptions(tools = Runner.tools)).toList().filterIsInstance<StreamEvent.TurnEnd>().single()
        assertEquals(listOf(ToolCall("call_9", "write_file", "{\"path\":\"a.txt\",\"content\":\"hi\"}")), end.toolCalls)
        val sent = seen.getValue("/v1/chat/completions").second
        assertTrue(sent.contains("\"tools\":[{\"type\":\"function\""))

        // A stopped run (no result) gets a synthetic tool reply so the API accepts the history.
        val replay = history + ChatMessage(role = Role.ASSISTANT, text = "", toolCalls = end.toolCalls) + ChatMessage(role = Role.USER, text = "never mind")
        val body = ChatClient().openAiBody(p, ChatClient().normalize(replay), RequestOptions()).toString()
        assertTrue(body, body.contains("{\"role\":\"tool\",\"tool_call_id\":\"call_9\",\"content\":\"Not run: the user stopped this step.\"}"))
        assertTrue(body.contains("\"tool_calls\":[{\"id\":\"call_9\""))
    }

    @Test fun droppedConnectionAndOverloadAreRetryableButBadRequestIsNot() = runBlocking {
        val p = Provider(name = "O", kind = ApiKind.OPENAI_COMPATIBLE, baseUrl = base, model = "m")
        routes["/v1/chat/completions"] = 299 to "data: {\"choices\":[{\"delta\":{\"content\":\"partial\"}}]}\n\n"
        val dropped = runCatching { ChatClient().stream(p, history, RequestOptions()).toList() }.exceptionOrNull()
        assertTrue("$dropped", dropped != null && isRetryable(dropped))

        routes["/v1/chat/completions"] = 503 to """{"error":{"message":"overloaded"}}"""
        val busy = runCatching { ChatClient().stream(p, history, RequestOptions()).toList() }.exceptionOrNull()
        assertTrue("$busy", busy is ApiException && busy.httpCode == 503 && isRetryable(busy))

        routes["/v1/chat/completions"] = 400 to """{"error":{"message":"bad model"}}"""
        val bad = runCatching { ChatClient().stream(p, history, RequestOptions()).toList() }.exceptionOrNull()
        assertTrue("$bad", bad is ApiException && !isRetryable(bad))
        assertTrue(isRetryable(java.net.SocketException("Software caused connection abort")))
    }
}
