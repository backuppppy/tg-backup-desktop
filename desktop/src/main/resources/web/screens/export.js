// Export screen (inventory 2c): ExportSetup (intro + account, session card, chat picker, options, Export) or, while
// `runningExport` is set, the ProgressPanel. Texts, enable rules and toasts are the Android ones (ExportScreen.kt);
// the English texts are Telegram Desktop's. The Android storage-permission row is dropped: Export is always enabled.
//
// Elements are built once and patched in place, so events (log, prefs, 300 ms progress samples) never wipe what is
// typed (search, path, dates), never steal the focus and never swallow a click on a button.
import { api } from '../lib/api.js';
import { h, toast, formDialog } from '../lib/ui.js';
import {
  MiB, sizeLimitByIndex, indexBySizeLimit, formatSizeText, progressRows, clampFrom, clampTill, apiErrorText,
  typeLabel, filterChats, dayStart, dateInputDay, timeInputValue, withTime, formatTime, shownTime,
} from '../lib/exportFormat.js';

const MEDIA = [
  ['Photos (תמונות)', 0x01],
  ['Videos (סרטונים)', 0x02],
  ['Voice messages (הודעות קוליות)', 0x04],
  ['Video messages (הודעות וידאו)', 0x08],
  ['Stickers (מדבקות)', 0x10],
  ['GIFs', 0x20],
  ['Files (קבצים)', 0x40],
];
const FORMATS = [['Human-readable HTML', 'Html'], ['Machine-readable JSON', 'Json'], ['Both', 'HtmlAndJson']];
const STOP_TEXT = "Are you sure you want to stop exporting your data?\n\nIf you do, you'll need to start over.";
/** `busy` while chats load: the text then comes from the `chatsProgress` count. */
const CHATS_BUSY = Symbol('chats');

const enc = encodeURIComponent;
const blank = (s) => !s || !s.trim();
const nowSecs = () => Math.floor(Date.now() / 1000);
const accLabel = (acc) => (blank(acc.name) ? acc.phone : acc.name);
/** A Latin (or mixed) run keeps its own direction inside the RTL page. */
const bdi = (text) => h('bdi', {}, text);
/** Android's toast is `<prefix>${it.message}`; the server already sends that text, so add the prefix only if missing. */
const prefixed = (prefix, msg) => (msg.startsWith(prefix) ? msg : prefix + msg);
const post = (path, body) => api('POST', path, body).catch((e) => toast(e.message));

// ---------------------------------------------------------------- setup state

let epoch = 0;
/** ExportSetup's remember{} state. It starts over when the panel shows (Android's setup leaves composition then). */
function freshSetup() {
  epoch += 1;
  return {
    epoch, accountId: null, loggedIn: null, sessionGen: 0, busy: null, chats: null, search: '', chat: null,
    topics: null, topic: null, topicsGen: 0, from: 0, till: 0,
  };
}
let s = freshSetup();

let view = null;
let last = null;

export function render(root, state) {
  last = state;
  const mode = state.runningExport ? 'panel' : 'setup';
  if (!view || view.root !== root || view.mode !== mode || !root.contains(view.el)) {
    const comesBack = view && view.mode === mode && mode === 'setup';
    if (mode === 'panel' && view?.mode !== 'panel') s = freshSetup();
    view = mode === 'panel' ? buildPanel(root) : buildSetup(root);
    root.replaceChildren(view.el);
    // A screen that comes back asks the export session again, as Android's LaunchedEffect does when composed.
    if (comesBack && s.accountId) checkSession(false);
  }
  if (mode === 'panel') updatePanel(state.runningExport);
  else updateSetup(state);
}

function redraw() {
  if (view && last && view.root.contains(view.el)) render(view.root, last);
}

/** The shell put the screen back in view (it was detached, not rebuilt): ask the export session again. */
export function comeBack() {
  if (view?.mode === 'setup' && s.accountId) checkSession(false);
}

// ---------------------------------------------------------------- setup

