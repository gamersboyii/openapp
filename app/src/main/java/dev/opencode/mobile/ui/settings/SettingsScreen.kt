package dev.opencode.mobile.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.opencode.mobile.LocalContainer
import dev.opencode.mobile.core.settings.ThemeMode
import dev.opencode.mobile.llm.ProviderConfig
import dev.opencode.mobile.llm.ProviderPreset
import dev.opencode.mobile.llm.ProviderPresets
import dev.opencode.mobile.llm.ProviderRegistry
import dev.opencode.mobile.ui.components.SectionHeader
import dev.opencode.mobile.ui.components.SettingSwitch
import dev.opencode.mobile.ui.components.rememberUrlOpener
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onOpenSkills: () -> Unit = {}) {
    val container = LocalContainer.current
    val store = container.settings
    val settings by store.settings.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val openUrl = rememberUrlOpener()

    var editing by remember { mutableStateOf<ProviderConfig?>(null) }
    var showPresets by remember { mutableStateOf(false) }
    var fetching by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var showPromptEditor by remember { mutableStateOf(false) }

    val instructionText by container.instructions.text.collectAsStateWithLifecycle()
    val instructionModified by container.instructions.modified.collectAsStateWithLifecycle()

    val activeProvider = settings.activeProvider

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Settings") },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background,
            ),
        )

        Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {

            // ---- providers -------------------------------------------------
            SectionHeader("Providers")

            if (settings.providers.isEmpty()) {
                Text(
                    text = "Add any provider key. OpenRouter is the easiest: one key, most models.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                Spacer(Modifier.height(10.dp))
            }

            settings.providers.forEach { provider ->
                ProviderRow(
                    provider = provider,
                    isActive = provider.id == activeProvider?.id,
                    onSelect = { store.selectModel(provider.id, provider.defaultModel) },
                    onEdit = { editing = provider },
                    onDelete = { store.removeProvider(provider.id) },
                )
            }

            Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Button(onClick = { showPresets = true }) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Add provider")
                }
            }

            // ---- model -----------------------------------------------------
            if (activeProvider != null) {
                SectionHeader("Model")
                Text(
                    text = activeProvider.name,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                Spacer(Modifier.height(8.dp))

                Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                    activeProvider.models.forEach { model ->
                        FilterChip(
                            selected = settings.activeModel == model,
                            onClick = { store.selectModel(activeProvider.id, model) },
                            label = {
                                Text(
                                    model,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            },
                            modifier = Modifier.padding(bottom = 6.dp),
                        )
                    }
                }

                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedButton(
                        enabled = !fetching && activeProvider.apiKey.isNotBlank(),
                        onClick = {
                            scope.launch {
                                fetching = true
                                notice = null
                                val result = runCatching {
                                    ProviderRegistry.forConfig(activeProvider).listModels(activeProvider)
                                }
                                result
                                    .onSuccess { models ->
                                        if (models.isEmpty()) {
                                            notice = "Provider returned no models."
                                        } else {
                                            store.upsertProvider(
                                                activeProvider.copy(models = models.take(200)),
                                            )
                                            notice = "Loaded ${models.size} models."
                                        }
                                    }
                                    .onFailure { notice = it.message ?: "Could not list models" }
                                fetching = false
                            }
                        },
                    ) {
                        if (fetching) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Icon(
                                Icons.Filled.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Text("Fetch model list")
                    }
                }

                notice?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }

                OutlinedTextField(
                    value = settings.activeModel,
                    onValueChange = { store.selectModel(activeProvider.id, it) },
                    label = { Text("Model id") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }

            // ---- agent -----------------------------------------------------
            SectionHeader("Agent")

            SettingSwitch(
                title = "Auto-approve writes",
                subtitle = "Skip the confirmation sheet before file writes, git commits and pushes.",
                checked = settings.autoApproveWrites,
                onCheckedChange = { value -> store.update { it.copy(autoApproveWrites = value) } },
            )

            SettingSwitch(
                title = "Auto-approve commands",
                subtitle =
                    "Run non-read-only terminal commands and build steps without asking. " +
                        "Blocked commands are still refused.",
                checked = settings.autoApproveCommands,
                onCheckedChange = { value -> store.update { it.copy(autoApproveCommands = value) } },
            )

            NumberRow(
                title = "Command timeout (seconds)",
                value = settings.commandTimeoutSeconds,
                onValue = { value ->
                    store.update { it.copy(commandTimeoutSeconds = value.coerceIn(10, 1800)) }
                },
            )

            SliderRow(
                title = "Temperature",
                value = settings.temperature.toFloat(),
                valueLabel = String.format("%.2f", settings.temperature),
                range = 0f..1f,
                steps = 19,
                onValueChange = { value ->
                    store.update { it.copy(temperature = value.toDouble()) }
                },
            )

            NumberRow(
                title = "Max tokens per reply",
                value = settings.maxTokens,
                onValue = { value -> store.update { it.copy(maxTokens = value.coerceIn(256, 32_000)) } },
            )

            NumberRow(
                title = "Max tool steps per message",
                value = settings.maxSteps,
                onValue = { value -> store.update { it.copy(maxSteps = value.coerceIn(1, 100)) } },
            )

            SettingSwitch(
                title = "Auto checkpoint",
                subtitle = "Snapshot the project before the agent's first change each turn, so a whole " +
                    "turn can be reviewed or undone.",
                checked = settings.autoCheckpoint,
                onCheckedChange = { value -> store.update { it.copy(autoCheckpoint = value) } },
            )

            NumberRow(
                title = "Keep last N checkpoints",
                value = settings.maxCheckpoints,
                onValue = { value -> store.update { it.copy(maxCheckpoints = value.coerceIn(1, 200)) } },
            )

            // ---- editor ----------------------------------------------------
            SectionHeader("Editor")

            NumberRow(
                title = "Font size",
                value = settings.editorFontSize,
                onValue = { value -> store.update { it.copy(editorFontSize = value.coerceIn(9, 24)) } },
            )

            SettingSwitch(
                title = "Word wrap",
                subtitle = "Wrap long lines instead of scrolling sideways.",
                checked = settings.wordWrap,
                onCheckedChange = { value -> store.update { it.copy(wordWrap = value) } },
            )

            // ---- git -------------------------------------------------------
            SectionHeader("Git")

            OutlinedTextField(
                value = settings.gitUserName,
                onValueChange = { value -> store.update { it.copy(gitUserName = value) } },
                label = { Text("Commit author name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            )
            OutlinedTextField(
                value = settings.gitUserEmail,
                onValueChange = { value -> store.update { it.copy(gitUserEmail = value) } },
                label = { Text("Commit author email") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            )
            OutlinedTextField(
                value = settings.gitUsername,
                onValueChange = { value -> store.update { it.copy(gitUsername = value) } },
                label = { Text("Git username (optional)") },
                placeholder = { Text("x-access-token") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            )
            SecretField(
                label = "Personal access token",
                value = settings.gitToken,
                onValueChange = { value -> store.update { it.copy(gitToken = value) } },
            )
            Text(
                text = "Used for private clones, pull and push. Stored encrypted on this device " +
                    "and only sent to the git host you clone from.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            TextButton(
                onClick = { openUrl("https://github.com/settings/tokens") },
                modifier = Modifier.padding(start = 8.dp),
            ) {
                Icon(Icons.Filled.OpenInNew, contentDescription = null, Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text("Create a GitHub token")
            }

            // ---- github hub -----------------------------------------------
            SectionHeader("GitHub Hub")

            val ghAccount by container.github.account.collectAsStateWithLifecycle()
            Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(10.dp)) {
                Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Code, contentDescription = null, Modifier.size(18.dp))
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Hub", style = MaterialTheme.typography.titleSmall)
                        Text(
                            text = ghAccount?.login
                                ?: settings.githubLogin.ifBlank { "not signed in" },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Text(
                "Sign in from the Hub tab to browse repos, read issues and PRs, watch Actions " +
                    "and clone private repositories. That token also powers git push/pull.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )

            // ---- appearance ------------------------------------------------
            SectionHeader("Appearance")
            ThemeMode.entries.forEach { mode ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = settings.themeMode == mode,
                        onClick = { store.update { it.copy(themeMode = mode) } },
                    )
                    Text(
                        text = when (mode) {
                            ThemeMode.SYSTEM -> "Follow system"
                            ThemeMode.DARK -> "Dark"
                            ThemeMode.LIGHT -> "Light"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            // ---- instructions ----------------------------------------------
            SectionHeader("Custom instructions")
            OutlinedTextField(
                value = settings.customInstructions,
                onValueChange = { value -> store.update { it.copy(customInstructions = value) } },
                label = { Text("Appended to every system prompt") },
                minLines = 3,
                maxLines = 8,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            )

            // ---- performance ------------------------------------------------
            SectionHeader("Performance")
            SettingSwitch(
                title = "Fast mode",
                subtitle = "Sends a condensed system prompt, hides GitHub tools while signed " +
                    "out and compacts old tool output. Big speedup on every request; " +
                    "turn off for the fullest agent guidance.",
                checked = settings.fastMode,
                onCheckedChange = { value -> store.update { it.copy(fastMode = value) } },
            )

            // ---- system prompt handbook -------------------------------------
            SectionHeader("System prompt")
            SettingSwitch(
                title = "Use agent handbook",
                subtitle = "Prepend the bundled INSTRUCTION.md operating handbook to every " +
                    "system prompt. Ignored while Fast mode is on.",
                checked = settings.useSystemPrompt,
                onCheckedChange = { value -> store.update { it.copy(useSystemPrompt = value) } },
            )
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            ) {
                Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.Description,
                        contentDescription = null,
                        Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("INSTRUCTION.md", style = MaterialTheme.typography.titleSmall)
                        Text(
                            text = when {
                                settings.fastMode -> "bypassed by Fast mode"
                                !settings.useSystemPrompt -> "disabled"
                                instructionModified -> "edited"
                                else -> "bundled version"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { showPromptEditor = true }) { Text("Edit") }
                    TextButton(
                        enabled = instructionModified,
                        onClick = {
                            scope.launch { container.instructions.resetToBundled() }
                        },
                    ) { Text("Reset") }
                }
            }
            Text(
                text = "The handbook defines how the agent plans, verifies, protects your " +
                    "code and handles permissions. Editing it changes every future turn.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )

            // ---- skills ------------------------------------------------------
            SectionHeader("Skills")
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                    .clickable(onClick = onOpenSkills),
            ) {
                Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.AutoAwesome,
                        contentDescription = null,
                        Modifier.size(18.dp),
                        tint = if (settings.enabledSkills.isEmpty()) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Built-in skill library", style = MaterialTheme.typography.titleSmall)
                        Text(
                            text = "${settings.enabledSkills.size} active · Design, debugging, " +
                                "minimalism, memory import & more",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(
                        Icons.Filled.ChevronRight,
                        contentDescription = null,
                        Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            SectionHeader("About")
            Text(
                text = "OpenCode Mobile runs the whole agent on device: your key talks straight " +
                    "to the provider, files stay in the app sandbox, git goes over HTTPS with " +
                    "JGit, and previews are served on 127.0.0.1. There is no companion server " +
                    "and no shell, so web projects use CDN import maps instead of npm.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Spacer(Modifier.height(32.dp))
        }
    }

    if (showPresets) {
        PresetPickerDialog(
            onDismiss = { showPresets = false },
            onPick = { preset ->
                showPresets = false
                editing = preset.toConfig()
            },
        )
    }

    if (showPromptEditor) {
        SystemPromptEditorDialog(
            initial = instructionText,
            onDismiss = { showPromptEditor = false },
            onSave = { value ->
                showPromptEditor = false
                container.instructions.update(value)
            },
            onReset = {
                showPromptEditor = false
                scope.launch { container.instructions.resetToBundled() }
            },
            modified = instructionModified,
        )
    }

    editing?.let { config ->
        ProviderEditorDialog(
            config = config,
            onDismiss = { editing = null },
            onSave = { updated ->
                editing = null
                store.upsertProvider(updated)
                if (settings.activeModel.isBlank() && updated.defaultModel.isNotBlank()) {
                    store.selectModel(updated.id, updated.defaultModel)
                }
            },
            onOpenKeyUrl = { url -> openUrl(url) },
        )
    }
}

// ---- rows ----------------------------------------------------------------

@Composable
private fun ProviderRow(
    provider: ProviderConfig,
    isActive: Boolean,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        color = if (isActive) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surface
        },
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(provider.name, style = MaterialTheme.typography.titleSmall)
                Text(
                    text = buildString {
                        append(provider.kind.name.lowercase())
                        append(" · ")
                        append(if (provider.apiKey.isBlank()) "no key" else "key set")
                        if (provider.models.isNotEmpty()) append(" · ${provider.models.size} models")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!isActive) {
                TextButton(onClick = onSelect) { Text("Use") }
            }
            IconButton(onClick = onEdit) {
                Icon(Icons.Filled.Edit, contentDescription = "Edit provider")
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "Remove provider",
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun SliderRow(
    title: String,
    value: Float,
    valueLabel: String,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValueChange: (Float) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Row {
            Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(
                text = valueLabel,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(value = value, onValueChange = onValueChange, valueRange = range, steps = steps)
    }
}

@Composable
private fun NumberRow(title: String, value: Int, onValue: (Int) -> Unit) {
    // Not keyed on `value`: the store clamps what it stores, and re-keying would
    // rewrite the field mid-typing (typing "1" of "1024" would snap to the minimum).
    var text by remember { mutableStateOf(value.toString()) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        OutlinedTextField(
            value = text,
            onValueChange = { raw ->
                text = raw.filter { it.isDigit() }.take(6)
                text.toIntOrNull()?.let(onValue)
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.width(110.dp),
        )
    }
}

@Composable
private fun SecretField(label: String, value: String, onValueChange: (String) -> Unit) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (visible) {
            VisualTransformation.None
        } else {
            PasswordVisualTransformation()
        },
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(
                    if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                    contentDescription = if (visible) "Hide" else "Show",
                )
            }
        },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
    )
}

// ---- dialogs -------------------------------------------------------------

@Composable
private fun PresetPickerDialog(onDismiss: () -> Unit, onPick: (ProviderPreset) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add provider") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                ProviderPresets.all.forEach { preset ->
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(preset.name, style = MaterialTheme.typography.bodyLarge)
                                    if (preset.note.isNotBlank()) {
                                        Text(
                                            text = preset.note,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                                TextButton(onClick = { onPick(preset) }) { Text("Add") }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun ProviderEditorDialog(
    config: ProviderConfig,
    onDismiss: () -> Unit,
    onSave: (ProviderConfig) -> Unit,
    onOpenKeyUrl: (String) -> Unit,
) {
    var name by remember { mutableStateOf(config.name) }
    var baseUrl by remember { mutableStateOf(config.baseUrl) }
    var apiKey by remember { mutableStateOf(config.apiKey) }
    var models by remember { mutableStateOf(config.models.joinToString("\n")) }
    var keyVisible by remember { mutableStateOf(false) }

    val preset = remember(config.id) { ProviderPresets.all.firstOrNull { it.id == config.id } }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(config.name.ifBlank { "Provider" }) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Display name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = { Text("Base URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("API key") },
                    singleLine = true,
                    visualTransformation = if (keyVisible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailingIcon = {
                        IconButton(onClick = { keyVisible = !keyVisible }) {
                            Icon(
                                if (keyVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                contentDescription = null,
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (preset != null && preset.keyUrl.isNotBlank()) {
                    TextButton(onClick = { onOpenKeyUrl(preset.keyUrl) }) {
                        Icon(Icons.Filled.OpenInNew, contentDescription = null, Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Get a key")
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = models,
                    onValueChange = { models = it },
                    label = { Text("Models, one per line") },
                    minLines = 3,
                    maxLines = 8,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "HTTPS only. Cleartext HTTP is blocked except on 127.0.0.1.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = baseUrl.startsWith("https://") || baseUrl.contains("127.0.0.1"),
                onClick = {
                    val list = models.lines().map { it.trim() }.filter { it.isNotEmpty() }
                    onSave(
                        config.copy(
                            name = name.ifBlank { config.id },
                            baseUrl = baseUrl.trim(),
                            apiKey = apiKey.trim(),
                            models = list,
                            defaultModel = list.firstOrNull() ?: config.defaultModel,
                        ),
                    )
                },
            ) {
                Text("Save")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// ---- system prompt editor ---------------------------------------------------

/**
 * Full-width editing surface for the bundled INSTRUCTION.md handbook. Text loads
 * once when the dialog opens; Save persists to app storage and takes effect on
 * the next agent turn.
 */
@Composable
private fun SystemPromptEditorDialog(
    initial: String,
    modified: Boolean,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
    onReset: () -> Unit,
) {
    var text by remember { mutableStateOf(initial) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Description,
                    contentDescription = null,
                    Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(8.dp))
                Column {
                    Text("System prompt")
                    Text(
                        text = if (modified) "edited — Reset restores the bundle" else "bundled version",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                minLines = 14,
                maxLines = 22,
                textStyle = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth().heightIn(max = 460.dp),
            )
        },
        confirmButton = {
            Button(onClick = { onSave(text) }, enabled = text.isNotBlank()) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (modified) {
                    TextButton(onClick = onReset) { Text("Reset") }
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}
