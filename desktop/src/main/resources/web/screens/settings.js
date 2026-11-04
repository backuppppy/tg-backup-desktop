// Settings screen (inventory 2e, desktop version): background work, and about with the version and links.
import { h } from '../lib/ui.js';

const BACKGROUND_TEXT =
  'בזמן שמשימה רצה המחשב לא ייכנס למצב שינה. סגירת החלון לא עוצרת את המשימות. כדי שהמשימות יתקדמו, המחשב צריך להיות דלוק.';

let view = null;

export function render(root, state) {
  if (!view || view.root !== root || !root.contains(view.version)) view = build(root);
  const text = state.version ? `גרסה ${state.version} · TDLib ${state.tdlibVersion}` : '';
  if (view.version.textContent !== text) view.version.textContent = text;
}

function build(root) {
  const version = h('p', {});
  const link = (href, label) => h('a', { class: 'button text', href, target: '_blank', rel: 'noopener' }, label);
  root.replaceChildren(
    h('section', { class: 'card' }, h('h2', {}, 'עבודה ברקע'), h('p', {}, BACKGROUND_TEXT)),
    h('section', { class: 'card' },
      h('h2', {}, 'אודות'),
      version,
      h('div', { class: 'row' },
        link('https://t.me/backuppppy', 'קבוצת טלגרם'),
        link('https://github.com/backuppppy/tg-backup-desktop', 'GitHub'),
      ),
    ),
  );
  return { root, version };
}
