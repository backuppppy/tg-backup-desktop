// Accounts screen (inventory 2b): the top note, one card per account, `➕ הוסף חשבון`, AccountDialog and the
// delete dialog. Texts, enable rules and toasts are the Android ones (AccountsScreen.kt).
//
// Cards are keyed by account id and patched in place: the shared input (code or password) is one element that
// stays in the page across renders, so an event (log, accountStates) never wipes or unfocuses what is typed.
// The code and the password live only in that input and in the request body.
import { api } from '../lib/api.js';
import { h, toast, formDialog, confirmDialog } from '../lib/ui.js';

export const MASKED_HASH = '••••';
const NO_CLIENT = { type: 'Unsupported', label: 'חסרים API ID / Hash', why: 'חסרים API ID / Hash' };
const LOGIN_PREFIX = 'ההתחברות נכשלה: ';
const CHECK_PREFIX = 'בדיקה נכשלה: ';

const blank = (s) => !s || !s.trim();
const enc = encodeURIComponent;
const nowSecs = () => Math.floor(Date.now() / 1000);

/** The server's text after its own `prefix`, so the Android toast can put its own in front. */
const after = (prefix, msg) => (msg.startsWith(prefix) ? msg.slice(prefix.length) : msg);

/** `${hrs}ש' ${mins}ד'` when hrs>0, else `${mins}ד'` (Android's `when_`). */
export function waitText(secs) {
  const hrs = Math.floor(secs / 3600);
  const mins = Math.floor((secs % 3600) / 60);
  return hrs > 0 ? `${hrs}ש' ${mins}ד'` : `${mins}ד'`;
}

let view = null;
let last = null; // the state of the last render, for redraws after a request or a timer
const cards = new Map(); // account id -> card controller (kept while the account exists)
let tick = null;

export function render(root, state) {
  last = state;
  if (!view || view.root !== root || !root.contains(view.list)) {
    view = {
      root,
      list: h('div', { class: 'accounts' }),
      note: h('p', { class: 'small accounts-note' }, 'API ID ו-API Hash מקבלים בכתובת my.telegram.org ← API development tools.'),
      add: h('button', { type: 'button', class: 'full', onclick: () => accountDialog(null) }, '➕ הוסף חשבון'),
      order: null,
    };
    root.replaceChildren(view.list);
    // A screen that comes back asks the export sessions again, as the Android card does when it is composed.
    for (const c of cards.values()) c.authKey = null;
  }
  const accounts = state.accounts ?? [];
  const ids = new Set(accounts.map((a) => a.id));
  for (const id of [...cards.keys()]) if (!ids.has(id)) cards.delete(id);
  for (const acc of accounts) {
    if (!cards.has(acc.id)) cards.set(acc.id, newCard(acc.id));
    update(cards.get(acc.id), acc, state);
  }
  // Only a changed order touches the list: moving a node would drop the focus inside it.
  const order = accounts.map((a) => a.id).join('\n');
  if (order !== view.order) {
    view.order = order;
    view.list.replaceChildren(view.note, ...accounts.map((a) => cards.get(a.id).el), view.add);
  }
  schedule(accounts, state);
}

function redraw() {
  if (view && last && view.root.contains(view.list)) render(view.root, last);
}

/** One timer while a countdown is shown: it fires when the shown minutes change (at most once a minute). */
function schedule(accounts, state) {
  if (tick) clearTimeout(tick);
  tick = null;
  const now = nowSecs();
  const waits = accounts
    .filter((a) => cards.get(a.id)?.shown?.exportLine?.wait)
    .map((a) => (state.exportPrefs?.availableAt?.[a.id] ?? 0) - now)
    .filter((s) => s > 0);
  if (!waits.length) return;
  // `mins` drops one second after a whole minute is left (left 7500 shows 5ד', 7499 shows 4ד').
  const next = Math.min(...waits.map((s) => (s % 60) + 1));
  tick = setTimeout(() => { tick = null; redraw(); }, next * 1000 + 50);
}

function newCard(id) {
  const c = {
    id,
    busy: false,
    exportBusy: false,
    exportLoggedIn: null,
    authKey: null,
    gen: 0,
    shown: null,
  };
  c.top = h('div', { class: 'account-part' });
  c.phoneButton = h('button', { type: 'button', onclick: () => act(c, () => api('POST', `/api/accounts/${enc(id)}/phone`)) }, 'התחבר (שלח קוד)');
  c.inputLabel = h('span', { class: 'field-label' });
  c.input = h('input', { type: 'text', autocomplete: 'off', dir: 'auto', oninput: () => setConfirm(c) });
  c.confirm = h('button', { type: 'submit' }, 'אשר');
  c.inputRow = h('form', { class: 'account-input', onsubmit: (e) => { e.preventDefault(); submit(c); } },
    h('label', { class: 'field' }, c.inputLabel, c.input),
    c.confirm);
  c.bottom = h('div', { class: 'account-part' });
  c.el = h('section', { class: 'card account-card', 'data-account-id': id }, c.top, c.phoneButton, c.inputRow, c.bottom);
  return c;
}

