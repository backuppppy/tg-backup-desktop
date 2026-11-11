// Phone width and long names (Task 10). The server is started with TGB_DEV_FIXTURE=long-names (global-setup does it
// by default): its 120-character Hebrew/English job name, `‎-100…` ids and long account name are read from the real
// state. The fixture's log lines are carried here as well, since shell.spec's `נקה` empties the server's log.
// On every tab, in every project (390, 1100, 1920): no horizontal scroll, no element wider than the viewport, the long
// names wrap or ellipsize inside their cards, and the navigation (the bottom bar, the rail) stays visible.
// At 1920 also the split view (jobs|log and accounts|export): nothing overflows its pane.
import { test, expect } from '@playwright/test';

const TABS = ['jobs', 'accounts', 'export', 'log', 'settings'];
const LRM = '\u200E';
const LONG_LOG = [
  { level: 'INFO', msg: `הועתקו 1500 הודעות מתוך ${LRM}-1001234567890 אל ${LRM}-1009876543210 — Copied 1500 messages, last id 987654 `.repeat(2) },
  { level: 'WARN', msg: `FLOOD_WAIT_120 בהעתקה מהערוץ ${LRM}-1001234567890, ממתין 120 שניות לפני הניסיון הבא — waiting before the next try` },
  { level: 'ERROR', msg: `C:\\Users\\משתמש\\AppData\\Local\\TGBackup\\exports\\${'Very_Long_Folder_Name_Without_Any_Spaces_'.repeat(3)}file.json` },
];

/** The real state, with the long log lines; fails clearly when the server was not started with the fixture. */
async function fixtureState(request) {
  // The real snapshot is served as the whole stream: without serverNow, as the page's own clock (as jobs.spec's fakeStream).
  const { serverNow, ...real } = await (await request.get('/api/state')).json();
  const job = real.jobs.find((j) => j.id === 'longjob1');
  expect(job, 'start the dev server with TGB_DEV_FIXTURE=long-names').toBeTruthy();
  expect(job.name).toHaveLength(120);
  const seq = Math.max(0, ...real.log.map((e) => e.seq)) + 1;
  const log = [...real.log, ...LONG_LOG.map((e, i) => ({ seq: seq + i, time: '12:00:00', ...e }))];
  return { real: { ...real, log }, job, account: real.accounts.find((a) => a.id === 'long1') };
}

