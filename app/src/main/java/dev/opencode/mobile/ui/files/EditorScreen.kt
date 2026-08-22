package dev.opencode.mobile.ui.files

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.opencode.mobile.LocalContainer
import dev.opencode.mobile.core.util.Highlighter
import dev.opencode.mobile.ui.components.EmptyState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(relativePath: String, onBack: () -> Unit) {
    val container = LocalContainer.current
    val workspace = container.workspace
    // Saves run on the app scope: popping this screen must not cancel a write.
    val scope = container.scope

    val settings by container.settings.settings.collectAsStateWithLifecycle()
    val project by workspace.activeProject.collectAsStateWithLifecycle()

    var value by remember { mutableStateOf(TextFieldValue("")) }
    var savedText by remember { mutableStateOf("") }
    var loaded by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var confirmExit by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }

    val fileName = relativePath.substringAfterLast('/')
    val dirty = loaded && value.text != savedText

    // Deliberately not keyed on workspace.revision: an agent write landing while
    // the user is typing must not silently replace the buffer. Reload is manual.
    LaunchedEffect(relativePath, project?.path) {
        val dir = project?.dir
        if (dir == null || relativePath.isBlank()) {
            error = "No file selected."
            return@LaunchedEffect
        }
        loaded = false
        runCatching { workspace.readText(dir, relativePath) }
            .onSuccess { text ->
                value = TextFieldValue(text)
                savedText = text
                error = null
                loaded = true
            }
            .onFailure { error = it.message ?: "Could not open $relativePath" }
    }

    LaunchedEffect(notice) {
        if (notice != null) {
            delay(1600)
            notice = null
        }
    }

    val save: () -> Unit = {
        val dir = project?.dir
        if (dir != null && !saving) {
            saving = true
            val snapshot = value.text
            scope.launch {
                runCatching { workspace.writeText(dir, relativePath, snapshot) }
                    .onSuccess {
                        savedText = snapshot
                        notice = "Saved"
                        // Live-reload any preview already pointed at this project.
                        container.preview.signalReload()
                    }
                    .onFailure { error = it.message ?: "Save failed" }
                saving = false
            }
        }
    }

    val leave: () -> Unit = { if (dirty) confirmExit = true else onBack() }

    BackHandler(enabled = dirty) { confirmExit = true }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Column {
                    Text(
                        text = fileName.ifBlank { "Editor" },
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = if (dirty) "$relativePath · unsaved" else relativePath,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (dirty) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            },
            navigationIcon = {
                IconButton(onClick = leave) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                }
            },
            actions = {
                if (saving) {
                    CircularProgressIndicator(
                        strokeWidth = 2.dp,
                        modifier = Modifier.padding(end = 12.dp).size(18.dp),
                    )
                } else {
                    IconButton(onClick = save, enabled = dirty) {
                        Icon(
                            imageVector = if (notice == "Saved") Icons.Filled.Check else Icons.Filled.Save,
                            contentDescription = "Save",
                            tint = if (dirty) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "More")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(if (settings.wordWrap) "Word wrap: on" else "Word wrap: off") },
                            onClick = {
                                menuOpen = false
                                container.settings.update { it.copy(wordWrap = !it.wordWrap) }
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Reload from disk") },
                            onClick = {
                                menuOpen = false
                                val dir = project?.dir
                                if (dir != null) {
                                    scope.launch {
                                        runCatching { workspace.readText(dir, relativePath) }
                                            .onSuccess { text ->
                                                value = TextFieldValue(text)
                                                savedText = text
                                                notice = "Reloaded"
                                            }
                                            .onFailure { error = it.message ?: "Reload failed" }
                                    }
                                }
                            },
                        )
                    }
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background,
            ),
        )

        val message = error
        if (message != null) {
            EmptyState(
                icon = Icons.Filled.ErrorOutline,
                title = "Cannot edit this file",
                message = message,
            ) {
                TextButton(onClick = onBack) { Text("Go back") }
            }
            return@Column
        }

        if (!loaded) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
            }
            return@Column
        }

        val fontSize = settings.editorFontSize.coerceIn(9, 26)
        val codeStyle = TextStyle(
            fontFamily = FontFamily.Monospace,
            fontSize = fontSize.sp,
            // Gutter and buffer must share one style or the line numbers drift.
            lineHeight = (fontSize * 1.5f).sp,
        )
        val lineCount = remember(value.text) { value.text.count { it == '\n' } + 1 }
        val gutter = remember(lineCount) {
            // Right-padded here rather than sized in dp: a dp width guessed from
            // the font size clips once the system font scale is turned up.
            val digits = lineCount.toString().length
            (1..lineCount).joinToString("\n") { it.toString().padStart(digits) }
        }
        val transformation = remember(fileName) { CodeTransformation(fileName) }
        val verticalScroll = rememberScrollState()
        val horizontalScroll = rememberScrollState()

        Box(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(verticalScroll)
                    .then(
                        if (settings.wordWrap) Modifier else Modifier.horizontalScroll(horizontalScroll),
                    ),
            ) {
                if (!settings.wordWrap) {
                    // With wrapping on, one logical line can occupy several rows,
                    // so a flat number column would no longer line up. Hide it.
                    Text(
                        text = gutter,
                        style = codeStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.End,
                        modifier = Modifier
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(horizontal = 8.dp, vertical = 12.dp),
                    )
                }
                BasicTextField(
                    value = value,
                    onValueChange = { value = it },
                    textStyle = codeStyle.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    visualTransformation = transformation,
                    // No softWrap parameter on this overload: wrapping falls out of
                    // the width constraint. Inside horizontalScroll the field is
                    // measured unbounded, so long lines extend instead of wrapping.
                    modifier = if (settings.wordWrap) {
                        Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 12.dp)
                    } else {
                        Modifier.padding(start = 10.dp, end = 24.dp, top = 12.dp, bottom = 12.dp)
                    },
                )
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        StatusBar(
            caret = caretPosition(value),
            lineCount = lineCount,
            charCount = value.text.length,
            notice = notice,
            fontSize = fontSize,
            onFontSize = { next ->
                container.settings.update { it.copy(editorFontSize = next.coerceIn(9, 26)) }
            },
        )
    }

    if (confirmExit) {
        AlertDialog(
            onDismissRequest = { confirmExit = false },
            title = { Text("Discard changes?") },
            text = { Text("$fileName has unsaved edits.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmExit = false
                        save()
                        onBack()
                    },
                ) {
                    Text("Save and close")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        confirmExit = false
                        onBack()
                    },
                ) {
                    Text("Discard", color = MaterialTheme.colorScheme.error)
                }
            },
        )
    }
}

