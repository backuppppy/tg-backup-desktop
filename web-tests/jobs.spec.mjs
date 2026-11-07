// Jobs screen (docs/ui-inventory.md §2a). Every test makes its own dummy accounts and jobs with unique names, and
// stops what it started: the dev server is shared by all tests and both viewports.
import { test, expect } from '@playwright/test';

const uid = () => `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 6)}`;
const MODE_ORDERED = '📑 מסודר — שומר על הסדר של המקור';
const MODE_FAST = '⚡ מהיר — כל חשבון מטפל בקטע משלו (סדר מבולגן)';

async function state(request) {
  return (await request.get('/api/state')).json();
}

/** A dummy account (it never logs in). Returns its id. */
async function addAccount(request, name) {
  const res = await request.post('/api/accounts', {
    data: { name, apiId: 1, apiHash: '0'.repeat(32), phone: '+10000000000' },
  });
  expect(res.ok()).toBeTruthy();
  return (await state(request)).accounts.find((a) => a.name === name).id;
}

/** A job through the API. Returns its id. */
async function addJob(request, job) {
  const res = await request.post('/api/jobs', { data: { source: '-1001000000000', target: '-1002000000000', ...job } });
  expect(res.ok(), await res.text()).toBeTruthy();
  return (await state(request)).jobs.find((j) => j.name === job.name).id;
}

async function open(page) {
  await page.goto('/#jobs');
  await expect(page.locator('#title')).toContainText('TG Backup  v');
  await expect(page.locator('[data-screen="jobs"] .jobs-header')).toBeVisible();
}

const card = (page, name) => page.locator('.job-card', { has: page.locator('.job-title', { hasText: name }) });

test('addJobFlow: שמור stays disabled until source, target and an account are set; the card shows the job', async ({ page }) => {
  const acc = `acc-${uid()}`;
  const name = `job-${uid()}`;
  await addAccount(page.request, acc);
  await open(page);
  await page.getByRole('button', { name: '➕ הוסף משימה' }).click();
  const dialog = page.getByRole('dialog', { name: 'משימה חדשה' });
  await expect(dialog).toBeVisible();
  const save = dialog.getByRole('button', { name: 'שמור' });
  await expect(save).toBeDisabled();
  await dialog.getByLabel('שם המשימה').fill(name);
  await dialog.getByLabel('מקור: מזהה (עם או בלי ‎-100) / @username / קישור').fill('  @src_chan  ');
  await expect(dialog.getByLabel('מקור: מזהה (עם או בלי ‎-100) / @username / קישור')).toHaveValue('@src_chan');
  await expect(save).toBeDisabled();
  await dialog.getByLabel('יעד: מזהה (עם או בלי ‎-100) / @username / קישור').fill('@dst_chan');
  await expect(save).toBeDisabled();
  // The defaults of a new job.
  await expect(dialog.getByRole('radio', { name: 'וידאו + קבצים' })).toBeChecked();
  await expect(dialog.getByLabel('תקרת הודעות לדקה לחשבון (0 = ללא)')).toHaveValue('25');
  await expect(dialog.getByLabel("התחל מהודעה מס' (ריק = להמשיך מאיפה שעצר)")).toHaveValue('');
  await expect(dialog.getByRole('switch', { name: 'הסר קישורים ותיוגים מהכיתוב' })).toBeChecked();
  await expect(dialog.getByRole('switch', { name: 'דלג על הודעות בערבית' })).not.toBeChecked();
  await expect(dialog.getByRole('switch', { name: '📡 העתקה בלי הורדה (דרך סשן הייצוא)' })).not.toBeChecked();
  await dialog.getByRole('checkbox', { name: acc, exact: true }).check();
  await expect(save).toBeEnabled();
  // An empty per-minute limit saves as 25.
  await dialog.getByLabel('תקרת הודעות לדקה לחשבון (0 = ללא)').fill('');
  await save.click();
  await expect(dialog).toBeHidden();
  const c = card(page, name);
  await expect(c.locator('.job-title')).toHaveText(`⚪ ${name}`);
  await expect(c).toContainText('@src_chan  ←  @dst_chan');
  await expect(c).toContainText(`וידאו + קבצים · מסודר · חשבונות: ${acc}`);
  await expect(c).toContainText('הועתקו בהרצה זו: 0');
  const saved = (await state(page.request)).jobs.find((j) => j.name === name);
  expect(saved.perMinuteLimit).toBe(25);
  expect(saved.stripLinks).toBe(true);
  expect(saved.skipArabic).toBe(false);
});

