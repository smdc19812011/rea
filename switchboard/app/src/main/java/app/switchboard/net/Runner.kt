package app.switchboard.net

import app.switchboard.data.ToolCall
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

/** Result handed back to the model. */
data class ToolResult(val text: String, val isError: Boolean = false, val attachments: List<app.switchboard.data.Attachment> = emptyList())

/** Somewhere tool actions run: the app's own sandbox, or runner.py in Termux / on a computer. */
interface ToolHost {
    /** One-line description of the machine for the model, or throws if unreachable. */
    suspend fun describe(): String
    suspend fun execute(call: ToolCall): ToolResult
    /** Copies a file from the host to [dest] on the phone (used by install_apk). */
    suspend fun fetchFile(path: String, dest: File)
}

/** Talks to runner.py (Termux or a computer). Holds no state besides the address and token. */
class Runner(private val baseUrl: String, private val token: String, private val unrestricted: Boolean) : ToolHost {
    override suspend fun describe(): String {
        val i = info()
        return "${i.str("os")}${if (i.str("termux") == "true") " (Termux)" else ""}, workspace ${i.str("workspace")}"
    }

    override suspend fun fetchFile(path: String, dest: File) { download(path, dest) }

    private val json = Json { ignoreUnknownKeys = true }
    private val jsonType = "application/json".toMediaType()
    private val http = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()

    suspend fun info(): JsonObject = getJson("/info")

    suspend fun exec(command: String, cwd: String?, timeoutSec: Int): JsonObject = run {
        val client = http.newBuilder().readTimeout(timeoutSec + 30L, TimeUnit.SECONDS).build()
        postJson("/exec", buildJsonObject { put("command", command); cwd?.let { put("cwd", it) }; put("timeout_sec", timeoutSec) }, client)
    }

    suspend fun write(path: String, content: String, encoding: String? = null): JsonObject = run {
        postJson("/write", buildJsonObject { put("path", path); put("content", content); encoding?.let { put("encoding", it) } })
    }

    suspend fun read(path: String): JsonObject = postJson("/read", buildJsonObject { put("path", path) })

    /** Streams a file from the runner into [dest]. */
    suspend fun download(path: String, dest: File) = run {
        val url = url("/download").newBuilder().addQueryParameter("path", path).build()
        val client = http.newBuilder().readTimeout(5, TimeUnit.MINUTES).build()
        client.newCall(Request.Builder().url(url).header("Authorization", "Bearer $token").build()).cancellable { r ->
            if (!r.isSuccessful) throw ApiException(errorOf(r.code, r.body?.string().orEmpty()))
            dest.parentFile?.mkdirs()
            dest.outputStream().use { out -> r.body!!.byteStream().copyTo(out) }
        }
    }

    private fun url(path: String) = (baseUrl.trimEnd('/') + path).toHttpUrlOrNull()
        ?.also { if (!it.isHttps && !unrestricted && !isLocalHost(it.host)) throw ApiException("Runner uses plain http:// on a non-local host. Use 127.0.0.1 (Termux), https://, or turn on Unrestricted mode.") }
        ?: throw ApiException("Runner URL is not valid: $baseUrl")

    private suspend fun getJson(path: String): JsonObject =
        http.newCall(Request.Builder().url(url(path)).header("Authorization", "Bearer $token").build()).cancellable { r ->
            val body = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw ApiException(errorOf(r.code, body))
            json.parseToJsonElement(body).jsonObject
        }

    private suspend fun postJson(path: String, body: JsonObject, client: OkHttpClient = http): JsonObject =
        client.newCall(Request.Builder().url(url(path)).header("Authorization", "Bearer $token").post(body.toString().toRequestBody(jsonType)).build())
            .cancellable { r ->
                val text = r.body?.string().orEmpty()
                if (!r.isSuccessful) throw ApiException(errorOf(r.code, text))
                json.parseToJsonElement(text).jsonObject
            }

    private fun errorOf(code: Int, body: String): String {
        val msg = runCatching { json.parseToJsonElement(body).jsonObject.str("error") }.getOrNull()
        return if (code == 401) "Runner rejected the token. Copy the token printed by runner.py into Settings > Tools."
        else "Runner HTTP $code: ${msg ?: body.take(200)}"
    }

