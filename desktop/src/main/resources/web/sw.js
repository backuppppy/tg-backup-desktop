// The phone's service worker: it only adds an offline page. offline.html is the one cached file (stored at install);
// a page load that cannot reach the PC shows it. Everything else (API, the event stream, scripts, styles, icons)
// goes to the network only and is never answered from a cache, so the phone always shows the PC's live state.
const CACHE = 'tgb-offline-v1';
const OFFLINE = '/offline.html';

self.addEventListener('install', (event) => {
  event.waitUntil(caches.open(CACHE).then((c) => c.add(new Request(OFFLINE, { cache: 'reload' }))).then(() => self.skipWaiting()));
});

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches.keys()
      .then((names) => Promise.all(names.filter((n) => n !== CACHE).map((n) => caches.delete(n))))
      .then(() => self.clients.claim()),
  );
});

self.addEventListener('fetch', (event) => {
  if (event.request.mode !== 'navigate') return; // the network, as without a worker
  event.respondWith(
    fetch(event.request).catch(async () => (await caches.match(OFFLINE, { cacheName: CACHE })) || Response.error()),
  );
});
