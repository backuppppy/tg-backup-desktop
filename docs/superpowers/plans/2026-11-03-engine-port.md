# TG Backup Desktop — Engine Port Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The Android app's engines (`data`, `engine`, `tg`, `export`) run on the JVM inside TG Backup Desktop. They are wired in a `DesktopApp` container that replaces `App.kt` and `BackupService.kt`, and they are proven on `windows-latest` with a Hebrew data dir. The real screens come in the next plan.

**Architecture:** New Gradle project `:engine` holds the Android files copied verbatim, in the **same packages** (`org.tgbackup.app.*`). That way a future fix is a plain file diff between the repos. Only the few Android-only spots are edited; every edit is listed in `docs/PORTING.md` and checked by a script. `:desktop` depends on `:engine` and owns the process: `DesktopApp`, sleep prevention and an engine smoke run. The JCEF candidate is removed, and WebView2 is the only view.

**Tech Stack:** Kotlin/JVM 17 (Kotlin 2.4.21), kotlinx-coroutines 1.11.0, kotlinx-serialization-json 1.11.0 (same as the Android app), `dev.g000sha256:tdl-coroutines-jvm:15.0.0`, `io.sentry:sentry:8.61.0` (JVM SDK, same API as `sentry-android`), JNA + jna-platform 5.19.1, Java ImageIO.

**Spec:** `docs/superpowers/specs/2026-11-03-tg-backup-desktop-design.md`. Spike results: `docs/superpowers/specs/2026-11-03-spike-results.md` (WebView2 approved by the user on 2026-11-03).

## Global Constraints

