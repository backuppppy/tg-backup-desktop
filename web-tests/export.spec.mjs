// Export screen (docs/ui-inventory.md §2c). Live tests use the real stream with dummy accounts (apiId 1; they never
// log in). The export session, chats, topics and start answers come from page.route, so nothing reaches Telegram.
// Progress panel states are published on the real stream by the DevServer's test-only POST /api/dev/export-state.
import { test, expect } from '@playwright/test';

const uid = () => `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 6)}`;
const MiB = 1024 * 1024;
const INTRO = 'ייצוא ערוץ, קבוצה או צ\'אט בדיוק כמו "Export chat history" של טלגרם דסקטופ (HTML / JSON), דרך Takeout session.';

test.use({ timezoneId: 'Asia/Jerusalem' });

async function state(request) {
  return (await request.get('/api/state')).json();
}

async function addAccount(request, name, phone = '+10000000000') {
  const res = await request.post('/api/accounts', { data: { name, apiId: 1, apiHash: '0'.repeat(32), phone } });
  expect(res.ok(), await res.text()).toBeTruthy();
  return (await state(request)).accounts.find((a) => a.phone === phone && a.name === name).id;
}

async function open(page) {
  await page.goto('/#export');
  await expect(page.locator('#title')).toContainText('TG Backup  v');
  await expect(page.getByText(INTRO, { exact: true })).toBeVisible();
}

const okJson = (route, body = { ok: true }) => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) });
const errJson = (route, status, error) => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify({ error }) });
const toastText = (page) => page.locator('#toast').evaluate((el) => (el.hidden ? '' : el.textContent));

/** Publishes `running` (a RunningExportDto or null) as the `export` event. */
async function publish(request, running) {
  const res = await request.post('/api/dev/export-state', { data: JSON.stringify(running), headers: { 'Content-Type': 'application/json' } });
  expect(res.ok(), await res.text()).toBeTruthy();
}

const CHATS = [
  { peerId: 11, title: 'Alpha News', username: 'alphanews', type: 'PublicChannel', isForum: false, archived: false },
  { peerId: 12, title: 'קבוצת משפחה', username: '', type: 'PrivateSupergroup', isForum: true, archived: true },
  { peerId: 13, title: 'Saved', username: '', type: 'Self', isForum: false, archived: false },
  { peerId: 14, title: 'Helper Bot', username: 'HelperBot', type: 'Bot', isForum: false, archived: false },
];

/** The export session answers `loggedIn`; chats answer CHATS (after `gate` when given). Returns the calls. */
async function fakeSession(page, { loggedIn = true, chats = CHATS, gate = null } = {}) {
  const calls = { session: 0, chats: 0, topics: [], start: [] };
  await page.route(/\/api\/export\/[^/]+\/session$/, (route) => { calls.session += 1; return okJson(route, { loggedIn }); });
  await page.route(/\/api\/export\/[^/]+\/chats$/, async (route) => {
    calls.chats += 1;
    if (gate) await gate;
    return okJson(route, chats);
  });
  return calls;
}

async function chooseAccount(page, label) {
  await page.getByRole('radio', { name: label, exact: true }).check();
  await expect(page.locator('.export-session h3')).toHaveText('סשן ייצוא');
}

async function loadChats(page) {
  await page.getByRole('button', { name: "טען צ'אטים" }).click();
  await expect(page.getByRole('button', { name: 'רענן' })).toBeVisible();
}

const chatCard = (page, title) => page.locator('.chat-card', { has: page.locator('.chat-title', { hasText: title }) });

// ---------------------------------------------------------------- setup

