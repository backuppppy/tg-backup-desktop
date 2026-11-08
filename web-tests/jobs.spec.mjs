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
  await expect(c).toContainText('הועתקו: 0');
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
    await expect(page.locator('.jobs-summary')).toHaveText(/^🟢 \d+ משימות פעילות · הועתקו: [\d,]+$/);
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

/**
 * Serves `patch` merged over the real snapshot as the whole event stream. The snapshot has no serverNow (the page
 * runs on its own clock, as before serverNow) unless `skew` is given: then serverNow is the time it is served plus
 * `skew` ms, a server clock that far from the page's. `statuses`, when given, follows as a statuses event with the
 * same serverNow.
 */
async function fakeStream(page, patch, { skew = null, statuses = null } = {}) {
  const { serverNow, ...real } = await state(page.request);
  await page.route('**/api/events', (route) => {
    const now = skew === null ? {} : { serverNow: Date.now() + skew };
    const snapshot = { ...real, ...patch, ...now };
    const more = statuses ? `event: statuses\ndata: ${JSON.stringify({ statuses, ...now })}\n\n` : '';
    return route.fulfill({
      status: 200,
      headers: { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache' },
      body: `retry: 600000\nevent: snapshot\ndata: ${JSON.stringify(snapshot)}\n\n${more}`,
    });
  });
}

/**
 * True when `parts`, in the order they are read (right to left), are laid out from right to left on the screen:
 * each part's box (found in the line's text) lies left of the one read before it.
 */
async function visualOrder(line, parts) {
  return line.evaluate((el, parts) => {
    const walker = document.createTreeWalker(el, NodeFilter.SHOW_TEXT);
    const nodes = [];
    let text = '';
    for (let n = walker.nextNode(); n; n = walker.nextNode()) { nodes.push([n, text.length]); text += n.data; }
    const at = (i) => { const [n, start] = nodes.findLast(([, s]) => s <= i); return [n, i - start]; };
    let from = 0;
    const boxes = parts.map((p) => {
      const i = text.indexOf(p, from);
      if (i < 0) throw new Error(`${p} not in ${text}`);
      from = i + p.length;
      const r = document.createRange();
      r.setStart(...at(i));
      r.setEnd(...at(i + p.length - 1));
      const end = document.createRange();
      end.setStart(...at(i + p.length - 1));
      end.setEnd(...at(i + p.length - 1).map((v, k) => (k === 1 ? v + 1 : v)));
      const rects = [...r.getClientRects(), ...end.getClientRects()].filter((b) => b.width > 0);
      return { left: Math.min(...rects.map((b) => b.left)), right: Math.max(...rects.map((b) => b.right)) };
    });
    return boxes.every((b, k) => k === 0 || b.right <= boxes[k - 1].left + 1);
  }, parts);
}

const status = (over) => ({
  running: false, paused: false, phase: '', lastId: 0, sourceChatId: 0, sourceTitle: '', isProtected: false,
  copied: 0, failed: 0, targetChatId: 0, lastTargetId: 0, lastLink: null, lastTargetLink: null,
  copiedTotal: 0, runMillis: 0, runningSince: 0, latestId: 0, ...over,
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
      f1: status({ running: true, paused: true, phase: 'מעתיק', copied: 3, copiedTotal: 3, failed: 2, lastId: 77,
        lastLink: 'https://t.me/c/100/77', lastTargetLink: 'https://t.me/c/200/5', sourceTitle: 'ערוץ מקור' }),
      f2: status({ running: true, phase: 'ממתין', copied: 4, copiedTotal: 4, isProtected: true }),
      f3: status({ lastId: 9 }),
    },
  });
  await page.goto('/#jobs');
  await expect(page.locator('.jobs-summary')).toHaveText('🟢 2 משימות פעילות · הועתקו: 7');
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
  await expect(c1).toContainText('הועתקו: 3 · נכשלו: 2');
  const phase = c1.getByRole('link', { name: 'ההודעה האחרונה ביעד' });
  await expect(phase).toHaveText('מעתיק');
  await expect(phase).toHaveAttribute('href', 'https://t.me/c/200/5');
  await expect(phase).toHaveAttribute('target', '_blank');
  await expect(phase).toHaveAttribute('rel', 'noopener');
  // The link is on the number only.
  await expect(c1.locator('.last')).toHaveText('אוחז ב-id 77');
  const last = c1.locator('.last').getByRole('link', { name: 'הודעה 77 במקור', exact: true });
  await expect(last).toHaveAttribute('href', 'https://t.me/c/100/77');
  await expect(last).toHaveAttribute('target', '_blank');
  // As in Android: הודעה אחרונה שטופלה opens the source's message, the phase the target's.
  await expect(c1.locator('.last-handled')).toHaveText('הודעה אחרונה שטופלה: 77');
  const handled = c1.locator('.last-handled').getByRole('link', { name: 'הודעה 77 במקור', exact: true });
  await expect(handled).toHaveAttribute('href', 'https://t.me/c/100/77');
  await expect(handled).toHaveAttribute('target', '_blank');
  // The lines in the Android order: phase, last handled, copied, position.
  const order = await c1.evaluate((el) => [...el.querySelectorAll('.phase, .last-handled, .copied, .last')].map((e) => e.classList[0]));
  expect(order).toEqual(['phase', 'last-handled', 'copied', 'last']);
  await expect(c1.getByRole('button', { name: '▶ המשך' })).toBeVisible();

  const c2 = card(page, 'משימה f2');
  await expect(c2.locator('.job-title')).toHaveText('🟢 משימה f2');
  await expect(c2).toContainText('🔒 ערוץ מוגן — הורדה והעלאה מחדש');
  await expect(c2.getByText('ממתין')).toBeVisible();
  await expect(c2.getByRole('link', { name: 'ממתין' })).toHaveCount(0);
  await expect(c2.getByRole('button', { name: '⏸ השהה' })).toBeVisible();

  const c3 = card(page, 'שלישית');
  await expect(c3.getByText('אוחז ב-id 9')).toBeVisible();
  await expect(c3.getByRole('link')).toHaveCount(0);
  await expect(c3).not.toContainText('נכשלו');
});

