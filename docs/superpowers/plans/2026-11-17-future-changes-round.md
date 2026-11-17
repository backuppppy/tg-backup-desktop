# Future-Changes Round Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build items 1, 2, 3, 5, 6, 7, 8 of `docs/future-changes.md`: one process, the original long caption first, new content types, slice size from the per-minute limit (with a warning), hot accounts in fast mode, a longer phone-server idle timeout, Ktor logging and a phone-server watchdog.

**Architecture:** Engine changes (`engine/`) are ported to Android by copy-and-adapt, as in the earlier rounds. Desktop-only changes touch `desktop/`. Pure decisions get unit tests; Telegram behavior gets a live run with the two test accounts.

**Tech Stack:** Kotlin/JVM 17, Ktor CIO, SLF4J 2 API (already pulled in by Ktor), kotlinx.coroutines, TDLib (tdl-coroutines 15.0.0), Playwright (web UI), Jetpack Compose (Android `JobsScreen`).

**Spec:** `docs/superpowers/specs/2026-11-17-future-changes-round-design.md`

## Global Constraints

- Warning text, exact: `מומלץ 25 (לכל היותר 30). מספר גבוה יותר עלול לגרום לטלגרם להגביל את החשבון או למחוק אותו.` It shows when the limit is above 30 or 0, never at 25 and never up to 30.
- Slice size: `perMinuteLimit` clamped to 1..100, 0 → 25. A saved wave keeps its `waveChunk` (0 = 25).
- New `MediaType`: `NO_PHOTO` "הכל בלי תמונות", `VIDEO` "רק סרטונים" (video, animation, round video), `FILE` "רק קבצים" (document, audio, voice). Existing values unchanged.
- Hot: the same numbers as ordered mode (`SenderRotation`: hot window clamp(wait, 5..30 min), rest ×3, at least 5 s).
- `connectionIdleTimeoutSeconds = 180` on the phone server only.
- Watchdog: every 30 s, 5 s timeout, restart after 2 failures in a row; any HTTP status line counts as alive.
- Git: additive pushes to `main` only, in both repos. Android ships as 2.2.27. Send the Releases links, and copy the APK to `/storage/emulated/0/Download/TG Backup`.

## Review Focus

- A wave saved before this version (no `waveChunk`) must resume with slices of 25 and skip nothing (Task 2, step 1).
- Changing the limit while a wave is saved must not change that wave's slices (Task 2, step 1).
- The caption retry must never send the media twice: the first refusal sent nothing (Task 4, step 1).
- The watchdog must not restart a server the user just switched off, and must not loop restarts when the port is taken by another program (Task 6, step 3).
- The SLF4J provider must never throw into Ktor, and must mask phones (Task 6, step 2).

---

### Task 1: New content types (engine + desktop UI)

**Files:** `engine/.../data/Models.kt` (MediaType), `engine/.../engine/Copier.kt` (`matches`), `engine/.../engine/TakeoutJobRunner.kt` (`CopyKind.matches`), `desktop/src/main/resources/web/screens/jobs.js` (MEDIA_TYPES); test `engine/src/test/.../engine/MediaTypeTest.kt`.

- [ ] Test first: for each `MediaType`, which kinds match, for both the TDLib contents (built with tdl dto constructors) and `CopyKind`. The cases: `NO_PHOTO` rejects only photos; `VIDEO` takes video, animation and round video only; `FILE` takes document, audio and voice only. Run it and see it fail.
- [ ] Add the enum values with the exact labels, both `matches` functions, and the three entries in the web UI list. Tests green; `./gradlew :engine:test :desktop:test --offline`.
- [ ] Commit `engine: content types — everything but photos, only videos, only files`.

### Task 2: Slice size from the per-minute limit + warning (engine + desktop UI)

**Files:** `Models.kt` (`Progress.waveChunk: Int = 0`), `JobRunner.runFast`, new pure `fun sliceSize(limit: Int): Int` (IdWindow.kt); `jobs.js` (warning under the limit field); tests `IdWindowTest`, `web-tests/jobs.spec.mjs`.

- [ ] Tests: `sliceSize(25)=25`, `(40)=40`, `(0)=25`, `(500)=100`, `(-3)=25`. Playwright: typing 40 or 0 shows the exact warning; 25 or 30 shows none.
- [ ] `runFast`: a new wave saves `waveChunk = sliceSize(job.perMinuteLimit)`. Resume uses `wave.waveChunk.takeIf { it > 0 } ?: 25`. All slice math uses that `chunk`. Wave size = n × chunk.
- [ ] Warning: a `div` under the field, updated on input.
- [ ] Commit.

