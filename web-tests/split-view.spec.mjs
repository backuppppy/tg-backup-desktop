// Split view (Task 10b): from 1200 px wide two screens side by side, each chosen in its pane's <select>; `מסך אחד`
// / `שני מסכים` in the top bar; a divider (mouse and keyboard) sets the right pane's share. From 900 to 1199 px one
// screen with the rail, under 900 px the phone layout. The layout is kept in localStorage (tgb.layout).
// Runs in the `wide` project (1920x1040); the tests that need other sizes resize the viewport themselves.
import { test, expect } from '@playwright/test';

const TABS = ['משימות', 'ייצוא', 'חשבונות', 'לוג', 'הגדרות'];
const NOTE = 'API ID ו-API Hash מקבלים בכתובת my.telegram.org ← API development tools.';

test.beforeEach(({}, info) => { test.skip(info.project.name !== 'wide', 'split view runs in the wide project'); });

async function open(page, hash = '') {
  await page.goto(`/${hash}`);
  await expect(page.locator('#title')).toContainText('TG Backup  v');
}

const pane = (page, side) => page.locator(`.pane[data-pane="${side}"]`);
const select = (page, side) => pane(page, side).locator('.pane-head select');
const screenIn = (page, side, name) => pane(page, side).locator(`[data-screen="${name}"]`);
const box = (locator) => locator.boundingBox();
// The pane divider (job cards have <hr> separators too).
const divider = (page) => page.locator('main > [role="separator"]');
const single = (page) => page.getByRole('button', { name: 'מסך אחד', exact: true });
const both = (page) => page.getByRole('button', { name: 'שני מסכים', exact: true });

async function expectSplit(page, right, left) {
  await expect(pane(page, 'right')).toBeVisible();
  await expect(pane(page, 'left')).toBeVisible();
  await expect(screenIn(page, 'right', right)).toBeVisible();
  await expect(screenIn(page, 'left', left)).toBeVisible();
  await expect(select(page, 'right')).toHaveValue(right);
  await expect(select(page, 'left')).toHaveValue(left);
}

/** One screen: one visible pane showing `name`, no divider, no pane header. */
async function expectSingle(page, name) {
  await expect(page.locator('.pane:visible')).toHaveCount(1);
  await expect(page.locator(`[data-screen="${name}"]`)).toBeVisible();
  await expect(divider(page)).toBeHidden();
  await expect(page.locator('.pane-head:visible')).toHaveCount(0);
}

test('with nothing saved: two panes, משימות on the right and לוג on the left, each with its select', async ({ page }) => {
  await open(page);
  await expectSplit(page, 'jobs', 'log');
  for (const side of ['right', 'left']) {
    await expect(select(page, side).locator('option')).toHaveText(TABS);
  }
  const r = await box(pane(page, 'right'));
  const l = await box(pane(page, 'left'));
  const d = await box(divider(page));
  const rail = await box(page.locator('nav.tabs'));
  // Rail, right pane, divider, left pane, from right to left.
  expect(r.x + r.width).toBeLessThanOrEqual(rail.x + 1);
  expect(d.x + d.width).toBeLessThanOrEqual(r.x + 1);
  expect(l.x + l.width).toBeLessThanOrEqual(d.x + 1);
  // A small margin keeps the active outline off the window's edge.
  expect(l.x).toBeGreaterThanOrEqual(4);
  expect(l.x).toBeLessThanOrEqual(16);
  // Names for assistive technology only.
  await expect(select(page, 'right')).toHaveAttribute('aria-label', 'חלק ימני');
  await expect(select(page, 'left')).toHaveAttribute('aria-label', 'חלק שמאלי');
  await expect(divider(page)).toHaveAttribute('aria-label', 'גודל החלקים');
  await expect(page.getByRole('group', { name: 'תצוגה' })).toContainText('מסך אחד');
  // Task 10c: the right pane shows the jobs, so it has a third of the room (up to 3 jobs) or half (from 4).
  const n = (await (await page.request.get('/api/state')).json()).jobs.length;
  expect(Math.abs(r.width / (r.width + l.width) - (n >= 4 ? 0.5 : 1 / 3))).toBeLessThan(0.01);
  // Each pane scrolls on its own; the page itself does not.
  for (const side of ['right', 'left']) {
    expect(await pane(page, side).locator('.pane-body').evaluate((el) => getComputedStyle(el).overflowY)).toBe('auto');
  }
  expect(await page.evaluate(() => document.documentElement.scrollHeight)).toBeLessThanOrEqual(1040);
  // The active pane (right) gets the primary outline; the rail marks both screens, the active one as the page.
  await expect(pane(page, 'right')).toHaveClass(/\bactive\b/);
  await expect(pane(page, 'left')).not.toHaveClass(/\bactive\b/);
  await expect(page.locator('nav.tabs a[aria-current="page"]')).toHaveText('משימות');
  await expect(page.locator('nav.tabs a.shown')).toHaveText('לוג');
  expect(await page.evaluate(() => location.hash)).toBe('#jobs');
  await expect(single(page)).toBeVisible();
  await expect(both(page)).toHaveAttribute('aria-pressed', 'true');
  await expect(single(page)).toHaveAttribute('aria-pressed', 'false');
});

