package app.switchboard.engine

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.switchboard.MainActivity
import app.switchboard.data.AppSettings
import app.switchboard.data.ChatMessage
import app.switchboard.data.Conversation
import app.switchboard.data.RateLimiter
import app.switchboard.data.Repeat
import app.switchboard.data.Role
import app.switchboard.data.RunnerMode
import app.switchboard.data.ScheduledTask
import app.switchboard.data.SecretBox
import app.switchboard.data.Store
import app.switchboard.net.ChatClient
import app.switchboard.net.Connectors
import app.switchboard.net.PhoneHost
import app.switchboard.net.RequestOptions
import app.switchboard.net.Runner
import app.switchboard.net.Termux
import app.switchboard.net.TokenStore
import app.switchboard.net.ToolResult
import app.switchboard.net.ToolSpec
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

@Serializable
data class TaskRun(val at: Long, val ok: Boolean, val summary: String, val conversationId: String?)

/** Last run of each task, in its own file because the background worker writes it. */
class TaskRuns(private val file: File) {
    private val json = Json { ignoreUnknownKeys = true }
    @Synchronized fun all(): Map<String, TaskRun> = runCatching { json.decodeFromString<Map<String, TaskRun>>(file.readText()) }.getOrElse { emptyMap() }
    @Synchronized fun put(id: String, r: TaskRun) { file.writeText(json.encodeToString(all() + (id to r))) }
}

object Scheduler {
    fun zoneOf(s: AppSettings): ZoneId = runCatching { if (s.timeZone.isBlank()) null else ZoneId.of(s.timeZone) }.getOrNull() ?: ZoneId.systemDefault()

    /** Next run strictly after [now], or null when a one-off task has already run. */
    fun nextRun(t: ScheduledTask, zone: ZoneId, now: Long, lastRun: Long?): Long? {
        if (t.repeat == Repeat.ONCE) return t.startAt.takeIf { lastRun == null || lastRun < t.startAt }?.coerceAtLeast(now + 1000)
        if (t.startAt > now) return t.startAt
        return when (t.repeat) {
            Repeat.HOURLY -> { val p = t.everyHours.coerceAtLeast(1) * 3_600_000L; t.startAt + ((now - t.startAt) / p + 1) * p }
            Repeat.DAILY, Repeat.WEEKLY -> {
                val step = if (t.repeat == Repeat.DAILY) 1L else 7L
                val start = ZonedDateTime.ofInstant(Instant.ofEpochMilli(t.startAt), zone)
                val days = java.time.Duration.ofMillis(now - t.startAt).toDays()
                var n = (days / step).coerceAtLeast(0)
                var next = start.plusDays(n * step)
                while (next.toInstant().toEpochMilli() <= now) { n++; next = start.plusDays(n * step) }
                next.toInstant().toEpochMilli()
            }
            Repeat.ONCE -> null
        }
    }

    fun sync(ctx: Context, s: AppSettings) {
        val wm = WorkManager.getInstance(ctx)
        val runs = TaskRuns(File(ctx.filesDir, "task_runs.json")).all()
        val zone = zoneOf(s)
        for (t in s.tasks) {
            val next = if (t.enabled) nextRun(t, zone, System.currentTimeMillis(), runs[t.id]?.at) else null
            if (next == null) { wm.cancelUniqueWork(name(t.id)); continue }
            enqueue(ctx, t.id, next - System.currentTimeMillis())
        }
    }

    fun cancel(ctx: Context, id: String) = WorkManager.getInstance(ctx).cancelUniqueWork(name(id))

    fun runNow(ctx: Context, id: String) = enqueue(ctx, id, 0, policy = ExistingWorkPolicy.REPLACE, manual = true)