test('on a desktop the missing-account warning comes before ➕ הוסף משימה in the DOM; under 900 px the button closes the list', async ({ page }, info) => {
  await fakeStream(page, { accounts: [], jobs: [job({ id: 'o1', name: 'אחת' })], statuses: {} });
  await page.goto('/#jobs');
  const list = page.locator('[data-screen="jobs"] .jobs');
  await expect(list.getByText("קודם הוסף חשבון בלשונית 'חשבונות'.")).toBeVisible();
  const order = await list.evaluate((el) => [...el.children].map((c) => (c.matches('.jobs-header') ? 'header'
    : c.matches('.error-text') ? 'warning' : c.matches('button.full') ? 'add' : 'card')));
  expect(order).toEqual(info.project.name === 'window' ? ['header', 'warning', 'add', 'card'] : ['header', 'warning', 'card', 'add']);
});

test('with nothing running, the all-jobs buttons but ▶ הפעל הכל are disabled', async ({ page }) => {
  await fakeStream(page, { jobs: [job({ id: 'g1', name: 'עצורה' })], statuses: {} });
  await page.goto('/#jobs');
  await expect(page.locator('.jobs-summary')).toHaveText('⚪ עצור · הועתקו: 0');
  await expect(page.getByRole('button', { name: '▶ הפעל הכל' })).toBeEnabled();
  await expect(page.getByRole('button', { name: '⏸ השהה הכל' })).toBeDisabled();
  await expect(page.getByRole('button', { name: '▶ המשך הכל' })).toBeDisabled();
  await expect(page.getByRole('button', { name: '⏹ עצור הכל' })).toBeDisabled();
});

