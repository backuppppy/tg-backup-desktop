// Pure helpers of the Export screen (inventory 2c), ported from Android ExportScreen.kt and export-core Format.kt.
// No DOM here: everything is unit-tested under node. Times are unixtime seconds, 0 = unset.

export const MiB = 1024 * 1024;
const SIZE_VALUE_COUNT = 100;
/** The 10-minute gap Telegram Desktop keeps between From and To. */
export const OFFSET = 600;

/** Desktop's SizeLimitByIndex for the 100-step size slider (1 MB .. 4000 MB). */
export function sizeLimitByIndex(i) {
  const index = i + 1;
  let mb;
  if (index <= 10) mb = index;
  else if (index <= 30) mb = 10 + (index - 10) * 2;
  else if (index <= 40) mb = 50 + (index - 30) * 5;
  else if (index <= 60) mb = 100 + (index - 40) * 10;
  else if (index <= 70) mb = 300 + (index - 60) * 20;
  else if (index <= 80) mb = 500 + (index - 70) * 50;
  else if (index <= 90) mb = 1000 + (index - 80) * 100;
  else mb = 2000 + (index - 90) * 200;
  return mb * MiB;
}

/** The first slider index whose limit is at least `limit`. */
export function indexBySizeLimit(limit) {
  for (let i = 0; i < SIZE_VALUE_COUNT; i += 1) if (sizeLimitByIndex(i) >= limit) return i;
  return SIZE_VALUE_COUNT - 1;
}

const div = (a, b) => Math.floor(a / b);

/** Ui::FormatDownloadText: "{ready} / {total} {unit}". */
export function formatDownload(ready, total) {
  if (total >= MiB) {
    const r = div(ready * 10, MiB);
    const t = div(total * 10, MiB);
    return `${div(r, 10)}.${r % 10} / ${div(t, 10)}.${t % 10} MB`;
  }
  if (total >= 1024) return `${div(ready, 1024)} / ${div(total, 1024)} KB`;
  return `${ready} / ${total} B`;
}

/** export-core FormatSizeText. */
export function formatSizeText(size) {
  if (size >= MiB) {
    const tenth = div(size * 10, MiB);
    return `${div(tenth, 10)}.${tenth % 10} MB`;
  }
  if (size >= 1024) {
    const tenth = div(size * 10, 1024);
    return `${div(tenth, 10)}.${tenth % 10} KB`;
  }
  return `${size} B`;
}

const clamp01 = (x) => (x < 0 ? 0 : x > 1 ? 1 : x);

/** ContentFromState of export_view_content.cpp: `[{label, info, progress}]` for a ProcessingDto. */
export function progressRows(s) {
  const rows = [];
  const row = (label, info, progress) => rows.push({ label, info, progress: clamp01(progress) });
  const mainProgress = () => {
    if (s.substepsTotal === 0) return 0;
    const done = s.substepsPassed / s.substepsTotal;
    const addPart = (index, count) => (count > 0 ? (s.substepsNow * index) / (s.substepsTotal * count) : 0);
    const add = s.entityCount === 1 && s.entityIndex === 0
      ? addPart(s.itemIndex, s.itemCount)
      : addPart(s.entityIndex, s.entityCount);
    return done + add;
  };
  const mainInfo = s.entityCount > 0 ? `${s.entityIndex + 1} / ${s.entityCount}` : '';
  switch (s.step) {
    case 'Initializing':
      row('Initializing...', mainInfo, mainProgress());
      break;
    case 'DialogsList':
      row('Processing chats...', mainInfo, mainProgress());
      break;
    case 'Dialogs':
    case 'Topic': {
      if (s.step === 'Topic' || s.entityCount > 1) row('Chats', mainInfo, mainProgress());
      let name = s.entityName;
      if (!s.entityName) name = 'Deleted';
      else if (s.entityType === 'SavedMessages') name = 'Saved Messages';
      else if (s.entityType === 'RepliesMessages') name = 'Replies';
      row(name, s.itemCount > 0 ? `${s.itemIndex} / ${s.itemCount}` : '', s.itemCount > 0 ? s.itemIndex / s.itemCount : 0);
      break;
    }
    default:
      row(s.step, mainInfo, mainProgress());
  }
  if (s.bytesCount > 0) row(s.bytesName, formatDownload(s.bytesLoaded, s.bytesCount), s.bytesLoaded / s.bytesCount);
  return rows;
}

/** A From time that would reach To moves to 10 minutes before it (Android's time dialog for From). */
export const clampFrom = (from, till) => (from >= till && till !== 0 ? till - OFFSET : from);

/** A To that would not be after From moves to 10 minutes after it (date and time dialogs for To). */
export const clampTill = (from, till) => (till <= from && from !== 0 ? from + OFFSET : till);

// ---- dates (Locale.getDefault() on Android: Hebrew here) ----

const LOCALE = 'he-IL';

function parts(t, timeZone) {
  const fmt = new Intl.DateTimeFormat(LOCALE, {
    year: 'numeric', month: 'long', day: 'numeric', hour: '2-digit', minute: '2-digit', hourCycle: 'h23',
    ...(timeZone ? { timeZone } : {}),
  });
  const num = new Intl.DateTimeFormat('en-US', {
    year: 'numeric', month: 'numeric', day: 'numeric', hour: 'numeric', minute: 'numeric', hourCycle: 'h23',
    ...(timeZone ? { timeZone } : {}),
  });
  const date = new Date(t * 1000);
  const p = {};
  for (const { type, value } of num.formatToParts(date)) p[type] = value;
  for (const { type, value } of fmt.formatToParts(date)) if (type === 'month') p.monthName = value;
  return p;
}

