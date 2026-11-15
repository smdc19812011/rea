package app.switchboard.net

import app.switchboard.data.ToolCall
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Runs tools inside Switchboard's own app sandbox with Android's /system/bin/sh (toybox).
 * No setup, but Android gives apps no package manager or compilers, so it can't build APKs.
 */
class PhoneHost(private val workspace: File, private val shell: String = "/system/bin/sh") : ToolHost {
    init { workspace.mkdirs() }

    override suspend fun describe() = "Android ${android.os.Build.VERSION.RELEASE} app sandbox, /system/bin/sh (toybox), workspace ${workspace.path}"

    private fun resolve(path: String?): File {
        val p = path.orEmpty().ifBlank { "." }
        return (if (p.startsWith("/")) File(p) else File(workspace, p)).normalize()
    }

    override suspend fun fetchFile(path: String, dest: File) = withContext(Dispatchers.IO) {
        val src = resolve(path)
        if (!src.isFile) throw ApiException("No such file: $src")
        dest.parentFile?.mkdirs(); src.copyTo(dest, overwrite = true); Unit
    }

    override suspend fun execute(call: ToolCall): ToolResult {
        val a = runCatching { Json.parseToJsonElement(call.arguments).jsonObject }.getOrElse { return ToolResult("Arguments were not valid JSON: ${call.arguments}", true) }
        return runCatching {
            when (call.name) {
                "run_command" -> run(a.str("command") ?: return ToolResult("Missing 'command'.", true), resolve(a.str("cwd")), a.int("timeout_sec", 600).coerceIn(1, 3600))
                "write_file" -> withContext(Dispatchers.IO) {
                    val f = resolve(a.str("path") ?: return@withContext ToolResult("Missing 'path'.", true))
                    f.parentFile?.mkdirs(); val bytes = a.str("content").orEmpty().toByteArray(); f.writeBytes(bytes)
                    ToolResult("Wrote ${bytes.size} bytes to $f.")
                }
                "read_file" -> withContext(Dispatchers.IO) {
                    val f = resolve(a.str("path") ?: return@withContext ToolResult("Missing 'path'.", true))
                    if (!f.isFile) ToolResult("No such file: $f", true)
                    else f.readText().let { ToolResult(it.take(100_000) + if (it.length > 100_000) "\n[truncated]" else "") }
                }
                else -> ToolResult("Unknown tool: ${call.name}", true)
            }
        }.getOrElse { ToolResult(it.message ?: it.javaClass.simpleName, true) }
    }

    private suspend fun run(command: String, cwd: File, timeoutSec: Int): ToolResult = runInterruptible(Dispatchers.IO) {
        cwd.mkdirs()
        val proc = ProcessBuilder(shell, "-c", command).directory(cwd).redirectErrorStream(true)
            .apply { environment()["HOME"] = workspace.path; environment()["TMPDIR"] = workspace.path }
            .start()
        proc.outputStream.close()
        val out = StringBuilder()
        val reader = Thread { proc.inputStream.bufferedReader().forEachLine { synchronized(out) { out.appendLine(it) } } }.apply { isDaemon = true; start() }
        try {
            val finished = proc.waitFor(timeoutSec.toLong(), TimeUnit.SECONDS)
            if (!finished) proc.destroyForcibly()
            reader.join(2000)
            val text = synchronized(out) { out.toString() }.trimEnd()
            val tail = text.takeLast(20_000)
            val head = (if (finished) "Exit code ${proc.exitValue()}." else "Timed out (process killed).") +
                if (text.length > tail.length) " Output truncated to the last 20,000 characters." else ""
            ToolResult("$head\n$tail".trimEnd(), isError = !finished || proc.exitValue() != 0)
        } catch (e: InterruptedException) {
            proc.destroyForcibly(); throw e // user pressed Stop
        }
    }
}