test('choosing חשבונות in the left select shows Accounts on the left', async ({ page }) => {
  await open(page);
  await select(page, 'left').selectOption({ label: 'חשבונות' });
  await expectSplit(page, 'jobs', 'accounts');
  await expect(pane(page, 'left').getByText(NOTE, { exact: true })).toBeVisible();
  await expect(page.locator('[data-screen="log"]')).toHaveCount(0);
  await expect(pane(page, 'left')).toHaveClass(/\bactive\b/);
  expect(await page.evaluate(() => location.hash)).toBe('#accounts');
});

test('choosing משימות in the left pane swaps the panes', async ({ page }) => {
  await open(page);
  await select(page, 'left').selectOption({ label: 'משימות' });
  await expectSplit(page, 'log', 'jobs');
  // The same screen never shows twice.
  await expect(page.locator('[data-screen="jobs"]')).toHaveCount(1);
});

test('after a click inside the left pane, the rail’s ייצוא puts Export on the left', async ({ page }) => {
  await open(page);
  await pane(page, 'left').locator('.pane-body').click({ position: { x: 20, y: 200 } });
  await expect(pane(page, 'left')).toHaveClass(/\bactive\b/);
  expect(await page.evaluate(() => location.hash)).toBe('#log');
  await page.locator('nav.tabs a', { hasText: 'ייצוא' }).click();
  await expectSplit(page, 'jobs', 'export');
  expect(await page.evaluate(() => location.hash)).toBe('#export');
  await expect(page.locator('nav.tabs a[aria-current="page"]')).toHaveText('ייצוא');
  await expect(page.locator('nav.tabs a.shown')).toHaveText('משימות');
});

test('dragging the divider changes the right pane’s width, which survives a reload; arrows move it by 5%', async ({ page }) => {
  await open(page);
  const before = (await box(pane(page, 'right'))).width;
  const beforeShare = before / (before + (await box(pane(page, 'left'))).width);
  const d = await box(divider(page));
  await page.mouse.move(d.x + d.width / 2, d.y + d.height / 2);
  await page.mouse.down();
  await page.mouse.move(d.x + d.width / 2 - 100, d.y + d.height / 2, { steps: 5 });
  await page.mouse.move(d.x + d.width / 2 - 200, d.y + d.height / 2, { steps: 5 });
  await page.mouse.up();
  const after = (await box(pane(page, 'right'))).width;
  expect(after).toBeGreaterThan(before + 150);
  // Task 10c: the drag starts from the jobs pane's automatic share (a third or half), not from 0.55. Final wave: it is
  // kept as the jobs' share (jobsRatio), the jobs being on the right.
  expect(JSON.parse(await page.evaluate(() => localStorage.getItem('tgb.layout'))).jobsRatio).toBeGreaterThan(beforeShare + 0.08);
  await page.reload();
  await expect(page.locator('#title')).toContainText('TG Backup  v');
  await expect(pane(page, 'left')).toBeVisible();
  expect(Math.abs((await box(pane(page, 'right'))).width - after)).toBeLessThanOrEqual(2);

  // From the keyboard: a 5% step, never past 70%.
  await divider(page).focus();
  const v0 = Number(await divider(page).getAttribute('aria-valuenow'));
  await page.keyboard.press('ArrowRight');
  await expect(divider(page)).toHaveAttribute('aria-valuenow', String(v0 - 5));
  for (let i = 0; i < 10; i++) await page.keyboard.press('ArrowLeft');
  await expect(divider(page)).toHaveAttribute('aria-valuenow', '70');
  await expect(divider(page)).toHaveAttribute('aria-orientation', 'vertical');
  expect(JSON.parse(await page.evaluate(() => localStorage.getItem('tgb.layout'))).jobsRatio).toBe(0.7);
});

test('מסך אחד shows one screen; שני מסכים brings back the same two', async ({ page }) => {
  await open(page);
  await select(page, 'left').selectOption({ label: 'הגדרות' });
  await select(page, 'right').selectOption({ label: 'ייצוא' });
  await single(page).click();
  await expectSingle(page, 'export');
  await expect(single(page)).toHaveAttribute('aria-pressed', 'true');
  await expect(page.locator('[data-screen="settings"]')).toHaveCount(0);
  await expect(page.locator('nav.tabs a.shown')).toHaveCount(0);
  // The 10a single layout: main at most 1200 px.
  expect((await box(page.locator('main'))).width).toBeLessThanOrEqual(1200);
  // The toggle stays, to turn the split back on, and the choice survives a reload.
  await page.reload();
  await expect(page.locator('#title')).toContainText('TG Backup  v');
  await expectSingle(page, 'export');
  await both(page).click();
  await expectSplit(page, 'export', 'settings');
});

