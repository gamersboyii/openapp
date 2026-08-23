package dev.opencode.mobile.agent

import dev.opencode.mobile.core.build.BuildSystem
import dev.opencode.mobile.core.checkpoint.Checkpoint
import dev.opencode.mobile.core.checkpoint.CheckpointService
import dev.opencode.mobile.core.exec.CommandPolicy
import dev.opencode.mobile.core.exec.CommandHistoryStore
import dev.opencode.mobile.core.exec.TerminalService
import dev.opencode.mobile.core.fs.Project
import dev.opencode.mobile.core.fs.WorkspaceManager
import dev.opencode.mobile.core.git.GitService
import dev.opencode.mobile.core.git.RepoSnapshotService
import dev.opencode.mobile.core.preview.PreviewServer
import dev.opencode.mobile.core.settings.AppSettings
import dev.opencode.mobile.core.settings.SettingsStore
import dev.opencode.mobile.llm.ChatMessage
import dev.opencode.mobile.llm.LlmEvent
import dev.opencode.mobile.llm.ProviderRegistry
import dev.opencode.mobile.llm.Role
import dev.opencode.mobile.llm.ToolCall
import dev.opencode.mobile.llm.ToolResult
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

enum class EntryKind { USER, ASSISTANT, TOOL, ERROR, NOTICE }

enum class ToolStatus { AWAITING_APPROVAL, RUNNING, DONE, FAILED, DENIED }

@Serializable
data class ToolRun(
    val callId: String,
    val name: String,
    val summary: String,
    val argumentsJson: String,
    val status: ToolStatus,
    val result: String = "",
)

@Serializable
data class ChatEntry(
    val id: Long,
    val kind: EntryKind,
    val text: String = "",
    val reasoning: String = "",
    val toolRun: ToolRun? = null,
    val streaming: Boolean = false,
)

data class ApprovalRequest(
    val entryId: Long,
    val toolName: String,
    val summary: String,
    val detail: String,
)

/**
 * Set after an agent turn that changed files on disk. Carries just enough for the
 * chat review bar; the review screen re-reads the live diff from [CheckpointService]
 * against [checkpointId] so per-file reverts stay accurate.
 */
data class TurnReview(
    val checkpointId: Long,
    val label: String,
    val fileCount: Int,
    val added: Int,
    val removed: Int,
)

@Serializable
private data class SessionSnapshot(
    val entries: List<ChatEntry>,
    val history: List<ChatMessage>,
)

/**
 * The tool-use loop: stream a turn, run whatever tools the model asked for, feed
 * the results back, repeat until it stops calling tools or hits `maxSteps`.
 *
 * Two message lists are kept on purpose. [entries] is what the UI renders and can
 * hold things the model never sees (notices, approval state). [history] is the
 * exact wire transcript sent to the provider.
 */
