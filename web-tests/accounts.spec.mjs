// Accounts screen (docs/ui-inventory.md §2b). Live tests use dummy accounts (apiId 1): they reach NeedPhone and
// never log in. States the dummy cannot reach (NeedCode, NeedPassword, Ready, export session) come from a faked
// event stream, and the login calls from page.route, so no request ever goes to Telegram.
import { test, expect } from '@playwright/test';

const uid = () => `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 6)}`;
const NOTE = 'API ID ו-API Hash מקבלים בכתובת my.telegram.org ← API development tools.';
const MASK = '••••';

async function state(request) {
  return (await request.get('/api/state')).json();
}

/** A dummy account through the API. Returns its id. */
async function addAccount(request, name) {
  const res = await request.post('/api/accounts', {
    data: { name, apiId: 1, apiHash: '0'.repeat(32), phone: '+10000000000' },
  });
  expect(res.ok(), await res.text()).toBeTruthy();
  return (await state(request)).accounts.find((a) => a.name === name).id;
}

async function open(page) {
  await page.goto('/#accounts');
  await expect(page.locator('#title')).toContainText('TG Backup  v');
  await expect(page.getByText(NOTE, { exact: true })).toBeVisible();
}

const card = (page, name) => page.locator('.account-card', { has: page.locator('.account-title', { hasText: name }) });
const toastText = (page) => page.locator('#toast').evaluate((el) => (el.hidden ? '' : el.textContent));

test('addAccountValidation: שמור needs a numeric API ID, a hash of 16+ characters and a phone', async ({ page }) => {
  await open(page);
  await page.getByRole('button', { name: '➕ הוסף חשבון' }).click();
  const dialog = page.getByRole('dialog', { name: 'חשבון חדש' });
  await expect(dialog).toBeVisible();
  const save = dialog.getByRole('button', { name: 'שמור' });
  await expect(save).toBeDisabled();
  await dialog.getByLabel('שם').fill(`acc-${uid()}`);
  await dialog.getByLabel('API ID').fill('12a3');
  await expect(dialog.getByLabel('API ID')).toHaveValue('123');
  await dialog.getByLabel('API Hash').fill('  0123456789  ');
  await expect(dialog.getByLabel('API Hash')).toHaveValue('0123456789');
  await dialog.getByLabel('טלפון (+972…)').fill('+972500000000');
  await expect(save).toBeDisabled();
  await dialog.getByLabel('API Hash').fill('0'.repeat(32));
  await expect(save).toBeEnabled();
  await dialog.getByLabel('טלפון (+972…)').fill('   ');
  await expect(save).toBeDisabled();
  await dialog.getByLabel('טלפון (+972…)').fill('+972500000000');
  await dialog.getByLabel('API ID').fill('');
  await expect(save).toBeDisabled();
  await dialog.getByRole('button', { name: 'ביטול' }).click();
  await expect(dialog).toBeHidden();
});

test('dummyAccountShowsDisconnected: a saved account reaches מנותק and offers התחבר (שלח קוד)', async ({ page }) => {
  const name = `acc-${uid()}`;
  await open(page);
  await page.getByRole('button', { name: '➕ הוסף חשבון' }).click();
  const dialog = page.getByRole('dialog', { name: 'חשבון חדש' });
  await dialog.getByLabel('שם').fill(`  ${name}  `);
  await dialog.getByLabel('API ID').fill('1');
  await dialog.getByLabel('API Hash').fill('0'.repeat(32));
  await dialog.getByLabel('טלפון (+972…)').fill(' +10000000000 ');
  await dialog.getByRole('button', { name: 'שמור' }).click();
  await expect(dialog).toBeHidden();
  const c = card(page, name);
  await expect(c.locator('.account-title')).toHaveText(name);
  await expect(c.locator('.account-phone')).toHaveText('+10000000000');
  await expect(c.getByText('📱 סשן רגיל: מנותק', { exact: true })).toBeVisible({ timeout: 30_000 });
  await expect(c.getByRole('button', { name: 'התחבר (שלח קוד)' })).toBeVisible();
  await expect(c.getByRole('button', { name: 'התחבר (שלח קוד)' })).toBeEnabled();
  await expect(c.getByRole('button', { name: 'ערוך' })).toBeVisible();
  await expect(c.getByRole('button', { name: 'מחק' })).toBeVisible();
  await expect(c.getByRole('button', { name: 'התנתק' })).toHaveCount(0);
  // No export session line or buttons while not Ready.
  await expect(c.getByText(/סשן ייצוא/)).toHaveCount(0);
  const saved = (await state(page.request)).accounts.find((a) => a.name === name);
  expect(saved).toMatchObject({ apiId: 1, hasApiHash: true, phone: '+10000000000' });
});

