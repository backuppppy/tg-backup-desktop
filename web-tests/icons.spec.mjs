import { test, expect } from '@playwright/test';

test('the page links the favicon, and /favicon.png is a 64 px PNG', async ({ page }) => {
  await page.goto('/');
  await expect(page.locator('link[rel="icon"]')).toHaveAttribute('href', 'favicon.png');
  const res = await page.request.get('/favicon.png');
  expect(res.status()).toBe(200);
  expect(res.headers()['content-type']).toMatch(/^image\/png/);
  const body = await res.body();
  // PNG signature, then the IHDR width and height (big-endian) at bytes 16..23.
  expect(body.subarray(1, 4).toString('latin1')).toBe('PNG');
  expect(body.readUInt32BE(16)).toBe(64);
  expect(body.readUInt32BE(20)).toBe(64);
});

test('/favicon.png needs the session cookie like every other static file', async ({ playwright, baseURL }) => {
  const anon = await playwright.request.newContext({ baseURL, storageState: { cookies: [], origins: [] } });
  expect((await anon.get('/favicon.png')).status()).toBe(401);
  expect((await anon.get('/app.css')).status()).toBe(401);
  await anon.dispose();
});
