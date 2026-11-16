package app.switchboard.net

import app.switchboard.data.Provider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Base64
import java.util.concurrent.TimeUnit

/** A generated file: raw bytes plus MIME type. */
class GeneratedFile(val bytes: ByteArray, val mime: String) {
    val extension get() = when (mime) {
        "image/jpeg" -> "jpg"; "image/webp" -> "webp"; "image/gif" -> "gif"; "image/png" -> "png"
        "audio/mpeg" -> "mp3"; "audio/wav", "audio/x-wav" -> "wav"; "audio/ogg" -> "ogg"; "audio/aac" -> "aac"; "audio/flac" -> "flac"
        "video/mp4" -> "mp4"; "video/webm" -> "webm"
        else -> mime.substringAfter('/').substringBefore('+').ifBlank { "bin" }
    }
}

/**
 * Asks one model on an OpenAI-compatible provider for an image, audio or video. Each call tries the request styles
 * that provider family uses; the caller walks the priority list when a model can't do it.
 */
class MediaGen {
    private val http = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(5, TimeUnit.MINUTES).build()
    private val json = Json { ignoreUnknownKeys = true }
    private val jsonType = "application/json".toMediaType()

    companion object {
        /** data:image/png;base64,... -> bytes. */
        fun decodeDataUrl(url: String): GeneratedFile? {
            if (!url.startsWith("data:")) return null
            val mime = url.substringAfter("data:").substringBefore(';').ifBlank { "image/png" }
            return GeneratedFile(Base64.getMimeDecoder().decode(url.substringAfter(",")), mime)
        }
    }

    /** Images: chat completions with image output (OpenRouter style), then the /images/generations API (OpenAI style). */
    suspend fun image(p: Provider, model: String, prompt: String, unrestricted: Boolean): Pair<List<GeneratedFile>, String> = withContext(Dispatchers.IO) {
        val first = runCatching { imageViaChat(p, model, prompt, unrestricted) }
        first.getOrNull()?.let { return@withContext it }
        val second = runCatching { imageViaApi(p, model, prompt, unrestricted) }
        second.getOrNull() ?: throw ApiException("$model couldn't make an image (${first.exceptionOrNull()?.message}; ${second.exceptionOrNull()?.message})")
    }

    private suspend fun imageViaChat(p: Provider, model: String, prompt: String, unrestricted: Boolean): Pair<List<GeneratedFile>, String> {
        val o = post(p, "/chat/completions", buildJsonObject {
            put("model", model); put("stream", false)
            putJsonArray("modalities") { add(JsonPrimitive("image")); add(JsonPrimitive("text")) }
            putJsonArray("messages") { add(buildJsonObject { put("role", "user"); put("content", prompt) }) }
        }, unrestricted)
        val msg = ((o["choices"] as? JsonArray)?.firstOrNull() as? JsonObject)?.get("message") as? JsonObject ?: throw ApiException("no message")
        val files = (msg["images"] as? JsonArray).orEmpty().mapNotNull { ((it as? JsonObject)?.get("image_url") as? JsonObject)?.str("url")?.let { fetch(it) } }
        if (files.isEmpty()) throw ApiException("no image in the reply")
        return files to msg.str("content").orEmpty()
    }

