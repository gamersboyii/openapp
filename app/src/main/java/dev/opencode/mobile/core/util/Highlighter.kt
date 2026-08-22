package dev.opencode.mobile.core.util

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle

/**
 * Regex-based highlighter. It is deliberately not a parser: one pass over the
 * text with an ordered alternation is fast enough to run on every keystroke in
 * the editor, and a wrong colour is a cosmetic issue, not a correctness one.
 */
object Highlighter {

    private val Comment = Color(0xFF5C6370)
    private val Str = Color(0xFF9ECE6A)
    private val Number = Color(0xFFFF9E64)
    private val Keyword = Color(0xFFBB9AF7)
    private val Type = Color(0xFF7DCFFF)
    private val Tag = Color(0xFFF7768E)
    private val Attribute = Color(0xFFE0AF68)

    private val C_FAMILY = setOf(
        "abstract", "as", "async", "await", "break", "case", "catch", "class", "const",
        "constructor", "continue", "data", "default", "delete", "do", "else", "enum",
        "export", "extends", "false", "final", "finally", "fun", "function", "get", "if",
        "implements", "import", "in", "instanceof", "interface", "internal", "is", "let",
        "new", "null", "object", "open", "operator", "override", "package", "private",
        "protected", "public", "return", "sealed", "set", "static", "super", "suspend",
        "switch", "this", "throw", "true", "try", "typeof", "val", "var", "void", "when",
        "while", "yield", "companion", "lateinit", "vararg", "reified", "inline", "crossinline",
    )

    private val PYTHON = setOf(
        "and", "as", "assert", "async", "await", "break", "class", "continue", "def", "del",
        "elif", "else", "except", "False", "finally", "for", "from", "global", "if", "import",
        "in", "is", "lambda", "None", "nonlocal", "not", "or", "pass", "raise", "return",
        "True", "try", "while", "with", "yield", "self",
    )

    private val CSS_KEYWORDS = setOf(
        "important", "media", "keyframes", "import", "supports", "font-face", "root", "var",
    )

    enum class Language { WEB_MARKUP, STYLES, C_LIKE, PYTHON, DATA, PLAIN }

    fun languageFor(fileName: String): Language =
        when (fileName.substringAfterLast('.', "").lowercase()) {
            "html", "htm", "xml", "svg", "vue", "svelte", "astro" -> Language.WEB_MARKUP
            "css", "scss", "sass", "less" -> Language.STYLES
            "js", "mjs", "cjs", "jsx", "ts", "tsx", "kt", "kts", "java", "c", "h", "cpp",
            "hpp", "cs", "go", "rs", "swift", "php", "dart", "cairo",
            -> Language.C_LIKE

            "py", "rb", "sh", "bash", "zsh" -> Language.PYTHON
            "json", "jsonc", "yaml", "yml", "toml", "properties", "ini" -> Language.DATA
            else -> Language.PLAIN
        }

    fun highlight(code: String, language: Language): AnnotatedString {
        if (language == Language.PLAIN || code.length > MAX_CHARS) return AnnotatedString(code)
        return when (language) {
            Language.WEB_MARKUP -> highlightMarkup(code)
            Language.STYLES -> highlightGeneric(code, CSS_KEYWORDS, typeAfterDot = false)
            Language.C_LIKE -> highlightGeneric(code, C_FAMILY, typeAfterDot = true)
            Language.PYTHON -> highlightGeneric(code, PYTHON, typeAfterDot = false)
            Language.DATA -> highlightData(code)
            Language.PLAIN -> AnnotatedString(code)
        }
    }

    fun highlightFile(fileName: String, code: String): AnnotatedString =
        highlight(code, languageFor(fileName))

    // Group 1 comment, 2 string, 3 number, 4 word.
    private val CODE_PATTERN = Regex(
        """(//[^\n]*|/\*[\s\S]*?\*/|#[^\n]*)""" +
            """|("(?:\\.|[^"\\\n])*"|'(?:\\.|[^'\\\n])*'|`(?:\\.|[^`\\])*`)""" +
            """|(\b\d[\d.xXa-fA-F_]*\b)""" +
            """|([A-Za-z_@\-][A-Za-z0-9_\-]*)""",
    )

