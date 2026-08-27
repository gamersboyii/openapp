package dev.opencode.mobile.agent

import dev.opencode.mobile.core.build.BuildAction
import dev.opencode.mobile.core.devserver.DevServerManager
import dev.opencode.mobile.core.exec.CommandPolicy
import dev.opencode.mobile.core.exec.PolicyDecision
import dev.opencode.mobile.core.git.GitIdentity
import kotlinx.serialization.json.JsonObject

// ---- File system ---------------------------------------------------------

object ListFilesTool : AgentTool {
    override val name = "list_files"
    override val description =
        "List files and directories in the project. Set recursive=true for a full tree. " +
            "Skips .git, node_modules and build output."
    override val parameters = schema(
        "path" to stringProp("Directory relative to the project root. Empty means the root."),
        "recursive" to boolProp("Walk subdirectories too. Default false."),
    )

    override suspend fun execute(args: JsonObject, context: ToolContext): String {
        val project = context.requireProject()
        val path = args.str("path").orEmpty()
        return if (args.bool("recursive")) {
            val entries = context.workspace.walk(project.dir, path)
            if (entries.isEmpty()) "(empty)" else entries.joinToString("\n")
        } else {
            val nodes = context.workspace.listDirectory(project.dir, path)
            if (nodes.isEmpty()) "(empty directory)"
            else nodes.joinToString("\n") { node ->
                if (node.isDirectory) "${node.name}/" else "${node.name}  (${node.sizeBytes} bytes)"
            }
        }
    }

    override fun summarize(args: JsonObject) = "list_files  ${args.str("path") ?: "/"}"
}

object ReadFileTool : AgentTool {
    override val name = "read_file"
    override val description =
        "Read a text file from the project. Output is prefixed with line numbers. " +
            "Use start_line/end_line for large files."
    override val parameters = schema(
        "path" to stringProp("File path relative to the project root."),
        "start_line" to intProp("First line to return, 1-based. Optional."),
        "end_line" to intProp("Last line to return, inclusive. Optional."),
        required = listOf("path"),
    )

    override suspend fun execute(args: JsonObject, context: ToolContext): String {
        val project = context.requireProject()
        val path = args.requireStr("path")
        val lines = context.workspace.readText(project.dir, path).lines()
        val from = (args.int("start_line", 1) - 1).coerceIn(0, maxOf(lines.size - 1, 0))
        val to = args.int("end_line", lines.size).coerceIn(from + 1, lines.size)
        val width = to.toString().length

        return lines.subList(from, to)
            .mapIndexed { index, line -> "${(from + index + 1).toString().padStart(width)}  $line" }
            .joinToString("\n")
            .ifBlank { "(empty file)" }
    }

    override fun summarize(args: JsonObject) = "read_file  ${args.str("path") ?: "?"}"
}

object WriteFileTool : AgentTool {
    override val name = "write_file"
    override val description =
        "Create a file or replace its entire contents. Parent directories are created " +
            "automatically. Prefer edit_file for small changes to an existing file."
    override val parameters = schema(
        "path" to stringProp("File path relative to the project root."),
        "content" to stringProp("Full file contents."),
        required = listOf("path", "content"),
    )
    override val mutating = true

    override suspend fun execute(args: JsonObject, context: ToolContext): String {
        val project = context.requireProject()
        val path = args.requireStr("path")
        val content = args["content"]?.let { element ->
            (element as? kotlinx.serialization.json.JsonPrimitive)?.content
        } ?: ""
        val file = context.workspace.writeText(project.dir, path, content)
        context.preview.signalReload()
        return "Wrote ${file.length()} bytes to $path (${content.lines().size} lines)"
    }

    override fun summarize(args: JsonObject) = "write_file  ${args.str("path") ?: "?"}"
}

