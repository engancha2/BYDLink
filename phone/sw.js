/* Service worker mínimo: cachea el "cascarón" para que la PWA abra sin conexión. */
const CACHE = 'bydlink-v1';
const SHELL = ['./', './index.html', './style.css', './backend.js', './webrtc.js', './app.js',
               './manifest.webmanifest', './icon.svg'];

self.addEventListener('install', e => {
  e.waitUntil(caches.open(CACHE).then(c => c.addAll(SHELL)).then(() => self.skipWaiting()));
});
self.addEventListener('activate', e => {
  e.waitUntil(caches.keys().then(ks => Promise.all(ks.filter(k => k !== CACHE).map(k => caches.delete(k)))).then(() => self.clients.claim()));
});
self.addEventListener('fetch', e => {
  const url = new URL(e.request.url);
  // Datos en tiempo real (Firebase) y mapas: siempre a la red.
  if (url.hostname.indexOf('firebase') !== -1 || url.hostname.indexOf('googleapis') !== -1 ||
      url.hostname.indexOf('tile.openstreetmap') !== -1) return;
  e.respondWith(caches.match(e.request).then(r => r || fetch(e.request)));
});
