package dev.opencode.mobile.core.preview

import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Serves the active project over `http://127.0.0.1:<port>` so the WebView can
 * load it like a real site.
 *
 * A plain `file://` load is not enough: Chromium blocks ES module imports,
 * `fetch`, and most CDN import maps on file URLs. Going through a loopback HTTP
 * server is what makes React/Vue-from-CDN templates work with no build step.
 */
class PreviewServer {

    private var server: Impl? = null

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    data class State(
        val running: Boolean = false,
        val port: Int = 0,
        val rootPath: String? = null,
        val entry: String = "index.html",
        val error: String? = null,
    ) {
        val url: String? get() = if (running) "http://127.0.0.1:$port/$entry" else null
    }

    /** (Re)starts the server rooted at [root]. Returns the URL to load. */
    fun start(root: File, entry: String = "index.html"): String {
        stop()
        var lastError: Exception? = null
        for (port in PORT_RANGE) {
            val attempt = Impl(root, port)
            try {
                attempt.start(SOCKET_TIMEOUT_MS, false)
                server = attempt
                _state.value = State(
                    running = true,
                    port = port,
                    rootPath = root.absolutePath,
                    entry = entry.removePrefix("/"),
                    error = null,
                )
                return _state.value.url!!
            } catch (e: Exception) {
                lastError = e
                attempt.stop()
            }
        }
        _state.value = State(error = "No free port in $PORT_RANGE: ${lastError?.message}")
        throw IllegalStateException(_state.value.error)
    }

    fun stop() {
        server?.stop()
        server = null
        if (_state.value.running) _state.value = State()
    }

    /** Tells connected pages to reload; the injected client polls for this. */
    fun signalReload() {
        server?.revision?.incrementAndGet()
    }

    fun setEntry(entry: String) {
        _state.value = _state.value.copy(entry = entry.removePrefix("/"))
    }

    private class Impl(private val root: File, port: Int) : NanoHTTPD("127.0.0.1", port) {

        val revision = java.util.concurrent.atomic.AtomicLong(0)

        override fun serve(session: IHTTPSession): Response {
            val rawPath = session.uri.substringBefore('?').ifBlank { "/" }

            if (rawPath == RELOAD_ENDPOINT) {
                return noStore(
                    newFixedLengthResponse(
                        Response.Status.OK,
                        "text/plain",
                        revision.get().toString(),
                    ),
                )
            }

            val decoded = runCatching {
                java.net.URLDecoder.decode(rawPath, "UTF-8")
            }.getOrDefault(rawPath)

            val resolved = resolve(decoded) ?: return noStore(
                newFixedLengthResponse(
                    Response.Status.NOT_FOUND,
                    MIME_HTML,
                    notFoundPage(decoded),
                ),
            )

            val mime = mimeFor(resolved.name)
            return if (mime == MIME_HTML) {
                val html = injectReloadClient(resolved.readText())
                noStore(newFixedLengthResponse(Response.Status.OK, MIME_HTML, html))
            } else {
                noStore(
                    newFixedLengthResponse(
                        Response.Status.OK,
                        mime,
                        FileInputStream(resolved),
                        resolved.length(),
                    ),
                )
            }
        }

        /**
         * Directory requests fall back to `index.html`. Extension-less misses also
         * fall back to the root `index.html` so client-side routers work.
         */
        private fun resolve(path: String): File? {
            val base = root.canonicalFile
            val candidate = File(base, path.removePrefix("/")).canonicalFile
            if (candidate.path != base.path && !candidate.path.startsWith(base.path + File.separator)) {
                return null
            }
            if (candidate.isDirectory) {
                val index = File(candidate, "index.html")
                return index.takeIf { it.isFile }
            }
            if (candidate.isFile) return candidate

            val looksLikeAsset = candidate.name.contains('.')
            if (!looksLikeAsset) {
                val rootIndex = File(base, "index.html")
                if (rootIndex.isFile) return rootIndex
            }
            return null
        }

        private fun injectReloadClient(html: String): String {
            if (html.contains(RELOAD_MARKER)) return html
            val script = """
                <script>$RELOAD_MARKER
                (function () {
                  var current = null;
                  setInterval(function () {
                    fetch('$RELOAD_ENDPOINT', { cache: 'no-store' })
                      .then(function (r) { return r.text(); })
                      .then(function (v) {
                        if (current === null) { current = v; return; }
                        if (v !== current) location.reload();
                      })
                      .catch(function () {});
                  }, 1200);
                })();
                </script>
            """.trimIndent()

            val closingBody = html.lastIndexOf("</body>", ignoreCase = true)
            return if (closingBody >= 0) {
                html.substring(0, closingBody) + script + html.substring(closingBody)
            } else {
                html + script
            }
        }

        private fun notFoundPage(path: String): String = """
            <!doctype html><meta name="viewport" content="width=device-width,initial-scale=1">
            <body style="font:14px ui-monospace,monospace;background:#0f1115;color:#c0caf5;padding:24px">
            <h3 style="color:#f7768e">404 — not found</h3>
            <p>No file matched <code>$path</code> in <code>${root.name}</code>.</p>
            <p style="color:#565f89">Create an <code>index.html</code>, or change the preview entry file.</p>
            </body>
        """.trimIndent()

        private fun noStore(response: Response): Response = response.apply {
            addHeader("Cache-Control", "no-store, must-revalidate")
            addHeader("Pragma", "no-cache")
        }
    }

    companion object {
        private val PORT_RANGE = 8765..8785
        private const val SOCKET_TIMEOUT_MS = 10_000
        private const val MIME_HTML = "text/html; charset=utf-8"
        private const val RELOAD_ENDPOINT = "/__opencode/revision"
        private const val RELOAD_MARKER = "/*opencode-live-reload*/"

        private val MIME_TYPES = mapOf(
            "html" to MIME_HTML,
            "htm" to MIME_HTML,
            "js" to "application/javascript; charset=utf-8",
            "mjs" to "application/javascript; charset=utf-8",
            "jsx" to "application/javascript; charset=utf-8",
            "ts" to "application/javascript; charset=utf-8",
            "tsx" to "application/javascript; charset=utf-8",
            "css" to "text/css; charset=utf-8",
            "json" to "application/json; charset=utf-8",
            "map" to "application/json; charset=utf-8",
            "svg" to "image/svg+xml",
            "png" to "image/png",
            "jpg" to "image/jpeg",
            "jpeg" to "image/jpeg",
            "gif" to "image/gif",
            "webp" to "image/webp",
            "ico" to "image/x-icon",
            "avif" to "image/avif",
            "woff" to "font/woff",
            "woff2" to "font/woff2",
            "ttf" to "font/ttf",
            "otf" to "font/otf",
            "mp4" to "video/mp4",
            "webm" to "video/webm",
            "mp3" to "audio/mpeg",
            "wasm" to "application/wasm",
            "txt" to "text/plain; charset=utf-8",
            "md" to "text/plain; charset=utf-8",
            "xml" to "application/xml; charset=utf-8",
            "pdf" to "application/pdf",
        )

        fun mimeFor(fileName: String): String =
            MIME_TYPES[fileName.substringAfterLast('.', "").lowercase()]
                ?: "application/octet-stream"
    }
}
