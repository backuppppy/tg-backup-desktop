import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  formatCount, formatRunTime, runMillisAt, positionParts, runTimeShown, phaseText,
} from '../../desktop/src/main/resources/web/lib/jobsFormat.js';

test('formatCount puts a comma between thousands', () => {
  assert.equal(formatCount(0), '0');
  assert.equal(formatCount(999), '999');
  assert.equal(formatCount(12340), '12,340');
  assert.equal(formatCount(1234567), '1,234,567');
  assert.equal(formatCount(undefined), '0');
});

test('formatRunTime is H:MM:SS, the hours not wrapped at a day', () => {
  assert.equal(formatRunTime(0), '0:00:00');
  assert.equal(formatRunTime(999), '0:00:00');
  assert.equal(formatRunTime(65_000), '0:01:05');
  assert.equal(formatRunTime(8_045_000), '2:14:05');
  assert.equal(formatRunTime(90_061_000), '25:01:01');
  assert.equal(formatRunTime(-5), '0:00:00');
});

test('runMillisAt adds the time since runningSince only while it is set', () => {
  assert.equal(runMillisAt({ runMillis: 1000, runningSince: 0 }, 50_000), 1000);
  assert.equal(runMillisAt({ runMillis: 1000, runningSince: 40_000 }, 50_000), 11_000);
  // A clock behind the server's adds nothing rather than taking time off.
  assert.equal(runMillisAt({ runMillis: 1000, runningSince: 60_000 }, 50_000), 1000);
  assert.equal(runMillisAt({}, 50_000), 0);
});

test('runMillisAt on the server clock: the offset (serverNow - Date.now()) is added to the page clock', () => {
  // The page's clock 60 s behind the server's: runningSince is 60 s ahead of the page's now.
  assert.equal(runMillisAt({ runMillis: 1000, runningSince: 110_000 }, 55_000, 60_000), 6000);
  // The page's clock 60 s ahead.
  assert.equal(runMillisAt({ runMillis: 1000, runningSince: 0 + 40_000 }, 110_000, -60_000), 11_000);
  // No offset: the page's own clock, as before.
  assert.equal(runMillisAt({ runMillis: 1000, runningSince: 40_000 }, 50_000, undefined), 11_000);
});

test('runTimeShown: a saved time, or a running clock', () => {
  assert.equal(runTimeShown({ runMillis: 0, runningSince: 0 }), false);
  assert.equal(runTimeShown({}), false);
  assert.equal(runTimeShown({ runMillis: 5, runningSince: 0 }), true);
  assert.equal(runTimeShown({ runMillis: 0, runningSince: 7 }), true);
});

test('positionParts: the formatted numbers the screen puts in their own <bdi>', () => {
  assert.deepEqual(positionParts(45210, 50000), { last: '45,210', latest: '50,000' });
  assert.deepEqual(positionParts(45210, 0), { last: '45,210', latest: null });
  assert.deepEqual(positionParts(0, 50000), { last: '0', latest: '50,000' });
  assert.equal(positionParts(0, 0), null);
});

test('phaseText: the copy and upload phases say the message is in the target', () => {
  assert.equal(phaseText('מעתיק הודעה 9384'), 'מעתיק הודעה ביעד 9384');
  assert.equal(phaseText('מעלה הודעה 12'), 'מעלה הודעה ביעד 12');
  assert.equal(phaseText('מאתר ערוצים'), 'מאתר ערוצים');
  assert.equal(phaseText(''), '');
  assert.equal(phaseText(undefined), '');
});
