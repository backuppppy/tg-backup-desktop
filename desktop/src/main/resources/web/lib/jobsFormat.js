// Pure helpers of the Jobs screen's run totals (plan 3b, section b). No DOM here: unit-tested under node.

/** A count with a comma between thousands: 12,340. */
export function formatCount(n) {
  return (Number(n) || 0).toLocaleString('en-US');
}

const two = (n) => String(n).padStart(2, '0');

/** A run time as H:MM:SS; the hours go on past a day (25:01:01). */
export function formatRunTime(ms) {
  const secs = Math.max(0, Math.floor((Number(ms) || 0) / 1000));
  return `${Math.floor(secs / 3600)}:${two(Math.floor(secs / 60) % 60)}:${two(secs % 60)}`;
}

/** The most of the live segment shown: JobManager's MAX_TICKS_PER_SEGMENT × its 10 s runTick. */
const MAX_LIVE_MILLIS = 20_000;

/**
 * The job's run time at the page's `now` (ms): the saved time, and while `runningSince` is set the time since then.
 * `runningSince` is the server's clock: `offset` (serverNow - Date.now() at the last receipt, 0 when unknown) turns
 * the page's now into the server's. A clock still behind the server's adds nothing rather than taking time off.
 * A segment longer than 20 s (the PC slept since the last write) shows 20 s of it, as the engine counts it.
 */
export function runMillisAt(status, now, offset = 0) {
  const saved = Number(status.runMillis) || 0;
  const since = Number(status.runningSince) || 0;
  const live = since > 0 ? Math.max(0, now + (Number(offset) || 0) - since) : 0;
  // The engine writes the time every 10 s and counts at most two of those per segment (the rest is the PC asleep).
  return saved + Math.min(live, MAX_LIVE_MILLIS);
}

/** The phase as shown: the message a copy or upload phase names is in the target (`מעתיק הודעה ביעד 9384`). */
export function phaseText(phase) {
  return String(phase ?? '').replace(/^(מעתיק|מעלה) הודעה(?= )/, '$1 הודעה ביעד');
}

/** The run time line is shown once there is a saved time or the clock runs. */
export function runTimeShown(status) {
  return (Number(status.runMillis) || 0) > 0 || (Number(status.runningSince) || 0) > 0;
}

/**
 * The numbers of `אוחז ב-id X מתוך Y`: `{last, latest}` formatted, `latest` null while the latest id is unknown (0);
 * null when neither is known. The screen puts each number in its own <bdi>, so `id` and X read in order in RTL.
 */
export function positionParts(lastId, latestId) {
  const last = Number(lastId) || 0;
  const latest = Number(latestId) || 0;
  if (last <= 0 && latest <= 0) return null;
  return { last: formatCount(last), latest: latest > 0 ? formatCount(latest) : null };
}
