# The PC Stays Awake: A Dimmed Screen Instead of a Dark One. Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** While a job or export runs and "מניעת שינה" is on, Windows never turns the screen off, so a Modern Standby PC never sleeps. After the user's own display timeout with no input, the built-in panel dims to its minimum. Input brings the saved brightness back, and so does the end of the work.

**Architecture:** `WindowsPowerRequests` adds a DisplayRequired request and `ES_DISPLAY_REQUIRED`. A pure `ScreenDimmer` decides when to dim and when to restore, from two small Windows ports: `Brightness` (WMI through PowerShell) and `IdleClock` (`GetLastInputInfo`, VIDEOIDLE AC/DC). A file saves the brightness to put back, so a crash, the next launch and the uninstaller (`--restore-power`) all restore it.

**Tech Stack:** Kotlin/JVM 17, JNA (jna-platform `User32.GetLastInputInfo`, `Kernel32.GetTickCount64`/`GetSystemPowerStatus`), powrprof (already loaded by `WindowsPowerSettings`), PowerShell CIM `root/WMI` `WmiMonitorBrightness` / `WmiMonitorBrightnessMethods.WmiSetBrightness`. JUnit 5 / kotlin.test, Playwright for the label.

**Spec:** `docs/superpowers/specs/2026-11-15-protection-and-screen-design.md` (part 2)

## Global Constraints

- Settings label, exact: `מניעת שינה בזמן שמשימה או ייצוא רצים (המסך מוחשך כשלא נוגעים במחשב)`.
- The switch's default stays on. With the switch off, nothing changes: no display request, no dimming.
- Dim to brightness `0`. Never touch an external monitor (WMI reaches only the built-in panel; with no WMI instance, dimming is skipped silently).
- The idle threshold is the user's own VIDEOIDLE for the current power source (AC or DC). A value of 0 ("never") means never dim.
- `--smoke` and non-Windows: no-ops, as `SleepOverride` already is.
- User-facing log lines in Hebrew.

## Review Focus

- The user comes back while the screen is dimmed: one touch must restore the brightness within the poll (2 s), not at the end of the job (Task 1 test `inputRestores`).
- A crash or a kill while dimmed: the next launch, or the uninstaller, restores the saved brightness, and the saved file is deleted only after the restore succeeds (Task 1 tests `recoverRestoresSaved`, `failedRestoreKeepsFile`).
- The user changes brightness while dimmed (Fn keys): they are active, so the dimmer restores right away. It never saves 0 as "the user's brightness" (Task 1 test `neverSavesOwnDim`).
- Work stops while dimmed: restore before the display request is cleared (Task 2, step 2).
- A PC without a WMI brightness panel (desktop PC, external monitor only): `read()` returns null, so no dimming and no repeated errors. One log line at most (Task 1 test `noPanelNoDim`).

---

### Task 1: `ScreenDimmer` logic (pure, tested)

**Files:**
- Create: `desktop/src/main/kotlin/org/tgbackup/desktop/ScreenDimmer.kt`
- Test: `desktop/src/test/kotlin/org/tgbackup/desktop/ScreenDimmerTest.kt`

**Interfaces:**
- Produces:
  - `interface Brightness { fun read(): Int?; fun set(level: Int) }` (null = no panel; `set` throws on failure)
  - `interface IdleClock { fun idleMs(): Long; fun displayTimeoutMs(): Long }` (0 = never)
  - `class ScreenDimmer(file: File, brightness: Brightness, idle: IdleClock, log: (String) -> Unit = {})` with:
    - `@Synchronized fun tick(active: Boolean)`. Active, not dimmed, timeout > 0, and idle ≥ timeout: read the level. Null means no panel (logged once). Else write it to [file], then `set(0)`. Dimmed and (not active, or idle < timeout): `restore()`.
    - `@Synchronized fun restore(): Boolean`: when [file] holds a level, `set(level)`, then delete the file. True when it restored.
    - `fun recover(): Boolean = restore()`
    - `companion object { const val FILE_NAME = "dimmed-brightness.txt" }`

- [ ] **Step 1: Write the failing tests** with fakes (a `FakeBrightness` that records sets and can throw; a `FakeIdle` with mutable values; a temp dir):
  - `dimsAfterTimeout`: level 70, timeout 60 000, idle 60 000, `tick(true)` → sets `[0]`, file holds `70`.
  - `notBeforeTimeout`: idle 59 999 → no set.
  - `inputRestores`: dimmed, then idle 1 000, `tick(true)` → sets `[0, 70]`, file gone.
  - `stopRestores`: dimmed, then `tick(false)` → restores 70.
  - `neverWhenTimeoutZero`: timeout 0, idle huge → no set.
  - `neverSavesOwnDim`: dimmed, a second `tick(true)` while still idle → no new read or write, file still `70`.
  - `noPanelNoDim`: `read()` null → no set, no file, and two ticks log one line.
  - `recoverRestoresSaved`: file holds `55`, a new dimmer's `recover()` → sets `[55]`, returns true, file gone.
  - `failedRestoreKeepsFile`: `set` throws during restore → the exception propagates and the file stays.
