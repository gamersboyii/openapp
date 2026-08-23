# CLAUDE.md — codebase context

Android app: an AI coding agent that runs entirely on the phone. Repo
[gamersboyii/openapp](https://github.com/gamersboyii/openapp), package
`dev.opencode.mobile`, ~9.2k lines of Kotlin across 34 files.

## Hard constraints (do not violate)

1. **No companion server.** Nothing may depend on a desktop, LAN address, or
   SSH tunnel. The only outbound calls are the user's LLM endpoint and the git
   host.
2. **No Linux / proot / node / npm.** So project templates must run in a browser
   with **zero build step** — CDN + import maps only. Never add a template or
   tool that needs a bundler or a package install.
3. **Cannot compile locally.** No Android SDK, no gradle, no adb on the dev
   machine. Every change is verified only by GitHub Actions. Write defensively:
   filled Material icons only, no Compose API newer than 1.7.x, no experimental
   overload without `@OptIn`.

## Versions

Kotlin 2.0.21 · AGP 8.5.2 · Gradle 8.7 · JDK 17 · compose BOM 2024.09.00
(→ ui/foundation 1.7.x, material3 1.3.0) · compileSdk/targetSdk 34 ·
**minSdk 26** (JGit needs `java.nio.file`) · JGit 5.13.0.202109080827-r
(last Java-8 line; SSH transports excluded, HTTPS only) · NanoHTTPD 2.3.1 ·
OkHttp 4.12 · kotlinx-serialization 1.7.3 · navigation-compose 2.8.3.

Versions live in `gradle/libs.versions.toml`; `app/build.gradle.kts` references
them via `libs.*`.

## Build

CI: `.github/workflows/android.yml` — checkout, JDK 17, `gradle/actions/setup-gradle@v4`
(Gradle 8.7), `sdkmanager` pins `platforms;android-34` + `build-tools;34.0.0`,
then `gradle :app:assembleDebug`. Artifact `opencode-debug-apk` holds
`opencode-<short-sha>.apk`. **No `gradle-wrapper.jar` is committed**, so CI
invokes `gradle`, not `./gradlew`.

Reading a failing run without `gh` installed (it is not): job list via
`api.github.com/repos/gamersboyii/openapp/actions/runs/<id>/jobs`, then logs via
`.../actions/jobs/<job_id>/logs` with a bearer token pulled from Windows
Credential Manager:

```bash
printf 'protocol=https\nhost=github.com\n\n' | git credential fill
```

## Architecture

Manual service locator, no DI framework. `AppContainer` in
[OpenCodeApp.kt](app/src/main/java/dev/opencode/mobile/OpenCodeApp.kt) builds
every app-lifetime singleton — `settings`, `workspace`, `git`, `snapshots`,
`preview`, `commandHistory`, `terminal`, `builds`, `scope`, `agent` — and is
handed to Compose through `LocalContainer` (a `staticCompositionLocalOf`).
`bootstrap()` restores the last project and the command history, and keeps the
agent transcript, remembered path, and preview root in step with
`workspace.activeProject`.

`OpenCodeApp.onCreate` calls `AndroidSystemReader.install(filesDir)` **before**
`AppContainer` is constructed. JGit otherwise probes `$HOME/.gitconfig` and
`/etc/gitconfig`, which do not exist on Android.

State is `StateFlow` throughout, consumed with `collectAsStateWithLifecycle`. No
ViewModels — screens hold local `remember` state and read the container.

### Module map

| Path | Role |
| --- | --- |
| `agent/AgentEngine.kt` (~590) | tool-calling loop, transcript, approval gate, session persistence |
| `agent/Tools.kt` (~640) | 21 `AgentTool` objects + `ToolRegistry` |
| `agent/Tool.kt` (~120) | `AgentTool`/`ToolContext` contracts, JSON arg + schema helpers, dynamic `needsApproval` |
| `agent/Templates.kt` (538) | 6 zero-build project templates |
| `core/fs/WorkspaceManager.kt` (317) | sandboxed file ops, search, zip export |
| `core/exec/TerminalService.kt` (~360) | sandboxed `sh -c` execution: process registry, timeouts, output caps, history store |
| `core/exec/CommandPolicy.kt` (~150) | SAFE / ASK / BLOCK command classifier |
| `core/build/BuildSystem.kt` (~475) | project-type detection + build/test/run/clean with structured diagnostics |
| `core/git/GitService.kt` (239) | JGit clone/status/commit/log/diff/branch/pull/push |
| `core/git/RepoSnapshotService.kt` (118) | zip-archive download alternative to clone |
| `core/git/AndroidSystemReader.kt` (60) | JGit `SystemReader` replacement |
| `core/preview/PreviewServer.kt` (232) | NanoHTTPD loopback server + live reload |
| `core/settings/SettingsStore.kt` (~130) | `AppSettings` as one JSON blob in EncryptedSharedPreferences |
| `core/util/Highlighter.kt` (177) | regex syntax highlighting |
| `llm/` (5 files) | provider registry, 3 wire protocols, SSE reader |
| `ui/` (10 files) | Compose screens (incl. terminal) + theme + shared components |

### Agent loop

[AgentEngine.kt](app/src/main/java/dev/opencode/mobile/agent/AgentEngine.kt):
stream one assistant turn, run any tool calls, feed results back, repeat until
the model stops calling tools or `settings.maxSteps` (default 24) is hit.

- `entries: StateFlow<List<ChatEntry>>` drives the UI; `history` holds the raw
  `ChatMessage` list sent to the model.
- Approval: tools decide per call via `needsApproval(args, settings)` — default
  is `mutating && !settings.autoApproveWrites`; `run_command` and
  `build_project` override it with the CommandPolicy verdict and
  `settings.autoApproveCommands`. The engine parks on a
  `CompletableDeferred<Boolean>` exposed as
  `pendingApproval: StateFlow<ApprovalRequest?>`; the UI calls
  `respondToApproval(Boolean)`.
- Session is persisted to `<project>/.opencode/session.json`
  (`SESSION_DIR`/`SESSION_FILE` constants).
- Reads (`list_files`, `read_file`, `search_code`, `project_info`, `git_status`,
  `git_diff`, `git_log`, `preview`) are not gated. Mutating: `write_file`,
  `edit_file`, `delete_path`, `create_directory`, `create_project`, `git_clone`,
  `fetch_repo_snapshot`, `git_init`, `git_commit`, `git_push`, `git_pull`.
- `run_command` executes through the sandboxed terminal; BLOCK-classified
  commands return an ERROR to the model without ever prompting. SAFE ones run
  silently. ASK prompts unless auto-approve commands is on.
- `build_project` gates every action except `detect`.

Adding a tool: implement `AgentTool` (`name`, `description`, `parameters` via
`schema(...)`/`stringProp`/`boolProp`/`intProp`, `execute`, `summarize`, and
`mutating = true` / a custom `needsApproval` if it acts), then register it in
`ToolRegistry.tools`. `ToolContext.requireProject()` throws a message aimed at
the model, not the user.

### Terminal & build

[TerminalService.kt](app/src/main/java/dev/opencode/mobile/core/exec/TerminalService.kt)
runs everything through `sh -c` with the working directory pinned to the active
project, a fixed minimal environment (`PATH` = system toybox only), per-stream
output caps (200k chars), a hard timeout (`settings.commandTimeoutSeconds`),
SIGTERM-then-kill cancellation, and at most 3 concurrent processes. The real
security boundary is Android's own app sandbox; CommandPolicy is the layer that
stops bad commands before they start.

[CommandPolicy.kt](app/src/main/java/dev/opencode/mobile/core/exec/CommandPolicy.kt)
classifies segment-by-segment (pipes, `&&`, `;`): read-only allowlist → SAFE,
everything else → ASK, destructive patterns / absolute paths outside the
approved prefixes → BLOCK. Conservative by design: unknown means ASK, never
SAFE. Watch for accidental escape hatches when adding commands to the allowlist
(`env`, bare `find -exec`, `git config` without `--get` were all removed once).

[BuildSystem.kt](app/src/main/java/dev/opencode/mobile/core/build/BuildSystem.kt)
detects project type from marker files (gradle/android, maven,
package.json→next/vite/react/node, python, cargo, go, static web) and runs
install/build/test/run/clean recipes through the terminal, returning structured
`BuildOutcome`s with regex-parsed diagnostics (Kotlin `e:` lines, javac, tsc,
Rust `-->`, flake8, gcc, Python tracebacks). On a stock phone the heavy
toolchains are absent — outcomes say so honestly rather than faking success.
The agent loop for builds: detect → install → build → read diagnostics → fix →
rebuild → test.

### Terminal UI

`ui/terminal/TerminalScreen.kt` is a full-screen push from the Files tab (not a
bottom tab). It lists running processes with stop buttons, renders stdout in
muted mono and stderr in error color, shows the live policy verdict under the
input as a hint, and reads persisted history from `terminal_history.json`
(app filesDir, capped at 100 entries). User-typed commands skip policy gating;
the badge is informational only.

### LLM layer

`LlmProvider.stream(...)` returns `Flow<LlmEvent>` and **never throws** —
network and protocol failures arrive as `LlmEvent.Failed`, so callers need no
try/catch. Three implementations, chosen by `ProviderKind` in
`ProviderRegistry.forKind`:

- `OpenAiCompatProvider` — `/chat/completions`; handles `reasoning_content`
  (DeepSeek) and `reasoning` (OpenRouter); accumulates `tool_calls` deltas per
  `index` because ids/names can arrive before arguments.
- `AnthropicProvider` — native Messages API.
- `GeminiProvider` — `streamGenerateContent`.

All three build their flow with `.flowOn(Dispatchers.IO)`.

`Http.forEachSseEvent` in
[Http.kt](app/src/main/java/dev/opencode/mobile/llm/Http.kt) is a **suspend**
extension taking a **suspend** lambda, deliberately not `inline` — every caller
emits into a flow from it. Callers stop early with
`return@forEachSseEvent false`.

`ProviderPresets` holds 11 presets (OpenRouter, OpenAI, Anthropic, Gemini, Groq,
DeepSeek, Mistral, xAI, Together, Cerebras, Fireworks) plus `CUSTOM`. Any
`/chat/completions` endpoint works through `CUSTOM`.

### Filesystem

Projects live under `context.filesDir/projects/<name>`. Every path from the model
or the UI goes through `WorkspaceManager.resolveSafely(projectDir, relativePath)`,
which canonicalizes and rejects anything outside the root. `HIDDEN_DIRS` =
`.git`, `node_modules`, `.gradle`, `build`, `.opencode`, `__pycache__` — skipped
by listing, walking, search, and file counts. `readText` throws past
`MAX_TEXT_BYTES` (1_500_000). `revision: StateFlow<Long>` is bumped by
`notifyChanged()` so screens can re-read after an agent write.

### Preview

[PreviewServer.kt](app/src/main/java/dev/opencode/mobile/core/preview/PreviewServer.kt)
binds the first free port in `8765..8785`. `file://` is not usable — Chromium
blocks ES module imports, `fetch`, and import maps there — which is the whole
reason this exists. The server injects a client that polls
`/__opencode/revision` every 1200 ms and reloads when the counter changes;
`signalReload()` increments it (the editor calls this after a successful save).

WebView rules in `PreviewScreen`: `javaScriptEnabled = true` (needed by the
reload client and user pages) but `allowFileAccess = false` and
`allowContentAccess = false`; non-loopback navigation is handed to the system
browser via `rememberUrlOpener()`.

### UI

`ui/AppRoot.kt` — five bottom-nav tabs (chat, files, preview, projects,
settings) plus `editor?path={path}` and `terminal` as full-screen pushes that
hide the nav bar. `Routes.editor(path)` URI-encodes the path. A global
`LinearProgressIndicator` + status line show while `agent.isRunning`.

Editor specifics ([EditorScreen.kt](app/src/main/java/dev/opencode/mobile/ui/files/EditorScreen.kt)):

- Load effect is keyed on `relativePath` + project path, **not**
  `workspace.revision` — an agent write must not clobber in-progress typing.
  "Reload from disk" in the overflow menu is the manual escape.
- Saves run on `container.scope`, not `rememberCoroutineScope()`, so popping the
  screen cannot cancel a write.
- Gutter and buffer share one `TextStyle` with an explicit `lineHeight` or the
  line numbers drift. Gutter width comes from `padStart(digits)`, not a dp guess,
  which survives a large system font scale. Gutter is hidden when word wrap is
  on, since a wrapped line breaks the 1:1 row mapping.
- Highlighting goes through a `VisualTransformation` with
  `OffsetMapping.Identity`, which is only safe because `Highlighter` output is
  character-for-character identical to its input. Keep it length-preserving.

## Compile traps already hit (do not reintroduce)

- `withStyle` is an extension in `androidx.compose.ui.text`, not a member of
  `AnnotatedString.Builder` — needs an explicit import.
- The `BasicTextField(value: TextFieldValue, …)` overload has **no `softWrap`
  parameter**. Passing it eliminates that overload and resolution falls through
  to the `TextFieldState` one, producing a cascade of "No parameter with name
  'value'". Wrapping follows from the width constraint instead: inside
  `horizontalScroll` the field measures unbounded, so long lines extend.
- `inline` functions cannot contain local functions, and an `inline` lambda
  parameter that a caller returns non-locally from needs `crossinline`. For
  anything that calls `emit`, use `suspend` + non-inline.
- `TextOverflow.MiddleEllipsis` does not exist in Compose 1.7.x.
- `androidx.compose.foundation` is declared explicitly even though material3
  brings it in transitively.

## Security invariants

- `res/xml/network_security_config.xml`: `cleartextTrafficPermitted="false"` in
  base-config; `127.0.0.1` and `localhost` are the only exceptions, for the
  preview server. Provider save is gated on `https://` or loopback.
- API keys and the git token live in the `AppSettings` JSON inside
  `EncryptedSharedPreferences`, with a plain-prefs fallback if the keystore is
  unavailable.
- Zip extraction (`RepoSnapshotService.unzipStrippingRoot`) has a zip-slip guard.
- FileProvider is `android:exported="false"`, scoped to `cache/exports/` via
  `res/xml/file_paths.xml`.
- No telemetry, analytics, or crash reporting anywhere.

## Known gaps

- No bundler-based dev servers (constraint 2). Terminal commands are limited to
  the phone's toybox shell — npm/python/jdk/cargo/go are absent, so ecosystem
  install/build recipes fail honestly with "not found".
- Clone/push HTTPS only; no SSH keys.
- Repo snapshots have no `.git`, so they cannot be committed or pushed.
- Highlighter disables itself past `MAX_CHARS` (120_000).
- Debug signing only; no release config, no tests, no lint baseline.
- Killing a compound `sh -c` command can orphan grandchildren until their pipes
  close; Android reaps them eventually.
