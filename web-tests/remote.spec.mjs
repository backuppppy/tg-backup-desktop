import { test, expect } from '@playwright/test';

// The phone's server (RemoteServer), started by global-setup with the password `secret1`. No stored state: every
// test starts logged out, as a new phone would.
const REMOTE_URL = process.env.TGB_DEV_REMOTE_URL;
const TABS = ['משימות', 'ייצוא', 'חשבונות', 'לוג', 'הגדרות'];

test.use({ baseURL: REMOTE_URL, storageState: { cookies: [], origins: [] } });

test.beforeEach(({}, testInfo) => {
  test.skip(testInfo.project.name !== 'phone', 'the phone project only');
});

async function login(page) {
  await page.goto('/');
  await page.getByLabel('סיסמה').fill('secret1');
  await page.getByRole('button', { name: 'כניסה' }).click();
  await expect(page.locator('#title')).toContainText('TG Backup  v');
}

test('opening / shows the login page', async ({ page }) => {
  await page.goto('/');
  await expect(page).toHaveURL(/\/login\.html$/);
  await expect(page.getByRole('heading', { name: 'TG Backup' })).toBeVisible();
  await expect(page.getByLabel('סיסמה')).toBeVisible();
  await expect(page.getByRole('button', { name: 'כניסה' })).toBeVisible();
});

test('a wrong password shows סיסמה שגויה', async ({ page }) => {
  await page.goto('/');
  await page.getByLabel('סיסמה').fill('not-the-password');
  await page.getByRole('button', { name: 'כניסה' }).click();
  await expect(page.locator('#login-error')).toHaveText('סיסמה שגויה');
  await expect(page).toHaveURL(/\/login\.html$/);
});

