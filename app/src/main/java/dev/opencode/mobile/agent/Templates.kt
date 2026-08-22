package dev.opencode.mobile.agent

/**
 * Scaffolds that run with **zero build step**: dependencies come from a CDN via
 * an import map, so there is no `npm install`, no bundler and no Node runtime on
 * the device. Every template previews correctly in the built-in WebView.
 */
data class ProjectTemplate(
    val id: String,
    val title: String,
    val description: String,
    val entry: String,
    val files: Map<String, String>,
)

object Templates {

    val all: List<ProjectTemplate> by lazy {
        listOf(blank, staticSite, tailwind, react, vue, landing)
    }

    fun byId(id: String): ProjectTemplate? = all.firstOrNull { it.id.equals(id, ignoreCase = true) }

    val ids: List<String> get() = all.map { it.id }

    private val blank = ProjectTemplate(
        id = "blank",
        title = "Blank",
        description = "One empty README. Start from nothing.",
        entry = "index.html",
        files = mapOf(
            "README.md" to "# New project\n\nCreated with OpenCode Mobile.\n",
        ),
    )

    private val staticSite = ProjectTemplate(
        id = "static",
        title = "Static site",
        description = "HTML + CSS + JS, no dependencies. Fastest preview.",
        entry = "index.html",
        files = mapOf(
            "index.html" to """
                <!doctype html>
                <html lang="en">
                <head>
                  <meta charset="utf-8" />
                  <meta name="viewport" content="width=device-width, initial-scale=1" />
                  <title>New site</title>
                  <link rel="stylesheet" href="styles.css" />
                </head>
                <body>
                  <header class="hero">
                    <p class="eyebrow">Built on a phone</p>
                    <h1>Hello, world.</h1>
                    <p class="lede">Edit <code>index.html</code>, save, and the preview reloads itself.</p>
                    <button id="counter" class="btn">Clicked 0 times</button>
                  </header>

                  <main class="grid">
                    <section class="card">
                      <h2>Fast</h2>
                      <p>No build step. The file you edit is the file that runs.</p>
                    </section>
                    <section class="card">
                      <h2>Local</h2>
                      <p>Served from 127.0.0.1 on this device. Works offline.</p>
                    </section>
                    <section class="card">
                      <h2>Yours</h2>
                      <p>Plain HTML, CSS and JavaScript. Nothing to unlearn.</p>
                    </section>
                  </main>

                  <script src="app.js"></script>
                </body>
                </html>
            """.trimIndent(),

            "styles.css" to """
                :root {
                  color-scheme: dark;
                  --bg: #0f1115;
                  --panel: #161922;
                  --line: #262b38;
                  --text: #e6e9f0;
                  --muted: #97a0b5;
                  --accent: #7aa2f7;
                }

                * { box-sizing: border-box; }

                body {
                  margin: 0;
                  background: var(--bg);
                  color: var(--text);
                  font: 16px/1.6 -apple-system, system-ui, "Segoe UI", Roboto, sans-serif;
                  padding: 24px 20px 64px;
                }

                .hero { max-width: 720px; margin: 24px auto 40px; }
                .eyebrow { color: var(--accent); font-size: 13px; letter-spacing: .12em; text-transform: uppercase; margin: 0 0 8px; }
                h1 { font-size: clamp(30px, 8vw, 48px); line-height: 1.1; margin: 0 0 12px; letter-spacing: -0.02em; }
                .lede { color: var(--muted); margin: 0 0 24px; }
                code { background: var(--panel); border: 1px solid var(--line); border-radius: 6px; padding: 2px 6px; font-size: .9em; }

                .btn {
                  appearance: none;
                  border: 1px solid var(--line);
                  background: var(--accent);
                  color: #0b0d12;
                  font-weight: 600;
                  font-size: 15px;
                  padding: 12px 18px;
                  border-radius: 12px;
                  min-height: 48px;
                  cursor: pointer;
                  transition: transform .08s ease, filter .15s ease;
                }
                .btn:active { transform: scale(.97); filter: brightness(1.08); }

                .grid {
                  max-width: 720px;
                  margin: 0 auto;
                  display: grid;
                  gap: 14px;
                  grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
                }

                .card {
                  background: var(--panel);
                  border: 1px solid var(--line);
                  border-radius: 16px;
                  padding: 18px;
                }
                .card h2 { margin: 0 0 6px; font-size: 17px; }
                .card p { margin: 0; color: var(--muted); font-size: 14px; }
            """.trimIndent(),

            "app.js" to """
                const button = document.getElementById('counter');
                let clicks = 0;

                button.addEventListener('click', () => {
                  clicks += 1;
                  button.textContent = 'Clicked ' + clicks + (clicks === 1 ? ' time' : ' times');
                });
            """.trimIndent(),

            "README.md" to "# Static site\n\nOpen the Preview tab. Edit `index.html`, `styles.css` or `app.js`.\n",
        ),
    )

