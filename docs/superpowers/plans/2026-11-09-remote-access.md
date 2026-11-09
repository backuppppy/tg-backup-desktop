# Phone Access through Tailscale (Plan 6) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The user turns on "גישה מהטלפון", sets a password, scans a QR, and gets the same screens on the phone at `https://<pc>.<tailnet>.ts.net`, installable from Chrome as an app; the program itself never listens outside the PC.

**Architecture:** A second Ktor server in the same process (`RemoteServer`, `127.0.0.1:47800`) with the same routes and screens but password + session-cookie auth. `tailscale serve --bg` terminates https on the tailnet name and proxies to it. A `RemoteAccessController` polls `tailscale status --json`, keeps serve and the server in line with the switch, and publishes a status the Settings screen shows. A manifest + service worker make the screens installable.

**Tech Stack:** Kotlin/JVM 17, Ktor 3 (CIO), kotlinx.serialization, JDK `PBKDF2WithHmacSHA256`, `io.nayuki:qrcodegen:1.8.0` (QR → SVG), ES modules, node unit tests, Playwright, GitHub Actions windows-latest.

**Spec:** `docs/superpowers/specs/2026-11-09-remote-access-design.md` (approved 2026-11-09, https version).

## Global Constraints
- Branch `engine-port`; after each green task push it and fast-forward `main` (`git push origin engine-port:main`). Never force, never delete branches; `git add` explicit paths only (`.kotlin/` stays untracked). Workflow-file pushes need the PAT (see the coordinator).
- Never symlink `desktop/build/resources/main/web` to src.
- The program listens ONLY on loopback (`127.0.0.1`) — the window's server (random port, as today) and `RemoteServer` (`127.0.0.1:47800`). Never `0.0.0.0`, never a tailnet/LAN address. Never Tailscale Funnel.
- Window auth is unchanged (entry token → `tgb_session`). Remote auth is separate: cookie `tgb_remote`, `Secure` (param `secureCookie`, default true), `HttpOnly`, `SameSite=Strict`, `Max-Age` 10 years.
- Password: at least 6 characters; `PBKDF2WithHmacSHA256`, 210 000 iterations, 16-byte random salt, 32-byte key. Sessions: 32 random bytes hex; on disk only `SHA-256(hex)`. File `remote.json` in the data dir, written atomically (temp + `AtomicFiles.replace`, as `DesktopPrefs`). The password and session values never reach the log.
- Remote viewers may NOT: set/change the password, switch remote access, disconnect all, open the export folder, call `/internal/show` → 403 `{"error":"זמין רק במחשב"}`. They may do everything else, including `PUT /api/desktop-prefs`.
- Changing the password deletes all sessions. Switching off keeps sessions. Switching off removes only OUR serve entry (443 → `http://127.0.0.1:47800`); never touch other serve config. Program exit leaves serve as is (it comes back on next launch).
- All user-facing text Hebrew, verbatim from the "Texts" section below.
- Verify: `./gradlew build`; `node --test web-tests/unit/*.test.mjs`; Playwright only per touched spec (`cd web-tests && npx playwright test --trace off <spec>`); CI on windows-latest green after push.

## Texts (verbatim)
- Settings card title `גישה מהטלפון`; switch `גישה מהטלפון דרך Tailscale`.
- Instructions (ordered list, shown in every state except Running):
  1. `מתקינים את Tailscale במחשב ובטלפון (tailscale.com/download) ומתחברים לאותו חשבון.`
  2. `פעם אחת, באתר של Tailscale, בעמוד DNS, לוחצים Enable HTTPS. שם המחשב ב-Tailscale נרשם ביומן ציבורי של תעודות, לכן כדאי שלא יכיל מידע פרטי.`
  3. `מדליקים את המתג וקובעים סיסמה.`
  4. `סורקים את הקוד בטלפון, מזינים את הסיסמה, ובתפריט של כרום בוחרים "התקן אפליקציה".`
