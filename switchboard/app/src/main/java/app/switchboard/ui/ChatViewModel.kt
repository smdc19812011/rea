package app.switchboard.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.switchboard.data.AppSettings
import app.switchboard.data.ChatMessage
import app.switchboard.data.Conversation
import app.switchboard.data.Provider
import app.switchboard.data.Role
import app.switchboard.data.Store
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import app.switchboard.data.ToolCall
import app.switchboard.data.Attachment
import android.webkit.MimeTypeMap
import app.switchboard.net.ChatClient
import app.switchboard.net.isRetryable
import app.switchboard.WorkService
import app.switchboard.net.RequestOptions
import app.switchboard.net.Runner
import app.switchboard.net.PhoneHost
import app.switchboard.net.Termux
import app.switchboard.net.ToolHost
import app.switchboard.net.DeviceManager
import app.switchboard.data.RunnerMode
import app.switchboard.data.DailyReset
import app.switchboard.data.RateLimiter
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay
import app.switchboard.net.StreamEvent
import app.switchboard.net.ToolResult
import app.switchboard.net.str
import kotlinx.coroutines.CompletableDeferred
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ChatViewModel(app: Application) : AndroidViewModel(app) {
    private val store = Store(app)
    private val limiter = RateLimiter(File(app.filesDir, "request_times.csv"))
    private val client = ChatClient()

    var settings by mutableStateOf(AppSettings()); private set
    var conversations by mutableStateOf(listOf<Conversation>()); private set
    var current by mutableStateOf(Conversation()); private set
    var streaming by mutableStateOf(false); private set
    var loaded by mutableStateOf(false); private set

    private var job: Job? = null

    enum class Approval { ONCE, ALWAYS, DENY }

    /** The tool call currently waiting for the user's Approve/Deny, if any. */
    var pendingApproval by mutableStateOf<ToolCall?>(null); private set
    /** Short progress note shown above the composer (e.g. starting the Termux runner). */
    var toolStatus by mutableStateOf<String?>(null); private set
    private var approvalDecision: CompletableDeferred<Approval>? = null
    /** Chats where the user picked "Always allow" (cleared when the app restarts). */
    private val alwaysAllowed = mutableSetOf<String>()

    /** Android runtime permissions the device tools are waiting for; MainActivity shows the system dialog. */
    var permissionRequest by mutableStateOf<Array<String>?>(null); private set
    private var permissionResult: CompletableDeferred<Boolean>? = null
    fun onPermissionsResult(granted: Boolean) { permissionRequest = null; permissionResult?.complete(granted) }

    private val devices = DeviceManager(app) { perms ->
        val d = CompletableDeferred<Boolean>()
        permissionResult = d
        permissionRequest = perms
        d.await()
    }

    override fun onCleared() { devices.closeAll(); super.onCleared() }

    val activeProvider: Provider?
        get() = settings.providers.firstOrNull { it.id == settings.activeProviderId } ?: settings.providers.firstOrNull()

    init {
        viewModelScope.launch {
            val (s, c) = withContext(Dispatchers.IO) { store.loadSettings() to store.loadConversations() }
            settings = s; conversations = c; loaded = true
        }
    }

    // --- conversations ---

    fun newChat() { stop(); current = Conversation() }

    fun open(id: String) { stop(); conversations.firstOrNull { it.id == id }?.let { current = it } }

    fun delete(id: String) {
        conversations = conversations.filterNot { it.id == id }
        if (current.id == id) current = Conversation()
        io { store.delete(id); attachmentsDir(id).deleteRecursively() }
    }

    fun send(text: String) {
        val provider = activeProvider ?: return
        val trimmed = text.trim()
        if (trimmed.isEmpty() || streaming) return
        val title = if (current.messages.isEmpty()) trimmed.lineSequence().first().take(48) else current.title
        current = current.copy(title = title, messages = current.messages + ChatMessage(role = Role.USER, text = trimmed))
        respond(provider)
    }

    /** Re-run the last reply, optionally on whichever provider/model is active now. */
    fun regenerate() {
        val provider = activeProvider ?: return
        if (streaming) return
        val msgs = current.messages
        val last = msgs.lastOrNull() ?: return
        if (last.role != Role.ASSISTANT) return
        // A failed step (e.g. dropped connection) is retried on its own, keeping the tool work done so far.
        if (last.isError) {
            current = current.copy(messages = msgs.dropLast(1))
            respond(provider)
            return
        }
        // Re-run the whole last answer, including any tool steps, from the last user message.
        val lastUser = msgs.indexOfLast { it.role == Role.USER }
        if (lastUser < 0) return
        current = current.copy(messages = msgs.take(lastUser + 1))
        respond(provider)
    }

    fun stop() { job?.cancel() }

    private fun respond(provider: Provider) {
        streaming = true
        WorkService.start(getApplication(), "Replying with ${provider.model}")
        job = viewModelScope.launch {
            try {
                val opts = buildOptions()
                var steps = 0
                while (true) {
                    val calls = streamTurn(provider, opts) ?: break
                    if (calls.isEmpty()) break
                    if (++steps > MAX_TOOL_STEPS) {
                        current = current.copy(messages = current.messages + ChatMessage(role = Role.ASSISTANT,
                            text = "Stopped after $MAX_TOOL_STEPS tool steps. Send a message to let it continue.", isError = true))
                        break
                    }
                    for (call in calls) {
                        val r = runTool(call)
                        current = current.copy(messages = current.messages + ChatMessage(role = Role.TOOL, text = r.text, toolCallId = call.id, isError = r.isError, attachments = r.attachments))
                        persistCurrent()
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) { // e.g. invalid extra-body JSON
                current = current.copy(messages = current.messages + ChatMessage(role = Role.ASSISTANT, text = "⚠ ${e.message}", isError = true))
            } finally {
                streaming = false
                pendingApproval = null
                WorkService.stop(getApplication())
                persistCurrent()
            }
        }
    }

    /** Streams one model turn into a new assistant message. Returns its tool calls, or null if it failed/was stopped. */
    val zone: ZoneId get() = runCatching { if (settings.timeZone.isBlank()) null else ZoneId.of(settings.timeZone) }.getOrNull() ?: ZoneId.systemDefault()

    private fun limits() = RateLimiter.Limits(settings.maxPerMinute, settings.maxPerDay, settings.dailyReset, zone)

    fun usage(): RateLimiter.Usage = limiter.usage(limits())

    /**
     * Waits until the shared per-minute/per-day limits allow another request, then counts it.
     * Re-checks every second, so changing the limits in Settings takes effect right away. Stop cancels the wait.
     */
    private suspend fun awaitRateLimit() {
        try {
            while (true) {
                val w = limiter.tryAcquire(limits()) ?: return
                val left = w.untilMs - System.currentTimeMillis()
                toolStatus = when (w.scope) {
                    RateLimiter.Scope.MINUTE -> "Limit of ${settings.maxPerMinute} requests per minute reached. Sending in ${(left + 999) / 1000} s…"
                    RateLimiter.Scope.DAY -> {
                        val at = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(w.untilMs), zone)
                        "Daily limit of ${settings.maxPerDay} requests reached. Sending at ${at.format(DateTimeFormatter.ofPattern("MMM d, h:mm a"))} (${zone.id}). Keep the app open."
                    }
                }
                delay(left.coerceIn(200, 1000))
            }
        } finally { toolStatus = null }
    }

    /** One model turn, retried automatically on dropped connections and overloaded servers. */
    private suspend fun streamTurn(provider: Provider, opts: RequestOptions): List<ToolCall>? {
        val backoff = listOf(3, 10, 30) // seconds before retry 1, 2, 3
        for (attempt in 0..backoff.size) {
            when (val r = attemptTurn(provider, opts, finalAttempt = attempt == backoff.size)) {
                is TurnOutcome.Done -> return r.calls
                is TurnOutcome.Failed -> return null
                is TurnOutcome.Retry -> try {
                    for (left in backoff[attempt] downTo 1) {
                        toolStatus = "Connection problem (${r.reason}). Retrying in $left s… (try ${attempt + 2} of ${backoff.size + 1})"
                        delay(1000)
                    }
                } finally { toolStatus = null }
            }
        }
        return null
    }

    private sealed interface TurnOutcome {
        data class Done(val calls: List<ToolCall>) : TurnOutcome
        data object Failed : TurnOutcome
        data class Retry(val reason: String) : TurnOutcome
    }

    private suspend fun attemptTurn(provider: Provider, opts: RequestOptions, finalAttempt: Boolean): TurnOutcome {
        awaitRateLimit()
        val history = current.messages
        val reply = ChatMessage(role = Role.ASSISTANT, text = "", source = "${provider.name} · ${provider.model}")
        current = current.copy(messages = history + reply)
        val buf = StringBuilder()
        try {
            var calls: List<ToolCall> = emptyList()
            client.stream(provider, history, opts).collect { ev ->
                when (ev) {
                    is StreamEvent.TextDelta -> { buf.append(ev.text); updateReply(reply.id) { it.copy(text = buf.toString()) } }
                    is StreamEvent.TurnEnd -> {
                        calls = ev.toolCalls
                        updateReply(reply.id) { it.copy(text = ev.text, toolCalls = ev.toolCalls, anthropicBlocks = ev.anthropicBlocks) }
                    }
                }
            }
            if (buf.isEmpty() && calls.isEmpty()) { updateReply(reply.id) { it.copy(text = "(empty response)", isError = true) }; return TurnOutcome.Failed }
            return TurnOutcome.Done(calls)
        } catch (e: kotlinx.coroutines.CancellationException) {
            if (buf.isEmpty()) updateReply(reply.id) { it.copy(text = "(stopped)", isError = true) }
            throw e
        } catch (e: Exception) {
            val msg = e.message ?: e.javaClass.simpleName
            if (!finalAttempt && isRetryable(e)) {
                // Drop the partial reply; the whole turn is requested again.
                current = current.copy(messages = current.messages.filterNot { it.id == reply.id })
                return TurnOutcome.Retry(msg)
            }
            val hint = if (isRetryable(e)) "\n\nTap ↻ to try this step again." else ""
            updateReply(reply.id) { it.copy(text = (buf.toString() + "\n\n⚠ $msg$hint").trim(), isError = true) }
            return TurnOutcome.Failed
        }
    }

    fun decide(a: Approval) { approvalDecision?.complete(a) }

    private suspend fun runTool(call: ToolCall): ToolResult {
        // Sending a file only copies it into the chat, so it never needs approval.
        if (call.name == Runner.SEND_FILE) return sendFile(call)
        if (settings.askBeforeRun && current.id !in alwaysAllowed && call.name !in DeviceManager.noApproval) {
            val d = CompletableDeferred<Approval>()
            approvalDecision = d
            pendingApproval = call
            val decision = try { d.await() } finally { pendingApproval = null; approvalDecision = null }
            when (decision) {
                Approval.DENY -> return ToolResult("The user denied this action. Ask them what to do instead.", isError = true)
                Approval.ALWAYS -> alwaysAllowed += current.id
                Approval.ONCE -> Unit
            }
        }
        return when (call.name) {
            Runner.INSTALL_APK -> installApk(call)
            in DeviceManager.names -> devices.execute(call, File(getApplication<Application>().filesDir, "workspace"), settings.unrestricted)
            else -> host().execute(call)
        }
    }

    private fun attachmentsDir(conversationId: String) = File(getApplication<Application>().filesDir, "attachments/$conversationId")

    /** Copies a file from the tool machine into the chat as an attachment the user can open, save or share. */
    private suspend fun sendFile(call: ToolCall): ToolResult {
        val args = runCatching { Json.parseToJsonElement(call.arguments).jsonObject }.getOrNull()
        val path = args?.str("path")?.takeIf { it.isNotBlank() } ?: return ToolResult("Missing 'path'.", true)
        val name = File(path).name.ifBlank { "file" }
        val dest = File(attachmentsDir(current.id), "${java.util.UUID.randomUUID().toString().take(8)}-$name")
        runCatching { host().fetchFile(path, dest) }.onFailure { dest.delete(); return ToolResult("Couldn't send $path: ${it.message}", true) }
        if (dest.length() > MAX_ATTACHMENT) { dest.delete(); return ToolResult("$name is larger than 200 MB; compress or split it first.", true) }
        val ext = name.substringAfterLast('.', "").lowercase()
        val mime = when (ext) { "apk" -> "application/vnd.android.package-archive"; else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream" }
        val att = Attachment(name, dest.path, dest.length(), mime)
        val caption = args.str("caption")?.let { " Caption: $it" } ?: ""
        return ToolResult("Sent $name (${dest.length()} bytes) to the chat. The user can open, save or share it.$caption", attachments = listOf(att))
    }

    private fun host(): ToolHost = when (settings.runnerMode) {
        RunnerMode.PHONE -> PhoneHost(File(getApplication<Application>().filesDir, "workspace"))
        RunnerMode.TERMUX -> Runner(Termux.URL, termuxToken(), settings.unrestricted)
        RunnerMode.REMOTE -> Runner(settings.runnerUrl, settings.runnerToken, settings.unrestricted)
    }

    private fun termuxToken(): String {
        if (settings.termuxToken.isEmpty()) {
            val token = java.security.SecureRandom().let { r -> ByteArray(18).also(r::nextBytes) }
                .let { android.util.Base64.encodeToString(it, android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING) }
            updateSettings { it.copy(termuxToken = token) }
        }
        return settings.termuxToken
    }

    /** Termux mode: if the runner isn't answering, start it through Termux and wait for it (first start installs python). */
    private suspend fun describeHost(): String {
        val h = host()
        if (settings.runnerMode != RunnerMode.TERMUX) return h.describe()
        runCatching { return h.describe() }
        toolStatus = "Starting the runner in Termux…"
        try {
            Termux.startRunner(getApplication(), termuxToken())
            repeat(90) {
                delay(1000)
                runCatching { return h.describe() }
            }
            throw IllegalStateException("Termux didn't start the runner within 90 s. Open Termux to see what happened.")
        } finally { toolStatus = null }
    }

    /** Settings button: start (or restart) the Termux runner now. */
    suspend fun startTermuxRunner(): Result<String> = runCatching {
        Termux.startRunner(getApplication(), termuxToken())
        val h = host()
        repeat(90) { delay(1000); runCatching { return@runCatching "Runner started: ${h.describe()}" } }
        error("No answer after 90 s. Open Termux to check for errors.")
    }

    /** Downloads the APK from the runner and hands it to Android's package installer (the user confirms). */
    private suspend fun installApk(call: ToolCall): ToolResult {
        val ctx = getApplication<Application>()
        val path = runCatching { Json.parseToJsonElement(call.arguments).jsonObject.str("path") }.getOrNull()
            ?: return ToolResult("Missing 'path'.", true)
        if (!path.endsWith(".apk")) return ToolResult("Only .apk files can be installed.", true)
        val dest = File(ctx.cacheDir, "apks/${File(path).name}")
        runCatching { host().fetchFile(path, dest) }.onFailure { return ToolResult("Copying the APK failed: ${it.message}", true) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !ctx.packageManager.canRequestPackageInstalls()) {
            ctx.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return ToolResult("Downloaded ${dest.length()} bytes, but Android needs the user to allow installs from Switchboard. " +
                "The settings page was opened. Once they allow it, call install_apk again.", true)
        }
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", dest)
        ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
        return ToolResult("Opened Android's installer for ${dest.name} (${dest.length() / 1024} KB). The user has to tap Install; you can't see whether they did.")
    }

    private suspend fun buildOptions(): RequestOptions {
        val base = requestOptions()
        if (!settings.toolsEnabled) return base
        val info = runCatching { describeHost() }.fold(
            { "Tools run on: $it." },
            { "The tool machine isn't reachable right now (${it.message}). If a tool fails, tell the user how to fix it in Settings > Tools." },
        )
        val modeNote = when (settings.runnerMode) {
            RunnerMode.PHONE -> "This is Switchboard's own app sandbox: Android's toybox shell only. There is no package manager, compiler, Java or Python, and nothing can be installed. " +
                "If the task needs real tools (for example building an APK), say so and tell the user to switch Settings > Tools to \"Termux on this phone\"."
            RunnerMode.TERMUX -> "This is Termux on the user's phone (no root). Install tools with `pkg install -y ...`. " +
                "To build an APK without Gradle: `pkg install -y openjdk-17 aapt aapt2 d8 apksigner zip`; find android.jar with `find ${'$'}PREFIX -name android.jar`; " +
                "then aapt2 compile/link, javac against android.jar, d8, zip the dex in, and sign with apksigner using a debug keystore made by keytool. " +
                "Gradle also works but is slow and needs android.aapt2FromMavenOverride pointing at Termux's aapt2. " +
                "Other coding tools come from pkg too: python, nodejs, clang, rust, golang, openjdk-17, git, make, cmake, arduino-cli via pip or a release download, esptool via pip."
            RunnerMode.REMOTE -> "This is the user's own computer."
        }
        val toolNote = """
            You can act with tools: run_command, write_file, read_file, install_apk (installs an .apk onto the user's phone; they tap Install), and send_file (puts any file into this chat so the user can open, save or share it; use it whenever they ask you to send or give them a file).
            $info
            $modeNote
            Devices: list_devices finds USB devices, Bluetooth (Classic and BLE) and local-network services; device_connect opens usb_serial, usb_raw, bt_serial, ble, tcp or udp; then device_write/device_read exchange bytes (text, hex or base64), and http_request talks to web APIs and smart devices. These always run on the phone itself. Android apps can't install kernel drivers: USB serial chips (CDC-ACM, CP210x, CH34x, FTDI) have built-in drivers, and other USB devices are reachable with usb_raw, where you implement the protocol over bulk/interrupt transfers.
            You may download and install whatever tools a task needs (compilers, SDKs, libraries, CLIs) on the tool machine${if (settings.runnerMode == RunnerMode.PHONE) " — except that this phone sandbox can't run downloaded programs; download_file is for data and files only, so suggest Termux mode when real tools are needed" else ""}.
            Do the work yourself with these tools instead of telling the user to run commands: install missing packages, create files, build, fix errors, and retry.
            Keep commands non-interactive. Builds can take several minutes; set timeout_sec accordingly.
        """.trimIndent()
        return base.copy(system = listOf(base.system, toolNote).filter { it.isNotBlank() }.joinToString("\n\n"), tools = Runner.tools + DeviceManager.tools(includeDownload = settings.runnerMode == RunnerMode.PHONE))
    }

    suspend fun testRunner(): Result<String> = runCatching { "Connected: " + host().describe() }

    fun setRunnerMode(m: RunnerMode) = updateSettings { it.copy(runnerMode = m) }

    fun setToolsEnabled(v: Boolean) = updateSettings { it.copy(toolsEnabled = v) }
    fun setRunner(url: String, token: String) = updateSettings { it.copy(runnerUrl = url.trim(), runnerToken = token.trim()) }
    fun setAskBeforeRun(v: Boolean) = updateSettings { it.copy(askBeforeRun = v) }
    fun setRateLimits(perMinute: Int, perDay: Int) = updateSettings { it.copy(maxPerMinute = perMinute.coerceAtLeast(0), maxPerDay = perDay.coerceAtLeast(0)) }
    fun setDailyReset(v: DailyReset) = updateSettings { it.copy(dailyReset = v) }
    fun setTimeZone(id: String) = updateSettings { it.copy(timeZone = id) }

    private fun updateReply(id: String, f: (ChatMessage) -> ChatMessage) {
        current = current.copy(messages = current.messages.map { if (it.id == id) f(it) else it })
    }

    private fun persistCurrent() {
        val c = current.copy(updatedAt = System.currentTimeMillis())
        current = c
        conversations = (listOf(c) + conversations.filterNot { it.id == c.id })
        io { store.save(c) }
    }

    // --- settings ---

    fun selectProvider(id: String) = updateSettings { it.copy(activeProviderId = id) }

    fun setModel(model: String) {
        val p = activeProvider ?: return
        upsertProvider(p.copy(model = model))
    }

    fun upsertProvider(p: Provider) = updateSettings { s ->
        val exists = s.providers.any { it.id == p.id }
        val list = if (exists) s.providers.map { if (it.id == p.id) p else it } else s.providers + p
        s.copy(providers = list, activeProviderId = s.activeProviderId ?: p.id)
    }

    fun removeProvider(id: String) = updateSettings { s ->
        val list = s.providers.filterNot { it.id == id }
        s.copy(providers = list, activeProviderId = if (s.activeProviderId == id) list.firstOrNull()?.id else s.activeProviderId)
    }

    fun setSystemPrompt(v: String) = updateSettings { it.copy(systemPrompt = v) }
    fun setMaxTokens(v: Int) = updateSettings { it.copy(maxTokens = v.coerceIn(1, maxTokenLimit)) }

    val maxTokenLimit get() = if (settings.unrestricted) 1_000_000 else 128_000

    fun setUnrestricted(on: Boolean) = updateSettings {
        it.copy(unrestricted = on, maxTokens = if (on) it.maxTokens else it.maxTokens.coerceAtMost(128_000))
    }

    fun setExtraBody(v: String) = updateSettings { it.copy(extraBody = v) }

    /** Null when blank; throws with a readable message when not a JSON object. */
    fun parseExtraBody(raw: String): JsonObject? {
        if (raw.isBlank()) return null
        return runCatching { Json.parseToJsonElement(raw).jsonObject }
            .getOrElse { throw IllegalArgumentException("Extra body params must be a JSON object, e.g. {\"temperature\": 0.7}") }
    }

    private fun requestOptions() = RequestOptions(
        system = listOf(settings.systemPrompt, timeNote()).filter { it.isNotBlank() }.joinToString("\n\n"),
        maxTokens = settings.maxTokens,
        unrestricted = settings.unrestricted,
        extraBody = if (settings.unrestricted) parseExtraBody(settings.extraBody) else null,
    )

    private fun timeNote(): String {
        val now = ZonedDateTime.now(zone)
        return "Current date and time for the user: ${now.format(DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy, h:mm a"))} (${zone.id})."
    }

    suspend fun fetchModels(p: Provider): Result<List<String>> = runCatching { client.listModels(p, settings.unrestricted) }

    private fun updateSettings(f: (AppSettings) -> AppSettings) {
        settings = f(settings)
        val snapshot = settings
        io { store.saveSettings(snapshot) }
    }

    companion object { const val MAX_TOOL_STEPS = 40
        const val MAX_ATTACHMENT = 200L * 1024 * 1024 }

    private fun io(block: () -> Unit) { viewModelScope.launch(Dispatchers.IO) { block() } }
}
