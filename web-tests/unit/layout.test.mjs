import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  DEFAULTS, TABS, SPLIT_MIN_WIDTH, effectiveMode, choose, showInActive, resolveSplit, clampRatio, parse, serialize, load, save,
  ratioFor, jobsShare, DESKTOP_QUERY, jobsListOrder, manualRatio, seenJobs,
} from '../../desktop/src/main/resources/web/lib/layout.js';

const split = { ...DEFAULTS };

test('the defaults: split, jobs on the right, log on the left, the right pane active, ratio 0.55', () => {
  assert.deepEqual(DEFAULTS, { mode: 'split', right: 'jobs', left: 'log', active: 'right', ratio: 0.55, jobsRatio: null, ratioJobs: null, jobsSeen: null });
  assert.deepEqual(TABS, ['jobs', 'export', 'accounts', 'log', 'settings']);
  assert.equal(SPLIT_MIN_WIDTH, 1200);
});

test('effectiveMode is split only from 1200 px and only when split is chosen', () => {
  assert.equal(effectiveMode(split, 1199), 'single');
  assert.equal(effectiveMode(split, 1200), 'split');
  assert.equal(effectiveMode(split, 1920), 'split');
  assert.equal(effectiveMode({ ...split, mode: 'single' }, 1920), 'single');
  assert.equal(effectiveMode(split, 390), 'single');
});

test('choose puts a tab in a pane and makes that pane active', () => {
  const s = choose(split, 'left', 'accounts');
  assert.deepEqual(s, { ...split, left: 'accounts', active: 'left' });
  // Not mutated.
  assert.equal(split.left, 'log');
});

test('choose swaps the panes when the other pane already shows the tab', () => {
  assert.deepEqual(choose(split, 'left', 'jobs'), { ...split, right: 'log', left: 'jobs', active: 'left' });
  assert.deepEqual(choose(split, 'right', 'log'), { ...split, right: 'log', left: 'jobs', active: 'right' });
});

test('choose of the tab a pane already shows only makes it active', () => {
  assert.deepEqual(choose(split, 'left', 'log'), { ...split, active: 'left' });
});

test('choose ignores an unknown pane or tab', () => {
  assert.deepEqual(choose(split, 'left', 'nope'), split);
  assert.deepEqual(choose(split, 'middle', 'jobs'), split);
});

test('clampRatio keeps 0.3..0.7', () => {
  assert.equal(clampRatio(0.1), 0.3);
  assert.equal(clampRatio(0.9), 0.7);
  assert.equal(clampRatio(0.5), 0.5);
  assert.equal(clampRatio(0.55 + 0.05), 0.6);
  assert.equal(clampRatio(NaN), 0.55);
  assert.equal(clampRatio('x'), 0.55);
});

test('parse of garbage, null or a non-object gives the defaults', () => {
  for (const bad of ['garbage', '', null, undefined, '42', '[]', '"split"', 'null']) {
    assert.deepEqual(parse(bad), DEFAULTS, String(bad));
  }
});

test('parse of a valid state round-trips through serialize', () => {
  const s = { mode: 'single', right: 'accounts', left: 'export', active: 'left', ratio: 0.4, jobsRatio: null, ratioJobs: null, jobsSeen: null };
  assert.deepEqual(parse(serialize(s)), s);
  assert.deepEqual(parse(serialize(DEFAULTS)), DEFAULTS);
});

test('parse keeps the valid fields and takes the defaults for the others', () => {
  assert.deepEqual(parse('{"mode":"single"}'), { ...DEFAULTS, mode: 'single' });
  assert.deepEqual(parse('{"mode":"x","right":"export","active":"up","ratio":2}'), { ...DEFAULTS, right: 'export', ratio: 0.7 });
});

test('parse never gives the same tab in both panes', () => {
  assert.deepEqual(parse('{"right":"log","left":"log"}'), { ...DEFAULTS });
  // right=log alone collides with the default left (log): the default panes come back.
  assert.deepEqual(parse('{"right":"log"}'), DEFAULTS);
});

