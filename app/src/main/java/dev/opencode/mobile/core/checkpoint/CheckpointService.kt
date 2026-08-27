package dev.opencode.mobile.core.checkpoint

import dev.opencode.mobile.core.fs.Project
import dev.opencode.mobile.core.util.TextDiff
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@Serializable
data class CheckpointFile(val path: String, val sha: String, val size: Long)

@Serializable
data class Checkpoint(
    val id: Long,
    val label: String,
    val createdAt: Long,
    val reason: String = "",
    /** Correlates a checkpoint with the agent turn that triggered it; 0 if manual. */
    val turn: Long = 0,
    val fileCount: Int = 0,
    val totalBytes: Long = 0,
)

enum class ChangeType { ADDED, MODIFIED, DELETED }

data class FileChange(val path: String, val type: ChangeType, val added: Int, val removed: Int)

@Serializable
private data class CheckpointIndex(
    val nextId: Long = 1,
    val items: List<Checkpoint> = emptyList(),
)

/**
 * Project-scoped snapshots that survive app restarts and do not depend on Git —
 * a project may not be a repository at all.
 *
 * Storage is content-addressed. Each snapshot writes a manifest of
 * `path -> sha256`; file bytes go into a shared `blobs/` directory keyed by hash,
 * so unchanged files across dozens of checkpoints cost one copy, not dozens.
 * Everything lives under `<project>/.opencode/checkpoints/`, which
 * [dev.opencode.mobile.core.fs.WorkspaceManager] already hides from listing,
 * search and — crucially — from the snapshot walk itself.
 */