test('editKeepsHash: renaming sends the masked hash, which the server keeps', async ({ page }) => {
  const name = `acc-${uid()}`;
  const renamed = `${name}-ren`;
  const id = await addAccount(page.request, name);
  await open(page);
  await card(page, name).getByRole('button', { name: 'ערוך' }).click();
  const dialog = page.getByRole('dialog', { name: 'עריכת חשבון' });
  await expect(dialog.getByLabel('שם')).toHaveValue(name);
  await expect(dialog.getByLabel('API ID')).toHaveValue('1');
  await expect(dialog.getByLabel('API Hash')).toHaveValue(MASK);
  await expect(dialog.getByLabel('טלפון (+972…)')).toHaveValue('+10000000000');
  const save = dialog.getByRole('button', { name: 'שמור' });
  await expect(save).toBeEnabled();
  await dialog.getByLabel('שם').fill(renamed);
  const put = page.waitForRequest((r) => r.method() === 'PUT' && r.url().endsWith(`/api/accounts/${id}`));
  await save.click();
  expect((await put).postDataJSON()).toEqual({ name: renamed, apiId: 1, apiHash: MASK, phone: '+10000000000' });
  await expect(dialog).toBeHidden();
  const acc = (await state(page.request)).accounts.find((a) => a.id === id);
  expect(acc).toMatchObject({ name: renamed, hasApiHash: true, apiId: 1 });
  await expect(card(page, renamed).getByText('📱 סשן רגיל: מנותק', { exact: true })).toBeVisible({ timeout: 30_000 });
});

test('editing the hash to a short value disables שמור', async ({ page }) => {
  const name = `acc-${uid()}`;
  await addAccount(page.request, name);
  await open(page);
  await card(page, name).getByRole('button', { name: 'ערוך' }).click();
  const dialog = page.getByRole('dialog', { name: 'עריכת חשבון' });
  await dialog.getByLabel('API Hash').fill('abc');
  await expect(dialog.getByRole('button', { name: 'שמור' })).toBeDisabled();
  await dialog.getByRole('button', { name: 'ביטול' }).click();
});

test('deleteConfirm: the dialog asks first, and אישור removes the card', async ({ page }) => {
  const name = `acc-${uid()}`;
  const id = await addAccount(page.request, name);
  await open(page);
  await card(page, name).getByRole('button', { name: 'מחק' }).click();
  const dialog = page.getByRole('dialog', { name: `למחוק את החשבון ${name}?` });
  await expect(dialog).toBeVisible();
  await expect(dialog).toContainText('החשבון יתנתק ויוסר מכל המשימות.');
  await dialog.getByRole('button', { name: 'ביטול' }).click();
  await expect(dialog).toBeHidden();
  await expect(card(page, name)).toHaveCount(1);
  await card(page, name).getByRole('button', { name: 'מחק' }).click();
  await page.getByRole('dialog', { name: `למחוק את החשבון ${name}?` }).getByRole('button', { name: 'אישור' }).click();
  await expect(card(page, name)).toHaveCount(0, { timeout: 30_000 });
  expect((await state(page.request)).accounts.some((a) => a.id === id)).toBe(false);
});

// ---------- Faked states ----------

const acc = (id, over = {}) => ({ id, name: `name-${id}`, apiId: 1, hasApiHash: true, phone: `+1000000${id}`, ...over });
const AUTH = {
  Starting: { type: 'Starting', label: 'מאתחל…' },
  NeedPhone: { type: 'NeedPhone', label: 'מנותק' },
  NeedCode: { type: 'NeedCode', label: 'ממתין לקוד' },
  NeedPassword: (hint) => ({ type: 'NeedPassword', label: 'ממתין לסיסמת אימות דו-שלבי', hint }),
  Ready: (display) => ({ type: 'Ready', label: 'מחבר', display }),
  Unsupported: (why) => ({ type: 'Unsupported', label: why, why }),
  Closed: { type: 'Closed', label: 'נסגר' },
};

