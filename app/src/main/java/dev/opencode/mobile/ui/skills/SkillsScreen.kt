package dev.opencode.mobile.ui.skills

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Construction
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.opencode.mobile.LocalContainer
import dev.opencode.mobile.core.skills.SkillDef
import dev.opencode.mobile.ui.components.EmptyState
import dev.opencode.mobile.ui.components.MarkdownText
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkillsScreen(onBack: () -> Unit) {
    val container = LocalContainer.current
    val store = container.settings
    val settings by store.settings.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var query by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf("All") }
    var detailId by rememberSaveable { mutableStateOf<String?>(null) }

    // Catalog load is a cached asset read; loaded once per screen entry.
    var catalog by remember { mutableStateOf(emptyList<SkillDef>()) }
    LaunchedEffect(Unit) { catalog = container.skills.all() }

    val categories = remember(catalog) {
        listOf("All") + catalog.map { it.category }.distinct().sorted()
    }

    val filtered = catalog.filter { skill ->
        (category == "All" || skill.category == category) &&
            (query.isBlank() ||
                skill.name.contains(query, ignoreCase = true) ||
                skill.description.contains(query, ignoreCase = true))
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Skills") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background,
            ),
        )

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.AutoAwesome,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "${settings.enabledSkills.size} of ${catalog.size} active — " +
                    "enabled skills steer every turn; the agent loads details itself.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Search skills") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            categories.forEach { name ->
                FilterChip(
                    selected = category == name,
                    onClick = { category = name },
                    label = { Text(name, style = MaterialTheme.typography.labelMedium) },
                )
            }
        }
        Spacer(Modifier.height(4.dp))

        if (filtered.isEmpty()) {
            EmptyState(
                icon = Icons.Filled.Extension,
                title = if (catalog.isEmpty()) "Loading built-in skills…" else "No matches",
                message = if (catalog.isEmpty())
                    "The skill library ships with the app and appears here instantly after first launch."
                else "Try another search or category.",
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(items = filtered, key = { it.id }) { skill ->
                    SkillCard(
                        skill = skill,
                        enabled = settings.enabledSkills.contains(skill.id),
                        onToggle = { on ->
                            scope.launch {
                                store.update { current ->
                                    current.copy(
                                        enabledSkills =
                                        if (on) current.enabledSkills + skill.id
                                        else current.enabledSkills - skill.id,
                                    )
                                }
                                // Keep the runtime cache warm so prompt rendering
                                // and use_skill never hit a cold path.
                                container.skills.content(skill)
                            }
                        },
                        onOpenDetail = { detailId = skill.id },
                    )
                }
            }
        }
    }

    detailId?.let { id ->
        catalog.firstOrNull { it.id == id }?.let { def ->
            val sheetState = androidx.compose.material3.rememberModalBottomSheetState(
                skipPartiallyExpanded = true,
            )
            ModalBottomSheet(
                onDismissRequest = { detailId = null },
                sheetState = sheetState,
            ) {
                SkillDetailSheet(def = def)
            }
        }
    }
}
@Composable
private fun SkillCard(
    skill: SkillDef,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    onOpenDetail: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = MaterialTheme.shapes.medium,
        tonalElevation = if (enabled) 2.dp else 0.dp,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenDetail),
    ) {
        Row(modifier = Modifier.padding(start = 14.dp, top = 12.dp, bottom = 12.dp)) {
            Icon(
                imageVector = categoryIcon(skill.category),
                contentDescription = null,
                tint = if (enabled) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp).align(Alignment.CenterVertically),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(skill.name, style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(2.dp))
                Text(
                    text = skill.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "${skill.category} · ${skill.source}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(6.dp))
            Switch(
                checked = enabled,
                onCheckedChange = onToggle,
                modifier = Modifier.padding(end = 12.dp).align(Alignment.CenterVertically),
            )
        }
    }
}

@Composable
private fun SkillDetailSheet(def: SkillDef) {
    val container = LocalContainer.current
    val settings by container.settings.settings.collectAsStateWithLifecycle()
    var body by remember(def.id) { mutableStateOf("") }

    LaunchedEffect(def.id) {
        body = container.skills.content(def)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 620.dp)
            .padding(horizontal = 20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(categoryIcon(def.category), null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(def.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = def.source,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = settings.enabledSkills.contains(def.id),
                onCheckedChange = { on ->
                    container.settings.update { current ->
                        current.copy(
                            enabledSkills =
                            if (on) current.enabledSkills + def.id
                            else current.enabledSkills - def.id,
                        )
                    }
                },
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = def.description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
    }
    MarkdownText(
        text = body.ifBlank { "_Loading…_" },
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 8.dp),
    )
}

private fun categoryIcon(category: String): ImageVector = when (category) {
    "Design" -> Icons.Filled.Palette
    "Communication" -> Icons.Filled.Forum
    "Engineering Process" -> Icons.Filled.Construction
    "Code Minimalism" -> Icons.Filled.Bolt
    "Memory" -> Icons.Filled.Memory
    else -> Icons.Filled.Extension
}