- [ ] **Step 2: Run** `./gradlew :desktop:test --offline --tests '*ScreenDimmerTest*'`. Expected: FAIL (unresolved references).
- [ ] **Step 3: Implement.** The file is written atomically, as `SleepOverride.save` does (tmp + replace).
- [ ] **Step 4: Run the same command.** Expected: 9 tests pass.
- [ ] **Step 5: Commit** `desktop: ScreenDimmer dims the panel after the user's display timeout while work runs`.

### Task 2: Windows ports, the display request, wiring, label

**Files:**
- Create: `desktop/src/main/kotlin/org/tgbackup/desktop/WindowsScreen.kt` (`WmiBrightness : Brightness`, `WindowsIdleClock : IdleClock`)
- Modify: `desktop/src/main/kotlin/org/tgbackup/desktop/SleepGuard.kt` (`PowerRequests` gets `fun idleTick(active: Boolean) {}`; `forCurrentOs` builds the dimmer next to `SleepOverride` with the same `sleepOverride` flag; `WindowsPowerRequests(override, dimmer)`)
- Modify: `desktop/src/main/kotlin/org/tgbackup/desktop/DesktopApp.kt` (a 2 s loop on IO: `power.idleTick(sleepGuard.state == true)`)
- Modify: `desktop/src/main/resources/web/screens/settings.js:242`, `web-tests/settings.spec.mjs:7` (the label)
- Test: `desktop/src/test/kotlin/org/tgbackup/desktop/SleepGuardTest.kt` (only if it pins the request set)

**Interfaces:**
- Consumes: `ScreenDimmer`, `Brightness`, `IdleClock` (Task 1).
- Produces: `PowerRequests.idleTick(active: Boolean)`.

- [ ] **Step 1: `WmiBrightness`.** `read()` runs `powershell -NoProfile -NonInteractive -Command "(Get-CimInstance -Namespace root/WMI -ClassName WmiMonitorBrightness -ErrorAction Stop | Select-Object -First 1).CurrentBrightness"` with a 10 s timeout. Empty output, an error or a timeout means null. `set(level)` runs `Get-CimInstance -Namespace root/WMI -ClassName WmiMonitorBrightnessMethods | Invoke-CimMethod -MethodName WmiSetBrightness -Arguments @{Timeout=0; Brightness=<level>}`. A non-zero exit throws. Spawn it with ProcessBuilder, as `Tailscale.kt` does: stdin from NUL, stderr merged. Java starts children without a console window.
- [ ] **Step 2: `WindowsIdleClock`.** `idleMs()` = `GetTickCount64() − LASTINPUTINFO.dwTime`, compared on the low 32 bits, because dwTime wraps. `displayTimeoutMs()` reads VIDEOIDLE (`SUB_VIDEO` 7516b95f-f776-4464-8c53-06167f40cc99, `VIDEOIDLE` 3c0bc021-c8a8-4e07-a973-6b14cbcb2b7e) from the active scheme, AC or DC by `GetSystemPowerStatus().ACLineStatus`, and multiplies by 1000. Reuse `WindowsPowerSettings`' powrprof binding: add `fun readVideoIdle(ac: Boolean): Int` beside `read()`.
- [ ] **Step 3: `WindowsPowerRequests`.** `keepAwake(true)` also sets `POWER_REQUEST_DISPLAY_REQUIRED` (0), and its execution state becomes `ES_CONTINUOUS or ES_SYSTEM_REQUIRED or ES_DISPLAY_REQUIRED`. `keepAwake(false)` calls `dimmer?.restore()` first, then clears the display request with the other two. `recover()` returns `override.recover()` or'd with `dimmer.recover()`, both always run. `idleTick(active)` = `dimmer?.tick(active)`, on the power thread. Update the class KDoc: it no longer says "never DisplayRequired".
- [ ] **Step 4: DesktopApp.** Add a loop: `while (true) { delay(2_000); runCatching { power.idleTick(sleepGuard.state == true) }.onFailure { powerFailed(it) } }`. The shutdown path already calls `sleepGuard.update(false)`, which now restores.
- [ ] **Step 5: Label.** Replace the text in `settings.js:242` and `settings.spec.mjs:7` with the Global Constraints label.
- [ ] **Step 6: Verify:** `./gradlew :desktop:test :engine:test --offline` (all pass) and `cd web-tests && npx playwright test settings.spec.mjs --trace off` (pass).
- [ ] **Step 7: Commit** `desktop: keep the display on while work runs (Modern Standby sleeps when it turns off), dim it when idle`.

### Task 3: Ship and test on the real PC

- [ ] Push `HEAD:engine-port HEAD:main`, wait for CI green on both (the windows job runs the desktop tests on real Windows), and copy the installer to `/sdcard/Download/TGBackup-Setup.exe`.
- [ ] User test, on AC: start a job, leave the PC alone for longer than the display timeout (1 h), and check from the phone that the app loads. The screen should be dim, not off. Touch the PC: brightness comes back. `powercfg /requests` shows TGBackup.exe under DISPLAY, SYSTEM and EXECUTION.
