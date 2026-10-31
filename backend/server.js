/* BYDLink · backend PROVISIONAL (datos inventados)
 * ------------------------------------------------------------------
 * Hace de intermediario entre la app del teléfono y "el coche", pero
 * de momento el coche es SIMULADO aquí dentro. Expone una API REST que
 * la app consume en su modo `cloud`. Cuando migremos:
 *   - a Firebase: la app usa su modo `firebase` y esto se retira.
 *   - a la nube BYD real: aquí se sustituye el simulador por BydClient (C#)
 *     o se llama a sus endpoints.
 * Formato de datos idéntico al de la demo del móvil (phone/backend.js).
 */
'use strict';
const express = require('express');
const path = require('path');
const os = require('os');

const app = express();
app.use(express.json());
// CORS permisivo (para abrir la app desde otro origen si hiciera falta)
app.use((req, res, next) => {
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Methods', 'GET,POST,OPTIONS');
  res.setHeader('Access-Control-Allow-Headers', 'Content-Type');
  if (req.method === 'OPTIONS') return res.sendStatus(204);
  next();
});

// ---------------------------------------------------------------
//  Estado del coche (inventado)
// ---------------------------------------------------------------
const base = [41.3870, 2.1700];
const route = [];
for (let i = 0; i < 80; i++) {
  const a = (i / 80) * Math.PI * 2;
  route.push([base[0] + 0.012 * Math.sin(a) + 0.002 * Math.sin(a * 3),
              base[1] + 0.016 * Math.cos(a) + 0.003 * Math.cos(a * 2)]);
}
let rp = 0;
const now = Date.now();