test('resizing: 960x1040 one screen with the rail, 960x540 one screen, back to 1920x1040 the split', async ({ page }) => {
  await open(page);
  await select(page, 'left').selectOption({ label: 'חשבונות' });
  await select(page, 'right').selectOption({ label: 'לוג' });
  // The active pane is the right one now (its select was the last used).
  await page.setViewportSize({ width: 960, height: 1040 });
  await expectSingle(page, 'log');
  const rail = await box(page.locator('nav.tabs'));
  expect(rail.height).toBeGreaterThan(rail.width);
  await expect(single(page)).toBeHidden();
  await expect(both(page)).toBeHidden();
  // The card grid fits the pane: 2 columns at 960.
  await page.locator('nav.tabs a', { hasText: 'משימות' }).click();
  await expectSingle(page, 'jobs');
  expect((await page.locator('.jobs').evaluate((el) => getComputedStyle(el).gridTemplateColumns)).split(' ')).toHaveLength(2);
  await page.locator('nav.tabs a', { hasText: 'לוג' }).click();
  await expectSingle(page, 'log');

  await page.setViewportSize({ width: 960, height: 540 });
  await expectSingle(page, 'log');
  await page.setViewportSize({ width: 1920, height: 1040 });
  await expectSplit(page, 'log', 'accounts');
  await expect(single(page)).toBeVisible();
});

test('390x844: no split, no toggle, the bottom bar', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await open(page);
  await expectSingle(page, 'jobs');
  await expect(single(page)).toBeHidden();
  await expect(both(page)).toBeHidden();
  const nav = await box(page.locator('nav.tabs'));
  expect(nav.width).toBeGreaterThanOrEqual(389);
  expect(nav.y + nav.height).toBeGreaterThanOrEqual(843);
  expect(await page.locator('nav.tabs').evaluate((el) => getComputedStyle(el).flexDirection)).toBe('row');
});

