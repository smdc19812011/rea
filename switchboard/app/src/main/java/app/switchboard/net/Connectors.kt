package app.switchboard.net

import app.switchboard.data.AuthConfig
import app.switchboard.data.AuthType
import app.switchboard.data.Connector
import app.switchboard.data.ConnectorKind
import app.switchboard.data.OAuthConfig
import app.switchboard.data.ToolCall
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

@Serializable
data class Tokens(val access: String, val refresh: String = "", val expiresAt: Long = 0)

/**
 * OAuth tokens per connector, in their own file so the UI and background jobs can both refresh them.
 * [encrypt]/[decrypt] wrap the whole file (Android Keystore on device; identity in tests).
 */
class TokenStore(private val file: File, private val encrypt: (String) -> String, private val decrypt: (String) -> String) {
    private val json = Json { ignoreUnknownKeys = true }
    @Synchronized private fun load(): MutableMap<String, Tokens> =
        runCatching { json.decodeFromString<Map<String, Tokens>>(decrypt(file.readText())).toMutableMap() }.getOrElse { mutableMapOf() }
    @Synchronized private fun save(m: Map<String, Tokens>) { file.parentFile?.mkdirs(); file.writeText(encrypt(json.encodeToString(m))) }
    @Synchronized fun get(id: String): Tokens? = load()[id]
    @Synchronized fun put(id: String, t: Tokens) = save(load().apply { put(id, t) })
    @Synchronized fun remove(id: String) = save(load().apply { remove(id) })
}

/**
 * Remote tools: MCP servers (Streamable HTTP transport) and REST APIs, with none/bearer/header/basic/OAuth 2.0 auth.
 * MCP tools are exposed as `mcp__<server>__<tool>`, REST APIs as `api__<name>`.
 */