object EditFileTool : AgentTool {
    override val name = "edit_file"
    override val description =
        "Replace an exact substring in a file. old_string must appear exactly once " +
            "unless replace_all is true. Include surrounding context to stay unique."
    override val parameters = schema(
        "path" to stringProp("File path relative to the project root."),
        "old_string" to stringProp("Exact text to find, including indentation."),
        "new_string" to stringProp("Replacement text."),
        "replace_all" to boolProp("Replace every occurrence instead of requiring exactly one."),
        required = listOf("path", "old_string", "new_string"),
    )
    override val mutating = true

    override suspend fun execute(args: JsonObject, context: ToolContext): String {
        val project = context.requireProject()
        val path = args.requireStr("path")
        val oldText = args.requireStr("old_string")
        val newText = args["new_string"]?.let {
            (it as? kotlinx.serialization.json.JsonPrimitive)?.content
        } ?: ""
        val replaceAll = args.bool("replace_all")

        val original = context.workspace.readText(project.dir, path)
        val occurrences = countOccurrences(original, oldText)

        when {
            occurrences == 0 -> return "ERROR: old_string not found in $path. " +
                "Read the file again — whitespace and indentation must match exactly."

            occurrences > 1 && !replaceAll -> return "ERROR: old_string appears $occurrences times in $path. " +
                "Add surrounding context to make it unique, or pass replace_all=true."
        }

        val updated = if (replaceAll) original.replace(oldText, newText)
        else original.replaceFirst(oldText, newText)

        context.workspace.writeText(project.dir, path, updated)
        context.preview.signalReload()
        val changed = if (replaceAll) occurrences else 1
        return "Edited $path ($changed replacement${if (changed == 1) "" else "s"})"
    }

    private fun countOccurrences(haystack: String, needle: String): Int {
        if (needle.isEmpty()) return 0
        var count = 0
        var index = haystack.indexOf(needle)
        while (index >= 0) {
            count++
            index = haystack.indexOf(needle, index + needle.length)
        }
        return count
    }

    override fun summarize(args: JsonObject) = "edit_file  ${args.str("path") ?: "?"}"
}

object DeletePathTool : AgentTool {
    override val name = "delete_path"
    override val description = "Delete a file, or a directory and everything inside it."
    override val parameters = schema(
        "path" to stringProp("File or directory relative to the project root."),
        required = listOf("path"),
    )
    override val mutating = true

    override suspend fun execute(args: JsonObject, context: ToolContext): String {
        val project = context.requireProject()
        val path = args.requireStr("path")
        val deleted = context.workspace.delete(project.dir, path)
        context.preview.signalReload()
        return if (deleted) "Deleted $path" else "ERROR: could not delete $path (does it exist?)"
    }

    override fun summarize(args: JsonObject) = "delete_path  ${args.str("path") ?: "?"}"
}

object CreateDirectoryTool : AgentTool {
    override val name = "create_directory"
    override val description = "Create a directory, including any missing parents."
    override val parameters = schema(
        "path" to stringProp("Directory relative to the project root."),
        required = listOf("path"),
    )
    override val mutating = true

    override suspend fun execute(args: JsonObject, context: ToolContext): String {
        val project = context.requireProject()
        val path = args.requireStr("path")
        return if (context.workspace.createDirectory(project.dir, path)) "Created $path/"
        else "$path/ already exists"
    }

    override fun summarize(args: JsonObject) = "create_directory  ${args.str("path") ?: "?"}"
}

object SearchCodeTool : AgentTool {
    override val name = "search_code"
    override val description =
        "Search file contents with a regular expression. Returns path:line:text matches. " +
            "Use this before reading files to locate code."
    override val parameters = schema(
        "pattern" to stringProp("Regular expression. Falls back to a literal search if invalid."),
        "glob" to stringProp("Optional path filter, for example **/*.js or src/**."),
        required = listOf("pattern"),
    )

