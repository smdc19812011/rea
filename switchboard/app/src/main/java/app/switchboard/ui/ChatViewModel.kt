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
import app.switchboard.build.ApkBuilder
import app.switchboard.data.Agent
import app.switchboard.data.Connector
import app.switchboard.data.Project
import app.switchboard.data.ProjectFile
import app.switchboard.data.Repeat
import app.switchboard.data.ScheduledTask
import app.switchboard.data.SecretBox
import app.switchboard.data.Skill
import app.switchboard.engine.AgentRunner
import app.switchboard.engine.Extras
import app.switchboard.engine.FileTools
import app.switchboard.build.DocMaker
import app.switchboard.build.PdfMaker
import app.switchboard.net.MediaGen
import app.switchboard.net.GeneratedFile
import app.switchboard.data.ApiKind
import app.switchboard.engine.Scheduler
import app.switchboard.engine.TaskRun
import app.switchboard.engine.TaskRuns
import app.switchboard.engine.finalText
import app.switchboard.net.Connectors
import app.switchboard.net.TokenStore
import app.switchboard.net.ToolSpec
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
import kotlinx.serialization.json.put
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

    private val tokens = TokenStore(File(app.filesDir, "tokens.enc"), SecretBox::encrypt, SecretBox::decrypt)
    private val connectors = Connectors(tokens)
    private val taskRuns = TaskRuns(File(app.filesDir, "task_runs.json"))

    val currentAgent: Agent? get() = settings.agents.firstOrNull { it.id == current.agentId }

    /** Provider for this chat: the chat's agent can pick its own provider and model. */
    fun providerFor(agent: Agent?): Provider? {
        val base = agent?.providerId?.let { id -> settings.providers.firstOrNull { it.id == id } } ?: activeProvider ?: return null
        return if (!agent?.model.isNullOrBlank()) base.copy(model = agent!!.model) else base
    }

    init {
        viewModelScope.launch {
            val (s, c) = withContext(Dispatchers.IO) { store.loadSettings() to store.loadConversations() }
            settings = s; conversations = c; loaded = true
            Scheduler.sync(app, s)
        }
    }

    // --- conversations ---

    fun newChat(projectId: String? = null) { stop(); current = Conversation(projectId = projectId) }

    fun renameChat(id: String, title: String, projectId: String?) {
        val t = title.trim().ifEmpty { "Untitled" }
        val updated = (conversations.firstOrNull { it.id == id } ?: current.takeIf { it.id == id } ?: return).copy(title = t, projectId = projectId)
        conversations = conversations.map { if (it.id == id) updated else it }
        if (current.id == id) current = current.copy(title = t, projectId = projectId)
        if (updated.messages.isNotEmpty()) io { store.save(updated) }
    }

    fun setChatAgent(agentId: String?) {
        current = current.copy(agentId = agentId)
        if (current.messages.isNotEmpty()) persistCurrent()
    }

    /** Picks up chats written by scheduled tasks while the app was in the background. */
    fun reloadConversations() {
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) { store.loadConversations() }
            conversations = list.map { c -> if (c.id == current.id && streaming) current else c }
        }
    }

    fun openById(id: String) {
        viewModelScope.launch {
            val c = conversations.firstOrNull { it.id == id } ?: withContext(Dispatchers.IO) { store.loadConversation(id) } ?: return@launch
            stop(); current = c
            if (conversations.none { it.id == id }) conversations = listOf(c) + conversations
        }
    }

    fun open(id: String) { stop(); conversations.firstOrNull { it.id == id }?.let { current = it } }

    fun delete(id: String) {
        conversations = conversations.filterNot { it.id == id }
        if (current.id == id) current = Conversation()
        io { store.delete(id); attachmentsDir(id).deleteRecursively() }
    }

    /**
     * [asImage] = the composer's image button is on. "/image ..." and (with auto-detect on) clear picture requests
     * go straight to the image model, so they work even when the chat model ignores tools.
     */
    fun send(text: String, asImage: Boolean = false) {
        val provider = providerFor(currentAgent) ?: return
        val trimmed = text.trim()
        if (trimmed.isEmpty() || streaming) return
        val title = if (current.messages.isEmpty()) trimmed.lineSequence().first().removePrefix("/image").trim().take(48) else current.title
        current = current.copy(title = title, messages = current.messages + ChatMessage(role = Role.USER, text = trimmed))
        val command = Regex("^/(image|img|draw|video)\\s+", RegexOption.IGNORE_CASE).find(trimmed)
        when {
            command != null -> respondWithMedia(trimmed.substring(command.range.last + 1), video = command.groupValues[1].lowercase() == "video")
            asImage -> respondWithMedia(trimmed, video = false)
            settings.fileTools && settings.autoImage && looksLikeVideoRequest(trimmed) && mediaProvider()?.videoModels?.isNotEmpty() == true -> respondWithMedia(trimmed, video = true)
            settings.fileTools && settings.autoImage && looksLikeImageRequest(trimmed) -> respondWithMedia(trimmed, video = false)
            else -> respond(provider)
        }
    }

    /** Makes an image (or video) straight from the user's words and posts it as the reply; no chat model involved. */
    private fun respondWithMedia(prompt: String, video: Boolean) {
        val reply = ChatMessage(role = Role.ASSISTANT, text = "", source = mediaProvider()?.let { "${it.name} · ${if (video) "video" else "image"}" })
        current = current.copy(messages = current.messages + reply)
        streaming = true
        WorkService.start(getApplication(), if (video) "Generating a video" else "Generating an image")
        job = viewModelScope.launch {
            try {
                val (atts, note, model) = if (video) makeVideo(prompt) else makeImage(prompt)
                updateReply(reply.id) { it.copy(text = note.trim().ifBlank { if (video) "Here's your video." else "Here's your image." }.take(2000),
                    attachments = atts, source = "${it.source?.substringBefore(" · ")} · $model") }
            } catch (e: kotlinx.coroutines.CancellationException) {
                updateReply(reply.id) { it.copy(text = "(stopped)", isError = true) }
                throw e
            } catch (e: Exception) {
                updateReply(reply.id) { it.copy(text = "⚠ ${e.message}", isError = true) }
            } finally {
                toolStatus = null; streaming = false
                WorkService.stop(getApplication())
                persistCurrent()
            }
        }
    }

    /** Re-run the last reply, optionally on whichever provider/model is active now. */
    fun regenerate() {
        val provider = providerFor(currentAgent) ?: return
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
                var p = provider
                while (true) {
                    val turn = streamTurn(p, opts) ?: break
                    p = turn.provider // stay on the model that answered for the rest of this reply
                    val calls = turn.calls
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

    private class TurnResult(val calls: List<ToolCall>, val provider: Provider)

    /**
     * One model turn. Dropped connections are retried; if a model can't answer (unavailable, out of credits, rate-limited,
     * overloaded), the next chat model in the provider's priority list takes over with the same full history.
     * If the conversation is too long for a model, the oldest messages are left out.
     */
    private suspend fun streamTurn(provider: Provider, opts: RequestOptions): TurnResult? {
        val models = provider.chatOrder.ifEmpty { listOf(provider.model) }
        for ((index, model) in models.withIndex()) {
            val p = provider.copy(model = model)
            val canSwitch = index < models.lastIndex
            val backoff = if (canSwitch) listOf(3) else listOf(3, 10, 30) // seconds; move on quickly when another model is waiting
            var attempt = 0
            var keep: Int? = null
            while (true) {
                when (val r = attemptTurn(p, opts, finalAttempt = attempt >= backoff.size, keepLast = keep, canSwitch = canSwitch)) {
                    is TurnOutcome.Done -> return TurnResult(r.calls, p)
                    is TurnOutcome.Failed -> return null
                    is TurnOutcome.TrimContext -> { keep = r.keep; toolStatus = "This chat is too long for $model; sending the most recent part…" }
                    is TurnOutcome.SwitchModel -> {
                        toolStatus = "$model couldn't answer (${r.reason.take(80)}). Trying ${models[index + 1]}…"
                        delay(1200); toolStatus = null
                        break
                    }
                    is TurnOutcome.Retry -> try {
                        for (left in backoff[attempt.coerceAtMost(backoff.lastIndex)] downTo 1) {
                            toolStatus = "Connection problem (${r.reason}). Retrying in $left s… (try ${attempt + 2} of ${backoff.size + 1})"
                            delay(1000)
                        }
                        attempt++
                    } finally { toolStatus = null }
                }
            }
        }
        return null
    }

    private sealed interface TurnOutcome {
        data class Done(val calls: List<ToolCall>) : TurnOutcome
        data object Failed : TurnOutcome
        data class Retry(val reason: String) : TurnOutcome
        data class SwitchModel(val reason: String) : TurnOutcome
        data class TrimContext(val keep: Int) : TurnOutcome
    }

    /** Errors that are about this model (not the network or the API key), so another model may succeed. */
    private fun modelProblem(e: Exception): Boolean {
        val code = (e as? app.switchboard.net.ApiException)?.httpCode ?: return false
        val msg = e.message.orEmpty()
        return code in setOf(402, 403, 404, 408, 409, 429, 500, 502, 503, 504, 529) ||
            (code in setOf(400, 422) && Regex("(?i)model|provider|endpoint|unavailable|not (found|supported)|no allowed|modalit").containsMatchIn(msg))
    }

    private fun contextTooLong(e: Exception): Boolean {
        val msg = e.message.orEmpty()
        return (e as? app.switchboard.net.ApiException)?.httpCode == 413 ||
            Regex("(?i)(context|prompt|input|token)s?.{0,40}(length|limit|too long|too large|maximum|exceed)|too many tokens").containsMatchIn(msg)
    }

    /** Models that answered "tools not supported"; they get plain requests from then on (until the app restarts). */
    private val noToolModels = mutableSetOf<String>()

    private suspend fun attemptTurn(provider: Provider, opts0: RequestOptions, finalAttempt: Boolean, keepLast: Int? = null, canSwitch: Boolean = false): TurnOutcome {
        var opts = if (provider.model in noToolModels && opts0.tools.isNotEmpty())
            opts0.copy(tools = emptyList(), system = opts0.system + "\n\nTools aren't available with this model, so answer directly.") else opts0
        awaitRateLimit()
        val full = current.messages
        // Too long for this model: send only the most recent part, starting at a user message.
        val history = if (keepLast == null || keepLast >= full.size) full else {
            val tail = full.takeLast(keepLast)
            val start = tail.indexOfFirst { it.role == Role.USER }.coerceAtLeast(0)
            opts = opts.copy(system = opts.system + "\n\nEarlier parts of this conversation (${full.size - tail.size + start} messages) were left out to fit this model's context. Ask the user if you need them.")
            tail.drop(start)
        }
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
                        val pics = ev.images.mapIndexedNotNull { i, url ->
                            runCatching { MediaGen.decodeDataUrl(url) }.getOrNull()?.let { f -> saveAttachment(f.bytes, "image-${System.currentTimeMillis() / 1000}-${i + 1}.${f.extension}", f.mime) }
                        }
                        updateReply(reply.id) { it.copy(text = ev.text, toolCalls = ev.toolCalls, anthropicBlocks = ev.anthropicBlocks, attachments = it.attachments + pics) }
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
            if (contextTooLong(e) && history.size > 3) {
                current = current.copy(messages = current.messages.filterNot { it.id == reply.id })
                return TurnOutcome.TrimContext((history.size / 2).coerceAtLeast(2))
            }
            if (canSwitch && modelProblem(e) && (!isRetryable(e) || finalAttempt) && !(opts.tools.isNotEmpty() && Regex("(?i)tool|function").containsMatchIn(msg))) {
                current = current.copy(messages = current.messages.filterNot { it.id == reply.id })
                return TurnOutcome.SwitchModel(msg)
            }
            if (opts.tools.isNotEmpty() && e is app.switchboard.net.ApiException && e.httpCode in setOf(400, 404, 422) && Regex("(?i)tool|function").containsMatchIn(msg)) {
                noToolModels += provider.model
                current = current.copy(messages = current.messages.filterNot { it.id == reply.id })
                return TurnOutcome.Retry("${provider.model} doesn't support tools; continuing without them")
            }
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
        // Building only writes inside Switchboard's storage; installing still goes through Android's own prompt.
        if (call.name == BUILD_APK) return buildApk(call)
        if (call.name == Extras.USE_SKILL) return Extras.useSkill(argsOf(call), settings.skills)
        when (call.name) {
            FileTools.GENERATE_IMAGE -> return generateImage(argsOf(call))
            FileTools.CREATE_DOCUMENT -> return createDocument(argsOf(call))
            FileTools.TEXT_TO_SPEECH -> return textToSpeech(argsOf(call))
            FileTools.GENERATE_VIDEO -> return generateVideo(argsOf(call))
        }
        if (call.name == Extras.RUN_AGENT) return runAgent(call)
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
            Extras.CREATE_SKILL -> createSkill(argsOf(call))
            Extras.CREATE_AGENT -> createAgent(argsOf(call))
            Extras.SCHEDULE_TASK -> scheduleTaskFromChat(argsOf(call))
            in connectorToolNames -> connectors.execute(call, settings.connectors, settings.unrestricted)
            Runner.INSTALL_APK -> installApk(call)
            in DeviceManager.names -> devices.execute(call, File(getApplication<Application>().filesDir, "workspace"), settings.unrestricted)
            else -> host().execute(call)
        }
    }

    private fun attachmentsDir(conversationId: String) = File(getApplication<Application>().filesDir, "attachments/$conversationId")

    /** Copies a file from the tool machine into the chat as an attachment the user can open, save or share. */
    /** Packages a web app (index.html + assets) in the phone workspace into a signed APK and attaches it to the chat. */
    private suspend fun buildApk(call: ToolCall): ToolResult = withContext(Dispatchers.IO) {
        val ctx = getApplication<Application>()
        val a = runCatching { Json.parseToJsonElement(call.arguments).jsonObject }.getOrNull() ?: return@withContext ToolResult("Arguments were not valid JSON.", true)
        val workspace = File(ctx.filesDir, "workspace")
        val dir = a.str("source_dir") ?: return@withContext ToolResult("Missing 'source_dir'.", true)
        val src = (if (dir.startsWith("/")) File(dir) else File(workspace, dir)).normalize()
        val spec = runCatching {
            ApkBuilder.Spec(
                appName = a.str("app_name") ?: error("Missing 'app_name'."),
                packageName = a.str("package") ?: error("Missing 'package'."),
                versionName = a.str("version_name") ?: "1.0",
                versionCode = a.str("version_code")?.toIntOrNull() ?: 1,
                permissions = (a["permissions"] as? kotlinx.serialization.json.JsonArray)?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }.orEmpty(),
                orientation = a.str("orientation")?.lowercase()?.takeIf { it == "portrait" || it == "landscape" },
            )
        }.getOrElse { return@withContext ToolResult(it.message ?: "Bad arguments.", true) }
        val out = File(workspace, "builds/${spec.packageName}-${spec.versionName}.apk")
        runCatching {
            val dex = ctx.assets.open("apk-template/classes.dex").use { it.readBytes() }
            ApkBuilder(dex, File(ctx.filesDir, "keys")).build(src, spec, out)
        }.onFailure { return@withContext ToolResult("Build failed: ${it.message}", true) }
        val dest = File(attachmentsDir(current.id), "${java.util.UUID.randomUUID().toString().take(8)}-${out.name}")
        dest.parentFile?.mkdirs(); out.copyTo(dest, overwrite = true)
        val att = Attachment(out.name, dest.path, dest.length(), "application/vnd.android.package-archive")
        ToolResult("Built ${out.name} (${out.length() / 1024} KB, ${spec.appName} ${spec.versionName}, versionCode ${spec.versionCode}) at ${out.path}. " +
            "It's attached in the chat; the user taps Install. To ship an update, rebuild with a higher version_code.", attachments = listOf(att))
    }

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

    private val connectorToolNames = object : AbstractSet<String>() {
        override val size get() = 0
        override fun iterator(): Iterator<String> = emptyList<String>().iterator()
        override fun contains(element: String) = connectors.owns(element)
    }

    private fun argsOf(call: ToolCall): JsonObject = runCatching { Json.parseToJsonElement(call.arguments).jsonObject }.getOrElse { JsonObject(emptyMap()) }

    /**
     * System prompt + tools for a request: settings prompt, the agent's prompt, project instructions/files, skills, agents,
     * connectors, and (when Tools is on) the machine/device tools.
     */
    private suspend fun buildOptions(agent: Agent? = currentAgent, allowAgents: Boolean = true): RequestOptions {
        val base = requestOptions()
        val project = settings.projects.firstOrNull { it.id == current.projectId }
        val sys = mutableListOf(base.system, agent?.systemPrompt.orEmpty(), Extras.projectNote(project))
        val tools = mutableListOf<ToolSpec>()
        val toolsAllowed = agent?.useTools != false
        if (toolsAllowed && settings.toolsEnabled) {
            val (note, specs) = machineTools()
            sys += note; tools += specs
            tools += listOf(Extras.createSkill, Extras.createAgent, Extras.scheduleTask)
            sys += "You can also save skills (create_skill), create agents (create_agent) and schedule prompts to run later or on repeat (schedule_task) when the user asks."
        }
        if (toolsAllowed && settings.connectors.any { it.enabled }) {
            val (specs, problems) = connectors.toolSpecs(settings.connectors)
            tools += specs
            if (specs.isNotEmpty()) sys += "Tools named mcp__* and api__* act on the user's connected services (Settings > Connectors)."
            if (problems.isNotEmpty()) sys += "These connectors couldn't load right now: ${problems.joinToString("; ")}. Mention it if the user needs them."
        }
        if (settings.skills.any { it.enabled }) { tools += Extras.useSkill; sys += Extras.skillsNote(settings.skills) }
        if (toolsAllowed && settings.fileTools) {
            tools += listOf(FileTools.createDocument, FileTools.textToSpeech)
            val img = mediaProvider()
            if (img != null) tools += FileTools.generateImage
            if (img?.videoModels?.isNotEmpty() == true) tools += FileTools.generateVideo
            sys.add(1, "You can make files for the user: create_document (PDF, Word, Excel, ZIP, or any text format), text_to_speech (audio)" +
                (if (img != null) ", generate_image (pictures)" else "") + (if (img?.videoModels?.isNotEmpty() == true) ", generate_video (video clips)" else "") +
                ". Files appear in the chat with Open/Save/Share buttons. Never say you can't make images or files: call these tools.")
        }
        if (allowAgents && toolsAllowed && settings.agents.isNotEmpty()) { tools += Extras.runAgent; sys += Extras.agentsNote(settings.agents) }
        return base.copy(system = sys.filter { it.isNotBlank() }.joinToString("\n\n"), tools = tools)
    }

    private suspend fun machineTools(): Pair<String, List<ToolSpec>> {
        val info = runCatching { describeHost() }.fold(
            { "Tools run on: $it." },
            { "The tool machine isn't reachable right now (${it.message}). If a tool fails, tell the user how to fix it in Settings > Tools." },
        )
        val modeNote = when (settings.runnerMode) {
            RunnerMode.PHONE -> "This is Switchboard's own app sandbox: Android's toybox shell only. There is no package manager, compiler, Java or Python, and nothing can be installed. " +
                "You CAN still build Android apps here: write the app as a web app (index.html plus CSS, JavaScript, images, sounds) in a folder in the workspace with write_file, " +
                "then call build_apk. That makes a real, signed APK and attaches it to this chat for the user to install, so do this instead of telling the user to build it themselves. " +
                "Make it self-contained (no CDN links unless you request the INTERNET permission), mobile-friendly (viewport meta tag, touch-sized controls), and save data with localStorage. " +
                "Only apps that truly need native Java/Kotlin code require Termux mode (Settings > Tools > Termux)."
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
        return toolNote to (Runner.tools + DeviceManager.tools(includeDownload = settings.runnerMode == RunnerMode.PHONE) + listOfNotNull(BUILD_APK_SPEC.takeIf { settings.runnerMode == RunnerMode.PHONE }))
    }

    // --- file generation ---

    /** Saves bytes as a chat attachment (and a copy in the workspace so tools can use it). */
    private fun saveAttachment(bytes: ByteArray, name: String, mime: String): Attachment {
        val ctx = getApplication<Application>()
        val safe = name.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_").take(120).ifBlank { "file" }
        val dest = File(attachmentsDir(current.id), "${java.util.UUID.randomUUID().toString().take(8)}-$safe")
        dest.parentFile?.mkdirs(); dest.writeBytes(bytes)
        runCatching { File(ctx.filesDir, "workspace/files/$safe").apply { parentFile?.mkdirs(); writeBytes(bytes) } }
        return Attachment(safe, dest.path, bytes.size.toLong(), mime)
    }

    /** Images, audio and video come from the chat's own provider when it speaks the OpenAI-style API (OpenRouter, OpenAI, ...). */
    private fun mediaProvider(): Provider? = providerFor(currentAgent)?.takeIf { it.kind == ApiKind.OPENAI_COMPATIBLE }

    /** Tries [models] in order; returns the first success, or throws with every model's reason. */
    private suspend fun <T> firstThatWorks(models: List<String>, what: String, attempt: suspend (String) -> T): Pair<T, String> {
        if (models.isEmpty()) error("No $what models are chosen. Open Settings, edit the provider, and pick $what models.")
        val errors = mutableListOf<String>()
        for (m in models.take(8)) {
            toolStatus = "Making $what with $m…"
            awaitRateLimit()
            try { return attempt(m) to m } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { errors += "• $m: ${e.message?.take(200)}" }
        }
        error("No model could make the $what:\n" + errors.joinToString("\n") + "\nAdd or reorder $what models in Settings > provider.")
    }

    private fun saveAll(files: List<GeneratedFile>, base: String) =
        files.mapIndexed { i, f -> saveAttachment(f.bytes, "$base${if (files.size > 1) "-${i + 1}" else ""}.${f.extension}", f.mime) }

    /** Current model first, then the provider's image priority list (or its other chat models if none are chosen). */
    private suspend fun makeImage(prompt: String, filename: String? = null): Triple<List<Attachment>, String, String> {
        val p = mediaProvider() ?: error("Images need an OpenAI-compatible provider such as OpenRouter or OpenAI; Anthropic models can't make images.")
        val candidates = (listOf(p.model) + p.imageModels + (if (p.imageModels.isEmpty()) p.chatModels else emptyList())).filter { it.isNotBlank() }.distinct()
        try {
            val (result, model) = firstThatWorks(candidates, "image") { m -> MediaGen().image(p, m, prompt, settings.unrestricted) }
            val atts = withContext(Dispatchers.IO) { saveAll(result.first, filename ?: "image-${System.currentTimeMillis() / 1000}") }
            return Triple(atts, result.second, model)
        } finally { toolStatus = null }
    }

    private suspend fun makeVideo(prompt: String, filename: String? = null): Triple<List<Attachment>, String, String> {
        val p = mediaProvider() ?: error("Videos need an OpenAI-compatible provider.")
        try {
            val (file, model) = firstThatWorks(p.videoModels, "video") { m -> MediaGen().video(p, m, prompt, settings.unrestricted) { toolStatus = "$m: $it" } }
            val atts = withContext(Dispatchers.IO) { saveAll(listOf(file), filename ?: "video-${System.currentTimeMillis() / 1000}") }
            return Triple(atts, "", model)
        } finally { toolStatus = null }
    }

    private suspend fun generateImage(a: JsonObject): ToolResult {
        val prompt = a.str("prompt")?.takeIf { it.isNotBlank() } ?: return ToolResult("Missing 'prompt'.", true)
        val prev = toolStatus
        return try {
            val (atts, note, model) = makeImage(prompt, a.str("filename")?.substringBeforeLast('.')?.ifBlank { null })
            ToolResult("Created ${atts.joinToString { it.name }} with $model; shown in the chat.${if (note.isNotBlank()) " Model note: ${note.take(300)}" else ""}", attachments = atts)
        } catch (e: kotlinx.coroutines.CancellationException) { throw e
        } catch (e: Exception) { ToolResult(e.message ?: "Image generation failed.", true)
        } finally { toolStatus = prev }
    }

    private suspend fun generateVideo(a: JsonObject): ToolResult {
        val prompt = a.str("prompt")?.takeIf { it.isNotBlank() } ?: return ToolResult("Missing 'prompt'.", true)
        val prev = toolStatus
        return try {
            val (atts, _, model) = makeVideo(prompt, a.str("filename")?.substringBeforeLast('.')?.ifBlank { null })
            ToolResult("Created ${atts.joinToString { it.name }} with $model; it's in the chat.", attachments = atts)
        } catch (e: kotlinx.coroutines.CancellationException) { throw e
        } catch (e: Exception) { ToolResult(e.message ?: "Video generation failed.", true)
        } finally { toolStatus = prev }
    }

    private suspend fun createDocument(a: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        val format = a.str("format")?.lowercase()?.trimStart('.') ?: return@withContext ToolResult("Missing 'format'.", true)
        val name = (a.str("filename")?.trim()?.ifBlank { null } ?: "document").let { if (it.lowercase().endsWith(".$format")) it else "$it.$format" }
        val content = a.str("content").orEmpty()
        runCatching {
            val bytes = when (format) {
                "pdf" -> PdfMaker.pdf(content, a.str("title"))
                "docx" -> DocMaker.docx(content, a.str("title"))
                "xlsx" -> {
                    val sheets = (a["sheets"] as? kotlinx.serialization.json.JsonArray)?.mapNotNull { el ->
                        val o = el as? JsonObject ?: return@mapNotNull null
                        (o.str("name") ?: "Sheet") to DocMaker.parseCsv(o.str("csv").orEmpty())
                    }?.takeIf { it.isNotEmpty() } ?: listOf("Sheet1" to DocMaker.parseCsv(content))
                    DocMaker.xlsx(sheets)
                }
                "zip" -> {
                    val ws = File(getApplication<Application>().filesDir, "workspace")
                    val files = (a["files"] as? kotlinx.serialization.json.JsonArray)?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }.orEmpty()
                    require(files.isNotEmpty()) { "List the workspace files to include in 'files'." }
                    DocMaker.zipFiles(ws, files.map { if (it.startsWith("/")) File(it) else File(ws, it) })
                }
                else -> content.toByteArray()
            }
            val mime = when (format) {
                "pdf" -> "application/pdf"; "zip" -> "application/zip"
                "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(format) ?: "text/plain"
            }
            val att = saveAttachment(bytes, name, mime)
            ToolResult("Created ${att.name} (${bytes.size} bytes); it's attached in the chat.", attachments = listOf(att))
        }.getOrElse { ToolResult("Couldn't create $name: ${it.message}", true) }
    }

    /** Speaks [text] into a WAV file with the phone's text-to-speech engine. */
    private suspend fun textToSpeech(a: JsonObject): ToolResult {
        val text = a.str("text")?.takeIf { it.isNotBlank() } ?: return ToolResult("Missing 'text'.", true)
        val ctx = getApplication<Application>()
        val p = mediaProvider()
        if (p != null && p.audioModels.isNotEmpty()) {
            val prev = toolStatus
            val viaModel = runCatching { firstThatWorks(p.audioModels, "audio") { m -> MediaGen().audio(p, m, text, a.str("voice") ?: "alloy", settings.unrestricted) } }
                .also { toolStatus = prev }
            viaModel.getOrNull()?.let { (file, model) ->
                val name = a.str("filename")?.substringBeforeLast('.')?.ifBlank { null } ?: "audio-${System.currentTimeMillis() / 1000}"
                val atts = withContext(Dispatchers.IO) { saveAll(listOf(file), name) }
                return ToolResult("Created ${atts.single().name} with $model; it's in the chat.", attachments = atts)
            }
            viaModel.exceptionOrNull()?.let { if (it is kotlinx.coroutines.CancellationException) throw it }
        }
        // No audio models (or none worked): use the phone's own text-to-speech.
        val out = File(ctx.cacheDir, "tts-${System.currentTimeMillis()}.wav")
        val ok = withContext(Dispatchers.Main) {
            kotlinx.coroutines.withTimeoutOrNull(120_000) {
                val ready = CompletableDeferred<android.speech.tts.TextToSpeech?>()
                lateinit var tts: android.speech.tts.TextToSpeech
                tts = android.speech.tts.TextToSpeech(ctx) { status -> ready.complete(if (status == android.speech.tts.TextToSpeech.SUCCESS) tts else null) }
                val engine = ready.await() ?: return@withTimeoutOrNull "The phone has no working text-to-speech engine."
                try {
                    a.str("language")?.let { engine.language = java.util.Locale.forLanguageTag(it) }
                    if (text.length > android.speech.tts.TextToSpeech.getMaxSpeechInputLength()) return@withTimeoutOrNull "Text is too long for one file (max ${android.speech.tts.TextToSpeech.getMaxSpeechInputLength()} characters); split it."
                    val done = CompletableDeferred<Boolean>()
                    engine.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                        override fun onStart(id: String?) {}
                        override fun onDone(id: String?) { done.complete(true) }
                        @Deprecated("old API") override fun onError(id: String?) { done.complete(false) }
                        override fun onError(id: String?, code: Int) { done.complete(false) }
                    })
                    if (engine.synthesizeToFile(text, null, out, "sb") != android.speech.tts.TextToSpeech.SUCCESS) return@withTimeoutOrNull "Text-to-speech refused the request."
                    if (done.await()) null else "Text-to-speech failed."
                } finally { engine.shutdown() }
            } ?: "Text-to-speech timed out."
        }
        if (ok != null || !out.isFile || out.length() == 0L) { out.delete(); return ToolResult(ok ?: "No audio was produced.", true) }
        val name = (a.str("filename")?.substringBeforeLast('.')?.ifBlank { null } ?: "speech") + ".wav"
        val att = withContext(Dispatchers.IO) { saveAttachment(out.readBytes(), name, "audio/wav").also { out.delete() } }
        return ToolResult("Created ${att.name} (${att.size / 1024} KB of audio); it's attached in the chat.", attachments = listOf(att))
    }

    fun setFileTools(v: Boolean) = updateSettings { it.copy(fileTools = v) }
    fun setAutoImage(v: Boolean) = updateSettings { it.copy(autoImage = v) }

    // --- agents (run_agent) ---

    private var agentDepth = 0

    private suspend fun runAgent(call: ToolCall): ToolResult {
        val a = argsOf(call)
        val name = a.str("agent").orEmpty()
        val agent = settings.agents.firstOrNull { it.name.equals(name, ignoreCase = true) }
            ?: return ToolResult("No agent named '$name'. Available: ${settings.agents.joinToString { it.name }}", true)
        if (agentDepth > 0) return ToolResult("Agents can't start other agents.", true)
        val provider = providerFor(agent) ?: return ToolResult("No provider is set up.", true)
        agentDepth++
        val prevStatus = toolStatus
        return try {
            val opts = buildOptions(agent, allowAgents = false)
            val added = AgentRunner(client) { awaitRateLimit() }.run(provider, opts,
                listOf(ChatMessage(role = Role.USER, text = a.str("task").orEmpty())), execute = { runTool(it) }, maxSteps = 20,
                onProgress = { toolStatus = "${agent.name}: $it" })
            ToolResult("${agent.name} (${provider.model}) answered:\n${added.finalText()}", isError = added.lastOrNull()?.isError == true,
                attachments = added.flatMap { it.attachments })
        } catch (e: kotlinx.coroutines.CancellationException) { throw e
        } catch (e: Exception) { ToolResult("${agent.name} failed: ${e.message}", true)
        } finally { agentDepth--; toolStatus = prevStatus }
    }

    // --- skills / agents / tasks created from chat ---

    private fun createSkill(a: JsonObject): ToolResult {
        val name = a.str("name")?.trim().orEmpty(); val instr = a.str("instructions").orEmpty()
        if (name.isEmpty() || instr.isBlank()) return ToolResult("name and instructions are required.", true)
        val old = settings.skills.firstOrNull { it.name.equals(name, true) }
        upsertSkill(Skill(id = old?.id ?: java.util.UUID.randomUUID().toString(), name = name, description = a.str("description").orEmpty(), instructions = instr))
        return ToolResult("${if (old != null) "Updated" else "Saved"} skill \"$name\". It's in Settings > Skills.")
    }

    private fun createAgent(a: JsonObject): ToolResult {
        val name = a.str("name")?.trim().orEmpty(); val prompt = a.str("system_prompt").orEmpty()
        if (name.isEmpty() || prompt.isBlank()) return ToolResult("name and system_prompt are required.", true)
        val old = settings.agents.firstOrNull { it.name.equals(name, true) }
        upsertAgent(Agent(id = old?.id ?: java.util.UUID.randomUUID().toString(), name = name, description = a.str("description").orEmpty(),
            systemPrompt = prompt, model = a.str("model").orEmpty(), providerId = old?.providerId))
        return ToolResult("${if (old != null) "Updated" else "Created"} agent \"$name\". It's in Settings > Agents.")
    }

    private fun scheduleTaskFromChat(a: JsonObject): ToolResult {
        val start = runCatching {
            java.time.LocalDateTime.parse(a.str("start").orEmpty().trim(), DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")).atZone(zone).toInstant().toEpochMilli()
        }.getOrElse { return ToolResult("start must look like 2026-11-16 08:30 (the user's local time).", true) }
        val repeat = runCatching { Repeat.valueOf(a.str("repeat").orEmpty().uppercase()) }.getOrElse { return ToolResult("repeat must be once, hourly, daily or weekly.", true) }
        val t = ScheduledTask(name = a.str("name").orEmpty().ifBlank { "Task" }, prompt = a.str("prompt").orEmpty(), repeat = repeat, startAt = start,
            everyHours = a.str("every_hours")?.toIntOrNull() ?: 1, projectId = current.projectId, agentId = current.agentId,
            allowTools = a.str("allow_tools") == "true")
        if (t.prompt.isBlank()) return ToolResult("prompt is required.", true)
        upsertTask(t)
        return ToolResult("Scheduled \"${t.name}\" (${t.repeat.name.lowercase()}); next run ${nextRunText(t)}. It's in Settings > Scheduled tasks.")
    }

    // --- connectors ---

    var oauthStatus by mutableStateOf<String?>(null); private set
    private var pendingAuth: Connectors.PendingAuth? = null

    fun upsertConnector(c: Connector) {
        connectors.forget(c.id)
        updateSettings { s -> s.copy(connectors = s.connectors.filterNot { it.id == c.id } + c) }
    }
    fun removeConnector(id: String) { connectors.forget(id); tokens.remove(id); updateSettings { s -> s.copy(connectors = s.connectors.filterNot { it.id == id }) } }
    fun isSignedIn(id: String) = tokens.get(id) != null
    fun signOut(id: String) { tokens.remove(id); connectors.forget(id); oauthStatus = "Signed out." }
    suspend fun testConnector(c: Connector): Result<String> = runCatching { connectors.test(c) }

    /** Discovers endpoints (and registers a client if needed), then opens the sign-in page in the browser. */
    suspend fun connectOAuth(c: Connector): Result<Unit> = runCatching {
        oauthStatus = "Preparing sign-in…"
        val cfg = connectors.discover(c)
        val updated = c.copy(auth = c.auth.copy(oauth = cfg))
        if (updated != c) upsertConnector(updated)
        val (url, pending) = connectors.beginAuth(updated, cfg)
        pendingAuth = pending
        getApplication<Application>().startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        oauthStatus = "Finish signing in in the browser…"
    }.onFailure { oauthStatus = "Couldn't start sign-in: ${it.message}" }

    /** switchboard://oauth?code=…&state=… */
    fun onOAuthRedirect(uri: Uri) {
        val p = pendingAuth ?: run { oauthStatus = "Sign-in response arrived, but no sign-in was in progress. Tap Connect again."; return }
        val err = uri.getQueryParameter("error")
        if (err != null) { oauthStatus = "Sign-in failed: ${uri.getQueryParameter("error_description") ?: err}"; pendingAuth = null; return }
        if (uri.getQueryParameter("state") != p.state) { oauthStatus = "Sign-in failed: the response didn't match (state). Try again."; return }
        val code = uri.getQueryParameter("code") ?: run { oauthStatus = "Sign-in failed: no code returned."; return }
        pendingAuth = null
        viewModelScope.launch {
            oauthStatus = runCatching { connectors.finishAuth(p, code); "Signed in." }.getOrElse { "Sign-in failed: ${it.message}" }
        }
    }

    // --- skills, agents, projects ---

    fun upsertSkill(k: Skill) = updateSettings { s -> s.copy(skills = s.skills.filterNot { it.id == k.id } + k) }
    fun removeSkill(id: String) = updateSettings { s -> s.copy(skills = s.skills.filterNot { it.id == id }) }
    fun upsertAgent(a: Agent) = updateSettings { s -> s.copy(agents = s.agents.filterNot { it.id == a.id } + a) }
    fun removeAgent(id: String) {
        updateSettings { s -> s.copy(agents = s.agents.filterNot { it.id == id }) }
        if (current.agentId == id) current = current.copy(agentId = null)
    }

    fun upsertProject(p: Project) = updateSettings { s -> s.copy(projects = s.projects.filterNot { it.id == p.id } + p) }
    fun removeProject(id: String) {
        updateSettings { s -> s.copy(projects = s.projects.filterNot { it.id == id }) }
        conversations.filter { it.projectId == id }.forEach { renameChat(it.id, it.title, null) }
        io { File(getApplication<Application>().filesDir, "projects/$id").deleteRecursively() }
    }

    /** Copies a picked file into the project's folder; its text is shared with every chat in the project. */
    suspend fun addProjectFile(projectId: String, uri: Uri): Result<ProjectFile> = runCatching {
        withContext(Dispatchers.IO) {
            val ctx = getApplication<Application>()
            val name = ctx.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: "file"
            val dest = File(ctx.filesDir, "projects/$projectId/${java.util.UUID.randomUUID().toString().take(6)}-$name")
            dest.parentFile?.mkdirs()
            ctx.contentResolver.openInputStream(uri)!!.use { input -> dest.outputStream().use { input.copyTo(it) } }
            if (dest.length() > 5L * 1024 * 1024) { dest.delete(); error("Files over 5 MB can't be added to a project.") }
            ProjectFile(name, dest.path, dest.length())
        }
    }

    // --- scheduled tasks ---

    fun upsertTask(t: ScheduledTask) {
        updateSettings { s -> s.copy(tasks = s.tasks.filterNot { it.id == t.id } + t) }
        Scheduler.sync(getApplication(), settings)
    }
    fun removeTask(id: String) {
        Scheduler.cancel(getApplication(), id)
        updateSettings { s -> s.copy(tasks = s.tasks.filterNot { it.id == id }) }
    }
    fun runTaskNow(id: String) = Scheduler.runNow(getApplication(), id)
    fun taskRun(id: String): TaskRun? = taskRuns.all()[id]
    fun nextRunText(t: ScheduledTask): String {
        if (!t.enabled) return "paused"
        val next = Scheduler.nextRun(t, zone, System.currentTimeMillis(), taskRun(t.id)?.at) ?: return "done"
        return ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(next), zone).format(DateTimeFormatter.ofPattern("EEE MMM d, h:mm a"))
    }

    suspend fun testRunner(): Result<String> = runCatching { "Connected: " + host().describe() }

    fun setRunnerMode(m: RunnerMode) = updateSettings { it.copy(runnerMode = m) }

    fun setToolsEnabled(v: Boolean) = updateSettings { it.copy(toolsEnabled = v) }
    fun setRunner(url: String, token: String) = updateSettings { it.copy(runnerUrl = url.trim(), runnerToken = token.trim()) }
    fun setAskBeforeRun(v: Boolean) = updateSettings { it.copy(askBeforeRun = v) }
    fun setRateLimits(perMinute: Int, perDay: Int) = updateSettings { it.copy(maxPerMinute = perMinute.coerceAtLeast(0), maxPerDay = perDay.coerceAtLeast(0)) }
    fun setDailyReset(v: DailyReset) = updateSettings { it.copy(dailyReset = v) }
    fun setTimeZone(id: String) { updateSettings { it.copy(timeZone = id) }; Scheduler.sync(getApplication(), settings) }

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

    /** Models the provider offers to this API key, with what each can produce. */
    suspend fun detectModels(p: Provider): Result<List<app.switchboard.net.ModelInfo>> = runCatching { client.listModelsDetailed(p, settings.unrestricted) }

    private fun updateSettings(f: (AppSettings) -> AppSettings) {
        settings = f(settings)
        val snapshot = settings
        io { store.saveSettings(snapshot) }
    }

    companion object { const val MAX_TOOL_STEPS = 40
        const val BUILD_APK = "build_apk"
        private fun prop(type: String, desc: String) = kotlinx.serialization.json.buildJsonObject { put("type", type); put("description", desc) }
        val BUILD_APK_SPEC = ToolSpec(BUILD_APK,
            "Build an installable Android app (signed APK) on this phone from a web app folder in the workspace. The folder must contain index.html; " +
                "everything in it (CSS, JS, images, sounds, fonts) is packaged and shown full screen. The APK is attached to the chat for the user to install.",
            kotlinx.serialization.json.buildJsonObject {
                put("type", "object")
                put("properties", kotlinx.serialization.json.buildJsonObject {
                    put("source_dir", prop("string", "Workspace folder containing index.html, e.g. TimezoneClock/www"))
                    put("app_name", prop("string", "Name shown under the app icon"))
                    put("package", prop("string", "Unique app id like com.user.timezoneclock; keep it the same for updates"))
                    put("version_name", prop("string", "e.g. 1.0"))
                    put("version_code", prop("integer", "Whole number; increase it for each update"))
                    put("permissions", kotlinx.serialization.json.buildJsonObject { put("type", "array"); put("items", kotlinx.serialization.json.buildJsonObject { put("type", "string") }); put("description", "Android permissions, e.g. [\"INTERNET\", \"VIBRATE\"]") })
                    put("orientation", prop("string", "portrait, landscape, or omit for both"))
                })
                put("required", kotlinx.serialization.json.JsonArray(listOf("source_dir", "app_name", "package").map { kotlinx.serialization.json.JsonPrimitive(it) }))
            })
        const val MAX_ATTACHMENT = 200L * 1024 * 1024 }

    private fun io(block: () -> Unit) { viewModelScope.launch(Dispatchers.IO) { block() } }
}

