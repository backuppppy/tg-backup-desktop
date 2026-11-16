# Ordered mode sends in turns; fast mode is not held by a stuck account; the Android app gets the live protection fix

Approved by the user on 2026-11-16 in conversation ("כל מה שכתבת הבנת נכון", "לכלול גם את הערוץ מוגן באפליקציה").
Applies to both engines: TG Backup Desktop (`engine/`, this repo) and the Android app (`backuppppy/tg-backup-apk`,
branch `takeout-export`, `app/src/main/java/org/tgbackup/app/`). The user asked for great care: this changes the engine.

## 1. Ordered mode rotates the sender

**Why.** In ordered mode (📑 מסודר), `runOrdered` sends every message from `accounts[0]` (`val sender = accounts[0]`).
The other accounts only download ahead when the source is protected. With several accounts the user wants the work and
Telegram's per-account limits (FloodWait, the per-minute cap) shared, while the target keeps the source's order.

**What the user wants.**
- With more than one account, each message is sent by the next account in turn: one message each, round robin.
- Still one message at a time. A message is sent only after the previous one finished (sent, skipped or failed), so
  the target's order equals the source's order.
- An account in FloodWait, or not connected, loses its turns until it is free. The message goes to the next free
  account. When every account is waiting, the job waits for the first one to be free.
- An account that cannot send a message (for example it is not a member of the target, or it cannot see the source
  message) does not lose the message: the message is tried on the next account. Only when every account failed is
  it counted as failed (as today, `report.failed()`).
- With one account nothing changes: FloodWait waits and retries on the same account.
- Fast mode (⚡ מהיר) does not change.
- Protected sources: prefetch (other accounts download ahead) works as today. The sending account uploads the file
  from the local copy, whichever account downloaded it.

**Design.**
- A pure class `SenderRotation(size: Int, now: () -> Long)` holds the turn and each account's "busy until" time:
  - `pick(usable: (Int) -> Boolean, exclude: Set<Int>): Pick` returns the next account in turn that is usable, not
    excluded, and not busy (`Pick.Account(index)`). If none is free, it returns how long until the first busy one is
    free (`Pick.Wait(ms)`). If none is usable at all, it returns `Pick.None`.
  - `busy(index, untilMs)` marks an account after FloodWait. Every pick of an account advances the turn past it.
- `runOrdered` uses it per message. The sender is the picked account, and the target chat is that account's resolved
  target (`chats[i].target.id`). The message object is the sender's own copy: `accounts[0]` keeps the one it read, and
  any other account loads it with `client.getMessage(chatId, id)`, as `downloadWith` already does. If that copy cannot
  be loaded, the account is excluded for this message and the next one is tried.
- `deliver` gets a hand-off mode, used by ordered mode with more than one account:
  - On FloodWait it returns `Delivery.FloodWait(seconds)` instead of sleeping. The caller marks the account busy and
    retries the same message on the next account.
  - After its retries on one account fail, it returns `Delivery.Failed` without `report.failed()`. The caller tries the
    next account, and calls `report.failed()` only when every account was tried.
  - `Delivery.Sent` and `Delivery.Skipped` (empty after link stripping, or a type a protected source cannot copy)
    end the message as today.
  - Without hand-off (one account, or fast mode) it behaves exactly as today.
- Progress (`lastId`, `copiedTotal`) is saved after each message, as today. A message is never skipped by the
  rotation itself.
- The per-account `RateLimiter` stays per account. With turns, the job's total rate can reach the sum.
- The random 1.4-3.8 s pause after a server-side copy stays, per message.
- Log lines name the sending account, as `deliver` already does (`[$tag · ${sender.tag}]`).

**Approaches considered.** (a) Round robin per message with hand-off on FloodWait and failure (chosen, approved).
(b) Round robin per batch of N messages, like the user's Telethon script: coarser sharing, and its FloodWait handling
lost the failed message. (c) Truly parallel sends: faster, but the target's order is then decided by who finishes
first, so ordered mode would not be ordered.

## 2. The Android app follows the source's protection live

**Why.** The same bug as the desktop (fixed there on 2026-11-15, commits 0c26856..7ea505d). `AccountClient.chatCache`
keeps the `Chat` snapshot, so `hasProtectedContent` never refreshes. A copy refused for protection sets
`protected = true` until the run ends.