    override suspend fun execute(args: JsonObject, context: ToolContext): String {
        val project = context.requireProject()
        val matches = context.workspace.search(
            projectDir = project.dir,
            pattern = args.requireStr("pattern"),
            pathGlob = args.str("glob"),
        )
        return if (matches.isEmpty()) "No matches."
        else matches.joinToString("\n") { "${it.path}:${it.line}: ${it.text}" }
    }

    override fun summarize(args: JsonObject) = "search_code  ${args.str("pattern") ?: "?"}"
}

// ---- Projects -------------------------------------------------------------

object CreateProjectTool : AgentTool {
    override val name = "create_project"
    override val description =
        "Create a new project from a zero-build template and make it the active project. " +
            "Templates: ${'"'}blank${'"'}, ${'"'}static${'"'} (HTML/CSS/JS), ${'"'}tailwind${'"'} (Tailwind CDN), " +
            "${'"'}react${'"'} (React 18 from esm.sh, JSX compiled in-page), ${'"'}vue${'"'} (Vue 3 global build), " +
            "${'"'}landing${'"'} (marketing page). There is no npm on this device, so never " +
            "scaffold anything that needs a build step."
    override val parameters = schema(
        "name" to stringProp("Project directory name."),
        "template" to stringProp("Template id.", enum = Templates.ids),
        required = listOf("name", "template"),
    )
    override val mutating = true

    override suspend fun execute(args: JsonObject, context: ToolContext): String {
        val requested = args.requireStr("template")
        val template = Templates.byId(requested)
            ?: return "ERROR: unknown template '$requested'. Available: ${Templates.ids.joinToString(", ")}"

        val dir = context.workspace.createProjectDir(args.requireStr("name"))
        template.files.forEach { (path, content) ->
            context.workspace.writeText(dir, path, content)
        }
        context.workspace.refresh()
        context.onProjectChanged(dir.absolutePath)

        return "Created project '${dir.name}' from template '${template.id}' with " +
            "${template.files.size} files: ${template.files.keys.joinToString(", ")}. " +
            "Entry point: ${template.entry}. It is now the active project."
    }

    override fun summarize(args: JsonObject) =
        "create_project  ${args.str("name") ?: "?"} (${args.str("template") ?: "?"})"
}

object ProjectInfoTool : AgentTool {
    override val name = "project_info"
    override val description =
        "Describe the active project: name, file count, whether it is a git repo, " +
            "the remote URL, and the top-level layout. Cheap orientation call."
    override val parameters = schema()

    override suspend fun execute(args: JsonObject, context: ToolContext): String {
        val project = context.project ?: return "No project is open."
        val top = context.workspace.listDirectory(project.dir, "")
            .joinToString("\n") { if (it.isDirectory) "  ${it.name}/" else "  ${it.name}" }
        val remote = if (project.isGitRepo) context.git.remoteUrl(project.dir) else null

        return buildString {
            appendLine("name: ${project.name}")
            appendLine("files: ${project.fileCount}")
            appendLine("git repo: ${project.isGitRepo}")
            if (remote != null) appendLine("remote: $remote")
            appendLine("top level:")
            append(top.ifBlank { "  (empty)" })
        }
    }
}

// ---- Git ------------------------------------------------------------------

object GitCloneTool : AgentTool {
    override val name = "git_clone"
    override val description =
        "Clone an HTTPS git repository into a new project, with full history so it can " +
            "commit and push. SSH URLs are not supported. History makes this slow on " +
            "large repos — prefer fetch_repo_snapshot when the user only wants the code."
    override val parameters = schema(
        "url" to stringProp("HTTPS clone URL, for example https://github.com/owner/repo.git"),
        "name" to stringProp("Optional project directory name. Defaults to the repo name."),
        "branch" to stringProp("Optional branch to check out."),
        required = listOf("url"),
    )
    override val mutating = true

