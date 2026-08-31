package dev.opencode.mobile.agent

import dev.opencode.mobile.core.build.BuildSystem
import dev.opencode.mobile.core.checkpoint.Checkpoint
import dev.opencode.mobile.core.checkpoint.CheckpointService
import dev.opencode.mobile.core.devserver.DevServerManager
import dev.opencode.mobile.core.exec.CommandPolicy
import dev.opencode.mobile.core.exec.CommandHistoryStore
import dev.opencode.mobile.core.exec.TerminalService
import dev.opencode.mobile.core.fs.Project
import dev.opencode.mobile.core.fs.WorkspaceManager
import dev.opencode.mobile.core.git.GitService
import dev.opencode.mobile.core.git.RepoSnapshotService
import dev.opencode.mobile.core.github.GitHubSession
import dev.opencode.mobile.core.instructions.InstructionStore
import dev.opencode.mobile.core.preview.PreviewServer
import dev.opencode.mobile.core.settings.AppSettings
import dev.opencode.mobile.core.settings.SettingsStore
import dev.opencode.mobile.core.skills.SkillStore
import dev.opencode.mobile.llm.ChatMessage
import dev.opencode.mobile.llm.LlmEvent
import dev.opencode.mobile.llm.ProviderRegistry
import dev.opencode.mobile.llm.Role
import dev.opencode.mobile.llm.ToolCall
import dev.opencode.mobile.llm.ToolResult
import dev.opencode.mobile.llm.ToolSpec
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
    /** Per-file summary, newest-path last — shown directly on the review bar. */
    val files: List<TurnFileStat> = emptyList(),
)

/** One row of "App.kt  +42 -18" on the review bar. */
data class TurnFileStat(val path: String, val type: String, val added: Int, val removed: Int)

/** One row of the Sessions screen. Lightweight — the full transcript stays on disk. */
data class SessionMeta(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val messageCount: Int,
    val preview: String,
)

@Serializable
private data class SessionSnapshot(
    val entries: List<ChatEntry>,
    val history: List<ChatMessage>,
)

/** Full transcript of one chat session, persisted as `sessions/<id>.json`. */
@Serializable
private data class SessionFile(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val entries: List<ChatEntry>,
    val history: List<ChatMessage>,
)

@Serializable
private data class SessionIndexEntry(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val messageCount: Int = 0,
    val preview: String = "",
)

