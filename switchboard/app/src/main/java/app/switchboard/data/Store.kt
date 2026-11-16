package app.switchboard.data

import android.content.Context
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Plain JSON files in app-private storage. API keys are encrypted before they reach disk;
 * in memory, [loadSettings] hands back decrypted keys.
 */
class Store(context: Context) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val dir = File(context.filesDir, "chats").apply { mkdirs() }
    private val settingsFile = File(context.filesDir, "settings.json")

    fun loadSettings(): AppSettings {
        val raw = runCatching { json.decodeFromString<AppSettings>(settingsFile.readText()) }.getOrNull()
            ?: return AppSettings()
        return raw.copy(
            // Older versions stored only one model: make it the first chat choice.
            providers = raw.providers.map { p ->
                p.copy(apiKey = SecretBox.decrypt(p.apiKey), chatModels = p.chatModels.ifEmpty { listOfNotNull(p.model.takeIf { it.isNotBlank() }) })
            },
            runnerToken = SecretBox.decrypt(raw.runnerToken),
            termuxToken = SecretBox.decrypt(raw.termuxToken),
            connectors = raw.connectors.map { it.copy(auth = it.auth.mapSecrets(SecretBox::decrypt)) },
        )
    }

    fun saveSettings(s: AppSettings) {
        val enc = s.copy(
            providers = s.providers.map { it.copy(apiKey = SecretBox.encrypt(it.apiKey)) },
            runnerToken = SecretBox.encrypt(s.runnerToken),
            termuxToken = SecretBox.encrypt(s.termuxToken),
            connectors = s.connectors.map { it.copy(auth = it.auth.mapSecrets(SecretBox::encrypt)) },
        )
        atomicWrite(settingsFile, json.encodeToString(AppSettings.serializer(), enc))
    }

    private fun AuthConfig.mapSecrets(f: (String) -> String) =
        copy(token = f(token), password = f(password), oauth = oauth.copy(clientSecret = f(oauth.clientSecret)))

    fun loadConversation(id: String): Conversation? =
        runCatching { json.decodeFromString<Conversation>(File(dir, "$id.json").readText()) }.getOrNull()

    fun loadConversations(): List<Conversation> =
        dir.listFiles { f -> f.extension == "json" }.orEmpty()
            .mapNotNull { runCatching { json.decodeFromString<Conversation>(it.readText()) }.getOrNull() }
            .sortedByDescending { it.updatedAt }

    fun save(c: Conversation) = atomicWrite(File(dir, "${c.id}.json"), json.encodeToString(Conversation.serializer(), c))

    fun delete(id: String) { File(dir, "$id.json").delete() }

    private fun atomicWrite(target: File, text: String) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(target)) { target.delete(); tmp.renameTo(target) }
    }
}