/**
 * Serves a snapshot with `accounts` and their `states` as the whole event stream, and answers each account's
 * `GET /api/export/{id}/session` from `sessions` (id -> loggedIn). Returns the list of session ids asked.
 */
async function fakeAccounts(page, { accounts, states, availableAt = {}, sessions = {} }) {
  const real = await state(page.request);
  const snapshot = {
    ...real,
    log: [],
    accounts,
    accountStates: states,
    exportPrefs: { ...real.exportPrefs, availableAt },
  };
  await page.route('**/api/events', (route) => route.fulfill({
    status: 200,
    headers: { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache' },
    body: `retry: 600000\nevent: snapshot\ndata: ${JSON.stringify(snapshot)}\n\n`,
  }));
  const asked = [];
  await page.route(/\/api\/export\/[^/]+\/session$/, (route) => {
    const id = route.request().url().split('/').at(-2);
    asked.push(id);
    return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ loggedIn: !!sessions[id] }) });
  });
  return asked;
}

const okJson = (route, body = { ok: true }) => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) });
const errJson = (route, status, error) => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify({ error }) });

test('each auth state shows its Android line and controls', async ({ page }) => {
  await fakeAccounts(page, {
    accounts: [acc('a1'), acc('a2'), acc('a3'), acc('a4'), acc('a5', { name: '' }), acc('a6'), acc('a7')],
    states: [
      { id: 'a1', auth: AUTH.Starting },
      { id: 'a2', auth: AUTH.NeedCode, lastError: '400: PHONE_CODE_INVALID' },
      { id: 'a3', auth: AUTH.NeedPassword('החתול') },
      { id: 'a4', auth: AUTH.NeedPassword('') },
      { id: 'a5', auth: AUTH.Unsupported('המספר לא רשום בטלגרם') },
      { id: 'a6', auth: AUTH.Closed },
      { id: 'a7', auth: AUTH.NeedPhone },
    ],
  });
  await open(page);
  const byId = (id) => page.locator(`.account-card[data-account-id="${id}"]`);

  await expect(byId('a1')).toContainText('📱 סשן רגיל: מאתחל…');
  await expect(byId('a1').locator('input')).toBeHidden();
  await expect(byId('a1').getByRole('button')).toHaveText(['ערוך', 'מחק']);

  const code = byId('a2').getByLabel('קוד מטלגרם');
  await expect(byId('a2')).toContainText('📱 סשן רגיל: ממתין לקוד');
  await expect(byId('a2').locator('.account-error')).toHaveText('400: PHONE_CODE_INVALID');
  await expect(code).toHaveAttribute('inputmode', 'numeric');
  await expect(byId('a2').getByRole('button', { name: 'אשר' })).toBeDisabled();
  await code.fill('  ');
  await expect(byId('a2').getByRole('button', { name: 'אשר' })).toBeDisabled();
  await code.fill('12345');
  await expect(byId('a2').getByRole('button', { name: 'אשר' })).toBeEnabled();
  await expect(byId('a2').getByRole('button', { name: 'התנתק' })).toBeEnabled();

  await expect(byId('a3')).toContainText('📱 סשן רגיל: ממתין לסיסמת אימות דו-שלבי');
  await expect(byId('a3').getByLabel('סיסמה (רמז: החתול)')).toHaveAttribute('type', 'password');
  await expect(byId('a3').getByRole('button', { name: 'התנתק' })).toBeVisible();
  await expect(byId('a4').getByLabel('סיסמת אימות דו-שלבי')).toHaveAttribute('type', 'password');

  await expect(byId('a5').locator('.account-title')).toHaveText('ללא שם');
  const why = byId('a5').locator('.account-auth');
  await expect(why).toHaveText('📱 סשן רגיל: המספר לא רשום בטלגרם');
  await expect(why).toHaveCSS('color', 'rgb(240, 113, 120)');
  await expect(byId('a5').getByRole('button', { name: 'התנתק' })).toHaveCount(0);

  await expect(byId('a6')).toContainText('📱 סשן רגיל: נסגר');
  await expect(byId('a6').locator('input')).toBeHidden();
  await expect(byId('a7').getByRole('button', { name: 'התחבר (שלח קוד)' })).toBeEnabled();
  await expect(byId('a7').getByRole('button', { name: 'התנתק' })).toHaveCount(0);
  for (const id of ['a1', 'a2', 'a3', 'a5', 'a6', 'a7']) await expect(byId(id).getByText(/סשן ייצוא/)).toHaveCount(0);
});

