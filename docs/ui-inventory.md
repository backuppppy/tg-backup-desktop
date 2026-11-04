# TG Backup Android UI inventory (source commit 6bdd532)

Sources: ui/MainActivity.kt, JobsScreen.kt, ExportScreen.kt, AccountsScreen.kt, LogScreen.kt (also holds SettingsScreen), service/BackupService.kt.
Engine = /root/tg-backup-desktop/engine/src/main/kotlin/org/tgbackup/app/. All UI strings below are verbatim. `app` = `App.instance` (fields: store, jobs, accounts, exports, log). Wiring in App.kt (not a screen): `store.config.map{it.accounts}.distinctUntilChanged().collect{ accounts.sync(it) }` (every account list change syncs clients) and `jobs.anyRunning.collect{ if(running) startBackupService() }`.

NOTE: MainActivity references SettingsScreen, ExportPasswordDialog, ConfirmDialog, which live in LogScreen.kt, ExportScreen.kt, AccountsScreen.kt. All covered here.

## 1. Navigation

Whole UI is forced RTL (`LayoutDirection.Rtl`), dark theme. Scaffold:
- **Top bar** (TopAppBar, no actions): title `TG Backup  v${BuildConfig.VERSION_NAME}` (two spaces before v).
- **Snackbar host** at bottom: global toast `LocalToast(msg)` = `snackbar.showSnackbar(msg)` (default short duration, one at a time queue). All screens use it.
- **Bottom NavigationBar**, in this visual order (RTL puts first item on the right), label / icon / internal tab index:
  1. `משימות` / PlaylistPlay / 0 (default tab, rememberSaveable)
  2. `ייצוא` / Archive / 4
  3. `חשבונות` / Person / 1
  4. `לוג` / Terminal / 2
  5. `הגדרות` / Info / 3 (SettingsScreen, section 6)
- No FAB. Global actions: none in top bar. Per-screen "add" buttons are inline at list end.
- **Global overlay**: `ExportPasswordDialog()` is composed over every tab (section 5 Export / dialogs), because copy jobs via takeout also log in.
- On activity create: request POST_NOTIFICATIONS (API>=33) if not granted; `App.instance.jobs.restore()` (auto-resumes jobs in `Config.autoResume`).
- Tab state is not routed (no deep links); all screens live-update from StateFlows.

## 2a. Jobs screen (`משימות`)

Collects: `store.config` (cfg.accounts, cfg.jobs), `jobs.statuses` (Map<jobId, JobStatus>). Vertical list, padding 12dp, gap 10dp.

### Header block (first list item)
- Summary text (titleSmall). `active = statuses.values.filter{running}`, `total = statuses.values.sumOf{copied}`:
  - none active: `⚪ עצור · הועתקו בהרצה זו: $total`
  - else: `🟢 ${active.size} משימות פעילות · הועתקו בהרצה זו: $total`
- Buttons (wrap row):
  - `▶ הפעל הכל` (filled, always enabled): `val errors = jobs.startAll()`; if non-empty toast `errors.joinToString("\n")`.
  - `⏸ השהה הכל` (outlined): enabled iff `active.any{ !it.paused }`; `jobs.pauseAll()`.
  - `▶ המשך הכל` (outlined): enabled iff `active.any{ it.paused }`; `jobs.resumeAll()`.
  - `⏹ עצור הכל` (outlined): enabled iff `active.isNotEmpty()`; `jobs.stopAll()`.
- If `cfg.accounts.isEmpty()`: error-colored text `קודם הוסף חשבון בלשונית 'חשבונות'.`

### Job card (one per cfg.jobs, key=id; status = statuses[id] ?: JobStatus())
Card background: surfaceVariant when running, default card color otherwise.
- Title (titleMedium): icon + " " + name. Icon: `⏸` if running&&paused, `🟢` if running, `⚪` otherwise. Name = `job.name` or `משימה ${job.id}` if blank.
- Line (bodySmall): `${status.sourceTitle.ifBlank{job.source}}  ←  ${job.target}` (two spaces around arrow).
- Line (bodySmall): `${job.mediaType.label} · ${if(job.mode==FAST) "מהיר" else "מסודר"}${if(job.viaTakeout) " · 📡 בלי הורדה" else ""} · חשבונות: ` + account names (names of job.accountIds resolved in cfg.accounts, blank names skipped; `—` if none) joined by `, `.
- If `job.viaTakeout`: `📡 קריאה דרך סשן הייצוא ושליחה בלי הורדה`; else if `status.isProtected`: `🔒 ערוץ מוגן — הורדה והעלאה מחדש`.
- Divider.
- If running: phase line (`status.phase`, primary color); if `status.lastTargetLink != null` it is underlined and clickable -> opens URL (ACTION_VIEW; failures swallowed).
- Always: `הועתקו בהרצה זו: ${status.copied}` + (if failed>0) ` · נכשלו: ${status.failed}`.
- If `status.lastId > 0`: `הודעה אחרונה שטופלה: ${status.lastId}`; if `status.lastLink != null` primary + underlined, clickable -> open link (`https://t.me/c/<internal>/<msgId>`; lastLink only non-null for -100xxx channel ids).
- Action row:
  - not running: `▶ הפעל` -> `jobs.start(job.id)?.let(toast)` (returned String is the error text; see list below)
  - running: `⏹ עצור` -> `jobs.stop(job.id)`; and if paused `▶ המשך` -> `jobs.resume(job.id)` else `⏸ השהה` -> `jobs.pause(job.id)`.
  - `ערוך` (outlined, enabled iff !running) -> opens JobDialog with this job.
  - `אפס התקדמות` (text btn, enabled iff !running) -> reset confirm dialog.
  - `מחק` (text btn, error color, enabled iff !running) -> delete confirm dialog.
