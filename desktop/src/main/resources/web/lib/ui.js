// Small DOM helpers shared by the screens: h(), toast(), dialogs.

/**
 * Creates an element. `attrs` keys: `class`, `on<event>` (listener; anything but a function throws), `hidden`/`disabled`/... (booleans),
 * anything else as an attribute. Children may be strings, nodes, arrays, or null/false (skipped).
 */
export function h(tag, attrs = {}, ...children) {
  const el = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs ?? {})) {
    if (v === null || v === undefined || v === false) continue;
    if (k.startsWith('on')) {
      // Never an inline handler: an `on*` value must be a function.
      if (typeof v !== 'function') throw new TypeError(`h(): ${k} must be a function`);
      el.addEventListener(k.slice(2).toLowerCase(), v);
    } else if (k === 'class') el.className = v;
    else if (k === 'value') el.value = v;
    else el.setAttribute(k, v === true ? '' : String(v));
  }
  append(el, children);
  return el;
}

function append(el, children) {
  for (const c of children) {
    if (c === null || c === undefined || c === false) continue;
    if (Array.isArray(c)) append(el, c);
    else el.append(c instanceof Node ? c : String(c));
  }
}

const TOAST_MS = 4000;
const toastQueue = [];
let toastBusy = false;

/** Shows `msg` at the bottom, above the tabs, for about 4 s. One at a time; later ones wait their turn. */
export function toast(msg) {
  toastQueue.push(String(msg));
  if (!toastBusy) nextToast();
}

function nextToast() {
  const el = document.getElementById('toast');
  const msg = toastQueue.shift();
  if (msg === undefined || !el) {
    toastBusy = false;
    if (el) el.hidden = true;
    return;
  }
  toastBusy = true;
  el.textContent = msg;
  el.hidden = false;
  setTimeout(nextToast, TOAST_MS);
}

/**
 * A modal dialog. `content` is extra nodes under the text (fields). `onOk` may return false (or a Promise of it)
 * to keep the dialog open. Clicking outside or Escape counts as cancel. `okClass` styles the OK button (default `text`);
 * while `okButton.disabled` is set, OK (and Enter) does nothing.
 * Returns `{ result: Promise<boolean>, close(ok), okButton }`.
 */
export function formDialog({ title, text, content = [], okText = 'אישור', cancelText = 'ביטול', okClass = 'text', onOk }) {
  let settle;
  const result = new Promise((resolve) => { settle = resolve; });
  let done = false;

  const close = (ok) => {
    if (done) return;
    done = true;
    overlay.remove();
    document.removeEventListener('keydown', onKey);
    settle(ok);
  };
  const ok = async () => {
    if (okButton.disabled) return;
    if (onOk && (await onOk()) === false) return;
    close(true);
  };
  const onKey = (e) => {
    if (e.key === 'Escape') close(false);
  };

  const okButton = h('button', { type: 'button', class: okClass, onclick: () => ok() }, okText);
  const dialog = h('div', { class: 'dialog', role: 'dialog', 'aria-modal': 'true', 'aria-label': title },
    title ? h('h2', {}, title) : null,
    text ? h('p', { class: 'text' }, text) : null,
    h('form', { class: 'body', onsubmit: (e) => { e.preventDefault(); ok(); } },
      content,
      // Enter in a field submits; the visible buttons are below.
      h('button', { type: 'submit', hidden: true, tabindex: '-1' }),
    ),
    h('div', { class: 'actions' },
      cancelText ? h('button', { type: 'button', class: 'text', onclick: () => close(false) }, cancelText) : null,
      okButton,
    ),
  );
  const overlay = h('div', { class: 'overlay', onclick: (e) => { if (e.target === overlay) close(false); } }, dialog);
  document.body.append(overlay);
  document.addEventListener('keydown', onKey);
  (dialog.querySelector('input, select, textarea') ?? dialog.querySelector('.actions button:last-child'))?.focus();
  return { result, close, okButton };
}

/** Asks a yes/no question with `אישור` / `ביטול`. */
export function confirmDialog({ title, text }) {
  return formDialog({ title, text }).result;
}
