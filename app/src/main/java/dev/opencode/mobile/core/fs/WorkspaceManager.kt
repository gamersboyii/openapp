package dev.opencode.mobile.core.fs

import android.content.Context
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

data class Project(
    val name: String,
    val path: String,
    val isGitRepo: Boolean,
    val lastModified: Long,
    val fileCount: Int,
) {
    val dir: File get() = File(path)
}

data class FileNode(
    val name: String,
    val relativePath: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
)

/** Directories that are noise in the tree and expensive to walk. */
private val HIDDEN_DIRS = setOf(".git", "node_modules", ".gradle", "build", ".opencode", "__pycache__")

private val TEXT_EXTENSIONS = setOf(
    "txt", "md", "markdown", "json", "jsonc", "yaml", "yml", "toml", "ini", "cfg", "conf",
    "html", "htm", "css", "scss", "sass", "less", "js", "mjs", "cjs", "jsx", "ts", "tsx",
    "kt", "kts", "java", "py", "rb", "go", "rs", "c", "h", "cpp", "hpp", "cs", "swift",
    "php", "sh", "bash", "zsh", "sql", "graphql", "gql", "vue", "svelte", "astro",
    "xml", "svg", "env", "gitignore", "properties", "lock", "csv", "tsv", "log", "cairo",
)

/**
 * Everything on disk lives under `filesDir/projects`. That keeps the app free of
 * storage permissions, and every agent tool path is resolved against a project
 * root with an explicit escape check.
 */
class WorkspaceManager(private val context: Context) {

    val root: File = File(context.filesDir, "projects").apply { mkdirs() }

    private val _projects = MutableStateFlow<List<Project>>(emptyList())
    val projects: StateFlow<List<Project>> = _projects.asStateFlow()

    private val _activeProject = MutableStateFlow<Project?>(null)
    val activeProject: StateFlow<Project?> = _activeProject.asStateFlow()

    /** Bumped whenever files change so screens can recompose without polling. */
    private val _revision = MutableStateFlow(0L)
    val revision: StateFlow<Long> = _revision.asStateFlow()

    fun notifyChanged() {
        _revision.value = _revision.value + 1
    }

    suspend fun refresh() = withContext(Dispatchers.IO) {
        val found = root.listFiles { file -> file.isDirectory }
            ?.map { dir ->
                Project(
                    name = dir.name,
                    path = dir.absolutePath,
                    isGitRepo = File(dir, ".git").exists(),
                    lastModified = dir.lastModified(),
                    fileCount = countFiles(dir, limit = 5000),
                )
            }
            ?.sortedByDescending { it.lastModified }
            .orEmpty()

        _projects.value = found
        // Drop a stale selection if the directory disappeared.
        _activeProject.value = _activeProject.value?.let { active ->
            found.firstOrNull { it.path == active.path }
        }
        notifyChanged()
    }

    fun select(project: Project?) {
        _activeProject.value = project
    }

    suspend fun selectByPath(path: String?) {
        if (path == null) {
            _activeProject.value = null
            return
        }
        if (_projects.value.isEmpty()) refresh()
        _activeProject.value = _projects.value.firstOrNull { it.path == path }
    }

    fun sanitizeProjectName(raw: String): String {
        val cleaned = raw.trim().replace(Regex("[^A-Za-z0-9._-]"), "-").trim('-', '.')
        return cleaned.ifBlank { "project" }.take(64)
    }

    /** Creates `root/<name>`, adding a `-2`, `-3`, ... suffix if it already exists. */
    suspend fun createProjectDir(name: String): File = withContext(Dispatchers.IO) {
        val base = sanitizeProjectName(name)
        var candidate = File(root, base)
        var suffix = 2
        while (candidate.exists()) {
            candidate = File(root, "$base-$suffix")
            suffix++
        }
        if (!candidate.mkdirs()) throw IOException("Could not create ${candidate.name}")
        candidate
    }

    suspend fun deleteProject(project: Project) = withContext(Dispatchers.IO) {
        project.dir.deleteRecursively()
        if (_activeProject.value?.path == project.path) _activeProject.value = null
        refresh()
    }

    suspend fun renameProject(project: Project, newName: String): Project? = withContext(Dispatchers.IO) {
        val target = File(root, sanitizeProjectName(newName))
        if (target.exists() || !project.dir.renameTo(target)) return@withContext null
        refresh()
        _projects.value.firstOrNull { it.path == target.absolutePath }?.also { select(it) }
    }

    /**
     * Resolves [relativePath] inside [projectDir], refusing anything that escapes
     * the root via `..` or a symlink. Every agent file tool goes through here.
     */
    fun resolveSafely(projectDir: File, relativePath: String): File {
        val cleaned = relativePath.trim().removePrefix("./").removePrefix("/")
        val base = projectDir.canonicalFile
        val target = File(base, cleaned).canonicalFile
        val basePath = base.path
        val ok = target.path == basePath || target.path.startsWith(basePath + File.separator)
        require(ok) { "Path '$relativePath' escapes the project directory" }
        return target
    }

    fun relativize(projectDir: File, file: File): String =
        file.canonicalPath.removePrefix(projectDir.canonicalPath).trimStart(File.separatorChar)
            .replace(File.separatorChar, '/')