test('a code typed in an Accounts card survives moving Accounts from the left pane to the right one', async ({ page }) => {
  const real = await (await page.request.get('/api/state')).json();
  const snapshot = {
    ...real,
    log: [],
    accounts: [{ id: 'a2', name: 'name-a2', apiId: 1, hasApiHash: true, phone: '+10000002' }],
    accountStates: [{ id: 'a2', auth: { type: 'NeedCode', label: 'ממתין לקוד' } }],
  };
  await page.route('**/api/events', (route) => route.fulfill({
    status: 200,
    headers: { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache' },
    body: `retry: 600000\nevent: snapshot\ndata: ${JSON.stringify(snapshot)}\n\n`,
  }));
  await page.route(/\/api\/export\/[^/]+\/session$/, (r) => r.fulfill({ status: 200, contentType: 'application/json', body: '{"loggedIn":false}' }));
  await open(page);
  await select(page, 'left').selectOption({ label: 'חשבונות' });
  const code = screenIn(page, 'left', 'accounts').getByLabel('קוד מטלגרם');
  await code.fill('12345');
  await code.evaluate((el) => { el.dataset.mark = 'same'; });
  await select(page, 'right').selectOption({ label: 'חשבונות' });
  await expectSplit(page, 'accounts', 'jobs');
  const moved = screenIn(page, 'right', 'accounts').getByLabel('קוד מטלגרם');
  await expect(moved).toHaveValue('12345');
  // The same input element, re-attached and not rebuilt.
  await expect(moved).toHaveAttribute('data-mark', 'same');
  // And into one-screen mode and back.
  await single(page).click();
  await expect(page.getByLabel('קוד מטלגרם')).toHaveValue('12345');
  await both(page).click();
  await expect(moved).toHaveValue('12345');
});

test('a dialog opened from a pane is centred on the window; Esc gives the focus back to its opener', async ({ page }) => {
  await open(page);
  await select(page, 'left').selectOption({ label: 'משימות' });
  const add = pane(page, 'left').getByRole('button', { name: '➕ הוסף משימה' });
  await add.click();
  const dialog = page.getByRole('dialog', { name: 'משימה חדשה' });
  await expect(dialog).toBeVisible();
  const b = await box(dialog);
  expect(Math.abs(b.x + b.width / 2 - 960)).toBeLessThanOrEqual(10);
  expect(Math.abs(b.y + b.height / 2 - 520)).toBeLessThanOrEqual(10);
  await page.keyboard.press('Escape');
  await expect(dialog).toBeHidden();
  await expect(add).toBeFocused();
});

test('export in a pane: one column under 1100 px of pane, two from there (a container query)', async ({ page }) => {
  await open(page);
  await select(page, 'right').selectOption({ label: 'ייצוא' });
  const exp = screenIn(page, 'right', 'export').locator('.export');
  await expect(exp).toBeVisible();
  expect((await box(pane(page, 'right'))).width).toBeLessThan(1100);
  expect(await exp.evaluate((el) => getComputedStyle(el).display)).toBe('flex');
  await divider(page).focus();
  for (let i = 0; i < 3; i++) await page.keyboard.press('ArrowLeft');
  await expect(divider(page)).toHaveAttribute('aria-valuenow', '70');
  expect((await box(pane(page, 'right'))).width).toBeGreaterThanOrEqual(1100);
  await expect.poll(() => exp.evaluate((el) => getComputedStyle(el).display)).toBe('grid');
  // Final wave: the arrows moved the divider without the jobs in view, so they set only this pair's ratio (0.7); the
  // jobs, once in a pane, get their automatic share.
  const n = (await (await page.request.get('/api/state')).json()).jobs.length;
  const auto = n >= 4 ? 0.5 : 0.33;
  expect(JSON.parse(await page.evaluate(() => localStorage.getItem('tgb.layout')))).toMatchObject({ ratio: 0.7, jobsRatio: null, ratioJobs: null });
  // The card grid in the left pane: a third (about 600 px) is one column, half (from 4 jobs) two; in the right pane,
  // at 70% from the arrows, at least two.
  await select(page, 'left').selectOption({ label: 'משימות' });
  await expect(divider(page)).toHaveAttribute('aria-valuenow', String(Math.round((1 - auto) * 100)));
  expect((await screenIn(page, 'left', 'jobs').locator('.jobs').evaluate((el) => getComputedStyle(el).gridTemplateColumns)).split(' ').length).toBe(n >= 4 ? 2 : 1);
  await select(page, 'right').selectOption({ label: 'משימות' });
  await expect(divider(page)).toHaveAttribute('aria-valuenow', String(Math.round(auto * 100)));
  await divider(page).focus();
  for (let i = 0; i < 10; i++) await page.keyboard.press('ArrowLeft');
  await expect(divider(page)).toHaveAttribute('aria-valuenow', '70');
  expect((await screenIn(page, 'right', 'jobs').locator('.jobs').evaluate((el) => getComputedStyle(el).gridTemplateColumns)).split(' ').length).toBeGreaterThanOrEqual(2);
});

test('what is typed survives a screen leaving the view: 960 px and back, one screen and back, the Export search', async ({ page }) => {
  const real = await (await page.request.get('/api/state')).json();
  const snapshot = {
    ...real,
    log: [],
    accounts: [{ id: 'a2', name: 'name-a2', apiId: 1, hasApiHash: true, phone: '+10000002' }],
    accountStates: [{ id: 'a2', auth: { type: 'NeedCode', label: 'ממתין לקוד' } }],
  };
  await page.route('**/api/events', (route) => route.fulfill({
    status: 200,
    headers: { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache' },
    body: `retry: 600000\nevent: snapshot\ndata: ${JSON.stringify(snapshot)}\n\n`,
  }));
  let asked = 0;
  await page.route(/\/api\/export\/[^/]+\/session$/, (r) => { asked += 1; return r.fulfill({ status: 200, contentType: 'application/json', body: '{"loggedIn":true}' }); });
  await page.route(/\/api\/export\/[^/]+\/chats$/, (r) => r.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify([{ peerId: 11, title: 'Alpha News', username: 'alphanews', type: 'PublicChannel', isForum: false, archived: false }]),
  }));
  await open(page);
  await select(page, 'left').selectOption({ label: 'חשבונות' });
  const code = page.getByLabel('קוד מטלגרם');
  await code.fill('12345');
  await code.evaluate((el) => { el.dataset.mark = 'same'; });

  // The right pane active: at 960 px only its screen shows, and Accounts leaves the view.
  await pane(page, 'right').locator('.pane-body').click({ position: { x: 20, y: 400 } });
  await page.setViewportSize({ width: 960, height: 1040 });
  await expectSingle(page, 'jobs');
  await expect(page.locator('[data-screen="accounts"]')).toHaveCount(0);
  await page.setViewportSize({ width: 1920, height: 1040 });
  await expectSplit(page, 'jobs', 'accounts');
  await expect(code).toHaveValue('12345');
  await expect(code).toHaveAttribute('data-mark', 'same');

  await pane(page, 'right').locator('.pane-body').click({ position: { x: 20, y: 400 } });
  await single(page).click();
  await expectSingle(page, 'jobs');
  await both(page).click();
  await expectSplit(page, 'jobs', 'accounts');
  await expect(code).toHaveValue('12345');
  await expect(code).toHaveAttribute('data-mark', 'same');

  // Export: the search survives the pane showing another screen, and the session is asked again on the way back.
  await select(page, 'right').selectOption({ label: 'ייצוא' });
  await page.getByRole('radio', { name: 'name-a2 (לא מחובר)', exact: true }).check();
  await page.getByRole('button', { name: "טען צ'אטים" }).click();
  await expect(page.locator('.chat-card', { hasText: 'Alpha News' })).toBeVisible();
  const search = screenIn(page, 'right', 'export').getByLabel('חיפוש');
  await search.fill('Alp');
  await search.evaluate((el) => { el.dataset.mark = 'same'; });
  await select(page, 'right').selectOption({ label: 'לוג' });
  await expect(page.locator('[data-screen="export"]')).toHaveCount(0);
  const before = asked;
  await select(page, 'right').selectOption({ label: 'ייצוא' });
  await expect(search).toHaveValue('Alp');
  await expect(search).toHaveAttribute('data-mark', 'same');
  await expect.poll(() => asked).toBeGreaterThan(before);
  await expect(code).toHaveValue('12345');
});

