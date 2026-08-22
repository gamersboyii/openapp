package dev.opencode.mobile.core.git

import dev.opencode.mobile.llm.Http
import java.io.File
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request

/**
 * A full `git clone` on a phone downloads the entire history. JGit 5.13 has no
 * shallow-clone support, so for "I just want the code" cases this pulls the
 * provider's zip snapshot instead: one HTTPS request, no `.git` directory,
 * typically a small fraction of the bytes.
 *
 * Trade-off: the result is not a repository, so it cannot commit or push. Use
 * [GitService.clone] when history matters.
 */
class RepoSnapshotService {

    data class Target(val host: String, val owner: String, val repo: String)

    suspend fun download(
        url: String,
        targetDir: File,
        branch: String? = null,
        token: String? = null,
    ): String = withContext(Dispatchers.IO) {
        val target = parse(url) ?: throw IllegalArgumentException(
            "Snapshot download supports github.com, gitlab.com and codeberg.org URLs. " +
                "Use git_clone for anything else.",
        )

        val branches = branch?.let(::listOf) ?: listOf("main", "master")
        var lastError = "unknown error"

        for (candidate in branches) {
            val zipUrl = archiveUrl(target, candidate)
            val request = Request.Builder().url(zipUrl).get()
                .apply { if (!token.isNullOrBlank()) header("Authorization", "Bearer $token") }
                .build()

            val outcome = runCatching {
                Http.client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        lastError = "HTTP ${response.code} for $candidate"
                        return@use null
                    }
                    val stream = response.body?.byteStream() ?: return@use null
                    val count = unzipStrippingRoot(ZipInputStream(stream), targetDir)
                    "Downloaded ${target.owner}/${target.repo}@$candidate — $count files (snapshot, no git history)"
                }
            }.getOrElse { lastError = it.message ?: it.toString(); null }

            if (outcome != null) return@withContext outcome
        }
        throw IllegalStateException("Snapshot download failed: $lastError")
    }

    fun parse(url: String): Target? {
        val cleaned = url.trim()
            .removeSuffix(".git")
            .removePrefix("https://")
            .removePrefix("http://")
            .removePrefix("git@")
            .replace(':', '/')
        val parts = cleaned.split('/').filter { it.isNotBlank() }
        if (parts.size < 3) return null
        val host = parts[0].lowercase()
        if (host !in SUPPORTED_HOSTS) return null
        return Target(host, parts[1], parts[2])
    }

    private fun archiveUrl(target: Target, branch: String): String = when (target.host) {
        "github.com" ->
            "https://codeload.github.com/${target.owner}/${target.repo}/zip/refs/heads/$branch"

        "gitlab.com" ->
            "https://gitlab.com/${target.owner}/${target.repo}/-/archive/$branch/${target.repo}-$branch.zip"

        else ->
            "https://codeberg.org/${target.owner}/${target.repo}/archive/$branch.zip"
    }

    /** Archive zips wrap everything in `repo-branch/`; that prefix is dropped. */
    private fun unzipStrippingRoot(zip: ZipInputStream, targetDir: File): Int {
        targetDir.mkdirs()
        val base = targetDir.canonicalFile
        var written = 0

        zip.use { stream ->
            var entry = stream.nextEntry
            while (entry != null) {
                val relative = entry.name.substringAfter('/', missingDelimiterValue = "")
                if (relative.isNotBlank()) {
                    val out = File(base, relative).canonicalFile
                    // Zip-slip guard: reject entries that resolve outside the target.
                    if (out.path == base.path || out.path.startsWith(base.path + File.separator)) {
                        if (entry.isDirectory) {
                            out.mkdirs()
                        } else {
                            out.parentFile?.mkdirs()
                            out.outputStream().buffered().use { sink -> stream.copyTo(sink) }
                            written++
                        }
                    }
                }
                stream.closeEntry()
                entry = stream.nextEntry
            }
        }
        return written
    }

    private companion object {
        val SUPPORTED_HOSTS = setOf("github.com", "gitlab.com", "codeberg.org")
    }
}
