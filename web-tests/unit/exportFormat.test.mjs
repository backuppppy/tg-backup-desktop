import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  MiB, sizeLimitByIndex, indexBySizeLimit, formatDownload, formatSizeText, progressRows, clampFrom, clampTill,
  takeoutDelayText, apiErrorText, typeLabel, chatSubtitle, filterChats, localMidnight, dateInputDay, dayStart,
  withTime, formatTime, shownTime,
} from '../../desktop/src/main/resources/web/lib/exportFormat.js';

const processing = (over = {}) => ({
  step: 'Initializing', substepsPassed: 0, substepsNow: 0, substepsTotal: 0, entityType: 'Chat', entityName: '',
  entityIndex: 0, entityCount: 0, itemIndex: 0, itemCount: 0, bytesRandomId: 0, bytesName: '', bytesLoaded: 0,
  bytesCount: 0, ...over,
});

test('sizeLimitByIndex follows the 100-step table', () => {
  assert.equal(sizeLimitByIndex(0), 1 * MiB);
  assert.equal(sizeLimitByIndex(9), 10 * MiB);
  assert.equal(sizeLimitByIndex(10), 12 * MiB);
  assert.equal(sizeLimitByIndex(29), 50 * MiB);
  assert.equal(sizeLimitByIndex(39), 100 * MiB);
  assert.equal(sizeLimitByIndex(59), 300 * MiB);
  assert.equal(sizeLimitByIndex(69), 500 * MiB);
  assert.equal(sizeLimitByIndex(79), 1000 * MiB);
  assert.equal(sizeLimitByIndex(89), 2000 * MiB);
  assert.equal(sizeLimitByIndex(99), 4000 * MiB);
});

test('indexBySizeLimit is the first index whose limit is at least the given one', () => {
  assert.equal(indexBySizeLimit(8 * MiB), 7);
  assert.equal(indexBySizeLimit(11 * MiB), 10);
  assert.equal(indexBySizeLimit(1), 0);
  assert.equal(indexBySizeLimit(99999 * MiB), 99);
  for (let i = 0; i < 100; i += 1) assert.equal(indexBySizeLimit(sizeLimitByIndex(i)), i);
});

test('formatDownload: MB with one decimal, KB and B whole', () => {
  assert.equal(formatDownload(1572864, 3145728), '1.5 / 3.0 MB');
  assert.equal(formatDownload(2048, 4096), '2 / 4 KB');
  assert.equal(formatDownload(10, 20), '10 / 20 B');
  assert.equal(formatDownload(0, MiB), '0.0 / 1.0 MB');
  assert.equal(formatDownload(1500, 1024), '1 / 1 KB');
});

test('formatSizeText: x.y MB, x.y KB, n B', () => {
  assert.equal(formatSizeText(1572864), '1.5 MB');
  assert.equal(formatSizeText(1536), '1.5 KB');
  assert.equal(formatSizeText(1023), '1023 B');
  assert.equal(formatSizeText(0), '0 B');
  assert.equal(formatSizeText(5 * 1024 * MiB), '5120.0 MB');
});

test('progressRows: Dialogs with several chats gives Chats and the chat row', () => {
  const rows = progressRows(processing({
    step: 'Dialogs', entityCount: 3, entityIndex: 1, itemIndex: 5, itemCount: 10, entityName: 'My chat',
    substepsPassed: 1, substepsNow: 2, substepsTotal: 4,
  }));
  assert.equal(rows.length, 2);
  assert.deepEqual(rows.map((r) => [r.label, r.info]), [['Chats', '2 / 3'], ['My chat', '5 / 10']]);
  // 1/4 + 2*1/(4*3)
  assert.ok(Math.abs(rows[0].progress - (0.25 + 2 / 12)) < 1e-9);
  assert.equal(rows[1].progress, 0.5);
});