test('in one screen the rail changes only the active pane; the split comes back with the other pane as it was', async ({ page }) => {
  await open(page);
  await single(page).click();
  await page.locator('nav.tabs a', { hasText: 'ייצוא' }).click();
  await expectSingle(page, 'export');
  await both(page).click();
  await expectSplit(page, 'export', 'log');

  // The tab the other pane shows: both name it while one screen shows; the swap happens on the way back.
  await single(page).click();
  await page.locator('nav.tabs a', { hasText: 'לוג' }).click();
  await expectSingle(page, 'log');
  await page.locator('nav.tabs a', { hasText: 'משימות' }).click();
  await expectSingle(page, 'jobs');
  await both(page).click();
  await expectSplit(page, 'jobs', 'log');

  await single(page).click();
  await page.locator('nav.tabs a', { hasText: 'לוג' }).click();
  await both(page).click();
  await expectSplit(page, 'log', 'jobs');
  expect(JSON.parse(await page.evaluate(() => localStorage.getItem('tgb.layout')))).toMatchObject({ right: 'log', left: 'jobs' });
});

test('a drag stops when the window gets narrower than 1200 px; the ratio is not changed', async ({ page }) => {
  await open(page);
  const d = await box(divider(page));
  await page.mouse.move(d.x + d.width / 2, d.y + d.height / 2);
  await page.mouse.down();
  await page.setViewportSize({ width: 1100, height: 1040 });
  await page.mouse.move(100, d.y + d.height / 2, { steps: 4 });
  await page.mouse.up();
  await page.setViewportSize({ width: 1920, height: 1040 });
  // Task 10c: the jobs are on the right, so the ratio shown is the automatic one (a third, or half from 4 jobs).
  const n = (await (await page.request.get('/api/state')).json()).jobs.length;
  const auto = n >= 4 ? 0.5 : 0.33;
  await expect(divider(page)).toHaveAttribute('aria-valuenow', String(Math.round(auto * 100)));
  expect(JSON.parse(await page.evaluate(() => localStorage.getItem('tgb.layout')) ?? '{}').ratio ?? 0.55).toBe(0.55);
  expect(JSON.parse(await page.evaluate(() => localStorage.getItem('tgb.layout')) ?? '{}').ratioJobs ?? null).toBeNull();
  const r = await box(pane(page, 'right'));
  const l = await box(pane(page, 'left'));
  expect(Math.abs(r.width / (r.width + l.width) - (n >= 4 ? 0.5 : 1 / 3))).toBeLessThan(0.01);
});

// ---------------------------------------------------------------- Task 10c: the jobs pane's width by the number of jobs
// Up to 3 jobs the jobs pane is a third of the room (one column of cards), from 4 half (two columns), on either side.
// The cards have a fixed width; `➕ הוסף משימה` sits under all of them. A divider moved by hand holds while the number
// of jobs stays the same.

/** The jobs pane's share for `n` jobs. */
const share = (n) => (n >= 4 ? 0.5 : 0.33);