private val imageNouns = "image|picture|pic|photo|photograph|drawing|illustration|painting|artwork|art|logo|icon|wallpaper|portrait|sketch|poster|render|cartoon|avatar"
private val imageAsk = Regex("^\\s*(please\\s+|can you\\s+|could you\\s+)?(generate|create|make|draw|paint|render|design|produce|give me|show me|imagine)\\b.{0,60}?\\b($imageNouns)s?\\b", RegexOption.IGNORE_CASE)
private val drawAsk = Regex("^\\s*(please\\s+|can you\\s+|could you\\s+)?(draw|paint|sketch|illustrate)\\s+(me\\s+)?(a|an|the|some|my)\\b", RegexOption.IGNORE_CASE)
/** Requests for code-made graphics stay with the chat model. */
private val notPicture = Regex("\\b(svg|html|css|code|ascii|chart|graph|diagram|plot|mermaid|latex|qr|table|spreadsheet|uml|flowchart)\\b", RegexOption.IGNORE_CASE)

/** True for messages that clearly ask for a picture, e.g. "generate an image of a man fighting a dragon". */
fun looksLikeImageRequest(text: String): Boolean =
    text.length < 1500 && !notPicture.containsMatchIn(text) && (imageAsk.containsMatchIn(text) || drawAsk.containsMatchIn(text))

private val videoAsk = Regex("^\\s*(please\\s+|can you\\s+|could you\\s+)?(generate|create|make|render|produce|give me|animate)\\b.{0,60}?\\b(video|clip|animation|movie|film)s?\\b", RegexOption.IGNORE_CASE)

/** True for messages that clearly ask for a video clip. */
fun looksLikeVideoRequest(text: String): Boolean =
    text.length < 1500 && videoAsk.containsMatchIn(text) && !Regex("(?i)\\b(script|code|html|css|storyboard|outline|youtube title)\\b").containsMatchIn(text)