test('setup: intro, account radios with (לא מחובר), none chosen until a click shows the session card', async ({ page }) => {
  const name = `exp-${uid()}`;
  const id = await addAccount(page.request, name);
  const blankId = await addAccount(page.request, '', '+10000000077');
  await open(page);
  await expect(page.locator('.export-intro h2')).toHaveText("ייצוא היסטוריית צ'אט");
  await expect(page.getByText('חשבון:', { exact: true })).toBeVisible();
  const radio = page.getByRole('radio', { name: `${name} (לא מחובר)`, exact: true });
  await expect(radio).toBeVisible();
  // A blank name shows the phone.
  await expect(page.getByRole('radio', { name: '+10000000077 (לא מחובר)', exact: true })).toBeVisible();
  for (const r of await page.getByRole('radio').all()) await expect(r).not.toBeChecked();
  await expect(page.locator('.export-session')).toBeHidden();

  await radio.check();
  const session = page.locator('.export-session');
  await expect(session.locator('h3')).toHaveText('סשן ייצוא');
  // The dummy's export session is not logged in.
  await expect(session.getByText(
    `הייצוא משתמש בסשן נפרד, כמו טלגרם דסקטופ. ההתחברות מאושרת אוטומטית דרך החשבון ${name} שמחובר ב-TG Backup (QR).`,
    { exact: true },
  )).toBeVisible({ timeout: 30_000 });
  await expect(session.getByRole('button', { name: 'התחבר לסשן הייצוא' })).toBeEnabled();
  // No chat picker while not logged in.
  await expect(page.getByRole('button', { name: "טען צ'אטים" })).toHaveCount(0);
  await page.request.delete(`/api/accounts/${id}`);
  await page.request.delete(`/api/accounts/${blankId}`);
});

test('session card: בודק… then ✅ מחובר in green, and the availableAt warning in red', async ({ page }) => {
  await page.clock.setFixedTime(new Date('2026-11-05T10:00:00Z'));
  const name = `exp-${uid()}`;
  const id = await addAccount(page.request, name);
  // availableAt 5 h 30 m from the fixed clock.
  const availableAt = Date.parse('2026-11-05T15:30:00Z') / 1000;
  const real = await state(page.request);
  const snapshot = { ...real, log: [], exportPrefs: { ...real.exportPrefs, availableAt: { [id]: availableAt } } };
  await page.route('**/api/events', (route) => route.fulfill({
    status: 200,
    headers: { 'Content-Type': 'text/event-stream' },
    body: `retry: 600000\nevent: snapshot\ndata: ${JSON.stringify(snapshot)}\n\n`,
  }));
  let release;
  const gate = new Promise((r) => { release = r; });
  await page.route(/\/api\/export\/[^/]+\/session$/, async (route) => { await gate; return okJson(route, { loggedIn: true }); });
  await open(page);
  await page.getByRole('radio', { name: `${name} (לא מחובר)` }).check();
  const session = page.locator('.export-session');
  await expect(session.getByText('בודק…', { exact: true })).toBeVisible();
  release();
  const ok = session.getByText('✅ מחובר (סשן נפרד מ-TG Backup)', { exact: true });
  await expect(ok).toBeVisible();
  await expect(ok).toHaveCSS('color', 'rgb(127, 209, 174)');
  const warn = session.getByText(
    '⏳ מטעמי אבטחה טלגרם יאפשר להתחיל את הייצוא רק בעוד 5 שעות (ב-05.11.2026 17:30). נשלחה הודעה לכל המכשירים שלך. חזור אז ונסה שוב מאותו מכשיר.',
    { exact: true },
  );
  await expect(warn).toBeVisible();
  await expect(warn).toHaveCSS('color', 'rgb(240, 113, 120)');
  await page.request.delete(`/api/accounts/${id}`);
});

test('session login: מתחבר… while running, the failure toast, then a re-check', async ({ page }) => {
  const name = `exp-${uid()}`;
  const id = await addAccount(page.request, name);
  let loggedIn = false;
  let asked = 0;
  await page.route(/\/api\/export\/[^/]+\/session$/, (route) => { asked += 1; return okJson(route, { loggedIn }); });
  let release;
  const gate = new Promise((r) => { release = r; });
  let logins = 0;
  await page.route(`**/api/export/${id}/login`, async (route) => {
    logins += 1;
    await gate;
    if (logins === 1) return errJson(route, 400, 'ההתחברות נכשלה: ההתחברות בוטלה');
    loggedIn = true;
    return okJson(route);
  });
  await open(page);
  await chooseAccount(page, `${name} (לא מחובר)`);
  const button = page.getByRole('button', { name: 'התחבר לסשן הייצוא' });
  await button.click();
  const busy = page.getByRole('button', { name: 'מתחבר…' });
  await expect(busy).toBeDisabled();
  release();
  await expect.poll(() => toastText(page)).toBe('ההתחברות נכשלה: ההתחברות בוטלה');
  await expect(button).toBeEnabled();
  await expect.poll(() => asked).toBe(2);
  await button.click();
  await expect(page.getByText('✅ מחובר (סשן נפרד מ-TG Backup)', { exact: true })).toBeVisible();
  // Logged in: the chat picker shows.
  await expect(page.getByRole('button', { name: "טען צ'אטים" })).toBeEnabled();
  await page.request.delete(`/api/accounts/${id}`);
});

