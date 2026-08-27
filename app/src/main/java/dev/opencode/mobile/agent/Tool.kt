package dev.opencode.mobile.agent

import dev.opencode.mobile.core.build.BuildSystem
import dev.opencode.mobile.core.devserver.DevServerManager
import dev.opencode.mobile.core.exec.CommandHistoryStore
import dev.opencode.mobile.core.fs.Project
import dev.opencode.mobile.core.fs.WorkspaceManager
import dev.opencode.mobile.core.git.GitService
import dev.opencode.mobile.core.git.RepoSnapshotService
import dev.opencode.mobile.core.github.GitHubSession
import dev.opencode.mobile.core.preview.PreviewServer
import dev.opencode.mobile.core.settings.AppSettings
import dev.opencode.mobile.core.exec.TerminalService
import dev.opencode.mobile.llm.ToolSpec
import dev.opencode.mobile.llm.safePrim
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

class ToolContext(
    val workspace: WorkspaceManager,
    val git: GitService,
    val snapshots: RepoSnapshotService,
    val preview: PreviewServer,
    val terminal: TerminalService,
    val builds: BuildSystem,
    val devServer: DevServerManager,
    val history: CommandHistoryStore,
    val settings: AppSettings,
    /** Signed-in GitHub state; a null client means not signed in. */
    val github: GitHubSession,
    /** Active project. Null until one is created or opened. */
    val project: Project?,
    /** Lets a tool report intermediate progress (clone percentage, file counts). */
    val onProgress: (String) -> Unit = {},
    /** Called when a tool creates or switches the active project. */
    val onProjectChanged: suspend (String) -> Unit = {},
) {
    fun requireProject(): Project = project
        ?: throw IllegalStateException(
            "No project is open. Call create_project or git_clone first, " +
                "or ask the user to open one from the Projects tab.",
        )
}

interface AgentTool {
    val name: String
    val description: String
    val parameters: JsonObject

    /** Mutating tools are gated behind user approval unless auto-approve is on. */
    val mutating: Boolean get() = false

    /**
     * Whether this call needs user approval before it runs. The default gates
     * [mutating] tools; tools with per-call policies (like run_command)
     * override this and decide from their arguments.
     */
    fun needsApproval(args: JsonObject, settings: AppSettings): Boolean =
        mutating && !settings.autoApproveWrites

    suspend fun execute(args: JsonObject, context: ToolContext): String

    /** Short human label for the tool-call card, e.g. `write_file · index.html`. */
    fun summarize(args: JsonObject): String = name

    fun toSpec(): ToolSpec = ToolSpec(name, description, parameters)
}

// ---- JSON argument helpers -------------------------------------------------

fun JsonObject.str(key: String): String? = this[key].safePrim?.contentOrNull?.takeIf { it.isNotBlank() }

fun JsonObject.requireStr(key: String): String =
    str(key) ?: throw IllegalArgumentException("Missing required argument '$key'")

fun JsonObject.bool(key: String, fallback: Boolean = false): Boolean {
    val primitive = this[key].safePrim ?: return fallback
    return primitive.booleanOrNull
        ?: primitive.contentOrNull?.equals("true", ignoreCase = true)
        ?: fallback
}

fun JsonObject.int(key: String, fallback: Int): Int =
    this[key].safePrim?.intOrNull ?: fallback

// ---- Schema helpers ------------------------------------------------------

fun schema(vararg properties: Pair<String, JsonObject>, required: List<String> = emptyList()): JsonObject =
    buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            properties.forEach { (name, spec) -> put(name, spec) }
        }
        put(
            "required",
            kotlinx.serialization.json.JsonArray(required.map { JsonPrimitive(it) }),
        )
    }

fun stringProp(description: String, enum: List<String>? = null): JsonObject = buildJsonObject {
    put("type", "string")
    put("description", description)
    if (enum != null) {
        put("enum", kotlinx.serialization.json.JsonArray(enum.map { JsonPrimitive(it) }))
    }
}

fun boolProp(description: String): JsonObject = buildJsonObject {
    put("type", "boolean")
    put("description", description)
}

fun intProp(description: String): JsonObject = buildJsonObject {
    put("type", "integer")
    put("description", description)
}
