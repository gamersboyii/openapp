package dev.opencode.mobile.llm

import kotlinx.coroutines.flow.Flow

interface LlmProvider {
    val kind: ProviderKind

    /**
     * Streams one assistant turn. The flow completes after [LlmEvent.Completed]
     * or [LlmEvent.Failed]; it never throws for network/protocol problems, so
     * callers do not need a try/catch around collection.
     */
    fun stream(
        config: ProviderConfig,
        model: String,
        systemPrompt: String,
        messages: List<ChatMessage>,
        tools: List<ToolSpec>,
        temperature: Double,
        maxTokens: Int,
    ): Flow<LlmEvent>

    /** Best-effort model discovery. Returns an empty list when unsupported. */
    suspend fun listModels(config: ProviderConfig): List<String>
}
