package dev.opencode.mobile

import android.app.Application
import androidx.compose.runtime.staticCompositionLocalOf
import dev.opencode.mobile.agent.AgentEngine
import dev.opencode.mobile.core.build.BuildSystem
import dev.opencode.mobile.core.exec.CommandHistoryStore
import dev.opencode.mobile.core.exec.TerminalService
import dev.opencode.mobile.core.fs.WorkspaceManager
import dev.opencode.mobile.core.git.AndroidSystemReader
import dev.opencode.mobile.core.git.GitService
import dev.opencode.mobile.core.git.RepoSnapshotService
import dev.opencode.mobile.core.preview.PreviewServer
import dev.opencode.mobile.core.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Hand-rolled service locator. A DI framework would add build complexity for a
 * graph this small, and every dependency here is an app-lifetime singleton.
 */
class AppContainer(application: Application) {

    val settings = SettingsStore(application)
    val workspace = WorkspaceManager(application)
    val git = GitService()
    val snapshots = RepoSnapshotService()
    val preview = PreviewServer()
    val commandHistory = CommandHistoryStore(application)
    val terminal = TerminalService(application, commandHistory)
    val builds = BuildSystem()

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val agent = AgentEngine(
        workspace = workspace,
        git = git,
        snapshots = snapshots,
        preview = preview,
        terminal = terminal,
        builds = builds,
        commandHistory = commandHistory,
        settingsStore = settings,
        scope = scope,
    )

    fun bootstrap() {
        scope.launch {
            commandHistory.load()
            workspace.refresh()
            settings.settings.value.lastProjectPath?.let { workspace.selectByPath(it) }
        }
        // Keep the transcript, the remembered project and the preview root in step
        // with whichever project is active.
        scope.launch {
            workspace.activeProject.collectLatest { project ->
                agent.bindProject(project)
                settings.update { it.copy(lastProjectPath = project?.path) }
                if (project == null) {
                    preview.stop()
                } else if (preview.state.value.running &&
                    preview.state.value.rootPath != project.dir.absolutePath
                ) {
                    runCatching { preview.start(project.dir, preview.state.value.entry) }
                }
            }
        }
    }
}

class OpenCodeApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // Must happen before the first JGit call: JGit otherwise probes desktop
        // paths ($HOME/.gitconfig, /etc/gitconfig) that do not exist on Android.
        AndroidSystemReader.install(filesDir)
        container = AppContainer(this)
        container.bootstrap()
    }
}

val LocalContainer = staticCompositionLocalOf<AppContainer> {
    error("AppContainer not provided")
}
