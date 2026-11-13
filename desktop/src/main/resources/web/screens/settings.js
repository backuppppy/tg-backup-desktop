// Settings screen (inventory 2e, desktop version): background work with its two switches, the phone access (on the
// PC: its switch, password, state and address; on the phone: a logout), and about with the version and links.
import { api } from '../lib/api.js';
import { h, toast, formDialog } from '../lib/ui.js';

const BACKGROUND_TEXT =
  'כשמשימה או ייצוא רצים וסוגרים את החלון, התוכנה ממשיכה לפעול ברקע (ענן ליד השעון). כשאין משימות פעילות, סגירת החלון מסיימת אותה. כדי שהמשימות יתקדמו, המחשב צריך להיות דלוק.';

/** What a server without desktop prefs means: today's behavior. */
const DEFAULT_PREFS = { autostart: false, keepAwake: true };

/** What a server without the phone access means: off, no password. */
const DEFAULT_REMOTE = { enabled: false, state: 'Off', url: null, detail: null, devices: 0, hasPassword: false };

const REMOTE_STEPS = [
  'מתקינים את Tailscale במחשב ובטלפון (tailscale.com/download) ומתחברים לאותו חשבון.',
  'פעם אחת, באתר של Tailscale, בעמוד DNS, לוחצים Enable HTTPS. שם המחשב ב-Tailscale נרשם ביומן ציבורי של תעודות, לכן כדאי שלא יכיל מידע פרטי.',
  'מדליקים את המתג וקובעים סיסמה.',
  'סורקים את הקוד בטלפון, מזינים את הסיסמה, ובתפריט של כרום בוחרים "התקן אפליקציה".',
];

/** Each state's line, and its link where it has one. */
const REMOTE_STATES = {
  Off: { text: () => 'כבוי.' },
  NotInstalled: { text: () => 'Tailscale לא מותקן במחשב.', link: ['להורדה', 'https://tailscale.com/download'] },
  NotConnected: { text: () => 'Tailscale מותקן אבל לא מחובר. פותחים את Tailscale ליד השעון ומתחברים.' },
  HttpsOff: { text: () => 'צריך להפעיל https בחשבון Tailscale (שלב 2).', link: ['לעמוד DNS', 'https://login.tailscale.com/admin/dns'] },
  PortBusy: { text: () => 'הפורט 47800 במחשב תפוס על ידי תוכנה אחרת.' },
  ServeBusy: { text: () => 'ב-Tailscale כבר מוגדר שירות אחר על https. התוכנה לא דורסת אותו.' },
  Error: { text: (st) => `שגיאה מ-Tailscale: ${st.detail ?? ''}` },
  Running: { text: () => 'פועל. סורקים את הקוד בטלפון:' },
};

const STAYS_ON = 'המתג נשאר דלוק, והחיבור יעלה לבד כשזה יסודר.';

let view = null;

export function render(root, state) {
  const remoteViewer = state.viewer === 'remote';
  if (!view || view.root !== root || !root.contains(view.version) || view.remoteViewer !== remoteViewer) view = build(root, remoteViewer);
  // Taken only when the server sends new prefs: a render for another event must not undo a save just answered.
  if (state.desktopPrefs !== view.seen) {
    view.seen = state.desktopPrefs;
    view.prefs = { ...DEFAULT_PREFS, ...(state.desktopPrefs ?? {}) };
  }
  // A switch being saved keeps the user's choice until the server's answer comes back.
  if (view.saving === 0) syncSwitches();
  const text = state.version ? `גרסה ${state.version} · TDLib ${state.tdlibVersion}` : '';
  if (view.version.textContent !== text) view.version.textContent = text;
  if (!remoteViewer) renderRemote({ ...DEFAULT_REMOTE, ...(state.remote ?? {}) });
}

// ---------------------------------------------------------------- the phone access (the PC)

