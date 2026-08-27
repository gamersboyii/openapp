package dev.opencode.mobile.ui.chat

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.animation.animateContentSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.opencode.mobile.LocalContainer
import dev.opencode.mobile.agent.ChatEntry
import dev.opencode.mobile.agent.EntryKind
import dev.opencode.mobile.agent.ToolRun
import dev.opencode.mobile.agent.ToolStatus
import dev.opencode.mobile.ui.components.CodeBlock
import dev.opencode.mobile.ui.components.MarkdownText
import dev.opencode.mobile.ui.review.TurnReviewBar
import dev.opencode.mobile.ui.theme.MonoStyle
import dev.opencode.mobile.ui.theme.StatusWarning
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

private val Suggestions = listOf(
    "Build a landing page for a coffee shop, then preview it",
    "Create a React todo app with local storage",
    "Clone https://github.com/octocat/Hello-World",
    "What files are in this project?",
)

private val LenientJson = Json { ignoreUnknownKeys = true; isLenient = true }

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
) {
    val container = LocalContainer.current
    val agent = container.agent

    val entries by agent.entries.collectAsStateWithLifecycle()
    val isRunning by agent.isRunning.collectAsStateWithLifecycle()
    val approval by agent.pendingApproval.collectAsStateWithLifecycle()
    val review by agent.pendingReview.collectAsStateWithLifecycle()
    val settings by container.settings.settings.collectAsStateWithLifecycle()
    val project by container.workspace.activeProject.collectAsStateWithLifecycle()

    var draft by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    // Follow the tail only while the user is already there, so scrolling back
    // through history is not yanked forward by each streamed token.
    val tailSignal = entries.size to (entries.lastOrNull()?.text?.length ?: 0)
    LaunchedEffect(tailSignal) {
        if (entries.isEmpty()) return@LaunchedEffect
        val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
        if (lastVisible >= entries.size - 3) listState.animateScrollToItem(entries.lastIndex)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Column(modifier = Modifier.clickable(onClick = onOpenProjects)) {
                    Text(
                        text = project?.name ?: "No project",
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = settings.activeModel.ifBlank { "No model — open Settings" },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            },
            actions = {
                IconButton(onClick = onOpenSkills) {
                    Icon(Icons.Filled.AutoAwesome, contentDescription = "Skills")
                }
                IconButton(onClick = onOpenCheckpoints) {
                    Icon(Icons.Filled.History, contentDescription = "Checkpoints")
                }
                IconButton(onClick = onOpenPreview) {
                    Icon(Icons.Filled.Visibility, contentDescription = "Preview")
                }
                IconButton(onClick = { agent.clear() }, enabled = entries.isNotEmpty()) {
                    Icon(Icons.Filled.DeleteSweep, contentDescription = "Clear chat")
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background,
            ),
        )

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (entries.isEmpty()) {
                StarterPanel(
                    hasProvider = settings.activeProvider?.apiKey?.isNotBlank() == true,
                    onOpenSettings = onOpenSettings,
                    onPick = { draft = it },
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(items = entries, key = { it.id }) { entry ->
                        EntryRow(entry = entry, onOpenFile = onOpenFile, modifier = Modifier.animateItem())
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
            onToggleChatOnly = { value -> container.settings.update { it.copy(chatOnly = value) } },
            onSend = {
                val text = draft.trim()
                if (text.isNotEmpty()) {
                    agent.send(text)
                    draft = ""
                }
            },
            onStop = { agent.cancel() },
            onRunInBackground = { ctx ->
                // API 33+: ask once for POST_NOTIFICATIONS; the foreground
                // service starts regardless — notifications are just hidden
                // while the permission is missing.
                val granted = androidx.core.content.ContextCompat.checkSelfPermission(
                    ctx,
                    android.Manifest.permission.POST_NOTIFICATIONS,
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                if (!granted && android.os.Build.VERSION.SDK_INT >= 33) {
                    permissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                }
                dev.opencode.mobile.bg.AgentForegroundService.start(ctx)
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
private fun EntryRow(entry: ChatEntry, onOpenFile: (String) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        when (entry.kind) {
            EntryKind.USER -> UserBubble(entry.text)
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

@Composable
private fun UserBubble(text: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            shape = RoundedCornerShape(14.dp, 14.dp, 4.dp, 14.dp),
            modifier = Modifier.fillMaxWidth(0.88f),
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
    }
}

@Composable
private fun AssistantBlock(entry: ChatEntry) {
    Column(modifier = Modifier.fillMaxWidth()) {
        if (entry.reasoning.isNotBlank()) ReasoningCard(entry.reasoning, entry.streaming)

        if (entry.text.isNotBlank()) {
            MarkdownText(text = entry.text, modifier = Modifier.fillMaxWidth())
        } else if (entry.streaming) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
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

@Composable
private fun ReasoningCard(reasoning: String, streaming: Boolean) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
    ) {
        Column(modifier = Modifier.clickable { expanded = !expanded }.padding(10.dp)) {
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

@Composable
private fun ToolCard(run: ToolRun, onOpenFile: (String) -> Unit) {
    var expanded by rememberSaveable(run.callId) { mutableStateOf(false) }
    val filePath = remember(run.argumentsJson) { pathArgument(run.argumentsJson) }

    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.surface)
                .clickable { expanded = !expanded }
                .padding(10.dp),
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
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    tint: Color,
    container: Color,
) {
    Surface(color = container, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.Top) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(9.dp))
            Text(text = text, style = MaterialTheme.typography.bodySmall, color = tint)
        }
    }
}

// ---- composer + starter --------------------------------------------------

@Composable
private fun Composer(
    value: String,
    onValueChange: (String) -> Unit,
    isRunning: Boolean,
    chatOnly: Boolean,
    onToggleChatOnly: (Boolean) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onRunInBackground: (android.content.Context) -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val ctx = LocalContext.current

    Surface(color = MaterialTheme.colorScheme.surface) {
        Column(modifier = Modifier.animateContentSize()) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            // Build / Chat Only switch. Chat Only strips every project tool from
            // the model — pure conversation; use_skill still works so style
            // skills like caveman keep applying.
            Row(modifier = Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, top = 8.dp)) {
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = !chatOnly,
                        onClick = {
                            if (chatOnly) {
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onToggleChatOnly(false)
                            }
                        },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                        label = { Text("Build", style = MaterialTheme.typography.labelMedium) },
                        icon = {},
                    )
                    SegmentedButton(
                        selected = chatOnly,
                        onClick = {
                            if (!chatOnly) {
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onToggleChatOnly(true)
                            }
                        },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                        label = { Text("Chat Only", style = MaterialTheme.typography.labelMedium) },
                        icon = {},
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .imePadding()
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                TextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier.weight(1f).heightIn(max = 160.dp),
                    placeholder = {
                        Text(if (chatOnly) "Ask anything…" else "Ask OpenCode to build something…")
                    },
                    maxLines = 6,
                    shape = RoundedCornerShape(14.dp),
                    colors = TextFieldDefaults.colors(
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent,
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                    keyboardActions = KeyboardActions(onSend = { onSend() }),
                )
                Spacer(Modifier.width(8.dp))
                if (isRunning) {
                    // Background Agent Mode (feature 12): hand the running turn to a
                    // foreground service with progress notifications.
                    IconButton(onClick = { onRunInBackground(ctx) }, modifier = Modifier.size(48.dp)) {
                        Icon(
                            Icons.Filled.NotificationsActive,
                            contentDescription = "Continue in background",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    FilledIconButton(
                        onClick = onStop,
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(Icons.Filled.Stop, contentDescription = "Stop")
                    }
                } else {
                    FilledIconButton(
                        onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            onSend()
                        },
                        enabled = value.isNotBlank(),
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(Icons.Filled.Send, contentDescription = "Send")
                    }
                }
            }
        }
    }
}

@Composable
private fun StarterPanel(
    hasProvider: Boolean,
    onOpenSettings: () -> Unit,
    onPick: (String) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("OpenCode", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(6.dp))
        Text(
            text = "A coding agent that runs on this phone. It writes files, clones repos " +
                "over HTTPS and previews sites locally. No desktop, no server.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (!hasProvider) {
            Spacer(Modifier.height(16.dp))
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = RoundedCornerShape(12.dp),
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        "No API key yet",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Add any provider key — OpenRouter, Anthropic, OpenAI, Gemini, Groq, " +
                            "DeepSeek or a custom endpoint.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    Spacer(Modifier.height(10.dp))
                    Button(onClick = onOpenSettings) { Text("Open Settings") }
                }
            }
        }

        Spacer(Modifier.height(22.dp))
        Text(
            "TRY",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Suggestions.forEach { suggestion ->
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
                    .clickable { onPick(suggestion) },
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Filled.Terminal,
                        contentDescription = null,
                        modifier = Modifier.size(15.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(suggestion, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
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

// ---- helpers -------------------------------------------------------------

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
    val path = runCatching { obj["path"]?.jsonPrimitive?.content }.getOrNull() ?: return null
    return path.takeIf { it.isNotBlank() && !it.endsWith("/") }
}
