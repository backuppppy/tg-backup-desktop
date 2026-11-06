// Desktop layout (Task 10a): from 900 px wide a navigation rail on the right replaces the bottom bar, the content is
// at most 1200 px and centred, cards sit in a grid, dialogs are centred at min(560px, 92vw), and pointer and keyboard
// get hover, focus rings, Tab, Enter and Esc. Under 900 px (the phone project) the layout stays as it was.
// Runs in the `window` (1100x750) and `wide` (1920x1040) projects; the phone check runs in `phone` only.
import { test, expect } from '@playwright/test';

const uid = () => `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 6)}`;
const TABS = ['משימות', 'ייצוא', 'חשבונות', 'לוג', 'הגדרות'];
const PRIMARY = 'rgb(90, 169, 240)';
const INTRO = 'ייצוא ערוץ, קבוצה או צ\'אט בדיוק כמו "Export chat history" של טלגרם דסקטופ (HTML / JSON), דרך Takeout session.';

async function state(request) {
  return (await request.get('/api/state')).json();
}

async function addAccount(request, name) {
  const res = await request.post('/api/accounts', { data: { name, apiId: 1, apiHash: '0'.repeat(32), phone: '+10000000000' } });
  expect(res.ok(), await res.text()).toBeTruthy();
  return (await state(request)).accounts.find((a) => a.name === name).id;
}

async function addJob(request, job) {
  const res = await request.post('/api/jobs', { data: { source: '-1001000000000', target: '-1002000000000', ...job } });
  expect(res.ok(), await res.text()).toBeTruthy();
  return (await state(request)).jobs.find((j) => j.name === job.name).id;
}

/** 3 dummy accounts and 3 jobs (one account each; a job needs one), as the jobs and accounts specs make them. */
async function seed(request) {
  const tag = uid();
  const accounts = [];
  for (const k of ['a', 'b', 'c']) accounts.push(await addAccount(request, `acc-${k}-${tag}`));
  const jobs = [];
  for (const [i, acc] of accounts.entries()) jobs.push(await addJob(request, { name: `job-${i + 1}-${tag}`, accountIds: [acc] }));
  return {
    tag,
    async cleanup() {
      for (const id of jobs) await request.delete(`/api/jobs/${id}`);
      for (const id of accounts) await request.delete(`/api/accounts/${id}`);
    },
  };
}

async function open(page, hash) {
  await page.goto(`/${hash}`);
  await expect(page.locator('#title')).toContainText('TG Backup  v');
}

const box = (locator) => locator.boundingBox();
const viewport = (page) => page.viewportSize();