test('modeHiddenWithOneAccount: the mode radios show only with two accounts checked', async ({ page }) => {
  const a1 = `acc-${uid()}`;
  const a2 = `acc-${uid()}`;
  await addAccount(page.request, a1);
  await addAccount(page.request, a2);
  await open(page);
  await page.getByRole('button', { name: '➕ הוסף משימה' }).click();
  const dialog = page.getByRole('dialog', { name: 'משימה חדשה' });
  await expect(dialog.getByText('מצב עבודה', { exact: true })).toBeHidden();
  await dialog.getByRole('checkbox', { name: a1, exact: true }).check();
  await expect(dialog.getByText('מצב עבודה', { exact: true })).toBeHidden();
  await expect(dialog.getByRole('radio', { name: MODE_ORDERED })).toBeHidden();
  await dialog.getByRole('checkbox', { name: a2, exact: true }).check();
  await expect(dialog.getByText('מצב עבודה', { exact: true })).toBeVisible();
  await expect(dialog.getByRole('radio', { name: MODE_ORDERED })).toBeChecked();
  await expect(dialog.getByRole('radio', { name: MODE_FAST })).toBeVisible();
  await dialog.getByRole('button', { name: 'ביטול' }).click();
  await expect(dialog).toBeHidden();
});

test('an account of another job is disabled in the dialog', async ({ page }) => {
  const acc = `acc-${uid()}`;
  const accId = await addAccount(page.request, acc);
  await addJob(page.request, { name: `job-${uid()}`, accountIds: [accId] });
  await open(page);
  await page.getByRole('button', { name: '➕ הוסף משימה' }).click();
  const dialog = page.getByRole('dialog', { name: 'משימה חדשה' });
  const box = dialog.getByRole('checkbox', { name: `${acc} (משויך למשימה אחרת)` });
  await expect(box).toBeDisabled();
  await dialog.getByRole('button', { name: 'ביטול' }).click();
});

test('editing a job opens עריכת משימה with its values and saves in place', async ({ page }) => {
  const acc = `acc-${uid()}`;
  const name = `job-${uid()}`;
  const accId = await addAccount(page.request, acc);
  const id = await addJob(page.request, { name, accountIds: [accId], mediaType: 'PHOTO', perMinuteLimit: 7, startFromId: 42 });
  await open(page);
  await card(page, name).getByRole('button', { name: 'ערוך' }).click();
  const dialog = page.getByRole('dialog', { name: 'עריכת משימה' });
  await expect(dialog.getByLabel('שם המשימה')).toHaveValue(name);
  await expect(dialog.getByRole('radio', { name: 'תמונות בלבד' })).toBeChecked();
  await expect(dialog.getByLabel('תקרת הודעות לדקה לחשבון (0 = ללא)')).toHaveValue('7');
  await expect(dialog.getByLabel("התחל מהודעה מס' (ריק = להמשיך מאיפה שעצר)")).toHaveValue('42');
  // Its own account is not "taken".
  await expect(dialog.getByRole('checkbox', { name: acc, exact: true })).toBeChecked();
  await dialog.getByRole('radio', { name: 'טקסט בלבד' }).check();
  // Digits only.
  await dialog.getByLabel('תקרת הודעות לדקה לחשבון (0 = ללא)').fill('1a2');
  await expect(dialog.getByLabel('תקרת הודעות לדקה לחשבון (0 = ללא)')).toHaveValue('12');
  await dialog.getByRole('button', { name: 'שמור' }).click();
  await expect(dialog).toBeHidden();
  await expect(card(page, name)).toContainText(`טקסט בלבד · מסודר · חשבונות: ${acc}`);
  const saved = (await state(page.request)).jobs.find((j) => j.id === id);
  expect(saved.perMinuteLimit).toBe(12);
});