function renderRemote(st) {
  const r = view.remote;
  r.status = st;
  // A switch being saved (or waiting for its password) keeps the user's choice until the server's answer comes back.
  if (r.saving === 0 && r.toggle.input.checked !== st.enabled) r.toggle.input.checked = st.enabled;
  const key = JSON.stringify([st.state, st.detail, st.url, st.devices]);
  if (r.key === key) return;
  r.key = key;
  const shown = REMOTE_STATES[st.state] ?? REMOTE_STATES.Error;
  const link = shown.link ? h('a', { class: 'button text', href: shown.link[1], target: '_blank', rel: 'noopener' }, shown.link[0]) : null;
  const running = st.state === 'Running';
  r.body.replaceChildren(...[
    h('p', { class: 'remote-state' }, shown.text(st)),
    st.state !== 'Off' && !running ? h('p', {}, STAYS_ON) : null,
    link,
    ...(running ? runningPart(st) : [h('ol', { class: 'remote-steps' }, REMOTE_STEPS.map((t) => h('li', {}, t)))]),
  ].filter(Boolean));
}

function runningPart(st) {
  return [
    h('p', { class: 'remote-url', dir: 'ltr' }, st.url ?? ''),
    h('img', { class: 'remote-qr', src: '/api/remote/qr.svg', alt: '' }),
    h('div', { class: 'row' }, h('button', { type: 'button', class: 'outlined', onclick: () => copy(st.url ?? '') }, 'העתק')),
    h('p', {}, `מכשירים מחוברים: ${st.devices}`),
    h('div', { class: 'row' },
      h('button', { type: 'button', class: 'outlined', onclick: () => passwordDialog().result }, 'החלף סיסמה'),
      h('button', { type: 'button', class: 'outlined', onclick: () => disconnectAll() }, 'נתק את כל המכשירים'),
    ),
  ];
}

async function copy(text) {
  try {
    await navigator.clipboard.writeText(text);
  } catch {
    // No clipboard API (or refused): the old way.
    const area = h('textarea', { readonly: true, style: 'position:fixed;opacity:0' }, text);
    document.body.append(area);
    area.select();
    document.execCommand('copy');
    area.remove();
  }
  toast('הועתק');
}

async function disconnectAll() {
  try {
    await api('POST', '/api/remote/disconnect-all');
    toast('כל המכשירים נותקו');
  } catch (e) {
    toast(e.message);
  }
}

/** Asks for the password twice (at least 6 characters) and saves it; the result is true once saved. */
function passwordDialog() {
  const field = (label) => {
    const input = h('input', { type: 'password', autocomplete: 'new-password', dir: 'ltr' });
    return { input, row: h('label', { class: 'field' }, h('span', { class: 'field-label' }, label), input) };
  };
  const pw = field('סיסמה');
  const again = field('שוב את הסיסמה');
  const error = h('p', { class: 'error-text', hidden: true });
  const fail = (msg) => {
    error.textContent = msg;
    error.hidden = false;
    return false;
  };
  const save = async () => {
    if (pw.input.value.length < 6) return fail('לפחות 6 תווים');
    if (pw.input.value !== again.input.value) return fail('הסיסמאות לא זהות');
    try {
      await api('PUT', '/api/remote/password', { password: pw.input.value });
      return true;
    } catch (e) {
      return fail(e.message);
    }
  };
  return formDialog({
    title: 'סיסמה לגישה מהטלפון',
    okText: 'שמור',
    cancelText: 'ביטול',
    okClass: '',
    onOk: save,
    content: [pw.row, again.row, error],
  });
}

/** The switch: on without a password asks for one first; a refusal shows its error and puts it back. */
async function remoteSwitched() {
  const r = view.remote;
  const on = r.toggle.input.checked;
  r.saving += 1;
  try {
    if (on && !r.status.hasPassword && !(await passwordDialog().result)) {
      r.toggle.input.checked = false;
      return;
    }
    await api('PUT', '/api/remote/enabled', { enabled: on });
  } catch (e) {
    toast(e.message);
    if (r.saving === 1) r.toggle.input.checked = r.status.enabled;
  } finally {
    r.saving -= 1;
    if (r.saving === 0 && view?.remote === r) renderRemote(r.status);
  }
}

