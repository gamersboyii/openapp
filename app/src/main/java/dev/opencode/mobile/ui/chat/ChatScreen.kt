package dev.opencode.mobile.ui.chat

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.opencode.mobile.LocalContainer
import dev.opencode.mobile.agent.ChatEntry
import dev.opencode.mobile.agent.EntryKind
import dev.opencode.mobile.agent.ToolRun
import dev.opencode.mobile.agent.ToolStatus
import dev.opencode.mobile.ui.components.CodeBlock
import dev.opencode.mobile.ui.components.MarkdownText
import dev.opencode.mobile.ui.components.SparkleAvatar
import dev.opencode.mobile.ui.components.UserAvatar
import dev.opencode.mobile.ui.review.TurnReviewBar
import dev.opencode.mobile.ui.theme.MonoStyle
import dev.opencode.mobile.ui.theme.StatusWarning
import dev.opencode.mobile.llm.safePrim
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull

private val Suggestions = listOf(
    "Build a landing page for a coffee shop, then preview it",
    "Create a React todo app with local storage",
    "Clone https://github.com/octocat/Hello-World",
    "What files are in this project?",
)

private val LenientJson = Json { ignoreUnknownKeys = true; isLenient = true }

/**
 * The conversation canvas, laid out exactly like the official ChatGPT app:
 * open messages on a flat background (no bubbles), the user's text right
 * aligned in white with a circular initial avatar, assistant text left
 * aligned in muted off-white behind a sparkle mark, and a single floating
 * capsule composer at the bottom.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    onOpenSettings: () -> Unit,
    onOpenProjects: () -> Unit,
    onOpenPreview: () -> Unit,
    onOpenFile: (String) -> Unit,
    onOpenReview: () -> Unit,
    onOpenCheckpoints: () -> Unit,
    onOpenSkills: () -> Unit = {},
    onOpenSessions: () -> Unit = {},
) {
    val container = LocalContainer.current
    val agent = container.agent

    val entries by agent.entries.collectAsStateWithLifecycle()
    val isRunning by agent.isRunning.collectAsStateWithLifecycle()
    val sessions by agent.sessions.collectAsStateWithLifecycle()
    val approval by agent.pendingApproval.collectAsStateWithLifecycle()
    val review by agent.pendingReview.collectAsStateWithLifecycle()
    val settings by container.settings.settings.collectAsStateWithLifecycle()
    val project by container.workspace.activeProject.collectAsStateWithLifecycle()

    var draft by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()
    val ctx = LocalContext.current

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    // ---- attachments --------------------------------------------------------

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        val rel = uri?.let { importToUploads(ctx, it, project?.path) }
        if (rel != null) draft = appendMention(draft, rel)
    }

    var pendingCapture by remember { mutableStateOf<File?>(null) }
    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { ok ->
        val src = pendingCapture
        pendingCapture = null
        val projectPath = project?.path
        if (ok && src != null && projectPath != null) {
            runCatching {
                val uploads = File(projectPath, "uploads").apply { mkdirs() }
                val dest = File(uploads, "capture-${System.currentTimeMillis()}.jpg")
                src.copyTo(dest, overwrite = true)
                draft = appendMention(draft, "uploads/${dest.name}")
            }
        }
    }

    val speechLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val text = result.data
            ?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
        if (!text.isNullOrBlank()) draft = if (draft.isBlank()) text else "$draft $text"
    }

    // Follow the tail only while the user is already there, so scrolling back
    // through history is not yanked forward by each streamed token. A new entry
    // animates smoothly; text growing inside the last entry snaps instead —
    // restarting an animation on every token is what made streaming feel slow.
    val lastEntry = entries.lastOrNull()
    val tailSignal = entries.size to ((lastEntry?.text?.length ?: 0) + (lastEntry?.reasoning?.length ?: 0))
    var lastEntryCount by remember { mutableStateOf(-1) }
    LaunchedEffect(tailSignal) {
        if (entries.isEmpty()) return@LaunchedEffect
        val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
        if (lastVisible >= entries.size - 3) {
            if (entries.size != lastEntryCount) {
                listState.animateScrollToItem(entries.lastIndex)
            } else {
                listState.scrollToItem(entries.lastIndex)
            }
        }
        lastEntryCount = entries.size
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (entries.isEmpty()) {
                StarterPanel(
                    hasProvider = settings.activeProvider?.apiKey?.isNotBlank() == true,
                    sessionCount = sessions.size,
                    onOpenSettings = onOpenSettings,
                    onOpenSessions = onOpenSessions,
                    onPick = { draft = it },
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(22.dp),
                ) {
                    items(items = entries, key = { it.id }) { entry ->
                        EntryRow(
                            entry = entry,
                            initial = userInitial(settings.githubLogin),
                            onOpenFile = onOpenFile,
                        )
                    }
                }
            }
        }

        val currentReview = review
        if (currentReview != null && !isRunning) {
            TurnReviewBar(
                review = currentReview,
                onReview = onOpenReview,
                onAccept = { agent.acceptReview() },
                onRejectAll = { agent.undoTurn() },
            )
        }

        Composer(
        value = draft,
        onValueChange = { draft = it },
        isRunning = isRunning,
        chatOnly = settings.chatOnly,
        hasProject = project != null,
        onPickFile = {
            runCatching {
                filePicker.launch(arrayOf("*/*"))
            }
        },
        onTakePhoto = {
            val shot = File(ctx.cacheDir, "exports/capture-${System.currentTimeMillis()}.jpg")
                .apply { parentFile?.mkdirs() }
            pendingCapture = shot
            runCatching {
                val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", shot)
                cameraLauncher.launch(uri)
            }
        },
        onVoice = {
            val intent = Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                )
            }
            runCatching { speechLauncher.launch(intent) }
        },
        onSend = {
            val text = draft.trim()
            if (text.isNotEmpty()) {
                agent.send(text)
                draft = ""
            }
        },
        onStop = { agent.cancel() },
        onRunInBackground = { context ->
            // API 33+: ask once for POST_NOTIFICATIONS; the foreground
            // service starts regardless — notifications are just hidden
            // while the permission is missing.
            val granted = androidx.core.content.ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.POST_NOTIFICATIONS,
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!granted && android.os.Build.VERSION.SDK_INT >= 33) {
                permissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
            dev.opencode.mobile.bg.AgentForegroundService.start(context)
        },
    )
    }

    val request = approval
    if (request != null) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { agent.respondToApproval(false) },
            sheetState = sheetState,
        ) {
            ApprovalSheet(
                toolName = request.toolName,
                summary = request.summary,
                detail = request.detail,
                onAllow = { agent.respondToApproval(true) },
                onDeny = { agent.respondToApproval(false) },
                onAlwaysAllow = {
                    container.settings.update { it.copy(autoApproveWrites = true) }
                    agent.respondToApproval(true)
                },
            )
        }
    }
}