/** The real state with `n` copies of the fixture's jobs (ids j1..jn), through a fake stream; `layout` saved first. */
async function openWithJobs(page, n, layout, { cards = n } = {}) {
  const real = await (await page.request.get('/api/state')).json();
  const base = real.jobs.length ? real.jobs : [{ id: 'x', name: 'job', source: '@a', target: '@b', mediaType: 'VIDEO_DOC', mode: 'ORDERED', accountIds: [], startFromId: 0, stripLinks: true, skipArabic: false, perMinuteLimit: 25, viaTakeout: false }];
  const jobs = Array.from({ length: n }, (_, i) => ({ ...base[i % base.length], id: `j${i + 1}` }));
  const snapshot = { ...real, jobs, statuses: {} };
  if (layout) {
    await page.addInitScript((value) => { try { localStorage.setItem('tgb.layout', value); } catch { /* none */ } }, JSON.stringify(layout));
  }
  await page.route('**/api/events', (route) => route.fulfill({
    status: 200,
    headers: { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache' },
    body: `retry: 600000\nevent: snapshot\ndata: ${JSON.stringify(snapshot)}\n\n`,
  }));
  await open(page);
  await expect(page.locator('.job-card')).toHaveCount(cards);
}

/** The jobs pane's share of the two panes' width. */
async function jobsShareOf(page, side) {
  const r = (await box(pane(page, 'right'))).width;
  const l = (await box(pane(page, 'left'))).width;
  return (side === 'right' ? r : l) / (r + l);
}

const columns = async (page, side) => (await screenIn(page, side, 'jobs').locator('.jobs')
  .evaluate((el) => getComputedStyle(el).gridTemplateColumns)).split(' ').length;

/** Every card in its own place, `cols` columns, and the add button under all the cards. */
async function expectCards(page, side, n, cols) {
  expect(await columns(page, side)).toBe(cols);
  const cards = [];
  for (const c of await screenIn(page, side, 'jobs').locator('.job-card').all()) cards.push(await box(c));
  expect(cards).toHaveLength(n);
  for (const b of cards) {
    expect(b.width).toBeGreaterThanOrEqual(319);
    expect(b.width).toBeLessThanOrEqual(441);
  }
  expect(new Set(cards.map((b) => Math.round(b.x))).size).toBe(Math.min(cols, n));
  const add = await box(screenIn(page, side, 'jobs').getByRole('button', { name: '➕ הוסף משימה' }));
  for (const b of cards) expect(add.y).toBeGreaterThanOrEqual(b.y + b.height);
  expect(add.width).toBeLessThanOrEqual(441);
  return cards;
}

test('2 jobs on the right: the jobs pane is a third, one column of cards, the add button under the last', async ({ page }) => {
  await openWithJobs(page, 2);
  await expectSplit(page, 'jobs', 'log');
  expect(Math.abs(await jobsShareOf(page, 'right') - 1 / 3)).toBeLessThan(0.01);
  await expect(divider(page)).toHaveAttribute('aria-valuenow', '33');
  const [a, b] = await expectCards(page, 'right', 2, 1);
  expect(b.y).toBeGreaterThanOrEqual(a.y + a.height);
  // Nothing saved: the automatic ratio is not stored as one set by hand.
  expect(await page.evaluate(() => localStorage.getItem('tgb.layout'))).toBeNull();
});

test('4 jobs on the right: half the room, two columns', async ({ page }) => {
  await openWithJobs(page, 4);
  expect(Math.abs(await jobsShareOf(page, 'right') - 0.5)).toBeLessThan(0.01);
  await expect(divider(page)).toHaveAttribute('aria-valuenow', '50');
  const cards = await expectCards(page, 'right', 4, 2);
  // Two cards share each row.
  expect(new Set(cards.map((b) => Math.round(b.y))).size).toBe(2);
});

for (const [n, cols] of [[1, 1], [3, 1], [4, 2], [7, 2]]) {
  test(`${n} jobs on the left: the same share on the other side, ${cols} column(s)`, async ({ page }) => {
    await openWithJobs(page, n, { mode: 'split', right: 'log', left: 'jobs', active: 'left', ratio: 0.55 });
    await expectSplit(page, 'log', 'jobs');
    expect(Math.abs(await jobsShareOf(page, 'left') - (n >= 4 ? 0.5 : 1 / 3))).toBeLessThan(0.01);
    await expect(divider(page)).toHaveAttribute('aria-valuenow', String(Math.round((1 - share(n)) * 100)));
    await expectCards(page, 'left', n, cols);
  });
}

test('without the jobs in view the saved ratio holds, as before', async ({ page }) => {
  await openWithJobs(page, 0, { mode: 'split', right: 'accounts', left: 'export', active: 'right', ratio: 0.42 });
  await expectSplit(page, 'accounts', 'export');
  expect(Math.abs(await jobsShareOf(page, 'right') - 0.42)).toBeLessThan(0.01);
});

test('a drag holds while the number of jobs stays; adding a job, or deleting one, brings back the automatic share', async ({ page }) => {
  const count = async () => (await (await page.request.get('/api/state')).json()).jobs.length;
  const n0 = await count();
  await open(page);
  await expectSplit(page, 'jobs', 'log');
  expect(Math.abs(await jobsShareOf(page, 'right') - share(n0))).toBeLessThan(0.01);
  const d = await box(divider(page));
  await page.mouse.move(d.x + d.width / 2, d.y + d.height / 2);
  await page.mouse.down();
  await page.mouse.move(d.x + d.width / 2 - 150, d.y + d.height / 2, { steps: 5 });
  await page.mouse.up();
  const dragged = await jobsShareOf(page, 'right');
  expect(dragged).toBeGreaterThan(share(n0) + 0.05);
  expect(JSON.parse(await page.evaluate(() => localStorage.getItem('tgb.layout')))).toMatchObject({ ratioJobs: n0 });
  await page.reload();
  await expect(page.locator('#title')).toContainText('TG Backup  v');
  await expect(page.locator('.job-card')).toHaveCount(n0);
  expect(Math.abs(await jobsShareOf(page, 'right') - dragged)).toBeLessThan(0.01);

  const tag = `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 6)}`;
  const acc = await page.request.post('/api/accounts', { data: { name: `acc-10c-${tag}`, apiId: 1, apiHash: '0'.repeat(32), phone: '+10000000000' } });
  expect(acc.ok(), await acc.text()).toBeTruthy();
  const accId = (await (await page.request.get('/api/state')).json()).accounts.find((a) => a.name === `acc-10c-${tag}`).id;
  let jobId = null;
  try {
    const res = await page.request.post('/api/jobs', { data: { name: `job-10c-${tag}`, source: '-1001000000000', target: '-1002000000000', accountIds: [accId] } });
    expect(res.ok(), await res.text()).toBeTruthy();
    jobId = (await (await page.request.get('/api/state')).json()).jobs.find((j) => j.name === `job-10c-${tag}`).id;
    await expect(page.locator('.job-card')).toHaveCount(n0 + 1);
    await expect(divider(page)).toHaveAttribute('aria-valuenow', String(Math.round(share(n0 + 1) * 100)));
    expect(Math.abs(await jobsShareOf(page, 'right') - (share(n0 + 1) === 0.5 ? 0.5 : 1 / 3))).toBeLessThan(0.01);
    expect(JSON.parse(await page.evaluate(() => localStorage.getItem('tgb.layout')))).toMatchObject({ ratioJobs: null });

    // Set by hand again, then a job deleted: automatic again.
    await divider(page).focus();
    await page.keyboard.press('ArrowLeft');
    expect(JSON.parse(await page.evaluate(() => localStorage.getItem('tgb.layout')))).toMatchObject({ ratioJobs: n0 + 1 });
    const v = Number(await divider(page).getAttribute('aria-valuenow'));
    expect(v).toBe(Math.round(share(n0 + 1) * 100) + 5);
    expect((await page.request.delete(`/api/jobs/${jobId}`)).ok()).toBeTruthy();
    jobId = null;
    await expect(page.locator('.job-card')).toHaveCount(n0);
    await expect(divider(page)).toHaveAttribute('aria-valuenow', String(Math.round(share(n0) * 100)));
    expect(Math.abs(await jobsShareOf(page, 'right') - (share(n0) === 0.5 ? 0.5 : 1 / 3))).toBeLessThan(0.01);
    expect(JSON.parse(await page.evaluate(() => localStorage.getItem('tgb.layout')))).toMatchObject({ ratioJobs: null });
  } finally {
    if (jobId) await page.request.delete(`/api/jobs/${jobId}`);
    await page.request.delete(`/api/accounts/${accId}`);
  }
});

for (const n of [2, 4]) {
  test(`1200x900 with ${n} jobs: the cards fit the jobs pane, the summary and the all-jobs buttons wrap, no sideways scroll`, async ({ page }) => {
    await page.setViewportSize({ width: 1200, height: 900 });
    await openWithJobs(page, n);
    await expectSplit(page, 'jobs', 'log');
    expect(Math.abs(await jobsShareOf(page, 'right') - (n >= 4 ? 0.5 : 1 / 3))).toBeLessThan(0.01);
    const r = await page.evaluate(() => {
      const body = document.querySelector('.pane[data-pane="right"] .pane-body');
      const b = body.getBoundingClientRect();
      const out = [];
      for (const el of body.querySelectorAll('*')) {
        const e = el.getBoundingClientRect();
        if (e.width && (e.left < b.left - 1 || e.right > b.right + 1)) out.push(`${el.tagName}.${el.className}`);
      }
      return { out: out.slice(0, 10), scroll: body.scrollWidth, client: body.clientWidth, doc: document.documentElement.scrollWidth };
    });
    expect(r.out).toEqual([]);
    expect(r.scroll).toBeLessThanOrEqual(r.client);
    expect(r.doc).toBeLessThanOrEqual(1200);
    expect(await columns(page, 'right')).toBe(1);
    // The all-jobs buttons do not fit one line in a third of 1200 px: they wrap.
    if (n === 2) {
      const tops = new Set();
      for (const b of await page.locator('.jobs-header button').all()) tops.add(Math.round((await box(b)).y));
      expect(tops.size).toBeGreaterThan(1);
    }
  });
}

test('one screen: the add button is on its own row under all the job cards', async ({ page }) => {
  await openWithJobs(page, 4, { mode: 'single', right: 'jobs', left: 'log', active: 'right', ratio: 0.55 });
  await expectSingle(page, 'jobs');
  const cards = [];
  for (const c of await page.locator('.job-card').all()) cards.push(await box(c));
  const add = await box(page.getByRole('button', { name: '➕ הוסף משימה' }));
  for (const b of cards) expect(add.y).toBeGreaterThanOrEqual(b.y + b.height);
});

test('one screen with 2 jobs: the add button does not take the free cell next to the last card', async ({ page }) => {
  await openWithJobs(page, 2, { mode: 'single', right: 'jobs', left: 'log', active: 'right', ratio: 0.55 });
  await expectSingle(page, 'jobs');
  const cards = [];
  for (const c of await page.locator('.job-card').all()) cards.push(await box(c));
  const add = await box(page.getByRole('button', { name: '➕ הוסף משימה' }));
  for (const b of cards) expect(add.y).toBeGreaterThanOrEqual(b.y + b.height);
});

// ---------------------------------------------------------------- final wave: the share set by hand is the jobs'
const saved = async (page) => JSON.parse(await page.evaluate(() => localStorage.getItem('tgb.layout')) ?? 'null');

/** A drag of the divider by `dx` px (negative: to the left, the right pane wider). */
async function drag(page, dx) {
  const d = await box(divider(page));
  await page.mouse.move(d.x + d.width / 2, d.y + d.height / 2);
  await page.mouse.down();
  await page.mouse.move(d.x + d.width / 2 + dx, d.y + d.height / 2, { steps: 5 });
  await page.mouse.up();
}

test('a drag sets the jobs pane’s width; moving the jobs to the other side keeps that width', async ({ page }) => {
  await openWithJobs(page, 2);
  await drag(page, -200);
  const dragged = await jobsShareOf(page, 'right');
  expect(dragged).toBeGreaterThan(0.33 + 0.08);
  const s = await saved(page);
  expect(s.ratioJobs).toBe(2);
  expect(Math.abs(s.jobsRatio - dragged)).toBeLessThan(0.01);
  expect(s.ratio).toBe(0.55);
  // The jobs to the left pane (a swap): the same width there.
  await select(page, 'left').selectOption({ label: 'משימות' });
  await expectSplit(page, 'log', 'jobs');
  expect(Math.abs(await jobsShareOf(page, 'left') - dragged)).toBeLessThan(0.01);
  // The arrows on that side change the jobs share too, and it comes back with them to the right.
  await divider(page).focus();
  await page.keyboard.press('ArrowRight');
  const moved = await jobsShareOf(page, 'left');
  expect(Math.abs(moved - (dragged + 0.05))).toBeLessThan(0.015);
  await select(page, 'right').selectOption({ label: 'משימות' });
  await expectSplit(page, 'jobs', 'log');
  expect(Math.abs(await jobsShareOf(page, 'right') - moved)).toBeLessThan(0.01);
});

test('a Task 10c tgb.layout loads: its ratio set with the jobs on the left stays the jobs’ width', async ({ page }) => {
  await openWithJobs(page, 2, { mode: 'split', right: 'log', left: 'jobs', active: 'left', ratio: 0.6, ratioJobs: 2 });
  await expectSplit(page, 'log', 'jobs');
  await expect(divider(page)).toHaveAttribute('aria-valuenow', '60');
  await select(page, 'right').selectOption({ label: 'משימות' });
  expect(Math.abs(await jobsShareOf(page, 'right') - 0.4)).toBeLessThan(0.01);
});

test('a click on the divider without a move saves nothing', async ({ page }) => {
  await openWithJobs(page, 2, { mode: 'split', right: 'jobs', left: 'log', active: 'right', ratio: 0.42 });
  const d = await box(divider(page));
  await page.mouse.move(d.x + d.width / 2, d.y + d.height / 2);
  await page.mouse.down();
  await page.mouse.up();
  await expect(divider(page)).toHaveAttribute('aria-valuenow', '33');
  const s = await saved(page);
  expect(s.ratio).toBe(0.42);
  expect(s.ratioJobs ?? null).toBeNull();
  expect(s.jobsRatio ?? null).toBeNull();
});

test('a drag without the jobs in view sets that pair’s ratio only; the jobs share is not touched', async ({ page }) => {
  await openWithJobs(page, 2, { mode: 'split', right: 'accounts', left: 'export', active: 'right', ratio: 0.42, jobsRatio: 0.4, ratioJobs: 2 }, { cards: 0 });
  await expectSplit(page, 'accounts', 'export');
  await drag(page, -150);
  const s = await saved(page);
  expect(s.ratio).toBeGreaterThan(0.42 + 0.05);
  expect(s.jobsRatio).toBe(0.4);
  expect(s.ratioJobs).toBe(2);
  await select(page, 'left').selectOption({ label: 'משימות' });
  expect(Math.abs(await jobsShareOf(page, 'left') - 0.4)).toBeLessThan(0.01);
});

test('pointercancel ends a drag without saving; a second pointerdown does not take over a drag', async ({ page }) => {
  await openWithJobs(page, 2, { mode: 'split', right: 'jobs', left: 'log', active: 'right', ratio: 0.42 });
  const d = await box(divider(page));
  const y = d.y + d.height / 2;
  await page.mouse.move(d.x + d.width / 2, y);
  await page.mouse.down();
  await page.mouse.move(d.x + d.width / 2 - 150, y, { steps: 5 });
  await divider(page).dispatchEvent('pointercancel', { pointerId: 1, isPrimary: true });
  await page.mouse.up();
  await expect(divider(page)).toHaveAttribute('aria-valuenow', '33');
  expect(Math.abs(await jobsShareOf(page, 'right') - 1 / 3)).toBeLessThan(0.01);
  expect((await saved(page)).ratioJobs ?? null).toBeNull();

  // A second pointer pressed mid-drag: the first drag goes on and is the one saved.
  await page.mouse.move(d.x + d.width / 2, y);
  await page.mouse.down();
  await page.mouse.move(d.x + d.width / 2 - 150, y, { steps: 5 });
  const during = Number(await divider(page).getAttribute('aria-valuenow'));
  expect(during).toBeGreaterThan(33 + 5);
  await divider(page).dispatchEvent('pointerdown', { pointerId: 77, button: 0, isPrimary: false });
  // A resize mid-drag (still a split) keeps the dragged width.
  await page.setViewportSize({ width: 1900, height: 1040 });
  await expect(divider(page)).toHaveAttribute('aria-valuenow', String(during));
  await page.mouse.up();
  const s = await saved(page);
  expect(s.ratioJobs).toBe(2);
  expect(Math.round(s.jobsRatio * 100)).toBe(during);
});

test('the number of jobs last seen gives the first paint its share, before the snapshot comes', async ({ page }) => {
  await openWithJobs(page, 4);
  expect((await saved(page)).jobsSeen).toBe(4);
  // The next load: the snapshot held back; the jobs pane is already half.
  let release;
  const held = new Promise((r) => { release = r; });
  await page.unroute('**/api/events');
  const real = await (await page.request.get('/api/state')).json();
  const base = real.jobs.length ? real.jobs : [];
  await page.route('**/api/events', async (route) => {
    await held;
    const jobs = Array.from({ length: 4 }, (_, i) => ({ ...(base[0] ?? {}), id: `j${i + 1}` }));
    await route.fulfill({
      status: 200,
      headers: { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache' },
      body: `retry: 600000\nevent: snapshot\ndata: ${JSON.stringify({ ...real, jobs, statuses: {} })}\n\n`,
    }).catch(() => {});
  });
  await page.reload({ waitUntil: 'domcontentloaded' });
  await expect(divider(page)).toBeVisible();
  await expect(divider(page)).toHaveAttribute('aria-valuenow', '50');
  release();
});
