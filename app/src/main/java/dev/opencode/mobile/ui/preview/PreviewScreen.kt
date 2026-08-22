package dev.opencode.mobile.ui.preview

import android.annotation.SuppressLint
import android.net.Uri
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.opencode.mobile.LocalContainer
import dev.opencode.mobile.ui.components.EmptyState
import dev.opencode.mobile.ui.components.rememberUrlOpener
import dev.opencode.mobile.ui.theme.MonoStyle
import dev.opencode.mobile.ui.theme.StatusWarning
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PreviewScreen() {
    val container = LocalContainer.current
    val preview = container.preview
    val scope = container.scope
    val openUrl = rememberUrlOpener()

    val project by container.workspace.activeProject.collectAsStateWithLifecycle()
    val state by preview.state.collectAsStateWithLifecycle()

    var web by remember { mutableStateOf<WebView?>(null) }
    var entry by remember { mutableStateOf(state.entry) }
    var progress by remember { mutableStateOf(0) }
    var canGoBack by remember { mutableStateOf(false) }
    var showConsole by remember { mutableStateOf(false) }
    val logs = remember { mutableStateListOf<String>() }
    val sheetState = rememberModalBottomSheetState()

    val start: () -> Unit = {
        val dir = project?.dir
        if (dir != null) {
            scope.launch {
                // NanoHTTPD binds a socket; keep that off the main thread.
                withContext(Dispatchers.IO) { runCatching { preview.start(dir, entry.ifBlank { "index.html" }) } }
            }
        }
    }

    // Auto-start on arrival so the tab is never a dead end, and re-point the
    // server when the user switches project.
    LaunchedEffect(project?.path) {
        val dir = project?.dir ?: return@LaunchedEffect
        withContext(Dispatchers.IO) {
            runCatching { preview.start(dir, entry.ifBlank { "index.html" }) }
        }
    }

    LaunchedEffect(state.entry) { entry = state.entry }

    val url = state.url
    LaunchedEffect(url, web) {
        val view = web ?: return@LaunchedEffect
        if (url != null) view.loadUrl(url)
    }

    BackHandler(enabled = canGoBack) { web?.goBack() }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Column {
                    Text("Preview", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = url ?: state.error ?: "Server stopped",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (state.error != null) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            },
            actions = {
                IconButton(onClick = { showConsole = true }) {
                    Icon(
                        Icons.Filled.BugReport,
                        contentDescription = "Console",
                        tint = if (logs.isEmpty()) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            StatusWarning
                        },
                    )
                }
                IconButton(onClick = { web?.reload() }, enabled = state.running) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Reload")
                }
                IconButton(onClick = { url?.let(openUrl) }, enabled = url != null) {
                    Icon(Icons.Filled.OpenInBrowser, contentDescription = "Open in browser")
                }
                if (state.running) {
                    IconButton(onClick = { preview.stop() }) {
                        Icon(Icons.Filled.Stop, contentDescription = "Stop server")
                    }
                } else {
                    IconButton(onClick = start, enabled = project != null) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = "Start server")
                    }
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background,
            ),
        )

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = entry,
                onValueChange = { entry = it },
                label = { Text("Entry file") },
                singleLine = true,
                textStyle = MonoStyle,
                modifier = Modifier.weight(1f),
            )
            Button(
                onClick = {
                    preview.setEntry(entry.trim().ifBlank { "index.html" })
                    if (!state.running) start()
                },
                enabled = project != null,
            ) {
                Text("Go")
            }
        }

        if (progress in 1..99) {
            LinearProgressIndicator(
                progress = { progress / 100f },
                modifier = Modifier.fillMaxWidth().height(2.dp),
            )
        } else {
            Spacer(Modifier.height(2.dp))
        }

        state.error?.let { message ->
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            ) {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(10.dp),
                )
            }
        }

        when {
            project == null -> EmptyState(
                icon = Icons.Filled.Visibility,
                title = "No project open",
                message = "Open a project first, then this tab serves it over loopback HTTP.",
            )

            !state.running -> EmptyState(
                icon = Icons.Filled.Visibility,
                title = "Server stopped",
                message = "Start it to preview ${project?.name}. Pages are served from " +
                    "127.0.0.1 so module imports and fetch work — unlike file:// URLs.",
            ) {
                Button(onClick = start) { Text("Start preview") }
            }

            else -> Box(modifier = Modifier.weight(1f)) {
                PreviewWeb(
                    background = MaterialTheme.colorScheme.background,
                    onCreated = { web = it },
                    onProgress = { progress = it },
                    onHistory = { canGoBack = it },
                    onConsole = { line ->
                        logs.add(line)
                        if (logs.size > 200) logs.removeAt(0)
                    },
                    onExternal = openUrl,
                )
            }
        }
    }

    if (showConsole) {
        ModalBottomSheet(onDismissRequest = { showConsole = false }, sheetState = sheetState) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Console (${logs.size})",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { logs.clear() }) { Text("Clear") }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            if (logs.isEmpty()) {
                Text(
                    text = "Nothing logged yet. JavaScript errors and console output from the " +
                        "page land here.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(20.dp),
                )
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    modifier = Modifier.heightIn(max = 420.dp),
                ) {
                    itemsIndexed(logs) { index, line ->
                        Text(
                            text = line,
                            style = MonoStyle,
                            color = if (line.startsWith("ERROR")) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                            modifier = Modifier.padding(vertical = 3.dp),
                        )
                        if (index < logs.lastIndex) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun PreviewWeb(
    background: Color,
    onCreated: (WebView) -> Unit,
    onProgress: (Int) -> Unit,
    onHistory: (Boolean) -> Unit,
    onConsole: (String) -> Unit,
    onExternal: (String) -> Unit,
) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            WebView(context).apply {
                setBackgroundColor(background.toArgb())
                with(settings) {
                    // Every previewed page is the user's own code served from
                    // loopback, and the live-reload client needs a script engine.
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    // Nothing legitimate needs to reach the app's private files.
                    allowFileAccess = false
                    allowContentAccess = false
                    builtInZoomControls = true
                    displayZoomControls = false
                    useWideViewPort = true
                    loadWithOverviewMode = true
                    mediaPlaybackRequiresUserGesture = false
                }
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: WebResourceRequest?,
                    ): Boolean {
                        val target = request?.url ?: return false
                        if (isLoopback(target)) return false
                        // Outbound links leave for the real browser instead of
                        // becoming a trapped tab inside the preview.
                        onExternal(target.toString())
                        return true
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        onHistory(view?.canGoBack() == true)
                    }
                }
                webChromeClient = object : WebChromeClient() {
                    override fun onProgressChanged(view: WebView?, newProgress: Int) {
                        onProgress(newProgress)
                    }

                    override fun onConsoleMessage(message: ConsoleMessage?): Boolean {
                        if (message != null) {
                            val level = when (message.messageLevel()) {
                                ConsoleMessage.MessageLevel.ERROR -> "ERROR"
                                ConsoleMessage.MessageLevel.WARNING -> "WARN"
                                else -> "LOG"
                            }
                            onConsole("$level ${message.lineNumber()}: ${message.message()}")
                        }
                        return true
                    }
                }
            }
        },
        // Publishing the instance from update, not factory: factory runs inside
        // composition and a state write there would loop the recomposer.
        update = { view -> onCreated(view) },
        onRelease = { view ->
            view.stopLoading()
            view.destroy()
        },
    )
}

private fun isLoopback(uri: Uri): Boolean =
    uri.host == "127.0.0.1" || uri.host == "localhost"
