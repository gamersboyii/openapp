package dev.opencode.mobile.core.exec

import android.content.Context
import java.io.BufferedReader
import java.io.File
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

enum class RunState { RUNNING, FINISHED, KILLED, TIMED_OUT, FAILED_TO_START }

/** One line in the persisted command history. */
@Serializable
data class HistoryEntry(
    val command: String,
    val projectName: String,
    /** "user" or "agent" — who asked for it. */
    val origin: String,
    val exitCode: Int?,
    val durationMs: Long,
    val timestamp: Long,
)

/**
 * Command history survives restarts as a small JSON file in app storage. Capped
 * so a chatty session cannot grow the file without bound.
 */
class CommandHistoryStore(context: Context) {

    private val json = Json { ignoreUnknownKeys = true }
    private val file = File(context.filesDir, "terminal_history.json")

    private val _entries = MutableStateFlow<List<HistoryEntry>>(emptyList())
    val entries: StateFlow<List<HistoryEntry>> = _entries.asStateFlow()

    fun load() {
        if (!file.isFile) return
        runCatching {
            _entries.value =
                json.decodeFromString(ListSerializer(HistoryEntry.serializer()), file.readText())
        }
    }

    fun record(entry: HistoryEntry) {
        val next = (listOf(entry) + _entries.value).take(MAX_ENTRIES)
        _entries.value = next
        runCatching {
            file.writeText(json.encodeToString(ListSerializer(HistoryEntry.serializer()), next))
        }
    }

    fun clear() {
        _entries.value = emptyList()
        runCatching { file.delete() }
    }

    private companion object {
        const val MAX_ENTRIES = 100
    }
}

/**
 * A command that ran or is running. Output is captured into capped strings so a
 * runaway process cannot exhaust memory through the terminal.
 */
data class CommandRun(
    val id: String,
    val command: String,
    val projectName: String,
    /** "user" or "agent". */
    val origin: String,
    val startedAtMillis: Long,
    val state: RunState,
    val exitCode: Int? = null,
    val durationMs: Long = 0,
    val stdout: String = "",
    val stderr: String = "",
    val truncatedStdout: Boolean = false,
    val truncatedStderr: Boolean = false,
) {
    val isRunning: Boolean get() = state == RunState.RUNNING

    /** Compact `$ cmd` / exit / duration summary used by tools and history. */
    fun summarize(): String = buildString {
        appendLine("$ $command")
        if (stdout.isNotBlank()) {
            appendLine()
            appendLine("[stdout]")
            append(stdout.trimEnd('\n'))
        }
        if (stderr.isNotBlank()) {
            appendLine()
            appendLine()
            appendLine("[stderr]")
            append(stderr.trimEnd('\n'))
        }
        appendLine()
        appendLine()
        append("Exit code: ${exitCode ?: "-"}")
        append(" · Duration: %.1fs".format(durationMs / 1000.0))
        when (state) {
            RunState.TIMED_OUT -> append(" · TIMED OUT")
            RunState.KILLED -> append(" · stopped by user")
            RunState.FAILED_TO_START -> append(" · could not start")
            else -> Unit
        }
        if (truncatedStdout || truncatedStderr) {
            appendLine()
            append("(output truncated)")
        }
    }
}

/**
 * Sandboxed command execution.
 *
 * Every command runs through `/system/bin/sh -c` with:
 *  - working directory pinned to the active project directory;
 *  - a minimal, fixed environment (PATH resolves against the system toybox
 *    binaries only; nothing from the app leaks in);
 *  - output capture capped per stream;
 *  - a hard timeout that terminates the process, forcibly after a grace period;
 *  - explicit cancellation via [stop] / [stopAll];
 *  - at most [MAX_CONCURRENT] processes at once.
 *
 * The ultimate boundary is Android itself: children inherit this app's UID and
 * cannot touch other apps' data or the system. On top of that, model-suggested
 * commands pass [CommandPolicy] before they reach here.
 *
 * Known limitation: killing a compound `sh -c` command may orphan grandchildren;
 * Android reaps them once their pipes close.
 */
