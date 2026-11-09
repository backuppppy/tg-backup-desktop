import { test } from 'node:test';
import assert from 'node:assert/strict';
import { api } from '../../desktop/src/main/resources/web/lib/api.js';

const reply = (status, body) => async () => new Response(JSON.stringify(body), {
  status, headers: { 'Content-Type': 'application/json' },
});

test('an error answer rejects with its text and HTTP status', async () => {
  globalThis.fetch = reply(409, { error: 'הבקשה כבר טופלה' });
  await assert.rejects(api('POST', '/api/export/password', { id: 'x', password: null }), (e) => {
    assert.equal(e.message, 'הבקשה כבר טופלה');
    assert.equal(e.status, 409);
    return true;
  });
});

test('an ok answer resolves with the body', async () => {
  globalThis.fetch = reply(200, { ok: true });
  assert.deepEqual(await api('DELETE', '/api/log'), { ok: true });
});

test('a 401 that asks for the login goes to the login page; other errors stay', async () => {
  const went = [];
  globalThis.location = { replace: (url) => went.push(url) };
  try {
    globalThis.fetch = reply(401, { error: 'נדרשת התחברות', login: true });
    await assert.rejects(api('GET', '/api/state'), (e) => e.status === 401);
    assert.deepEqual(went, ['/login.html']);
    // The window's server never sends `login`: its 401 does not leave the page.
    globalThis.fetch = reply(401, { error: 'נדרשת התחברות' });
    await assert.rejects(api('GET', '/api/state'), (e) => e.status === 401);
    assert.deepEqual(went, ['/login.html']);
  } finally {
    delete globalThis.location;
  }
});
