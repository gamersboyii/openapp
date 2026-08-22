package dev.opencode.mobile.llm

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Wire protocol a provider speaks. Everything OpenAI-compatible collapses into
 * [OPENAI], which covers OpenRouter, Groq, DeepSeek, Together, Mistral, xAI,
 * Cerebras, LM Studio, vLLM and a self-hosted Ollama behind `/v1`.
 */
@Serializable
enum class ProviderKind {
    @SerialName("openai") OPENAI,
    @SerialName("anthropic") ANTHROPIC,
    @SerialName("gemini") GEMINI,
}

@Serializable
data class ProviderConfig(
    val id: String,
    val name: String,
    val kind: ProviderKind,
    val baseUrl: String,
    val apiKey: String = "",
    /** Models the user pinned. Empty means "ask the provider at runtime". */
    val models: List<String> = emptyList(),
    val defaultModel: String = "",
    val extraHeaders: Map<String, String> = emptyMap(),
) {
    val isReady: Boolean get() = apiKey.isNotBlank() || kind == ProviderKind.OPENAI && baseUrl.contains("127.0.0.1")
}

@Serializable
data class ToolCall(
    val id: String,
    val name: String,
    /** Raw JSON object string. Kept as text so partial streaming deltas can append. */
    val argumentsJson: String,
)

@Serializable
data class ToolResult(
    val callId: String,
    val name: String,
    val content: String,
    val isError: Boolean = false,
)

@Serializable
enum class Role { SYSTEM, USER, ASSISTANT, TOOL }

@Serializable
data class ChatMessage(
    val role: Role,
    val text: String = "",
    val toolCalls: List<ToolCall> = emptyList(),
    val toolResult: ToolResult? = null,
)

data class ToolSpec(
    val name: String,
    val description: String,
    /** JSON Schema object for the arguments. */
    val parameters: JsonObject,
)

data class Usage(val inputTokens: Int = 0, val outputTokens: Int = 0)

sealed interface LlmEvent {
    /** Incremental assistant prose. */
    data class TextDelta(val text: String) : LlmEvent

    /** Incremental "thinking"/reasoning text, when the provider exposes it. */
    data class ReasoningDelta(val text: String) : LlmEvent

    /** A tool call finished streaming and is ready to execute. */
    data class ToolCallReady(val call: ToolCall) : LlmEvent

    data class Completed(val finishReason: String?, val usage: Usage?) : LlmEvent

    data class Failed(val message: String) : LlmEvent
}

internal val LlmJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
    isLenient = true
}