    private fun enqueue(ctx: Context, id: String, delayMs: Long, policy: ExistingWorkPolicy = ExistingWorkPolicy.REPLACE, manual: Boolean = false) {
        val req = OneTimeWorkRequestBuilder<TaskWorker>()
            .setInitialDelay(delayMs.coerceAtLeast(0), TimeUnit.MILLISECONDS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(workDataOf("task" to id, "manual" to manual))
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork(name(id) + if (manual) "-now" else "", policy, req)
    }

    private fun name(id: String) = "task-$id"
}

/** Runs one scheduled task in the background, saves the result as a chat, notifies, and schedules the next run. */
class TaskWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val store = Store(ctx)
        val s = store.loadSettings()
        val task = s.tasks.firstOrNull { it.id == inputData.getString("task") } ?: return Result.success()
        val manual = inputData.getBoolean("manual", false)
        if (!task.enabled && !manual) return Result.success()
        val runs = TaskRuns(File(ctx.filesDir, "task_runs.json"))
        val zone = Scheduler.zoneOf(s)
        val outcome = runCatching { execute(ctx, store, s, task, zone) }
        val (ok, summary, convId) = outcome.getOrElse { Triple(false, "Failed: ${it.message}", null) }
        runs.put(task.id, TaskRun(System.currentTimeMillis(), ok, summary.take(300), convId))
        notify(ctx, task, summary, convId)
        if (!manual) Scheduler.sync(ctx, store.loadSettings())
        return Result.success()
    }

    private suspend fun execute(ctx: Context, store: Store, s: AppSettings, t: ScheduledTask, zone: ZoneId): Triple<Boolean, String, String?> {
        val agent = s.agents.firstOrNull { it.id == t.agentId }
        val base = s.providers.firstOrNull { it.id == (agent?.providerId ?: s.activeProviderId) } ?: s.providers.firstOrNull()
            ?: return Triple(false, "No model provider is set up.", null)
        val provider = if (!agent?.model.isNullOrBlank()) base.copy(model = agent!!.model) else base
        val project = s.projects.firstOrNull { it.id == t.projectId }

        // Tools: skills always; connectors and machine tools only if the task may run them unattended.
        val connectors = Connectors(TokenStore(File(ctx.filesDir, "tokens.enc"), SecretBox::encrypt, SecretBox::decrypt))
        val tools = mutableListOf<ToolSpec>()
        if (s.skills.any { it.enabled }) tools += Extras.useSkill
        if (t.allowTools && (agent?.useTools != false)) {
            tools += connectors.toolSpecs(s.connectors).first
            if (s.toolsEnabled) tools += Runner.tools.filter { it.name in setOf("run_command", "write_file", "read_file") }
        }
        val host = when (s.runnerMode) {
            RunnerMode.PHONE -> PhoneHost(File(ctx.filesDir, "workspace"))
            RunnerMode.TERMUX -> Runner(Termux.URL, s.termuxToken, s.unrestricted)
            RunnerMode.REMOTE -> Runner(s.runnerUrl, s.runnerToken, s.unrestricted)
        }
        val system = listOf(s.systemPrompt, agent?.systemPrompt.orEmpty(), Extras.projectNote(project), Extras.skillsNote(s.skills), Extras.timeNote(zone),
            if (t.allowTools && s.toolsEnabled) Toolbox.note(s.packages, s.runnerMode) else "",
            "This is a scheduled task (\"${t.name}\") running in the background; nobody is watching, so finish the job and end with a short summary.")
            .filter { it.isNotBlank() }.joinToString("\n\n")
        val opts = RequestOptions(system = system, maxTokens = s.maxTokens, unrestricted = s.unrestricted, tools = tools)

        val existing = if (t.sameChat) store.loadConversations().firstOrNull { it.title == "⏰ ${t.name}" } else null
        val stamp = ZonedDateTime.now(zone).format(java.time.format.DateTimeFormatter.ofPattern("MMM d, h:mm a"))
        val conv = existing ?: Conversation(title = if (t.sameChat) "⏰ ${t.name}" else "⏰ ${t.name} · $stamp", projectId = t.projectId, agentId = t.agentId)
        val history = conv.messages + ChatMessage(role = Role.USER, text = t.prompt)

        val limiter = RateLimiter(File(ctx.filesDir, "request_limits.tsv"))
        val runner = AgentRunner(ChatClient()) { pr ->
            val l = pr.limits[pr.model] ?: app.switchboard.data.ModelLimit()
            val limits = RateLimiter.Limits(l.perMinute, l.perDay, s.dailyReset, zone)
            while (true) {
                val w = limiter.tryAcquire(RateLimiter.key(pr.id, pr.model), limits) ?: break
                if (w.scope == RateLimiter.Scope.DAY) throw IllegalStateException("daily request limit reached")
                delay((w.untilMs - System.currentTimeMillis()).coerceIn(200, 60_000))
            }
        }
        val json = Json { ignoreUnknownKeys = true }
        val added = runner.run(provider, opts, history, execute = { call ->
            when {
                call.name == Extras.USE_SKILL -> Extras.useSkill(json.parseToJsonElement(call.arguments).jsonObject, s.skills)
                connectors.owns(call.name) -> connectors.execute(call, s.connectors, s.unrestricted)
                else -> host.execute(call)
            }
        }, maxSteps = 25)
        val saved = conv.copy(messages = history + added, updatedAt = System.currentTimeMillis())
        store.save(saved)
        val failed = added.lastOrNull()?.isError == true
        return Triple(!failed, added.finalText().take(300), saved.id)
    }

    private fun notify(ctx: Context, t: ScheduledTask, summary: String, convId: String?) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) nm.createNotificationChannel(NotificationChannel("tasks", "Scheduled tasks", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(ctx, t.id.hashCode(), Intent(ctx, MainActivity::class.java).putExtra("conversationId", convId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) android.app.Notification.Builder(ctx, "tasks") else @Suppress("DEPRECATION") android.app.Notification.Builder(ctx))
            .setSmallIcon(android.R.drawable.ic_popup_reminder).setContentTitle("⏰ ${t.name}").setContentText(summary)
            .setStyle(android.app.Notification.BigTextStyle().bigText(summary)).setContentIntent(open).setAutoCancel(true).build()
        runCatching { nm.notify(t.id.hashCode(), n) }
    }
}