// Plan 3b section b: the run totals. The copied count and the run time are the job's totals (kept over a stop), the
// position is the last handled id of the source's latest one.
test('run totals: הועתקו with thousands, אוחז ב-id X מתוך Y, ⏱ רץ, and the summary sums the totals', async ({ page }) => {
  await page.route('**/api/jobs/*/latest', (route) => route.fulfill({ status: 200, contentType: 'application/json', body: '{"ok":true,"latestId":0}' }));
  await fakeStream(page, {
    jobs: [
      job({ id: 's1', name: 'מלאה' }),
      job({ id: 's2', name: 'בלי אחרון' }),
      job({ id: 's3', name: 'רק אחרון' }),
      job({ id: 's4', name: 'חדשה' }),
    ],
    statuses: {
      s1: status({ copiedTotal: 12340, copied: 5, failed: 1234, lastId: 45210, latestId: 50000, runMillis: 8045000,
        lastLink: 'https://t.me/c/100/45210' }),
      s2: status({ copiedTotal: 1234567, lastId: 987654, runMillis: 90061000 }),
      s3: status({ latestId: 50000 }),
    },
  });
  await page.goto('/#jobs');
  await expect(page.locator('.jobs-summary')).toHaveText('⚪ עצור · הועתקו: 1,246,907');

  const c1 = card(page, 'מלאה');
  await expect(c1).toContainText('הועתקו: 12,340 · נכשלו: 1,234');
  await expect(c1.locator('.last')).toHaveText('אוחז ב-id 45,210 מתוך 50,000');
  await expect(c1.locator('.run-time')).toHaveText('⏱ רץ: 2:14:05');
  await expect(c1.locator('.last-handled')).toHaveText('הודעה אחרונה שטופלה: 45210');
  expect(await visualOrder(c1.locator('.last-handled'), ['הודעה', 'אחרונה', 'שטופלה:', '45210'])).toBe(true);
  // Not shown before any message was handled.
  await expect(card(page, 'רק אחרון').locator('.last-handled')).toHaveCount(0);
  // Read right to left: אוחז ב-id, 45,210, מתוך, 50,000; and ⏱ רץ: then the time, הועתקו: then the count.
  expect(await visualOrder(c1.locator('.last'), ['אוחז ב-', 'id', '45,210', 'מתוך', '50,000'])).toBe(true);
  expect(await visualOrder(c1.locator('.run-time'), ['⏱', 'רץ:', '2:14:05'])).toBe(true);
  expect(await visualOrder(c1.locator('.copied'), ['הועתקו:', '12,340', '·', 'נכשלו:', '1,234'])).toBe(true);
  expect(await visualOrder(card(page, 'בלי אחרון').locator('.last'), ['אוחז ב-', 'id', '987,654'])).toBe(true);
  // The link is on X only.
  const last = c1.locator('.last').getByRole('link', { name: 'הודעה 45,210 במקור', exact: true });
  await expect(last).toHaveAttribute('href', 'https://t.me/c/100/45210');
  await expect(last).toHaveText('45,210');
  const handled = c1.locator('.last-handled').getByRole('link', { name: 'הודעה 45210 במקור', exact: true });
  await expect(handled).toHaveAttribute('href', 'https://t.me/c/100/45210');
  await expect(handled).toHaveText('45210');

  const c2 = card(page, 'בלי אחרון');
  await expect(c2).toContainText('הועתקו: 1,234,567');
  await expect(c2).not.toContainText('נכשלו');
  await expect(c2.locator('.last')).toHaveText('אוחז ב-id 987,654');
  await expect(c2.locator('.run-time')).toHaveText('⏱ רץ: 25:01:01');

  const c3 = card(page, 'רק אחרון');
  await expect(c3.locator('.last')).toHaveText('אוחז ב-id 0 מתוך 50,000');
  await expect(c3.locator('.run-time')).toHaveCount(0);

  // A job with no status yet: nothing copied, no position, no run time.
  const c4 = card(page, 'חדשה');
  await expect(c4).toContainText('הועתקו: 0');
  await expect(c4.locator('.last')).toHaveCount(0);
  await expect(c4.locator('.run-time')).toHaveCount(0);

  // A stopped job's clock does not move.
  await page.waitForTimeout(1500);
  await expect(c1.locator('.run-time')).toHaveText('⏱ רץ: 2:14:05');
});

