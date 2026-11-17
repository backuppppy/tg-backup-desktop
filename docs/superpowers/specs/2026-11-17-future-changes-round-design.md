# Future-changes round: items 1, 2, 3, 5, 6, 7, 8 of docs/future-changes.md

Approved by the user on 2026-11-17 ("הכל בהחלט", answers in conversation). Item 4 (slow chat finding) is already
instrumented and stays open. "Both" means TG Backup Desktop (this repo) and the Android app
(`backuppppy/tg-backup-apk`, branch `main`).

## 1. One process instead of two with a Hebrew TEMP (desktop)

**Today.** `NativeTmp.ensureAsciiTmpdir` restarts the program with an ASCII TEMP and the first process waits for it
(`exitProcess(pb.start().waitFor())`). The installer and Task Manager see two `TGBackup.exe`.

**Design.** The first process exits as soon as the second one has started, with exit code 0. Under `--smoke`, it keeps
waiting, because CI reads the exit code of the process it started. Single instance, the uninstaller and "start with
Windows" are unaffected: only the second process takes the instance lock.

## 2. A too-long caption: try the original caption first (both)

**Today.** When Telegram refuses a caption as too long, the media is sent again with the first 1024 characters and the
rest follows as text (since 2.2.25 / build 156).

**Design (user: "כן").** A server-side copy (`Copier.copyContent`, not protected, not takeout) whose replaced
caption (links stripped) is refused as too long first tries again keeping the **original** caption
(`replaceCaption = false`): a Premium author's long caption usually passes then. If that is refused too, it splits as
today. Links are not stripped in those messages. Uploads (protected sources) and takeout keep today's split, because
there the message is always new.

## 3. Content types: multi-select, and a file-extension field (both)

**User (17/11, replacing the earlier draft):**
- "Only files" means real files (documents). Voice messages are not files, and music is not files.
- Add "only music".
- Content types are a multi-select. "הכל" is exclusive: choosing it clears every other choice, and choosing another
  type clears "הכל".
- An optional file-extension field ("סיומות קבצים", e.g. `pdf, zip`). It can be combined with types, e.g. photos +
  `pdf`.

**Design.**
- New field `Job.mediaTypes: List<MediaType>` (empty = not set yet: use the old `mediaType`, so saved jobs behave
  exactly as before). The editors show only these choices:

| Value | Label | Matches (TDLib / takeout `CopyKind`) |
|---|---|---|
| `ALL` | הכל | everything copyable, as today (exclusive) |
| `PHOTO` | תמונות | photo |
| `VIDEO` | סרטונים | video, GIF (animation), round video |
| `FILE` | קבצים | document only |
| `MUSIC` | מוזיקה | audio (music) |
| `VOICE` | הודעות קוליות | voice message |
| `TEXT` | טקסט | text |

  `VIDEO_DOC` and `ALL_MEDIA` stay in the enum only for reading old jobs; opening such a job in the editor shows the
  equivalent boxes (`VIDEO_DOC` → videos, files, music, voice; `ALL_MEDIA` → photos, videos, files, music, voice) and
  saving writes the new list.
- New field `Job.extensions: String = ""`. Parsed as a comma/space separated list, case-insensitive, with or without a
  leading dot. It matches when the message carries a file whose name ends with `.ext` (document, video, audio,
  animation; TDLib `fileName`, takeout `DocumentAttributeFilename`).
- **Rule:** a message is copied when it matches one of the chosen types **or** one of the extensions.
  - "הכל" selected → the extension field is disabled (everything is copied anyway).
  - No type selected, extensions filled → only those extensions.
  - No type and no extension → the editor refuses to save ("בחר לפחות סוג תוכן אחד או סיומת").
- A line under the field explains it: "קבצים עם הסיומות האלה יגובו בנוסף לסוגים שסימנת".
- My call: voice messages get their own choice ("הודעות קוליות"), since they are neither files nor music. Photos and
  normal videos have no file name in Telegram, so an extension like `jpg` will not catch them (checked in the live
  test).

## 5. Fast mode: slice size = the per-minute limit (both)

**Design.**
- Slice size = `perMinuteLimit` clamped to 1..100. A limit of 0 (no cap) gives 25.
- The size of a saved wave is stored with it: new field `Progress.waveChunk`, where 0 means "saved before this
  version", i.e. 25. A wave in progress continues with the size it started with, even if the limit changed.
- **Warning in the job editor (both UIs):** when the limit is above 30, or 0, a line under the field says:
  "מומלץ 25 (לכל היותר 30). מספר גבוה יותר עלול לגרום לטלגרם להגביל את החשבון או למחוק אותו." With 25, and
  anything up to 30, there is no warning.

## 6. "Hot" accounts in fast mode too (both)

**Design.** Fast mode keeps a `SenderRotation` for its accounts, used only for the rest and the hot logic, not for
turns. A FloodWait or a stuck send sets `busy(i, until)`, so the account becomes hot. The pause after each copy in fast
mode becomes `restMs(i, base)`: three times longer, at least 5 s, while hot. The hand-off of item 8 of the earlier
round stays as it is.

## 7. Longer idle timeout on the phone server (desktop)

**Design.** `RemoteServer`'s CIO engine gets `connectionIdleTimeoutSeconds = 180`, longer than `tailscale serve`'s
keep-alive (about 90 s), so a reused connection is not already closed.

## 8. Ktor's log into ours and Sentry, and a watchdog for the phone server (desktop)

**Why.** On 17/11 the phone server stopped accepting connections. Port 47800 was LISTENING, `curl` was refused, a
thread dump at that moment showed nothing stuck, and nothing was logged. Ktor logs through SLF4J, and the program has
no SLF4J provider, so Ktor's errors vanish.

**Design.**
- **SLF4J provider of our own** (`org.slf4j.spi.SLF4JServiceProvider` plus the `META-INF/services` file): Ktor's WARN
  and ERROR lines go to `LogBus` (as `[שרת] …`), and ERROR with a throwable goes to Sentry once per message key
  (`SentryOnce`), masked like every other report. DEBUG, TRACE and INFO are dropped.
- **Watchdog**, while phone access is on: every 30 s it opens a TCP connection to `127.0.0.1:47800`, sends
  `GET /api/health`, and expects any HTTP status line within 5 s. The Host check may answer 4xx, and that still proves
  the server accepts. After two failures in a row:
  - log "שרת הגישה מהטלפון לא עונה — מפעיל אותו מחדש";
  - report once to Sentry, with the failure (refused or timeout);
  - restart the server through `RemoteAccessController` (the same path as switching it off and on).

## Testing

- **Unit:**
  - `MediaType` matching (TDLib and `CopyKind`) for every type;
  - the slice-size function and the use of a saved `waveChunk`;
  - the caption retry decision (pure);
  - the SLF4J logger's forwarding (fake sink);
  - the watchdog's decision (fake probe: one failure, two failures, recovery);
  - `NativeTmp` waits only under smoke (pure decision).
- **Desktop UI:** Playwright for the warning text and the new content types in the editor.
- **Live:** with the two test accounts (`/root/tg-live`), a fast run with limit 25 (slice 25) and with limit 40
  (slice 40), and a run with "רק קבצים" or "רק סרטונים".
- **The user's tests:** two TGBackup processes become one (Task Manager); the phone keeps working over a long run; a
  forced phone-server stop is restarted within about a minute.
- **Shipping:** Android 2.2.27, desktop pre-release, Releases links, APK to `Download/TG Backup`.