- `jobs.start` error strings (toast): `המשימה כבר פועלת`, `המשימה לא נמצאה`, `חסר מקור או יעד`, `לא נבחרו חשבונות למשימה`, `החשבון ${names} כבר עובד במשימה אחרת`. startAll prefixes `${job.name.ifBlank{job.id}}: `.
- Last list item: `➕ הוסף משימה` (full width) -> `editing = Job()` (new dialog with defaults).

### JobDialog (AlertDialog; scrollable column max 520dp)
Title: `משימה חדשה` if `initial.source.isBlank()` else `עריכת משימה`. Fields, in order:
1. TextField `שם המשימה` (single line, free text, saved trimmed; may be blank -> card shows `משימה <id>`).
2. TextField `מקור: מזהה (עם או בלי ‎-100) / @username / קישור` -> `source` (value `.trim()` on every keystroke).
3. TextField `יעד: מזהה (עם או בלי ‎-100) / @username / קישור` -> `target` (trimmed).
4. Label `סוג תוכן` (labelLarge) + radio list over `MediaType.entries` using `.label`: `וידאו + קבצים` (VIDEO_DOC, default), `תמונות בלבד` (PHOTO), `כל המדיה (ללא מדבקות)` (ALL_MEDIA), `טקסט בלבד` (TEXT), `הכל` (ALL).
5. Label `חשבונות (חשבון אחד — למשימה אחת בלבד)` + one checkbox row per `cfg.accounts` labelled `acc.name.ifBlank{acc.phone}`; if `taken` (account id in other jobs' accountIds and not selected here) append ` (משויך למשימה אחרת)`, row disabled, text 40% alpha. If no accounts: error text `אין חשבונות — הוסף בלשונית 'חשבונות'`. Stored order = cfg.accounts order filtered by selection.
6. Switch `  📡 העתקה בלי הורדה (דרך סשן הייצוא)` -> `viaTakeout` (default false). When on, help text (bodySmall): `עובד גם בערוצים מוגנים וחסומים. בפעם הראשונה טלגרם עשוי לבקש לאשר את הייצוא בהודעת שירות.`
7. Only if `selected.size > 1`: label `מצב עבודה` + radios over `JobMode.entries` labels: `📑 מסודר — שומר על הסדר של המקור` (ORDERED default), `⚡ מהיר — כל חשבון מטפל בקטע משלו (סדר מבולגן)` (FAST). Mode field is retained (not reset) when hidden.
8. TextField `התחל מהודעה מס' (ריק = להמשיך מאיפה שעצר)` numeric, digits only; empty/blank shown when startFromId<=0; saved `toLongOrNull() ?: 0` -> `startFromId` (one-shot; engine clears it after use).
9. TextField `תקרת הודעות לדקה לחשבון (0 = ללא)` numeric digits only, default `25`; saved `toIntOrNull() ?: 25` -> `perMinuteLimit` (empty input becomes 25, not 0).
10. Switch `  הסר קישורים ותיוגים מהכיתוב` -> `stripLinks` (default true).
11. Switch `  דלג על הודעות בערבית` -> `skipArabic` (default false).
- Buttons: `שמור` (enabled iff `source.isNotBlank() && target.isNotBlank() && selected.isNotEmpty()`) and `ביטול` (dismiss). Save = `store.updateConfig{ replace-by-id or append }`; closes dialog. Edit keeps `id` (`initial.copy`). Editing a running job is prevented only by button disable (Edit disabled while running).
- No other validation (no link/username parsing in UI; engine's `resolveChat` handles at run time).

### Dialogs
- Delete (`ConfirmDialog`): title `למחוק את המשימה ${job.name}?`, text `ההתקדמות השמורה תימחק.`, buttons `אישור` / `ביטול`. On OK: `jobs.forget(job.id)` then `store.updateConfig{ jobs.filter{id!=} }`.
- Reset progress: title `לאפס התקדמות?`, text `המשימה ${job.name} תתחיל מההודעה הראשונה בערוץ (ייתכנו כפילויות ביעד).`, `אישור`/`ביטול`. On OK: `jobs.resetProgress(job.id)`.

## 2b. Accounts screen (`חשבונות`)

Collects `store.config`, `accounts.clients` (Map<id, AccountClient>), `exports.prefs`.
- Top note (bodySmall): `API ID ו-API Hash מקבלים בכתובת my.telegram.org ← API development tools.`
- One AccountCard per `cfg.accounts`; last item `➕ הוסף חשבון` -> `editing = Account()`.

### AccountCard
`client = clients[acc.id]`; `auth` = `client.auth` (StateFlow<AuthUi>), or if no client: constant `AuthUi.Unsupported("חסרים API ID / Hash")` (clients only exist when apiId!=0 && apiHash non-blank). `error` = `client.lastError` (StateFlow<String?>).
- Title (titleMedium): `acc.name` or `ללא שם`; below it `acc.phone` (bodySmall).
- Regular-session status line: Ready -> `✅ סשן רגיל: מחובר כ-${display}` (secondary/green; display = "First Last (@user)" or tag); otherwise `📱 סשן רגיל: ${auth.label}`; color red for Unsupported, default else.
- `error` text (red, bodySmall) when non-null: raw TDLib text `"<code>: <message>"` from last sendPhone/sendCode/sendPassword failure; cleared on Ready.
- Export-session status line (bodySmall) only when auth is Ready; `exportLoggedIn` is re-evaluated on (acc.id, auth) via `exports.isLoggedIn(acc)` (runCatching -> false); null when auth not Ready:
  - exportBusy or exportLoggedIn==null: `📤 סשן ייצוא: בודק…`
  - false: `📤 סשן ייצוא: לא מחובר`
  - `availableAt[acc.id] > now`: `⏳ סשן ייצוא: ממתין לאישור ב-Telegram (עוד $when_)` in red, where `when_` = `${hrs}ש' ${mins}ד'` if hrs>0 else `${mins}ד'` (hrs = secs/3600, mins=(secs%3600)/60). `availableAt` from `exports.prefs.availableAt`.
  - else: `✅ סשן ייצוא: מחובר ומוכן` (green)
- **AuthUi labels / login flow** (sealed class AuthUi(label)):
  - `Starting` "מאתחל…": no input. (Also used before first update.)
  - `NeedPhone` "מנותק": button `התחבר (שלח קוד)` enabled iff `!busy && client != null` -> `client.sendPhone()` (phone from account: spaces/dashes stripped, `+` prefixed if missing).
  - `NeedCode` "ממתין לקוד": input `קוד מטלגרם` (numeric keyboard) + button `אשר` enabled iff `!busy && input.isNotBlank()` -> `client.sendCode(input)` (trimmed).
  - `NeedPassword(hint)` "ממתין לסיסמת אימות דו-שלבי": password input (masked) labelled `סיסמה (רמז: ${hint})` if hint non-blank else `סיסמת אימות דו-שלבי`; button `אשר` same enable rule -> `client.sendPassword(input)`.
  - `Ready(display)` "מחבר": no input; `לוג` gets `[tag] מחובר לטלגרם כ-$display`.
  - `Unsupported(why)`: label=why, red. Possible whys: `טלגרם דורש אימות מייל — התחבר פעם אחת מאפליקציית טלגרם הרשמית ונסה שוב`; `המספר לא רשום בטלגרם`; `מצב התחברות לא נתמך: <StateName>`; `API ID / API Hash לא תקינים (<err>)`; `חסרים API ID / Hash` (UI-side).
  - `Closed` "נסגר": label only.
  - Action helper `act{}`: sets busy; result String? = error -> toast `שגיאה: $err`, else clears input. Inputs shared in a single `input` field (cleared after success).
- Buttons row: `ערוך` (always) -> AccountDialog; `התנתק` shown iff auth is Ready/NeedCode/NeedPassword, enabled iff !busy -> `accounts.logout(acc.id)` (logs out, wipes TDLib dir, starts fresh client -> returns to NeedPhone); `מחק` (red) -> delete confirm.
- Export-session buttons row (only when auth Ready):
  - exportLoggedIn==false: `חבר סשן ייצוא` (busy label `מחבר…`), enabled iff !exportBusy -> `exports.login(acc)`; on failure toast `חיבור סשן ייצוא נכשל: ${it.message}`; then re-check `isLoggedIn`. May pop the 2FA password dialog (section 5).
  - exportLoggedIn==true: `בדוק סשן ייצוא` (busy `בודק…`) -> `exports.checkTakeoutReady(acc)`: success true -> toast `✅ סשן הייצוא מוכן להעברה`; false -> toast `⏳ טלגרם עדיין ממתין לאישורך — לחץ Allow בהודעת טלגרם`; failure -> toast `בדיקה נכשלה: ${it.message}`. (Side effect: updates prefs.availableAt.)
  - null: none.

### AccountDialog
Title: `חשבון חדש` if `initial.name.isBlank() && initial.apiId == 0` else `עריכת חשבון`. Fields:
- `שם` free text (trimmed on save).
- `API ID` digits only (filter), numeric keyboard; blank when apiId==0.
- `API Hash` trimmed live.
- `טלפון (+972…)` phone keyboard; trimmed on save.
- `שמור` enabled iff `apiId.toIntOrNull()!=null && apiHash.length>=16 && phone.isNotBlank()`; `ביטול`. Save -> `store.updateConfig{replace/append by id}` (AccountManager.sync recreates client if apiId/apiHash/phone changed, else just renames live).
- Account.id = 8-char uuid, auto.

### Delete account (ConfirmDialog)
Title `למחוק את החשבון ${acc.name}?`, text `החשבון יתנתק ויוסר מכל המשימות.`, `אישור`/`ביטול`. On OK, in order: for each running job id in `jobs.statuses.value.keys` whose job contains acc.id -> `jobs.stop(id)`; `store.updateConfig{ accounts - acc; each job.accountIds - acc.id }`; async `accounts.purge(acc.id)` (logout + wipe TDLib dir). NOTE: it does NOT call `exports.forget(acc.id)` (export session left on disk) - possible gap; desktop should call it.

## 2c. Export screen (`ייצוא`)

Root: `exports.running` (StateFlow<RunningExport?>): non-null -> ProgressPanel, null -> ExportSetup. Single export at a time (`start` is a no-op if running != null).

### ExportSetup (LazyColumn)
State (UI-local): accountId, loggedIn (Boolean?), busy (String?), chats (List<ChatEntry>?), search, chat, topics, topic, from, till (Int unixtime, 0 = unset). Collects store.config, accounts.clients, exports.prefs. On accountId change: `loggedIn = exports.isLoggedIn(acc)`.
1. **Intro card**: title `ייצוא היסטוריית צ'אט`; text `ייצוא ערוץ, קבוצה או צ'אט בדיוק כמו "Export chat history" של טלגרם דסקטופ (HTML / JSON), דרך Takeout session.`; label `חשבון:`; radio per cfg.accounts: `acc.name.ifBlank{acc.phone}` + ` (לא מחובר)` unless that account's auth is Ready. No account preselected (accountId null).
2. **SessionCard** (when account selected): title `סשן ייצוא`;
   - loggedIn null: `בודק…`; true: `✅ מחובר (סשן נפרד מ-TG Backup)` (green); false: text `הייצוא משתמש בסשן נפרד, כמו טלגרם דסקטופ. ההתחברות מאושרת אוטומטית דרך החשבון ${acc.name.ifBlank{acc.phone}} שמחובר ב-TG Backup (QR).` + button (enabled iff busy==null) `התחבר לסשן הייצוא` (shows busy text `מתחבר…` while running) -> `exports.login(acc)`; failure toast `ההתחברות נכשלה: ${msg}`; re-check isLoggedIn.
   - If `prefs.availableAt[acc.id] > now` (red, bodySmall): `⏳ מטעמי אבטחה טלגרם יאפשר להתחיל את הייצוא רק בעוד ${(availableAt-now)/3600} שעות (ב-$whenText). נשלחה הודעה לכל המכשירים שלך. חזור אז ונסה שוב מאותו מכשיר.` whenText = `dd.MM.yyyy HH:mm` local. (Does not disable Export button.)
3. **Chat picker** (when loggedIn==true and chat==null): button `טען צ'אטים` (becomes `רענן` once loaded), enabled iff busy==null -> `exports.loadChats(acc){n->busy="טוען צ'אטים… $n"}`; busy text `טוען צ'אטים…` (+ ` N` progress); failure toast `טעינת הצ'אטים נכשלה: ${msg}`. After load: search field `חיפוש` (filters title or username contains, case-insensitive; blank = all); list of cards (key peerId): title (titleSmall) and subtitle joined by ` · `: type label, `@username` if non-blank, `פורום` if isForum, `בארכיון` if archived. Type labels (DialogInfo.Type): Self `הודעות שמורות`; Replies/VerifyCodes/Personal `צ'אט פרטי`; Bot `בוט`; PrivateGroup/PrivateSupergroup `קבוצה פרטית`; PublicSupergroup `קבוצה ציבורית`; PrivateChannel `ערוץ פרטי`; PublicChannel `ערוץ ציבורי`; Unknown (empty).
   - Click card: chat = entry; topic=null; topics=null; from=till=0; if entry.isForum -> `exports.loadTopics(acc, entry)` (failure toast `טעינת הנושאים נכשלה: ${msg}` and empty list).
4. **ExportOptions card** (when chat chosen): header row: chat title (titleMedium) + button `החלף צ'אט` (chat=null).
   - If `chat.isForum`: label `נושא (Topic export settings):`; radio `כל הצ'אט` (topic=null); `טוען נושאים…` while topics==null; scrollable (max 260dp) radio list of `t.title` (TopicEntry(id,title)).
   - Label `Media export settings`; 7 checkboxes (bitmask `prefs.mediaTypes`, toggled via `exports.updatePrefs{copy(mediaTypes=...)}`; persisted): `Photos (תמונות)`=0x01, `Videos (סרטונים)`=0x02, `Voice messages (הודעות קוליות)`=0x04, `Video messages (הודעות וידאו)`=0x08, `Stickers (מדבקות)`=0x10, `GIFs`=0x20, `Files (קבצים)`=0x40. Default mediaTypes = Photo only (0x01).
   - `Size limit: ${prefs.sizeLimit / 1MiB} MB` + Slider index 0..99 (100 steps) -> `updatePrefs{copy(sizeLimit=SizeLimitByIndex(i))}`. Default sizeLimit 8 MiB. SizeLimitByIndex(i): idx=i+1; MB = idx<=10: idx; <=30: 10+(idx-10)*2; <=40: 50+(idx-30)*5; <=60: 100+(idx-40)*10; <=70: 300+(idx-60)*20; <=80: 500+(idx-70)*50; <=90: 1000+(idx-80)*100; else 2000+(idx-90)*200 (max 4000 MB = kMaxFileSize). IndexBySizeLimit = first i with SizeLimitByIndex(i) >= limit.
   - Label `Format`: radios `Human-readable HTML` ("Html", default), `Machine-readable JSON` ("Json"), `Both` ("HtmlAndJson") -> prefs.format (string = `Format.valueOf` name).
   - TextField `Download path` (placeholder = `exports.defaultPath()`; free text -> prefs.path; blank = default; effective path `exports.exportPath()`).
   - Date range (locale date format `d MMMM yyyy` + `, ` + `HH:mm`): `From: ` + shown(from) or `the oldest message`; buttons `תאריך` (date picker), `שעה` (time picker, only when from!=0), `Reset` (from=0). Same for `To: ` ... or `present` with till.
     - Date dialog (Material DatePicker): `שמור`/`ביטול`. Saves local midnight of picked date; for To: if `limit <= from && from != 0` then `from + 600`.
     - Time dialog (24h TimePicker): `שמור`/`ביטול`; result = h*3600+m*60 + local-midnight(current). From: if `result >= till && till != 0` then `till - 600`; To: if `result <= from && from != 0` then `from + 600`. (10-min gap constant `kOffset=600`.)
   - If `!StorageAccess.granted`: error text `כדי לשמור את הייצוא בתיקיית ההורדות צריך הרשאת גישה לקבצים.` + button `אפשר גישה לקבצים` (Android only).
   - Start button `Export` (full width; enabled iff storageOk) -> `exports.start(account, chat, topic, from, till){ app.startBackupService() }`.

### ProgressPanel (when `running != null`; state = `running.controller.state` StateFlow<ExportState>)
- Idle: `Initializing...`
- Processing(state: ProcessingState): title `Exporting your data`; rows (see below); help `אפשר לסגור את המסך. הייצוא ממשיך ברקע עם התראה קבועה.`; buttons: `Skip this file` (outlined, shown iff `bytesCount > 0`) -> `exports.skipFile()`; `Stop` -> opens confirm.
  - Stop confirm dialog (no title): text `Are you sure you want to stop exporting your data?\n\nIf you do, you'll need to start over.`; `Stop` -> `exports.stop()`; `ביטול`.
  - Rows (ContentFromState): each = label + info right-aligned + LinearProgress. mainInfo = `${entityIndex+1} / ${entityCount}` if entityCount>0 else "". mainProgress = substepsTotal==0 ? 0 : substepsPassed/substepsTotal + (if entityCount==1&&entityIndex==0: substepsNow*itemIndex/(substepsTotal*itemCount) else substepsNow*entityIndex/(substepsTotal*entityCount)) (each add 0 if count<=0).
    - Step Initializing: row(`Initializing...`, mainInfo, mainProgress)
    - DialogsList: row(`Processing chats...`, ...)
    - Dialogs/Topic: if step==Topic or entityCount>1 -> row(`Chats`, mainInfo, mainProgress); then row(name, `${itemIndex} / ${itemCount}` if itemCount>0, itemIndex/itemCount) with name = `Deleted` if entityName empty; `Saved Messages` for EntityType.SavedMessages; `Replies` for RepliesMessages; else entityName.
    - other steps (PersonalInfo, Userpics, Stories, ProfileMusic, Contacts, Sessions, OtherData): row(step.name, mainInfo, mainProgress)
    - If `bytesCount > 0`: extra row(bytesName, FormatDownload(bytesLoaded, bytesCount), loaded/count). FormatDownload: total>=1MiB: `r.r / t.t MB` (1 decimal); >=1KiB: `${r/1024} / ${t/1024} KB`; else `r / t B`.
- Finished(path, filesCount, bytesCount): `Data export completed.` (green titleMedium), `Total files: ${filesCount}`, `Total size: ${FormatSizeText(bytesCount)}` (`x.y MB`/`x.y KB`/`n B`), path (bodySmall); buttons `Show my data` (opens path as .json ->application/json else text/html through FileProvider; desktop: open file/folder) and `סגור` -> `exports.dismiss()`.
- ApiError(code,type) (red) + `סגור`:
  - type == `TAKEOUT_INVALID`: `Sorry, you started a new data export, so this data export has been canceled.`
  - type startsWith `TAKEOUT_INIT_DELAY_`: seconds = max(suffix.toIntOrNull()?:1, 1); hours=seconds/3600; hoursText = `less than an hour` if hours<=0 else `$hours hours`; whenText = now+seconds formatted `d MMMM yyyy 'at' HH:mm`; text: `For security reasons, you will be able to begin downloading your data in $hoursText. We have notified all your devices about the export request to make sure it's authorized and give you time to react if it's not.\n\nPlease come back on $whenText and repeat the request using the same device.` (engine also stores prefs.availableAt[accountId]).
  - else: `API Error happened :(\n${code}: ${type}`
- OutputError(path): red `Could not write to ${path}` + `סגור`.
- Cancelled: `הייצוא הופסק.` + `סגור`.
- `dismiss()` is a no-op while job still active.

### ExportPasswordDialog (global, over all tabs)
Collects `exports.password` (StateFlow<PasswordRequest?>; PasswordRequest(hint, answer: CompletableDeferred<String?>)). Title `סיסמת אימות דו-שלבי`; text `סשן הייצוא צריך את הסיסמה של החשבון.` + (hint non-blank ? `\nרמז: ${hint}` : ""); masked single-line field (no label); `אישור` -> `answer.complete(value)`; `ביטול` or outside-dismiss -> `answer.complete(null)` (login fails with `ההתחברות בוטלה`). Desktop: expose via SSE + POST answer endpoint.

## 2d. Log screen (`לוג`)

Collects `log.entries` (StateFlow<List<LogEntry(seq: Long, time: "HH:mm:ss", level, msg)>>; ring buffer MAX 500; also mirrored to log.txt / log.old.txt in the data dir).
- Top row: button `נקה` (outlined) -> `log.clear()` (UI buffer only; file untouched); label `${entries.size} שורות` (bodySmall).
- List: `entries.asReversed()` (NEWEST FIRST, key=seq), each a single Text `"${time}  ${msg}"` (two spaces), 12sp monospace. No auto-scroll code at all (list is newest-on-top so the latest is always visible at scroll position 0); no filtering/search/copy/export.
- Level colors: SUCCESS #7FD1AE, WARN #FFCB6B, ERROR #F07178, INFO #B0BEC5.
- Log producers (Hebrew examples): `[label] מתחיל…`, `[label] <msg> — מפעיל מחדש בעוד 15 שנ'`, `[id] נעצר`, `[tag] מחובר לטלגרם כ-…`, `[export] הייצוא הושלם: N קבצים, B בתים`, `[export] שגיאת API: …`, `[export] שגיאת כתיבה: …`, `[export] הייצוא נכשל: …`, `[tag] סשן הייצוא אושר`, `[tag] סשן הייצוא מחובר`.

## 2e. Settings screen (`הגדרות`, defined in LogScreen.kt)
Scrollable column, two cards.
- Card 1: title `עבודה ברקע`; text `בזמן שמשימה רצה מוצגת התראה קבועה — זה מה שמונע מאנדרואיד לסגור את האפליקציה. כדי שזה יעבוד גם עם מסך כבוי לאורך זמן, כדאי לפטור את האפליקציה מחיסכון בסוללה.`; if `pm.isIgnoringBatteryOptimizations`: green `✅ האפליקציה פטורה מחיסכון בסוללה` else button `פטור מחיסכון בסוללה` (intent ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS; re-checked on resume); text `ב-Xiaomi / Samsung / Huawei יש גם הגדרת 'הפעלה אוטומטית' או 'אפליקציות ישנות' — כדאי לאשר שם את TG Backup.`
- Card 2: title `אודות`; `גרסה ${VERSION_NAME} · TDLib ${TdlClient.TDL_VERSION}`; link buttons `קבוצת טלגרם` -> https://t.me/backuppppy ; `GitHub` -> https://github.com/backuppppy/tg-backup-apk .
- Desktop: drop the battery card (or replace with "start with Windows"/keep-awake); keep About + links; version from build.

## 3. Theme

- Dark only, Material3 `darkColorScheme`: primary `#5AA9F0`, secondary `#7FD1AE`, background `#0F1720`, surface `#16212D`, surfaceVariant `#1E2C3A`, error `#F07178`. All other M3 dark defaults (onSurface ~ #E6E1E5 light-on-dark; onPrimary dark). Android window/status/nav bars `#0F1720`.
- Usage: primary = clickable links / running phase text; secondary = success/connected; error = errors, delete text, warnings; surfaceVariant = running job card; default Card (surface-ish container) otherwise. Log colors above.
- Typography: Material3 defaults (titleMedium for card titles, titleSmall header/chat title, bodySmall for secondary, labelLarge for section labels); log 12sp monospace. Font = system (Roboto).
- Shapes: M3 default Card (12dp rounded), AlertDialog 28dp, filled/outlined buttons pill (fully rounded), text fields outlined 4dp. Paddings: screen 12dp, card 14dp, gaps 6/8/10dp.
- RTL: `LocalLayoutDirection = Rtl` for entire tree (mixed English strings in Export screen render inside RTL). Nav bar order and button rows flow right-to-left. Web: `<html dir="rtl" lang="he">`, bottom bar -> right-first order. Link/ID inputs contain LRM marks (`‎-100`).

## 4. Engine API cheat-sheet (REST/SSE surface)

Package org.tgbackup.app. Types: `Account(id,name,apiId:Int,apiHash,phone)`, `Job(id,name,source,target,mediaType:MediaType,mode:JobMode,accountIds:List<String>,startFromId:Long,stripLinks:Boolean=true,skipArabic:Boolean=false,perMinuteLimit:Int=25,viaTakeout:Boolean=false)`, `Config(accounts,jobs,autoResume:Set<String>)` (data/Models.kt); enums `MediaType{VIDEO_DOC,PHOTO,ALL_MEDIA,TEXT,ALL}`, `JobMode{ORDERED,FAST}` (each has `.label`).

**Store** (data/Store.kt)
- `val config: StateFlow<Config>` -> GET /config + SSE
- `fun updateConfig(transform: (Config) -> Config)` -> accounts/jobs CRUD (UI does replace-by-id / append / filter itself; server needs POST/PUT/DELETE for accounts and jobs)
- `fun progress(jobId: String): Progress` / `saveProgress` / `clearProgress` (UI uses none directly)

**JobManager** (engine/JobManager.kt)
- `val statuses: StateFlow<Map<String, JobStatus>>` -> SSE. `JobStatus(running,paused,phase:String,lastId:Long,sourceChatId:Long,sourceTitle,isProtected:Boolean,copied:Int,failed:Int,targetChatId:Long,lastTargetId:Long)` with computed `lastLink: String?`, `lastTargetLink: String?` (getters; serialize them).
- `val anyRunning: StateFlow<Boolean>`
- `fun start(jobId: String): String?` (error text or null)
- `fun stop(jobId: String)`, `fun pause(jobId: String)`, `fun resume(jobId: String)`
- `fun startAll(): List<String>` (errors), `fun pauseAll()`, `fun resumeAll()`, `fun stopAll()`
- `fun resetProgress(jobId: String)`, `fun forget(jobId: String)`, `fun restore()` (call at startup), `fun isRunning(id: String): Boolean`

**AccountManager** (tg/AccountManager.kt)
- `val clients: StateFlow<Map<String, AccountClient>>`; `operator fun get(id)`
- `suspend fun sync(accounts: List<Account>)` (call whenever config.accounts changes)
- `suspend fun logout(id: String)`, `suspend fun purge(id: String)`

**AccountClient** (tg/AccountClient.kt)
- `val auth: StateFlow<AuthUi>`; sealed `AuthUi(val label)`: `Starting`, `NeedPhone`, `NeedCode`, `NeedPassword(hint)`, `Ready(display)`, `Unsupported(why)`, `Closed`. Serialize as {type, label, hint?/display?/why?}.
- `val lastError: StateFlow<String?>`
- `suspend fun sendPhone(): String?`, `suspend fun sendCode(code: String): String?`, `suspend fun sendPassword(password: String): String?` (null = ok, else "code: message")
- `val isReady: Boolean`

**ExportManager** (export/ExportManager.kt)
- `val prefs: StateFlow<ExportPrefs>`; `ExportPrefs(mediaTypes:Int=Photo(1), sizeLimit:Long=8MiB, format:String="Html", path:String="", availableAt:Map<String,Int>)`
- `fun updatePrefs(change: (ExportPrefs) -> ExportPrefs)` -> PATCH /export/prefs
- `fun defaultPath(): String`, `fun exportPath(): String`
- `val password: StateFlow<PasswordRequest?>` (`PasswordRequest(hint, answer: CompletableDeferred<String?>)`) -> SSE event + POST answer (null = cancel)
- `val running: StateFlow<RunningExport?>` (`RunningExport(accountId,title,controller: Controller,job)`); `controller.state: StateFlow<ExportState>` -> SSE; sealed `ExportState{Idle, Processing(state: ProcessingState), ApiError(code:Int,type:String), OutputError(path), Cancelled, Finished(path,filesCount:Int,bytesCount:Long)}`; `ProcessingState` fields: step(enum Initializing,DialogsList,PersonalInfo,Userpics,Stories,ProfileMusic,Contacts,Sessions,OtherData,Dialogs,Topic), substepsPassed/Now/Total, entityType(Chat,SavedMessages,RepliesMessages,VerifyCodes,Topic,Other), entityName, entityIndex, entityCount, itemIndex, itemCount, bytesRandomId, bytesName, bytesLoaded, bytesCount (mutable class: snapshot to DTO).
- `suspend fun isLoggedIn(account: Account): Boolean`
- `suspend fun login(account: Account): Result<Unit>`
- `suspend fun checkTakeoutReady(account: Account): Result<Boolean>`
- `suspend fun loadChats(account: Account, progress: (Int) -> Unit): List<ChatEntry>` (progress -> SSE/stream); `ChatEntry(input: MTPInputPeer, peerId, title, username, type: DialogInfo.Type, isForum, archived)` - server must keep an opaque handle map (input peer is not JSON-able; use peerId as key)
- `suspend fun loadTopics(account: Account, chat: ChatEntry): List<TopicEntry>`; `TopicEntry(id:Int,title)`
- `fun start(account: Account, chat: ChatEntry, topic: TopicEntry?, from: Int, till: Int, onStarted: () -> Unit)`
- `fun skipFile()`, `fun stop()`, `fun dismiss()`, `fun isActive(): Boolean`, `suspend fun forget(accountId: String)`

**LogBus** (engine/LogBus.kt)
- `val entries: StateFlow<List<LogEntry>>`; `LogEntry(seq:Long,time:String,level:Level,msg:String)`; `enum Level{INFO,SUCCESS,WARN,ERROR}`; `fun clear()` -> SSE stream + DELETE /log.

Helpers the UI uses and the web must re-implement: `FormatSizeText(Long)` (export-core data/Format.kt), `SizeLimitByIndex`/`IndexBySizeLimit`, `FormatDownload`, date `local0` midnight logic (all in ExportScreen.kt; plain functions).

## 5. Android-only behaviour and desktop equivalents

- **Foreground service + ongoing notification** (BackupService): channel `backup` named `גיבוי פעיל`, IMPORTANCE_LOW, id 1, ongoing, onlyAlertOnce. Initial title `TG Backup פועל`, text `מתחיל…`. Live content (recomputed from combine(jobs.statuses, config, export progress)):
  - title `${active.size} משימות פעילות · הועתקו $copied` + (export active ? ` · ייצוא: ${title}` : ""); text = active job names joined `, ` + ` — ` + first active status.phase (names omitted if blank).
  - export only: title `ייצוא: ${running.title}`, text `${itemIndex} / ${itemCount} הודעות` or `מאתחל…` (empty text when not Processing).
  - Action button `עצור הכל` -> `jobs.stopAll()` + `exports.stop()`. Tap opens MainActivity. Service stops itself 3 s after nothing runs. Holds PARTIAL_WAKE_LOCK `TGBackup:run`, START_STICKY, restores jobs on restart (`jobs.restore()`).
  - Desktop: Windows tray icon + tooltip/balloon with the same title/text, tray menu `עצור הכל`, prevent sleep (SetThreadExecutionState) while anything runs, launch at login for auto-resume; or in web UI a persistent status bar + `document.title`/Notification API.
- **POST_NOTIFICATIONS permission** (API 33+): n/a on desktop; optional browser Notification permission.
- **Battery optimisation exemption / OEM autostart** (Settings): drop; replace with "keep awake" toggle.
- **All-files storage permission** (`StorageAccess`, gating the Export button): n/a; Export always enabled. Default export path = `exports.defaultPath()` (Downloads/<AppName>); desktop needs a folder picker or typed path (server-side path text field; optionally native dialog via the host process).
- **Intents ACTION_VIEW** for t.me message links (job card `lastLink`/`lastTargetLink`) and Settings links: use `<a target=_blank href=...>` (or `tg://` fallback).
- **FileProvider + ACTION_VIEW "Show my data"**: server `GET /export/open` that opens the file in the default browser (`Desktop.open`/`start`) or "show in folder"; for remote browsers, serve the export folder as static files.
- **Material DatePicker/TimePicker**: `<input type=date>` + `<input type=time>` (24h) with identical local-midnight and 600 s gap rules.
- **Snackbar toasts**: web toast/snackbar queue (same strings).
- **Clipboard**: none used anywhere in these screens. **Activity lifecycle** (`LifecycleResumeEffect` re-checks permissions): n/a.
- **rememberSaveable(tab)**: remember active tab in URL hash/localStorage.
- **Edge-to-edge/system bars**: n/a.
- **Process death**: the engine's `autoResume` + `jobs.restore()` replace it; call on server start.
- **Surprises/gaps**: Settings is a 5th tab not listed in the user's screen set; account deletion does not forget the export session; Log has no auto-scroll code (newest-first instead); `perMinuteLimit` blank saves as 25 not 0; job `mode` radio is hidden unless 2+ accounts selected; Export screen strings are English (mirroring Telegram Desktop) with only a few Hebrew bits (`תאריך`, `שעה`, `שמור`, `ביטול`, `סגור`, notes).