- States (`RemoteState`): `Off` `כבוי.` · `NotInstalled` `Tailscale לא מותקן במחשב.` + link `להורדה` → `https://tailscale.com/download` · `NotConnected` `Tailscale מותקן אבל לא מחובר. פותחים את Tailscale ליד השעון ומתחברים.` · `HttpsOff` `צריך להפעיל https בחשבון Tailscale (שלב 2).` + link `לעמוד DNS` → `https://login.tailscale.com/admin/dns` · `PortBusy` `הפורט 47800 במחשב תפוס על ידי תוכנה אחרת.` · `ServeBusy` `ב-Tailscale כבר מוגדר שירות אחר על https. התוכנה לא דורסת אותו.` · `Error` `שגיאה מ-Tailscale: <detail>` · `Running` `פועל. סורקים את הקוד בטלפון:`
- Every state except `Off`/`Running` adds: `המתג נשאר דלוק, והחיבור יעלה לבד כשזה יסודר.`
- Running extras: the URL, button `העתק` (toast `הועתק`), `מכשירים מחוברים: <n>`, buttons `החלף סיסמה`, `נתק את כל המכשירים` (toast `כל המכשירים נותקו`).
- Password dialog: title `סיסמה לגישה מהטלפון`; fields `סיסמה`, `שוב את הסיסמה`; errors `לפחות 6 תווים`, `הסיסמאות לא זהות`; buttons `שמור`, `ביטול`. Enabling without a password → 409 `קודם קובעים סיסמה`.
- Phone Settings card `חיבור מהטלפון`: `מחובר למחשב דרך Tailscale.` + button `התנתק`.
- Login page: heading `TG Backup`, field `סיסמה`, button `כניסה`; wrong `סיסמה שגויה`; throttled `יותר מדי ניסיונות. אפשר לנסות שוב בעוד <n> שניות.`
- Export finished, remote viewer: instead of `Show my data` the line `הקבצים נשמרו במחשב, בתיקייה שלמעלה.`
- Offline page (service worker, navigation failed): `אין חיבור למחשב. בודקים ש-Tailscale מחובר בטלפון ושהמחשב דלוק.` + button `נסה שוב`.
- Log: `מכשיר התחבר מהטלפון (<who>)` · `ניסיון כניסה כושל (<who>)` · `הגישה מהטלפון פועלת: <url>` · `הגישה מהטלפון כובתה` · `גישה מהטלפון: <state text>` (on every other state change) · `כל המכשירים נותקו`. `<who>` = `Tailscale-User-Login` header, else the first `X-Forwarded-For` address, else `לא ידוע`.

## Review Focus
1. Tailscale stops / the PC sleeps and wakes / the tailnet name changes while Running: the state follows within one poll, serve is re-checked, and the phone gets the login page or the offline page — never a crash or a stuck "פועל".
2. A user who already uses `tailscale serve` on 443 for something else: the program shows `ServeBusy` and leaves it untouched, also when the switch is turned off.
3. `tailscale` hangs or asks something interactive (HTTPS not enabled, consent URL): every CLI call has a 20 s timeout and never blocks the UI, the engine or shutdown.
4. Two phones and the PC window at once: SSE events reach all; a logout on one phone does not log out the other; "נתק את כל המכשירים" logs out both at their next request.
5. A remote page left open while the password changes: its next request is 401 and it lands on the login page (no blank screen, no reconnect loop).

---

### Task 1: Remote auth core — password, sessions, throttle

**Files:** Create `desktop/src/main/kotlin/org/tgbackup/desktop/remote/RemoteAuth.kt`. Test `desktop/src/test/kotlin/org/tgbackup/desktop/remote/RemoteAuthTest.kt`.

**Interfaces (produces):**
- `class RemoteAuth(dir: File, clock: () -> Long = System::currentTimeMillis)`:
  `fun hasPassword(): Boolean`; `fun setPassword(pw: String)` (throws `IllegalArgumentException` under 6 chars; clears sessions); `fun login(pw: String, who: String): LoginResult`; `fun validate(session: String?): Boolean` (updates lastSeen at most once a minute); `fun logout(session: String)`; `fun disconnectAll()`; `val devices: StateFlow<Int>`.
- `sealed interface LoginResult { data class Ok(val session: String); data object Wrong; data class Wait(val seconds: Int) }`.
- `remote.json`: `{"hash","salt","iterations","sessions":[{"sha256","created","lastSeen"}]}`.
- Throttle: per `who`, after 3 failures each further failure doubles a wait starting at 2 s, capped at 300 s; globally, 20 failures within 10 min → every login waits 60 s. A success resets that `who`. While waiting, `login` returns `Wait` without checking the password.