class AgentEngine(
    private val workspace: WorkspaceManager,
    private val git: GitService,
    private val snapshots: RepoSnapshotService,
    private val checkpoints: CheckpointService,
    private val preview: PreviewServer,
    private val terminal: TerminalService,
    private val builds: BuildSystem,
    private val commandHistory: CommandHistoryStore,
    private val settingsStore: SettingsStore,
    private val scope: CoroutineScope,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }

    private var nextId = 1L
    private val history = mutableListOf<ChatMessage>()

    private val _entries = MutableStateFlow<List<ChatEntry>>(emptyList())
    val entries: StateFlow<List<ChatEntry>> = _entries.asStateFlow()

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _status = MutableStateFlow("")
    val status: StateFlow<String> = _status.asStateFlow()

    private val _pendingApproval = MutableStateFlow<ApprovalRequest?>(null)
    val pendingApproval: StateFlow<ApprovalRequest?> = _pendingApproval.asStateFlow()

    private val _pendingReview = MutableStateFlow<TurnReview?>(null)
    val pendingReview: StateFlow<TurnReview?> = _pendingReview.asStateFlow()

    private var approvalGate: CompletableDeferred<Boolean>? = null
    private var turnJob: Job? = null
    private var sessionDir: File? = null

    // Snapshot taken before the first change of the current turn, and the project
    // it belongs to, so the post-turn diff and any undo target the right tree.
    private var turnCheckpoint: Checkpoint? = null
    private var turnProject: Project? = null
    private var turnReason: String = ""

    // ---- public API -------------------------------------------------------

    fun send(userText: String) {
        if (_isRunning.value || userText.isBlank()) return
        turnJob = scope.launch { runTurn(userText.trim()) }
    }

    fun cancel() {
        turnJob?.cancel()
        approvalGate?.complete(false)
        approvalGate = null
        _pendingApproval.value = null
        _isRunning.value = false
        _status.value = ""
        updateStreamingEntries()
    }

    fun respondToApproval(approved: Boolean) {
        approvalGate?.complete(approved)
        approvalGate = null
        _pendingApproval.value = null
    }

    /** Keeps the turn's changes; the checkpoint stays available for a later undo. */
    fun acceptReview() {
        _pendingReview.value = null
    }

    /** Rolls the working tree back to the pre-turn checkpoint. Files, not chat, revert. */
    fun undoTurn() {
        val review = _pendingReview.value ?: return
        val project = turnProject ?: workspace.activeProject.value ?: return
        scope.launch {
            val restored = runCatching { checkpoints.restore(project, review.checkpointId) }.getOrDefault(-1)
            workspace.notifyChanged()
            if (preview.state.value.running) preview.signalReload()
            addNotice(
                if (restored >= 0) "Reverted $restored files to ${review.label}."
                else "Could not restore ${review.label} — the checkpoint may have been pruned.",
            )
            _pendingReview.value = null
            persist()
        }
    }

    fun clear() {
        cancel()
        history.clear()
        _entries.value = emptyList()
        _pendingReview.value = null
        turnCheckpoint = null
        turnProject = null
        scope.launch { persist() }
    }

    /** Swaps the transcript when the active project changes. */
    suspend fun bindProject(project: Project?) {
        _pendingReview.value = null
        turnCheckpoint = null
        turnProject = null
        if (project == null) {
            sessionDir = null
            history.clear()
            _entries.value = emptyList()
            return
        }
        val dir = File(project.dir, SESSION_DIR)
        if (sessionDir?.absolutePath == dir.absolutePath) return
        sessionDir = dir
        restore()
    }

    fun addNotice(text: String) {
        appendEntry(ChatEntry(id = nextId++, kind = EntryKind.NOTICE, text = text))
    }

    // ---- turn loop --------------------------------------------------------

    private suspend fun runTurn(userText: String) {
        val settings = settingsStore.settings.value
        val provider = settings.activeProvider
        val model = settings.activeModel.ifBlank { provider?.defaultModel.orEmpty() }

        // A fresh turn supersedes any unreviewed one; moving on accepts it implicitly.
        _pendingReview.value = null
        turnCheckpoint = null
        turnProject = null
        turnReason = userText.take(80)

        appendEntry(ChatEntry(id = nextId++, kind = EntryKind.USER, text = userText))
        history += ChatMessage(role = Role.USER, text = userText)

        if (provider == null || model.isBlank()) {
            appendEntry(
                ChatEntry(
                    id = nextId++,
                    kind = EntryKind.ERROR,
                    text = "No model selected. Open Settings, add a provider key, and pick a model.",
                ),
            )
            persist()
            return
        }

        _isRunning.value = true
        _status.value = "Thinking…"

        try {
            var step = 0
            while (step < settings.maxSteps) {
                step++
                val calls = streamOneAssistantTurn(provider, model, settings.temperature, settings.maxTokens)
                    ?: break // a provider error already surfaced

                if (calls.isEmpty()) break

                for (call in calls) {
                    executeCall(call)
                }

                if (step == settings.maxSteps) {
                    appendEntry(
                        ChatEntry(
                            id = nextId++,
                            kind = EntryKind.NOTICE,
                            text = "Stopped after ${settings.maxSteps} tool steps. Send another message to continue.",
                        ),
                    )
                }
            }
        } catch (cancel: CancellationException) {
            appendEntry(ChatEntry(id = nextId++, kind = EntryKind.NOTICE, text = "Cancelled."))
        } catch (error: Throwable) {
            appendEntry(
                ChatEntry(
                    id = nextId++,
                    kind = EntryKind.ERROR,
                    text = error.message ?: error.toString(),
                ),
            )
        } finally {
            _isRunning.value = false
            _status.value = ""
            updateStreamingEntries()
            finishTurnReview()
            persist()
        }
    }

    /**
     * Turns the pre-turn checkpoint into a reviewable diff. Runs [NonCancellable] so a
     * stopped turn still surfaces (and can undo) whatever it managed to change. An empty
     * diff means nothing was written, so the spurious checkpoint is dropped.
     */
    private suspend fun finishTurnReview() = withContext(NonCancellable + Dispatchers.IO) {
        val checkpoint = turnCheckpoint ?: return@withContext
        val project = turnProject ?: return@withContext
        turnCheckpoint = null
        val changes = runCatching { checkpoints.diff(project, checkpoint.id) }.getOrDefault(emptyList())
        if (changes.isEmpty()) {
            runCatching { checkpoints.delete(project, checkpoint.id) }
            _pendingReview.value = null
        } else {
            _pendingReview.value = TurnReview(
                checkpointId = checkpoint.id,
                label = checkpoint.label,
                fileCount = changes.size,
                added = changes.sumOf { it.added },
                removed = changes.sumOf { it.removed },
            )
        }
    }

    /**
     * Streams one assistant message. Returns the tool calls it requested, or null
     * when the provider failed (the error is already appended as an entry).
     */
    private suspend fun streamOneAssistantTurn(
        provider: dev.opencode.mobile.llm.ProviderConfig,
        model: String,
        temperature: Double,
        maxTokens: Int,
    ): List<ToolCall>? {
        val entryId = nextId++
        appendEntry(ChatEntry(id = entryId, kind = EntryKind.ASSISTANT, streaming = true))

        val text = StringBuilder()
        val reasoning = StringBuilder()
        val calls = mutableListOf<ToolCall>()
        var failure: String? = null

        ProviderRegistry.forConfig(provider).stream(
            config = provider,
            model = model,
            systemPrompt = buildSystemPrompt(),
            messages = history.toList(),
            tools = ToolRegistry.specs(),
            temperature = temperature,
            maxTokens = maxTokens,
        ).collect { event ->
            when (event) {
                is LlmEvent.TextDelta -> {
                    text.append(event.text)
                    patchEntry(entryId) { it.copy(text = text.toString()) }
                }

                is LlmEvent.ReasoningDelta -> {
                    reasoning.append(event.text)
                    patchEntry(entryId) { it.copy(reasoning = reasoning.toString()) }
                }

                is LlmEvent.ToolCallReady -> calls += event.call

                is LlmEvent.Failed -> failure = event.message

                is LlmEvent.Completed -> Unit
            }
        }

        patchEntry(entryId) { it.copy(streaming = false) }

        if (failure != null) {
            // Drop the empty assistant bubble; the error entry carries the detail.
            if (text.isBlank() && calls.isEmpty()) removeEntry(entryId)
            appendEntry(ChatEntry(id = nextId++, kind = EntryKind.ERROR, text = failure!!))
            return null
        }

        if (text.isBlank() && calls.isEmpty()) {
            removeEntry(entryId)
            appendEntry(
                ChatEntry(
                    id = nextId++,
                    kind = EntryKind.NOTICE,
                    text = "The model returned an empty response. Try again or pick another model.",
                ),
            )
            return null
        }

        history += ChatMessage(role = Role.ASSISTANT, text = text.toString(), toolCalls = calls)
        return calls
    }

    private enum class CallOutcome { DONE, FAILED, DENIED }

    private suspend fun executeCall(call: ToolCall): CallOutcome {
        val tool = ToolRegistry.find(call.name)
        val args = dev.opencode.mobile.llm.parseArgs(call.argumentsJson)
        val settings = settingsStore.settings.value

        if (tool == null) {
            val message = "ERROR: unknown tool '${call.name}'. " +
                "Available: ${ToolRegistry.tools.joinToString(", ") { it.name }}"
            recordToolEntry(call, "unknown tool", ToolStatus.FAILED, message)
            history += ChatMessage(
                role = Role.TOOL,
                toolResult = ToolResult(call.id, call.name, message, isError = true),
            )
            return CallOutcome.FAILED
        }

        val summary = runCatching { tool.summarize(args) }.getOrDefault(tool.name)
        val needsApproval = tool.needsApproval(args, settings)
        val entryId = recordToolEntry(
            call = call,
            summary = summary,
            status = if (needsApproval) ToolStatus.AWAITING_APPROVAL else ToolStatus.RUNNING,
        )

        if (needsApproval) {
            _status.value = "Waiting for approval"
            val gate = CompletableDeferred<Boolean>()
            approvalGate = gate
            _pendingApproval.value = ApprovalRequest(
                entryId = entryId,
                toolName = tool.name,
                summary = summary,
                detail = approvalDetail(tool, args),
            )
            val approved = gate.await()
            _pendingApproval.value = null

            if (!approved) {
                val message = "The user declined this action. Do not retry it. " +
                    "Explain what you wanted to do, or propose a different approach."
                patchEntry(entryId) {
                    it.copy(toolRun = it.toolRun?.copy(status = ToolStatus.DENIED, result = "Declined"))
                }
                history += ChatMessage(
                    role = Role.TOOL,
                    toolResult = ToolResult(call.id, call.name, message, isError = true),
                )
                return CallOutcome.DENIED
            }
            patchEntry(entryId) { it.copy(toolRun = it.toolRun?.copy(status = ToolStatus.RUNNING)) }
        }

        _status.value = summary

        maybeCheckpoint(tool, settings)

        val context = ToolContext(
            workspace = workspace,
            git = git,
            snapshots = snapshots,
            preview = preview,
            terminal = terminal,
            builds = builds,
            history = commandHistory,
            settings = settings,
            project = workspace.activeProject.value,
            onProgress = { progress -> _status.value = "$summary — $progress" },
            onProjectChanged = { path ->
                workspace.selectByPath(path)
                settingsStore.update { it.copy(lastProjectPath = path) }
                sessionDir = File(path, SESSION_DIR)
            },
        )

        val result = runCatching { tool.execute(args, context) }
        val output = result.getOrElse { error ->
            "ERROR: ${error.message ?: error.toString()}"
        }
        val isError = result.isFailure || output.startsWith("ERROR")

        patchEntry(entryId) {
            it.copy(
                toolRun = it.toolRun?.copy(
                    status = if (isError) ToolStatus.FAILED else ToolStatus.DONE,
                    result = output,
                ),
            )
        }
        history += ChatMessage(
            role = Role.TOOL,
            toolResult = ToolResult(call.id, call.name, output.take(MAX_TOOL_RESULT_CHARS), isError = isError),
        )
        workspace.notifyChanged()
        return if (isError) CallOutcome.FAILED else CallOutcome.DONE
    }

    /** Snapshots the tree before the first mutating action of a turn (once per turn). */
    private suspend fun maybeCheckpoint(tool: AgentTool, settings: AppSettings) {
        if (turnCheckpoint != null || !settings.autoCheckpoint) return
        val mutates = tool.mutating ||
            tool.name == RunCommandTool.name ||
            tool.name == BuildProjectTool.name
        if (!mutates) return
        val project = workspace.activeProject.value ?: return
        val checkpoint = runCatching {
            checkpoints.capture(
                project = project,
                reason = turnReason,
                retain = settings.maxCheckpoints,
            )
        }.getOrNull() ?: return
        turnCheckpoint = checkpoint
        turnProject = project
        addNotice("${checkpoint.label} saved before changes.")
    }

    private fun approvalDetail(tool: AgentTool, args: kotlinx.serialization.json.JsonObject): String =
        when (tool.name) {
            WriteFileTool.name -> {
                val content = args.str("content").orEmpty()
                "${args.str("path")}\n\n${content.take(1200)}" +
                    if (content.length > 1200) "\n… (${content.length} chars total)" else ""
            }

            EditFileTool.name -> buildString {
                appendLine(args.str("path").orEmpty())
                appendLine()
                appendLine("- " + args.str("old_string").orEmpty().take(600).replace("\n", "\n- "))
                appendLine("+ " + args.str("new_string").orEmpty().take(600).replace("\n", "\n+ "))
            }

            RunCommandTool.name -> {
                val command = args.str("command").orEmpty()
                val verdict = CommandPolicy.classify(command)
                "$ $command\n\nPolicy: ${verdict.decision} — ${verdict.reason}"
            }

            BuildProjectTool.name -> {
                val action = args.str("action") ?: "detect"
                "Runs the '$action' lifecycle step for this project's detected type."
            }

            else -> args.entries
                .filter { it.key != "content" }
                .joinToString("\n") { "${it.key}: ${it.value.toString().take(300)}" }
                .ifBlank { "(no arguments)" }
        }

    private fun recordToolEntry(
        call: ToolCall,
        summary: String,
        status: ToolStatus,
        result: String = "",
    ): Long {
        val id = nextId++
        appendEntry(
            ChatEntry(
                id = id,
                kind = EntryKind.TOOL,
                toolRun = ToolRun(
                    callId = call.id,
                    name = call.name,
                    summary = summary,
                    argumentsJson = call.argumentsJson,
                    status = status,
                    result = result,
                ),
            ),
        )
        return id
    }

    // ---- system prompt ----------------------------------------------------

    private fun buildSystemPrompt(): String {
        val settings = settingsStore.settings.value
        val project = workspace.activeProject.value

        return buildString {
            appendLine(
                """
                You are OpenCode, a coding agent running entirely on an Android phone.
                You work by calling tools. Prefer acting over describing: read the files
                you need, make the edit, then say what changed in one or two sentences.

                ENVIRONMENT — read this carefully, it is unusual:
                - Commands run through `run_command` on the phone's Android shell
                  (toybox) inside a sandbox: working directory pinned to the project,
                  fixed PATH, hard timeout, output caps. Basic inspection works
                  (ls, cat, grep, find, wc). There is NO Node.js/npm, NO Python,
                  NO JDK/Gradle, NO cargo/go installed — ecosystem installs and
                  builds fail with 'not found'. Never pretend a build succeeded.
                - Every command is classified before it runs: read-only commands run
                  immediately; anything that writes or installs asks the user;
                  destructive commands and access outside the project are blocked.
                  If a command was blocked, do not retry variations of it.
                - `build_project` detects the project type and runs detect/install/
                  build/test/run/clean, returning structured file:line diagnostics.
                  Read them, fix the named files, then build again. For static web
                  projects there is nothing to compile — use `preview` instead.
                - Web projects must work by opening an HTML file directly. Get dependencies
                  from a CDN (esm.sh, unpkg, jsdelivr) using an import map, or use a global
                  script build. For React, JSX is compiled in-page by @babel/standalone.
                - Tailwind is available through its CDN build (no PostCSS).
                - Files live in a private sandbox for the active project. All paths are
                  relative to the project root; `..` is rejected.
                - Git works through JGit over HTTPS only. SSH remotes fail.
                - `preview` serves the project on 127.0.0.1 and the Preview tab
                  auto-reloads after every write. Call it once the site is worth looking at.
                - The screen is small: keep replies short, avoid dumping whole files back
                  to the user, and never paste a file you just wrote.

                WORKING RULES:
                - Call `project_info` or `list_files` first when you do not know the layout.
                - Read a file before editing it. `edit_file` needs an exact, unique match.
                - Use `edit_file` for small changes, `write_file` for new or rewritten files.
                - Batch related edits in one turn instead of asking after each step.
                - When the user asks for a website or app, scaffold with `create_project`
                  using the closest template, then customise it.
                - After finishing a visual change, call `preview` so the user can see it.
                - If a tool returns an error, fix the cause; do not repeat the same call.
                """.trimIndent(),
            )

            appendLine()
            if (project == null) {
                appendLine("No project is open. Use create_project, git_clone or fetch_repo_snapshot to start one.")
            } else {
                appendLine("ACTIVE PROJECT: ${project.name}")
                appendLine("Files: ${project.fileCount}. Git repository: ${project.isGitRepo}.")
                appendLine("Templates available to create_project: ${Templates.ids.joinToString(", ")}.")
            }

            if (settings.autoApproveWrites) {
                appendLine("File writes are auto-approved; the user is not asked before each change.")
            } else {
                appendLine("Every mutating tool call is shown to the user for approval before it runs.")
            }

            if (settings.autoApproveCommands) {
                appendLine("Non-read-only commands and build steps are auto-approved after policy screening.")
            } else {
                appendLine("Commands that are not read-only are shown to the user for approval first.")
            }

            if (settings.customInstructions.isNotBlank()) {
                appendLine()
                appendLine("USER INSTRUCTIONS:")
                appendLine(settings.customInstructions.trim())
            }
        }
    }

    // ---- entry list helpers ----------------------------------------------

    private fun appendEntry(entry: ChatEntry) {
        _entries.value = _entries.value + entry
    }

    private fun patchEntry(id: Long, transform: (ChatEntry) -> ChatEntry) {
        _entries.value = _entries.value.map { if (it.id == id) transform(it) else it }
    }

    private fun removeEntry(id: Long) {
        _entries.value = _entries.value.filterNot { it.id == id }
    }

    private fun updateStreamingEntries() {
        if (_entries.value.none { it.streaming }) return
        _entries.value = _entries.value.map { if (it.streaming) it.copy(streaming = false) else it }
    }

    // ---- persistence ------------------------------------------------------

    /** Runs [NonCancellable] so a cancelled turn still saves what it produced. */
    private suspend fun persist() = withContext(NonCancellable + Dispatchers.IO) {
        val dir = sessionDir ?: return@withContext
        runCatching {
            dir.mkdirs()
            File(dir, SESSION_FILE).writeText(
                json.encodeToString(
                    SessionSnapshot.serializer(),
                    SessionSnapshot(_entries.value, history.toList()),
                ),
            )
        }
    }

    private suspend fun restore() = withContext(Dispatchers.IO) {
        val file = sessionDir?.let { File(it, SESSION_FILE) }
        if (file == null || !file.isFile) {
            history.clear()
            _entries.value = emptyList()
            nextId = 1L
            return@withContext
        }
        runCatching {
            val snapshot = json.decodeFromString(SessionSnapshot.serializer(), file.readText())
            history.clear()
            history += snapshot.history
            _entries.value = snapshot.entries.map {
                if (it.streaming) it.copy(streaming = false) else it
            }
            nextId = (snapshot.entries.maxOfOrNull { it.id } ?: 0L) + 1L
        }.onFailure {
            history.clear()
            _entries.value = emptyList()
            nextId = 1L
        }
    }

    private companion object {
        const val SESSION_DIR = ".opencode"
        const val SESSION_FILE = "session.json"
        const val MAX_TOOL_RESULT_CHARS = 30_000
    }
}
