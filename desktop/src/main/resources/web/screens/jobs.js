// Jobs screen (inventory 2a): the header with the all-jobs buttons, one card per job, `➕ הוסף משימה`,
// JobDialog, and the delete and reset dialogs. Texts and enable rules are the Android ones.
import { api } from '../lib/api.js';
import { h, toast, formDialog, confirmDialog } from '../lib/ui.js';
import { formatCount, formatRunTime, runMillisAt, runTimeShown, positionParts } from '../lib/jobsFormat.js';

const MEDIA_TYPES = [
  ['VIDEO_DOC', 'וידאו + קבצים'],
  ['PHOTO', 'תמונות בלבד'],
  ['ALL_MEDIA', 'כל המדיה (ללא מדבקות)'],
  ['TEXT', 'טקסט בלבד'],
  ['ALL', 'הכל'],
];
const MEDIA_LABEL = Object.fromEntries(MEDIA_TYPES);
const MODES = [
  ['ORDERED', '📑 מסודר — שומר על הסדר של המקור'],
  ['FAST', '⚡ מהיר — כל חשבון מטפל בקטע משלו (סדר מבולגן)'],
];
const NEW_JOB = {
  id: '', name: '', source: '', target: '', mediaType: 'VIDEO_DOC', mode: 'ORDERED', accountIds: [],
  startFromId: 0, stripLinks: true, skipArabic: false, perMinuteLimit: 25, viaTakeout: false,
};
const IDLE = { running: false, paused: false, phase: '', lastId: 0, sourceTitle: '', isProtected: false, copied: 0, failed: 0, lastLink: null, lastTargetLink: null,
  copiedTotal: 0, runMillis: 0, runningSince: 0, latestId: 0 };

const blank = (s) => !s || !s.trim();
const report = (e) => toast(e.message);
const post = (path) => api('POST', path).catch(report);

let view = null;
/** The one run time timer of the screen (a second apart), while a shown job's clock runs. */
let tick = null;
/** Jobs whose source's latest id was asked this page load: once each, not on every render. */
const askedLatest = new Set();

export function render(root, state) {
  if (!view || view.root !== root || !root.contains(view.list)) {
    view = { root, list: h('div', { class: 'jobs' }), shown: null, clocks: [], offset: 0 };
    root.replaceChildren(view.list);
  }
  // The server's clock less the page's, from the last snapshot or statuses event: runningSince is server time.
  view.offset = Number(state.clockOffset) || 0;
  const { accounts = [], jobs = [], statuses = {} } = state;
  const shown = view.shown;
  if (shown && shown.accounts === accounts && shown.jobs === jobs && shown.statuses === statuses) {
    schedule();
    return;
  }
  view.shown = { accounts, jobs, statuses };
  view.clocks = [];
  askLatest(jobs, statuses);
  const focus = focusedButton(view.list);
  view.list.replaceChildren(...[
    header(statuses),
    accounts.length === 0 ? h('p', { class: 'error-text' }, "קודם הוסף חשבון בלשונית 'חשבונות'.") : null,
    jobs.map((job) => jobCard(job, statuses[job.id] ?? IDLE, accounts, state)),
    h('button', { type: 'button', class: 'full', onclick: () => jobDialog(NEW_JOB, state) }, '➕ הוסף משימה'),
  ].flat().filter(Boolean));
  restoreFocus(view.list, focus);
  schedule();
}

/** The shell took the screen out of the view: no run time timer while it is away. */
export function goAway() {
  if (tick) clearInterval(tick);
  tick = null;
}

/** The shell put the screen back: the render that follows starts the timer again. */
export function comeBack() {
  goAway();
}

/** The run time lines of now, text only: the cards are not drawn again (focus and selection stay). */
function updateClocks() {
  const now = Date.now();
  for (const { el, status } of view.clocks) el.textContent = runTimeOf(status, now, view.offset);
}

/** One interval while the screen is in view and a shown job's clock runs; none otherwise. */
function schedule() {
  const runs = view && view.root.isConnected && view.clocks.some(({ status }) => (Number(status.runningSince) || 0) > 0);
  if (!runs) {
    goAway();
    return;
  }
  if (tick) return;
  tick = setInterval(() => {
    if (!view || !view.root.isConnected) goAway();
    else updateClocks();
  }, 1000);
}

/** The run time as H:MM:SS at the page's `now`, on the server's clock by `offset`. */
const runTimeOf = (status, now, offset) => formatRunTime(runMillisAt(status, now, offset));

/** A shown job without its source's latest id asks the server to read it, once a page load; it comes by SSE. */
function askLatest(jobs, statuses) {
  for (const job of jobs) {
    if ((Number(statuses[job.id]?.latestId) || 0) > 0 || askedLatest.has(job.id)) continue;
    askedLatest.add(job.id);
    api('POST', `/api/jobs/${encodeURIComponent(job.id)}/latest`).catch(() => {});
  }
}

