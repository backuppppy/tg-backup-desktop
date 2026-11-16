# Ordered Turns, Fast Hand-off, Android Protection: Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** In ordered mode the accounts send in turns: the pause after a send belongs to the account that sent, and a hot account rests longer but keeps working. In fast mode a stuck account hands its slice to a free one. The Android app gets the live protection fix. All of it ships in both engines.

**Architecture:** Two pure, unit-tested classes in the engine: `SenderRotation` (whose turn, and how long each account rests) and `SliceQueue` (who works which slice of a fast wave). `deliver` returns a `Delivery` result instead of a Boolean, so the callers can hand a message or a slice to another account. The Android `JobRunner` gets the same changes. The pure classes are copied byte for byte.

**Tech Stack:** Kotlin/JVM 17 (desktop `engine/`), Android Kotlin (`/root/tg-backup-apk/app/src/main/java/org/tgbackup/app/`, branch `takeout-export`), kotlinx.coroutines, tdl-coroutines 15.0.0, kotlin.test.

**Spec:** `docs/superpowers/specs/2026-11-16-ordered-sender-rotation-design.md`

## Global Constraints

- Ordered mode never sends two messages at once. The target order equals the source order.
- One account in ordered mode, and fast mode with short FloodWaits (≤ 60 s): behavior unchanged.
- Hot window: `hotUntil = busyUntil + clamp(floodWaitMs, 300_000, 1_800_000)`. While hot, the account keeps its turns, and its rest after a send is `max(baseMs * 3, 5_000)`.
- Fast-mode hand-off threshold: FloodWait > 60 s, or the account is not connected.
- `Progress` keeps its saved format (`workerLast` stays keyed by slice index).
- Log lines in Hebrew, in the existing `[$tag · ${ac.tag}]` style.
- Git: only additive pushes. Desktop: `HEAD:engine-port HEAD:main`. Android: `takeout-export`, version 2.2.21, pushed after the version change.
- After each new desktop build, send the user the Releases link (the file name carries version and build).

## Review Focus

- Ordered mode: a message whose sender fails after a hand-off must not be sent twice. A `Failed` comes only after the send was refused (`SendOutcome.Failed` without retry-after), never after a send that may have gone through (Task 2, step 3).
- Fast mode: a released slice resumes from `workerLast[slice] + 1`, so the stuck message is retried and none is skipped (Task 4, step 2).
- A restart in the middle of a fast wave with a released slice: it resumes as today, each slice from its `workerLast` (Task 4, step 3).
- Every account resting or busy: ordered mode waits for the first free account and never spins in a tight loop (Task 1 test `allRestingGivesWait`).
- An account that cannot load the source message (not a member) is excluded for that message. When all accounts are excluded, the message counts as failed, not stuck (Task 1 test `allExcludedGivesNone`, Task 2, step 3).

---

### Task 1: `SenderRotation` (pure, tested)

**Files:**
- Create: `engine/src/main/kotlin/org/tgbackup/app/engine/SenderRotation.kt`
- Test: `engine/src/test/kotlin/org/tgbackup/app/engine/SenderRotationTest.kt`

**Interfaces:**
- Produces: `class SenderRotation(size: Int, now: () -> Long = System::currentTimeMillis)` with:
  - `sealed interface Pick { data class Account(val index: Int) : Pick; data class Wait(val ms: Long) : Pick; data object None : Pick }`
  - `@Synchronized fun pick(usable: (Int) -> Boolean = { true }, exclude: Set<Int> = emptySet()): Pick`
  - `@Synchronized fun busy(index: Int, untilMs: Long)`: FloodWait; also sets the hot window.
  - `@Synchronized fun rest(index: Int, untilMs: Long)`: the pause after a send.
  - `@Synchronized fun restMs(index: Int, baseMs: Long): Long`: `max(baseMs * 3, 5_000)` while hot, else `baseMs`.
  - `@Synchronized fun isHot(index: Int): Boolean`
- `pick` algorithm: candidates in round-robin order from the cursor. A candidate is free when it is usable, not excluded, and `max(busyUntil, restUntil) <= now()`. The first free one is picked, hot or not, and the cursor moves past it. With no free candidate, it returns `Wait(min over usable, not excluded of (max(busy, rest) − now))`. With no usable, not-excluded account, it returns `None`.

- [ ] **Step 1: Write the failing tests** (fake clock `var t`):
  - `roundRobin`: size 3, three picks give 0, 1, 2, and a fourth gives 0.
  - `restingSkipped`: `rest(1, t+2000)` → picks 0, 2, then 0 again. At `t+2000`, picks include 1.
  - `busySkippedThenHotStillWorks`: `busy(0, t+10_000)` → 0 is skipped until `t+10_000`. Then 0 gets its turn again (not starved), and `isHot(0)`. `restMs(0, 2000)` is 6000; `restMs(0, 1000)` is 5000; `restMs(0, 0)` is 5000; `restMs(1, 2000)` is 2000.
  - `hotWindowIsWaitClamped`: `busy(0, t+1000)` (a 1 s wait) → hot until `t+1000+300_000`. `busy(1, t+3_600_000)` → hot until `t+3_600_000+1_800_000`. After the window, `restMs(0, 2000)` is 2000.
  - `allRestingGivesWait`: all resting, the earliest until `t+700` → `Wait(700)`.
  - `allExcludedGivesNone`: `exclude = {0, 1, 2}` → `None`; `usable = { false }` → `None`.
  - `singleAccountAlwaysZero`: size 1, picks always 0. A rest gives `Wait`.
