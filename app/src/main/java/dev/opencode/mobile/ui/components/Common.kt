package dev.opencode.mobile.ui.components

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.opencode.mobile.core.util.Highlighter
import dev.opencode.mobile.ui.theme.MonoStyle

// ---- formatting -----------------------------------------------------------

fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
}

fun relativeTime(millis: Long, now: Long): String {
    val delta = now - millis
    return when {
        delta < 60_000 -> "just now"
        delta < 3_600_000 -> "${delta / 60_000}m ago"
        delta < 86_400_000 -> "${delta / 3_600_000}h ago"
        delta < 30 * 86_400_000L -> "${delta / 86_400_000}d ago"
        else -> "${delta / (30 * 86_400_000L)}mo ago"
    }
}

fun openExternally(context: Context, url: String): Boolean = runCatching {
    context.startActivity(
        Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}.isSuccess

// ---- small building blocks ------------------------------------------------

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 6.dp),
    )
}

@Composable
fun SettingSwitch(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.size(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit = {},
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(40.dp),
        )
        Spacer(Modifier.height(12.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        content()
    }
}

/**
 * Monospace block with a copy button. Code scrolls sideways instead of wrapping:
 * wrapped code on a phone-width screen is harder to read than a scroll.
 */
@Composable
fun CodeBlock(
    code: String,
    language: String = "",
    modifier: Modifier = Modifier,
    fontSize: Int = 12,
) {
    val clipboard = LocalClipboardManager.current
    val highlighted = remember(code, language) {
        val lang = if (language.isBlank()) {
            Highlighter.Language.PLAIN
        } else {
            Highlighter.languageFor("x.${language.lowercase()}")
        }
        Highlighter.highlight(code, lang)
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = language.ifBlank { "code" },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { clipboard.setText(AnnotatedString(code)) }) {
                Icon(
                    Icons.Filled.ContentCopy,
                    contentDescription = "Copy code",
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            text = highlighted,
            style = MonoStyle.copy(fontSize = fontSize.sp, lineHeight = (fontSize + 6).sp),
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
        )
    }
}

// ---- minimal markdown -----------------------------------------------------

sealed interface MdBlock {
    data class Prose(val text: String) : MdBlock
    data class Code(val language: String, val code: String) : MdBlock
}

/**
 * Splits fenced code out of assistant text. Deliberately tiny: full markdown is
 * overkill for chat, and an unterminated fence has to render while streaming.
 */
fun parseMarkdownBlocks(source: String): List<MdBlock> {
    val blocks = mutableListOf<MdBlock>()
    val prose = StringBuilder()
    val code = StringBuilder()
    var inCode = false
    var language = ""

    source.lines().forEach { line ->
        if (line.trimStart().startsWith("```")) {
            if (inCode) {
                blocks += MdBlock.Code(language, code.toString().trimEnd('\n'))
                code.setLength(0)
                inCode = false
                language = ""
            } else {
                if (prose.isNotBlank()) blocks += MdBlock.Prose(prose.toString().trim())
                prose.setLength(0)
                inCode = true
                language = line.trimStart().removePrefix("```").trim().substringBefore(' ')
            }
            return@forEach
        }
        if (inCode) code.append(line).append('\n') else prose.append(line).append('\n')
    }

    // A fence still open means the model is mid-stream; show what arrived so far.
    if (inCode && code.isNotBlank()) blocks += MdBlock.Code(language, code.toString().trimEnd('\n'))
    if (!inCode && prose.isNotBlank()) blocks += MdBlock.Prose(prose.toString().trim())
    return blocks
}

private val INLINE_PATTERN = Regex(
    """\*\*(.+?)\*\*""" +
        """|`([^`\n]+)`""" +
        """|\[([^\]]+)]\(([^)\s]+)\)""" +
        """|(?<![*\w])\*([^*\n]+)\*(?!\w)""",
)

fun inlineMarkdown(text: String, accent: Color, code: Color): AnnotatedString = buildAnnotatedString {
    text.lines().forEachIndexed { index, raw ->
        if (index > 0) append('\n')

        var line = raw
        var lineStyle: SpanStyle? = null

        val heading = Regex("^(#{1,6})\\s+").find(line)
        if (heading != null) {
            line = line.removeRange(heading.range)
            lineStyle = SpanStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
        } else if (Regex("^\\s*[-*+]\\s+").containsMatchIn(line)) {
            line = line.replaceFirst(Regex("^(\\s*)[-*+]\\s+"), "$1• ")
        }

        val start = length
        var cursor = 0
        INLINE_PATTERN.findAll(line).forEach { match ->
            if (match.range.first > cursor) append(line.substring(cursor, match.range.first))
            when {
                match.groups[1] != null ->
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(match.groupValues[1]) }

                match.groups[2] != null -> withStyle(
                    SpanStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = code),
                ) { append(match.groupValues[2]) }

                match.groups[3] != null -> withStyle(
                    SpanStyle(color = accent, textDecoration = TextDecoration.Underline),
                ) { append(match.groupValues[3]) }

                else -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(match.groupValues[5]) }
            }
            cursor = match.range.last + 1
        }
        if (cursor < line.length) append(line.substring(cursor))

        if (lineStyle != null) addStyle(lineStyle, start, length)
    }
}

/** Renders assistant text: prose with light inline styling, code in [CodeBlock]. */
@Composable
fun MarkdownText(text: String, modifier: Modifier = Modifier) {
    val accent = MaterialTheme.colorScheme.primary
    val codeColor = MaterialTheme.colorScheme.tertiary
    val blocks = remember(text) { parseMarkdownBlocks(text) }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Prose -> Text(
                    text = remember(block.text, accent, codeColor) {
                        inlineMarkdown(block.text, accent, codeColor)
                    },
                    style = MaterialTheme.typography.bodyLarge,
                )

                is MdBlock.Code -> CodeBlock(code = block.code, language = block.language)
            }
        }
    }
}

@Composable
fun rememberUrlOpener(): (String) -> Unit {
    val context = LocalContext.current
    return remember(context) {
        { url: String ->
            openExternally(context, url)
            Unit
        }
    }
}
