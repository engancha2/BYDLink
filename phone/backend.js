/* BYDLink · capa de datos
 * Tres implementaciones con la MISMA interfaz (app.js no sabe cuál usa):
 *   - DemoBackend     : coche simulado en el navegador (?demo o sin Firebase en file://).
 *   - CloudBackend    : servidor Node local de pruebas (backend/), por REST.
 *   - FirebaseBackend : Realtime Database + Auth (producción, coche real).
 *
 * Interfaz:
 *   mode, requiresAuth
 *   onAuth(cb) login register loginGoogle logout resetPassword     (solo Firebase)
 *   listCars() -> Promise<[{id,name}]>   savedCar() saveCar(id)   useCar(id)
 *   pair(code) -> Promise<carId>   unpair(id)   renameCar(id, name)
 *   watch({meta,status,config,seats,cameras,trips,sentry,charging,net,error})
 *   sendCommand(action, payload) -> Promise<{ok, error?, data?}>
 *   getTripPoints(id)   signalRef(session)   getDemoCameraStream(cam)?   carId
 */
(function () {
  'use strict';

  const params = new URLSearchParams(location.search);
  const isLocal = location.hostname === 'localhost' || location.hostname === '127.0.0.1';
  const cfg = window.FIREBASE_CONFIG;
  const hasFirebase =
    cfg && typeof cfg.apiKey === 'string' && cfg.apiKey.indexOf('PON_AQUI') === -1 &&
    typeof firebase !== 'undefined';

  function lsGet(k) { try { return localStorage.getItem(k); } catch (e) { return null; } }
  function lsSet(k, v) { try { if (v == null) localStorage.removeItem(k); else localStorage.setItem(k, v); } catch (e) {} }
  const savedCar = () => lsGet('bydlink_car') || lsGet('bydlink_carId');
  const saveCar = id => { lsSet('bydlink_car', id); lsSet('bydlink_carId', null); };
  const call = (h, k, v) => { if (h && typeof h[k] === 'function') { try { h[k](v); } catch (e) { console.error(e); } } };

  // =====================================================================
  //  DEMO — coche simulado
  // =====================================================================
  function DemoBackend() {
    let h = {};
    const emit = (k, v) => call(h, k, v);

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
    let rp = 0, driveTicks = 0;

    const meta = { name: 'Dolphin Surf', model: 'BYD Dolphin Surf (demo)', online: true, lastSeen: Date.now(), appVersion: 'demo' };
    const status = {
      ts: Date.now(), soc: 72, charging: false, chargingPower: 0, chargeRestMin: 0,
      gear: 'P', speed: 0, locked: true,
      doors: { fl: false, fr: false, rl: false, rr: false, trunk: false, hood: false },
      windows: { fl: false, fr: true, rl: false, rr: false, sunroof: false },
      tyres: { fl: 2.4, fr: 2.4, rl: 2.5, rr: 2.1 },
      seatbelts: { driver: false, passenger: false },
      outTemp: 19, tempUnit: 'C', odometer: 18452,
      rangeKm: 331, power: 0, consumptionKwh100: 14.2, voltage: 402,
      location: { lat: base[0], lng: base[1], heading: 0, ts: Date.now() },
      // Campos ampliados (protocol/telemetria.md)
      powerState: 'ON', alarm: false, bmsState: 15, chargingGun: 'none', soh: 98, batteryTempMax: 24, batteryTempMin: 21,
      aux12v: 12.7, inTemp: 22, tyresState: { fl: 0, fr: 0, rl: 0, rr: 2 },
      climate: { on: false, temp: 21, fan: 3, recirc: false, defrostFront: false, defrostRear: false, auto: true }
    };
    const config = {
      sentry: { on: true, workMode: 'standard', sensitivity: 5, proximityDetection: true, shadowFilter: true },
      automations: { autoCloseWindowsOnLock: true }
    };
    const seats = { driverStage: 0, passengerStage: 0 };

    const now = Date.now();
    const trips = [
      { id: 't3', start: now - 36e5 * 3, end: now - 36e5 * 2.6, distanceKm: 12.4, durationSec: 1440, startAddr: 'Casa', endAddr: 'Trabajo' },
      { id: 't2', start: now - 36e5 * 26, end: now - 36e5 * 25.5, distanceKm: 8.1, durationSec: 1800, startAddr: 'Trabajo', endAddr: 'Gimnasio' },
      { id: 't1', start: now - 36e5 * 50, end: now - 36e5 * 49, distanceKm: 23.7, durationSec: 2700, startAddr: 'Casa', endAddr: 'Aeropuerto' }
    ];
    function tripPoints(id) {
      const s = ({ t1: 0, t2: 20, t3: 45 })[id] || 0, n = 24, pts = [];
      for (let i = 0; i < n; i++) {
        const p = route[(s + i) % route.length];
        pts.push({ lat: p[0], lng: p[1], t: now - (n - i) * 60e3, speed: 20 + Math.round(30 * Math.abs(Math.sin(i))) });
      }
      return pts;
    }
    // Cámara de la demo: imágenes sintéticas, igual que las que manda el coche.
    const demoCam = {
      timer: null, cam: 'front', seq: 0, onLive: null, onPhoto: null,
      start(cam) {
        this.cam = cam; clearInterval(this.timer);
        this.timer = setInterval(() => {
          if (this.onLive) this.onLive({ ts: Date.now(), seq: ++this.seq, camera: this.cam, jpeg: demoJpeg(this.cam, status, 480, 300) });
        }, 500);
      },
      stop() { clearInterval(this.timer); this.timer = null; },
      photo(cam) { if (this.onPhoto) this.onPhoto({ ts: Date.now(), camera: cam, w: 960, h: 600, jpeg: demoJpeg(cam, status, 960, 600) }); }
    };
    const H = 3600e3, now0 = Date.now();
    const demoVid = {
      cfg: { store: 'internal', current: 'internal', mode: 'off', quality: 'normal', quotaGb: 0, stamp: true, segMin: 3 },
      rec: null, req: null, onStatus: null, onList: null,
      items: [
        { id: 'Centinela/demo/1', kind: 'centinela', camera: 'back', ts: now0 - 0.6 * H, bytes: 2.3e6, volume: 'internal' },
        { id: 'Dashcam/demo/2', kind: 'dashcam', camera: 'back', ts: now0 - 2.2 * H, bytes: 29e6, volume: 'internal' },
        { id: 'Dashcam/demo/3', kind: 'dashcam', camera: 'back', ts: now0 - 2.25 * H, bytes: 29e6, volume: 'internal' },
        { id: 'Grabaciones/demo/4', kind: 'manual', camera: 'back', ts: now0 - 26 * H, bytes: 9.4e6, volume: 'A1B2-C3D4' },
        { id: 'Centinela/demo/5', kind: 'centinela', camera: 'back', ts: now0 - 27 * H, bytes: 2.1e6, volume: 'internal' }
      ],
      status() {
        return Object.assign({}, this.cfg, {
          volumes: [
            { id: 'internal', kind: 'internal', label: 'Memoria del coche', free: 23.4e9, total: 64e9, used: 1.2e9, lasting: true },
            { id: 'A1B2-C3D4', kind: 'usb', label: 'Memoria USB', free: 58.1e9, total: 64e9, used: 9.4e6, lasting: false }
          ],
          quotaBytes: 8 * 1073741824, recording: this.rec ? [this.rec] : [], dashcam: { recording: this.cfg.mode === 'parked' }
        });
      },
      emit() { if (this.onStatus) this.onStatus(this.status()); if (this.onList) this.onList({ items: this.items.slice() }); }
    };
    // Automatizaciones y avisos de ejemplo (ver autos.js).
    const demoAuto = {
      rules: {
        a1: { name: 'Al apagar el coche, activa el centinela', on: true, when: { type: 'acc', on: false }, do: [{ action: 'setSentryConfig', payload: { on: true } }], cooldownMin: 5, created: now0 - 50 * H },
        a2: { name: 'Si la batería baja del 20 %, avísame', on: true, when: { type: 'soc', op: 'below', value: 20 }, do: [{ action: 'notify', payload: { text: 'Al coche le queda poca batería.' } }], cooldownMin: 60, created: now0 - 40 * H },
        a3: { name: 'Aire a 21° entre semana', on: false, when: { type: 'time', at: '08:00', days: [1, 2, 3, 4, 5] }, do: [{ action: 'bydClimate', payload: { on: true, temp: 21, minutes: 15 } }], cooldownMin: 5, created: now0 - 30 * H }
      },
      states: { a1: { last: now0 - 3 * H, ok: true, why: 'Has apagado el coche' } },
      log: [{ id: 'l1', ts: now0 - 3 * H, rule: 'a1', name: 'Al apagar el coche, activa el centinela', why: 'Has apagado el coche', ok: true,
              results: [{ action: 'setSentryConfig', label: 'Activar el centinela', ok: true }] }],
      subs: {}, on: [],
      emit() { const r = JSON.parse(JSON.stringify(this.rules)); this.on.forEach(f => f(r)); }
    };
    const sentryEvents = [
      { id: 's2', ts: now - 1800e3, camera: 'front' },
      { id: 's1', ts: now - 7200e3, camera: 'left' },
      { id: 's0', ts: now - 36e5 * 27, camera: 'back' }
    ];
    const chargingSessions = [
      { id: 'c6', start: now - 36e5 * 10,  socStart: 38, socEnd: 80, kwh: 18.1, durationMin: 48, type: 'AC', place: 'Casa',      costEur: 2.9 },
      { id: 'c5', start: now - 36e5 * 34,  socStart: 22, socEnd: 62, kwh: 17.3, durationMin: 18, type: 'DC', place: 'Iberdrola', costEur: 8.6 },
      { id: 'c4', start: now - 36e5 * 58,  socStart: 45, socEnd: 90, kwh: 19.4, durationMin: 54, type: 'AC', place: 'Casa',      costEur: 3.1 },
      { id: 'c3', start: now - 36e5 * 82,  socStart: 30, socEnd: 55, kwh: 10.8, durationMin: 12, type: 'DC', place: 'Repsol',    costEur: 5.4 },
      { id: 'c2', start: now - 36e5 * 110, socStart: 20, socEnd: 95, kwh: 32.3, durationMin: 61, type: 'AC', place: 'Casa',      costEur: 5.2 },
      { id: 'c1', start: now - 36e5 * 140, socStart: 50, socEnd: 85, kwh: 15.1, durationMin: 42, type: 'AC', place: 'Trabajo',   costEur: 0.0 }
    ];

    const anyWindowOpen = () => Object.keys(status.windows).some(k => status.windows[k]);
    const setAllWindows = open => { status.windows = { fl: open, fr: open, rl: open, rr: open, sunroof: false }; };

    // Bucle de simulación: aparcado unos segundos, da una vuelta y vuelve a aparcar.
    setInterval(() => {
      driveTicks++;
      const driving = (driveTicks % 60) > 20 && (driveTicks % 60) < 50;
      status.gear = driving ? 'D' : 'P';
      if (driving) {
        rp = (rp + 1) % route.length;
        const p = route[rp];
        status.location = { lat: p[0], lng: p[1], heading: (status.location.heading + 8) % 360, ts: Date.now() };
        status.speed = 25 + Math.round(20 * Math.abs(Math.sin(rp / 4)));
        status.soc = Math.max(5, +(status.soc - 0.03).toFixed(2));
        status.power = 8 + Math.round(status.speed * 0.6);
      } else { status.speed = 0; status.power = 0; }
      status.rangeKm = Math.round(status.soc * 4.6);
      status.voltage = 380 + Math.round(status.soc * 0.4);
      status.ts = Date.now();
      meta.lastSeen = status.ts;
      emit('status', status);
    }, 1500);

    setInterval(() => {
      if (!config.sentry.on || status.gear !== 'P' || Math.random() > 0.3) return;
      const cams = ['front', 'back', 'left', 'right'];
      sentryEvents.unshift({ id: 's' + Date.now(), ts: Date.now(), camera: cams[(Math.random() * 4) | 0], hasThumb: true });
      emit('sentry', sentryEvents.slice());
    }, 15000);

    return {
      mode: 'demo', requiresAuth: false, carId: 'demo',
      listCars() { return Promise.resolve([{ id: 'demo', name: meta.name }]); },
      savedCar() { return 'demo'; }, saveCar() {}, useCar() {},
      pair() { return Promise.reject(new Error('En la demo no se pueden vincular coches.')); },
      unpair() { return Promise.resolve(); },
      renameCar(id, name) { meta.name = name; emit('meta', meta); return Promise.resolve(); },
      watch(handlers) {
        h = handlers || {};
        setTimeout(() => {
          emit('net', true);
          emit('meta', meta); emit('status', status); emit('config', config); emit('seats', seats);
          emit('trips', trips.slice()); emit('sentry', sentryEvents.slice()); emit('charging', chargingSessions.slice());
        }, 120);
      },
      getTripPoints(id) { return Promise.resolve(tripPoints(id)); },

      sendCommand(action, payload) {
        payload = payload || {};
        let data = {};
        switch (action) {
          case 'recordVideo': demoVid.rec = { kind: 'manual', camera: 'back', since: Date.now() }; demoVid.emit(); break;
          case 'stopVideo':
            if (demoVid.rec) { demoVid.items.unshift({ id: 'Grabaciones/demo/' + Date.now(), kind: 'manual', camera: 'back', ts: Date.now(), bytes: 9e6, volume: demoVid.cfg.current }); demoVid.rec = null; demoVid.emit(); }
            break;
          case 'setVideoConfig':
            Object.assign(demoVid.cfg, payload); if (payload.store) demoVid.cfg.current = payload.store; demoVid.emit(); break;
          case 'deleteVideo': demoVid.items = demoVid.items.filter(v => v.id !== payload.id); demoVid.emit(); break;
          case 'keepVideo': demoVid.items.forEach(v => { if (v.id === payload.id) v.kind = 'manual'; }); demoVid.emit(); break;
          case 'getVideo': demoVid.req = payload.req; data = { parts: 1, bytes: 2.1e6 }; break;
          case 'runAutomation': {
            const r = demoAuto.rules[payload.id] || { do: [] };
            data = { ok: true, results: (r.do || []).map(a => ({ action: a.action, label: a.action === 'notify' ? 'Avisar al móvil' : a.action, ok: true })) };
            break;
          }
          case 'pushTest': data = { sent: 1, failed: 0 }; break;
          case 'setLock':
            status.locked = !!payload.locked;
            if (status.locked && config.automations.autoCloseWindowsOnLock && anyWindowOpen()) setAllWindows(false);
            break;
          case 'openWindows': setAllWindows(true); break;
          case 'closeWindows': setAllWindows(false); break;
          case 'setAutomation':
            if (payload.key) { config.automations[payload.key] = !!payload.value; emit('config', config); }
            break;
          case 'setSeatComfort':
            if (payload.driver != null) seats.driverStage = clamp(payload.driver, -2, 2);
            if (payload.passenger != null) seats.passengerStage = clamp(payload.passenger, -2, 2);
            emit('seats', seats); break;
          case 'setSentryConfig': Object.assign(config.sentry, payload); emit('config', config); break;
          case 'setConfig': deepMerge(config, payload); emit('config', config); break;
          case 'setClimate':
            Object.assign(status.climate, payload.on === false ? { on: false } : Object.assign({ on: true }, payload)); break;
          case 'openTrunk': status.doors.trunk = true; break;
          case 'closeTrunk': status.doors.trunk = false; break;
          case 'setWindow': { const k = ['', 'fl', 'fr', 'rl', 'rr'][payload.area]; if (k) status.windows[k] = !!payload.open; break; }
          case 'setSunroof': status.windows.sunroof = !!payload.open; break;
          case 'powerOff': status.powerState = 'OFF'; break;
          case 'startLiveJpeg': demoCam.start(payload.camera || 'front'); break;
          case 'stopLiveJpeg': demoCam.stop(); break;
          case 'takePhoto': setTimeout(() => demoCam.photo(payload.camera || 'front'), 700); break;
          default: break;
        }
        emit('status', status);
        return new Promise(r => setTimeout(() => r({ ok: true, data }), 450));
      },
      // Vídeos de ejemplo (en la demo no se pueden ver: no hay vídeo de verdad).
      videoStatus(cb) { demoVid.onStatus = cb; setTimeout(() => cb(demoVid.status()), 150); return () => {}; },
      videoList(cb) { demoVid.onList = cb; setTimeout(() => cb({ items: demoVid.items }), 150); return () => {}; },
      videoDlWatch(cb) { setTimeout(() => cb({ req: demoVid.req, state: 'error', error: 'En la demo no hay vídeos de verdad: con tu coche se ven aquí.' }), 700); return () => {}; },
      videoDlPart() { return Promise.resolve(null); },
      videoDlClear() { return Promise.resolve(); },
      autoWatch(cb) { demoAuto.on.push(cb); setTimeout(() => cb(JSON.parse(JSON.stringify(demoAuto.rules))), 100); return () => {}; },
      autoStateWatch(cb) { setTimeout(() => cb(demoAuto.states), 100); return () => {}; },
      autoLog() { return Promise.resolve(demoAuto.log.slice()); },
      autoSave(id, rule) { id = id || 'a' + Date.now().toString(36); demoAuto.rules[id] = rule; demoAuto.emit(); return Promise.resolve(id); },
      autoDelete(id) { delete demoAuto.rules[id]; demoAuto.emit(); return Promise.resolve(); },
      autoToggle(id, on) { if (demoAuto.rules[id]) demoAuto.rules[id].on = !!on; demoAuto.emit(); return Promise.resolve(); },
      bydCloudWatch(cb) { setTimeout(() => cb(true), 100); return () => {}; },
      pushKey() { return Promise.resolve(null); },
      pushSave(id, sub) { demoAuto.subs[id] = Object.assign({ ts: Date.now() }, sub); return Promise.resolve(); },
      pushUpdate(id, v) { Object.assign(demoAuto.subs[id] || {}, v); return Promise.resolve(); },
      pushGet(id) { return Promise.resolve(demoAuto.subs[id] || null); },
      pushRemove(id) { delete demoAuto.subs[id]; return Promise.resolve(); },
      getDemoCameraStream(camera) { return makeSyntheticStream(camera, status); },
      camLive(cb) { demoCam.onLive = cb; return () => { demoCam.onLive = null; }; },
      camPhoto(cb) { demoCam.onPhoto = cb; return () => { demoCam.onPhoto = null; }; },
      camViewer() { return Promise.resolve(); },
      sentryThumb(key) { return Promise.resolve({ ts: Date.now(), camera: 'front', jpeg: demoJpeg('front', status, 320, 200) }); },
      camViews() { return Promise.resolve(null); },
      camDiag() { return Promise.resolve({ verdict: 'OK_FRAMES', verdictMsg: 'Cámara simulada de la demo: funciona.', ts: Date.now() }); }
    };
  }

  // =====================================================================
  //  FIREBASE — coche real
  // =====================================================================
  function FirebaseBackend() {
    firebase.initializeApp(cfg);
    const auth = firebase.auth();
    const db = firebase.database();
    // Emuladores locales para pruebas: http://localhost:5055/?emu
    if (isLocal && params.has('emu')) {
      auth.useEmulator('http://127.0.0.1:9099', { disableWarnings: true });
      db.useEmulator('127.0.0.1', 9000);
    }
    const TS = firebase.database.ServerValue.TIMESTAMP;
    const uid = () => auth.currentUser && auth.currentUser.uid;
    let carId = null, root = null;

    auth.onAuthStateChanged(u => { if (u && !u.isAnonymous) writeProfile(u); });

    function writeProfile(u) {
      const ref = db.ref('users/' + u.uid);
      ref.update({
        email: u.email || null,
        name: u.displayName || null,
        provider: (u.providerData && u.providerData[0] && u.providerData[0].providerId) || 'password',
        lastLoginAt: TS,
        lang: navigator.language || null,
        tz: (Intl.DateTimeFormat().resolvedOptions().timeZone) || null
      }).catch(() => {});
      ref.child('createdAt').once('value').then(s => {
        if (!s.exists()) return ref.child('createdAt').set(TS);
      }).catch(() => {});
    }

    function addToAccount(id) {
      const mine = db.ref('users/' + uid() + '/cars/' + id);
      return mine.once('value').then(s => {
        if (s.exists()) return { id, name: (s.val() || {}).name || 'Mi BYD' };   // ya estaba: no pisar el nombre que le puso
        return db.ref('cars/' + id + '/meta/name').once('value')
          .then(n => n.val(), () => null)
          .then(name => {
            name = name || 'Mi BYD';
            return mine.update({ name, pairedAt: TS }).then(() => ({ id, name }));
          });
      });
    }

    return {
      mode: 'firebase', requiresAuth: true,
      get carId() { return carId; },

      onAuth(cb) { auth.onAuthStateChanged(cb); },
      register(email, pw) { return auth.createUserWithEmailAndPassword(email, pw); },
      login(email, pw) { return auth.signInWithEmailAndPassword(email, pw); },
      loginGoogle() {
        const p = new firebase.auth.GoogleAuthProvider();
        return auth.signInWithPopup(p).catch(e => {
          // Navegadores que bloquean ventanas emergentes (p. ej. app instalada en iPhone)
          if (e && (e.code === 'auth/popup-blocked' || e.code === 'auth/operation-not-supported-in-this-environment'))
            return auth.signInWithRedirect(p);
          throw e;
        });
      },
      redirectResult() { return auth.getRedirectResult(); },
      logout() { return auth.signOut(); },
      resetPassword(email) { return auth.sendPasswordResetEmail(email); },

      // --- Coches de la cuenta (users/{uid}/cars) ---
      listCars() {
        const u = uid();
        if (!u) return Promise.resolve([]);
        return db.ref('users/' + u + '/cars').once('value').then(s => {
          const cars = [];
          s.forEach(c => { const v = c.val() || {}; cars.push({ id: c.key, name: v.name || 'Mi BYD', pairedAt: v.pairedAt || 0 }); });
          cars.sort((a, b) => a.pairedAt - b.pairedAt);
          if (cars.length) return cars;
          // Vinculación antigua guardada solo en este navegador → pasarla a la cuenta.
          const legacy = savedCar();
          if (!legacy || legacy === 'mi-byd') return cars;
          return db.ref('access/' + legacy + '/' + u).once('value')
            .then(a => a.exists() ? addToAccount(legacy).then(c => [c]) : cars)
            .catch(() => cars);
        });
      },
      savedCar, saveCar,
      useCar(id) { carId = id; root = db.ref('cars/' + id); },

      pair(code) {
        code = String(code || '').toUpperCase().replace(/[^A-Z0-9]/g, '');
        if (code.length !== 6) return Promise.reject(new Error('El código tiene 6 caracteres.'));
        const u = uid();
        return db.ref('pairing/' + code).once('value').then(snap => {
          const v = snap.val();
          if (!v || !v.carId) throw new Error('Código no válido. Revisa el que aparece en la pantalla del coche.');
          // El valor es el propio código: las reglas comprueban que corresponde a ese coche.
          return db.ref('access/' + v.carId + '/' + u).set(code)
            .then(() => addToAccount(v.carId))
            .then(() => v.carId);
        });
      },
      unpair(id) {
        const u = uid();
        return db.ref('users/' + u + '/cars/' + id).remove()
          .then(() => db.ref('access/' + id + '/' + u).remove().catch(() => {}));
      },
      renameCar(id, name) { return db.ref('users/' + uid() + '/cars/' + id + '/name').set(name); },

      watch(h) {
        const err = e => call(h, 'error', e);
        const val = (path, k) => root.child(path).on('value', s => call(h, k, s.val()), err);
        const list = (q, k) => q.on('value', s => {
          const a = []; s.forEach(c => { const o = c.val() || {}; o.id = c.key; a.push(o); });
          call(h, k, a.reverse());
        }, err);
        db.ref('.info/connected').on('value', s => call(h, 'net', !!s.val()));
        val('meta', 'meta');
        val('status', 'status');
        val('config', 'config');
        val('seats', 'seats');
        val('cameras', 'cameras');
        val('caps', 'caps');
        val('capsOverride', 'capsOverride');
        list(root.child('trips').orderByChild('start').limitToLast(50), 'trips');
        list(root.child('sentryEvents').orderByChild('ts').limitToLast(60), 'sentry');
        list(root.child('chargingSessions').orderByChild('start').limitToLast(100), 'charging');
      },
      getTripPoints(id) { return root.child('tripPoints/' + id).once('value').then(s => s.val() || []); },

      sendCommand(action, payload) {
        const ref = root.child('commands').push();
        const rref = root.child('responses/' + ref.key);
        return new Promise(resolve => {
          let done = false;
          const finish = v => {
            if (done) return; done = true;
            clearTimeout(timer); rref.off('value', onResp); resolve(v);
          };
          const onResp = s => {
            const v = s.val(); if (!v) return;
            finish({ ok: !!v.ok, error: v.error || (v.data && v.data.error) || null, data: v.data || {} });
            rref.remove().catch(() => {});
          };
          // Si el coche no contesta, se retira el comando: así no se ejecuta horas después
          // (p. ej. un "abrir" cuando el coche vuelva a encenderse).
          const timer = setTimeout(() => { ref.remove().catch(() => {}); finish({ ok: false, error: 'timeout' }); }, 15000);
          rref.on('value', onResp, e => finish({ ok: false, error: (e && e.code) || 'permission' }));
          ref.set({ action, payload: payload || {}, ts: TS })
            .catch(e => finish({ ok: false, error: (e && e.code) || 'write' }));
        });
      },

      signalRef(session) { return root.child('signaling/' + session); },

      // Cámara por imágenes (funciona con cualquier red; ver protocol/camara.md)
      camLive(cb) { const r = root.child('camera/live'), f = s => cb(s.val()); r.on('value', f); return () => r.off('value', f); },
      camPhoto(cb) { const r = root.child('camera/photo'), f = s => cb(s.val()); r.on('value', f); return () => r.off('value', f); },
      camViewer() { return root.child('camera/viewer').set(TS).catch(() => {}); },
      sentryThumb(key) { return root.child('sentryThumbs/' + key).once('value').then(s => s.val()); },
      // Vídeos guardados en el coche (memoria, SD o USB) y envío de uno al móvil por trozos (ver CameraCommands.sendVideo)
      videoStatus(cb) { const r = root.child('video'), f = s => cb(s.val()); r.on('value', f); return () => r.off('value', f); },
      videoList(cb) { const r = root.child('videos'), f = s => cb(s.val()); r.on('value', f); return () => r.off('value', f); },
      videoDlWatch(cb) {
        const refs = ['req', 'state', 'done', 'n', 'bytes', 'error'].map(k => root.child('videoDl/' + k));
        const cur = {};
        const fs = refs.map(r => { const f = s => { cur[r.key] = s.val(); cb(Object.assign({}, cur)); }; r.on('value', f); return f; });
        return () => refs.forEach((r, i) => r.off('value', fs[i]));
      },
      videoDlPart(i) { return root.child('videoDl/parts/' + i).once('value').then(s => s.val()); },
      videoDlClear() { return root.child('videoDl').remove().catch(() => {}); },
      // Automatizaciones (las cumple el coche) y avisos al móvil: se escriben directamente, sin pasar por órdenes, así
      // se guardan aunque el coche esté sin cobertura (ver protocol/automatizaciones.md).
      autoWatch(cb) { const r = root.child('automations'), f = s => cb(s.val() || {}); r.on('value', f); return () => r.off('value', f); },
      autoStateWatch(cb) { const r = root.child('autoState'), f = s => cb(s.val() || {}); r.on('value', f); return () => r.off('value', f); },
      autoLog(n) {
        return root.child('autoLog').orderByChild('ts').limitToLast(n || 40).once('value').then(s => {
          const a = []; s.forEach(c => { const o = c.val() || {}; o.id = c.key; a.push(o); }); return a.reverse();
        });
      },
      autoSave(id, rule) { const ref = id ? root.child('automations/' + id) : root.child('automations').push(); return ref.set(rule).then(() => ref.key); },
      autoDelete(id) { return root.child('automations/' + id).remove().then(() => root.child('autoState/' + id).remove().catch(() => {})); },
      autoToggle(id, on) { return root.child('automations/' + id + '/on').set(!!on); },
      bydCloudWatch(cb) { const r = root.child('bydCloud/linked'), f = s => cb(s.val() === true); r.on('value', f); return () => r.off('value', f); },
      pushKey() { return root.child('push/key').once('value').then(s => s.val()); },
      pushSave(id, sub) { return root.child('push/subs/' + id).set(Object.assign({ uid: uid(), ts: TS }, sub)); },
      pushUpdate(id, v) { return root.child('push/subs/' + id).update(v); },
      pushGet(id) { return root.child('push/subs/' + id).once('value').then(s => s.val()); },
      pushRemove(id) { return root.child('push/subs/' + id).remove(); },
      // Resultado de la última prueba de cámara (solo el veredicto: las miniaturas pesan)
      camViews() { return root.child('cameras/list').once('value').then(s => s.val() || null, () => null); },
      camDiag() {
        const f = k => root.child('diag/cam/' + k).once('value').then(s => s.val(), () => null);
        return Promise.all([f('verdict'), f('verdictMsg'), f('ts')]).then(([verdict, verdictMsg, ts]) => verdict ? { verdict, verdictMsg, ts } : null);
      }
    };
  }

  // =====================================================================
  //  CLOUD — servidor provisional (Node local) vía REST
  // =====================================================================
  function CloudBackend() {
    const api = (window.BYDLINK_API || '') + '/api';
    let h = {}, curStatus = {};
    const last = {};
    function fireIfChanged(key, v) {
      if (v === undefined) return;
      const j = JSON.stringify(v);
      if (j !== last[key]) { last[key] = j; call(h, key, v); }
    }
    async function poll() {
      try {
        const r = await fetch(api + '/state', { cache: 'no-store' });
        if (!r.ok) return;
        const st = await r.json();
        call(h, 'net', true);
        fireIfChanged('meta', st.meta); fireIfChanged('cameras', st.cameras);
        fireIfChanged('config', st.config); fireIfChanged('seats', st.seats);
        fireIfChanged('trips', st.trips); fireIfChanged('sentry', st.sentryEvents);
        fireIfChanged('charging', st.chargingSessions);
        if (st.status) { curStatus = st.status; call(h, 'status', st.status); }
      } catch (e) { call(h, 'net', false); }
    }
    return {
      mode: 'cloud', requiresAuth: false, carId: 'local',
      listCars() { return Promise.resolve([{ id: 'local', name: 'Coche (servidor local)' }]); },
      savedCar() { return 'local'; }, saveCar() {}, useCar() {},
      pair() { return Promise.reject(new Error('El servidor local no usa vinculación.')); },
      unpair() { return Promise.resolve(); }, renameCar() { return Promise.resolve(); },
      watch(handlers) { h = handlers || {}; poll(); setInterval(poll, 1500); },
      getTripPoints(id) { return fetch(api + '/trip/' + id + '/points').then(r => r.json()).catch(() => []); },
      sendCommand(action, payload) {
        return fetch(api + '/command', { method: 'POST', headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ action, payload: payload || {} }) })
          .then(r => r.json()).then(res => { poll(); return res; })
          .catch(e => ({ ok: false, error: String(e) }));
      },
      getDemoCameraStream(camera) { return makeSyntheticStream(camera, curStatus); }
    };
  }

  // ---- utilidades ----
  function clamp(v, a, b) { return Math.max(a, Math.min(b, v)); }
  function deepMerge(t, s) { for (const k in s) { if (s[k] && typeof s[k] === 'object' && !Array.isArray(s[k])) { t[k] = t[k] || {}; deepMerge(t[k], s[k]); } else t[k] = s[k]; } return t; }

  // Vídeo sintético para la demo de cámara.
  // Escena sintética de la demo (vídeo HD y también las imágenes JPEG).
  function drawDemo(ctx, camera, status, t) {
      const labels = { front: 'FRONTAL', back: 'TRASERA', left: 'IZQUIERDA', right: 'DERECHA' };
      ctx.save(); ctx.scale(ctx.canvas.width / 960, ctx.canvas.height / 600);
      const g = ctx.createLinearGradient(0, 0, 0, 600);
      g.addColorStop(0, '#1b2a3a'); g.addColorStop(.55, '#2a3542'); g.addColorStop(1, '#151a20');
      ctx.fillStyle = g; ctx.fillRect(0, 0, 960, 600);
      ctx.strokeStyle = 'rgba(255,255,255,.18)'; ctx.lineWidth = 3;
      for (let i = -6; i <= 6; i++) { ctx.beginPath(); ctx.moveTo(480 + i * 18, 330); ctx.lineTo(480 + i * 160, 600); ctx.stroke(); }
      for (let i = 0; i < 8; i++) {
        const y = 330 + Math.pow(((i * 0.125 + t * 0.01) % 1), 2) * 270;
        ctx.beginPath(); ctx.moveTo(0, y); ctx.lineTo(960, y); ctx.stroke();
      }
      const x = (t * 4) % 1100 - 70;
      ctx.fillStyle = 'rgba(52,211,153,.9)';
      ctx.beginPath(); ctx.arc(x + 22, 300, 20, 0, 7); ctx.fill();
      ctx.fillRect(x, 322, 44, 90);
      ctx.fillStyle = 'rgba(0,0,0,.45)'; ctx.fillRect(0, 548, 960, 52);
      ctx.fillStyle = '#fff'; ctx.font = '600 24px system-ui';
      ctx.fillText((labels[camera] || camera) + ' · DEMO', 22, 582);
      ctx.textAlign = 'right';
      ctx.fillText(new Date().toLocaleTimeString() + (status && status.soc ? '   ' + Math.round(status.soc) + '%' : ''), 938, 582);
      ctx.textAlign = 'left';
      ctx.restore();
  }
  let demoT = 0;
  function demoJpeg(camera, status, w, h) {
    const c = document.createElement('canvas'); c.width = w; c.height = h;
    drawDemo(c.getContext('2d'), camera, status, demoT += 8);
    return c.toDataURL('image/jpeg', 0.7).split(',')[1];
  }
  function makeSyntheticStream(camera, status) {
    const c = document.createElement('canvas'); c.width = 960; c.height = 600;
    const ctx = c.getContext('2d');
    let t = 0;
    const timer = setInterval(() => { t += 1; drawDemo(ctx, camera, status, t); }, 66);
    const stream = c.captureStream(15);
    stream.__stop = () => clearInterval(timer);
    return stream;
  }

  // ---- elegir implementación ----
  const isHttp = location.protocol === 'http:' || location.protocol === 'https:';
  const wantDemo = params.has('demo');
  window.Backend = wantDemo ? DemoBackend()
    : hasFirebase ? FirebaseBackend()
    : (isHttp ? CloudBackend() : DemoBackend());
  document.documentElement.dataset.backend = window.Backend.mode;
})();
