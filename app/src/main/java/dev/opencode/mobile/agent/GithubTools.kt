package dev.opencode.mobile.agent

import dev.opencode.mobile.core.github.GhComment
import dev.opencode.mobile.core.github.GhCommit
import dev.opencode.mobile.core.github.GhIssue
import dev.opencode.mobile.core.github.GhPull
import dev.opencode.mobile.core.github.GhRepo
import dev.opencode.mobile.core.github.GhRun
import dev.opencode.mobile.core.github.GitHubClient
import dev.opencode.mobile.core.settings.redactSecrets
import kotlinx.serialization.json.JsonObject

/**
 * GitHub tools (feature 8). Every call goes through the session's client; the
 * token is attached inside the client and is never part of any argument or
 * result the model sees. Reads are ungated like every other read tool;
 * anything that creates content on GitHub (repos, issues, comments, PRs) is a
 * mutating call and follows the normal approval flow.
 */
object GithubAuthTool : AgentTool {
    override val name = "github_account"
    override val description =
        "Report the signed-in GitHub account, or explain how to sign in from the Hub tab. Read-only."
    override val parameters = schema()

    override suspend fun execute(args: JsonObject, context: ToolContext): String {
        val account = context.github.account.value
        return if (account != null) {
            "Signed in as ${account.login}" +
                (account.name?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: "") +
                ". Repositories and issues/PRs/Actions tools are available."
        } else {
            "ERROR: not signed in to GitHub. The user can sign in from the Hub tab with a " +
                "personal access token or OAuth device flow."
        }
    }

    override fun summarize(args: JsonObject) = "github_account"
}

private fun ToolContext.requireGithub(): GitHubClient =
    github.client.value ?: throw IllegalStateException(
        "Not signed in to GitHub. Ask the user to sign in from the Hub tab " +
            "(token or OAuth), then retry.",
    )