test('Ready shows the connected line and the export session line per state', async ({ page }) => {
  const now = Math.floor(Date.now() / 1000);
  await page.clock.install({ time: now * 1000 });
  await fakeAccounts(page, {
    accounts: [acc('r1'), acc('r2'), acc('r3'), acc('r4')],
    states: [
      { id: 'r1', auth: AUTH.Ready('Dana Levi (@dana)') },
      { id: 'r2', auth: AUTH.Ready('Bob') },
      { id: 'r3', auth: AUTH.Ready('Carol') },
      { id: 'r4', auth: AUTH.Ready('Dave') },
    ],
    // r3 waits 2h 5m 30s, r4 waits 4m 10s.
    availableAt: { r3: now + 2 * 3600 + 5 * 60 + 30, r4: now + 250, r1: now - 10 },
    sessions: { r1: true, r2: false, r3: true, r4: true },
  });
  await open(page);
  const byId = (id) => page.locator(`.account-card[data-account-id="${id}"]`);
  const auth = byId('r1').locator('.account-auth');
  await expect(auth).toHaveText('✅ סשן רגיל: מחובר כ-Dana Levi (@dana)');
  await expect(auth).toHaveCSS('color', 'rgb(127, 209, 174)');
  await expect(byId('r1').locator('.account-export')).toHaveText('✅ סשן ייצוא: מחובר ומוכן');
  await expect(byId('r1').locator('.account-export')).toHaveCSS('color', 'rgb(127, 209, 174)');
  await expect(byId('r1').getByRole('button', { name: 'בדוק סשן ייצוא' })).toBeEnabled();
  await expect(byId('r1').getByRole('button', { name: 'חבר סשן ייצוא' })).toHaveCount(0);
  await expect(byId('r1').getByRole('button', { name: 'התנתק' })).toBeEnabled();
  await expect(byId('r1').locator('input')).toBeHidden();

  await expect(byId('r2').locator('.account-export')).toHaveText('📤 סשן ייצוא: לא מחובר');
  await expect(byId('r2').getByRole('button', { name: 'חבר סשן ייצוא' })).toBeEnabled();
  await expect(byId('r2').getByRole('button', { name: 'בדוק סשן ייצוא' })).toHaveCount(0);

  const wait3 = byId('r3').locator('.account-export');
  await expect(wait3).toHaveText("⏳ סשן ייצוא: ממתין לאישור ב-Telegram (עוד 2ש' 5ד')");
  await expect(wait3).toHaveCSS('color', 'rgb(240, 113, 120)');
  await expect(byId('r4').locator('.account-export')).toHaveText("⏳ סשן ייצוא: ממתין לאישור ב-Telegram (עוד 4ד')");

  // The countdown moves once a minute while it is shown, and ends in the ready line.
  await page.clock.runFor(61_000);
  await expect(wait3).toHaveText("⏳ סשן ייצוא: ממתין לאישור ב-Telegram (עוד 2ש' 4ד')");
  await expect(byId('r4').locator('.account-export')).toHaveText("⏳ סשן ייצוא: ממתין לאישור ב-Telegram (עוד 3ד')");
  await page.clock.runFor(4 * 60_000);
  await expect(byId('r4').locator('.account-export')).toHaveText('✅ סשן ייצוא: מחובר ומוכן');
});

test('the export line reads בודק… until the session answer comes', async ({ page }) => {
  await fakeAccounts(page, { accounts: [acc('s1')], states: [{ id: 's1', auth: AUTH.Ready('S') }] });
  let release;
  const gate = new Promise((r) => { release = r; });
  await page.unroute(/\/api\/export\/[^/]+\/session$/);
  await page.route('**/api/export/s1/session', async (route) => {
    await gate;
    await okJson(route, { loggedIn: false });
  });
  await open(page);
  const line = page.locator('.account-card[data-account-id="s1"] .account-export');
  await expect(line).toHaveText('📤 סשן ייצוא: בודק…');
  await expect(page.locator('.account-card[data-account-id="s1"]').getByRole('button', { name: /סשן ייצוא/ })).toHaveCount(0);
  release();
  await expect(line).toHaveText('📤 סשן ייצוא: לא מחובר');
});

