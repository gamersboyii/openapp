package dev.opencode.mobile.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Workspaces
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import dev.opencode.mobile.ui.chat.ChatScreen
import dev.opencode.mobile.ui.files.EditorScreen
import dev.opencode.mobile.ui.files.FilesScreen
import dev.opencode.mobile.ui.preview.PreviewScreen
import dev.opencode.mobile.ui.projects.ProjectsScreen
import dev.opencode.mobile.ui.settings.SettingsScreen
import dev.opencode.mobile.ui.terminal.TerminalScreen

object Routes {
    const val CHAT = "chat"
    const val FILES = "files"
    const val PREVIEW = "preview"
    const val PROJECTS = "projects"
    const val SETTINGS = "settings"
    const val EDITOR = "editor"
    const val TERMINAL = "terminal"

    fun editor(path: String): String = "$EDITOR?path=${android.net.Uri.encode(path)}"
}

private data class TabItem(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    TabItem(Routes.CHAT, "Chat", Icons.Filled.Forum),
    TabItem(Routes.FILES, "Files", Icons.Filled.Folder),
    TabItem(Routes.PREVIEW, "Preview", Icons.Filled.Visibility),
    TabItem(Routes.PROJECTS, "Projects", Icons.Filled.Workspaces),
    TabItem(Routes.SETTINGS, "Settings", Icons.Filled.Settings),
)

@Composable
fun AppRoot() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    val container = LocalContainer.current
    val isRunning by container.agent.isRunning.collectAsStateWithLifecycle()
    val status by container.agent.status.collectAsStateWithLifecycle()

    Scaffold(
        bottomBar = {
            // The editor and terminal are full-screen pushes; hiding the bar
            // there stops the keyboard and the nav bar fighting for the same space.
            val route = currentRoute
            if (route?.startsWith(Routes.EDITOR) != true && route != Routes.TERMINAL) {
                NavigationBar {
                    val destination = backStackEntry?.destination
                    tabs.forEach { tab ->
                        val selected = destination?.hierarchy?.any { it.route == tab.route } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                if (!selected) {
                                    navController.navigate(tab.route) {
                                        popUpTo(navController.graph.findStartDestination().id) {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                }
                            },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            AnimatedVisibility(visible = isRunning) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
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
            ) {
                composable(Routes.CHAT) {
                    ChatScreen(
                        onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                        onOpenProjects = { navController.navigate(Routes.PROJECTS) },
                        onOpenPreview = { navController.navigate(Routes.PREVIEW) },
                        onOpenFile = { path -> navController.navigate(Routes.editor(path)) },
                    )
                }
                composable(Routes.FILES) {
                    FilesScreen(
                        onOpenFile = { path -> navController.navigate(Routes.editor(path)) },
                        onOpenTerminal = { navController.navigate(Routes.TERMINAL) },
                    )
                }
                composable(Routes.PREVIEW) { PreviewScreen() }
                composable(Routes.PROJECTS) {
                    ProjectsScreen(onOpenChat = { navController.navigate(Routes.CHAT) })
                }
                composable(Routes.SETTINGS) { SettingsScreen() }
                composable(Routes.TERMINAL) {
                    TerminalScreen(onBack = { navController.popBackStack() })
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
