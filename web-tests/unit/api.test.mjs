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
