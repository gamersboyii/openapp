package dev.opencode.mobile.ui.projects

import android.content.Intent
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Workspaces
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.opencode.mobile.LocalContainer
import dev.opencode.mobile.agent.Templates
import dev.opencode.mobile.core.fs.Project
import dev.opencode.mobile.core.git.GitService
import dev.opencode.mobile.ui.components.EmptyState
import dev.opencode.mobile.ui.components.relativeTime
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectsScreen(onOpenChat: () -> Unit) {
    val container = LocalContainer.current
    val workspace = container.workspace
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val projects by workspace.projects.collectAsStateWithLifecycle()
    val active by workspace.activeProject.collectAsStateWithLifecycle()
    val revision by workspace.revision.collectAsStateWithLifecycle()
    val settings by container.settings.settings.collectAsStateWithLifecycle()

    var busy by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var showCreate by remember { mutableStateOf(false) }
    var showClone by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<Project?>(null) }
    var deleting by remember { mutableStateOf<Project?>(null) }

    val now = remember(revision, projects) { System.currentTimeMillis() }

    LaunchedEffect(Unit) { workspace.refresh() }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Projects") },
            actions = {
                IconButton(onClick = { scope.launch { workspace.refresh() } }) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background,
            ),
        )

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Button(
                onClick = { showCreate = true },
                enabled = busy == null,
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("New")
            }
            OutlinedButton(
                onClick = { showClone = true },
                enabled = busy == null,
                modifier = Modifier.weight(1f),
            ) {
                Icon(
                    Icons.Filled.CloudDownload,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text("Clone")
            }
        }

        busy?.let { message ->
            Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                Text(message, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }

        error?.let { message ->
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { error = null }) { Text("Dismiss") }
                }
            }
        }

        if (projects.isEmpty() && busy == null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyState(
                    icon = Icons.Filled.Workspaces,
                    title = "No projects yet",
                    message = "Create one from a template, clone a repo over HTTPS, or just ask " +
                        "in chat and the agent will scaffold it.",
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(items = projects, key = { it.path }) { project ->
                    ProjectCard(
                        project = project,
                        isActive = project.path == active?.path,
                        subtitle = buildString {
                            append("${project.fileCount} files")
                            if (project.isGitRepo) append(" · git")
                            append(" · ")
                            append(relativeTime(project.lastModified, now))
                        },
                        onSelect = {
                            workspace.select(project)
                            onOpenChat()
                        },
                        onRename = { renaming = project },
                        onDelete = { deleting = project },
                        onExport = {
                            scope.launch {
                                busy = "Zipping ${project.name}…"
                                runCatching {
                                    val zip = workspace.exportZip(project)
                                    val uri = FileProvider.getUriForFile(
                                        context,
                                        "${context.packageName}.fileprovider",
                                        zip,
                                    )
                                    val send = Intent(Intent.ACTION_SEND)
                                        .setType("application/zip")
                                        .putExtra(Intent.EXTRA_STREAM, uri)
                                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    context.startActivity(
                                        Intent.createChooser(send, "Export ${project.name}"),
                                    )
                                }.onFailure { error = it.message ?: "Export failed" }
                                busy = null
                            }
                        },
                    )
                }
            }
        }
    }

    if (showCreate) {
        CreateProjectDialog(
            onDismiss = { showCreate = false },
            onCreate = { name, templateId ->
                showCreate = false
                scope.launch {
                    busy = "Creating $name…"
                    runCatching {
                        val template = Templates.byId(templateId)
                            ?: throw IllegalArgumentException("Unknown template $templateId")
                        val dir = workspace.createProjectDir(name)
                        template.files.forEach { (path, content) ->
                            workspace.writeText(dir, path, content)
                        }
                        workspace.refresh()
                        workspace.selectByPath(dir.absolutePath)
                        container.preview.setEntry(template.entry)
                    }.onFailure { error = it.message ?: "Could not create project" }
                    busy = null
                }
            },
        )
    }

    if (showClone) {
        CloneDialog(
            hasToken = settings.gitToken.isNotBlank(),
            onDismiss = { showClone = false },
            onClone = { url, branch, fullHistory ->
                showClone = false
                scope.launch {
                    busy = if (fullHistory) "Cloning…" else "Downloading snapshot…"
                    runCatching {
                        val name = repoNameFrom(url)
                        val dir = workspace.createProjectDir(name)
                        try {
                            if (fullHistory) {
                                container.git.clone(
                                    url = url,
                                    targetDir = dir,
                                    branch = branch,
                                    credentials = settings.gitCredentials,
                                    progress = GitService.Progress { task, percent ->
                                        busy = if (percent >= 0) "$task $percent%" else task
                                    },
                                )
                            } else {
                                container.snapshots.download(
                                    url = url,
                                    targetDir = dir,
                                    branch = branch,
                                    token = settings.gitToken.takeIf { it.isNotBlank() },
                                )
                            }
                        } catch (failure: Throwable) {
                            // A half-written directory would show up as a broken project.
                            dir.deleteRecursively()
                            throw failure
                        }
                        workspace.refresh()
                        workspace.selectByPath(dir.absolutePath)
                    }.onFailure { error = it.message ?: "Clone failed" }
                    busy = null
                }
            },
        )
    }

    renaming?.let { project ->
        TextPromptDialog(
            title = "Rename project",
            initial = project.name,
            confirmLabel = "Rename",
            onDismiss = { renaming = null },
            onConfirm = { newName ->
                renaming = null
                scope.launch {
                    if (workspace.renameProject(project, newName) == null) {
                        error = "Could not rename — a project called $newName may already exist"
                    }
                }
            },
        )
    }

    deleting?.let { project ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete ${project.name}?") },
            text = {
                Text(
                    "This removes the project directory and all ${project.fileCount} files from " +
                        "the device. It cannot be undone.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleting = null
                        scope.launch { workspace.deleteProject(project) }
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
private fun ProjectCard(
    project: Project,
    isActive: Boolean,
    subtitle: String,
    onSelect: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onExport: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }

    Surface(
        color = if (isActive) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surface
        },
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = isActive, onClick = onSelect),
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = project.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isActive) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            if (isActive) {
                Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = "Active project",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "Project actions")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Open in chat") },
                        leadingIcon = { Icon(Icons.Filled.Forum, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            onSelect()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Rename") },
                        leadingIcon = {
                            Icon(Icons.Filled.DriveFileRenameOutline, contentDescription = null)
                        },
                        onClick = {
                            menuOpen = false
                            onRename()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Export as zip") },
                        leadingIcon = { Icon(Icons.Filled.IosShare, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            onExport()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                        leadingIcon = {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                            )
                        },
                        onClick = {
                            menuOpen = false
                            onDelete()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun CreateProjectDialog(onDismiss: () -> Unit, onCreate: (String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var templateId by remember { mutableStateOf("static") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New project") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(14.dp))
                Text(
                    text = "TEMPLATE",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Column(modifier = Modifier.heightIn(max = 260.dp)) {
                    Templates.all.forEach { template ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = templateId == template.id,
                                    onClick = { templateId = template.id },
                                )
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = templateId == template.id,
                                onClick = { templateId = template.id },
                            )
                            Column(modifier = Modifier.padding(start = 4.dp)) {
                                Text(template.title, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    text = template.description,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onCreate(name.trim(), templateId) },
                enabled = name.isNotBlank(),
            ) {
                Text("Create")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun CloneDialog(
    hasToken: Boolean,
    onDismiss: () -> Unit,
    onClone: (String, String?, Boolean) -> Unit,
) {
    var url by remember { mutableStateOf("") }
    var branch by remember { mutableStateOf("") }
    var fullHistory by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Clone a repository") },
        text = {
            Column {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("HTTPS URL") },
                    placeholder = { Text("https://github.com/owner/repo") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = branch,
                    onValueChange = { branch = it },
                    label = { Text("Branch (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(14.dp))
                ModeRow(
                    selected = !fullHistory,
                    title = "Fast snapshot",
                    subtitle = "Downloads a zip of one branch. No .git, so no commit or push.",
                    onClick = { fullHistory = false },
                )
                ModeRow(
                    selected = fullHistory,
                    title = "Full clone",
                    subtitle = "Real git repo with history. Slower and larger.",
                    onClick = { fullHistory = true },
                )
                if (!hasToken) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "Public repos only until you add a git token in Settings.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onClone(url.trim(), branch.trim().ifBlank { null }, fullHistory) },
                enabled = url.trim().startsWith("https://"),
            ) {
                Text(if (fullHistory) "Clone" else "Download")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ModeRow(selected: Boolean, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column(modifier = Modifier.padding(start = 4.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TextPromptDialog(
    title: String,
    initial: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value.trim()) }, enabled = value.isNotBlank()) {
                Text(confirmLabel)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun repoNameFrom(url: String): String =
    url.trimEnd('/').substringAfterLast('/').removeSuffix(".git").ifBlank { "repo" }
