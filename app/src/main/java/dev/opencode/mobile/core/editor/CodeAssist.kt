package dev.opencode.mobile.core.editor

import dev.opencode.mobile.core.util.Highlighter

/**
 * Pure-Kotlin brains for the advanced editor (feature 9): bracket matching and
 * auto-closing, smart indentation, word completion, symbol extraction, in-file
 * rename, and an undo/redo history. Everything here is UI-free so it stays easy
 * to reason about; the Compose screen only translates results into buffer edits.
 */
object Brackets {

    private val OPENERS = setOf('(', '[', '{')
    private val CLOSERS = setOf(')', ']', '}')
    private val QUOTES = setOf('"', '\'', '`')

    /** Typing one of these chars skips over it instead of inserting a duplicate. */
    private val SKIPPABLE = CLOSERS + QUOTES
    private val MATCH: Map<Char, Char> =
        mapOf('(' to ')', '[' to ']', '{' to '}', ')' to '(', ']' to '[', '}' to '{')

    /**
     * What auto-close should insert after [typed] at the caret, or null when the
     * character is never paired.
     */
    fun autoInsert(typed: Char, nextCharInLine: Char?): String? {
        if (typed == '(' || typed == '[' || typed == '{') return MATCH[typed]?.toString()
        val quote = when (typed) {
            '"', '\'', '`' -> typed
            else -> return null
        }
        // Don't wrap when a quote or word character already sits ahead — typing
        // "abc" must not end up as "abc"".
        if (nextCharInLine != null && (nextCharInLine.isLetterOrDigit() || nextCharInLine == quote)) {
            return null
        }
        return quote.toString()
    }

    /**
     * True when [typed] should skip over an identical existing character instead
     * of inserting a duplicate closer/quote.
     */
    fun shouldSkipOver(typed: Char, nextCharInLine: Char?): Boolean =
        nextCharInLine == typed && typed in SKIPPABLE

    /**
     * Offset of the bracket matching the one at [offset], scanned within a
     * bounded window both directions (string/comment awareness is out of scope —
     * a wrong highlight beats no highlight on a phone). Null when unmatched.
     */
    fun matchOffset(text: String, offset: Int): Int? {
        if (offset !in text.indices) return null
        val source = text[offset]
        val target = MATCH[source] ?: return null
        val forward = source in OPENERS
        var depth = 0
        val limit = 4000

        if (forward) {
            var i = offset
            while (i < text.length && i - offset < limit) {
                val c = text[i]
                if (c == source) depth++
                else if (c == target) {
                    depth--
                    if (depth == 0) return i
                }
                i++
            }
        } else {
            var i = offset
            while (i >= 0 && offset - i < limit) {
                val c = text[i]
                if (c == source) depth++
                else if (c == target) {
                    depth--
                    if (depth == 0) return i
                }
                i--
            }
        }
        return null
    }

    /** Line number (1-based) of the first unmatched closing bracket, if any. */
    fun firstUnbalancedLine(text: String): Int? {
        var depth = 0
        var line = 1
        var i = 0
        while (i < text.length && i < MAX_SCAN) {
            when (text[i]) {
                '\n' -> line++
                '(', '[', '{' -> depth++
                ')', ']', '}' -> {
                    depth--
                    if (depth < 0) return line
                }
            }
            i++
        }
        return null
    }

    private const val MAX_SCAN = 300_000
}

/** Smart indentation for Enter / Tab behaviour. */
object Indent {

    const val DEFAULT_UNIT = "    "

    /** Whitespace prefix of the line containing [caret]. */
    fun currentIndent(text: String, caret: Int): String {
        val start = (text.lastIndexOf('\n', (caret - 1).coerceAtLeast(0)) + 1)
            .coerceAtMost(text.length)
        val builder = StringBuilder()
        var i = start
        while (i < caret && i < text.length && (text[i] == ' ' || text[i] == '\t')) {
            builder.append(text[i])
            i++
        }
        return builder.toString()
    }

