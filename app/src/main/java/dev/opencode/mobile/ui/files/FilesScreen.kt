package dev.opencode.mobile.ui.files

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.opencode.mobile.LocalContainer
import dev.opencode.mobile.core.fs.FileNode
import dev.opencode.mobile.core.fs.WorkspaceManager
import dev.opencode.mobile.ui.components.EmptyState
import dev.opencode.mobile.ui.components.formatBytes
import dev.opencode.mobile.ui.theme.MonoStyle
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesScreen(onOpenFile: (String) -> Unit, onOpenTerminal: () -> Unit = {}) {
    val container = LocalContainer.current
    val workspace = container.workspace
    val scope = rememberCoroutineScope()

    val project by workspace.activeProject.collectAsStateWithLifecycle()
    val revision by workspace.revision.collectAsStateWithLifecycle()

    var path by remember { mutableStateOf("") }
    var nodes by remember { mutableStateOf<List<FileNode>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var matches by remember { mutableStateOf<List<WorkspaceManager.Match>>(emptyList()) }
    var newFile by remember { mutableStateOf(false) }
    var newFolder by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<FileNode?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    // Reset to the root when the project changes; a stale subdirectory would 404.
    LaunchedEffect(project?.path) { path = "" }

    LaunchedEffect(project?.path, path, revision) {
        val dir = project?.dir
        nodes = if (dir == null) {
            emptyList()
        } else {
            runCatching { workspace.listDirectory(dir, path) }.getOrElse { emptyList() }
        }
    }

    BackHandler(enabled = path.isNotEmpty() || searching) {
        when {
            searching -> {
                searching = false
                query = ""
                matches = emptyList()
            }

            else -> path = path.substringBeforeLast('/', "").takeIf { it != path }.orEmpty()
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Column {
                    Text(
                        text = project?.name ?: "No project",
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = if (path.isBlank()) "root" else path,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            },
            actions = {
                IconButton(onClick = onOpenTerminal, enabled = project != null) {
                    Icon(Icons.Filled.Terminal, contentDescription = "Terminal")
                }
                IconButton(onClick = { searching = !searching }) {
                    Icon(
                        if (searching) Icons.Filled.Close else Icons.Filled.Search,
                        contentDescription = if (searching) "Close search" else "Search code",
                    )
                }
                IconButton(onClick = { newFile = true }, enabled = project != null) {
                    Icon(Icons.Filled.NoteAdd, contentDescription = "New file")
                }
                IconButton(onClick = { newFolder = true }, enabled = project != null) {
                    Icon(Icons.Filled.CreateNewFolder, contentDescription = "New folder")
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background,
            ),
        )

        if (project == null) {
            EmptyState(
                icon = Icons.Filled.FolderOpen,
                title = "No project open",
                message = "Pick or create a project first — the Projects tab is next door.",
            )
            return@Column
        }

        if (searching) {
            OutlinedTextField(
                value = query,
                onValueChange = { value ->
                    query = value
                    scope.launch {
                        val dir = project?.dir
                        matches = if (dir == null || value.length < 2) {
                            emptyList()
                        } else {
                            runCatching { workspace.search(dir, value) }.getOrElse { emptyList() }
                        }
                    }
                },
                label = { Text("Search file contents") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(items = matches, key = { "${it.path}:${it.line}" }) { match ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpenFile(match.path) }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        Text(
                            text = "${match.path}:${match.line}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = match.text,
                            style = MonoStyle,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                        )
                    }
                }
                if (matches.isEmpty() && query.length >= 2) {
                    item {
                        Text(
                            text = "No matches.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
            }
            return@Column
        }

        error?.let { message ->
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            ) {
                Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { error = null }) { Text("OK") }
                }
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            if (path.isNotBlank()) {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { path = path.substringBeforeLast('/', "") }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Filled.ArrowUpward,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(12.dp))
                        Text("Up one level", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            items(items = nodes, key = { it.relativePath }) { node ->
                FileRow(
                    node = node,
                    onClick = {
                        if (node.isDirectory) path = node.relativePath else onOpenFile(node.relativePath)
                    },
                    onDelete = { deleting = node },
                )
            }

            if (nodes.isEmpty()) {
                item {
                    Box(modifier = Modifier.fillMaxWidth().padding(top = 40.dp)) {
                        EmptyState(
                            icon = Icons.Filled.Folder,
                            title = "Empty folder",
                            message = "Nothing here yet. Create a file, or ask the agent to " +
                                "scaffold something.",
                        )
                    }
                }
            }
        }
    }

    if (newFile) {
        NameDialog(
            title = "New file",
            placeholder = "index.html",
            onDismiss = { newFile = false },
            onConfirm = { name ->
                newFile = false
                val dir = project?.dir ?: return@NameDialog
                val target = if (path.isBlank()) name else "$path/$name"
                scope.launch {
                    runCatching { workspace.writeText(dir, target, "") }
                        .onSuccess { onOpenFile(target) }
                        .onFailure { error = it.message ?: "Could not create $target" }
                }
            },
        )
    }

    if (newFolder) {
        NameDialog(
            title = "New folder",
            placeholder = "components",
            onDismiss = { newFolder = false },
            onConfirm = { name ->
                newFolder = false
                val dir = project?.dir ?: return@NameDialog
                val target = if (path.isBlank()) name else "$path/$name"
                scope.launch {
                    runCatching { workspace.createDirectory(dir, target) }
                        .onFailure { error = it.message ?: "Could not create $target" }
                }
            },
        )
    }

    deleting?.let { node ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete ${node.name}?") },
            text = {
                Text(
                    if (node.isDirectory) {
                        "Deletes the folder and everything inside it. This cannot be undone."
                    } else {
                        "Deletes this file. This cannot be undone."
                    },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleting = null
                        val dir = project?.dir
                        if (dir != null) {
                            scope.launch {
                                runCatching { workspace.delete(dir, node.relativePath) }
                                    .onFailure { error = it.message ?: "Delete failed" }
                            }
                        }
                    },
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun FileRow(node: FileNode, onClick: () -> Unit, onDelete: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (node.isDirectory) Icons.Filled.Folder else Icons.Filled.Description,
            contentDescription = null,
            tint = if (node.isDirectory) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = node.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!node.isDirectory) {
                Text(
                    text = formatBytes(node.sizeBytes),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        IconButton(onClick = onDelete) {
            Icon(
                Icons.Filled.Delete,
                contentDescription = "Delete ${node.name}",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun NameDialog(
    title: String,
    placeholder: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    placeholder = { Text(placeholder) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Nested paths work too: src/components/Card.jsx",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(value.trim().trim('/')) },
                enabled = value.isNotBlank() && !value.contains(".."),
            ) {
                Text("Create")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