test('serialize writes only the eight fields', () => {
  assert.deepEqual(JSON.parse(serialize({ ...DEFAULTS, extra: 1 })), DEFAULTS);
});

test('load and save use tgb.layout and survive a storage that throws', () => {
  const mem = new Map();
  const storage = { getItem: (k) => (mem.has(k) ? mem.get(k) : null), setItem: (k, v) => mem.set(k, v) };
  assert.deepEqual(load(storage), DEFAULTS);
  save(storage, { ...DEFAULTS, left: 'settings' });
  assert.deepEqual(JSON.parse(mem.get('tgb.layout')), { ...DEFAULTS, left: 'settings' });
  assert.deepEqual(load(storage), { ...DEFAULTS, left: 'settings' });
  const broken = { getItem: () => { throw new Error('denied'); }, setItem: () => { throw new Error('denied'); } };
  assert.deepEqual(load(broken), DEFAULTS);
  assert.doesNotThrow(() => save(broken, DEFAULTS));
  assert.deepEqual(load(undefined), DEFAULTS);
});

test('showInActive puts a tab in the active pane and never swaps', () => {
  assert.deepEqual(showInActive(split, 'export'), { ...split, right: 'export' });
  assert.deepEqual(showInActive(split, 'log'), { ...split, right: 'log' });
  assert.deepEqual(showInActive({ ...split, active: 'left' }, 'jobs'), { ...split, active: 'left', left: 'jobs' });
  assert.deepEqual(showInActive(split, 'nope'), split);
});

test('resolveSplit gives the other pane the displaced tab only when both panes name the same tab', () => {
  assert.deepEqual(resolveSplit({ ...split, right: 'export' }, 'jobs'), { ...split, right: 'export' });
  assert.deepEqual(resolveSplit({ ...split, right: 'log' }, 'jobs'), { ...split, right: 'log', left: 'jobs' });
  assert.deepEqual(resolveSplit({ ...split, active: 'left', right: 'jobs', left: 'jobs' }, 'log'), { ...split, active: 'left', right: 'log', left: 'jobs' });
  // No usable displaced tab: the first other tab.
  assert.deepEqual(resolveSplit({ ...split, right: 'log' }, 'log'), { ...split, right: 'log', left: 'jobs' });
  assert.deepEqual(resolveSplit({ ...split, right: 'jobs', left: 'jobs' }, undefined), { ...split, right: 'jobs', left: 'export' });
});

// Task 10c: the jobs pane's width follows the number of jobs (a third up to 3, half from 4), unless the divider was
// moved by hand while that many jobs existed.
test('jobsShare: a third for 0..3 jobs, half from 4', () => {
  for (const n of [0, 1, 2, 3]) assert.equal(jobsShare(n), 0.33, String(n));
  for (const n of [4, 5, 7, 20]) assert.equal(jobsShare(n), 0.5, String(n));
});

// Plan 3b, part C: the cards fill the jobs pane, one column while it is a third, two once it is half.
// Plan 3b, the user's ruling: on a desktop (from 900 px) `➕ הוסף משימה` heads the list, right after the header.
test('DESKTOP_QUERY is the 900 px of app.css', () => {
  assert.equal(DESKTOP_QUERY, '(min-width: 900px)');
});

test('jobsListOrder: on a desktop the add button right after the header, before the warning and the cards', () => {
  const parts = { header: 'H', warning: 'W', cards: ['c1', 'c2', 'c3'], add: 'A' };
  assert.deepEqual(jobsListOrder(parts, true), ['H', 'A', 'W', 'c1', 'c2', 'c3']);
  assert.deepEqual(jobsListOrder({ ...parts, warning: null }, true), ['H', 'A', 'c1', 'c2', 'c3']);
  assert.deepEqual(jobsListOrder({ ...parts, cards: [] }, true), ['H', 'A', 'W']);
});

