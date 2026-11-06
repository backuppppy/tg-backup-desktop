import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  DEFAULTS, TABS, SPLIT_MIN_WIDTH, effectiveMode, choose, showInActive, resolveSplit, clampRatio, parse, serialize, load, save,
  ratioFor, jobsShare, manualRatio,
} from '../../desktop/src/main/resources/web/lib/layout.js';

const split = { ...DEFAULTS };

test('the defaults: split, jobs on the right, log on the left, the right pane active, ratio 0.55', () => {
  assert.deepEqual(DEFAULTS, { mode: 'split', right: 'jobs', left: 'log', active: 'right', ratio: 0.55, ratioJobs: null });
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
  const s = { mode: 'single', right: 'accounts', left: 'export', active: 'left', ratio: 0.4, ratioJobs: null };
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

test('serialize writes only the six fields', () => {
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

test('ratioFor: a ratio set by hand holds only while the number of jobs is the one it was set with', () => {
  const s = { ...DEFAULTS, ratio: 0.62, ratioJobs: 2 };
  assert.equal(ratioFor(s, 2), 0.62);
  assert.equal(ratioFor(s, 3), 0.33);
  assert.equal(ratioFor(s, 1), 0.33);
  assert.equal(ratioFor(s, 4), 0.5);
  assert.equal(ratioFor({ ...s, right: 'export', left: 'jobs' }, 2), 0.62);
  assert.equal(ratioFor({ ...s, right: 'export', left: 'jobs' }, 5), 0.5);
  // Zero jobs set by hand is a count like any other.
  assert.equal(ratioFor({ ...s, ratioJobs: 0 }, 0), 0.62);
  assert.equal(ratioFor({ ...s, ratioJobs: 0 }, 1), 0.33);
});

test('ratioFor without jobs in view: the saved ratio, as before', () => {
  const s = { ...DEFAULTS, right: 'accounts', left: 'export', ratio: 0.42, ratioJobs: null };
  assert.equal(ratioFor(s, 0), 0.42);
  assert.equal(ratioFor(s, 7), 0.42);
  assert.equal(ratioFor({ ...s, ratioJobs: 3 }, 5), 0.42);
  assert.equal(ratioFor({ ...DEFAULTS, right: 'log', left: 'settings' }, 2), 0.55);
});

test('manualRatio keeps the ratio with the number of jobs it was set with', () => {
  assert.deepEqual(manualRatio(DEFAULTS, 0.61, 3), { ...DEFAULTS, ratio: 0.61, ratioJobs: 3 });
  assert.deepEqual(manualRatio(DEFAULTS, 0.95, 0), { ...DEFAULTS, ratio: 0.7, ratioJobs: 0 });
});

test('parse: ratioJobs a count of jobs, anything else null; an old tgb.layout without it loads', () => {
  assert.equal(DEFAULTS.ratioJobs, null);
  assert.deepEqual(parse('{"mode":"split","right":"jobs","left":"log","active":"right","ratio":0.6}'), { ...DEFAULTS, ratio: 0.6 });
  assert.equal(parse('{"ratioJobs":4}').ratioJobs, 4);
  assert.equal(parse('{"ratioJobs":0}').ratioJobs, 0);
  for (const bad of ['-1', '1.5', '"3"', 'true', '[]', '{}', 'null', '1e400']) {
    assert.equal(parse(`{"ratioJobs":${bad}}`).ratioJobs, null, bad);
  }
  const s = { ...DEFAULTS, ratio: 0.45, ratioJobs: 2 };
  assert.deepEqual(parse(serialize(s)), s);
});
