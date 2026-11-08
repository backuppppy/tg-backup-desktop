# Cleanup Round (deferred minors) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close the deferred review minors of plans 3 and 3b that a user can feel or that hide a real race, without changing what the user already approved.

**Architecture:** Four independent tasks: engine run lifecycle (Kotlin), jobs screen + run clock (web), 2FA dialog queue (web), log screen rendering (web). Each is TDD and ends with its own commit.

**Tech Stack:** Kotlin/JVM 17, Ktor, kotlinx.coroutines; plain ES modules, node `--test` unit tests, Playwright e2e (`web-tests/`).

**Spec:** the deferred minors in `.superpowers/sdd/2026-11-07-run-stats/progress.md` and the reviews `task-1-fix1-review.md`, `task-2-fix1-review.md`, `task-3-review.md`, `task-4-review.md` beside it. Ledger for this round: `.superpowers/sdd/2026-11-08-cleanup/progress.md`.

## Global Constraints
- Branch `engine-port`; after each green task push `engine-port` and fast-forward `main` (`git push origin engine-port:main`). Never force, never delete branches.
- `git add` explicit paths only (never `-A`; `.kotlin/` stays untracked).
- Never symlink `desktop/build/resources/main/web` to src.
- UI copy is Hebrew, exactly as written here. Desktop only; the phone layout stays as is.
- Kept as approved: jobs pane share (a third up to 3 jobs, half from 4), columns by pane width (2 from 720 px), jobs header on the right, `➕ הוסף משימה` on top on desktop, card lines order (phase, `הודעה אחרונה שטופלה`, `הועתקו`, `אוחז ב-id`, `⏱ רץ`).
- Out of scope (accepted, not touched): Gradle "spike" task names (CI packaging depends on them), DevServer temp dir, the 200 answer in the 2FA cancel window, the SSE id map until reconnect, `runJob` extraction diff.
- Verify: `node --test web-tests/unit/*.test.mjs`; `cd web-tests && npx playwright test --trace off <spec>` per touched spec (the full suite hits the 15 min global timeout here; run by project/spec); `./gradlew build`; CI on windows green after push.

## Review Focus
1. Delete or reset pressed right after `⏹ עצור`: the old run must not write progress after it was cleared (no orphan progress file, no resurrected `lastId`).
2. Two quick `▶ הפעל` on the same job (or a second job sharing the account) in the moment before the run starts: only one run, the second gets `המשימה כבר פועלת` / the busy-account message.
3. A job whose source's newest messages were deleted (latest < lastId): `מתוך Y` never shows Y below X and the progress file is not rewritten every minute.
4. Export screen asks for the 2FA password while a job's request already waits: the screen's request (the one with a 10-minute limit) is shown first.
5. A long run that logs many lines: the log screen keeps up and the scroll position/selection of old lines is not reset on each new line.

---

### Task 1: Engine run lifecycle and the jobs routes

**Files:**
- Modify: `engine/src/main/kotlin/org/tgbackup/app/engine/JobManager.kt` (isRunning ~150, start/finishRun ~136-180, reporter.latest ~270)
- Modify: `desktop/src/main/kotlin/org/tgbackup/desktop/server/JobRoutes.kt` (delete/reset ~179, ~229), `Commands.kt` (`CommandLocks`)
- Test: `engine/src/test/kotlin/org/tgbackup/app/engine/JobManagerTest.kt`, `desktop/src/test/kotlin/org/tgbackup/desktop/server/JobRoutesTest.kt`

**Interfaces:**
- Produces: `JobManager.isRunning(id)` true from registration (LAZY, not yet started) until stop is asked; false while a stopped run is cancelling (fast stop→start keeps working).
- Produces: `suspend fun JobManager.awaitStopped(id: String, timeoutMs: Long = 10_000): Boolean` — true once no run of `id` (including one still cancelling) is left; false on timeout.
- Produces: `CommandLocks.forget(id: String)` — drops the per-job mutex.

