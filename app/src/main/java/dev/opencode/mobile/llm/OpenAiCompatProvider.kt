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
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

/**
 * Speaks the OpenAI `/chat/completions` protocol, which is the lingua franca for
 * OpenRouter, Groq, DeepSeek, Together, Mistral, xAI, Cerebras, LM Studio, vLLM
 * and Ollama's `/v1` shim. Only [ProviderConfig.baseUrl] changes between them.
 */
class OpenAiCompatProvider : LlmProvider {
    override val kind = ProviderKind.OPENAI

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
            put("temperature", temperature)
            put("max_tokens", maxTokens)
            put("messages", encodeMessages(systemPrompt, messages))
            if (tools.isNotEmpty()) {
                put("tools", encodeTools(tools))
                put("tool_choice", "auto")
            }
        }

        val request = Request.Builder()
            .url("${config.baseUrl.trimTrailingSlash()}/chat/completions")
            .post(payload.toString().toRequestBody(JSON_MEDIA))
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
            .apply {
                if (config.apiKey.isNotBlank()) header("Authorization", "Bearer ${config.apiKey}")
                // OpenRouter attributes traffic with these; harmless elsewhere.
                header("HTTP-Referer", "https://github.com/opencode-mobile")
                header("X-Title", "OpenCode Mobile")
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

            // tool_calls stream as deltas keyed by index; ids and names may arrive
            // in an earlier chunk than the arguments, so accumulate per index.
            val partial = sortedMapOf<Int, PartialCall>()
            var finish: String? = null
            var usage: Usage? = null

            it.forEachSseEvent { sse ->
                if (sse.data == "[DONE]") return@forEachSseEvent false
                val chunk = runCatching { LlmJson.parseToJsonElement(sse.data).jsonObject }.getOrNull()
                    ?: return@forEachSseEvent true

                chunk["error"]?.let { err ->
                    emit(LlmEvent.Failed(err.toString()))
                    return@forEachSseEvent false
                }

                chunk["usage"]?.jsonObject?.let { u ->
                    usage = Usage(
                        inputTokens = u["prompt_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
                        outputTokens = u["completion_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
                    )
                }

                val choice = chunk["choices"]?.jsonArray?.firstOrNull()?.jsonObject
                    ?: return@forEachSseEvent true
                choice["finish_reason"]?.jsonPrimitive?.contentOrNull?.let { reason -> finish = reason }

                val delta = choice["delta"]?.jsonObject ?: return@forEachSseEvent true

                delta["content"]?.jsonPrimitive?.contentOrNull
                    ?.takeIf { text -> text.isNotEmpty() }
                    ?.let { text -> emit(LlmEvent.TextDelta(text)) }

                // DeepSeek uses reasoning_content, OpenRouter uses reasoning.
                (delta["reasoning_content"] ?: delta["reasoning"])?.jsonPrimitive?.contentOrNull
                    ?.takeIf { text -> text.isNotEmpty() }
                    ?.let { text -> emit(LlmEvent.ReasoningDelta(text)) }

                delta["tool_calls"]?.jsonArray?.forEach { raw ->
                    val call = raw.jsonObject
                    val index = call["index"]?.jsonPrimitive?.intOrNull ?: 0
                    val slot = partial.getOrPut(index) { PartialCall() }
                    call["id"]?.jsonPrimitive?.contentOrNull?.let { id -> slot.id = id }
                    call["function"]?.jsonObject?.let { fn ->
                        fn["name"]?.jsonPrimitive?.contentOrNull?.let { n -> slot.name = n }
                        fn["arguments"]?.jsonPrimitive?.contentOrNull?.let { a -> slot.args.append(a) }
                    }
                }
                true
            }

            partial.values.forEach { slot ->
                if (slot.name.isNotBlank()) {
                    emit(LlmEvent.ToolCallReady(slot.toToolCall()))
                }
            }
            emit(LlmEvent.Completed(finish, usage))
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun listModels(config: ProviderConfig): List<String> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("${config.baseUrl.trimTrailingSlash()}/models")
            .get()
            .apply {
                if (config.apiKey.isNotBlank()) header("Authorization", "Bearer ${config.apiKey}")
                config.extraHeaders.forEach { (k, v) -> header(k, v) }
            }
            .build()

        runCatching {
            Http.client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext emptyList()
                val body = response.body?.string().orEmpty()
                LlmJson.parseToJsonElement(body).jsonObject["data"]?.jsonArray
                    ?.mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.contentOrNull }
                    ?.sorted()
                    .orEmpty()
            }
        }.getOrDefault(emptyList())
    }

    private fun encodeMessages(systemPrompt: String, messages: List<ChatMessage>): JsonArray =
        buildJsonArray {
            if (systemPrompt.isNotBlank()) {
                add(buildJsonObject {
                    put("role", "system")
                    put("content", systemPrompt)
                })
            }
            messages.forEach { message ->
                when (message.role) {
                    Role.SYSTEM -> add(buildJsonObject {
                        put("role", "system")
                        put("content", message.text)
                    })

                    Role.USER -> add(buildJsonObject {
                        put("role", "user")
                        put("content", message.text)
                    })

                    Role.ASSISTANT -> add(buildJsonObject {
                        put("role", "assistant")
                        put("content", message.text)
                        if (message.toolCalls.isNotEmpty()) {
                            putJsonArray("tool_calls") {
                                message.toolCalls.forEach { call ->
                                    add(buildJsonObject {
                                        put("id", call.id)
                                        put("type", "function")
                                        putJsonObject("function") {
                                            put("name", call.name)
                                            put("arguments", call.argumentsJson.ifBlank { "{}" })
                                        }
                                    })
                                }
                            }
                        }
                    })

                    Role.TOOL -> message.toolResult?.let { result ->
                        add(buildJsonObject {
                            put("role", "tool")
                            put("tool_call_id", result.callId)
                            put("name", result.name)
                            put("content", result.content)
                        })
                    }
                }
            }
        }

    private fun encodeTools(tools: List<ToolSpec>): JsonArray = buildJsonArray {
        tools.forEach { tool ->
            add(buildJsonObject {
                put("type", "function")
                putJsonObject("function") {
                    put("name", tool.name)
                    put("description", tool.description)
                    put("parameters", tool.parameters)
                }
            })
        }
    }
}

internal class PartialCall {
    var id: String = ""
    var name: String = ""
    val args = StringBuilder()

    fun toToolCall(): ToolCall = ToolCall(
        id = id.ifBlank { "call_${name}_${args.length}" },
        name = name,
        argumentsJson = args.toString().ifBlank { "{}" },
    )
}

internal fun JsonObject.intOr(key: String, fallback: Int): Int =
    this[key]?.jsonPrimitive?.runCatching { int }?.getOrNull() ?: fallback
