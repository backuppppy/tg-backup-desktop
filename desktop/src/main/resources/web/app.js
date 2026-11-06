// The shell: top bar, reconnect banner, tabs (the active pane's tab kept in location.hash), one screen or two side by
// side (the split view, lib/layout.js), and the global two-step password dialog of the export session (inventory 2c).
import { connect } from './lib/store.js';
import { api } from './lib/api.js';
import { h, formDialog, toast } from './lib/ui.js';
import * as jobs from './screens/jobs.js';
import * as accounts from './screens/accounts.js';
import * as exportScreen from './screens/export.js';
import * as log from './screens/log.js';
import * as settings from './screens/settings.js';
import {
  TABS, PANES, SPLIT_MIN_WIDTH, RATIO_MIN, RATIO_MAX, effectiveMode, choose, clampRatio, load, save,
} from './lib/layout.js';

const SCREENS = { jobs, export: exportScreen, accounts, log, settings };

const main = document.getElementById('screen');
const title = document.getElementById('title');
const banner = document.getElementById('banner');
const tabLinks = [...document.querySelectorAll('nav.tabs a')];
const modeSwitch = document.getElementById('mode-switch');
const modeButtons = [...modeSwitch.querySelectorAll('button')];

let state = { connected: false };
let seenSnapshot = false;

// ---------------------------------------------------------------- split view (Task 10b)
// Every screen renders into its own host element, which moves between the panes as a whole (re-attached, never
// rebuilt), so what is typed in it survives a move. A screen that leaves the view is emptied, as a tab that was
// left always was, and builds itself again when it comes back.

const LABELS = Object.fromEntries(tabLinks.map((a) => [a.getAttribute('href').slice(1), a.querySelector('.label').textContent]));
const hosts = Object.fromEntries(TABS.map((tab) => [tab, h('div', { class: 'screen', 'data-screen': tab })]));

let layout = load(storage());
/** What the page shows now: `{mode, tabs: {pane: tab}}`, to tell what changed. */
let shown = { mode: null, tabs: {} };

function storage() {
  try {
    return window.localStorage;
  } catch {
    return null;
  }
}

function buildPane(side) {
  const select = h('select', { onchange: () => setLayout(choose(layout, side, select.value)) },
    TABS.map((tab) => h('option', { value: tab }, LABELS[tab])));
  const head = h('div', { class: 'pane-head' }, select);
  const body = h('div', { class: 'pane-body' });
  const pane = h('section', {
    class: 'pane',
    'data-pane': side,
    onpointerdown: () => activate(side),
    onfocusin: () => activate(side),
  }, head, body);
  return { pane, head, body, select };
}

const panes = { right: buildPane('right'), left: buildPane('left') };
const divider = h('div', {
  class: 'divider',
  role: 'separator',
  'aria-orientation': 'vertical',
  'aria-valuemin': String(Math.round(RATIO_MIN * 100)),
  'aria-valuemax': String(Math.round(RATIO_MAX * 100)),
  tabindex: '0',
});
main.replaceChildren(panes.right.pane, divider, panes.left.pane);

function setLayout(next, { persist = true } = {}) {
  layout = next;
  if (persist) save(storage(), layout);
  apply();
}

/** A click or the focus inside a pane makes it the active one (the rail and the hash follow it). */
function activate(side) {
  if (layout.active === side || effectiveMode(layout, window.innerWidth) !== 'split') return;
  setLayout({ ...layout, active: side });
}