- [ ] **Step 1: Failing tests**
  - `JobManagerTest.aRegisteredRunNotYetStartedCountsAsRunning`: with the `OneThread` hold used by `aStopAndAQuickStartKeepTheNewRun`, call `start(a)` and, before release, `start(a)` again → `"המשימה כבר פועלת"`; a second job on the same account → message starts with `"החשבון "`.
  - `JobManagerTest.aCancellingRunIsAwaited`: start, `stop`, `awaitStopped(id)` returns true only after the runner's `finally` ran (hold it, assert not done, release, assert true).
  - `JobManagerTest.latestIsNeverBelowThePosition`: reporter `position(120)` then `latest(100)` → status `latestId == 120`, and `store.saveProgress` called once for latest (count writes with the test store).
  - `JobManagerTest.aFailingCompletionStillShowsStopped`: a store whose `saveProgress` throws once inside `finishRun` → status `running == false`, `phase == "עצור"`.
  - `JobRoutesTest.deleteRightAfterStopLeavesNoProgress`: running job, POST stop then DELETE at once → 200, then no progress file for the id after the runner ends; `reset` the same → progress `lastId == 0` after the runner ends.
  - `JobRoutesTest.deleteForgetsTheJobLock`: after DELETE, `locks` holds no mutex for the id.
- [ ] **Step 2:** `./gradlew :engine:test :desktop:test` → the new tests FAIL.
- [ ] **Step 3: Implement**
  - `isRunning`: `run != null && !run.isCancelled && !run.isCompleted`.
  - Keep a cancelled run reachable until it completes (e.g. `stopping[id]`) so `awaitStopped` can `join` it under `withTimeoutOrNull`.
  - `finishRun` body in `runCatching` (log the error); setting `running=false, phase="עצור"` happens even when the flush throws.
  - `reporter.latest(id)` saves `maxOf(id, current status lastId)`; `position` keeps raising it as today.
  - Delete and reset routes: `stop` if running, `awaitStopped(id)`; on false → 409 with the existing `MSG_JOB_RUNNING`; then the existing `requireStopped` + clear. Delete calls `locks.forget(id)` after the config change.
- [ ] **Step 4:** `./gradlew build` → green (engine, desktop, export-core).
- [ ] **Step 5: Commit** `engine: a run counts as running from its registration, a cancelling one is awaited before delete/reset, latest never below the position, finishRun cannot leave a job shown running; server: delete/reset wait for the stopped run, the job lock is forgotten`.

### Task 2: Jobs screen, run clock and test hygiene

**Files:**
- Modify: `desktop/src/main/resources/web/lib/store.js` (offsetOf / statuses case), `lib/jobsFormat.js` (drop `positionText`), `lib/layout.js` (`jobsListOrder`), `screens/jobs.js` (links, order comment)
- Test: `web-tests/unit/store.test.mjs` (or the existing store unit file), `unit/jobsFormat.test.mjs`, `unit/layout.test.mjs`, `jobs.spec.mjs`, `split-view.spec.mjs`, `layout.spec.mjs`, `shell.spec.mjs`

**Interfaces:**
- Produces: `export const OFFSET_JITTER_MS = 1000` in `lib/store.js`; a `statuses` event changes `clockOffset` only when `|new - old| > OFFSET_JITTER_MS` (a snapshot always sets it).
- Produces: `jobsListOrder` order on desktop `[header, warning, add, ...cards]`.

- [ ] **Step 1: Failing tests**
  - unit `reduce: a statuses offset within 1000 ms of the current keeps it` (old 0, new 400 → 0; new 1500 → 1500; snapshot 300 → 300).
  - unit `jobsListOrder` desktop with warning → `[header, warning, add, card]`; remove the `positionText` unit tests.
  - `jobs.spec`: the position link has `aria-label` `הודעה 45,210 במקור`; the `הודעה אחרונה שטופלה` link `aria-label` `הודעה 45210 במקור`; the phase link `aria-label` `ההודעה האחרונה ביעד`.
  - `jobs.spec` (window project, 1100 wide, no accounts): `קודם הוסף חשבון…` comes before `➕ הוסף משימה` in DOM order.
  - `split-view.spec`: `resizing within the desktop width keeps the focused card button` — 1920, focus `ערוך` on the first card, viewport 1500, the same node (`isSameNode`) has focus.
  - `split-view.spec`: the listener-count test imports `DESKTOP_QUERY`-equal string from one place and also counts `addListener`; the hardcoded 480/366/420 px become values computed from `paneContent`.
  - Tests that restore a real snapshot (`layout.spec.mjs:19-37`, `split-view.spec.mjs` ~397/~683, `shell.spec`) delete `serverNow` from it, as `fakeStream` does.