    /**
     * Indentation for the line created by Enter at [caret]: inherit the current
     * indent plus one extra level when the line opens a block ({ [ ( or a
     * Python/JS-style trailing `:`).
     */
    fun forNewline(text: String, caret: Int, unit: String): String {
        val safeCaret = caret.coerceIn(0, text.length)
        val indent = currentIndent(text, safeCaret)
        val lineStart = text.lastIndexOf('\n', (safeCaret - 1).coerceAtLeast(0)) + 1
        val typedPortion = text.substring(lineStart.coerceAtMost(safeCaret), safeCaret)
        val trimmed = typedPortion.trimEnd()
        val opensBlock = trimmed.endsWith('{') || trimmed.endsWith('[') ||
            trimmed.endsWith('(') || trimmed.endsWith(":")
        return if (opensBlock) indent + unit else indent
    }
}

/**
 * Word-based autocomplete from the document itself plus language keywords — no
 * LLM round-trip, so suggestions are instant on mid-range hardware.
 */
object Completer {

    const val MIN_PREFIX = 2
    const val MAX_SUGGESTIONS = 6
    const val MAX_DOC_CHARS = 120_000
    private val WORD = Regex("[A-Za-z_][A-Za-z0-9_]*")

    fun suggest(
        text: String,
        caret: Int,
        fileName: String,
        enabled: Boolean = true,
    ): List<String> {
        if (!enabled || text.length > MAX_DOC_CHARS) return emptyList()

        val start = findWordStart(text, caret.coerceIn(0, text.length))
        if (caret - start < MIN_PREFIX) return emptyList()
        val prefix = text.substring(start, caret)
        if (!(prefix[0].isLetter() || prefix[0] == '_')) return emptyList()

        val ranked = LinkedHashMap<String, Int>(64)

        WORD.findAll(text).forEach { m ->
            val word = m.value
            if (word.startsWith(prefix, ignoreCase = true) && word != prefix) {
                val score = if (word.startsWith(prefix)) 0 else 1000
                val rank = score + word.length
                val existing = ranked[word]
                if (existing == null || rank < existing) ranked[word] = rank
            }
        }

        Highlighter.keywordsFor(fileName).forEach { kw ->
            if (kw.startsWith(prefix) && kw != prefix) {
                val existing = ranked[kw] ?: Int.MAX_VALUE
                ranked[kw] = minOf(existing, 500) // keywords slightly demoted vs doc words
            }
        }

        return ranked.entries.sortedBy { it.value }.take(MAX_SUGGESTIONS).map { it.key }
    }

    /** Start offset of the identifier immediately before [caret]. */
    fun findWordStart(text: String, caret: Int): Int {
        var i = caret.coerceIn(0, text.length)
        while (i > 0) {
            val c = text[i - 1]
            if (!(c.isLetterOrDigit() || c == '_')) break
            i--
        }
        return i
    }
}

/** One outline entry, used by symbol navigation. */
data class CodeSymbol(val name: String, val kind: String, val lineOneBased: Int)

/**
 * Regex outline extraction per language family — deliberately pragmatic, the
 * same trade the syntax highlighter makes. Good enough to jump somewhere useful.
 */
object Symbols {

    // Group patterns run per line, most specific first.
    private val KEYWORD_DECLS = Regex(
        "(?:public|private|protected|internal|open|abstract|final|sealed|data|static|suspend|" +
            "inline|override|export|async)?\\s*" +
            "(fun|function|class|interface|object|enum|struct|trait|impl|namespace|record)" +
            "\\s+([A-Za-z_][A-Za-z0-9_]*)",
    )
    private val DEF_DECLS = Regex("^\\s*(?:def|fn)\\s+([A-Za-z_][A-Za-z0-9_]*)")
    private val CONST_DECLS = Regex("^\\s*(?:const|val|let)\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*=")

