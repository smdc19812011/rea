package app.switchboard.engine

import app.switchboard.data.ChatMessage
import app.switchboard.data.Provider
import app.switchboard.data.Role
import app.switchboard.data.ToolCall
import app.switchboard.net.ChatClient
import app.switchboard.net.RequestOptions
import app.switchboard.net.StreamEvent
import app.switchboard.net.ToolResult
import app.switchboard.net.isRetryable
import kotlinx.coroutines.delay

/**
 * Runs the model + tool loop without a screen: used for sub-agents (run_agent) and scheduled tasks.
 * Returns only the messages it added. [beforeRequest] applies the model's request limits.
 */
class AgentRunner(private val client: ChatClient, private val beforeRequest: suspend (Provider) -> Unit) {
    suspend fun run(
        provider: Provider, opts: RequestOptions, history: List<ChatMessage>,
        execute: suspend (ToolCall) -> ToolResult, maxSteps: Int = 15, onProgress: (String) -> Unit = {},
    ): List<ChatMessage> {
        val added = mutableListOf<ChatMessage>()
        repeat(maxSteps) { step ->
            val end = turn(provider, opts, history + added)
            added += ChatMessage(role = Role.ASSISTANT, text = end.text, source = "${provider.name} · ${provider.model}",
                toolCalls = end.toolCalls, anthropicBlocks = end.anthropicBlocks)
            if (end.toolCalls.isEmpty()) return added
            for (c in end.toolCalls) {
                onProgress("step ${step + 1}: ${c.name}")
                val r = execute(c)
                added += ChatMessage(role = Role.TOOL, text = r.text, toolCallId = c.id, isError = r.isError, attachments = r.attachments)
            }
        }
        added += ChatMessage(role = Role.ASSISTANT, text = "Stopped after $maxSteps steps.", isError = true)
        return added
    }

    /** One model turn with up to 3 retries on dropped connections / overloaded servers. */
    private suspend fun turn(provider: Provider, opts: RequestOptions, msgs: List<ChatMessage>): StreamEvent.TurnEnd {
        val backoff = listOf(3_000L, 10_000L, 30_000L)
        var attempt = 0
        while (true) {
            beforeRequest(provider)
            try {
                var end: StreamEvent.TurnEnd? = null
                client.stream(provider, msgs, opts).collect { if (it is StreamEvent.TurnEnd) end = it }
                return end ?: StreamEvent.TurnEnd("(empty response)", emptyList(), null)
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (e: Exception) {
                if (attempt >= backoff.size || !isRetryable(e)) throw e
                delay(backoff[attempt++])
            }
        }
    }
}

/** Final answer text of a run: the last assistant message's text. */
fun List<ChatMessage>.finalText(): String = lastOrNull { it.role == Role.ASSISTANT && it.text.isNotBlank() }?.text ?: "(no answer)"
