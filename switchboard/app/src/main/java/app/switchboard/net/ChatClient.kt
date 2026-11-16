package app.switchboard.net

import app.switchboard.data.ApiKind
import app.switchboard.data.ChatMessage
import app.switchboard.data.Provider
import app.switchboard.data.Role
import app.switchboard.data.ToolCall
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

class ApiException(message: String, val httpCode: Int? = null) : IOException(message)

/** Dropped connections, timeouts, rate limiting and overloaded servers are worth retrying; bad requests are not. */
fun isRetryable(e: Throwable): Boolean = when (e) {
    is ApiException -> e.httpCode in setOf(408, 429, 500, 502, 503, 504, 529)
    is IOException -> true // SocketException ("Software caused connection abort"), timeouts, resets, DNS hiccups
    else -> false
}

/** Per-request options derived from settings. */
data class RequestOptions(
    val system: String = "",
    val maxTokens: Int = 8192,
    val unrestricted: Boolean = false,
    val extraBody: JsonObject? = null,
    val tools: List<ToolSpec> = emptyList(),
)

/** A model offered by a provider. [outputs] is empty when the provider doesn't say (then the name decides). */
data class ModelInfo(val id: String, val name: String = id, val inputs: Set<String> = emptySet(), val outputs: Set<String> = emptySet(),
                     val free: Boolean = false, val contextLength: Int? = null) {
    private val n = id.lowercase()
    private val imageName = Regex("image|dall-?e|imagen|flux|stable-?diffusion|sdxl|midjourney|recraft|ideogram|seedream")
    private val audioName = Regex("tts|audio|speech|voice|lyria|music")
    private val videoName = Regex("sora|veo|video|kling|runway|hailuo|seedance|pika|luma")
    fun fits(task: app.switchboard.data.Task): Boolean = when (task) {
        app.switchboard.data.Task.CHAT -> if (outputs.isNotEmpty()) "text" in outputs else !videoName.containsMatchIn(n) && !Regex("^(dall-?e|tts|whisper|sora|text-embedding|omni-moderation)").containsMatchIn(n)
        app.switchboard.data.Task.IMAGE -> "image" in outputs || (outputs.isEmpty() && imageName.containsMatchIn(n))
        app.switchboard.data.Task.AUDIO -> "audio" in outputs || (outputs.isEmpty() && audioName.containsMatchIn(n))
        app.switchboard.data.Task.VIDEO -> "video" in outputs || videoName.containsMatchIn(n)
    }
}

/** Provider-neutral tool definition; [schema] is a JSON Schema object. */
data class ToolSpec(val name: String, val description: String, val schema: JsonObject)

sealed interface StreamEvent {
    data class TextDelta(val text: String) : StreamEvent
    /** Reasoning the model streams before answering (DeepSeek/NIM reasoning_content, OpenRouter reasoning, Claude thinking summaries). */
    data class ThinkingDelta(val text: String) : StreamEvent
    /** Emitted once at the end of a successful turn. */
    /** [images] are data: or https: URLs of images the model produced itself (e.g. OpenRouter image models). */
    data class TurnEnd(val text: String, val toolCalls: List<ToolCall>, val anthropicBlocks: String?, val images: List<String> = emptyList()) : StreamEvent
}

private val localHosts = setOf("localhost", "127.0.0.1", "10.0.2.2")
fun isLocalHost(host: String): Boolean {
    if (host in localHosts || host.endsWith(".local") || host.endsWith(".lan")) return true
    // Private LAN ranges: 10/8, 172.16/12, 192.168/16, 169.254/16 (link-local).
    val o = host.split('.').mapNotNull { it.toIntOrNull() }.takeIf { it.size == 4 } ?: return false
    return o[0] == 10 || (o[0] == 172 && o[1] in 16..31) || (o[0] == 192 && o[1] == 168) || (o[0] == 169 && o[1] == 254)
}

/** Streams replies from either an Anthropic Messages endpoint or any OpenAI-compatible /chat/completions endpoint. */
class ChatClient {
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        // Servers send data or keep-alive pings while a model thinks; 2.5 min of total silence means the connection is dead.
        .readTimeout(150, TimeUnit.SECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true }
    private val jsonType = "application/json".toMediaType()