function apply() {
  const width = window.innerWidth;
  const mode = effectiveMode(layout, width);
  const visiblePanes = mode === 'split' ? PANES : [layout.active];
  const tabs = Object.fromEntries(visiblePanes.map((side) => [side, layout[side]]));
  const visibleTabs = new Set(Object.values(tabs));

  for (const tab of TABS) {
    if (!visibleTabs.has(tab)) {
      hosts[tab].remove();
      hosts[tab].replaceChildren();
    }
  }
  for (const side of PANES) {
    const p = panes[side];
    const visible = visiblePanes.includes(side);
    p.pane.hidden = !visible;
    p.head.hidden = mode !== 'split';
    p.pane.classList.toggle('active', mode === 'split' && layout.active === side);
    if (p.select.value !== layout[side]) p.select.value = layout[side];
    if (!visible) continue;
    const host = hosts[layout[side]];
    if (host.parentNode !== p.body) {
      p.body.replaceChildren(host);
      p.body.scrollTop = 0;
    }
  }
  const activeTab = layout[layout.active];
  if (mode === 'single' && (shown.mode !== 'single' || shown.tabs[layout.active] !== activeTab)) window.scrollTo(0, 0);
  shown = { mode, tabs };

  document.body.dataset.layout = mode;
  main.style.setProperty('--right-fr', `${layout.ratio}fr`);
  main.style.setProperty('--left-fr', `${Math.round((1 - layout.ratio) * 100) / 100}fr`);
  divider.hidden = mode !== 'split';
  divider.setAttribute('aria-valuenow', String(Math.round(layout.ratio * 100)));

  modeSwitch.hidden = width < SPLIT_MIN_WIDTH;
  for (const b of modeButtons) b.setAttribute('aria-pressed', String(b.dataset.mode === layout.mode));

  for (const a of tabLinks) {
    const tab = a.getAttribute('href').slice(1);
    if (tab === activeTab) a.setAttribute('aria-current', 'page');
    else a.removeAttribute('aria-current');
    a.classList.toggle('shown', tab !== activeTab && visibleTabs.has(tab));
  }
  // The hash keeps the active pane's tab, so old links still work.
  if (location.hash !== `#${activeTab}`) history.replaceState(null, '', `#${activeTab}`);
  renderScreens();
}

function renderScreens() {
  if (!seenSnapshot) return;
  for (const side of PANES) {
    if (!panes[side].pane.hidden) SCREENS[layout[side]].render(hosts[layout[side]], state);
  }
}

/** A rail click (or any link to #tab) puts that screen in the active pane. */
function onHash() {
  const tab = location.hash.slice(1);
  if (TABS.includes(tab) && tab !== layout[layout.active]) setLayout(choose(layout, layout.active, tab));
  else apply();
}

for (const b of modeButtons) b.addEventListener('click', () => setLayout({ ...layout, mode: b.dataset.mode }));

// The divider: dragged with the pointer (saved on release) or moved 5% at a time with the arrow keys. The ratio is
// the right pane's share; with dir=rtl the right pane comes first, so going left makes it wider.
function ratioAt(clientX) {
  const r = panes.right.pane.getBoundingClientRect();
  const l = panes.left.pane.getBoundingClientRect();
  const room = r.right - l.left - divider.getBoundingClientRect().width;
  return clampRatio((r.right - clientX) / room);
}
let dragging = false;
divider.addEventListener('pointerdown', (e) => {
  if (e.button !== 0) return;
  dragging = true;
  divider.setPointerCapture(e.pointerId);
  divider.classList.add('dragging');
  e.preventDefault();
});
divider.addEventListener('pointermove', (e) => {
  if (!dragging) return;
  const ratio = ratioAt(e.clientX);
  if (ratio !== layout.ratio) setLayout({ ...layout, ratio }, { persist: false });
});
const endDrag = () => {
  if (!dragging) return;
  dragging = false;
  divider.classList.remove('dragging');
  save(storage(), layout);
};
divider.addEventListener('pointerup', endDrag);
divider.addEventListener('lostpointercapture', endDrag);
divider.addEventListener('keydown', (e) => {
  const step = { ArrowLeft: 0.05, ArrowRight: -0.05 }[e.key];
  if (step === undefined) return;
  e.preventDefault();
  setLayout({ ...layout, ratio: clampRatio(layout.ratio + step) });
});

function onState(next) {
  state = next;
  if (state.connected) seenSnapshot = true;
  title.textContent = state.version ? `TG Backup  v${state.version}` : 'TG Backup';
  banner.hidden = !seenSnapshot || state.connected;
  passwordDialog(state.password);
  renderScreens();
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

window.addEventListener('hashchange', onHash);
onHash();
connect(onState);