    suspend fun listDirectory(projectDir: File, relativePath: String): List<FileNode> =
        withContext(Dispatchers.IO) {
            val dir = resolveSafely(projectDir, relativePath)
            if (!dir.isDirectory) return@withContext emptyList()
            dir.listFiles()
                ?.filterNot { it.isDirectory && it.name in HIDDEN_DIRS }
                ?.sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() })
                ?.map { file ->
                    FileNode(
                        name = file.name,
                        relativePath = relativize(projectDir, file),
                        isDirectory = file.isDirectory,
                        sizeBytes = if (file.isFile) file.length() else 0L,
                    )
                }
                .orEmpty()
        }

    /** Flat recursive listing, capped so a huge clone cannot stall the agent. */
    suspend fun walk(projectDir: File, relativePath: String = "", limit: Int = 800): List<String> =
        withContext(Dispatchers.IO) {
            val start = resolveSafely(projectDir, relativePath)
            val out = mutableListOf<String>()
            fun recurse(dir: File, depth: Int) {
                if (out.size >= limit || depth > 12) return
                val children = dir.listFiles()
                    ?.sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name })
                    ?: return
                for (child in children) {
                    if (out.size >= limit) return
                    if (child.isDirectory) {
                        if (child.name in HIDDEN_DIRS) continue
                        out += relativize(projectDir, child) + "/"
                        recurse(child, depth + 1)
                    } else {
                        out += relativize(projectDir, child)
                    }
                }
            }
            if (start.isDirectory) recurse(start, 0) else out += relativize(projectDir, start)
            out
        }

    suspend fun readText(projectDir: File, relativePath: String): String = withContext(Dispatchers.IO) {
        val file = resolveSafely(projectDir, relativePath)
        if (!file.isFile) throw IOException("Not a file: $relativePath")
        if (file.length() > MAX_TEXT_BYTES) {
            throw IOException("File is ${file.length() / 1024}KB, larger than the ${MAX_TEXT_BYTES / 1024}KB text limit")
        }
        file.readText()
    }

    suspend fun writeText(projectDir: File, relativePath: String, content: String): File =
        withContext(Dispatchers.IO) {
            val file = resolveSafely(projectDir, relativePath)
            file.parentFile?.mkdirs()
            file.writeText(content)
            notifyChanged()
            file
        }

    suspend fun delete(projectDir: File, relativePath: String): Boolean = withContext(Dispatchers.IO) {
        val file = resolveSafely(projectDir, relativePath)
        val removed = if (file.isDirectory) file.deleteRecursively() else file.delete()
        if (removed) notifyChanged()
        removed
    }

    suspend fun createDirectory(projectDir: File, relativePath: String): Boolean =
        withContext(Dispatchers.IO) {
            val created = resolveSafely(projectDir, relativePath).mkdirs()
            if (created) notifyChanged()
            created
        }

    data class Match(val path: String, val line: Int, val text: String)

    suspend fun search(
        projectDir: File,
        pattern: String,
        pathGlob: String? = null,
        maxResults: Int = 120,
    ): List<Match> = withContext(Dispatchers.IO) {
        val regex = runCatching { Regex(pattern, RegexOption.IGNORE_CASE) }.getOrNull()
            ?: Regex(Regex.escape(pattern), RegexOption.IGNORE_CASE)
        val globRegex = pathGlob?.takeIf { it.isNotBlank() }?.let { globToRegex(it) }
        val results = mutableListOf<Match>()

        projectDir.walkTopDown()
            .onEnter { it.name !in HIDDEN_DIRS }
            .filter { it.isFile && isProbablyText(it) && it.length() <= MAX_TEXT_BYTES }
            .forEach { file ->
                if (results.size >= maxResults) return@forEach
                val relative = relativize(projectDir, file)
                if (globRegex != null && !globRegex.matches(relative)) return@forEach
                runCatching {
                    file.useLines { lines ->
                        lines.forEachIndexed { index, line ->
                            if (results.size < maxResults && regex.containsMatchIn(line)) {
                                results += Match(relative, index + 1, line.trim().take(300))
                            }
                        }
                    }
                }
            }
        results
    }

    fun isProbablyText(file: File): Boolean {
        val extension = file.extension.lowercase()
        if (extension.isEmpty()) return file.name.startsWith(".") || file.name.lowercase() in NAMED_TEXT_FILES
        return extension in TEXT_EXTENSIONS
    }

    /** Zips a project into the cache dir so it can be shared out with an Intent. */
    suspend fun exportZip(project: Project): File = withContext(Dispatchers.IO) {
        val out = File(context.cacheDir, "exports").apply { mkdirs() }
            .resolve("${project.name}.zip")
        ZipOutputStream(out.outputStream().buffered()).use { zip ->
            project.dir.walkTopDown()
                .onEnter { it.name != ".gradle" && it.name != "build" }
                .filter { it.isFile }
                .forEach { file ->
                    zip.putNextEntry(ZipEntry(relativize(project.dir, file)))
                    file.inputStream().use { input -> input.copyTo(zip) }
                    zip.closeEntry()
                }
        }
        out
    }

    private fun countFiles(dir: File, limit: Int): Int {
        var count = 0
        dir.walkTopDown().onEnter { it.name !in HIDDEN_DIRS }.forEach {
            if (it.isFile) count++
            if (count >= limit) return limit
        }
        return count
    }

    companion object {
        const val MAX_TEXT_BYTES = 1_500_000L
        private val NAMED_TEXT_FILES = setOf("dockerfile", "makefile", "license", "readme", "procfile")
    }
}

/** Supports the `**`, `*` and `?` subset of glob syntax used by the search tool. */
fun globToRegex(glob: String): Regex {
    val builder = StringBuilder()
    var index = 0
    while (index < glob.length) {
        when (val ch = glob[index]) {
            '*' -> if (index + 1 < glob.length && glob[index + 1] == '*') {
                builder.append(".*")
                index++
            } else {
                builder.append("[^/]*")
            }

            '?' -> builder.append("[^/]")
            '.', '(', ')', '+', '|', '^', '$', '@', '%', '{', '}', '[', ']', '\\' ->
                builder.append('\\').append(ch)

            else -> builder.append(ch)
        }
        index++
    }
    return Regex(builder.toString())
}