    fun stream(p: Provider, history: List<ChatMessage>, opts: RequestOptions): Flow<StreamEvent> = callbackFlow {
        val msgs = normalize(history)
        val request = when (p.kind) {
            ApiKind.ANTHROPIC -> anthropicRequest(p, msgs, opts)
            ApiKind.OPENAI_COMPATIBLE -> openAiRequest(p, msgs, opts)
        }
        checkTransport(request, opts.unrestricted)
        val call = http.newCall(request)
        // The blocking read runs in a child; awaitClose is registered right away, so Stop cancels the call immediately.
        launch(Dispatchers.IO) {
            try {
                call.execute().use { resp ->
                    if (!resp.isSuccessful) throw ApiException(errorText(resp), resp.code)
                    val source = resp.body?.source() ?: throw ApiException("Empty response")
                    val acc: TurnAccumulator = if (p.kind == ApiKind.ANTHROPIC) AnthropicAccumulator() else OpenAiAccumulator()
                    while (true) {
                        val line = source.readUtf8Line() ?: break
                        if (!line.startsWith("data:")) continue
                        val data = line.removePrefix("data:").trim()
                        if (data == "[DONE]") break
                        val obj = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull() ?: continue
                        acc.accept(obj)?.let { trySend(StreamEvent.TextDelta(it)) }
                        acc.takeThinking()?.let { trySend(StreamEvent.ThinkingDelta(it)) }
                    }
                    trySend(acc.finish())
                }
                close()
            } catch (e: Exception) {
                close(if (call.isCanceled()) null else e)
            }
        }
        awaitClose { call.cancel() }
    }.flowOn(Dispatchers.IO)

    suspend fun listModels(p: Provider, unrestricted: Boolean = false): List<String> = listModelsDetailed(p, unrestricted).map { it.id }

    /** Every model the API key can use, with input/output types and price when the provider reports them (OpenRouter does). */
    suspend fun listModelsDetailed(p: Provider, unrestricted: Boolean = false): List<ModelInfo> = withContext(Dispatchers.IO) {
        val b = Request.Builder().url(p.baseUrl.trimEnd('/') + "/models?limit=1000").get()
        authHeaders(b, p)
        val req = b.build()
        checkTransport(req, unrestricted)
        http.newCall(req).cancellable { resp ->
            if (!resp.isSuccessful) throw ApiException(errorText(resp), resp.code)
            val root = json.parseToJsonElement(resp.body!!.string()).jsonObject
            root["data"]?.jsonArray.orEmpty().mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                val id = o.str("id") ?: return@mapNotNull null
                val arch = o["architecture"] as? JsonObject
                fun set(k: String) = (arch?.get(k) as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.toSet().orEmpty()
                val pricing = o["pricing"] as? JsonObject
                val free = id.endsWith(":free") || (pricing != null && listOf("prompt", "completion").all { k -> pricing.str(k)?.toDoubleOrNull() == 0.0 })
                ModelInfo(id, o.str("name") ?: o.str("display_name") ?: id, set("input_modalities"), set("output_modalities"), free,
                    o.str("context_length")?.toIntOrNull())
            }.sortedBy { it.id }
        }
    }

    // --- Anthropic Messages API ---

    internal fun anthropicBody(p: Provider, msgs: List<ChatMessage>, opts: RequestOptions): JsonObject = buildJsonObject {
        put("model", p.model)
        put("max_tokens", opts.maxTokens)
        put("stream", true)
        if (opts.system.isNotBlank()) put("system", opts.system)
        if (opts.tools.isNotEmpty()) putJsonArray("tools") {
            for (t in opts.tools) addJsonObject { put("name", t.name); put("description", t.description); put("input_schema", t.schema) }
        }
        // Each turn as a content-block array; adjacent same-role turns are concatenated (tool results + text etc.).
        val turns = mutableListOf<Pair<String, MutableList<JsonElement>>>()
        for (m in msgs) {
            val role = if (m.role == Role.ASSISTANT) "assistant" else "user"
            val blocks: List<JsonElement> = when (m.role) {
                Role.USER -> listOf(textBlock(m.text))
                Role.TOOL -> listOf(buildJsonObject {
                    put("type", "tool_result"); put("tool_use_id", m.toolCallId ?: ""); put("content", m.text.ifEmpty { "(no output)" })
                    if (m.isError) put("is_error", true)
                })
                Role.ASSISTANT -> m.anthropicBlocks?.let { raw -> runCatching { json.parseToJsonElement(raw).jsonArray.toList() }.getOrNull() }
                    ?: buildList {
                        if (m.text.isNotBlank()) add(textBlock(m.text))
                        for (c in m.toolCalls) add(buildJsonObject {
                            put("type", "tool_use"); put("id", c.id); put("name", c.name); put("input", parseArgs(c.arguments))
                        })
                    }
            }
            if (blocks.isEmpty()) continue
            if (turns.lastOrNull()?.first == role) turns.last().second += blocks else turns += role to blocks.toMutableList()
        }
        putJsonArray("messages") {
            for ((role, blocks) in turns) addJsonObject { put("role", role); put("content", JsonArray(blocks)) }
        }
    }.withExtras(opts)

