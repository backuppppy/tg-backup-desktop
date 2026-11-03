# TG Backup Desktop — Foundation and Spike Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A buildable repo with CI on Linux and Windows. Answer, with evidence, the two risks that decide the rest of the project: does TDLib load and run on Windows from the JVM, and which embedded view component can show a local page in TGBackup's own window, and how big is the resulting installer.

**Architecture:** Gradle multi-project with `export-core` (copied verbatim from the Android repo) and `desktop` (the program). Spike code lives in `desktop` under `org.tgbackup.desktop.spike` and is deleted by the next plan once a component is chosen. CI runs JVM tests on Ubuntu, and smoke runs plus installer builds on `windows-latest`.

**Tech Stack:** Kotlin/JVM 17 (Kotlin 2.4.21, coroutines 1.10.2, same as the Android repo), Gradle 8.14.3, `dev.g000sha256:tdl-coroutines-jvm:15.0.0`, Ktor server 3.x, jpackage, GitHub Actions.

**Spec:** `docs/superpowers/specs/2026-11-03-tg-backup-desktop-design.md`

## Global Constraints

- Windows only as the target. Linux/arm64 is the development host here, used for JVM tests and local runs.
- A normal program: installer `TGBackup-Setup.exe`, program `TGBackup.exe`, Java bundled. No browser, no Edge window.
- Everything runs inside the program; nothing is downloaded at runtime (no CEF/TDLib download on first run).
- Data directory: `%LOCALAPPDATA%\TGBackup` on Windows (dev fallback: `~/.tgbackup`).
- Internal server listens on `127.0.0.1` only in this plan.
- TDLib: `tdl-coroutines-jvm:15.0.0`, the same version as the Android app.
- `export-core` is copied from `/root/tg-backup-apk` at its committed `HEAD` (`git archive`). Never copy untracked `*LiveTest*`, `FileRefSourceTest`, `SavedUnlockTest` or the uncommitted `export-core/build.gradle.kts` diff, because they contain credentials and local test props.
- No secrets in the repo: no API id/hash, sessions, phone numbers or Sentry tokens.
- The Android repo `/root/tg-backup-apk` is read-only for this work.
- Commit trailer: `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

1. **Windows user name with Hebrew or spaces** (e.g. `C:\Users\משה כהן\AppData\Local`). Native libs and data must load from a Unicode path. Task 3 smoke runs with the data dir under a path containing `תיקייה עם רווח`.
2. **Offline first run.** The chosen view must work with no network. Task 4 smoke sets an unreachable proxy for the view and still expects `PAGE_LOADED`.
3. **Port already in use.** The internal server must pick a free port, not crash. Task 4 binds port 0 and reports the chosen port.
4. **A second launch while one is running.** This is out of scope for the spike; it is owned by the next plan (single instance), noted here so it is not forgotten.
5. **Missing WebView2 runtime** (old Windows 10). If WebView2 is chosen, the result doc must state the fallback. Task 4 records whether the runner had it preinstalled.

---

### Task 1: Gradle skeleton, copied export-core, Linux CI

**Files:**
- Create: `settings.gradle.kts`, `build.gradle.kts`, `gradle/wrapper/*`, `gradlew`, `gradlew.bat`
- Create: `export-core/**`, from `git -C /root/tg-backup-apk archive HEAD export-core | tar -x`
- Create: `desktop/build.gradle.kts`, `desktop/src/main/kotlin/org/tgbackup/desktop/Main.kt`
- Create: `desktop/src/test/kotlin/org/tgbackup/desktop/AppDirsTest.kt`, `desktop/src/main/kotlin/org/tgbackup/desktop/AppDirs.kt`
- Create: `.github/workflows/build.yml`

**Interfaces:**
- Produces: `object AppDirs { fun dataDir(env: Map<String,String> = System.getenv(), home: String = System.getProperty("user.home"), os: String = System.getProperty("os.name")): java.nio.file.Path }`. Windows → `%LOCALAPPDATA%\TGBackup`. If `LOCALAPPDATA` is missing → `<home>\AppData\Local\TGBackup`. Others → `<home>/.tgbackup`.
- Produces: Gradle projects `:export-core` and `:desktop`. The wrapper pins Gradle 8.14.3.

- [x] **Step 1:** Copy export-core via `git archive` (Global Constraints). Confirm no `*LiveTest*` files: `find export-core -name '*LiveTest*'` must print nothing.
- [x] **Step 2:** Write `AppDirsTest` with these cases: `windows LOCALAPPDATA → C:\Users\משה כהן\AppData\Local\TGBackup`; `windows without LOCALAPPDATA → <home>\AppData\Local\TGBackup`; `linux → /home/u/.tgbackup`. Compare paths as strings.
- [x] **Step 3:** Run `./gradlew :desktop:test`. Expected: FAIL, unresolved `AppDirs`.
- [x] **Step 4:** Implement `AppDirs.dataDir`. Write `Main.kt` that prints `TGBackup <version>` and the data dir.
- [x] **Step 5:** Run `./gradlew test`. Expected: PASS for export-core's existing tests and `AppDirsTest`.
- [x] **Step 6:** Write `build.yml` job `linux` (ubuntu-latest, Temurin 17, `./gradlew test`), triggered on push and pull_request.
- [x] **Step 7:** Commit and push. Confirm the `linux` job is green with `gh run watch`.

### Task 2: TDLib loads and answers on the JVM (Linux)

**Files:**
- Create: `desktop/src/main/kotlin/org/tgbackup/desktop/spike/TdlibSmoke.kt`
- Create: `desktop/src/test/kotlin/org/tgbackup/desktop/spike/TdlibSmokeTest.kt`
- Modify: `desktop/build.gradle.kts` (dependency, and the `tdlibSmoke` JavaExec task)

**Interfaces:**
- Consumes: `AppDirs.dataDir()`.
- Produces: `suspend fun tdlibVersion(dbDir: java.nio.file.Path): String`, which returns TDLib's `version` option without logging in. Also a `main` that prints `TDLIB_OK <version>` and exits 0, or prints `TDLIB_FAIL <reason>` and exits 1. Follow the client creation pattern in `/root/tg-backup-apk/app/src/main/java/org/tgbackup/app/tg/AccountClient.kt`. Check the JVM API in the library's sources jar.

- [x] **Step 1:** Write `TdlibSmokeTest.tdlibReportsAVersion`: `tdlibVersion(tempDir resolved under "תיקייה עם רווח")` matches `Regex("""\d+\.\d+\.\d+""")` within 30 s.
- [x] **Step 2:** Run `./gradlew :desktop:test --tests '*TdlibSmokeTest*'`. Expected: FAIL, unresolved `tdlibVersion`.
- [x] **Step 3:** Implement it. Close the client in `finally`.
- [x] **Step 4:** Run the test. Expected: PASS on linux/arm64 here. A native load failure is a finding: record it, do not work around it silently.
- [x] **Step 5:** Commit.

### Task 3: Windows CI — TDLib smoke on windows-latest

**Files:**
- Modify: `.github/workflows/build.yml` (add job `windows`)

**Interfaces:**
- Consumes: the `tdlibSmoke` Gradle task from Task 2.

- [x] **Step 1:** Add job `windows` (windows-latest, Temurin 17): `./gradlew :desktop:test` and `./gradlew :desktop:tdlibSmoke`, with env `LOCALAPPDATA=${{ runner.temp }}\תיקייה עם רווח` so the data dir path holds Hebrew and a space.
- [x] **Step 2:** Push. Expected: the log contains `TDLIB_OK` and the job is green. If red, keep the log as evidence for the result doc. Do not mask failures with `continue-on-error`.
- [x] **Step 3:** Commit.

### Task 4: Embedded view spike — two candidates, measured

**Files:**
- Create: `desktop/src/main/kotlin/org/tgbackup/desktop/spike/LocalPageServer.kt`
- Create: `desktop/src/main/kotlin/org/tgbackup/desktop/spike/WebView2Window.kt` (candidate A: WebView2 via a JVM binding such as `dev.webview:webview_java`; pin the exact version)
- Create: `desktop/src/main/kotlin/org/tgbackup/desktop/spike/JcefWindow.kt` (candidate B: JCEF via `me.friwi:jcefmaven`, with natives bundled at build time, never downloaded at runtime)
- Create: `desktop/src/test/kotlin/org/tgbackup/desktop/spike/LocalPageServerTest.kt`
- Modify: `desktop/build.gradle.kts` (two jpackage variants: `packageWebView2`, `packageJcef`), `.github/workflows/build.yml`

**Interfaces:**
- Produces: `class LocalPageServer { fun start(): Int /* bound port, 127.0.0.1, port 0 */; fun stop() }`. It serves `GET /` with a Hebrew RTL page `<html dir="rtl" lang="he"><title>TG Backup</title>…שלום…</html>` and `GET /health` → `ok`.
- Produces: for each candidate, `main(args)` with `--smoke`. It starts `LocalPageServer`, opens a window titled `TG Backup` at 1100×750 loading `http://127.0.0.1:<port>/`, waits for the page's load event, prints `PAGE_LOADED`, holds the window for 5 s, then exits 0. On failure or a 60 s timeout it prints `PAGE_FAIL <reason>` and exits 1.

