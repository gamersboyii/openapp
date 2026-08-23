package dev.opencode.mobile.core.exec

/**
 * What the user is asked before a command runs.
 *
 * SAFE  — read-only, project-local; runs without prompting.
 * ASK   — mutates something, installs, or is unknown; needs explicit approval
 *         (unless auto-approve commands is on).
 * BLOCK — refused outright, never shown for approval.
 */
enum class PolicyDecision { SAFE, ASK, BLOCK }

data class PolicyVerdict(val decision: PolicyDecision, val reason: String)

/**
 * Classifies a shell command before it is allowed anywhere near a process.
 *
 * The classifier is deliberately conservative: anything it cannot recognise
 * lands on ASK, never on SAFE. It is a convenience layer on top of the real
 * Android sandbox (a child process inherits this app's UID and can only touch
 * what the app can touch) — but it stops the model from even attempting
 * destructive or off-project work without a human in the loop.
 *
 * Pipes and `&&` chains are classified segment by segment; the strongest
 * verdict wins.
 */
object CommandPolicy {

    /** Read-only commands that never need a prompt when run inside the project. */
    private val SAFE_FIRST_TOKENS = setOf(
        "ls", "cat", "head", "tail", "wc", "grep", "pwd", "echo", "date",
        "whoami", "uname", "du", "df", "stat", "which", "printenv",
        "basename", "dirname", "sort", "uniq", "cut", "tr", "diff",
        "getprop",
    )

    /** Read-only git subcommands; every other git subcommand lands on ASK. */
    private val SAFE_GIT_SUBCOMMANDS = setOf(
        "status", "log", "diff", "show", "branch", "tag", "remote", "rev-parse",
        "ls-files", "describe",
    )

    /** find(1) arguments that make it execute or delete things. */
    private val FIND_UNSAFE_ARGS = setOf("-exec", "-execdir", "-ok", "-okdir", "-delete", "-fprintf")

    private val BLOCK_PATTERNS = listOf(
        Regex("""\brm\s+(-[a-zA-Z]*r[a-zA-Z]*f|-[a-zA-Z]*f[a-zA-Z]*r)\b""") to "recursive force delete (rm -rf) is blocked",
        Regex("""(^|\s)rm\s+-[a-zA-Z]*\s+(/|[~])""") to "rm against an absolute path or home is blocked",
        Regex("""\b(mkfs(\.\w+)?|fdisk|parted|badblocks)\b""") to "disk-level tooling is blocked",
        Regex("""\bdd\b\s+.*\bof=/dev/(block|mmcblk|sd)""") to "raw device writes are blocked",
        Regex("""\b(shutdown|reboot|halt|poweroff)\b""") to "system power control is blocked",
        Regex("""\bsudo\b|\bsu\b(\s|$)""") to "privilege escalation is blocked",
        Regex("""\b(pm|am)\s+(install|grant|revoke|force-stop)\b""") to "Android package/activity manager mutations are blocked",
        Regex("""\bchmod\s+(-R\s+)?777\b""") to "chmod 777 is blocked",
        Regex(""":\(\)\s*\{.*\};:""") to "fork bomb pattern is blocked",
        Regex("""(curl|wget)\b[^|]*\|\s*(sh|bash|zsh)\b""") to "piping downloads straight into a shell is blocked",
        Regex("""\b(mv|cp)\s+\S*\s*/system\b""") to "writes into /system are blocked",
        Regex("""\b>\s*/system\b|\btee\s+/system\b""") to "writes into /system are blocked",
        Regex("""\bkill(all)?\b\s+-9\s+(\d+$|\$\!)""") to "targeted kill -9 from the model is blocked",
    )

    /**
     * Absolute prefixes a command may reference. Everything else outside the
     * project directory is refused: other apps' data, /sdcard, arbitrary
     * binaries, and so on.
     */
    private val ALLOWED_ABSOLUTE_PREFIXES = listOf(
        "/dev/null", "/dev/stderr", "/dev/stdout", "/dev/tty",
        "/proc", "/sys", "/system", "/apex", "/product", "/vendor", "/odm",
        "/data/local/tmp",
    )