const db = {
  meta: { name: 'Mi BYD (Dolphin Surf)', model: 'BYD Dolphin Surf', online: true, lastSeen: now, appVersion: 'backend-0.1' },
  status: {
    ts: now, soc: 72, charging: false, chargingPower: 0, chargeRestMin: 0,
    gear: 'P', speed: 0, locked: true,
    doors: { fl: false, fr: false, rl: false, rr: false, trunk: false, hood: false },
    windows: { fl: false, fr: true, rl: false, rr: false, sunroof: false },
    tyres: { fl: 2.4, fr: 2.4, rl: 2.5, rr: 2.3 },
    seatbelts: { driver: true, passenger: false },
    outTemp: 19, tempUnit: 'C', odometer: 18452,
    rangeKm: 331, power: 0, consumptionKwh100: 14.2, voltage: 402,
    location: { lat: base[0], lng: base[1], heading: 0, ts: now }
  },
  config: {
    sentry: { on: true, workMode: 'standard', sensitivity: 5, proximityDetection: true, shadowFilter: true },
    dashcam: { on: true, hasOEM: true, oemLimitEnabled: true, oemStorageLimit: 32 },
    notifications: { enabled: true, flash: false, gif: true, sentryMovement: true, doorOpenClose: true },
    overlays: { soc: true, seatbelt: false, speed: true },
    safeLocations: { enabled: true },
    automations: { autoCloseWindowsOnLock: true }
  },
  seats: { driverStage: 0, passengerStage: 0 },
  cameras: { count: 4, list: [
    { id: 0, tag: 'front', name: 'Frontal' }, { id: 1, tag: 'back', name: 'Trasera' },
    { id: 2, tag: 'left', name: 'Izquierda' }, { id: 3, tag: 'right', name: 'Derecha' }
  ]},
  trips: [
    { id: 't3', start: now - 36e5 * 3, end: now - 36e5 * 2.6, distanceKm: 12.4, durationSec: 1440, startAddr: 'Casa', endAddr: 'Trabajo' },
    { id: 't2', start: now - 36e5 * 26, end: now - 36e5 * 25.5, distanceKm: 8.1, durationSec: 1800, startAddr: 'Trabajo', endAddr: 'Gimnasio' },
    { id: 't1', start: now - 36e5 * 50, end: now - 36e5 * 49, distanceKm: 23.7, durationSec: 2700, startAddr: 'Casa', endAddr: 'Aeropuerto' }
  ],
  sentryEvents: [
    { id: 's2', ts: now - 1800e3, camera: 'front', thumbUrl: null, videoId: 'v2' },
    { id: 's1', ts: now - 7200e3, camera: 'left', thumbUrl: null, videoId: 'v1' }
  ],
  chargingSessions: [
    { id: 'c6', start: now - 36e5 * 10, end: now - 36e5 * 9.2, socStart: 38, socEnd: 80, kwh: 18.1, durationMin: 48, type: 'AC', place: 'Casa', costEur: 2.9 },
    { id: 'c5', start: now - 36e5 * 34, end: now - 36e5 * 33.7, socStart: 22, socEnd: 62, kwh: 17.3, durationMin: 18, type: 'DC', place: 'Iberdrola', costEur: 8.6 },
    { id: 'c4', start: now - 36e5 * 58, end: now - 36e5 * 57.1, socStart: 45, socEnd: 90, kwh: 19.4, durationMin: 54, type: 'AC', place: 'Casa', costEur: 3.1 },
    { id: 'c3', start: now - 36e5 * 82, end: now - 36e5 * 81.8, socStart: 30, socEnd: 55, kwh: 10.8, durationMin: 12, type: 'DC', place: 'Repsol', costEur: 5.4 },
    { id: 'c2', start: now - 36e5 * 110, end: now - 36e5 * 109, socStart: 20, socEnd: 95, kwh: 32.3, durationMin: 61, type: 'AC', place: 'Casa', costEur: 5.2 },
    { id: 'c1', start: now - 36e5 * 140, end: now - 36e5 * 139.3, socStart: 50, socEnd: 85, kwh: 15.1, durationMin: 42, type: 'AC', place: 'Trabajo', costEur: 0.0 }
  ]
};
const tripPointsCache = {};
function tripPoints(id) {
  if (tripPointsCache[id]) return tripPointsCache[id];
  const seed = { t1: 0, t2: 20, t3: 45 }[id] || 0, n = 24, pts = [];
  for (let i = 0; i < n; i++) {
    const p = route[(seed + i) % route.length];
    pts.push({ lat: p[0], lng: p[1], t: now - (n - i) * 60e3, speed: 20 + Math.round(30 * Math.abs(Math.sin(i))) });
  }
  return (tripPointsCache[id] = pts);
}

// ---------------------------------------------------------------
//  Simulación
// ---------------------------------------------------------------
const s = db.status;
let driving = false;
setInterval(() => {
  if (driving) {
    rp = (rp + 1) % route.length;
    const p = route[rp];
    s.location = { lat: p[0], lng: p[1], heading: (s.location.heading + 8) % 360, ts: Date.now() };
    s.speed = 25 + Math.round(20 * Math.abs(Math.sin(rp / 4)));
    s.gear = 'D';
    s.soc = Math.max(5, +(s.soc - 0.03).toFixed(2));
    s.power = 8 + Math.round(s.speed * 0.6);          // kW consumidos conduciendo
  } else {
    s.speed = 0;
    if (s.charging) { s.soc = Math.min(100, +(s.soc + 0.2).toFixed(2)); s.chargeRestMin = Math.max(0, Math.round((100 - s.soc) * 3)); s.chargingPower = 7; s.power = -s.chargingPower; }
    else s.power = 0;
  }
  s.rangeKm = Math.round(s.soc * 4.6);               // autonomía estimada
  s.voltage = 380 + Math.round(s.soc * 0.4);         // ~380-420 V según SOC
  s.ts = Date.now();
}, 1500);

