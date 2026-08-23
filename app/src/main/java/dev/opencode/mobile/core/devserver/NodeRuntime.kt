package dev.opencode.mobile.core.devserver

import android.content.Context
import dev.opencode.mobile.core.exec.RunState
import dev.opencode.mobile.core.exec.TerminalService
import java.io.File

/**
 * Whether a Node.js runtime can actually execute here, plus the environment
 * needed to reach it.
 */
data class NodeStatus(
    val available: Boolean,
    val version: String? = null,
    /** Extra env layered onto the sandbox — a PATH prefix pointing at the runtime. */
    val env: Map<String, String> = emptyMap(),
    val note: String = "",
)

/**
 * Resolves whether a Node.js runtime is usable on this device.
 *
 * Stock Android ships no Node, and the sandbox PATH is toybox-only, so by default
 * this reports unavailable — honestly. A runtime can appear two ways:
 *
 *  1. An extracted runtime under `filesDir/runtime/bin` (a bundled nodejs-mobile
 *     binary unpacked at first run, or a user-provided build). Preferred; its
 *     directory is prepended to PATH.
 *  2. `node` already resolvable on the system PATH (uncommon).
 *
 * Detection is empirical — it runs `node --version` rather than trusting a file to
 * exist, because an extracted binary can still be unrunnable (wrong ABI, or an
 * SELinux denial on exec from app-writable storage). The result is cached; the
 * runtime does not appear or vanish within a session.
 */
class NodeRuntime(context: Context, private val terminal: TerminalService) {

    private val runtimeBin = File(context.filesDir, "runtime/bin")

    @Volatile
    private var cached: NodeStatus? = null

    /** Cached status if already probed, else null. */
    fun peek(): NodeStatus? = cached

    /** The env a caller should layer onto the sandbox to reach the runtime. */
    fun pathEnv(): Map<String, String> =
        if (runtimeBin.isDirectory) {
            mapOf("PATH" to "${runtimeBin.absolutePath}:${TerminalService.ANDROID_PATH}")
        } else {
            emptyMap()
        }

    suspend fun probe(projectDir: File, projectName: String, force: Boolean = false): NodeStatus {
        cached?.let { if (!force) return it }
        val env = pathEnv()
        val id = runCatching {
            terminal.start("node --version", projectDir, projectName, ORIGIN, PROBE_TIMEOUT_SECONDS, env)
        }.getOrNull() ?: return unavailable().also { cached = it }

        val run = runCatching { terminal.await(id) }.getOrNull()
        val version = run?.stdout?.lineSequence()?.map { it.trim() }?.firstOrNull { it.startsWith("v") }
        val status = if (run?.state == RunState.FINISHED && run.exitCode == 0 && version != null) {
            NodeStatus(available = true, version = version, env = env, note = "Node $version")
        } else {
            unavailable()
        }
        cached = status
        return status
    }

    private fun unavailable() = NodeStatus(
        available = false,
        note = "No Node.js runtime on this device. Install the runtime pack to run npm dev " +
            "servers; static (zero-build) projects still preview without it.",
    )

    private companion object {
        const val ORIGIN = "devserver"
        const val PROBE_TIMEOUT_SECONDS = 20
    }
}