class Connectors(private val tokens: TokenStore) {
    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS).build()
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val jsonType = "application/json".toMediaType()

    data class McpTool(val name: String, val description: String, val schema: JsonObject)
    private val mcpTools = ConcurrentHashMap<String, List<McpTool>>()
    private val mcpSession = ConcurrentHashMap<String, String>()
    private val mcpReady = ConcurrentHashMap<String, Boolean>()
    /** Exposed tool name -> (connector id, real tool name). */
    private val routes = ConcurrentHashMap<String, Pair<String, String>>()
    private var rpcId = 1

    companion object {
        const val REDIRECT_URI = "switchboard://oauth"
        const val PROTOCOL = "2025-06-18"
        fun slug(s: String, max: Int) = s.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').take(max).ifEmpty { "x" }
    }

    fun owns(toolName: String) = toolName.startsWith("mcp__") || toolName.startsWith("api__")

    /** Tool definitions for every enabled connector. MCP tool lists are fetched once and cached; failures are reported, not thrown. */
    suspend fun toolSpecs(connectors: List<Connector>): Pair<List<ToolSpec>, List<String>> {
        val specs = mutableListOf<ToolSpec>(); val problems = mutableListOf<String>()
        for (c in connectors.filter { it.enabled }) when (c.kind) {
            ConnectorKind.REST -> {
                val name = "api__" + slug(c.name, 40)
                routes[name] = c.id to ""
                specs += ToolSpec(name, "Call the ${c.name} REST API (base URL ${c.url}). ${c.description}".trim(), restSchema)
            }
            ConnectorKind.MCP -> failedAt[c.id]?.takeIf { System.currentTimeMillis() - it.first < 5 * 60_000 }?.let { problems += "${c.name}: ${it.second}" } ?: runCatching { mcpTools[c.id] ?: loadMcpTools(c) }
                .onFailure { failedAt[c.id] = System.currentTimeMillis() to (it.message ?: "unreachable") }
                .onSuccess { tools ->
                    tools.forEach { t ->
                        val name = ("mcp__" + slug(c.name, 20) + "__" + t.name.replace(Regex("[^A-Za-z0-9_-]"), "_")).take(64)
                        routes[name] = c.id to t.name
                        specs += ToolSpec(name, "[${c.name}] ${t.description}".take(1024), t.schema)
                    }
                }
                .onFailure { problems += "${c.name}: ${it.message}" }
        }
        return specs to problems
    }

    suspend fun execute(call: ToolCall, connectors: List<Connector>, unrestricted: Boolean): ToolResult {
        val (cid, real) = routes[call.name] ?: return ToolResult("Unknown connector tool ${call.name}.", true)
        val c = connectors.firstOrNull { it.id == cid && it.enabled } ?: return ToolResult("That connector was removed or turned off.", true)
        val args = runCatching { json.parseToJsonElement(call.arguments).jsonObject }.getOrElse { return ToolResult("Arguments were not valid JSON.", true) }
        return runCatching {
            try {
                when (c.kind) {
                    ConnectorKind.MCP -> callMcpTool(c, real, args)
                    ConnectorKind.REST -> callRest(c, args, unrestricted)
                }
            } catch (e: kotlinx.coroutines.CancellationException) { http.dispatcher.cancelAll(); throw e }
        }.getOrElse { ToolResult(it.message ?: it.javaClass.simpleName, true) }
    }

    fun forget(connectorId: String) { mcpTools.remove(connectorId); mcpSession.remove(connectorId); mcpReady.remove(connectorId); failedAt.remove(connectorId) }

    /** Connectors whose tool list couldn't load: skipped for 5 minutes instead of slowing every message down. */
    private val failedAt = ConcurrentHashMap<String, Pair<Long, String>>()

    // ---------- auth ----------

    private suspend fun authHeaders(c: Connector): Map<String, String> {
        val a = c.auth
        return when (a.type) {
            AuthType.NONE -> emptyMap()
            AuthType.BEARER -> mapOf("Authorization" to "Bearer ${a.token}")
            AuthType.HEADER -> mapOf(a.headerName.ifBlank { "X-API-Key" } to a.token)
            AuthType.BASIC -> mapOf("Authorization" to "Basic " + Base64.getEncoder().encodeToString("${a.username}:${a.password}".toByteArray()))
            AuthType.OAUTH -> {
                var t = tokens.get(c.id) ?: throw ApiException("${c.name} isn't signed in. Open Settings > Connectors > ${c.name} and tap Connect.")
                if (t.expiresAt != 0L && t.expiresAt < System.currentTimeMillis() + 60_000) t = refresh(c, t)
                mapOf("Authorization" to "Bearer ${t.access}")
            }
        }
    }

    private suspend fun refresh(c: Connector, t: Tokens): Tokens {
        if (t.refresh.isBlank() || c.auth.oauth.tokenUrl.isBlank()) throw ApiException("${c.name}'s sign-in expired. Tap Connect in Settings > Connectors.")
        val form = FormBody.Builder().add("grant_type", "refresh_token").add("refresh_token", t.refresh).add("client_id", c.auth.oauth.clientId)
        if (c.auth.oauth.clientSecret.isNotBlank()) form.add("client_secret", c.auth.oauth.clientSecret)
        val fresh = tokenRequest(c.auth.oauth.tokenUrl, form.build(), keepRefresh = t.refresh)
        tokens.put(c.id, fresh)
        return fresh
    }

    // ---------- OAuth 2.0 (authorization code + PKCE) ----------

    data class PendingAuth(val connectorId: String, val state: String, val verifier: String, val config: OAuthConfig, val resource: String?)

    /**
     * Fills in missing endpoints from the server's metadata (MCP protected-resource metadata, then RFC 8414 / OpenID discovery)
     * and registers a client (RFC 7591) when no client ID is set and the server allows it.
     */
    suspend fun discover(c: Connector): OAuthConfig = withContext(Dispatchers.IO) {
        var cfg = c.auth.oauth
        val base = c.url.toHttpUrlOrNull() ?: throw ApiException("Invalid URL: ${c.url}")
        if (cfg.authUrl.isBlank() || cfg.tokenUrl.isBlank()) {
            val origin = "${base.scheme}://${base.host}${if (base.port != HttpUrl.defaultPort(base.scheme)) ":${base.port}" else ""}"
            val path = base.encodedPath.trimEnd('/')
            val asUrl = listOf("$origin/.well-known/oauth-protected-resource$path", "$origin/.well-known/oauth-protected-resource")
                .firstNotNullOfOrNull { getJson(it)?.get("authorization_servers")?.let { a -> (a as? JsonArray)?.firstOrNull()?.let { s -> (s as JsonPrimitive).content } } }
                ?: origin
            val asBase = asUrl.toHttpUrlOrNull() ?: throw ApiException("Bad authorization server URL $asUrl")
            val asOrigin = "${asBase.scheme}://${asBase.host}${if (asBase.port != HttpUrl.defaultPort(asBase.scheme)) ":${asBase.port}" else ""}"
            val asPath = asBase.encodedPath.trimEnd('/')
            val meta = listOf("$asOrigin/.well-known/oauth-authorization-server$asPath", "$asOrigin/.well-known/oauth-authorization-server",
                "$asOrigin/.well-known/openid-configuration$asPath", "$asOrigin/.well-known/openid-configuration")
                .firstNotNullOfOrNull { getJson(it)?.takeIf { m -> m.str("authorization_endpoint") != null } }
                ?: throw ApiException("Couldn't find OAuth settings for ${c.name}. Fill in the authorization and token URLs yourself.")
            cfg = cfg.copy(authUrl = meta.str("authorization_endpoint")!!, tokenUrl = meta.str("token_endpoint") ?: cfg.tokenUrl)
            if (cfg.clientId.isBlank()) meta.str("registration_endpoint")?.let { reg -> cfg = register(reg, cfg) }
        }
        if (cfg.clientId.isBlank()) throw ApiException("${c.name} needs an OAuth client ID. Create an app with redirect URI $REDIRECT_URI on the service and paste its client ID.")
        cfg
    }

    private fun register(endpoint: String, cfg: OAuthConfig): OAuthConfig {
        val body = buildJsonObject {
            put("client_name", "Switchboard")
            putJsonArray("redirect_uris") { add(JsonPrimitive(REDIRECT_URI)) }
            putJsonArray("grant_types") { add(JsonPrimitive("authorization_code")); add(JsonPrimitive("refresh_token")) }
            putJsonArray("response_types") { add(JsonPrimitive("code")) }
            put("token_endpoint_auth_method", "none")
        }
        http.newCall(Request.Builder().url(endpoint).post(body.toString().toRequestBody(jsonType)).build()).execute().use { r ->
            val o = runCatching { json.parseToJsonElement(r.body!!.string()).jsonObject }.getOrNull()
            if (!r.isSuccessful || o?.str("client_id") == null) throw ApiException("Client registration failed (HTTP ${r.code}). Enter a client ID manually.")
            return cfg.copy(clientId = o.str("client_id")!!, clientSecret = o.str("client_secret") ?: cfg.clientSecret)
        }
    }

    fun beginAuth(c: Connector, cfg: OAuthConfig): Pair<String, PendingAuth> {
        val rnd = SecureRandom()
        fun b64(b: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(b)
        val verifier = b64(ByteArray(32).also(rnd::nextBytes))
        val state = b64(ByteArray(16).also(rnd::nextBytes))
        val challenge = b64(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
        val resource = c.url.takeIf { c.kind == ConnectorKind.MCP }
        val url = (cfg.authUrl.toHttpUrlOrNull() ?: throw ApiException("Invalid authorization URL")).newBuilder()
            .addQueryParameter("response_type", "code").addQueryParameter("client_id", cfg.clientId)
            .addQueryParameter("redirect_uri", REDIRECT_URI).addQueryParameter("state", state)
            .addQueryParameter("code_challenge", challenge).addQueryParameter("code_challenge_method", "S256")
            .apply { if (cfg.scopes.isNotBlank()) addQueryParameter("scope", cfg.scopes); resource?.let { addQueryParameter("resource", it) } }
            .build().toString()
        return url to PendingAuth(c.id, state, verifier, cfg, resource)
    }

    suspend fun finishAuth(p: PendingAuth, code: String) = withContext(Dispatchers.IO) {
        val form = FormBody.Builder().add("grant_type", "authorization_code").add("code", code).add("redirect_uri", REDIRECT_URI)
            .add("client_id", p.config.clientId).add("code_verifier", p.verifier)
        if (p.config.clientSecret.isNotBlank()) form.add("client_secret", p.config.clientSecret)
        p.resource?.let { form.add("resource", it) }
        tokens.put(p.connectorId, tokenRequest(p.config.tokenUrl, form.build(), keepRefresh = ""))
        forget(p.connectorId)
    }

    private fun tokenRequest(url: String, form: FormBody, keepRefresh: String): Tokens =
        http.newCall(Request.Builder().url(url).header("Accept", "application/json").post(form).build()).execute().use { r ->
            val text = r.body?.string().orEmpty()
            val o = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
            val access = o?.str("access_token") ?: throw ApiException("Token request failed (HTTP ${r.code}): ${o?.str("error_description") ?: o?.str("error") ?: text.take(200)}")
            val expires = o.str("expires_in")?.toLongOrNull()?.let { System.currentTimeMillis() + it * 1000 } ?: 0L
            Tokens(access, o.str("refresh_token") ?: keepRefresh, expires)
        }

    private fun getJson(url: String): JsonObject? = runCatching {
        http.newCall(Request.Builder().url(url).header("Accept", "application/json").build()).execute().use { r ->
            if (!r.isSuccessful) null else json.parseToJsonElement(r.body!!.string()).jsonObject
        }
    }.getOrNull()

    // ---------- MCP (Streamable HTTP) ----------

    suspend fun loadMcpTools(c: Connector): List<McpTool> = interruptibly({ http.dispatcher.cancelAll() }) { kotlinx.coroutines.runBlocking { loadMcpToolsBlocking(c) } }

    private suspend fun loadMcpToolsBlocking(c: Connector): List<McpTool> = withContext(Dispatchers.IO) {
        ensureInit(c)
        val out = mutableListOf<McpTool>(); var cursor: String? = null
        do {
            val res = rpc(c, "tools/list", cursor?.let { buildJsonObject { put("cursor", it) } }) ?: break
            res["tools"]?.jsonArray?.forEach { t ->
                val o = t.jsonObject
                out += McpTool(o.str("name") ?: return@forEach, o.str("description").orEmpty(),
                    (o["inputSchema"] as? JsonObject) ?: buildJsonObject { put("type", "object"); putJsonObject("properties") {} })
            }
            cursor = res.str("nextCursor")
        } while (cursor != null && out.size < 500)
        mcpTools[c.id] = out
        out
    }

    private suspend fun callMcpTool(c: Connector, tool: String, args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        ensureInit(c)
        val res = rpc(c, "tools/call", buildJsonObject { put("name", tool); put("arguments", args) }) ?: return@withContext ToolResult("No result.", true)
        val parts = (res["content"] as? JsonArray).orEmpty().map { el ->
            val o = el.jsonObject
            when (o.str("type")) {
                "text" -> o.str("text").orEmpty()
                "image", "audio" -> "[${o.str("type")} ${o.str("mimeType") ?: ""}, ${o.str("data")?.length ?: 0} base64 chars, not shown]"
                "resource" -> (o["resource"] as? JsonObject)?.let { r -> r.str("text") ?: "[resource ${r.str("uri")}]" }.orEmpty()
                "resource_link" -> "[link ${o.str("uri")}] ${o.str("name") ?: ""}"
                else -> o.toString()
            }
        }
        val text = parts.joinToString("\n").ifBlank { res["structuredContent"]?.toString() ?: "(empty result)" }
        ToolResult(text.take(50_000), isError = res.str("isError") == "true")
    }

    private fun ensureInit(c: Connector) {
        if (mcpReady[c.id] == true) return
        mcpSession.remove(c.id)
        rpcBlocking(c, "initialize", buildJsonObject {
            put("protocolVersion", PROTOCOL)
            putJsonObject("capabilities") {}
            putJsonObject("clientInfo") { put("name", "Switchboard"); put("version", "1.10") }
        }, notification = false)
        rpcBlocking(c, "notifications/initialized", null, notification = true)
        mcpReady[c.id] = true
    }

    private suspend fun rpc(c: Connector, method: String, params: JsonObject?): JsonObject? = try {
        rpcBlocking(c, method, params, notification = false)
    } catch (e: SessionExpired) {
        mcpReady.remove(c.id); ensureInit(c); rpcBlocking(c, method, params, notification = false)
    }

    private class SessionExpired : Exception()

    /** One JSON-RPC exchange. The server may answer with plain JSON or an SSE stream carrying the response. */
    private fun rpcBlocking(c: Connector, method: String, params: JsonObject?, notification: Boolean): JsonObject? {
        val id = synchronized(this) { rpcId++ }
        val body = buildJsonObject {
            put("jsonrpc", "2.0"); put("method", method)
            if (!notification) put("id", id)
            params?.let { put("params", it) }
        }
        val headers = kotlinx.coroutines.runBlocking { authHeaders(c) }
        val req = Request.Builder().url(c.url).post(body.toString().toRequestBody(jsonType))
            .header("Accept", "application/json, text/event-stream").header("MCP-Protocol-Version", PROTOCOL)
            .apply { mcpSession[c.id]?.let { header("Mcp-Session-Id", it) }; headers.forEach { (k, v) -> header(k, v) } }
            .build()
        http.newCall(req).execute().use { r ->
            r.header("Mcp-Session-Id")?.let { mcpSession[c.id] = it }
            if (r.code == 404 && mcpSession[c.id] != null && method != "initialize") throw SessionExpired()
            if (r.code == 401 || r.code == 403) throw ApiException("${c.name} refused access (HTTP ${r.code}). Check its sign-in in Settings > Connectors.")
            if (!r.isSuccessful) throw ApiException("${c.name}: HTTP ${r.code} ${r.body?.string()?.take(200).orEmpty()}")
            if (notification) return null
            val type = r.header("Content-Type").orEmpty()
            val message: JsonObject = if ("text/event-stream" in type) {
                val src = r.body!!.source(); var found: JsonObject? = null
                while (found == null) {
                    val line = src.readUtf8Line() ?: break
                    if (!line.startsWith("data:")) continue
                    val o = runCatching { json.parseToJsonElement(line.removePrefix("data:").trim()).jsonObject }.getOrNull() ?: continue
                    if (o["id"]?.let { (it as? JsonPrimitive)?.content } == id.toString()) found = o
                }
                found ?: throw ApiException("${c.name} closed the stream without answering $method.")
            } else json.parseToJsonElement(r.body!!.string()).jsonObject
            (message["error"] as? JsonObject)?.let { throw ApiException("${c.name}: ${it.str("message") ?: it}") }
            return message["result"] as? JsonObject
        }
    }

    // ---------- REST ----------

    private val restSchema = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("method") { put("type", "string"); put("description", "GET (default), POST, PUT, PATCH or DELETE") }
            putJsonObject("path") { put("type", "string"); put("description", "Path after the base URL, e.g. /v1/items") }
            putJsonObject("query") { put("type", "object"); put("description", "Query parameters") }
            putJsonObject("headers") { put("type", "object"); put("description", "Extra headers") }
            putJsonObject("body") { put("description", "JSON body (object/array) or text") }
        }
        putJsonArray("required") { add(JsonPrimitive("path")) }
    }

    private suspend fun callRest(c: Connector, a: JsonObject, unrestricted: Boolean): ToolResult = withContext(Dispatchers.IO) {
        val base = c.url.trimEnd('/') + "/" + (a.str("path") ?: "").trimStart('/')
        val url = (base.toHttpUrlOrNull() ?: throw ApiException("Invalid URL $base")).newBuilder()
            .apply { (a["query"] as? JsonObject)?.forEach { (k, v) -> addQueryParameter(k, (v as? JsonPrimitive)?.content ?: v.toString()) } }.build()
        if (!url.isHttps && !unrestricted && !isLocalHost(url.host)) throw ApiException("Plain http:// to the internet needs Unrestricted mode.")
        val method = (a.str("method") ?: "GET").uppercase()
        val bodyEl: JsonElement? = a["body"]
        val body = when {
            bodyEl == null -> if (method in setOf("POST", "PUT", "PATCH")) ByteArray(0).toRequestBody() else null
            bodyEl is JsonPrimitive && bodyEl.isString -> bodyEl.content.toRequestBody("text/plain".toMediaType())
            else -> bodyEl.toString().toRequestBody(jsonType)
        }
        val req = Request.Builder().url(url).method(method, body).header("Accept", "application/json, */*")
            .apply { authHeaders(c).forEach { (k, v) -> header(k, v) }; (a["headers"] as? JsonObject)?.forEach { (k, v) -> header(k, (v as? JsonPrimitive)?.content ?: v.toString()) } }
            .build()
        http.newCall(req).execute().use { r ->
            val text = r.body?.string().orEmpty()
            ToolResult("HTTP ${r.code}\n${text.take(50_000)}", isError = !r.isSuccessful)
        }
    }

    /** Quick check from Settings: MCP lists tools; REST does a GET on the base URL. */
    suspend fun test(c: Connector): String = withContext(Dispatchers.IO) {
        forget(c.id)
        when (c.kind) {
            ConnectorKind.MCP -> loadMcpTools(c).let { t -> "Connected: ${t.size} tools" + if (t.isNotEmpty()) " (${t.take(8).joinToString { it.name }}${if (t.size > 8) ", …" else ""})" else "" }
            ConnectorKind.REST -> {
                val req = Request.Builder().url(c.url).apply { authHeaders(c).forEach { (k, v) -> header(k, v) } }.build()
                http.newCall(req).execute().use { r -> "Reached ${c.url}: HTTP ${r.code}" }
            }
        }
    }
}