async function openWith(page, snapshot, hash, layout) {
  await page.addInitScript((value) => {
    try { localStorage.setItem('tgb.layout', value); } catch { /* none */ }
  }, JSON.stringify(layout));
  await page.route('**/api/events', (route) => route.fulfill({
    status: 200,
    headers: { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache' },
    body: `retry: 600000\nevent: snapshot\ndata: ${JSON.stringify(snapshot)}\n\n`,
  }));
  await page.goto(`/#${hash}`);
  await expect(page.locator('#title')).toContainText('TG Backup  v');
}

/** No horizontal scroll, and every visible element inside the viewport (left and right). */
async function expectNoOverflow(page) {
  const width = page.viewportSize().width;
  const r = await page.evaluate(() => {
    const vw = document.documentElement.clientWidth;
    const wide = [];
    for (const el of document.querySelectorAll('body *')) {
      const b = el.getBoundingClientRect();
      if (b.width === 0 && b.height === 0) continue;
      if (getComputedStyle(el).visibility === 'hidden') continue;
      if (b.left < -1 || b.right > vw + 1 || b.width > vw + 1) {
        wide.push(`${el.tagName.toLowerCase()}.${el.className} ${Math.round(b.left)}..${Math.round(b.right)}`);
      }
    }
    return {
      docScroll: document.documentElement.scrollWidth,
      bodyScroll: document.body.scrollWidth,
      vw,
      wide: wide.slice(0, 10),
    };
  });
  expect(r.wide, 'elements outside the viewport').toEqual([]);
  expect(r.docScroll).toBeLessThanOrEqual(width);
  expect(r.bodyScroll).toBeLessThanOrEqual(r.vw);
}

/** The bottom bar under 900 px, the rail from 900; either way visible and inside the window. */
async function expectNav(page) {
  const { width, height } = page.viewportSize();
  const nav = page.locator('nav.tabs');
  await expect(nav).toBeVisible();
  const b = await nav.boundingBox();
  if (width < 900) {
    expect(b.width).toBeGreaterThanOrEqual(width - 1);
    expect(b.y + b.height).toBeGreaterThanOrEqual(height - 1);
    expect(b.y + b.height).toBeLessThanOrEqual(height + 1);
  } else {
    expect(b.height).toBeGreaterThanOrEqual(height - 1);
    expect(b.x + b.width).toBeGreaterThanOrEqual(width - 1);
  }
  for (const a of await nav.locator('a').all()) await expect(a).toBeInViewport();
}

/** `text` wraps (more than one line) or ellipsizes, and stays inside `card` horizontally. */
async function expectFits(text, card) {
  const r = await text.evaluate((el, cardEl) => {
    const b = el.getBoundingClientRect();
    const c = cardEl.getBoundingClientRect();
    const cs = getComputedStyle(el);
    const line = parseFloat(cs.lineHeight) || parseFloat(cs.fontSize) * 1.2;
    return {
      inside: b.left >= c.left - 1 && b.right <= c.right + 1,
      wraps: b.height > line * 1.5,
      ellipsis: cs.textOverflow === 'ellipsis' && el.scrollWidth > el.clientWidth,
      fits: el.scrollWidth <= el.clientWidth + 1,
    };
  }, await card.elementHandle());
  expect(r.inside, 'inside its card').toBe(true);
  // Either the whole text shows on several lines, or one line with … (nothing cut silently).
  expect(r.wraps || r.ellipsis || r.fits).toBe(true);
  if (!r.ellipsis) expect(r.fits, 'no text cut off without …').toBe(true);
}

async function expectLongNames(page, tab, { job, account }, root = page) {
  if (tab === 'jobs') {
    const card = root.locator(`.job-card[data-job-id="${job.id}"]`);
    await expect(card).toBeVisible();
    await expectFits(card.locator('.job-title'), card);
    for (const id of ['.job-source', '.job-target']) {
      await expect(card.locator(id)).toContainText('-100');
      await expectFits(card.locator(id), card);
    }
    // The long account name is in the details line.
    await expectFits(card.locator('.small').nth(1), card);
  }
  if (tab === 'accounts') {
    const card = root.locator('.account-card').filter({ hasText: account.name }).first();
    await expect(card).toBeVisible();
    await expectFits(card.locator('.account-title'), card);
  }
  if (tab === 'log') {
    const lines = root.locator('.log-line');
    // The fixture's lines, and the spec's copy of them when the server's log still has them.
    await expect(lines.filter({ hasText: 'Very_Long_Folder_Name' }).first()).toBeVisible();
    const list = root.locator('.log-list');
    for (const line of await lines.all()) await expectFits(line, list);
  }
}

for (const tab of TABS) {
  test(`${tab}: no horizontal scroll, long names fit, the navigation stays`, async ({ page }) => {
    const fx = await fixtureState(page.request);
    // One screen in every project: the split has its own tests below.
    await openWith(page, fx.real, tab, { mode: 'single', right: tab, left: tab === 'log' ? 'jobs' : 'log', active: 'right' });
    await expect(page.locator(`[data-screen="${tab}"]`)).toBeVisible();
    await expectLongNames(page, tab, fx);
    await expectNoOverflow(page);
    await expectNav(page);
  });
}

// Plan 3b section b, at every width (390, 1100, 1920) and the same on each: the fixture's run totals on the cards.
test('the run totals on the fixture cards: copied, position and run time, inside their cards', async ({ page }) => {
  const fx = await fixtureState(page.request);
  await openWith(page, fx.real, 'jobs', { mode: 'single', right: 'jobs', left: 'log', active: 'right' });
  const one = page.locator('.job-card[data-job-id="longjob1"]');
  const two = page.locator('.job-card[data-job-id="longjob2"]');
  await expect(one).toContainText('הועתקו: 12,340');
  await expect(one.locator('.last')).toHaveText('אוחז במקור ב-id 45,210 מתוך 50,000');
  await expect(one.locator('.run-time')).toHaveText('⏱ רץ: 2:14:05');
  await expect(two).toContainText('הועתקו: 1,234,567');
  await expect(two.locator('.last')).toHaveText('אוחז במקור ב-id 987,654');
  await expect(two.locator('.run-time')).toHaveText('⏱ רץ: 25:01:01');
  await expect(page.locator('.jobs-summary')).toContainText('הועתקו: 1,246,907');
  for (const c of [one, two]) {
    await expectFits(c.locator('.last'), c);
    await expectFits(c.locator('.run-time'), c);
  }
  await expectNoOverflow(page);
});

test('the long job in its edit dialog and in the delete question: everything inside the window', async ({ page }) => {
  const fx = await fixtureState(page.request);
  await openWith(page, fx.real, 'jobs', { mode: 'single', right: 'jobs', left: 'log', active: 'right' });
  const card = page.locator(`.job-card[data-job-id="${fx.job.id}"]`);
  await card.getByRole('button', { name: 'ערוך' }).click();
  const edit = page.getByRole('dialog');
  await expect(edit).toBeVisible();
  await expect(edit.locator('input').first()).toHaveValue(fx.job.name);
  await expectNoOverflow(page);
  await expectFits(edit.locator('.check-row').filter({ hasText: fx.account.name }).first(), edit);
  await page.keyboard.press('Escape');
  await expect(edit).toBeHidden();
  await card.getByRole('button', { name: 'מחק' }).click();
  const ask = page.getByRole('dialog');
  await expect(ask).toContainText(fx.job.name);
  await expectFits(ask.locator('h2'), ask);
  await expectNoOverflow(page);
  for (const b of await ask.getByRole('button').all()) await expect(b).toBeInViewport();
  await page.keyboard.press('Escape');
});

const LONG_TITLE = `ערוץ הארכיון הגדול של הקהילה ${LRM}-1001234567890 — The Very Long Community Archive Channel Name`;
const LONG_CHATS = [
  { peerId: 21, title: LONG_TITLE, username: 'very_long_community_archive_channel_username_x', type: 'PublicChannel', isForum: false, archived: false },
  { peerId: 22, title: `${LONG_TITLE} (פורום)`, username: '', type: 'PrivateSupergroup', isForum: true, archived: true },
];
const json = (route, body) => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) });