    private fun highlightGeneric(
        code: String,
        keywords: Set<String>,
        typeAfterDot: Boolean,
    ): AnnotatedString = buildAnnotatedString {
        var cursor = 0
        CODE_PATTERN.findAll(code).forEach { match ->
            if (match.range.first > cursor) append(code.substring(cursor, match.range.first))
            val token = match.value

            val style = when {
                match.groups[1] != null -> SpanStyle(color = Comment, fontStyle = FontStyle.Italic)
                match.groups[2] != null -> SpanStyle(color = Str)
                match.groups[3] != null -> SpanStyle(color = Number)
                token in keywords -> SpanStyle(color = Keyword)
                token.startsWith("@") || token.startsWith("--") -> SpanStyle(color = Attribute)
                typeAfterDot && token.first().isUpperCase() -> SpanStyle(color = Type)
                else -> null
            }

            if (style != null) withStyle(style) { append(token) } else append(token)
            cursor = match.range.last + 1
        }
        if (cursor < code.length) append(code.substring(cursor))
    }

    // Group 1 comment, 2 tag name, 3 string, 4 attribute name.
    private val MARKUP_PATTERN = Regex(
        """(<!--[\s\S]*?-->)""" +
            """|(</?[A-Za-z][A-Za-z0-9:-]*|/?>)""" +
            """|("(?:[^"\\]|\\.)*"|'(?:[^'\\]|\\.)*')""" +
            """|([A-Za-z_:][A-Za-z0-9_:.-]*)(?==)""",
    )

    private fun highlightMarkup(code: String): AnnotatedString = buildAnnotatedString {
        var cursor = 0
        MARKUP_PATTERN.findAll(code).forEach { match ->
            if (match.range.first > cursor) append(code.substring(cursor, match.range.first))
            val style = when {
                match.groups[1] != null -> SpanStyle(color = Comment, fontStyle = FontStyle.Italic)
                match.groups[2] != null -> SpanStyle(color = Tag)
                match.groups[3] != null -> SpanStyle(color = Str)
                else -> SpanStyle(color = Attribute)
            }
            withStyle(style) { append(match.value) }
            cursor = match.range.last + 1
        }
        if (cursor < code.length) append(code.substring(cursor))
    }

    // Group 1 key, 2 string value, 3 number, 4 literal.
    private val DATA_PATTERN = Regex(
        """("[^"\n]*"|[A-Za-z_][A-Za-z0-9_.\-]*)(?=\s*[:=])""" +
            """|("(?:[^"\\\n]|\\.)*")""" +
            """|(\b-?\d[\d.eE+\-]*\b)""" +
            """|(\b(?:true|false|null|yes|no)\b)""",
    )

    private fun highlightData(code: String): AnnotatedString = buildAnnotatedString {
        var cursor = 0
        DATA_PATTERN.findAll(code).forEach { match ->
            if (match.range.first > cursor) append(code.substring(cursor, match.range.first))
            val style = when {
                match.groups[1] != null -> SpanStyle(color = Type)
                match.groups[2] != null -> SpanStyle(color = Str)
                match.groups[3] != null -> SpanStyle(color = Number)
                else -> SpanStyle(color = Keyword)
            }
            withStyle(style) { append(match.value) }
            cursor = match.range.last + 1
        }
        if (cursor < code.length) append(code.substring(cursor))
    }

    /** Colours a unified diff: `+` green, `-` red, hunk headers muted. */
    fun highlightDiff(diff: String): AnnotatedString = buildAnnotatedString {
        diff.lineSequence().forEach { line ->
            val color = when {
                line.startsWith("+++") || line.startsWith("---") -> Comment
                line.startsWith("@@") -> Type
                line.startsWith("+") -> Str
                line.startsWith("-") -> Tag
                line.startsWith("diff ") || line.startsWith("index ") -> Comment
                else -> null
            }
            if (color != null) withStyle(SpanStyle(color = color)) { append(line) } else append(line)
            append('\n')
        }
    }

    /** Above this the per-keystroke cost is noticeable on mid-range hardware. */
    private const val MAX_CHARS = 120_000
}