test('jobsListOrder: under 900 px as before, the add button after the last card', () => {
  const parts = { header: 'H', warning: 'W', cards: ['c1', 'c2'], add: 'A' };
  assert.deepEqual(jobsListOrder(parts, false), ['H', 'W', 'c1', 'c2', 'A']);
  assert.deepEqual(jobsListOrder({ ...parts, warning: null, cards: [] }, false), ['H', 'A']);
  assert.deepEqual(jobsListOrder({ header: 'H', add: 'A' }, false), ['H', 'A']);
});

test('ratioFor with jobs on the right: the right pane gets the jobs share', () => {
  const s = { ...DEFAULTS, ratioJobs: null };
  for (const n of [0, 1, 2, 3]) assert.equal(ratioFor(s, n), 0.33, String(n));
  for (const n of [4, 7]) assert.equal(ratioFor(s, n), 0.5, String(n));
});

test('ratioFor with jobs on the left: the right pane gets the rest', () => {
  const s = { ...DEFAULTS, right: 'log', left: 'jobs', ratioJobs: null };
  for (const n of [0, 1, 2, 3]) assert.equal(ratioFor(s, n), 0.67, String(n));
  for (const n of [4, 7]) assert.equal(ratioFor(s, n), 0.5, String(n));
});

test('ratioFor: the jobs share set by hand holds only while the number of jobs is the one it was set with', () => {
  const s = { ...DEFAULTS, jobsRatio: 0.62, ratioJobs: 2 };
  assert.equal(ratioFor(s, 2), 0.62);
  assert.equal(ratioFor(s, 3), 0.33);
  assert.equal(ratioFor(s, 1), 0.33);
  assert.equal(ratioFor(s, 4), 0.5);
  // Zero jobs set by hand is a count like any other.
  assert.equal(ratioFor({ ...s, ratioJobs: 0 }, 0), 0.62);
  assert.equal(ratioFor({ ...s, ratioJobs: 0 }, 1), 0.33);
});

test('ratioFor: the share set by hand belongs to the jobs, on whichever side they are (final wave)', () => {
  const s = { ...DEFAULTS, jobsRatio: 0.62, ratioJobs: 2 };
  // The jobs on the left: the right pane gets the rest, so the jobs keep their width.
  assert.equal(ratioFor({ ...s, right: 'export', left: 'jobs' }, 2), 0.38);
  assert.equal(ratioFor({ ...s, right: 'export', left: 'jobs' }, 5), 0.5);
  assert.equal(ratioFor({ ...s, right: 'log', left: 'jobs' }, 3), 0.67);
});

test('ratioFor without jobs in view: the saved ratio, never the jobs share', () => {
  const s = { ...DEFAULTS, right: 'accounts', left: 'export', ratio: 0.42 };
  assert.equal(ratioFor(s, 0), 0.42);
  assert.equal(ratioFor(s, 7), 0.42);
  assert.equal(ratioFor({ ...s, jobsRatio: 0.6, ratioJobs: 5 }, 5), 0.42);
  assert.equal(ratioFor({ ...DEFAULTS, right: 'log', left: 'settings' }, 2), 0.55);
});

test('manualRatio with the jobs in view keeps the jobs share and the number of jobs; ratio stays', () => {
  assert.deepEqual(manualRatio(DEFAULTS, 0.61, 3), { ...DEFAULTS, jobsRatio: 0.61, ratioJobs: 3 });
  assert.deepEqual(manualRatio(DEFAULTS, 0.95, 0), { ...DEFAULTS, jobsRatio: 0.7, ratioJobs: 0 });
  // The jobs on the left: their share is the rest of the right pane's.
  const left = { ...DEFAULTS, right: 'log', left: 'jobs' };
  assert.deepEqual(manualRatio(left, 0.6, 2), { ...left, jobsRatio: 0.4, ratioJobs: 2 });
  // Moved on one side, shown the same on the other.
  assert.equal(ratioFor({ ...manualRatio(DEFAULTS, 0.45, 2), right: 'log', left: 'jobs' }, 2), 0.55);
});

