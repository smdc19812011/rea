package app.switchboard.engine

import app.switchboard.data.ApiKind
import app.switchboard.data.AuthConfig
import app.switchboard.data.AuthType
import app.switchboard.data.ChatMessage
import app.switchboard.data.Connector
import app.switchboard.data.ConnectorKind
import app.switchboard.data.Project
import app.switchboard.data.ProjectFile
import app.switchboard.data.Provider
import app.switchboard.data.Repeat
import app.switchboard.data.Role
import app.switchboard.data.ScheduledTask
import app.switchboard.data.Skill
import app.switchboard.data.ToolCall
import app.switchboard.net.ChatClient
import app.switchboard.net.Connectors
import app.switchboard.net.RequestOptions
import app.switchboard.net.TokenStore
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.net.ServerSocket
import java.time.ZoneId
import java.time.ZonedDateTime

class ConnectorsAndEngineTest {
    companion object {
        private lateinit var proc: Process
        private var port = 0
        private val base get() = "http://127.0.0.1:$port"

        @BeforeClass @JvmStatic fun start() {
            port = ServerSocket(0).use { it.localPort }
            proc = ProcessBuilder("python3", File("src/test/resources/mock_services.py").absolutePath, "$port").redirectErrorStream(true).start()
            assertEquals("ready", proc.inputStream.bufferedReader().readLine())
        }
        @AfterClass @JvmStatic fun stop() { proc.destroy() }
    }

    private fun tokens() = TokenStore(File.createTempFile("tok", ".json").apply { delete() }, { it }, { it })

    @Test fun mcpWithOAuthDiscoveryRegistrationPkceAndRefresh() = runBlocking {
        val conn = Connectors(tokens())
        val c = Connector(kind = ConnectorKind.MCP, name = "Test", url = "$base/mcp", auth = AuthConfig(type = AuthType.OAUTH))
        val cfg = conn.discover(c)
        assertEquals("dyn-client", cfg.clientId)
        assertEquals("$base/auth/token", cfg.tokenUrl)
        val (url, pending) = conn.beginAuth(c, cfg)
        assertTrue(url, url.startsWith("$base/auth/authorize?") && "code_challenge_method=S256" in url && "redirect_uri=switchboard%3A%2F%2Foauth" in url && "resource=" in url)
        conn.finishAuth(pending, "abc") // token expires in 1 s, so the next call must refresh it
        val c2 = c.copy(auth = c.auth.copy(oauth = cfg))
        val (specs, problems) = conn.toolSpecs(listOf(c2))
        assertEquals(emptyList<String>(), problems)
        assertEquals(listOf("mcp__test__echo"), specs.map { it.name })
        assertTrue(specs[0].description.contains("Echo text back"))
        val r = conn.execute(ToolCall("1", "mcp__test__echo", """{"text":"hi"}"""), listOf(c2), unrestricted = false)
        assertEquals("echo: hi", r.text)
        val stats = java.net.URL("$base/stats").readText()
        assertTrue(stats, stats.contains("\"refreshes\": 1"))
    }

    @Test fun mcpWithoutSignInExplainsWhatToDo() = runBlocking {
        val conn = Connectors(tokens())
        val c = Connector(kind = ConnectorKind.MCP, name = "Test", url = "$base/mcp", auth = AuthConfig(type = AuthType.OAUTH))
        val (_, problems) = conn.toolSpecs(listOf(c))
        assertTrue(problems.toString(), problems.single().contains("isn't signed in"))
    }

    @Test fun restApiWithHeaderKey() = runBlocking {
        val conn = Connectors(tokens())
        val c = Connector(kind = ConnectorKind.REST, name = "Items API", url = "$base/api", auth = AuthConfig(type = AuthType.HEADER, headerName = "X-API-Key", token = "k"))
        val (specs, _) = conn.toolSpecs(listOf(c))
        assertEquals("api__items_api", specs.single().name)
        val r = conn.execute(ToolCall("1", "api__items_api", """{"path":"/items","query":{"q":"x"}}"""), listOf(c), unrestricted = false)
        assertTrue(r.text, r.text.startsWith("HTTP 200") && r.text.contains("\"q\": \"x\"") && !r.isError)
        val bad = conn.execute(ToolCall("2", "api__items_api", """{"path":"/items"}"""), listOf(c.copy(auth = c.auth.copy(token = "wrong"))), unrestricted = false)
        assertTrue(bad.isError)
    }

