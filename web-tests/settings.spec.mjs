// The Settings screen's background card: both switches show the server's desktop prefs and save on change.
// It runs against the real dev server; the Run key is a no-op there (no jpackage exe), and every test puts the
// prefs back as it found them.
import { test, expect } from '@playwright/test';

const AUTOSTART = 'הפעלה עם Windows (בלי חלון, ליד השעון)';
const KEEP_AWAKE = 'מניעת שינה בזמן שמשימה או ייצוא רצים (המסך ממשיך להיכבות)';

/** The two background switches (the prefs also hold the phone access switch, tested below). */
async function prefs(page) {
  const { autostart, keepAwake } = await (await page.request.get('/api/desktop-prefs')).json();
  return { autostart, keepAwake };
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
  await expect(page.getByText('כשמשימה או ייצוא רצים וסוגרים את החלון, התוכנה ממשיכה לפעול ברקע (ענן ליד השעון). כשאין משימות פעילות, סגירת החלון מסיימת אותה. כדי שהמשימות יתקדמו, המחשב צריך להיות דלוק.')).toBeVisible();
  await expect(page.getByRole('switch', { name: AUTOSTART })).toBeVisible();
  await expect(page.getByRole('switch', { name: KEEP_AWAKE, exact: true })).toBeVisible();
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

test('the switches look like the job dialog switches (a flex row, a 40x22 track)', async ({ page }) => {
  await open(page);
  const row = page.locator('[data-screen="settings"] .switch-row').first();
  expect(await row.evaluate((el) => getComputedStyle(el).display)).toBe('flex');
  const input = page.getByRole('switch', { name: AUTOSTART });
  const look = await input.evaluate((el) => {
    const s = getComputedStyle(el);
    return { appearance: s.appearance, width: s.width, height: s.height };
  });
  // The job dialog's switch (.dialog input[role="switch"]): appearance none, 40 x 22 px.
  expect(look).toEqual({ appearance: 'none', width: '40px', height: '22px' });
});

test('overlapping saves: the first fails, the second succeeds, the switches end on the server state', async ({ page }) => {
  await page.request.put('/api/desktop-prefs', { data: { autostart: false, keepAwake: true } });
  await open(page);
  let n = 0;
  let releaseFirst;
  const firstHeld = new Promise((r) => { releaseFirst = r; });
  let releaseSecond;
  const secondHeld = new Promise((r) => { releaseSecond = r; });
  let secondSaved;
  const secondReached = new Promise((r) => { secondSaved = r; });
  await page.route('**/api/desktop-prefs', async (route) => {
    if (route.request().method() !== 'PUT') return route.continue();
    n += 1;
    if (n === 1) {
      await firstHeld;
      return route.abort();
    }
    // The second reaches the server now; its answer waits.
    const res = await route.fetch();
    secondSaved();
    await secondHeld;
    return route.fulfill({ response: res });
  });
  const autostart = page.getByRole('switch', { name: AUTOSTART });
  const keepAwake = page.getByRole('switch', { name: KEEP_AWAKE });
  await autostart.click();
  await keepAwake.click();
  await secondReached;
  releaseFirst();
  // The first save's failure is shown (while the second is still in flight, nothing is put back yet).
  await expect(page.locator('#toast')).toBeVisible();
  // Changed elsewhere while the second save is still waiting for its answer.
  await page.request.put('/api/desktop-prefs', { data: { autostart: false, keepAwake: true } });
  releaseSecond();
  await expect(autostart).not.toBeChecked();
  await expect(keepAwake).toBeChecked();
  expect(await prefs(page)).toEqual({ autostart: false, keepAwake: true });
});

test('a successful save keeps the new value while the desktopPrefs event has not come yet', async ({ page }) => {
  // The stream sends only the snapshot, so the desktopPrefs event never comes; the PUT answers ok without the server.
  const { serverNow, ...real } = await (await page.request.get('/api/state')).json();
  const snapshot = { ...real, desktopPrefs: { autostart: false, keepAwake: true } };
  await page.route('**/api/events', (route) => route.fulfill({
    status: 200,
    headers: { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache' },
    body: `retry: 600000\nevent: snapshot\ndata: ${JSON.stringify(snapshot)}\n\n`,
  }));
  await page.route('**/api/desktop-prefs', (route) => (route.request().method() === 'PUT'
    ? route.fulfill({ status: 200, contentType: 'application/json', body: '{"ok":true}' })
    : route.continue()));
  await open(page);
  const autostart = page.getByRole('switch', { name: AUTOSTART });
  await expect(autostart).not.toBeChecked();
  // Every frame's value of the switch, from the click on.
  await page.evaluate(() => {
    const input = [...document.querySelectorAll('input[role="switch"]')][0];
    window.seenChecked = [];
    const tick = () => { window.seenChecked.push(input.checked); requestAnimationFrame(tick); };
    requestAnimationFrame(tick);
  });
  const answered = page.waitForResponse((r) => r.request().method() === 'PUT' && r.url().endsWith('/api/desktop-prefs'));
  await autostart.click();
  await answered;
  expect(await autostart.isChecked()).toBe(true);
  await page.waitForTimeout(500);
  // Another render with the same (old) state: the tab is left and opened again.
  await page.evaluate(() => { location.hash = '#jobs'; });
  await page.evaluate(() => { location.hash = '#settings'; });
  await page.waitForTimeout(300);
  await expect(autostart).toBeChecked();
  const seen = await page.evaluate(() => window.seenChecked);
  expect(seen.slice(seen.indexOf(true))).not.toContain(false);
});

// ---------------------------------------------------------------- the phone-access card (the window)
// The states are published on the real stream by the DevServer's test-only POST /api/dev/remote-status; the
// DevServer's Tailscale is never installed. Every test switches the access off and publishes the real status again.

const REMOTE_SWITCH = 'גישה מהטלפון דרך Tailscale';
const STAYS_ON = 'המתג נשאר דלוק, והחיבור יעלה לבד כשזה יסודר.';
const STEPS = [
  'מתקינים את Tailscale במחשב ובטלפון (tailscale.com/download) ומתחברים לאותו חשבון.',
  'פעם אחת, באתר של Tailscale, בעמוד DNS, לוחצים Enable HTTPS. שם המחשב ב-Tailscale נרשם ביומן ציבורי של תעודות, לכן כדאי שלא יכיל מידע פרטי.',
  'מדליקים את המתג וקובעים סיסמה.',
  'סורקים את הקוד בטלפון, מזינים את הסיסמה, ובתפריט של כרום בוחרים "התקן אפליקציה".',
];
const STATES = [
  { state: 'Off', text: 'כבוי.', staysOn: false },
  { state: 'NotInstalled', text: 'Tailscale לא מותקן במחשב.', staysOn: true, link: ['להורדה', 'https://tailscale.com/download'] },
  { state: 'NotConnected', text: 'Tailscale מותקן אבל לא מחובר. פותחים את Tailscale ליד השעון ומתחברים.', staysOn: true },
  { state: 'HttpsOff', text: 'צריך להפעיל https בחשבון Tailscale (שלב 2).', staysOn: true, link: ['לעמוד DNS', 'https://login.tailscale.com/admin/dns'] },
  { state: 'PortBusy', text: 'הפורט 47800 במחשב תפוס על ידי תוכנה אחרת.', staysOn: true },
  { state: 'ServeBusy', text: 'ב-Tailscale כבר מוגדר שירות אחר על https. התוכנה לא דורסת אותו.', staysOn: true },
  { state: 'Error', detail: 'timeout', text: 'שגיאה מ-Tailscale: timeout', staysOn: true },
];
const RUNNING = { enabled: true, state: 'Running', url: 'https://my-pc.tail1234.ts.net', devices: 2, hasPassword: true };

async function publishRemote(page, status) {
  const res = await page.request.post('/api/dev/remote-status', { data: JSON.stringify(status), headers: { 'Content-Type': 'application/json' } });
  expect(res.ok(), await res.text()).toBeTruthy();
}

const remoteCard = (page) => page.locator('[data-screen="settings"] section.card', { has: page.getByRole('heading', { name: 'גישה מהטלפון', exact: true }) });

test.describe('the phone-access card', () => {
  test.afterEach(async ({ page }) => {
    await page.request.put('/api/remote/enabled', { data: { enabled: false } });
    await publishRemote(page, await (await page.request.get('/api/remote')).json());
  });

  test('each state shows its text, the line that the switch stays on where due, the links and the steps', async ({ page }) => {
    await open(page);
    const card = remoteCard(page);
    await expect(card.getByRole('switch', { name: REMOTE_SWITCH })).toBeVisible();
    for (const s of STATES) {
      await publishRemote(page, { enabled: s.state !== 'Off', state: s.state, detail: s.detail ?? null, devices: 0, hasPassword: true });
      await expect(card.locator('.remote-state')).toHaveText(s.text);
      await expect(card.getByText(STAYS_ON)).toHaveCount(s.staysOn ? 1 : 0);
      if (s.link) await expect(card.getByRole('link', { name: s.link[0] })).toHaveAttribute('href', s.link[1]);
      await expect(card.locator('ol li')).toHaveText(STEPS);
      await expect(card.getByRole('switch', { name: REMOTE_SWITCH })).toBeChecked({ checked: s.state !== 'Off' });
    }
    await expect(card.getByRole('link', { name: 'להורדה' })).toHaveCount(0);
  });

  test('Running shows the address, its QR, copy, the devices, and no steps', async ({ page }) => {
    await open(page);
    const card = remoteCard(page);
    await publishRemote(page, RUNNING);
    await expect(card.locator('.remote-state')).toHaveText('פועל. סורקים את הקוד בטלפון:');
    await expect(card.getByText('https://my-pc.tail1234.ts.net', { exact: true })).toBeVisible();
    await expect(card.locator('img')).toHaveAttribute('src', '/api/remote/qr.svg');
    await expect(card.getByText('מכשירים מחוברים: 2')).toBeVisible();
    await expect(card.getByText(STAYS_ON)).toHaveCount(0);
    await expect(card.locator('ol')).toHaveCount(0);
    await expect(card.getByRole('button', { name: 'החלף סיסמה' })).toBeVisible();
    await card.getByRole('button', { name: 'העתק' }).click();
    await expect(page.locator('#toast')).toHaveText('הועתק');
  });

  test('switching on without a password asks for one (6 characters, the same twice), saves it, then switches on', async ({ page }) => {
    await open(page);
    const card = remoteCard(page);
    await publishRemote(page, { enabled: false, state: 'Off', devices: 0, hasPassword: false });
    const puts = [];
    page.on('request', (r) => { if (r.method() === 'PUT' && r.url().includes('/api/remote/')) puts.push([new URL(r.url()).pathname, r.postDataJSON()]); });
    await card.getByRole('switch', { name: REMOTE_SWITCH }).click();
    const dialog = page.getByRole('dialog', { name: 'סיסמה לגישה מהטלפון' });
    await expect(dialog).toBeVisible();
    await expect(dialog.getByRole('button', { name: 'ביטול' })).toBeVisible();
    await dialog.getByLabel('סיסמה', { exact: true }).fill('abc');
    await dialog.getByLabel('שוב את הסיסמה').fill('abc');
    await dialog.getByRole('button', { name: 'שמור' }).click();
    await expect(dialog.getByText('לפחות 6 תווים')).toBeVisible();
    await dialog.getByLabel('סיסמה', { exact: true }).fill('secret1');
    await dialog.getByLabel('שוב את הסיסמה').fill('secret2');
    await dialog.getByRole('button', { name: 'שמור' }).click();
    await expect(dialog.getByText('הסיסמאות לא זהות')).toBeVisible();
    expect(puts).toEqual([]);
    // The dev server's password stays secret1 (remote.spec logs in with it).
    await dialog.getByLabel('שוב את הסיסמה').fill('secret1');
    await dialog.getByRole('button', { name: 'שמור' }).click();
    await expect(dialog).toHaveCount(0);
    await expect.poll(() => puts.length).toBe(2);
    expect(puts).toEqual([['/api/remote/password', { password: 'secret1' }], ['/api/remote/enabled', { enabled: true }]]);
    // The DevServer has no Tailscale.
    await expect(card.locator('.remote-state')).toHaveText('Tailscale לא מותקן במחשב.');
    await expect(card.getByRole('switch', { name: REMOTE_SWITCH })).toBeChecked();
  });

  test('cancelling the password dialog leaves the switch off', async ({ page }) => {
    await open(page);
    const card = remoteCard(page);
    await publishRemote(page, { enabled: false, state: 'Off', devices: 0, hasPassword: false });
    await card.getByRole('switch', { name: REMOTE_SWITCH }).click();
    await page.getByRole('dialog').getByRole('button', { name: 'ביטול' }).click();
    await expect(card.getByRole('switch', { name: REMOTE_SWITCH })).not.toBeChecked();
    expect((await (await page.request.get('/api/remote')).json()).enabled).toBe(false);
  });

  test('with a password the switch saves at once, both ways', async ({ page }) => {
    await open(page);
    const card = remoteCard(page);
    const sw = card.getByRole('switch', { name: REMOTE_SWITCH });
    await sw.click();
    await expect(page.getByRole('dialog')).toHaveCount(0);
    await expect.poll(async () => (await (await page.request.get('/api/remote')).json()).enabled).toBe(true);
    await expect(card.locator('.remote-state')).toHaveText('Tailscale לא מותקן במחשב.');
    await sw.click();
    await expect.poll(async () => (await (await page.request.get('/api/remote')).json()).enabled).toBe(false);
    await expect(card.locator('.remote-state')).toHaveText('כבוי.');
  });

  test('disconnect all posts and says so', async ({ page }) => {
    await open(page);
    const card = remoteCard(page);
    await publishRemote(page, RUNNING);
    const posted = page.waitForRequest((r) => r.method() === 'POST' && r.url().endsWith('/api/remote/disconnect-all'));
    await card.getByRole('button', { name: 'נתק את כל המכשירים' }).click();
    await posted;
    await expect(page.locator('#toast')).toHaveText('כל המכשירים נותקו');
  });
});
