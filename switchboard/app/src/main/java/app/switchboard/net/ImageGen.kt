package app.switchboard.net

import app.switchboard.data.ImageMethod
import app.switchboard.data.Provider
import kotlinx.coroutines.Dispatchers
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
    val extension get() = when (mime) { "image/jpeg" -> "jpg"; "image/webp" -> "webp"; "image/gif" -> "gif"; else -> "png" }
}

/** Calls an image model: OpenRouter-style chat completions with image output, or the OpenAI-style /images/generations API. */
class ImageGen {
    private val http = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(5, TimeUnit.MINUTES).build()
    private val json = Json { ignoreUnknownKeys = true }
    private val jsonType = "application/json".toMediaType()

    companion object {
        fun defaultModel(p: Provider) = when {
            "openrouter.ai" in p.baseUrl -> "google/gemini-2.5-flash-image"
            "api.openai.com" in p.baseUrl -> "gpt-image-1"
            else -> ""
        }
        fun methodFor(p: Provider, m: ImageMethod) = if (m != ImageMethod.AUTO) m else if ("openrouter.ai" in p.baseUrl) ImageMethod.CHAT else ImageMethod.IMAGES_API

        /** data:image/png;base64,... -> bytes. */
        fun decodeDataUrl(url: String): GeneratedFile? {
            if (!url.startsWith("data:")) return null
            val mime = url.substringAfter("data:").substringBefore(';').ifBlank { "image/png" }
            return GeneratedFile(Base64.getMimeDecoder().decode(url.substringAfter(",")), mime)
        }
    }

    suspend fun generate(p: Provider, model: String, method: ImageMethod, prompt: String, size: String, unrestricted: Boolean): Pair<List<GeneratedFile>, String> =
        withContext(Dispatchers.IO) {
            val m = model.ifBlank { defaultModel(p) }.ifBlank { throw ApiException("Choose an image model in Settings > File generation.") }
            when (methodFor(p, method)) {
                ImageMethod.CHAT -> viaChat(p, m, prompt, unrestricted)
                else -> viaImagesApi(p, m, prompt, size, unrestricted)
            }
        }

    private fun viaChat(p: Provider, model: String, prompt: String, unrestricted: Boolean): Pair<List<GeneratedFile>, String> {
        val body = buildJsonObject {
            put("model", model); put("stream", false)
            putJsonArray("modalities") { add(JsonPrimitive("image")); add(JsonPrimitive("text")) }
            putJsonArray("messages") { add(buildJsonObject { put("role", "user"); put("content", prompt) }) }
        }
        val o = post(p, "/chat/completions", body, unrestricted)
        val msg = (o["choices"] as? JsonArray)?.firstOrNull()?.jsonObject?.get("message") as? JsonObject
            ?: throw ApiException("The image model returned no message.")
        val files = (msg["images"] as? JsonArray).orEmpty().mapNotNull { el ->
            ((el as? JsonObject)?.get("image_url") as? JsonObject)?.str("url")?.let(::fetch)
        }
        val text = msg.str("content").orEmpty()
        if (files.isEmpty()) throw ApiException("$model returned no image${if (text.isNotBlank()) ": ${text.take(300)}" else ". Pick a model that can output images."}")
        return files to text
    }

    private fun viaImagesApi(p: Provider, model: String, prompt: String, size: String, unrestricted: Boolean): Pair<List<GeneratedFile>, String> {
        val body = buildJsonObject { put("model", model); put("prompt", prompt); put("n", 1); if (size.isNotBlank()) put("size", size) }
        val o = post(p, "/images/generations", body, unrestricted)
        val files = (o["data"] as? JsonArray).orEmpty().mapNotNull { el ->
            val d = el as? JsonObject ?: return@mapNotNull null
            d.str("b64_json")?.let { GeneratedFile(Base64.getMimeDecoder().decode(it), "image/png") } ?: d.str("url")?.let(::fetch)
        }
        if (files.isEmpty()) throw ApiException("$model returned no image.")
        return files to ((o["data"] as? JsonArray)?.firstOrNull() as? JsonObject)?.str("revised_prompt").orEmpty()
    }

    private fun fetch(url: String): GeneratedFile? = decodeDataUrl(url) ?: runCatching {
        http.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) null else GeneratedFile(r.body!!.bytes(), r.header("Content-Type")?.substringBefore(';') ?: "image/png")
        }
    }.getOrNull()

    private fun post(p: Provider, path: String, body: JsonObject, unrestricted: Boolean): JsonObject {
        val url = (p.baseUrl.trimEnd('/') + path).toHttpUrlOrNull() ?: throw ApiException("Invalid provider URL ${p.baseUrl}")
        if (!url.isHttps && !unrestricted && !isLocalHost(url.host)) throw ApiException("Plain http:// to the internet needs Unrestricted mode.")
        val req = Request.Builder().url(url).post(body.toString().toRequestBody(jsonType))
            .apply { if (p.apiKey.isNotBlank()) header("Authorization", "Bearer ${p.apiKey}"); if ("openrouter.ai" in p.baseUrl) header("X-Title", "Switchboard") }
            .build()
        http.newCall(req).execute().use { r ->
            val text = r.body?.string().orEmpty()
            val o = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
            if (!r.isSuccessful || o == null) {
                val err = (o?.get("error") as? JsonObject)?.str("message") ?: text.take(300)
                throw ApiException("Image request failed (HTTP ${r.code}): $err", r.code)
            }
            (o["error"] as? JsonObject)?.let { throw ApiException("Image request failed: ${it.str("message") ?: it}") }
            return o
        }
    }
}
