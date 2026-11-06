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
  expect(l.x).toBeLessThanOrEqual(1);
  // The right pane has 55% of the room.
  expect(Math.abs(r.width / (r.width + l.width) - 0.55)).toBeLessThan(0.01);
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
  const d = await box(divider(page));
  await page.mouse.move(d.x + d.width / 2, d.y + d.height / 2);
  await page.mouse.down();
  await page.mouse.move(d.x + d.width / 2 - 100, d.y + d.height / 2, { steps: 5 });
  await page.mouse.move(d.x + d.width / 2 - 200, d.y + d.height / 2, { steps: 5 });
  await page.mouse.up();
  const after = (await box(pane(page, 'right'))).width;
  expect(after).toBeGreaterThan(before + 150);
  expect(JSON.parse(await page.evaluate(() => localStorage.getItem('tgb.layout'))).ratio).toBeGreaterThan(0.6);
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
  expect(JSON.parse(await page.evaluate(() => localStorage.getItem('tgb.layout'))).ratio).toBe(0.7);
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
  // The card grid in the left pane (30%, about 540 px) is one column; in the right pane at least two.
  await select(page, 'left').selectOption({ label: 'משימות' });
  expect((await screenIn(page, 'left', 'jobs').locator('.jobs').evaluate((el) => getComputedStyle(el).gridTemplateColumns)).split(' ').length).toBe(1);
  await select(page, 'right').selectOption({ label: 'משימות' });
  expect((await screenIn(page, 'right', 'jobs').locator('.jobs').evaluate((el) => getComputedStyle(el).gridTemplateColumns)).split(' ').length).toBeGreaterThanOrEqual(2);
});