    private fun anthropicRequest(p: Provider, msgs: List<ChatMessage>, opts: RequestOptions): Request {
        val b = Request.Builder().url(p.baseUrl.trimEnd('/') + "/messages").post(anthropicBody(p, msgs, opts).toString().toRequestBody(jsonType))
        authHeaders(b, p)
        return b.build()
    }

    private interface TurnAccumulator {
        /** Returns visible text to show, if any. */
        fun accept(o: JsonObject): String?
        /** Reasoning text received since the last call, if any. */
        fun takeThinking(): String? = null
        fun finish(): StreamEvent.TurnEnd
    }

    /** Rebuilds every content block (text, thinking + signature, tool_use) so the turn can be replayed unchanged. */
    private inner class AnthropicAccumulator : TurnAccumulator {
        private val blocks = sortedMapOf<Int, MutableMap<String, JsonElement>>()
        private val partialJson = mutableMapOf<Int, StringBuilder>()
        private val text = StringBuilder()
        private val thinking = StringBuilder()
        override fun takeThinking(): String? = thinking.takeIf { it.isNotEmpty() }?.toString()?.also { thinking.clear() }

        override fun accept(o: JsonObject): String? {
            when (o.str("type")) {
                "content_block_start" -> {
                    val i = o.int("index")
                    blocks[i] = o["content_block"]!!.jsonObject.toMutableMap()
                }
                "content_block_delta" -> {
                    val i = o.int("index"); val d = o["delta"]!!.jsonObject
                    // Tolerate a missing content_block_start (some proxies drop it) by assuming a text block.
                    val b = blocks.getOrPut(i) { mutableMapOf("type" to JsonPrimitive("text"), "text" to JsonPrimitive("")) }
                    when (d.str("type")) {
                        "text_delta" -> { val t = d.str("text").orEmpty(); b.append("text", t); text.append(t); return t }
                        "thinking_delta" -> { val t = d.str("thinking").orEmpty(); b.append("thinking", t); thinking.append(t) }
                        "signature_delta" -> b["signature"] = JsonPrimitive(d.str("signature").orEmpty())
                        "input_json_delta" -> partialJson.getOrPut(i) { StringBuilder() }.append(d.str("partial_json").orEmpty())
                    }
                }
                "content_block_stop" -> {
                    val i = o.int("index"); val b = blocks[i] ?: return null
                    if (b["type"]?.jsonPrimitive?.contentOrNull == "tool_use") b["input"] = parseArgs(partialJson[i]?.toString().orEmpty())
                }
                "message_delta" -> when (o["delta"]?.jsonObject?.str("stop_reason")) {
                    "refusal" -> return "\n\n_[The model declined this request.]_".also { text.append(it) }
                    "max_tokens" -> return "\n\n_[Stopped: reached the max output tokens limit.]_".also { text.append(it) }
                }
                "error" -> throw ApiException(o["error"]?.jsonObject?.str("message") ?: "Stream error")
            }
            return null
        }

        override fun finish(): StreamEvent.TurnEnd {
            val all = blocks.values.map { JsonObject(it) }
            val calls = all.filter { it.str("type") == "tool_use" }.map { ToolCall(it.str("id")!!, it.str("name")!!, it["input"].toString()) }
            return StreamEvent.TurnEnd(text.toString(), calls, JsonArray(all).toString())
        }

        private fun MutableMap<String, JsonElement>.append(k: String, v: String) {
            this[k] = JsonPrimitive(((this[k] as? JsonPrimitive)?.contentOrNull ?: "") + v)
        }
    }

