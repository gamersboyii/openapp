package dev.opencode.mobile.ui.review

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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.RateReview
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.opencode.mobile.LocalContainer
import dev.opencode.mobile.core.checkpoint.FileChange
import dev.opencode.mobile.ui.components.EmptyState
import kotlinx.coroutines.launch

/**
 * The full diff of the last agent turn. Backed by the pre-turn checkpoint, so it
 * offers a whole-turn undo, a per-file revert, and an explicit keep.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewScreen(onBack: () -> Unit) {
    val container = LocalContainer.current
    val agent = container.agent
    val checkpoints = container.checkpoints

    val review by agent.pendingReview.collectAsStateWithLifecycle()
    val project by container.workspace.activeProject.collectAsStateWithLifecycle()

    // Bumped after a per-file revert so the diff re-reads from disk.
    var refresh by remember { mutableIntStateOf(0) }

    val activeReview = review
    val activeProject = project

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Review changes") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        bottomBar = {
            if (activeReview != null) {
                Surface(color = MaterialTheme.colorScheme.surface) {
                    Column {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            OutlinedButton(
                                onClick = {
                                    agent.undoTurn()
                                    onBack()
                                },
                                modifier = Modifier.weight(1f),
                            ) {
                                Icon(Icons.Filled.Undo, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Undo turn")
                            }
                            Button(
                                onClick = {
                                    agent.acceptReview()
                                    onBack()
                                },
                                modifier = Modifier.weight(1f),
                            ) {
                                Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Keep changes")
                            }
                        }
                    }
                }
            }
        },
    ) { padding ->
        if (activeReview == null || activeProject == null) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                EmptyState(
                    icon = Icons.Filled.RateReview,
                    title = "Nothing to review",
                    message = "Changes from the last agent turn appear here until you keep or undo them.",
                )
            }
            return@Scaffold
        }

        val changes by produceState<List<FileChange>?>(
            initialValue = null,
            key1 = activeReview.checkpointId,
            key2 = refresh,
        ) {
            value = runCatching { checkpoints.diff(activeProject, activeReview.checkpointId) }
                .getOrDefault(emptyList())
        }

        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text(activeReview.label, style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = summaryLine(activeReview.fileCount, activeReview.added, activeReview.removed),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            val current = changes
            when {
                current == null -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                }

                current.isEmpty() -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    EmptyState(
                        icon = Icons.Filled.Check,
                        title = "No changes remain",
                        message = "Every file from this turn has been reverted.",
                    )
                }

                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(items = current, key = { it.path }) { change ->
                        DiffFileCard(
                            change = change,
                            loadTexts = { checkpoints.fileDiff(activeProject, activeReview.checkpointId, change.path) },
                            onRevert = {
                                container.scope.launch {
                                    checkpoints.restoreFile(activeProject, activeReview.checkpointId, change.path)
                                    container.workspace.notifyChanged()
                                    if (container.preview.state.value.running) container.preview.signalReload()
                                    refresh++
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

private fun summaryLine(files: Int, added: Int, removed: Int): String {
    val fileWord = if (files == 1) "file" else "files"
    return "$files $fileWord · +$added −$removed"
}
