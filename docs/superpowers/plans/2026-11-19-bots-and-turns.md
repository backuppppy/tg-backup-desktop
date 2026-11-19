# Bots as helper senders + N messages per turn in ordered mode (desktop + Android)

User decisions (2026-11-19): a bot only helps sending (a job needs at least one regular account, which reads the
source); "messages per turn" is a job setting, default 3; the user creates a test bot and sends its token for a live
test.

## Facts that shape the design
- A bot logs in with a token (TDLib `checkAuthenticationBotToken`), no phone/code. TDLib still needs API ID/hash.
- A bot cannot read history (`getHistory`) nor load chat lists (`loadChats`); it can fetch messages by id
  (`getMessages`) in a chat it is a member of. Our ordered/fast modes already read by id (IdWindow), so a bot can copy
  a message server-side when it is a member (admin) of the source and an admin of the target.
- A bot cannot open an export (takeout) session: "בלי הורדה" stays for regular accounts only.
- Telegram limits a bot to ~20 messages per minute in one chat: a bot's per-minute limit is capped at 20.

## Task 1 — model + login (engine, both)
- `Account.botToken: String = ""` (`isBot` = token not blank). Phone empty for bots.
- `AccountClient`: on `AuthorizationStateWaitPhoneNumber`, a bot sends its token (`checkAuthenticationBotToken`)
  instead of showing NeedPhone; errors (bad token) shown as today. `chatByIds`: skip `loadChats` for bots.
- Tests: pure helpers (`isBot`, API credentials taken from the first regular account when the bot form leaves them
  empty).

## Task 2 — N messages per turn (engine, both)
- `Job.messagesPerTurn: Int = 3` (1..10).
- `SenderRotation(size, turnSize)`: `pick` keeps the current holder for up to `turnSize` messages while it is free;
  `sent(i, restMs)` counts the turn and rests the account only when its turn ends (hot ×3 rule unchanged); a FloodWait
  (`busy`) or a failure ends the turn at once.
- Tests in `SenderRotationTest`: turns of 3 with 2/3 accounts, busy in the middle of a turn, turnSize 1 = today.

## Task 3 — bots in jobs (engine + server, both)
- Validation: a job with bots needs at least one regular account; the reader (`accounts[0]`) is always a regular
  account (bots are ordered after the regular ones); `viaTakeout` with a bot is refused with a Hebrew message.
- `RateLimiter` per account: `min(job.perMinuteLimit or 25, 20)` for a bot.
- Fast mode: a bot's slice reads by id like the others; a bot that cannot see the source hands its slice over (the
  existing stuck/hand-off path).

## Task 4 — UI (desktop web + Android)
- Accounts screen: `🤖 הוסף בוט` → dialog: name, bot token; API ID/hash prefilled from the first regular account.
  Bot cards show 🤖 and "בוט מחובר כ-@name".
- Job editor: bots listed with 🤖; "הודעות בכל תור" field (1..10, default 3) shown in ordered mode with 2+ senders;
  the takeout switch is disabled when a bot is selected, with a note why.
- Playwright (desktop): add bot dialog, job editor rules.

## Task 5 — live test, review, ship
- Live (`/root/tg-live`): the user's test bot as an extra sender in an ordered run (turns of 3) and a fast run.
- Fresh reviewer; desktop build + Android 2.2.33; releases; APK to Download/TG Backup; checked direct links.
