import { test, expect } from '@playwright/test';

const TABS = ['משימות', 'ייצוא', 'חשבונות', 'לוג', 'הגדרות'];

/** Loads the page at `hash` and waits until the first snapshot has arrived. */
async function open(page, hash = '') {
  await page.goto(`/${hash}`);
  await expect(page.locator('#title')).toContainText('TG Backup  v');
}

test('the page is right-to-left Hebrew', async ({ page }) => {
  await open(page);
  expect(await page.evaluate(() => document.documentElement.dir)).toBe('rtl');
  expect(await page.evaluate(() => document.documentElement.lang)).toBe('he');
});

test('the top bar shows the version from /api/state', async ({ page }) => {
  const state = await (await page.request.get('/api/state')).json();
  await open(page);
  await expect(page.locator('#title')).toHaveText(`TG Backup  v${state.version}`);
});

test('the five tabs appear in order: right to left on the bottom bar, top to bottom on the desktop rail', async ({ page }) => {
  await open(page);
  const tabs = page.locator('nav.tabs a');
  await expect(tabs).toHaveText(TABS);
  const boxes = [];
  for (let i = 0; i < TABS.length; i++) boxes.push(await tabs.nth(i).boundingBox());
  // From 900 px wide the nav is a rail on the right (Task 10a).
  const rail = page.viewportSize().width >= 900;
  for (let i = 1; i < boxes.length; i++) {
    if (rail) expect(boxes[i].y).toBeGreaterThan(boxes[i - 1].y);
    else expect(boxes[i].x).toBeLessThan(boxes[i - 1].x);
  }
});

test('every tab has an icon above its label', async ({ page }) => {
  await open(page);
  const tabs = page.locator('nav.tabs a');
  for (let i = 0; i < TABS.length; i++) {
    const icon = tabs.nth(i).locator('svg');
    await expect(icon).toHaveCount(1);
    const iconBox = await icon.boundingBox();
    const labelBox = await tabs.nth(i).locator('.label').boundingBox();
    expect(iconBox.y + iconBox.height).toBeLessThanOrEqual(labelBox.y + 1);
  }
});

test('h() refuses an on* attribute that is not a function', async ({ page }) => {
  await open(page);
  const result = await page.evaluate(async () => {
    const { h } = await import('/lib/ui.js');
    try {
      const el = h('div', { onclick: 'window.__x = 1' });
      return el.hasAttribute('onclick') ? 'inline handler' : 'ignored';
    } catch (e) {
      return 'threw';
    }
  });
  expect(result).toBe('threw');
});