// ---------------------------------------------------------------- chat picker

test('chat picker: loading text, cards with type labels, search, רענן, and the failure toast', async ({ page }) => {
  const name = `exp-${uid()}`;
  const id = await addAccount(page.request, name);
  let release;
  const gate = new Promise((r) => { release = r; });
  const calls = await fakeSession(page, { gate });
  await open(page);
  await chooseAccount(page, `${name} (לא מחובר)`);
  await page.getByRole('button', { name: "טען צ'אטים" }).click();
  await expect(page.getByText("טוען צ'אטים…", { exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: "טען צ'אטים" })).toBeDisabled();
  release();
  await expect(page.getByRole('button', { name: 'רענן' })).toBeEnabled();
  await expect(page.getByText("טוען צ'אטים…", { exact: true })).toHaveCount(0);

  await expect(page.locator('.chat-card')).toHaveCount(4);
  await expect(chatCard(page, 'Alpha News').locator('.chat-sub')).toHaveText('ערוץ ציבורי · @alphanews');
  await expect(chatCard(page, 'קבוצת משפחה').locator('.chat-sub')).toHaveText('קבוצה פרטית · פורום · בארכיון');
  await expect(chatCard(page, 'Saved').locator('.chat-sub')).toHaveText('הודעות שמורות');
  await expect(chatCard(page, 'Helper Bot').locator('.chat-sub')).toHaveText('בוט · @HelperBot');

  const search = page.getByLabel('חיפוש');
  await search.fill('HELPERB');
  await expect(page.locator('.chat-card')).toHaveCount(1);
  await expect(chatCard(page, 'Helper Bot')).toBeVisible();
  await search.fill('news');
  await expect(page.locator('.chat-card .chat-title')).toHaveText(['Alpha News']);
  await search.fill('   ');
  await expect(page.locator('.chat-card')).toHaveCount(4);

  // An event (here a prefs change) while typing keeps the search text and the focus.
  await search.fill('');
  await search.click();
  await search.pressSequentially('Alp');
  const before = (await state(page.request)).exportPrefs;
  await page.request.patch('/api/export/prefs', { data: { format: before.format === 'Json' ? 'Html' : 'Json' } });
  await expect.poll(async () => (await state(page.request)).exportPrefs.format).not.toBe(before.format);
  await page.waitForTimeout(300);
  await expect(search).toHaveValue('Alp');
  expect(await search.evaluate((el) => document.activeElement === el)).toBe(true);
  await search.pressSequentially('ha');
  await expect(search).toHaveValue('Alpha');
  await expect(page.locator('.chat-card')).toHaveCount(1);
  await page.request.patch('/api/export/prefs', { data: { format: before.format } });

  await page.unroute(/\/api\/export\/[^/]+\/chats$/);
  await page.route(/\/api\/export\/[^/]+\/chats$/, (route) => errJson(route, 400, "טעינת הצ'אטים נכשלה: FLOOD_WAIT_5"));
  await page.getByRole('button', { name: 'רענן' }).click();
  await expect.poll(() => toastText(page)).toBe("טעינת הצ'אטים נכשלה: FLOOD_WAIT_5");
  // The loaded list stays.
  await expect(page.locator('.chat-card')).toHaveCount(1);
  expect(calls.chats).toBe(1);
  await page.request.delete(`/api/accounts/${id}`);
});

