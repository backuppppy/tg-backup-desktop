// Settings screen (inventory 2e, desktop version): background work with its two switches, and about with the
// version and links.
import { api } from '../lib/api.js';
import { h, toast } from '../lib/ui.js';

const BACKGROUND_TEXT =
  'כשמשימה או ייצוא רצים וסוגרים את החלון, התוכנה שואלת אם להמשיך ברקע (סמל ליד השעון) או להיסגר. כדי שהמשימות יתקדמו, המחשב צריך להיות דלוק.';

/** What a server without desktop prefs means: today's behavior. */
const DEFAULT_PREFS = { autostart: false, keepAwake: true };

let view = null;

export function render(root, state) {
  if (!view || view.root !== root || !root.contains(view.version)) view = build(root);
  view.prefs = { ...DEFAULT_PREFS, ...(state.desktopPrefs ?? {}) };
  // A switch being saved keeps the user's choice until the server's answer comes back.
  if (view.saving === 0) syncSwitches();
  const text = state.version ? `גרסה ${state.version} · TDLib ${state.tdlibVersion}` : '';
  if (view.version.textContent !== text) view.version.textContent = text;
}

function syncSwitches() {
  if (view.autostart.input.checked !== view.prefs.autostart) view.autostart.input.checked = view.prefs.autostart;
  if (view.keepAwake.input.checked !== view.prefs.keepAwake) view.keepAwake.input.checked = view.prefs.keepAwake;
}

/** Saves both switches as shown; a refusal shows its error and puts them back to the server's prefs. */
async function save() {
  const v = view;
  v.saving += 1;
  try {
    await api('PUT', '/api/desktop-prefs', { autostart: v.autostart.input.checked, keepAwake: v.keepAwake.input.checked });
  } catch (e) {
    toast(e.message);
    if (v.saving === 1) {
      v.autostart.input.checked = v.prefs.autostart;
      v.keepAwake.input.checked = v.prefs.keepAwake;
    }
  } finally {
    v.saving -= 1;
  }
}

const toggle = (label) => {
  const input = h('input', { type: 'checkbox', role: 'switch', onchange: () => save() });
  return { input, row: h('label', { class: 'switch-row' }, input, h('span', { class: 'pre' }, label)) };
};

function build(root) {
  const version = h('p', {});
  const link = (href, label) => h('a', { class: 'button text', href, target: '_blank', rel: 'noopener' }, label);
  const autostart = toggle('הפעלה עם Windows (בלי חלון, ליד השעון)');
  const keepAwake = toggle('מניעת שינה בזמן שמשימה או ייצוא רצים');
  root.replaceChildren(
    h('section', { class: 'card' },
      h('h2', {}, 'עבודה ברקע'),
      h('p', {}, BACKGROUND_TEXT),
      autostart.row,
      keepAwake.row,
    ),
    h('section', { class: 'card' },
      h('h2', {}, 'אודות'),
      version,
      h('div', { class: 'row' },
        link('https://t.me/backuppppy', 'קבוצת טלגרם'),
        link('https://github.com/backuppppy/tg-backup-desktop', 'GitHub'),
      ),
    ),
  );
  return { root, version, autostart, keepAwake, prefs: { ...DEFAULT_PREFS }, saving: 0 };
}
