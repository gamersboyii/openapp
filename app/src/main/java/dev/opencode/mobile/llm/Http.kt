package dev.opencode.mobile.llm

import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Response

/** Shared OkHttp client. Read timeout is long because token streams idle between chunks. */
object Http {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.MINUTES)
        .writeTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
}

/** One `event:`/`data:` record from a text/event-stream response. */
data class SseEvent(val event: String?, val data: String)

/**
 * Reads a server-sent-event body line by line and hands each record to [onEvent].
 * Returning false from [onEvent] stops reading early (used to abort on `[DONE]`).
 */
inline fun Response.forEachSseEvent(onEvent: (SseEvent) -> Boolean) {
    val source = body?.source() ?: return
    var eventName: String? = null
    val data = StringBuilder()

    fun flush(): Boolean {
        if (data.isEmpty() && eventName == null) return true
        val payload = data.toString()
        data.setLength(0)
        val name = eventName
        eventName = null
        return onEvent(SseEvent(name, payload))
    }

    while (!source.exhausted()) {
        val line = source.readUtf8Line() ?: break
        when {
            line.isEmpty() -> if (!flush()) return
            line.startsWith(":") -> Unit // comment / keep-alive
            line.startsWith("event:") -> eventName = line.removePrefix("event:").trim()
            line.startsWith("data:") -> {
                if (data.isNotEmpty()) data.append('\n')
                data.append(line.removePrefix("data:").removePrefix(" "))
            }
        }
    }
    flush()
}

/** Trims a trailing slash so callers can always concatenate with a leading slash. */
fun String.trimTrailingSlash(): String = trimEnd('/')

fun httpErrorMessage(response: Response): String {
    val body = runCatching { response.body?.string() }.getOrNull()?.take(1200).orEmpty()
    val detail = if (body.isBlank()) "" else "\n$body"
    return "HTTP ${response.code} ${response.message}$detail"
}