- [ ] **Step 2: Run** `./gradlew :engine:test --offline --tests '*SenderRotationTest*'`. Expected: FAIL (unresolved).
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run the same command.** Expected: 7 tests pass.
- [ ] **Step 5: Commit** `engine: SenderRotation — turns, rest, FloodWait and hot accounts`.

### Task 2: Ordered mode sends in turns (desktop `JobRunner`)

**Files:**
- Modify: `engine/src/main/kotlin/org/tgbackup/app/engine/JobRunner.kt` (`deliver`, `runOrdered`, and `rangeWorker`'s call)

**Interfaces:**
- Consumes: `SenderRotation` (Task 1).
- Produces:
  - `sealed interface Delivery { data object Sent; data object Skipped; data class FloodWait(val seconds: Int); data object Failed }` (in JobRunner.kt).
  - `deliver(sender, target, msg, prefetched, floodHandOffAboveSec: Int? = null, failHandOff: Boolean = false, pauseAfter: Boolean = true): Delivery`.

- [ ] **Step 1: `deliver` returns `Delivery`.** Today's `return true` becomes `Sent`. `return false` on a skip becomes `Skipped`. After the last retry it is `Failed`, with `report.failed()` only when `!failHandOff`.
  - A FloodWait with `floodHandOffAboveSec != null && retryAfterSec > floodHandOffAboveSec` returns `FloodWait(retryAfterSec)` without sleeping. Otherwise it waits and retries in place, as today.
  - The random pause after a server-side copy runs only when `pauseAfter`.
  - The download, upload and cleanup paths stay as they are.
- [ ] **Step 2: Fast mode call site.** `rangeWorker` calls `deliver(..., floodHandOffAboveSec = null)` for now; Task 4 changes it. `sent = 1` only on `Sent`. Build: `./gradlew :engine:test --offline`, all pass (no behavior change yet).
- [ ] **Step 3: `runOrdered` with more than one account.** Per wanted message, loop on `rotation.pick(usable = { accounts[it].isReady }, exclude = tried)`:
  - `Account(i)`: the sender's own copy. `accounts[0]` uses `msg`. Any other uses `client.getMessage(msg.chatId, msg.id).getOrNull()`; when that is null, add `i` to `tried` and continue. Then `deliver(accounts[i], chats[i].target.id, own, prefetch (first try only), floodHandOffAboveSec = 0, failHandOff = true, pauseAfter = false)`.
  - `Sent`: `rotation.rest(i, now + rotation.restMs(i, base))`, where `base` is `Random.nextLong(1_400, 3_800)` (or 0 when the source is protected). Then the next message.
  - `Skipped`: the next message.
  - `FloodWait(s)`: `rotation.busy(i, now + (s+1)*1000)`, log, and pick again (same message).
  - `Failed`: add `i` to `tried`, pick again.
  - `Wait(ms)`: phase `"כל החשבונות בהמתנה — ${ms/1000} שנ'"`, `delay(ms)`, pick again.
  - `None`: `report.failed()`, log, the next message.
  - With one account, `runOrdered` calls `deliver` exactly as today, with no rotation.
- [ ] **Step 4: Prefetch with turns.** A prefetched download is passed only to the first try. If the message later goes to another account, that account downloads with its own client (`downloadWith(sender, sender, own)`). The unused prefetch goes to `discard`.
- [ ] **Step 5: Verify** `./gradlew :engine:test :desktop:compileKotlin --offline`. Expected: BUILD SUCCESSFUL, all pass.
- [ ] **Step 6: Commit** `engine: ordered mode sends in turns; the pause is the sender's; hot accounts rest longer`.

### Task 3: `SliceQueue` (pure, tested)

**Files:**
- Create: `engine/src/main/kotlin/org/tgbackup/app/engine/SliceQueue.kt`
- Test: `engine/src/test/kotlin/org/tgbackup/app/engine/SliceQueueTest.kt`

**Interfaces:**
- Produces: `class SliceQueue(count: Int)`. At start, slice `i` is held by its own worker. Methods:
  - `suspend fun release(slice: Int)`: the holder stopped; the slice is queued.
  - `suspend fun done(slice: Int)`
  - `suspend fun next(): Int?`: a queued slice, now held by the caller. It suspends while nothing is queued but some slice is still held. It returns null when every slice is done.
  - `val allDone: Boolean`
  - Thread-safe with a `Mutex` plus a `MutableStateFlow<Int>` version that `next()` waits on.

- [ ] **Step 1: Write the failing tests** (`runBlocking`, `withTimeout`):
  - `allDoneReturnsNull`: count 2, `done(0)`, `done(1)` → `next()` is null.
  - `releasedIsTaken`: count 2, `done(0)`, `release(1)` → `next()` is 1. After `done(1)`, `next()` is null.
  - `waitsWhileHeld`: count 2, `done(0)`. A `next()` launched in the background is still running after 100 ms. Then `release(1)` → it returns 1.
  - `neverTwoHolders`: count 1, `release(0)`. Two concurrent `next()` calls: one gets 0, and the other gets null after `done(0)`.
- [ ] **Step 2: Run** `./gradlew :engine:test --offline --tests '*SliceQueueTest*'`. Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run.** Expected: 4 pass.
- [ ] **Step 5: Commit** `engine: SliceQueue for fast-mode hand-off`.

### Task 4: Fast mode hands a stuck slice over (desktop `JobRunner`)

**Files:**
- Modify: `engine/src/main/kotlin/org/tgbackup/app/engine/JobRunner.kt` (`runFast`, `rangeWorker`)

**Interfaces:**
- Consumes: `SliceQueue` (Task 3), `Delivery` (Task 2).
- Produces: `rangeWorker(ac: AccountClient, i: Int, slice: Int, chats: Pair2, rs: Long, re: Long): SliceEnd`, where `SliceEnd` is `Done` or `Released(untilMs: Long)`.

- [ ] **Step 1: A worker loop per account in `runFast`.** Account `i` starts with slice `i` when it exists. Slices compute as today: `rs = ws + slice*25`, `re = min(rs+24, we)`. When the account is not ready, `queue.release(i)` and the worker ends. Otherwise it runs the slice:
  - `Done` → `queue.done(slice)`.
  - `Released(until)` → `queue.release(slice)`, `delay(until − now)`, then continue.
  - Then `while (true) { val s = queue.next() ?: break; run s }`.
  - `awaitAll` on the worker loops. The wave ends (save `lastId = waveEnd`) only when `queue.allDone`.
- [ ] **Step 2: `rangeWorker`** reads `last = maxOf(rs - 1, workerLast[slice] ?: 0)` and saves `workerLast + (slice to last)`. The map is keyed by slice, as today. Before each message: when `!ac.isReady`, return `Released(now)`. It calls `deliver(..., floodHandOffAboveSec = 60)`:
  - `FloodWait(s)` → return `Released(now + (s+1)*1000)`, with `last` NOT advanced past this message.
  - `Sent`, `Skipped` and `Failed` advance `last` as today.
- [ ] **Step 3: Resume.** A restart mid-wave starts every worker on its own slice, as today. A slice whose `workerLast` already reached `re` is done at once. No format change.
- [ ] **Step 4: Verify** `./gradlew :engine:test :desktop:test --offline` (all pass).
- [ ] **Step 5: Commit** `engine: fast mode — a stuck account hands its slice to a free one`.

### Task 5: Desktop review and ship

- [ ] Run the whole-branch review (fresh reviewer, with the Review Focus above). Fix Critical and Important findings.
- [ ] Push `HEAD:engine-port HEAD:main`, wait for green CI, and send the user the pre-release link (`v0.3.0-build.N`, `TGBackup-Setup-0.3.0-build.N.exe`).

### Task 6: Android: the protection fix and the same engine changes

**Files (in `/root/tg-backup-apk`, branch `takeout-export`):**
- Create: `app/src/main/java/org/tgbackup/app/engine/SourceProtection.kt`, `SenderRotation.kt`, `SliceQueue.kt`, each identical to the desktop file (same package).
- Modify: `app/src/main/java/org/tgbackup/app/tg/AccountClient.kt` (`ProtectionNotice`, `protectionNotices`, the fresh read in `resolveChat`: as desktop commit 4f6ff2d).
- Modify: `app/src/main/java/org/tgbackup/app/engine/JobRunner.kt`: all JobRunner changes from desktop commits 4f6ff2d and 7ea505d, and from Tasks 2 and 4. Keep the Android-only differences: no `report.latest`, no `copiedTotal`.
- Modify: `app/build.gradle.kts` (`appVersionName = "2.2.21"`, `appVersionCode = 2_000_022_021`).

- [ ] **Step 1:** Copy the three pure classes, then `diff` each against the desktop file. Expected: no output.
- [ ] **Step 2:** Port AccountClient and JobRunner, then `diff` the two JobRunners. Only the known Android differences may remain.
- [ ] **Step 3:** Build with what `.github/workflows/build.yml` runs. Expected: BUILD SUCCESSFUL. If the local SDK is missing, CI is the build check.
- [ ] **Step 4:** Commit `v2.2.21: ordered turns, fast hand-off, live source protection`, push `takeout-export`, and wait for CI. Check that the CI release has the APK, and copy the APK to `/sdcard/Download`.

### Task 7: The user's live test

- [ ] An ordered job with two or more accounts: the log shows the accounts in turns, the target keeps the order, and copying is faster than before.
- [ ] A FloodWait on one account: the others continue, and that account sends less often for a while but keeps sending.
- [ ] A fast job where one account gets a long FloodWait: the others finish its slice, and the wave moves on.