    override suspend fun execute(args: JsonObject, context: ToolContext): String {
        val url = args.requireStr("url")
        val fallbackName = url.trimEnd('/').substringAfterLast('/').removeSuffix(".git")
        val dir = context.workspace.createProjectDir(args.str("name") ?: fallbackName)

        val result = runCatching {
            context.git.clone(
                url = url,
                targetDir = dir,
                branch = args.str("branch"),
                credentials = context.settings.effectiveGitCredentials,
            ) { task, percent ->
                context.onProgress(if (percent >= 0) "$task $percent%" else task)
            }
        }.getOrElse { error ->
            dir.deleteRecursively()
            context.workspace.refresh()
            return "ERROR: clone failed — ${error.message}"
        }

        context.workspace.refresh()
        context.onProjectChanged(dir.absolutePath)
        val fileCount = context.workspace.walk(dir, "", limit = 4000).size
        return "$result. Active project is now '${dir.name}' ($fileCount entries)."
    }

    override fun summarize(args: JsonObject) = "git_clone  ${args.str("url") ?: "?"}"
}

object FetchRepoSnapshotTool : AgentTool {
    override val name = "fetch_repo_snapshot"
    override val description =
        "Download a repository as a zip snapshot (github.com, gitlab.com, codeberg.org). " +
            "Much faster and smaller than git_clone because no history is transferred, " +
            "but the result is not a git repo, so it cannot commit or push."
    override val parameters = schema(
        "url" to stringProp("Repository URL, for example https://github.com/owner/repo"),
        "name" to stringProp("Optional project directory name."),
        "branch" to stringProp("Optional branch. Tries main then master when omitted."),
        required = listOf("url"),
    )
    override val mutating = true

    override suspend fun execute(args: JsonObject, context: ToolContext): String {
        val url = args.requireStr("url")
        val target = context.snapshots.parse(url)
        val dir = context.workspace.createProjectDir(args.str("name") ?: target?.repo ?: "repo")

        val result = runCatching {
            context.snapshots.download(
                url = url,
                targetDir = dir,
                branch = args.str("branch"),
                token = context.settings.gitToken.takeIf { it.isNotBlank() }
                    ?: context.settings.githubToken.takeIf { it.isNotBlank() },
            )
        }.getOrElse { error ->
            dir.deleteRecursively()
            context.workspace.refresh()
            return "ERROR: snapshot download failed — ${error.message}"
        }

        context.workspace.refresh()
        context.onProjectChanged(dir.absolutePath)
        return "$result. Active project is now '${dir.name}'."
    }

    override fun summarize(args: JsonObject) = "fetch_repo_snapshot  ${args.str("url") ?: "?"}"
}

object GitStatusTool : AgentTool {
    override val name = "git_status"
    override val description = "Show the current branch and every changed, staged or untracked file."
    override val parameters = schema()

    override suspend fun execute(args: JsonObject, context: ToolContext): String {
        val project = context.requireProject()
        if (!project.isGitRepo) return "Not a git repository. Use git_init first."
        return context.git.status(project.dir).render()
    }
}

object GitInitTool : AgentTool {
    override val name = "git_init"
    override val description = "Initialize a git repository in the active project."
    override val parameters = schema()
    override val mutating = true

    override suspend fun execute(args: JsonObject, context: ToolContext): String {
        val project = context.requireProject()
        val result = context.git.init(project.dir)
        context.workspace.refresh()
        return result
    }
}

object GitCommitTool : AgentTool {
    override val name = "git_commit"
    override val description =
        "Stage every change and create a commit. The author identity comes from Settings."
    override val parameters = schema(
        "message" to stringProp("Commit message. First line should be a short summary."),
        required = listOf("message"),
    )
    override val mutating = true

    override suspend fun execute(args: JsonObject, context: ToolContext): String {
        val project = context.requireProject()
        if (!project.isGitRepo) return "Not a git repository. Use git_init first."
        val staged = context.git.stageAll(project.dir)
        if (staged == 0 && context.git.status(project.dir).clean) return "Nothing to commit."
        return context.git.commit(
            dir = project.dir,
            message = args.requireStr("message"),
            identity = GitIdentity(context.settings.gitUserName, context.settings.gitUserEmail),
        )
    }