- [ ] Failing tests: `aShortPasswordIsRefused`; `theRightPasswordGivesASessionThatValidates`; `theWrongPasswordIsWrong`; `sessionsSurviveANewInstance` (new `RemoteAuth(dir)` validates the old session); `theFileHoldsNoPasswordOrSession` (file text contains neither); `changingThePasswordDropsAllSessions`; `logoutDropsOnlyThatSession`; `disconnectAllDropsAll` (`devices` 2 → 0); `theFourthFailureWaits` (fake clock: 3×Wrong, then `Wait(2)`, after 2 s `Wrong`, then `Wait(4)`); `twentyFailuresLockEveryone` (different `who`s → `Wait(60)`); `aBadFileMeansNoPassword`.
- [ ] Run `./gradlew :desktop:test --tests '*RemoteAuthTest*'` → FAIL. Implement. Run → PASS.
- [ ] Commit `desktop: remote auth — a hashed password, hashed sessions that survive restarts, and a growing wait after failed logins`.

### Task 2: The remote server — password login on 127.0.0.1:47800

**Files:** Create `desktop/src/main/kotlin/org/tgbackup/desktop/server/RemoteSecurity.kt`, `desktop/src/main/kotlin/org/tgbackup/desktop/server/RemoteServer.kt`, `desktop/src/main/resources/web/login.html`, `desktop/src/main/resources/web/login.js`. Modify `server/ApiServer.kt` (share the route set: extract `fun Route.appRoutes(app, events, locks, handles, extraRoutes)` used by both servers), `server/Dto.kt` (`SnapshotDto.viewer: String = "local"`), the SSE/snapshot route (copy with the call's viewer), `server/ExportRoutes.kt` (`open` → 403 for remote), `DevServer.kt` (env `TGB_DEV_REMOTE_PORT`, `TGB_DEV_REMOTE_PASSWORD`), `resources/web/lib/api.js` + `lib/store.js` (401 on a remote page → `location.replace('/login.html')`). Tests `desktop/src/test/kotlin/org/tgbackup/desktop/server/RemoteServerTest.kt`, new `web-tests/remote.spec.mjs` (+ `global-setup.mjs` starts the dev server with a remote port and password `secret1`).

**Interfaces:**
- Consumes `RemoteAuth` (Task 1).
- Produces `class RemoteServer(app: DesktopApp, auth: RemoteAuth, port: Int = REMOTE_PORT, allowedNames: () -> Set<String>, secureCookie: Boolean = true)` with `start()` (throws `java.net.BindException` when the port is taken) and `stop()`; `const val REMOTE_PORT = 47800`. A call attribute `ViewerKey` = `"remote"` on this server, `"local"` on `ApiServer`; helper `fun ApplicationCall.isRemote(): Boolean` and `suspend fun ApplicationCall.requireLocal()` (403 `זמין רק במחשב`).
- Remote security, before routing: `Host` (and `X-Forwarded-Host` when present) must be in `allowedNames()` (the tailnet name, with or without `:443`) or `127.0.0.1:47800`/`localhost:47800`; non-GET with `Origin` must be `https://<allowed name>` or `http://127.0.0.1:47800`; public without cookie: `GET /login.html`, `GET /login.js`, `GET /app.css`, `GET /favicon.png`, `POST /api/login`, `GET /api/health`, and (Task 5) the PWA files; `/internal/show` → 404; everything else needs a `tgb_remote` cookie that `auth.validate`s, else 401 (HTML navigations to `/` → redirect `/login.html`).
- `POST /api/login {password}` → 200 + cookie, 401 `סיסמה שגויה`, 429 `{"error": "...", "seconds": n}`; `POST /api/logout` → clears cookie + session. Log lines per Texts.

- [ ] Failing Kotlin tests (`testApplication` with the remote module): `withoutACookieTheApiIs401`; `theRootRedirectsToLogin`; `loginSetsASecureHttpOnlyStrictCookie`; `theCookieOpensTheApi`; `aWrongHostIs403`; `aForeignOriginIs403`; `internalShowIsNotThere`; `exportOpenIsRefusedRemotely` (403 `זמין רק במחשב`) and still works on the local module; `snapshotSaysRemote`; `logoutEndsOnlyThisSession`; `theWaitIs429WithSeconds`; `noLogLineHoldsThePassword`.
- [ ] Failing `remote.spec.mjs` (phone project only, base `http://127.0.0.1:<remote port>`, no stored state): opening `/` shows the login page; wrong password shows `סיסמה שגויה`; `secret1` opens the jobs screen with the bottom tabs; reload stays logged in; the tabs navigate.
- [ ] Run → FAIL. Implement. Run `./gradlew build` and `npx playwright test --trace off remote.spec.mjs shell.spec.mjs` → PASS.
- [ ] Commit `server: a second server for the phone on 127.0.0.1:47800 — same screens, password login with a long-lived session cookie; the export folder and window-only calls stay on the PC`.

### Task 3: Tailscale and the controller

**Files:** Create `desktop/src/main/kotlin/org/tgbackup/desktop/remote/Tailscale.kt`, `desktop/src/main/kotlin/org/tgbackup/desktop/remote/RemoteAccessController.kt`, `desktop/src/main/kotlin/org/tgbackup/desktop/server/RemoteRoutes.kt`. Modify `DesktopPrefs.kt` (`remoteAccess: Boolean = false`), `DesktopApp.kt` (owns `RemoteAuth` + controller, starts/stops it), `server/Dto.kt` + `server/Events.kt` (snapshot field `remote: RemoteStatusDto`, SSE event `remote`), `desktop/build.gradle.kts` (`io.nayuki:qrcodegen:1.8.0`). Tests `desktop/src/test/kotlin/org/tgbackup/desktop/remote/TailscaleTest.kt`, `RemoteAccessControllerTest.kt`, `server/RemoteRoutesTest.kt`, fixtures `desktop/src/test/resources/tailscale/*.json`.

**Interfaces:**
- `interface TailscaleCli { fun status(): TsStatus; fun serveStatus(): ServeConfig; fun serveOn(port: Int); fun serveOff() }`; `class ProcessTailscale(exe: File?)` runs the real CLI with a 20 s timeout per call (kill on timeout → `TsError("timeout")`); `fun findTailscale(): File?` (PATH, then `C:\Program Files\Tailscale\tailscale.exe`).
- `sealed interface TsStatus { NotInstalled; NotConnected; data class Up(val dnsName: String, val httpsOn: Boolean); data class Failed(val detail: String) }` — parsed from `tailscale status --json`: `BackendState != "Running"` → NotConnected; `Self.DNSName` without the trailing dot; `httpsOn` = `CertDomains` non-empty.
- `ServeConfig` from `tailscale serve status --json`: `ours` (443 proxies to `http://127.0.0.1:47800`), `busy` (443 has anything else), `none`. Verify the exact CLI syntax for `serve --bg --https=443 http://127.0.0.1:47800`, `serve --https=443 off` and `serve status --json` against the current tailscale release (download the static linux tarball into the scratchpad and read `--help`); record the versions in the task report and keep real outputs as fixtures.
- `class RemoteAccessController(prefs, auth, cli: TailscaleCli, startServer: (names: () -> Set<String>) -> AutoCloseable, log: LogBus, scope: CoroutineScope, pollMs: Long = 10_000, runningPollMs: Long = 60_000)`; `val status: StateFlow<RemoteStatusDto>`; `@Serializable data class RemoteStatusDto(val enabled: Boolean, val state: String, val url: String? = null, val detail: String? = null, val devices: Int = 0, val hasPassword: Boolean)`. CLI calls run on `Dispatchers.IO`, never on the caller's thread.
- Loop while enabled: status → NotInstalled / NotConnected / HttpsOff / Failed→Error; Up+https → serveStatus: `busy` → ServeBusy; else start the server (BindException → PortBusy), `serveOn` when not `ours`, → Running with `url = "https://<dnsName>"`. Switch off → stop server, `serveOff` only when `ours`, → Off. A state change logs per Texts.
- Routes: `GET /api/remote` → status; `PUT /api/remote/password {password}`, `PUT /api/remote/enabled {enabled}` (409 `קודם קובעים סיסמה`), `POST /api/remote/disconnect-all`, `GET /api/remote/qr.svg` (QR of `url`, 404 when not Running) — the PUT/POST ones `requireLocal()`.

- [ ] Failing tests: parser on fixtures (not running, running+no certs, running+certs, trailing dot); serve status ours/busy/none; `ProcessTailscale` with a fake exe script that sleeps → `timeout` within 21 s; controller with a fake CLI: off→NotInstalled→Running as the fake changes, `ServeBusy` never calls `serveOn`/`serveOff`, switch off when `ours` calls `serveOff` once, `PortBusy` on a bound port, name change → new url; routes: remote viewer gets 403 on the three writes, enabling without password 409, qr.svg is `image/svg+xml` containing `<svg`.
- [ ] Run → FAIL. Implement. `./gradlew build` → PASS.
- [ ] Commit `desktop: remote access follows Tailscale — status polling, our own serve entry on 443 (never another's), the phone server started when https is up, and its status for the settings`.

### Task 4: Settings — the PC card and the phone's logout

**Files:** Modify `desktop/src/main/resources/web/screens/settings.js`, `desktop/src/main/resources/web/screens/export.js` (Finished, remote viewer), `desktop/src/main/resources/web/lib/store.js` (reduce `remote` event + `viewer`), `desktop/src/main/resources/web/app.css`. Tests `web-tests/settings.spec.mjs`, `web-tests/remote.spec.mjs`, `web-tests/unit/store.test.mjs`. Dev routes: `POST /api/dev/remote-status` (DevServer only) to publish any `RemoteStatusDto`.

**Interfaces:** Consumes `GET/PUT /api/remote*`, SSE `remote`, `snapshot.viewer` (Tasks 2–3).

- [ ] Failing tests: local, each `state` shows its text verbatim + the "המתג נשאר דלוק" line where due + the links' hrefs; Running shows url, QR `<img src="/api/remote/qr.svg">`, `העתק`, `מכשירים מחוברים: 2`; switch on with no password opens the dialog (6-char and mismatch errors), save then enable; `נתק את כל המכשירים` posts and toasts; remote (in `remote.spec`): the card `חיבור מהטלפון` with `התנתק` (back to login), no remote switch; export Finished shows the line instead of `Show my data`; an open phone page after `disconnect-all` (called from the window's context) lands on the login page at its next request, no reconnect loop; two phone contexts: logout on one leaves the other in; store reduce test for `remote`.
- [ ] Run → FAIL. Implement (dialog via `formDialog` from `lib/ui.js`; switch save pattern as the existing switches). Run the specs + unit → PASS.
- [ ] Commit `web: the phone-access card on the PC (switch, password, state and instructions, address with QR, devices, disconnect all) and a logout on the phone`.