test('progressRows: a third row for the file being downloaded', () => {
  const rows = progressRows(processing({
    step: 'Dialogs', entityCount: 3, entityIndex: 1, itemIndex: 5, itemCount: 10, entityName: 'x',
    bytesName: 'video.mp4', bytesLoaded: 1572864, bytesCount: 3145728,
  }));
  assert.equal(rows.length, 3);
  assert.deepEqual(rows[2], { label: 'video.mp4', info: '1.5 / 3.0 MB', progress: 0.5 });
});

test('progressRows: one chat has no Chats row; names for deleted, saved and replies', () => {
  const one = (over) => progressRows(processing({ step: 'Dialogs', entityCount: 1, ...over }));
  assert.deepEqual(one({ entityName: 'A' }).map((r) => r.label), ['A']);
  assert.equal(one({ entityName: '' })[0].label, 'Deleted');
  assert.equal(one({ entityName: 'x', entityType: 'SavedMessages' })[0].label, 'Saved Messages');
  assert.equal(one({ entityName: 'x', entityType: 'RepliesMessages' })[0].label, 'Replies');
  assert.deepEqual(one({ entityName: 'A', itemCount: 0 })[0], { label: 'A', info: '', progress: 0 });
  // A topic always shows the Chats row.
  const topic = progressRows(processing({ step: 'Topic', entityCount: 1, entityName: 'T' }));
  assert.deepEqual(topic.map((r) => [r.label, r.info]), [['Chats', '1 / 1'], ['T', '']]);
});

test('progressRows: main progress with one chat uses the items', () => {
  const rows = progressRows(processing({
    step: 'Dialogs', entityCount: 1, entityIndex: 0, itemIndex: 3, itemCount: 6, entityName: 'A',
    substepsPassed: 0, substepsNow: 1, substepsTotal: 2,
  }));
  assert.equal(rows.length, 1);
  const topic = progressRows(processing({
    step: 'Topic', entityCount: 1, entityIndex: 0, itemIndex: 3, itemCount: 6, entityName: 'A',
    substepsPassed: 0, substepsNow: 1, substepsTotal: 2,
  }));
  assert.equal(topic[0].progress, 0.25);
});

test('progressRows: other steps', () => {
  assert.deepEqual(progressRows(processing()), [{ label: 'Initializing...', info: '', progress: 0 }]);
  assert.equal(progressRows(processing({ step: 'DialogsList' }))[0].label, 'Processing chats...');
  assert.deepEqual(progressRows(processing({ step: 'PersonalInfo', entityCount: 2, entityIndex: 0 }))[0],
    { label: 'PersonalInfo', info: '1 / 2', progress: 0 });
  // Clamped to 0..1 as LinearProgressIndicator does.
  assert.equal(progressRows(processing({ substepsPassed: 5, substepsTotal: 2 }))[0].progress, 1);
});

test('clampTill keeps To 600 s after From; clampFrom keeps From 600 s before To', () => {
  assert.equal(clampTill(1000, 900), 1600);
  assert.equal(clampTill(1000, 1000), 1600);
  assert.equal(clampTill(1000, 1001), 1001);
  assert.equal(clampTill(0, 900), 900);
  assert.equal(clampFrom(5000, 4000), 3400);
  assert.equal(clampFrom(4000, 4000), 3400);
  assert.equal(clampFrom(3999, 4000), 3999);
  assert.equal(clampFrom(5000, 0), 5000);
});

test('takeoutDelayText: less than an hour, or whole hours, and the date', () => {
  const now = Date.UTC(2026, 10, 5, 10, 0, 0) / 1000;
  const short = takeoutDelayText(1800, now, 'UTC');
  assert.ok(short.includes('less than an hour'), short);
  assert.equal(short,
    'For security reasons, you will be able to begin downloading your data in less than an hour. ' +
    "We have notified all your devices about the export request to make sure it's authorized and give you time to react if it's not.\n\n" +
    'Please come back on 5 נובמבר 2026 at 10:30 and repeat the request using the same device.');
  assert.ok(takeoutDelayText(7 * 3600 + 5, now, 'UTC').includes('in 7 hours.'));
});