    @Test fun agentRunnerLoopsThroughToolsToFinalAnswer() = runBlocking {
        val conn = Connectors(tokens())
        val c = Connector(kind = ConnectorKind.REST, name = "unused", url = "$base/api")
        val provider = Provider(name = "Mock", kind = ApiKind.OPENAI_COMPATIBLE, baseUrl = "$base/v1", model = "m")
        var calls = 0
        val added = AgentRunner(ChatClient()) { calls++ }.run(provider, RequestOptions(), listOf(ChatMessage(role = Role.USER, text = "go")),
            execute = { call -> assertEquals("mcp__test__echo", call.name); app.switchboard.net.ToolResult("echo: ping") })
        assertEquals(listOf(Role.ASSISTANT, Role.TOOL, Role.ASSISTANT), added.map { it.role })
        assertEquals("Done: echo: ping", added.finalText())
        assertEquals(2, calls) // rate limiter consulted once per model request
        conn.forget(c.id)
    }

    @Test fun nextRunRespectsTimeZoneAndRepeat() {
        val z = ZoneId.of("Asia/Manila")
        val start = ZonedDateTime.of(2026, 11, 1, 8, 30, 0, 0, z).toInstant().toEpochMilli()
        val now = ZonedDateTime.of(2026, 11, 15, 9, 0, 0, 0, z).toInstant().toEpochMilli()
        val daily = ScheduledTask(name = "d", prompt = "p", repeat = Repeat.DAILY, startAt = start)
        assertEquals(ZonedDateTime.of(2026, 11, 16, 8, 30, 0, 0, z).toInstant().toEpochMilli(), Scheduler.nextRun(daily, z, now, null))
        val weekly = daily.copy(repeat = Repeat.WEEKLY)
        assertEquals(ZonedDateTime.of(2026, 11, 22, 8, 30, 0, 0, z).toInstant().toEpochMilli(), Scheduler.nextRun(weekly, z, now, null))
        val hourly = daily.copy(repeat = Repeat.HOURLY, everyHours = 6)
        assertEquals(ZonedDateTime.of(2026, 11, 15, 14, 30, 0, 0, z).toInstant().toEpochMilli(), Scheduler.nextRun(hourly, z, now, null))
        val once = daily.copy(repeat = Repeat.ONCE, startAt = now + 60_000)
        assertEquals(now + 60_000, Scheduler.nextRun(once, z, now, null))
        assertEquals(null, Scheduler.nextRun(once, z, now, lastRun = now + 61_000))
        // Daylight saving: a 08:30 daily task stays at 08:30 local time across the change.
        val ny = ZoneId.of("America/New_York")
        val nyStart = ZonedDateTime.of(2026, 10, 30, 8, 30, 0, 0, ny).toInstant().toEpochMilli()
        val nyNow = ZonedDateTime.of(2026, 11, 2, 9, 0, 0, 0, ny).toInstant().toEpochMilli()
        assertEquals(ZonedDateTime.of(2026, 11, 3, 8, 30, 0, 0, ny).toInstant().toEpochMilli(),
            Scheduler.nextRun(daily.copy(startAt = nyStart), ny, nyNow, null))
    }

