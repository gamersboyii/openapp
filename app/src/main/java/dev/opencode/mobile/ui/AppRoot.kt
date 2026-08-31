package dev.opencode.mobile.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.RateReview
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Workspaces
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dev.opencode.mobile.LocalContainer
import dev.opencode.mobile.llm.ProviderConfig
import dev.opencode.mobile.ui.chat.ChatScreen
import dev.opencode.mobile.ui.checkpoints.CheckpointsScreen
import dev.opencode.mobile.ui.components.SparkleAvatar
import dev.opencode.mobile.ui.files.EditorScreen
import dev.opencode.mobile.ui.files.FilesScreen
import dev.opencode.mobile.ui.github.GitHubScreen
import dev.opencode.mobile.ui.preview.PreviewScreen
import dev.opencode.mobile.ui.projects.ProjectsScreen
import dev.opencode.mobile.ui.review.ReviewScreen
import dev.opencode.mobile.ui.sessions.SessionsScreen
import dev.opencode.mobile.ui.settings.SettingsScreen
import dev.opencode.mobile.ui.skills.SkillsScreen
import dev.opencode.mobile.ui.terminal.TerminalScreen
import kotlinx.coroutines.launch

object Routes {
    const val CHAT = "chat"
    const val FILES = "files"
    const val PREVIEW = "preview"
    const val GITHUB = "github"
    const val PROJECTS = "projects"
    const val SETTINGS = "settings"
    const val EDITOR = "editor"
    const val TERMINAL = "terminal"
    const val REVIEW = "review"
    const val CHECKPOINTS = "checkpoints"
    const val SKILLS = "skills"
    const val SESSIONS = "sessions"

    fun editor(path: String): String = "$EDITOR?path=${android.net.Uri.encode(path)}"
}

private data class DrawerItem(val route: String, val label: String, val icon: ImageVector)

private val drawerItems = listOf(
    DrawerItem(Routes.CHAT, "Chat", Icons.Filled.Forum),
    DrawerItem(Routes.SESSIONS, "Sessions", Icons.Filled.Chat),
    DrawerItem(Routes.FILES, "Files", Icons.Filled.Folder),
    DrawerItem(Routes.PREVIEW, "Preview", Icons.Filled.Visibility),
    DrawerItem(Routes.GITHUB, "Hub", Icons.Filled.Code),
    DrawerItem(Routes.PROJECTS, "Projects", Icons.Filled.Workspaces),
    DrawerItem(Routes.SKILLS, "Skills", Icons.Filled.Folder),
    DrawerItem(Routes.CHECKPOINTS, "Checkpoints", Icons.Filled.History),
    DrawerItem(Routes.REVIEW, "Review changes", Icons.Filled.RateReview),
    DrawerItem(Routes.SETTINGS, "Settings", Icons.Filled.Settings),
)

