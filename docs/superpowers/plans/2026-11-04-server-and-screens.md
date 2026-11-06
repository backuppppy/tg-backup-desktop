# TG Backup Desktop — Server and Screens Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The program shows the Android app's five screens: משימות, ייצוא, חשבונות, לוג, הגדרות. They have the same texts, features and dark RTL look, and are served by an internal server on top of `DesktopApp`. The screens open inside the WebView2 window and also work at phone width in a browser.

**Architecture:**
- **Server:** a Ktor server inside the program (`desktop/.../server/`) exposes the engine as a JSON REST API.
  - It has one SSE stream. Each connection starts with a full snapshot, then sends incremental events.
  - The REST handlers repeat the exact rules of the Android screens (save validation, account delete order).
- **Screens:** plain HTML/CSS/JavaScript ES modules, packed as resources in the desktop jar (`desktop/src/main/resources/web/`). There is no build step, no npm runtime dependency and no CDN, because the program must work offline.
- **Helpers:** pure JS helpers (sizes, progress rows, date rules) live in their own modules and are tested with `node --test`. Screens are tested with Playwright against a dev server that runs the real engine.

**Tech Stack:**
- Ktor 3.6.1: `ktor-server-cio`, `-content-negotiation`, `-sse`, `ktor-serialization-kotlinx-json`, `ktor-server-test-host` for tests.
- kotlinx-serialization 1.11.0.
- Vanilla JS (ES2022 modules).
- Node 22 (`node --test`) and Playwright with Chromium for UI tests.

**Spec:** `docs/superpowers/specs/2026-11-03-tg-backup-desktop-design.md`. The feature inventory of the Android screens, with verbatim Hebrew strings, engine calls, theme colours and Android-only behaviours, is at `docs/ui-inventory.md` (Task 1 copies it there from `.superpowers/sdd/ui-inventory.md`). **It is the binding reference for every screen task.**

## Global Constraints