    // --- OpenAI-compatible Chat Completions ---

    internal fun openAiBody(p: Provider, msgs: List<ChatMessage>, opts: RequestOptions): JsonObject = buildJsonObject {
        put("model", p.model)
        put("stream", true)
        if (opts.unrestricted) put("max_tokens", opts.maxTokens)
        if (opts.tools.isNotEmpty()) putJsonArray("tools") {
            for (t in opts.tools) addJsonObject {
                put("type", "function")
                putJsonObject("function") { put("name", t.name); put("description", t.description); put("parameters", t.schema) }
            }
        }
        putJsonArray("messages") {
            if (opts.system.isNotBlank()) addJsonObject { put("role", "system"); put("content", opts.system) }
            for (m in mergePlain(msgs)) addJsonObject {
                when (m.role) {
                    Role.USER -> { put("role", "user"); put("content", m.text) }
                    Role.TOOL -> { put("role", "tool"); put("tool_call_id", m.toolCallId ?: ""); put("content", m.text.ifEmpty { "(no output)" }) }
                    Role.ASSISTANT -> {
                        put("role", "assistant")
                        if (m.text.isNotBlank()) put("content", m.text) else put("content", JsonNull)
                        if (m.toolCalls.isNotEmpty()) putJsonArray("tool_calls") {
                            for (c in m.toolCalls) addJsonObject {
                                put("id", c.id); put("type", "function")
                                putJsonObject("function") { put("name", c.name); put("arguments", c.arguments) }
                            }
                        }
                    }
                }
            }
        }
    }.withExtras(opts)

    private fun openAiRequest(p: Provider, msgs: List<ChatMessage>, opts: RequestOptions): Request {
        val b = Request.Builder().url(p.baseUrl.trimEnd('/') + "/chat/completions").post(openAiBody(p, msgs, opts).toString().toRequestBody(jsonType))
        authHeaders(b, p)
        return b.build()
    }

    private class OpenAiAccumulator : TurnAccumulator {
        private val text = StringBuilder()
        private class Partial(var id: String = "", var name: String = "", val args: StringBuilder = StringBuilder())
        private val calls = sortedMapOf<Int, Partial>()
        private val images = mutableListOf<String>()
        private val thinking = StringBuilder()
        override fun takeThinking(): String? = thinking.takeIf { it.isNotEmpty() }?.toString()?.also { thinking.clear() }

        override fun accept(o: JsonObject): String? {
            o["error"]?.let { throw ApiException((it as? JsonObject)?.str("message") ?: it.toString()) }
            val choice = o["choices"]?.jsonArray?.firstOrNull()?.jsonObject ?: return null
            val delta = choice["delta"]?.jsonObject
            // Image output: [{"type":"image_url","image_url":{"url":"data:image/png;base64,..."}}]
            (delta?.get("images") as? JsonArray)?.forEach { el ->
                ((el as? JsonObject)?.get("image_url") as? JsonObject)?.str("url")?.let { images += it }
            }
            (delta?.get("tool_calls") as? JsonArray)?.forEach { el ->
                val tc = el.jsonObject
                val p = calls.getOrPut(tc.int("index", calls.size)) { Partial() }
                tc.str("id")?.let { if (it.isNotEmpty()) p.id = it }
                tc["function"]?.jsonObject?.let { f -> f.str("name")?.let { p.name += it }; f.str("arguments")?.let { p.args.append(it) } }
            }
            (delta?.str("reasoning_content") ?: delta?.str("reasoning"))?.let { thinking.append(it) }
            val t = delta?.str("content")
            if (!t.isNullOrEmpty()) text.append(t)
            if (choice.str("finish_reason") == "length") return "\n\n_[Stopped: reached the max output tokens limit.]_".also { text.append(it) }
            return t
        }

        override fun finish() = StreamEvent.TurnEnd(
            text.toString(),
            calls.values.filter { it.name.isNotEmpty() }.mapIndexed { i, c ->
                ToolCall(c.id.ifEmpty { "call_$i" }, c.name, c.args.toString().ifBlank { "{}" })
            },
            null,
            images.toList(),
        )
    }

    // --- shared ---