class CheckpointService {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }

    private var boundDir: File? = null
    private val _checkpoints = MutableStateFlow<List<Checkpoint>>(emptyList())
    val checkpoints: StateFlow<List<Checkpoint>> = _checkpoints.asStateFlow()

    /** Loads the checkpoint list for [project] into [checkpoints] for the UI. */
    suspend fun bind(project: Project?) = withContext(Dispatchers.IO) {
        if (project == null) {
            boundDir = null
            _checkpoints.value = emptyList()
            return@withContext
        }
        boundDir = project.dir
        _checkpoints.value = readIndex(project.dir).items
    }

    /**
     * Snapshots the current working tree. Returns null if the project is too large
     * to checkpoint (better to say so than to silently skip files on restore).
     */
    suspend fun capture(
        project: Project,
        label: String = "",
        reason: String = "",
        turn: Long = 0,
        retain: Int = 30,
    ): Checkpoint? = withContext(Dispatchers.IO) {
        val root = project.dir
        baseDir(root).mkdirs()
        val blobs = blobsDir(root).apply { mkdirs() }

        val files = collect(root)
        if (files.size > MAX_FILES) return@withContext null

        var total = 0L
        val entries = ArrayList<CheckpointFile>(files.size)
        for (file in files) {
            val size = file.length()
            total += size
            if (total > MAX_TOTAL_BYTES) return@withContext null
            val sha = sha256(file)
            val blob = File(blobs, sha)
            if (!blob.exists()) runCatching { file.copyTo(blob, overwrite = false) }
            entries += CheckpointFile(relativize(root, file), sha, size)
        }

        var index = readIndex(root)
        val id = index.nextId
        writeManifest(root, id, entries)
        val checkpoint = Checkpoint(
            id = id,
            label = label.ifBlank { "Checkpoint $id" },
            createdAt = System.currentTimeMillis(),
            reason = reason,
            turn = turn,
            fileCount = entries.size,
            totalBytes = total,
        )
        index = index.copy(nextId = id + 1, items = listOf(checkpoint) + index.items)
        index = prune(root, index, retain)
        writeIndex(root, index)
        publish(root, index)
        checkpoint
    }

    /** Rewrites the tree to match checkpoint [id]. Returns files written, or -1 if the manifest is gone. */
    suspend fun restore(project: Project, id: Long): Int = withContext(Dispatchers.IO) {
        val root = project.dir
        val entries = readManifest(root, id) ?: return@withContext -1
        val wanted = entries.associateBy { it.path }
        val blobs = blobsDir(root)

        // Remove files the checkpoint did not contain (e.g. ones the agent created).
        collect(root).forEach { file ->
            if (relativize(root, file) !in wanted) file.delete()
        }

        var count = 0
        for (entry in entries) {
            val target = File(root, entry.path)
            val blob = File(blobs, entry.sha)
            if (!blob.exists()) continue
            if (target.isFile && target.length() == entry.size && sha256(target) == entry.sha) {
                count++
                continue
            }
            target.parentFile?.mkdirs()
            runCatching { blob.copyTo(target, overwrite = true) }.onSuccess { count++ }
        }
        pruneEmptyDirs(root)
        count
    }

    /** Reverts a single [path] to checkpoint [id]. A file added after the checkpoint is deleted. */
    suspend fun restoreFile(project: Project, id: Long, path: String): Boolean = withContext(Dispatchers.IO) {
        val root = project.dir
        val entry = readManifest(root, id)?.firstOrNull { it.path == path }
        val target = File(root, path)
        if (entry == null) {
            return@withContext if (target.exists()) target.delete() else true
        }
        val blob = File(blobsDir(root), entry.sha)
        if (!blob.exists()) return@withContext false
        target.parentFile?.mkdirs()
        runCatching { blob.copyTo(target, overwrite = true) }.isSuccess
    }

    /**
     * Feature 10 — hunk-level accept/reject. Re-applies the listed hunk indices
     * from the pre-turn baseline into the live file: a rejected hunk restores
     * its old lines while everything else keeps the agent's version. Hunks are
     * those returned by [TextDiff.hunks] for (baseline text, live text), in order.
     */
    suspend fun revertHunks(
        project: Project,
        id: Long,
        path: String,
        rejectedIndices: List<Int>,
    ): Boolean = withContext(Dispatchers.IO) {
        if (rejectedIndices.isEmpty()) return@withContext false
        val root = project.dir
        val entry = readManifest(root, id)?.firstOrNull { it.path == path } ?: return@withContext false
        val oldText = blobText(root, entry.sha) ?: return@withContext false
        val newText = readTextOrNull(File(root, path)) ?: return@withContext false

        val hunks = TextDiff.hunks(oldText, newText)
        if (hunks.isEmpty() || rejectedIndices.any { it < 0 || it >= hunks.size }) {
            return@withContext false
        }

        val rejectSet = rejectedIndices.toSortedSet()
        val oldLines = oldText.split('\n')
        val newLines = newText.split('\n')
        val out = ArrayList<String>(newLines.size + oldLines.size)

        var cursor = 0 // 0-based index into newLines of what has been emitted so far
        hunks.forEachIndexed { index, hunk ->
            // Line numbers in Row are 1-based; find this hunk's window on each side.
            val newStart = ((hunk.rows.firstOrNull { it.newLine > 0 }?.newLine ?: (cursor + 1)) - 1)
                .coerceIn(0, newLines.size)
            val oldStart = (hunk.rows.firstOrNull { it.oldLine > 0 }?.oldLine ?: (newStart + 1))
                .coerceIn(0, oldLines.size)
            val oldCount = hunk.rows.count { it.kind != TextDiff.Kind.ADD }
            val newCount = hunk.rows.count { it.kind != TextDiff.Kind.REMOVE }
            val newFrom = cursor.coerceIn(0, newLines.size)
            val newTo = (newStart + newCount).coerceIn(newFrom, newLines.size)

            if (index in rejectSet) {
                // Keep live lines before the hunk, then splice the baseline segment.
                out.addAll(newLines.subList(newFrom, newStart.coerceIn(newFrom, newTo)))
                out.addAll(
                    oldLines.subList(oldStart, (oldStart + oldCount).coerceIn(oldStart, oldLines.size)),
                )
            } else {
                out.addAll(newLines.subList(newFrom, newTo))
            }
            cursor = newTo
        }
        if (cursor < newLines.size) out.addAll(newLines.subList(cursor, newLines.size))

        runCatching {
            File(root, path).parentFile?.mkdirs()
            File(root, path).writeText(out.joinToString("\n"))
        }.isSuccess
    }

    suspend fun delete(project: Project, id: Long) = withContext(Dispatchers.IO) {
        val root = project.dir
        var index = readIndex(root)
        if (index.items.none { it.id == id }) return@withContext
        index = index.copy(items = index.items.filterNot { it.id == id })
        manifestFile(root, id).delete()
        gcBlobs(root, index)
        writeIndex(root, index)
        publish(root, index)
    }

    /** Deletes every checkpoint for the project; files on disk are untouched. */
    suspend fun deleteAll(project: Project) = withContext(Dispatchers.IO) {
        val root = project.dir
        val index = readIndex(root)
        if (index.items.isEmpty()) return@withContext
        index.items.forEach { manifestFile(root, it.id).delete() }
        blobsDir(root).deleteRecursively()
        baseDir(root).mkdirs()
        blobsDir(root).mkdirs()
        val empty = CheckpointIndex(nextId = index.nextId)
        writeIndex(root, empty)
        publish(root, empty)
    }

    /** Feature 11: renames a checkpoint without touching its manifest. */
    suspend fun rename(project: Project, id: Long, label: String): Boolean =
        withContext(Dispatchers.IO) {
            val root = project.dir
            val clean = label.trim().take(80)
            if (clean.isEmpty()) return@withContext false
            var index = readIndex(root)
            val target = index.items.firstOrNull { it.id == id } ?: return@withContext false
            index = index.copy(
                items = index.items.map { if (it.id == id) target.copy(label = clean) else it },
            )
            writeIndex(root, index)
            publish(root, index)
            true
        }

    /** Changes between checkpoint [id] and the live working tree. */
    suspend fun diff(project: Project, id: Long): List<FileChange> = withContext(Dispatchers.IO) {
        val root = project.dir
        val old = readManifest(root, id)?.associateBy { it.path } ?: return@withContext emptyList()
        val current = HashMap<String, String>()
        collect(root).forEach { current[relativize(root, it)] = sha256(it) }
        buildChanges(
            oldPaths = old.keys,
            newPaths = current.keys,
            oldSha = { old.getValue(it).sha },
            newSha = { current.getValue(it) },
            oldText = { old[it]?.let { file -> blobText(root, file.sha) } },
            newText = { readTextOrNull(File(root, it)) },
        )
    }

    /** Changes between two checkpoints (a = older/base, b = newer). */
    suspend fun compare(project: Project, a: Long, b: Long): List<FileChange> = withContext(Dispatchers.IO) {
        val root = project.dir
        val old = readManifest(root, a)?.associateBy { it.path } ?: return@withContext emptyList()
        val new = readManifest(root, b)?.associateBy { it.path } ?: return@withContext emptyList()
        buildChanges(
            oldPaths = old.keys,
            newPaths = new.keys,
            oldSha = { old.getValue(it).sha },
            newSha = { new.getValue(it).sha },
            oldText = { old[it]?.let { file -> blobText(root, file.sha) } },
            newText = { new[it]?.let { file -> blobText(root, file.sha) } },
        )
    }

    /** (checkpoint text, current text) for one file — either side blank when absent. */
    suspend fun fileDiff(project: Project, id: Long, path: String): Pair<String, String> =
        withContext(Dispatchers.IO) {
            val root = project.dir
            val entry = readManifest(root, id)?.firstOrNull { it.path == path }
            val old = entry?.let { blobText(root, it.sha) } ?: ""
            val new = readTextOrNull(File(root, path)) ?: ""
            old to new
        }

    /** (text in a, text in b) for one file across two checkpoints. */
    suspend fun compareFileTexts(project: Project, a: Long, b: Long, path: String): Pair<String, String> =
        withContext(Dispatchers.IO) {
            val root = project.dir
            val old = readManifest(root, a)?.firstOrNull { it.path == path }?.let { blobText(root, it.sha) } ?: ""
            val new = readManifest(root, b)?.firstOrNull { it.path == path }?.let { blobText(root, it.sha) } ?: ""
            old to new
        }

    // ---- internals --------------------------------------------------------

    private fun buildChanges(
        oldPaths: Set<String>,
        newPaths: Set<String>,
        oldSha: (String) -> String,
        newSha: (String) -> String,
        oldText: (String) -> String?,
        newText: (String) -> String?,
    ): List<FileChange> {
        val out = ArrayList<FileChange>()
        for (path in (oldPaths + newPaths).sorted()) {
            val inOld = path in oldPaths
            val inNew = path in newPaths
            when {
                !inOld && inNew ->
                    out += FileChange(path, ChangeType.ADDED, lineCount(newText(path)), 0)

                inOld && !inNew ->
                    out += FileChange(path, ChangeType.DELETED, 0, lineCount(oldText(path)))

                oldSha(path) != newSha(path) -> {
                    val o = oldText(path)
                    val n = newText(path)
                    if (o != null && n != null) {
                        val (added, removed) = TextDiff.stat(o, n)
                        out += FileChange(path, ChangeType.MODIFIED, added, removed)
                    } else {
                        out += FileChange(path, ChangeType.MODIFIED, 0, 0)
                    }
                }
            }
        }
        return out
    }

    private fun prune(root: File, index: CheckpointIndex, retain: Int): CheckpointIndex {
        if (retain <= 0 || index.items.size <= retain) return index
        index.items.drop(retain).forEach { manifestFile(root, it.id).delete() }
        val trimmed = index.copy(items = index.items.take(retain))
        gcBlobs(root, trimmed)
        return trimmed
    }

    /** Deletes blob files no surviving manifest references. */
    private fun gcBlobs(root: File, index: CheckpointIndex) {
        val referenced = HashSet<String>()
        index.items.forEach { checkpoint ->
            readManifest(root, checkpoint.id)?.forEach { referenced += it.sha }
        }
        blobsDir(root).listFiles()?.forEach { if (it.name !in referenced) it.delete() }
    }

    private fun pruneEmptyDirs(root: File) {
        root.walkBottomUp().forEach { dir ->
            if (dir.isDirectory && dir != root && dir.name !in SKIP && dir.listFiles()?.isEmpty() == true) {
                dir.delete()
            }
        }
    }

    private fun publish(root: File, index: CheckpointIndex) {
        if (boundDir?.absolutePath == root.absolutePath) _checkpoints.value = index.items
    }

    private fun collect(root: File): List<File> {
        val out = ArrayList<File>()
        root.walkTopDown().onEnter { it.name !in SKIP }.forEach { if (it.isFile) out += it }
        return out
    }

    private fun relativize(root: File, file: File): String =
        file.toRelativeString(root).replace(File.separatorChar, '/')

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun blobText(root: File, sha: String): String? = readTextOrNull(File(blobsDir(root), sha))

    /** Reads a file as text, or null if it is missing, too large, or looks binary. */
    private fun readTextOrNull(file: File): String? {
        if (!file.isFile || file.length() > MAX_DIFF_BYTES) return null
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return null
        val probe = minOf(bytes.size, 8000)
        for (i in 0 until probe) if (bytes[i] == 0.toByte()) return null
        return String(bytes, Charsets.UTF_8)
    }

    private fun lineCount(text: String?): Int = when {
        text.isNullOrEmpty() -> 0
        else -> text.split('\n').size
    }

    private fun readIndex(root: File): CheckpointIndex {
        val file = indexFile(root)
        if (!file.isFile) return CheckpointIndex()
        return runCatching { json.decodeFromString(CheckpointIndex.serializer(), file.readText()) }
            .getOrDefault(CheckpointIndex())
    }

    private fun writeIndex(root: File, index: CheckpointIndex) {
        baseDir(root).mkdirs()
        runCatching {
            indexFile(root).writeText(json.encodeToString(CheckpointIndex.serializer(), index))
        }
    }

    private fun readManifest(root: File, id: Long): List<CheckpointFile>? {
        val file = manifestFile(root, id)
        if (!file.isFile) return null
        return runCatching {
            json.decodeFromString(ListSerializer(CheckpointFile.serializer()), file.readText())
        }.getOrNull()
    }

    private fun writeManifest(root: File, id: Long, entries: List<CheckpointFile>) {
        runCatching {
            manifestFile(root, id).writeText(
                json.encodeToString(ListSerializer(CheckpointFile.serializer()), entries),
            )
        }
    }

    private fun baseDir(root: File) = File(root, "$SESSION_DIR/checkpoints")
    private fun blobsDir(root: File) = File(baseDir(root), "blobs")
    private fun indexFile(root: File) = File(baseDir(root), "index.json")
    private fun manifestFile(root: File, id: Long) = File(baseDir(root), "$id.manifest.json")

    private companion object {
        const val SESSION_DIR = ".opencode"
        const val MAX_FILES = 6000
        const val MAX_TOTAL_BYTES = 64L * 1024 * 1024
        const val MAX_DIFF_BYTES = 1_000_000L
        val SKIP = setOf(".git", "node_modules", ".gradle", "build", ".opencode", "__pycache__")
    }
}
