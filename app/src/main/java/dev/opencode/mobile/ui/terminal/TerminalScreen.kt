package dev.opencode.mobile.ui.terminal

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.opencode.mobile.LocalContainer
import dev.opencode.mobile.core.exec.CommandPolicy
import dev.opencode.mobile.core.exec.CommandRun
import dev.opencode.mobile.core.exec.HistoryEntry
import dev.opencode.mobile.core.exec.PolicyDecision
import dev.opencode.mobile.core.exec.RunState
import dev.opencode.mobile.core.exec.TerminalService
import dev.opencode.mobile.ui.components.EmptyState
import dev.opencode.mobile.ui.theme.MonoStyle

/**
 * User-facing console over the sandboxed terminal. Runs started here use the
 * app-lifetime scope, so navigating away does not kill them; the process list
 * keeps stop buttons available the whole time.
 *
 * Commands typed here are user-authored and skip CommandPolicy gating — the
 * policy exists to screen what the model asks for. The verdict badge is still
 * shown as a hint.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(onBack: () -> Unit) {
    val container = LocalContainer.current
    val terminal = container.terminal
    val historyStore = container.commandHistory

    val project by container.workspace.activeProject.collectAsStateWithLifecycle()
    val settings by container.settings.settings.collectAsStateWithLifecycle()
    val runs by terminal.runs.collectAsStateWithLifecycle()
    val pastCommands by historyStore.entries.collectAsStateWithLifecycle()

    var input by remember { mutableStateOf("") }
    var showHistory by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val listState = rememberLazyListState()

    LaunchedEffect(runs.size, runs.firstOrNull()?.state) {
        if (runs.isNotEmpty()) {
            runCatching { listState.animateScrollToItem(runs.size - 1) }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Column {
                    Text("Terminal", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = project?.name ?: "No project",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                }
            },
            actions = {
                if (runs.any { it.isRunning }) {
                    TextButton(onClick = terminal::stopAll) { Text("Stop all") }
                }
                IconButton(onClick = { showHistory = true }) {
                    Icon(Icons.Filled.History, contentDescription = "Command history")
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background,
            ),
        )

        if (project == null) {
            EmptyState(
                icon = Icons.Filled.Terminal,
                title = "No project open",
                message = "Open a project first — commands run inside its directory.",
            )
            return@Column
        }

        RunningBanner(runs = runs, onStop = { id -> terminal.stop(id) })

        error?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }

        RunOutputList(runs = runs.asReversed(), state = listState, modifier = Modifier.weight(1f))

        InputBar(
            input = input,
            onInputChanged = { input = it; error = null },
            enabled = true,
            canRun = runs.count { it.isRunning } < TerminalService.MAX_CONCURRENT,
            onRun = {
                val dir = project?.dir ?: return@onRun
                val name = project?.name ?: "project"
                try {
                    terminal.start(
                        command = input.trim(),
                        projectDir = dir,
                        projectName = name,
                        origin = "user",
                        timeoutSeconds = settings.commandTimeoutSeconds,
                    )
                    input = ""
                } catch (failure: IllegalStateException) {
                    error = failure.message
                }
            },
        )
    }

    if (showHistory) {
        HistoryDialog(
            entries = pastCommands,
            onPick = { command ->
                input = command
                showHistory = false
            },
            onClear = {
                historyStore.clear()
                showHistory = false
            },
            onDismiss = { showHistory = false },
        )
    }
}

@Composable
private fun RunningBanner(runs: List<CommandRun>, onStop: (String) -> Unit) {
    val running = runs.filter { it.isRunning }
    if (running.isEmpty()) return

    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
            running.forEach { run ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "● ${run.command}",
                        style = MonoStyle.copy(fontSize = MaterialTheme.typography.labelMedium.fontSize),
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { onStop(run.id) }) {
                        Icon(
                            Icons.Filled.Stop,
                            contentDescription = "Stop ${run.command}",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RunOutputList(runs: List<CommandRun>, state: LazyListState, modifier: Modifier = Modifier) {
    if (runs.isEmpty()) {
        Box(modifier = modifier) {
            EmptyState(
                icon = Icons.Filled.Terminal,
                title = "Nothing has run yet",
                message = "Type a command below. Read-only commands run immediately; " +
                    "the agent's commands are screened first.",
            )
        }
        return
    }

    LazyColumn(
        state = state,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(bottom = 12.dp),
    ) {
        items(items = runs, key = { it.id }) { run ->
            RunCard(run)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

@Composable
private fun RunCard(run: CommandRun) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "$ ${run.command}",
                style = MonoStyle.copy(fontWeight = FontWeight.SemiBold),
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState()),
            )
            StateLabel(run)
        }

        if (run.stdout.isNotBlank()) {
            Text(
                text = run.stdout.trimEnd('\n'),
                style = MonoStyle.copy(fontSize = MaterialTheme.typography.bodySmall.fontSize),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp).horizontalScroll(rememberScrollState()),
            )
        }

        if (run.stderr.isNotBlank()) {
            Text(
                text = run.stderr.trimEnd('\n'),
                style = MonoStyle.copy(fontSize = MaterialTheme.typography.bodySmall.fontSize),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp).horizontalScroll(rememberScrollState()),
            )
        }

        val notes = buildList {
            when (run.state) {
                RunState.RUNNING -> add("running…")
                RunState.TIMED_OUT -> add("timed out")
                RunState.KILLED -> add("stopped by user")
                RunState.FAILED_TO_START -> add("could not start")
                else -> add("exit code: ${run.exitCode}")
            }
            if (run.durationMs > 0) add("%.1fs".format(run.durationMs / 1000.0))
            if (run.truncatedStdout || run.truncatedStderr) add("output truncated")
        }.joinToString(" · ")

        Text(
            text = notes,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
private fun StateLabel(run: CommandRun) {
    val (label, color) = when (run.state) {
        RunState.RUNNING -> "RUNNING" to MaterialTheme.colorScheme.primary
        RunState.FINISHED ->
            if (run.exitCode == 0) "OK" to MaterialTheme.colorScheme.tertiary
            else "FAILED" to MaterialTheme.colorScheme.error
        RunState.KILLED -> "STOPPED" to MaterialTheme.colorScheme.error
        RunState.TIMED_OUT -> "TIMEOUT" to MaterialTheme.colorScheme.error
        RunState.FAILED_TO_START -> "ERROR" to MaterialTheme.colorScheme.error
    }
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
private fun InputBar(
    input: String,
    onInputChanged: (String) -> Unit,
    enabled: Boolean,
    canRun: Boolean,
    onRun: () -> Unit,
) {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

    val verdict = remember(input) {
        if (input.isBlank()) null else CommandPolicy.classify(input)
    }

    Column {
        if (verdict != null) {
            val (label, color) = when (verdict.decision) {
                PolicyDecision.SAFE -> "safe · runs without approval" to MaterialTheme.colorScheme.tertiary
                PolicyDecision.ASK -> "asks for confirmation" to MaterialTheme.colorScheme.secondary
                PolicyDecision.BLOCK -> "would be blocked: ${verdict.reason}" to MaterialTheme.colorScheme.error
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = color,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = onInputChanged,
                placeholder = { Text("$ ls", style = MonoStyle) },
                singleLine = true,
                enabled = enabled,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(4.dp))
            IconButton(onClick = onRun, enabled = enabled && canRun && input.isNotBlank()) {
                Icon(Icons.Filled.PlayArrow, contentDescription = "Run command")
            }
        }
    }
}

@Composable
private fun HistoryDialog(
    entries: List<HistoryEntry>,
    onPick: (String) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Command history") },
        text = {
            if (entries.isEmpty()) {
                Text("No commands yet.")
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 380.dp)) {
                    items(items = entries) { entry ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPick(entry.command) }
                                .padding(vertical = 8.dp),
                        ) {
                            Text(
                                text = entry.command,
                                style = MonoStyle.copy(
                                    fontSize = MaterialTheme.typography.bodySmall.fontSize,
                                ),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = "${entry.projectName} · exit ${entry.exitCode ?: "-"} · %.1fs"
                                    .format(entry.durationMs / 1000.0),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        dismissButton = { TextButton(onClick = onClear) { Text("Clear history") } },
    )
}