test('startShowsRunningCard: ▶ הפעל becomes ⏹ עצור on a surfaceVariant card; ערוך is disabled', async ({ page }) => {
  const acc = `acc-${uid()}`;
  const name = `job-${uid()}`;
  const accId = await addAccount(page.request, acc);
  const id = await addJob(page.request, { name, accountIds: [accId] });
  try {
    await open(page);
    const c = card(page, name);
    await expect(c).toHaveCSS('background-color', 'rgb(22, 33, 45)');
    await c.getByRole('button', { name: '▶ הפעל' }).click();
    await expect(c.getByRole('button', { name: '⏹ עצור' })).toBeVisible();
    await expect(c.getByRole('button', { name: '⏸ השהה' })).toBeVisible();
    await expect(c.getByRole('button', { name: '▶ הפעל' })).toHaveCount(0);
    await expect(c).toHaveCSS('background-color', 'rgb(30, 44, 58)');
    await expect(c.getByRole('button', { name: 'ערוך' })).toBeDisabled();
    await expect(c.getByRole('button', { name: 'אפס התקדמות' })).toBeDisabled();
    await expect(c.getByRole('button', { name: 'מחק' })).toBeDisabled();
    await expect(c.locator('.job-title')).toHaveText(`🟢 ${name}`);
    await expect(page.locator('.jobs-summary')).toHaveText(/^🟢 \d+ משימות פעילות · הועתקו בהרצה זו: \d+$/);
    await expect(page.getByRole('button', { name: '⏹ עצור הכל' })).toBeEnabled();
    await expect(page.getByRole('button', { name: '⏸ השהה הכל' })).toBeEnabled();
    await c.getByRole('button', { name: '⏸ השהה' }).click();
    await expect(c.getByRole('button', { name: '▶ המשך' })).toBeVisible();
    await expect(c.locator('.job-title')).toHaveText(`⏸ ${name}`);
    await c.getByRole('button', { name: '⏹ עצור' }).click();
    await expect(c.getByRole('button', { name: '▶ הפעל' })).toBeVisible();
    await expect(c).toHaveCSS('background-color', 'rgb(22, 33, 45)');
    await expect(c.getByRole('button', { name: 'ערוך' })).toBeEnabled();
    await expect(c.locator('.job-title')).toHaveText(`⚪ ${name}`);
  } finally {
    await page.request.post(`/api/jobs/${id}/stop`);
  }
});

test('deleteNeedsConfirm: ביטול keeps the card, אישור removes it', async ({ page }) => {
  const acc = `acc-${uid()}`;
  const name = `job-${uid()}`;
  const accId = await addAccount(page.request, acc);
  await addJob(page.request, { name, accountIds: [accId] });
  await open(page);
  const c = card(page, name);
  await c.getByRole('button', { name: 'מחק' }).click();
  const dialog = page.getByRole('dialog', { name: `למחוק את המשימה ${name}?` });
  await expect(dialog).toBeVisible();
  await expect(dialog).toContainText('ההתקדמות השמורה תימחק.');
  await dialog.getByRole('button', { name: 'ביטול' }).click();
  await expect(dialog).toBeHidden();
  await expect(c).toBeVisible();
  await c.getByRole('button', { name: 'מחק' }).click();
  await page.getByRole('dialog', { name: `למחוק את המשימה ${name}?` }).getByRole('button', { name: 'אישור' }).click();
  await expect(c).toHaveCount(0);
});

test('the reset dialog has the Android title and text, and אישור calls reset', async ({ page }) => {
  const acc = `acc-${uid()}`;
  const name = `job-${uid()}`;
  const accId = await addAccount(page.request, acc);
  const id = await addJob(page.request, { name, accountIds: [accId] });
  const calls = [];
  await page.route(`**/api/jobs/${id}/reset`, (route) => {
    calls.push(route.request().method());
    return route.fulfill({ status: 200, contentType: 'application/json', body: '{"ok":true}' });
  });
  await open(page);
  await card(page, name).getByRole('button', { name: 'אפס התקדמות' }).click();
  const dialog = page.getByRole('dialog', { name: 'לאפס התקדמות?' });
  await expect(dialog).toContainText(`המשימה ${name} תתחיל מההודעה הראשונה בערוץ (ייתכנו כפילויות ביעד).`);
  await dialog.getByRole('button', { name: 'אישור' }).click();
  await expect.poll(() => calls).toEqual(['POST']);
});