test('the default tab is jobs and the active tab follows the hash', async ({ page }) => {
  await open(page);
  await expect(page).toHaveURL(/#jobs$/);
  await expect(page.locator('nav.tabs a[aria-current="page"]')).toHaveText('משימות');
  await page.locator('nav.tabs a', { hasText: 'לוג' }).click();
  await expect(page).toHaveURL(/#log$/);
  await expect(page.locator('nav.tabs a[aria-current="page"]')).toHaveText('לוג');
});

test('#log shows the log screen', async ({ page }) => {
  await open(page, '#log');
  await expect(page.locator('[data-screen="log"]')).toBeVisible();
  await expect(page.getByRole('button', { name: 'נקה' })).toBeVisible();
  await expect(page.locator('.log-count')).toHaveText(/^\d+ שורות$/);
});

test('נקה empties the log', async ({ page }) => {
  await open(page, '#log');
  await page.getByRole('button', { name: 'נקה' }).click();
  await expect(page.locator('.log-count')).toHaveText('0 שורות');
  await expect(page.locator('.log-line')).toHaveCount(0);
});

test('settings shows the version and the links', async ({ page }) => {
  const state = await (await page.request.get('/api/state')).json();
  await open(page, '#settings');
  await expect(page.locator('[data-screen="settings"]')).toBeVisible();
  await expect(page.getByText(`גרסה ${state.version} · TDLib ${state.tdlibVersion}`)).toBeVisible();
  await expect(page.getByRole('heading', { name: 'עבודה ברקע' })).toBeVisible();
  await expect(page.getByText('בזמן שמשימה רצה המחשב לא ייכנס למצב שינה. סגירת החלון לא עוצרת את המשימות. כדי שהמשימות יתקדמו, המחשב צריך להיות דלוק.')).toBeVisible();
  await expect(page.getByRole('link', { name: 'קבוצת טלגרם' })).toHaveAttribute('href', 'https://t.me/backuppppy');
  await expect(page.getByRole('link', { name: 'GitHub' })).toHaveAttribute('href', 'https://github.com/backuppppy/tg-backup-desktop');
});

for (const hash of ['#jobs', '#export', '#accounts', '#log', '#settings']) {
  test(`no horizontal scroll on ${hash}`, async ({ page }) => {
    await open(page, hash);
    const { scrollWidth, innerWidth } = await page.evaluate(() => ({
      scrollWidth: document.documentElement.scrollWidth,
      innerWidth: window.innerWidth,
    }));
    expect(scrollWidth).toBeLessThanOrEqual(innerWidth);
  });
}

test('going offline shows the reconnect banner; back online it goes away', async ({ page, context }) => {
  await open(page, '#log');
  const banner = page.getByText('מתחבר מחדש…');
  await expect(banner).toBeHidden();
  await context.setOffline(true);
  await expect(banner).toBeVisible({ timeout: 20_000 });
  await context.setOffline(false);
  await expect(banner).toBeHidden({ timeout: 30_000 });
});

/** Serves `snapshot` (merged over the real one) as the whole event stream, and records password answers. */
async function fakeStream(page, patch) {
  const real = await (await page.request.get('/api/state')).json();
  const snapshot = { ...real, ...patch };
  await page.route('**/api/events', (route) => route.fulfill({
    status: 200,
    headers: { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache' },
    body: `retry: 600000\nevent: snapshot\ndata: ${JSON.stringify(snapshot)}\n\n`,
  }));
  const answers = [];
  await page.route('**/api/export/password', async (route) => {
    answers.push(route.request().postDataJSON());
    await route.fulfill({ status: 200, contentType: 'application/json', body: '{"ok":true}' });
  });
  return answers;
}

test('the export 2FA dialog shows on every tab and sends the password', async ({ page }) => {
  const answers = await fakeStream(page, { password: { id: 'p1', hint: 'החתול' } });
  await page.goto('/#settings');
  const dialog = page.getByRole('dialog', { name: 'סיסמת אימות דו-שלבי' });
  await expect(dialog).toBeVisible();
  await expect(dialog).toContainText('סשן הייצוא צריך את הסיסמה של החשבון.');
  await expect(dialog).toContainText('רמז: החתול');
  await expect(dialog.locator('input')).toHaveAttribute('type', 'password');
  await dialog.locator('input').fill('secret');
  await dialog.getByRole('button', { name: 'אישור' }).click();
  await expect(dialog).toBeHidden();
  await expect.poll(() => answers).toEqual([{ id: 'p1', password: 'secret' }]);
});

test('cancelling the 2FA dialog sends null', async ({ page }) => {
  const answers = await fakeStream(page, { password: { id: 'p2', hint: '' } });
  await page.goto('/#jobs');
  const dialog = page.getByRole('dialog', { name: 'סיסמת אימות דו-שלבי' });
  await expect(dialog).toBeVisible();
  await expect(dialog).not.toContainText('רמז');
  await dialog.getByRole('button', { name: 'ביטול' }).click();
  await expect.poll(() => answers).toEqual([{ id: 'p2', password: null }]);
});

test('an answered 2FA request does not reopen on later events', async ({ page }) => {
  const real = await (await page.request.get('/api/state')).json();
  const snapshot = { ...real, log: [], password: { id: 'p3', hint: '' } };
  let release;
  const answeredGate = new Promise((r) => { release = r; });
  let calls = 0;
  await page.route('**/api/events', async (route) => {
    calls += 1;
    if (calls === 1) {
      // The stream ends after the snapshot; the browser reconnects after `retry` ms.
      return route.fulfill({
        status: 200,
        headers: { 'Content-Type': 'text/event-stream' },
        body: `retry: 300\nevent: snapshot\ndata: ${JSON.stringify(snapshot)}\n\n`,
      });
    }
    if (calls > 2) return; // left pending: no more events
    await answeredGate;
    // Only a log event: the state still holds password p3 (the server has not sent password:null yet).
    const log = { entries: [{ seq: 99999, time: '12:00:00', level: 'INFO', msg: 'אחרי התשובה' }], cleared: false };
    return route.fulfill({
      status: 200,
      headers: { 'Content-Type': 'text/event-stream' },
      body: `retry: 600000\nevent: log\ndata: ${JSON.stringify(log)}\n\n`,
    });
  });
  const answers = [];
  await page.route('**/api/export/password', async (route) => {
    answers.push(route.request().postDataJSON());
    await route.fulfill({ status: 200, contentType: 'application/json', body: '{"ok":true}' });
  });
  await page.goto('/#log');
  const dialog = page.getByRole('dialog', { name: 'סיסמת אימות דו-שלבי' });
  await expect(dialog).toBeVisible();
  await dialog.locator('input').fill('secret');
  await dialog.getByRole('button', { name: 'אישור' }).click();
  await expect.poll(() => answers).toEqual([{ id: 'p3', password: 'secret' }]);
  release();
  await expect(page.getByText('אחרי התשובה')).toBeVisible();
  await expect(dialog).toBeHidden();
  await page.waitForTimeout(500);
  await expect(dialog).toHaveCount(0);
});

test('a 409 answer to the 2FA request shows no toast', async ({ page }) => {
  await fakeStream(page, { password: { id: 'p4', hint: '' } });
  await page.unroute('**/api/export/password');
  await page.route('**/api/export/password', (route) => route.fulfill({
    status: 409, contentType: 'application/json', body: JSON.stringify({ error: 'הבקשה כבר טופלה' }),
  }));
  await page.goto('/#jobs');
  const dialog = page.getByRole('dialog', { name: 'סיסמת אימות דו-שלבי' });
  await dialog.locator('input').fill('x');
  await dialog.getByRole('button', { name: 'אישור' }).click();
  await expect(dialog).toBeHidden();
  await page.waitForTimeout(800);
  expect(await page.evaluate(() => [document.getElementById('toast').hidden, document.getElementById('toast').textContent]))
    .toEqual([true, '']);
});

test('a 2FA answer that fails (not 409) can be sent again: the dialog comes back', async ({ page }) => {
  await fakeStream(page, { password: { id: 'p5', hint: '' } });
  await page.unroute('**/api/export/password');
  const answers = [];
  await page.route('**/api/export/password', async (route) => {
    answers.push(route.request().postDataJSON());
    // The first answer is lost on the way (a network failure); the second one gets through.
    if (answers.length === 1) return route.abort('failed');
    return route.fulfill({ status: 200, contentType: 'application/json', body: '{"ok":true}' });
  });
  await page.goto('/#jobs');
  const dialog = page.getByRole('dialog', { name: 'סיסמת אימות דו-שלבי' });
  await dialog.locator('input').fill('first');
  await dialog.getByRole('button', { name: 'אישור' }).click();
  await expect.poll(() => answers.length).toBe(1);
  await expect(page.locator('#toast')).toBeVisible();
  await expect(dialog).toBeVisible();
  await dialog.locator('input').fill('second');
  await dialog.getByRole('button', { name: 'אישור' }).click();
  await expect.poll(() => answers).toEqual([{ id: 'p5', password: 'first' }, { id: 'p5', password: 'second' }]);
  await expect(dialog).toBeHidden();
});
