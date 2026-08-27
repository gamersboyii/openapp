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
`dev_server_start` · `dev_server_stop` · `dev_server_status` ·
`github_account` · `github_repos` · `github_repo_info` ·
`github_create_repo` · `github_branches` · `github_commits` ·
`github_issues` · `github_get_issue` · `github_create_issue` ·
`github_comment` · `github_pulls` · `github_get_pull` ·
`github_create_pull` · `github_actions_status`

Every call is gated on an in-chat approve/deny prompt, and the loop has a
`maxSteps` backstop so a confused model cannot spin forever. Session history is
persisted to `<project>/.opencode/session.json`.

---

---

## GitHub integration (feature 8)

The **Hub tab** signs you in and covers the read side of GitHub without a
server in between:

- **Sign-in** - a personal access token, or the OAuth **device flow** against
  your own OAuth app's client id (no client secret ships in the APK); verified
  against `/user`.
- **Repository browsing & creation** - your accessible repos newest-first,
  create repos where your token permits, per-repo contents browser.
- **Branches / commits / issues / PRs / Actions** - open/closed filters,
  issue and pull-request detail with comment threads and, on PRs, review
  decisions.
- **Clone** - one tap lands the repo as a local project and switches the agent
  to it. Private repos work everywhere because cloning/pushing/pulling fall
  back to the signed-in token when no explicit git token is configured.

The fifteen `github_*` agent tools let the model do all of this on your behalf,
so the flagship workflow runs end-to-end from chat:

> "Fix issue #42 and open a PR"

fetch issue -> inspect repository -> modify code -> run tests -> commit ->
push branch -> create PR (`Fixes #42`).

### Token safety

Tokens live in EncryptedSharedPreferences next to every other secret. They are
attached to requests inside `GitHubClient` only - never placed in exceptions,
logs, tool results or model context - and user-facing error strings pass
through a redaction filter (`redactSecrets`) that masks anything shaped like a
GitHub credential as defense in depth. The system prompt additionally forbids
the model from asking for tokens; it has no way to read them.

---

## Advanced code editor (feature 9)

Still one `BasicTextField`, but now editor-grade:

- **File tabs** with unsaved dots; buffers survive leaving the screen, and
  closing a dirty tab keeps its text cached until you reopen it.
- **Undo / redo** with keystroke coalescing; structural breaks on newline,
  paste and programmatic replacements.
- **Find / replace** with match highlighting in the buffer, x/y counter,
  next/prev, replace current/all, case toggle.
- **Autocomplete** - instant word completions from document + language
  keywords; no LLM round-trip.
- **Smart indent** on Enter (inherit indentation, open one level after `{`,
  `[`, `(` or a trailing `:`).
- **Brackets** - auto-close pairs, skip over duplicate closers and quotes,
  live match highlight at the caret, unbalanced-bracket diagnostic.
- **Symbols** - outline sheet with tap-to-jump, go-to-line, go-to-definition
  within the file, whole-word rename dialog.
- **Diff view** of buffer vs saved copy, rendered as colored hunks.
- **Large-file handling** - past 60k chars autocomplete/pairing/bracket UI
  drop into "lite" mode; past 120k the highlighter switches off too.

Honest limitation: true multi-cursor editing requires a custom text engine
that Compose's text field cannot express - that item is deliberately not faked.

---

## Change review & checkpoints (features 10-11)

After an agent turn changes files, a bar in chat shows *N files changed* with a
per-file `path +adds -dels` list and three actions: **Review Changes**,
**Reject** (revert everything) and **Accept**, plus *undo entire agent turn*.
Inside the review screen each file can be reverted alone, and hunks can be
accepted/rejected individually: tap the `@@` headers to mark them, apply, and
only those regions are restored from the pre-turn checkpoint.

Checkpoints remain content-addressed, survive app restarts and work without a
`.git` directory. New in this round: rename a checkpoint and delete the whole
history with one confirmation.

---

## Background agent mode (feature 12)

While a turn runs, the composer offers a *continue in background* action that
starts a `dataSync` foreground service. The notification mirrors progress
(analysed project... modified N files... build failed -> fixing), with pause /
resume / stop actions riding along, Retry after failures (re-sends the last
prompt), and a pointer back into the app when approval is needed. The service
stops itself the moment the engine is idle, so nothing lingers.