const pad = (v) => String(v).padStart(2, '0');

/** SimpleDateFormat for the patterns the screen uses: d dd MM MMMM yyyy HH mm and 'quoted' text. */
export function formatTime(t, pattern, timeZone) {
  const p = parts(t, timeZone);
  const hour = Number(p.hour) % 24;
  const tokens = {
    dd: pad(p.day), d: String(Number(p.day)), MMMM: p.monthName, MM: pad(p.month), yyyy: p.year,
    HH: pad(hour), mm: pad(p.minute),
  };
  return pattern.replace(/'([^']*)'|dd|d|MMMM|MM|yyyy|HH|mm/g, (m, quoted) => (quoted !== undefined ? quoted : tokens[m]));
}

/** `d MMMM yyyy, HH:mm`: how From and To are shown. */
export const shownTime = (t, timeZone) => `${formatTime(t, 'd MMMM yyyy', timeZone)}, ${formatTime(t, 'HH:mm', timeZone)}`;

/** Local midnight of the day of `t` (Android's local0). */
export function localMidnight(t) {
  const d = new Date(t * 1000);
  return dayStartOf(d.getFullYear(), d.getMonth(), d.getDate());
}

function dayStartOf(year, month, day) {
  const d = new Date(2000, 0, 1, 0, 0, 0, 0);
  d.setFullYear(year, month, day);
  return Math.floor(d.getTime() / 1000);
}

/** Local midnight of an `<input type=date>` value (`YYYY-MM-DD`), or null when it is not a date. */
export function dayStart(value) {
  const m = /^(\d{4,})-(\d{2})-(\d{2})$/.exec(value ?? '');
  return m ? dayStartOf(Number(m[1]), Number(m[2]) - 1, Number(m[3])) : null;
}

/** The `<input type=date>` value of the local day of `t`. */
export function dateInputDay(t) {
  const d = new Date(t * 1000);
  return `${String(d.getFullYear()).padStart(4, '0')}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
}

/** The `<input type=time>` value (24h) of `t`. */
export function timeInputValue(t) {
  const d = new Date(t * 1000);
  return `${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

/** Android's time dialog: h*3600 + m*60 on the local midnight of `current`. Null when `value` is not `HH:MM`. */
export function withTime(current, value) {
  const m = /^(\d{2}):(\d{2})/.exec(value ?? '');
  return m ? Number(m[1]) * 3600 + Number(m[2]) * 60 + localMidnight(current) : null;
}

// ---- texts ----

/** Kotlin's String.toIntOrNull(). */
function toIntOrNull(s) {
  if (!/^[+-]?\d+$/.test(s)) return null;
  const n = Number(s);
  return n >= -2147483648 && n <= 2147483647 ? n : null;
}

/** The TAKEOUT_INIT_DELAY_ text for a wait of `seconds`, `now` in unixtime. */
export function takeoutDelayText(seconds, now, timeZone) {
  const hours = Math.floor(seconds / 3600);
  const hoursText = hours <= 0 ? 'less than an hour' : `${hours} hours`;
  const whenText = formatTime(now + seconds, "d MMMM yyyy 'at' HH:mm", timeZone);
  return `For security reasons, you will be able to begin downloading your data in ${hoursText}. ` +
    "We have notified all your devices about the export request to make sure it's authorized and give you time to react if it's not.\n\n" +
    `Please come back on ${whenText} and repeat the request using the same device.`;
}

/** The ApiError text of the progress panel. */
export function apiErrorText(code, type, now, timeZone) {
  if (type === 'TAKEOUT_INVALID') return 'Sorry, you started a new data export, so this data export has been canceled.';
  const prefix = 'TAKEOUT_INIT_DELAY_';
  if (type.startsWith(prefix)) {
    const seconds = Math.max(toIntOrNull(type.slice(prefix.length)) ?? 1, 1);
    return takeoutDelayText(seconds, now, timeZone);
  }
  return `API Error happened :(\n${code}: ${type}`;
}

const TYPE_LABELS = {
  Self: 'הודעות שמורות',
  Replies: "צ'אט פרטי",
  VerifyCodes: "צ'אט פרטי",
  Personal: "צ'אט פרטי",
  Bot: 'בוט',
  PrivateGroup: 'קבוצה פרטית',
  PrivateSupergroup: 'קבוצה פרטית',
  PublicSupergroup: 'קבוצה ציבורית',
  PrivateChannel: 'ערוץ פרטי',
  PublicChannel: 'ערוץ ציבורי',
  Unknown: '',
};

/** DialogInfo.Type label. */
export const typeLabel = (type) => TYPE_LABELS[type] ?? '';

const blank = (s) => !s || !s.trim();

/** The chat card's second line: type, @username, פורום, בארכיון joined by ` · `. */
export function chatSubtitle(c) {
  return [
    typeLabel(c.type),
    blank(c.username) ? null : `@${c.username}`,
    c.isForum ? 'פורום' : null,
    c.archived ? 'בארכיון' : null,
  ].filter((x) => x !== null).join(' · ');
}

/** The search: title or username contains it, ignoring case; blank shows every chat. */
export function filterChats(chats, search) {
  if (blank(search)) return chats;
  const q = search.toLowerCase();
  return chats.filter((c) => c.title.toLowerCase().includes(q) || (c.username ?? '').toLowerCase().includes(q));
}