@Composable
private fun StatusBar(
    caret: Pair<Int, Int>,
    lineCount: Int,
    charCount: Int,
    notice: String?,
    fontSize: Int,
    onFontSize: (Int) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = notice ?: "Ln ${caret.first}, Col ${caret.second}",
            style = MaterialTheme.typography.labelSmall,
            color = if (notice != null) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        Text(
            text = "$lineCount lines · $charCount chars",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = { onFontSize(fontSize - 1) }) { Text("A-") }
        Text(
            text = "$fontSize",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = { onFontSize(fontSize + 1) }) { Text("A+") }
        Spacer(Modifier.width(2.dp))
    }
}

/** 1-based line and column of the caret, for the status bar. */
private fun caretPosition(value: TextFieldValue): Pair<Int, Int> {
    val offset = value.selection.start.coerceIn(0, value.text.length)
    val before = value.text.substring(0, offset)
    val line = before.count { it == '\n' } + 1
    val column = offset - (before.lastIndexOf('\n') + 1) + 1
    return line to column
}

/**
 * Highlighter output is character-for-character identical to the input, so the
 * caret mapping stays the identity — anything else would desync the cursor.
 */
private class CodeTransformation(private val fileName: String) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText =
        TransformedText(Highlighter.highlightFile(fileName, text.text), OffsetMapping.Identity)
}