- [x] **Step 1:** Write `LocalPageServerTest`: `start()` returns a port > 0; `GET /health` returns `ok`; `GET /` contains `dir="rtl"` and `שלום`; two servers started at once get different ports.
- [x] **Step 2:** Run it. Expected: FAIL (unresolved).
- [x] **Step 3:** Implement `LocalPageServer` with Ktor, bound to 127.0.0.1, port 0.
- [x] **Step 4:** Run it. Expected: PASS.
- [x] **Step 5:** Implement both candidate windows.
- [x] **Step 6:** Add jpackage tasks producing `TGBackup-Setup.exe` per candidate, on Windows only. Use jpackage `--type exe` when WiX is available on the runner; otherwise build an app-image and an Inno Setup installer (Inno Setup is preinstalled on windows-latest). Record which path was used.
- [x] **Step 7:** CI job `windows-spike`, run for each candidate:
  - Build the installer.
  - Print its size in MB.
  - Install it silently into `${{ runner.temp }}\inst`.
  - Run the installed `TGBackup.exe --smoke` with `HTTPS_PROXY=http://127.0.0.1:9` (offline check).
  - Assert `PAGE_LOADED`.
  - Take a PowerShell screenshot while the window is open and upload it plus the installers as artifacts.
  - Record whether the WebView2 runtime was preinstalled (registry key `HKLM\SOFTWARE\WOW6432Node\Microsoft\EdgeUpdate\Clients\{F3017226-FE2A-4295-8BDF-00C3A9A7E4C5}`).
- [x] **Step 8:** Commit and push. Collect the run id, sizes, smoke results and screenshot artifact names.

### Task 5: Spike results and decision record

**Files:**
- Create: `docs/superpowers/specs/2026-11-03-spike-results.md`

- [x] **Step 1:** Write the results with evidence (CI run ids, log lines, sizes):
  - TDLib on Linux and Windows.
  - Per candidate: installer size, offline smoke, screenshot, Hebrew-path behaviour, and the WebView2 runtime finding.
  - A recommended component with a one-line reason, and the fallback for a missing WebView2 runtime if it is chosen.
- [x] **Step 2:** Commit and push.

---

## Out of scope for this plan

Engine port, real screens, accounts/login, jobs, tray, autostart, single instance, remote access, update check, Sentry. Each goes in the next plan, written from the spike results.
