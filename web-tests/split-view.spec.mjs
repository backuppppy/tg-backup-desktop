// Split view (Task 10b): from 1200 px wide two screens side by side, each chosen in its pane's <select>; `מסך אחד`
// / `שני מסכים` in the top bar; a divider (mouse and keyboard) sets the right pane's share. From 900 to 1199 px one
// screen with the rail, under 900 px the phone layout. The layout is kept in localStorage (tgb.layout).
// Runs in the `wide` project (1920x1040); the tests that need other sizes resize the viewport themselves.
import { test, expect } from '@playwright/test';
import { DESKTOP_QUERY } from '../desktop/src/main/resources/web/lib/layout.js';

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
  // Empty accounts show the how-to card instead of the note.
  await expect(pane(page, 'left').getByText(NOTE, { exact: true }).or(pane(page, 'left').getByRole('heading', { name: 'איך מקבלים API ID ו-API Hash' }))).toBeVisible();
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
  const { serverNow, ...real } = await (await page.request.get('/api/state')).json();
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
  // at 70% from the arrows, two by the pane's width whatever the number of jobs (the user's ruling, 2026-11-08), the
  // cards filling the pane.
  await select(page, 'left').selectOption({ label: 'משימות' });
  await expect(divider(page)).toHaveAttribute('aria-valuenow', String(Math.round((1 - auto) * 100)));
  expect((await screenIn(page, 'left', 'jobs').locator('.jobs').evaluate((el) => getComputedStyle(el).gridTemplateColumns)).split(' ').length).toBe(n >= 4 ? 2 : 1);
  await select(page, 'right').selectOption({ label: 'משימות' });
  await expect(divider(page)).toHaveAttribute('aria-valuenow', String(Math.round(auto * 100)));
  await divider(page).focus();
  for (let i = 0; i < 10; i++) await page.keyboard.press('ArrowLeft');
  await expect(divider(page)).toHaveAttribute('aria-valuenow', '70');
  expect((await screenIn(page, 'right', 'jobs').locator('.jobs').evaluate((el) => getComputedStyle(el).gridTemplateColumns)).split(' ').length).toBe(2);
  const room = await paneContent(page, 'right');
  const card = await box(screenIn(page, 'right', 'jobs').locator('.job-card').first());
  expect(Math.abs(card.width - (room - 12) / 2)).toBeLessThanOrEqual(2);
});

