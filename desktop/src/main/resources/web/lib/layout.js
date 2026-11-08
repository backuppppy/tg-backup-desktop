// The split view's state (Task 10b): which screen each pane shows, which pane is active, one or two screens, and the
// right pane's share when no pane shows the jobs. Task 10c: while a pane shows the jobs, that pane's width follows the
// number of jobs (ratioFor), unless the divider was moved by hand while that many jobs existed (jobsRatio, the jobs
// pane's own share, on whichever side it is, with ratioJobs). Pure functions (unit-tested under node) plus load/save in localStorage under `tgb.layout`.

export const TABS = Object.freeze(['jobs', 'export', 'accounts', 'log', 'settings']);
export const PANES = Object.freeze(['right', 'left']);
/** From this window width a chosen split shows; below it one screen (the active pane's). */
export const SPLIT_MIN_WIDTH = 1200;
export const RATIO_MIN = 0.3;
export const RATIO_MAX = 0.7;
export const KEY = 'tgb.layout';

/**
 * `ratio`: the right pane's share while no pane shows the jobs. `jobsRatio`: the jobs pane's share set by hand, and
 * `ratioJobs` the number of jobs then (both null when never set). `jobsSeen`: the last number of jobs that changed
 * their share, so the first paint before the snapshot already has it (null: none seen yet).
 */
export const DEFAULTS = Object.freeze({
  mode: 'split', right: 'jobs', left: 'log', active: 'right', ratio: 0.55, jobsRatio: null, ratioJobs: null, jobsSeen: null,
});
/** From this many jobs the jobs pane gets half the room; fewer get a third. app.css sets the columns by the pane's width. */
export const JOBS_HALF_FROM = 4;

const other = (pane) => (pane === 'right' ? 'left' : 'right');

/** `"split"` only when split is chosen and the window is at least 1200 px wide. */
export function effectiveMode(state, width) {
  return state.mode === 'split' && width >= SPLIT_MIN_WIDTH ? 'split' : 'single';
}

/** `tab` in `pane`, which becomes active. When the other pane shows `tab`, the two swap. */
export function choose(state, pane, tab) {
  if (!PANES.includes(pane) || !TABS.includes(tab)) return state;
  const o = other(pane);
  const next = { ...state, [pane]: tab, active: pane };
  if (state[o] === tab) next[o] = state[pane];
  return next;
}

/**
 * One screen: `tab` in the active pane, without a swap; the other pane keeps its tab, so both may name the same tab
 * until the split is back (see resolveSplit).
 */
export function showInActive(state, tab) {
  if (!TABS.includes(tab)) return state;
  return { ...state, [state.active]: tab };
}

/**
 * Back to two screens: when both panes name the same tab, the other pane gets `displaced`, the tab the active pane
 * had when the split was left (or else the first other tab).
 */
export function resolveSplit(state, displaced) {
  if (state.right !== state.left) return state;
  const mine = state[state.active];
  const tab = TABS.includes(displaced) && displaced !== mine ? displaced : TABS.find((t) => t !== mine);
  return { ...state, [other(state.active)]: tab };
}

/** The ratio within 0.3..0.7, to two decimals; anything not a number gives the default. */
export function clampRatio(r) {
  if (typeof r !== 'number' || !Number.isFinite(r)) return DEFAULTS.ratio;
  return Math.round(Math.min(RATIO_MAX, Math.max(RATIO_MIN, r)) * 100) / 100;
}

/** The jobs pane's share of the room for `count` jobs: a third up to 3, half from 4. */
export function jobsShare(count) {
  return count >= JOBS_HALF_FROM ? 0.5 : 0.33;
}

/** The desktop layout's width, as app.css's @media (min-width: 900px): from it `➕ הוסף משימה` heads the job list. */
export const DESKTOP_QUERY = '(min-width: 900px)';

/**
 * The job list's children in order (plan 3b, the user's ruling): on a desktop (`addOnTop`) the add button after the
 * header and the warning, before the cards, so Tab reaches it before them; under 900 px it closes the list as before.
 * Missing parts (no warning) are left out.
 */
export function jobsListOrder({ header, warning = null, cards = [], add }, addOnTop) {
  return (addOnTop ? [header, warning, add, ...cards] : [header, warning, ...cards, add]).filter(Boolean);
}

