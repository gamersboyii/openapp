package dev.opencode.mobile.ui.files

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Difference
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
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
import dev.opencode.mobile.core.editor.Brackets
import dev.opencode.mobile.core.editor.CodeSymbol
import dev.opencode.mobile.core.editor.Completer
import dev.opencode.mobile.core.editor.EditorHistory
import dev.opencode.mobile.core.editor.Indent
import dev.opencode.mobile.core.editor.RenameSymbol
import dev.opencode.mobile.core.editor.Symbols
import dev.opencode.mobile.core.util.Highlighter
import dev.opencode.mobile.core.util.TextDiff
import dev.opencode.mobile.ui.components.EmptyState
import dev.opencode.mobile.ui.theme.DiffAdded
import dev.opencode.mobile.ui.theme.DiffRemoved
import dev.opencode.mobile.ui.theme.MonoStyle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Past this size the editor drops autocomplete/pairing/bracket UI ("lite mode"). */
private const val LITE_MODE_CHARS = 60_000

private data class FindState(
    val query: String = "",
    val caseSensitive: Boolean = false,
    val replacement: String = "",
)

/**
 * Advanced code editor (feature 9): syntax highlighting, line numbers, file tabs
 * with unsaved dots, undo/redo, find/replace, autocomplete, smart indent,
 * bracket pairing + matching, symbol outline, go-to-line, diff vs saved copy,
 * and basic diagnostics — tuned for thumb-first use on a phone.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(relativePath: String, onBack: () -> Unit) {
    val container = LocalContainer.current
    val workspace = container.workspace
    // Saves run on the app scope: popping this screen must not cancel a write.
    val scope = container.scope
    val tabStore = container.editorTabs

    val settings by container.settings.settings.collectAsStateWithLifecycle()
    val project by workspace.activeProject.collectAsStateWithLifecycle()

    // ---- open-files session (tabs survive leaving the editor) ---------------
    val tabs by tabStore.tabs.collectAsStateWithLifecycle()

    // Seeded from the nav argument; afterwards the internal selection drives it.
    var currentPath by remember { mutableStateOf(relativePath) }
    var renameDialogTarget by remember { mutableStateOf<String?>(null) }

    var value by remember { mutableStateOf(TextFieldValue("")) }
    var savedText by remember { mutableStateOf("") }
    var loaded by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }

    var showFind by remember { mutableStateOf(false) }
    var find by remember { mutableStateOf(FindState()) }
    var matches by remember { mutableStateOf<List<IntRange>>(emptyList()) }
    var matchIndex by remember { mutableIntStateOf(-1) }

    var showOutline by remember { mutableStateOf(false) }
    var symbols by remember { mutableStateOf<List<CodeSymbol>>(emptyList()) }
    var showDiff by remember { mutableStateOf(false) }
    var confirmExit by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var gotoDialog by remember { mutableStateOf(false) }
    var pendingJumpOffset by remember { mutableStateOf<Int?>(null) }
    var layoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }

    /** True while a history-driven edit lands, so it is not re-recorded. */
    var applyingHistory by remember { mutableStateOf(false) }

    val fontSize = settings.editorFontSize.coerceIn(9, 26)
    val liteMode = value.text.length > LITE_MODE_CHARS
    val fileName = currentPath.substringAfterLast('/')
    val dirty = loaded && value.text != savedText

    val history = remember(currentPath) { EditorHistory("") }

    // ------------------------------------------------------------------ loading
    //
    // Deliberately NOT keyed on workspace.revision: an agent write landing while
    // the user types must not clobber the buffer. Dirty tabs restore their
    // cached buffer untouched; clean tabs re-read so outside edits come through.
    LaunchedEffect(currentPath, project?.path) {
        val dir = project?.dir
        if (dir == null || currentPath.isBlank()) {
            if (currentPath.isBlank()) error = "No file selected."
            loaded = dir != null && currentPath.isNotBlank()
            return@LaunchedEffect
        }
        val cached = tabStore.buffer(currentPath)
        if (cached != null && cached.dirty) {
            value = TextFieldValue(
                cached.text,
                TextRange(cached.selectionStart, cached.selectionEnd),
            )
            savedText = cached.savedText
            history.reset(cached.text)
            applyingHistory = false
            error = null
            loaded = true
        } else {
            loaded = false
            runCatching { workspace.readText(dir, currentPath) }
                .onSuccess { text ->
                    value = TextFieldValue(text)
                    savedText = text
                    history.reset(text)
                    tabStore.snapshot(currentPath, text, text, 0, 0)
                    applyingHistory = false
                    error = null
                    loaded = true
                }
                .onFailure { err ->
                    error = err.message ?: "Could not open $currentPath"
                }
        }
        layoutResult = null
        matchIndex = -1
    }

    LaunchedEffect(notice) {
        if (notice != null) {
            delay(1800)
            notice = null
        }
    }

    // Keep the matches fresh against buffer/query movement.
    LaunchedEffect(value.text, find.query, find.caseSensitive) {
        matches = findAllMatches(value.text, find.query, find.caseSensitive)
        matchIndex = -1
    }

    // ------------------------------------------------------------- local helpers

    fun snapshotToStore() {
        tabStore.snapshot(currentPath, value.text, savedText, value.selection.start, value.selection.end)
    }

    fun setBuffer(text: String, caret: Int, recordWithBreak: Boolean) {
        val clampedCaret = caret.coerceIn(0, text.length)
        value = TextFieldValue(text, TextRange(clampedCaret, clampedCaret))
        if (!applyingHistory) {
            history.record(text, clampedCaret, recordWithBreak)
        }
        snapshotToStore()
    }

    /**
     * Central onValueChange: classifies the delta, then layers bracket pairing,
     * skip-over and smart indentation over whatever the IME produced, and feeds
     * the undo history.
     */
    fun onBufferChange(raw: TextFieldValue) {
        if (applyingHistory) {
            value = raw
            snapshotToStore()
            return
        }
        val old = value
        var text = raw.text
        var selStart = raw.selection.start
        var selEnd = raw.selection.end

        val change = classifyChange(old.text, text)
        var forcedBreak = false

        when {
            change.inserted.isEmpty() -> Unit // pure deletion — nothing to embellish

            change.inserted == "\n" && change.removed == 0 -> {
                val indent = Indent.forNewline(old.text, change.start, Indent.DEFAULT_UNIT)
                if (indent.isNotEmpty()) {
                    val insertAt = change.start + 1
                    text = StringBuilder(text).insert(insertAt, indent).toString()
                    selStart += indent.length
                    selEnd += indent.length
                }
                forcedBreak = true
            }

            change.inserted.length == 1 && change.removed == 0 && !liteMode -> {
                val typed = change.inserted[0]
                val charAhead = old.text.getOrNull(change.start)
                when {
                    Brackets.shouldSkipOver(typed, charAhead) -> {
                        // Remove the pre-existing duplicate: net effect = step over.
                        val dupAt = change.start + 1
                        if (dupAt < text.length) text = StringBuilder(text).deleteCharAt(dupAt).toString()
                    }

                    else -> {
                        val insert = Brackets.autoInsert(typed, charAhead)
                        if (insert != null) {
                            text = StringBuilder(text).insert(change.start + 1, insert).toString()
                            // Caret stays between the pair at change.start+1.
                        }
                    }
                }
            }

            change.inserted.length > 1 -> forcedBreak = true // paste / word-suggestion
        }

        value = TextFieldValue(text, TextRange(selStart, selEnd))
        history.record(text, selStart, forcedBreak)
        snapshotToStore()
    }

    fun applyHistoryEntry(entry: Pair<String, Int>) {
        applyingHistory = true
        value = TextFieldValue(entry.first, TextRange(entry.second, entry.second))
        snapshotToStore()
        applyingHistory = false
    }

    fun focusMatch(range: IntRange) {
        pendingJumpOffset = range.first
        value = TextFieldValue(value.text, TextRange(range.first, range.last + 1))
        snapshotToStore()
    }

    fun replaceCurrentMatch() {
        val range = matches.getOrNull(matchIndex) ?: return
        val sb = StringBuilder(value.text).replace(range.first, range.last + 1, find.replacement)
        val sel = (range.first + find.replacement.length).coerceAtMost(sb.length)
        value = TextFieldValue(sb.toString(), TextRange(sel, sel))
        history.record(sb.toString(), sel, forceBreak = true)
        snapshotToStore()
        pendingJumpOffset = sel
    }

    fun replaceAllMatches() {
        if (find.query.length < 2) return
        val occurrences = findAllMatches(value.text, find.query, find.caseSensitive).size
        if (occurrences == 0) return
        val updated = if (find.caseSensitive) {
            value.text.replace(find.query, find.replacement)
        } else {
            regexIgnoreCase(find.query)?.replace(value.text, find.replacement) ?: return
        }
        value = TextFieldValue(updated)
        history.record(updated, 0, forceBreak = true)
        snapshotToStore()
        notice = "Replaced $occurrences"
    }

    fun applyCompletion(word: String) {
        val caret = value.selection.start
        val start = Completer.findWordStart(value.text, caret)
        if (caret > start) {
            val sb = StringBuilder(value.text).replace(start, caret, word)
            setBuffer(sb.toString(), start + word.length, recordWithBreak = true)
        }
    }

    fun lineCount(): Int = value.text.count { it == '\n' } + 1

    fun jumpToLine(line: Int) {
        val clamped = line.coerceIn(1, lineCount())
        var offset = 0
        var counted = 1
        for (c in value.text) {
            if (counted >= clamped) break
            if (c == '\n') counted++
            offset++
        }
        value = TextFieldValue(value.text, TextRange(offset, offset))
        pendingJumpOffset = offset
        snapshotToStore()
    }

    fun saveActive() {
        val dir = project?.dir
        if (dir != null && !saving) {
            saving = true
            val snapshot = value.text
            scope.launch {
                runCatching { workspace.writeText(dir, currentPath, snapshot) }
                    .onSuccess {
                        savedText = snapshot
                        tabStore.markSaved(currentPath, snapshot)
                        notice = "Saved"
                        container.preview.signalReload()
                    }
                    .onFailure { notice = (it.message ?: "Save failed").take(80) }
                saving = false
            }
        }
    }

    fun saveAllDirtyTabs(onDone: () -> Unit = {}) {
        val dir = project?.dir ?: run { onDone(); return }
        tabs.forEach { path ->
            val buffered = tabStore.buffer(path) ?: return@forEach
            if (buffered.dirty) {
                scope.launch {
                    runCatching { workspace.writeText(dir, path, buffered.text) }
                        .onSuccess { tabStore.markSaved(path, buffered.text) }
                }
            }
        }
        savedText = value.text
        if (container.preview.state.value.running) container.preview.signalReload()
        onDone()
    }

    val leaveEditor: () -> Unit = {
        if (tabStore.dirtyCount() > 0) confirmExit = true else onBack()
    }

    BackHandler(enabled = tabStore.dirtyCount() > 0) { confirmExit = true }

    // ---- navigation-in-editor helpers ---------------------------------------

    fun selectTab(path: String) {
        if (path == currentPath) return
        snapshotToStore()
        tabStore.focus(path)
        currentPath = path
    }

    fun closeTab(path: String) {
        val wasDirty = tabStore.buffer(path)?.dirty == true
        if (wasDirty) {
            // Keep the buffer cached so reopening continues where typing stopped.
            tabStore.close(path, discardBuffer = false)
            notice = "Unsaved edits of ${path.substringAfterLast('/')} kept in memory"
        } else {
            tabStore.close(path, discardBuffer = true)
        }
        if (path == currentPath) {
            val next = tabStore.activePath.value
            if (next != null) currentPath = next else onBack()
        }
    }

    // ---- scroll-to-offset machinery -------------------------------------------

    val verticalScrollState = rememberScrollState()
    val horizontalScrollState = rememberScrollState()

    LaunchedEffect(pendingJumpOffset, layoutResult, currentPath) {
        val target = pendingJumpOffset ?: return@LaunchedEffect
        val layout = layoutResult ?: return@LaunchedEffect
        pendingJumpOffset = null
        runCatching {
            val safeOffset = target.coerceIn(0, (value.text.length - 1).coerceAtLeast(0))
            val line = layout.getLineForOffset(safeOffset)
            verticalScrollState.animateScrollTo((layout.getLineTop(line).toInt() - 96).coerceAtLeast(0))
            if (!settings.wordWrap) {
                val x = layout.getHorizontalPosition(safeOffset, usePrimaryDirection = true)
                horizontalScrollState.animateScrollTo((x.toInt() - 240).coerceAtLeast(0))
            }
        }
    }

    // ---- derived visuals --------------------------------------------------------

    val completions = remember(value.text, value.selection.start, liteMode) {
        if (liteMode || !value.selection.collapsed) emptyList()
        else Completer.suggest(value.text, value.selection.start, fileName)
    }

    val caretLine = lineOfOffset(value.text, value.selection.start)
    val caretColumn = columnOfOffset(value.text, value.selection.start)
    val totalLines = lineCount()
    val unmatchedLine = remember(value.text) { Brackets.firstUnbalancedLine(value.text) }

    val codeStyle = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = fontSize.sp,
        // Gutter and buffer must share one style or the line numbers drift.
        lineHeight = (fontSize * 1.5f).sp,
    )

    val transformation = remember(
        fileName, find.query, find.caseSensitive, matchIndex,
        value.selection.start, liteMode, value.text.length,
    ) {
        CodeTransformation(
            fileName = fileName,
            findQuery = find.query.takeIf { it.length >= 2 },
            caseSensitive = find.caseSensitive,
            activeMatchIndex = matchIndex,
            matchCountHint = matches.size,
            caret = value.selection.start,
            liteMode = liteMode,
        )
    }

    // ==================================================================== UI

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
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (dirty) {
                            Box(
                                modifier = Modifier
                                    .padding(end = 6.dp)
                                    .size(7.dp)
                                    .background(MaterialTheme.colorScheme.primary, CircleShape),
                            )
                        }
                        Text(
                            text = currentPath.substringBeforeLast('/').ifBlank { "project root" },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            },
            navigationIcon = {
                IconButton(onClick = { leaveEditor() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
            actions = {
                if (saving) {
                    CircularProgressIndicator(
                        strokeWidth = 2.dp,
                        modifier = Modifier.padding(end = 12.dp).size(18.dp),
                    )
                } else {
                    IconButton(onClick = { saveActive() }, enabled = dirty) {
                        Icon(
                            Icons.Filled.Save,
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
                            text = { Text(if (showFind) "Hide find" else "Find / replace") },
                            onClick = {
                                menuOpen = false
                                showFind = !showFind
                                if (!showFind) find = FindState()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Go to line…") },
                            onClick = {
                                menuOpen = false
                                gotoDialog = true
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Go to definition…") },
                            enabled = Symbols.isIdentifier(wordBeforeCaret(value.text, value.selection.start)),
                            onClick = {
                                menuOpen = false
                                val identifier = wordBeforeCaret(value.text, value.selection.start)
                                symbols = Symbols.extract(fileName, value.text)
                                val def = Symbols.findDefinition(symbols, identifier)
                                if (def != null) {
                                    notice = "${def.kind} ${def.name}"
                                    jumpToLine(def.lineOneBased)
                                } else {
                                    notice = "No in-file definition of '$identifier'"
                                }
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(if (showDiff) "Hide diff vs saved" else "Diff vs saved") },
                            onClick = {
                                menuOpen = false
                                showDiff = !showDiff
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Rename symbol…") },
                            enabled = Symbols.isIdentifier(wordBeforeCaret(value.text, value.selection.start)),
                            onClick = {
                                menuOpen = false
                                renameDialogTarget = wordBeforeCaret(value.text, value.selection.start)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Reload from disk") },
                            onClick = {
                                menuOpen = false
                                val dir = project?.dir
                                if (dir != null) {
                                    scope.launch {
                                        runCatching { workspace.readText(dir, currentPath) }
                                            .onSuccess { text ->
                                                value = TextFieldValue(text)
                                                savedText = text
                                                history.reset(text)
                                                tabStore.markSaved(currentPath, text)
                                                notice = "Reloaded"
                                            }
                                            .onFailure { notice = (it.message ?: "Reload failed").take(80) }
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

        // ---- file tabs (feature 9) ------------------------------------------
        if (tabs.size > 1 || tabs.firstOrNull()?.isNotBlank() == true) {
            EditorTabsBar(
                paths = tabs,
                activePath = currentPath,
                dirtyChecker = { path -> tabStore.buffer(path)?.dirty == true },
                onSelect = ::selectTab,
                onClose = ::closeTab,
            )
        }

        // ---- editor toolbar -----------------------------------------------------
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = { history.undo()?.let(::applyHistoryEntry) },
                enabled = history.canUndo(),
                modifier = Modifier.size(38.dp),
            ) {
                Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo", modifier = Modifier.size(18.dp))
            }
            IconButton(
                onClick = { history.redo()?.let(::applyHistoryEntry) },
                enabled = history.canRedo(),
                modifier = Modifier.size(38.dp),
            ) {
                Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = "Redo", modifier = Modifier.size(18.dp))
            }
            IconButton(onClick = { showFind = !showFind }, modifier = Modifier.size(38.dp)) {
                Icon(
                    Icons.Filled.Search,
                    contentDescription = "Find",
                    tint = if (showFind) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.size(18.dp),
                )
            }
            IconButton(
                onClick = {
                    symbols = Symbols.extract(fileName, value.text)
                    showOutline = true
                },
                modifier = Modifier.size(38.dp),
            ) {
                Icon(Icons.Filled.List, contentDescription = "Symbol outline", modifier = Modifier.size(18.dp))
            }
            IconButton(onClick = { showDiff = !showDiff }, modifier = Modifier.size(38.dp)) {
                Icon(
                    Icons.Filled.Difference,
                    contentDescription = "Diff vs saved",
                    tint = if (showDiff) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.size(18.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { gotoDialog = true }) {
                Text("Ln", style = MaterialTheme.typography.labelMedium)
            }
        }

        if (showFind) {
            FindReplaceBar(
                state = find,
                matchCount = matches.size,
                currentIndex = if (matchIndex < 0) 0 else matchIndex,
                onQuery = { q -> find = find.copy(query = q) },
                onReplacement = { r -> find = find.copy(replacement = r) },
                onToggleCase = { find = find.copy(caseSensitive = !find.caseSensitive) },
                onNext = {
                    if (matches.isNotEmpty()) {
                        matchIndex = ((if (matchIndex < 0) 0 else matchIndex) + 1) % matches.size
                        focusMatch(matches[matchIndex])
                    }
                },
                onPrev = {
                    if (matches.isNotEmpty()) {
                        matchIndex =
                            ((if (matchIndex < 0) 0 else matchIndex) - 1 + matches.size) % matches.size
                        focusMatch(matches[matchIndex])
                    }
                },
                onReplaceCurrent = { replaceCurrentMatch() },
                onReplaceAll = { replaceAllMatches() },
                onClose = {
                    showFind = false
                    find = FindState()
                },
            )
        }

        val message = error
        if (message != null && !loaded) {
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

        // ---- editor body ----------------------------------------------------------

        Box(modifier = Modifier.weight(1f)) {
            if (showDiff) {
                BufferDiffPane(saved = savedText, current = value.text, fontSizeSp = fontSize)
            } else {
                val gutterChars = totalLines.toString().length
                val gutter = remember(totalLines) {
                    (1..totalLines).joinToString("\n") { it.toString().padStart(gutterChars) }
                }
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(verticalScrollState)
                        .then(
                            if (settings.wordWrap) Modifier else Modifier.horizontalScroll(horizontalScrollState),
                        ),
                ) {
                    if (!settings.wordWrap) {
                        // With wrapping on, one logical line can occupy several rows;
                        // a flat number column would no longer line up. Hidden then.
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
                        onValueChange = ::onBufferChange,
                        textStyle = codeStyle.copy(color = MaterialTheme.colorScheme.onSurface),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        visualTransformation = transformation,
                        onTextLayout = { layoutResult = it },
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

                // Autocomplete strip floats above the status bar.
                if (completions.isNotEmpty()) {
                    Box(modifier = Modifier.align(Alignment.BottomCenter)) {
                        CompletionStrip(words = completions, onPick = { word -> applyCompletion(word) })
                    }
                }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        StatusBar(
            caretLine = caretLine,
            caretColumn = caretColumn,
            lineCount = totalLines,
            liteMode = liteMode,
            unmatchedLine = unmatchedLine,
            notice = notice,
            fontSize = fontSize,
            onFontSize = { next ->
                container.settings.update { it.copy(editorFontSize = next.coerceIn(9, 26)) }
            },
        )
    }

    // ---- dialogs & sheets --------------------------------------------------------

    if (confirmExit) {
        val dirtyTabs = tabs.count { tabStore.buffer(it)?.dirty == true }
        AlertDialog(
            onDismissRequest = { confirmExit = false },
            title = { Text("Unsaved changes") },
            text = {
                Text(
                    if (dirtyTabs <= 1) "$fileName has unsaved edits." else "$dirtyTabs files have unsaved edits.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmExit = false
                        saveAllDirtyTabs { onBack() }
                    },
                ) { Text("Save all and exit") }
            },
            dismissButton = {
                TextButton(onClick = { confirmExit = false; onBack() }) {
                    Text("Discard", color = MaterialTheme.colorScheme.error)
                }
            },
        )
    }

    if (gotoDialog) {
        var lineText by remember { mutableStateOf(caretLine.toString()) }
        AlertDialog(
            onDismissRequest = { gotoDialog = false },
            title = { Text("Go to line") },
            text = {
                OutlinedTextField(
                    value = lineText,
                    onValueChange = { raw -> lineText = raw.filter { it.isDigit() }.take(7) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                )
            },
            confirmButton = {
                TextButton(
                    enabled = lineText.toIntOrNull() != null,
                    onClick = {
                        gotoDialog = false
                        jumpToLine(lineText.toIntOrNull()?.coerceIn(1, totalLines) ?: caretLine)
                    },
                ) { Text("Go") }
            },
            dismissButton = { TextButton(onClick = { gotoDialog = false }) { Text("Cancel") } },
        )
    }

    if (renameDialogTarget != null) {
        var renameText by remember(renameDialogTarget) { mutableStateOf(renameDialogTarget.orEmpty()) }
        AlertDialog(
            onDismissRequest = { renameDialogTarget = null },
            title = { Text("Rename '${renameDialogTarget}'") },
            text = {
                Column {
                    OutlinedTextField(
                        value = renameText,
                        onValueChange = { raw -> renameText = raw.trim().take(60) },
                        singleLine = true,
                    )
                    Spacer(Modifier.size(6.dp))
                    Text(
                        "Renames every whole-word occurrence in this file.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(enabled = renameText.isNotBlank() && renameText.trim() != renameDialogTarget, onClick = {
                    val outcome = RenameSymbol.rename(value.text, renameDialogTarget.orEmpty(), renameText.trim())
                    if (outcome != null) {
                        setBuffer(outcome.text, value.selection.start.coerceAtMost(outcome.text.length), recordWithBreak = true)
                        notice = "Renamed ${outcome.occurrences} occurrence(s)"
                    } else {
                        notice = "Nothing renamed"
                    }
                    renameDialogTarget = null
                }) { Text("Rename") }
            },
            dismissButton = {
                TextButton(onClick = { renameDialogTarget = null }) { Text("Cancel") }
            },
        )
    }

    if (showOutline) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(onDismissRequest = { showOutline = false }, sheetState = sheetState) {
            SymbolSheet(
                fileName = fileName,
                symbols = symbols,
                onPick = { symbol ->
                    showOutline = false
                    jumpToLine(symbol.lineOneBased)
                },
            )
        }
    }
}

// ---- tabs bar -------------------------------------------------------------

@Composable
fun EditorTabsBar(
    paths: List<String>,
    activePath: String,
    dirtyChecker: (String) -> Boolean,
    onSelect: (String) -> Unit,
    onClose: (String) -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 8.dp),
        ) {
            items(items = paths, key = { it }) { path ->
                val active = path == activePath
                Surface(
                    color = if (active) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .padding(horizontal = 3.dp, vertical = 4.dp)
                        .clickable { onSelect(path) },
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(start = 10.dp, end = 4.dp, top = 7.dp, bottom = 7.dp),
                    ) {
                        if (dirtyChecker(path)) {
                            Box(
                                modifier = Modifier
                                    .padding(end = 6.dp)
                                    .size(6.dp)
                                    .background(MaterialTheme.colorScheme.primary, CircleShape),
                            )
                        }
                        Text(
                            text = path.substringAfterLast('/'),
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = if (active) {
                                MaterialTheme.colorScheme.onSecondaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                        Spacer(Modifier.width(6.dp))
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = "Close $path",
                            modifier = Modifier
                                .size(15.dp)
                                .clickable { onClose(path) },
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

// ---- find / replace ---------------------------------------------------------

@Composable
private fun FindReplaceBar(
    state: FindState,
    matchCount: Int,
    currentIndex: Int,
    onQuery: (String) -> Unit,
    onReplacement: (String) -> Unit,
    onToggleCase: () -> Unit,
    onNext: () -> Unit,
    onPrev: () -> Unit,
    onReplaceCurrent: () -> Unit,
    onReplaceAll: () -> Unit,
    onClose: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = state.query,
                    onValueChange = onQuery,
                    placeholder = { Text("Find") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = if (matchCount == 0) "0/0" else "${currentIndex + 1}/$matchCount",
                    style = MonoStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
                IconButton(onClick = onPrev) {
                    Icon(Icons.Filled.ExpandLess, contentDescription = "Previous match")
                }
                IconButton(onClick = onNext) {
                    Icon(Icons.Filled.ExpandMore, contentDescription = "Next match")
                }
                IconButton(onClick = onClose) {
                    Icon(Icons.Filled.Close, contentDescription = "Close find")
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onToggleCase) {
                    Text(
                        if (state.caseSensitive) "Aa" else "aa",
                        fontWeight = if (state.caseSensitive) FontWeight.Bold else FontWeight.Normal,
                        color = if (state.caseSensitive) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                Spacer(Modifier.width(6.dp))
                OutlinedTextField(
                    value = state.replacement,
                    onValueChange = onReplacement,
                    placeholder = { Text("Replace with") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(6.dp))
                TextButton(onClick = onReplaceCurrent, enabled = matchCount > 0) { Text("This") }
                TextButton(onClick = onReplaceAll, enabled = matchCount > 0) { Text("All") }
            }
        }
    }
}

// ---- autocomplete strip ------------------------------------------------------

@Composable
private fun CompletionStrip(words: List<String>, onPick: (String) -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp),
        tonalElevation = 4.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        LazyRow(
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(items = words, key = { it }) { word ->
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.clickable { onPick(word) },
                ) {
                    Text(
                        text = word,
                        style = MonoStyle,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
            }
        }
    }
}

// ---- outline sheet -------------------------------------------------------------

@Composable
private fun SymbolSheet(fileName: String, symbols: List<CodeSymbol>, onPick: (CodeSymbol) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
        Text(
            text = "${fileName.substringAfterLast('.')} · ${symbols.size} symbols",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        )
        if (symbols.isEmpty()) {
            Text(
                text = "No declarations found (functions, classes, consts).",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                symbols.forEach { symbol ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(symbol) }
                            .padding(horizontal = 20.dp, vertical = 9.dp),
                    ) {
                        Text(
                            text = symbol.kind,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.width(64.dp),
                        )
                        Text(
                            text = symbol.name,
                            style = MonoStyle,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = ":${symbol.lineOneBased}",
                            style = MonoStyle,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

// ---- buffer-vs-saved diff pane ---------------------------------------------------

@Composable
private fun BufferDiffPane(saved: String, current: String, fontSizeSp: Int) {
    val hunks = remember(saved, current) { TextDiff.hunks(saved, current) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(10.dp),
    ) {
        if (hunks.isEmpty()) {
            Text(
                "No differences against the saved copy.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        hunks.forEach { hunk ->
            Text(
                text = hunk.header,
                style = MonoStyle,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(vertical = 2.dp),
            )
            hunk.rows.forEach { row ->
                val (bg, prefix, tint) = when (row.kind) {
                    TextDiff.Kind.ADD -> Triple(DiffAdded.copy(alpha = 0.16f), "+", Color.Unspecified)
                    TextDiff.Kind.REMOVE -> Triple(DiffRemoved.copy(alpha = 0.16f), "-", Color.Unspecified)
                    TextDiff.Kind.CONTEXT -> Triple(Color.Transparent, " ", Color.Unspecified)
                }
                Text(
                    text = "$prefix ${row.text}",
                    style = MonoStyle.copy(fontSize = (fontSizeSp - 1).sp),
                    color = if (tint == Color.Unspecified) MaterialTheme.colorScheme.onSurface else tint,
                    maxLines = 1,
                    modifier = Modifier.fillMaxWidth().background(bg),
                )
            }
        }
    }
}

// ---- status bar --------------------------------------------------------------------

@Composable
private fun StatusBar(
    caretLine: Int,
    caretColumn: Int,
    lineCount: Int,
    liteMode: Boolean,
    unmatchedLine: Int?,
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
            text = when {
                notice != null -> notice
                unmatchedLine != null -> "⚠ unbalanced bracket near line $unmatchedLine"
                else -> "Ln $caretLine, Col $caretColumn"
            },
            style = MaterialTheme.typography.labelSmall,
            color = when {
                notice != null -> MaterialTheme.colorScheme.primary
                unmatchedLine != null -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = buildString {
                append("$lineCount lines")
                if (liteMode) append(" · lite mode")
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
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

// ---- visual transformation: highlighter + find marks + bracket match --------------

private class CodeTransformation(
    private val fileName: String,
    private val findQuery: String?,
    private val caseSensitive: Boolean,
    private val activeMatchIndex: Int,
    @Suppress("unused") private val matchCountHint: Int,
    private val caret: Int,
    private val liteMode: Boolean,
) : VisualTransformation {

    override fun filter(text: AnnotatedString): TransformedText {
        val annotated = Highlighter.highlightFile(fileName, text.text)
        val builder = AnnotatedString.Builder(annotated)

        if (!liteMode) {
            val source = text.text

            findQuery?.let { query ->
                try {
                    val options = if (caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)
                    Regex(Regex.escape(query), options)
                        .findAll(source)
                        .take(MAX_MARKED_MATCHES)
                        .forEachIndexed { i, m ->
                            builder.addStyle(MATCH_BG_SPAN, m.range.first, m.range.last + 1)
                            if (i == activeMatchIndex) {
                                builder.addStyle(ACTIVE_MATCH_SPAN, m.range.first, m.range.last + 1)
                            }
                        }
                } catch (_: Throwable) {
                    // Never let search cosmetics break rendering.
                }
            }

            val bracketPartner = Brackets.matchOffset(source, (caret - 1).coerceAtLeast(0))
                ?: Brackets.matchOffset(source, caret.coerceAtMost((source.length - 1).coerceAtLeast(0)))
            bracketPartner?.let { partner ->
                val anchor = if (partner > caret) caret - 1 else caret
                listOf(partner, anchor).filter { it in source.indices }.forEach { pos ->
                    builder.addStyle(BRACKET_SPAN, pos, pos + 1)
                }
            }
        }

        // Identity mapping — output must stay character-for-character identical.
        return TransformedText(builder.toAnnotatedString(), OffsetMapping.Identity)
    }

    private companion object {
        private const val MAX_MARKED_MATCHES = 600
    }
}

private val MATCH_BG_SPAN = SpanStyle(background = Color(0x2E7AA2F7)) // soft blue overlay
private val ACTIVE_MATCH_SPAN = SpanStyle(background = Color(0x55E0AF68)) // amber for the current hit
private val BRACKET_SPAN = SpanStyle(background = Color(0x339ECE6A))

private data class ChangeInfo(val start: Int, val inserted: String, val removed: Int)

private fun classifyChange(old: String, new: String): ChangeInfo {
    val maxPrefix = minOf(old.length, new.length)
    var start = 0
    while (start < maxPrefix && old[start] == new[start]) start++

    val maxSuffix = minOf(old.length - start, new.length - start)
    var suffix = 0
    while (suffix < maxSuffix && old[old.length - 1 - suffix] == new[new.length - 1 - suffix]) suffix++

    val inserted = new.substring(start, (new.length - suffix).coerceAtLeast(start))
    return ChangeInfo(start, inserted, old.length - start - suffix)
}

/** Identifier immediately before [offset] — powers rename / go-to-definition. */
private fun wordBeforeCaret(text: String, offset: Int): String {
    val start = Completer.findWordStart(text, offset.coerceIn(0, text.length))
    return text.substring(start, offset.coerceIn(start, text.length))
}

private fun lineOfOffset(text: String, offset: Int): Int =
    text.substring(0, offset.coerceIn(0, text.length)).count { it == '\n' } + 1

private fun columnOfOffset(text: String, offset: Int): Int {
    val safe = offset.coerceIn(0, text.length)
    val before = text.lastIndexOf('\n', (safe - 1).coerceAtLeast(0))
    return safe - (before + 1) + 1
}

private fun findAllMatches(text: String, query: String, caseSensitive: Boolean): List<IntRange> {
    if (query.length < 2) return emptyList()
    val results = ArrayList<IntRange>(128)
    try {
        if (caseSensitive) {
            var i = text.indexOf(query)
            while (i >= 0 && results.size < MAX_STORED_MATCHES) {
                results += i until i + query.length
                i = text.indexOf(query, i + query.length.coerceAtLeast(1))
            }
        } else {
            Regex(Regex.escape(query), RegexOption.IGNORE_CASE).findAll(text).forEach { m ->
                if (results.size < MAX_STORED_MATCHES) results += m.range
            }
        }
    } catch (_: Throwable) {
        // Search quirks never take the editor down.
    }
    return results
}

private fun regexIgnoreCase(query: String): Regex? =
    try {
        Regex(Regex.escape(query), RegexOption.IGNORE_CASE)
    } catch (_: Throwable) {
        null
    }

private const val MAX_STORED_MATCHES = 2000
