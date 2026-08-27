package dev.opencode.mobile.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
private const val ANTHROPIC_VERSION = "2023-06-01"

/** Native Anthropic Messages API, including `tool_use` / `tool_result` blocks. */
class AnthropicProvider : LlmProvider {
    override val kind = ProviderKind.ANTHROPIC

    override fun stream(
        config: ProviderConfig,
        model: String,
        systemPrompt: String,
        messages: List<ChatMessage>,
        tools: List<ToolSpec>,
        temperature: Double,
        maxTokens: Int,
    ): Flow<LlmEvent> = flow {
        val payload = buildJsonObject {
            put("model", model)
            put("stream", true)
            put("max_tokens", maxTokens)
            put("temperature", temperature)
            if (systemPrompt.isNotBlank()) put("system", systemPrompt)
            put("messages", encodeMessages(messages))
            if (tools.isNotEmpty()) put("tools", encodeTools(tools))
        }

        val request = Request.Builder()
            .url("${config.baseUrl.trimTrailingSlash()}/v1/messages")
            .post(payload.toString().toRequestBody(JSON_MEDIA))
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
            .header("anthropic-version", ANTHROPIC_VERSION)
            .apply {
                if (config.apiKey.isNotBlank()) header("x-api-key", config.apiKey)
                config.extraHeaders.forEach { (k, v) -> header(k, v) }
            }
            .build()

        val response = runCatching { Http.client.newCall(request).execute() }
            .getOrElse { emit(LlmEvent.Failed(it.message ?: it.toString())); return@flow }

        response.use {
            if (!it.isSuccessful) {
                emit(LlmEvent.Failed(httpErrorMessage(it)))
                return@flow
            }

            val partial = sortedMapOf<Int, PartialCall>()
            var stopReason: String? = null
            var inputTokens = 0
            var outputTokens = 0

            it.forEachSseEvent { sse ->
                val json = runCatching { LlmJson.parseToJsonElement(sse.data).safeObj }.getOrNull()
                    ?: return@forEachSseEvent true

                when (sse.event ?: json["type"].safePrim?.contentOrNull) {
                    "message_start" -> {
                        inputTokens = json["message"].safeObj
                            ?.get("usage").safeObj
                            ?.get("input_tokens").safePrim?.intOrNull ?: 0
                    }

                    "content_block_start" -> {
                        val index = json["index"].safePrim?.intOrNull ?: 0
                        val block = json["content_block"].safeObj
                        if (block?.get("type")?.safePrim?.contentOrNull == "tool_use") {
                            partial[index] = PartialCall().apply {
                                id = block["id"]?.safePrim?.contentOrNull.orEmpty()
                                name = block["name"]?.safePrim?.contentOrNull.orEmpty()
                            }
                        }
                    }

                    "content_block_delta" -> {
                        val index = json["index"].safePrim?.intOrNull ?: 0
                        val delta = json["delta"].safeObj ?: return@forEachSseEvent true
                        when (delta["type"].safePrim?.contentOrNull) {
                            "text_delta" -> delta["text"].safePrim?.contentOrNull
                                ?.let { text -> emit(LlmEvent.TextDelta(text)) }

                            "thinking_delta" -> delta["thinking"].safePrim?.contentOrNull
                                ?.let { text -> emit(LlmEvent.ReasoningDelta(text)) }

                            "input_json_delta" -> delta["partial_json"].safePrim?.contentOrNull
                                ?.let { chunk -> partial[index]?.args?.append(chunk) }
                        }
                    }

                    "message_delta" -> {
                        json["delta"].safeObj?.get("stop_reason").safePrim?.contentOrNull
                            ?.let { reason -> stopReason = reason }
                        json["usage"].safeObj?.get("output_tokens").safePrim?.intOrNull
                            ?.let { count -> outputTokens = count }
                    }

                    "error" -> {
                        emit(LlmEvent.Failed(json["error"]?.toString() ?: sse.data))
                        return@forEachSseEvent false
                    }

                    "message_stop" -> return@forEachSseEvent false
                }
                true
            }

            partial.values.forEach { slot ->
                if (slot.name.isNotBlank()) emit(LlmEvent.ToolCallReady(slot.toToolCall()))
            }
            emit(LlmEvent.Completed(stopReason, Usage(inputTokens, outputTokens)))
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun listModels(config: ProviderConfig): List<String> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("${config.baseUrl.trimTrailingSlash()}/v1/models?limit=100")
            .get()
            .header("anthropic-version", ANTHROPIC_VERSION)
            .apply { if (config.apiKey.isNotBlank()) header("x-api-key", config.apiKey) }
            .build()

        runCatching {
            Http.client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext emptyList()
                LlmJson.parseToJsonElement(response.body?.string().orEmpty())
                    .safeObj?.get("data").safeArr
                    ?.mapNotNull { it.safeObj?.get("id")?.safePrim?.contentOrNull }
                    .orEmpty()
            }
        }.getOrDefault(emptyList())
    }

    /**
     * Anthropic requires strictly alternating user/assistant turns, and every
     * `tool_result` must live inside a user turn. Consecutive tool results are
     * therefore merged into a single user message.
     */
    private fun encodeMessages(messages: List<ChatMessage>): JsonArray = buildJsonArray {
        var index = 0
        while (index < messages.size) {
            val message = messages[index]
            when (message.role) {
                Role.SYSTEM -> index++ // handled by the top-level `system` field

                Role.USER -> {
                    add(buildJsonObject {
                        put("role", "user")
                        putJsonArray("content") {
                            add(buildJsonObject {
                                put("type", "text")
                                put("text", message.text.ifBlank { "(empty)" })
                            })
                        }
                    })
                    index++
                }

                Role.ASSISTANT -> {
                    add(buildJsonObject {
                        put("role", "assistant")
                        putJsonArray("content") {
                            if (message.text.isNotBlank()) {
                                add(buildJsonObject {
                                    put("type", "text")
                                    put("text", message.text)
                                })
                            }
                            message.toolCalls.forEach { call ->
                                add(buildJsonObject {
                                    put("type", "tool_use")
                                    put("id", call.id)
                                    put("name", call.name)
                                    put("input", parseArgs(call.argumentsJson))
                                })
                            }
                        }
                    })
                    index++
                }

                Role.TOOL -> {
                    val group = mutableListOf<ToolResult>()
                    while (index < messages.size && messages[index].role == Role.TOOL) {
                        messages[index].toolResult?.let(group::add)
                        index++
                    }
                    if (group.isNotEmpty()) {
                        add(buildJsonObject {
                            put("role", "user")
                            putJsonArray("content") {
                                group.forEach { result ->
                                    add(buildJsonObject {
                                        put("type", "tool_result")
                                        put("tool_use_id", result.callId)
                                        put("content", result.content)
                                        if (result.isError) put("is_error", true)
                                    })
                                }
                            }
                        })
                    }
                }
            }
        }
    }

    private fun encodeTools(tools: List<ToolSpec>): JsonArray = buildJsonArray {
        tools.forEach { tool ->
            add(buildJsonObject {
                put("name", tool.name)
                put("description", tool.description)
                put("input_schema", tool.parameters)
            })
        }
    }
}

internal fun parseArgs(raw: String): JsonObject =
    runCatching { LlmJson.parseToJsonElement(raw.ifBlank { "{}" }).safeObj }
        .getOrDefault(JsonObject(emptyMap()))
