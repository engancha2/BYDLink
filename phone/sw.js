/* Service worker: "red primero" para los archivos propios (siempre la última versión
 * publicada) y caché solo como respaldo sin conexión. Firebase, mapas y CDNs van
 * directos a la red. */
const CACHE = 'bydlink-2.18';   // cambiarla junto con WEB_VERSION de app.js
const SHELL = ['./', './style.css', './backend.js', './webrtc.js', './app.js', './skins.js', './i18n.js', './extras.js', './videos.js', './history.js', './history.css', './keys.js', './autos.js', './firebase-config.js',
               './manifest.webmanifest', './icon.svg', './icon-192.png', './icon-512.png', './icon-maskable-512.png', './badge-96.png'];

self.addEventListener('install', e => {
  e.waitUntil(caches.open(CACHE)
    .then(c => Promise.all(SHELL.map(u => c.add(new Request(u, { cache: 'reload' })).catch(() => {}))))
    .then(() => self.skipWaiting()));
});

self.addEventListener('activate', e => {
  e.waitUntil((async () => {
    const keys = await caches.keys();
    const upgraded = keys.some(k => k !== CACHE && k.indexOf('bydlink-') === 0);
    await Promise.all(keys.filter(k => k !== CACHE).map(k => caches.delete(k)));
    await self.clients.claim();
    // La versión anterior servía archivos antiguos desde la caché: recargar las pestañas abiertas.
    if (upgraded) {
      const wins = await self.clients.matchAll({ type: 'window' });
      wins.forEach(w => { try { w.navigate(w.url); } catch (err) {} });
    }
  })());
});

self.addEventListener('fetch', e => {
  const req = e.request;
  if (req.method !== 'GET') return;
  const url = new URL(req.url);
  if (url.origin !== self.location.origin) return;
  if (url.pathname.indexOf('/descargas/') !== -1) return;   // el APK va directo, sin caché
  // Páginas propias de Firebase (login con Google: /__/auth/handler, /__/auth/iframe, /__/firebase/init.json): desde que
  // authDomain es app.bydhack.com pasan por aquí; directas a la red, sin caché ni la portada de la app como respaldo.
  if (url.pathname.indexOf('/__/') === 0) return;
  e.respondWith(
    fetch(req, { cache: 'no-cache' }).then(res => {
      if (res.ok && res.type === 'basic' && !res.redirected) {
        const copy = res.clone();
        caches.open(CACHE).then(c => c.put(req, copy)).catch(() => {});
      }
      return res;
    }).catch(() => caches.match(req, { ignoreSearch: true })
      .then(r => r || (req.mode === 'navigate' ? caches.match('./') : Response.error())))
  );
});

// Avisos que manda el propio coche (Web Push, ver autos.js y protocol/automatizaciones.md): se enseñan aunque la web
// esté cerrada; al tocarlos se abre BYDLink en la sección del aviso.
self.addEventListener('push', e => {
  let d = {};
  try { d = e.data ? e.data.json() : {}; } catch (err) { d = { b: e.data ? e.data.text() : '' }; }
  e.waitUntil(self.registration.showNotification(d.t || 'BYDLink', {
    body: d.b || '', tag: d.tag || undefined, renotify: !!d.tag, timestamp: d.ts || Date.now(),
    icon: './icon-192.png', badge: './badge-96.png', data: { url: d.u || './' }
  }));
});

self.addEventListener('notificationclick', e => {
  e.notification.close();
  const url = new URL((e.notification.data && e.notification.data.url) || './', self.registration.scope).href;
  e.waitUntil(self.clients.matchAll({ type: 'window', includeUncontrolled: true }).then(wins => {
    const w = wins.find(x => x.url.indexOf(self.registration.scope) === 0);
    if (!w) return self.clients.openWindow(url);
    return w.focus().then(f => { try { return (f || w).navigate(url); } catch (err) { return null; } }).catch(() => null);
  }));
});