- Source of the port: `/root/tg-backup-apk` at commit `6bdd532` (v2.2.20, branch `takeout-export`), read with `git -C /root/tg-backup-apk show 6bdd532:<path>`. That repo is read-only for this work.
- Copy only `app/src/main/java/org/tgbackup/app/{data,engine,tg,export}/*.kt`. Never copy `ui/*`, `service/*`, `App.kt`, untracked files, or the Android `SENTRY_DSN`.
- `JobRunner` and `TakeoutJobRunner` keep their logic unchanged, and the rules in memory `project_tg_backup_takeout_rules.md` apply. No behaviour change in the copy/takeout engines.
- Data dir is `AppDirs.dataDir()` (`%LOCALAPPDATA%\TGBackup`), which can be a Hebrew path. `NativeTmp.ensureAsciiTmpdir` stays the first call in every `main`.
- No secrets in the repo: no API id/hash, sessions, phone numbers, DSN or tokens. Sentry is not initialised in this plan (that belongs to the installer plan); `Sentry.*` calls are then no-ops.
- Windows only as the target. Linux is the dev host, and every new test must pass on both.
- Commit trailer: `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. Pushes that touch `.github/workflows/` need the PAT from memory `github_token.md`.

## Review Focus

1. **Saving over an existing file on Windows.** `File.renameTo` does not replace an existing file on Windows. Today `Store` (config, progress), `LogBus` (rotation) and export-core `MtpStorage` (the export session) would silently keep their first version forever. Expected: every save survives a restart. Owner: Task 2. Its tests run in the `windows` CI job.
2. **The PC going to sleep while a job runs** (Android held a wake lock). Expected: no system sleep while a job or an export is active, and normal sleep again when all stop. Owner: Task 5.
3. **Closing the program mid-job.** Expected: the job restarts on the next launch from its saved position. `DesktopApp.close()` must not call `jobs.stopAll()`, because that clears `autoResume`. Owner: Task 6.
4. **A Hebrew data dir for every engine file** (config, progress, log, TDLib db, export session, thumbnails). Owner: Tasks 2, 3 and 6, with tests under `תיקייה עם רווח`. The Task 7 Windows smoke covers TDLib.
5. **Thumbnails of formats ImageIO cannot write (WebP) or broken images during an export.** Expected: the thumbnail is skipped (`false`/`null`), never a crash. Owner: Task 3.

---

### Task 1: Drop JCEF; WebView2 is the only package

**Files:**
- Delete: `desktop/src/main/kotlin/org/tgbackup/desktop/spike/JcefWindow.kt`
- Modify: `desktop/build.gradle.kts` (remove `me.friwi:jcefmaven`, `jcefNatives`, `JCEF_NATIVES`, the `jcef` `SpikeCandidate`), `.github/workflows/build.yml` (`windows-spike` matrix keeps only `webview2`; rename the job to `windows-package`)

- [ ] **Step 1:** Remove the items above. `WebView2Window.kt`, `LocalPageServer.kt`, `SpikeSmoke.kt` and `TdlibSmoke.kt` stay.
- [ ] **Step 2:** Run `./gradlew :desktop:test`. Expected: PASS, and `grep -ri jcef desktop .github` prints nothing.
- [ ] **Step 3:** Commit and push. Expected: CI green, with job `windows-package` showing `PAGE_LOADED`, `WINDOW_INSIDE yes` and `NATIVE_TMP_RELAUNCH`.

### Task 2: `:engine` project, verbatim copy, Windows-safe file replace

**Files:**
- Create: `engine/build.gradle.kts`; Modify: `settings.gradle.kts` (`include(":engine")`), `desktop/build.gradle.kts` (`implementation(project(":engine"))`)
- Create: `engine/src/main/kotlin/org/tgbackup/app/{data,engine,tg,export}/*.kt` (copied, see Step 1)
- Create: `export-core/src/main/kotlin/org/tgbackup/io/AtomicFiles.kt`, `export-core/src/test/kotlin/org/tgbackup/io/AtomicFilesTest.kt`
- Create: `engine/src/main/kotlin/org/tgbackup/app/platform/DeviceInfo.kt`
- Create: `engine/src/test/kotlin/org/tgbackup/app/data/StoreTest.kt`, `engine/src/test/kotlin/org/tgbackup/app/engine/LogBusTest.kt`
- Create: `docs/PORTING.md`, `tools/engine-sync-check.sh`
- Modify (documented deviations): `Store.kt`, `LogBus.kt`, `AccountClient.kt`, export-core `mtproto/MtpClient.kt` (`MtpStorage.set`)
- Modify: `.github/workflows/build.yml` (the `windows` job runs `./gradlew.bat test`, all projects, instead of `:desktop:test`)

**Interfaces:**
- Produces: `object AtomicFiles { fun replace(tmp: java.io.File, target: java.io.File) }`. It uses `Files.move(tmp, target, REPLACE_EXISTING, ATOMIC_MOVE)`, falls back to `REPLACE_EXISTING` alone on `AtomicMoveNotSupportedException`, and retries up to 5 times 100 ms apart on `AccessDeniedException`/`FileSystemException` (antivirus locks). After that it rethrows.
- Produces: `object DeviceInfo { val deviceModel: String /* "TG Backup Desktop" */; val systemVersion: String /* System.getProperty("os.name"), e.g. "Windows 11" */ }`.
- Produces: `org.tgbackup.app.BuildConfig.VERSION_NAME` generated by Gradle from `version` into `engine/build/generated/buildconfig`, so the copied `import org.tgbackup.app.BuildConfig` compiles unchanged. Root `gradle.properties` gets `tgbVersion=0.1.0`, used by both `:engine` and `:desktop` (`Main.VERSION` reads `BuildConfig.VERSION_NAME`).
- Produces: all copied classes under their Android names: `Store(dir: File)`, `LogBus(dir: File)`, `AccountManager(rootDir: File, log: LogBus)`, `JobManager(store, accounts, log, scope, exports: () -> ExportManager)`, `ExportManager(rootDir: File, accounts: AccountManager, log: LogBus)`.

- [ ] **Step 1:** Copy the files with `git -C /root/tg-backup-apk archive 6bdd532 app/src/main/java/org/tgbackup/app/data app/src/main/java/org/tgbackup/app/engine app/src/main/java/org/tgbackup/app/tg app/src/main/java/org/tgbackup/app/export | tar -x` into a scratch dir, then move each to `engine/src/main/kotlin/...` at the same relative package path. Do not copy `export/AndroidImageThumbs.kt` (Task 3). `engine/build.gradle.kts`: `kotlin("jvm")`, `kotlin("plugin.serialization") version "2.4.21"`, and the dependencies from the Tech Stack line plus `project(":export-core")`.
- [ ] **Step 2: Write the failing tests.**
  - `AtomicFilesTest.replaceOverwritesAnExistingTarget`: write `a` to target and `b` to tmp, call `replace`, then the target reads `b` and tmp does not exist.
  - `StoreTest.configSurvivesTwoUpdatesAndAReload`: dir = temp dir resolved under `"תיקייה עם רווח"`. `Store(dir).updateConfig { it.copy(jobs = listOf(Job(id = "j1"))) }` and then a second update adding `"j2"`. `Store(dir).config.value.jobs.map { it.id } == listOf("j1", "j2")`.
  - `StoreTest.progressSurvivesTwoSavesAndAReload`: `saveProgress("j1") { Progress(lastId = 10) }`, then `{ it.copy(lastId = 20) }`. `Store(dir).progress("j1").lastId == 20L`.
  - `LogBusTest.rotationReplacesAnOldRotatedFile`: create `log.old.txt` with `old`, then write `log.txt` > 1,000,000 bytes and call `LogBus(dir).info("x")`. `log.old.txt` no longer reads `old`, and `log.txt` is < 1,000 bytes.
  - `MtpStorageTest.secondSetIsKeptAfterReload` (export-core test dir): `set("main_dc","2")`, `set("main_dc","4")`, then a new `MtpStorage(sameFile).mainDc == 4`.
- [ ] **Step 3:** Run `./gradlew :engine:compileKotlin`. Expected: FAIL only on `android.os.Build` in `AccountClient.kt` and `ExportManager.kt`, and on `AndroidImageThumbs` in `ExportManager.kt`. Any other error is a finding: report it rather than editing the engine.
- [ ] **Step 4:** In `AccountClient.kt`, replace `"${Build.MANUFACTURER} ${Build.MODEL}"` / `"Android ${Build.VERSION.RELEASE}"` with `DeviceInfo.deviceModel` / `DeviceInfo.systemVersion`. Do the same in `ExportManager.kt`, and for now temporarily construct `JvmImageThumbs` there as a `TODO()` stub file (`export/JvmImageThumbs.kt`, implemented in Task 3). Use `AtomicFiles.replace` instead of `renameTo` in `Store.writeConfig`, `Store.saveProgress` and `MtpStorage.set`. In `LogBus`, rotate with `Files.move(..., REPLACE_EXISTING)`.
- [ ] **Step 5:** Run `./gradlew test`. Expected: PASS for export-core, engine and desktop.
- [ ] **Step 6:** Write `docs/PORTING.md`: the source commit `6bdd532`, the list of edited files with a one-line reason each (the edits above, plus Task 3's), the new files, and a note that the `AtomicFiles` fix should also go to the Android repo (harmless there). Write `tools/engine-sync-check.sh <android-repo> <commit>`: for each `engine/src/main/kotlin/org/tgbackup/app/**/*.kt` it compares against `app/src/main/java/...` at the commit, prints `SAME`/`EDITED`/`NEW`/`MISSING` per file, and exits 1 if an `EDITED` file is not listed in `PORTING.md`. Run it. Expected: only listed files are `EDITED`, and `export/AndroidImageThumbs.kt` is the only `MISSING`.
- [ ] **Step 7:** Switch the `windows` CI job to `./gradlew.bat test`. Commit and push. Expected: CI green on Windows, which proves the replace fix there. A Windows failure in export-core's own tests is a finding: fix it if it is a path or rename issue, otherwise stop and report.

### Task 3: `JvmImageThumbs` (ImageIO) and the Windows Downloads folder

**Files:**
- Create: `engine/src/main/kotlin/org/tgbackup/app/export/JvmImageThumbs.kt`, `engine/src/main/kotlin/org/tgbackup/app/platform/DownloadsDir.kt`
- Modify: `engine/src/main/kotlin/org/tgbackup/app/export/ExportManager.kt` (`defaultPath()`)
- Test: `engine/src/test/kotlin/org/tgbackup/app/export/JvmImageThumbsTest.kt`, `engine/src/test/kotlin/org/tgbackup/app/platform/DownloadsDirTest.kt`

**Interfaces:**
- Consumes: `org.tgbackup.export.output.ImageThumbs` (export-core): `size(path): Pair<Int,Int>?`, `scaleAndSave(path, width, height, target, format: String?, quality: Int): Boolean`.
- Produces: `class JvmImageThumbs : ImageThumbs`. It mirrors `AndroidImageThumbs`: format from `format`, else from the source's reader format. Supported are `png`/`image/png` and `jpg`/`jpeg`/`image/jpeg`; anything else (including WebP, which ImageIO cannot write) returns `false`. JPEG quality is `if (quality < 0) 75 else quality`. JPEG output drops alpha onto white. Scaling uses progressive halving and then bicubic to the exact size. `size()` reads the header via `ImageReader.getWidth(0)/getHeight(0)` without decoding pixels.
- Produces: `object DownloadsDir { fun resolve(knownFolder: () -> String? = ::windowsKnownFolder, home: String = System.getProperty("user.home")): java.io.File }`. It returns the known folder when non-blank, else `<home>/Downloads`. `windowsKnownFolder` = `Shell32Util.getKnownFolderPath(KnownFolders.FOLDERID_Downloads)` on Windows inside `runCatching`, else `null`. `ExportManager.defaultPath()` = `File(DownloadsDir.resolve(), "AyuGram Desktop").absolutePath`, the same folder name as Android.

- [ ] **Step 1: Write the failing tests.** Test images are generated in the test (`BufferedImage` written with ImageIO) under a temp dir in `"תיקייה עם רווח"`:
  - `sizeReadsDimensions`: a 400×300 PNG gives `400 to 300`.
  - `sizeOfABrokenFileIsNull`: a file with bytes `"not an image"` gives `null`.
  - `scalesPngToJpegWithExactSize`: ARGB 400×300 → `scaleAndSave(src, 100, 75, "<dir>/sub/t.jpg", "jpg", -1)` is `true`, and reading `t.jpg` gives 100×75 (the parent `sub` is created).
  - `keepsPngFormatWhenFormatIsNull`: PNG source, `format = null`, gives a PNG file 50×50.
  - `webpIsNotSupported`: `format = "webp"` gives `false`, and no target file exists.
  - `brokenSourceReturnsFalse`.
  - `DownloadsDirTest`: `resolve({ "D:\\הורדות" }, "C:\\Users\\u").path == "D:\\הורדות"`, and `resolve({ null }, "/home/u") == File("/home/u", "Downloads")`.
- [ ] **Step 2:** Run `./gradlew :engine:test --tests '*JvmImageThumbs*' --tests '*DownloadsDir*'`. Expected: FAIL (the `TODO()` stub / unresolved `DownloadsDir`).
- [ ] **Step 3:** Implement both, then update `defaultPath()` and add both edits to `PORTING.md`.
- [ ] **Step 4:** Run `./gradlew test` and `tools/engine-sync-check.sh /root/tg-backup-apk 6bdd532`. Expected: PASS, and the check exits 0.
- [ ] **Step 5:** Commit.

### Task 4: Engine tests the Android repo never had (pure logic, no network)

**Files:**
- Test: `engine/src/test/kotlin/org/tgbackup/app/engine/JobManagerTest.kt`

**Interfaces:**
- Consumes: `JobManager`, `Store`, `LogBus`, `AccountManager` (with no accounts, so no TDLib client is created).

- [ ] **Step 1: Write the tests** with a `TestScope`/`Job()` scope and a temp data dir:
  - `startRejectsAJobWithoutAccounts`: a job with source/target but empty `accountIds` → `start(id) == "לא נבחרו חשבונות למשימה"`, and `autoResume` stays empty.
  - `startRejectsMissingSourceOrTarget` → `"חסר מקור או יעד"`.
  - `restoreIgnoresDeletedJobs`: `autoResume = setOf("gone")` with no such job, then `restore()` does not throw and `isRunning("gone") == false`.
  - `stopRemovesAutoResume`: `updateConfig { it.copy(autoResume = setOf("j1"), jobs = listOf(Job(id="j1"))) }`, then `stop("j1")`, then `autoResume` is empty. This pins the behaviour Task 6 must avoid on close.
- [ ] **Step 2:** Run them. Expected: PASS without changing engine code (these pin existing behaviour). A failure is a finding: report it, do not change the engine.
- [ ] **Step 3:** Commit.

### Task 5: Keep Windows awake while work runs (replaces the Android wake lock)

**Files:**
- Create: `desktop/src/main/kotlin/org/tgbackup/desktop/SleepGuard.kt`
- Test: `desktop/src/test/kotlin/org/tgbackup/desktop/SleepGuardTest.kt`

**Interfaces:**
- Produces: `fun interface PowerRequests { fun keepAwake(on: Boolean) }`, with `companion object { fun forCurrentOs(): PowerRequests }`. On Windows that is `WindowsPowerRequests`; elsewhere a no-op.
- Produces: `class SleepGuard(private val power: PowerRequests) { fun update(active: Boolean) }`. It calls `power.keepAwake` only on a change, so it is idempotent.
- `WindowsPowerRequests`: `SetThreadExecutionState` is **per thread**, so every call runs on one dedicated daemon thread (`Executors.newSingleThreadExecutor`). On: `ES_CONTINUOUS or ES_SYSTEM_REQUIRED` (`0x80000000 or 0x1`). Off: `ES_CONTINUOUS`. No `ES_DISPLAY_REQUIRED`: the screen may still turn off. Declare the call through `com.sun.jna.platform.win32.Kernel32.INSTANCE.SetThreadExecutionState`.

- [ ] **Step 1: Write the failing tests** with a recording fake:
  - `firstActiveTurnsOn`: `update(true)` gives `[true]`.
  - `repeatedStateIsNotResent`: `true, true, false, false` gives `[true, false]`.
  - `startsInactive`: `update(false)` as the first call gives `[]`.
- [ ] **Step 2:** Run. Expected: FAIL (unresolved).
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** Run. Expected: PASS.
- [ ] **Step 5:** Commit.

### Task 6: `DesktopApp` — the engine host (replaces `App.kt` and `BackupService.kt`)

**Files:**
- Create: `desktop/src/main/kotlin/org/tgbackup/desktop/DesktopApp.kt`
- Test: `desktop/src/test/kotlin/org/tgbackup/desktop/DesktopAppTest.kt`

**Interfaces:**
- Consumes: Task 2 classes, `SleepGuard`/`PowerRequests` (Task 5), `AppDirs.dataDir()`.
- Produces: `class DesktopApp(dataDir: java.nio.file.Path, power: PowerRequests = PowerRequests.forCurrentOs()) : AutoCloseable`, with `val store: Store`, `val log: LogBus`, `val accounts: AccountManager`, `val jobs: JobManager`, `val exports: ExportManager`, `val scope: CoroutineScope`, and `fun start()`. `start()` mirrors `App.onCreate()` without Sentry and the service:
  - log the `migratedFromLegacy` message;
  - collect `store.config.map { it.accounts }.distinctUntilChanged()` into `accounts.sync`;
  - collect `combine(jobs.anyRunning, exports.running) { r, e -> r || e?.job?.isActive == true }` into `SleepGuard.update`;
  - then `jobs.restore()` (what `MainActivity` and `BackupService` did).
- `close()`: **no `jobs.stopAll()`** (it clears `autoResume`; Task 4 pins that). It calls `exports.stop()`, cancels `scope` (like Android process death, so progress and `autoResume` stay on disk), closes every client in `accounts.clients.value` inside `runBlocking { withTimeoutOrNull(15.seconds) { … } }`, and calls `SleepGuard.update(false)`. Closing twice is a no-op.

- [ ] **Step 1: Write the failing tests** (temp data dir under `"תיקייה עם רווח"`, a recording `PowerRequests`):
  - `startAndCloseOnAnEmptyDirWritesNothingSurprising`: `start()` then `close()` with no exception, and `close()` again is fine.
  - `closeKeepsAutoResume`: before `start()`, write a config with `jobs = listOf(Job(id="j1", source="s", target="t"))` (no accounts, so `restore()`'s `start` returns an error and does not run) and `autoResume = setOf("j1")`. After `start()` and `close()`, `Store(dir).config.value.autoResume == setOf("j1")`.
  - `noAccountsMeansNoTdlibClients`: after `start()`, `accounts.clients.value.isEmpty()`.
  - `idleAppDoesNotKeepThePcAwake`: after `start()` and `close()`, the recorded power calls contain no `true`.
- [ ] **Step 2:** Run. Expected: FAIL (unresolved).
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** Run `./gradlew test`. Expected: PASS.
- [ ] **Step 5:** Commit.

### Task 7: Engine smoke on windows-latest (TDLib through the real engine, Hebrew path)

**Files:**
- Create: `desktop/src/main/kotlin/org/tgbackup/desktop/EngineSmoke.kt`
- Modify: `desktop/build.gradle.kts` (JavaExec task `engineSmoke`, like `tdlibSmoke`: same `TGB_TEST_LOCALAPPDATA`, TEMP/TMP handling and `LC_ALL`), `.github/workflows/build.yml` (`windows` job step `Engine smoke`, Hebrew `LOCALAPPDATA` and `TEMP`)

**Interfaces:**
- Produces: `main(args)` that calls `NativeTmp.ensureAsciiTmpdir("org.tgbackup.desktop.EngineSmokeKt", args)` and then builds `DesktopApp(AppDirs.dataDir().resolve("engine-smoke"))`. It writes one account `Account(id="smoke", name="smoke", apiId=1, apiHash="0".repeat(32), phone="")` (dummy values, no network login is attempted), calls `start()`, and waits up to 60 s until `accounts["smoke"]` exists and its `auth` value is not the initial loading state. Then it prints `ENGINE_OK <auth state class simpleName>`, calls `close()` and exits 0. On timeout or exception it prints `ENGINE_FAIL <reason>` and exits 1. Read `AccountClient.AuthUi` for the exact initial state name before writing the wait.

- [ ] **Step 1:** Implement, then run `./gradlew :desktop:engineSmoke` on Linux. Expected: `ENGINE_OK …`, and `<data dir>/engine-smoke/tdlib/smoke/db` exists.
- [ ] **Step 2:** Add the CI step. It asserts `ENGINE_OK`, `NATIVE_TMP_RELAUNCH`, and a `config_v2.json` under the Hebrew dir. Push and watch. Expected: green. Keep the log lines for the results.
- [ ] **Step 3:** Commit.

### Task 8: Optional live read-only check (local, with the user)

Run only if the user agrees and gives a login code; it is not in CI.

**Files:**
- Create: `desktop/src/main/kotlin/org/tgbackup/desktop/EngineLive.kt` (task `engineLive`)

**Interfaces:**
- Reads `~/.tgbackup-live.properties` (outside the repo: `apiId`, `apiHash`, `phone`, `source`). It logs in through `AccountClient` with the code and password read from stdin, then calls `resolveChat(source)` and reads one page of history, printing `LIVE_OK <title> <count>`. **No send, copy or export.**

- [ ] **Step 1:** Implement and run with the user's test account. Expected: `LIVE_OK`.
- [ ] **Step 2:** Commit the tool (never the properties file or session).

### Task 9: Results and handoff

- [ ] **Step 1:** Append to `docs/PORTING.md` a "Verified" section: CI run id, `ENGINE_OK` line, test counts, the `AtomicFiles` finding, and the Task 8 result or "skipped".
- [ ] **Step 2:** Update memory `project_tg_backup_desktop.md` (plan 2 done, next: plan 3, server and UI).
- [ ] **Step 3:** Commit and push.

---

## Out of scope for this plan

Server/REST/SSE and screens (plan 3), real window, tray, single instance and autostart (plan 4/5), remote access (plan 6), Sentry init, release and update check (plan 7).