    override fun summarize(args: JsonObject) =
        "git_commit  ${args.str("message")?.lineSequence()?.firstOrNull() ?: ""}"
}

object GitDiffTool : AgentTool {
    override val name = "git_diff"
    override val description = "Show a unified diff of unstaged changes, or staged ones when staged=true."
    override val parameters = schema(
        "staged" to boolProp("Diff the index against HEAD instead of the working tree."),
    )

    override suspend fun execute(args: JsonObject, context: ToolContext): String {
        val project = context.requireProject()
        if (!project.isGitRepo) return "Not a git repository."
        return context.git.diff(project.dir, staged = args.bool("staged")).take(20_000)
    }
}

object GitLogTool : AgentTool {
    override val name = "git_log"
    override val description = "List recent commits, newest first."
    override val parameters = schema(
        "limit" to intProp("How many commits to return. Default 20."),
    )

    override suspend fun execute(args: JsonObject, context: ToolContext): String {
        val project = context.requireProject()
        if (!project.isGitRepo) return "Not a git repository."
        val commits = context.git.log(project.dir, args.int("limit", 20))
        return if (commits.isEmpty()) "No commits yet."
        else commits.joinToString("\n") { "${it.id.take(7)}  ${it.shortMessage}  (${it.author})" }
    }
}

object GitPushTool : AgentTool {
    override val name = "git_push"
    override val description =
        "Push the current branch to origin. Requires a git username and access token in Settings."
    override val parameters = schema(
        "branch" to stringProp("Optional branch name. Defaults to the current branch."),
    )
    override val mutating = true

    override suspend fun execute(args: JsonObject, context: ToolContext): String {
        val project = context.requireProject()
        val credentials = context.settings.effectiveGitCredentials
            ?: return "ERROR: no git token configured. Add one in Settings — or sign in to " +
                "GitHub from the Hub tab — then push again."
        return context.git.push(project.dir, credentials, args.str("branch"))
    }
}

object GitPullTool : AgentTool {
    override val name = "git_pull"
    override val description = "Fetch and merge the tracked upstream branch."
    override val parameters = schema()
    override val mutating = true

    override suspend fun execute(args: JsonObject, context: ToolContext): String {
        val project = context.requireProject()
        return context.git.pull(project.dir, context.settings.effectiveGitCredentials)
    }
}

// ---- Preview --------------------------------------------------------------

object PreviewTool : AgentTool {
    override val name = "preview"
    override val description =
        "Serve the project over http://127.0.0.1 and open it in the Preview tab. " +
            "Required for anything using ES modules, fetch or a CDN import map — those " +
            "do not work from a file:// URL. Call this after creating or editing a site."
    override val parameters = schema(
        "entry" to stringProp("HTML file to open. Defaults to index.html."),
    )

    override suspend fun execute(args: JsonObject, context: ToolContext): String {
        val project = context.requireProject()
        val entry = args.str("entry") ?: "index.html"
        val url = context.preview.start(project.dir, entry)
        return "Preview running at $url. The Preview tab reloads automatically after each edit."
    }

    override fun summarize(args: JsonObject) = "preview  ${args.str("entry") ?: "index.html"}"
}

// ---- Terminal / build ------------------------------------------------------

object RunCommandTool : AgentTool {
    override val name = "run_command"
    override val description =
        "Run a shell command inside the active project directory (Android toybox sh). " +
            "Read-only commands like ls/cat/grep/find/wc run immediately; anything that " +
            "writes, installs or is unknown asks the user first; destructive commands and " +
            "access outside the project are blocked outright. There is no npm/node/python/" +
            "jdk/cargo/go on this device — those fail with 'not found'."
    override val parameters = schema(
        "command" to stringProp("Shell command to run, relative to the project root."),
        "timeout_seconds" to intProp(
            "Kill the process after this many seconds (1-1800). Default: the user's setting.",
        ),
        required = listOf("command"),
    )