    private val tailwind = ProjectTemplate(
        id = "tailwind",
        title = "Tailwind page",
        description = "Tailwind via CDN. Utility classes, no PostCSS, no install.",
        entry = "index.html",
        files = mapOf(
            "index.html" to """
                <!doctype html>
                <html lang="en" class="dark">
                <head>
                  <meta charset="utf-8" />
                  <meta name="viewport" content="width=device-width, initial-scale=1" />
                  <title>Tailwind page</title>
                  <!-- The CDN build compiles utilities in the browser: no npm, no PostCSS. -->
                  <script src="https://cdn.tailwindcss.com"></script>
                  <script>
                    tailwind.config = {
                      theme: {
                        extend: {
                          colors: { ink: '#0f1115', panel: '#161922', line: '#262b38' },
                        },
                      },
                    };
                  </script>
                </head>
                <body class="bg-ink text-slate-100 antialiased">
                  <div class="mx-auto max-w-2xl px-5 py-10">
                    <span class="text-xs uppercase tracking-widest text-sky-400">Tailwind</span>
                    <h1 class="mt-2 text-4xl font-semibold tracking-tight">Ship a page in minutes</h1>
                    <p class="mt-3 text-slate-400">Every class is compiled in the browser. Edit and the preview reloads.</p>

                    <div class="mt-8 grid gap-3 sm:grid-cols-2">
                      <article class="rounded-2xl border border-line bg-panel p-5">
                        <h2 class="font-medium">Responsive</h2>
                        <p class="mt-1 text-sm text-slate-400">Breakpoint prefixes work exactly as documented.</p>
                      </article>
                      <article class="rounded-2xl border border-line bg-panel p-5">
                        <h2 class="font-medium">Dark first</h2>
                        <p class="mt-1 text-sm text-slate-400">Tuned for reading on a phone screen.</p>
                      </article>
                    </div>

                    <button
                      class="mt-8 min-h-12 w-full rounded-xl bg-sky-500 px-5 font-semibold text-slate-900 active:scale-[0.98] sm:w-auto"
                      onclick="this.textContent = 'Works'">
                      Tap me
                    </button>
                  </div>
                </body>
                </html>
            """.trimIndent(),

            "README.md" to "# Tailwind page\n\nUses the Tailwind CDN build, so utilities compile at runtime. " +
                "Great for prototyping; for production output you would run the real Tailwind CLI on a desktop.\n",
        ),
    )

