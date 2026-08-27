package dev.opencode.mobile.core.github

import dev.opencode.mobile.core.settings.redactSecrets
import dev.opencode.mobile.llm.Http
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.add
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/** HTTP failure carrying the status and a short body excerpt — never the token. */
class GitHubApiException(val code: Int, message: String) : IOException(message)

/**
 * GitHub REST API v3 client.
 *
 * Security contract: the token lives only in this class (and Encrypted-
 * SharedPreferences). It is attached to outgoing requests as a header, is never
 * placed in an exception message, a tool result, a log line or any returned
 * model, and every user-facing string is passed through [redactSecrets] before
 * leaving this package — so even a server echo of the header cannot leak it.
 */
class GitHubClient(private val token: String) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // ---- low level ---------------------------------------------------------

    private fun request(path: String): Request.Builder =
        Request.Builder()
            .url("$BASE_URL${if (path.startsWith('/')) path else "/$path"}")
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", API_VERSION)
            .header("User-Agent", USER_AGENT)

    private suspend fun call(path: String, method: String = "GET", body: JsonObject? = null): String =
        withContext(Dispatchers.IO) {
            val builder = request(path)
            when {
                body != null -> builder.method(
                    method,
                    json.encodeToString(JsonObject.serializer(), body)
                        .toRequestBody(JSON_MEDIA),
                )

                method != "GET" -> builder.method(method, ByteArray(0).toRequestBody(null))
            }
            val response: Response = runCatching { Http.client.newCall(builder.build()).execute() }
                .getOrElse { error -> throw GitHubApiException(0, "Network error: ${error.message ?: "failed"}") }
            response.use { resp ->
                val text = runCatching { resp.body?.string() }.getOrDefault("")
                if (!resp.isSuccessful) throw GitHubApiException(resp.code, apiError(resp.code, text))
                text
            }
        }

    /** Extracts a human-safe message from an error payload; falls back to the status line. */
    private fun apiError(code: Int, body: String): String {
        val parsed = runCatching {
            val obj = json.decodeFromString(JsonObject.serializer(), body)
            obj["message"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        }.getOrNull()
        return ("HTTP $code · ${parsed ?: STATUS.getOrDefault(code, "request failed")}")
            .redactSecrets()
            .take(MAX_ERROR_CHARS)
    }

    // ---- account ------------------------------------------------------------

    suspend fun me(): GhUser = withContext(Dispatchers.IO) {
        json.decodeFromString(GhUser.serializer(), call("/user"))
    }

    // ---- repositories -------------------------------------------------------

    /** Repositories the account has explicit access to, newest activity first. */
    suspend fun listRepos(limit: Int = 100): List<GhRepo> = withContext(Dispatchers.IO) {
        val text = call("/user/repos?affiliation=owner,collaborator,organization_member&sort=updated&per_page=$limit")
        json.decodeFromString(ListSerializer(GhRepo.serializer()), text)
    }

    suspend fun getRepo(slug: String): GhRepo = withContext(Dispatchers.IO) {
        json.decodeFromString(GhRepo.serializer(), call("/repos/$slug"))
    }

    suspend fun createRepo(
        name: String,
        description: String,
        isPrivate: Boolean,
        autoInit: Boolean,
    ): GhRepo = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("name", name)
            if (description.isNotBlank()) put("description", description.take(300))
            put("private", isPrivate)
            put("auto_init", autoInit)
        }
        json.decodeFromString(GhRepo.serializer(), call("/user/repos", method = "POST", body = body))
    }

    // ---- branches / commits / contents ---------------------------------------

    suspend fun listBranches(slug: String, limit: Int = 100): List<GhBranch> = withContext(Dispatchers.IO) {
        val text = call("/repos/$slug/branches?per_page=$limit")
        json.decodeFromString(ListSerializer(GhBranch.serializer()), text)
    }

    suspend fun listCommits(slug: String, ref: String? = null, limit: Int = 30): List<GhCommit> =
        withContext(Dispatchers.IO) {
            val query = buildString {
                append("/repos/$slug/commits?per_page=$limit")
                if (!ref.isNullOrBlank()) append("&sha=").append(android.net.Uri.encode(ref))
            }
            json.decodeFromString(ListSerializer(GhCommit.serializer()), call(query))
        }

    suspend fun listContents(slug: String, path: String, ref: String? = null): List<GhContent> =
        withContext(Dispatchers.IO) {
            val cleanPath = path.trim('/').trimStart('/')
            val encoded = cleanPath.split('/').filter { it.isNotEmpty() }
                .joinToString("/") { android.net.Uri.encode(it) }
            var query = "/repos/$slug/contents" + if (encoded.isEmpty()) "" else "/$encoded"
            query += "?per_page=1000"
            if (!ref.isNullOrBlank()) query += "&ref=" + android.net.Uri.encode(ref)
            json.decodeFromString(ListSerializer(GhContent.serializer()), call(query))
        }

    // ---- issues ---------------------------------------------------------------

    suspend fun listIssues(slug: String, state: String = "open", limit: Int = 50): List<GhIssue> =
        withContext(Dispatchers.IO) {
            val text = call("/repos/$slug/issues?state=$state&sort=updated&direction=desc&per_page=$limit")
            json.decodeFromString(ListSerializer(GhIssue.serializer()), text).filterNot { it.isPullRequest }
        }

    suspend fun getIssue(slug: String, number: Int): GhIssue = withContext(Dispatchers.IO) {
        json.decodeFromString(GhIssue.serializer(), call("/repos/$slug/issues/$number"))
    }

    suspend fun createIssue(slug: String, title: String, body: String, labels: List<String>): GhIssue =
        withContext(Dispatchers.IO) {
            val payload = buildJsonObject {
                put("title", title)
                if (body.isNotBlank()) put("body", body)
                if (labels.isNotEmpty()) putJsonArray("labels") { labels.forEach { add(it) } }
            }
            json.decodeFromString(GhIssue.serializer(), call("/repos/$slug/issues", "POST", payload))
        }

    /** Comments work identically on issues and pull requests. */
    suspend fun listComments(slug: String, number: Int, limit: Int = 60): List<GhComment> =
        withContext(Dispatchers.IO) {
            val text = call("/repos/$slug/issues/$number/comments?per_page=$limit")
            json.decodeFromString(ListSerializer(GhComment.serializer()), text)
        }

    suspend fun addComment(slug: String, number: Int, body: String): GhComment = withContext(Dispatchers.IO) {
        val payload = buildJsonObject { put("body", body) }
        json.decodeFromString(
            GhComment.serializer(),
            call("/repos/$slug/issues/$number/comments", "POST", payload),
        )
    }

    // ---- pull requests ----------------------------------------------------------

    suspend fun listPulls(slug: String, state: String = "open", limit: Int = 50): List<GhPull> =
        withContext(Dispatchers.IO) {
            val text = call("/repos/$slug/pulls?state=$state&sort=updated&direction=desc&per_page=$limit")
            json.decodeFromString(ListSerializer(GhPull.serializer()), text)
        }

    suspend fun getPull(slug: String, number: Int): GhPull = withContext(Dispatchers.IO) {
        json.decodeFromString(GhPull.serializer(), call("/repos/$slug/pulls/$number"))
    }

    suspend fun createPull(
        slug: String,
        title: String,
        head: String,
        base: String,
        body: String,
        draft: Boolean,
    ): GhPull = withContext(Dispatchers.IO) {
        val payload = buildJsonObject {
            put("title", title)
            put("head", head)
            put("base", base)
            if (body.isNotBlank()) put("body", body)
            put("draft", draft)
        }
        json.decodeFromString(GhPull.serializer(), call("/repos/$slug/pulls", "POST", payload))
    }

    suspend fun listReviews(slug: String, number: Int, limit: Int = 40): List<GhReview> =
        withContext(Dispatchers.IO) {
            val text = call("/repos/$slug/pulls/$number/reviews?per_page=$limit")
            json.decodeFromString(ListSerializer(GhReview.serializer()), text)
        }

    // ---- Actions -----------------------------------------------------------------

    suspend fun listWorkflowRuns(slug: String, branch: String? = null, limit: Int = 20): List<GhRun> =
        withContext(Dispatchers.IO) {
            var query = "/repos/$slug/actions/runs?per_page=$limit"
            if (!branch.isNullOrBlank()) query += "&branch=" + android.net.Uri.encode(branch)
            @Serializable data class Runs(@SerialName("workflow_runs") val runs: List<GhRun> = emptyList())
            json.decodeFromString(Runs.serializer(), call(query)).runs
        }

    companion object {
        const val BASE_URL = "https://api.github.com"
        const val DEVICE_CODE_URL = "https://github.com/login/device/code"
        const val TOKEN_URL = "https://github.com/login/oauth/access_token"
        const val DEVICE_VERIFY_URL = "https://github.com/login/device"
        private const val API_VERSION = "2022-11-28"
        private const val USER_AGENT = "opencode-mobile"
        private const val MAX_ERROR_CHARS = 400
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        private val STATUS = mapOf(
            301 to "moved permanently",
            304 to "not modified",
            401 to "bad credentials — check your token",
            403 to "forbidden — resource may be private or rate limit reached",
            404 to "not found — no permission, or wrong owner/name",
            409 to "conflict",
            422 to "validation failed",
            429 to "rate limited — retry later",
            500 to "GitHub server error",
            502 to "GitHub server error",
            503 to "service unavailable",
        )
    }
}