    override fun needsApproval(
        args: JsonObject,
        settings: dev.opencode.mobile.core.settings.AppSettings,
    ): Boolean {
        val command = args.str("command") ?: return false
        // SAFE runs silently; BLOCK is refused outright in execute(); only ASK
        // goes through the approval gate.
        val decision = CommandPolicy.classify(command).decision
        return decision == PolicyDecision.ASK && !settings.autoApproveCommands
    }

    override suspend fun execute(args: JsonObject, context: ToolContext): String {
        val project = context.requireProject()
        val command = args.requireStr("command")

        val verdict = CommandPolicy.classify(command)
        if (verdict.decision == PolicyDecision.BLOCK) {
            return "ERROR: command blocked by policy — ${verdict.reason}. " +
                "Do not attempt variations of this; propose a safe alternative instead."
        }

        val timeoutSeconds = args.int("timeout_seconds", context.settings.commandTimeoutSeconds)
            .coerceIn(1, 1800)

        return try {
            val finished = context.terminal.await(
                context.terminal.start(
                    command = command,
                    projectDir = project.dir,
                    projectName = project.name,
                    origin = ORIGIN_AGENT,
                    timeoutSeconds = timeoutSeconds,
                ),
            )
            finished.summarize()
        } catch (error: IllegalStateException) {
            "ERROR: ${error.message}"
        }
    }

    override fun summarize(args: JsonObject) =
        "run_command  ${args.str("command").orEmpty().take(60)}"
}

object BuildProjectTool : AgentTool {
    override val name = "build_project"
    override val description =
        "Detect the project type (Gradle/Android, Maven, Next.js, Vite, React, Node, " +
            "Python, Rust, Go, static web) and run one action against it. Returns structured " +
            "results with file:line diagnostics — read them and fix the listed files. " +
            "Start with action=detect if you don't know the layout."
    override val parameters = schema(
        "action" to stringProp(
            "Which lifecycle step to run.",
            enum = listOf("detect", "install", "build", "test", "run", "clean"),
        ),
        "timeout_seconds" to intProp("Kill the process after this many seconds (1-1800)."),
        required = listOf("action"),
    )

    override fun needsApproval(
        args: JsonObject,
        settings: dev.opencode.mobile.core.settings.AppSettings,
    ): Boolean {
        val action = args.str("action")?.lowercase().orEmpty()
        return action != "detect" && !settings.autoApproveCommands
    }

    override suspend fun execute(args: JsonObject, context: ToolContext): String {
        val project = context.requireProject()
        val rawAction = args.str("action")?.lowercase().orEmpty()
        val action = BuildAction.entries.firstOrNull { it.name.lowercase() == rawAction }
            ?: return "ERROR: unknown action '$rawAction'. Use one of: detect, install, build, test, run, clean."

        val timeoutSeconds = args.int("timeout_seconds", context.settings.commandTimeoutSeconds)
            .coerceIn(1, 1800)

        return context.builds.perform(
            terminal = context.terminal,
            projectDir = project.dir,
            projectName = project.name,
            origin = ORIGIN_AGENT,
            action = action,
            timeoutSeconds = timeoutSeconds,
        ).render()
    }

    override fun summarize(args: JsonObject) = "build_project  ${args.str("action") ?: "?"}"
}

private const val ORIGIN_AGENT = "agent"

// ---- Dev server (feature 5) ------------------------------------------------

/** Shared rendering of dev-server state for the tool results. */
private fun renderDevServer(state: DevServerManager.State): String = buildString {
    appendLine("dev server: ${state.status.name.lowercase()} · ${state.kind.display}")
    state.url?.let { appendLine("url: $it  (open the Preview tab to see it)") }
    state.error?.let { appendLine("error: $it") }
    if (state.note.isNotBlank() && state.url == null && state.error == null) {
        appendLine(state.note)
    }
    if (state.status == DevServerManager.Status.STARTING ||
        state.status == DevServerManager.Status.INSTALLING
    ) {
        appendLine("(still ${state.status.name.lowercase()} — call dev_server_status again in a few seconds)")
    }
    val tail = state.lines.takeLast(12)
    if (tail.isNotEmpty()) {
        appendLine()
        appendLine("recent output:")
        tail.forEach { append(if (it.isError) "! " else "  ").appendLine(it.text.take(200)) }
    }
}.trimEnd()