function buildSetup(root) {
  const v = { root, mode: 'setup' };
  v.radios = h('div', { class: 'radios', role: 'radiogroup', 'aria-label': 'חשבון:' });
  v.intro = h('section', { class: 'card export-intro' },
    h('h2', {}, "ייצוא היסטוריית צ'אט"),
    h('p', { class: 'small' }, 'ייצוא ערוץ, קבוצה או צ\'אט בדיוק כמו "Export chat history" של טלגרם דסקטופ (HTML / JSON), דרך Takeout session.'),
    h('div', { class: 'section-label' }, 'חשבון:'),
    v.radios);
  v.session = h('section', { class: 'card export-session' });

  v.loadButton = h('button', { type: 'button', onclick: () => loadChats() });
  v.busyText = h('span', { class: 'small' });
  v.search = h('input', { type: 'text', autocomplete: 'off', dir: 'auto', oninput: () => { s.search = v.search.value; redraw(); } });
  v.searchRow = h('label', { class: 'field' }, h('span', { class: 'field-label' }, 'חיפוש'), v.search);
  v.list = h('div', { class: 'chat-list' });
  v.picker = h('div', { class: 'export-picker' }, h('div', { class: 'row' }, v.loadButton, v.busyText), v.searchRow, v.list);
  v.optionsSlot = h('div', { class: 'options-slot' });
  v.el = h('div', { class: 'export' }, v.intro, v.session, v.picker, v.optionsSlot);
  v.keys = {};
  return v;
}

/** Replaces `el`'s children only when `key` changed since the last time. */
function patch(v, name, key, el, build) {
  if (v.keys[name] === key) return;
  v.keys[name] = key;
  el.replaceChildren(...[build()].flat(Infinity).filter(Boolean));
}

function busyText(state) {
  if (s.busy === CHATS_BUSY) {
    const n = state.chatsLoading?.[s.accountId];
    return n > 0 ? `טוען צ'אטים… ${n}` : "טוען צ'אטים…";
  }
  if (s.busy !== null) return s.busy;
  // A load this page did not start (it reloaded mid-load): the server still reports it.
  const n = s.accountId ? state.chatsLoading?.[s.accountId] : undefined;
  if (n === undefined) return null;
  return n > 0 ? `טוען צ'אטים… ${n}` : "טוען צ'אטים…";
}

function updateSetup(state) {
  const v = view;
  const accounts = state.accounts ?? [];
  const ready = new Set((state.accountStates ?? []).filter((x) => x.auth?.type === 'Ready').map((x) => x.id));
  const radios = accounts.map((a) => ({ id: a.id, label: accLabel(a), ready: ready.has(a.id) }));
  patch(v, 'radios', JSON.stringify([radios, s.accountId]), v.radios, () => radios.map((r) =>
    h('label', { class: 'radio-row' },
      h('input', { type: 'radio', name: 'export-account', checked: r.id === s.accountId, onchange: () => chooseAccount(r.id) }),
      h('span', {}, bdi(r.label), r.ready ? '' : ' (לא מחובר)'))));

  const account = accounts.find((a) => a.id === s.accountId) ?? null;
  const busy = busyText(state);

  v.session.hidden = !account;
  if (account) {
    const availableAt = state.exportPrefs?.availableAt?.[account.id] ?? 0;
    const now = nowSecs();
    const wait = availableAt > now
      ? `⏳ מטעמי אבטחה טלגרם יאפשר להתחיל את הייצוא רק בעוד ${Math.floor((availableAt - now) / 3600)} שעות (ב-${formatTime(availableAt, 'dd.MM.yyyy HH:mm')}). נשלחה הודעה לכל המכשירים שלך. חזור אז ונסה שוב מאותו מכשיר.`
      : null;
    const key = JSON.stringify([account.id, accLabel(account), s.loggedIn, busy, wait]);
    patch(v, 'session', key, v.session, () => [
      h('h3', {}, 'סשן ייצוא'),
      s.loggedIn === null ? h('p', {}, 'בודק…') : null,
      s.loggedIn === true ? h('p', { class: 'ok-color' }, '✅ מחובר (סשן נפרד מ-TG Backup)') : null,
      s.loggedIn === false ? [
        h('p', { class: 'small' }, 'הייצוא משתמש בסשן נפרד, כמו טלגרם דסקטופ. ההתחברות מאושרת אוטומטית דרך החשבון ',
          bdi(accLabel(account)), ' שמחובר ב-TG Backup (QR).'),
        h('div', { class: 'row' }, h('button', { type: 'button', disabled: busy !== null, onclick: () => login(account) },
          busy ?? 'התחבר לסשן הייצוא')),
      ] : null,
      wait ? h('p', { class: 'small error-color' }, wait) : null,
    ]);
  } else {
    v.keys.session = null;
  }

  const showPicker = !!account && s.loggedIn === true && !s.chat;
  v.picker.hidden = !showPicker;
  if (showPicker) {
    v.loadButton.textContent = s.chats === null ? "טען צ'אטים" : 'רענן';
    v.loadButton.disabled = busy !== null;
    v.busyText.textContent = busy ?? '';
    v.busyText.hidden = busy === null;
    v.searchRow.hidden = s.chats === null;
    v.search.disabled = s.chats === null;
    if (document.activeElement !== v.search && v.search.value !== s.search) v.search.value = s.search;
    const list = s.chats === null ? [] : filterChats(s.chats, s.search);
    patch(v, 'list', JSON.stringify([s.chatsVersion, s.chats === null ? null : list.map((c) => c.peerId)]), v.list,
      () => list.map(chatCard));
  } else {
    v.keys.list = null;
    v.list.replaceChildren();
  }

  const showOptions = !!account && s.loggedIn === true && !!s.chat;
  if (!showOptions) {
    if (v.options) v.optionsSlot.replaceChildren();
    v.options = null;
  } else {
    if (!v.options || v.options.chat !== s.chat) {
      v.options = buildOptions(s.chat);
      v.optionsSlot.replaceChildren(v.options.el);
    }
    updateOptions(v.options, state, account);
  }
}

