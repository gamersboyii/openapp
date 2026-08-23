# OpenCode Mobile

An AI coding agent that runs **on the phone**. No desktop, no companion server, no
SSH tunnel back to a PC. Bring your own LLM API key, clone a repo, let the agent
write files, and preview the result in a built-in browser — all on-device.

- `applicationId` — `dev.opencode.mobile`
- `versionName` — `0.1.0`
- `minSdk` 26 · `targetSdk` / `compileSdk` 34
- Kotlin 2.0.21 · Jetpack Compose (Material 3) · AGP 8.5.2 · Gradle 8.7 · JDK 17

---

## Design decisions

### No desktop server
Everything the agent needs is in the APK: the file tools, the git client, the
HTTP preview server, the LLM transport. Nothing points at a LAN address. Kill
Wi-Fi and the only thing that breaks is the model call itself.

### No Linux, no proot
A proot Alpine/Debian rootfs is the usual way to get `node` on Android, and it
costs 200–400 MB plus a fragile bootstrap. This app skips it entirely. The
consequence is a hard rule that shapes the whole product:

> **Project templates must run in a browser with zero build step.**

So instead of `npm install` + a bundler, templates use:

| Template | How it runs without a build |
| --- | --- |
| Blank | one `README.md` |
| Static site | plain HTML + CSS + JS |
| Tailwind page | Tailwind via CDN — no PostCSS |
| React app | React 18 from `esm.sh` via import map, JSX compiled in-page by `@babel/standalone` |
| Vue 3 app | Vue 3 global build |
| Landing page | static marketing page (hero / features / pricing / FAQ / footer) |

That zero-build rule still governs **templates**. A cloned Vite/Next/Webpack
project is a different story: an **optional** on-device Node runtime (next
section) can `npm install` and start a *pure-JS* dev server — but never the
bundler's own native dev mode. Either way you can always clone such a project,
read it, edit it, commit it, and push it.

### Node dev servers (optional runtime)
For Node web projects (Vite / Next.js / React / plain Node), the agent can stand
up a live dev server with `dev_server_start`: detect the project type, run
`npm install`, launch the dev command, sniff the port it prints, and point the
Preview tab at the running URL. `dev_server_status` reports state and recent
output; `dev_server_stop` tears it down.

This needs a Node runtime on the device, which is **optional and not bundled by
default**. Without it, `dev_server_start` returns `NO_RUNTIME` and you fall back
to the loopback preview, which still serves static / zero-build sites. Even with
the runtime, only *pure-JS* servers run — Vite/webpack/esbuild dev mode relies on
native binaries that cannot exec under the Android app sandbox. It runs through
the same sandboxed terminal as every other command; there is no second execution
path and still no proot.

