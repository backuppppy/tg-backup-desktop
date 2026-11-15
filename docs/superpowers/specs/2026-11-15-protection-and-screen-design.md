# Source protection follows Telegram live; the PC stays awake with a dimmed screen

Approved by the user on 2026-11-15 (both parts and the order: protection first, then the screen). Small changes, short design.

## 1. A source's protection follows Telegram while a job runs

**Why.** An admin removed the forwarding restriction from a source channel. The running job (and the same job started
again) kept downloading and re-uploading every file. `AccountClient.resolveChat` caches the `Chat` object for the life
of the program, so `hasProtectedContent` stays the value read the first time. Restarting the job does not refresh it.
Also, once a copy fails with a protection error, `JobRunner.protected` stays true until the run ends.

**What the user wants.** While a job runs, listen to Telegram's notice of a protection change. When the restriction is
removed, switch to the normal server-side copy at once. When it is added, switch to download + upload at once. The
job card's 🔒 line follows.

**Design.**
- `AccountClient` turns TDLib's `UpdateChatHasProtectedContent` into a flow of `(chatId, hasProtectedContent)`.
- `resolveChat` keeps caching how a source string maps to a chat, but returns TDLib's current `Chat` (`getChat(id)`), so
  a job that starts again reads the current flag.
- `JobRunner` watches the first account's flow for the source chat while it runs. A change sets `protected`, reports
  `sourceInfo` (the card), and logs one line. Ordered and fast modes both read the same field.
- A copy refused for protection still switches to download + upload, as today. A later notice that protection was
  removed switches it back.
- Prefetched downloads started while protected are dropped (the job cancels them and deletes their files) when a message
  is copied without upload.

**Approaches considered.** (a) Listen to the update (chosen: the user asked for it, and it is immediate). (b) Read
`getChat` again every batch: simpler, but the switch is late by a batch, and the listener is still needed for "at once".
(c) Restart the program: today's workaround only.

## 2. The PC stays awake while work runs: the screen dims instead of turning off

**Why.** The user's laptop (Modern Standby, on AC, lid open) slept within 1 second of the screen turning off at 09:00
and stayed asleep for 7.7 hours. A test with TG Backup's SystemRequired + ExecutionRequired requests shown by
`powercfg /requests` slept 5 seconds after the screen turned off. On this PC, a screen that turns off means sleep.

**What the user wants.** Real sleep prevention while a job or export runs. The screen is dark only when nobody touches
the PC.

**Design.**
- While work runs and the "מניעת שינה" switch is on (unchanged default: on), TG Backup also holds a DisplayRequired
  request, so Windows does not turn the screen off by idle.
- When the PC has had no input for the user's own display timeout (VIDEOIDLE, AC or DC as current), TG Backup sets the
  internal panel's brightness to its minimum. The first input brings the saved brightness back. When work stops,
  brightness is restored and the request is cleared.
- The brightness to restore is saved on disk before dimming. A start after a crash restores it, as SleepOverride does
  for the sleep timeouts.
- Settings label: "מניעת שינה בזמן שמשימה או ייצוא רצים (המסך מוחשך כשלא נוגעים במחשב)".
- Limit: brightness is set through WMI (`WmiMonitorBrightnessMethods`), which only reaches built-in laptop panels. An
  external monitor stays at its brightness, and the PC still stays awake.

**Approaches considered.** (a) Dim only when idle (chosen by the user: the screen is normal while they work). (b) Dim as
soon as work starts: simpler, but dark while the user sits at the PC. (c) Turn off Modern Standby system-wide
(PlatformAoAcOverride): a registry change and reboot, may leave the PC without normal sleep, not something to ask of the
group's users.

**Left to the builder.** How brightness is read and set (WMI through JNA/COM or a hidden PowerShell call), the idle poll
interval, and the minimum brightness value (0 or the panel's lowest level).