    companion object {
        const val INSTALL_APK = "install_apk"
        const val SEND_FILE = "send_file"

        val tools: List<ToolSpec> = listOf(
            ToolSpec("run_command",
                "Run a shell command on the user's machine (Termux on their phone, or their computer) through the Switchboard runner. " +
                    "Use non-interactive flags (e.g. -y). Output is stdout+stderr combined; long output keeps only the tail.",
                schema(required = listOf("command")) {
                    putJsonObject("command") { put("type", "string"); put("description", "Shell command (bash -lc when available).") }
                    putJsonObject("cwd") { put("type", "string"); put("description", "Working directory; relative paths are inside the runner workspace.") }
                    putJsonObject("timeout_sec") { put("type", "integer"); put("description", "Default 600, max 3600. Builds and installs may need more.") }
                }),
            ToolSpec("write_file", "Create or overwrite a file on the tool machine (text, or any binary file via base64). Relative paths are inside the workspace.",
                schema(required = listOf("path", "content")) {
                    putJsonObject("path") { put("type", "string") }
                    putJsonObject("content") { put("type", "string"); put("description", "File content: plain text, or base64 when encoding is base64") }
                    putJsonObject("encoding") { put("type", "string"); put("description", "text (default) or base64 for binary files (images, audio, archives, ...)") }
                }),
            ToolSpec("read_file", "Read a text file from the runner machine (first 100k characters).",
                schema(required = listOf("path")) { putJsonObject("path") { put("type", "string") } }),
            ToolSpec(INSTALL_APK,
                "Copy an .apk built on the runner machine to the user's phone and open Android's installer. The user confirms the install.",
                schema(required = listOf("path")) { putJsonObject("path") { put("type", "string"); put("description", "Path to the .apk on the runner machine.") } }),
            ToolSpec(SEND_FILE,
                "Send any file (APK, zip, image, PDF, document, anything) from the tool machine into this chat, where the user can open, save or share it. " +
                    "Use it whenever the user asks you to send, give, share or attach a file. Up to 200 MB.",
                schema(required = listOf("path")) {
                    putJsonObject("path") { put("type", "string"); put("description", "Path to the file on the tool machine.") }
                    putJsonObject("caption") { put("type", "string"); put("description", "Optional short note shown with the file.") }
                }),
        )

        private fun schema(required: List<String>, props: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) = buildJsonObject {
            put("type", "object")
            putJsonObject("properties", props)
            put("required", kotlinx.serialization.json.JsonArray(required.map { kotlinx.serialization.json.JsonPrimitive(it) }))
        }

        /** Short human-readable summary of a call for the approval card. */
        fun describe(call: ToolCall): String {
            val a = runCatching { Json.parseToJsonElement(call.arguments).jsonObject }.getOrNull()
            return when (call.name) {
                "run_command" -> a?.str("command") ?: call.arguments
                "write_file", "read_file", INSTALL_APK, SEND_FILE -> a?.str("path") ?: call.arguments
                else -> call.arguments
            }
        }
    }

    /** Executes a runner-side tool. [INSTALL_APK] is handled by the caller (needs Android APIs). */
    override suspend fun execute(call: ToolCall): ToolResult {
        val a = runCatching { json.parseToJsonElement(call.arguments).jsonObject }.getOrElse { return ToolResult("Arguments were not valid JSON: ${call.arguments}", true) }
        return runCatching {
            when (call.name) {
                "run_command" -> {
                    val cmd = a.str("command") ?: return ToolResult("Missing 'command'.", true)
                    val r = exec(cmd, a.str("cwd"), (a.int("timeout_sec", 600)).coerceIn(1, 3600))
                    val exit = r.str("exit_code")
                    val head = when {
                        r.str("timed_out") == "true" -> "Timed out (process killed)."
                        else -> "Exit code $exit."
                    } + if (r.str("truncated") == "true") " Output truncated to the last 20,000 characters." else ""
                    ToolResult("$head\n${r.str("output").orEmpty()}".trimEnd(), isError = exit != "0")
                }
                "write_file" -> {
                    val r = write(a.str("path") ?: return ToolResult("Missing 'path'.", true), a.str("content").orEmpty(), a.str("encoding"))
                    ToolResult("Wrote ${r.str("bytes")} bytes to ${r.str("path")}.")
                }
                "read_file" -> {
                    val r = read(a.str("path") ?: return ToolResult("Missing 'path'.", true))
                    ToolResult(r.str("content").orEmpty() + if (r.str("truncated") == "true") "\n[truncated]" else "")
                }
                else -> ToolResult("Unknown tool: ${call.name}", true)
            }
        }.getOrElse { ToolResult(it.message ?: it.javaClass.simpleName, true) }
    }
}