// ---- entries --------------------------------------------------------------

@Composable
private fun EntryRow(
    entry: ChatEntry,
    initial: String,
    onOpenFile: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        when (entry.kind) {
            EntryKind.USER -> UserRow(entry.text, initial)
            EntryKind.ASSISTANT -> AssistantBlock(entry)
            EntryKind.TOOL -> entry.toolRun?.let { ToolCard(it, onOpenFile) }
            EntryKind.ERROR -> BannerRow(
                icon = Icons.Filled.ErrorOutline,
                text = entry.text,
                tint = MaterialTheme.colorScheme.error,
                container = MaterialTheme.colorScheme.errorContainer,
            )

            EntryKind.NOTICE -> BannerRow(
                icon = Icons.Filled.Info,
                text = entry.text,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                container = MaterialTheme.colorScheme.surfaceVariant,
            )
        }
    }
}

/** User message: right-aligned white text with the circular avatar beside it. */
@Composable
private fun UserRow(text: String, initial: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = Color.White,
            modifier = Modifier.widthIn(max = 300.dp),
        )
        Spacer(Modifier.width(10.dp))
        UserAvatar(initial = initial)
    }
}

/** Assistant message: sparkle mark on the left, muted off-white prose. */
@Composable
private fun AssistantBlock(entry: ChatEntry) {
    Row(modifier = Modifier.fillMaxWidth()) {
        SparkleAvatar(
            size = 20.dp,
            modifier = Modifier.padding(top = 3.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            if (entry.reasoning.isNotBlank()) ReasoningCard(entry.reasoning, entry.streaming)

            if (entry.text.isNotBlank()) {
                MarkdownText(text = entry.text, modifier = Modifier.fillMaxWidth())
            } else if (entry.streaming) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = "Thinking…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun ReasoningCard(reasoning: String, streaming: Boolean) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
    ) {
        Column(modifier = Modifier.clickable { expanded = !expanded }.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Insights,
                    contentDescription = null,
                    modifier = Modifier.size(15.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (streaming) "Reasoning…" else "Reasoning",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            AnimatedVisibility(visible = expanded) {
                Text(
                    text = reasoning,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

/** Flat floating card for a tool run — no borders, one step above the canvas. */
@Composable
private fun ToolCard(run: ToolRun, onOpenFile: (String) -> Unit) {
    var expanded by rememberSaveable(run.callId) { mutableStateOf(false) }
    val filePath = remember(run.argumentsJson) { pathArgument(run.argumentsJson) }

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .clickable { expanded = !expanded }
                .padding(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ToolStatusIcon(run.status)
                Spacer(Modifier.width(9.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = run.summary,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = run.name + statusSuffix(run.status),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.padding(top = 8.dp)) {
                    CodeBlock(code = prettyArgs(run.argumentsJson), language = "json")
                    if (run.result.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = run.result.take(4000),
                            style = MonoStyle,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                .horizontalScroll(rememberScrollState())
                                .padding(10.dp),
                        )
                    }
                    if (filePath != null) {
                        TextButton(onClick = { onOpenFile(filePath) }) { Text("Open $filePath") }
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolStatusIcon(status: ToolStatus) {
    when (status) {
        ToolStatus.RUNNING -> CircularProgressIndicator(
            modifier = Modifier.size(15.dp),
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        ToolStatus.AWAITING_APPROVAL -> Icon(
            Icons.Filled.Warning,
            contentDescription = null,
            tint = StatusWarning,
            modifier = Modifier.size(16.dp),
        )

        ToolStatus.DONE -> Icon(
            Icons.Filled.Check,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.size(16.dp),
        )

        ToolStatus.FAILED -> Icon(
            Icons.Filled.Close,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(16.dp),
        )

        ToolStatus.DENIED -> Icon(
            Icons.Filled.Block,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
    }
}

private fun statusSuffix(status: ToolStatus): String = when (status) {
    ToolStatus.AWAITING_APPROVAL -> " · waiting for approval"
    ToolStatus.RUNNING -> " · running"
    ToolStatus.DONE -> ""
    ToolStatus.FAILED -> " · failed"
    ToolStatus.DENIED -> " · declined"
}

@Composable
private fun BannerRow(
    icon: ImageVector,
    text: String,
    tint: Color,
    container: Color,
) {
    Surface(color = container, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(9.dp))
            Text(text = text, style = MaterialTheme.typography.bodySmall, color = tint)
        }
    }
}

// ---- composer -------------------------------------------------------------

/**
 * The floating capsule: rounded pill (#2F2F2F) holding, left to right, a plus
 * button, a camera button, a borderless "Message" field, and a trailing slot
 * that is a soundwave voice button while empty and an upright send arrow once
 * there is text. While the agent runs the slot becomes stop (+ background), and
 * if the user types mid-run it shows stop + send — the message is queued and
 * fires the moment the turn ends.
 */
@Composable
private fun Composer(
    value: String,
    onValueChange: (String) -> Unit,
    isRunning: Boolean,
    chatOnly: Boolean,
    hasProject: Boolean,
    onPickFile: () -> Unit,
    onTakePhoto: () -> Unit,
    onVoice: () -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onRunInBackground: (android.content.Context) -> Unit,
) {
    var attachMenuOpen by remember { mutableStateOf(false) }
    val ctx = LocalContext.current

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .imePadding()
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(28.dp),
            shadowElevation = 3.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                verticalAlignment = Alignment.Bottom,
                modifier = Modifier.padding(start = 2.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
            ) {
                // Far left: plus button for attachments.
                Box {
                    IconButton(onClick = { attachMenuOpen = true }) {
                        Icon(
                            Icons.Filled.Add,
                            contentDescription = "Add",
                            tint = MaterialTheme.colorScheme.onBackground,
                        )
                    }
                    DropdownMenu(
                        expanded = attachMenuOpen,
                        onDismissRequest = { attachMenuOpen = false },
                        shape = RoundedCornerShape(16.dp),
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ) {
                        DropdownMenuItem(
                            text = { Text("Attach file") },
                            onClick = {
                                attachMenuOpen = false
                                onPickFile()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Take photo") },
                            onClick = {
                                attachMenuOpen = false
                                onTakePhoto()
                            },
                        )
                        if (isRunning) {
                            DropdownMenuItem(
                                text = { Text("Continue in background") },
                                leadingIcon = {
                                    Icon(Icons.Filled.NotificationsActive, contentDescription = null)
                                },
                                onClick = {
                                    attachMenuOpen = false
                                    onRunInBackground(ctx)
                                },
                            )
                        }
                        if (!hasProject) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        "Create a project first (Projects tab)",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                },
                                onClick = {
                                    attachMenuOpen = false
                                },
                            )
                        }
                    }
                }

                // Mid left: camera.
                IconButton(onClick = onTakePhoto) {
                    Icon(
                        Icons.Filled.PhotoCamera,
                        contentDescription = "Camera",
                        tint = MaterialTheme.colorScheme.onBackground,
                    )
                }

                // Center: borderless message field.
                TextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier.weight(1f).heightIn(max = 160.dp),
                    placeholder = {
                        Text(
                            text = "Message",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    maxLines = 6,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        disabledContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent,
                        cursorColor = MaterialTheme.colorScheme.onBackground,
                        focusedTextColor = MaterialTheme.colorScheme.onBackground,
                        unfocusedTextColor = MaterialTheme.colorScheme.onBackground,
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                    keyboardActions = KeyboardActions(onSend = { onSend() }),
                )

                // Far right: voice when empty, send when there is text. While a
                // turn runs, typing shows stop + send — the message is displayed
                // immediately and queued behind the running turn.
                when {
                    isRunning && value.isNotBlank() -> {
                        RoundAction(
                            icon = Icons.Filled.Stop,
                            contentDescription = "Stop",
                            onClick = onStop,
                        )
                        RoundAction(
                            icon = Icons.Filled.ArrowUpward,
                            contentDescription = "Queue message",
                            onClick = onSend,
                        )
                    }

                    isRunning -> {
                        IconButton(onClick = { onRunInBackground(ctx) }) {
                            Icon(
                                Icons.Filled.NotificationsActive,
                                contentDescription = "Continue in background",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        RoundAction(
                            icon = Icons.Filled.Stop,
                            contentDescription = "Stop",
                            onClick = onStop,
                        )
                    }

                    value.isNotBlank() -> RoundAction(
                        icon = Icons.Filled.ArrowUpward,
                        contentDescription = "Send",
                        onClick = onSend,
                    )

                    else -> IconButton(onClick = onVoice) {
                        Icon(
                            Icons.Filled.GraphicEq,
                            contentDescription = "Voice mode",
                            tint = MaterialTheme.colorScheme.onBackground,
                        )
                    }
                }
            }
        }
    }
}

/** ChatGPT-style circular trailing action: filled with the inverse color. */
@Composable
private fun RoundAction(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .padding(start = 2.dp, bottom = 6.dp, top = 6.dp, end = 2.dp)
            .size(36.dp)
            .background(MaterialTheme.colorScheme.primary, CircleShape)
            .clickable(onClick = onClick),
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size(20.dp),
        )
    }
}

// ---- starter --------------------------------------------------------------

@Composable
private fun StarterPanel(
    hasProvider: Boolean,
    sessionCount: Int,
    onOpenSettings: () -> Unit,
    onOpenSessions: () -> Unit,
    onPick: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                SparkleAvatar(size = 46.dp)
                Spacer(Modifier.height(16.dp))
                Text(
                    text = "opencode",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "A coding agent that runs on this phone. It writes files, " +
                        "clones repos over HTTPS and previews sites locally.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )

                if (!hasProvider) {
                    Spacer(Modifier.height(18.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.clickable(onClick = onOpenSettings),
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Text(
                                "No API key yet",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onBackground,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "Tap to add any provider — OpenRouter, Anthropic, OpenAI, " +
                                    "Gemini, Groq, DeepSeek or a custom endpoint.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                if (sessionCount > 0) {
                    Spacer(Modifier.height(10.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenSessions),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Filled.History,
                                contentDescription = null,
                                modifier = Modifier.size(15.dp),
                                tint = MaterialTheme.colorScheme.secondary,
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                "Past sessions ($sessionCount) — continue where you left off",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onBackground,
                            )
                        }
                    }
                }
            }
        }

        Text(
            "TRY",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        Suggestions.forEach { suggestion ->
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
                    .clickable { onPick(suggestion) },
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Filled.Terminal,
                        contentDescription = null,
                        modifier = Modifier.size(15.dp),
                        tint = MaterialTheme.colorScheme.secondary,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        suggestion,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
    }
}

@Composable
private fun ApprovalSheet(
    toolName: String,
    summary: String,
    detail: String,
    onAllow: () -> Unit,
    onDeny: () -> Unit,
    onAlwaysAllow: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 28.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Warning, contentDescription = null, tint = StatusWarning)
            Spacer(Modifier.width(10.dp))
            Text("Approve action", style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = toolName,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        Text(summary, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(12.dp))
        Text(
            text = detail,
            style = MonoStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 280.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .horizontalScroll(rememberScrollState())
                .padding(12.dp),
        )
        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = onDeny, modifier = Modifier.weight(1f)) { Text("Deny") }
            Button(onClick = onAllow, modifier = Modifier.weight(1f)) { Text("Allow") }
        }
        TextButton(onClick = onAlwaysAllow, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text("Allow all writes from now on")
        }
    }
}

// ---- helpers --------------------------------------------------------------

private val PrettyJson = Json { prettyPrint = true }

private fun prettyArgs(raw: String): String {
    if (raw.isBlank()) return "{}"
    return runCatching {
        val element = LenientJson.parseToJsonElement(raw)
        PrettyJson.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), element)
    }.getOrDefault(raw)
}

/** Pulls a `path` argument out so the tool card can offer to open the file. */
private fun pathArgument(raw: String): String? {
    val obj = runCatching { LenientJson.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: return null
    val path = obj["path"].safePrim?.contentOrNull ?: return null
    return path.takeIf { it.isNotBlank() && !it.endsWith("/") }
}

private fun userInitial(githubLogin: String): String =
    githubLogin.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "U"

private fun appendMention(draft: String, relPath: String): String {
    val mention = "[attached: $relPath]"
    return if (draft.isBlank()) mention else "$draft $mention"
}

/** Copies a picked/captured file into the active project's uploads/ folder. */
private fun importToUploads(context: Context, uri: Uri, projectPath: String?): String? {
    if (projectPath.isNullOrBlank()) return null
    return runCatching {
        val resolver = context.contentResolver
        val queried = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        val name = queried ?: "file-${System.currentTimeMillis()}"
        val safe = name.replace(Regex("[^A-Za-z0-9._ -]"), "_").ifBlank { "file" }
        val uploads = File(projectPath, "uploads").apply { mkdirs() }
        var dest = File(uploads, safe)
        var n = 1
        while (dest.exists()) {
            val dot = safe.lastIndexOf('.')
            dest = if (dot > 0) {
                File(uploads, "${safe.substring(0, dot)}-$n${safe.substring(dot)}")
            } else {
                File(uploads, "$safe-$n")
            }
            n++
        }
        resolver.openInputStream(uri)?.use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
        } ?: return null
        "uploads/${dest.name}"
    }.getOrNull()
}
