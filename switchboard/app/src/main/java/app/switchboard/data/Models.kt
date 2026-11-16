package app.switchboard.data

import kotlinx.serialization.Serializable
import java.util.UUID

/** Wire protocol a provider speaks. Most hosted gateways (OpenRouter, Groq, Together, Ollama, LM Studio) are OpenAI-compatible. */
@Serializable
enum class ApiKind { ANTHROPIC, OPENAI_COMPATIBLE }

@Serializable
data class Provider(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val kind: ApiKind,
    val baseUrl: String,
    /** Encrypted at rest; see [SecretBox]. Never logged. */
    val apiKey: String = "",
    /** The model chats use right now (always the first entry of [chatModels] when that list is set). */
    val model: String,
    /** Models in priority order for each kind of task; the next one is tried when one fails. */
    val chatModels: List<String> = emptyList(),
    val imageModels: List<String> = emptyList(),
    val audioModels: List<String> = emptyList(),
    val videoModels: List<String> = emptyList(),
    /** Request limits per model ID (shared by all chats). Models without an entry are unlimited. */
    val limits: Map<String, ModelLimit> = emptyMap(),
) {
    /** Chat models to try, current first, then the rest of the priority list. */
    val chatOrder: List<String> get() = (listOf(model) + chatModels).filter { it.isNotBlank() }.distinct()
}

/** How a toolbox entry is installed. AUTO decides from the source address. */
@Serializable
enum class PackageKind { AUTO, PACKAGE, PIP, NPM, GIT, URL, SCRIPT }

@Serializable
data class InstallState(val at: Long, val ok: Boolean, val location: String = "", val log: String = "")

/**
 * A tool installed once on the tool machine so chats, agents, skills and tasks reuse it instead of downloading it again.
 * [scripts]/[checks] hold per-machine commands for built-in presets (keyed by RunnerMode name).
 */
@Serializable
data class ToolPackage(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val kind: PackageKind = PackageKind.AUTO,
    /** Package name, git repo URL, download URL, or the script itself. */
    val source: String,
    val description: String = "",
    /** Optional access token for private repos/downloads; encrypted at rest. */
    val token: String = "",
    val scripts: Map<String, String> = emptyMap(),
    val checks: Map<String, String> = emptyMap(),
    /** Install results per machine (PHONE, TERMUX, REMOTE). */
    val installs: Map<String, InstallState> = emptyMap(),
)

/** Requests allowed for one model; 0 = no limit. */
@Serializable
data class ModelLimit(val perMinute: Int = 0, val perDay: Int = 0) {
    val isSet get() = perMinute > 0 || perDay > 0
    fun label() = listOfNotNull("$perMinute/min".takeIf { perMinute > 0 }, "$perDay/day".takeIf { perDay > 0 }).joinToString(" · ").ifEmpty { "no limit" }
}

/** What a task needs from a model. */
@Serializable
enum class Task { CHAT, IMAGE, AUDIO, VIDEO }

/** Where tool actions run. */
@Serializable
enum class RunnerMode { PHONE, TERMUX, REMOTE }

@Serializable
enum class Role { USER, ASSISTANT, TOOL }

/** A tool invocation requested by the model. [arguments] is the raw JSON object string. */
@Serializable
data class ToolCall(val id: String, val name: String, val arguments: String)

/** A file the model sent into the chat; [path] is the copy in Switchboard's private storage. */
@Serializable
data class Attachment(val name: String, val path: String, val size: Long, val mime: String)

@Serializable
data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: Role,
    val text: String,
    /** Which provider/model produced an assistant message, shown under the bubble. */
    val source: String? = null,
    val isError: Boolean = false,
    /** Assistant only: tools the model asked to run in this turn. */
    val toolCalls: List<ToolCall> = emptyList(),
    /** Assistant only: exact Anthropic content blocks (incl. thinking signatures) for faithful replay. */
    val anthropicBlocks: String? = null,
    /** Assistant only: the model's visible reasoning, shown collapsed under the reply (not sent back to models). */
    val thinking: String = "",
    /** Tool only: which call this result answers. */
    val toolCallId: String? = null,
    /** Tool only: files delivered to the chat by send_file. */
    val attachments: List<Attachment> = emptyList(),
)

@Serializable
data class Conversation(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "New chat",
    val messages: List<ChatMessage> = emptyList(),
    val updatedAt: Long = System.currentTimeMillis(),
    /** Project this chat belongs to (shared instructions and files), if any. */
    val projectId: String? = null,
    /** Agent answering in this chat (its prompt and model), if any. */
    val agentId: String? = null,
)

// ---------- connectors ----------

@Serializable
enum class AuthType { NONE, BEARER, HEADER, BASIC, OAUTH }

/** OAuth 2.0 client settings. Tokens live separately in TokenStore so background jobs can refresh them safely. */
@Serializable
data class OAuthConfig(
    val authUrl: String = "",
    val tokenUrl: String = "",
    val clientId: String = "",
    /** Encrypted at rest. Leave empty for public clients (PKCE). */
    val clientSecret: String = "",
    val scopes: String = "",
)

