package dev.opencode.mobile.core.instructions

import android.content.Context
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Manages the agent's base system prompt ("INSTRUCTION.md").
 *
 * The bundled asset is copied to app-private storage on first use so it can be
 * edited by the user without touching the APK, and restored from the asset with
 * a reset. The engine prepends this content to every system prompt while
 * `useSystemPrompt` is enabled in settings.
 */
class InstructionStore(
    private val context: Context,
    private val file: File,
) {
    private val _text = MutableStateFlow("")
    val text: StateFlow<String> = _text.asStateFlow()

    private val _modified = MutableStateFlow(false)
    /** True when the stored text diverges from the bundled copy. */
    val modified: StateFlow<Boolean> = _modified.asStateFlow()

    @Volatile private var bundledCache: String? = null

    suspend fun load() = withContext(Dispatchers.IO) {
        if (!file.isFile) copyBundled()
        read()
        recomputeModified(_text.value)
    }

    /** Persists an edit made in Settings. */
    fun update(value: String) {
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(value)
        }
        _text.value = value
        recomputeModified(value)
    }

    /** Re-copies the APK's INSTRUCTION.md over any user edits. */
    suspend fun resetToBundled() = withContext(Dispatchers.IO) {
        copyBundled()
        read()
    }

    private fun copyBundled() {
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(bundledCopy())
        }
    }

    private fun read() {
        _text.value = runCatching { file.readText() }.getOrDefault("")
    }

    private fun recomputeModified(current: String) {
        val bundled = bundledCopy()
        _modified.value = bundled.isNotEmpty() && current != bundled
    }

    private fun bundledCopy(): String {
        bundledCache?.let { return it }
        return runCatching { context.assets.open(BUNDLED).bufferedReader().use { it.readText() } }
            .getOrDefault("")
            .also { if (it.isNotEmpty()) bundledCache = it }
    }

    companion object {
        const val BUNDLED = "INSTRUCTION.md"

        /**
         * Hard cap on how much of the instruction file reaches the model. The
         * bundled document is well under this; the cap only exists so a runaway
         * user paste cannot blow up every request.
         */
        const val MAX_PROMPT_CHARS = 32_000

        fun create(context: Context): InstructionStore {
            val dir = File(context.filesDir, "instructions")
            return InstructionStore(context.applicationContext, File(dir, BUNDLED))
        }
    }
}
