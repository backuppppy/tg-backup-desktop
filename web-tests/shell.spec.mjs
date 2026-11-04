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

test('the five tabs appear in order from right to left', async ({ page }) => {
  await open(page);
  const tabs = page.locator('nav.tabs a');
  await expect(tabs).toHaveText(TABS);
  const xs = [];
  for (let i = 0; i < TABS.length; i++) {
    const box = await tabs.nth(i).boundingBox();
    xs.push(box.x);
  }
  for (let i = 1; i < xs.length; i++) expect(xs[i]).toBeLessThan(xs[i - 1]);
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