- [ ] **Step 2:** `node --test web-tests/unit/*.test.mjs` and the touched specs → new ones FAIL.
- [ ] **Step 3: Implement** the jitter threshold, the order, the three `aria-label`s, delete `positionText`.
- [ ] **Step 4:** unit + `jobs.spec`, `split-view.spec` (wide), `layout.spec`, `shell.spec` → pass.
- [ ] **Step 5: Commit** `web: the run clock's offset moves only past 1 s of jitter; the jobs warning before the add button; link names for screen readers; positionText removed; tests: restored snapshots without serverNow, focus kept on resize within the desktop width, computed widths`.

### Task 3: 2FA dialog queue

**Files:**
- Modify: `desktop/src/main/resources/web/lib/password.js`, `app.js` (password dialog flow ~260-305)
- Test: `web-tests/unit/password.test.mjs`, `shell.spec.mjs`

**Interfaces:**
- Changes: `nextPassword(state, answered)` returns the oldest unanswered `origin: 'screen'` request first, else the oldest unanswered one.
- Produces: in `app.js`, a request whose answer failed (non-409) is queued again but never closes a dialog already open for another request; it comes back after that one is answered or cancelled.

- [ ] **Step 1: Failing tests**
  - unit `nextPassword: a screen request comes before an older job request`; `…the oldest job request when no screen request waits`; existing tests stay.
  - `shell.spec`: `a failed answer does not close the dialog open for the next request` — p1 (job) answered, POST aborted; meanwhile p2 dialog open with typed `abc` → still open, value `abc`; after p2 is sent, p1's dialog opens again.
  - `shell.spec`: `Escape closes only the first of two chained dialogs and the second gets the focus` — two requests, Escape once → cancel POST for the first only, the second dialog's input focused.
- [ ] **Step 2:** unit + `shell.spec` → FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4:** unit + `shell.spec` (window and phone) → pass.
- [ ] **Step 5: Commit** `web: the export screen's 2FA request is shown before a job's; a failed answer waits behind the open dialog instead of replacing it`.

### Task 4: Log screen without full rebuilds

**Files:**
- Modify: `desktop/src/main/resources/web/screens/log.js`
- Test: `web-tests/jobs.spec.mjs`-style faked stream in a new `web-tests/log.spec.mjs` (window project picks it up by `*.spec.mjs`), or extend the existing log tests in `shell.spec.mjs` if they live there.

**Interfaces:**
- Consumes: `state.log` (oldest first, capped at `LOG_MAX = 500` by `lib/store.js`), entries `{time, level, msg}`.
- Produces: `render` prepends only the new lines and drops the ones that fell off the cap; a full rebuild only when the new list is not the old one plus newer lines (cleared, reconnect snapshot).

- [ ] **Step 1: Failing tests** (`log.spec.mjs`, faked stream)
  - `a new log line keeps the existing line nodes` — mark the first `.log-line` node, push one `log` event, the marked node is still connected and the new line is first; `3 שורות` → `4 שורות`.
  - `past 500 lines the oldest drop and the rest stay` — 500 lines, push 1, count 500, the newest first, the marked newest-before node still connected.
  - `נקה empties the list` — after the cleared snapshot/event, 0 lines, `0 שורות`.
- [ ] **Step 2:** → FAIL (first two).
- [ ] **Step 3: Implement** by comparing the new array with the shown one (same leading entries by identity after the dropped count).
- [ ] **Step 4:** `log.spec.mjs` + `shell.spec.mjs` → pass.
- [ ] **Step 5: Commit** `web: the log screen adds new lines and drops the oldest instead of rebuilding 500 lines on every event`.

### Finish
- [ ] Whole-branch review of the round; fix round if needed.
- [ ] Push `engine-port`, fast-forward `main`, CI green on windows; update the ledger and the project memory.