    fun classify(command: String): PolicyVerdict {
        val trimmed = command.trim()
        if (trimmed.isEmpty()) return PolicyVerdict(PolicyDecision.BLOCK, "empty command")

        BLOCK_PATTERNS.firstOrNull { it.containsMatchIn(trimmed) }?.let { (pattern, why) ->
            return PolicyVerdict(PolicyDecision.BLOCK, why)
        }

        val segments = splitSegments(trimmed)
        var worst = PolicyDecision.SAFE
        var reason = "read-only command"

        for (segment in segments) {
            val verdict = classifySegment(segment)
            if (strength(verdict.decision) > strength(worst)) {
                worst = verdict.decision
                reason = verdict.reason
            }
            if (worst == PolicyDecision.BLOCK) break
        }
        return PolicyVerdict(worst, reason)
    }

    private fun classifySegment(segment: String): PolicyVerdict {
        val tokens = segment.trim().split(WhitespaceRegex)
            .filter { it.isNotBlank() && it != "&" }
        if (tokens.isEmpty()) return PolicyVerdict(PolicyDecision.SAFE, "empty")

        // Any absolute path that is not on the allowlist refuses the whole segment.
        for (token in tokens) {
            val path = token.substringBeforeLast('=')
                .removePrefix("1>").removePrefix("2>")
                .removeSuffix(">")
            if (path.startsWith("/")) {
                val allowed = ALLOWED_ABSOLUTE_PREFIXES.any { path == it || path.startsWith("$it/") }
                if (!allowed) {
                    return PolicyVerdict(
                        PolicyDecision.BLOCK,
                        "access to '$path' is outside the approved directories",
                    )
                }
            }
            if (path.startsWith("~")) {
                return PolicyVerdict(PolicyDecision.BLOCK, "'~' paths are not part of the project")
            }
        }

        // Redirecting output creates or overwrites files → at least ask.
        if (REDIRECT_REGEX.containsMatchIn(segment)) {
            return PolicyVerdict(PolicyDecision.ASK, "command redirects output")
        }

        // find is read-only until it isn't: -exec/-delete change that.
        val first = tokens.first().substringAfterLast('/')
        if (first == "find" && tokens.any { it.lowercase() in FIND_UNSAFE_ARGS }) {
            return PolicyVerdict(PolicyDecision.ASK, "find with -exec/-delete can modify the project")
        }

        if (first == "git" && tokens.size > 1) {
            val sub = tokens[1]
            val safeGit = sub in SAFE_GIT_SUBCOMMANDS ||
                (sub == "config" && tokens.getOrNull(2) == "--get")
            if (!safeGit) {
                return PolicyVerdict(PolicyDecision.ASK, "'git $sub' can change repository state")
            }
            return PolicyVerdict(PolicyDecision.SAFE, "read-only git query")
        }

        return if (first in SAFE_FIRST_TOKENS || first == "find") {
            PolicyVerdict(PolicyDecision.SAFE, "read-only command")
        } else {
            PolicyVerdict(PolicyDecision.ASK, "'$first' is not on the read-only allowlist")
        }
    }

    private fun splitSegments(command: String): List<String> =
        command.split(SegmentSplitRegex).map { it.trim() }.filter { it.isNotBlank() }

    private fun strength(decision: PolicyDecision): Int = when (decision) {
        PolicyDecision.SAFE -> 0
        PolicyDecision.ASK -> 1
        PolicyDecision.BLOCK -> 2
    }

    /** `&&` must win over bare `&` so chains split into real segments. */
    private val SegmentSplitRegex = Regex("""&&|\|\||;|\||&""")
    private val WhitespaceRegex = Regex("""\s+""")
    private val REDIRECT_REGEX = Regex("""\d?>>|<<|\d?>""")
}
