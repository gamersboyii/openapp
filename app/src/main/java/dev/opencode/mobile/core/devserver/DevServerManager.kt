package dev.opencode.mobile.core.devserver

import dev.opencode.mobile.core.build.BuildSystem
import dev.opencode.mobile.core.build.ProjectKind
import dev.opencode.mobile.core.exec.RunState
import dev.opencode.mobile.core.exec.TerminalService
import dev.opencode.mobile.core.fs.Project
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Hosts one long-running Node dev server for the active project.
 *
 * This is the orchestration half of "real web dev support": detect the project,
 * install dependencies, launch the dev command, sniff the port it prints, expose
 * a URL the preview WebView can point at, and tear it down cleanly. It runs
 * everything through the one sandboxed [TerminalService] path — no second
 * execution mechanism — using its no-timeout + live-line-callback modes.
 *
 * It does NOT ship a Node runtime. Whether a dev server can actually start is
 * delegated to [NodeRuntime]; on a stock phone that reports unavailable and this
 * manager surfaces an honest [Status.NO_RUNTIME] rather than pretending. Static
 * (zero-build) projects never come through here — they keep using the loopback
 * preview server.
 */
class DevServerManager(
    private val terminal: TerminalService,
    private val build: BuildSystem,
    private val runtime: NodeRuntime,
    private val scope: CoroutineScope,
) {

    enum class Status {
        /** No server running; nothing attempted yet, or stopped by the user. */
        STOPPED,

        /** Project type has no on-device dev server (e.g. static, gradle, python). */
        UNSUPPORTED,

        /** Runnable project, but no Node runtime is installed on this device. */
        NO_RUNTIME,

        INSTALLING,
        STARTING,
        RUNNING,

        /** The process exited or install failed without a user stop. */
        CRASHED,
    }

    data class LogLine(val text: String, val isError: Boolean)

    data class State(
        val status: Status = Status.STOPPED,
        val kind: ProjectKind = ProjectKind.UNKNOWN,
        val port: Int? = null,
        val url: String? = null,
        val error: String? = null,
        val note: String = "",
        val lines: List<LogLine> = emptyList(),
    ) {
        val isBusy: Boolean get() = status == Status.INSTALLING || status == Status.STARTING
        val isLive: Boolean get() = status == Status.RUNNING
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** Serializes start vs. start; stop is a fast, lock-free interrupt. */
    private val lifecycle = Mutex()

    /** Guards the read-modify-write in [consume] against the two drain threads. */
    private val logLock = Any()

    @Volatile
    private var runId: String? = null

    /** True when the current [state] kind has an on-device dev server. */
    val isRunnable: Boolean get() = _state.value.kind in RUNNABLE

    /**
     * Points the manager at [project]. Stops any server tied to the previous
     * project and re-detects the kind, but does not start anything.
     */
    fun bind(project: Project?) {
        stop()
        _state.value = State(kind = project?.let { build.detect(it.dir) } ?: ProjectKind.UNKNOWN)
    }

    /**
     * Detects, optionally installs dependencies, then launches the dev server and
     * begins sniffing its port. Suspends only until the process is spawned — the
     * server keeps running until [stop]. Concurrent calls are collapsed: a second
     * start while one is live is a no-op.
     */
    suspend fun start(project: Project, install: Boolean = true) {
        lifecycle.withLock {
            if (runId != null) return
            val kind = build.detect(project.dir)
            if (kind !in RUNNABLE) {
                _state.value = State(
                    status = Status.UNSUPPORTED,
                    kind = kind,
                    note = "${kind.display} has no on-device dev server. " +
                        "Use the static preview or build_project.",
                )
                return
            }

            val node = runtime.probe(project.dir, project.name)
            if (!node.available) {
                _state.value = State(status = Status.NO_RUNTIME, kind = kind, error = node.note)
                return
            }

            if (install) {
                _state.value = State(status = Status.INSTALLING, kind = kind, note = "$ $INSTALL_CMD")
                val installRun = runCatching {
                    terminal.await(
                        terminal.start(INSTALL_CMD, project.dir, project.name, ORIGIN, INSTALL_TIMEOUT, node.env),
                    )
                }.getOrNull()
                if (installRun == null || installRun.state != RunState.FINISHED || installRun.exitCode != 0) {
                    _state.value = State(
                        status = Status.CRASHED,
                        kind = kind,
                        error = "npm install failed" +
                            (installRun?.exitCode?.let { " (exit $it)" } ?: "") + ". " +
                            installRun?.stderr?.lineSequence()
                                ?.firstOrNull { it.isNotBlank() }.orEmpty().take(200),
                    )
                    return
                }
            }

            val command = devCommand(kind)
            _state.value = State(status = Status.STARTING, kind = kind, note = "$ $command")
            val id = runCatching {
                terminal.start(
                    command = command,
                    projectDir = project.dir,
                    projectName = project.name,
                    origin = ORIGIN,
                    timeoutSeconds = 0, // long-running: no timeout
                    env = node.env,
                    onLine = { line, isError -> consume(line, isError) },
                )
            }.getOrElse { error ->
                _state.value = State(
                    status = Status.CRASHED,
                    kind = kind,
                    error = error.message ?: "Could not start the dev server.",
                )
                return
            }
            runId = id
            watch(id)
        }
    }

    fun stop() {
        val id = runId ?: return
        runId = null
        terminal.stop(id)
        _state.value = _state.value.copy(status = Status.STOPPED, port = null, url = null)
    }

    suspend fun restart(project: Project) {
        stop()
        // Dependencies are already installed after a successful first start.
        start(project, install = false)
    }

    /**
     * Suspends until the server leaves a transient state (INSTALLING/STARTING) —
     * i.e. it is RUNNING, CRASHED, or was rejected (UNSUPPORTED/NO_RUNTIME) — or
     * [timeoutMs] elapses, whichever comes first. Lets a caller report a settled
     * result instead of the momentary STARTING it would otherwise see right after
     * [start] returns.
     */
    suspend fun awaitSettled(timeoutMs: Long = 60_000): State =
        withTimeoutOrNull(timeoutMs) {
            state.first { it.status != Status.INSTALLING && it.status != Status.STARTING }
        } ?: state.value

    /** Feeds one captured output line into port sniffing + the bounded log. */
    private fun consume(line: String, isError: Boolean) {
        synchronized(logLock) {
            val current = _state.value
            val lines = (current.lines + LogLine(line, isError)).takeLast(MAX_LINES)
            if (current.port == null) {
                val port = sniffPort(line)
                if (port != null) {
                    _state.value = current.copy(
                        lines = lines,
                        port = port,
                        url = "http://localhost:$port/",
                        status = Status.RUNNING,
                    )
                    return
                }
            }
            _state.value = current.copy(lines = lines)
        }
    }

    /** Watches for the process ending and reflects it (crash vs. clean stop). */
    private fun watch(id: String) {
        scope.launch {
            val run = runCatching { terminal.await(id) }.getOrNull()
            // A newer start/stop superseded this run: leave its state alone.
            if (runId != id) return@launch
            runId = null
            val ended = _state.value
            _state.value = when {
                run == null -> ended.copy(status = Status.CRASHED, error = "Server process vanished.")
                run.state == RunState.KILLED -> ended.copy(status = Status.STOPPED, port = null, url = null)
                else -> ended.copy(
                    status = Status.CRASHED,
                    port = null,
                    url = null,
                    error = "Dev server exited (code ${run.exitCode ?: "-"}).",
                )
            }
        }
    }

    private fun devCommand(kind: ProjectKind): String = when (kind) {
        ProjectKind.NODE_REACT -> "npm start"
        else -> "npm run dev"
    }

    companion object {
        private val RUNNABLE = setOf(
            ProjectKind.NODE_VITE,
            ProjectKind.NODE_REACT,
            ProjectKind.NODE_NEXT,
            ProjectKind.NODE_GENERIC,
        )

        /** Whether [kind] has an on-device dev server (reactive callers pass state.kind). */
        fun isRunnableKind(kind: ProjectKind): Boolean = kind in RUNNABLE
        private const val ORIGIN = "devserver"
        private const val INSTALL_CMD = "npm install"

        /** npm installs can be slow on mobile data; give them room but not forever. */
        private const val INSTALL_TIMEOUT = 900
        private const val MAX_LINES = 300

        // Ordered specific-first. A dev server almost always prints one of these.
        private val PORT_PATTERNS = listOf(
            Regex("""https?://(?:localhost|127\.0\.0\.1|0\.0\.0\.0):(\d{2,5})""", RegexOption.IGNORE_CASE),
            Regex("""(?:listening|running|started|ready)\b.{0,40}?:(\d{2,5})\b""", RegexOption.IGNORE_CASE),
            Regex("""\bport\s+(\d{2,5})\b""", RegexOption.IGNORE_CASE),
        )

        /** Extracts a listening port from one server-banner line, or null. */
        fun sniffPort(line: String): Int? {
            for (pattern in PORT_PATTERNS) {
                val match = pattern.find(line) ?: continue
                val port = match.groupValues[1].toIntOrNull() ?: continue
                if (port in 1..65_535) return port
            }
            return null
        }
    }
}