function chatCard(c) {
  const sub = [typeLabel(c.type), blank(c.username) ? null : bdi(`@${c.username}`), c.isForum ? 'פורום' : null,
    c.archived ? 'בארכיון' : null].filter((x) => x !== null);
  const parts = sub.flatMap((p, i) => (i ? [' · ', p] : [p]));
  return h('button', { type: 'button', class: 'card chat-card', 'data-peer-id': String(c.peerId), onclick: () => chooseChat(c) },
    h('span', { class: 'chat-title', dir: 'auto' }, c.title),
    h('span', { class: 'small chat-sub' }, parts));
}

function chooseAccount(id) {
  if (id === s.accountId) return;
  // remember(accountId): loggedIn, chats and chat start over for another account.
  s.accountId = id;
  s.loggedIn = null;
  s.chats = null;
  s.chat = null;
  checkSession(true);
  redraw();
}

/** `loggedIn = exports.isLoggedIn(acc)`; `reset` shows בודק… meanwhile (a re-check after login keeps the old value). */
async function checkSession(reset) {
  const my = s;
  const gen = ++my.sessionGen;
  const id = my.accountId;
  if (reset) my.loggedIn = null;
  let loggedIn = false;
  try {
    loggedIn = !!(await api('GET', `/api/export/${enc(id)}/session`)).loggedIn;
  } catch {
    loggedIn = false;
  }
  if (my !== s || gen !== my.sessionGen || id !== my.accountId) return;
  my.loggedIn = loggedIn;
  redraw();
}

async function login(account) {
  if (s.busy !== null) return;
  const my = s;
  my.busy = 'מתחבר…';
  redraw();
  try {
    await api('POST', `/api/export/${enc(account.id)}/login`);
  } catch (e) {
    toast(prefixed('ההתחברות נכשלה: ', e.message));
  }
  if (my !== s) return;
  my.busy = null;
  redraw();
  if (my.accountId === account.id) await checkSession(false);
}

async function loadChats() {
  if (s.busy !== null || !s.accountId) return;
  const my = s;
  const id = my.accountId;
  my.busy = CHATS_BUSY;
  redraw();
  try {
    const chats = await api('POST', `/api/export/${enc(id)}/chats`);
    // remember(accountId): a list for an account that is no longer chosen is dropped.
    if (my === s && my.accountId === id) {
      my.chats = Array.isArray(chats) ? chats : [];
      my.chatsVersion = (my.chatsVersion ?? 0) + 1;
    }
  } catch (e) {
    toast(prefixed("טעינת הצ'אטים נכשלה: ", e.message));
  }
  if (my !== s) return;
  my.busy = null;
  redraw();
}