/** The scope of a button in the list: its job card, or else the list itself (the all-jobs and add buttons). */
const scopeOf = (list, el) => el.closest('[data-job-id]') ?? list;
const buttonsIn = (scope, list) => [...scope.querySelectorAll('button')].filter((b) => scopeOf(list, b) === scope);

/** The focused button in the list, by its card's job id, its text and its place among the card's buttons. */
function focusedButton(list) {
  const el = document.activeElement;
  if (!(el instanceof HTMLButtonElement) || !list.contains(el)) return null;
  const scope = scopeOf(list, el);
  return { jobId: scope === list ? null : scope.dataset.jobId, text: el.textContent, index: buttonsIn(scope, list).indexOf(el) };
}

/** A card button that only changes its text: each text and the other one it becomes, in the same place. */
const SWAPPED = {
  '⏸ השהה': '▶ המשך', '▶ המשך': '⏸ השהה',
  '▶ הפעל': '⏹ עצור', '⏹ עצור': '▶ הפעל',
};

/**
 * The same button after the list was drawn again; when its text changed (⏸ השהה → ▶ המשך), the one in its place.
 * A button that became disabled (ערוך once the job runs) passes the focus to no other button.
 */
function restoreFocus(list, focus) {
  if (!focus) return;
  const scope = focus.jobId === null ? list
    : [...list.querySelectorAll('[data-job-id]')].find((c) => c.dataset.jobId === focus.jobId);
  if (!scope) return;
  const buttons = buttonsIn(scope, list);
  const inPlace = buttons[focus.index];
  const target = buttons.find((b) => b.textContent === focus.text && !b.disabled)
    ?? (inPlace && !inPlace.disabled && inPlace.textContent === SWAPPED[focus.text] ? inPlace : null);
  target?.focus({ preventScroll: true });
}

function header(statuses) {
  const all = Object.values(statuses);
  const active = all.filter((s) => s.running);
  const total = formatCount(all.reduce((n, s) => n + (Number(s.copiedTotal) || 0), 0));
  const summary = active.length === 0
    ? `⚪ עצור · הועתקו: ${total}`
    : `🟢 ${active.length} משימות פעילות · הועתקו: ${total}`;
  const startAll = () => api('POST', '/api/jobs/all/start')
    .then((r) => { if (r.errors?.length) toast(r.errors.join('\n')); })
    .catch(report);
  return h('div', { class: 'jobs-header' },
    h('div', { class: 'jobs-summary' }, summary),
    h('div', { class: 'row' },
      h('button', { type: 'button', onclick: startAll }, '▶ הפעל הכל'),
      h('button', { type: 'button', class: 'outlined', disabled: !active.some((s) => !s.paused), onclick: () => post('/api/jobs/all/pause') }, '⏸ השהה הכל'),
      h('button', { type: 'button', class: 'outlined', disabled: !active.some((s) => s.paused), onclick: () => post('/api/jobs/all/resume') }, '▶ המשך הכל'),
      h('button', { type: 'button', class: 'outlined', disabled: active.length === 0, onclick: () => post('/api/jobs/all/stop') }, '⏹ עצור הכל'),
    ),
  );
}

/**
 * A link that opens in a new window (Task 9 routes it to the system browser), or plain text when there is no URL
 * or it is not http(s).
 */
function maybeLink(cls, url, text) {
  return typeof url === 'string' && /^https?:\/\//i.test(url)
    ? h('a', { class: `${cls} link`, href: url, target: '_blank', rel: 'noopener' }, text)
    : h('div', { class: cls }, text);
}

/** The position's X: a link to the source message when there is an http(s) one, else the number alone. */
function numberLink(url, text) {
  return typeof url === 'string' && /^https?:\/\//i.test(url)
    ? h('a', { class: 'link', href: url, target: '_blank', rel: 'noopener' }, text)
    : text;
}

