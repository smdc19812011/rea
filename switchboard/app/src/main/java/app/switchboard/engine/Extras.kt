package app.switchboard.engine

import app.switchboard.data.Agent
import app.switchboard.data.Project
import app.switchboard.data.Skill
import app.switchboard.net.ToolResult
import app.switchboard.net.ToolSpec
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** Tool definitions and prompt text for skills, agents, projects and scheduled tasks (shared by chat and background runs). */
object Extras {
    const val USE_SKILL = "use_skill"
    const val CREATE_SKILL = "create_skill"
    const val RUN_AGENT = "run_agent"
    const val CREATE_AGENT = "create_agent"
    const val SCHEDULE_TASK = "schedule_task"

    private fun schema(required: List<String>, vararg props: Pair<String, String>) = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") { props.forEach { (k, d) -> putJsonObject(k) { put("type", if (k in intProps) "integer" else if (k in boolProps) "boolean" else "string"); put("description", d) } } }
        put("required", JsonArray(required.map { JsonPrimitive(it) }))
    }
    private val intProps = setOf("every_hours")
    private val boolProps = setOf("allow_tools")

    val useSkill = ToolSpec(USE_SKILL, "Load the full instructions of one of the user's skills before doing a task it covers.",
        schema(listOf("name"), "name" to "Skill name exactly as listed"))
    val createSkill = ToolSpec(CREATE_SKILL, "Save reusable instructions as a new skill (or replace one with the same name) so future chats can use them.",
        schema(listOf("name", "description", "instructions"), "name" to "Short name", "description" to "When to use it (one sentence)",
            "instructions" to "The full instructions, in Markdown"))
    val runAgent = ToolSpec(RUN_AGENT, "Hand a self-contained task to one of the user's agents and get its final answer. Give it everything it needs in 'task'.",
        schema(listOf("agent", "task"), "agent" to "Agent name exactly as listed", "task" to "Complete instructions for the agent"))
    val createAgent = ToolSpec(CREATE_AGENT, "Create a reusable agent (a named assistant with its own instructions) or replace one with the same name.",
        schema(listOf("name", "system_prompt"), "name" to "Agent name", "description" to "What it's for", "system_prompt" to "The agent's instructions",
            "model" to "Optional model ID to use instead of the current one"))
    val scheduleTask = ToolSpec(SCHEDULE_TASK, "Schedule a prompt to run automatically later or on repeat (the result is saved as a chat and the user is notified).",
        schema(listOf("name", "prompt", "start", "repeat"), "name" to "Short name", "prompt" to "What to do at each run, written as a complete request",
            "start" to "First run in the user's time zone, format yyyy-MM-dd HH:mm", "repeat" to "once, hourly, daily or weekly",
            "every_hours" to "For hourly: run every N hours (default 1)", "allow_tools" to "Let runs use tools/connectors without asking (default false)"))

    fun skillsNote(skills: List<Skill>): String = skills.filter { it.enabled }.takeIf { it.isNotEmpty() }?.let { list ->
        "The user's skills (call use_skill with the name to load one before relying on it):\n" + list.joinToString("\n") { "- ${it.name}: ${it.description}" }
    }.orEmpty()

    fun agentsNote(agents: List<Agent>): String = agents.takeIf { it.isNotEmpty() }?.let { list ->
        "The user's agents (run_agent hands one a task):\n" + list.joinToString("\n") { "- ${it.name}: ${it.description.ifBlank { it.systemPrompt.take(100) }}" }
    }.orEmpty()

    fun useSkill(args: JsonObject, skills: List<Skill>): ToolResult {
        val name = (args["name"] as? JsonPrimitive)?.content.orEmpty()
        val s = skills.firstOrNull { it.enabled && it.name.equals(name, ignoreCase = true) }
            ?: return ToolResult("No skill named '$name'. Available: ${skills.filter { it.enabled }.joinToString { it.name }}", true)
        return ToolResult("Skill: ${s.name}\n\n${s.instructions}")
    }

    /** Project instructions plus the text of its files (up to ~100 KB in total). */
    fun projectNote(p: Project?): String {
        if (p == null) return ""
        val sb = StringBuilder("This chat is in the project \"${p.name}\".")
        if (p.instructions.isNotBlank()) sb.append("\nProject instructions:\n${p.instructions}")
        var budget = 100_000
        for (f in p.files) {
            val file = File(f.path)
            if (!file.isFile || budget <= 0) continue
            val text = runCatching { file.readText() }.getOrNull()?.takeIf { t -> t.count { it == '\u0000' } == 0 } ?: continue
            val part = text.take(budget); budget -= part.length
            sb.append("\n\n--- Project file: ${f.name} ---\n$part")
        }
        return sb.toString()
    }

    fun timeNote(zone: ZoneId): String =
        "Current date and time for the user: ${ZonedDateTime.now(zone).format(DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy, h:mm a"))} (${zone.id})."
}
