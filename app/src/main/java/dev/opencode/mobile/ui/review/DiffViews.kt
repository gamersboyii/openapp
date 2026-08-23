package dev.opencode.mobile.ui.review

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Difference
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.agent.TurnReview
import dev.opencode.mobile.core.checkpoint.ChangeType
import dev.opencode.mobile.core.checkpoint.FileChange
import dev.opencode.mobile.core.util.TextDiff
import dev.opencode.mobile.ui.theme.DiffAdded
import dev.opencode.mobile.ui.theme.DiffRemoved
import dev.opencode.mobile.ui.theme.MonoStyle

/** Rows rendered past this cap are collapsed into a single notice, to protect the UI. */
private const val MAX_DIFF_ROWS = 600

/** The pinned bar in chat after an agent turn changed files. */
@Composable
fun TurnReviewBar(
    review: TurnReview,
    onReview: () -> Unit,
    onAccept: () -> Unit,
    onUndo: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Difference,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(9.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "${review.fileCount} ${plural(review.fileCount, "file")} changed",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = countLabel(review.added, review.removed) + " · ${review.label}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.size(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onReview, modifier = Modifier.weight(1f)) {
                    Text("Review")
                }
                OutlinedButton(onClick = onUndo, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.Undo, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Undo")
                }
                Button(onClick = onAccept, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Keep")
                }
            }
        }
    }
}

/**
 * A changed file. Collapsed it shows path + counts; expanded it lazily loads both
 * sides via [loadTexts] and renders the hunks. [onRevert], when present, restores
 * just this file to the baseline.
 */
@Composable
fun DiffFileCard(
    change: FileChange,
    loadTexts: suspend () -> Pair<String, String>?,
    onRevert: (() -> Unit)? = null,
) {
    var expanded by rememberSaveable(change.path) { mutableStateOf(false) }
    var hunks by remember(change.path) { mutableStateOf<List<TextDiff.Hunk>?>(null) }
    var loading by remember(change.path) { mutableStateOf(false) }

    LaunchedEffect(expanded, change.path) {
        if (expanded && hunks == null && !loading) {
            loading = true
            val texts = loadTexts()
            hunks = if (texts == null) emptyList() else TextDiff.hunks(texts.first, texts.second)
            loading = false
        }
    }

    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.clickable { expanded = !expanded }.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ChangeBadge(change.type)
                Spacer(Modifier.width(9.dp))
                Text(
                    text = change.path,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                CountText(change.added, change.removed)
                Icon(
                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.padding(top = 8.dp)) {
                    when {
                        loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(10.dp))
                            Text("Loading diff…", style = MaterialTheme.typography.bodySmall)
                        }

                        hunks.isNullOrEmpty() -> Text(
                            text = "No textual diff — binary file, or content is identical.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        else -> DiffBody(hunks!!)
                    }
                    if (onRevert != null) {
                        TextButton(onClick = onRevert) {
                            Icon(Icons.Filled.Undo, contentDescription = null, modifier = Modifier.size(15.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Revert this file")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DiffBody(hunks: List<TextDiff.Hunk>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .horizontalScroll(rememberScrollState())
            .padding(vertical = 6.dp),
    ) {
        var rendered = 0
        for (hunk in hunks) {
            if (rendered >= MAX_DIFF_ROWS) break
            Text(
                text = hunk.header,
                style = MonoStyle,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp),
            )
            for (row in hunk.rows) {
                if (rendered >= MAX_DIFF_ROWS) break
                DiffLine(row)
                rendered++
            }
        }
        if (hunks.sumOf { it.rows.size } > MAX_DIFF_ROWS) {
            Text(
                text = "… diff truncated (open the file to see the rest)",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun DiffLine(row: TextDiff.Row) {
    val (bg, prefix, tint) = when (row.kind) {
        TextDiff.Kind.ADD -> Triple(DiffAdded.copy(alpha = 0.16f), "+", MaterialTheme.colorScheme.onSurface)
        TextDiff.Kind.REMOVE -> Triple(DiffRemoved.copy(alpha = 0.16f), "-", MaterialTheme.colorScheme.onSurface)
        TextDiff.Kind.CONTEXT -> Triple(Color.Transparent, " ", MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Text(
        text = "$prefix ${row.text}",
        style = MonoStyle,
        color = tint,
        maxLines = 1,
        modifier = Modifier.fillMaxWidth().background(bg).padding(horizontal = 10.dp, vertical = 1.dp),
    )
}

@Composable
private fun ChangeBadge(type: ChangeType) {
    val (label, color) = when (type) {
        ChangeType.ADDED -> "ADD" to DiffAdded
        ChangeType.MODIFIED -> "MOD" to MaterialTheme.colorScheme.primary
        ChangeType.DELETED -> "DEL" to DiffRemoved
    }
    Surface(color = color.copy(alpha = 0.18f), shape = RoundedCornerShape(5.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun CountText(added: Int, removed: Int) {
    if (added == 0 && removed == 0) return
    Row(modifier = Modifier.padding(end = 6.dp)) {
        if (added > 0) {
            Text("+$added", style = MaterialTheme.typography.labelSmall, color = DiffAdded)
            if (removed > 0) Spacer(Modifier.width(6.dp))
        }
        if (removed > 0) {
            Text("−$removed", style = MaterialTheme.typography.labelSmall, color = DiffRemoved)
        }
    }
}

private fun countLabel(added: Int, removed: Int): String = when {
    added == 0 && removed == 0 -> "no line changes"
    else -> buildString {
        if (added > 0) append("+$added")
        if (added > 0 && removed > 0) append(" ")
        if (removed > 0) append("−$removed")
    }
}

private fun plural(n: Int, word: String): String = if (n == 1) word else "${word}s"