test('manualRatio without the jobs in view sets only ratio; the jobs share and its count are not touched', () => {
  const s = { ...DEFAULTS, right: 'accounts', left: 'export', jobsRatio: 0.4, ratioJobs: 2 };
  assert.deepEqual(manualRatio(s, 0.66, 2), { ...s, ratio: 0.66 });
  const none = { ...DEFAULTS, right: 'accounts', left: 'export' };
  assert.deepEqual(manualRatio(none, 0.2, 5), { ...none, ratio: 0.3 });
});

test('parse: jobsRatio with its count, both or neither; anything bad null', () => {
  assert.deepEqual(parse('{"jobsRatio":0.4,"ratioJobs":4}'), { ...DEFAULTS, jobsRatio: 0.4, ratioJobs: 4 });
  assert.deepEqual(parse('{"jobsRatio":0.9,"ratioJobs":0}'), { ...DEFAULTS, jobsRatio: 0.7, ratioJobs: 0 });
  for (const bad of ['-1', '1.5', '"3"', 'true', '[]', '{}', 'null', '1e400']) {
    assert.deepEqual(parse(`{"jobsRatio":0.4,"ratioJobs":${bad}}`), DEFAULTS, bad);
  }
  for (const bad of ['"0.4"', 'true', '[]', '{}', 'null', '1e400']) {
    assert.deepEqual(parse(`{"jobsRatio":${bad},"ratioJobs":2}`), DEFAULTS, bad);
  }
  const s = { ...DEFAULTS, ratio: 0.45, jobsRatio: 0.4, ratioJobs: 2, jobsSeen: 2 };
  assert.deepEqual(parse(serialize(s)), s);
});

test('parse: an old tgb.layout loads; a 10c ratio set with the jobs in view becomes the jobs share', () => {
  // Before Task 10c: no count at all.
  assert.deepEqual(parse('{"mode":"split","right":"jobs","left":"log","active":"right","ratio":0.6}'), { ...DEFAULTS, ratio: 0.6 });
  // Task 10c: `ratio` was the right pane's share, `ratioJobs` the count.
  assert.deepEqual(parse('{"right":"jobs","left":"log","ratio":0.6,"ratioJobs":2}'), { ...DEFAULTS, ratio: 0.6, jobsRatio: 0.6, ratioJobs: 2 });
  assert.deepEqual(parse('{"right":"log","left":"jobs","ratio":0.6,"ratioJobs":2}'), { ...DEFAULTS, right: 'log', left: 'jobs', ratio: 0.6, jobsRatio: 0.4, ratioJobs: 2 });
  // Saved without the jobs in view: only the ratio of that pair.
  assert.deepEqual(parse('{"right":"accounts","left":"export","ratio":0.6,"ratioJobs":2}'), { ...DEFAULTS, right: 'accounts', left: 'export', ratio: 0.6 });
  for (const bad of ['-1', '1.5', '"3"', 'true', '[]', '{}', 'null', '1e400']) {
    assert.equal(parse(`{"ratioJobs":${bad}}`).ratioJobs, null, bad);
  }
});

test('parse: jobsSeen a count of jobs, anything else null', () => {
  assert.equal(parse('{"jobsSeen":5}').jobsSeen, 5);
  assert.equal(parse('{"jobsSeen":0}').jobsSeen, 0);
  for (const bad of ['-1', '1.5', '"3"', 'true', '[]', 'null', '1e400']) assert.equal(parse(`{"jobsSeen":${bad}}`).jobsSeen, null, bad);
});

test('seenJobs keeps the last number of jobs only when the jobs share it gives changes (no write otherwise)', () => {
  assert.equal(seenJobs(DEFAULTS, 2), DEFAULTS);
  assert.deepEqual(seenJobs(DEFAULTS, 4), { ...DEFAULTS, jobsSeen: 4 });
  const four = { ...DEFAULTS, jobsSeen: 4 };
  assert.equal(seenJobs(four, 7), four);
  assert.deepEqual(seenJobs(four, 3), { ...DEFAULTS, jobsSeen: 3 });
});
