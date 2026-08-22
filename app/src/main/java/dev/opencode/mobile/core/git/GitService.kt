package dev.opencode.mobile.core.git

import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.ProgressMonitor
import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider

data class GitIdentity(val name: String, val email: String)

data class GitFileStatus(val path: String, val state: String)

data class GitStatusSummary(
    val branch: String,
    val clean: Boolean,
    val files: List<GitFileStatus>,
    val ahead: Int = 0,
) {
    fun render(): String = buildString {
        appendLine("branch: $branch")
        if (clean) {
            appendLine("working tree clean")
        } else {
            files.forEach { appendLine("${it.state}  ${it.path}") }
        }
    }
}

data class GitCommitInfo(
    val id: String,
    val shortMessage: String,
    val author: String,
    val timestampMillis: Long,
)

/**
 * All git work runs through JGit, so no native binary or shell is needed.
 *
 * Only HTTPS remotes are supported: SSH transports were excluded from the build
 * because they pull JCE providers Android does not ship.
 */
class GitService {

    /** Progress callback: (task label, percent 0..100 or -1 when unknown). */
    fun interface Progress {
        fun onProgress(task: String, percent: Int)
    }

    suspend fun clone(
        url: String,
        targetDir: File,
        branch: String? = null,
        credentials: Pair<String, String>? = null,
        progress: Progress? = null,
    ): String = withContext(Dispatchers.IO) {
        require(url.startsWith("https://") || url.startsWith("http://")) {
            "Only https:// remotes work on device (ssh transport is not bundled)"
        }

        val command = Git.cloneRepository()
            .setURI(url)
            .setDirectory(targetDir)
            // Single branch keeps the download and the object store small on a phone.
            .setCloneAllBranches(false)
            .setTimeout(120)
            .setProgressMonitor(progress.asMonitor())

        if (!branch.isNullOrBlank()) command.setBranch("refs/heads/$branch")
        credentials?.let { (user, token) ->
            command.setCredentialsProvider(UsernamePasswordCredentialsProvider(user, token))
        }

        command.call().use { git ->
            val head = git.repository.branch ?: "HEAD"
            val count = git.log().setMaxCount(1).call().firstOrNull()?.name?.take(7) ?: "?"
            "Cloned into ${targetDir.name} (branch $head, head $count)"
        }
    }

    suspend fun init(dir: File): String = withContext(Dispatchers.IO) {
        Git.init().setDirectory(dir).call().use { "Initialized empty repository in ${dir.name}" }
    }

    suspend fun status(dir: File): GitStatusSummary = withContext(Dispatchers.IO) {
        open(dir).use { git ->
            val status = git.status().call()
            val files = buildList {
                status.added.forEach { add(GitFileStatus(it, "A")) }
                status.changed.forEach { add(GitFileStatus(it, "M")) }
                status.removed.forEach { add(GitFileStatus(it, "D")) }
                status.modified.forEach { add(GitFileStatus(it, "M")) }
                status.missing.forEach { add(GitFileStatus(it, "D")) }
                status.untracked.forEach { add(GitFileStatus(it, "?")) }
                status.conflicting.forEach { add(GitFileStatus(it, "U")) }
            }.distinctBy { it.path }

            GitStatusSummary(
                branch = git.repository.branch ?: "HEAD",
                clean = status.isClean,
                files = files,
            )
        }
    }

    suspend fun stageAll(dir: File): Int = withContext(Dispatchers.IO) {
        open(dir).use { git ->
            git.add().addFilepattern(".").call()
            // Records deletions too; add() alone does not.
            git.add().addFilepattern(".").setUpdate(true).call()
            git.status().call().let { it.added.size + it.changed.size + it.removed.size }
        }
    }

    suspend fun commit(dir: File, message: String, identity: GitIdentity): String =
        withContext(Dispatchers.IO) {
            open(dir).use { git ->
                val commit = git.commit()
                    .setMessage(message)
                    .setAuthor(identity.name, identity.email)
                    .setCommitter(identity.name, identity.email)
                    .call()
                "${commit.name.take(7)} ${commit.shortMessage}"
            }
        }

