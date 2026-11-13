import { test } from 'node:test';
import assert from 'node:assert/strict';
import { reduce, LOG_MAX, OFFSET_JITTER_MS } from '../../desktop/src/main/resources/web/lib/store.js';

const entry = (seq) => ({ seq, time: '12:00:00', level: 'INFO', msg: `m${seq}` });

const snapshot = (log = []) => ({
  version: '0.1.0', tdlibVersion: '1.8.0', accounts: [], accountStates: [], jobs: [], statuses: {},
  exportPrefs: {}, runningExport: null, password: null, log, chatsLoading: {},
});

test('snapshot then a log event with 2 entries gives 2 more', () => {
  let s = reduce({}, 'snapshot', snapshot([entry(1)]));
  assert.equal(s.connected, true);
  s = reduce(s, 'log', { entries: [entry(2), entry(3)], cleared: false });
  assert.deepEqual(s.log.map((e) => e.seq), [1, 2, 3]);
});

test('600 entries are capped to the newest 500', () => {
  let s = reduce({}, 'snapshot', snapshot());
  const many = Array.from({ length: 600 }, (_, i) => entry(i + 1));
  s = reduce(s, 'log', { entries: many, cleared: false });
  assert.equal(LOG_MAX, 500);
  assert.equal(s.log.length, 500);
  assert.equal(s.log[0].seq, 101);
  assert.equal(s.log.at(-1).seq, 600);
});

test('cleared replaces the list', () => {
  let s = reduce({}, 'snapshot', snapshot([entry(1), entry(2)]));
  s = reduce(s, 'log', { entries: [entry(7)], cleared: true });
  assert.deepEqual(s.log.map((e) => e.seq), [7]);
});

test('a new snapshot replaces the whole state', () => {
  let s = reduce({}, 'snapshot', snapshot([entry(1)]));
  s = reduce(s, 'disconnected');
  assert.equal(s.connected, false);
  s = reduce(s, 'snapshot', { ...snapshot([entry(9)]), version: '0.2.0' });
  assert.equal(s.connected, true);
  assert.equal(s.version, '0.2.0');
  assert.deepEqual(s.log.map((e) => e.seq), [9]);
});

test('incremental events update their fields', () => {
  let s = reduce({}, 'snapshot', snapshot());
  s = reduce(s, 'config', { accounts: [{ id: 'a' }], jobs: [{ id: 'j' }] });
  assert.deepEqual([s.accounts[0].id, s.jobs[0].id], ['a', 'j']);
  s = reduce(s, 'statuses', { j: { running: true } });
  assert.equal(s.statuses.j.running, true);
  s = reduce(s, 'accountStates', [{ id: 'a', auth: { type: 'NeedPhone', label: 'מנותק' } }]);
  assert.equal(s.accountStates[0].auth.type, 'NeedPhone');
  s = reduce(s, 'exportPrefs', { format: 'Html' });
  assert.equal(s.exportPrefs.format, 'Html');
  s = reduce(s, 'desktopPrefs', { autostart: true, keepAwake: false });
  assert.deepEqual(s.desktopPrefs, { autostart: true, keepAwake: false });
  s = reduce(s, 'export', { accountId: 'a', title: 't', state: { type: 'Idle' } });
  assert.equal(s.runningExport.title, 't');
  s = reduce(s, 'password', { id: 'p1', hint: 'h' });
  assert.equal(s.password.id, 'p1');
  s = reduce(s, 'password', null);
  assert.equal(s.password, null);
  s = reduce(s, 'chatsProgress', { accountId: 'a', count: 40 });
  assert.equal(s.chatsLoading.a, 40);
  s = reduce(s, 'chatsProgress', { accountId: 'a', count: 50, done: true });
  assert.equal('a' in s.chatsLoading, false);
});

test('a snapshot log over 500 is capped too', () => {
  const s = reduce({}, 'snapshot', snapshot(Array.from({ length: 501 }, (_, i) => entry(i))));
  assert.equal(s.log.length, 500);
});

test('serverNow gives the clock offset at receipt; without it the offset is 0 (the page clock)', () => {
  let s = reduce({}, 'snapshot', { ...snapshot(), serverNow: 160_000 }, 100_000);
  assert.equal(s.clockOffset, 60_000);
  s = reduce(s, 'statuses', { statuses: { j: { running: true } }, serverNow: 150_000 }, 200_000);
  assert.equal(s.statuses.j.running, true);
  assert.equal(s.clockOffset, -50_000);
  // A statuses event of the old shape (the map alone) keeps the offset.
  s = reduce(s, 'statuses', { j: { running: false } }, 300_000);
  assert.equal(s.statuses.j.running, false);
  assert.equal(s.clockOffset, -50_000);
  // A snapshot without serverNow: the page's own clock.
  s = reduce(s, 'snapshot', snapshot(), 400_000);
  assert.equal(s.clockOffset, 0);
});

test('reduce: a statuses offset within 1000 ms of the current keeps it', () => {
  assert.equal(OFFSET_JITTER_MS, 1000);
  let s = reduce({}, 'snapshot', { ...snapshot(), serverNow: 100_000 }, 100_000);
  assert.equal(s.clockOffset, 0);
  s = reduce(s, 'statuses', { statuses: {}, serverNow: 200_400 }, 200_000);
  assert.equal(s.clockOffset, 0);
  s = reduce(s, 'statuses', { statuses: {}, serverNow: 301_500 }, 300_000);
  assert.equal(s.clockOffset, 1500);
  // A snapshot always sets it.
  s = reduce(s, 'snapshot', { ...snapshot(), serverNow: 400_300 }, 400_000);
  assert.equal(s.clockOffset, 300);
});

test('the snapshot keeps viewer and remote; a remote event replaces remote only', () => {
  const remote = { enabled: false, state: 'Off', devices: 0, hasPassword: true };
  let s = reduce({}, 'snapshot', { ...snapshot(), viewer: 'remote', remote });
  assert.equal(s.viewer, 'remote');
  assert.deepEqual(s.remote, remote);
  const running = { enabled: true, state: 'Running', url: 'https://pc.tail1.ts.net', devices: 2, hasPassword: true };
  s = reduce(s, 'remote', running);
  assert.deepEqual(s.remote, running);
  assert.equal(s.viewer, 'remote');
  assert.equal(s.version, '0.1.0');
});