function jobCard(job, status, accounts, state) {
  const running = status.running;
  const icon = running && status.paused ? '⏸' : running ? '🟢' : '⚪';
  const names = job.accountIds
    .map((id) => accounts.find((a) => a.id === id)?.name)
    .filter((n) => n && n.trim());
  const base = `/api/jobs/${encodeURIComponent(job.id)}`;

  const details = `${MEDIA_LABEL[job.mediaType] ?? job.mediaType} · ${job.mode === 'FAST' ? 'מהיר' : 'מסודר'}`
    + `${job.viaTakeout ? ' · 📡 בלי הורדה' : ''} · חשבונות: ${(names.length ? names : ['—']).join(', ')}`;
  const note = job.viaTakeout ? '📡 קריאה דרך סשן הייצוא ושליחה בלי הורדה'
    : status.isProtected ? '🔒 ערוץ מוגן — הורדה והעלאה מחדש' : null;

  const remove = async () => {
    if (await confirmDialog({ title: `למחוק את המשימה ${job.name}?`, text: 'ההתקדמות השמורה תימחק.' })) {
      api('DELETE', base).catch(report);
    }
  };
  const reset = async () => {
    if (await confirmDialog({
      title: 'לאפס התקדמות?',
      text: `המשימה ${job.name} תתחיל מההודעה הראשונה בערוץ (ייתכנו כפילויות ביעד).`,
    })) post(`${base}/reset`);
  };

  const actions = running
    ? [
      h('button', { type: 'button', onclick: () => post(`${base}/stop`) }, '⏹ עצור'),
      status.paused
        ? h('button', { type: 'button', class: 'outlined', onclick: () => post(`${base}/resume`) }, '▶ המשך')
        : h('button', { type: 'button', class: 'outlined', onclick: () => post(`${base}/pause`) }, '⏸ השהה'),
    ]
    : [h('button', { type: 'button', onclick: () => post(`${base}/start`) }, '▶ הפעל')];

  // Each number in its own <bdi>: in RTL `id` stays before X (one LTR run would put X first), and the link is on X.
  const position = positionParts(status.lastId, status.latestId);
  const positionLine = position && h('div', { class: 'last' },
    'אוחז ב-id ',
    h('bdi', {}, numberLink(status.lastLink, position.last)),
    position.latest ? [' מתוך ', h('bdi', {}, position.latest)] : null);
  let clock = null;
  if (runTimeShown(status)) {
    const time = h('bdi', {}, runTimeOf(status, Date.now(), view.offset));
    clock = h('div', { class: 'run-time' }, '⏱ רץ: ', time);
    view.clocks.push({ el: time, status });
  }

  return h('section', { class: `card job-card${running ? ' running' : ''}`, 'data-job-id': job.id },
    h('h3', { class: 'job-title' }, `${icon} ${blank(job.name) ? `משימה ${job.id}` : job.name}`),
    // RTL as in Android: source on the right, the arrow toward the target; each id keeps its own direction.
    h('div', { class: 'small pre', dir: 'rtl' },
      h('bdi', { class: 'job-source' }, blank(status.sourceTitle) ? job.source : status.sourceTitle),
      '  ←  ',
      h('bdi', { class: 'job-target' }, job.target)),
    h('div', { class: 'small' }, details),
    note ? h('div', { class: 'small' }, note) : null,
    h('hr'),
    running ? maybeLink('phase', status.lastTargetLink, status.phase) : null,
    h('div', { class: 'copied' }, 'הועתקו: ', h('bdi', {}, formatCount(status.copiedTotal)),
      status.failed > 0 ? [' · נכשלו: ', h('bdi', {}, status.failed)] : null),
    positionLine,
    clock,
    h('div', { class: 'row' },
      actions,
      h('button', { type: 'button', class: 'outlined', disabled: running, onclick: () => jobDialog(job, state) }, 'ערוך'),
      h('button', { type: 'button', class: 'text', disabled: running, onclick: reset }, 'אפס התקדמות'),
      h('button', { type: 'button', class: 'text danger', disabled: running, onclick: remove }, 'מחק'),
    ),
  );
}

const digitsOnly = (input) => input.addEventListener('input', () => {
  const v = input.value.replace(/\D/g, '');
  if (v !== input.value) input.value = v;
});

const textField = (label, value, attrs = {}) => {
  const input = h('input', { type: 'text', value, autocomplete: 'off', dir: 'auto', ...attrs });
  return { input, row: h('label', { class: 'field' }, h('span', { class: 'field-label' }, label), input) };
};

const toggle = (label, checked, onchange) => {
  const input = h('input', { type: 'checkbox', role: 'switch', onchange });
  input.checked = checked;
  return { input, row: h('label', { class: 'switch-row' }, input, h('span', { class: 'pre' }, label)) };
};

function radios(name, options, current, onchange) {
  return options.map(([value, label]) => {
    const input = h('input', { type: 'radio', name, value, onchange: () => onchange(value) });
    input.checked = value === current;
    return h('label', { class: 'radio-row' }, input, h('span', {}, label));
  });
}

/** Android's `toIntOrNull()`: digits that fit in an Int, else null. */
function toInt(s) {
  if (!/^\d+$/.test(s)) return null;
  const n = Number(s);
  return n <= 2147483647 ? n : null;
}

/** Android's `toLongOrNull()` for a digits-only field; beyond what JSON numbers keep exactly, null. */
function toLong(s) {
  if (!/^\d+$/.test(s)) return null;
  const n = Number(s);
  return Number.isSafeInteger(n) ? n : null;
}