function setConfirm(c) {
  c.confirm.disabled = c.busy || blank(c.input.value);
}

function submit(c) {
  if (c.confirm.disabled || c.inputRow.hidden) return;
  const value = c.input.value;
  if (c.authType === 'NeedCode') act(c, () => api('POST', `/api/accounts/${enc(c.id)}/code`, { code: value.trim() }));
  else if (c.authType === 'NeedPassword') act(c, () => api('POST', `/api/accounts/${enc(c.id)}/password`, { password: value }));
}

/** Android's `act{}`: busy while it runs; an error is a toast (the server sends `שגיאה: …`), success clears the input. */
async function act(c, request) {
  if (c.busy) return;
  c.busy = true;
  redraw();
  try {
    await request();
    c.input.value = '';
  } catch (e) {
    toast(e.message);
  } finally {
    c.busy = false;
    redraw();
  }
}

/** Asks the server whether the export session is logged in; a failure reads as false (Android's runCatching). */
async function checkSession(c) {
  const gen = ++c.gen;
  c.exportLoggedIn = null;
  let loggedIn = false;
  try {
    loggedIn = !!(await api('GET', `/api/export/${enc(c.id)}/session`)).loggedIn;
  } catch {
    loggedIn = false;
  }
  if (gen !== c.gen) return;
  c.exportLoggedIn = loggedIn;
  redraw();
}

async function exportAction(c, run) {
  if (c.exportBusy) return;
  c.exportBusy = true;
  redraw();
  try {
    await run();
  } finally {
    await checkSession(c);
    c.exportBusy = false;
    redraw();
  }
}

const exportLogin = (c) => exportAction(c, () => api('POST', `/api/export/${enc(c.id)}/login`)
  .catch((e) => toast(`חיבור סשן ייצוא נכשל: ${after(LOGIN_PREFIX, e.message)}`)));

const exportCheck = (c) => exportAction(c, () => api('POST', `/api/export/${enc(c.id)}/check`)
  .then((r) => toast(r.ready ? '✅ סשן הייצוא מוכן להעברה' : '⏳ טלגרם עדיין ממתין לאישורך — לחץ Allow בהודעת טלגרם'))
  .catch((e) => toast(`בדיקה נכשלה: ${after(CHECK_PREFIX, e.message)}`)));

function exportLine(c, availableAt) {
  if (c.exportBusy || c.exportLoggedIn === null) return { text: '📤 סשן ייצוא: בודק…', cls: '' };
  if (c.exportLoggedIn === false) return { text: '📤 סשן ייצוא: לא מחובר', cls: '' };
  const left = (availableAt ?? 0) - nowSecs();
  if (left > 0) return { text: `⏳ סשן ייצוא: ממתין לאישור ב-Telegram (עוד ${waitText(left)})`, cls: 'error-color', wait: true };
  return { text: '✅ סשן ייצוא: מחובר ומוכן', cls: 'ok-color' };
}