// ---------------------------------------------------------------- the phone's own card

async function logout() {
  try {
    await api('POST', '/api/logout');
  } catch {
    // Already out: the login page either way.
  }
  globalThis.location.replace('/login.html');
}

function syncSwitches() {
  if (view.autostart.input.checked !== view.prefs.autostart) view.autostart.input.checked = view.prefs.autostart;
  if (view.keepAwake.input.checked !== view.prefs.keepAwake) view.keepAwake.input.checked = view.prefs.keepAwake;
}

/** Saves both switches as shown; a refusal shows its error and puts them back to the server's prefs. */
async function save() {
  const v = view;
  v.saving += 1;
  const sent = { autostart: v.autostart.input.checked, keepAwake: v.keepAwake.input.checked };
  const seenAtSend = v.seen;
  try {
    await api('PUT', '/api/desktop-prefs', sent);
    // Saved: the server holds these now, even before its desktopPrefs event comes. An event that came while the
    // PUT ran is this save's own or a later change, so it is kept.
    if (v.seen === seenAtSend) v.prefs = { ...v.prefs, ...sent };
  } catch (e) {
    toast(e.message);
    if (v.saving === 1) {
      v.autostart.input.checked = v.prefs.autostart;
      v.keepAwake.input.checked = v.prefs.keepAwake;
    }
  } finally {
    v.saving -= 1;
    // The last save to settle shows the server's prefs (an earlier failure or a change elsewhere may have come meanwhile).
    if (v.saving === 0 && view === v) syncSwitches();
  }
}

const toggle = (label) => {
  const input = h('input', { type: 'checkbox', role: 'switch', onchange: () => save() });
  return { input, row: h('label', { class: 'switch-row' }, input, h('span', { class: 'pre' }, label)) };
};

function remoteCard() {
  const input = h('input', { type: 'checkbox', role: 'switch', onchange: () => remoteSwitched() });
  const toggle = { input, row: h('label', { class: 'switch-row' }, input, h('span', { class: 'pre' }, 'גישה מהטלפון דרך Tailscale')) };
  const body = h('div', { class: 'remote-body' });
  const el = h('section', { class: 'card remote-card' }, h('h2', {}, 'גישה מהטלפון'), toggle.row, body);
  return { el, toggle, body, key: null, saving: 0, status: { ...DEFAULT_REMOTE } };
}

function phoneCard() {
  return h('section', { class: 'card' },
    h('h2', {}, 'חיבור מהטלפון'),
    h('p', {}, 'מחובר למחשב דרך Tailscale.'),
    h('div', { class: 'row' }, h('button', { type: 'button', class: 'outlined', onclick: () => logout() }, 'התנתק')),
  );
}

function build(root, remoteViewer) {
  const remote = remoteViewer ? null : remoteCard();
  const version = h('p', {});
  const link = (href, label) => h('a', { class: 'button text', href, target: '_blank', rel: 'noopener' }, label);
  const autostart = toggle('הפעלה עם Windows (בלי חלון, ליד השעון)');
  const keepAwake = toggle('מניעת שינה בזמן שמשימה או ייצוא רצים (המסך ממשיך להיכבות)');
  root.replaceChildren(
    h('section', { class: 'card' },
      h('h2', {}, 'עבודה ברקע'),
      h('p', {}, BACKGROUND_TEXT),
      autostart.row,
      keepAwake.row,
    ),
    remoteViewer ? phoneCard() : remote.el,
    h('section', { class: 'card' },
      h('h2', {}, 'אודות'),
      version,
      h('div', { class: 'row' },
        link('https://t.me/backuppppy', 'קבוצת טלגרם'),
        link('https://github.com/backuppppy/tg-backup-desktop', 'GitHub'),
      ),
    ),
  );
  return { root, remoteViewer, remote, version, autostart, keepAwake, prefs: { ...DEFAULT_PREFS }, seen: undefined, saving: 0 };
}