test('chat picker: live `טוען צ\'אטים… N` from chatsProgress, also after a reconnect mid-load', async ({ page }) => {
  const name = `exp-${uid()}`;
  const id = await addAccount(page.request, name);
  const real = await state(page.request);
  await fakeSession(page);
  // First stream: a snapshot that says the load is at 40. Second stream (held): progress 120, then done.
  let release;
  const gate = new Promise((r) => { release = r; });
  let streams = 0;
  await page.route('**/api/events', async (route) => {
    streams += 1;
    if (streams === 1) {
      return route.fulfill({
        status: 200,
        headers: { 'Content-Type': 'text/event-stream' },
        body: `retry: 300\nevent: snapshot\ndata: ${JSON.stringify({ ...real, log: [], chatsLoading: { [id]: 40 } })}\n\n`,
      });
    }
    if (streams > 2) return;
    await gate;
    const p = (count, done) => `event: chatsProgress\ndata: ${JSON.stringify({ accountId: id, count, done })}\n\n`;
    return route.fulfill({
      status: 200,
      headers: { 'Content-Type': 'text/event-stream' },
      body: `retry: 600000\n${p(120, false)}`,
    });
  });
  await open(page);
  await chooseAccount(page, `${name} (לא מחובר)`);
  await expect(page.getByText("טוען צ'אטים… 40", { exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: "טען צ'אטים" })).toBeDisabled();
  release();
  await expect(page.getByText("טוען צ'אטים… 120", { exact: true })).toBeVisible();
  await page.request.delete(`/api/accounts/${id}`);
});

test('choosing a forum loads its topics; failure shows the toast and an empty list', async ({ page }) => {
  const name = `exp-${uid()}`;
  const id = await addAccount(page.request, name);
  await fakeSession(page);
  let release;
  const gate = new Promise((r) => { release = r; });
  const bodies = [];
  await page.route(`**/api/export/${id}/topics`, async (route) => {
    bodies.push(route.request().postDataJSON());
    if (bodies.length === 1) {
      await gate;
      return okJson(route, [{ id: 1, title: 'General' }, { id: 7, title: 'הודעות' }]);
    }
    return errJson(route, 400, 'טעינת הנושאים נכשלה: CHANNEL_INVALID');
  });
  await open(page);
  await chooseAccount(page, `${name} (לא מחובר)`);
  await loadChats(page);
  await chatCard(page, 'קבוצת משפחה').click();
  const options = page.locator('.export-options');
  await expect(options.locator('.options-title')).toHaveText('קבוצת משפחה');
  await expect(options.getByText('נושא (Topic export settings):', { exact: true })).toBeVisible();
  await expect(options.getByRole('radio', { name: "כל הצ'אט" })).toBeChecked();
  await expect(options.getByText('טוען נושאים…', { exact: true })).toBeVisible();
  // The chat list is gone while a chat is chosen.
  await expect(page.locator('.chat-card')).toHaveCount(0);
  release();
  await expect(options.getByRole('radio', { name: 'General' })).toBeVisible();
  await expect(options.getByText('טוען נושאים…', { exact: true })).toHaveCount(0);
  expect(bodies[0]).toEqual({ peerId: 12 });
  await options.getByRole('radio', { name: 'הודעות' }).check();
  await expect(options.getByRole('radio', { name: "כל הצ'אט" })).not.toBeChecked();

  // החלף צ'אט goes back to the list (search kept); another forum fails to load its topics.
  await options.getByRole('button', { name: "החלף צ'אט" }).click();
  await expect(page.locator('.chat-card')).toHaveCount(4);
  await chatCard(page, 'קבוצת משפחה').click();
  await expect.poll(() => toastText(page)).toBe('טעינת הנושאים נכשלה: CHANNEL_INVALID');
  await expect(options.getByText('טוען נושאים…', { exact: true })).toHaveCount(0);
  await expect(options.locator('.export-topics').getByRole('radio')).toHaveCount(1);
  await expect(options.getByRole('radio', { name: "כל הצ'אט" })).toBeChecked();

  // A chat that is not a forum has no topic section.
  await options.getByRole('button', { name: "החלף צ'אט" }).click();
  await chatCard(page, 'Alpha News').click();
  await expect(options.locator('.options-title')).toHaveText('Alpha News');
  await expect(options.getByText('נושא (Topic export settings):')).toHaveCount(0);
  expect(bodies.length).toBe(2);
  await page.request.delete(`/api/accounts/${id}`);
});