test('startErrorToast: a refused start shows the server error as a toast', async ({ page }) => {
  const acc = `acc-${uid()}`;
  const name = `job-${uid()}`;
  const accId = await addAccount(page.request, acc);
  const id = await addJob(page.request, { name, accountIds: [accId] });
  const msg = `החשבון ${acc} כבר עובד במשימה אחרת`;
  await page.route(`**/api/jobs/${id}/start`, (route) => route.fulfill({
    status: 409, contentType: 'application/json', body: JSON.stringify({ error: msg }),
  }));
  await open(page);
  await card(page, name).getByRole('button', { name: '▶ הפעל' }).click();
  await expect(page.locator('#toast')).toHaveText(msg);
  await expect(card(page, name).getByRole('button', { name: '▶ הפעל' })).toBeVisible();
});

test('▶ הפעל הכל shows the start errors as one toast joined by new lines', async ({ page }) => {
  await page.route('**/api/jobs/all/start', (route) => route.fulfill({
    status: 200, contentType: 'application/json', body: JSON.stringify({ errors: ['א: חסר מקור או יעד', 'ב: המשימה כבר פועלת'] }),
  }));
  await open(page);
  await page.getByRole('button', { name: '▶ הפעל הכל' }).click();
  await expect.poll(() => page.locator('#toast').evaluate((el) => el.textContent)).toBe('א: חסר מקור או יעד\nב: המשימה כבר פועלת');
});