@Serializable
data class AuthConfig(
    val type: AuthType = AuthType.NONE,
    /** Bearer token or API key value; encrypted at rest. */
    val token: String = "",
    val headerName: String = "X-API-Key",
    val username: String = "",
    /** Encrypted at rest. */
    val password: String = "",
    val oauth: OAuthConfig = OAuthConfig(),
)

@Serializable
enum class ConnectorKind { MCP, REST }

@Serializable
data class Connector(
    val id: String = UUID.randomUUID().toString(),
    val kind: ConnectorKind,
    val name: String,
    /** MCP endpoint (Streamable HTTP) or REST base URL. */
    val url: String,
    /** REST: what the API does, shown to the model. */
    val description: String = "",
    val auth: AuthConfig = AuthConfig(),
    val enabled: Boolean = true,
)

// ---------- skills, agents, projects, scheduled tasks ----------

/** Reusable instructions; the model sees name + description and loads the full text with use_skill. */
@Serializable
data class Skill(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val description: String,
    val instructions: String,
    val enabled: Boolean = true,
)

@Serializable
data class Agent(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val description: String = "",
    val systemPrompt: String,
    /** Provider to use instead of the active one (null = active provider). */
    val providerId: String? = null,
    /** Model to use instead of the provider's model (blank = provider's). */
    val model: String = "",
    val useTools: Boolean = true,
)

@Serializable
data class ProjectFile(val name: String, val path: String, val size: Long)

@Serializable
data class Project(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val instructions: String = "",
    val files: List<ProjectFile> = emptyList(),
)

@Serializable
enum class Repeat { ONCE, HOURLY, DAILY, WEEKLY }

@Serializable
data class ScheduledTask(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val prompt: String,
    val repeat: Repeat = Repeat.DAILY,
    /** First run (epoch ms); later runs keep this local time of day in the user's time zone. */
    val startAt: Long,
    /** For HOURLY: run every N hours. */
    val everyHours: Int = 1,
    val enabled: Boolean = true,
    val projectId: String? = null,
    val agentId: String? = null,
    /** Let the run use tools and connectors without asking (nobody is there to approve). */
    val allowTools: Boolean = false,
    /** Append every run to one chat instead of starting a new chat each time. */
    val sameChat: Boolean = false,
)

@Serializable
data class AppSettings(
    val providers: List<Provider> = emptyList(),
    val activeProviderId: String? = null,
    val systemPrompt: String = "",
    val maxTokens: Int = 8192,
    /** Lifts the app's own limits: plain HTTP to any host, 1M token cap, max_tokens sent everywhere, raw extra body params. */
    val unrestricted: Boolean = false,
    /** JSON object merged into every request body while unrestricted, e.g. {"temperature":0.7}. */
    val extraBody: String = "",
    /** Lets the model run commands/files through a runner script the user starts (Termux or a computer). */
    val toolsEnabled: Boolean = false,
    val runnerMode: RunnerMode = RunnerMode.PHONE,
    /** Generated by the app for the runner it starts in Termux; encrypted at rest. */
    val termuxToken: String = "",
    val runnerUrl: String = "http://127.0.0.1:8765",
    /** Encrypted at rest like API keys. */
    val runnerToken: String = "",
    /** Show Approve/Deny before every tool action. */
    val askBeforeRun: Boolean = true,
    /** Max model requests per minute / per day, shared by all chats. 0 = no limit. */
    val maxPerMinute: Int = 0,
    val maxPerDay: Int = 0,
    val dailyReset: DailyReset = DailyReset.MIDNIGHT,
    /** IANA zone ID like "Asia/Manila"; empty = follow the device. */
    val timeZone: String = "",
    val connectors: List<Connector> = emptyList(),
    val skills: List<Skill> = emptyList(),
    val agents: List<Agent> = emptyList(),
    val projects: List<Project> = emptyList(),
    val tasks: List<ScheduledTask> = emptyList(),
    val packages: List<ToolPackage> = emptyList(),
    /** Offer generate_image, create_document and text_to_speech to the model. */
    val fileTools: Boolean = true,
    /** Send messages that clearly ask for a picture straight to the image model (doesn't rely on the chat model using tools). */
    val autoImage: Boolean = true,
)

/** Starting points offered in the "Add provider" dialog. Users can edit every field. */
object Presets {
    val all = listOf(
        Provider(name = "Anthropic", kind = ApiKind.ANTHROPIC, baseUrl = "https://api.anthropic.com/v1", model = "claude-opus-5-5"),
        Provider(name = "OpenRouter", kind = ApiKind.OPENAI_COMPATIBLE, baseUrl = "https://openrouter.ai/api/v1", model = "anthropic/claude-opus-5-5"),
        Provider(name = "OpenAI", kind = ApiKind.OPENAI_COMPATIBLE, baseUrl = "https://api.openai.com/v1", model = ""),
        Provider(name = "Ollama (emulator host)", kind = ApiKind.OPENAI_COMPATIBLE, baseUrl = "http://10.0.2.2:11434/v1", model = "llama3.2"),
        Provider(name = "Custom (OpenAI-compatible)", kind = ApiKind.OPENAI_COMPATIBLE, baseUrl = "https://", model = ""),
    )
}