/** The pane that shows the jobs, or null. */
function jobsSide(state) {
  return state.right === 'jobs' ? 'right' : state.left === 'jobs' ? 'left' : null;
}

const rest = (share) => Math.round((1 - share) * 100) / 100;

/**
 * The right pane's effective share. With the jobs in a pane: their share set by hand while it was set with the same
 * number of jobs, otherwise the jobs share; the right pane gets it or the rest, by the jobs' side. Without the jobs in
 * view: the saved ratio.
 */
export function ratioFor(state, jobsCount) {
  const side = jobsSide(state);
  if (!side) return state.ratio;
  const share = state.jobsRatio !== null && state.jobsRatio !== undefined && state.ratioJobs === jobsCount
    ? state.jobsRatio : jobsShare(jobsCount);
  return side === 'right' ? share : rest(share);
}

/**
 * The divider moved by hand to `ratio` (the right pane's share). With the jobs in view, it is kept as the jobs pane's
 * share with the number of jobs at the time; without them, as the ratio of that pair only.
 */
export function manualRatio(state, ratio, jobsCount) {
  const side = jobsSide(state);
  if (!side) return { ...state, ratio: clampRatio(ratio) };
  return { ...state, jobsRatio: clampRatio(side === 'right' ? ratio : 1 - ratio), ratioJobs: jobsCount };
}

/** The state with the number of jobs seen kept, only when it changes the jobs share (the same object otherwise). */
export function seenJobs(state, jobsCount) {
  if (jobsShare(jobsCount) === jobsShare(state.jobsSeen ?? 0)) return state;
  return { ...state, jobsSeen: jobsCount };
}

const isCount = (n) => Number.isSafeInteger(n) && n >= 0;

/** A validated state from JSON: every bad or missing field takes its default; the panes never show the same tab. */
export function parse(json) {
  let raw;
  try {
    raw = JSON.parse(json);
  } catch {
    return { ...DEFAULTS };
  }
  if (!raw || typeof raw !== 'object' || Array.isArray(raw)) return { ...DEFAULTS };
  const s = {
    mode: raw.mode === 'single' || raw.mode === 'split' ? raw.mode : DEFAULTS.mode,
    right: TABS.includes(raw.right) ? raw.right : DEFAULTS.right,
    left: TABS.includes(raw.left) ? raw.left : DEFAULTS.left,
    active: PANES.includes(raw.active) ? raw.active : DEFAULTS.active,
    ratio: raw.ratio === undefined ? DEFAULTS.ratio : clampRatio(raw.ratio),
    jobsRatio: null,
    ratioJobs: isCount(raw.ratioJobs) ? raw.ratioJobs : null,
    jobsSeen: isCount(raw.jobsSeen) ? raw.jobsSeen : null,
  };
  if (s.right === s.left) {
    s.right = DEFAULTS.right;
    s.left = DEFAULTS.left;
  }
  if (raw.jobsRatio === undefined && s.ratioJobs !== null) {
    // A Task 10c layout: `ratio` was the right pane's share, set with the jobs on the side they are on now.
    const side = jobsSide(s);
    if (side) s.jobsRatio = side === 'right' ? s.ratio : clampRatio(1 - s.ratio);
  } else if (typeof raw.jobsRatio === 'number' && Number.isFinite(raw.jobsRatio)) {
    s.jobsRatio = clampRatio(raw.jobsRatio);
  }
  // The share set by hand and its count go together.
  if (s.jobsRatio === null || s.ratioJobs === null) {
    s.jobsRatio = null;
    s.ratioJobs = null;
  }
  return s;
}

export function serialize(state) {
  const { mode, right, left, active, ratio, jobsRatio = null, ratioJobs = null, jobsSeen = null } = state;
  return JSON.stringify({ mode, right, left, active, ratio, jobsRatio, ratioJobs, jobsSeen });
}

/** The saved state, or the defaults when there is none or the storage cannot be read. */
export function load(storage) {
  try {
    return parse(storage?.getItem(KEY));
  } catch {
    return { ...DEFAULTS };
  }
}

export function save(storage, state) {
  try {
    storage?.setItem(KEY, serialize(state));
  } catch {
    // Storage off (private window, blocked site data): the layout lasts until the page closes.
  }
}
