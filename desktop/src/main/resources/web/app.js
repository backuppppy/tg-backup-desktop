// The shell: top bar, reconnect banner, tabs (the active pane's tab kept in location.hash), one screen or two side by
// side (the split view, lib/layout.js), and the global two-step password dialog of the export session (inventory 2c).
import { connect } from './lib/store.js';
import { api } from './lib/api.js';
import { h, formDialog, toast } from './lib/ui.js';
import { nextPassword, passwordAsker } from './lib/password.js';
import * as jobs from './screens/jobs.js';
import * as accounts from './screens/accounts.js';
import * as exportScreen from './screens/export.js';
import * as log from './screens/log.js';
import * as settings from './screens/settings.js';
import {
  TABS, PANES, SPLIT_MIN_WIDTH, RATIO_MIN, RATIO_MAX,
  effectiveMode, choose, showInActive, resolveSplit, clampRatio, ratioFor, manualRatio, seenJobs, load, save,
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
// rebuilt), so what is typed in it survives a move. A screen that leaves the view is only detached, kept whole, and
// draws nothing while away (its module's goAway(), if any, stops its timers); when it comes back, its module's
// comeBack() (if any) asks again what a screen asks when it is shown (the export session, as Android's
// LaunchedEffect does when composed), and it is rendered with the state of now.

const LABELS = Object.fromEntries(tabLinks.map((a) => [a.getAttribute('href').slice(1), a.querySelector('.label').textContent]));
const hosts = Object.fromEntries(TABS.map((tab) => [tab, h('div', { class: 'screen', 'data-screen': tab })]));

let layout = load(storage());
/** The active pane's tab when the split was last shown: the other pane's tab if both name the same on the way back. */
let splitTab = layout[layout.active];
/** What the page shows now: `{mode, tabs: {pane: tab}}`, to tell what changed. */
let shown = { mode: null, tabs: {} };
/** Screens detached from the view since they were last shown. */
const away = new Set();

function storage() {
  try {
    return window.localStorage;
  } catch {
    return null;
  }
}

function buildPane(side) {
  const select = h('select', { 'aria-label': side === 'right' ? 'חלק ימני' : 'חלק שמאלי', onchange: () => setLayout(choose(layout, side, select.value)) },
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
  'aria-label': 'גודל החלקים',
  tabindex: '0',
});
main.replaceChildren(panes.right.pane, divider, panes.left.pane);

function setLayout(next) {
  layout = next;
  persist();
  apply();
}

/** Saved as the split would be: both panes never name the same tab in storage. */
function persist() {
  save(storage(), resolveSplit(layout, splitTab));
}

/** A click or the focus inside a pane makes it the active one (the rail and the hash follow it). */
function activate(side) {
  if (layout.active === side || effectiveMode(layout, window.innerWidth) !== 'split') return;
  setLayout({ ...layout, active: side });
}

function apply() {
  const width = window.innerWidth;
  const mode = effectiveMode(layout, width);
  if (mode === 'split') {
    // Back from one screen, where the rail changed only the active pane: a clash is solved now, by a swap.
    if (layout.right === layout.left) {
      layout = resolveSplit(layout, splitTab);
      persist();
    }
    splitTab = layout[layout.active];
  }
  const visiblePanes = mode === 'split' ? PANES : [layout.active];
  const tabs = Object.fromEntries(visiblePanes.map((side) => [side, layout[side]]));
  const visibleTabs = new Set(Object.values(tabs));

  for (const tab of TABS) {
    if (!visibleTabs.has(tab) && hosts[tab].isConnected) {
      hosts[tab].remove();
      away.add(tab);
      SCREENS[tab].goAway?.();
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
  // Mid-drag the CSS keeps following the pointer.
  showRatio(dragRatio ?? ratio());
  divider.hidden = mode !== 'split';

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
    if (panes[side].pane.hidden) continue;
    const tab = layout[side];
    if (away.delete(tab)) SCREENS[tab].comeBack?.();
    SCREENS[tab].render(hosts[tab], state);
  }
}

/**
 * The number of jobs the ratio follows. Until the first snapshot it is unknown: a share set by hand stays shown, or
 * else the one of the number of jobs last seen.
 */
function jobsCount() {
  return seenSnapshot ? (state.jobs ?? []).length : (layout.ratioJobs ?? layout.jobsSeen ?? 0);
}

/** The right pane's share now (Task 10c): with the jobs in a pane, by the number of jobs unless set by hand. */
function ratio() {
  return ratioFor(layout, jobsCount());
}

/** The panes' widths and the divider's value; nothing else is drawn (a drag calls it on every move). */
function showRatio(ratio) {
  main.style.setProperty('--right-fr', `${ratio}fr`);
  main.style.setProperty('--left-fr', `${Math.round((1 - ratio) * 100) / 100}fr`);
  divider.setAttribute('aria-valuenow', String(Math.round(ratio * 100)));
}

/** A rail click (or any link to #tab) puts that screen in the active pane. */
// With one screen shown it changes only that screen, without a swap; the split comes back as it was (apply).
function onHash() {
  const tab = location.hash.slice(1);
  if (!TABS.includes(tab) || tab === layout[layout.active]) apply();
  else if (effectiveMode(layout, window.innerWidth) === 'split') setLayout(choose(layout, layout.active, tab));
  else setLayout(showInActive(layout, tab));
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
/** The ratio while a drag is on (null when none): only the CSS follows it until the release. */
let dragRatio = null;
let dragPointer = null;
/** Whether a move of this drag changed the ratio: a click without one saves nothing. */
let dragMoved = false;
divider.addEventListener('pointerdown', (e) => {
  // A second pointer does not take over a drag that is on.
  if (e.button !== 0 || divider.hidden || dragRatio !== null) return;
  dragRatio = ratio();
  dragPointer = e.pointerId;
  dragMoved = false;
  divider.setPointerCapture(e.pointerId);
  divider.classList.add('dragging');
  e.preventDefault();
});
/** Ends the drag; `keep` saves its ratio when a move changed it, otherwise the saved one comes back. */
function endDrag(keep) {
  if (dragRatio === null) return;
  const ratio = dragRatio;
  dragRatio = null;
  divider.classList.remove('dragging');
  if (divider.hasPointerCapture(dragPointer)) divider.releasePointerCapture(dragPointer);
  if (keep && dragMoved && !divider.hidden) setLayout(manualRatio(layout, ratio, jobsCount()));
  else apply();
}
divider.addEventListener('pointermove', (e) => {
  if (dragRatio === null) return;
  // The window got narrower than 1200 px during the drag: no split, no drag.
  if (divider.hidden) {
    endDrag(false);
    return;
  }
  if (e.pointerId !== dragPointer) return;
  const next = ratioAt(e.clientX);
  if (next === dragRatio) return;
  dragRatio = next;
  dragMoved = true;
  showRatio(dragRatio);
});
divider.addEventListener('pointerup', (e) => { if (e.pointerId === dragPointer) endDrag(true); });
divider.addEventListener('lostpointercapture', (e) => { if (e.pointerId === dragPointer) endDrag(true); });
// The system took the pointer (a touch turned into a scroll, a window switch): the drag is dropped, not saved.
divider.addEventListener('pointercancel', (e) => { if (e.pointerId === dragPointer) endDrag(false); });
divider.addEventListener('keydown', (e) => {
  const step = { ArrowLeft: 0.05, ArrowRight: -0.05 }[e.key];
  if (step === undefined) return;
  e.preventDefault();
  setLayout(manualRatio(layout, ratio() + step, jobsCount()));
});

window.addEventListener('resize', apply);

function onState(next) {
  state = next;
  if (state.connected) seenSnapshot = true;
  title.textContent = state.version ? `TG Backup  v${state.version}` : 'TG Backup';
  banner.hidden = !seenSnapshot || state.connected;
  passwordDialog(nextPassword(state, answered));
  if (seenSnapshot) {
    // A job added or deleted: a share set by hand for the old number of jobs gives way to the automatic one.
    let next = layout;
    if (next.ratioJobs !== null && next.ratioJobs !== jobsCount()) next = { ...next, jobsRatio: null, ratioJobs: null };
    next = seenJobs(next, jobsCount());
    if (next !== layout) {
      layout = next;
      persist();
    }
  }
  if (dragRatio === null) showRatio(ratio());
  renderScreens();
}

// The export session asks for the account's 2FA password; the answer (or null = cancel) goes back by id.
// A job and the export screen may wait at once: the dialog shows the oldest request not answered yet, and once it is
// answered or cancelled the next one. An answered id never opens again, though it stays in the state until the
// server drops it.
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
  // 409: the request was already answered or is gone (another window, or it timed out); nothing to tell. Any other
  // failure (the network, the server) leaves the request unanswered: it can be answered again, so the dialog comes
  // back while the request is still in the state.
  const answer = (password) => api('POST', '/api/export/password', { id, password })
    .catch((e) => {
      if (e.status === 409) return;
      toast(e.message);
      answered.delete(id);
      passwordDialog(nextPassword(state, answered));
    });
  const asker = passwordAsker(request, state.accounts);
  const askerLine = asker && h('p', { class: 'password-asker' }, `מבקש: ${asker.who}`,
    ...(asker.account ? [' · חשבון: ', h('bdi', {}, asker.account)] : []));
  const dialog = formDialog({
    title: 'סיסמת אימות דו-שלבי',
    text: 'סשן הייצוא צריך את הסיסמה של החשבון.' + (hint && hint.trim() ? `\nרמז: ${hint}` : ''),
    content: askerLine ? [askerLine, field] : [field],
  });
  open = { id, dialog };
  dialog.result.then((ok) => {
    // Closed by us because the request went away: nothing to answer.
    if (open?.id !== id) return;
    open = null;
    answered.add(id);
    answer(ok ? field.value : null);
    // The next waiting request, if any, opens now (it needs no further event).
    passwordDialog(nextPassword(state, answered));
  });
}

window.addEventListener('hashchange', onHash);
onHash();
connect(onState);