class TerminalService(
    context: Context,
    private val history: CommandHistoryStore,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val tmpDir: File = File(context.cacheDir, "terminal").apply { mkdirs() }

    private val nextRunId = AtomicLong(1)
    private val processes = ConcurrentHashMap<String, Process>()
    private val stopped = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    private val completions = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    private val runsLock = Any()

    private val _runs = MutableStateFlow<List<CommandRun>>(emptyList())
    val runs: StateFlow<List<CommandRun>> = _runs.asStateFlow()

    val activeRuns: List<CommandRun> get() = _runs.value.filter { it.isRunning }

    /**
     * Starts [command] inside [projectDir]. Throws [IllegalStateException] when
     * too many processes are already running; returns the run id otherwise.
     */
    fun start(
        command: String,
        projectDir: File,
        projectName: String,
        origin: String,
        timeoutSeconds: Int,
    ): String {
        check(activeRuns.size < MAX_CONCURRENT) {
            "Too many processes are already running ($MAX_CONCURRENT max). Stop one first."
        }
        val id = nextRunId.getAndIncrement().toString()
        upsertRun(
            CommandRun(
                id = id,
                command = command,
                projectName = projectName,
                origin = origin,
                startedAtMillis = System.currentTimeMillis(),
                state = RunState.RUNNING,
            ),
        )

        scope.launch { execute(id, projectDir, timeoutSeconds) }
        return id
    }

    /** Suspends until the run leaves RUNNING state, then returns its final form. */
    suspend fun await(id: String): CommandRun {
        while (true) {
            val done = _runs.value.firstOrNull { it.id == id }?.takeIf { !it.isRunning }
            if (done != null) return done
            // An unknown id means the run already scrolled out of the capped
            // history list; fail loudly instead of parking forever.
            check(_runs.value.any { it.id == id }) { "Unknown or expired run id '$id'" }
            completions.getOrPut(id) { CompletableDeferred() }.await()
        }
    }

    /** Politely asks a running process to terminate (SIGTERM). */
    fun stop(id: String): Boolean {
        val process = processes[id] ?: return false
        stopped.add(id)
        return runCatching { process.destroy() }.isSuccess
    }

    fun stopAll() {
        processes.keys.toList().forEach { stop(it) }
    }

    private suspend fun execute(id: String, projectDir: File, timeoutSeconds: Int) {
        var patch = _runs.value.first { it.id == id }

        suspend fun update(transform: (CommandRun) -> CommandRun) {
            patch = transform(patch)
            upsertRun(patch)
        }

        val builder = ProcessBuilder("sh", "-c", patch.command).directory(projectDir)
        // A minimal, predictable environment: nothing from the app leaks in,
        // and PATH resolves against the system toybox binaries only.
        builder.environment().apply {
            put("PATH", ANDROID_PATH)
            put("HOME", projectDir.absolutePath)
            put("TMPDIR", tmpDir.absolutePath)
            put("LANG", "C.UTF-8")
        }

        val process = try {
            builder.start()
        } catch (error: Exception) {
            update {
                it.copy(
                    state = RunState.FAILED_TO_START,
                    stderr = error.message ?: error.toString(),
                    durationMs = System.currentTimeMillis() - it.startedAtMillis,
                )
            }
            finish(patch)
            return
        }

        processes[id] = process

        val outBuffer = StringBuilder()
        val errBuffer = StringBuilder()
        val outTruncated = java.util.concurrent.atomic.AtomicBoolean(false)
        val errTruncated = java.util.concurrent.atomic.AtomicBoolean(false)

        // Readers only fill buffers/flags; the worker below is the single
        // writer to the CommandRun, so no state races on it.
        val outJob = scope.launch {
            if (drain(process.inputStream.bufferedReader(), outBuffer)) outTruncated.set(true)
        }
        val errJob = scope.launch {
            if (drain(process.errorStream.bufferedReader(), errBuffer)) errTruncated.set(true)
        }

        val exited = withTimeoutOrNull(timeoutSeconds * 1000L) { process.waitFor() }
        val timedOut = exited == null
        if (timedOut) {
            runCatching { process.destroy() }
            delay(FORCE_KILL_GRACE_MS)
            runCatching { if (process.isAlive) process.destroyForcibly() }
        }

        outJob.join()
        errJob.join()
        processes.remove(id)

        val wasStopped = stopped.remove(id)
        val finalState = when {
            timedOut -> RunState.TIMED_OUT
            wasStopped -> RunState.KILLED
            else -> RunState.FINISHED
        }

        update {
            it.copy(
                state = finalState,
                exitCode = exited ?: -1,
                durationMs = System.currentTimeMillis() - it.startedAtMillis,
                stdout = outBuffer.toString(),
                stderr = errBuffer.toString(),
                truncatedStdout = outTruncated.get(),
                truncatedStderr = errTruncated.get(),
            )
        }

        finish(patch)
    }

    /** Single writer for the run list: insert new, or replace by id. */
    private fun upsertRun(run: CommandRun) {
        synchronized(runsLock) {
            _runs.value = if (_runs.value.any { it.id == run.id }) {
                _runs.value.map { if (it.id == run.id) run else it }
            } else {
                listOf(run) + _runs.value.take(MAX_HISTORY - 1)
            }
        }
    }

    /**
     * Reads lines until EOF, appending into [buffer] up to the capture cap.
     * Reading continues past the cap (discarding lines) so the child never
     * blocks on a full pipe and the timeout stays meaningful. Returns whether
     * anything was discarded.
     */
    private fun drain(reader: BufferedReader, buffer: StringBuilder): Boolean {
        var truncated = false
        try {
            while (true) {
                val line = reader.readLine() ?: break
                synchronized(buffer) {
                    if (buffer.length + line.length + 1 > MAX_CAPTURE_CHARS) {
                        truncated = true
                    } else {
                        buffer.append(line).append('\n')
                    }
                }
            }
        } catch (_: Exception) {
            // Stream torn down mid-read by destroy(); keep whatever arrived.
        }
        runCatching { reader.close() }
        return truncated
    }

    /** Records history and wakes anyone parked in [await]. */
    private fun finish(final: CommandRun) {
        history.record(
            HistoryEntry(
                command = final.command,
                projectName = final.projectName,
                origin = final.origin,
                exitCode = final.exitCode,
                durationMs = final.durationMs,
                timestamp = final.startedAtMillis,
            ),
        )
        completions.remove(final.id)?.complete(Unit)
    }

    companion object {
        const val MAX_CONCURRENT = 3
        const val MAX_HISTORY = 20

        /** Per-stream capture cap, in characters. */
        const val MAX_CAPTURE_CHARS = 200_000
        const val FORCE_KILL_GRACE_MS = 2_000L
        const val ANDROID_PATH =
            "/system/bin:/system/xbin:/odm/bin:/vendor/bin:/apex/com.android.runtime/bin:/apex/com.android.art/bin"
    }
}
