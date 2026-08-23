package dev.opencode.mobile.core.build

import dev.opencode.mobile.core.exec.CommandRun
import dev.opencode.mobile.core.exec.RunState
import dev.opencode.mobile.core.exec.TerminalService
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

enum class BuildAction(val label: String) {
    DETECT("detect"),
    INSTALL("install"),
    BUILD("build"),
    TEST("test"),
    RUN("run"),
    CLEAN("clean"),
}

enum class ProjectKind(val display: String) {
    GRADLE_ANDROID("Android (Gradle)"),
    GRADLE_JVM("Java/Kotlin (Gradle)"),
    MAVEN("Java (Maven)"),
    NODE_NEXT("Next.js"),
    NODE_VITE("Vite"),
    NODE_REACT("React"),
    NODE_GENERIC("Node.js"),
    PYTHON("Python"),
    RUST("Rust"),
    GO("Go"),
    STATIC_WEB("Static web (zero-build)"),
    UNKNOWN("Unknown"),
}

/** One structured error/warning extracted from raw build output. */
data class Diagnostic(
    val file: String,
    val line: Int,
    val column: Int?,
    val severity: String,
    val message: String,
)

/**
 * Structured result of a build/test/run/clean attempt — the agent reads
 * [diagnostics] and [message] instead of parsing raw terminal output.
 */
data class BuildOutcome(
    val action: BuildAction,
    val kind: ProjectKind,
    val success: Boolean,
    /** False when there was genuinely nothing to execute (no-op / not applicable). */
    val executed: Boolean,
    val command: String = "",
    val exitCode: Int? = null,
    val durationMs: Long = 0,
    val message: String = "",
    val diagnostics: List<Diagnostic> = emptyList(),
) {
    fun render(): String = buildString {
        appendLine("${action.label} · ${kind.display}")
        if (!executed) {
            append(message.ifBlank { "Nothing to do." })
            return@buildString
        }
        appendLine("$ $command")
        if (diagnostics.isNotEmpty()) {
            appendLine()
            appendLine("[errors]")
            diagnostics.take(MAX_RENDERED_DIAGNOSTICS).forEach { d ->
                val col = d.column?.let { ":$it" } ?: ""
                appendLine("${d.file}:${d.line}$col  ${d.severity}: ${d.message.take(300)}")
            }
            if (diagnostics.size > MAX_RENDERED_DIAGNOSTICS) {
                appendLine("… and ${diagnostics.size - MAX_RENDERED_DIAGNOSTICS} more")
            }
        }
        appendLine()
        appendLine()
        append("Exit code: ${exitCode ?: "-"}")
        append(" · Duration: %.1fs".format(durationMs / 1000.0))
        if (message.isNotBlank()) {
            appendLine()
            append(message)
        }
    }

    private companion object {
        const val MAX_RENDERED_DIAGNOSTICS = 20
    }
}

/**
 * Generic build abstraction over the sandboxed terminal.
 *
 * Detection is marker-file based. Recipes are plain shell commands executed
 * through [TerminalService], so they inherit its project-directory pinning,
 * timeouts, output caps and cancellation. On a stock phone most toolchains
 * (JDK, Node, Python, cargo, go) are absent; those runs end with an honest
 * failure instead of pretending to build.
 *
 * Approval is NOT handled here — callers gate non-detect actions themselves
 * (`build_project` prompts unless auto-approve commands is on).
 */
class BuildSystem {

    // ---- detection ---------------------------------------------------------

    fun detect(projectDir: File): ProjectKind {
        if (!projectDir.isDirectory) return ProjectKind.UNKNOWN

        val gradleFiles = listOf(
            "build.gradle.kts", "build.gradle", "settings.gradle.kts", "settings.gradle",
        ).map { File(projectDir, it) }.filter { it.isFile }

        if (gradleFiles.isNotEmpty() || File(projectDir, "gradlew").isFile) {
            val mentionsAndroid = gradleFiles.any { file ->
                runCatching { file.readText().contains("com.android") }.getOrDefault(false)
            } || File(projectDir, "app/build.gradle").isFile ||
                File(projectDir, "app/build.gradle.kts").isFile
            return if (mentionsAndroid) ProjectKind.GRADLE_ANDROID else ProjectKind.GRADLE_JVM
        }

        if (File(projectDir, "pom.xml").isFile) return ProjectKind.MAVEN

        val packageJson = File(projectDir, "package.json")
        if (packageJson.isFile) {
            val deps = packageJsonDependencies(packageJson)
            return when {
                "next" in deps -> ProjectKind.NODE_NEXT
                "vite" in deps -> ProjectKind.NODE_VITE
                "react" in deps || "react-native" in deps -> ProjectKind.NODE_REACT
                else -> ProjectKind.NODE_GENERIC
            }
        }

        if (File(projectDir, "pyproject.toml").isFile ||
            File(projectDir, "requirements.txt").isFile ||
            File(projectDir, "setup.py").isFile ||
            File(projectDir, "main.py").isFile
        ) {
            return ProjectKind.PYTHON
        }

        if (File(projectDir, "Cargo.toml").isFile) return ProjectKind.RUST
        if (File(projectDir, "go.mod").isFile) return ProjectKind.GO
        if (File(projectDir, "index.html").isFile) return ProjectKind.STATIC_WEB

        return ProjectKind.UNKNOWN
    }