**Design.** Port the desktop change as it now stands, including the review fixes:
- `SourceProtection`
- `ProtectionNotice` / `protectionNotices` from `UpdateChatHasProtectedContent`
- `resolveChat` reading the current chat
- the watcher started `UNDISPATCHED` before the flag is read
- `needsUpload` re-reading a stale `canBeSaved`
- `protectionRefused` checking `getChat`
- unused prefetches awaited and deleted

The Android `JobRunner` differs only in small places (no `report.latest`, no `copiedTotal`). The port keeps those
differences.

## 3. Fast mode: a stuck account does not hold the wave

Approved by the user on 2026-11-16 ("כן מסכים כל מה שאמרת"). Applies to both engines.

**Why.** `runFast` splits each wave into fixed slices of 25 messages. Slice `i` belongs to account `i`, and the next
wave starts only after `awaitAll`. One account in a 20-minute FloodWait, or disconnected, holds every other account
for the whole wait.

**What the user wants.**
- An account counts as stuck when it gets a FloodWait longer than 60 s, or when it is not connected. It hands back the
  rest of its slice, including the message it was stuck on, and the first free account takes it over.
- A short FloodWait (60 s or less) waits and continues in place, as today.
- An account that is only slow (a big upload) is not stuck, and nothing is taken from it.
- The wave ends when every message in it was handled, by whichever account. The stuck account joins the next wave once
  it is free. No message is lost or sent twice by the hand-off.

**Design.**
- Slices stay as today. Slice `i` covers `ws + i*25 .. min(ws + i*25 + 24, we)`, and it starts with account `i`.
  `Progress.workerLast` is already keyed by slice index, so the saved format does not change. Resume after a restart
  works as today: each unfinished slice continues from `workerLast[i] + 1`.
- Per wave: a queue of slices waiting for an account. A worker loop per account takes its own slice first, then any
  queued slice, until no slice is queued and none is held. Then it ends.
- A connected account whose slice is done takes the next queued slice. A slice whose account is not connected at
  wave start is queued at once.
- `rangeWorker` runs one slice for one account, on that account's own history reads (as today). Before each message
  it checks the account is connected. If it is not, it queues the rest of the slice and stops.
- A FloodWait above 60 s comes back from `deliver` as `Delivery.FloodWait`, the hand-off of part 1, with the threshold
  as a parameter. The worker then queues the rest of the slice: `workerLast[i]` is not advanced past the stuck
  message. The account sits out until its wait ends, then takes queued slices if any are left.
- One slice is worked by one account at a time. Release happens only after the worker stopped touching the slice, so
  two accounts never send the same message.
- The wave's end (`lastId = waveEnd`) is saved only when every slice reached its end.

**Approaches considered.** (a) Hand back the rest of the slice on a long FloodWait or a disconnect (chosen,
approved). (b) Let a free account start the next wave while one is stuck: the saved progress would need several open
waves, which is a schema change and riskier. (c) Time-based stealing from slow accounts: it would also split healthy
slow uploads, which the user ruled out.

## Testing

- The wave's slice queue (claim, release, done) is a pure class with unit tests: a released slice is taken by the next
  free account; the wave ends only when all slices are done; a slice is never held by two accounts.
- `SenderRotation` and `SourceProtection` are pure, with unit tests: the turn order; a FloodWait account skipped until
  free; all busy gives a wait; excluded and unusable accounts are skipped; one account never rotates.
- The wiring needs live Telegram clients. It is checked by compiling and running the existing suites (desktop and
  Android CI), by a fresh reviewer, and by the user's live test. The live test is an ordered job with two or more
  accounts: the target keeps the order, the log shows the accounts in turns, and a FloodWait on one account moves the
  work to the others.
- The Android app ships as version 2.2.21 (git push and a GitHub release with the APK, per the project's rule). The
  desktop ships through the usual pre-release with the version in the file name.

## Left to the builder

- Where `SenderRotation` lives (engine package) and how its tests run on Android: the app module may have no unit-test
  setup. Same source in both repos, tested in the desktop engine, is acceptable.
- Exact log wording, in Hebrew, in the existing style.
