import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  DEFAULTS, TABS, SPLIT_MIN_WIDTH, effectiveMode, choose, clampRatio, parse, serialize, load, save,
} from '../../desktop/src/main/resources/web/lib/layout.js';

const split = { ...DEFAULTS };

test('the defaults: split, jobs on the right, log on the left, the right pane active, ratio 0.55', () => {
  assert.deepEqual(DEFAULTS, { mode: 'split', right: 'jobs', left: 'log', active: 'right', ratio: 0.55 });
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
  const s = { mode: 'single', right: 'accounts', left: 'export', active: 'left', ratio: 0.4 };
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

test('serialize writes only the five fields', () => {
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