    suspend fun log(dir: File, limit: Int = 30): List<GitCommitInfo> = withContext(Dispatchers.IO) {
        open(dir).use { git ->
            runCatching {
                git.log().setMaxCount(limit).call().map { commit ->
                    GitCommitInfo(
                        id = commit.name,
                        shortMessage = commit.shortMessage,
                        author = commit.authorIdent.name,
                        timestampMillis = commit.authorIdent.`when`.time,
                    )
                }
            }.getOrDefault(emptyList()) // A fresh repo has no HEAD yet.
        }
    }

    suspend fun diff(dir: File, staged: Boolean = false): String = withContext(Dispatchers.IO) {
        open(dir).use { git ->
            val out = ByteArrayOutputStream()
            git.diff().setCached(staged).setOutputStream(out).call()
            out.toString("UTF-8").ifBlank { "(no changes)" }
        }
    }

    suspend fun branches(dir: File): List<String> = withContext(Dispatchers.IO) {
        open(dir).use { git ->
            git.branchList().call().map { it.name.removePrefix("refs/heads/") }
        }
    }

    suspend fun checkout(dir: File, branch: String, create: Boolean): String =
        withContext(Dispatchers.IO) {
            open(dir).use { git ->
                git.checkout().setName(branch).setCreateBranch(create).call()
                "Switched to $branch"
            }
        }

    suspend fun pull(dir: File, credentials: Pair<String, String>?): String =
        withContext(Dispatchers.IO) {
            open(dir).use { git ->
                val command = git.pull().setTimeout(120)
                credentials?.let { (user, token) ->
                    command.setCredentialsProvider(UsernamePasswordCredentialsProvider(user, token))
                }
                val result = command.call()
                if (result.isSuccessful) "Pull complete: ${result.mergeResult?.mergeStatus ?: "ok"}"
                else "Pull failed: ${result.mergeResult?.mergeStatus ?: result}"
            }
        }

    suspend fun push(
        dir: File,
        credentials: Pair<String, String>,
        branch: String? = null,
    ): String = withContext(Dispatchers.IO) {
        open(dir).use { git ->
            val head = branch ?: git.repository.branch
            val command = git.push()
                .setTimeout(120)
                .setCredentialsProvider(
                    UsernamePasswordCredentialsProvider(credentials.first, credentials.second),
                )
            if (head != null) command.setRefSpecs(RefSpec("refs/heads/$head:refs/heads/$head"))
            command.call().joinToString("\n") { result ->
                result.remoteUpdates.joinToString("\n") { "${it.status} ${it.remoteName}" }
            }.ifBlank { "Nothing to push" }
        }
    }

    suspend fun remoteUrl(dir: File): String? = withContext(Dispatchers.IO) {
        runCatching {
            open(dir).use { git ->
                git.repository.config.getString("remote", "origin", "url")
            }
        }.getOrNull()
    }

    fun isRepo(dir: File): Boolean = File(dir, ".git").exists()

    private fun open(dir: File): Git {
        require(isRepo(dir)) { "${dir.name} is not a git repository" }
        return Git.open(dir)
    }
}

private fun GitService.Progress?.asMonitor(): ProgressMonitor = object : ProgressMonitor {
    private var task = ""
    private var total = 0
    private var done = 0

    override fun start(totalTasks: Int) = Unit

    override fun beginTask(title: String?, totalWork: Int) {
        task = title.orEmpty()
        total = totalWork
        done = 0
        this@asMonitor?.onProgress(task, if (totalWork > 0) 0 else -1)
    }

    override fun update(completed: Int) {
        done += completed
        val percent = if (total > 0) (done * 100 / total).coerceIn(0, 100) else -1
        this@asMonitor?.onProgress(task, percent)
    }

    override fun endTask() {
        this@asMonitor?.onProgress(task, 100)
    }

    override fun isCancelled(): Boolean = false
}
