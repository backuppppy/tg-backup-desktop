# Source Protection Follows Telegram Live: Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** While a job runs, a change to the source's forwarding protection, in either direction, switches the job between server-side copy and download + upload at once. The 🔒 line on the card follows.

**Architecture:** `AccountClient` sends TDLib's `UpdateChatHasProtectedContent` on to a flow. `resolveChat` returns TDLib's current `Chat`, not a snapshot it cached. `JobRunner` keeps the flag in a small `SourceProtection` holder that a watcher coroutine updates from that flow.

**Tech Stack:** Kotlin/JVM 17, kotlinx.coroutines, tdl-coroutines-jvm 15.0.0 (`dev.g000sha256.tdl.dto.UpdateChatHasProtectedContent`: `chatId: Long`, `hasProtectedContent: Boolean`), JUnit 5.

**Spec:** `docs/superpowers/specs/2026-11-15-protection-and-screen-design.md` (part 1)

## Global Constraints

- Log lines are in Hebrew, in the `[$tag]` style JobRunner already uses.
- The change touches only the regular copy path (`JobRunner`). `TakeoutJobRunner` sends by reference whatever the flag says, so it is left as is.
- No change to the Android app (separate repo).

## Review Focus

- Protection removed while prefetched downloads are in flight: those downloads must not leak files. Each one is cancelled, or its file deleted, once its message is copied without an upload (Task 2, step 3).
- A notice for another chat, or for the target chat, must not change the source's mode (Task 1 test `ignoresOtherChats`).
- A notice that repeats the current value must not log or report again (Task 1 test `sameValueIsNoChange`).
- A job that starts again after the restriction was removed must read the current flag, not the one cached at the first resolve (Task 2, step 1).
- A copy refused for protection after a "removed" notice still falls back to download + upload for that message, and sets the job to protected again (unchanged code path, kept).

---

### Task 1: `SourceProtection` holder (pure, tested)

**Files:**
- Create: `engine/src/main/kotlin/org/tgbackup/app/engine/SourceProtection.kt`
- Test: `engine/src/test/kotlin/org/tgbackup/app/engine/SourceProtectionTest.kt`

**Interfaces:**
- Produces: `class SourceProtection(initial: Boolean)` with `val isProtected: Boolean` (volatile), `fun set(value: Boolean): Boolean` (true when the value changed), and `fun onNotice(chatId: Long, sourceId: Long, value: Boolean): Boolean` (ignores other chats; else `set(value)`).

- [ ] **Step 1: Write the failing tests**: `startsWithInitial` (`SourceProtection(true).isProtected == true`); `removedThenAdded` (`onNotice(5, 5, false)` returns true and sets `isProtected` to false; then `onNotice(5, 5, true)` returns true and sets it to true); `ignoresOtherChats` (`onNotice(6, 5, false)` returns false, and the value stays true); `sameValueIsNoChange` (`set(true)` on true returns false).
- [ ] **Step 2: Run** `./gradlew :engine:test --offline --tests '*SourceProtectionTest*'`. Expected: FAIL (unresolved reference).
- [ ] **Step 3: Implement** with a `@Volatile private var`.
- [ ] **Step 4: Run the same command.** Expected: PASS.
- [ ] **Step 5: Commit** `engine: SourceProtection holder for a source's live protection flag`.

### Task 2: Wire the notice through AccountClient into JobRunner

**Files:**
- Modify: `engine/src/main/kotlin/org/tgbackup/app/tg/AccountClient.kt` (the `start()` update `when`, `resolveChat`)
- Modify: `engine/src/main/kotlin/org/tgbackup/app/engine/JobRunner.kt` (field `protected` at line 86, `run()` at lines 107-121, `deliver` `finally`)

**Interfaces:**
- Consumes: `SourceProtection` (Task 1).
- Produces: `AccountClient.protectionNotices: SharedFlow<ProtectionNotice>` with `data class ProtectionNotice(val chatId: Long, val isProtected: Boolean)` (declared in AccountClient.kt). It is a `MutableSharedFlow(extraBufferCapacity = 64)`, filled with `tryEmit` from the update `when`.

- [ ] **Step 1: `resolveChat`.** On a cache hit, return `client.getChat(cached.id).getOrNull() ?: cached`, and store the fresh one. TDLib keeps its own `Chat` current from updates, so this read is cheap and current.
- [ ] **Step 2: JobRunner.** Replace `private var protected` with `private val protection = SourceProtection(false)`, and read `protection.isProtected` at every place that reads `protected` today (lines 179, 219, 334, 380). The protection-error fallback at line 393 calls `protection.set(true)`. In `run()`, after `sourceInfo`, start `val watch = launch { accounts[0].protectionNotices.collect { n -> ... } }` and wrap `if (fast) runFast(chats) else runOrdered(chats)` in `try { … } finally { watch.cancel() }`. On a change, call `report.sourceInfo(source.id, source.title, n.isProtected)` and log `"[$tag] ערוץ המקור כבר לא מוגן — עובר להעתקה רגילה"` or `"[$tag] ערוץ המקור הפך למוגן — עובר להורדה והעלאה"`.
- [ ] **Step 3: Prefetch cleanup in `deliver`'s `finally`.** If `downloaded == null` and `prefetched != null`, cancel it. If it had already completed with success, `deleteLocal` its file through the owner, the way an upload's download is deleted today.
- [ ] **Step 4: Build and run the engine tests:** `./gradlew :engine:test :desktop:compileKotlin --offline`. Expected: BUILD SUCCESSFUL, all engine tests pass.
- [ ] **Step 5: Commit** `engine: a running job follows the source's protection notices; resolveChat reads the current chat`.

### Task 3: Ship

- [ ] Push `HEAD:engine-port HEAD:main` (fast-forward only), wait for CI to be green on both, and copy the installer to `/sdcard/Download/TGBackup-Setup.exe`.
- [ ] Ask the user to test: a running job on a channel whose admin turns protection off and then on again. Expected: the log line appears, the 🔒 line on the card goes and comes back, and copying switches mode without a restart.
