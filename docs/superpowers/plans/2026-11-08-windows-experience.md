# Windows Experience (Plan 5) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make TGBackup.exe behave like a Windows program people expect before the user's first real test: ask on X when work runs (background in the tray or stop), one instance only, start with Windows, an API explanation for new users, a source change that starts the job over, and the user's icons.

**Architecture:** Kotlin desktop shell (`desktop/src/main/kotlin/org/tgbackup/desktop/`) around the engine; WebView2 window through our JNA binding (`AppWindow.kt`); internal Ktor server (`server/`); web screens (`desktop/src/main/resources/web/`). New small units: DesktopPrefs, Autostart, SingleInstance, Tray, WindowLoop.

**Tech Stack:** Kotlin/JVM 17, Ktor 3, kotlinx.coroutines/serialization, JNA 5.19 + jna-platform, java.awt.SystemTray; ES modules; node unit tests; Playwright; GitHub Actions on windows-latest.

**Spec:** user decisions 2026-11-08, recorded in `/root/.claude/plans/floating-bubbling-kurzweil.md` (approved). Master plan stage 5: `/root/.claude/plans/sunny-sauteeing-forest.md`.

## Global Constraints
- Branch `engine-port`; after each green task push it and fast-forward `main` (`git push origin engine-port:main`). Never force, never delete branches; `git add` explicit paths only (`.kotlin/` stays untracked).
- Never symlink `desktop/build/resources/main/web` to src.
- All user-facing text Hebrew, verbatim from this plan.
- X on the window: nothing running (no job running and no active export — the same signal as the sleep guard: `jobs.anyRunning` OR export active) → the program exits. Something running → Windows Yes/No box, title `TG Backup`, text `יש משימה או ייצוא שרצים. להמשיך לרוץ ברקע?\nכן – ממשיכה ליד השעון.\nלא – התוכנה תיסגר, והמשימות ימשיכו מאותו מקום בפתיחה הבאה.`; Yes → background (tray icon), No → exit.
- The tray icon exists ONLY while running in the background without a window (after "Yes", or after `--minimized`). Click / "פתח" opens the window and removes the icon; "יציאה" exits at once, no question. Window open or program closed → no icon.
- Exit = today's shutdown path (`DesktopApp.close()` = process-death equivalent, autoResume kept, so jobs resume on next launch).
- `--smoke` and machines without a system tray keep today's behavior: closing the window exits.
- Autostart: settings switch, default OFF; `HKCU\Software\Microsoft\Windows\CurrentVersion\Run`, value name `TGBackup`, data `"<exe>" --minimized`; exe from system property `jpackage.app-path`; no exe or not Windows → no-op.
- Keep-awake: settings switch, default ON (today's behavior).
- Phone layout unchanged; a wide tablet (>=1200 px) keeps the split view.
- Verify: `./gradlew build`; `node --test web-tests/unit/*.test.mjs`; Playwright only per touched spec (`cd web-tests && npx playwright test --trace off <spec>`; the full suite exceeds its 15-min global timeout here); CI on windows-latest green after push.

## Review Focus
1. X pressed while an export (not a job) runs: must ask, not exit.
2. Second launch while the first runs in the background with no window: the first shows its window and the tray icon disappears; the second exits 0.
3. A stale `instance.json` (the first instance crashed): the next launch must start normally, not show "already running".
4. Autostart switched on, then the program updated/moved: the Run value is rewritten to the current exe on the next launch.
5. Editing only a job's name keeps its progress; changing its source clears all of it (position, הועתקו, רץ).

---

### Task 1: A source change starts the job over; edit waits for a stopping run; phase link name

**Files:** Modify `desktop/src/main/kotlin/org/tgbackup/desktop/server/JobRoutes.kt` (`save` ~120), `desktop/src/main/resources/web/screens/jobs.js` (phase `maybeLink` aria-label). Test `desktop/src/test/kotlin/org/tgbackup/desktop/server/JobRoutesTest.kt`, `web-tests/jobs.spec.mjs`.

**Interfaces:** Consumes `JobManager.resetProgress(id)` (clears progress like `אפס התקדמות`), `requireEnded(id)` in JobRoutes (requireStopped → awaitStopped → requireStopped).

- [ ] Failing tests: `aSourceChangeClearsTheProgress` (progress lastId 500, copiedTotal 40, runMillis 9000, latestId 900 → PUT with a new source → all four 0); `aNameChangeKeepsTheProgress` (same progress, PUT with a new name only → unchanged); `anEditRightAfterStopWaitsForTheRun` (pattern of `deleteRightAfterStopLeavesNoProgress`); jobs.spec: the phase link's accessible name is `מעתיק הודעה 5 · ההודעה האחרונה ביעד` for phase `מעתיק הודעה 5`.
- [ ] Run → FAIL.
- [ ] Implement: in `save`, `requireEnded(id)` instead of `requireStopped(id)`; when `old.source != job.source` call `jobs.resetProgress(id)` (replaces the latestId-only reset); aria-label `${status.phase} · ההודעה האחרונה ביעד`.
- [ ] `./gradlew build` + `npx playwright test --trace off jobs.spec.mjs` → pass.
- [ ] Commit `server: a job whose source changes starts over (its progress cleared like reset), an edit right after stop waits for the run; web: the phase link's name keeps the phase`.

### Task 2: Desktop settings — start with Windows, keep awake

**Files:** Create `desktop/.../DesktopPrefs.kt`, `desktop/.../Autostart.kt`; modify `DesktopApp.kt`, `server/Dto.kt`, `server/Events.kt`, `server/StateRoutes.kt` (or the routes file that owns `/api/export/prefs`), `resources/web/lib/store.js`, `resources/web/screens/settings.js`. Tests: `desktop/src/test/.../DesktopPrefsTest.kt`, `AutostartTest.kt`, a route test, `web-tests/shell.spec.mjs` (or a new `settings.spec.mjs`).

**Interfaces (produces):**
- `@Serializable data class DesktopPrefsData(val autostart: Boolean = false, val keepAwake: Boolean = true)`; `class DesktopPrefs(dir: File) { val value: StateFlow<DesktopPrefsData>; fun update(f: (DesktopPrefsData) -> DesktopPrefsData) }` — file `desktop.json`, atomic write (temp + move, as Store does), a bad/missing file → defaults.
- `interface RunKey { fun get(): String?; fun set(command: String); fun remove() }`; `class Autostart(key: RunKey, exe: String?) { fun apply(on: Boolean) }` — on: set `"<exe>" --minimized` if different; off: remove if present; `exe == null` → nothing. `WindowsRunKey` uses `Advapi32Util` on HKCU Run, value `TGBackup`. `Autostart.forCurrentOs()` picks WindowsRunKey on Windows with `System.getProperty("jpackage.app-path")`, else a no-op.
- `DesktopApp` gets `val prefs: DesktopPrefs`; the sleep signal becomes `(jobs || export) && prefs.keepAwake`; on start and on every `autostart` change `Autostart.apply`.
- API `GET /api/desktop-prefs` → `{autostart, keepAwake}`; `PUT` same body → saves, 200. Snapshot field and SSE event `desktopPrefs`.
- Settings card `עבודה ברקע`: text `כשמשימה או ייצוא רצים וסוגרים את החלון, התוכנה שואלת אם להמשיך ברקע (סמל ליד השעון) או להיסגר. כדי שהמשימות יתקדמו, המחשב צריך להיות דלוק.`; switches `הפעלה עם Windows (בלי חלון, ליד השעון)` and `מניעת שינה בזמן שמשימה או ייצוא רצים`.

- [ ] Failing tests: prefs defaults/round-trip/bad file; Autostart with a fake RunKey (on writes `"C:\\TG\\TGBackup.exe" --minimized`, a changed exe rewrites, off removes, null exe touches nothing); route GET/PUT + snapshot carries `desktopPrefs`; sleep guard off when keepAwake false (fake PowerRequests, as existing DesktopApp tests do); web: both switches reflect state and PUT on change.
- [ ] Run → FAIL. Implement. Run → PASS (`./gradlew build`, unit, the spec).
- [ ] Commit `desktop: settings to start with Windows (Run key, --minimized) and to keep the PC awake, saved in desktop.json and shown in Settings`.

### Task 3: One instance

**Files:** Create `desktop/.../SingleInstance.kt`; modify `Main.kt`, `server/ApiServer.kt` (internal route). Tests `SingleInstanceTest.kt`, an ApiServer/route test.

**Interfaces (produces):**
- `class SingleInstance(dataDir: Path) { fun acquire(): Boolean; fun publish(port: Int, token: String); fun handOff(): HandOff; fun release() }` — lock `instance.lock` via `FileChannel.tryLock`; `instance.json` `{port, token}`; `handOff()` reads it and POSTs `http://127.0.0.1:<port>/internal/show` with header `X-TGB-Instance: <token>` (2 s timeout): `HandOff.Shown | HandOff.NoAnswer`.
- Server: `POST /internal/show` outside the session-cookie check, only from loopback, header must equal the instance token (else 403); calls `onShow()` (a callback given to ApiServer; Task 4 wires it to the window loop; until then it is a no-op).
- `Main.run`: before `DesktopApp`, `acquire()`; when false → `handOff()`: Shown → print `INSTANCE_HANDED_OFF`, exit 0; NoAnswer → `MessageBoxW` `התוכנה כבר פועלת.` and exit 0. `--smoke` uses its own data dir, so the lock never collides with a real instance.
- The lock is released by the OS when the process ends (crash-safe); `instance.json` from a dead process is simply overwritten by the next owner.

- [ ] Failing tests: two SingleInstance on one temp dir — first acquire true, second false (separate channel in the same JVM is not enough on all OSes: use a child JVM or `tryLock` semantics via a second FileChannel and assert `OverlappingFileLockException` → treated as held); publish/handOff against a local test server returns Shown; no server → NoAnswer; route 403 without/with wrong token, 200 and callback called with the right one.
- [ ] Implement, run, commit `desktop: one instance — a second launch shows the running one's window (POST /internal/show with the instance token) and exits`.
- [ ] CI: in `.github/workflows/build.yml` windows-package job, after the smoke run, start the installed exe twice with a test data dir env (or `--smoke` twice with a delay) and assert the second prints `INSTANCE_HANDED_OFF` and exits 0. If that cannot be done without a real window, assert at least the lock + NoAnswer path and record why.

### Task 4: Background in the tray, window reopened

**Files:** Create `desktop/.../Tray.kt`, `desktop/.../WindowLoop.kt`; modify `Main.kt` (`run`), `WindowLifecycle.kt`, `AppWindow.kt` only if a reopen needs it. Tests `WindowLoopTest.kt`.

**Interfaces (produces):**
- `class WindowLoop(window: () -> WindowOutcome, busy: () -> Boolean, askBackground: () -> Boolean, tray: TrayControl?, startHidden: Boolean)` with `fun run(): WindowOutcome` and `fun show()` (thread-safe; from tray click, "פתח", `/internal/show`) and `fun quit()` ("יציאה"). `interface TrayControl { fun showIcon(onOpen: () -> Unit, onQuit: () -> Unit); fun hideIcon() }`.
- Rules (Global Constraints): after the window closes — `!busy()` → return CLOSED; `busy()` → `askBackground()`: false → CLOSED; true → `tray.showIcon`, wait for show/quit; show → `hideIcon`, open the window again; quit → `hideIcon`, CLOSED. `tray == null` → CLOSED at once. `startHidden` (`--minimized`) starts in the waiting state with the icon. A `show()` while the window is already open brings it to the front (`SetForegroundWindow` on its HWND) — wire through AppWindow if needed.
- `askBackground` = `MessageBoxW` with `MB_YESNO | MB_ICONQUESTION` and the exact text from Global Constraints; Yes (6) → true.
- `Tray.kt`: `SystemTray.isSupported()` else null; icon from resources `tray-16.png`/`tray-32.png` by `SystemTray.trayIconSize` (Task 6 supplies them; until then a placeholder generated in code is fine but must be replaced in Task 6); tooltip `TG Backup — רץ ברקע`; left click → open; `PopupMenu` items `פתח`, `יציאה`. All AWT on the EDT.
- `--smoke`: tray null (today's behavior).

- [ ] Failing WindowLoopTest cases: not busy → closes once, no question; busy + No → closed, icon never shown; busy + Yes → icon shown, `show()` → window opened again and icon hidden; busy + Yes → `quit()` → closed, icon hidden; `startHidden` → no window until `show()`; tray null + busy → closed without asking.
- [ ] Implement, `./gradlew build`, CI smoke stays green (smoke path unchanged).
- [ ] Commit `desktop: closing the window while work runs asks to go on in the background; the tray icon (only then) opens the window again or exits; --minimized starts in the tray`.

### Task 5: API explanation on the empty Accounts screen

**Files:** Modify `desktop/src/main/resources/web/screens/accounts.js` (and `app.css` if needed). Test `web-tests/accounts.spec.mjs`.

- [ ] Failing tests (faked state with `accounts: []` and with one account): with none, a card titled `איך מקבלים API ID ו-API Hash` appears before `➕ הוסף חשבון` (or the existing add button's text) with exactly these steps as an ordered list: `נכנסים ל-my.telegram.org ומתחברים עם מספר הטלפון (הקוד מגיע בטלגרם).` / `בוחרים API development tools.` / `ממלאים שם אפליקציה וכינוי (כל טקסט באנגלית) ולוחצים Create.` / `מעתיקים את App api_id ואת App api_hash לחלון "חשבון חדש".` and a link `פתח את my.telegram.org` to `https://my.telegram.org` (target _blank); with an account, no card. Window and phone projects.
- [ ] Implement, run accounts.spec, commit `web: the empty Accounts screen explains how to get an API ID and hash, with a link to my.telegram.org`.

### Task 6: Icons

**Files:** Create `desktop/src/main/icons/source/backup-logo.jpg` (copy of `/root/.claude/uploads/29371532-e52b-5dab-8002-db3cbbfd2619/939f2381-image.jpg`), `desktop/src/main/icons/source/cloud.jpg` (copy of `.../8141ff03-image.jpg`), `tools/make-icons.py` (Pillow; the controller's approved generator is at `/tmp/claude-0/-root/29371532-e52b-5dab-8002-db3cbbfd2619/scratchpad/icons/make.py` — port it to read the repo sources and write the outputs below), outputs `desktop/src/main/icons/TGBackup.ico` (16,24,32,48,64,128,256: drawing only below 64, drawing + word BACKUP from 64; white on a black circle, transparent corners), `desktop/src/main/resources/tray-16.png`, `tray-32.png` (white cloud in a black circle), `desktop/src/main/resources/web/favicon.png` (64) + `<link rel="icon">` in `index.html`. Modify `desktop/build.gradle.kts` (jpackage `--icon` with the .ico for app-image and installer), `AppWindow.kt` (set the window icon: `LoadImageW` of the .ico from `<install>\app` or a resource extracted to temp, then `SendMessageW(hwnd, WM_SETICON, ICON_SMALL/ICON_BIG, hIcon)`).
- [ ] The outputs are committed (the build does not need Python). Running `python tools/make-icons.py` again reproduces them byte-identically or pixel-identically (state which).
- [ ] CI windows-package: the saved window screenshot shows the icon in the title bar; the installed exe has the icon (check with PowerShell `[System.Drawing.Icon]::ExtractAssociatedIcon` size > 0 and differs from the Java default, or record what can be asserted).
- [ ] Commit `desktop: the user's icons — the BACKUP logo for the program (exe, window, installer, favicon) and a cloud for the tray`.

### Finish
- [ ] Version 0.2.0 (where `versionName` is set for `BuildConfig.VERSION_NAME`), commit, push (rule: git after every version).
- [ ] Whole-branch review; CI green on windows (smoke, package, window shot).
- [ ] New screenshots (settings, empty accounts; phone + window) to `docs/screens/` and `/sdcard/Download/tg-backup-desktop-screens`.