test('export with the long account: chat cards, the options and the topics fit', async ({ page }) => {
  const fx = await fixtureState(page.request);
  await page.route(/\/api\/export\/[^/]+\/session$/, (route) => json(route, { loggedIn: true }));
  await page.route(/\/api\/export\/[^/]+\/chats$/, (route) => json(route, LONG_CHATS));
  await page.route(/\/api\/export\/[^/]+\/topics$/, (route) => json(route, [{ id: 3, title: `${LONG_TITLE} — topic` }]));
  await openWith(page, fx.real, 'export', { mode: 'single', right: 'export', left: 'log', active: 'right' });
  const radio = page.locator('.export .radio-row').filter({ hasText: fx.account.name }).first();
  await expectFits(radio, radio.locator('xpath=ancestor::*[contains(concat(" ", @class, " "), " card ")][1]'));
  await radio.locator('input').check();
  await page.getByRole('button', { name: "טען צ'אטים" }).click();
  const cards = page.locator('.chat-card');
  await expect(cards).toHaveCount(2);
  for (const c of await cards.all()) {
    await expectFits(c.locator('.chat-title'), c);
    await expectFits(c.locator('.chat-sub'), c);
  }
  await expectNoOverflow(page);
  await cards.nth(1).click();
  const options = page.locator('.export-options');
  await expect(options).toBeVisible();
  await expectFits(options.locator('.options-title'), options);
  await expectFits(options.locator('.radio-row').filter({ hasText: 'topic' }), options);
  await expectNoOverflow(page);
  await expectNav(page);
});

test('the progress panel with long names fits', async ({ page }) => {
  const fx = await fixtureState(page.request);
  const runningExport = {
    accountId: 'long1',
    title: LONG_TITLE,
    state: {
      type: 'Processing',
      state: {
        step: 'Dialogs', substepsPassed: 1, substepsNow: 1, substepsTotal: 2, entityType: 'Chat', entityName: LONG_TITLE,
        entityIndex: 1, entityCount: 3, itemIndex: 5, itemCount: 10, bytesRandomId: 9,
        bytesName: `${'Very_Long_File_Name_Without_Spaces_'.repeat(3)}video.mp4`, bytesLoaded: 1572864, bytesCount: 3145728,
      },
    },
  };
  await openWith(page, { ...fx.real, runningExport }, 'export', { mode: 'single', right: 'export', left: 'log', active: 'right' });
  const panel = page.locator('.export-panel');
  await expect(panel).toBeVisible();
  for (const row of await panel.locator('.progress-row').all()) await expectFits(row.locator('.progress-label'), panel);
  await expectNoOverflow(page);
  await expectNav(page);
});

for (const [right, left] of [['jobs', 'log'], ['accounts', 'export']]) {
  test(`split ${right}|${left}: nothing overflows its pane`, async ({ page }, info) => {
    test.skip(info.project.name !== 'wide', 'the split shows from 1200 px');
    const fx = await fixtureState(page.request);
    await openWith(page, fx.real, right, { mode: 'split', right, left, active: 'right' });
    const panes = { right: page.locator('.pane[data-pane="right"]'), left: page.locator('.pane[data-pane="left"]') };
    await expect(panes.right.locator(`[data-screen="${right}"]`)).toBeVisible();
    await expect(panes.left.locator(`[data-screen="${left}"]`)).toBeVisible();
    for (const [side, tab] of [['right', right], ['left', left]]) {
      const p = panes[side];
      const r = await p.evaluate((paneEl) => {
        const pb = paneEl.getBoundingClientRect();
        const body = paneEl.querySelector('.pane-body');
        const out = [];
        for (const el of paneEl.querySelectorAll('*')) {
          const b = el.getBoundingClientRect();
          if (b.width === 0 && b.height === 0) continue;
          if (b.left < pb.left - 1 || b.right > pb.right + 1) {
            out.push(`${el.tagName.toLowerCase()}.${el.className} ${Math.round(b.left)}..${Math.round(b.right)} in ${Math.round(pb.left)}..${Math.round(pb.right)}`);
          }
        }
        return { out: out.slice(0, 10), scroll: body.scrollWidth, client: body.clientWidth };
      });
      expect(r.out, `${side} pane (${tab})`).toEqual([]);
      expect(r.scroll, `${side} pane scrolls sideways`).toBeLessThanOrEqual(r.client);
      await expectLongNames(page, tab, fx, p);
    }
    await expectNoOverflow(page);
    await expectNav(page);
  });
}
