package dev.opencode.mobile.core.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dev.opencode.mobile.llm.ProviderConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
enum class ThemeMode { SYSTEM, DARK, LIGHT }

@Serializable
data class AppSettings(
    val providers: List<ProviderConfig> = emptyList(),
    val activeProviderId: String? = null,
    val activeModel: String = "",
    /** Reads (list/read/search) never need confirmation; writes are gated by default. */
    val autoApproveWrites: Boolean = false,
    /** SAFE commands always run; ASK commands skip their prompt when this is on. */
    val autoApproveCommands: Boolean = false,
    /** Hard timeout applied to sandboxed terminal commands. */
    val commandTimeoutSeconds: Int = 120,
    val temperature: Double = 0.2,
    val maxTokens: Int = 8192,
    /** Hard stop on the tool-use loop so a confused model cannot spin forever. */
    val maxSteps: Int = 24,
    /** Snapshot the project before the first change of an agent turn. */
    val autoCheckpoint: Boolean = true,
    /** Oldest checkpoints past this count are pruned (with their unreferenced blobs). */
    val maxCheckpoints: Int = 30,
    val gitUserName: String = "OpenCode Mobile",
    val gitUserEmail: String = "opencode@localhost",
    val gitUsername: String = "",
    val gitToken: String = "",
    /** GitHub OAuth/PAT token for the Hub tab and github_* tools. Never logged, never sent to the model. */
    val githubToken: String = "",
    /** Cached account login from the last successful verification (display only). */
    val githubLogin: String = "",
    /** OAuth app client id used by the GitHub device flow. Not a secret, but user-supplied. */
    val githubClientId: String = "",
    val editorFontSize: Int = 13,
    val wordWrap: Boolean = false,
    val themeMode: ThemeMode = ThemeMode.DARK,
    val lastProjectPath: String? = null,
    val customInstructions: String = "",
    /** Prepend the bundled INSTRUCTION.md handbook to every system prompt. */
    val useSystemPrompt: Boolean = true,
    /**
     * Fast mode (default on): send a condensed system prompt instead of the full
     * ~23 KB handbook, trim github_* tool specs while signed out, and compact
     * stale tool results. Cuts most of the dead prompt tokens that make every
     * request slow to produce its first token.
     */
    val fastMode: Boolean = true,
    /** Chat Only: pure conversation, project tools stripped from the model. */
    val chatOnly: Boolean = false,
    /** Built-in skill ids whose descriptions ride along in every prompt. */
    val enabledSkills: Set<String> = emptySet(),
    val onboarded: Boolean = false,
) {
    val activeProvider: ProviderConfig?
        get() = providers.firstOrNull { it.id == activeProviderId } ?: providers.firstOrNull()

    val gitCredentials: Pair<String, String>?
        get() = if (gitToken.isBlank()) null
        else (gitUsername.ifBlank { "x-access-token" }) to gitToken

    /** Falls back to the GitHub session token when no explicit host token is set. */
    val effectiveGitCredentials: Pair<String, String>?
        get() = gitCredentials
            ?: githubToken.takeIf { it.isNotBlank() }?.let { "x-access-token" to it }
}

/**
 * Settings live in [EncryptedSharedPreferences] because provider API keys and the
 * git token are stored alongside the rest. Everything is one JSON blob, which
 * keeps schema evolution to a single `ignoreUnknownKeys` parse.
 */
class SettingsStore(context: Context) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val prefs: SharedPreferences = runCatching {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "opencode_secure",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        ) as SharedPreferences
    }.getOrElse {
        // Keystore can be unavailable on a small number of devices; a plain file
        // is better than a crash loop on launch.
        context.getSharedPreferences("opencode_fallback", Context.MODE_PRIVATE)
    }

    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private fun load(): AppSettings {
        val raw = prefs.getString(KEY, null) ?: return AppSettings()
        return runCatching { json.decodeFromString<AppSettings>(raw) }.getOrDefault(AppSettings())
    }

    fun update(transform: (AppSettings) -> AppSettings) {
        val next = transform(_settings.value)
        _settings.value = next
        prefs.edit().putString(KEY, json.encodeToString(AppSettings.serializer(), next)).apply()
    }

    fun upsertProvider(config: ProviderConfig) = update { current ->
        val existing = current.providers.indexOfFirst { it.id == config.id }
        val providers = if (existing >= 0) {
            current.providers.toMutableList().apply { this[existing] = config }
        } else {
            current.providers + config
        }
        current.copy(
            providers = providers,
            activeProviderId = current.activeProviderId ?: config.id,
            activeModel = current.activeModel.ifBlank { config.defaultModel },
        )
    }

    fun removeProvider(id: String) = update { current ->
        val providers = current.providers.filterNot { it.id == id }
        val stillActive = current.activeProviderId.takeIf { it != id } ?: providers.firstOrNull()?.id
        current.copy(
            providers = providers,
            activeProviderId = stillActive,
            activeModel = if (current.activeProviderId == id) {
                providers.firstOrNull()?.defaultModel.orEmpty()
            } else {
                current.activeModel
            },
        )
    }

    fun selectModel(providerId: String, model: String) = update {
        it.copy(activeProviderId = providerId, activeModel = model)
    }

    private companion object {
        const val KEY = "settings_json_v1"
    }
}

/**
 * Masks anything that looks like a credential before a string reaches the UI or
 * a tool result. Defense in depth: tokens should never be in these strings in
 * the first place, but redaction must not depend on that.
 */
fun String.redactSecrets(): String = redactTokenRegex.replace(this, "[redacted]")

private val redactTokenRegex = Regex(
    "gh[pousr]_[A-Za-z0-9]{16,}" + // classic PATs
        "|github_pat_[A-Za-z0-9_]{20,}" + // fine-grained PATs
        "|gho_[A-Za-z0-9]{16,}" + // OAuth tokens
        "|[A-Fa-f0-9]{40}", // 40-hex tokens (legacy)
)
