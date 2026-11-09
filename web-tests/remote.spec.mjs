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
