package dev.opencode.mobile.llm

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Null-safe JSON navigation.
 *
 * kotlinx.serialization models JSON `null` as the singleton [JsonNull], which is
 * a *non-null* [JsonElement]. That breaks the usual Kotlin safe-call chain:
 * `payload["usage"]?.jsonObject` still throws `IllegalArgumentException:
 * JsonNull is not a JsonObject` whenever a provider omits or nulls an optional
 * field mid-stream (OpenAI sends `"usage": null` in every streaming chunk).
 *
 * These extensions replace the throwing `jsonObject` / `jsonArray` /
 * `jsonPrimitive` accessors everywhere the shape of the data is not fully
 * controlled by us: a missing field, a JSON `null`, or an unexpected type all
 * collapse to `null` instead of crashing the chat stream.
 */
internal val JsonElement?.safeObj: JsonObject?
    get() = this as? JsonObject

internal val JsonElement?.safeArr: JsonArray?
    get() = this as? JsonArray

internal val JsonElement?.safePrim: JsonPrimitive?
    get() = this as? JsonPrimitive