    private fun packageJsonDependencies(file: File): Set<String> = runCatching {
        val obj = Json.parseToJsonElement(file.readText()).jsonObject
        (obj["dependencies"]?.jsonObject?.keys.orEmpty() +
            obj["devDependencies"]?.jsonObject?.keys.orEmpty()).toSet()
    }.getOrDefault(emptySet())

    // ---- recipes -----------------------------------------------------------

    private data class Recipe(
        val install: String?,
        val build: String?,
        val test: String?,
        val run: String?,
        val clean: String?,
        val notes: Map<BuildAction, String> = emptyMap(),
    )

    private fun recipeFor(kind: ProjectKind): Recipe = when (kind) {
        ProjectKind.GRADLE_ANDROID -> Recipe(
            install = null,
            build = "./gradlew assembleDebug --console=plain -q || gradle assembleDebug --console=plain -q",
            test = "./gradlew testDebugUnitTest --console=plain -q || gradle test --console=plain -q",
            run = null,
            clean = "./gradlew clean --console=plain -q",
            notes = mapOf(
                BuildAction.INSTALL to "Gradle resolves dependencies during build; there is no separate install step.",
                BuildAction.RUN to "APKs cannot be launched on-device. Build, then export or push the artifact.",
            ),
        )

        ProjectKind.GRADLE_JVM -> Recipe(
            install = null,
            build = "./gradlew build --console=plain -q || gradle build --console=plain -q",
            test = "./gradlew test --console=plain -q",
            run = null,
            clean = "./gradlew clean --console=plain -q",
            notes = mapOf(BuildAction.RUN to "No main class is known; run the produced jar manually if needed."),
        )

        ProjectKind.MAVEN -> Recipe(
            install = null,
            build = "mvn -q -DskipTests package",
            test = "mvn -q test",
            run = null,
            clean = "mvn -q clean",
        )

        ProjectKind.NODE_NEXT -> Recipe(
            install = "npm install",
            build = "npm run build",
            test = "npm test",
            run = "npm run dev",
            clean = "npm run clean --if-present",
            notes = mapOf(BuildAction.RUN to "Dev servers are long-running; stop them from the process list."),
        )

        ProjectKind.NODE_VITE -> Recipe(
            install = "npm install",
            build = "npm run build",
            test = "npm test",
            run = "npm run dev",
            clean = "npm run clean --if-present",
            notes = mapOf(
                BuildAction.RUN to "Dev servers are long-running; stop them from the process list.",
            ),
        )

        ProjectKind.NODE_REACT -> Recipe(
            install = "npm install",
            build = "npm run build",
            test = "npm test",
            run = "npm start",
            clean = "npm run clean --if-present",
        )

        ProjectKind.NODE_GENERIC -> Recipe(
            install = "npm install",
            build = "npm run build --if-present",
            test = "npm test --if-present",
            run = "npm start --if-present",
            clean = "npm run clean --if-present",
        )

        ProjectKind.PYTHON -> Recipe(
            install = "pip install -r requirements.txt",
            build = null,
            test = "python -m pytest -q",
            run = "python main.py",
            clean = null,
            notes = mapOf(BuildAction.BUILD to "Python projects need no compile step."),
        )

        ProjectKind.RUST -> Recipe(
            install = "cargo fetch",
            build = "cargo build",
            test = "cargo test",
            run = "cargo run",
            clean = "cargo clean",
        )

        ProjectKind.GO -> Recipe(
            install = "go mod download",
            build = "go build ./...",
            test = "go test ./...",
            run = "go run .",
            clean = "go clean ./...",
        )

        ProjectKind.STATIC_WEB -> Recipe(
            install = null,
            build = null,
            test = null,
            run = null,
            clean = null,
            notes = mapOf(
                BuildAction.INSTALL to "Zero-build project: dependencies come from CDNs, nothing to install.",
                BuildAction.BUILD to "Zero-build project: nothing to compile. Use the preview server instead.",
                BuildAction.TEST to "No test runner configured for static sites.",
                BuildAction.RUN to "Start the preview server with the `preview` tool instead.",
                BuildAction.CLEAN to "Nothing cached to clean.",
            ),
        )

        ProjectKind.UNKNOWN -> Recipe(
            install = null,
            build = null,
            test = null,
            run = null,
            clean = null,
            notes = mapOf(
                BuildAction.INSTALL to "Project type could not be detected. Inspect files with list_files first.",
                BuildAction.BUILD to "Project type could not be detected. Inspect files with list_files first.",
                BuildAction.TEST to "Project type could not be detected. Inspect files with list_files first.",
                BuildAction.RUN to "Project type could not be detected. Inspect files with list_files first.",
                BuildAction.CLEAN to "Project type could not be detected. Inspect files with list_files first.",
            ),
        )
    }

