import { test } from 'node:test';
import assert from 'node:assert/strict';
import { waitingPasswords, nextPassword, passwordAsker, passwordText } from '../../desktop/src/main/resources/web/lib/password.js';
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
  const j2 = { id: 'j2', hint: '', accountId: 'a1', origin: 'job' };
  const state = { passwords: [p1, j2], password: p1 };
  assert.equal(nextPassword(state, new Set()), p1);
  assert.equal(nextPassword(state, new Set(['p1'])), j2);
  assert.equal(nextPassword(state, new Set(['p1', 'j2'])), null);
});

test('nextPassword: a screen request comes before an older job request', () => {
  const state = { passwords: [p1, p2], password: p1 };
  assert.equal(nextPassword(state, new Set()), p2);
  assert.equal(nextPassword(state, new Set(['p2'])), p1);
});

test('nextPassword: the oldest job request when no screen request waits', () => {
  const j2 = { id: 'j2', hint: '', accountId: 'a1', origin: 'job' };
  assert.equal(nextPassword({ passwords: [p1, j2] }, new Set()), p1);
  assert.equal(nextPassword({ passwords: [p1, j2] }, new Set(['p1'])), j2);
  // An older server sends no origin: plain oldest first.
  const old = { id: 'o1', hint: '' };
  assert.equal(nextPassword({ passwords: [old, j2] }, new Set()), old);
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

test('the dialog text: the hint when there is one, and the wrong-password line only after a wrong password', () => {
  assert.equal(passwordText({ id: 'x', hint: '' }), 'סשן הייצוא צריך את הסיסמה של החשבון.');
  assert.equal(passwordText({ id: 'x', hint: 'כלב' }), 'סשן הייצוא צריך את הסיסמה של החשבון.\nרמז: כלב');
  assert.ok(!passwordText({ id: 'x', hint: 'כלב', wrong: false }).includes('הסיסמה שגויה. נסה שוב.'));
  assert.ok(!passwordText({ id: 'x', hint: 'כלב' }).includes('הסיסמה שגויה'));
  const again = passwordText({ id: 'x', hint: 'כלב', wrong: true });
  assert.ok(again.includes('הסיסמה שגויה. נסה שוב.'), again);
  assert.ok(again.includes('רמז: כלב'), again);
});