    private suspend fun imageViaApi(p: Provider, model: String, prompt: String, unrestricted: Boolean): Pair<List<GeneratedFile>, String> {
        val o = post(p, "/images/generations", buildJsonObject { put("model", model); put("prompt", prompt); put("n", 1) }, unrestricted)
        val data = (o["data"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
        val files = data.mapNotNull { d -> d.str("b64_json")?.let { GeneratedFile(Base64.getMimeDecoder().decode(it), "image/png") } ?: d.str("url")?.let { fetch(it) } }
        if (files.isEmpty()) throw ApiException("no image returned")
        return files to data.firstOrNull()?.str("revised_prompt").orEmpty()
    }

    /** Speech/audio: /audio/speech (OpenAI style), then chat completions with audio output. */
    suspend fun audio(p: Provider, model: String, text: String, voice: String, unrestricted: Boolean): GeneratedFile = withContext(Dispatchers.IO) {
        val first = runCatching {
            val bytes = postRaw(p, "/audio/speech", buildJsonObject { put("model", model); put("input", text); put("voice", voice); put("response_format", "mp3") }, unrestricted)
            GeneratedFile(bytes, "audio/mpeg")
        }
        first.getOrNull()?.let { return@withContext it }
        val second = runCatching {
            val o = post(p, "/chat/completions", buildJsonObject {
                put("model", model); put("stream", false)
                putJsonArray("modalities") { add(JsonPrimitive("text")); add(JsonPrimitive("audio")) }
                putJsonObject("audio") { put("voice", voice); put("format", "wav") }
                putJsonArray("messages") { add(buildJsonObject { put("role", "user"); put("content", "Say this exactly, with natural expression:\n$text") }) }
            }, unrestricted)
            val audio = (((o["choices"] as? JsonArray)?.firstOrNull() as? JsonObject)?.get("message") as? JsonObject)?.get("audio") as? JsonObject
            GeneratedFile(Base64.getMimeDecoder().decode(audio?.str("data") ?: throw ApiException("no audio in the reply")), "audio/wav")
        }
        second.getOrNull() ?: throw ApiException("$model couldn't make audio (${first.exceptionOrNull()?.message}; ${second.exceptionOrNull()?.message})")
    }

    /** Video: submit a job to /videos, poll until it finishes (up to 15 min), then download the result. */
    suspend fun video(p: Provider, model: String, prompt: String, unrestricted: Boolean, onStatus: (String) -> Unit): GeneratedFile = withContext(Dispatchers.IO) {
        val job = post(p, "/videos", buildJsonObject { put("model", model); put("prompt", prompt) }, unrestricted)
        val id = job.str("id") ?: throw ApiException("$model didn't start a video job")
        val deadline = System.currentTimeMillis() + 15 * 60_000
        while (true) {
            val st = get(p, "/videos/$id", unrestricted)
            val status = st.str("status").orEmpty()
            onStatus("Video ${st.str("progress")?.let { "$it% " } ?: ""}$status")
            when (status) {
                "completed", "succeeded", "success" -> break
                "failed", "error", "cancelled" -> throw ApiException("Video failed: ${(st["error"] as? JsonObject)?.str("message") ?: status}")
            }
            if (System.currentTimeMillis() > deadline) throw ApiException("The video took longer than 15 minutes.")
            delay(5_000)
        }
        GeneratedFile(getRaw(p, "/videos/$id/content", unrestricted), "video/mp4")
    }

    // ---------- HTTP ----------

    private suspend fun fetch(url: String): GeneratedFile? = decodeDataUrl(url) ?: runCatching {
        http.newCall(Request.Builder().url(url).build()).cancellable { r ->
            if (!r.isSuccessful) null else GeneratedFile(r.body!!.bytes(), r.header("Content-Type")?.substringBefore(';') ?: "image/png")
        }
    }.getOrNull()

    private fun request(p: Provider, path: String, unrestricted: Boolean): Request.Builder {
        val url = (p.baseUrl.trimEnd('/') + path).toHttpUrlOrNull() ?: throw ApiException("Invalid provider URL ${p.baseUrl}")
        if (!url.isHttps && !unrestricted && !isLocalHost(url.host)) throw ApiException("Plain http:// to the internet needs Unrestricted mode.")
        return Request.Builder().url(url).apply {
            if (p.apiKey.isNotBlank()) header("Authorization", "Bearer ${p.apiKey}")
            if ("openrouter.ai" in p.baseUrl) header("X-Title", "Switchboard")
        }
    }

    private fun failure(code: Int, text: String): ApiException {
        val o = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
        return ApiException("HTTP $code: ${(o?.get("error") as? JsonObject)?.str("message") ?: o?.str("error") ?: text.take(300)}", code)
    }

    private suspend fun post(p: Provider, path: String, body: JsonObject, unrestricted: Boolean): JsonObject =
        http.newCall(request(p, path, unrestricted).post(body.toString().toRequestBody(jsonType)).build()).cancellable { r ->
            val text = r.body?.string().orEmpty()
            val o = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
            if (!r.isSuccessful || o == null) throw failure(r.code, text)
            (o["error"] as? JsonObject)?.let { throw ApiException(it.str("message") ?: it.toString()) }
            o
        }

    private suspend fun postRaw(p: Provider, path: String, body: JsonObject, unrestricted: Boolean): ByteArray =
        http.newCall(request(p, path, unrestricted).post(body.toString().toRequestBody(jsonType)).build()).cancellable { r ->
            if (!r.isSuccessful) throw failure(r.code, r.body?.string().orEmpty())
            if (r.header("Content-Type").orEmpty().contains("json")) throw ApiException("expected audio, got JSON")
            r.body!!.bytes()
        }

    private suspend fun get(p: Provider, path: String, unrestricted: Boolean): JsonObject =
        http.newCall(request(p, path, unrestricted).build()).cancellable { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw failure(r.code, text)
            json.parseToJsonElement(text).jsonObject
        }

    private suspend fun getRaw(p: Provider, path: String, unrestricted: Boolean): ByteArray =
        http.newCall(request(p, path, unrestricted).build()).cancellable { r ->
            if (!r.isSuccessful) throw failure(r.code, r.body?.string().orEmpty())
            r.body!!.bytes()
        }
}