// ---------------------------------------------------------------- options

test('options: media checkboxes, size slider, format radios and the path are saved as prefs', async ({ page }) => {
  const name = `exp-${uid()}`;
  const id = await addAccount(page.request, name);
  const before = (await state(page.request)).exportPrefs;
  await page.request.patch('/api/export/prefs', { data: { mediaTypes: 1, sizeLimit: 8 * MiB, format: 'Html', path: '' } });
  await fakeSession(page);
  await open(page);
  await chooseAccount(page, `${name} (לא מחובר)`);
  await loadChats(page);
  await chatCard(page, 'Alpha News').click();
  const options = page.locator('.export-options');
  await expect(options.getByText('Media export settings', { exact: true })).toBeVisible();
  const labels = ['Photos (תמונות)', 'Videos (סרטונים)', 'Voice messages (הודעות קוליות)', 'Video messages (הודעות וידאו)',
    'Stickers (מדבקות)', 'GIFs', 'Files (קבצים)'];
  await expect(options.getByRole('checkbox')).toHaveCount(7);
  for (const [i, l] of labels.entries()) {
    const box = options.getByRole('checkbox', { name: l, exact: true });
    if (i === 0) await expect(box).toBeChecked();
    else await expect(box).not.toBeChecked();
  }
  const prefs = async () => (await state(page.request)).exportPrefs;
  await options.getByRole('checkbox', { name: 'GIFs' }).check();
  await expect.poll(async () => (await prefs()).mediaTypes).toBe(0x21);
  await options.getByRole('checkbox', { name: 'Files (קבצים)' }).check();
  await options.getByRole('checkbox', { name: 'Photos (תמונות)' }).uncheck();
  await expect.poll(async () => (await prefs()).mediaTypes).toBe(0x60);

  await expect(options.getByText('Size limit: 8 MB', { exact: true })).toBeVisible();
  const slider = options.getByRole('slider');
  await expect(slider).toHaveValue('7');
  await slider.fill('29');
  await expect(options.getByText('Size limit: 50 MB', { exact: true })).toBeVisible();
  await expect.poll(async () => (await prefs()).sizeLimit).toBe(50 * MiB);
  await slider.fill('99');
  await expect.poll(async () => (await prefs()).sizeLimit).toBe(4000 * MiB);
  await expect(options.getByText('Size limit: 4000 MB', { exact: true })).toBeVisible();

  await expect(options.getByText('Format', { exact: true })).toBeVisible();
  await expect(options.getByRole('radio', { name: 'Human-readable HTML' })).toBeChecked();
  await options.getByRole('radio', { name: 'Machine-readable JSON' }).check();
  await expect.poll(async () => (await prefs()).format).toBe('Json');
  await options.getByRole('radio', { name: 'Both' }).check();
  await expect.poll(async () => (await prefs()).format).toBe('HtmlAndJson');

  const path = options.getByLabel('Download path');
  await expect(path).toHaveAttribute('placeholder', before.defaultPath);
  await expect(path).toHaveValue('');
  await path.fill('/tmp/תיקייה של ייצוא');
  // Saved on Enter (and on blur), not on every key.
  expect((await prefs()).path).toBe('');
  await path.press('Enter');
  await expect.poll(async () => (await prefs()).path).toBe('/tmp/תיקייה של ייצוא');
  await path.fill('/tmp/x');
  await options.getByText('Media export settings').click();
  await expect.poll(async () => (await prefs()).path).toBe('/tmp/x');

  await page.request.patch('/api/export/prefs', {
    data: { mediaTypes: before.mediaTypes, sizeLimit: before.sizeLimit, format: before.format, path: before.path },
  });
  await page.request.delete(`/api/accounts/${id}`);
});