- **Branch:** work on `engine-port` and push to `origin engine-port`. **Never merge to main** (user's decision, 2026-11-04). Dev version stays `tgbVersion=0.1.0`.
- **Texts:** every UI string is copied verbatim from `docs/ui-inventory.md`, Hebrew and the English Telegram-Desktop strings of the export screen alike. No new wording, except where a desktop replacement is named in this plan.
- **Look:** dark only, `<html dir="rtl" lang="he">`.
  - Colours: primary `#5AA9F0`, secondary `#7FD1AE`, background `#0F1720`, surface `#16212D`, surfaceVariant `#1E2C3A`, error `#F07178`.
  - Log colours: SUCCESS `#7FD1AE`, WARN `#FFCB6B`, ERROR `#F07178`, INFO `#B0BEC5`.
  - Bottom navigation order: משימות, ייצוא, חשבונות, לוג, הגדרות. The first item sits on the right.
- **Offline:** no external network resources in the page. No CDN, no web fonts; use the system font stack.
- **Network:** the server listens on `127.0.0.1` only in this plan. Remote access over Tailscale comes in plan 6. Every request except `/api/health` needs the session cookie (Task 1).
- **Secrets:** login codes, 2FA passwords and API hashes never go to the log or to stdout, and API hashes are never sent back to the page. `GET` responses return `apiHash` masked as `"••••"` plus `hasApiHash: true`.
- **Engine:** `engine/` files stay verbatim. All new code goes in `desktop/`. `tools/engine-sync-check.sh /root/tg-backup-apk 6bdd532` must still exit 0.
- **Commits:** the trailer is `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. Pushes touching `.github/workflows/` need the PAT from memory `github_token.md`.

## Review Focus

1. **Another program or web page on the PC calling the local API.** This covers cross-site requests from a browser tab and DNS rebinding. Expected: refused. Owner: Task 1. The tests cover:
   - no cookie → 401;
   - wrong `Host` header → 403;
   - foreign `Origin` on a POST → 403.
2. **The same command from two places at once** (PC window and phone browser, or a double click). Expected:
   - the second start returns `המשימה כבר פועלת`;
   - editing or deleting a running job is refused with `לא ניתן לשנות משימה בזמן שהיא פועלת`;
   - nothing is saved half-way.

   Owner: Task 3.
3. **The connection drops** (PC sleeps, phone loses Wi-Fi, server restarts). Expected:
   - the page shows `מתחבר מחדש…`;
   - it reconnects by itself;
   - it shows the current state from a fresh snapshot.

   Owners: Task 2 (snapshot first on every connection) and Task 5 (Playwright, offline then online).
4. **Long Hebrew/English mixed names** (job names, chat titles, ids with `‎-100`). Expected: no horizontal scroll at 390 px phone width, and long text wraps or is cut with an ellipsis. Owner: Task 10, with a long-names fixture.
5. **A busy log** (500 lines, several per second during a job). Expected:
   - the SSE sends only new entries by `seq`, not the whole list each time;
   - the page keeps at most 500 lines;
   - `נקה` clears for every open page.

   Owners: Task 2 (incremental by seq) and Task 5 (cap).

---

### Task 1: Server foundation — Ktor, session cookie, host/origin checks, static files

**Files:**
- Create: `desktop/src/main/kotlin/org/tgbackup/desktop/server/ApiServer.kt`, `.../server/Security.kt`
- Create: `desktop/src/main/resources/web/index.html` (placeholder: `<title>TG Backup</title>`, `dir="rtl"`)
- Create: `docs/ui-inventory.md` (copied from `.superpowers/sdd/ui-inventory.md`)
- Modify: `desktop/build.gradle.kts`
  - Add the Ktor modules from the Tech Stack.
  - Add `kotlin("plugin.serialization") version "2.4.21"`.
  - Add `kotlinx-serialization-json:1.11.0`.
- Test: `desktop/src/test/kotlin/org/tgbackup/desktop/server/SecurityTest.kt`

**Interfaces:**
- Produces: `class ApiServer(app: DesktopApp, host: String = "127.0.0.1", port: Int = 0)`, with these members:
  - `fun start(): Int`: returns the bound port.
  - `fun stop()`
  - `val sessionToken: String`: 32 random bytes, hex encoded, new on every launch.
  - `fun entryUrl(): String`: `http://127.0.0.1:<port>/?t=<token>`.
- Produces: `fun Application.tgbModule(app: DesktopApp, token: String, allowedHosts: Set<String>)`, so tests can install it with `testApplication`. Routes are added by later tasks in their own files as `fun Route.xxxRoutes(app: DesktopApp)`, installed inside the authenticated block.
- **Security rules (`Security.kt`):**
  - `GET /?t=<token>` with the correct token sets the cookie `tgb_session=<token>; HttpOnly; SameSite=Strict; Path=/`, then redirects to `/`.
  - Every request except `GET /api/health` needs that cookie, or answers 401 with `{"error":"נדרשת התחברות"}`. Static files need it as well.
  - The `Host` header must be in `allowedHosts`, which is `127.0.0.1:<port>` and `localhost:<port>`. Otherwise answer 403.
  - For non-GET requests, an `Origin` header that is present must equal `http://<allowed host>`. Otherwise answer 403.
  - Errors are always JSON `{"error": "<Hebrew text>"}`.
- **Static files:** served from classpath `web/`. `index.html` and `*.js`/`*.css` get `Cache-Control: no-cache` and the correct content types (`text/javascript; charset=utf-8` for modules).

- [ ] **Step 1: Write the failing tests** (`testApplication` with `tgbModule(app = DesktopApp(tempHebrewDir, recordingPower), token = "t1", allowedHosts = setOf("localhost:80"))`):
  - `healthNeedsNoCookie`: `GET /api/health` returns 200 with body `ok`.
  - `noCookieIs401`: `GET /` without a cookie returns 401, and the body contains `נדרשת התחברות`.
  - `tokenSetsCookieAndRedirects`: `GET /?t=t1` returns 302 to `/`, and `Set-Cookie` contains `tgb_session=t1`, `HttpOnly` and `SameSite=Strict`.
  - `wrongTokenIs401`: `GET /?t=bad` returns 401.
  - `wrongHostIs403`: with the cookie and `Host: evil.example`, the request returns 403.
  - `foreignOriginPostIs403`: a POST with the cookie and `Origin: http://evil.example` returns 403.
  - `indexServedWithCookie`: with the cookie, `GET /` returns 200, contains `dir="rtl"`, and has `Cache-Control: no-cache`.
- [ ] **Step 2:** Run `./gradlew :desktop:test --tests '*SecurityTest*'`. Expected: FAIL (unresolved).
- [ ] **Step 3:** Implement, then run the tests. Expected: PASS.
- [ ] **Step 4:** Commit and push. Expected: CI green.

### Task 2: DTOs, snapshot and the SSE event stream

**Files:**
- Create: `desktop/.../server/Dto.kt`, `.../server/Events.kt`, `.../server/StateRoutes.kt`
- Test: `desktop/src/test/kotlin/org/tgbackup/desktop/server/DtoTest.kt`, `.../server/EventsTest.kt`

**Interfaces:**
- Consumes: `DesktopApp` (`store.config`, `jobs.statuses`, `accounts.clients` with each client's `auth` and `lastError`, `exports.prefs`/`password`/`running`, the running export's `controller.state`, `log.entries`). The engine types are listed in `docs/ui-inventory.md` §4.
- Produces `@Serializable` DTOs:
  - `AccountDto(id, name, apiId, hasApiHash, phone)`
  - `JobDto`: every `Job` field.
  - `JobStatusDto`: every `JobStatus` field, plus `lastLink` and `lastTargetLink`.
  - `AuthDto(type: "Starting"|"NeedPhone"|"NeedCode"|"NeedPassword"|"Ready"|"Unsupported"|"Closed", label, hint?, display?, why?)`
  - `AccountStateDto(id, auth, lastError?)`. An account without a client gets `Unsupported("חסרים API ID / Hash")`.
  - `ExportPrefsDto`: the `ExportPrefs` fields plus `defaultPath`.
  - `ExportStateDto(type, …)`: one shape per `ExportState` variant. `Processing` carries a field-by-field snapshot of `ProcessingState`, with `step` and `entityType` as enum names.
  - `RunningExportDto(accountId, title, state)`
  - `PasswordRequestDto(id: String, hint)`: `id` is a random id per request.
  - `LogEntryDto(seq, time, level, msg)`
  - `SnapshotDto(version, tdlibVersion, accounts, accountStates, jobs, statuses, exportPrefs, runningExport?, password?, log: List<LogEntryDto>)`
- Produces: `GET /api/state` → `SnapshotDto`.
- Produces: `GET /api/events` (SSE). The first event is `snapshot` (a `SnapshotDto`). After it come:
  - `config` (accounts and jobs)
  - `statuses`
  - `accountStates`
  - `exportPrefs`
  - `export` (`RunningExportDto?`)
  - `password` (`PasswordRequestDto?`)
  - `log`: `{entries: [only entries with seq > last sent], cleared: Boolean}`. Clearing sends `cleared: true` with the full current list.
  - `chatsProgress` (Task 4)
  - a comment ping every 15 s.
- `ProcessingState` is a mutable object, so while `Processing` the export event is sampled at most every 300 ms. On any other state change it is sent at once.

- [ ] **Step 1: Write the failing tests.**
  - `DtoTest`:
    - each `AuthUi` variant maps to its `type`/`label` (`NeedPassword("רמז")` gives `hint = "רמז"`);
    - `JobStatusDto.lastLink` equals the engine getter;
    - `AccountDto` never contains the API hash;
    - `ExportStateDto` for `ApiError(400,"TAKEOUT_INVALID")` gives `type = "ApiError"`, `code = 400`, `errorType = "TAKEOUT_INVALID"`.
  - `EventsTest` (`testApplication`, with the cookie):
    - `firstEventIsSnapshot`;
    - `configChangeSendsConfigEvent`: `store.updateConfig` adds a job, and a `config` event follows that contains it;
    - `logIsIncremental`: after the snapshot, `log.info("a")` and then `log.info("b")` give two `log` events with one entry each, and their seq values increase;
    - `logClearSendsCleared`.
- [ ] **Step 2:** Run the tests. Expected: FAIL.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** Run the tests. Expected: PASS.
- [ ] **Step 5:** Commit.

### Task 3: Jobs and accounts commands

**Files:**
- Create: `desktop/.../server/JobRoutes.kt`, `.../server/AccountRoutes.kt`
- Test: `desktop/src/test/kotlin/org/tgbackup/desktop/server/JobRoutesTest.kt`, `.../server/AccountRoutesTest.kt`

**Interfaces:**
- **Responses:** every command returns `{"ok":true}`, or a 4xx with `{"error":"<Hebrew>"}`. The page shows that text as a toast, the same as the Android `toast(it)`.
- **Jobs:**
  - `POST /api/jobs`: create, with a new id when it is blank. `PUT /api/jobs/{id}`: replace.
    - Body: a `JobDto` without `id` on create.
    - Validation mirrors the Android save-enable rule: blank source or target, or no accounts, gives 400 with `חסר מקור או יעד` or `לא נבחרו חשבונות למשימה`.
    - An account already used by another job gives 400 `החשבון ${names} כבר עובד במשימה אחרת`.
    - A running job gives 409 with `לא ניתן לשנות משימה בזמן שהיא פועלת`.
  - `DELETE /api/jobs/{id}`: 409 if running. Otherwise `jobs.forget(id)` and then remove the job from config.
  - `POST /api/jobs/{id}/start`: returns 409 with the engine's error text when `jobs.start` returns one.
  - `POST /api/jobs/{id}/stop`, `/pause`, `/resume`.
  - `POST /api/jobs/{id}/reset`: 409 if running.
  - `POST /api/jobs/all/start`: returns `{"errors":[…]}`, the same list as `startAll()`.
  - `POST /api/jobs/all/pause`, `/resume`, `/stop`.
- **Accounts:**
  - `POST /api/accounts` and `PUT /api/accounts/{id}`.
    - Validation mirrors the Android dialog: `apiId` must be an Int, `apiHash.length >= 16` and `phone` must not be blank. Otherwise 400 `פרטי החשבון לא תקינים`.
    - On PUT, an `apiHash` of `"••••"` keeps the stored hash.
  - `DELETE /api/accounts/{id}`, in the Android order:
    1. stop the running jobs that use the account;
    2. remove it from the accounts and from every `job.accountIds`;
    3. call `accounts.purge(id)`;
    4. **and** call `exports.forget(id)`. This fixes the gap noted in the inventory: Android left the export session on disk.
  - `POST /api/accounts/{id}/phone`, `/code` (`{code}`), `/password` (`{password}`), `/logout`. These return 400 `שגיאה: <engine error>` when the client returns an error text. With no client: 400 `חסרים API ID / Hash`.
- All config changes go through `store.updateConfig`, which runs under the store lock. The "running" check and the write happen inside one synchronized block per job id. Two parallel requests on the same id therefore can't both pass.

- [ ] **Step 1: Write the failing tests.** Each test uses a temp Hebrew dir and dummy accounts `apiId=1`, `apiHash="0"*32`, so no network login happens.
  - `createJobValidates`: an empty source gives 400 `חסר מקור או יעד`. With no accounts: 400 `לא נבחרו חשבונות למשימה`.
  - `createJobPersists`: after a 200, the job appears in `store.config.value.jobs`, with `perMinuteLimit = 25` when it is omitted.
  - `secondStartSaysAlreadyRunning`: two parallel `start` calls for the same job give one 200 and one 409 `המשימה כבר פועלת`. Then stop the job.
  - `editRunningJobIs409` and `deleteRunningJobIs409`.
  - `accountGetHidesHash`: `GET /api/state` does not contain the raw hash.
  - `putWithMaskedHashKeepsHash`.
  - `deleteAccountRemovesFromJobsAndForgetsExport`: create `export/<id>/session.properties`, delete the account, and the dir is gone.
  - `badAccountIs400`: `apiHash` of length 10 gives 400 `פרטי החשבון לא תקינים`.
- [ ] **Step 2:** Run the tests. Expected: FAIL.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** Run the tests. Expected: PASS.
- [ ] **Step 5:** Commit.

### Task 4: Export commands

**Files:**
- Create: `desktop/.../server/ExportRoutes.kt`, `.../server/ChatHandles.kt`
- Test: `desktop/src/test/kotlin/org/tgbackup/desktop/server/ExportRoutesTest.kt`

**Interfaces:**
- **Account session:**
  - `GET /api/export/{accountId}/session` returns `{loggedIn: Boolean}`, from `isLoggedIn`. A failure counts as false, as in Android.
  - `POST /api/export/{accountId}/login`. On failure: 400 `ההתחברות נכשלה: <msg>`.
  - `POST /api/export/{accountId}/check` returns `{ready: Boolean}`. On failure: 400 `בדיקה נכשלה: <msg>`.
- **Chats and topics:**
  - `POST /api/export/{accountId}/chats` loads the chats. Progress goes out as SSE `chatsProgress {accountId, count}`. The response is `[{peerId, title, username, type, isForum, archived}]`. On failure: 400 `טעינת הצ'אטים נכשלה: <msg>`.
  - `ChatHandles` keeps the latest `List<ChatEntry>` per account, keyed by `peerId`, because `MTPInputPeer` can't go to JSON.
  - `POST /api/export/{accountId}/topics` with `{peerId}` returns `[{id, title}]`. On failure: 400 `טעינת הנושאים נכשלה: <msg>`.
- **Prefs:** `PATCH /api/export/prefs` takes `{mediaTypes?, sizeLimit?, format?, path?}`.
  - `format` must be one of `Html`, `Json`, `HtmlAndJson`.
  - `sizeLimit` must be in 1 MiB to 4000 MiB.
  - Otherwise 400 `ערך לא תקין`.
- **Running an export:**
  - `POST /api/export/start` with `{accountId, peerId, topicId?, from, till}`.
    - It returns 409 `ייצוא כבר פועל` when one is running.
    - An unknown `peerId` gives 400 `יש לטעון את הצ'אטים מחדש`.
  - `POST /api/export/skip`, `/stop`, `/dismiss`.
- **Opening the result:** `POST /api/export/open` opens `Finished.path` on the PC with `java.awt.Desktop.open`, run on a background thread. When `Desktop` is unsupported: 400 `לא ניתן לפתוח במחשב הזה`. This is the desktop equivalent of `Show my data`.
- **Password:** `POST /api/export/password` with `{id, password: String?}` completes the pending `PasswordRequest`. It is ignored with 409 `הבקשה כבר טופלה` when `id` doesn't match the current request. `null` cancels.

- [ ] **Step 1: Write the failing tests.**
  - `prefsPatchPersists`: format `Json` and sizeLimit 50 MiB are saved, and a new `ExportManager` on the same dir reads them back.
  - `badFormatIs400`.
  - `startWithUnknownPeerIs400`.
  - `sessionOfAccountWithoutClientIsFalse`.
  - `passwordWithWrongIdIs409`.
- [ ] **Step 2:** Run the tests. Expected: FAIL.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** Run the tests. Expected: PASS.
- [ ] **Step 5:** Commit.

### Task 5: Web shell, dev server, Log and Settings screens, UI test setup

**Files:**
- Create: `desktop/src/main/resources/web/index.html`, `app.css`, `app.js`, `lib/api.js`, `lib/store.js`, `lib/ui.js`, `screens/log.js`, `screens/settings.js`
- Create: `desktop/src/main/kotlin/org/tgbackup/desktop/DevServer.kt` plus the Gradle task `devServer`
- Create: `web-tests/package.json`, `web-tests/playwright.config.mjs`, `web-tests/shell.spec.mjs`, `web-tests/unit/*.test.mjs`
- Modify: `.github/workflows/build.yml`: the `linux` job adds Node 22, `npm ci` in `web-tests`, `npx playwright install --with-deps chromium`, `node --test web-tests/unit`, and the Playwright run against `devServer`.

**Interfaces:**
- **`DevServer` main:**
  - `DesktopApp` on `TGB_DEV_DATA` (default: a fresh temp dir) and `ApiServer` on `127.0.0.1:<TGB_DEV_PORT, default 8765>`.
  - It prints `DEV_URL <entryUrl>` and runs until killed.
  - If `TGB_DEV_FIXTURE=long-names` is set, it seeds the config with long Hebrew/English names (Task 10).
- **`lib/api.js`:** `api(method, path, body?) → Promise<object>`. A JSON error rejects with `Error(error)`.
- **`lib/store.js`:** `connect(onState)`. It opens `EventSource('/api/events')`, applies `snapshot` and then the incremental events to one state object, and caps `log` at 500 entries.
  - When the connection drops, it sets `state.connected = false`, and the shell shows a banner `מתחבר מחדש…`.
  - EventSource reconnects by itself. The next `snapshot` replaces the state.
- **`lib/ui.js`:**
  - `toast(msg)`: one at a time, about 4 s, at the bottom above the nav. It is the snackbar equivalent.
  - `confirmDialog({title, text}) → Promise<boolean>`, with buttons `אישור`/`ביטול`.
  - `formDialog`, a generic modal.
  - `h(tag, attrs, ...children)`, a tiny DOM helper.
- **Shell:**
  - Top bar `TG Backup  v<version>`, with the two spaces before `v`.
  - Bottom nav with the five tabs in the Global Constraints order and their Hebrew labels.
  - The active tab is kept in `location.hash` (`#jobs`, `#export`, `#accounts`, `#log`, `#settings`; default `#jobs`).
  - The global `ExportPasswordDialog` is shown whenever `state.password` is non-null, with the strings from inventory §2c.
- **Log screen (inventory §2d):**
  - Newest entries first, in monospace, coloured per level.
  - `נקה` calls `DELETE /api/log`, which is added in this task to StateRoutes and calls `log.clear()`.
  - The line count shows as `${n} שורות`.
- **Settings screen (inventory §2e, desktop version):**
  - Card `עבודה ברקע`, with the text: `בזמן שמשימה רצה המחשב לא ייכנס למצב שינה. סגירת החלון לא עוצרת את המשימות. כדי שהמשימות יתקדמו, המחשב צריך להיות דלוק.` The last sentence comes from the spec's "limits" section.
  - Card `אודות`: `גרסה <version> · TDLib <tdlibVersion>`, and links `קבוצת טלגרם` → `https://t.me/backuppppy` and `GitHub` → `https://github.com/backuppppy/tg-backup-desktop`.
  - The autostart switch is not part of this plan (plan 5).
- **Playwright:**
  - Use the Chromium already in `~/.cache/ms-playwright` locally. Pin the `playwright` version in `web-tests/package.json` to the one whose browser revision is there (`chromium-1234`), and don't download browsers locally.
  - Viewports are `1100x750` (window) and `390x844` (phone).

- [ ] **Step 1: Write the failing unit tests and specs.**
  - `unit/store.test.mjs` (pure reducer):
    - a snapshot and then a `log` event with 2 entries gives 2 more;
    - 600 entries are capped to the newest 500;
    - `cleared` replaces the list.
  - `shell.spec.mjs` (both viewports):
    - the five tab labels appear in order from right to left;
    - `#log` shows the log screen;
    - `document.documentElement.dir === "rtl"`;
    - no horizontal scroll (`scrollWidth <= innerWidth`);
    - going offline and back online with `context.setOffline` shows `מתחבר מחדש…`, and after reconnect the banner disappears;
    - Settings shows the version from `/api/state`.
- [ ] **Step 2:** Run `node --test web-tests/unit` and `npx playwright test` (with `devServer` running). Expected: FAIL.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** Run both. Expected: PASS.
- [ ] **Step 5:** Commit and push using the PAT, since this task touches the workflow. Expected: CI green, with the Playwright step in the `linux` job.

### Task 6: Jobs screen

**Files:**
- Create: `desktop/src/main/resources/web/screens/jobs.js`
- Test: `web-tests/jobs.spec.mjs`

**Interfaces:**
- Consumes: `api`, `store`, `ui` (Task 5), and the job routes (Task 3).
- This task implements inventory §2a exactly:
  - the header summary and the four all-jobs buttons with their enable rules;
  - the no-accounts warning;
  - the cards: icon, source ← target line, details line, takeout/protected line, phase with the clickable `lastTargetLink`, counters, `lastLink`, and the action row with its enable rules;
  - `➕ הוסף משימה`.
- `JobDialog` has all 11 fields, defaults and rules from §2a:
  - the mode radios show only when 2 or more accounts are selected;
  - an empty per-minute limit saves as `25`;
  - taken accounts are disabled with ` (משויך למשימה אחרת)`;
  - `שמור` is enabled only by the Android rule.
- The delete and reset dialogs use the exact titles and texts.
- Links open with `target="_blank" rel="noopener"`. In WebView2 this opens the default browser; Task 9 handles it with the window's new-window event.

- [ ] **Step 1: Write the failing specs.** The dev server is seeded through the API with one dummy account.
  - `addJobFlow`: `שמור` is disabled until source, target and an account are set. After saving, a card shows the name and the line `וידאו + קבצים · מסודר · חשבונות: <name>`.
  - `modeHiddenWithOneAccount`: with two accounts checked, the mode radios appear.
  - `startShowsRunningCard`: `▶ הפעל` turns into `⏹ עצור` and the card background is surfaceVariant. `ערוך` is disabled. `⏹ עצור` returns the card to its idle state.
  - `deleteNeedsConfirm`: the dialog title is `למחוק את המשימה <name>?`, and `ביטול` keeps the card.
  - `startErrorToast`: start a job whose account is busy in another running job, and the toast shows `החשבון … כבר עובד במשימה אחרת`.
- [ ] **Step 2:** Run the specs. Expected: FAIL.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** Run the specs. Expected: PASS.
- [ ] **Step 5:** Commit.

### Task 7: Accounts screen

**Files:**
- Create: `desktop/src/main/resources/web/screens/accounts.js`
- Test: `web-tests/accounts.spec.mjs`

**Interfaces:**
- This task implements inventory §2b exactly:
  - the top note;
  - `AccountCard`: name/phone, the regular-session line per `AuthDto`, `lastError`, and the export-session line with the `availableAt` countdown format;
  - the login controls per auth state, sharing one input field that is cleared after success, and an error toast `שגיאה: <err>`;
  - the buttons `ערוך`, `התנתק` (only in Ready/NeedCode/NeedPassword) and `מחק` (with the confirm texts);
  - the export-session buttons `חבר סשן ייצוא` and `בדוק סשן ייצוא`, with their toasts.
- `AccountDialog`:
  - the fields `שם`, `API ID` (digits only), `API Hash` and `טלפון (+972…)`;
  - `שמור` follows the Android rule;
  - when editing, the hash field shows the masked value and keeps it unless the user changes it.

- [ ] **Step 1: Write the failing specs.**
  - `addAccountValidation`: `שמור` is disabled with a 10-character hash and enabled with 32 characters.
  - `dummyAccountShowsDisconnected`: after saving with `apiId=1`, the card reaches `📱 סשן רגיל: מנותק` within 30 s, and `התחבר (שלח קוד)` is visible.
  - `editKeepsHash`: edit the name only, then `GET /api/state` still has `hasApiHash: true`, and login still reaches NeedPhone.
  - `deleteConfirm`: the dialog title is `למחוק את החשבון <name>?`. After `אישור` the card is gone.
- [ ] **Step 2:** Run the specs. Expected: FAIL.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** Run the specs. Expected: PASS.
- [ ] **Step 5:** Commit.

### Task 8: Export screen

**Files:**
- Create: `desktop/src/main/resources/web/screens/export.js`, `desktop/src/main/resources/web/lib/exportFormat.js`
- Test: `web-tests/unit/exportFormat.test.mjs`, `web-tests/export.spec.mjs`

**Interfaces:**
- `lib/exportFormat.js` holds pure exports ported from ExportScreen.kt and export-core, with the exact formulas from inventory §2c:
  - `sizeLimitByIndex(i)`, `indexBySizeLimit(bytes)`
  - `formatDownload(ready, total)`, `formatSizeText(bytes)`
  - `progressRows(processing)`, which returns `[{label, info, progress}]`
  - `clampFrom(from, till)` and `clampTill(from, till)`, the 600 s gap rules
  - `takeoutDelayText(seconds, now)`
- `screens/export.js` implements §2c exactly:
  - **Setup:** the intro card and account radios; the session card with its 3 states and the `availableAt` warning; the chat picker (`טען צ'אטים`/`רענן`, live `טוען צ'אטים… N` from `chatsProgress`, search, cards with type labels); the options card (topics, the 7 media checkboxes, size slider 0..99, format, `Download path` with the default as placeholder, From/To with `<input type=date>`/`<input type=time>` and `Reset`); `Export`.
  - **ProgressPanel:** every `ExportStateDto` variant with the exact strings, `Skip this file`, `Stop` with the confirm text, `Show my data` (calls `/api/export/open`) and `סגור`.
  - The Android storage-permission row is dropped; on desktop Export is always enabled.

- [ ] **Step 1: Write the failing tests.**
  - `exportFormat.test.mjs`:
    - `sizeLimitByIndex(0) === 1 MiB`, `(9) === 10 MiB`, `(29) === 50 MiB`, `(99) === 4000 MiB`;
    - `indexBySizeLimit(8 MiB) === 7`;
    - `formatDownload(1572864, 3145728) === "1.5 / 3.0 MB"`, `formatDownload(2048, 4096) === "2 / 4 KB"`, `formatDownload(10, 20) === "10 / 20 B"`;
    - `progressRows` for a `Dialogs` step with `entityCount = 3`, `entityIndex = 1`, `itemIndex = 5`, `itemCount = 10`: rows `Chats` (info `2 / 3`) and `<name>` (`5 / 10`);
    - with `bytesCount > 0`, a third row;
    - `clampTill(1000, 900) === 1600`;
    - `takeoutDelayText(1800, …)` contains `less than an hour`.
  - `export.spec.mjs`:
    - with a dummy account the setup shows the account radio with ` (לא מחובר)`;
    - choosing it shows the session card;
    - a progress panel fixture: the dev server's test-only route `POST /api/dev/export-state` publishes an `export` SSE event with the given `RunningExportDto` directly on the event stream, bypassing ExportManager. The route exists only in `DevServer` and only when `TGB_DEV_FIXTURE` is set; `ApiServer` itself never has it. The test injects each `ExportStateDto` variant and checks its exact strings.
- [ ] **Step 2:** Run the tests. Expected: FAIL.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** Run the tests. Expected: PASS.
- [ ] **Step 5:** Commit.

### Task 9: The real program — `TGBackup.exe` opens the screens

**Files:**
- Create: `desktop/src/main/kotlin/org/tgbackup/desktop/AppWindow.kt`. It is the WebView2 window moved out of `spike/WebView2Window.kt`, with the same natives loading, profile dir, `WindowFit` centring and title `TG Backup`.
- Modify:
  - `desktop/src/main/kotlin/org/tgbackup/desktop/Main.kt`
  - `desktop/build.gradle.kts`: the packaged main class becomes `org.tgbackup.desktop.MainKt`, and `--smoke` stays supported.
  - `.github/workflows/build.yml`: the `windows-package` smoke asserts the real UI.
- Delete: `spike/WebView2Window.kt`, `spike/LocalPageServer.kt`, `spike/SpikeSmoke.kt` and their tests, once nothing uses them. `TdlibSmoke` stays.

**Interfaces:**
- **`main(args)`:**
  1. `NativeTmp.ensureAsciiTmpdir("org.tgbackup.desktop.MainKt", args)`.
  2. `DesktopApp(AppDirs.dataDir()).start()`.
  3. `ApiServer(app).start()`.
  4. `AppWindow.open(server.entryUrl())`.
  5. When the window closes, run `app.close()` and `server.stop()` on a background thread, then exit. In this plan closing the window ends the program; the tray, where X hides the window, comes in plan 5.
- **Window behaviour:**
  - Links with `target=_blank`, and any navigation off `127.0.0.1:<port>`, open in the default browser through `Desktop.browse` and never inside the window. Use the webview library's navigation hook if it has one. Otherwise intercept clicks in an injected init script that calls a bound `tgbOpenExternal(url)`.
- **`--smoke` contract:**
  - wait for the real UI's `load` event, check that the page has `#app` and the five tab labels, and print `PAGE_LOADED`;
  - print `API_OK` after `GET /api/health`;
  - keep `WINDOW_INSIDE yes` and `NATIVE_TMP_RELAUNCH`;
  - exit 0.

- [ ] **Step 1:** Implement, then run `./gradlew :desktop:test` and `npx playwright test`. Expected: PASS.
- [ ] **Step 2:** Update the CI smoke assertions to `PAGE_LOADED`, `API_OK`, `WINDOW_INSIDE yes` and `NATIVE_TMP_RELAUNCH`. Keep the screenshot artifact, which now shows the real Jobs screen. Push with the PAT. Expected: CI green.
- [ ] **Step 3:** Download the screenshot artifact and look at it. It must be the dark RTL Jobs screen with the bottom nav. Commit.

### Task 10a: Desktop layout (added 2026-11-05 at the user's request)

The user: the screens must also be comfortable on a PC screen, not only phone-like. The phone layout (under 900 px) stays exactly as it is.

**Files:**
- Modify: `desktop/src/main/resources/web/app.css`, `index.html`, `app.js`, `screens/*.js` (only where the layout needs it)
- Test: `web-tests/desktop-layout.spec.mjs`

**Interfaces:**
- `≥ 900px` wide:
  - A **navigation rail** on the right side (RTL start) replaces the bottom bar. It is about 96 px wide, with the same five icons and labels and the same order from top to bottom. It is fixed while the content scrolls.
  - The top bar stays.
- The content area has `max-width: 1200px` and is centred.
- **Cards and lists:**
  - Job cards and account cards sit in a responsive grid: 2 columns from 900 px and 3 from 1500 px, using `grid-template-columns: repeat(auto-fill, minmax(420px, 1fr))`.
  - The add buttons (`➕ הוסף משימה` / `➕ הוסף חשבון`) fit the grid and are not full-window width.
  - The all-jobs header row stays on one line.
- **Export screen:** two columns at ≥ 1100 px.
  - The setup or chat picker goes on the right.
  - The options card goes on the left.
  - The progress panel stays a single centred column of at most 720 px.
- **Log screen:** full content width, monospace.
- **Settings screen:** the cards have a max width of 720 px.
- **Dialogs:** centred, `width: min(560px, 92vw)`, with the action buttons kept visible while the fields scroll.
- **Pointer and keyboard:**
  - Buttons, cards with actions, links and nav items get `:hover` and `:focus-visible` styles (a visible ring in primary colour).
  - Keyboard: Tab reaches every control in order. Enter submits a dialog when `שמור` or `אישור` is enabled, and Esc cancels it.
  - The cursor is `pointer` on clickable items.
- **Sizes:** text sizes and click targets stay at least as large as on the phone (body 15–16 px, controls at least 40 px tall).

- [ ] **Step 1: Write the failing spec** `desktop-layout.spec.mjs`, at viewports `1100x750` and `1920x1040`, using the same seed data as the jobs and accounts specs:
  - The nav rail is visible on the right: its bounding box `x + width` is close to the viewport width, and it is taller than it is wide. The bottom bar is not visible.
  - With 3 jobs seeded, at least 2 cards share the same `top` (grid), and no card is wider than 700 px.
  - The content width is at most 1200 px.
  - A dialog is no wider than 560 px and is centred within 10 px.
  - Hover over `▶ הפעל` changes its background, and Tab focus shows an outline.
  - Esc closes the JobDialog, and Enter saves it when `שמור` is enabled.
  - At `390x844` the bottom bar is still there and the rail is not, so the phone layout is unchanged.
- [ ] **Step 2:** Run the spec. Expected: FAIL.
- [ ] **Step 3:** Implement with CSS media queries first. Change JS only where the structure needs it, e.g. a wrapper element for the grid.
- [ ] **Step 4:** Run the whole Playwright suite. Expected: PASS, and the existing phone specs are unchanged.
- [ ] **Step 5:** Save screenshots of every tab at `1100x750` and of the Jobs tab at `1920x1040` (deviceScaleFactor 2) to the workspace dir. Commit and push. Expected: CI green. The Windows CI screenshot then shows the rail.

### Task 10b: Log beside every screen on a wide PC screen (added 2026-11-06 at the user's request)

The user wants the log visible next to משימות, ייצוא, חשבונות and הגדרות on a full PC screen, because there is a lot of free space. The phone layout (under 900 px) stays unchanged.

**Files:**
- Create: `desktop/src/main/resources/web/screens/logPanel.js`. It reuses the Log screen's rendering: extract the shared entry list from `screens/log.js` and don't duplicate it.
- Modify: `app.css`, `app.js`, `index.html`, `screens/log.js`
- Test: `web-tests/log-panel.spec.mjs`

**Interfaces:**
- **From 1200 px wide:** the layout is the rail on the right, then the current screen, then the **log panel** on the left (RTL end).
  - The panel is `width: clamp(360px, 38vw, 640px)` and has its own vertical scroll.
  - It is shown on משימות, ייצוא, חשבונות and הגדרות, and hidden on לוג, which keeps the full-width log.
  - Its contents match the Log screen: newest first, monospace, level colours, `נקה` (the same `DELETE /api/log`, clearing every open page) and `${n} שורות`.
  - With the panel shown, the export screen's two columns collapse to one.
- **From 900 to 1199 px:** no panel by default. A toggle button `הצג לוג` / `הסתר לוג` in the top bar opens the same panel as an overlay drawer on the left that does not cover the rail.
  - The choice is kept in `localStorage` (`tgb.logPanel`), wrapped in try/catch.
  - From 1200 px the panel is always shown and the toggle is hidden.
- **Below 900 px:** nothing changes. There is no panel and no toggle.
- **Performance:** the panel and the Log tab share one rendered list per state update, capped at 500. Appending a new entry must not rebuild the 500 existing nodes: add only the new entries at the top, and rebuild only on `cleared`.

- [ ] **Step 1: Write the failing spec** `log-panel.spec.mjs`:
  - At 1920x1040 and 1300x800 on each of the four tabs: the panel is visible, its box is left of the screen content (`panel.x + panel.width <= content.x + 2`), and it shows a log line seeded through the API (for example by creating a job and starting it, or with a server-side `log.info` from DevServer's fixture).
  - On `#log` the panel is hidden and the full Log screen is shown.
  - `נקה` in the panel empties both the panel and the Log tab.
  - At 1100x750 the panel is hidden. `הצג לוג` opens it, the setting survives a reload, and `הסתר לוג` closes it.
  - At 390x844 there is no panel and no toggle.
  - Append performance: push 50 log events and assert the first existing `.log-line` element is the same DOM node before and after (it was not rebuilt).
- [ ] **Step 2:** Run the spec. Expected: FAIL.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** Run the whole Playwright suite. Expected: PASS.
- [ ] **Step 5:** Save screenshots at 1920x1040 of all four tabs with the panel, plus the 1100x750 drawer open. Commit and push. Expected: CI green. Download the Windows CI screenshot.

### Task 10: Phone width, long names, and the results

**Files:**
- Create: `web-tests/layout.spec.mjs`, `docs/screens/*.png`
- Modify: `DevServer.kt` (the `long-names` fixture), `docs/PORTING.md` (the "Screens" section)

- [ ] **Step 1: Write the failing spec.** `layout.spec.mjs`, on every tab at both viewports with `TGB_DEV_FIXTURE=long-names`:
  - no horizontal scroll;
  - no element wider than the viewport;
  - a job name of 120 characters with mixed Hebrew/English and `‎-100…` ids wraps or ellipsizes inside its card;
  - the bottom nav stays visible.
- [ ] **Step 2:** Run it. Expected: FAIL where the layout breaks. Fix the CSS, then run again. Expected: PASS.
- [ ] **Step 3:** Save one screenshot per tab at each viewport to `docs/screens/` (deviceScaleFactor 2).
- [ ] **Step 4:** Add a "Screens" section to `docs/PORTING.md`:
  - the parity checklist against the inventory, with every §2a–§2e item marked done;
  - the desktop replacements: tray and notification (plan 5), storage permission (dropped), battery card (replaced), `Show my data` (opens on the PC).
- [ ] **Step 5:** Update memory `project_tg_backup_desktop.md` with plan 3 done; the next plan is plan 4/5, the Windows experience. Commit and push.

---

## Out of scope for this plan

- Tray icon, single instance, autostart switch, the first-run API explanation screen, and hide-on-X: plan 5.
- Remote access from the phone over Tailscale with a password: plan 6.
- Sentry, release, the update check and the installer polish: plan 7.