function chooseChat(entry) {
  s.chat = entry;
  s.topic = null;
  s.topics = null;
  s.from = 0;
  s.till = 0;
  const gen = ++s.topicsGen;
  if (entry.isForum) loadTopics(entry, gen);
  redraw();
}

async function loadTopics(entry, gen) {
  const my = s;
  const id = my.accountId;
  let topics;
  try {
    const list = await api('POST', `/api/export/${enc(id)}/topics`, { peerId: entry.peerId });
    topics = Array.isArray(list) ? list : [];
  } catch (e) {
    toast(prefixed('טעינת הנושאים נכשלה: ', e.message));
    topics = [];
  }
  if (my !== s || gen !== my.topicsGen) return;
  my.topics = topics;
  redraw();
}

// ---------------------------------------------------------------- options

let radioGroup = 0;

function buildOptions(chat) {
  const o = { chat, keys: {}, appliedPrefs: null, dragging: false };
  const group = ++radioGroup;
  o.topics = h('div', { class: 'export-topics' });

  o.media = MEDIA.map(([label, bit]) => {
    const input = h('input', { type: 'checkbox', onchange: () => saveMedia(o) });
    return { bit, input, row: h('label', { class: 'check-row' }, input, h('span', {}, bdi(label))) };
  });

  o.sizeLabel = h('div', { id: `size-label-${group}` });
  o.slider = h('input', {
    type: 'range', min: '0', max: '99', step: '1', 'aria-labelledby': `size-label-${group}`,
    oninput: () => { o.dragging = true; o.sizeLabel.replaceChildren(bdi(sizeText(sizeLimitByIndex(Number(o.slider.value))))); },
    onchange: () => {
      o.dragging = false;
      savePrefs(o, { sizeLimit: sizeLimitByIndex(Number(o.slider.value)) });
    },
  });

  o.formats = FORMATS.map(([label, value]) => {
    const input = h('input', { type: 'radio', name: `export-format-${group}`, onchange: () => savePrefs(o, { format: value }) });
    return { value, input, row: h('label', { class: 'radio-row' }, input, h('span', {}, bdi(label))) };
  });

  o.path = h('input', {
    type: 'text', autocomplete: 'off', dir: 'auto', spellcheck: 'false',
    onkeydown: (e) => { if (e.key === 'Enter') { e.preventDefault(); savePath(o); } },
    onblur: () => savePath(o),
  });

  o.dates = { from: dateRow(o, 'from'), till: dateRow(o, 'till') };

  o.el = h('section', { class: 'card export-options' },
    h('div', { class: 'options-head' },
      h('h3', { class: 'options-title', dir: 'auto' }, chat.title),
      h('button', { type: 'button', class: 'text', onclick: () => { s.chat = null; redraw(); } }, "החלף צ'אט")),
    o.topics,
    h('div', { class: 'section-label' }, bdi('Media export settings')),
    o.media.map((m) => m.row),
    o.sizeLabel,
    o.slider,
    h('hr'),
    h('div', { class: 'section-label' }, bdi('Format')),
    o.formats.map((f) => f.row),
    h('label', { class: 'field' }, h('span', { class: 'field-label' }, bdi('Download path')), o.path),
    h('hr'),
    o.dates.from.el,
    o.dates.till.el,
    h('hr'),
    h('button', { type: 'button', class: 'full', onclick: () => start() }, 'Export'));
  return o;
}

const sizeText = (limit) => `Size limit: ${Math.floor(limit / MiB)} MB`;

function dateRow(o, which) {
  const d = { which };
  d.text = h('div', { class: 'date-text' });
  d.date = h('input', { type: 'date', 'aria-label': 'תאריך', onchange: () => pickDate(which, d.date.value) });
  d.time = h('input', { type: 'time', 'aria-label': 'שעה', onchange: () => pickTime(which, d.time.value) });
  d.reset = h('button', { type: 'button', class: 'text', onclick: () => { s[which] = 0; redraw(); } }, 'Reset');
  d.el = h('div', { class: 'date-row', 'data-which': which }, d.text, h('div', { class: 'row' }, d.date, d.time, d.reset));
  return d;
}