test('sending a code: the body carries it, the input clears on success and stays on error', async ({ page }) => {
  await fakeAccounts(page, { accounts: [acc('c1')], states: [{ id: 'c1', auth: AUTH.NeedCode }] });
  const bodies = [];
  let fail = true;
  await page.route('**/api/accounts/c1/code', async (route) => {
    bodies.push(route.request().postDataJSON());
    if (fail) await errJson(route, 400, 'שגיאה: 400: PHONE_CODE_INVALID');
    else await okJson(route);
  });
  await open(page);
  const c = page.locator('.account-card[data-account-id="c1"]');
  const input = c.getByLabel('קוד מטלגרם');
  await input.fill(' 11111 ');
  await c.getByRole('button', { name: 'אשר' }).click();
  await expect.poll(() => toastText(page)).toBe('שגיאה: 400: PHONE_CODE_INVALID');
  await expect(input).toHaveValue(' 11111 ');
  fail = false;
  await input.fill('22222');
  await input.press('Enter');
  await expect(input).toHaveValue('');
  expect(bodies).toEqual([{ code: '11111' }, { code: '22222' }]);
  expect(page.url()).not.toContain('22222');
});

test('a password goes in the body only; a timeout shows the server text', async ({ page }) => {
  await fakeAccounts(page, { accounts: [acc('p1')], states: [{ id: 'p1', auth: AUTH.NeedPassword('') }] });
  const bodies = [];
  await page.route('**/api/accounts/p1/password', async (route) => {
    bodies.push(route.request().postDataJSON());
    await errJson(route, 504, 'טלגרם לא ענה בזמן, נסה שוב');
  });
  const logs = [];
  page.on('console', (m) => logs.push(m.text()));
  await open(page);
  const c = page.locator('.account-card[data-account-id="p1"]');
  await c.getByLabel('סיסמת אימות דו-שלבי').fill('s3cr3t pass');
  await c.getByRole('button', { name: 'אשר' }).click();
  await expect.poll(() => toastText(page)).toBe('טלגרם לא ענה בזמן, נסה שוב');
  expect(bodies).toEqual([{ password: 's3cr3t pass' }]);
  expect(logs.join('\n')).not.toContain('s3cr3t');
  expect(page.url()).not.toContain('s3cr3t');
});

test('busy: the buttons are disabled while a request is in flight', async ({ page }) => {
  await fakeAccounts(page, { accounts: [acc('b1')], states: [{ id: 'b1', auth: AUTH.NeedPhone }] });
  let release;
  const gate = new Promise((r) => { release = r; });
  let calls = 0;
  await page.route('**/api/accounts/b1/phone', async (route) => {
    calls += 1;
    await gate;
    await okJson(route);
  });
  await open(page);
  const send = page.locator('.account-card[data-account-id="b1"]').getByRole('button', { name: 'התחבר (שלח קוד)' });
  await send.click();
  await expect(send).toBeDisabled();
  release();
  await expect(send).toBeEnabled();
  expect(calls).toBe(1);
});

test('התנתק posts logout', async ({ page }) => {
  await fakeAccounts(page, { accounts: [acc('l1')], states: [{ id: 'l1', auth: AUTH.NeedCode }] });
  let calls = 0;
  await page.route('**/api/accounts/l1/logout', async (route) => { calls += 1; await okJson(route); });
  await open(page);
  await page.locator('.account-card[data-account-id="l1"]').getByRole('button', { name: 'התנתק' }).click();
  await expect.poll(() => calls).toBe(1);
});

test('export login: the Android failure toast, then the session is asked again', async ({ page }) => {
  const asked = await fakeAccounts(page, { accounts: [acc('e1')], states: [{ id: 'e1', auth: AUTH.Ready('E') }], sessions: { e1: false } });
  let release;
  const gate = new Promise((r) => { release = r; });
  await page.route('**/api/export/e1/login', async (route) => {
    await gate;
    await errJson(route, 400, 'ההתחברות נכשלה: ההתחברות בוטלה');
  });
  await open(page);
  const c = page.locator('.account-card[data-account-id="e1"]');
  await c.getByRole('button', { name: 'חבר סשן ייצוא' }).click();
  await expect(c.getByRole('button', { name: 'מחבר…' })).toBeDisabled();
  await expect(c.locator('.account-export')).toHaveText('📤 סשן ייצוא: בודק…');
  const before = asked.length;
  release();
  await expect.poll(() => toastText(page)).toBe('חיבור סשן ייצוא נכשל: ההתחברות בוטלה');
  await expect(c.getByRole('button', { name: 'חבר סשן ייצוא' })).toBeEnabled();
  expect(asked.length).toBeGreaterThan(before);
});