object DevServerStartTool : AgentTool {
    override val name = "dev_server_start"
    override val description =
        "Start a Node dev server (Vite / Next.js / React / Node) for the active project and " +
            "point the Preview tab at it. Detects the project type, installs dependencies " +
            "(unless install=false), launches the dev command, and sniffs the port it prints. " +
            "Requires a Node runtime on the device — without one this returns no_runtime and " +
            "you should use the `preview` tool instead (which works for static, zero-build " +
            "sites). The server keeps running until dev_server_stop."
    override val parameters = schema(
        "install" to boolProp("Run npm install first. Default true; set false when dependencies are already present."),
    )

    // Starts processes (npm install + a dev server), so gate it like a command.
    override fun needsApproval(
        args: JsonObject,
        settings: dev.opencode.mobile.core.settings.AppSettings,
    ): Boolean = !settings.autoApproveCommands

    override suspend fun execute(args: JsonObject, context: ToolContext): String {
        val project = context.requireProject()
        context.devServer.start(project, install = args.bool("install", true))
        return renderDevServer(context.devServer.awaitSettled())
    }

    override fun summarize(args: JsonObject) = "dev_server_start"
}

object DevServerStopTool : AgentTool {
    override val name = "dev_server_stop"
    override val description = "Stop the running Node dev server for the active project."
    override val parameters = schema()

    override suspend fun execute(args: JsonObject, context: ToolContext): String {
        context.devServer.stop()
        return "Dev server stopped."
    }

    override fun summarize(args: JsonObject) = "dev_server_stop"
}

object DevServerStatusTool : AgentTool {
    override val name = "dev_server_status"
    override val description =
        "Report the Node dev server's state for the active project: status, detected type, " +
            "the URL it is serving on, any error, and recent output lines. Read-only."
    override val parameters = schema()

    override suspend fun execute(args: JsonObject, context: ToolContext): String =
        renderDevServer(context.devServer.state.value)

    override fun summarize(args: JsonObject) = "dev_server_status"
}

// ---- Registry -------------------------------------------------------------

object ToolRegistry {
    val tools: List<AgentTool> = listOf(
        ProjectInfoTool,
        ListFilesTool,
        ReadFileTool,
        SearchCodeTool,
        WriteFileTool,
        EditFileTool,
        CreateDirectoryTool,
        DeletePathTool,
        CreateProjectTool,
        GitCloneTool,
        FetchRepoSnapshotTool,
        GitInitTool,
        GitStatusTool,
        GitDiffTool,
        GitLogTool,
        GitCommitTool,
        GitPushTool,
        GitPullTool,
        PreviewTool,
        RunCommandTool,
        BuildProjectTool,
        DevServerStartTool,
        DevServerStopTool,
        DevServerStatusTool,
        // GitHub integration (feature 8)
        GithubAuthTool,
        GithubReposTool,
        GithubRepoInfoTool,
        GithubCreateRepoTool,
        GithubBranchesTool,
        GithubCommitsTool,
        GithubIssuesTool,
        GithubGetIssueTool,
        GithubCreateIssueTool,
        GithubCommentTool,
        GithubPullsTool,
        GithubGetPullTool,
        GithubCreatePullTool,
        GithubActionsTool,
        // Built-in skills (use_skill stays reachable in Chat Only mode too)
        UseSkillTool,
    )

    private val byName = tools.associateBy { it.name }

    fun find(name: String): AgentTool? = byName[name]

    fun specs() = tools.map { it.toSpec() }
}