/** The date dialog's שמור: local midnight of the day; To keeps 10 minutes after From. The From date is not clamped. */
function pickDate(which, value) {
  // The field emptied (not a date half typed, which also reads as ''): no bound, as Reset.
  if (value === '' && !view?.options?.dates?.[which]?.date.validity.badInput) {
    s[which] = 0;
    resync(which);
    redraw();
    return;
  }
  const limit = dayStart(value);
  // Typing a year digit by digit passes through years like 0002: only a real date counts.
  if (limit === null || limit < 0) return;
  if (which === 'from') s.from = limit;
  else s.till = clampTill(s.from, limit);
  resync(which);
  redraw();
}

/** The time dialog's שמור: the time on the local midnight of the current value, with the 600 s gap rules. */
function pickTime(which, value) {
  const current = s[which];
  if (!current) return;
  const result = withTime(current, value);
  if (result === null) return;
  if (which === 'from') s.from = clampFrom(result, s.till);
  else s.till = clampTill(s.from, result);
  resync(which);
  redraw();
}

/** After a pick the fields show the saved value, also when a clamp kept it as it was. */
function resync(which) {
  const d = view?.options?.dates?.[which];
  if (d) d.shown = undefined;
}

function updateDate(d, t) {
  const text = `${d.which === 'from' ? 'From: ' : 'To: '}${t ? shownTime(t) : d.which === 'from' ? 'the oldest message' : 'present'}`;
  if (d.text.textContent !== text) d.text.replaceChildren(bdi(text));
  d.time.hidden = !t;
  d.time.disabled = !t;
  d.reset.hidden = !t;
  // The fields follow the value only when it changes (a pick, a clamp, Reset, another chat): what is being typed
  // (a year passes through 0002, 0020, …) is never overwritten by an unrelated event.
  if (d.shown !== t) {
    d.shown = t;
    d.date.value = t ? dateInputDay(t) : '';
    d.time.value = t ? timeInputValue(t) : '';
  }
}

function updateOptions(o, state, account) {
  const chat = o.chat;
  patch(o, 'topics', JSON.stringify([chat.isForum, s.topics, s.topic?.id ?? null]), o.topics, () => {
    if (!chat.isForum) return [];
    const radio = (label, topic) => h('label', { class: 'radio-row' },
      h('input', { type: 'radio', name: 'export-topic', checked: (s.topic?.id ?? null) === (topic?.id ?? null), onchange: () => { s.topic = topic; redraw(); } }),
      h('span', { dir: 'auto' }, label));
    return [
      h('div', { class: 'section-label' }, 'נושא (Topic export settings):'),
      radio("כל הצ'אט", null),
      s.topics === null ? h('div', { class: 'small' }, 'טוען נושאים…') : null,
      h('div', { class: 'topic-list' }, (s.topics ?? []).map((t) => radio(t.title, t))),
      h('hr'),
    ];
  });

  // The prefs are shown when they come from the server; until then a click keeps what it shows.
  const prefs = state.exportPrefs ?? {};
  if (prefs !== o.appliedPrefs) {
    o.appliedPrefs = prefs;
    for (const m of o.media) m.input.checked = ((prefs.mediaTypes ?? 0) & m.bit) === m.bit;
    if (!o.dragging) {
      o.slider.value = String(indexBySizeLimit(prefs.sizeLimit ?? 8 * MiB));
      o.sizeLabel.replaceChildren(bdi(sizeText(prefs.sizeLimit ?? 8 * MiB)));
    }
    for (const f of o.formats) f.input.checked = prefs.format === f.value;
    if (document.activeElement !== o.path) o.path.value = prefs.path ?? '';
  }
  o.path.placeholder = prefs.defaultPath ?? '';
  updateDate(o.dates.from, s.from);
  updateDate(o.dates.till, s.till);
}

async function savePrefs(o, change) {
  try {
    await api('PATCH', '/api/export/prefs', change);
  } catch (e) {
    toast(e.message);
    // Show the server's prefs again.
    o.appliedPrefs = null;
    redraw();
  }
}

function saveMedia(o) {
  const mediaTypes = o.media.reduce((m, x) => (x.input.checked ? m | x.bit : m), 0);
  savePrefs(o, { mediaTypes });
}