    @Test fun skillsAndProjectContext() {
        val skills = listOf(Skill(name = "Weekly report", description = "Format for status reports", instructions = "Use three headings."),
            Skill(name = "Off", description = "x", instructions = "y", enabled = false))
        assertEquals("The user's skills (call use_skill with the name to load one before relying on it):\n- Weekly report: Format for status reports", Extras.skillsNote(skills))
        val args = Json.parseToJsonElement("""{"name":"weekly REPORT"}""").jsonObject
        assertEquals("Skill: Weekly report\n\nUse three headings.", Extras.useSkill(args, skills).text)
        assertTrue(Extras.useSkill(Json.parseToJsonElement("""{"name":"Off"}""").jsonObject, skills).isError)
        val f = File.createTempFile("notes", ".txt").apply { writeText("Budget is 5k.") }
        val note = Extras.projectNote(Project(name = "Launch", instructions = "Be brief.", files = listOf(ProjectFile("notes.txt", f.path, f.length()))))
        assertTrue(note, note.contains("project \"Launch\"") && note.contains("Be brief.") && note.contains("--- Project file: notes.txt ---\nBudget is 5k."))
    }

    @Test fun imageGenerationTriesChatOutputThenImagesApi() = runBlocking {
        val p = Provider(name = "Mock", kind = ApiKind.OPENAI_COMPATIBLE, baseUrl = "$base/v1", model = "chat")
        val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())
        val (viaChat, text) = app.switchboard.net.MediaGen().image(p, "img-model", "a dragon", false)
        assertEquals("Here you go", text)
        assertTrue(viaChat.single().bytes.copyOf(4).contentEquals(png) && viaChat.single().mime == "image/png")
        // Chat image output not supported for this model -> falls back to /images/generations.
        val (viaApi, _) = app.switchboard.net.MediaGen().image(p, "api-only", "a dragon", false)
        assertTrue(viaApi.single().bytes.copyOf(4).contentEquals(png))
        // A text-only model fails both ways with a clear reason (the app then moves to the next model).
        val err = runCatching { app.switchboard.net.MediaGen().image(p, "text/model", "a dragon", false) }.exceptionOrNull()
        assertTrue("$err", err?.message.orEmpty().contains("text/model couldn't make an image"))
    }

    @Test fun audioAndVideoGeneration() = runBlocking {
        val p = Provider(name = "Mock", kind = ApiKind.OPENAI_COMPATIBLE, baseUrl = "$base/v1", model = "chat")
        val mp3 = app.switchboard.net.MediaGen().audio(p, "voice/model", "hello", "alloy", false)
        assertEquals("audio/mpeg", mp3.mime); assertEquals("mp3", mp3.extension); assertEquals("ID3fake-mp3", mp3.bytes.decodeToString())
        val statuses = mutableListOf<String>()
        val video = app.switchboard.net.MediaGen().video(p, "openai/sora-2", "waves at sunset", false) { statuses += it }
        assertEquals("video/mp4", video.mime)
        assertTrue(statuses.toString(), statuses.first().contains("in_progress") && statuses.last().contains("completed"))
        assertTrue(runCatching { app.switchboard.net.MediaGen().video(p, "text/model", "x", false) {} }.isFailure)
    }

    @Test fun detectsModelsWithCapabilities() = runBlocking {
        val p = Provider(name = "Mock", kind = ApiKind.OPENAI_COMPATIBLE, baseUrl = "$base/v1", model = "chat")
        val models = ChatClient().listModelsDetailed(p).associateBy { it.id }
        assertEquals(setOf("text/model", "draw/model:free", "voice/model", "openai/sora-2"), models.keys)
        val t = app.switchboard.data.Task.entries
        fun fits(id: String) = t.filter { models.getValue(id).fits(it) }
        assertEquals(listOf(app.switchboard.data.Task.CHAT), fits("text/model"))
        assertEquals(listOf(app.switchboard.data.Task.CHAT, app.switchboard.data.Task.IMAGE), fits("draw/model:free"))
        assertEquals(listOf(app.switchboard.data.Task.CHAT, app.switchboard.data.Task.AUDIO), fits("voice/model"))
        assertTrue(app.switchboard.data.Task.VIDEO in fits("openai/sora-2") && app.switchboard.data.Task.CHAT !in fits("openai/sora-2"))
        assertTrue(models.getValue("draw/model:free").free && !models.getValue("text/model").free)
        assertEquals(128000, models.getValue("text/model").contextLength)
    }
}
