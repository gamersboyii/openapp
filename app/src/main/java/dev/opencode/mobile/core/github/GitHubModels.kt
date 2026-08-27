package dev.opencode.mobile.core.github

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire models for the GitHub REST API v3. Only the fields the app actually
 * reads are declared; `ignoreUnknownKeys` covers the rest, so GitHub can add
 * properties without breaking a decode.
 */
@Serializable
data class GhUser(
    val login: String,
    val name: String? = null,
    @SerialName("avatar_url") val avatarUrl: String? = null,
    @SerialName("html_url") val htmlUrl: String? = null,
    @SerialName("public_repos") val publicRepos: Int = 0,
)

@Serializable
data class GhPermissions(
    val admin: Boolean = false,
    val push: Boolean = false,
    val pull: Boolean = false,
)

@Serializable
data class GhRepo(
    val id: Long,
    val name: String,
    @SerialName("full_name") val fullName: String,
    @SerialName("private") val isPrivate: Boolean = false,
    val description: String? = null,
    @SerialName("html_url") val htmlUrl: String = "",
    @SerialName("clone_url") val cloneUrl: String = "",
    @SerialName("default_branch") val defaultBranch: String = "main",
    val language: String? = null,
    @SerialName("stargazers_count") val stargazersCount: Int = 0,
    @SerialName("forks_count") val forksCount: Int = 0,
    @SerialName("open_issues_count") val openIssuesCount: Int = 0,
    @SerialName("updated_at") val updatedAt: String? = null,
    val permissions: GhPermissions? = null,
    val owner: GhUser? = null,
) {
    val slug: String get() = fullName.ifBlank { "${owner?.login.orEmpty()}/$name" }
}

@Serializable
data class GhBranchCommit(val sha: String = "")

@Serializable
data class GhBranch(
    val name: String,
    val commit: GhBranchCommit? = null,
    val protected: Boolean = false,
)

@Serializable
data class GitPerson(
    val name: String? = null,
    val email: String? = null,
    val date: String? = null,
)

@Serializable
data class CommitDetail(val message: String? = null, val author: GitPerson? = null)

@Serializable
data class GhCommit(
    val sha: String,
    val commit: CommitDetail? = null,
    @SerialName("html_url") val htmlUrl: String? = null,
) {
    val shortSha: String get() = sha.take(7)
    val message: String get() = commit?.message.orEmpty()
    val subjectLine: String get() = message.lineSequence().firstOrNull().orEmpty().take(120)
    val authorName: String get() = commit?.author?.name.orEmpty()
}

/** A content entry from /repos/{owner}/{repo}/contents/{path}. */
@Serializable
data class GhContent(
    val name: String,
    val path: String,
    val type: String,
    val size: Long = 0,
    @SerialName("download_url") val downloadUrl: String? = null,
    @SerialName("html_url") val htmlUrl: String? = null,
) {
    val isDirectory: Boolean get() = type == "dir"
}

/**
 * Issues and pull requests share an endpoint; a payload that carries a
 * `pull_request` object is a PR and gets filtered out of issue lists.
 */
@Serializable
data class GhPullRef(val url: String? = null)

@Serializable
data class GhLabel(val name: String = "")

@Serializable
data class GhIssue(
    val id: Long,
    val number: Int,
    val title: String,
    val body: String? = null,
    val state: String = "open",
    val user: GhUser? = null,
    val comments: Int = 0,
    @SerialName("html_url") val htmlUrl: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("pull_request") val pullRequest: GhPullRef? = null,
    val labels: List<GhLabel> = emptyList(),
) {
    val isPullRequest: Boolean get() = pullRequest != null
    val labelNames: List<String> get() = labels.map { it.name }
}

@Serializable
data class GhComment(
    val id: Long,
    val body: String,
    val user: GhUser? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("html_url") val htmlUrl: String? = null,
)

@Serializable
data class GhPullBranch(
    val ref: String? = null,
    val sha: String? = null,
    val label: String? = null,
)

@Serializable
data class GhPull(
    val id: Long,
    val number: Int,
    val title: String,
    val body: String? = null,
    val state: String = "open",
    val draft: Boolean = false,
    val merged: Boolean = false,
    val mergeable: Boolean? = null,
    val user: GhUser? = null,
    val head: GhPullBranch? = null,
    val base: GhPullBranch? = null,
    @SerialName("html_url") val htmlUrl: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("changed_files") val changedFiles: Int = 0,
    val additions: Int = 0,
    val deletions: Int = 0,
    val comments: Int = 0,
    @SerialName("review_comments") val reviewComments: Int = 0,
) {
    val headRef: String get() = head?.ref.orEmpty()
    val baseRef: String get() = base?.ref.orEmpty()
}

@Serializable
data class GhReview(
    val id: Long,
    val state: String? = null,
    val body: String? = null,
    val user: GhUser? = null,
    @SerialName("submitted_at") val submittedAt: String? = null,
)

@Serializable
data class GhRun(
    val id: Long,
    val name: String? = null,
    @SerialName("display_title") val displayTitle: String? = null,
    val status: String = "",
    val conclusion: String? = null,
    @SerialName("head_branch") val headBranch: String? = null,
    val event: String? = null,
    @SerialName("run_number") val runNumber: Int = 0,
    @SerialName("html_url") val htmlUrl: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
) {
    /** success | failure | cancelled | neutral | skipped | timed_out | null while running */
    val isFailure: Boolean get() = conclusion == "failure" || conclusion == "timed_out"
    val isRunning: Boolean get() = status != "completed"
}

// ---- device flow -----------------------------------------------------------

/** Step 1 of the OAuth device flow: what the user types into github.com/login/device. */
data class DeviceFlowStart(
    val deviceCode: String,
    val userCode: String,
    val verificationUri: String,
    val expiresInSeconds: Int,
    val intervalSeconds: Int,
)
