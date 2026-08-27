package dev.opencode.mobile.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

/** Google Gemini `generateContent` with SSE streaming and function calling. */
class GeminiProvider : LlmProvider {
    override val kind = ProviderKind.GEMINI

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
            put("contents", encodeContents(messages))
            if (systemPrompt.isNotBlank()) {
                putJsonObject("systemInstruction") {
                    putJsonArray("parts") {
                        add(buildJsonObject { put("text", systemPrompt) })
                    }
                }
            }
            putJsonObject("generationConfig") {
                put("temperature", temperature)
                put("maxOutputTokens", maxTokens)
            }
            if (tools.isNotEmpty()) {
                putJsonArray("tools") {
                    add(buildJsonObject {
                        putJsonArray("functionDeclarations") {
                            tools.forEach { tool ->
                                add(buildJsonObject {
                                    put("name", tool.name)
                                    put("description", tool.description)
                                    put("parameters", sanitizeSchema(tool.parameters))
                                })
                            }
                        }
                    })
                }
            }
        }

        val base = config.baseUrl.trimTrailingSlash()
        val request = Request.Builder()
            .url("$base/models/$model:streamGenerateContent?alt=sse")
            .post(payload.toString().toRequestBody(JSON_MEDIA))
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
            .apply {
                if (config.apiKey.isNotBlank()) header("x-goog-api-key", config.apiKey)
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

            val calls = mutableListOf<ToolCall>()
            var finish: String? = null
            var usage: Usage? = null

            it.forEachSseEvent { sse ->
                val json = runCatching { LlmJson.parseToJsonElement(sse.data).safeObj }.getOrNull()
                    ?: return@forEachSseEvent true

                json["error"]?.let { err ->
                    emit(LlmEvent.Failed(err.toString()))
                    return@forEachSseEvent false
                }

                json["usageMetadata"].safeObj?.let { meta ->
                    usage = Usage(
                        inputTokens = meta["promptTokenCount"].safePrim?.intOrNull ?: 0,
                        outputTokens = meta["candidatesTokenCount"].safePrim?.intOrNull ?: 0,
                    )
                }

                val candidate = json["candidates"].safeArr?.firstOrNull().safeObj
                    ?: return@forEachSseEvent true
                candidate["finishReason"].safePrim?.contentOrNull?.let { reason -> finish = reason }

                candidate["content"].safeObj?.get("parts").safeArr?.forEach { rawPart ->
                    val part = rawPart.safeObj ?: return@forEach
                    part["text"].safePrim?.contentOrNull
                        ?.takeIf { text -> text.isNotEmpty() }
                        ?.let { text ->
                            if (part["thought"].safePrim?.contentOrNull == "true") {
                                emit(LlmEvent.ReasoningDelta(text))
                            } else {
                                emit(LlmEvent.TextDelta(text))
                            }
                        }

                    part["functionCall"].safeObj?.let { fn ->
                        val name = fn["name"].safePrim?.contentOrNull.orEmpty()
                        if (name.isNotBlank()) {
                            // Gemini does not issue call ids; synthesize a stable one.
                            calls += ToolCall(
                                id = "gemini_${calls.size}_$name",
                                name = name,
                                argumentsJson = (fn["args"] ?: JsonObject(emptyMap())).toString(),
                            )
                        }
                    }
                }
                true
            }

            calls.forEach { call -> emit(LlmEvent.ToolCallReady(call)) }
            emit(LlmEvent.Completed(finish, usage))
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun listModels(config: ProviderConfig): List<String> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("${config.baseUrl.trimTrailingSlash()}/models?pageSize=200")
            .get()
            .apply { if (config.apiKey.isNotBlank()) header("x-goog-api-key", config.apiKey) }
            .build()

        runCatching {
            Http.client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext emptyList()
                LlmJson.parseToJsonElement(response.body?.string().orEmpty())
                    .safeObj?.get("models").safeArr
                    ?.mapNotNull { entry ->
                        entry.safeObj?.get("name")?.safePrim?.contentOrNull
                            ?.removePrefix("models/")
                    }
                    ?.filter { name -> name.startsWith("gemini") }
                    ?.sorted()
                    .orEmpty()
            }
        }.getOrDefault(emptyList())
    }

    private fun encodeContents(messages: List<ChatMessage>): JsonArray = buildJsonArray {
        var index = 0
        while (index < messages.size) {
            val message = messages[index]
            when (message.role) {
                Role.SYSTEM -> index++

                Role.USER -> {
                    add(buildJsonObject {
                        put("role", "user")
                        putJsonArray("parts") {
                            add(buildJsonObject { put("text", message.text.ifBlank { "(empty)" }) })
                        }
                    })
                    index++
                }

                Role.ASSISTANT -> {
                    add(buildJsonObject {
                        put("role", "model")
                        putJsonArray("parts") {
                            if (message.text.isNotBlank()) {
                                add(buildJsonObject { put("text", message.text) })
                            }
                            message.toolCalls.forEach { call ->
                                add(buildJsonObject {
                                    putJsonObject("functionCall") {
                                        put("name", call.name)
                                        put("args", parseArgs(call.argumentsJson))
                                    }
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
                            putJsonArray("parts") {
                                group.forEach { result ->
                                    add(buildJsonObject {
                                        putJsonObject("functionResponse") {
                                            put("name", result.name)
                                            putJsonObject("response") {
                                                put("result", result.content)
                                                if (result.isError) put("error", true)
                                            }
                                        }
                                    })
                                }
                            }
                        })
                    }
                }
            }
        }
    }
}

/**
 * Gemini rejects JSON Schema keywords it does not implement (`additionalProperties`,
 * `$schema`, `examples`, ...), so only the supported subset is forwarded.
 */
private val GEMINI_SCHEMA_KEYS = setOf(
    "type", "format", "description", "nullable", "enum", "items", "properties", "required",
)

private fun sanitizeSchema(element: JsonElement): JsonElement = when (element) {
    is JsonObject -> buildJsonObject {
        element.forEach { (key, value) ->
            if (key !in GEMINI_SCHEMA_KEYS) return@forEach
            when (key) {
                // `properties` is a map of property name -> schema; the keys are
                // user data and must survive, only their values are schemas.
                "properties" -> putJsonObject("properties") {
                    (value as? JsonObject)?.forEach { (name, schema) ->
                        put(name, sanitizeSchema(schema))
                    }
                }
                "items" -> put("items", sanitizeSchema(value))
                // `enum` and `required` are plain string arrays; `type`, `format`,
                // `description` and `nullable` are scalars. All pass through as-is.
                else -> put(key, value)
            }
        }
    }

    else -> element
}