/**
 * ChatGPT-style app shell: a flat canvas with a centered model toggle up top,
 * a hamburger drawer for section navigation, and no bottom navigation bar at
 * all — every destination lives in the drawer, which removes the old
 * bottom-bar tap target problems entirely.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(DrawerValue.Closed)

    val container = LocalContainer.current
    val agent = container.agent
    val settings by container.settings.settings.collectAsStateWithLifecycle()
    val project by container.workspace.activeProject.collectAsStateWithLifecycle()
    val isRunning by agent.isRunning.collectAsStateWithLifecycle()
    val status by agent.status.collectAsStateWithLifecycle()

    // Editor, terminal, review, checkpoints, sessions and skills are full-screen
    // pushes with their own headers; the shell top bar and drawer hide there.
    val route = currentRoute
    val fullScreen = route?.startsWith(Routes.EDITOR) == true ||
        route == Routes.TERMINAL ||
        route == Routes.REVIEW ||
        route == Routes.CHECKPOINTS ||
        route == Routes.SESSIONS ||
        route == Routes.SKILLS

    val navigate: (String) -> Unit = { target ->
        scope.launch { drawerState.close() }
        navController.navigate(target) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = !fullScreen,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = MaterialTheme.colorScheme.background,
            ) {
                AppDrawer(
                    currentRoute = route,
                    projectLabel = project?.name,
                    isRunning = isRunning,
                    onNavigate = navigate,
                    onNewChat = {
                        scope.launch { drawerState.close() }
                        agent.newSession()
                        navigate(Routes.CHAT)
                    },
                )
            }
        },
    ) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                if (!fullScreen) {
                    OpenCodeTopBar(
                        providers = settings.providers,
                        activeProviderId = settings.activeProviderId,
                        activeModel = settings.activeModel,
                        chatOnly = settings.chatOnly,
                        canNewChat = !isRunning,
                        onSelectModel = { providerId, model ->
                            container.settings.selectModel(providerId, model)
                        },
                        onToggleChatOnly = { value ->
                            container.settings.update { it.copy(chatOnly = value) }
                        },
                        onOpenDrawer = { scope.launch { drawerState.open() } },
                        onOpenSettings = { navigate(Routes.SETTINGS) },
                        onOpenSessions = { navigate(Routes.SESSIONS) },
                        onNewChat = { agent.newSession() },
                    )
                }
            },
        ) { padding ->
            Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                AnimatedVisibility(
                    visible = isRunning,
                    enter = expandVertically(tween(200)) + fadeIn(tween(220)),
                    exit = shrinkVertically(tween(180)) + fadeOut(tween(140)),
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.secondary,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant,
                        )
                        if (status.isNotBlank()) {
                            Text(
                                text = status,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                            )
                        }
                    }
                }

                NavHost(
                    navController = navController,
                    startDestination = Routes.CHAT,
                    modifier = Modifier.fillMaxSize(),
                    enterTransition = {
                        fadeIn(tween(220)) + slideInHorizontally(tween(260)) { it / 8 }
                    },
                    exitTransition = { fadeOut(tween(120)) },
                    popEnterTransition = {
                        fadeIn(tween(220)) + slideInHorizontally(tween(260)) { -it / 8 }
                    },
                    popExitTransition = {
                        fadeOut(tween(120)) + slideOutHorizontally(tween(220)) { it / 8 }
                    },
                ) {
                    composable(Routes.CHAT) {
                        ChatScreen(
                            onOpenSettings = { navigate(Routes.SETTINGS) },
                            onOpenProjects = { navigate(Routes.PROJECTS) },
                            onOpenPreview = { navigate(Routes.PREVIEW) },
                            onOpenFile = { path -> navController.navigate(Routes.editor(path)) },
                            onOpenReview = { navigate(Routes.REVIEW) },
                            onOpenCheckpoints = { navigate(Routes.CHECKPOINTS) },
                            onOpenSkills = { navigate(Routes.SKILLS) },
                            onOpenSessions = { navigate(Routes.SESSIONS) },
                        )
                    }
                    composable(Routes.FILES) {
                        FilesScreen(
                            onOpenFile = { path -> navController.navigate(Routes.editor(path)) },
                            onOpenTerminal = { navController.navigate(Routes.TERMINAL) },
                        )
                    }
                    composable(Routes.PREVIEW) { PreviewScreen() }
                    composable(Routes.GITHUB) {
                        GitHubScreen(onOpenChat = { navigate(Routes.CHAT) })
                    }
                    composable(Routes.PROJECTS) {
                        ProjectsScreen(onOpenChat = { navigate(Routes.CHAT) })
                    }
                    composable(Routes.SETTINGS) {
                        SettingsScreen(
                            onOpenSkills = { navController.navigate(Routes.SKILLS) },
                        )
                    }
                    composable(Routes.TERMINAL) {
                        TerminalScreen(onBack = { navController.popBackStack() })
                    }
                    composable(Routes.REVIEW) {
                        ReviewScreen(onBack = { navController.popBackStack() })
                    }
                    composable(Routes.CHECKPOINTS) {
                        CheckpointsScreen(onBack = { navController.popBackStack() })
                    }
                    composable(Routes.SESSIONS) {
                        SessionsScreen(
                            onBack = { navController.popBackStack() },
                            onOpenChat = { navigate(Routes.CHAT) },
                        )
                    }
                    composable(Routes.SKILLS) {
                        SkillsScreen(onBack = { navController.popBackStack() })
                    }
                    composable("${Routes.EDITOR}?path={path}") { entry ->
                        EditorScreen(
                            relativePath = entry.arguments?.getString("path").orEmpty(),
                            onBack = { navController.popBackStack() },
                        )
                    }
                }
            }
        }
    }
}

/**
 * Top bar exactly like the official app: hamburger on the left, a centered
 * tappable "opencode ▾" that opens the model picker (plus the Chat Only
 * switch), a sessions action and a new-chat action on the right.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OpenCodeTopBar(
    providers: List<ProviderConfig>,
    activeProviderId: String?,
    activeModel: String,
    chatOnly: Boolean,
    canNewChat: Boolean,
    onSelectModel: (String, String) -> Unit,
    onToggleChatOnly: (Boolean) -> Unit,
    onOpenDrawer: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenSessions: () -> Unit,
    onNewChat: () -> Unit,
) {
    var modelMenuOpen by remember { mutableStateOf(false) }

    CenterAlignedTopAppBar(
        navigationIcon = {
            IconButton(onClick = onOpenDrawer) {
                Icon(Icons.Filled.Menu, contentDescription = "Menu")
            }
        },
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clickable { modelMenuOpen = true }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "opencode",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Icon(
                            Icons.Filled.ArrowDropDown,
                            contentDescription = "Switch model",
                            tint = MaterialTheme.colorScheme.onBackground,
                        )
                    }
                    if (activeModel.isNotBlank()) {
                        Text(
                            text = activeModel,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            ModelMenu(
                expanded = modelMenuOpen,
                onDismiss = { modelMenuOpen = false },
                providers = providers,
                activeProviderId = activeProviderId,
                activeModel = activeModel,
                chatOnly = chatOnly,
                onSelectModel = onSelectModel,
                onToggleChatOnly = onToggleChatOnly,
                onOpenSettings = {
                    modelMenuOpen = false
                    onOpenSettings()
                },
            )
        },
        actions = {
            IconButton(onClick = onOpenSessions) {
                Icon(Icons.Filled.History, contentDescription = "Chat sessions")
            }
            IconButton(onClick = onNewChat, enabled = canNewChat) {
                Icon(Icons.Filled.Add, contentDescription = "New chat")
            }
        },
        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
            navigationIconContentColor = MaterialTheme.colorScheme.onBackground,
            titleContentColor = MaterialTheme.colorScheme.onBackground,
            actionIconContentColor = MaterialTheme.colorScheme.onBackground,
        ),
    )
}

/** The model dropdown: every configured provider's models + Chat Only switch. */
@Composable
private fun ModelMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    providers: List<ProviderConfig>,
    activeProviderId: String?,
    activeModel: String,
    chatOnly: Boolean,
    onSelectModel: (String, String) -> Unit,
    onToggleChatOnly: (Boolean) -> Unit,
    onOpenSettings: () -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(16.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Text(
            text = "MODEL",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
        )
        val hasAny = providers.any { it.models.isNotEmpty() || it.defaultModel.isNotBlank() }
        if (!hasAny) {
            DropdownMenuItem(
                text = { Text("No models yet — add a provider") },
                onClick = onOpenSettings,
            )
        } else {
            providers.forEach { provider ->
                val models = provider.models.ifEmpty {
                    provider.defaultModel.takeIf { it.isNotBlank() }?.let { listOf(it) }.orEmpty()
                }
                models.forEach { model ->
                    val active = provider.id == activeProviderId && model == activeModel
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = model,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (active) MaterialTheme.colorScheme.secondary
                                else MaterialTheme.colorScheme.onSurface,
                            )
                        },
                        trailingIcon = if (active) {
                            { Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.secondary) }
                        } else {
                            null
                        },
                        onClick = {
                            onSelectModel(provider.id, model)
                            onDismiss()
                        },
                    )
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        DropdownMenuItem(
            text = {
                Text(
                    "Chat Only",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            },
            trailingIcon = {
                Switch(checked = chatOnly, onCheckedChange = onToggleChatOnly)
            },
            onClick = { onToggleChatOnly(!chatOnly) },
        )
        DropdownMenuItem(
            text = {
                Text(
                    "Manage providers",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            onClick = onOpenSettings,
        )
    }
}

/** ChatGPT-style drawer: opencode header, new chat, then all sections. */
@Composable
private fun AppDrawer(
    currentRoute: String?,
    projectLabel: String?,
    isRunning: Boolean,
    onNavigate: (String) -> Unit,
    onNewChat: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 22.dp),
        ) {
            SparkleAvatar(size = 24.dp)
            Spacer(Modifier.width(10.dp))
            Text(
                text = "opencode",
                style = MaterialTheme.typography.titleMedium,
            )
        }

        DrawerRow(
            label = "New chat",
            icon = Icons.Filled.Add,
            selected = false,
            onClick = onNewChat,
        )

        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant,
            modifier = Modifier.padding(vertical = 8.dp),
        )

        drawerItems.forEach { item ->
            DrawerRow(
                label = item.label,
                icon = item.icon,
                selected = currentRoute == item.route ||
                    (item.route != Routes.CHAT && currentRoute?.startsWith(item.route) == true),
                onClick = { onNavigate(item.route) },
            )
        }

        Spacer(Modifier.weight(1f))

        Text(
            text = when {
                isRunning -> "Agent running…"
                projectLabel != null -> projectLabel
                else -> "No project selected"
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
        )
    }
}

@Composable
private fun DrawerRow(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (selected) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(16.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (selected) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onBackground,
        )
    }
}
