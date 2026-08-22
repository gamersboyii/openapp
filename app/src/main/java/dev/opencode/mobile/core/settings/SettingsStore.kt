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
    val temperature: Double = 0.2,
    val maxTokens: Int = 8192,
    /** Hard stop on the tool-use loop so a confused model cannot spin forever. */
    val maxSteps: Int = 24,
    val gitUserName: String = "OpenCode Mobile",
    val gitUserEmail: String = "opencode@localhost",
    val gitUsername: String = "",
    val gitToken: String = "",
    val editorFontSize: Int = 13,
    val wordWrap: Boolean = false,
    val themeMode: ThemeMode = ThemeMode.DARK,
    val lastProjectPath: String? = null,
    val customInstructions: String = "",
    val onboarded: Boolean = false,
) {
    val activeProvider: ProviderConfig?
        get() = providers.firstOrNull { it.id == activeProviderId } ?: providers.firstOrNull()

    val gitCredentials: Pair<String, String>?
        get() = if (gitToken.isBlank()) null
        else (gitUsername.ifBlank { "x-access-token" }) to gitToken
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
