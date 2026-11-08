// Log screen (inventory 2d): `נקה`, the line count, then the lines newest first, coloured per level.
import { api } from '../lib/api.js';
import { h, toast } from '../lib/ui.js';

let view = null;

export function render(root, state) {
  if (!view || view.root !== root || !root.contains(view.list)) view = build(root);
  const log = state.log ?? [];
  if (view.shown === log) return;
  const kept = keptFrom(view.shown, log);
  view.count.textContent = `${log.length} שורות`;
  if (kept === null) {
    view.nodes = log.map(line);
    view.list.replaceChildren(...[...view.nodes].reverse());
  } else {
    // The same lines stay; the ones past the cap leave the old end and the newer ones go in front.
    const dropped = view.shown.length - kept;
    for (const node of view.nodes.splice(0, dropped)) node.remove();
    const added = log.slice(kept).map(line);
    view.nodes.push(...added);
    view.list.prepend(...added.reverse());
  }
  view.shown = log;
}

const line = (e) => h('div', { class: `log-line log-${e.level}` }, `${e.time}  ${e.msg}`);

/**
 * How many of the `shown` entries (oldest first) are still the leading entries of `log` once the oldest fell off the cap,
 * or null when `log` is not `shown` plus newer entries (cleared, a new snapshot): the screen draws it all again.
 */
function keptFrom(shown, log) {
  if (!shown?.length || !log.length) return null;
  const dropped = shown.indexOf(log[0]);
  if (dropped < 0) return null;
  const kept = shown.length - dropped;
  if (kept > log.length) return null;
  for (let i = 0; i < kept; i++) if (shown[dropped + i] !== log[i]) return null;
  return kept;
}

function build(root) {
  const clear = () => api('DELETE', '/api/log').catch((e) => toast(e.message));
  const count = h('span', { class: 'log-count dim' });
  const list = h('div', { class: 'log-list' });
  root.replaceChildren(
    h('div', { class: 'row' }, h('button', { type: 'button', class: 'outlined', onclick: clear }, 'נקה'), count),
    list,
  );
  return { root, count, list, shown: null, nodes: [] };
}
