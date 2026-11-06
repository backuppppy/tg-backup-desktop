// The split view's state (Task 10b): which screen each pane shows, which pane is active, one or two screens, and the
// right pane's share. Pure functions (unit-tested under node) plus load/save in localStorage under `tgb.layout`.

export const TABS = Object.freeze(['jobs', 'export', 'accounts', 'log', 'settings']);
export const PANES = Object.freeze(['right', 'left']);
/** From this window width a chosen split shows; below it one screen (the active pane's). */
export const SPLIT_MIN_WIDTH = 1200;
export const RATIO_MIN = 0.3;
export const RATIO_MAX = 0.7;
export const KEY = 'tgb.layout';

export const DEFAULTS = Object.freeze({ mode: 'split', right: 'jobs', left: 'log', active: 'right', ratio: 0.55 });

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
  };
  if (s.right === s.left) {
    s.right = DEFAULTS.right;
    s.left = DEFAULTS.left;
  }
  return s;
}

export function serialize(state) {
  const { mode, right, left, active, ratio } = state;
  return JSON.stringify({ mode, right, left, active, ratio });
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