test('options: From/To with the local-midnight and 600 s rules, Reset, and Export sends the start body', async ({ page }) => {
  const name = `exp-${uid()}`;
  const id = await addAccount(page.request, name);
  await fakeSession(page);
  await page.route(`**/api/export/${id}/topics`, (route) => okJson(route, [{ id: 3, title: 'Topic three' }]));
  const starts = [];
  await page.route('**/api/export/start', (route) => {
    starts.push(route.request().postDataJSON());
    return starts.length === 1 ? errJson(route, 409, 'ייצוא כבר פועל') : okJson(route);
  });
  await open(page);
  await chooseAccount(page, `${name} (לא מחובר)`);
  await loadChats(page);
  await chatCard(page, 'קבוצת משפחה').click();
  const options = page.locator('.export-options');
  const from = options.locator('.date-row[data-which="from"]');
  const till = options.locator('.date-row[data-which="till"]');
  await expect(from.locator('.date-text')).toHaveText('From: the oldest message');
  await expect(till.locator('.date-text')).toHaveText('To: present');
  // שעה and Reset only once a date is set.
  await expect(from.getByLabel('שעה')).toBeHidden();
  await expect(from.getByRole('button', { name: 'Reset' })).toBeHidden();

  // Asia/Jerusalem is UTC+2 in November.
  const at = (iso) => Date.parse(iso) / 1000;
  await from.getByLabel('תאריך').fill('2026-11-05');
  await expect(from.locator('.date-text')).toHaveText('From: 5 נובמבר 2026, 00:00');
  await from.getByLabel('שעה').fill('10:30');
  await expect(from.locator('.date-text')).toHaveText('From: 5 נובמבר 2026, 10:30');
  // To on the same day: its midnight is not after From, so From + 10 minutes.
  await till.getByLabel('תאריך').fill('2026-11-05');
  await expect(till.locator('.date-text')).toHaveText('To: 5 נובמבר 2026, 10:40');
  // A To time before From: From + 10 minutes again.
  await till.getByLabel('שעה').fill('09:00');
  await expect(till.locator('.date-text')).toHaveText('To: 5 נובמבר 2026, 10:40');
  await till.getByLabel('שעה').fill('23:15');
  await expect(till.locator('.date-text')).toHaveText('To: 5 נובמבר 2026, 23:15');
  // A From time at or after To: To - 10 minutes.
  await from.getByLabel('שעה').fill('23:59');
  await expect(from.locator('.date-text')).toHaveText('From: 5 נובמבר 2026, 23:05');
  // The From date is not clamped (as in Android).
  await from.getByLabel('תאריך').fill('2026-11-07');
  await expect(from.locator('.date-text')).toHaveText('From: 7 נובמבר 2026, 00:00');
  await from.getByLabel('תאריך').fill('2026-11-01');

  await options.getByRole('radio', { name: 'Topic three' }).check();
  await options.getByRole('button', { name: 'Export', exact: true }).click();
  await expect.poll(() => toastText(page)).toBe('ייצוא כבר פועל');
  expect(starts[0]).toEqual({ accountId: id, peerId: 12, topicId: 3, from: at('2026-10-31T22:00:00Z'), till: at('2026-11-05T21:15:00Z') });

  await till.getByRole('button', { name: 'Reset' }).click();
  await expect(till.locator('.date-text')).toHaveText('To: present');
  await from.getByRole('button', { name: 'Reset' }).click();
  await expect(from.locator('.date-text')).toHaveText('From: the oldest message');
  await options.getByRole('radio', { name: "כל הצ'אט" }).check();
  await options.getByRole('button', { name: 'Export', exact: true }).click();
  await expect.poll(() => starts.length).toBe(2);
  expect(starts[1]).toEqual({ accountId: id, peerId: 12, from: 0, till: 0 });

  // Choosing another chat clears the dates.
  await from.getByLabel('תאריך').fill('2026-11-05');
  await options.getByRole('button', { name: "החלף צ'אט" }).click();
  await chatCard(page, 'Alpha News').click();
  await expect(options.locator('.date-row[data-which="from"] .date-text')).toHaveText('From: the oldest message');
  await page.request.delete(`/api/accounts/${id}`);
});

// ---------------------------------------------------------------- progress panel

const PROCESSING = {
  step: 'Dialogs', substepsPassed: 1, substepsNow: 1, substepsTotal: 2, entityType: 'Chat', entityName: 'Alpha News',
  entityIndex: 1, entityCount: 3, itemIndex: 5, itemCount: 10, bytesRandomId: 9, bytesName: 'video.mp4',
  bytesLoaded: 1572864, bytesCount: 3145728,
};
const running = (st) => ({ accountId: 'a1', title: 'Alpha News', state: st });