test('run time: the clock moves once a second while running, without drawing the card again, and after the screen comes back', async ({ page }) => {
  await page.route('**/api/jobs/*/latest', (route) => route.fulfill({ status: 200, contentType: 'application/json', body: '{"ok":true,"latestId":0}' }));
  // The page runs on this machine's clock, as the server does.
  const since = Date.now() - 5000;
  await fakeStream(page, {
    jobs: [job({ id: 't1', name: 'רצה' }), job({ id: 't2', name: 'מושהית' })],
    statuses: {
      t1: status({ running: true, phase: 'מעתיק', runMillis: 60000, runningSince: since, lastId: 3, latestId: 9 }),
      t2: status({ running: true, paused: true, phase: 'מושהה', runMillis: 7000, lastId: 1, latestId: 9 }),
    },
  });
  await page.goto('/#jobs');
  const c1 = card(page, 'רצה');
  const clock = c1.locator('.run-time');
  // 60 s saved and 5 s since runningSince, plus the time the page took to load (up to 25 s on a slow machine):
  // neither the saved time alone (0:01:00) nor a clock off by a minute.
  await expect(clock).toHaveText(/^⏱ רץ: 0:01:(0[5-9]|[12]\d)$/);
  const seconds = async () => Number((await clock.textContent()).split(':').pop());
  // The card is not drawn again: a focused button keeps its focus, the same element stays.
  await c1.getByRole('button', { name: '⏸ השהה' }).focus();
  await c1.evaluate((el) => { el.dataset.marked = '1'; });
  const first = await seconds();
  await expect.poll(seconds, { timeout: 4000 }).toBeGreaterThanOrEqual(first + 2);
  await expect(c1).toHaveAttribute('data-marked', '1');
  await expect(c1.getByRole('button', { name: '⏸ השהה' })).toBeFocused();
  // A paused job (runningSince 0) stays at its saved time.
  await expect(card(page, 'מושהית').locator('.run-time')).toHaveText('⏱ רץ: 0:00:07');

  // Away and back: the clock goes on.
  await page.goto('/#accounts');
  await expect(page.locator('[data-screen="accounts"]')).toBeVisible();
  await page.goto('/#jobs');
  await expect(clock).toBeVisible();
  const back = await seconds();
  await expect.poll(seconds, { timeout: 4000 }).toBeGreaterThan(back);
});

test('run time: back on the screen the clock shows the time of now at once, not a second later', async ({ page }) => {
  await page.route('**/api/jobs/*/latest', (route) => route.fulfill({ status: 200, contentType: 'application/json', body: '{"ok":true,"latestId":0}' }));
  // The page's clock is a fake one: no tick comes unless the test moves the time on.
  const t0 = Date.parse('2026-11-07T10:00:00Z');
  await page.clock.install({ time: t0 });
  await fakeStream(page, {
    jobs: [job({ id: 'b1', name: 'חוזרת' })],
    statuses: { b1: status({ running: true, phase: 'מעתיק', runMillis: 60000, runningSince: t0 - 5000, lastId: 3, latestId: 9 }) },
  });
  await page.goto('/#jobs');
  const clock = card(page, 'חוזרת').locator('.run-time');
  await expect(clock).toBeVisible();
  await page.clock.pauseAt(t0 + 20_000);
  await expect(clock).toHaveText('⏱ רץ: 0:01:25');
  await page.goto('/#accounts');
  await expect(page.locator('[data-screen="accounts"]')).toBeVisible();
  await page.clock.fastForward(30_000);
  await page.goto('/#jobs');
  // The time stands still now: only a clock drawn on the way back shows 0:01:55.
  await expect(clock).toHaveText('⏱ רץ: 0:01:55');
});

/** Counts the page's one second intervals (only the jobs screen's run clock uses one): created and still active. */
async function countIntervals(page) {
  await page.addInitScript(() => {
    const counts = { created: 0, active: new Set() };
    window.__intervals = () => ({ created: counts.created, active: counts.active.size });
    const set = window.setInterval;
    const clear = window.clearInterval;
    window.setInterval = function (fn, ms, ...rest) {
      const id = set.call(this, fn, ms, ...rest);
      if (ms === 1000) { counts.created += 1; counts.active.add(id); }
      return id;
    };
    window.clearInterval = function (id) {
      counts.active.delete(id);
      return clear.call(this, id);
    };
  });
  return () => page.evaluate(() => window.__intervals());
}

test('run time: the timer stops while the screen is away and runs again when it is back', async ({ page }) => {
  await page.route('**/api/jobs/*/latest', (route) => route.fulfill({ status: 200, contentType: 'application/json', body: '{"ok":true,"latestId":0}' }));
  const intervals = await countIntervals(page);
  await fakeStream(page, {
    jobs: [job({ id: 'w1', name: 'נעלמת' })],
    statuses: { w1: status({ running: true, phase: 'מעתיק', runMillis: 1000, runningSince: Date.now(), lastId: 3, latestId: 9 }) },
  });
  await page.goto('/#jobs');
  await expect(card(page, 'נעלמת').locator('.run-time')).toBeVisible();
  await expect.poll(intervals).toEqual({ created: 1, active: 1 });
  await page.goto('/#accounts');
  await expect(page.locator('[data-screen="accounts"]')).toBeVisible();
  await expect.poll(intervals).toEqual({ created: 1, active: 0 });
  await page.waitForTimeout(1500);
  expect(await intervals()).toEqual({ created: 1, active: 0 });
  await page.goto('/#jobs');
  await expect.poll(intervals).toEqual({ created: 2, active: 1 });
});