test.describe('desktop', () => {
  test.beforeEach(async ({ page }, info) => {
    test.skip(info.project.name === 'phone', 'desktop widths only');
    // These checks are for one screen. From 1200 px the split view (Task 10b) is the default, so at 1920 the
    // one-screen layout is chosen first; at 1100 there is no split anyway.
    await page.addInitScript(() => localStorage.setItem('tgb.layout', '{"mode":"single"}'));
  });

  test('the nav rail sits on the right, taller than wide, the tabs top to bottom; no bottom bar', async ({ page }) => {
    await open(page, '#jobs');
    const vp = viewport(page);
    const nav = page.locator('nav.tabs');
    const b = await box(nav);
    expect(Math.abs(b.x + b.width - vp.width)).toBeLessThanOrEqual(2);
    expect(b.height).toBeGreaterThan(b.width);
    expect(b.width).toBeGreaterThanOrEqual(88);
    expect(b.width).toBeLessThanOrEqual(104);
    expect(b.y).toBeLessThanOrEqual(1);
    expect(b.height).toBeGreaterThanOrEqual(vp.height - 1);
    // Not a bottom bar: nothing full-width is pinned to the bottom.
    expect(b.width).toBeLessThan(vp.width / 2);
    const tabs = nav.locator('a');
    await expect(tabs).toHaveText(TABS);
    const ys = [];
    for (let i = 0; i < TABS.length; i++) ys.push((await box(tabs.nth(i))).y);
    for (let i = 1; i < ys.length; i++) expect(ys[i]).toBeGreaterThan(ys[i - 1]);
    // The active pill stays.
    const pill = nav.locator('a[aria-current="page"] .pill');
    expect(await pill.evaluate((el) => getComputedStyle(el).backgroundColor)).toBe('rgb(30, 44, 58)');
    // The rail stays put while the content scrolls.
    expect(await nav.evaluate((el) => getComputedStyle(el).position)).toBe('fixed');
  });

  test('the content is at most 1200 px, centred left of the rail, and never under it', async ({ page }) => {
    for (const hash of ['#jobs', '#export', '#accounts', '#log', '#settings']) {
      await open(page, hash);
      const vp = viewport(page);
      const main = await box(page.locator('main'));
      const rail = await box(page.locator('nav.tabs'));
      expect(main.width, hash).toBeLessThanOrEqual(1200);
      expect(main.x + main.width, hash).toBeLessThanOrEqual(rail.x + 1);
      // Centred in the space left of the rail.
      const left = main.x;
      const right = rail.x - (main.x + main.width);
      expect(Math.abs(left - right), hash).toBeLessThanOrEqual(2);
      const top = await box(page.locator('.topbar'));
      expect(top.x + top.width, hash).toBeLessThanOrEqual(rail.x + 1);
      // No horizontal scroll.
      expect(await page.evaluate(() => document.documentElement.scrollWidth), hash).toBeLessThanOrEqual(vp.width);
    }
  });

  test('job and account cards sit in a grid: two share a row, none wider than 700 px; add buttons fit the grid', async ({ page }) => {
    const s = await seed(page.request);
    try {
      await open(page, '#jobs');
      const cards = page.locator('.job-card');
      await expect(cards.filter({ hasText: s.tag })).toHaveCount(3);
      const jobBoxes = [];
      for (const c of await cards.all()) jobBoxes.push(await box(c));
      for (const b of jobBoxes) expect(b.width).toBeLessThanOrEqual(700);
      const tops = jobBoxes.map((b) => Math.round(b.y));
      expect(tops.some((t, i) => tops.indexOf(t) !== i)).toBe(true);
      const addJob = await box(page.getByRole('button', { name: '➕ הוסף משימה' }));
      expect(addJob.width).toBeLessThanOrEqual(700);
      // The all-jobs buttons stay on one line.
      const row = page.locator('.jobs-header button');
      const rowTops = new Set();
      for (const b of await row.all()) rowTops.add(Math.round((await box(b)).y));
      expect(rowTops.size).toBe(1);

      await open(page, '#accounts');
      const acc = page.locator('.account-card');
      await expect(acc.filter({ hasText: s.tag })).toHaveCount(3);
      const accTops = [];
      for (const c of await acc.all()) {
        const b = await box(c);
        expect(b.width).toBeLessThanOrEqual(700);
        accTops.push(Math.round(b.y));
      }
      expect(accTops.some((t, i) => accTops.indexOf(t) !== i)).toBe(true);
      const addAcc = await box(page.getByRole('button', { name: '➕ הוסף חשבון' }));
      expect(addAcc.width).toBeLessThanOrEqual(700);
    } finally {
      await s.cleanup();
    }
  });

  test('a dialog is at most 560 px wide and centred within 10 px', async ({ page }) => {
    await open(page, '#jobs');
    await page.getByRole('button', { name: '➕ הוסף משימה' }).click();
    const dialog = page.getByRole('dialog', { name: 'משימה חדשה' });
    await expect(dialog).toBeVisible();
    const vp = viewport(page);
    const b = await box(dialog);
    expect(b.width).toBeLessThanOrEqual(560);
    expect(b.width).toBeGreaterThanOrEqual(Math.min(560, vp.width * 0.92) - 1);
    expect(Math.abs(b.x + b.width / 2 - vp.width / 2)).toBeLessThanOrEqual(10);
    expect(Math.abs(b.y + b.height / 2 - vp.height / 2)).toBeLessThanOrEqual(10);
    // The buttons stay in view while the fields scroll.
    const actions = await box(dialog.locator('.actions'));
    expect(actions.y + actions.height).toBeLessThanOrEqual(vp.height);
  });

  test('hover changes the ▶ הפעל background; Tab focus shows a primary ring; controls are at least 40 px tall', async ({ page }) => {
    const s = await seed(page.request);
    try {
      await open(page, '#jobs');
      const start = page.locator('.job-card', { hasText: `job-3-${s.tag}` }).getByRole('button', { name: '▶ הפעל' });
      await expect(start).toBeVisible();
      const bg = () => start.evaluate((el) => { const c = getComputedStyle(el); return `${c.backgroundColor} ${c.backgroundImage}`; });
      await page.mouse.move(1, 1);
      const before = await bg();
      await start.hover();
      await expect.poll(bg).not.toBe(before);
      expect(await start.evaluate((el) => getComputedStyle(el).cursor)).toBe('pointer');

      // Tab reaches the button. A click on the card's title (not focusable) sets where Tab starts, so the
      // jobs other tests left on the shared server do not matter.
      await page.mouse.move(1, 1);
      await page.locator('.job-card', { hasText: `job-3-${s.tag}` }).locator('.job-title').click();
      let found = false;
      for (let i = 0; i < 10 && !found; i++) {
        await page.keyboard.press('Tab');
        found = await start.evaluate((el) => el === document.activeElement);
      }
      expect(found).toBe(true);
      const ring = await start.evaluate((el) => {
        const c = getComputedStyle(el);
        return { style: c.outlineStyle, width: parseFloat(c.outlineWidth), color: c.outlineColor };
      });
      expect(ring.style).toBe('solid');
      expect(ring.width).toBeGreaterThanOrEqual(2);
      expect(ring.color).toBe(PRIMARY);

      // A nav item takes the ring too.
      const navLink = page.locator('nav.tabs a', { hasText: 'לוג' });
      await navLink.focus();
      await page.keyboard.press('Shift+Tab');
      await page.keyboard.press('Tab');
      expect(await navLink.evaluate((el) => getComputedStyle(el).outlineColor)).toBe(PRIMARY);

      for (const b of await page.locator('main button:visible').all()) {
        expect((await box(b)).height, await b.textContent()).toBeGreaterThanOrEqual(40);
      }
      expect(parseFloat(await page.evaluate(() => getComputedStyle(document.body).fontSize))).toBeGreaterThanOrEqual(15);
    } finally {
      await s.cleanup();
    }
  });

  test('JobDialog: Esc cancels, Enter saves when שמור is enabled, Tab stays inside the dialog', async ({ page }) => {
    const acc = `acc-${uid()}`;
    const name = `job-key-${uid()}`;
    const accId = await addAccount(page.request, acc);
    try {
      await open(page, '#jobs');
      const add = page.getByRole('button', { name: '➕ הוסף משימה' });
      await add.click();
      let dialog = page.getByRole('dialog', { name: 'משימה חדשה' });
      await expect(dialog).toBeVisible();
      await page.keyboard.press('Escape');
      await expect(dialog).toHaveCount(0);

      await add.click();
      dialog = page.getByRole('dialog', { name: 'משימה חדשה' });
      const nameField = dialog.getByLabel('שם המשימה');
      await nameField.fill(name);
      // שמור is disabled: Enter does nothing.
      await nameField.press('Enter');
      await expect(dialog).toBeVisible();
      await dialog.getByLabel('מקור: מזהה (עם או בלי ‎-100) / @username / קישור').fill('@k_src');
      await dialog.getByLabel('יעד: מזהה (עם או בלי ‎-100) / @username / קישור').fill('@k_dst');
      await dialog.getByRole('checkbox', { name: acc, exact: true }).check();
      await expect(dialog.getByRole('button', { name: 'שמור' })).toBeEnabled();

      // Tab never leaves the dialog (forward and back).
      for (let i = 0; i < 40; i++) {
        await page.keyboard.press('Tab');
        expect(await dialog.evaluate((el) => el.contains(document.activeElement))).toBe(true);
      }
      for (let i = 0; i < 5; i++) {
        await page.keyboard.press('Shift+Tab');
        expect(await dialog.evaluate((el) => el.contains(document.activeElement))).toBe(true);
      }

      await nameField.focus();
      await page.keyboard.press('Enter');
      await expect(dialog).toHaveCount(0);
      await expect(page.locator('.job-title', { hasText: name })).toBeVisible();
      const job = (await state(page.request)).jobs.find((j) => j.name === name);
      expect(job.source).toBe('@k_src');
      await page.request.delete(`/api/jobs/${job.id}`);
    } finally {
      await page.request.delete(`/api/accounts/${accId}`);
    }
  });

  test('export: setup on the right and options on the left; the progress panel at most 720 px, centred', async ({ page }) => {
    const name = `exp-${uid()}`;
    const id = await addAccount(page.request, name);
    try {
      await page.route(/\/api\/export\/[^/]+\/session$/, (r) => r.fulfill({ status: 200, contentType: 'application/json', body: '{"loggedIn":true}' }));
      await page.route(/\/api\/export\/[^/]+\/chats$/, (r) => r.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify([{ peerId: 11, title: 'Alpha News', username: 'alphanews', type: 'PublicChannel', isForum: false, archived: false }]),
      }));
      await open(page, '#export');
      await expect(page.getByText(INTRO, { exact: true })).toBeVisible();
      await page.getByRole('radio', { name: `${name} (לא מחובר)`, exact: true }).check();
      await page.getByRole('button', { name: "טען צ'אטים" }).click();
      await page.locator('.chat-card', { hasText: 'Alpha News' }).click();
      const options = page.locator('.export-options');
      await expect(options).toBeVisible();
      const intro = await box(page.locator('.export-intro'));
      const opt = await box(options);
      // Two columns: the setup on the right (RTL start), the options on the left, side by side.
      expect(opt.x + opt.width).toBeLessThanOrEqual(intro.x + 1);
      expect(Math.abs(opt.y - intro.y)).toBeLessThanOrEqual(2);

      const res = await page.request.post('/api/dev/export-state', {
        data: JSON.stringify({ accountId: 'a1', title: 'Alpha News', state: { type: 'Idle' } }),
        headers: { 'Content-Type': 'application/json' },
      });
      expect(res.ok()).toBeTruthy();
      const panel = page.locator('.export-panel');
      await expect(panel).toBeVisible();
      const p = await box(panel);
      const main = await box(page.locator('main'));
      expect(p.width).toBeLessThanOrEqual(720);
      expect(Math.abs(p.x + p.width / 2 - (main.x + main.width / 2))).toBeLessThanOrEqual(2);
    } finally {
      await page.request.post('/api/dev/export-state', { data: 'null', headers: { 'Content-Type': 'application/json' } });
      await page.request.delete(`/api/accounts/${id}`);
    }
  });

  test('log spans the content width in monospace; settings cards are at most 720 px', async ({ page }) => {
    await open(page, '#log');
    const main = await box(page.locator('main'));
    const list = await box(page.locator('.log-list'));
    // The whole content box of main (its width less its padding).
    const inner = await page.locator('main').evaluate((el) => {
      const c = getComputedStyle(el);
      return el.clientWidth - parseFloat(c.paddingLeft) - parseFloat(c.paddingRight);
    });
    expect(Math.abs(list.width - inner)).toBeLessThanOrEqual(1);
    expect(await page.locator('.log-list').evaluate((el) => getComputedStyle(el).fontFamily)).toMatch(/mono/i);
    await open(page, '#settings');
    for (const c of await page.locator('[data-screen="settings"] .card').all()) {
      const b = await box(c);
      expect(b.width).toBeLessThanOrEqual(720);
      expect(Math.abs(b.x + b.width / 2 - (main.x + main.width / 2))).toBeLessThanOrEqual(2);
    }
  });
});

test('phone: the bottom bar stays and there is no rail', async ({ page }, info) => {
  test.skip(info.project.name !== 'phone', 'phone width only');
  await open(page, '#jobs');
  const vp = viewport(page);
  const b = await box(page.locator('nav.tabs'));
  expect(b.width).toBeGreaterThanOrEqual(vp.width - 1);
  expect(b.height).toBe(80);
  expect(Math.abs(b.y + b.height - vp.height)).toBeLessThanOrEqual(1);
  expect(await page.locator('nav.tabs').evaluate((el) => getComputedStyle(el).flexDirection)).toBe('row');
  const main = await box(page.locator('main'));
  expect(main.x).toBeLessThanOrEqual(1);
  expect(main.width).toBeGreaterThanOrEqual(vp.width - 1);
});