private fun normalizeSlug(raw: String): String? {
    val value = raw.trim().trimEnd('/')
    return when {
        value.matches(Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")) -> value
        value.startsWith("https://") || value.startsWith("http://") ->
            value.removePrefix("https://").removePrefix("http://")
                .removePrefix("www.").removePrefix("github.com/")
                .removeSuffix(".git").takeIf { '/' in it }
        else -> null
    }
}

object GithubReposTool : AgentTool {
    override val name = "github_repos"
    override val description =
        "List the user's accessible GitHub repositories (newest activity first) with permissions, " +
            "language, default branch and open-issue counts. Read-only."
    override val parameters = schema("limit" to intProp("Max repos to return (default 30, cap 100)."))

    override suspend fun execute(args: JsonObject, context: ToolContext): String {
        val limit = args.int("limit", 30).coerceIn(1, 100)
        val repos = context.requireGithub().listRepos(limit)
        if (repos.isEmpty()) return "No repositories are visible to this account."
        return repos.joinToString("\n") { r -> renderRepoLine(r) }.redactSecrets()
    }

    override fun summarize(args: JsonObject) = "github_repos"
}

private fun renderRepoLine(r: GhRepo): String = buildString {
    append("${r.slug} · default:${r.defaultBranch}")
    if (r.isPrivate) append(" · private")
    append(" · push:") // permission snapshot for this account
    if (r.permissions?.push == true || !r.isPrivate) append("yes") else append("no")
    r.language?.let { append(" · ").append(it) }
    if (r.openIssuesCount > 0) append(" · ${r.openIssuesCount} open issues")
}

object GithubRepoInfoTool : AgentTool {
    override val name = "github_repo_info"
    override val description =
        "Fetch one repository's details by owner/name slug or https URL: default branch, " +
            "permissions, clone URL, sizes. Read-only."
    override val parameters =
        schema("repo" to stringProp("owner/name or an https://github.com/... URL", required = true))

    override suspend fun execute(args: JsonObject, context: ToolContext): String {
        val repo = context.requireGithub()
        val slug = normalizeSlug(args.requireStr("repo"))
            ?: return "ERROR: could not parse '${args.str("repo")}'. Use owner/name."
        val info = repo.getRepo(slug)
        return buildString {
            appendLine("repo: ${info.slug}")
            appendLine("default branch: ${info.defaultBranch}")
            appendLine("clone url: ${info.cloneUrl.ifBlank { "https://github.com/${info.slug}.git" }}")
            appendLine("push allowed: ${info.permissions?.push == true}")
            info.language?.let { appendLine("language: $it") }
            appendLine("open issues: ${info.openIssuesCount}")
            info.description?.let { appendLine("description: $it".take(300)) }
        }.redactSecrets()
    }

    override fun summarize(args: JsonObject) = "github_repo_info  ${args.str("repo").orEmpty()}"
}

object GithubCreateRepoTool : AgentTool {
    override val name = "github_create_repo"
    override val mutating = true
    override val description =
        "Create a new repository on the user's GitHub account. Use auto_init=true for an empty " +
            "initial commit when you plan to push code to it afterwards."
    override val parameters = schema(
        "name" to stringProp("Repository name", required = true),
        "description" to stringProp("Short description"),
        "private" to boolProp("Private repository. Default false."),
        "auto_init" to boolProp("Create an initial README commit. Default true."),
    )

    override suspend fun execute(args: JsonObject, context: ToolContext): String {
        val api = context.requireGithub()
        val created = api.createRepo(
            name = args.requireStr("name"),
            description = args.str("description").orEmpty(),
            isPrivate = args.bool("private"),
            autoInit = args.bool("auto_init", true),
        )
        return buildString {
            appendLine("Created ${created.htmlUrl}")
            appendLine("clone url: ${created.cloneUrl}")
            appendLine("default branch: ${created.defaultBranch}")
        }.redactSecrets()
    }

    override fun summarize(args: JsonObject) = "github_create_repo  ${args.str("name").orEmpty()}"
}

object GithubBranchesTool : AgentTool {
    override val name = "github_branches"
    override val description = "List branches of a GitHub repository with their head SHAs. Read-only."
    override val parameters =
        schema("repo" to stringProp("owner/name or https URL", required = true))

    override suspend fun execute(args: JsonObject, context: ToolContext): String {
        val slug = normalizeSlug(args.requireStr("repo"))
            ?: return "ERROR: expected owner/name, got '${args.str("repo")}'"
        val branches = context.requireGithub().listBranches(slug)
        if (branches.isEmpty()) return "No branches (empty repository?)."
        return branches.joinToString("\n") { b ->
            "${b.name}  ${b.commit?.sha?.take(7).orEmpty()}" + if (b.protected) "  [protected]" else ""
        }.redactSecrets()
    }

    override fun summarize(args: JsonObject) = "github_branches  ${args.str("repo").orEmpty()}"
}

object GithubCommitsTool : AgentTool {
    override val name = "github_commits"
    override val description =
        "List recent commits on a branch of a GitHub repository (SHA, author, subject). Read-only."
    override val parameters = schema(
        "repo" to stringProp("owner/name or https URL", required = true),
        "branch" to stringProp("Branch or ref; defaults to the repository default branch."),
        "limit" to intProp("Max commits (default 20, cap 60)."),
    )

    override suspend fun execute(args: JsonObject, context: ToolContext): String {
        val slug = normalizeSlug(args.requireStr("repo"))
            ?: return "ERROR: expected owner/name, got '${args.str("repo")}'"
        val commits: List<GhCommit> = context.requireGithub()
            .listCommits(slug, ref = args.str("branch"), limit = args.int("limit", 20).coerceIn(1, 60))
        if (commits.isEmpty()) return "No commits found."
        return commits.joinToString("\n") { c ->
            "${c.shortSha}  ${c.authorName.ifBlank { "?" }}  ${c.subjectLine}"
        }.redactSecrets()
    }

    override fun summarize(args: JsonObject) = "github_commits  ${args.str("repo").orEmpty()}"
}

object GithubIssuesTool : AgentTool {
    override val name = "github_issues"
    override val description =
        "List issues of a GitHub repository (pull requests excluded): number, state, title, labels. Read-only."
    override val parameters = schema(
        "repo" to stringProp("owner/name or https URL", required = true),
        "state" to stringProp("open | closed | all. Default open.", enum = listOf("open", "closed", "all")),
        "limit" to intProp("Max issues (default 30, cap 100)."),
    )

    override suspend fun execute(args: JsonObject, context: ToolContext): String {
        val slug = normalizeSlug(args.requireStr("repo"))
            ?: return "ERROR: expected owner/name, got '${args.str("repo")}'"
        val issues: List<GhIssue> = context.requireGithub()
            .listIssues(slug, state = args.str("state") ?: "open", limit = args.int("limit", 30).coerceIn(1, 100))
        if (issues.isEmpty()) return "No issues match."
        return issues.joinToString("\n") { i ->
            "#${i.number} [${i.state}] ${i.title.take(120)}" +
                (if (i.labelNames.isNotEmpty()) "  (${i.labelNames.joinToString(",")})" else "")
        }.redactSecrets()
    }

    override fun summarize(args: JsonObject) = "github_issues  ${args.str("repo").orEmpty()}"
}

object GithubGetIssueTool : AgentTool {
    override val name = "github_get_issue"
    override val description =
        "Fetch one issue of a GitHub repository by number, including its body and recent comments. " +
            "Use this before fixing an issue so you know what is actually asked. Read-only."
    override val parameters = schema(
        "repo" to stringProp("owner/name or https URL", required = true),
        "number" to intProp("Issue number", required = true),
    )

    override suspend fun execute(args: JsonObject, context: ToolContext): String {
        val api = context.requireGithub()
        val slug = normalizeSlug(args.requireStr("repo"))
            ?: return "ERROR: expected owner/name, got '${args.str("repo")}'"
        val issue = api.getIssue(slug, args.int("number", -1))

        val body = buildString {
            appendLine("#${issue.number} [${issue.state}] ${issue.title}")
            issue.user?.login?.let { appendLine("opened by $it") }
            issue.labelNames.takeIf { it.isNotEmpty() }?.let { appendLine("labels: ${it.joinToString(",")}") }
            appendLine()
            appendLine(issue.body.orEmpty().ifBlank { "(no description)" }.take(MAX_BODY))
        }
        val comments: List<GhComment> =
            if (issue.comments > 0) api.listComments(slug, issue.number, limit = 10) else emptyList()
        return (body + renderComments(comments)).redactSecrets()
    }

    override fun summarize(args: JsonObject) = "github_get_issue  #${args.int("number", 0)}"
}

private const val MAX_BODY = 2400

private fun renderComments(comments: List<GhComment>): String {
    if (comments.isEmpty()) return ""
    return buildString {
        appendLine()
        appendLine("--- comments ---")
        comments.forEach { c ->
            appendLine("${c.user?.login ?: "?"}: ${c.body.take(600)}")
        }
    }
}

object GithubCreateIssueTool : AgentTool {
    override val name = "github_create_issue"
    override val mutating = true
    override val description = "Create an issue in a GitHub repository."
    override val parameters = schema(
        "repo" to stringProp("owner/name or https URL", required = true),
        "title" to stringProp("Issue title", required = true),
        "body" to stringProp("Issue body (markdown)"),
        "labels" to stringProp("Comma-separated label names (must already exist)"),
    )

    override suspend fun execute(args: JsonObject, context: ToolContext): String {
        val slug = normalizeSlug(args.requireStr("repo"))
            ?: return "ERROR: expected owner/name, got '${args.str("repo")}'"
        val labels = args.str("labels").orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val issue = context.requireGithub().createIssue(
            slug,
            title = args.requireStr("title"),
            body = args.str("body").orEmpty(),
            labels = labels,
        )
        return "Created #${issue.number}: ${issue.title}".redactSecrets() +
            (issue.htmlUrl?.let { "\n$it" } ?: "")
    }

    override fun summarize(args: JsonObject) = "github_create_issue  ${args.str("title").orEmpty().take(40)}"
}

object GithubCommentTool : AgentTool {
    override val name = "github_comment"
    override val mutating = true
    override val description =
        "Post a comment on a GitHub issue or pull request (both share the comment API)."
    override val parameters = schema(
        "repo" to stringProp("owner/name or https URL", required = true),
        "number" to intProp("Issue or PR number", required = true),
        "body" to stringProp("Comment body (markdown)", required = true),
    )

    override suspend fun execute(args: JsonObject, context: ToolContext): String {
        val slug = normalizeSlug(args.requireStr("repo"))
            ?: return "ERROR: expected owner/name, got '${args.str("repo")}'"
        val comment = context.requireGithub().addComment(slug, args.int("number", -1), args.requireStr("body"))
        return "Comment posted.".redactSecrets() + (comment.htmlUrl?.let { "\n$it" } ?: "")
    }

    override fun summarize(args: JsonObject) = "github_comment  #${args.int("number", 0)}"
}

object GithubPullsTool : AgentTool {
    override val name = "github_pulls"
    override val description =
        "List pull requests of a GitHub repository: number, state/draft, title, head→base. Read-only."
    override val parameters = schema(
        "repo" to stringProp("owner/name or https URL", required = true),
        "state" to stringProp("open | closed | all. Default open.", enum = listOf("open", "closed", "all")),
    )

    override suspend fun execute(args: JsonObject, context: ToolContext): String {
        val slug = normalizeSlug(args.requireStr("repo"))
            ?: return "ERROR: expected owner/name, got '${args.str("repo")}'"
        val pulls: List<GhPull> = context.requireGithub()
            .listPulls(slug, state = args.str("state") ?: "open")
        if (pulls.isEmpty()) return "No pull requests match."
        return pulls.joinToString("\n") { p ->
            "#${p.number} [${prState(p)}] ${p.title.take(110)}  (${p.headRef}→${p.baseRef})"
        }.redactSecrets()
    }

    override fun summarize(args: JsonObject) = "github_pulls  ${args.str("repo").orEmpty()}"
}

private fun prState(p: GhPull): String = when {
    p.merged -> "merged"
    p.draft -> "draft"
    else -> p.state
}

object GithubGetPullTool : AgentTool {
    override val name = "github_get_pull"
    override val description =
        "Fetch one pull request: description, diff stat, review decisions and review comments. Read-only."
    override val parameters = schema(
        "repo" to stringProp("owner/name or https URL", required = true),
        "number" to intProp("PR number", required = true),
    )

    override suspend fun execute(args: JsonObject, context: ToolContext): String {
        val api = context.requireGithub()
        val slug = normalizeSlug(args.requireStr("repo"))
            ?: return "ERROR: expected owner/name, got '${args.str("repo")}'"
        val number = args.int("number", -1)
        val pull = api.getPull(slug, number)
        val reviews = api.listReviews(slug, number)
        val comments = api.listComments(slug, number, limit = 8)

        return buildString {
            appendLine("#$number [${prState(pull)}] ${pull.title}")
            appendLine("${pull.headRef} → ${pull.baseRef} · +${pull.additions} −${pull.deletions}" +
                " across ${pull.changedFiles} files")
            appendLine()
            appendLine(pull.body.orEmpty().ifBlank { "(no description)" }.take(MAX_BODY))
            if (reviews.isNotEmpty()) {
                appendLine()
                appendLine("--- reviews ---")
                reviews.forEach { r ->
                    appendLine("${r.user?.login ?: "?"}: ${r.state ?: "?"}" +
                        (r.body?.takeIf { it.isNotBlank() }?.let { " — ${it.take(300)}" } ?: ""))
                }
            }
            append(renderComments(comments))
        }.redactSecrets()
    }

    override fun summarize(args: JsonObject) = "github_get_pull  #${args.int("number", 0)}"
}

object GithubCreatePullTool : AgentTool {
    override val name = "github_create_pull"
    override val mutating = true
    override val description =
        "Open a pull request on GitHub. Call after committing and pushing the feature branch — " +
            "the head branch must exist on GitHub first. Reference the originating issue as 'Fixes #123'."
    override val parameters = schema(
        "repo" to stringProp("owner/name or https URL", required = true),
        "title" to stringProp("PR title", required = true),
        "head" to stringProp("Feature branch that carries your changes", required = true),
        "base" to stringProp("Target branch, e.g. main"),
        "body" to stringProp("PR description (markdown); mention 'Fixes #N' to close the issue on merge"),
        "draft" to boolProp("Create as draft. Default false."),
    )

    override suspend fun execute(args: JsonObject, context: ToolContext): String {
        val slug = normalizeSlug(args.requireStr("repo"))
            ?: return "ERROR: expected owner/name, got '${args.str("repo")}'"
        val created = context.requireGithub().createPull(
            slug,
            title = args.requireStr("title"),
            head = args.requireStr("head"),
            base = args.str("base") ?: "main",
            body = args.str("body").orEmpty(),
            draft = args.bool("draft"),
        )
        return buildString {
            appendLine("Opened PR #${created.number}: ${created.title}".redactSecrets())
            created.htmlUrl?.let { appendLine(it) }
            appendLine("${created.headRef} → ${created.baseRef}")
        }
    }

    override fun summarize(args: JsonObject) = "github_create_pull  ${args.str("title").orEmpty().take(40)}"
}

object GithubActionsTool : AgentTool {
    override val name = "github_actions_status"
    override val description =
        "Recent GitHub Actions workflow runs for a repository: status, conclusion, branch. Read-only."
    override val parameters = schema(
        "repo" to stringProp("owner/name or https URL", required = true),
        "branch" to stringProp("Filter to one branch"),
        "limit" to intProp("Max runs (default 10, cap 25)."),
    )

    override suspend fun execute(args: JsonObject, context: ToolContext): String {
        val slug = normalizeSlug(args.requireStr("repo"))
            ?: return "ERROR: expected owner/name, got '${args.str("repo")}'"
        val runs: List<GhRun> = context.requireGithub()
            .listWorkflowRuns(slug, branch = args.str("branch"), limit = args.int("limit", 10).coerceIn(1, 25))
        if (runs.isEmpty()) return "No workflow runs recorded."
        return runs.joinToString("\n") { r ->
            val icon = when {
                r.isRunning -> "● running"
                r.conclusion == "success" -> "✓ success"
                r.isFailure -> "✗ failed"
                else -> "- ${r.conclusion ?: r.status}"
            }
            "#${r.runNumber} $icon  ${(r.name ?: r.displayTitle).orEmpty().take(50)}" +
                (r.headBranch?.let { "  [$it]" } ?: "")
        }.redactSecrets()
    }

    override fun summarize(args: JsonObject) = "github_actions_status  ${args.str("repo").orEmpty()}"
}
