// Log screen (inventory 2d): `נקה`, the line count, then the lines newest first, coloured per level.
import { api } from '../lib/api.js';
import { h, toast } from '../lib/ui.js';

let view = null;

export function render(root, state) {
  if (!view || view.root !== root || !root.contains(view.list)) view = build(root);
  const log = state.log ?? [];
  if (view.shown === log) return;
  view.shown = log;
  view.count.textContent = `${log.length} שורות`;
  const lines = [];
  for (let i = log.length - 1; i >= 0; i--) {
    const e = log[i];
    lines.push(h('div', { class: `log-line log-${e.level}` }, `${e.time}  ${e.msg}`));
  }
  view.list.replaceChildren(...lines);
}

function build(root) {
  const clear = () => api('DELETE', '/api/log').catch((e) => toast(e.message));
  const count = h('span', { class: 'log-count dim' });
  const list = h('div', { class: 'log-list' });
  root.replaceChildren(
    h('div', { class: 'row' }, h('button', { type: 'button', class: 'outlined', onclick: clear }, 'נקה'), count),
    list,
  );
  return { root, count, list, shown: null };
}
