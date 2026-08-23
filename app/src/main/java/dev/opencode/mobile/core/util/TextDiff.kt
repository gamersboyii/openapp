package dev.opencode.mobile.core.util

import kotlin.math.max

/**
 * Line-based diff used by the change-review and checkpoint screens.
 *
 * A full LCS table is O(n*m) in memory, which a phone cannot afford for large
 * files. Two defences keep it bounded: common prefix/suffix are trimmed first
 * (most edits touch a small region, so the middle stays tiny even for big
 * files), and if the remaining middle is still too large the diff degrades to a
 * coarse "everything replaced" result instead of allocating a huge table.
 */
object TextDiff {

    enum class Kind { CONTEXT, ADD, REMOVE }

    /** One line of the diff. [oldLine]/[newLine] are 1-based, or -1 when absent. */
    data class Row(val kind: Kind, val text: String, val oldLine: Int, val newLine: Int)

    data class Hunk(val header: String, val rows: List<Row>)

    private const val MAX_MIDDLE_PRODUCT = 2_000_000 // n*m ceiling for the DP table
    private const val MAX_TOTAL_LINES = 20_000

    /** Added and removed line counts. */
    fun stat(old: String, new: String): Pair<Int, Int> {
        var add = 0
        var rem = 0
        for (row in diffRows(old, new)) {
            when (row.kind) {
                Kind.ADD -> add++
                Kind.REMOVE -> rem++
                Kind.CONTEXT -> Unit
            }
        }
        return add to rem
    }

    fun diffRows(old: String, new: String): List<Row> {
        val a = splitLines(old)
        val b = splitLines(new)

        // Common prefix.
        var pre = 0
        while (pre < a.size && pre < b.size && a[pre] == b[pre]) pre++

        // Common suffix (not overlapping the prefix).
        var suf = 0
        while (suf < a.size - pre && suf < b.size - pre && a[a.size - 1 - suf] == b[b.size - 1 - suf]) suf++

        val midA = a.subList(pre, a.size - suf)
        val midB = b.subList(pre, b.size - suf)

        val rows = ArrayList<Row>(a.size + b.size)
        var oldNo = 1
        var newNo = 1

        for (i in 0 until pre) {
            rows += Row(Kind.CONTEXT, a[i], oldNo++, newNo++)
        }

        val coarse = midA.size.toLong() * midB.size.toLong() > MAX_MIDDLE_PRODUCT ||
            a.size + b.size > MAX_TOTAL_LINES
        if (coarse) {
            for (line in midA) rows += Row(Kind.REMOVE, line, oldNo++, -1)
            for (line in midB) rows += Row(Kind.ADD, line, -1, newNo++)
        } else {
            for (step in lcsMiddle(midA, midB)) {
                when (step.kind) {
                    Kind.CONTEXT -> rows += Row(Kind.CONTEXT, step.text, oldNo++, newNo++)
                    Kind.REMOVE -> rows += Row(Kind.REMOVE, step.text, oldNo++, -1)
                    Kind.ADD -> rows += Row(Kind.ADD, step.text, -1, newNo++)
                }
            }
        }

        for (i in a.size - suf until a.size) {
            rows += Row(Kind.CONTEXT, a[i], oldNo++, newNo++)
        }
        return rows
    }

    /** Groups changed rows into unified hunks with [context] surrounding lines. */
    fun hunks(old: String, new: String, context: Int = 3): List<Hunk> {
        val rows = diffRows(old, new)
        if (rows.none { it.kind != Kind.CONTEXT }) return emptyList()

        // Mark which rows to keep: any change plus `context` neighbours.
        val keep = BooleanArray(rows.size)
        rows.forEachIndexed { index, row ->
            if (row.kind != Kind.CONTEXT) {
                for (j in (index - context).coerceAtLeast(0)..(index + context).coerceAtMost(rows.size - 1)) {
                    keep[j] = true
                }
            }
        }

        val hunks = ArrayList<Hunk>()
        var i = 0
        while (i < rows.size) {
            if (!keep[i]) {
                i++
                continue
            }
            var j = i
            while (j < rows.size && keep[j]) j++
            val slice = rows.subList(i, j)
            hunks += Hunk(header = hunkHeader(slice), rows = slice.toList())
            i = j
        }
        return hunks
    }

    private fun hunkHeader(rows: List<Row>): String {
        val oldStart = rows.firstOrNull { it.oldLine > 0 }?.oldLine ?: 0
        val newStart = rows.firstOrNull { it.newLine > 0 }?.newLine ?: 0
        val oldCount = rows.count { it.kind != Kind.ADD }
        val newCount = rows.count { it.kind != Kind.REMOVE }
        return "@@ -$oldStart,$oldCount +$newStart,$newCount @@"
    }

    private data class Step(val kind: Kind, val text: String)

    private fun lcsMiddle(a: List<String>, b: List<String>): List<Step> {
        val n = a.size
        val m = b.size
        if (n == 0) return b.map { Step(Kind.ADD, it) }
        if (m == 0) return a.map { Step(Kind.REMOVE, it) }

        // dp[i][j] = LCS length of a[i:] and b[j:].
        val dp = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) {
            for (j in m - 1 downTo 0) {
                dp[i][j] = if (a[i] == b[j]) dp[i + 1][j + 1] + 1
                else max(dp[i + 1][j], dp[i][j + 1])
            }
        }

        val steps = ArrayList<Step>(n + m)
        var i = 0
        var j = 0
        while (i < n && j < m) {
            when {
                a[i] == b[j] -> {
                    steps += Step(Kind.CONTEXT, a[i]); i++; j++
                }
                dp[i + 1][j] >= dp[i][j + 1] -> {
                    steps += Step(Kind.REMOVE, a[i]); i++
                }
                else -> {
                    steps += Step(Kind.ADD, b[j]); j++
                }
            }
        }
        while (i < n) steps += Step(Kind.REMOVE, a[i++])
        while (j < m) steps += Step(Kind.ADD, b[j++])
        return steps
    }

    /** Empty content is zero lines; otherwise split on newlines keeping them all. */
    private fun splitLines(text: String): List<String> =
        if (text.isEmpty()) emptyList() else text.split('\n')
}
