# Ordered mode: accounts send in turns; the Android app gets the live protection fix

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

## Testing

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