### Task 5: Installable on the phone

**Files:** Create `desktop/src/main/resources/web/manifest.webmanifest`, `desktop/src/main/resources/web/sw.js`, `desktop/src/main/resources/web/offline.html`, `desktop/src/main/resources/web/icon-192.png`, `icon-512.png`, `apple-touch-icon.png` (180), all from `tools/make-icons.py` (extend it; the BACKUP program icon). Modify `index.html` + `login.html` (manifest link, `theme-color`, apple-touch-icon, register `sw.js`), `ApiServer.kt` `webRoutes` (content types `webmanifest` → `application/manifest+json`; `sw.js` no-cache), remote public paths (Task 2) add these files. Tests `web-tests/remote.spec.mjs`, `web-tests/icons.spec.mjs`.

**Interfaces:** manifest `{"name":"TG Backup","short_name":"TG Backup","start_url":"/","scope":"/","display":"standalone","dir":"rtl","lang":"he","background_color":<app bg>,"theme_color":<app bg>,"icons":[192,512 png, purpose "any"]}`. `sw.js`: a real fetch handler — navigations: network, on failure `offline.html` (precached at install, the only cached file); everything else (API, SSE, assets): network only, never cached.

- [ ] Failing tests: manifest served with the right type and fields; icons are PNGs of 192/512/180 px; the page registers a service worker (`navigator.serviceWorker.ready`); with the context offline a navigation shows the offline text and `נסה שוב`; `/api/events` is never answered from cache.
- [ ] Run → FAIL. Implement. Run specs → PASS.
- [ ] Commit `web: installable on the phone — manifest, icons, and a service worker that only adds an offline page`.

### Task 6: Windows CI check and version

**Files:** Modify `.github/workflows/*.yml` (the windows smoke job), `desktop/src/main/kotlin/org/tgbackup/desktop/AppSmoke.kt` / `SmokeApi` (set a password and enable remote; print `REMOTE_STATE <state>`), version → `0.3.0` where the version lives.

- [ ] In the smoke: after seeding, `PUT /api/remote/password` + `enabled`; wait up to 15 s for a state other than Off; print it. CI asserts `REMOTE_STATE NotInstalled` (no Tailscale on the runner) and that every `Get-NetTCPConnection -State Listen -OwningProcess <pid>` address is `127.0.0.1` or `::1`.
- [ ] `./gradlew build`; push (PAT, workflow file); CI windows green with both assertions in the log.
- [ ] Commit `ci: the smoke turns phone access on and checks the program still listens on loopback only; release: 0.3.0`.
