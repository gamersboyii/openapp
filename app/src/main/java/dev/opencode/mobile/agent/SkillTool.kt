package dev.opencode.mobile.agent

import dev.opencode.mobile.core.skills.SkillStore
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Loads a built-in skill's full SKILL.md into context on demand.
 *
 * This is deliberately the ONLY channel for skill bodies: the system prompt
 * carries just ids + descriptions, so every enabled skill costs one line until
 * the model actually needs it. Also the one tool that stays available in Chat
 * Only mode, so style skills (caveman, ponytail) keep working there.
 */
object UseSkillTool : AgentTool {

    override val name = "use_skill"

    // `ids`/`description` need the store; they are wired up once in ToolRegistry
    // when AppContainer hands it the SkillStore instance. Until then the tool is
    // inert (no specs), which keeps construction order simple.
    @Volatile private var store: SkillStore? = null

    override val description: String
        get() = "Load the full instructions of a built-in skill into context. " +
            "Call this once at the start of a task the skill covers, before acting on it."

    override val parameters: JsonObject
        get() {
            // Catalog is preloaded during app bootstrap; this never blocks.
            val ids = store?.allCached()?.map { it.id }.orEmpty()
            return buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    put(
                        "id",
                        buildJsonObject {
                            put("type", "string")
                            put("description", "Skill id from the active skills list.")
                            if (ids.isNotEmpty()) {
                                putJsonArray("enum") { ids.forEach { add(JsonPrimitive(it)) } }
                            }
                        },
                    )
                }
                put("required", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("id"))))
            }
        }

    fun bind(store: SkillStore) {
        this.store = store
    }

    val available: Boolean get() = store != null

    override suspend fun execute(args: JsonObject, context: ToolContext): String {
        val skills = store ?: return "ERROR: no skill library available."
        val id = args.str("id").orEmpty().trim()
        val def = skills.all().firstOrNull { it.id == id || it.name.equals(id, ignoreCase = true) }
            ?: run {
                val known = skills.all().joinToString(", ") { it.id }
                return "ERROR: unknown skill '$id'. Available: $known"
            }

        context.onProgress("loading skill ${def.id}")
        val body = skills.content(def)
        if (body.isBlank()) return "ERROR: skill '$id' exists but its file could not be read."

        // Strip YAML front matter — the model does not need duplicate metadata.
        val payload = stripFrontMatter(body)

        val capped = if (payload.length > MAX_SKILL_CHARS) {
            payload.take(MAX_SKILL_CHARS) +
                "\n\n[skill truncated after ${MAX_SKILL_CHARS} characters — it is very long; " +
                "apply what you have]"
        } else {
            payload
        }

        return "SKILL ${def.id} (${def.name}, from ${def.source}):\n\n$capped"
    }

    override fun summarize(args: JsonObject) = "use_skill  ${args.str("id") ?: "?"}"

    const val MAX_SKILL_CHARS = 60_000

    /** Drops a leading `---\n...\n---` YAML block when present. */
    private fun stripFrontMatter(body: String): String {
        if (!body.startsWith("---")) return body
        val end = body.indexOf("\n---", 3)
        return if (end >= 0) body.substring(end + 4).trimStart() else body
    }
}