---

## System prompt handbook (feature 13)

Every build-mode turn is prefixed with the bundled **`INSTRUCTION.md`** agent
handbook (`assets/INSTRUCTION.md`): 25 sections covering inspect-before-edit,
minimal-change defaults, Android-first rules, approval models, trust boundaries
against prompt injection, verification discipline and completion criteria.

- The file is copied to app-private storage on first launch so it can be edited
  in **Settings → System prompt → Edit**; **Reset** restores the bundled copy.
- **Use agent handbook** (default on) switches the prefix off entirely for
  minimal prompts. A hard cap keeps the handbook from exceeding ~32k characters.
- The handbook defines *behaviour*; the device-facts block that follows it
  defines the *actual on-phone capabilities* — where they disagree, device facts win.

## Built-in skills (feature 14)

The app ships a curated skill library under `assets/skills/` — each skill is an
agent-skills-convention `SKILL.md`. Enabled skills add one description line to
every system prompt; full bodies load on demand through the `use_skill` tool,
so token cost stays proportional to what the agent actually uses.

| Category | Skills |
|---|---|
| Design | Taste — Anti-Slop Frontend, Frontend Design (anthropics), UI/UX Pro Max |
| Communication | Caveman Mode (~65% output-token cut) |
| Engineering Process | Superpowers: overview router, brainstorming, systematic debugging, TDD, writing/executing plans, requesting/receiving review, verification-before-completion, subagent-driven development |
| Code Minimalism | Ponytail: lazy-senior-dev core + audit / debt / gain / review |
| Memory | Import Memory from Another Assistant — pastes a ChatGPT/Gemini export, privacy-filters it, confirms the plan, then files it into additive `memory/` files |

Manage them in **Settings → Skills** or the ✦ button in the chat top bar:
search, category filters, per-skill toggles, and a detail sheet rendering the
full SKILL.md. Skill availability survives app restarts (encrypted settings).

## Chat Only mode & project creation

The composer has a **Build ⇄ Chat Only** segmented switch:

- **Chat Only** strips every project tool from the model for pure conversation;
  only `use_skill` remains available (style skills keep working). The engine
  additionally refuses any tool call the model still attempts, with a hint to
  flip back to Build.
- **Build** is the full agent. Asking "make me a to-do app" with no project open
  triggers `create_project` from one of six zero-build templates
  (blank/static/tailwind/react/vue/landing) and the agent keeps editing until it
  works, then hands you the live preview.

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
  agent/     tool-calling loop, tool implementations (incl. github_*, use_skill), templates
  bg/        AgentForegroundService - background agent mode
  core/fs/   WorkspaceManager — sandboxed file ops, search, zip export
  core/editor/  CodeAssist (brackets, indent, completions, symbols, undo)
                + EditorTabsStore — file tabs & unsaved buffers
  core/github/  GitHub REST client + device-flow auth session
  core/instructions/  InstructionStore — bundled INSTRUCTION.md system prompt,
                user-editable in Settings
  core/skills/  SkillStore — built-in skill library from assets/skills
  core/checkpoint/  content-addressed project snapshots (checkpoints + review)
  core/build/  project-type detection + build/test/run/clean recipes
  core/devserver/  Node dev-server host + on-device runtime probe
  core/git/  JGit wrapper, AndroidSystemReader, snapshot downloader
  core/preview/  NanoHTTPD server, live-reload injection
  core/settings/ SettingsStore, theme
  core/util/ syntax Highlighter, TextDiff
  llm/       provider registry, three wire protocols, SSE streaming
  ui/        Compose screens: chat, projects, files, editor, preview, settings,
             terminal, review, checkpoints, hub (GitHub), skills
```

Assets:
```
app/src/main/assets/
  INSTRUCTION.md      agent handbook prefixed to every build-mode turn
  skills/index.json   catalog of bundled skills
  skills/<id>/SKILL.md one folder per skill (agent-skills convention)
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
- Multi-cursor editing is not supported (see feature 9 notes).
- The background service runs only while an agent turn is active; when the
  system refuses a backgrounded foreground-start, the turn continues at
  normal priority instead of failing.
