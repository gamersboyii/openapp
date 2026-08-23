package dev.opencode.mobile.ui.checkpoints

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.opencode.mobile.LocalContainer
import dev.opencode.mobile.core.checkpoint.Checkpoint
import dev.opencode.mobile.core.checkpoint.FileChange
import dev.opencode.mobile.core.fs.Project
import dev.opencode.mobile.ui.components.EmptyState
import dev.opencode.mobile.ui.components.formatBytes
import dev.opencode.mobile.ui.components.relativeTime
import dev.opencode.mobile.ui.review.DiffFileCard
import kotlinx.coroutines.launch

private enum class DiffMode { SINCE_NOW, VS_PREVIOUS }

/**
 * The checkpoint history for the active project: restore a whole snapshot, delete
 * one, or expand it to see what changed since (against the live tree or the
 * previous checkpoint).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CheckpointsScreen(onBack: () -> Unit) {
    val container = LocalContainer.current
    val checkpoints = container.checkpoints

    val items by checkpoints.checkpoints.collectAsStateWithLifecycle()
    val project by container.workspace.activeProject.collectAsStateWithLifecycle()

    val snackbar = remember { SnackbarHostState() }
    var restoreTarget by remember { mutableStateOf<Checkpoint?>(null) }
    var deleteTarget by remember { mutableStateOf<Checkpoint?>(null) }

    val activeProject = project

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Checkpoints") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            if (activeProject != null) {
                ExtendedFloatingActionButton(
                    onClick = {
                        container.scope.launch {
                            val cp = runCatching {
                                checkpoints.capture(activeProject, label = "Manual checkpoint", reason = "Saved by hand")
                            }.getOrNull()
                            snackbar.showSnackbar(
                                if (cp != null) "Saved ${cp.label}."
                                else "Project is too large to checkpoint.",
                            )
                        }
                    },
                    icon = { Icon(Icons.Filled.AddCircle, contentDescription = null) },
                    text = { Text("Save checkpoint") },
                )
            }
        },
    ) { padding ->
        if (activeProject == null || items.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                EmptyState(
                    icon = Icons.Filled.History,
                    title = if (activeProject == null) "No project open" else "No checkpoints yet",
                    message = if (activeProject == null) {
                        "Open a project to see its checkpoint history."
                    } else {
                        "A checkpoint is saved automatically before the agent's first change each turn."
                    },
                )
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            itemsIndexed(items = items, key = { _, cp -> cp.id }) { index, checkpoint ->
                CheckpointCard(
                    checkpoint = checkpoint,
                    project = activeProject,
                    olderId = items.getOrNull(index + 1)?.id,
                    onRestore = { restoreTarget = checkpoint },
                    onDelete = { deleteTarget = checkpoint },
                )
            }
        }
    }

    val toRestore = restoreTarget
    if (toRestore != null) {
        AlertDialog(
            onDismissRequest = { restoreTarget = null },
            title = { Text("Restore ${toRestore.label}?") },
            text = {
                Text(
                    "This rewrites the project's files to match the checkpoint. Files created " +
                        "afterwards are removed. Your chat history is kept.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    restoreTarget = null
                    if (activeProject != null) {
                        container.scope.launch {
                            val n = runCatching { checkpoints.restore(activeProject, toRestore.id) }.getOrDefault(-1)
                            container.workspace.notifyChanged()
                            if (container.preview.state.value.running) container.preview.signalReload()
                            snackbar.showSnackbar(
                                if (n >= 0) "Restored $n files from ${toRestore.label}."
                                else "Could not restore — the checkpoint may be incomplete.",
                            )
                        }
                    }
                }) { Text("Restore") }
            },
            dismissButton = { TextButton(onClick = { restoreTarget = null }) { Text("Cancel") } },
        )
    }

    val toDelete = deleteTarget
    if (toDelete != null) {
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete ${toDelete.label}?") },
            text = { Text("The snapshot is removed. Files on disk are not touched.") },
            confirmButton = {
                TextButton(onClick = {
                    deleteTarget = null
                    if (activeProject != null) {
                        container.scope.launch {
                            checkpoints.delete(activeProject, toDelete.id)
                            snackbar.showSnackbar("Deleted ${toDelete.label}.")
                        }
                    }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun CheckpointCard(
    checkpoint: Checkpoint,
    project: Project,
    olderId: Long?,
    onRestore: () -> Unit,
    onDelete: () -> Unit,
) {
    val container = LocalContainer.current
    val checkpoints = container.checkpoints
    var expanded by rememberSaveable(checkpoint.id) { mutableStateOf(false) }
    var mode by remember(checkpoint.id) { mutableStateOf(DiffMode.SINCE_NOW) }
    val now = remember(checkpoint.id) { System.currentTimeMillis() }

    Surface(
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(4.dp)) {
            Row(
                modifier = Modifier.clickable { expanded = !expanded }.padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(checkpoint.label, style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = metaLine(checkpoint, now),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (checkpoint.reason.isNotBlank()) {
                        Text(
                            text = checkpoint.reason,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                IconButton(onClick = onRestore) {
                    Icon(Icons.Filled.Restore, contentDescription = "Restore", tint = MaterialTheme.colorScheme.primary)
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(
                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.padding(horizontal = 10.dp).padding(bottom = 8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = mode == DiffMode.SINCE_NOW,
                            onClick = { mode = DiffMode.SINCE_NOW },
                            label = { Text("Since now") },
                        )
                        FilterChip(
                            selected = mode == DiffMode.VS_PREVIOUS,
                            onClick = { if (olderId != null) mode = DiffMode.VS_PREVIOUS },
                            enabled = olderId != null,
                            label = { Text("vs previous") },
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    CheckpointDiff(
                        project = project,
                        checkpointId = checkpoint.id,
                        olderId = olderId,
                        mode = mode,
                    )
                }
            }
        }
    }
}

@Composable
private fun CheckpointDiff(
    project: Project,
    checkpointId: Long,
    olderId: Long?,
    mode: DiffMode,
) {
    val container = LocalContainer.current
    val checkpoints = container.checkpoints
    val base = if (mode == DiffMode.VS_PREVIOUS) olderId else null

    val changes by androidx.compose.runtime.produceState<List<FileChange>?>(
        initialValue = null,
        key1 = checkpointId,
        key2 = base,
    ) {
        value = runCatching {
            if (base != null) checkpoints.compare(project, base, checkpointId)
            else checkpoints.diff(project, checkpointId)
        }.getOrDefault(emptyList())
    }

    val current = changes
    when {
        current == null -> Row(
            modifier = Modifier.padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
            Text("Computing diff…", style = MaterialTheme.typography.bodySmall)
        }

        current.isEmpty() -> Text(
            text = if (base != null) "Identical to the previous checkpoint." else "No changes since this checkpoint.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 4.dp),
        )

        else -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            current.forEach { change ->
                DiffFileCard(
                    change = change,
                    loadTexts = {
                        if (base != null) checkpoints.compareFileTexts(project, base, checkpointId, change.path)
                        else checkpoints.fileDiff(project, checkpointId, change.path)
                    },
                )
            }
        }
    }
}

private fun metaLine(checkpoint: Checkpoint, now: Long): String {
    val fileWord = if (checkpoint.fileCount == 1) "file" else "files"
    return "${relativeTime(checkpoint.createdAt, now)} · ${checkpoint.fileCount} $fileWord · ${formatBytes(checkpoint.totalBytes)}"
}