/** JobDialog: a new job (`initial.source` blank) or an edit of `initial`, which keeps its id. */
function jobDialog(initial, state) {
  const accounts = state.accounts ?? [];
  const usedElsewhere = new Set((state.jobs ?? []).filter((j) => j.id !== initial.id).flatMap((j) => j.accountIds));
  const selected = new Set(initial.accountIds);
  let mediaType = initial.mediaType;
  let mode = initial.mode;

  const name = textField('שם המשימה', initial.name);
  const source = textField('מקור: מזהה (עם או בלי ‎-100) / @username / קישור', initial.source);
  const target = textField('יעד: מזהה (עם או בלי ‎-100) / @username / קישור', initial.target);
  for (const f of [source, target]) {
    f.input.addEventListener('input', () => {
      const v = f.input.value.trim();
      if (v !== f.input.value) f.input.value = v;
    });
  }

  const accountRows = accounts.map((acc) => {
    const taken = usedElsewhere.has(acc.id) && !selected.has(acc.id);
    const input = h('input', {
      type: 'checkbox',
      disabled: taken,
      onchange: () => {
        if (input.checked) selected.add(acc.id); else selected.delete(acc.id);
        update();
      },
    });
    input.checked = selected.has(acc.id);
    const label = (blank(acc.name) ? acc.phone : acc.name) + (taken ? ' (משויך למשימה אחרת)' : '');
    return h('label', { class: `check-row${taken ? ' taken' : ''}` }, input, h('span', {}, label));
  });

  const takeout = toggle('  📡 העתקה בלי הורדה (דרך סשן הייצוא)', initial.viaTakeout, () => update());
  const takeoutHelp = h('div', { class: 'small' },
    'עובד גם בערוצים מוגנים וחסומים. ' + 'בפעם הראשונה טלגרם עשוי לבקש לאשר את הייצוא בהודעת שירות.');
  // The mode is kept (not reset) while the section is hidden.
  const modeSection = h('div', { class: 'section' },
    h('div', { class: 'section-label' }, 'מצב עבודה'),
    radios('job-mode', MODES, mode, (v) => { mode = v; }),
  );

  const startFrom = textField("התחל מהודעה מס' (ריק = להמשיך מאיפה שעצר)", initial.startFromId > 0 ? String(initial.startFromId) : '', { inputmode: 'numeric' });
  const limit = textField('תקרת הודעות לדקה לחשבון (0 = ללא)', String(initial.perMinuteLimit), { inputmode: 'numeric' });
  digitsOnly(startFrom.input);
  digitsOnly(limit.input);
  const strip = toggle('  הסר קישורים ותיוגים מהכיתוב', initial.stripLinks);
  const arabic = toggle('  דלג על הודעות בערבית', initial.skipArabic);

  const save = async () => {
    const body = {
      name: name.input.value.trim(),
      source: source.input.value,
      target: target.input.value,
      mediaType,
      mode,
      accountIds: accounts.map((a) => a.id).filter((id) => selected.has(id)),
      startFromId: toLong(startFrom.input.value) ?? 0,
      stripLinks: strip.input.checked,
      skipArabic: arabic.input.checked,
      perMinuteLimit: toInt(limit.input.value) ?? 25,
      viaTakeout: takeout.input.checked,
    };
    try {
      if (initial.id) await api('PUT', `/api/jobs/${encodeURIComponent(initial.id)}`, body);
      else await api('POST', '/api/jobs', body);
      return true;
    } catch (e) {
      report(e);
      return false;
    }
  };

  const dialog = formDialog({
    title: blank(initial.source) ? 'משימה חדשה' : 'עריכת משימה',
    okText: 'שמור',
    okClass: '',
    onOk: save,
    content: [
      name.row,
      source.row,
      target.row,
      h('div', { class: 'section' },
        h('div', { class: 'section-label' }, 'סוג תוכן'),
        radios('job-media', MEDIA_TYPES, mediaType, (v) => { mediaType = v; }),
      ),
      h('div', { class: 'section' },
        h('div', { class: 'section-label' }, 'חשבונות (חשבון אחד — למשימה אחת בלבד)'),
        accounts.length === 0 ? h('div', { class: 'error-text' }, "אין חשבונות — הוסף בלשונית 'חשבונות'") : null,
        accountRows,
      ),
      takeout.row,
      takeoutHelp,
      modeSection,
      startFrom.row,
      limit.row,
      strip.row,
      arabic.row,
    ],
  });

  function update() {
    takeoutHelp.hidden = !takeout.input.checked;
    modeSection.hidden = selected.size <= 1;
    // A start id beyond what a JSON number keeps exactly is refused, not saved as 0.
    const startBad = startFrom.input.value !== '' && toLong(startFrom.input.value) === null;
    dialog.okButton.disabled = blank(source.input.value) || blank(target.input.value) || selected.size === 0 || startBad;
  }
  for (const f of [source, target, startFrom]) f.input.addEventListener('input', update);
  update();
  return dialog;
}
