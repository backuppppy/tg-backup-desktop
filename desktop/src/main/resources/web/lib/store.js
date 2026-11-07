// The page's one state object, fed by the server's event stream (GET /api/events).
// `reduce` is pure (unit-tested under node); `connect` wires it to EventSource.

export const LOG_MAX = 500;

const EVENTS = ['snapshot', 'config', 'statuses', 'accountStates', 'exportPrefs', 'export', 'password', 'log', 'chatsProgress'];

const capLog = (list) => (list.length > LOG_MAX ? list.slice(list.length - LOG_MAX) : list);

/** serverNow - receivedAt when the event carries the server's clock (epoch ms), else null. */
const offsetOf = (data, receivedAt) => (typeof data?.serverNow === 'number' ? data.serverNow - receivedAt : null);

/**
 * The state after event `name` with parsed `data`, received at `receivedAt` (the page's Date.now()).
 * `disconnected` is the page's own event. `clockOffset` is the server's clock less the page's (the jobs' run clocks).
 */
export function reduce(state, name, data, receivedAt = Date.now()) {
  switch (name) {
    case 'snapshot':
      return {
        ...data, log: capLog(data.log ?? []), chatsLoading: { ...(data.chatsLoading ?? {}) }, connected: true,
        clockOffset: offsetOf(data, receivedAt) ?? 0,
      };
    case 'disconnected':
      return { ...state, connected: false };
    case 'config':
      return { ...state, accounts: data.accounts, jobs: data.jobs };
    case 'statuses': {
      // `{statuses, serverNow}`; the map alone from a server before serverNow. A status is never a number.
      const offset = offsetOf(data, receivedAt);
      return offset === null ? { ...state, statuses: data } : { ...state, statuses: data.statuses, clockOffset: offset };
    }
    case 'accountStates':
      return { ...state, accountStates: data };
    case 'exportPrefs':
      return { ...state, exportPrefs: data };
    case 'export':
      return { ...state, runningExport: data };
    case 'password':
      return { ...state, password: data };
    case 'log':
      return { ...state, log: capLog(data.cleared ? data.entries : [...(state.log ?? []), ...data.entries]) };
    case 'chatsProgress': {
      const chatsLoading = { ...(state.chatsLoading ?? {}) };
      if (data.done) delete chatsLoading[data.accountId];
      else chatsLoading[data.accountId] = data.count;
      return { ...state, chatsLoading };
    }
    default:
      return state;
  }
}

/** Delay before reopening a stream the browser gave up on (EventSource retries network errors by itself). */
const REOPEN_MS = 3000;

/**
 * Opens the event stream and calls `onState(state)` after every change. Until the first `snapshot` the state is
 * `{connected: false}`. EventSource reconnects by itself; the next `snapshot` replaces everything.
 * When the browser reports going offline, the stream is restarted at once, since an open stream may not notice.
 */
export function connect(onState) {
  let state = { connected: false };
  let source = null;
  const apply = (name, data) => {
    state = reduce(state, name, data, Date.now());
    onState(state);
  };
  const lost = () => {
    if (state.connected) apply('disconnected');
  };
  const open = () => {
    source?.close();
    const s = new EventSource('/api/events');
    source = s;
    for (const name of EVENTS) {
      s.addEventListener(name, (e) => {
        if (s === source) apply(name, JSON.parse(e.data));
      });
    }
    s.addEventListener('error', () => {
      if (s !== source) return;
      lost();
      if (s.readyState === EventSource.CLOSED) setTimeout(() => { if (s === source) open(); }, REOPEN_MS);
    });
  };
  window.addEventListener('offline', () => {
    lost();
    open();
  });
  open();
  onState(state);
  return { get state() { return state; } };
}