    fun extract(fileName: String, text: String, max: Int = 400): List<CodeSymbol> {
        if (text.isEmpty()) return emptyList()
        val out = ArrayList<CodeSymbol>(minOf(max, 64))

        text.lineSequence().forEachIndexed { index, raw ->
            if (out.size >= max) return
            val line = raw.trim()
            if (line.isEmpty()) return@forEachIndexed

            KEYWORD_DECLS.find(line)?.let { m ->
                val name = m.groupValues[2]
                if (name.isNotBlank()) {
                    out += CodeSymbol(name, m.groupValues[1], index + 1)
                    return@forEachIndexed
                }
            }
            DEF_DECLS.find(line)?.let { m ->
                val name = m.groupValues[1]
                if (name.isNotBlank()) out += CodeSymbol(name, "fun", index + 1)
                return@forEachIndexed
            }
            CONST_DECLS.find(line)?.let { m ->
                val name = m.groupValues[1]
                if (name.isNotBlank()) out += CodeSymbol(name, "val", index + 1)
            }
        }
        return out.take(max)
    }

    /** Best-effort “go to definition”: first declaration matching [identifier]. */
    fun findDefinition(symbols: List<CodeSymbol>, identifier: String): CodeSymbol? =
        symbols.firstOrNull { it.name == identifier }

    /** A whole-word identifier suitable for rename / go-to-definition. */
    fun isIdentifier(word: String): Boolean =
        word.length >= 2 && word.all { it.isLetterOrDigit() || it == '_' } &&
            (word[0].isLetter() || word[0] == '_')
}

/** Whole-word rename inside one file (feature 9). */
object RenameSymbol {

    data class Result(val text: String, val occurrences: Int)

    fun rename(text: String, from: String, to: String): Result? {
        if (from.isBlank() || to.isBlank() || from == to) return null
        if (!Symbols.isIdentifier(from) || !Symbols.isIdentifier(to)) return null
        val pattern = Regex("\\b${Regex.escape(from)}\\b")
        val matches = pattern.findAll(text, 0).toList()
        if (matches.isEmpty() || matches.size > MAX_OCCURRENCES) return null
        return Result(pattern.replace(text, to), matches.size)
    }

    private const val MAX_OCCURRENCES = 500
}

/**
 * Undo/redo history with keystroke coalescing. Every buffer change flows into
 * [record]; bursts of fast typing collapse into one step, and structural edits
 * (newline, paste, replacements) force a fresh step with [forceBreak].
 */
class EditorHistory(initialText: String) {

    private data class Entry(val text: String, val selection: Int)

    private val entries = ArrayList<Entry>(64)
    private var index = -1
    private var lastRecordAt = 0L

    init {
        entries.add(Entry(initialText, 0))
        index = 0
    }

    @Synchronized
    fun record(newText: String, newSelection: Int, forceBreak: Boolean) {
        if (index < 0 || index >= entries.size) return
        if (entries[index].text == newText) return

        val now = System.currentTimeMillis()
        val burst = !forceBreak && now - lastRecordAt < COALESCE_MS

        if (burst && index == entries.lastIndex) {
            entries[index] = Entry(newText, newSelection)
        } else {
            // A genuinely new step invalidates the redo tail beyond [index].
            while (entries.size > index + 1) entries.removeAt(entries.lastIndex)
            entries.add(Entry(newText, newSelection))
            if (entries.size > LIMIT) entries.removeAt(0)
            index = entries.lastIndex
        }
        lastRecordAt = now
    }

    /** Returns (text, selection) to restore, or null when nothing left to undo. */
    @Synchronized
    fun undo(): Pair<String, Int>? {
        if (index <= 0) return null
        index--
        return entries[index].text to entries[index].selection
    }

    @Synchronized
    fun redo(): Pair<String, Int>? {
        if (index >= entries.lastIndex) return null
        index++
        return entries[index].text to entries[index].selection
    }

    fun canUndo(): Boolean = index > 0

    fun canRedo(): Boolean = index < entries.lastIndex

    @Synchronized
    fun reset(text: String) {
        entries.clear()
        entries.add(Entry(text, 0))
        index = 0
        lastRecordAt = 0L
    }

    private companion object {
        const val LIMIT = 120
        const val COALESCE_MS = 700L
    }
}