### Preview over loopback HTTP, not `file://`
`file://` pages cannot use ES modules, `fetch`, or import maps — Chrome's origin
rules block them. So previews are served by an in-process
[NanoHTTPD](https://github.com/NanoHttpd/nanohttpd) bound to
`127.0.0.1`, first free port in `8765..8785`. The server injects a small client
that polls `/__opencode/revision` every 1200 ms, so saving a file in the editor
reloads the preview immediately.

Only loopback URLs load inside the app's WebView. Any outbound link is handed to
the system browser instead of becoming a trapped tab.

### Git without a native binary
[JGit](https://www.eclipse.org/jgit/) 5.13 — the last line that still targets
Java 8 bytecode and works under Android's `java.nio.file` subset. SSH transports
are excluded from the dependency, so **clone/push are HTTPS-only**. JGit's
`SystemReader` is replaced by an `AndroidSystemReader` before the first call,
because the default one goes looking for `/etc/gitconfig` and a `$HOME` that
Android does not provide.

`minSdk 26` exists for exactly this reason: `java.nio.file` (and therefore JGit)
is unavailable below API 26.

For read-only work there is a faster path — `fetch_repo_snapshot` downloads the
tarball/zip endpoint (GitHub codeload, GitLab, Codeberg) and unpacks it. Much
faster than a full clone on mobile data, but there is no `.git`, so you cannot
commit or push from a snapshot.

### Checkpoints and change review, not git

Before the agent's first change each turn, the app snapshots the whole project
into a content-addressed store under `<project>/.opencode/checkpoints` (a
`blobs/<sha256>` pool + one JSON manifest per checkpoint; identical files share a
blob). This is **not** git-backed on purpose:

- it must survive the app being killed, and
- it has to work in a project with no `.git` at all — a template, or a snapshot.

That snapshot powers two things. **Change review:** after a turn touches files, a
bar in chat offers *Review* (a full diff), *Keep*, or *Undo* — and undo reverts
the files only, so your chat history is never destroyed to unwind an edit.
**Checkpoints:** the History screen lists every snapshot for the project, diffs
one against the live tree or the previous checkpoint, and restores or deletes it.
You can also save one by hand. Retention is capped (Settings → *Keep last N
checkpoints*), and the whole feature can be switched off (Settings → *Auto
checkpoint*).

---

## Build

The APK is built in CI. This repo does **not** commit `gradle-wrapper.jar`
(binary), so the workflow provisions Gradle with `gradle/actions/setup-gradle`
and invokes `gradle`, not `./gradlew`.

1. Push this directory to a GitHub repo.
2. `.github/workflows/android.yml` runs on push to `main`/`master`, on PRs, and
   via **Actions → Build APK → Run workflow**.
3. Download the `opencode-debug-apk` artifact. It contains
   `opencode-<short-sha>.apk`.
4. Install it on the phone (allow install from unknown sources).

### Building locally instead

Needs JDK 17 and an Android SDK with platform 34.

```bash
gradle wrapper --gradle-version 8.7
```

```bash
./gradlew :app:assembleDebug
```

Output lands at `app/build/outputs/apk/debug/app-debug.apk`.

If Gradle cannot find the SDK, add a `local.properties` at this directory's root:

```properties
sdk.dir=/absolute/path/to/Android/sdk
```

---

## Setup on the phone

1. **Settings → Providers → Add.** Pick a preset or "Custom
   (OpenAI-compatible)", paste the API key, choose a model. Presets included:
   OpenRouter, OpenAI, Anthropic, Google Gemini, Groq, DeepSeek, Mistral, xAI,
   Together AI, Cerebras, Fireworks.
2. **Settings → Git identity.** Name + email for commits; a host token if you
   want to push.
3. **Projects tab.** Create from a template, or clone a repo URL.
4. **Chat tab.** Ask for what you want built. Tool calls appear inline and wait
   for approval before touching anything.
5. **Preview tab.** Auto-starts the loopback server for the open project. Change
   the entry file if it isn't `index.html`.

Three wire protocols are implemented natively, so a preset works without a proxy:
OpenAI `/chat/completions`, Anthropic Messages, and Gemini
`streamGenerateContent`. Streaming is manual SSE parsing over OkHttp.

## Agent tools

`list_files` · `read_file` · `write_file` · `edit_file` · `delete_path` ·
`create_directory` · `search_code` · `create_project` · `project_info` ·
`git_clone` · `fetch_repo_snapshot` · `git_status` · `git_init` · `git_commit` ·
`git_diff` · `git_log` · `git_push` · `git_pull` · `preview` ·
`dev_server_start` · `dev_server_stop` · `dev_server_status`

Every call is gated on an in-chat approve/deny prompt, and the loop has a
`maxSteps` backstop so a confused model cannot spin forever. Session history is
persisted to `<project>/.opencode/session.json`.

---

## Security notes

These are load-bearing, not decoration:

- **Keys at rest.** API keys and the git token live in
  `EncryptedSharedPreferences` (`opencode_secure`). If the device's keystore is
  unavailable, the app falls back to plain prefs — that fallback is a real
  downgrade, so treat a rooted or keystore-broken device as untrusted.
- **No cleartext HTTP.** `network_security_config.xml` sets
  `cleartextTrafficPermitted="false"` in the base config; `127.0.0.1` and
  `localhost` are the only exceptions, for the preview server. Adding a provider
  is likewise gated on an `https://` base URL (or loopback).
- **Path containment.** `WorkspaceManager.resolveSafely` rejects any path that
  escapes the project root, and archive extraction has a zip-slip guard, so a
  malicious repo cannot write outside its own directory.
- **The token only goes to the git host.** No telemetry, no analytics, no
  crash reporter — the only outbound connections are the LLM endpoint you
  configured and the git host you clone from.
- **WebView.** JavaScript is on (the live-reload client and your own pages need
  it), but `allowFileAccess` and `allowContentAccess` are off, so a previewed
  page cannot read app-private files.
- **Sharing.** Exports go through a `FileProvider` that is
  `android:exported="false"` and scoped to `cache/exports/`.

The agent can delete files it has been approved to delete. Approve tool calls by
reading them, not reflexively.

---

## Layout

```
app/src/main/java/dev/opencode/mobile/
  agent/     tool-calling loop, tool implementations, project templates
  core/fs/   WorkspaceManager — sandboxed file ops, search, zip export
  core/checkpoint/  content-addressed project snapshots (checkpoints + review)
  core/build/  project-type detection + build/test/run/clean recipes
  core/devserver/  Node dev-server host + on-device runtime probe
  core/git/  JGit wrapper, AndroidSystemReader, snapshot downloader
  core/preview/  NanoHTTPD server, live-reload injection
  core/settings/ SettingsStore, theme
  core/util/ syntax Highlighter, TextDiff
  llm/       provider registry, three wire protocols, SSE streaming
  ui/        Compose screens: chat, projects, files, editor, preview, settings,
             terminal, review, checkpoints
```

Dependencies are wired by hand through an `AppContainer` service locator exposed
as `LocalContainer`. No Hilt — one less annotation processor to break a build
that can only be verified in CI.

## Known limits

- Node dev servers need an optional runtime that is **not bundled yet**; until it
  is, `dev_server_start` reports `NO_RUNTIME`. Even then, only pure-JS servers run
  — no Vite/webpack/esbuild dev mode (see above).
- Clone and push are HTTPS-only; no SSH keys.
- Repo snapshots have no `.git` and cannot be committed or pushed.
- Text files only in the editor, with a size cap; the highlighter turns itself
  off past 120k characters.
- Debug-signed APK. Not Play-Store-ready; no release signing config.