    private val react = ProjectTemplate(
        id = "react",
        title = "React app",
        description = "React 18 from esm.sh with JSX compiled in-page. No bundler.",
        entry = "index.html",
        files = mapOf(
            "index.html" to """
                <!doctype html>
                <html lang="en">
                <head>
                  <meta charset="utf-8" />
                  <meta name="viewport" content="width=device-width, initial-scale=1" />
                  <title>React app</title>
                  <link rel="stylesheet" href="styles.css" />

                  <!--
                    Two CDN pieces replace the whole toolchain:
                      1. The import map resolves bare "react" specifiers to esm.sh.
                      2. Babel standalone compiles the JSX in App.jsx at load time.
                    Slower first paint than a bundler, but zero install.
                  -->
                  <script type="importmap">
                  {
                    "imports": {
                      "react": "https://esm.sh/react@18.3.1",
                      "react-dom/client": "https://esm.sh/react-dom@18.3.1/client"
                    }
                  }
                  </script>
                  <script src="https://unpkg.com/@babel/standalone@7.25.6/babel.min.js"></script>
                </head>
                <body>
                  <div id="root"></div>
                  <script type="text/babel" data-type="module" src="App.jsx"></script>
                </body>
                </html>
            """.trimIndent(),

            "App.jsx" to """
                import React, { useState } from 'react';
                import { createRoot } from 'react-dom/client';

                function Counter() {
                  const [count, setCount] = useState(0);

                  return (
                    <button className="btn" onClick={() => setCount(count + 1)}>
                      Count: {count}
                    </button>
                  );
                }

                function App() {
                  return (
                    <main className="wrap">
                      <p className="eyebrow">React 18</p>
                      <h1>Components, no bundler</h1>
                      <p className="lede">Edit App.jsx and save. The preview reloads on its own.</p>
                      <Counter />
                    </main>
                  );
                }

                createRoot(document.getElementById('root')).render(<App />);
            """.trimIndent(),

            "styles.css" to """
                :root { color-scheme: dark; --accent: #7aa2f7; }
                * { box-sizing: border-box; }
                body {
                  margin: 0;
                  background: #0f1115;
                  color: #e6e9f0;
                  font: 16px/1.6 -apple-system, system-ui, "Segoe UI", Roboto, sans-serif;
                }
                .wrap { max-width: 680px; margin: 0 auto; padding: 40px 20px; }
                .eyebrow { color: var(--accent); font-size: 13px; letter-spacing: .12em; text-transform: uppercase; margin: 0 0 8px; }
                h1 { font-size: clamp(28px, 7vw, 42px); line-height: 1.12; margin: 0 0 12px; letter-spacing: -0.02em; }
                .lede { color: #97a0b5; margin: 0 0 24px; }
                .btn {
                  appearance: none; border: 0; border-radius: 12px;
                  background: var(--accent); color: #0b0d12;
                  font: 600 15px/1 inherit; padding: 0 18px; min-height: 48px; cursor: pointer;
                }
                .btn:active { transform: scale(.97); }
            """.trimIndent(),

            "README.md" to "# React app\n\nReact and ReactDOM load from esm.sh; JSX is compiled by " +
                "@babel/standalone in the page. First load needs network access, then the CDN " +
                "response is cached by the WebView.\n",
        ),
    )

    private val vue = ProjectTemplate(
        id = "vue",
        title = "Vue 3 app",
        description = "Vue 3 global build. Reactive templates, no build step.",
        entry = "index.html",
        files = mapOf(
            "index.html" to """
                <!doctype html>
                <html lang="en">
                <head>
                  <meta charset="utf-8" />
                  <meta name="viewport" content="width=device-width, initial-scale=1" />
                  <title>Vue app</title>
                  <link rel="stylesheet" href="styles.css" />
                  <script src="https://unpkg.com/vue@3.5.13/dist/vue.global.prod.js"></script>
                </head>
                <body>
                  <div id="app">
                    <p class="eyebrow">Vue 3</p>
                    <h1>{{ title }}</h1>
                    <p class="lede">Reactivity with the global build. No compiler needed.</p>

                    <input v-model="name" class="field" placeholder="Your name" />
                    <p class="greeting" v-if="name">Hi, {{ name }}.</p>

                    <button class="btn" @click="count++">Count: {{ count }}</button>
                  </div>
                  <script src="app.js"></script>
                </body>
                </html>
            """.trimIndent(),

            "app.js" to """
                const { createApp, ref } = Vue;

                createApp({
                  setup() {
                    const title = ref('Built on a phone');
                    const name = ref('');
                    const count = ref(0);
                    return { title, name, count };
                  },
                }).mount('#app');
            """.trimIndent(),

            "styles.css" to """
                :root { color-scheme: dark; --accent: #42d392; }
                * { box-sizing: border-box; }
                body {
                  margin: 0; background: #0f1115; color: #e6e9f0;
                  font: 16px/1.6 -apple-system, system-ui, "Segoe UI", Roboto, sans-serif;
                }
                #app { max-width: 680px; margin: 0 auto; padding: 40px 20px; }
                .eyebrow { color: var(--accent); font-size: 13px; letter-spacing: .12em; text-transform: uppercase; margin: 0 0 8px; }
                h1 { font-size: clamp(28px, 7vw, 42px); line-height: 1.12; margin: 0 0 12px; letter-spacing: -0.02em; }
                .lede, .greeting { color: #97a0b5; }
                .field {
                  width: 100%; min-height: 48px; margin: 16px 0 8px; padding: 0 14px;
                  border-radius: 12px; border: 1px solid #262b38; background: #161922;
                  color: inherit; font: inherit;
                }
                .btn {
                  appearance: none; border: 0; border-radius: 12px; margin-top: 16px;
                  background: var(--accent); color: #0b0d12;
                  font: 600 15px/1 inherit; padding: 0 18px; min-height: 48px;
                }
            """.trimIndent(),

            "README.md" to "# Vue 3 app\n\nUses the global (non-module) Vue build so templates are compiled at runtime.\n",
        ),
    )