test('apiErrorText: invalid, init delay (bad suffix reads as 1 s) and the rest', () => {
  const now = Date.UTC(2026, 10, 5, 10, 0, 0) / 1000;
  assert.equal(apiErrorText(400, 'TAKEOUT_INVALID', now),
    'Sorry, you started a new data export, so this data export has been canceled.');
  assert.equal(apiErrorText(420, 'TAKEOUT_INIT_DELAY_86400', now, 'UTC'), takeoutDelayText(86400, now, 'UTC'));
  assert.equal(apiErrorText(420, 'TAKEOUT_INIT_DELAY_x', now, 'UTC'), takeoutDelayText(1, now, 'UTC'));
  assert.equal(apiErrorText(420, 'TAKEOUT_INIT_DELAY_-5', now, 'UTC'), takeoutDelayText(1, now, 'UTC'));
  assert.equal(apiErrorText(500, 'INTERNAL', now), 'API Error happened :(\n500: INTERNAL');
});

test('typeLabel and chatSubtitle', () => {
  assert.equal(typeLabel('Self'), 'הודעות שמורות');
  for (const t of ['Replies', 'VerifyCodes', 'Personal']) assert.equal(typeLabel(t), "צ'אט פרטי");
  assert.equal(typeLabel('Bot'), 'בוט');
  assert.equal(typeLabel('PrivateGroup'), 'קבוצה פרטית');
  assert.equal(typeLabel('PrivateSupergroup'), 'קבוצה פרטית');
  assert.equal(typeLabel('PublicSupergroup'), 'קבוצה ציבורית');
  assert.equal(typeLabel('PrivateChannel'), 'ערוץ פרטי');
  assert.equal(typeLabel('PublicChannel'), 'ערוץ ציבורי');
  assert.equal(typeLabel('Unknown'), '');
  assert.equal(chatSubtitle({ type: 'PublicChannel', username: 'news', isForum: true, archived: true }),
    'ערוץ ציבורי · @news · פורום · בארכיון');
  assert.equal(chatSubtitle({ type: 'Personal', username: ' ', isForum: false, archived: false }), "צ'אט פרטי");
  // listOfNotNull keeps the empty Unknown label.
  assert.equal(chatSubtitle({ type: 'Unknown', username: 'u', isForum: false, archived: false }), ' · @u');
});

test('filterChats: title or username, ignoring case; blank shows all', () => {
  const chats = [{ title: 'Alpha News', username: '' }, { title: 'ב', username: 'BetaBot' }];
  assert.equal(filterChats(chats, '   ').length, 2);
  assert.deepEqual(filterChats(chats, 'news').map((c) => c.title), ['Alpha News']);
  assert.deepEqual(filterChats(chats, 'betab').map((c) => c.title), ['ב']);
  assert.equal(filterChats(chats, 'zzz').length, 0);
});

test('dates: local midnight, date input values, time of day, Hebrew display', () => {
  const t = Date.UTC(2026, 10, 5, 13, 45, 30) / 1000;
  const mid = localMidnight(t);
  const d = new Date(mid * 1000);
  assert.deepEqual([d.getHours(), d.getMinutes(), d.getSeconds()], [0, 0, 0]);
  assert.equal(dayStart('2026-11-05'), new Date(2026, 10, 5).getTime() / 1000);
  assert.equal(dayStart(''), null);
  assert.equal(dateInputDay(dayStart('2026-01-09')), '2026-01-09');
  assert.equal(withTime(dayStart('2026-11-05') + 3600, '14:30'), dayStart('2026-11-05') + 14 * 3600 + 30 * 60);
  assert.equal(withTime(dayStart('2026-11-05'), ''), null);
  assert.equal(formatTime(t, 'd MMMM yyyy', 'UTC'), '5 נובמבר 2026');
  assert.equal(formatTime(t, 'HH:mm', 'UTC'), '13:45');
  assert.equal(formatTime(Date.UTC(2026, 0, 2, 3, 4) / 1000, 'dd.MM.yyyy HH:mm', 'UTC'), '02.01.2026 03:04');
  assert.equal(shownTime(t, 'UTC'), '5 נובמבר 2026, 13:45');
});
