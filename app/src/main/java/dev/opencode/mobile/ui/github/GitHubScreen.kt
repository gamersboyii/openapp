package dev.opencode.mobile.ui.github

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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CallMerge
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.opencode.mobile.LocalContainer
import dev.opencode.mobile.core.github.GhBranch
import dev.opencode.mobile.core.github.GhComment
import dev.opencode.mobile.core.github.GhCommit
import dev.opencode.mobile.core.github.GhContent
import dev.opencode.mobile.core.github.GhIssue
import dev.opencode.mobile.core.github.GhPull
import dev.opencode.mobile.core.github.GhRepo
import dev.opencode.mobile.core.github.GhReview
import dev.opencode.mobile.core.github.GhRun
import dev.opencode.mobile.core.settings.redactSecrets
import dev.opencode.mobile.ui.components.EmptyState
import dev.opencode.mobile.ui.theme.MonoStyle
import dev.opencode.mobile.ui.theme.StatusWarning
import kotlinx.coroutines.launch

/**
 * Feature 8 — GitHub Hub.
 *
 * Sign-in (PAT or OAuth device flow), account overview, repository browsing,
 * creation where permitted, branches, commits, issues and PRs with comments and
 * review info, Actions status, and one-tap clone into the local workspace.
 * The token never appears here: only account-derived data is rendered.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GitHubScreen(onOpenChat: () -> Unit) {
    val container = LocalContainer.current
    val github = container.github

    val client by github.client.collectAsStateWithLifecycle()
    val account by github.account.collectAsStateWithLifecycle()
    val devicePrompt by github.devicePrompt.collectAsStateWithLifecycle()
    val busy by github.busy.collectAsStateWithLifecycle()
    val authNotice by github.notice.collectAsStateWithLifecycle()

    var repoSlug by rememberSaveable { mutableStateOf<String?>(null) }
    var issueNumber by rememberSaveable { mutableStateOf<Int?>(null) }
    var pullNumber by rememberSaveable { mutableStateOf<Int?>(null) }
    var showCreateRepo by remember { mutableStateOf(false) }

    val snackbar = remember { SnackbarHostState() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = when {
                                issueNumber != null && repoSlug != null -> "Issue #$issueNumber"
                                pullNumber != null && repoSlug != null -> "Pull #$pullNumber"
                                repoSlug != null -> repoSlug.orEmpty().substringAfter('/')
                                else -> "GitHub"
                            },
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val login = account?.login
                        Text(
                            text = login?.let { "signed in as $it" } ?: "Hub",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    val canGoBack = issueNumber != null || pullNumber != null || repoSlug != null
                    if (canGoBack) {
                        IconButton(onClick = {
                            when {
                                issueNumber != null -> issueNumber = null
                                pullNumber != null -> pullNumber = null
                                else -> repoSlug = null
                            }
                        }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            // Create-repo action lives at the top level only.
            if (client != null && repoSlug == null) {
                ExtendedFloatingActionButton(
                    onClick = { showCreateRepo = true },
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text("New repository") },
                )
            }
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            val api = client
            when {
                api == null -> SignInPanel(
                    busy = busy,
                    notice = authNotice,
                    devicePrompt = devicePrompt,
                    clientId = container.settings.settings.collectAsStateWithLifecycle().value.githubClientId,
                    onTokenSignIn = { token -> github.signInWithToken(token) },
                    onStartDeviceFlow = { id -> github.startDeviceFlow(id) },
                )

                issueNumber != null && repoSlug != null ->
                    IssueDetailPane(api = api, slug = repoSlug.orEmpty(), number = issueNumber!!)

                pullNumber != null && repoSlug != null ->
                    PullDetailPane(api = api, slug = repoSlug.orEmpty(), number = pullNumber!!)

                repoSlug != null ->
                    RepoDetailPane(
                        api = api,
                        slug = repoSlug.orEmpty(),
                        onOpenIssue = { issueNumber = it },
                        onOpenPull = { pullNumber = it },
                        onCloned = {
                            container.scope.launch { snackbar.showSnackbar("Repository cloned — project switched.") }
                            onOpenChat()
                        },
                        onNotice = { msg -> container.scope.launch { snackbar.showSnackbar(msg.redactSecrets()) } },
                    )

                else -> RepositoriesPane(
                    api = api,
                    onOpenRepo = { slug -> repoSlug = slug },
                )
            }

            authNotice.takeIf { it.isNotBlank() && api != null }?.let { message ->
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.align(Alignment.TopCenter).padding(12.dp),
                ) {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }

    if (showCreateRepo) {
        CreateRepoDialog(
            onDismiss = { showCreateRepo = false },
            onCreate = { name, description, isPrivate, autoInit ->
                showCreateRepo = false
                val session = container.scope
                session.launch {
                    runCatching { client?.createRepo(name, description, isPrivate, autoInit) }
                        .onSuccess { repo -> repo?.let { r -> container.scope.launch { snackbar.showSnackbar("Created ${r.slug}") } } }
                        .onFailure { e -> container.scope.launch { snackbar.showSnackbar((e.message ?: "Create failed").redactSecrets()) } }
                }
            },
        )
    }
}

// ---- sign-in -----------------------------------------------------------------

@Composable
private fun SignInPanel(
    busy: Boolean,
    notice: String,
    devicePrompt: dev.opencode.mobile.core.github.GitHubSession.DevicePrompt?,
    clientId: String,
    onTokenSignIn: (String) -> Unit,
    onStartDeviceFlow: (String) -> Unit,
) {
    val openUrl = dev.opencode.mobile.ui.components.rememberUrlOpener()
    var token by remember { mutableStateOf("") }
    var clientIdText by remember(clientId) { mutableStateOf(clientId) }
    var showClientIdField by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Filled.Code,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(36.dp),
        )
        Spacer(Modifier.height(10.dp))
        Text("Connect to GitHub", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(6.dp))
        Text(
            "Sign in to browse repositories, read issues and pull requests, track Actions, " +
                "and clone private repos. The token is stored encrypted and never shown " +
                "to the AI agent.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(18.dp))
        OutlinedTextField(
            value = token,
            onValueChange = { token = it.trim() },
            label = { Text("Personal access token") },
            placeholder = { Text("ghp_… or github_pat_…") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(10.dp))
        Button(
            onClick = { onTokenSignIn(token) },
            enabled = token.length >= 20 && !busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (busy) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            else Text("Sign in with token")
        }
        TextButton(onClick = { openUrl("https://github.com/settings/tokens?type=beta") }) {
            Icon(Icons.Filled.OpenInNew, contentDescription = null, Modifier.size(15.dp))
            Spacer(Modifier.width(6.dp))
            Text("Create a token (repo + workflow scopes)")
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 14.dp))

        Text("Or use OAuth device flow", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(4.dp))
        Text(
            "Requires your own OAuth app's Client ID — no secret ships in this APK.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = { showClientIdField = !showClientIdField }) {
                Text(if (showClientIdField) "Hide client ID" else "Enter client ID")
            }
            Button(
                onClick = { onStartDeviceFlow(clientIdText) },
                enabled = !busy,
            ) { Text("Start device sign-in") }
        }
        if (showClientIdField || clientIdText.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = clientIdText,
                onValueChange = { clientIdText = it.trim() },
                label = { Text("OAuth App Client ID") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        devicePrompt?.let { prompt ->
            Spacer(Modifier.height(16.dp))
            Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(12.dp)) {
                Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
                    Text("1 · Open", style = MaterialTheme.typography.labelMedium)
                    TextButton(onClick = { openUrl(prompt.verificationUri) }) {
                        Text(prompt.verificationUri, fontWeight = FontWeight.Bold)
                    }
                    Text("2 · Enter code", style = MaterialTheme.typography.labelMedium)
                    Text(
                        text = prompt.userCode,
                        style = dev.opencode.mobile.ui.theme.MonoStyle.copy(fontSize = 22.sp),
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 2.dp, bottom = 4.dp),
                    )
                    Text(
                        "Waiting for authorization…",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
        }

        if (notice.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = notice,
                style = MaterialTheme.typography.labelMedium,
                color = StatusWarning,
            )
        }
    }
}

// ---- repositories ---------------------------------------------------------------

private class Loadable<T>(val value: T? = null, val error: String? = null)

@Composable
private fun RepositoriesPane(api: dev.opencode.mobile.core.github.GitHubClient, onOpenRepo: (String) -> Unit) {
    val repos by produceState<Loadable<List<GhRepo>>>(initialValue = Loadable(), producer = {
        value = try {
            Loadable(api.listRepos())
        } catch (error: Throwable) {
            Loadable(error = error.message ?: "Failed to load repositories")
        }
    })

    val reposSnapshot = repos
    when {
        reposSnapshot.error != null -> EmptyState(
            icon = Icons.Filled.Code,
            title = "Could not load repositories",
            message = reposSnapshot.error,
        )

        reposSnapshot.value == null -> LoadingRow()

        reposSnapshot.value.isEmpty() -> EmptyState(
            icon = Icons.Filled.Code,
            title = "No repositories yet",
            message = "Repositories you own or collaborate on appear here.",
        )

        else -> LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(items = reposSnapshot.value!!, key = { it.id }) { repo ->
                RepoCard(repo = repo, onClick = { onOpenRepo(repo.slug) })
            }
        }
    }
}

@Composable
private fun RepoCard(repo: GhRepo, onClick: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = repo.fullName,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (repo.isPrivate) {
                    Surface(color = StatusWarning.copy(alpha = 0.18f), shape = RoundedCornerShape(5.dp)) {
                        Text(
                            "private",
                            style = MaterialTheme.typography.labelSmall,
                            color = StatusWarning,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                        )
                    }
                }
            }
            repo.description?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
            Row(modifier = Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MetaText("${repo.defaultBranch}")
                repo.language?.let { MetaText(it) }
                MetaText("\u2605 ${repo.stargazersCount}")
                if (repo.openIssuesCount > 0) MetaText("${repo.openIssuesCount} issues")
            }
        }
    }
}

@Composable
private fun MetaText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

// ---- repo detail ---------------------------------------------------------------

private enum class RepoTab { CONTENTS, COMMITS, BRANCHES, ISSUES, PULLS, ACTIONS }

@Composable
private fun RepoDetailPane(
    api: dev.opencode.mobile.core.github.GitHubClient,
    slug: String,
    onOpenIssue: (Int) -> Unit,
    onOpenPull: (Int) -> Unit,
    onCloned: () -> Unit,
    onNotice: (String) -> Unit,
) {
    val container = LocalContainer.current
    val workspace = container.workspace
    val scope = container.scope
    val settings by container.settings.settings.collectAsStateWithLifecycle()

    var tab by rememberSaveable(slug) { mutableIntStateOf(0) }
    var cloning by remember { mutableStateOf(false) }
    val tabs = RepoTab.entries.toList()

    val repoInfo by produceState<Loadable<GhRepo>>(initialValue = Loadable(), key1 = slug) {
        value = try {
            Loadable(api.getRepo(slug))
        } catch (error: Throwable) {
            Loadable(error = error.message)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // clone strip
        Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "default: ${repoInfo.value?.defaultBranch ?: "…"}" +
                            if (repoInfo.value?.permissions?.push == true) " · push ok" else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (cloning) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Button(
                        onClick = {
                            val info = repoInfo.value
                            cloning = true
                            scope.launch {
                                val url = "https://github.com/$slug.git"
                                val dir = runCatching { workspace.createProjectDir(info?.name ?: slug.substringAfter('/')) }.getOrNull()
                                if (dir == null) {
                                    onNotice("Could not create project directory")
                                    cloning = false
                                    return@launch
                                }
                                runCatching {
                                    container.git.clone(
                                        url = url,
                                        targetDir = dir,
                                        branch = info?.defaultBranch,
                                        credentials = settings.effectiveGitCredentials,
                                    ) { _, _ -> }
                                }.onSuccess {
                                    container.workspace.refresh()
                                    container.workspace.selectByPath(dir.absolutePath)
                                    container.settings.update { s -> s.copy(lastProjectPath = dir.absolutePath) }
                                    onNotice("Cloned $slug")
                                    cloning = false
                                    onCloned()
                                }.onFailure { error ->
                                    dir.deleteRecursively()
                                    container.workspace.refresh()
                                    onNotice("Clone failed: ${error.message ?: "unknown"}")
                                    cloning = false
                                }
                            }
                        },
                        enabled = repoInfo.value != null,
                    ) {
                        Icon(Icons.Filled.Code, contentDescription = null, Modifier.size(15.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Clone")
                    }
                }
            }
        }

        TabRow(selectedTabIndex = tab) {
            tabs.forEachIndexed { index, repoTab ->
                Tab(
                    selected = tab == index,
                    onClick = { tab = index },
                    text = { Text(repoTab.name.lowercase()) },
                )
            }
        }

        Box(modifier = Modifier.weight(1f)) {
            when (tabs[tab]) {
                RepoTab.CONTENTS -> ContentsTab(api = api, slug = slug, defaultRef = repoInfo.value?.defaultBranch)
                RepoTab.COMMITS -> CommitsTab(api = api, slug = slug, defaultRef = repoInfo.value?.defaultBranch)
                RepoTab.BRANCHES -> BranchesTab(api = api, slug = slug)
                RepoTab.ISSUES -> IssuesTab(api = api, slug = slug, onOpenIssue = onOpenIssue)
                RepoTab.PULLS -> PullsTab(api = api, slug = slug, onOpenPull = onOpenPull)
                RepoTab.ACTIONS -> ActionsTab(api = api, slug = slug)
            }
        }
    }
}

/** Simple path-driven contents browser (feature 8 “repository browsing”). */
@Composable
private fun ContentsTab(api: dev.opencode.mobile.core.github.GitHubClient, slug: String, defaultRef: String?) {
    var path by rememberSaveable(slug) { mutableStateOf("") }

    val entries by produceState<Loadable<List<GhContent>>>(
        initialValue = Loadable(),
        key1 = slug, key2 = path,
    ) {
        value = try {
            Loadable(api.listContents(slug, path, defaultRef))
        } catch (error: Throwable) {
            Loadable(error = error.message)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        if (path.isNotBlank()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clickable {
                    path = path.substringBeforeLast('/', "")
                }.padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                Text(".. up one level", color = MaterialTheme.colorScheme.primary, style = MonoStyle)
            }
        }
        val list = entries.value
        when {
            entries.error != null -> TabError(entries.error!!)
            list == null -> LoadingRow()
            list.isEmpty() -> TabMessage("Empty directory")
            else -> LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                items(items = list, key = { it.path }) { item ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(if (item.isDirectory) Modifier.clickable { path = item.path } else Modifier)
                            .padding(horizontal = 14.dp, vertical = 9.dp),
                    ) {
                        Text(
                            text = if (item.isDirectory) "\uD83D\uDCC1 ${item.name}" else item.name,
                            style = MonoStyle,
                            color = if (item.isDirectory) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        if (!item.isDirectory && item.size > 0) {
                            Text(
                                "${item.size / 1024} KB".replace(" 0 KB", "<1 KB"),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun SectionLabel(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
    )
}

@Composable
private fun CommitsTab(api: dev.opencode.mobile.core.github.GitHubClient, slug: String, defaultRef: String?) {
    val commits by produceState<Loadable<List<GhCommit>>>(initialValue = Loadable(), key1 = slug, key2 = defaultRef) {
        value = try {
            Loadable(api.listCommits(slug, ref = defaultRef, limit = 40))
        } catch (error: Throwable) {
            Loadable(error = error.message)
        }
    }
    ListOrState(commits) { commit ->
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp)) {
            Text(commit.subjectLine, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                "${commit.shortSha} · ${commit.authorName.ifBlank { "?" }}${commit.commit?.author?.date?.let { " · ${it.take(10)}" } ?: ""}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun BranchesTab(api: dev.opencode.mobile.core.github.GitHubClient, slug: String) {
    val branches by produceState<Loadable<List<GhBranch>>>(initialValue = Loadable(), key1 = slug) {
        value = try {
            Loadable(api.listBranches(slug))
        } catch (error: Throwable) {
            Loadable(error = error.message)
        }
    }
    ListOrState(branches) { branch ->
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 9.dp),
        ) {
            Icon(Icons.Filled.Code, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(9.dp))
            Text(branch.name, style = MonoStyle, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text(branch.commit?.sha?.take(7) ?: "", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun IssuesTab(api: dev.opencode.mobile.core.github.GitHubClient, slug: String, onOpenIssue: (Int) -> Unit) {
    var stateFilter by rememberSaveable(slug) { mutableStateOf("open") }
    val issues by produceState<Loadable<List<GhIssue>>>(initialValue = Loadable(), key1 = slug, key2 = stateFilter) {
        value = try {
            Loadable(api.listIssues(slug, state = stateFilter))
        } catch (error: Throwable) {
            Loadable(error = error.message)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            listOf("open", "closed").forEach { option ->
                FilterChip(
                    selected = stateFilter == option,
                    onClick = { stateFilter = option },
                    label = { Text(option) },
                )
            }
        }
        ListOrState(issues, emptyText = "No issues.") { issue ->
            Column(modifier = Modifier.fillMaxWidth().clickable { onOpenIssue(issue.number) }
                .padding(horizontal = 14.dp, vertical = 9.dp)) {
                Text(
                    "#${issue.number}  ${issue.title}",
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    buildString {
                        append("[${issue.state}]")
                        issue.user?.login?.let { append(" ${it}") }
                        if (issue.comments > 0) append(" · ${issue.comments} comments")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

@Composable
private fun PullsTab(api: dev.opencode.mobile.core.github.GitHubClient, slug: String, onOpenPull: (Int) -> Unit) {
    var stateFilter by rememberSaveable(slug) { mutableStateOf("open") }
    val pulls by produceState<Loadable<List<GhPull>>>(initialValue = Loadable(), key1 = slug, key2 = stateFilter) {
        value = try {
            Loadable(api.listPulls(slug, state = stateFilter))
        } catch (error: Throwable) {
            Loadable(error = error.message)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            listOf("open", "closed").forEach { option ->
                FilterChip(
                    selected = stateFilter == option,
                    onClick = { stateFilter = option },
                    label = { Text(option) },
                )
            }
        }
        ListOrState(pulls, emptyText = "No pull requests.") { pull ->
            Column(modifier = Modifier.fillMaxWidth().clickable { onOpenPull(pull.number) }
                .padding(horizontal = 14.dp, vertical = 9.dp)) {
                Text(
                    "#${pull.number}  ${pull.title}" + if (pull.draft) "  [draft]" else "",
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${pull.headRef} \u2192 ${pull.baseRef}" +
                        (if (pull.merged) " · merged" else "") +
                        " · +${pull.additions} −${pull.deletions}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

@Composable
private fun ActionsTab(api: dev.opencode.mobile.core.github.GitHubClient, slug: String) {
    val runs by produceState<Loadable<List<GhRun>>>(initialValue = Loadable(), key1 = slug) {
        value = try {
            Loadable(api.listWorkflowRuns(slug, limit = 25))
        } catch (error: Throwable) {
            Loadable(error = error.message)
        }
    }
    ListOrState(runs, emptyText = "No workflow runs recorded.") { run ->
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 9.dp),
        ) {
            val (glyph, tint) = when {
                run.isRunning -> "\u25CF" to MaterialTheme.colorScheme.primary
                run.conclusion == "success" -> "\u2713" to DiffOkGreen
                run.isFailure -> "\u2717" to MaterialTheme.colorScheme.error
                else -> "\u2013" to MaterialTheme.colorScheme.onSurfaceVariant
            }
            Text(glyph, style = MaterialTheme.typography.titleMedium, color = tint)
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "#${run.runNumber} ${run.name ?: run.displayTitle ?: "workflow"}",
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    listOfNotNull(run.status, run.conclusion, run.headBranch).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private val DiffOkGreen = androidx.compose.ui.graphics.Color(0xFF9ECE6A)

@Composable
private inline fun <reified T> ListOrState(
    state: Loadable<List<T>>,
    emptyText: String = "Nothing here.",
    crossinline row: @Composable (T) -> Unit,
) {
    when {
        state.error != null -> TabError(state.error!!)
        state.value == null -> LoadingRow()
        state.value!!.isEmpty() -> TabMessage(emptyText)
        else -> LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            items(items = state.value!!) { item -> row(item) }
        }
    }
}

@Composable
private fun LoadingRow() {
    Box(modifier = Modifier.fillMaxWidth().padding(28.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun TabMessage(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(24.dp),
    )
}

@Composable
private fun TabError(message: String) {
    Text(
        text = message,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.fillMaxWidth().padding(16.dp),
    )
}

// ---- issue detail ------------------------------------------------------------

@Composable
private fun IssueDetailPane(api: dev.opencode.mobile.core.github.GitHubClient, slug: String, number: Int) {
    val issue by produceState<Loadable<GhIssue>>(initialValue = Loadable(), key1 = slug, key2 = number) {
        value = try {
            Loadable(api.getIssue(slug, number))
        } catch (error: Throwable) {
            Loadable(error = error.message)
        }
    }

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        val current = issue.value
        when {
            issue.error != null -> TabError(issue.error!!)
            current == null -> LoadingRow()
            else -> {
                Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(current.title, style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "#${current.number} · ${current.state}" +
                                (current.user?.login?.let { " · opened by $it" } ?: ""),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                current.body?.takeIf { it.isNotBlank() }?.let { body ->
                    Text(
                        text = body,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(14.dp),
                    )
                }
            }
        }

        CommentsBlock(api = api, slug = slug, number = number)
        Spacer(Modifier.height(28.dp))
    }
}

@Composable
private fun PullDetailPane(api: dev.opencode.mobile.core.github.GitHubClient, slug: String, number: Int) {
    val pull by produceState<Loadable<GhPull>>(initialValue = Loadable(), key1 = slug, key2 = number) {
        value = try {
            Loadable(api.getPull(slug, number))
        } catch (error: Throwable) {
            Loadable(error = error.message)
        }
    }
    val reviews by produceState<Loadable<List<GhReview>>>(initialValue = Loadable(emptyList()), key1 = slug, key2 = number) {
        value = try {
            Loadable(api.listReviews(slug, number))
        } catch (error: Throwable) {
            Loadable(error = error.message)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        val current = pull.value
        if (pull.error != null) {
            TabError(pull.error!!)
        } else if (current == null) {
            LoadingRow()
        } else {
            Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(current.title, style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "${current.state}${if (current.draft) " · draft" else ""}${if (current.merged) " · merged" else ""}" +
                            " · ${current.headRef} \u2192 ${current.baseRef}" +
                            " · +${current.additions} −${current.deletions} across ${current.changedFiles} files",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            val reviewList = reviews.value.orEmpty()
            if (reviewList.isNotEmpty()) {
                SectionLabel("Reviews")
                reviewList.forEach { review ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 5.dp),
                    ) {
                        Text(
                            text = review.state ?: "?",
                            style = MaterialTheme.typography.labelSmall,
                            color = when (review.state) {
                                "APPROVED" -> DiffOkGreen
                                "CHANGES_REQUESTED" -> MaterialTheme.colorScheme.error
                                else -> StatusWarning
                            },
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.width(64.dp),
                        )
                        Text(
                            text = "${review.user?.login ?: "?"}" +
                                (review.body?.takeIf { it.isNotBlank() }?.let { " — ${it.take(140)}" } ?: ""),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }

        CommentsBlock(api = api, slug = slug, number = number)
        Spacer(Modifier.height(28.dp))
    }
}

// ---- comments (issues and PRs share the endpoint) ------------------------------

/**
 * Loads and renders the comment thread, then offers a write box. Posting is a
 * mutating call: it goes through the container scope so it survives navigation.
 */
@Composable
private fun CommentsBlock(
    api: dev.opencode.mobile.core.github.GitHubClient,
    slug: String,
    number: Int,
) {
    val container = LocalContainer.current
    val snackbar = remember { SnackbarHostState() }
    var commentDraft by remember { mutableStateOf("") }
    var posting by remember { mutableStateOf(false) }

    val comments by produceState<Loadable<List<GhComment>>>(initialValue = Loadable(), key1 = slug, key2 = number) {
        value = try {
            Loadable(api.listComments(slug, number))
        } catch (error: Throwable) {
            Loadable(error = error.message)
        }
    }

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        SectionLabel("Comments")
        val list = comments.value
        when {
            comments.error != null -> TabError(comments.error!!)
            list == null -> LoadingRow()
            list.isEmpty() -> TabMessage("No comments yet.")
            else -> list.forEach { comment ->
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp)) {
                    Text(
                        "${comment.user?.login ?: "?"}" +
                            (comment.createdAt?.let { " · ${it.take(10)}" } ?: ""),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(comment.body, style = MaterialTheme.typography.bodySmall)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }

        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = commentDraft,
            onValueChange = { commentDraft = it },
            label = { Text("Write a comment") },
            minLines = 2,
            maxLines = 6,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp),
        )
        Row(
            horizontalArrangement = Arrangement.End,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp),
        ) {
            if (posting) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                Button(
                    onClick = {
                        if (commentDraft.isNotBlank()) {
                            posting = true
                            val body = commentDraft
                            commentDraft = ""
                            container.scope.launch {
                                try {
                                    api.addComment(slug, number, body)
                                    posting = false
                                    snackbar.showSnackbar("Comment posted")
                                } catch (e: Throwable) {
                                    posting = false
                                    commentDraft = body
                                    snackbar.showSnackbar((e.message ?: "Post failed").redactSecrets())
                                }
                            }
                        }
                    },
                    enabled = commentDraft.isNotBlank(),
                ) { Text("Post") }
            }
        }
        SnackbarHost(hostState = snackbar)
    }
}

@Composable
private fun CreateRepoDialog(
    onDismiss: () -> Unit,
    onCreate: (String, String, Boolean, Boolean) -> Unit,
) {
    var name by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
    var description by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
    var isPrivate by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var autoInit by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(true) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { androidx.compose.material3.Text("Create repository") },
        text = {
            androidx.compose.foundation.layout.Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.OutlinedTextField(value = name, onValueChange = { name = it }, label = { androidx.compose.material3.Text("Name") }, singleLine = true)
                androidx.compose.material3.OutlinedTextField(value = description, onValueChange = { description = it }, label = { androidx.compose.material3.Text("Description") })
                androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    androidx.compose.material3.Checkbox(checked = isPrivate, onCheckedChange = { isPrivate = it })
                    androidx.compose.material3.Text(" Private", modifier = androidx.compose.ui.Modifier.clickable { isPrivate = !isPrivate })
                }
                androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    androidx.compose.material3.Checkbox(checked = autoInit, onCheckedChange = { autoInit = it })
                    androidx.compose.material3.Text(" Auto-init README", modifier = androidx.compose.ui.Modifier.clickable { autoInit = !autoInit })
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = { onCreate(name.trim(), description.trim(), isPrivate, autoInit) }, enabled = name.isNotBlank()) { androidx.compose.material3.Text("Create") }
        },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { androidx.compose.material3.Text("Cancel") } },
    )
}