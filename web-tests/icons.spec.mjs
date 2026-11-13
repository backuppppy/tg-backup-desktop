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

// ---------------------------------------------------------------- Installable on the phone (Plan 6 Task 5)
// The install icons (from tools/make-icons.py, the BACKUP icon) and the links in both pages.
function pngSize(body) {
  expect(body.subarray(1, 4).toString('latin1')).toBe('PNG');
  return [body.readUInt32BE(16), body.readUInt32BE(20)];
}

for (const [path, size] of [['/icon-192.png', 192], ['/icon-512.png', 512], ['/apple-touch-icon.png', 180]]) {
  test(`${path} is a ${size} px PNG`, async ({ page }) => {
    const res = await page.request.get(path);
    expect(res.status()).toBe(200);
    expect(res.headers()['content-type']).toMatch(/^image\/png/);
    expect(pngSize(await res.body())).toEqual([size, size]);
  });
}

test('the page links the manifest, the theme colour and the apple-touch-icon', async ({ page }) => {
  await page.goto('/');
  await expect(page.locator('link[rel="manifest"]')).toHaveAttribute('href', 'manifest.webmanifest');
  await expect(page.locator('link[rel="apple-touch-icon"]')).toHaveAttribute('href', 'apple-touch-icon.png');
  await expect(page.locator('meta[name="theme-color"]')).toHaveAttribute('content', '#0F1720');
});

test('the window registers no service worker (the offline page is the phone\'s)', async ({ page }) => {
  await page.goto('/');
  await expect(page.locator('#title')).toContainText('TG Backup  v');
  await page.waitForTimeout(500);
  expect(await page.evaluate(async () => (await navigator.serviceWorker.getRegistrations()).length)).toBe(0);
});