test.describe('progress panel', () => {
  test.afterEach(async ({ page }) => { await publish(page.request, null); });

  async function openPanel(page, st) {
    await open(page);
    await publish(page.request, running(st));
    await expect(page.locator('.export-panel')).toBeVisible();
    await expect(page.getByText(INTRO)).toHaveCount(0);
  }

  test('Idle shows Initializing...', async ({ page }) => {
    await openPanel(page, { type: 'Idle' });
    await expect(page.locator('.export-panel')).toHaveText('Initializing...');
    await publish(page.request, null);
    await expect(page.getByText(INTRO, { exact: true })).toBeVisible();
  });

  test('Processing: rows, help, Skip this file and Stop with its confirm', async ({ page }) => {
    const posts = [];
    await page.route(/\/api\/export\/(skip|stop)$/, (route) => { posts.push(route.request().url().split('/').at(-1)); return okJson(route); });
    await openPanel(page, { type: 'Processing', state: PROCESSING });
    const panel = page.locator('.export-panel');
    await expect(panel.locator('h2')).toHaveText('Exporting your data');
    await expect(panel.locator('.progress-label')).toHaveText(['Chats', 'Alpha News', 'video.mp4']);
    await expect(panel.locator('.progress-info')).toHaveText(['2 / 3', '5 / 10', '1.5 / 3.0 MB']);
    await expect(panel.locator('progress')).toHaveCount(3);
    const values = await panel.locator('progress').evaluateAll((els) => els.map((e) => e.value));
    expect(values[0]).toBeCloseTo(2 / 3, 5);
    expect(values.slice(1)).toEqual([0.5, 0.5]);
    await expect(panel.getByText('אפשר לסגור את המסך. הייצוא ממשיך ברקע עם התראה קבועה.', { exact: true })).toBeVisible();
    await panel.getByRole('button', { name: 'Skip this file' }).click();
    await expect.poll(() => posts).toEqual(['skip']);

    await panel.getByRole('button', { name: 'Stop' }).click();
    const dialog = page.getByRole('dialog');
    await expect(dialog.locator('p.text')).toHaveText("Are you sure you want to stop exporting your data?\n\nIf you do, you'll need to start over.");
    await dialog.getByRole('button', { name: 'ביטול' }).click();
    await expect(dialog).toBeHidden();
    expect(posts).toEqual(['skip']);
    await panel.getByRole('button', { name: 'Stop' }).click();
    await page.getByRole('dialog').getByRole('button', { name: 'Stop' }).click();
    await expect.poll(() => posts).toEqual(['skip', 'stop']);

    // A new sample updates the rows in place; with no file, no Skip.
    await publish(page.request, running({ type: 'Processing', state: { ...PROCESSING, itemIndex: 6, bytesCount: 0 } }));
    await expect(panel.locator('.progress-info')).toHaveText(['2 / 3', '6 / 10']);
    await expect(panel.getByRole('button', { name: 'Skip this file' })).toHaveCount(0);
    await expect(panel.getByRole('button', { name: 'Stop' })).toBeVisible();
  });

  test('Finished: totals, path, Show my data (error toast) and סגור', async ({ page }) => {
    const dismissed = [];
    page.on('request', (r) => { if (r.url().endsWith('/api/export/dismiss')) dismissed.push(r.method()); });
    await openPanel(page, { type: 'Finished', path: '/home/u/Downloads/TG Backup/ChatExport_2026-11-05', filesCount: 3, bytesCount: 1572864 });
    const panel = page.locator('.export-panel');
    const done = panel.getByText('Data export completed.', { exact: true });
    await expect(done).toBeVisible();
    await expect(done).toHaveCSS('color', 'rgb(127, 209, 174)');
    await expect(panel.getByText('Total files: 3', { exact: true })).toBeVisible();
    await expect(panel.getByText('Total size: 1.5 MB', { exact: true })).toBeVisible();
    await expect(panel.getByText('/home/u/Downloads/TG Backup/ChatExport_2026-11-05', { exact: true })).toBeVisible();
    // The engine has no finished export (the state is faked): the server says so.
    await panel.getByRole('button', { name: 'Show my data' }).click();
    await expect.poll(() => toastText(page)).toBe('אין ייצוא שהסתיים');
    await panel.getByRole('button', { name: 'סגור' }).click();
    await expect.poll(() => dismissed).toEqual(['POST']);
  });

  test('Show my data calls /api/export/open; its 400 is a toast', async ({ page }) => {
    let opened = 0;
    await page.route('**/api/export/open', (route) => { opened += 1; return errJson(route, 400, 'לא ניתן לפתוח במחשב הזה'); });
    await openPanel(page, { type: 'Finished', path: '/x', filesCount: 0, bytesCount: 0 });
    await expect(page.getByText('Total size: 0 B', { exact: true })).toBeVisible();
    await page.getByRole('button', { name: 'Show my data' }).click();
    await expect.poll(() => toastText(page)).toBe('לא ניתן לפתוח במחשב הזה');
    expect(opened).toBe(1);
  });

  test('ApiError: TAKEOUT_INVALID, TAKEOUT_INIT_DELAY_ and others, in red with סגור', async ({ page }) => {
    await page.clock.setFixedTime(new Date('2026-11-05T10:00:00Z'));
    await openPanel(page, { type: 'ApiError', code: 400, errorType: 'TAKEOUT_INVALID' });
    const panel = page.locator('.export-panel');
    const text = panel.locator('.export-error');
    await expect(text).toHaveText('Sorry, you started a new data export, so this data export has been canceled.');
    await expect(text).toHaveCSS('color', 'rgb(240, 113, 120)');
    await expect(panel.getByRole('button', { name: 'סגור' })).toBeVisible();

    await publish(page.request, running({ type: 'ApiError', code: 420, errorType: 'TAKEOUT_INIT_DELAY_7200' }));
    await expect(text).toHaveText(
      'For security reasons, you will be able to begin downloading your data in 2 hours. ' +
      "We have notified all your devices about the export request to make sure it's authorized and give you time to react if it's not.\n\n" +
      'Please come back on 5 נובמבר 2026 at 14:00 and repeat the request using the same device.',
    );
    await publish(page.request, running({ type: 'ApiError', code: 420, errorType: 'TAKEOUT_INIT_DELAY_1800' }));
    await expect(text).toContainText('in less than an hour.');
    await expect(text).toContainText('Please come back on 5 נובמבר 2026 at 12:30 and');

    await publish(page.request, running({ type: 'ApiError', code: 500, errorType: 'INTERNAL_SERVER_ERROR' }));
    await expect(text).toHaveText('API Error happened :(\n500: INTERNAL_SERVER_ERROR');
  });

  test('OutputError and Cancelled', async ({ page }) => {
    const dismissed = [];
    page.on('request', (r) => { if (r.url().endsWith('/api/export/dismiss')) dismissed.push(r.method()); });
    await openPanel(page, { type: 'OutputError', path: '/root/no access' });
    const panel = page.locator('.export-panel');
    await expect(panel.locator('.export-error')).toHaveText('Could not write to /root/no access');
    await expect(panel.locator('.export-error')).toHaveCSS('color', 'rgb(240, 113, 120)');
    await publish(page.request, running({ type: 'Cancelled' }));
    await expect(panel.getByText('הייצוא הופסק.', { exact: true })).toBeVisible();
    await panel.getByRole('button', { name: 'סגור' }).click();
    await expect.poll(() => dismissed).toEqual(['POST']);
  });

  test('after the panel the setup starts over (no account chosen)', async ({ page }) => {
    const name = `exp-${uid()}`;
    const id = await addAccount(page.request, name);
    await fakeSession(page);
    await open(page);
    await chooseAccount(page, `${name} (לא מחובר)`);
    await publish(page.request, running({ type: 'Cancelled' }));
    await expect(page.locator('.export-panel')).toBeVisible();
    await publish(page.request, null);
    await expect(page.getByText(INTRO, { exact: true })).toBeVisible();
    await expect(page.locator('.export-session')).toBeHidden();
    await page.request.delete(`/api/accounts/${id}`);
  });
});
