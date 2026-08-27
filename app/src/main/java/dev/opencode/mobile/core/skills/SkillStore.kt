package dev.opencode.mobile.core.skills

import android.content.Context
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class SkillDef(
    val id: String,
    val name: String,
    val category: String,
    val source: String,
    val path: String,
    val description: String,
)

@Serializable
private data class SkillCatalogFile(
    val version: Int = 1,
    val skills: List<SkillDef> = emptyList(),
)

/**
 * The built-in skill library shipped in `assets/skills/`.
 *
 * A skill is a folder with a SKILL.md following the agent-skills convention:
 * YAML front matter (name/description) plus the instruction body. The system
 * prompt only ever carries each ENABLED skill's name + description; the full
 * body is loaded on demand through the `use_skill` tool, so token cost stays
 * proportional to what the model actually works with.
 */
class SkillStore(private val context: Context) {

    @Volatile private var cache: List<SkillDef>? = null

    /** Parsed once per process from assets/skills/index.json. */
    suspend fun all(): List<SkillDef> {
        cache?.let { return it }
        return loadCatalog().also { cache = it }
    }

    /** Non-blocking view of the catalog; empty until the first [all] completes. */
    fun allCached(): List<SkillDef> = cache.orEmpty()

    fun byIdBlocking(id: String): SkillDef? =
        cache?.firstOrNull { it.id == id }

    /** Full SKILL.md body for one skill; cached because assets never change at runtime. */
    suspend fun content(def: SkillDef): String {
        contentCache[def.id]?.let { return it }
        return withContext(Dispatchers.IO) {
            runCatching { context.assets.open(def.path).bufferedReader().use { it.readText() } }
                .getOrElse { "" }
        }.also { if (it.isNotEmpty()) contentCache[def.id] = it }
    }

    /**
     * Renders the ACTIVE SKILLS block appended to the system prompt: name and
     * description only — full text arrives via use_skill when needed.
     */
    suspend fun renderPromptBlock(enabledIds: Set<String>): String? {
        if (enabledIds.isEmpty()) return null
        val all = all()
        if (all.isEmpty()) return null
        val lines = buildList {
            add("SKILLS — guidance you should follow whenever relevant to the task.")
            add("Load full instructions by calling use_skill with the skill id.")
            addAll(enabledIds.mapNotNull { id ->
                val def = all.firstOrNull { it.id == id } ?: return@mapNotNull null
                "- ${def.id}: ${def.name} — ${def.description}"
            })
        }
        return lines.joinToString("\n")
    }

    private suspend fun loadCatalog(): List<SkillDef> = withContext(Dispatchers.IO) {
        val raw = runCatching {
            context.assets.open(CATALOG).bufferedReader().use { it.readText() }
        }.getOrNull() ?: return@withContext emptyList()
        runCatching { json.decodeFromString(SkillCatalogFile.serializer(), raw) }
            .getOrDefault(SkillCatalogFile()).skills.filter { it.id.isNotBlank() }
    }

    private val contentCache = ConcurrentHashMap<String, String>()

    companion object {
        const val CATALOG = "skills/index.json"

        private val json = Json { ignoreUnknownKeys = true }
    }
}
