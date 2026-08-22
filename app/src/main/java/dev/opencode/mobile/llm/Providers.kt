package dev.opencode.mobile.llm

/** Maps a [ProviderKind] to the object that speaks that wire protocol. */
object ProviderRegistry {
    private val openAi = OpenAiCompatProvider()
    private val anthropic = AnthropicProvider()
    private val gemini = GeminiProvider()

    fun forKind(kind: ProviderKind): LlmProvider = when (kind) {
        ProviderKind.OPENAI -> openAi
        ProviderKind.ANTHROPIC -> anthropic
        ProviderKind.GEMINI -> gemini
    }

    fun forConfig(config: ProviderConfig): LlmProvider = forKind(config.kind)
}

data class ProviderPreset(
    val id: String,
    val name: String,
    val kind: ProviderKind,
    val baseUrl: String,
    val suggestedModels: List<String>,
    val keyUrl: String,
    val note: String = "",
) {
    fun toConfig(): ProviderConfig = ProviderConfig(
        id = id,
        name = name,
        kind = kind,
        baseUrl = baseUrl,
        models = suggestedModels,
        defaultModel = suggestedModels.firstOrNull().orEmpty(),
    )
}

/**
 * Starting points shown in Settings. Any endpoint that implements
 * `/chat/completions` also works through [CUSTOM] without a preset.
 */
object ProviderPresets {
    val CUSTOM = ProviderPreset(
        id = "custom",
        name = "Custom (OpenAI-compatible)",
        kind = ProviderKind.OPENAI,
        baseUrl = "https://",
        suggestedModels = emptyList(),
        keyUrl = "",
        note = "Any endpoint exposing /chat/completions. Must be HTTPS.",
    )

    val all: List<ProviderPreset> = listOf(
        ProviderPreset(
            id = "openrouter",
            name = "OpenRouter",
            kind = ProviderKind.OPENAI,
            baseUrl = "https://openrouter.ai/api/v1",
            suggestedModels = listOf(
                "anthropic/claude-sonnet-4.5",
                "anthropic/claude-opus-4.1",
                "openai/gpt-4.1",
                "google/gemini-2.5-pro",
                "deepseek/deepseek-chat",
                "qwen/qwen-2.5-coder-32b-instruct",
            ),
            keyUrl = "https://openrouter.ai/keys",
            note = "One key, most models. Easiest starting point.",
        ),
        ProviderPreset(
            id = "anthropic",
            name = "Anthropic",
            kind = ProviderKind.ANTHROPIC,
            baseUrl = "https://api.anthropic.com",
            suggestedModels = listOf(
                "claude-opus-4-5",
                "claude-sonnet-4-5",
                "claude-haiku-4-5",
            ),
            keyUrl = "https://console.anthropic.com/settings/keys",
            note = "Native Messages API with tool use.",
        ),
        ProviderPreset(
            id = "openai",
            name = "OpenAI",
            kind = ProviderKind.OPENAI,
            baseUrl = "https://api.openai.com/v1",
            suggestedModels = listOf("gpt-4.1", "gpt-4.1-mini", "o4-mini"),
            keyUrl = "https://platform.openai.com/api-keys",
        ),
        ProviderPreset(
            id = "gemini",
            name = "Google Gemini",
            kind = ProviderKind.GEMINI,
            baseUrl = "https://generativelanguage.googleapis.com/v1beta",
            suggestedModels = listOf("gemini-2.5-pro", "gemini-2.5-flash"),
            keyUrl = "https://aistudio.google.com/apikey",
            note = "Generous free tier on Flash.",
        ),
        ProviderPreset(
            id = "groq",
            name = "Groq",
            kind = ProviderKind.OPENAI,
            baseUrl = "https://api.groq.com/openai/v1",
            suggestedModels = listOf(
                "llama-3.3-70b-versatile",
                "qwen-2.5-coder-32b",
                "moonshotai/kimi-k2-instruct",
            ),
            keyUrl = "https://console.groq.com/keys",
            note = "Very fast. Good for cheap edit loops.",
        ),
        ProviderPreset(
            id = "deepseek",
            name = "DeepSeek",
            kind = ProviderKind.OPENAI,
            baseUrl = "https://api.deepseek.com/v1",
            suggestedModels = listOf("deepseek-chat", "deepseek-reasoner"),
            keyUrl = "https://platform.deepseek.com/api_keys",
        ),
        ProviderPreset(
            id = "mistral",
            name = "Mistral",
            kind = ProviderKind.OPENAI,
            baseUrl = "https://api.mistral.ai/v1",
            suggestedModels = listOf("mistral-large-latest", "codestral-latest"),
            keyUrl = "https://console.mistral.ai/api-keys",
        ),
        ProviderPreset(
            id = "xai",
            name = "xAI",
            kind = ProviderKind.OPENAI,
            baseUrl = "https://api.x.ai/v1",
            suggestedModels = listOf("grok-4", "grok-3-mini"),
            keyUrl = "https://console.x.ai",
        ),
        ProviderPreset(
            id = "together",
            name = "Together AI",
            kind = ProviderKind.OPENAI,
            baseUrl = "https://api.together.xyz/v1",
            suggestedModels = listOf("Qwen/Qwen2.5-Coder-32B-Instruct"),
            keyUrl = "https://api.together.xyz/settings/api-keys",
        ),
        ProviderPreset(
            id = "cerebras",
            name = "Cerebras",
            kind = ProviderKind.OPENAI,
            baseUrl = "https://api.cerebras.ai/v1",
            suggestedModels = listOf("qwen-3-coder-480b", "llama-3.3-70b"),
            keyUrl = "https://cloud.cerebras.ai",
        ),
        ProviderPreset(
            id = "fireworks",
            name = "Fireworks",
            kind = ProviderKind.OPENAI,
            baseUrl = "https://api.fireworks.ai/inference/v1",
            suggestedModels = listOf("accounts/fireworks/models/qwen3-coder-480b-a35b-instruct"),
            keyUrl = "https://fireworks.ai/account/api-keys",
        ),
        CUSTOM,
    )
}
