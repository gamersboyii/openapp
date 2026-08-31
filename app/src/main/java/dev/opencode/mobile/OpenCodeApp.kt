package dev.opencode.mobile

import android.app.Application
import androidx.compose.runtime.staticCompositionLocalOf
import dev.opencode.mobile.agent.AgentEngine
import dev.opencode.mobile.agent.UseSkillTool
import dev.opencode.mobile.core.build.BuildSystem
import dev.opencode.mobile.core.checkpoint.CheckpointService
import dev.opencode.mobile.core.devserver.DevServerManager
import dev.opencode.mobile.core.devserver.NodeRuntime
import dev.opencode.mobile.core.editor.EditorTabsStore
import dev.opencode.mobile.core.exec.CommandHistoryStore
import dev.opencode.mobile.core.exec.TerminalService
import dev.opencode.mobile.core.fs.WorkspaceManager
import dev.opencode.mobile.core.git.AndroidSystemReader
import dev.opencode.mobile.core.git.GitService
import dev.opencode.mobile.core.git.RepoSnapshotService
import dev.opencode.mobile.core.github.GitHubSession
import dev.opencode.mobile.core.instructions.InstructionStore
import dev.opencode.mobile.core.preview.PreviewServer
import dev.opencode.mobile.core.settings.SettingsStore
import dev.opencode.mobile.core.skills.SkillStore
import java.io.File
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
    val checkpoints = CheckpointService()
    val preview = PreviewServer()
    val commandHistory = CommandHistoryStore(application)
    val terminal = TerminalService(application, commandHistory)
    val builds = BuildSystem()

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val nodeRuntime = NodeRuntime(application, terminal)
    val devServer = DevServerManager(terminal, builds, nodeRuntime, scope)

    // Feature 8: GitHub account state lives for the whole app lifetime and is
    // observed by both the Hub tab and the agent's github_* tools.
    val github = GitHubSession(settings, scope)

    // Feature 9: open editor buffers (unsaved state) survive screen navigation.
    val editorTabs = EditorTabsStore()

    // System prompt handbook (bundled INSTRUCTION.md, editable in Settings) and
    // the built-in skill library shipped in assets/skills.
    val instructions = InstructionStore.create(application)
    val skills = SkillStore(application)

    init {
        // The use_skill tool needs the library to describe its ids.
        UseSkillTool.bind(skills)
    }

    val agent = AgentEngine(
        workspace = workspace,
        git = git,
        snapshots = snapshots,
        checkpoints = checkpoints,
        preview = preview,
        terminal = terminal,
        builds = builds,
        devServer = devServer,
        commandHistory = commandHistory,
        settingsStore = settings,
        github = github,
        skills = skills,
        instructions = instructions,
        // Session storage when no project is open, so Chat Only conversations
        // and pre-project chats persist across restarts like project chats do.
        fallbackSessionsRoot = File(application.filesDir, "chat-sessions"),
        scope = scope,
    )

    fun bootstrap() {
        scope.launch {
            commandHistory.load()
            // Preload before the first turn so prompts/specs see the catalog.
            instructions.load()
            skills.all()
            workspace.refresh()
            settings.settings.value.lastProjectPath?.let { workspace.selectByPath(it) }
        }
        // Keep the transcript, the remembered project and the preview root in step
        // with whichever project is active.
        scope.launch {
            workspace.activeProject.collectLatest { project ->
                agent.bindProject(project)
                checkpoints.bind(project)
                devServer.bind(project)
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