test('secret1 opens the jobs screen with the bottom tabs', async ({ page }) => {
  await login(page);
  await expect(page).toHaveURL(/\/(#jobs)?$/);
  await expect(page.locator('nav.tabs a')).toHaveText(TABS);
  await expect(page.locator('nav.tabs a[aria-current="page"]')).toHaveText('משימות');
  const nav = await page.locator('nav.tabs').boundingBox();
  expect(nav.y + nav.height).toBeGreaterThan(page.viewportSize().height - 2);
});

test('a reload stays logged in', async ({ page }) => {
  await login(page);
  await page.reload();
  await expect(page.locator('#title')).toContainText('TG Backup  v');
  await expect(page).not.toHaveURL(/login/);
});

test('the tabs navigate', async ({ page }) => {
  await login(page);
  for (const [name, hash] of [['ייצוא', 'export'], ['חשבונות', 'accounts'], ['לוג', 'log'], ['הגדרות', 'settings'], ['משימות', 'jobs']]) {
    await page.locator('nav.tabs a', { hasText: name }).click();
    await expect(page).toHaveURL(new RegExp(`#${hash}$`));
    await expect(page.locator('nav.tabs a[aria-current="page"]')).toHaveText(name);
  }
});

// ---------------------------------------------------------------- Settings and export on the phone
// The window's own server (the stored session cookie) publishes test states and disconnects all.
const LOCAL_URL = `http://127.0.0.1:${process.env.TGB_DEV_PORT || '8765'}`;

async function windowRequest(playwright) {
  return playwright.request.newContext({ baseURL: LOCAL_URL, storageState: '.auth/state.json' });
}

async function openSettings(page) {
  await page.locator('nav.tabs a', { hasText: 'הגדרות' }).click();
  await expect(page.locator('[data-screen="settings"]')).toBeVisible();
}

test('Settings on the phone: the card חיבור מהטלפון with התנתק, no phone-access switch', async ({ page }) => {
  await login(page);
  await openSettings(page);
  const card = page.locator('[data-screen="settings"] section.card', { has: page.getByRole('heading', { name: 'חיבור מהטלפון' }) });
  await expect(card.getByText('מחובר למחשב דרך Tailscale.')).toBeVisible();
  await expect(page.getByRole('switch', { name: 'גישה מהטלפון דרך Tailscale' })).toHaveCount(0);
  await expect(page.getByRole('heading', { name: 'גישה מהטלפון', exact: true })).toHaveCount(0);
  await card.getByRole('button', { name: 'התנתק' }).click();
  await expect(page).toHaveURL(/\/login\.html$/);
  // The session is gone: / goes back to the login.
  await page.goto('/');
  await expect(page).toHaveURL(/\/login\.html$/);
});

test('a logout the server did not answer stays logged in and says why', async ({ page }) => {
  await login(page);
  await openSettings(page);
  await page.route('**/api/logout', (route) => route.fulfill({ status: 500, contentType: 'application/json', body: '{"error":"השרת לא ענה"}' }));
  await page.getByRole('button', { name: 'התנתק' }).click();
  await expect(page.locator('#toast')).toHaveText('השרת לא ענה');
  await expect(page).not.toHaveURL(/login/);
  await page.unroute('**/api/logout');
  await page.route('**/api/logout', (route) => route.abort());
  await page.getByRole('button', { name: 'התנתק' }).click();
  await expect(page.locator('#toast')).toBeVisible();
  await page.waitForTimeout(500);
  await expect(page).not.toHaveURL(/login/);
});

test('export Finished on the phone shows where the files are instead of Show my data', async ({ page }) => {
  await login(page);
  // The phone's own stream, with a finished export in its snapshot (the DevServer's test routes are the window's).
  // From the page: its Secure cookie is the browser's (over http only to 127.0.0.1).
  const { serverNow, ...real } = await page.evaluate(async () => (await fetch('/api/state')).json());
  expect(real.viewer).toBe('remote');
  const finished = { accountId: 'a', title: 'Chat', state: { type: 'Finished', filesCount: 3, bytesCount: 1024, path: 'C:\\exports\\Chat' } };
  await page.route('**/api/events', (route) => route.fulfill({
    status: 200,
    headers: { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache' },
    body: `retry: 600000\nevent: snapshot\ndata: ${JSON.stringify({ ...real, runningExport: finished })}\n\n`,
  }));
  // A new page load, so the stream above is the one it opens.
  await page.goto('/#export');
  await page.reload();
  await expect(page.getByText('Data export completed.')).toBeVisible();
  await expect(page.getByText('הקבצים נשמרו במחשב, בתיקייה שלמעלה.')).toBeVisible();
  await expect(page.getByRole('button', { name: 'Show my data' })).toHaveCount(0);
  await expect(page.getByRole('button', { name: 'סגור' })).toBeVisible();
});

test('an open phone page lands on the login after disconnect all, without a reconnect loop', async ({ page, playwright }) => {
  await login(page);
  await openSettings(page);
  const win = await windowRequest(playwright);
  try {
    const res = await win.post('/api/remote/disconnect-all');
    expect(res.ok(), await res.text()).toBeTruthy();
  } finally {
    await win.dispose();
  }
  await expect(page).toHaveURL(/\/login\.html$/);
  let streams = 0;
  page.on('request', (r) => { if (r.url().includes('/api/events')) streams += 1; });
  await page.waitForTimeout(4000);
  expect(streams).toBe(0);
  await expect(page.getByRole('button', { name: 'כניסה' })).toBeVisible();
});

test('two phones: a logout on one leaves the other in', async ({ browser }) => {
  const opts = { baseURL: REMOTE_URL, storageState: { cookies: [], origins: [] }, viewport: { width: 390, height: 844 } };
  const one = await browser.newContext(opts);
  const two = await browser.newContext(opts);
  try {
    const a = await one.newPage();
    const b = await two.newPage();
    await login(a);
    await login(b);
    await openSettings(a);
    await a.getByRole('button', { name: 'התנתק' }).click();
    await expect(a).toHaveURL(/\/login\.html$/);
    await b.reload();
    await expect(b.locator('#title')).toContainText('TG Backup  v');
    await expect(b).not.toHaveURL(/login/);
  } finally {
    await one.close();
    await two.close();
  }
});

// ---------------------------------------------------------------- Installable on the phone (Task 5)
const OFFLINE_TEXT = 'אין חיבור למחשב. בודקים ש-Tailscale מחובר בטלפון ושהמחשב דלוק.';

test('the manifest is served without a session, as application/manifest+json, with the install fields', async ({ request }) => {
  const res = await request.get('/manifest.webmanifest');
  expect(res.status()).toBe(200);
  expect(res.headers()['content-type']).toMatch(/^application\/manifest\+json/);
  const m = await res.json();
  expect(m).toMatchObject({
    name: 'TG Backup', short_name: 'TG Backup', start_url: '/', scope: '/', display: 'standalone',
    dir: 'rtl', lang: 'he', background_color: '#0F1720', theme_color: '#0F1720',
  });
  expect(m.icons).toEqual([
    { src: 'icon-192.png', sizes: '192x192', type: 'image/png', purpose: 'any' },
    { src: 'icon-512.png', sizes: '512x512', type: 'image/png', purpose: 'any' },
  ]);
  for (const path of ['/icon-192.png', '/icon-512.png', '/apple-touch-icon.png', '/sw.js', '/offline.html']) {
    expect((await request.get(path)).status(), path).toBe(200);
  }
});

test('the login page links the manifest and registers the service worker', async ({ page }) => {
  await page.goto('/login.html');
  await expect(page.locator('link[rel="manifest"]')).toHaveAttribute('href', 'manifest.webmanifest');
  await expect(page.locator('link[rel="apple-touch-icon"]')).toHaveAttribute('href', 'apple-touch-icon.png');
  await expect(page.locator('meta[name="theme-color"]')).toHaveAttribute('content', '#0F1720');
  const scope = await page.evaluate(async () => (await navigator.serviceWorker.ready).scope);
  expect(new URL(scope).pathname).toBe('/');
});

test('the app on the phone registers the service worker', async ({ page }) => {
  await login(page);
  const scope = await page.evaluate(async () => (await navigator.serviceWorker.ready).scope);
  expect(new URL(scope).pathname).toBe('/');
});

/** Logs in and waits until the service worker controls the page (a reload after the first install). */
async function loginControlled(page) {
  await login(page);
  await page.evaluate(async () => { await navigator.serviceWorker.ready; });
  if (!(await page.evaluate(() => !!navigator.serviceWorker.controller))) await page.reload();
  await expect.poll(() => page.evaluate(() => !!navigator.serviceWorker.controller)).toBe(true);
  await expect(page.locator('#title')).toContainText('TG Backup  v');
}

test('offline, a page load shows the offline page with נסה שוב, which reloads once back online', async ({ page, context }) => {
  await loginControlled(page);
  await context.setOffline(true);
  await page.reload();
  await expect(page.getByText(OFFLINE_TEXT)).toBeVisible();
  const retry = page.getByRole('button', { name: 'נסה שוב' });
  await expect(retry).toBeVisible();
  await context.setOffline(false);
  await retry.click();
  await expect(page.locator('#title')).toContainText('TG Backup  v');
  await expect(page.getByText(OFFLINE_TEXT)).toHaveCount(0);
});

test('only offline.html is cached; /api/events and the app files are never answered from the cache', async ({ page, context }) => {
  await loginControlled(page);
  const cached = await page.evaluate(async () => {
    const urls = [];
    for (const name of await caches.keys()) {
      for (const req of await (await caches.open(name)).keys()) urls.push(new URL(req.url).pathname);
    }
    return urls;
  });
  expect(cached).toEqual(['/offline.html']);
  await context.setOffline(true);
  const results = await page.evaluate(async () => {
    const out = {};
    for (const path of ['/api/events', '/api/state', '/app.js', '/app.css']) {
      try {
        const r = await fetch(path);
        out[path] = `answered ${r.status}`;
      } catch {
        out[path] = 'failed';
      }
    }
    return out;
  });
  await context.setOffline(false);
  expect(results).toEqual({ '/api/events': 'failed', '/api/state': 'failed', '/app.js': 'failed', '/app.css': 'failed' });
});