    /**
     * Drops failed/empty replies, and makes sure every tool call is answered right after it
     * (a stopped or denied run would otherwise leave an orphan call, which every API rejects).
     */
    internal fun normalize(history: List<ChatMessage>): List<ChatMessage> {
        val out = mutableListOf<ChatMessage>()
        var pending = listOf<ToolCall>()
        fun flushPending() {
            for (c in pending) out += ChatMessage(role = Role.TOOL, text = "Not run: the user stopped this step.", toolCallId = c.id, isError = true)
            pending = emptyList()
        }
        for (m in history) {
            if (m.role == Role.TOOL) {
                if (pending.any { it.id == m.toolCallId }) { out += m.withAttachmentNote(); pending = pending.filterNot { it.id == m.toolCallId } }
                continue
            }
            flushPending()
            if (m.role == Role.ASSISTANT && (m.isError || (m.text.isBlank() && m.toolCalls.isEmpty()))) continue
            if (m.role == Role.USER && m.text.isBlank()) continue
            out += m.withAttachmentNote()
            if (m.role == Role.ASSISTANT) pending = m.toolCalls
        }
        flushPending()
        return out
    }

    /** OpenAI-style APIs are fine with tool sequences but some reject back-to-back plain user (or assistant) turns. */
    private fun mergePlain(msgs: List<ChatMessage>): List<ChatMessage> {
        val out = mutableListOf<ChatMessage>()
        for (m in msgs) {
            val last = out.lastOrNull()
            val plain = m.role != Role.TOOL && m.toolCalls.isEmpty()
            if (last != null && plain && last.role == m.role && last.toolCalls.isEmpty()) out[out.lastIndex] = last.copy(text = last.text + "\n\n" + m.text)
            else out += m
        }
        return out
    }

    private fun textBlock(t: String) = buildJsonObject { put("type", "text"); put("text", t) }

    private fun parseArgs(raw: String): JsonObject =
        runCatching { json.parseToJsonElement(raw.ifBlank { "{}" }).jsonObject }.getOrElse { buildJsonObject { } }

    /** Extra body params apply only in unrestricted mode; they override built-in fields except model/stream/messages/tools. */
    private fun JsonObject.withExtras(opts: RequestOptions): JsonObject {
        val extra = opts.extraBody?.takeIf { opts.unrestricted } ?: return this
        val protected = setOf("model", "stream", "messages", "tools")
        return JsonObject(this + extra.filterKeys { it !in protected })
    }

    private fun checkTransport(req: Request, unrestricted: Boolean) {
        if (!req.isHttps && !unrestricted && !isLocalHost(req.url.host))
            throw ApiException("Plain http:// is only allowed for local servers. Use https:// or turn on Unrestricted mode.")
    }

    private fun authHeaders(b: Request.Builder, p: Provider) {
        when (p.kind) {
            ApiKind.ANTHROPIC -> {
                b.header("x-api-key", p.apiKey)
                b.header("anthropic-version", "2026-10-21")
            }
            ApiKind.OPENAI_COMPATIBLE -> {
                if (p.apiKey.isNotBlank()) b.header("Authorization", "Bearer ${p.apiKey}")
                if ("openrouter.ai" in p.baseUrl) b.header("X-Title", "Switchboard")
            }
        }
    }

    private fun errorText(resp: Response): String {
        val raw = resp.body?.string().orEmpty()
        val msg = runCatching {
            val o = json.parseToJsonElement(raw).jsonObject
            o["error"]?.let { e -> if (e is JsonObject) e.str("message") else e.jsonPrimitive.contentOrNull }
                ?: o.str("message")
        }.getOrNull()
        return "HTTP ${resp.code}: ${msg ?: raw.take(300).ifBlank { resp.message }}"
    }
}

/**
 * Files in a message (generated images, documents, audio) are described in its text, so every model, including one
 * switched in later, knows what's in the chat.
 */
fun ChatMessage.withAttachmentNote(): ChatMessage = if (attachments.isEmpty()) this else copy(
    text = (text.trim() + "\n\n" + attachments.joinToString("\n") { "[File shared in this chat: ${it.name} (${it.mime}, ${it.size / 1024} KB)]" }).trim(),
    attachments = emptyList(),
)

internal fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.contentOrNull
internal fun JsonObject.int(k: String, default: Int = 0): Int = (this[k] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: default