function update(c, acc, state) {
  const st = (state.accountStates ?? []).find((s) => s.id === acc.id);
  const auth = st?.auth ?? NO_CLIENT;
  const error = st?.lastError ?? null;
  const ready = auth.type === 'Ready';

  // Android's LaunchedEffect(acc.id, auth): ask the export session again whenever the auth changes to Ready.
  const authKey = JSON.stringify(auth);
  if (authKey !== c.authKey) {
    c.authKey = authKey;
    if (ready) checkSession(c);
    else { c.gen += 1; c.exportLoggedIn = null; }
  }
  c.authType = auth.type;

  const line = ready ? exportLine(c, state.exportPrefs?.availableAt?.[acc.id]) : null;
  const shown = { acc, auth, error, exportLine: line, busy: c.busy, exportBusy: c.exportBusy, exportLoggedIn: c.exportLoggedIn };
  const key = JSON.stringify(shown);
  if (c.shownKey === key) return;
  c.shownKey = key;
  c.shown = shown;

  // A Latin display name keeps its own order (`(@user)`) inside the RTL line.
  const authText = ready ? ['✅ סשן רגיל: מחובר כ-', h('bdi', {}, auth.display ?? '')] : `📱 סשן רגיל: ${auth.label}`;
  const authCls = ready ? 'ok-color' : auth.type === 'Unsupported' ? 'error-color' : '';
  c.top.replaceChildren(...[
    h('h3', { class: 'account-title' }, blank(acc.name) ? 'ללא שם' : acc.name),
    h('div', { class: 'small account-phone', dir: 'rtl' }, h('bdi', {}, acc.phone)),
    h('div', { class: `account-auth ${authCls}` }, authText),
    error != null ? h('div', { class: 'small account-error error-color' }, error) : null,
    line ? h('div', { class: `small account-export ${line.cls}` }, line.text) : null,
  ].filter(Boolean));

  c.phoneButton.hidden = auth.type !== 'NeedPhone';
  c.phoneButton.disabled = c.busy;
  const needsInput = auth.type === 'NeedCode' || auth.type === 'NeedPassword';
  c.inputRow.hidden = !needsInput;
  if (auth.type === 'NeedCode') {
    c.input.type = 'text';
    c.input.setAttribute('inputmode', 'numeric');
    c.inputLabel.textContent = 'קוד מטלגרם';
  } else if (auth.type === 'NeedPassword') {
    c.input.type = 'password';
    c.input.removeAttribute('inputmode');
    c.inputLabel.textContent = blank(auth.hint) ? 'סיסמת אימות דו-שלבי' : `סיסמה (רמז: ${auth.hint})`;
  }
  // A hidden input is not in the page's tab order nor in what the tests count.
  c.input.disabled = !needsInput;
  setConfirm(c);

  const canLogout = ready || needsInput;
  const remove = async () => {
    if (await confirmDialog({ title: `למחוק את החשבון ${acc.name}?`, text: 'החשבון יתנתק ויוסר מכל המשימות.' })) {
      api('DELETE', `/api/accounts/${enc(acc.id)}`).catch((e) => toast(e.message));
    }
  };
  let exportButton = null;
  if (ready && c.exportLoggedIn === false) {
    exportButton = h('button', { type: 'button', class: 'outlined', disabled: c.exportBusy, onclick: () => exportLogin(c) },
      c.exportBusy ? 'מחבר…' : 'חבר סשן ייצוא');
  } else if (ready && c.exportLoggedIn === true) {
    exportButton = h('button', { type: 'button', class: 'outlined', disabled: c.exportBusy, onclick: () => exportCheck(c) },
      c.exportBusy ? 'בודק…' : 'בדוק סשן ייצוא');
  }
  c.bottom.replaceChildren(...[
    h('div', { class: 'row' },
      h('button', { type: 'button', class: 'outlined', onclick: () => accountDialog(acc) }, 'ערוך'),
      canLogout
        ? h('button', { type: 'button', class: 'outlined', disabled: c.busy, onclick: () => act(c, () => api('POST', `/api/accounts/${enc(acc.id)}/logout`)) }, 'התנתק')
        : null,
      h('button', { type: 'button', class: 'text danger', onclick: remove }, 'מחק'),
    ),
    exportButton ? h('div', { class: 'row' }, exportButton) : null,
  ].filter(Boolean));
}

/** Android's `toIntOrNull()` on a digits-only field. */
function toInt(s) {
  if (!/^\d+$/.test(s)) return null;
  const n = Number(s);
  return n <= 2147483647 ? n : null;
}

const textField = (label, value, attrs = {}) => {
  const input = h('input', { type: 'text', value, autocomplete: 'off', dir: 'auto', ...attrs });
  return { input, row: h('label', { class: 'field' }, h('span', { class: 'field-label' }, label), input) };
};

/**
 * AccountDialog: a new account (`initial` null) or an edit. On an edit the hash field shows `••••`; sent as is, the
 * server keeps the stored hash. The Android rule `apiHash.length >= 16` counts the stored hash for the mask.
 */
function accountDialog(initial) {
  const editing = !!initial;
  const name = textField('שם', initial?.name ?? '');
  const apiId = textField('API ID', initial && initial.apiId !== 0 ? String(initial.apiId) : '', { inputmode: 'numeric' });
  const keepsHash = editing && initial.hasApiHash;
  const apiHash = textField('API Hash', keepsHash ? MASKED_HASH : '', { spellcheck: 'false' });
  const phone = textField('טלפון (+972…)', initial?.phone ?? '', { type: 'tel' });

  apiId.input.addEventListener('input', () => {
    const v = apiId.input.value.replace(/\D/g, '');
    if (v !== apiId.input.value) apiId.input.value = v;
  });
  apiHash.input.addEventListener('input', () => {
    const v = apiHash.input.value.trim();
    if (v !== apiHash.input.value) apiHash.input.value = v;
  });
  // Typing over the mask replaces it.
  apiHash.input.addEventListener('focus', () => { if (apiHash.input.value === MASKED_HASH) apiHash.input.select(); });

  const hashOk = () => apiHash.input.value.length >= 16 || (keepsHash && apiHash.input.value === MASKED_HASH);

  const save = async () => {
    const body = {
      name: name.input.value.trim(),
      apiId: toInt(apiId.input.value),
      apiHash: apiHash.input.value,
      phone: phone.input.value.trim(),
    };
    try {
      if (editing) await api('PUT', `/api/accounts/${enc(initial.id)}`, body);
      else await api('POST', '/api/accounts', body);
      return true;
    } catch (e) {
      toast(e.message);
      return false;
    }
  };

  const dialog = formDialog({
    title: !editing || (blank(initial.name) && initial.apiId === 0) ? 'חשבון חדש' : 'עריכת חשבון',
    okText: 'שמור',
    okClass: '',
    onOk: save,
    content: [name.row, apiId.row, apiHash.row, phone.row],
  });
  const updateOk = () => {
    dialog.okButton.disabled = toInt(apiId.input.value) === null || !hashOk() || blank(phone.input.value);
  };
  for (const f of [apiId, apiHash, phone]) f.input.addEventListener('input', updateOk);
  updateOk();
  return dialog;
}