    private fun commandFor(recipe: Recipe, action: BuildAction): String? = when (action) {
        BuildAction.DETECT -> null
        BuildAction.INSTALL -> recipe.install
        BuildAction.BUILD -> recipe.build
        BuildAction.TEST -> recipe.test
        BuildAction.RUN -> recipe.run
        BuildAction.CLEAN -> recipe.clean
    }

    // ---- execution ---------------------------------------------------------

    /**
     * Runs one action against [projectDir]. DETECT never executes anything;
     * unsupported actions return `executed=false` with an explanatory note.
     */
    suspend fun perform(
        terminal: TerminalService,
        projectDir: File,
        projectName: String,
        origin: String,
        action: BuildAction,
        timeoutSeconds: Int,
    ): BuildOutcome {
        val kind = detect(projectDir)

        if (action == BuildAction.DETECT) {
            return BuildOutcome(
                action = action,
                kind = kind,
                success = true,
                executed = false,
                message = "Detected ${kind.display}.",
            )
        }

        val recipe = recipeFor(kind)
        val note = recipe.notes[action]
        val command = commandFor(recipe, action)

        if (command == null) {
            return BuildOutcome(
                action = action,
                kind = kind,
                success = false,
                executed = false,
                message = note ?: "${action.label} is not supported for ${kind.display}.",
            )
        }

        val finished = terminal.await(
            terminal.start(command, projectDir, projectName, origin, timeoutSeconds),
        )
        val diagnostics = Diagnostics.parse(finished.stdout + "\n" + finished.stderr)

        return BuildOutcome(
            action = action,
            kind = kind,
            success = finished.state == RunState.FINISHED && finished.exitCode == 0 &&
                diagnostics.none { it.severity == "error" },
            executed = true,
            command = command,
            exitCode = finished.exitCode,
            durationMs = finished.durationMs,
            message = summarizeFinish(action, timeoutSeconds, finished, diagnostics),
            diagnostics = diagnostics,
        )
    }

    private fun summarizeFinish(
        action: BuildAction,
        timeoutSeconds: Int,
        finished: CommandRun,
        diagnostics: List<Diagnostic>,
    ): String = when (finished.state) {
        RunState.TIMED_OUT -> "Timed out after ${timeoutSeconds}s."
        RunState.KILLED -> "Stopped before finishing."
        RunState.FAILED_TO_START ->
            missingToolchainHint(finished.stderr) ?: "Could not start the command."
        else ->
            if (finished.exitCode == 0 && diagnostics.none { it.severity == "error" }) {
                "${action.label.replaceFirstChar { it.uppercaseChar() }} succeeded in %.1fs"
                    .format(finished.durationMs / 1000.0)
            } else {
                "${action.label} failed${" with ${diagnostics.size} diagnostic(s)".takeIf { diagnostics.isNotEmpty() } ?: ""}."
            }
    }

    /** Turns `sh: foo: not found` into something the agent can act on. */
    private fun missingToolchainHint(stderr: String): String? {
        val missing = Regex("""(\w[\w.-]*):?\s*(?:not found|Permission denied)""", RegexOption.IGNORE_CASE)
            .find(stderr)?.groupValues?.get(1)?.lowercase()?.substringAfterLast('/')
        return when (missing) {
            "npm", "node", "npx" -> "Node.js/npm is not installed on this device, so this step cannot run here."
            "mvn", "java", "javac", "gradle", "gradlew" ->
                "A JDK/Gradle is required but not installed on this device."
            "python", "python3", "pip" -> "Python is not installed on this device."
            "cargo", "rustc" -> "The Rust toolchain is not installed on this device."
            "go" -> "The Go toolchain is not installed on this device."
            else -> stderr.lineSequence().firstOrNull()?.take(200)?.ifBlank { null }
        }
    }
}