function savePath(o) {
  if (o.path.value === (last?.exportPrefs?.path ?? '')) return;
  savePrefs(o, { path: o.path.value });
}

async function start() {
  const account = s.accountId;
  const chat = s.chat;
  if (!account || !chat) return;
  try {
    await api('POST', '/api/export/start', { accountId: account, peerId: chat.peerId, topicId: s.topic?.id, from: s.from, till: s.till });
  } catch (e) {
    toast(e.message);
  }
}

// ---------------------------------------------------------------- progress panel

function buildPanel(root) {
  const v = { root, mode: 'panel', key: null };
  v.card = h('section', { class: 'card export-panel' });
  v.el = h('div', { class: 'export' }, v.card);
  return v;
}

const closeButton = () => h('button', { type: 'button', class: 'outlined', onclick: () => post('/api/export/dismiss') }, 'סגור');

function updatePanel(running) {
  const v = view;
  const st = running.state ?? { type: 'Idle' };
  if (st.type === 'Processing') {
    if (v.key !== 'Processing') {
      v.key = 'Processing';
      v.rows = h('div', { class: 'progress-rows' });
      v.skip = h('button', { type: 'button', class: 'outlined', onclick: () => post('/api/export/skip') }, 'Skip this file');
      v.card.replaceChildren(
        h('h2', {}, bdi('Exporting your data')),
        v.rows,
        h('p', { class: 'small' }, 'אפשר לסגור את המסך. הייצוא ממשיך ברקע עם התראה קבועה.'),
        h('div', { class: 'row' }, v.skip, h('button', { type: 'button', onclick: () => confirmStop() }, 'Stop')));
    }
    updateRows(v.rows, progressRows(st.state));
    v.skip.hidden = !(st.state.bytesCount > 0);
    return;
  }
  const key = JSON.stringify(st);
  if (v.key === key) return;
  v.key = key;
  v.card.replaceChildren(...panelContent(st));
}

function panelContent(st) {
  switch (st.type) {
    case 'Finished':
      return [
        h('h2', { class: 'ok-color' }, bdi('Data export completed.')),
        h('p', {}, bdi(`Total files: ${st.filesCount}`)),
        h('p', {}, bdi(`Total size: ${formatSizeText(st.bytesCount)}`)),
        h('p', { class: 'small export-path', dir: 'auto' }, st.path),
        h('div', { class: 'row' },
          h('button', { type: 'button', onclick: () => post('/api/export/open') }, 'Show my data'),
          closeButton()),
      ];
    case 'ApiError':
      return [h('p', { class: 'export-error error-color pre' }, bdi(apiErrorText(st.code, st.errorType, nowSecs()))), closeButton()];
    case 'OutputError':
      return [h('p', { class: 'export-error error-color' }, bdi(`Could not write to ${st.path}`)), closeButton()];
    case 'Cancelled':
      return [h('p', {}, 'הייצוא הופסק.'), closeButton()];
    default:
      return [h('p', {}, bdi('Initializing...'))];
  }
}

/** One element per row, kept across samples; only texts and values change. */
function updateRows(container, rows) {
  while (container.children.length > rows.length) container.lastElementChild.remove();
  while (container.children.length < rows.length) {
    container.append(h('div', { class: 'progress-row' },
      h('div', { class: 'progress-head' }, h('span', { class: 'progress-label', dir: 'auto' }), h('span', { class: 'small progress-info', dir: 'ltr' })),
      h('progress', { max: '1' })));
  }
  rows.forEach((r, i) => {
    const el = container.children[i];
    const label = el.querySelector('.progress-label');
    const info = el.querySelector('.progress-info');
    if (label.textContent !== r.label) label.textContent = r.label;
    if (info.textContent !== r.info) info.textContent = r.info;
    el.querySelector('progress').value = r.progress;
  });
}

/** The Stop confirm (no title): `Stop` stops the export, `ביטול` keeps it. */
function confirmStop() {
  formDialog({ text: STOP_TEXT, okText: 'Stop', cancelText: 'ביטול' }).result.then((ok) => {
    if (ok) post('/api/export/stop');
  });
}
