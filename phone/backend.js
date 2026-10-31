/* BYDLink · capa de backend
 * Dos implementaciones con la MISMA interfaz:
 *   - DemoBackend     : coche simulado dentro del navegador (sin internet ni Firebase).
 *   - FirebaseBackend : lee/escribe en Realtime Database (coche real).
 * app.js no sabe cuál usa: llama siempre a Backend.*.
 */
(function () {
  'use strict';

  // ---- ¿Hay config de Firebase válida? ----
  const cfg = window.FIREBASE_CONFIG;
  const hasFirebase =
    cfg && typeof cfg.apiKey === 'string' && cfg.apiKey.indexOf('PON_AQUI') === -1 &&
    typeof firebase !== 'undefined';

  // =====================================================================
  //  DEMO — coche simulado
  // =====================================================================
  function DemoBackend() {
    const listeners = { status: [], config: [], seats: [], cameras: [], meta: [], trips: [], sentry: [], charging: [] };
    const emit = (k, v) => listeners[k].forEach(cb => { try { cb(v); } catch (e) { console.error(e); } });

    // Ruta de demo: un bucle por Barcelona.
    const base = [41.3870, 2.1700];
    const route = [];
    for (let i = 0; i < 80; i++) {
      const a = (i / 80) * Math.PI * 2;
      route.push([
        base[0] + 0.012 * Math.sin(a) + 0.002 * Math.sin(a * 3),
        base[1] + 0.016 * Math.cos(a) + 0.003 * Math.cos(a * 2)
      ]);
    }
    let rp = 0;

    const meta = { name: 'Mi BYD', model: 'BYD (demo)', online: true, lastSeen: Date.now(), appVersion: '1.0-demo' };
    const status = {
      ts: Date.now(), soc: 72, charging: false, chargingPower: 0, chargeRestMin: 0,
      gear: 'P', speed: 0, locked: true,
      doors: { fl: false, fr: false, rl: false, rr: false, trunk: false, hood: false },
      windows: { fl: false, fr: true, rl: false, rr: false, sunroof: false },
      tyres: { fl: 2.4, fr: 2.4, rl: 2.5, rr: 2.3 },
      seatbelts: { driver: true, passenger: false },
      outTemp: 19, tempUnit: 'C', odometer: 18452,
      rangeKm: 331, power: 0, consumptionKwh100: 14.2, voltage: 402,
      location: { lat: base[0], lng: base[1], heading: 0, ts: Date.now() }
    };
    const config = {
      sentry: { on: true, workMode: 'standard', sensitivity: 5, proximityDetection: true, shadowFilter: true },
      dashcam: { on: true, hasOEM: true, oemLimitEnabled: true, oemStorageLimit: 32 },
      notifications: { enabled: true, flash: false, gif: true, sentryMovement: true, doorOpenClose: true },
      overlays: { soc: true, seatbelt: false, speed: true },
      safeLocations: { enabled: true },
      automations: { autoCloseWindowsOnLock: true }
    };
    const seats = { driverStage: 0, passengerStage: 0 };
    const cameras = { count: 4, list: [
      { id: 0, tag: 'front', name: 'Frontal' }, { id: 1, tag: 'back', name: 'Trasera' },
      { id: 2, tag: 'left', name: 'Izquierda' }, { id: 3, tag: 'right', name: 'Derecha' }
    ]};

    // Viajes de ejemplo.
    const now = Date.now();
    const trips = [
      { id: 't3', start: now - 3600e3 * 3, end: now - 3600e3 * 2.6, distanceKm: 12.4, durationSec: 1440, startAddr: 'Casa', endAddr: 'Trabajo' },
      { id: 't2', start: now - 3600e3 * 26, end: now - 3600e3 * 25.5, distanceKm: 8.1, durationSec: 1800, startAddr: 'Trabajo', endAddr: 'Gimnasio' },
      { id: 't1', start: now - 3600e3 * 50, end: now - 3600e3 * 49, distanceKm: 23.7, durationSec: 2700, startAddr: 'Casa', endAddr: 'Aeropuerto' }
    ];
    function tripPoints(id) {
      const seedByTrip = { t1: 0, t2: 20, t3: 45 };
      const s = seedByTrip[id] || 0, n = 24, pts = [];
      for (let i = 0; i < n; i++) {
        const p = route[(s + i) % route.length];
        pts.push({ lat: p[0], lng: p[1], t: now - (n - i) * 60e3, speed: 20 + Math.round(30 * Math.abs(Math.sin(i))) });
      }
      return pts;
    }

    const sentryEvents = [
      { id: 's2', ts: now - 1800e3, camera: 'front', thumbUrl: null, videoId: 'v2' },
      { id: 's1', ts: now - 7200e3, camera: 'left',  thumbUrl: null, videoId: 'v1' }
    ];

    // Historial de ciclos de carga (como Electro). type: 'AC' lento / 'DC' rápido.
    const chargingSessions = [
      { id: 'c6', start: now - 36e5 * 10,  end: now - 36e5 * 9.2, socStart: 38, socEnd: 80, kwh: 18.1, durationMin: 48, type: 'AC', place: 'Casa',       costEur: 2.9 },
      { id: 'c5', start: now - 36e5 * 34,  end: now - 36e5 * 33.7,socStart: 22, socEnd: 62, kwh: 17.3, durationMin: 18, type: 'DC', place: 'Iberdrola',  costEur: 8.6 },
      { id: 'c4', start: now - 36e5 * 58,  end: now - 36e5 * 57.1,socStart: 45, socEnd: 90, kwh: 19.4, durationMin: 54, type: 'AC', place: 'Casa',       costEur: 3.1 },
      { id: 'c3', start: now - 36e5 * 82,  end: now - 36e5 * 81.8,socStart: 30, socEnd: 55, kwh: 10.8, durationMin: 12, type: 'DC', place: 'Repsol',     costEur: 5.4 },
      { id: 'c2', start: now - 36e5 * 110, end: now - 36e5 * 109,  socStart: 20, socEnd: 95, kwh: 32.3, durationMin: 61, type: 'AC', place: 'Casa',       costEur: 5.2 },
      { id: 'c1', start: now - 36e5 * 140, end: now - 36e5 * 139.3,socStart: 50, socEnd: 85, kwh: 15.1, durationMin: 42, type: 'AC', place: 'Trabajo',    costEur: 0.0 }
    ];

    function anyWindowOpen() { const w = status.windows; return w.fl || w.fr || w.rl || w.rr || w.sunroof; }
    function setAllWindows(open) { status.windows = { fl: open, fr: open, rl: open, rr: open, sunroof: open }; emit('status', status); }

    // Bucle de simulación.
    setInterval(() => {
      if (status.gear === 'D') {
        rp = (rp + 1) % route.length;
        const p = route[rp];
        status.location = { lat: p[0], lng: p[1], heading: (status.location.heading + 8) % 360, ts: Date.now() };
        status.speed = 25 + Math.round(20 * Math.abs(Math.sin(rp / 4)));
        status.soc = Math.max(5, +(status.soc - 0.03).toFixed(2));
        status.power = 8 + Math.round(status.speed * 0.6);
      } else {
        status.speed = 0;
        if (status.charging) {
          status.soc = Math.min(100, +(status.soc + 0.2).toFixed(2));
          status.chargeRestMin = Math.max(0, Math.round((100 - status.soc) * 3));
          status.chargingPower = 7; status.power = -7;
        } else status.power = 0;
      }
      status.rangeKm = Math.round(status.soc * 4.6);
      status.voltage = 380 + Math.round(status.soc * 0.4);
      status.ts = Date.now();
      emit('status', status);
    }, 1500);

    // Evento de centinela de vez en cuando.
    setInterval(() => {
      if (!config.sentry.on) return;
      if (Math.random() < 0.25) {
        const cams = ['front', 'back', 'left', 'right'];
        const ev = { id: 's' + Date.now(), ts: Date.now(), camera: cams[(Math.random() * 4) | 0], thumbUrl: null, videoId: 'v' + Date.now() };
        sentryEvents.unshift(ev);
        emit('sentry', sentryEvents.slice());
      }
    }, 12000);

    return {
      mode: 'demo',
      carId: 'demo',
      init() {
        setTimeout(() => {
          emit('meta', meta); emit('status', status); emit('config', config);
          emit('seats', seats); emit('cameras', cameras);
          emit('trips', trips.slice()); emit('sentry', sentryEvents.slice());
          emit('charging', chargingSessions.slice());
        }, 150);
        return Promise.resolve();
      },
      onMeta(cb) { listeners.meta.push(cb); },
      onCharging(cb) { listeners.charging.push(cb); },
      onStatus(cb) { listeners.status.push(cb); },
      onConfig(cb) { listeners.config.push(cb); },
      onSeats(cb) { listeners.seats.push(cb); },
      onCameras(cb) { listeners.cameras.push(cb); },
      onTrips(cb) { listeners.trips.push(cb); },
      onSentryEvents(cb) { listeners.sentry.push(cb); },
      getTripPoints(id) { return Promise.resolve(tripPoints(id)); },

      sendCommand(action, payload) {
        payload = payload || {};
        switch (action) {
          case 'setLock':
            status.locked = !!payload.locked;
            // Automatización: al cerrar el coche, subir ventanas (lo que el Surf trae capado)
            if (status.locked && config.automations.autoCloseWindowsOnLock && anyWindowOpen()) setAllWindows(false);
            break;
          case 'openWindows': setAllWindows(true); break;
          case 'closeWindows': setAllWindows(false); break;
          case 'setAutomation':
            if (payload.key) { config.automations[payload.key] = !!payload.value; emit('config', config); }
            break;
          case 'setClimate': config._climateOn = !!payload.on; status._climateTemp = payload.temp; break;
          case 'setSeatComfort':
            if (payload.driver !== undefined) seats.driverStage = clamp(payload.driver, -2, 2);
            if (payload.passenger !== undefined) seats.passengerStage = clamp(payload.passenger, -2, 2);
            emit('seats', seats); break;
          case 'setSentryConfig':
            Object.assign(config.sentry, payload); emit('config', config); break;
          case 'setDashcamConfig':
            Object.assign(config.dashcam, payload); emit('config', config); break;
          case 'setConfig':
            deepMerge(config, payload); emit('config', config); break;
          case 'drive': // ayuda de demo: arranca/para el coche simulado
            status.gear = payload.on ? 'D' : 'P'; break;
          default: break;
        }
        emit('status', status);
        return Promise.resolve({ ok: true, data: {} });
      },

      // Cámara de demo: vídeo sintético con canvas (no necesita WebRTC).
      getDemoCameraStream(camera) {
        return makeSyntheticStream(camera, status);
      }
    };
  }

  // =====================================================================
  //  FIREBASE — coche real
  // =====================================================================
  function FirebaseBackend() {
    firebase.initializeApp(cfg);
    const auth = firebase.auth();
    const db = firebase.database();
    let carId = null;
    try { carId = localStorage.getItem('bydlink_carId'); } catch (e) {}
    carId = carId || window.CAR_ID || 'mi-byd';
    const root = db.ref('cars/' + carId);

    // Login real (email / Google). authReady resuelve cuando hay un usuario autenticado.
    let _resolveAuth; const authReady = new Promise(r => { _resolveAuth = r; });
    auth.onAuthStateChanged(u => { if (u) { writeProfile(u); _resolveAuth(u); } });

    function writeProfile(u) {
      try {
        const ref = db.ref('users/' + u.uid);
        ref.update({
          email: u.email || null,
          name: u.displayName || null,
          provider: (u.providerData && u.providerData[0] && u.providerData[0].providerId) || 'password',
          lastLoginAt: firebase.database.ServerValue.TIMESTAMP,
          lang: navigator.language || null,
          tz: (Intl.DateTimeFormat().resolvedOptions().timeZone) || null
        });
        ref.child('createdAt').once('value').then(s => {
          if (!s.exists()) ref.child('createdAt').set(firebase.database.ServerValue.TIMESTAMP);
        }).catch(() => {});
      } catch (e) {}
    }

    function sub(path, cb, asList) {
      root.child(path).on('value', snap => {
        const val = snap.val();
        if (asList) {
          const arr = [];
          snap.forEach(c => { const o = c.val() || {}; o.id = c.key; arr.push(o); });
          cb(arr);
        } else if (val !== null) cb(val);
      });
    }

    return {
      mode: 'firebase',
      carId,
      requiresAuth: true,
      onAuth(cb) { auth.onAuthStateChanged(cb); },
      currentUser() { return auth.currentUser; },
      register(email, pw) { return auth.createUserWithEmailAndPassword(email, pw).then(c => { writeProfile(c.user); return c.user; }); },
      login(email, pw) { return auth.signInWithEmailAndPassword(email, pw); },
      loginGoogle() { return auth.signInWithPopup(new firebase.auth.GoogleAuthProvider()); },
      logout() { return auth.signOut(); },
      resetPassword(email) { return auth.sendPasswordResetEmail(email); },
      init() { return authReady; },
      onMeta(cb) { sub('meta', cb); },
      onStatus(cb) { sub('status', cb); },
      onConfig(cb) { sub('config', cb); },
      onSeats(cb) { sub('seats', cb); },
      onCameras(cb) { sub('cameras', cb); },
      onTrips(cb) { root.child('trips').orderByChild('start').on('value', s => { const a = []; s.forEach(c => { const o = c.val(); o.id = c.key; a.unshift(o); }); cb(a); }); },
      onSentryEvents(cb) { root.child('sentryEvents').orderByChild('ts').limitToLast(50).on('value', s => { const a = []; s.forEach(c => { const o = c.val(); o.id = c.key; a.unshift(o); }); cb(a); }); },
      onCharging(cb) { root.child('chargingSessions').orderByChild('start').limitToLast(100).on('value', s => { const a = []; s.forEach(c => { const o = c.val(); o.id = c.key; a.unshift(o); }); cb(a); }); },
      getTripPoints(id) { return root.child('tripPoints/' + id).once('value').then(s => s.val() || []); },

      sendCommand(action, payload) {
        const ref = root.child('commands').push();
        const id = ref.key;
        ref.set({ action, payload: payload || {}, ts: firebase.database.ServerValue.TIMESTAMP });
        return new Promise((resolve) => {
          const rref = root.child('responses/' + id);
          const to = setTimeout(() => { rref.off(); resolve({ ok: false, error: 'timeout' }); }, 10000);
          rref.on('value', s => {
            const v = s.val();
            if (v) { clearTimeout(to); rref.off(); resolve(v); }
          });
        });
      },

      // Acceso a la señalización WebRTC para webrtc.js
      signalRef(session) { return root.child('signaling/' + session); },
      serverTime() { return firebase.database.ServerValue.TIMESTAMP; },

      // --- Emparejamiento multiusuario ---
      pair(code) {
        code = (code || '').trim().toUpperCase();
        if (!code) return Promise.reject(new Error('Escribe un código'));
        return authReady
          .then(() => db.ref('pairing/' + code).once('value'))
          .then(snap => {
            const v = snap.val();
            if (!v || !v.carId) throw new Error('Código no válido');
            const uid = auth.currentUser && auth.currentUser.uid;
            return db.ref('access/' + v.carId + '/' + uid).set(true).then(() => {
              try { localStorage.setItem('bydlink_carId', v.carId); } catch (e) {}
              return v.carId;
            });
          });
      },
      unpair() { try { localStorage.removeItem('bydlink_carId'); } catch (e) {} }
    };
  }

  // ---- utilidades ----
  function clamp(v, a, b) { return Math.max(a, Math.min(b, v)); }
  function deepMerge(t, s) { for (const k in s) { if (s[k] && typeof s[k] === 'object' && !Array.isArray(s[k])) { t[k] = t[k] || {}; deepMerge(t[k], s[k]); } else t[k] = s[k]; } return t; }

  // Vídeo sintético para la demo de cámara.
  function makeSyntheticStream(camera, status) {
    const c = document.createElement('canvas'); c.width = 640; c.height = 360;
    const ctx = c.getContext('2d');
    const labels = { front: 'FRONTAL', back: 'TRASERA', left: 'IZQUIERDA', right: 'DERECHA' };
    let t = 0;
    const timer = setInterval(() => {
      t += 1;
      const g = ctx.createLinearGradient(0, 0, 0, 360);
      g.addColorStop(0, '#0a1b2a'); g.addColorStop(1, '#06303a');
      ctx.fillStyle = g; ctx.fillRect(0, 0, 640, 360);
      // "suelo" en movimiento
      ctx.strokeStyle = 'rgba(54,163,255,.25)';
      for (let i = 0; i < 12; i++) {
        const y = 180 + ((i * 20 + t * 2) % 180);
        ctx.beginPath(); ctx.moveTo(0, y); ctx.lineTo(640, y); ctx.stroke();
      }
      // objeto que cruza (simula movimiento detectado)
      const x = (t * 3) % 700 - 30;
      ctx.fillStyle = 'rgba(47,210,122,.85)';
      ctx.fillRect(x, 150, 40, 70);
      // HUD
      ctx.fillStyle = '#fff'; ctx.font = 'bold 22px system-ui';
      ctx.fillText(labels[camera] || camera, 16, 34);
      ctx.font = '14px system-ui'; ctx.fillStyle = 'rgba(255,255,255,.7)';
      ctx.fillText(new Date().toLocaleTimeString(), 16, 340);
      if (status && status.soc) ctx.fillText('SOC ' + Math.round(status.soc) + '%', 540, 340);
      ctx.fillStyle = 'rgba(255,60,60,.9)'; ctx.beginPath(); ctx.arc(620, 26, 6, 0, 7); ctx.fill();
    }, 66);
    const stream = c.captureStream(15);
    stream.__stop = () => clearInterval(timer);
    return stream;
  }

  // =====================================================================
  //  CLOUD — backend provisional (Node local) vía REST
  // =====================================================================
  function CloudBackend() {
    const api = (window.BYDLINK_API || '') + '/api';
    const listeners = { status: [], config: [], seats: [], cameras: [], meta: [], trips: [], sentry: [], charging: [] };
    const last = {};
    let curStatus = {};
    const emit = (k, v) => listeners[k].forEach(cb => { try { cb(v); } catch (e) { console.error(e); } });

    function fireIfChanged(key, val) {
      if (val === undefined) return;
      const j = JSON.stringify(val);
      if (j !== last[key]) { last[key] = j; emit(key, val); }
    }
    async function poll() {
      try {
        const r = await fetch(api + '/state', { cache: 'no-store' });
        if (!r.ok) return;
        const st = await r.json();
        fireIfChanged('meta', st.meta);
        fireIfChanged('cameras', st.cameras);
        fireIfChanged('config', st.config);
        fireIfChanged('seats', st.seats);
        fireIfChanged('trips', st.trips);
        fireIfChanged('sentry', st.sentryEvents);
        fireIfChanged('charging', st.chargingSessions);
        if (st.status) { curStatus = st.status; emit('status', st.status); }
      } catch (e) { /* servidor no disponible: reintenta en el próximo tick */ }
    }

    return {
      mode: 'cloud', carId: 'cloud (local)',
      init() { poll(); setInterval(poll, 1500); return Promise.resolve(); },
      onMeta(cb) { listeners.meta.push(cb); },
      onStatus(cb) { listeners.status.push(cb); },
      onConfig(cb) { listeners.config.push(cb); },
      onSeats(cb) { listeners.seats.push(cb); },
      onCameras(cb) { listeners.cameras.push(cb); },
      onTrips(cb) { listeners.trips.push(cb); },
      onSentryEvents(cb) { listeners.sentry.push(cb); },
      onCharging(cb) { listeners.charging.push(cb); },
      getTripPoints(id) { return fetch(api + '/trip/' + id + '/points').then(r => r.json()).catch(() => []); },
      sendCommand(action, payload) {
        return fetch(api + '/command', { method: 'POST', headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ action: action, payload: payload || {} }) })
          .then(r => r.json()).then(res => { poll(); return res; })
          .catch(e => ({ ok: false, error: String(e) }));
      },
      // La cámara en vivo real llega en Fase 2 (coche). De momento, vídeo sintético.
      getDemoCameraStream(camera) { return makeSyntheticStream(camera, curStatus); }
    };
  }

  // ---- elegir implementación ----
  const isHttp = location.protocol === 'http:' || location.protocol === 'https:';
  window.Backend = hasFirebase ? FirebaseBackend() : (isHttp ? CloudBackend() : DemoBackend());
  document.documentElement.dataset.backend = window.Backend.mode;
})();
