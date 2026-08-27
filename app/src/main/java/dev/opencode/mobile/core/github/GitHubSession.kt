package dev.opencode.mobile.core.github

import dev.opencode.mobile.llm.Http
import dev.opencode.mobile.core.settings.SettingsStore
import dev.opencode.mobile.core.settings.redactSecrets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * App-lifetime GitHub auth state. Rebuilds the [GitHubClient] whenever the
 * stored token changes, verifies it against `/user`, and runs the OAuth device
 * flow for users who prefer OAuth over pasting a personal access token.
 *
 * The token itself never leaves this package's reach: screens see only the
 * verified account; agent tools get an already-authenticated client.
 */
class GitHubSession(
    private val settings: SettingsStore,
    private val scope: CoroutineScope,
) {

    private val _client = MutableStateFlow<GitHubClient?>(null)
    val client: StateFlow<GitHubClient?> = _client.asStateFlow()

    private val _account = MutableStateFlow<GhUser?>(null)
    val account: StateFlow<GhUser?> = _account.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /** One-line status for the Hub screen ("Verifying…", errors, etc.). */
    private val _notice = MutableStateFlow("")
    val notice: StateFlow<String> = _notice.asStateFlow()

    // Device-flow polling state, observed by the sign-in dialog.
    data class DevicePrompt(val userCode: String, val verificationUri: String)

    private val _devicePrompt = MutableStateFlow<DevicePrompt?>(null)
    val devicePrompt: StateFlow<DevicePrompt?> = _devicePrompt.asStateFlow()

    init {
        // Rebuild the client from settings at startup and after every change,
        // verifying each new token against /user so the login becomes real.
        scope.launch {
            settings.settings
                .map { it.githubToken.trim() }
                .distinctUntilChanged()
                .collectLatest { token ->
                    if (token.isBlank()) {
                        _client.value = null
                        _account.value = null
                    } else {
                        _client.value = GitHubClient(token)
                        verify()
                    }
                }
        }
    }

    val signedIn: Boolean get() = client.value != null

    /** Verifies the current token against /user; caches login on success. */
    suspend fun verify(): Result<GhUser> {
        val api = _client.value
            ?: return Result.failure(IllegalStateException("No GitHub token saved"))
        _busy.value = true
        _notice.value = "Verifying…"
        return runCatching { api.me() }
            .onSuccess { user ->
                _account.value = user
                settings.update { it.copy(githubLogin = user.login) }
                _notice.value = ""
            }
            .onFailure { error ->
                _notice.value = (error.message ?: "Verification failed").redactSecrets()
            }
            .also { _busy.value = false }
    }

    /** Personal-access-token path. Verification happens right after. */
    fun signInWithToken(token: String) {
        val trimmed = token.trim()
        if (trimmed.isBlank()) return
        settings.update { it.copy(githubToken = trimmed, githubLogin = "") }
        _account.value = null
    }

    fun updateClientId(id: String) = settings.update { it.copy(githubClientId = id.trim()) }

    fun signOut() {
        settings.update { it.copy(githubToken = "", githubLogin = "") }
        _account.value = null
        _notice.value = ""
        _devicePrompt.value = null
        _client.value = null
    }

    // ---- OAuth device flow -----------------------------------------------------

    /**
     * Starts the device flow: returns the short code the user types into
     * github.com/login/device and begins polling in the background. Requires a
     * client id from the user's own OAuth app (no client secret is shipped in
     * the APK, so this stays "bring your own app" by design).
     */
    fun startDeviceFlow(clientId: String) {
        val id = clientId.trim().ifBlank { settings.settings.value.githubClientId.trim() }
        if (id.isBlank()) {
            _notice.value = "A client ID is required for OAuth — or paste a token instead."
            return
        }
        settings.update { it.copy(githubClientId = id) }
        scope.launch { runDeviceFlow(id) }
    }

    private suspend fun runDeviceFlow(clientId: String): Unit = withContext(Dispatchers.IO) {
        val json = Json { ignoreUnknownKeys = true; isLenient = true }
        _busy.value = true
        try {
            val startBody = buildJsonObject {
                put("client_id", clientId)
                put("scope", "repo workflow read:user")
            }
            val startResponse = postForm(GitHubClient.DEVICE_CODE_URL, json, startBody)

            val deviceCode = startResponse.str("device_code")
                ?: throw GitHubApiException(0, startResponse.str("error_description") ?: "device flow rejected")
            val prompt = DevicePrompt(
                userCode = startResponse.str("user_code").orEmpty(),
                verificationUri = startResponse.str("verification_uri") ?: GitHubClient.DEVICE_VERIFY_URL,
            )
            _devicePrompt.value = prompt
            _notice.value = ""

            var intervalMs = ((startResponse.intOf("interval") ?: 5).coerceAtLeast(3)) * 1000L
            val expiresAt = System.currentTimeMillis() +
                ((startResponse.intOf("expires_in") ?: 900).coerceAtLeast(60)) * 1000L

            while (System.currentTimeMillis() < expiresAt) {
                delay(intervalMs)
                val poll = postForm(GitHubClient.TOKEN_URL, json, buildJsonObject {
                    put("client_id", clientId)
                    put("device_code", deviceCode)
                    put("grant_type", GRANT_DEVICE)
                })
                when (val err = poll.str("error")) {
                    null -> {
                        val token = poll.str("access_token")
                        if (token.isNullOrBlank()) {
                            _notice.value = "GitHub did not return a token. Try a personal access token."
                        } else {
                            settings.update { it.copy(githubToken = token) }
                            verify()
                        }
                        return@withContext
                    }

                    ERR_PENDING -> Unit
                    ERR_SLOW_DOWN -> intervalMs += 5000
                    ERR_EXPIRED -> {
                        _notice.value = "The device code expired — start again."
                        return@withContext
                    }

                    else -> {
                        _notice.value = err.redactSecrets()
                        return@withContext
                    }
                }
            }
            _notice.value = "The device code expired — start again."
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        } catch (error: Throwable) {
            _notice.value = (error.message ?: "Device flow failed").redactSecrets()
        } finally {
            _busy.value = false
            _devicePrompt.value = null
        }
    }

    private val GRANT_DEVICE = "urn:ietf:params:oauth:grant-type:device_code"
    private val ERR_PENDING = "authorization_pending"
    private val ERR_SLOW_DOWN = "slow_down"
    private val ERR_EXPIRED = "expired_token"

    private suspend fun postForm(url: String, json: Json, payload: JsonObject): JsonObject =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .header("User-Agent", "opencode-mobile")
                .post(
                    json.encodeToString(JsonObject.serializer(), payload)
                        .toRequestBody("application/json".toMediaType()),
                )
                .build()
            Http.client.newCall(request).execute().use { response ->
                val text = runCatching { response.body?.string() ?: "" }.getOrDefault("")
                val obj = runCatching { json.decodeFromString(JsonObject.serializer(), text) }.getOrNull()
                    ?: JsonObject(emptyMap())
                if (!response.isSuccessful && obj.str("error") == null) {
                    throw GitHubApiException(response.code, "HTTP ${response.code} during GitHub auth")
                }
                obj
            }
        }
}

private fun JsonObject.str(key: String): String? =
    this[key]?.jsonPrimitive?.contentOrNull

private fun JsonObject.intOf(key: String): Int? =
    this[key]?.jsonPrimitive?.intOrNull

