// The shell: top bar, reconnect banner, tabs (kept in location.hash), the current screen, and the global
// two-step password dialog of the export session (inventory 2c).
import { connect } from './lib/store.js';
import { api } from './lib/api.js';
import { h, formDialog, toast } from './lib/ui.js';
import * as jobs from './screens/jobs.js';
import * as accounts from './screens/accounts.js';
import * as exportScreen from './screens/export.js';
import * as log from './screens/log.js';
import * as settings from './screens/settings.js';

const SCREENS = { jobs, export: exportScreen, accounts, log, settings };
const DEFAULT_TAB = 'jobs';

const root = document.getElementById('screen');
const title = document.getElementById('title');
const banner = document.getElementById('banner');
const tabLinks = [...document.querySelectorAll('nav.tabs a')];

let state = { connected: false };
let seenSnapshot = false;
let tab = null;

function currentTab() {
  const name = location.hash.slice(1);
  return name in SCREENS ? name : DEFAULT_TAB;
}

function showTab() {
  const next = currentTab();
  if (location.hash !== `#${next}`) history.replaceState(null, '', `#${next}`);
  if (next !== tab) {
    tab = next;
    root.dataset.screen = tab;
    root.replaceChildren();
    window.scrollTo(0, 0);
    for (const a of tabLinks) {
      if (a.getAttribute('href') === `#${tab}`) a.setAttribute('aria-current', 'page');
      else a.removeAttribute('aria-current');
    }
  }
  renderScreen();
}

function renderScreen() {
  if (seenSnapshot) SCREENS[tab].render(root, state);
}

function onState(next) {
  state = next;
  if (state.connected) seenSnapshot = true;
  title.textContent = state.version ? `TG Backup  v${state.version}` : 'TG Backup';
  banner.hidden = !seenSnapshot || state.connected;
  passwordDialog(state.password);
  renderScreen();
}

// The export session asks for the account's 2FA password; the answer (or null = cancel) goes back by id.
// An answered id never opens again: the request stays in the state until the server's `password: null` arrives.
let open = null;
const answered = new Set();

function passwordDialog(request) {
  if (open && (!request || request.id !== open.id)) {
    open.dialog.close(false);
    open = null;
  }
  if (!request || open || answered.has(request.id)) return;
  const { id, hint } = request;
  const field = h('input', { type: 'password', autocomplete: 'off', 'aria-label': 'סיסמה' });
  // 409: the request was already answered or is gone (another window, or it timed out); nothing to tell.
  const answer = (password) => api('POST', '/api/export/password', { id, password })
    .catch((e) => { if (e.status !== 409) toast(e.message); });
  const dialog = formDialog({
    title: 'סיסמת אימות דו-שלבי',
    text: 'סשן הייצוא צריך את הסיסמה של החשבון.' + (hint && hint.trim() ? `\nרמז: ${hint}` : ''),
    content: [field],
  });
  open = { id, dialog };
  dialog.result.then((ok) => {
    // Closed by us because the request went away: nothing to answer.
    if (open?.id !== id) return;
    open = null;
    answered.add(id);
    answer(ok ? field.value : null);
  });
}

window.addEventListener('hashchange', showTab);
showTab();
connect(onState);
