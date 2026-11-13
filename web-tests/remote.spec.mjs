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