// ---- output parsing --------------------------------------------------------

/**
 * Extracts structured diagnostics from compiler/test output. Regex-based on
 * purpose: every toolchain has its own line format and a handful of patterns
 * covers Kotlin, javac, tsc/eslint, Rust and pytest without real parsers.
 */
object Diagnostics {

    private enum class Format { KOTLIN, TSC, RUST_LOCATION, JAVAC, FLAKE8, GCC, PY_TRACEBACK }

    private data class Pattern(val format: Format, val regex: Regex)

    // Order matters: specific formats first, generic last.
    private val patterns = listOf(
        // e: file.kt:12:34 message   (Kotlin compiler via Gradle)
        Pattern(Format.KOTLIN, Regex("""^e:\s+(\S+?):(\d+):(\d+)\s+(.*)$""")),
        // src/App.tsx(42,10): error TS2339: ...      (tsc / eslint stylish)
        Pattern(Format.TSC, Regex("""^(.+?)\((\d+),(\d+)\):\s+(error|warning)[^:]*:?\s*(.*)$""")),
        //   --> src/main.rs:12:34                    (Rust)
        Pattern(Format.RUST_LOCATION, Regex("""^\s*-->\s+(\S+?):(\d+):(\d+)\s*$""")),
        // src/Main.java:12: error: message           (javac)
        Pattern(Format.JAVAC, Regex("""^(\S+?):(\d+):\s+(error|warning):\s*(.*)$""")),
        // src/x.py:12:5: E501 line too long          (flake8-ish)
        Pattern(Format.FLAKE8, Regex("""^(\S+?):(\d+):(\d+):\s+([A-Z]\w*.*)$""")),
        // path/file.c:12:3: fatal error: message     (gcc/clang generic)
        Pattern(Format.GCC, Regex("""^(\S+?):(\d+):(\d+):\s+(fatal error|error|warning):\s*(.*)$""")),
        // File "src/app.py", line 12                 (Python traceback)
        Pattern(Format.PY_TRACEBACK, Regex("""^\s*File\s+"(.+?)",\s+line\s+(\d+)""")),
    )

    fun parse(output: String): List<Diagnostic> {
        val lines = output.lines()
        val seen = HashSet<String>()
        val found = ArrayList<Diagnostic>()

        lines.forEachIndexed { index, line ->
            if (found.size >= MAX_DIAGNOSTICS) return
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.length > 500) return@forEachIndexed

            for (pattern in patterns) {
                val match = pattern.regex.find(trimmed) ?: continue
                val g = match.groupValues

                val diagnostic = when (pattern.format) {
                    Format.KOTLIN -> Diagnostic(g[1], g[2].toIntOrNull() ?: 0, g[3].toIntOrNull(), "error", g[4])

                    Format.TSC -> Diagnostic(
                        g[1], g[2].toIntOrNull() ?: 0, g[3].toIntOrNull(),
                        g[4].ifBlank { "error" }, g[5],
                    )

                    Format.RUST_LOCATION -> Diagnostic(
                        g[1], g[2].toIntOrNull() ?: 0, g[3].toIntOrNull(),
                        "error", lines.getOrNull(index - 1)?.trim().orEmpty().ifBlank { "compile error" },
                    )

                    Format.JAVAC -> Diagnostic(g[1], g[2].toIntOrNull() ?: 0, null, g[3], g[4])

                    Format.FLAKE8 -> Diagnostic(g[1], g[2].toIntOrNull() ?: 0, g[3].toIntOrNull(), "error", g[4])

                    Format.GCC -> Diagnostic(g[1], g[2].toIntOrNull() ?: 0, g[3].toIntOrNull(), g[4], g[5])

                    Format.PY_TRACEBACK -> {
                        // The exception line sits below the frame; scan a few lines ahead.
                        val message = ((index + 1)..minOf(index + 6, lines.size - 1))
                            .asSequence()
                            .mapNotNull { lines.getOrNull(it)?.trim() }
                            .firstOrNull { it.contains("Error") }
                            ?: "exception"
                        Diagnostic(g[1], g[2].toIntOrNull() ?: 0, null, "error", message)
                    }
                }

                val key = "${diagnostic.file}:${diagnostic.line}:${diagnostic.message}"
                if (key !in seen && diagnostic.file.isNotBlank() && diagnostic.line > 0) {
                    seen += key
                    found += diagnostic
                }
                break
            }
        }
        return found
    }

    private const val MAX_DIAGNOSTICS = 30
}