/** `sessions/index.json` — the cheap catalog the Sessions screen reads. */
@Serializable
private data class SessionIndex(
    val activeId: String? = null,
    val sessions: List<SessionIndexEntry> = emptyList(),
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
    private val devServer: DevServerManager,
    private val commandHistory: CommandHistoryStore,
    private val settingsStore: SettingsStore,
    private val github: GitHubSession,
    private val skills: SkillStore,
    private val instructions: InstructionStore,
    /** Where sessions live when no project is open — general chats survive restarts too. */
    private val fallbackSessionsRoot: File,
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

    /** Background mode: a turn pauses between steps while this is set (feature 12). */
    private val _paused = MutableStateFlow(false)
    val paused: StateFlow<Boolean> = _paused.asStateFlow()

    /** Bounded recent-activity log mirrored into the background notification. */
    private val _progress = MutableStateFlow<List<String>>(emptyList())
    val progress: StateFlow<List<String>> = _progress.asStateFlow()

    /** Files successfully written/edited/deleted by the current or last turn. */
    private val _turnFilesChanged = MutableStateFlow(0)
    val turnFilesChanged: StateFlow<Int> = _turnFilesChanged.asStateFlow()

    /** Last user prompt; powers notification retry after a failed background turn. */
    var lastPrompt: String = ""
        private set

    private var approvalGate: CompletableDeferred<Boolean>? = null
    private var turnJob: Job? = null

    // ---- multi-session state -------------------------------------------------
    // Sessions live under <root>/sessions/ where root is the project's .opencode
    // dir, or [fallbackSessionsRoot] when no project is open. An index.json next
    // to the session files is the cheap catalog the Sessions screen reads.
    private var sessionRoot: File? = null
    private var sessionId: String? = null
    private var sessionTitle: String = ""
    private var sessionCreatedAt: Long = 0L

    private val _sessions = MutableStateFlow<List<SessionMeta>>(emptyList())
    val sessions: StateFlow<List<SessionMeta>> = _sessions.asStateFlow()

    private val _activeSessionId = MutableStateFlow<String?>(null)
    val activeSessionId: StateFlow<String?> = _activeSessionId.asStateFlow()

    /** Messages typed while a turn is running; they send the moment it finishes. */
    private val pendingQueue = ArrayDeque<String>()

    // Snapshot taken before the first change of the current turn, and the project
    // it belongs to, so the post-turn diff and any undo target the right tree.
    private var turnCheckpoint: Checkpoint? = null
    private var turnProject: Project? = null
    private var turnReason: String = ""

    // ---- public API -------------------------------------------------------

    /**
     * Sends a user message. While a turn is running the text is shown immediately
     * and queued — it goes to the provider the moment the current turn ends, so
     * nothing typed mid-run is ever dropped.
     */
    fun send(userText: String) {
        val text = userText.trim()
        if (text.isBlank()) return
        if (_isRunning.value) {
            appendEntry(ChatEntry(id = nextId++, kind = EntryKind.USER, text = text))
            addNotice("Queued — it will send when this turn finishes.")
            pendingQueue += text
            scope.launch { persist() }
            return
        }
        lastPrompt = text
        turnJob = scope.launch { runTurn(text) }
    }

    fun cancel() {
        turnJob?.cancel()
        approvalGate?.complete(false)
        approvalGate = null
        _pendingApproval.value = null
        _isRunning.value = false
        _paused.value = false
        _status.value = ""
        updateStreamingEntries()
    }

    fun respondToApproval(approved: Boolean) {
        approvalGate?.complete(approved)
        approvalGate = null
        _pendingApproval.value = null
    }

    // ---- background-mode controls (feature 12) -----------------------------

    /** Takes effect at the next step boundary of a running turn. */
    fun pause() {
        if (_isRunning.value) {
            _paused.value = true
            _status.value = "Paused"
            addProgress("‖ paused")
        }
    }

    fun resume() {
        if (_paused.value) {
            _paused.value = false
            _status.value = "Resuming…"
            addProgress("▶ resumed")
        }
    }

    /** Re-sends the last user prompt after a failed turn (failure recovery). */
    fun retryLastTurn() {
        if (!_isRunning.value && lastPrompt.isNotBlank()) send(lastPrompt)
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

    /**
     * Starts a fresh chat. The current transcript is saved into the session list
     * first, so "New chat" never destroys history — it just starts a new file.
     */
    fun newSession() {
        if (_isRunning.value) return
        pendingQueue.clear()
        cancel()
        val snapshot = SessionSnapshot(_entries.value, history.toList())
        val savedId = sessionId
        val savedTitle = sessionTitle
        val savedCreated = sessionCreatedAt
        resetTranscript()
        if (savedId == null && snapshot.entries.isEmpty() && snapshot.history.isEmpty()) return
        scope.launch {
            persistCaptured(savedId, savedTitle, savedCreated, snapshot)
            // The archived chat is no longer the open one — clear its active marker
            // so a restart reopens nothing instead of the chat just archived.
            writeIndex { it.copy(activeId = null) }
        }
    }

    /**
     * Replaces the open chat with a saved session. Returns false while a turn is
     * running (switching mid-run would corrupt the wire transcript) or on a no-op.
     */
    fun switchTo(id: String): Boolean {
        if (_isRunning.value || id == sessionId) return false
        scope.launch {
            persist()
            if (restoreSession(id)) {
                // Keep the index marker in step so a restart reopens this session.
                writeIndex { it.copy(activeId = id) }
            } else {
                addNotice("That session could not be loaded.")
            }
        }
        return true
    }

    /** Deletes a saved session; deleting the open chat resets to a fresh canvas. */
    fun deleteSession(id: String) {
        scope.launch {
            withContext(Dispatchers.IO) {
                runCatching { File(sessionsDir(), "$id.json").delete() }
            }
            removeFromIndex(id)
            if (id == sessionId) resetTranscript()
        }
    }

    /** Renames a saved session (index + file, so a restore keeps the name). */
    fun renameSession(id: String, title: String) {
        val name = title.trim().take(120)
        if (name.isBlank()) return
        if (id == sessionId) sessionTitle = name
        scope.launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    val file = File(sessionsDir(), "$id.json")
                    if (file.isFile) {
                        val snap = json.decodeFromString(SessionFile.serializer(), file.readText())
                        file.writeText(
                            json.encodeToString(SessionFile.serializer(), snap.copy(title = name)),
                        )
                    }
                }
            }
            writeIndex { current ->
                current.copy(
                    sessions = current.sessions.map { if (it.id == id) it.copy(title = name) else it },
                )
            }
        }
    }

    /** Swaps the session space when the active project changes. */
    suspend fun bindProject(project: Project?) {
        _pendingReview.value = null
        turnCheckpoint = null
        turnProject = null
        val root = if (project != null) File(project.dir, SESSION_DIR) else fallbackSessionsRoot
        if (sessionRoot?.absolutePath == root.absolutePath) return
        persist() // save the outgoing chat under the old space
        sessionRoot = root
        // The outgoing chat's id must not leak into the new space — its index
        // decides which session opens here.
        sessionId = null
        _activeSessionId.value = null
        migrateLegacySession(root)
        readIndex()
        restoreActiveOrFresh()
    }

    fun addNotice(text: String) {
        appendEntry(ChatEntry(id = nextId++, kind = EntryKind.NOTICE, text = text))
    }

    // ---- turn loop --------------------------------------------------------

    private suspend fun runTurn(userText: String, entryAlreadyAppended: Boolean = false) {
        val settings = settingsStore.settings.value
        val provider = settings.activeProvider
        val model = settings.activeModel.ifBlank { provider?.defaultModel.orEmpty() }

        // A fresh turn supersedes any unreviewed one; moving on accepts it implicitly.
        _pendingReview.value = null
        turnCheckpoint = null
        turnProject = null
        turnReason = userText.take(80)

        if (!entryAlreadyAppended) {
            appendEntry(ChatEntry(id = nextId++, kind = EntryKind.USER, text = userText))
        }
        history += ChatMessage(role = Role.USER, text = userText)
        if (sessionTitle.isBlank()) sessionTitle = deriveTitle(userText)

        if (provider == null || model.isBlank()) {
            appendEntry(
                ChatEntry(
                    id = nextId++,
                    kind = EntryKind.ERROR,
                    text = "No model selected. Open Settings, add a provider key, and pick a model.",
                ),
            )
            persist()
            drainPendingQueue()
            return
        }

        _isRunning.value = true
        _status.value = "Thinking…"
        _paused.value = false
        _turnFilesChanged.value = 0
        addProgress("✓ analysing project…")

        try {
            var step = 0
            while (step < settings.maxSteps) {
                awaitIfPaused()
                step++
                val calls = streamOneAssistantTurn(provider, model, settings.temperature, settings.maxTokens)
                    ?: break // a provider error already surfaced

                if (calls.isEmpty()) break

                for (call in calls) {
                    executeCall(call)
                    awaitIfPaused()
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
            drainPendingQueue()
        }
    }

    /** Sends the next queued message, if any, once the running turn is over. */
    private fun drainPendingQueue() {
        val next = pendingQueue.removeFirstOrNull() ?: return
        if (_isRunning.value) return
        lastPrompt = next
        turnJob = scope.launch { runTurn(userText = next, entryAlreadyAppended = true) }
    }

    /**
     * Background-mode gate: blocks at step boundaries while paused. Polling keeps
     * this simple and responsive; cancellation still arrives through the job.
     */
    private suspend fun awaitIfPaused() {
        while (_paused.value && _isRunning.value) {
            kotlinx.coroutines.delay(300)
        }
    }

    /** Appends one line to the bounded progress log shown in notifications. */
    private fun addProgress(line: String) {
        _progress.value = (_progress.value + line).takeLast(MAX_PROGRESS_LINES)
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
            addProgress("• ${changes.size} file(s) changed")
            _pendingReview.value = TurnReview(
                checkpointId = checkpoint.id,
                label = checkpoint.label,
                fileCount = changes.size,
                added = changes.sumOf { it.added },
                removed = changes.sumOf { it.removed },
                files = changes.sortedByDescending { it.added + it.removed }
                    .take(REVIEW_BAR_FILES)
                    .map { TurnFileStat(it.path, it.type.name, it.added, it.removed) },
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

        // Coalesce UI patches: streaming tokens can arrive dozens per second, and
        // each patch copies the whole entry list. Patching at most every 50 ms keeps
        // long chats smooth; the final flush below always delivers the full text.
        var lastUiPatch = 0L

        ProviderRegistry.forConfig(provider).stream(
            config = provider,
            model = model,
            systemPrompt = buildSystemPrompt(),
            messages = wireHistory(),
            tools = availableToolSpecs(),
            temperature = temperature,
            maxTokens = maxTokens,
        ).collect { event ->
            when (event) {
                is LlmEvent.TextDelta -> {
                    text.append(event.text)
                    val now = System.nanoTime()
                    if (now - lastUiPatch > STREAM_PATCH_INTERVAL_NANOS) {
                        lastUiPatch = now
                        patchEntry(entryId) { it.copy(text = text.toString()) }
                    }
                }

                is LlmEvent.ReasoningDelta -> {
                    reasoning.append(event.text)
                    val now = System.nanoTime()
                    if (now - lastUiPatch > STREAM_PATCH_INTERVAL_NANOS) {
                        lastUiPatch = now
                        patchEntry(entryId) { it.copy(reasoning = reasoning.toString()) }
                    }
                }

                is LlmEvent.ToolCallReady -> calls += event.call

                is LlmEvent.Failed -> failure = event.message

                is LlmEvent.Completed -> Unit
            }
        }

        // Full flush: whatever the throttle skipped lands here, exactly once.
        patchEntry(entryId) {
            it.copy(text = text.toString(), reasoning = reasoning.toString(), streaming = false)
        }

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

    /**
     * History as sent on the wire. Tool results the model already consumed are
     * compacted — a long build turn otherwise re-ships megabytes of file contents
     * with every step, which is the single biggest cause of slow requests.
     */
    private fun wireHistory(): List<ChatMessage> {
        val toolIndexes = history.withIndex()
            .filter { it.value.role == Role.TOOL }
            .map { it.index }
        if (toolIndexes.size <= RECENT_TOOL_RESULTS) return history.toList()
        val recent = toolIndexes.takeLast(RECENT_TOOL_RESULTS).toHashSet()
        return history.mapIndexed { index, message ->
            if (index !in recent && message.role == Role.TOOL) {
                message.copy(
                    toolResult = message.toolResult?.let { result ->
                        if (result.content.length <= STALE_TOOL_RESULT_CHARS) result
                        else result.copy(content = result.content.take(STALE_TOOL_RESULT_CHARS) + STALE_TOOL_SUFFIX)
                    },
                )
            } else {
                message
            }
        }
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

        // Chat Only mode strips the agent tools; anything else is refused with a
        // pointer back to conversation so the model does not retry it.
        if (settings.chatOnly && tool != UseSkillTool) {
            val message = "ERROR: '${call.name}' is unavailable in Chat Only mode. " +
                "The user disabled project actions for this chat — answer in text, or tell " +
                "them to flip the composer switch back to Build if edits are really needed."
            recordToolEntry(call, "blocked (Chat Only)", ToolStatus.DENIED, message)
            history += ChatMessage(
                role = Role.TOOL,
                toolResult = ToolResult(call.id, call.name, message, isError = true),
            )
            return CallOutcome.DENIED
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
            devServer = devServer,
            history = commandHistory,
            settings = settings,
            github = github,
            project = workspace.activeProject.value,
            onProgress = { progress -> _status.value = "$summary — $progress" },
            onProjectChanged = { path ->
                val newRoot = File(path, SESSION_DIR)
                if (sessionRoot?.absolutePath != newRoot.absolutePath) {
                    // Checkpoint the transcript under the old project's session list,
                    // then repoint BEFORE selectByPath so the bindProject triggered
                    // by the project switch is a no-op and the running turn keeps
                    // its context. The transcript continues as its own session in
                    // the new project instead of overwriting anything there.
                    persist()
                    sessionRoot = newRoot
                    sessionId = generateSessionId()
                    _activeSessionId.value = sessionId
                    readIndex()
                }
                workspace.selectByPath(path)
                settingsStore.update { it.copy(lastProjectPath = path) }
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
            toolResult = ToolResult(
                call.id,
                call.name,
                output.take(if (tool == UseSkillTool) UseSkillTool.MAX_SKILL_CHARS + 2_000 else MAX_TOOL_RESULT_CHARS),
                isError = isError,
            ),
        )
        workspace.notifyChanged()

        // Background notification feed: ✓/✗ per finished call, plus a running
        // count of touched files for the "Modified N files" line.
        val mark = if (isError) "✗" else "✓"
        addProgress("$mark ${summary.take(60)}")
        if (!isError && tool.mutating && WRITE_COUNTING_TOOLS.contains(tool.name)) {
            _turnFilesChanged.value += 1
        }
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

    /**
     * Tool specs on offer right now. Chat Only trims the list to skills; the 15
     * `github_*` tools are withheld while signed out — they would only burn prompt
     * tokens the model can never use, and the system prompt says how to sign in.
     */
    private fun availableToolSpecs(): List<ToolSpec> {
        if (settingsStore.settings.value.chatOnly) {
            return listOf(UseSkillTool.toSpec())
        }
        val signedIn = github.client.value != null
        return ToolRegistry.specs().let { specs ->
            if (signedIn) specs else specs.filterNot { it.name.startsWith("github_") }
        }
    }

    private suspend fun buildSystemPrompt(): String {
        val settings = settingsStore.settings.value
        val project = workspace.activeProject.value

        // Chat Only mode: pure conversation. No project tools, no engineering
        // scaffolding — just persona, active skills, and how to leave the mode.
        if (settings.chatOnly) {
            return buildString {
                appendLine(
                    """
                    You are OpenCode in Chat Only mode, running on an Android phone.
                    The user wants plain conversation in this chat: answer questions,
                    explain ideas, plan work, review snippets they paste — but do NOT
                    reach for project tools. Project file changes are disabled here.
                    If a request genuinely needs you to create or modify a project,
                    say so and tell them to switch the composer back to Build.
                    """.trimIndent(),
                )
                appendPromptExtras(settings)
            }
        }

        return buildString {
            // 1. Operating rules. Fast mode swaps the ~23 KB handbook + device facts
            // for a condensed briefing — the request leaves (and the first token
            // arrives) much sooner, at the cost of less agent guidance.
            if (settings.fastMode) {
                appendLine(FAST_MODE_BRIEFING)
                appendLine()
            } else {
                if (settings.useSystemPrompt) {
                    val base = instructions.text.value.trim()
                    if (base.isNotEmpty()) {
                        appendLine(base.take(InstructionStore.MAX_PROMPT_CHARS))
                        appendLine()
                        appendLine(
                            "The handbook above defines HOW you behave. The DEVICE FACTS below " +
                                "describe what is actually true of THIS execution environment; " +
                                "where the two disagree about concrete capabilities, the device facts win.",
                        )
                        appendLine()
                    }
                }

                // 2. Device / runtime facts (the on-phone reality).
                appendLine(DEVICE_FACTS)
                appendLine()
            }

            // 3. Project state.
            if (project == null) {
                appendLine("No project is open. Use create_project, git_clone or fetch_repo_snapshot to start one.")
            } else {
                appendLine("ACTIVE PROJECT: ${project.name}")
                appendLine("Files: ${project.fileCount}. Git repository: ${project.isGitRepo}.")
                appendLine("Templates available to create_project: ${Templates.ids.joinToString(", ")}.")
            }

            // 4. Approval posture.
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

            appendPromptExtras(settings)

            // 5. GitHub (feature 8). Only advertise what is usable.
            if (github.client.value != null) {
                val login = github.account.value?.login.orEmpty().ifBlank { "(checking…)" }
                appendLine()
                appendLine(
                    "GITHUB — signed in as $login. Available: github_repos, github_repo_info, " +
                        "github_branches, github_commits, github_issues(+get/create), github_comment, " +
                        "github_pulls(+get), github_create_pull, github_actions_status, github_create_repo.",
                )
                appendLine(
                    "When the user references an issue or PR number (\"fix issue #42\"), fetch it with " +
                        "github_get_issue first. Push the feature branch BEFORE calling github_create_pull; " +
                        "reference the originating issue in the PR body as \"Fixes #42\".",
                )
                appendLine(
                    "Cloning/pushing private GitHub repos uses the signed-in token automatically — " +
                        "git_clone/git_push need no extra setup. NEVER ask for or repeat tokens/API keys; " +
                        "they are managed outside this conversation and you have no access to them.",
                )
            } else {
                appendLine()
                appendLine(
                    "GITHUB tools are not offered while the user is signed out. If a task clearly " +
                        "needs issues/PRs/repo creation, say they can sign in from the Hub tab (the " +
                        "tools appear after sign-in). Public repos still clone fine with git_clone.",
                )
            }
        }
    }

    /**
     * Blocks shared by every mode: active skills + the user's own instructions
     * (Custom instructions in Settings).
     */
    private suspend fun StringBuilder.appendPromptExtras(settings: AppSettings) {
        if (settings.customInstructions.isNotBlank()) {
            appendLine()
            appendLine("USER INSTRUCTIONS:")
            appendLine(settings.customInstructions.trim())
        }
        skills.renderPromptBlock(settings.enabledSkills)?.let { block ->
            appendLine()
            appendLine(block)
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

    /** Directory holding `<id>.json` files for the current session space. */
    private fun sessionsDir(): File = File(sessionRoot ?: fallbackSessionsRoot, SESSIONS_SUBDIR)

    /**
     * Saves the open transcript as its session file and refreshes the index.
     * Runs [NonCancellable] so a cancelled turn still saves what it produced.
     * State is captured on the caller's (main) thread — only file IO hops to IO.
     */
    private suspend fun persist() {
        val entries = _entries.value
        val hasContent = entries.isNotEmpty() || history.isNotEmpty()
        val id = sessionId ?: if (!hasContent) return else generateSessionId()
        if (sessionId == null) {
            sessionId = id
            _activeSessionId.value = id
        }
        if (sessionCreatedAt == 0L) sessionCreatedAt = System.currentTimeMillis()
        val root = sessionRoot ?: fallbackSessionsRoot
        persistCaptured(id, sessionTitle, sessionCreatedAt, SessionSnapshot(entries, history.toList()), root, markActive = true)
    }

    /**
     * Writes one captured snapshot to disk without touching live state. Used by
     * [persist] and by [newSession] (which resets state first and must not flip
     * the active marker to the chat it just archived).
     */
    private suspend fun persistCaptured(
        id: String?,
        title: String,
        createdAt: Long,
        snapshot: SessionSnapshot,
        root: File = sessionRoot ?: fallbackSessionsRoot,
        markActive: Boolean = false,
    ) {
        val sid = id ?: run {
            if (snapshot.entries.isEmpty() && snapshot.history.isEmpty()) return
            generateSessionId()
        }
        val stamp = System.currentTimeMillis()
        val created = createdAt.takeIf { it != 0L } ?: stamp
        withContext(NonCancellable + Dispatchers.IO) {
            runCatching {
                val dir = File(root, SESSIONS_SUBDIR)
                dir.mkdirs()
                File(dir, "$sid.json").writeText(
                    json.encodeToString(
                        SessionFile.serializer(),
                        SessionFile(
                            id = sid,
                            title = title,
                            createdAt = created,
                            updatedAt = stamp,
                            entries = snapshot.entries,
                            history = snapshot.history,
                        ),
                    ),
                )
            }
            writeIndex(root, markActive) { current ->
                current.copy(
                    sessions = (
                        current.sessions.filterNot { it.id == sid } + SessionIndexEntry(
                            id = sid,
                            title = title,
                            createdAt = created,
                            updatedAt = stamp,
                            messageCount = snapshot.entries.count {
                                it.kind == EntryKind.USER || it.kind == EntryKind.ASSISTANT
                            },
                            preview = previewOf(snapshot.entries),
                        )
                        ).sortedByDescending { it.updatedAt }.take(MAX_SESSIONS),
                )
            }
        }
    }

    /** Loads one session file into the engine. Returns false when unreadable. */
    private suspend fun restoreSession(id: String): Boolean {
        val loaded = withContext(Dispatchers.IO) {
            val file = File(sessionsDir(), "$id.json")
            if (!file.isFile) return@withContext null
            runCatching { json.decodeFromString(SessionFile.serializer(), file.readText()) }.getOrNull()
        }
        if (loaded == null) return false
        history.clear()
        history += loaded.history
        _entries.value = loaded.entries.map { if (it.streaming) it.copy(streaming = false) else it }
        nextId = (loaded.entries.maxOfOrNull { it.id } ?: 0L) + 1L
        sessionId = loaded.id
        sessionTitle = loaded.title
        sessionCreatedAt = loaded.createdAt
        _activeSessionId.value = loaded.id
        return true
    }

    /** Reads `sessions/index.json` into [sessions]; a missing index is just empty. */
    private suspend fun readIndex(root: File = sessionRoot ?: fallbackSessionsRoot) {
        val index = withContext(Dispatchers.IO) {
            runCatching {
                val file = File(root, INDEX_FILE)
                if (file.isFile) json.decodeFromString(SessionIndex.serializer(), file.readText()) else SessionIndex()
            }.getOrDefault(SessionIndex())
        }
        _sessions.value = index.sessions
            .sortedByDescending { it.updatedAt }
            .map {
                SessionMeta(
                    id = it.id,
                    title = it.title,
                    createdAt = it.createdAt,
                    updatedAt = it.updatedAt,
                    messageCount = it.messageCount,
                    preview = it.preview,
                )
            }
        if (sessionId == null) _activeSessionId.value = index.activeId
    }

    /** Reads, transforms and rewrites the index, then mirrors it into [sessions]. */
    private suspend fun writeIndex(
        root: File = sessionRoot ?: fallbackSessionsRoot,
        markActive: Boolean = false,
        transform: (SessionIndex) -> SessionIndex,
    ) {
        val next = withContext(Dispatchers.IO) {
            runCatching {
                val file = File(root, INDEX_FILE)
                val current = if (file.isFile) {
                    runCatching { json.decodeFromString(SessionIndex.serializer(), file.readText()) }
                        .getOrDefault(SessionIndex())
                } else {
                    SessionIndex()
                }
                val transformed = transform(current).let {
                    if (markActive && sessionId != null) it.copy(activeId = sessionId) else it
                }
                root.mkdirs()
                file.writeText(json.encodeToString(SessionIndex.serializer(), transformed))
                transformed
            }.getOrNull()
        } ?: return
        _sessions.value = next.sessions
            .sortedByDescending { it.updatedAt }
            .map {
                SessionMeta(
                    id = it.id,
                    title = it.title,
                    createdAt = it.createdAt,
                    updatedAt = it.updatedAt,
                    messageCount = it.messageCount,
                    preview = it.preview,
                )
            }
        if (markActive && sessionId != null) _activeSessionId.value = sessionId
    }

    /** Drops one session from the index; the active chat stays whatever it is. */
    private suspend fun removeFromIndex(id: String) {
        writeIndex(markActive = false) { current ->
            current.copy(
                activeId = if (current.activeId == id) null else current.activeId,
                sessions = current.sessions.filterNot { it.id == id },
            )
        }
    }

    /**
     * One-time import of the pre-multi-session `<project>/.opencode/session.json`
     * into the session store, so nobody loses their old chat on upgrade.
     */
    private suspend fun migrateLegacySession(root: File) {
        withContext(Dispatchers.IO) {
            val legacy = File(root, LEGACY_SESSION_FILE)
            if (!legacy.isFile) return@withContext
            runCatching {
                val snapshot = json.decodeFromString(SessionSnapshot.serializer(), legacy.readText())
                if (snapshot.entries.isNotEmpty() || snapshot.history.isNotEmpty()) {
                    val id = generateSessionId()
                    val stamp = legacy.lastModified().takeIf { it > 0 } ?: System.currentTimeMillis()
                    val title = deriveTitle(
                        snapshot.entries.firstOrNull { it.kind == EntryKind.USER }?.text ?: "Imported chat",
                    )
                    val dir = File(root, SESSIONS_SUBDIR)
                    dir.mkdirs()
                    File(dir, "$id.json").writeText(
                        json.encodeToString(
                            SessionFile.serializer(),
                            SessionFile(id, title, stamp, stamp, snapshot.entries, snapshot.history),
                        ),
                    )
                    writeIndex(root, markActive = false) { current ->
                        current.copy(
                            activeId = current.activeId ?: id,
                            sessions = (
                                current.sessions.filterNot { it.id == id } + SessionIndexEntry(
                                    id = id,
                                    title = title,
                                    createdAt = stamp,
                                    updatedAt = stamp,
                                    messageCount = snapshot.entries.count {
                                        it.kind == EntryKind.USER || it.kind == EntryKind.ASSISTANT
                                    },
                                    preview = previewOf(snapshot.entries),
                                )
                                ).sortedByDescending { it.updatedAt },
                        )
                    }
                }
                legacy.delete()
            }
        }
    }

    /** Loads the index's active session, or starts a fresh canvas. */
    private suspend fun restoreActiveOrFresh() {
        val active = _activeSessionId.value
        resetTranscript()
        if (active == null) return
        if (!restoreSession(active)) {
            _activeSessionId.value = null
        }
    }

    private fun resetTranscript() {
        history.clear()
        _entries.value = emptyList()
        _pendingReview.value = null
        turnCheckpoint = null
        turnProject = null
        sessionId = null
        sessionTitle = ""
        sessionCreatedAt = 0L
        _activeSessionId.value = null
        nextId = 1L
    }

    // ---- small helpers ----------------------------------------------------

    private fun generateSessionId(): String =
        "s${System.currentTimeMillis().toString(36)}-${(0..0xFFFF).random().toString(16)}"

    /** Session title from the first user message: first line, whitespace-collapsed. */
    private fun deriveTitle(text: String): String =
        text.lineSequence().firstOrNull()?.trim()
            ?.replace(Regex("\\s+"), " ")
            ?.take(60)
            .orEmpty()
            .ifBlank { "New chat" }

    /** One-line preview for the Sessions list: the latest thing the user said. */
    private fun previewOf(entries: List<ChatEntry>): String =
        entries.lastOrNull { it.kind == EntryKind.USER }?.text
            ?.replace(Regex("\\s+"), " ")
            ?.take(100)
            .orEmpty()

    private companion object {
        const val SESSION_DIR = ".opencode"
        const val SESSIONS_SUBDIR = "sessions"
        const val INDEX_FILE = "index.json"

        /** Pre-multi-session transcript; imported once, then deleted. */
        const val LEGACY_SESSION_FILE = "session.json"

        /** Session catalog cap — keeps index reads O(small) forever. */
        const val MAX_SESSIONS = 200

        /** How often the streaming UI is allowed to re-patch the entry list. */
        const val STREAM_PATCH_INTERVAL_NANOS = 50_000_000L

        /** Tool results older than the last [RECENT_TOOL_RESULTS] calls are cut to this. */
        const val STALE_TOOL_RESULT_CHARS = 900
        const val STALE_TOOL_SUFFIX = "\n…(older tool output truncated to save context)"
        const val RECENT_TOOL_RESULTS = 4

        const val MAX_TOOL_RESULT_CHARS = 30_000
        const val MAX_PROGRESS_LINES = 6
        const val REVIEW_BAR_FILES = 8

        /** Mutating tools whose success means one more file (or path) changed. */
        val WRITE_COUNTING_TOOLS = setOf(
            WriteFileTool.name,
            EditFileTool.name,
            DeletePathTool.name,
            CreateDirectoryTool.name,
        )

        /** Condensed operating briefing sent in Fast mode (default on). */
        const val FAST_MODE_BRIEFING = """
You are OpenCode, a pragmatic coding agent running entirely on an Android phone. Be concise.

DEVICE FACTS (these override any other assumption):
- Commands run in a sandboxed toybox shell: ls/cat/grep/find/wc work; by default
  there is NO Node/npm, Python, JDK/Gradle, cargo or go — ecosystem commands
  fail with 'not found'. Never pretend a build succeeded.
- Mutating file operations and non-read-only commands ask the user first;
  destructive commands and paths outside the project are blocked outright.
  Do not retry a blocked command.
- Web projects must run with ZERO build step: dependencies from CDNs (esm.sh,
  unpkg, jsdelivr) via import map, React JSX compiled in-page with
  @babel/standalone, Tailwind via its CDN build. Call `preview` once the site
  is worth looking at (the Preview tab auto-reloads after every write).
- Paths are relative to the active project root; `..` is rejected. Git is
  JGit over HTTPS only. `build_project` reports structured diagnostics for the
  detected project type.
- dev_server_start runs a real dev server only when a Node runtime pack is
  present; otherwise fall back to `preview`. Check dev_server_status first.
- Small screen: keep replies short, never dump whole files.

WORKFLOW:
- Inspect before editing (list_files, read_file, search_code); make minimal,
  targeted edits; verify what you changed; say clearly what to check next.
- When the user asks for any app/site/tool/game with no suitable project open,
  call `create_project` with the closest template, keep building until it
  actually works, then call `preview`. Scaffold first, ask later.
- Never ask the user for API keys or tokens; you cannot and must not use them.
"""

        /** Full device/runtime facts block (non-fast mode). */
        const val DEVICE_FACTS = """
DEVICE EXECUTION FACTS — read this carefully, it is unusual:
- You run entirely on an Android phone. Commands go through `run_command`
  on the toybox shell inside a sandbox: working directory pinned to the
  project, fixed PATH, hard timeout, output caps. Basic inspection works
  (ls, cat, grep, find, wc). By default there is NO Node.js/npm, NO
  Python, NO JDK/Gradle, NO cargo/go — ecosystem installs and builds fail
  with 'not found'. Never pretend a build succeeded. (An optional Node
  runtime pack enables real dev servers — see dev_server_start below.)
- Every command is classified before it runs: read-only commands run
  immediately; anything that writes or installs asks the user;
  destructive commands and access outside the project are blocked.
  If a command was blocked, do not retry variations of it.
- `build_project` detects the project type and runs detect/install/build/
  test/run/clean with structured file:line diagnostics. For static web
  projects there is nothing to compile — use `preview` instead.
- For Node web apps (Vite/Next.js/React/Node), `dev_server_start` runs the
  real dev server IF a Node runtime is present: detects, installs, starts,
  sniffs the port, points Preview at it. On no_runtime fall back to
  `preview` for static, zero-build sites. Check dev_server_status;
  stop with dev_server_stop.
- Web projects must work by opening an HTML file directly. Get dependencies
  from a CDN (esm.sh, unpkg, jsdelivr) using an import map, or use a global
  script build. For React, JSX is compiled in-page by @babel/standalone;
  Tailwind via its CDN build (no PostCSS).
- Files live in a private sandbox for the active project. Paths are relative
  to the project root; `..` is rejected.
- Git goes through JGit over HTTPS only; SSH remotes fail.
- `preview` serves the project on 127.0.0.1 and the Preview tab auto-reloads
  after every write. Call it once the site is worth looking at.
- Screen is small: keep replies short, never dump whole files, never paste
  a file you just wrote.

PROJECT CREATION:
- You CAN build complete projects from scratch — this is expected here.
  When the user asks for any app/site/tool/game ("make me a to-do app",
  "build a portfolio site") with no suitable project open, call
  `create_project` with the closest template and flesh it out immediately.
  Scaffold first, ask questions later — wrong guesses are one edit away.
  After creating, keep building files until the thing actually works,
  then call `preview`.
"""
    }
}