    private val landing = ProjectTemplate(
        id = "landing",
        title = "Landing page",
        description = "Marketing page: hero, features, pricing, FAQ, footer.",
        entry = "index.html",
        files = mapOf(
            "index.html" to """
                <!doctype html>
                <html lang="en">
                <head>
                  <meta charset="utf-8" />
                  <meta name="viewport" content="width=device-width, initial-scale=1" />
                  <title>Product — landing</title>
                  <meta name="description" content="A one-page landing template with no dependencies." />
                  <link rel="stylesheet" href="styles.css" />
                </head>
                <body>
                  <nav class="nav">
                    <a class="brand" href="#top">◆ Product</a>
                    <a class="nav-cta" href="#pricing">Get started</a>
                  </nav>

                  <header id="top" class="hero">
                    <h1>The tool your team keeps asking for</h1>
                    <p>Set it up in two minutes. Cancel in one. No sales call in between.</p>
                    <div class="actions">
                      <a class="btn btn-primary" href="#pricing">Start free</a>
                      <a class="btn btn-ghost" href="#features">See features</a>
                    </div>
                    <p class="trust">Trusted by 4,000+ teams · No credit card</p>
                  </header>

                  <section id="features" class="section">
                    <h2>Everything included</h2>
                    <div class="feature-grid">
                      <article><h3>Instant setup</h3><p>Connect a repo and you are done. No agents to install.</p></article>
                      <article><h3>Real-time sync</h3><p>Changes land for everyone in under a second.</p></article>
                      <article><h3>Audit trail</h3><p>Every action is logged and exportable.</p></article>
                      <article><h3>Fair pricing</h3><p>Per workspace, not per seat. Invite everybody.</p></article>
                    </div>
                  </section>

                  <section id="pricing" class="section">
                    <h2>Pricing</h2>
                    <div class="plans">
                      <article class="plan">
                        <h3>Free</h3>
                        <p class="price">${'$'}0<span>/mo</span></p>
                        <ul><li>1 workspace</li><li>Community support</li><li>7-day history</li></ul>
                        <a class="btn btn-ghost" href="#">Choose Free</a>
                      </article>
                      <article class="plan plan-featured">
                        <span class="badge">Popular</span>
                        <h3>Team</h3>
                        <p class="price">${'$'}19<span>/mo</span></p>
                        <ul><li>Unlimited workspaces</li><li>Priority support</li><li>1-year history</li></ul>
                        <a class="btn btn-primary" href="#">Choose Team</a>
                      </article>
                    </div>
                  </section>

                  <section class="section">
                    <h2>Questions</h2>
                    <details><summary>Can I self-host?</summary><p>Yes, with the Team plan or above.</p></details>
                    <details><summary>Is there a free trial?</summary><p>The Free plan never expires.</p></details>
                    <details><summary>How do I cancel?</summary><p>One button in settings. No email required.</p></details>
                  </section>

                  <footer class="footer">
                    <p>© 2026 Product</p>
                    <p><a href="#top">Back to top</a></p>
                  </footer>
                </body>
                </html>
            """.trimIndent(),

            "styles.css" to """
                :root {
                  color-scheme: dark;
                  --bg: #0b0d12;
                  --panel: #14171f;
                  --line: #232834;
                  --text: #e8ebf2;
                  --muted: #969fb4;
                  --accent: #7aa2f7;
                }

                * { box-sizing: border-box; }
                html { scroll-behavior: smooth; }
                body {
                  margin: 0; background: var(--bg); color: var(--text);
                  font: 16px/1.65 -apple-system, system-ui, "Segoe UI", Roboto, sans-serif;
                  -webkit-font-smoothing: antialiased;
                }
                a { color: inherit; text-decoration: none; }

                .nav {
                  position: sticky; top: 0; z-index: 10;
                  display: flex; align-items: center; justify-content: space-between;
                  padding: 14px 20px;
                  background: rgba(11, 13, 18, .82);
                  backdrop-filter: blur(12px);
                  border-bottom: 1px solid var(--line);
                }
                .brand { font-weight: 600; letter-spacing: -0.01em; }
                .nav-cta { color: var(--accent); font-size: 14px; font-weight: 600; }

                .hero { max-width: 760px; margin: 0 auto; padding: 64px 20px 48px; text-align: center; }
                .hero h1 { font-size: clamp(32px, 9vw, 56px); line-height: 1.06; letter-spacing: -0.03em; margin: 0 0 16px; }
                .hero p { color: var(--muted); font-size: 17px; margin: 0 auto 28px; max-width: 46ch; }
                .actions { display: flex; flex-wrap: wrap; gap: 12px; justify-content: center; }
                .trust { font-size: 13px; margin-top: 24px; color: #6f778b; }

                .btn {
                  display: inline-flex; align-items: center; justify-content: center;
                  min-height: 50px; padding: 0 22px; border-radius: 12px;
                  font-weight: 600; font-size: 15px; border: 1px solid transparent;
                  transition: transform .08s ease;
                }
                .btn:active { transform: scale(.98); }
                .btn-primary { background: var(--accent); color: #0b0d12; }
                .btn-ghost { border-color: var(--line); background: var(--panel); color: var(--text); }

                .section { max-width: 900px; margin: 0 auto; padding: 48px 20px; }
                .section h2 { font-size: clamp(24px, 6vw, 32px); letter-spacing: -0.02em; margin: 0 0 24px; }

                .feature-grid { display: grid; gap: 14px; grid-template-columns: repeat(auto-fit, minmax(230px, 1fr)); }
                .feature-grid article { background: var(--panel); border: 1px solid var(--line); border-radius: 16px; padding: 20px; }
                .feature-grid h3 { margin: 0 0 6px; font-size: 16px; }
                .feature-grid p { margin: 0; color: var(--muted); font-size: 14px; }

                .plans { display: grid; gap: 16px; grid-template-columns: repeat(auto-fit, minmax(260px, 1fr)); }
                .plan { position: relative; background: var(--panel); border: 1px solid var(--line); border-radius: 20px; padding: 24px; }
                .plan-featured { border-color: var(--accent); box-shadow: 0 0 0 1px rgba(122, 162, 247, .35); }
                .badge {
                  position: absolute; top: -11px; left: 24px;
                  background: var(--accent); color: #0b0d12; font-size: 11px; font-weight: 700;
                  letter-spacing: .06em; text-transform: uppercase; padding: 4px 10px; border-radius: 999px;
                }
                .plan h3 { margin: 0 0 8px; font-size: 15px; color: var(--muted); text-transform: uppercase; letter-spacing: .08em; }
                .price { font-size: 40px; font-weight: 650; margin: 0 0 16px; letter-spacing: -0.03em; }
                .price span { font-size: 15px; font-weight: 400; color: var(--muted); }
                .plan ul { list-style: none; padding: 0; margin: 0 0 20px; color: var(--muted); font-size: 14px; }
                .plan li { padding: 7px 0; border-bottom: 1px solid var(--line); }
                .plan .btn { width: 100%; }

                details {
                  background: var(--panel); border: 1px solid var(--line);
                  border-radius: 14px; padding: 16px 18px; margin-bottom: 10px;
                }
                summary { cursor: pointer; font-weight: 550; }
                details p { color: var(--muted); margin: 10px 0 0; font-size: 14px; }

                .footer {
                  display: flex; justify-content: space-between; flex-wrap: wrap; gap: 8px;
                  max-width: 900px; margin: 0 auto; padding: 32px 20px 56px;
                  border-top: 1px solid var(--line); color: #6f778b; font-size: 13px;
                }
            """.trimIndent(),

            "README.md" to "# Landing page\n\nSingle-file page, no dependencies. Replace the copy in `index.html`.\n",
        ),
    )
}