test('what is typed survives a screen leaving the view: 960 px and back, one screen and back, the Export search', async ({ page }) => {
  const { serverNow, ...real } = await (await page.request.get('/api/state')).json();
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
// Plan 3b, part C: the cards fill the pane (one column the pane's width, or two that share it, no empty room beside
// them); `➕ הוסף משימה` (the user's ruling, plan 3b) heads them all, the list's width. A divider moved by hand holds while the number of
// jobs stays the same.

/** The jobs pane's share for `n` jobs. */
const share = (n) => (n >= 4 ? 0.5 : 0.33);

/** The real state with `n` copies of the fixture's jobs (ids j1..jn), through a fake stream; `layout` saved first. */
async function openWithJobs(page, n, layout, { cards = n } = {}) {
  const { serverNow, ...real } = await (await page.request.get('/api/state')).json();
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

/** The width inside the pane's padding, the room the job list has. */
const paneContent = (page, side) => pane(page, side).locator('.pane-body').evaluate((el) => {
  const st = getComputedStyle(el);
  return el.clientWidth - parseFloat(st.paddingLeft) - parseFloat(st.paddingRight);
});

/**
 * Every card in its own place, `cols` columns that fill the pane: each card the pane's content width (one column) or
 * that less the 12 px gap, halved (two), within 2 px; the add button above all the cards (on a desktop it heads the
 * list since plan 3b; it used to sit under them), the list's width.
 */
async function expectCards(page, side, n, cols) {
  expect(await columns(page, side)).toBe(cols);
  const room = await paneContent(page, side);
  const cards = [];
  for (const c of await screenIn(page, side, 'jobs').locator('.job-card').all()) cards.push(await box(c));
  expect(cards).toHaveLength(n);
  const want = (room - 12 * (cols - 1)) / cols;
  for (const b of cards) expect(Math.abs(b.width - want), `card ${b.width} of ${room}`).toBeLessThanOrEqual(2);
  expect(new Set(cards.map((b) => Math.round(b.x))).size).toBe(Math.min(cols, n));
  const add = await box(screenIn(page, side, 'jobs').getByRole('button', { name: '➕ הוסף משימה' }));
  for (const b of cards) expect(b.y).toBeGreaterThanOrEqual(add.y + add.height);
  expect(Math.abs(add.width - room)).toBeLessThanOrEqual(2);
  return cards;
}

test('2 jobs on the right: the jobs pane is a third, one column of cards, the add button above the first', async ({ page }) => {
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

test('2 jobs, the divider moved to widen the jobs pane: two columns by the pane\'s width, back to one when narrowed', async ({ page }) => {
  await openWithJobs(page, 2);
  await expectCards(page, 'right', 2, 1);
  await divider(page).focus();
  for (let i = 0; i < 5; i += 1) await page.keyboard.press('ArrowLeft');
  await expect(divider(page)).toHaveAttribute('aria-valuenow', '58');
  const cards = await expectCards(page, 'right', 2, 2);
  // Side by side, one row.
  expect(new Set(cards.map((b) => Math.round(b.y))).size).toBe(1);
  for (let i = 0; i < 5; i += 1) await page.keyboard.press('ArrowRight');
  await expectCards(page, 'right', 2, 1);
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
    // Half of 1200 px holds no two cards side by side: one column, the pane's width.
    expect(await columns(page, 'right')).toBe(1);
    const room = await paneContent(page, 'right');
    for (const c of await page.locator('.job-card').all()) expect(Math.abs((await box(c)).width - room)).toBeLessThanOrEqual(2);
    // The all-jobs buttons do not fit one line in a third of 1200 px: they wrap.
    if (n === 2) {
      const tops = new Set();
      for (const b of await page.locator('.jobs-header button').all()) tops.add(Math.round((await box(b)).y));
      expect(tops.size).toBeGreaterThan(1);
    }
  });
}

test('12 jobs: two columns, the jobs pane scrolls on its own, no sideways scroll, the add button above the first card', async ({ page }) => {
  await openWithJobs(page, 12);
  await expectSplit(page, 'jobs', 'log');
  await expectCards(page, 'right', 12, 2);
  const body = pane(page, 'right').locator('.pane-body');
  const m = await body.evaluate((el) => ({ sh: el.scrollHeight, ch: el.clientHeight, sw: el.scrollWidth, cw: el.clientWidth }));
  expect(m.sh).toBeGreaterThan(m.ch);
  expect(m.sw).toBeLessThanOrEqual(m.cw);
  const doc = await page.evaluate(() => ({ sw: document.documentElement.scrollWidth, sh: document.documentElement.scrollHeight }));
  expect(doc.sw).toBeLessThanOrEqual(1920);
  expect(doc.sh).toBeLessThanOrEqual(1040);
  // The add button heads the list (plan 3b; it used to close it): in view before any scroll, right after the header.
  const add = screenIn(page, 'right', 'jobs').getByRole('button', { name: '➕ הוסף משימה' });
  await expect(add).toBeInViewport();
  expect(await screenIn(page, 'right', 'jobs').locator('.jobs-header + button.full + .job-card').count()).toBe(1);
  const first = await box(screenIn(page, 'right', 'jobs').locator('.job-card').first());
  expect(first.y).toBeGreaterThanOrEqual((await box(add)).y + (await box(add)).height);
  // The last card is down the scroll of the pane alone.
  await screenIn(page, 'right', 'jobs').locator('.job-card').last().scrollIntoViewIfNeeded();
  expect(await body.evaluate((el) => el.scrollTop)).toBeGreaterThan(0);
  await expect(add).not.toBeInViewport();
  // The rail and the log pane did not move with the jobs pane's scroll.
  await expect(page.locator('nav.tabs')).toBeInViewport();
  await expect(screenIn(page, 'left', 'log')).toBeInViewport();
});

test('half of 1920 px with 5 jobs: two columns that fill the pane, no room beside them', async ({ page }) => {
  await openWithJobs(page, 5);
  expect(Math.abs(await jobsShareOf(page, 'right') - 0.5)).toBeLessThan(0.01);
  const cards = await expectCards(page, 'right', 5, 2);
  // The two columns reach both edges of the pane's content: no room left beside them.
  const room = await paneContent(page, 'right');
  const xs = cards.map((b) => b.x);
  const rights = cards.map((b) => b.x + b.width);
  expect(Math.abs(Math.max(...rights) - Math.min(...xs) - room)).toBeLessThanOrEqual(2);
});

/** The job list's own content width (inside its padding), the room its cards share. */
const listContent = (page, side) => screenIn(page, side, 'jobs').locator('.jobs').evaluate((el) => {
  const st = getComputedStyle(el);
  return el.clientWidth - parseFloat(st.paddingLeft) - parseFloat(st.paddingRight);
});

// The window the user saw: Windows maximised, 1936x1040. A third of it held a narrow card and empty room beside it.
for (const n of [1, 2, 3, 4, 6]) {
  test(`1936x1040 with ${n} jobs: the cards fill the jobs pane (${n >= 4 ? 'two columns, half' : 'one column, a third'})`, async ({ page }) => {
    await page.setViewportSize({ width: 1936, height: 1040 });
    await openWithJobs(page, n);
    await expectSplit(page, 'jobs', 'log');
    expect(Math.abs(await jobsShareOf(page, 'right') - share(n))).toBeLessThan(0.01);
    const cols = n >= 4 ? 2 : 1;
    const cards = await expectCards(page, 'right', n, cols);
    const list = await listContent(page, 'right');
    expect(Math.abs(list - await paneContent(page, 'right'))).toBeLessThanOrEqual(2);
    for (const b of cards) expect(Math.abs(b.width - (list - 12 * (cols - 1)) / cols), `card ${b.width} of ${list}`).toBeLessThanOrEqual(2);
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(1936);
  });
}

// Unchanged by part C (measured before it): one screen on a desktop and the phone keep their card widths.
/** The visible job list's content width (inside its padding), the room the cards and the add button share. */
const visibleListRoom = (page) => page.locator('.jobs:visible').evaluate((el) => {
  const st = getComputedStyle(el);
  return el.clientWidth - parseFloat(st.paddingLeft) - parseFloat(st.paddingRight);
});

test('one screen at 1100x750 keeps its two columns of cards, computed from the pane', async ({ page }) => {
  await page.setViewportSize({ width: 1100, height: 750 });
  await openWithJobs(page, 4);
  const room = await visibleListRoom(page);
  // The grid: columns of at least 340 px with a 12 px gap; the add button 420 px at most.
  const cols = Math.floor((room + 12) / (340 + 12));
  expect(cols).toBe(2);
  const card = Math.round((room - 12 * (cols - 1)) / cols);
  const widths = [];
  for (const c of await page.locator('.job-card').all()) widths.push(Math.round((await box(c)).width));
  expect(widths).toEqual([card, card, card, card]);
  expect(await page.locator('.jobs:visible').evaluate((el) => getComputedStyle(el).gridTemplateColumns)).toBe(`${card}px ${card}px`);
  expect(Math.round((await box(page.getByRole('button', { name: '➕ הוסף משימה' }))).width)).toBe(Math.round(Math.min(room, 420)));
});

for (const n of [2, 12]) {
  test(`390x844 with ${n} jobs: the phone keeps its cards the list's width, one under another, the add button too`, async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 });
    await openWithJobs(page, n);
    const boxes = [];
    for (const c of await page.locator('.job-card').all()) boxes.push(await box(c));
    const room = Math.round(await visibleListRoom(page));
    expect(room).toBe(390 - 2 * 12);
    for (const b of boxes) {
      expect(Math.round(b.width)).toBe(room);
      expect(Math.round(b.x)).toBe(12);
    }
    for (let i = 1; i < boxes.length; i += 1) expect(boxes[i].y).toBeGreaterThanOrEqual(boxes[i - 1].y + boxes[i - 1].height);
    expect(await page.locator('.jobs:visible').evaluate((el) => getComputedStyle(el).display)).toBe('flex');
    expect(Math.round((await box(page.getByRole('button', { name: '➕ הוסף משימה' }))).width)).toBe(room);
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(390);
  });
}

// Plan 3b (the user's ruling): on a desktop the add button heads the list; these used to expect it under the cards.
test('one screen: the add button is on its own row above all the job cards', async ({ page }) => {
  await openWithJobs(page, 4, { mode: 'single', right: 'jobs', left: 'log', active: 'right', ratio: 0.55 });
  await expectSingle(page, 'jobs');
  const cards = [];
  for (const c of await page.locator('.job-card').all()) cards.push(await box(c));
  const add = await box(page.getByRole('button', { name: '➕ הוסף משימה' }));
  for (const b of cards) expect(b.y).toBeGreaterThanOrEqual(add.y + add.height);
});

test('one screen with 2 jobs: the add button does not take a cell next to a card', async ({ page }) => {
  await openWithJobs(page, 2, { mode: 'single', right: 'jobs', left: 'log', active: 'right', ratio: 0.55 });
  await expectSingle(page, 'jobs');
  const cards = [];
  for (const c of await page.locator('.job-card').all()) cards.push(await box(c));
  const add = await box(page.getByRole('button', { name: '➕ הוסף משימה' }));
  for (const b of cards) expect(b.y).toBeGreaterThanOrEqual(add.y + add.height);
});

// ---------------------------------------------------------------- plan 3b: `➕ הוסף משימה` heads the list on a desktop
// From 900 px (one screen and split) the button is in the DOM right after .jobs-header, so Tab reaches it before the
// first card; under 900 px it closes the list as before. The header itself (summary and all-jobs buttons) is unchanged.

const addButton = (scope) => scope.getByRole('button', { name: '➕ הוסף משימה' });

/** In `scope` the add button is right after the header and before the first card (top), or the list's last child. */
async function expectAddAt(scope, top) {
  const list = scope.locator('.jobs');
  if (top) {
    expect(await list.locator(':scope > .jobs-header + button.full').count()).toBe(1);
    expect(await list.evaluate((el) => {
      const kids = [...el.children];
      return kids.indexOf(el.querySelector(':scope > button.full')) < kids.indexOf(el.querySelector(':scope > .job-card'));
    })).toBe(true);
    const add = await box(addButton(scope));
    const first = await box(scope.locator('.job-card').first());
    expect(first.y).toBeGreaterThanOrEqual(add.y + add.height);
  } else {
    expect(await list.locator(':scope > :last-child').evaluate((el) => el.matches('button.full'))).toBe(true);
    const add = await box(addButton(scope));
    const last = await box(scope.locator('.job-card').last());
    expect(add.y).toBeGreaterThanOrEqual(last.y + last.height);
  }
}

/** The texts of the buttons Tab reaches from ▶ הפעל הכל, up to the first one inside a job card. */
async function tabOrder(page, scope) {
  await scope.getByRole('button', { name: '▶ הפעל הכל' }).focus();
  const seen = [];
  for (let i = 0; i < 12; i += 1) {
    await page.keyboard.press('Tab');
    const at = await page.evaluate(() => ({ text: document.activeElement?.textContent ?? '', inCard: !!document.activeElement?.closest('.job-card') }));
    seen.push(at.inCard ? `card:${at.text}` : at.text);
    if (at.inCard) break;
  }
  return seen;
}

for (const where of ['split', 'single']) {
  test(`${where === 'split' ? 'split' : 'one screen'} at 1920: the add button heads the list, Tab reaches it before the first card`, async ({ page }) => {
    await openWithJobs(page, 3, where === 'single' ? { mode: 'single', right: 'jobs', left: 'log', active: 'right', ratio: 0.55 } : undefined);
    const scope = where === 'split' ? screenIn(page, 'right', 'jobs') : page.locator('[data-screen="jobs"]');
    await expect(scope).toBeVisible();
    await expectAddAt(scope, true);
    const order = await tabOrder(page, scope);
    expect(order.indexOf('➕ הוסף משימה')).toBeGreaterThanOrEqual(0);
    expect(order.at(-1)).toMatch(/^card:/);
    expect(order.indexOf('➕ הוסף משימה')).toBe(order.length - 2);
    // The header is as before: the summary and the all-jobs buttons on the right, the buttons on one line.
    const head = await box(scope.locator('.jobs-header'));
    const summary = await box(scope.locator('.jobs-summary'));
    expect(Math.abs(summary.x + summary.width - (head.x + head.width))).toBeLessThanOrEqual(2);
  });
}

test('one screen at 1100x750 and the rail at 960: the add button heads the list; at 899 it closes it', async ({ page }) => {
  await page.setViewportSize({ width: 1100, height: 750 });
  await openWithJobs(page, 4);
  const scope = page.locator('[data-screen="jobs"]');
  await expectAddAt(scope, true);
  await page.setViewportSize({ width: 960, height: 1040 });
  await expectAddAt(scope, true);
  await page.setViewportSize({ width: 899, height: 1040 });
  await expect(scope.locator('.jobs > :last-child')).toHaveClass(/full/);
  await expectAddAt(scope, false);
});

test('the window to 390 and back: the add button closes the list on the phone and heads it again on the desktop', async ({ page }) => {
  await openWithJobs(page, 4);
  await expectAddAt(screenIn(page, 'right', 'jobs'), true);
  await page.setViewportSize({ width: 390, height: 844 });
  const scope = page.locator('[data-screen="jobs"]');
  await expect(scope.locator('.jobs > :last-child')).toHaveClass(/full/);
  await expectAddAt(scope, false);
  await page.setViewportSize({ width: 1920, height: 1040 });
  await expectSplit(page, 'jobs', 'log');
  await expect(screenIn(page, 'right', 'jobs').locator('.jobs > .jobs-header + button.full')).toHaveCount(1);
  await expectAddAt(screenIn(page, 'right', 'jobs'), true);
  // The focus on the add button survives the move.
  await addButton(screenIn(page, 'right', 'jobs')).focus();
  await page.setViewportSize({ width: 390, height: 844 });
  await expect(scope.locator('.jobs > :last-child')).toHaveClass(/full/);
  await expect(addButton(scope)).toBeFocused();
});

test('the 900 px listener is there only while the jobs screen is in view', async ({ page }) => {
  // The query string is the page's own (layout.js DESKTOP_QUERY); both the addEventListener and the old addListener form count.
  await page.addInitScript((query) => {
    const live = new Set();
    window.__mq = () => live.size;
    const proto = MediaQueryList.prototype;
    // addEventListener(type, fn) / addListener(fn): the change listener's function is the last argument.
    const track = (name, op) => {
      const orig = proto[name];
      proto[name] = function wrapped(...args) {
        if (this.media === query && (args.length === 1 || args[0] === 'change')) op(args[args.length === 1 ? 0 : 1]);
        return orig.apply(this, args);
      };
    };
    track('addEventListener', (f) => live.add(f));
    track('addListener', (f) => live.add(f));
    track('removeEventListener', (f) => live.delete(f));
    track('removeListener', (f) => live.delete(f));
  }, DESKTOP_QUERY);
  await openWithJobs(page, 2);
  await expect.poll(() => page.evaluate(() => window.__mq())).toBe(1);
  // The jobs leave both panes: the left shows Accounts, the right Log.
  await select(page, 'right').selectOption({ label: 'לוג' });
  await select(page, 'left').selectOption({ label: 'חשבונות' });
  await expect(page.locator('[data-screen="jobs"]')).toHaveCount(0);
  await expect.poll(() => page.evaluate(() => window.__mq())).toBe(0);
  await select(page, 'right').selectOption({ label: 'משימות' });
  await expect(screenIn(page, 'right', 'jobs')).toBeVisible();
  await expect.poll(() => page.evaluate(() => window.__mq())).toBe(1);
  await expectAddAt(screenIn(page, 'right', 'jobs'), true);
});

test('resizing within the desktop width keeps the focused card button', async ({ page }) => {
  await openWithJobs(page, 2);
  const edit = screenIn(page, 'right', 'jobs').locator('.job-card').first().getByRole('button', { name: 'ערוך' });
  await edit.focus();
  await edit.evaluate((el) => { window.__focused = el; });
  await page.setViewportSize({ width: 1500, height: 1040 });
  await expect(screenIn(page, 'right', 'jobs')).toBeVisible();
  expect(await page.evaluate(() => document.activeElement.isSameNode(window.__focused))).toBe(true);
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
  const { serverNow, ...real } = await (await page.request.get('/api/state')).json();
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
