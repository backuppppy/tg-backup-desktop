// Log screen (docs/ui-inventory.md §2d). The event stream is faked (a stand-in EventSource the test pushes events
// into), so the lines are known and a later event can arrive after the screen was drawn.
import { test, expect } from '@playwright/test';

const entry = (n) => ({ time: '10:00:00', level: 'info', msg: `שורה ${n}` });

/** Loads #log with `count` lines (numbered 1..count, oldest first); `push(event, data)` sends one more event. */
async function open(page, count) {
  const real = await (await page.request.get('/api/state')).json();
  await page.addInitScript(() => {
    window.__sources = [];
    window.EventSource = class {
      constructor() { this.l = {}; this.readyState = 1; window.__sources.push(this); }
      addEventListener(name, fn) { (this.l[name] ??= []).push(fn); }
      close() { this.readyState = 2; }
    };
    window.__push = (name, data) => window.__sources.at(-1).l[name]?.forEach((fn) => fn({ data: JSON.stringify(data) }));
  });
  await page.goto('/#log');
  const log = Array.from({ length: count }, (_, i) => entry(i + 1));
  await page.evaluate((s) => window.__push('snapshot', s), { ...real, log });
  await expect(page.locator('.log-count')).toHaveText(`${count} שורות`);
  return (name, data) => page.evaluate(([n, d]) => window.__push(n, d), [name, data]);
}

const mark = (page, nth) => page.locator('.log-line').nth(nth).evaluate((el) => { (window.__marked ??= []).push(el); });
const connected = (page, i) => page.evaluate((i) => window.__marked[i].isConnected, i);

test('a new log line keeps the existing line nodes', async ({ page }) => {
  const push = await open(page, 3);
  await mark(page, 0);
  await push('log', { entries: [entry(4)] });
  await expect(page.locator('.log-count')).toHaveText('4 שורות');
  await expect(page.locator('.log-line')).toHaveCount(4);
  await expect(page.locator('.log-line').first()).toHaveText('10:00:00  שורה 4');
  await expect(page.locator('.log-line').last()).toHaveText('10:00:00  שורה 1');
  expect(await connected(page, 0)).toBe(true);
});

test('past 500 lines the oldest drop and the rest stay', async ({ page }) => {
  const push = await open(page, 500);
  await mark(page, 0);
  await push('log', { entries: [entry(501)] });
  await expect(page.locator('.log-line').first()).toHaveText('10:00:00  שורה 501');
  await expect(page.locator('.log-count')).toHaveText('500 שורות');
  await expect(page.locator('.log-line')).toHaveCount(500);
  await expect(page.locator('.log-line').last()).toHaveText('10:00:00  שורה 2');
  expect(await connected(page, 0)).toBe(true);
});

test('a cleared log empties the list', async ({ page }) => {
  const push = await open(page, 3);
  await push('log', { cleared: true, entries: [] });
  await expect(page.locator('.log-count')).toHaveText('0 שורות');
  await expect(page.locator('.log-line')).toHaveCount(0);
});

test('a log cleared and refilled shows only the new lines', async ({ page }) => {
  const push = await open(page, 3);
  await push('log', { cleared: true, entries: [entry(9)] });
  await expect(page.locator('.log-count')).toHaveText('1 שורות');
  await expect(page.locator('.log-line')).toHaveText(['10:00:00  שורה 9']);
});
