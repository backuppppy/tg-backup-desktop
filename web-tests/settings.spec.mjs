// The Settings screen's background card: both switches show the server's desktop prefs and save on change.
// It runs against the real dev server; the Run key is a no-op there (no jpackage exe), and every test puts the
// prefs back as it found them.
import { test, expect } from '@playwright/test';

const AUTOSTART = 'הפעלה עם Windows (בלי חלון, ליד השעון)';
const KEEP_AWAKE = 'מניעת שינה בזמן שמשימה או ייצוא רצים';

async function prefs(page) {
  return (await page.request.get('/api/desktop-prefs')).json();
}

async function open(page) {
  await page.goto('/#settings');
  await expect(page.locator('#title')).toContainText('TG Backup  v');
  await expect(page.locator('[data-screen="settings"]')).toBeVisible();
}

let before;
test.beforeEach(async ({ page }) => { before = await prefs(page); });
test.afterEach(async ({ page }) => { await page.request.put('/api/desktop-prefs', { data: before }); });

test('the background card explains closing the window and has both switches', async ({ page }) => {
  await open(page);
  await expect(page.getByRole('heading', { name: 'עבודה ברקע' })).toBeVisible();
  await expect(page.getByText('כשמשימה או ייצוא רצים וסוגרים את החלון, התוכנה שואלת אם להמשיך ברקע (סמל ליד השעון) או להיסגר. כדי שהמשימות יתקדמו, המחשב צריך להיות דלוק.')).toBeVisible();
  await expect(page.getByRole('switch', { name: AUTOSTART })).toBeVisible();
  await expect(page.getByRole('switch', { name: KEEP_AWAKE })).toBeVisible();
});

test('the switches show the saved prefs and follow a change made elsewhere', async ({ page }) => {
  await page.request.put('/api/desktop-prefs', { data: { autostart: false, keepAwake: true } });
  await open(page);
  const autostart = page.getByRole('switch', { name: AUTOSTART });
  const keepAwake = page.getByRole('switch', { name: KEEP_AWAKE });
  await expect(autostart).not.toBeChecked();
  await expect(keepAwake).toBeChecked();
  // The desktopPrefs event updates the open screen.
  await page.request.put('/api/desktop-prefs', { data: { autostart: true, keepAwake: false } });
  await expect(autostart).toBeChecked();
  await expect(keepAwake).not.toBeChecked();
});

test('changing a switch saves it with a PUT and keeps the other one', async ({ page }) => {
  await page.request.put('/api/desktop-prefs', { data: { autostart: false, keepAwake: true } });
  await open(page);
  const puts = [];
  page.on('request', (r) => { if (r.method() === 'PUT' && r.url().endsWith('/api/desktop-prefs')) puts.push(r.postDataJSON()); });
  await page.getByRole('switch', { name: AUTOSTART }).click();
  await expect.poll(() => prefs(page)).toEqual({ autostart: true, keepAwake: true });
  await page.getByRole('switch', { name: KEEP_AWAKE }).click();
  await expect.poll(() => prefs(page)).toEqual({ autostart: true, keepAwake: false });
  expect(puts).toEqual([{ autostart: true, keepAwake: true }, { autostart: true, keepAwake: false }]);
  await expect(page.getByRole('switch', { name: AUTOSTART })).toBeChecked();
  await expect(page.getByRole('switch', { name: KEEP_AWAKE })).not.toBeChecked();
});

test('a refused save shows the error and puts the switch back', async ({ page }) => {
  await page.request.put('/api/desktop-prefs', { data: { autostart: false, keepAwake: true } });
  await open(page);
  await page.route('**/api/desktop-prefs', (route) => (route.request().method() === 'PUT'
    ? route.fulfill({ status: 500, contentType: 'application/json', body: '{"error":"השמירה נכשלה"}' })
    : route.continue()));
  const keepAwake = page.getByRole('switch', { name: KEEP_AWAKE });
  await keepAwake.click();
  await expect(page.getByText('השמירה נכשלה')).toBeVisible();
  await expect(keepAwake).toBeChecked();
});