/** Serves `patch` merged over the real snapshot as the whole event stream. */
async function fakeStream(page, patch) {
  const real = await state(page.request);
  const snapshot = { ...real, ...patch };
  await page.route('**/api/events', (route) => route.fulfill({
    status: 200,
    headers: { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache' },
    body: `retry: 600000\nevent: snapshot\ndata: ${JSON.stringify(snapshot)}\n\n`,
  }));
}

const status = (over) => ({
  running: false, paused: false, phase: '', lastId: 0, sourceChatId: 0, sourceTitle: '', isProtected: false,
  copied: 0, failed: 0, targetChatId: 0, lastTargetId: 0, lastLink: null, lastTargetLink: null, ...over,
});
const job = (over) => ({
  id: 'x', name: '', source: 's', target: 't', mediaType: 'VIDEO_DOC', mode: 'ORDERED', accountIds: [],
  startFromId: 0, stripLinks: true, skipArabic: false, perMinuteLimit: 25, viaTakeout: false, ...over,
});

test('header, warning and card lines from a faked state', async ({ page }) => {
  await fakeStream(page, {
    accounts: [],
    jobs: [
      job({ id: 'f1', name: 'ראשונה', mode: 'FAST', viaTakeout: true, mediaType: 'ALL' }),
      job({ id: 'f2', name: '', mediaType: 'PHOTO' }),
      job({ id: 'f3', name: 'שלישית' }),
    ],
    statuses: {
      f1: status({ running: true, paused: true, phase: 'מעתיק', copied: 3, failed: 2, lastId: 77,
        lastLink: 'https://t.me/c/100/77', lastTargetLink: 'https://t.me/c/200/5', sourceTitle: 'ערוץ מקור' }),
      f2: status({ running: true, phase: 'ממתין', copied: 4, isProtected: true }),
      f3: status({ lastId: 9 }),
    },
  });
  await page.goto('/#jobs');
  await expect(page.locator('.jobs-summary')).toHaveText('🟢 2 משימות פעילות · הועתקו בהרצה זו: 7');
  await expect(page.getByText("קודם הוסף חשבון בלשונית 'חשבונות'.")).toBeVisible();
  await expect(page.getByRole('button', { name: '▶ הפעל הכל' })).toBeEnabled();
  await expect(page.getByRole('button', { name: '⏸ השהה הכל' })).toBeEnabled();
  await expect(page.getByRole('button', { name: '▶ המשך הכל' })).toBeEnabled();
  await expect(page.getByRole('button', { name: '⏹ עצור הכל' })).toBeEnabled();

  const c1 = card(page, 'ראשונה');
  await expect(c1.locator('.job-title')).toHaveText('⏸ ראשונה');
  await expect(c1).toContainText('ערוץ מקור  ←  t');
  await expect(c1).toContainText('הכל · מהיר · 📡 בלי הורדה · חשבונות: —');
  await expect(c1).toContainText('📡 קריאה דרך סשן הייצוא ושליחה בלי הורדה');
  await expect(c1).toContainText('הועתקו בהרצה זו: 3 · נכשלו: 2');
  const phase = c1.getByRole('link', { name: 'מעתיק' });
  await expect(phase).toHaveAttribute('href', 'https://t.me/c/200/5');
  await expect(phase).toHaveAttribute('target', '_blank');
  await expect(phase).toHaveAttribute('rel', 'noopener');
  const last = c1.getByRole('link', { name: 'הודעה אחרונה שטופלה: 77' });
  await expect(last).toHaveAttribute('href', 'https://t.me/c/100/77');
  await expect(last).toHaveAttribute('target', '_blank');
  await expect(c1.getByRole('button', { name: '▶ המשך' })).toBeVisible();

  const c2 = card(page, 'משימה f2');
  await expect(c2.locator('.job-title')).toHaveText('🟢 משימה f2');
  await expect(c2).toContainText('🔒 ערוץ מוגן — הורדה והעלאה מחדש');
  await expect(c2.getByText('ממתין')).toBeVisible();
  await expect(c2.getByRole('link', { name: 'ממתין' })).toHaveCount(0);
  await expect(c2.getByRole('button', { name: '⏸ השהה' })).toBeVisible();

  const c3 = card(page, 'שלישית');
  await expect(c3.getByText('הודעה אחרונה שטופלה: 9')).toBeVisible();
  await expect(c3.getByRole('link')).toHaveCount(0);
  await expect(c3).not.toContainText('נכשלו');
});

test('with nothing running, the all-jobs buttons but ▶ הפעל הכל are disabled', async ({ page }) => {
  await fakeStream(page, { jobs: [job({ id: 'g1', name: 'עצורה' })], statuses: {} });
  await page.goto('/#jobs');
  await expect(page.locator('.jobs-summary')).toHaveText('⚪ עצור · הועתקו בהרצה זו: 0');
  await expect(page.getByRole('button', { name: '▶ הפעל הכל' })).toBeEnabled();
  await expect(page.getByRole('button', { name: '⏸ השהה הכל' })).toBeDisabled();
  await expect(page.getByRole('button', { name: '▶ המשך הכל' })).toBeDisabled();
  await expect(page.getByRole('button', { name: '⏹ עצור הכל' })).toBeDisabled();
});

test('the dialog with no accounts shows the warning, and via-takeout shows its help text', async ({ page }) => {
  await fakeStream(page, { accounts: [], jobs: [] });
  await page.goto('/#jobs');
  await page.getByRole('button', { name: '➕ הוסף משימה' }).click();
  const dialog = page.getByRole('dialog', { name: 'משימה חדשה' });
  await expect(dialog.getByText("אין חשבונות — הוסף בלשונית 'חשבונות'")).toBeVisible();
  const help = 'עובד גם בערוצים מוגנים וחסומים. בפעם הראשונה טלגרם עשוי לבקש לאשר את הייצוא בהודעת שירות.';
  await expect(dialog.getByText(help)).toBeHidden();
  await dialog.getByRole('switch', { name: '📡 העתקה בלי הורדה (דרך סשן הייצוא)' }).click();
  await expect(dialog.getByText(help)).toBeVisible();
  await expect(dialog.getByText('סוג תוכן', { exact: true })).toBeVisible();
  for (const t of ['וידאו + קבצים', 'תמונות בלבד', 'כל המדיה (ללא מדבקות)', 'טקסט בלבד', 'הכל']) {
    await expect(dialog.getByRole('radio', { name: t, exact: true })).toBeVisible();
  }
  await expect(dialog.getByText('חשבונות (חשבון אחד — למשימה אחת בלבד)')).toBeVisible();
});

async function sourceRightOfTarget(c) {
  const src = await c.locator('.job-source').boundingBox();
  const dst = await c.locator('.job-target').boundingBox();
  expect(src.x).toBeGreaterThan(dst.x);
}

test('the source sits right of the target, with a Latin source and with a Hebrew source title', async ({ page }) => {
  await fakeStream(page, {
    jobs: [
      job({ id: 'b1', name: 'לטינית', source: '@src_x', target: '@dst_y' }),
      job({ id: 'b2', name: 'עברית', source: '@src_z', target: 'https://t.me/c/200/1' }),
    ],
    statuses: { b2: status({ sourceTitle: 'ערוץ המקור' }) },
  });
  await page.goto('/#jobs');
  const latin = card(page, 'לטינית');
  await expect(latin).toContainText('@src_x  ←  @dst_y');
  await expect(latin.locator('.job-source')).toHaveText('@src_x');
  await sourceRightOfTarget(latin);
  const hebrew = card(page, 'עברית');
  await expect(hebrew.locator('.job-source')).toHaveText('ערוץ המקור');
  await sourceRightOfTarget(hebrew);
});

test('a link that is not http(s) is shown as plain text', async ({ page }) => {
  await fakeStream(page, {
    jobs: [job({ id: 'j1', name: 'סכמה' })],
    statuses: { j1: status({ running: true, phase: 'שלב', lastId: 5, lastLink: 'javascript:alert(1)', lastTargetLink: 'JavaScript:alert(2)' }) },
  });
  await page.goto('/#jobs');
  const c = card(page, 'סכמה');
  await expect(c.getByText('הודעה אחרונה שטופלה: 5')).toBeVisible();
  await expect(c.getByText('שלב')).toBeVisible();
  await expect(page.locator('a[href^="javascript" i]')).toHaveCount(0);
  await expect(c.getByRole('link')).toHaveCount(0);
});

test('the keyboard focus on a card button survives the list being drawn again', async ({ page }) => {
  const acc = `acc-${uid()}`;
  const name = `job-${uid()}`;
  const accId = await addAccount(page.request, acc);
  const id = await addJob(page.request, { name, accountIds: [accId] });
  let other = null;
  try {
    await open(page);
    const edit = card(page, name).getByRole('button', { name: 'ערוך' });
    await edit.focus();
    await expect(edit).toBeFocused();
    // Any change of the state draws the list again: here another account.
    other = await addAccount(page.request, `acc-${uid()}`);
    await expect.poll(() => page.evaluate((jobId) => {
      const el = document.activeElement;
      return el?.closest('[data-job-id]')?.dataset.jobId === jobId ? el.textContent : null;
    }, id)).toBe('ערוך');
    // It is the button in the page now, not a detached one.
    expect(await page.evaluate(() => document.activeElement.isConnected)).toBe(true);
  } finally {
    await page.request.delete(`/api/jobs/${id}`);
    await page.request.delete(`/api/accounts/${accId}`);
    if (other) await page.request.delete(`/api/accounts/${other}`);
  }
});

test('the focus follows a button that changed its text (⏸ השהה ↔ ▶ המשך), not one that became disabled', async ({ page }) => {
  const acc = `acc-${uid()}`;
  const name = `job-${uid()}`;
  const accId = await addAccount(page.request, acc);
  const id = await addJob(page.request, { name, accountIds: [accId] });
  const focused = () => page.evaluate((jobId) => {
    const el = document.activeElement;
    return el?.closest('[data-job-id]')?.dataset.jobId === jobId ? el.textContent : null;
  }, id);
  try {
    await open(page);
    const c = card(page, name);
    // ערוך becomes disabled when the job starts: the focus does not move to ⏸ השהה in its place.
    const edit = c.getByRole('button', { name: 'ערוך' });
    await edit.focus();
    await expect(edit).toBeFocused();
    await page.request.post(`/api/jobs/${id}/start`);
    await expect(edit).toBeDisabled();
    await expect(c.getByRole('button', { name: '⏸ השהה' })).toBeVisible();
    expect(await focused()).toBeNull();
    // The same button with its other text keeps the focus, both ways.
    await c.getByRole('button', { name: '⏸ השהה' }).click();
    await expect(c.getByRole('button', { name: '▶ המשך' })).toBeVisible();
    await expect.poll(focused).toBe('▶ המשך');
    await c.getByRole('button', { name: '▶ המשך' }).click();
    await expect(c.getByRole('button', { name: '⏸ השהה' })).toBeVisible();
    await expect.poll(focused).toBe('⏸ השהה');
    // ⏹ עצור becomes ▶ הפעל in its place.
    await c.getByRole('button', { name: '⏹ עצור' }).click();
    await expect(c.getByRole('button', { name: '▶ הפעל' })).toBeVisible();
    await expect.poll(focused).toBe('▶ הפעל');
  } finally {
    await page.request.post(`/api/jobs/${id}/stop`);
    await page.request.delete(`/api/jobs/${id}`);
    await page.request.delete(`/api/accounts/${accId}`);
  }
});

test('closing a dialog gives the focus to its opener drawn again while the dialog was open', async ({ page }) => {
  const acc = `acc-${uid()}`;
  const name = `job-${uid()}`;
  const accId = await addAccount(page.request, acc);
  const id = await addJob(page.request, { name, accountIds: [accId] });
  let other = null;
  try {
    await open(page);
    await card(page, name).getByRole('button', { name: 'ערוך' }).click();
    const dialog = page.getByRole('dialog');
    await expect(dialog).toBeVisible();
    await page.evaluate(() => { document.activeElement.closest('.dialog') && document.querySelectorAll('.job-card').forEach((c) => { c.dataset.old = '1'; }); });
    // Another account draws the list again behind the dialog: the button that opened it is gone from the page.
    other = await addAccount(page.request, `acc-${uid()}`);
    await expect(page.locator('.job-card[data-old]')).toHaveCount(0);
    await expect(card(page, name)).toBeVisible();
    await dialog.getByRole('button', { name: 'ביטול' }).click();
    await expect(dialog).toBeHidden();
    await expect.poll(() => page.evaluate((jobId) => {
      const el = document.activeElement;
      return el?.isConnected && el.closest('[data-job-id]')?.dataset.jobId === jobId ? el.textContent : null;
    }, id)).toBe('ערוך');
  } finally {
    await page.request.delete(`/api/jobs/${id}`);
    await page.request.delete(`/api/accounts/${accId}`);
    if (other) await page.request.delete(`/api/accounts/${other}`);
  }
});

test("a start message id beyond what a number keeps exactly is refused: שמור stays disabled", async ({ page }) => {
  const acc = `acc-${uid()}`;
  const accId = await addAccount(page.request, acc);
  try {
    await open(page);
    await page.getByRole('button', { name: '➕ הוסף משימה' }).click();
    const dialog = page.getByRole('dialog', { name: 'משימה חדשה' });
    await dialog.getByLabel('מקור: מזהה (עם או בלי ‎-100) / @username / קישור').fill('@a');
    await dialog.getByLabel('יעד: מזהה (עם או בלי ‎-100) / @username / קישור').fill('@b');
    await dialog.getByRole('checkbox', { name: acc, exact: true }).check();
    const save = dialog.getByRole('button', { name: 'שמור' });
    const start = dialog.getByLabel("התחל מהודעה מס' (ריק = להמשיך מאיפה שעצר)");
    await expect(save).toBeEnabled();
    await start.fill('9007199254740993');
    await expect(save).toBeDisabled();
    await start.fill('9007199254740991');
    await expect(save).toBeEnabled();
    await start.fill('');
    await expect(save).toBeEnabled();
    await dialog.getByRole('button', { name: 'ביטול' }).click();
  } finally {
    await page.request.delete(`/api/accounts/${accId}`);
  }
});