setInterval(() => {
  if (!db.config.sentry.on) return;
  if (Math.random() < 0.25) {
    const cams = ['front', 'back', 'left', 'right'];
    db.sentryEvents.unshift({ id: 's' + Date.now(), ts: Date.now(), camera: cams[(Math.random() * 4) | 0], thumbUrl: null, videoId: 'v' + Date.now() });
    db.sentryEvents = db.sentryEvents.slice(0, 50);
  }
}, 12000);

// Arranca "en marcha" a los pocos segundos para ver el mapa moverse
setTimeout(() => { driving = true; s.gear = 'D'; }, 5000);

// ---------------------------------------------------------------
//  Lógica de comandos (incluye la automatización de ventanas)
// ---------------------------------------------------------------
function clamp(v, a, b) { return Math.max(a, Math.min(b, v)); }
function anyWindowOpen() { const w = s.windows; return w.fl || w.fr || w.rl || w.rr || w.sunroof; }
function setAllWindows(open) { s.windows = { fl: open, fr: open, rl: open, rr: open, sunroof: open }; }

function handle(action, payload) {
  payload = payload || {};
  switch (action) {
    case 'setLock':
      s.locked = !!payload.locked;
      if (s.locked && db.config.automations.autoCloseWindowsOnLock && anyWindowOpen()) setAllWindows(false);
      return { closedWindows: s.locked };
    case 'openWindows': setAllWindows(true); return {};
    case 'closeWindows': setAllWindows(false); return {};
    case 'setClimate': db.config._climateOn = !!payload.on; s._climateTemp = payload.temp; return {};
    case 'setSeatComfort':
      if (payload.driver !== undefined) db.seats.driverStage = clamp(payload.driver, -2, 2);
      if (payload.passenger !== undefined) db.seats.passengerStage = clamp(payload.passenger, -2, 2);
      return {};
    case 'setSentryConfig': Object.assign(db.config.sentry, payload); return {};
    case 'setDashcamConfig': Object.assign(db.config.dashcam, payload); return {};
    case 'setConfig': deepMerge(db.config, payload); return {};
    case 'setAutomation': if (payload.key) db.config.automations[payload.key] = !!payload.value; return {};
    case 'drive': driving = !!payload.on; s.gear = driving ? 'D' : 'P'; return {};
    default: return {};
  }
}
function deepMerge(t, src) { for (const k in src) { if (src[k] && typeof src[k] === 'object' && !Array.isArray(src[k])) { t[k] = t[k] || {}; deepMerge(t[k], src[k]); } else t[k] = src[k]; } return t; }

// ---------------------------------------------------------------
//  API
// ---------------------------------------------------------------
app.get('/api/ping', (req, res) => res.json({ ok: true, backend: 'bydlink-provisional' }));
app.get('/api/state', (req, res) => res.json({
  meta: db.meta, status: db.status, config: db.config, seats: db.seats,
  cameras: db.cameras, trips: db.trips, sentryEvents: db.sentryEvents, chargingSessions: db.chargingSessions
}));
app.get('/api/trip/:id/points', (req, res) => res.json(tripPoints(req.params.id)));
app.post('/api/command', (req, res) => {
  const { action, payload } = req.body || {};
  try { res.json({ ok: true, data: handle(action, payload) }); }
  catch (e) { res.status(500).json({ ok: false, error: String(e && e.message || e) }); }
});

// Sirve la app del teléfono en la raíz, para abrirla desde el móvil.
app.use('/', express.static(path.join(__dirname, '..', 'phone')));

const PORT = process.env.PORT || 8099;  // 3000 suele estar ocupado en este PC
app.listen(PORT, '0.0.0.0', () => {
  const ips = [].concat(...Object.values(os.networkInterfaces()))
    .filter(i => i.family === 'IPv4' && !i.internal).map(i => i.address);
  console.log('BYDLink backend (provisional) escuchando en el puerto ' + PORT);
  console.log('  PC:    http://localhost:' + PORT);
  ips.forEach(ip => console.log('  Móvil: http://' + ip + ':' + PORT + '   (misma WiFi)'));
});