test('export login success re-checks and shows the check button', async ({ page }) => {
  const sessions = { e2: false };
  await fakeAccounts(page, { accounts: [acc('e2')], states: [{ id: 'e2', auth: AUTH.Ready('E') }], sessions });
  await page.route('**/api/export/e2/login', async (route) => { sessions.e2 = true; await okJson(route); });
  await open(page);
  const c = page.locator('.account-card[data-account-id="e2"]');
  await c.getByRole('button', { name: 'חבר סשן ייצוא' }).click();
  await expect(c.locator('.account-export')).toHaveText('✅ סשן ייצוא: מחובר ומוכן');
  await expect(c.getByRole('button', { name: 'בדוק סשן ייצוא' })).toBeEnabled();
});

test('בדוק סשן ייצוא: ready, waiting and failure toasts', async ({ page }) => {
  await fakeAccounts(page, { accounts: [acc('k1')], states: [{ id: 'k1', auth: AUTH.Ready('K') }], sessions: { k1: true } });
  const answers = [
    (route) => okJson(route, { ready: true }),
    (route) => okJson(route, { ready: false }),
    (route) => errJson(route, 400, 'בדיקה נכשלה: FLOOD_WAIT_X'),
  ];
  await page.route('**/api/export/k1/check', (route) => answers.shift()(route));
  await open(page);
  const check = page.locator('.account-card[data-account-id="k1"]').getByRole('button', { name: 'בדוק סשן ייצוא' });
  const expected = [
    '✅ סשן הייצוא מוכן להעברה',
    '⏳ טלגרם עדיין ממתין לאישורך — לחץ Allow בהודעת טלגרם',
    'בדיקה נכשלה: FLOOD_WAIT_X',
  ];
  for (const text of expected) {
    await check.click();
    await expect.poll(() => toastText(page)).toBe(text);
    await page.evaluate(() => { document.getElementById('toast').hidden = true; });
    await expect(check).toBeEnabled();
    // Let the 4 s toast slot pass so the next one shows.
    await page.waitForTimeout(4100);
  }
});

test('typing survives accountStates and log events (the input is kept across renders)', async ({ page }) => {
  const real = await state(page.request);
  const accounts = [acc('t1'), acc('t2')];
  const snapshot = {
    ...real,
    log: [],
    accounts,
    accountStates: [{ id: 't1', auth: AUTH.NeedCode }, { id: 't2', auth: AUTH.NeedPhone }],
  };
  let release;
  const gate = new Promise((r) => { release = r; });
  let calls = 0;
  await page.route('**/api/events', async (route) => {
    calls += 1;
    if (calls === 1) {
      return route.fulfill({
        status: 200,
        headers: { 'Content-Type': 'text/event-stream' },
        body: `retry: 300\nevent: snapshot\ndata: ${JSON.stringify(snapshot)}\n\n`,
      });
    }
    if (calls > 2) return;
    await gate;
    const log = { entries: [{ seq: 77777, time: '12:00:00', level: 'INFO', msg: 'שורת לוג' }], cleared: false };
    const states = [
      { id: 't1', auth: AUTH.NeedCode, lastError: '400: PHONE_CODE_EMPTY' },
      { id: 't2', auth: AUTH.Starting },
    ];
    return route.fulfill({
      status: 200,
      headers: { 'Content-Type': 'text/event-stream' },
      body: `retry: 600000\nevent: log\ndata: ${JSON.stringify(log)}\n\nevent: accountStates\ndata: ${JSON.stringify(states)}\n\n`,
    });
  });
  await open(page);
  const c = page.locator('.account-card[data-account-id="t1"]');
  const input = c.getByLabel('קוד מטלגרם');
  await input.click();
  await input.pressSequentially('987');
  release();
  await expect(c.locator('.account-error')).toHaveText('400: PHONE_CODE_EMPTY');
  await expect(page.locator('.account-card[data-account-id="t2"]')).toContainText('📱 סשן רגיל: מאתחל…');
  await expect(input).toHaveValue('987');
  expect(await input.evaluate((el) => document.activeElement === el)).toBe(true);
  await input.pressSequentially('6');
  await expect(input).toHaveValue('9876');
});
