import { test } from 'node:test';
import assert from 'node:assert/strict';
import { waitingPasswords, nextPassword, passwordAsker } from '../../desktop/src/main/resources/web/lib/password.js';
import { reduce } from '../../desktop/src/main/resources/web/lib/store.js';

const p1 = { id: 'p1', hint: '', accountId: 'a1', origin: 'job' };
const p2 = { id: 'p2', hint: '', accountId: 'a2', origin: 'screen' };

test('the list comes from `passwords`, else from the single `password` (an older server)', () => {
  assert.deepEqual(waitingPasswords({ passwords: [p1, p2], password: p1 }), [p1, p2]);
  assert.deepEqual(waitingPasswords({ password: p2 }), [p2]);
  assert.deepEqual(waitingPasswords({ passwords: [], password: p2 }), [p2]);
  assert.deepEqual(waitingPasswords({ passwords: [], password: null }), []);
  assert.deepEqual(waitingPasswords({}), []);
});

test('the dialog shows the oldest request not answered yet', () => {
  const state = { passwords: [p1, p2], password: p1 };
  assert.equal(nextPassword(state, new Set()), p1);
  assert.equal(nextPassword(state, new Set(['p1'])), p2);
  assert.equal(nextPassword(state, new Set(['p1', 'p2'])), null);
});

test('who asks: a job or the export screen, and the account by its name', () => {
  const accounts = [{ id: 'a1', name: 'דני' }];
  assert.deepEqual(passwordAsker(p1, accounts), { who: 'משימה', account: 'דני' });
  assert.deepEqual(passwordAsker(p2, accounts), { who: 'ייצוא', account: 'a2' });
  // An older server sends neither.
  assert.equal(passwordAsker({ id: 'x', hint: '' }, accounts), null);
});

test('a `passwords` event sets the list and its oldest as `password`', () => {
  let s = reduce({}, 'passwords', [p1, p2]);
  assert.deepEqual(s.passwords, [p1, p2]);
  assert.equal(s.password, p1);
  s = reduce(s, 'passwords', []);
  assert.deepEqual(s.passwords, []);
  assert.equal(s.password, null);
});