### Task 3: Hot accounts in fast mode (engine)

**Files:** `JobRunner.runFast` / `rangeWorker`.

- [ ] `runFast` creates `val hot = SenderRotation(n)`, passed to `rangeWorker`. On `Delivery.FloodWait(s)`: `hot.busy(i, now + (s+1)*1000)`. `rangeWorker` calls `deliver(..., pauseAfter = false)`, and after `Sent` does `delay(hot.restMs(i, base))`, where base is 1.4–3.8 s (0 when protected, as today).
- [ ] Engine tests green; commit.

### Task 4: The original long caption first (engine)

**Files:** `Copier.copyContent` (a `keepOriginalCaption: Boolean = false` parameter), `JobRunner.deliver`; pure decision `fun captionRetry(state): CaptionStep` in Copier.kt; test `CaptionSplitTest`.

- [ ] Test the decision: replaced caption refused → `KeepOriginal`; original refused (or upload, or no replacement) → `Split`; refused after split → `GiveUp` (the existing failure path).
- [ ] `deliver`: the first `isCaptionTooLong` on a server-side copy with stripping → retry with the original caption. The second → split as today. Log lines in Hebrew.
- [ ] Commit.

### Task 5: One process with a Hebrew TEMP (desktop)

**Files:** `desktop/.../NativeTmp.kt`; test `NativeTmpTest`.

- [ ] Pure `fun waitForChild(smoke: Boolean): Boolean = smoke`; test it. In `ensureAsciiTmpdir`: `val child = pb.start()`; if `waitForChild(...)`, then `exitProcess(child.waitFor())`, else `exitProcess(0)`. The smoke flag comes from args (`--smoke`).
- [ ] Desktop tests green; commit.

### Task 6: Phone server idle timeout, Ktor log, watchdog (desktop)

**Files:** `RemoteServer.kt` (engine config), new `desktop/.../KtorLog.kt` (SLF4J provider + `object KtorLogSink { var sink: (level, msg, throwable) -> Unit }`), `src/main/resources/META-INF/services/org.slf4j.spi.SLF4JServiceProvider`, new `desktop/.../remote/RemoteWatchdog.kt`, wiring in `RemoteAccessController` / `Main.kt`; tests `KtorLogTest`, `RemoteWatchdogTest`.

- [ ] Step 1: `embeddedServer(CIO, configure = { connectionIdleTimeoutSeconds = 180 }, ...)` for the phone server.
- [ ] Step 2: the provider. Check the SLF4J version on the classpath (`./gradlew :desktop:dependencies`) and implement against it. Loggers forward WARN and ERROR to `KtorLogSink`. DesktopApp sets the sink: `LogBus.warn("[שרת] $msg")`, and ERROR with a throwable → `SentryOnce.exception("ktor|$VERSION|${t::class.java.name}|${msg.take(80)}", t)`. Wrap everything in `runCatching`. Test with a fake sink: WARN and ERROR forwarded, INFO dropped, a throwing sink swallowed.
- [ ] Step 3: `class RemoteWatchdog(probe: suspend () -> Boolean, restart: suspend () -> Unit, report: (String) -> Unit, everyMs = 30_000, failuresToRestart = 2)`, with a `suspend fun check()` driven by a loop. Tests: one failure → no restart; two → one restart + report; a success resets the count. Wiring: it runs only while the controller's state is "running" (never when off, and never while the port is taken). The real probe: a Socket to 127.0.0.1:47800, write `GET /api/health HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n`, then read the first line within 5 s; it is alive when the line starts with `HTTP/`. Restart: the controller's server stop and start.
- [ ] Desktop tests green; commit.

### Task 7: Review, Android port, ship, live test

- [ ] Fresh reviewer on the whole desktop diff (Review Focus above); fix Critical and Important.
- [ ] Push desktop, wait for CI, get the pre-release link.
- [ ] Android (`/root/tg-backup-apk`, `main`): copy the engine changes with the known Android differences (MediaType + Models `waveChunk`, Copier, JobRunner, TakeoutJobRunner via patch, IdWindow). `JobsScreen.kt`: the warning under the limit field (same text, same rule). The new types appear by themselves (`MediaType.entries`). Version 2.2.27; build with the local Gradle 8.14.3; commit, push, CI, release, APK to `Download/TG Backup`.
- [ ] Live (`/root/tg-live`): a fast run with limit 25 and with limit 40, checking slice sizes in the "סבב" log lines; a run with `FILE` or `VIDEO`.