test('run time: the timer stops once no shown job runs, and the clock stays at the saved time', async ({ page }) => {
  await page.route('**/api/jobs/*/latest', (route) => route.fulfill({ status: 200, contentType: 'application/json', body: '{"ok":true,"latestId":0}' }));
  const intervals = await countIntervals(page);
  const jobs = [job({ id: 'e1', name: 'נגמרת' })];
  // The snapshot shows it running; the statuses event right after it shows it stopped with its time saved.
  await fakeStream(page, {
    jobs, statuses: { e1: status({ running: true, phase: 'מעתיק', runMillis: 1000, runningSince: Date.now(), lastId: 3, latestId: 9 }) },
  }, { skew: 0, statuses: { e1: status({ runMillis: 4000, lastId: 3, latestId: 9 }) } });
  await page.goto('/#jobs');
  const clock = card(page, 'נגמרת').locator('.run-time');
  await expect(clock).toHaveText('⏱ רץ: 0:00:04');
  await expect.poll(intervals).toEqual({ created: 1, active: 0 });
  await page.waitForTimeout(1500);
  await expect(clock).toHaveText('⏱ רץ: 0:00:04');
});

for (const skew of [-60_000, 60_000]) {
  test(`run time on the server clock: a server clock ${skew / 1000} s from the page's still shows the right time, moving each second`, async ({ page }) => {
    await page.route('**/api/jobs/*/latest', (route) => route.fulfill({ status: 200, contentType: 'application/json', body: '{"ok":true,"latestId":0}' }));
    // The server's runningSince: 5 s ago on the server's clock, which is `skew` from this one.
    const since = Date.now() + skew - 5000;
    const running = status({ running: true, phase: 'מעתיק', runMillis: 60000, runningSince: since, lastId: 3, latestId: 9 });
    const jobs = [job({ id: 'k1', name: 'סטייה' })];
    // -60 s by the snapshot alone; +60 s by a statuses event after a snapshot of the job stopped.
    if (skew < 0) await fakeStream(page, { jobs, statuses: { k1: running } }, { skew });
    else await fakeStream(page, { jobs, statuses: { k1: status({ runMillis: 1000 }) } }, { skew, statuses: { k1: running } });
    await page.goto('/#jobs');
    const clock = card(page, 'סטייה').locator('.run-time');
    // On the page's clock it would read 0:02:05 (60 s behind) or stay at 0:01:00 (60 s ahead).
    await expect(clock).toHaveText(/^⏱ רץ: 0:01:(0[5-9]|[12]\d)$/);
    const seconds = async () => Number((await clock.textContent()).split(':').pop());
    const first = await seconds();
    await expect.poll(seconds, { timeout: 2500 }).toBeGreaterThanOrEqual(first + 1);
    await expect.poll(seconds, { timeout: 3500 }).toBeGreaterThanOrEqual(first + 2);
  });
}

test('the latest id is asked once a page load for each job shown without one', async ({ page }) => {
  const asked = [];
  await page.route('**/api/jobs/*/latest', (route) => {
    asked.push(`${route.request().method()} ${new URL(route.request().url()).pathname}`);
    return route.fulfill({ status: 200, contentType: 'application/json', body: '{"ok":true,"latestId":0}' });
  });
  await fakeStream(page, {
    jobs: [job({ id: 'u1', name: 'בלי' }), job({ id: 'u2', name: 'עם' }), job({ id: 'u 3', name: 'רווח' })],
    statuses: { u1: status({ lastId: 4 }), u2: status({ lastId: 4, latestId: 10 }) },
  });
  await page.goto('/#jobs');
  await expect(page.locator('.job-card')).toHaveCount(3);
  await expect.poll(() => asked.length).toBe(2);
  // Drawn again (away and back, a focus move): no more requests.
  await page.goto('/#accounts');
  await expect(page.locator('[data-screen="accounts"]')).toBeVisible();
  await page.goto('/#jobs');
  await expect(page.locator('.job-card')).toHaveCount(3);
  await page.waitForTimeout(500);
  expect(asked.sort()).toEqual(['POST /api/jobs/u%203/latest', 'POST /api/jobs/u1/latest']);
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
  await expect(c.getByText('אוחז ב-id 5')).toBeVisible();
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
