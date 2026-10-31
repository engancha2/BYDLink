/* BYDLink · lógica de la interfaz */
(function () {
  'use strict';
  const $ = (s, r) => (r || document).querySelector(s);
  const $$ = (s, r) => Array.prototype.slice.call((r || document).querySelectorAll(s));
  const RING = 326.7;                 // circunferencia del anillo SOC
  const KM_PER_PCT = 4.6;             // estimación de autonomía

  let lastStatus = null, lastConfig = null, map = null, carMarker = null, routeLine = null, mapReady = false;

  // ---------- Navegación por pestañas ----------
  $$('.tab').forEach(t => t.addEventListener('click', () => {
    $$('.tab').forEach(x => x.classList.remove('active'));
    t.classList.add('active');
    const go = t.dataset.go;
    $$('.view').forEach(v => v.hidden = (v.dataset.view !== go));
    if (go === 'map') ensureMap();
    if (go !== 'cam') LiveCam.stop(), resetCamUI();
  }));

  // Deep-link opcional: index.html#charge abre directamente esa pestaña
  (function () { const g = (location.hash || '').slice(1); if (g) { const t = document.querySelector('.tab[data-go="' + g + '"]'); if (t) t.click(); } })();

  // ---------- Dashboard ----------
  function renderStatus(s) {
    lastStatus = s;
    const soc = Math.round(s.soc);
    $('#socBig').textContent = soc;
    $('#topSoc').textContent = soc + '%';
    $('#socArc').style.strokeDashoffset = RING * (1 - soc / 100);
    $('#socArc').style.stroke = soc <= 15 ? 'var(--danger)' : soc <= 30 ? 'var(--warn)' : 'var(--accent)';
    $('#topBattFill').style.width = soc + '%';
    $('#topBattFill').style.background = soc <= 15 ? 'var(--danger)' : 'var(--accent)';
    $('#rangeVal').textContent = (s.rangeKm != null ? s.rangeKm : Math.round(soc * KM_PER_PCT)) + ' km';
    // telemetría
    $('#tmPower').textContent = (s.power != null ? (s.power > 0 ? '+' : '') + s.power : '--') + ' kW';
    $('#tmCons').textContent = (s.consumptionKwh100 != null ? s.consumptionKwh100.toFixed(1) : '--');
    $('#tmOdo').textContent = (s.odometer != null ? s.odometer.toLocaleString('es-ES') : '--') + ' km';
    $('#tmVolt').textContent = (s.voltage != null ? s.voltage : '--') + ' V';
    $('#chargeVal').textContent = s.charging
      ? ('Cargando · ' + (s.chargeRestMin ? s.chargeRestMin + ' min' : '')) : (s.speed > 0 ? 'En marcha' : 'Aparcado');
    $('#gearVal').textContent = s.gear;
    $('#tempVal').textContent = (s.outTemp != null ? s.outTemp : '--') + ' °' + (s.tempUnit || 'C');

    // cierre
    $('#lockVal').textContent = s.locked ? 'Cerrado' : 'Abierto';
    $('#lockIco').textContent = s.locked ? '🔒' : '🔓';
    $('#lockTile').classList.toggle('active', !!s.locked);

    // clima
    const climaOn = lastConfig && lastConfig._climateOn;
    $('#climateVal').textContent = climaOn ? ((s._climateTemp || 21) + '°') : 'Apagado';
    $('#climateTile').classList.toggle('active', !!climaOn);

    // puertas
    const d = s.doors || {};
    $$('#doorsGrid .door').forEach(el => el.classList.toggle('open', !!d[el.dataset.door]));

    // ventanas
    const w = s.windows || {};
    $$('#windowsGrid .door').forEach(el => el.classList.toggle('open', !!w[el.dataset.win]));

    // neumáticos
    const ty = s.tyres || {};
    $$('#tyresGrid .tyre').forEach(el => {
      const v = ty[el.dataset.tyre];
      el.querySelector('.tp').textContent = (v != null ? v.toFixed(1) : '--');
      el.classList.toggle('warn', v != null && (v < 2.2 || v > 2.8));
    });

    // carga actual (pestaña Carga)
    $('#chargeNowSoc').textContent = soc + '%';
    $('#chargeNowTxt').textContent = s.charging
      ? ('Cargando' + (s.chargeRestMin ? ' · ' + s.chargeRestMin + ' min restantes' : '') + (s.chargingPower ? ' · ' + s.chargingPower + ' kW' : ''))
      : 'No está cargando';

    // mapa
    if (s.location && mapReady) updateCar(s.location);
  }

  function renderSeats(se) {
    setStage($('#seatDriver'), se.driverStage);
    setStage($('#seatPassenger'), se.passengerStage);
  }
  function setStage(el, v) {
    v = v || 0; el.textContent = v > 0 ? '+' + v : v;
    el.classList.toggle('heat', v > 0); el.classList.toggle('cool', v < 0);
  }

  function renderMeta(m) {
    $('#carName').textContent = m.name || 'Mi coche';
    $('#carState').textContent = m.online ? 'en línea' : 'desconectado';
    $('#carDot').classList.toggle('online', !!m.online);
    $('#setCarId').textContent = Backend.carId;
    $('#setMode').textContent = Backend.mode === 'demo' ? 'Demo (simulado)'
      : (Backend.mode === 'cloud' ? 'Nube (backend local)' : 'Firebase');
    $('#setAppVer').textContent = m.appVersion || '—';
  }

  function renderConfig(c) {
    lastConfig = c;
    $('#sentrySwitch').checked = !!(c.sentry && c.sentry.on);
    $('#sentryStateTxt').textContent = (c.sentry && c.sentry.on) ? 'Activo' : 'Inactivo';
    if (c.sentry && c.sentry.sensitivity) { $('#sentrySens').value = c.sentry.sensitivity; $('#sentrySensVal').textContent = c.sentry.sensitivity; }
    $$('[data-cfg]').forEach(inp => {
      const val = getPath(c, inp.dataset.cfg);
      if (typeof val === 'boolean') inp.checked = val;
    });
    $$('[data-auto]').forEach(inp => {
      const a = (c.automations || {})[inp.dataset.auto];
      if (typeof a === 'boolean') inp.checked = a;
    });
  }

  // ---------- Acciones ----------
  $('#lockTile').addEventListener('click', () => {
    if (!lastStatus) return;
    Backend.sendCommand('setLock', { locked: !lastStatus.locked });
  });
  $('#climateTile').addEventListener('click', () => {
    const on = !(lastConfig && lastConfig._climateOn);
    Backend.sendCommand('setClimate', { on, temp: 21 });
  });
  $$('.stepper').forEach(st => st.addEventListener('click', e => {
    const b = e.target.closest('button'); if (!b) return;
    const seat = st.dataset.seat, delta = +b.dataset.d;
    const cur = +$('#seat' + seat[0].toUpperCase() + seat.slice(1)).textContent.replace('+', '') || 0;
    const nv = Math.max(-2, Math.min(2, cur + delta));
    Backend.sendCommand('setSeatComfort', seat === 'driver' ? { driver: nv } : { passenger: nv });
  }));

  // Ventanas
  $('#winClose').addEventListener('click', () => Backend.sendCommand('closeWindows', {}));
  $('#winOpen').addEventListener('click', () => Backend.sendCommand('openWindows', {}));

  // Automatizaciones
  $$('[data-auto]').forEach(inp => inp.addEventListener('change', () =>
    Backend.sendCommand('setAutomation', { key: inp.dataset.auto, value: inp.checked })));

  // Vinculación de coche (solo en modo firebase)
  if (typeof Backend.pair === 'function') {
    $('#pairCard').hidden = false;
    let linked = null; try { linked = localStorage.getItem('bydlink_carId'); } catch (e) {}
    if (linked) $('#unpairBtn').hidden = false;
    $('#pairBtn').addEventListener('click', () => {
      $('#pairStatus').textContent = 'Vinculando…';
      Backend.pair($('#pairCode').value).then(carId => {
        $('#pairStatus').textContent = 'Vinculado ✓ (' + carId + '). Recargando…';
        setTimeout(() => location.reload(), 900);
      }).catch(err => { $('#pairStatus').textContent = 'Error: ' + (err && err.message || err); });
    });
    $('#unpairBtn').addEventListener('click', () => {
      Backend.unpair(); $('#pairStatus').textContent = 'Desvinculado. Recargando…';
      setTimeout(() => location.reload(), 600);
    });
  }

  // Centinela
  $('#sentrySwitch').addEventListener('change', e =>
    Backend.sendCommand('setSentryConfig', { on: e.target.checked }));
  $('#sentrySens').addEventListener('input', e => $('#sentrySensVal').textContent = e.target.value);
  $('#sentrySens').addEventListener('change', e =>
    Backend.sendCommand('setSentryConfig', { sensitivity: +e.target.value }));

  // Ajustes (toggles genéricos -> setConfig con ruta anidada)
  $$('[data-cfg]').forEach(inp => inp.addEventListener('change', () => {
    Backend.sendCommand('setConfig', setPath({}, inp.dataset.cfg, inp.checked));
  }));

  // ---------- Mapa ----------
  function ensureMap() {
    if (mapReady || typeof L === 'undefined') return;
    map = L.map('map', { zoomControl: false, attributionControl: false }).setView([41.387, 2.17], 13);
    L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', { maxZoom: 19 }).addTo(map);
    mapReady = true;
    if (lastStatus && lastStatus.location) updateCar(lastStatus.location);
  }
  function updateCar(loc) {
    if (!mapReady) return;
    const ll = [loc.lat, loc.lng];
    if (!carMarker) {
      carMarker = L.marker(ll, { icon: L.divIcon({ className: 'car-pin', html: '🚗', iconSize: [28, 28] }) }).addTo(map);
      map.setView(ll, 14);
    } else carMarker.setLatLng(ll);
  }
  function drawTrip(points) {
    if (!mapReady || !points.length) return;
    if (routeLine) map.removeLayer(routeLine);
    const latlngs = points.map(p => [p.lat, p.lng]);
    routeLine = L.polyline(latlngs, { color: '#36a3ff', weight: 5, opacity: .9 }).addTo(map);
    map.fitBounds(routeLine.getBounds().pad(0.2));
  }

  function renderTrips(trips) {
    const box = $('#tripList');
    if (!trips.length) { box.innerHTML = '<div class="empty">Sin viajes</div>'; return; }
    box.innerHTML = '';
    trips.forEach(t => {
      const el = document.createElement('div');
      el.className = 'trip';
      el.innerHTML =
        '<div><div class="t-main">' + (t.startAddr || '—') + ' → ' + (t.endAddr || '—') + '</div>' +
        '<div class="t-sub">' + fmtDate(t.start) + ' · ' + Math.round((t.durationSec || 0) / 60) + ' min</div></div>' +
        '<div class="t-km">' + (t.distanceKm || 0).toFixed(1) + ' km</div>';
      el.addEventListener('click', async () => {
        $$('.trip').forEach(x => x.classList.remove('active')); el.classList.add('active');
        ensureMap();
        const pts = await Backend.getTripPoints(t.id);
        drawTrip(pts);
      });
      box.appendChild(el);
    });
  }

  // ---------- Cámara ----------
  $$('.cambtn').forEach(b => b.addEventListener('click', () => {
    $$('.cambtn').forEach(x => x.classList.remove('active'));
    b.classList.add('active');
    $('#camPlaceholder').style.display = 'none';
    $('#camStatus').hidden = false;
    $('#camStop').hidden = false;
    const setStat = txt => { $('#camStatus').textContent = txt; };
    if (LiveCam.camera) LiveCam.switchCam(b.dataset.cam, $('#liveVideo'), setStat);
    else LiveCam.start(b.dataset.cam, $('#liveVideo'), setStat);
  }));
  $('#camStop').addEventListener('click', () => { LiveCam.stop(); resetCamUI(); });
  function resetCamUI() {
    $$('.cambtn').forEach(x => x.classList.remove('active'));
    $('#liveVideo').style.display = 'none';
    $('#liveVideo').srcObject = null;
    $('#camPlaceholder').style.display = '';
    $('#camStatus').hidden = true;
    $('#camStop').hidden = true;
  }

  // ---------- Centinela: eventos ----------
  function renderSentry(events) {
    const box = $('#sentryList');
    if (!events.length) { box.innerHTML = '<div class="empty">Sin eventos</div>'; return; }
    const camName = { front: 'Frontal', back: 'Trasera', left: 'Izquierda', right: 'Derecha' };
    box.innerHTML = '';
    events.slice(0, 30).forEach(ev => {
      const el = document.createElement('div');
      el.className = 'event';
      const thumb = ev.thumbUrl
        ? '<img class="thumb" src="' + ev.thumbUrl + '">'
        : '<div class="thumb" style="display:flex;align-items:center;justify-content:center">🎥</div>';
      el.innerHTML = thumb +
        '<div><div class="e-main">Movimiento · ' + (camName[ev.camera] || ev.camera) + '</div>' +
        '<div class="e-sub">' + fmtDate(ev.ts) + '</div></div>';
      box.appendChild(el);
    });
  }

  // ---------- Carga (historial de ciclos) ----------
  function renderCharging(sessions) {
    const list = $('#chgList'), chart = $('#chgChart');
    if (!sessions || !sessions.length) { list.innerHTML = '<div class="empty">Sin cargas</div>'; chart.innerHTML = ''; return; }
    const totalKwh = sessions.reduce((a, s) => a + (s.kwh || 0), 0);
    const totalCost = sessions.reduce((a, s) => a + (s.costEur || 0), 0);
    $('#chgCount').textContent = sessions.length;
    $('#chgKwh').textContent = totalKwh.toFixed(1);
    $('#chgCost').textContent = totalCost.toFixed(2).replace('.', ',') + '€';
    const asc = sessions.slice().sort((a, b) => a.start - b.start);
    const max = Math.max.apply(null, asc.map(s => s.kwh || 0)) || 1;
    chart.innerHTML = asc.map(s => {
      const h = Math.max(6, Math.round(((s.kwh || 0) / max) * 100));
      return '<div class="bar ' + (s.type === 'DC' ? 'dc' : 'ac') + '" style="height:' + h + '%" title="' + (s.kwh || 0) + ' kWh"></div>';
    }).join('');
    list.innerHTML = '';
    sessions.forEach(s => {
      const el = document.createElement('div'); el.className = 'charge-item';
      el.innerHTML =
        '<div class="ci-badge ' + (s.type === 'DC' ? 'dc' : 'ac') + '">' + (s.type || 'AC') + '</div>' +
        '<div class="ci-main"><div class="ci-t">' + (s.place || '—') + ' · ' + (s.socStart || 0) + '→' + (s.socEnd || 0) + '%</div>' +
        '<div class="ci-s">' + fmtDate(s.start) + ' · ' + (s.durationMin || 0) + ' min</div></div>' +
        '<div class="ci-kwh">' + (s.kwh || 0).toFixed(1) + ' kWh' + (s.costEur != null ? '<span>' + s.costEur.toFixed(2).replace('.', ',') + '€</span>' : '') + '</div>';
      list.appendChild(el);
    });
  }

  // ---------- utilidades ----------
  function fmtDate(ts) {
    const d = new Date(ts), now = new Date();
    const hhmm = d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
    if (d.toDateString() === now.toDateString()) return 'Hoy ' + hhmm;
    return d.toLocaleDateString([], { day: '2-digit', month: 'short' }) + ' ' + hhmm;
  }
  function getPath(o, p) { return p.split('.').reduce((a, k) => (a == null ? a : a[k]), o); }
  function setPath(o, p, v) { const ks = p.split('.'); let c = o; ks.forEach((k, i) => { if (i === ks.length - 1) c[k] = v; else c = c[k] = c[k] || {}; }); return o; }

  // ---------- Autenticación / arranque ----------
  let _started = false;
  function startSubscriptions() {
    if (_started) return; _started = true;
    Backend.onMeta(renderMeta);
    Backend.onStatus(renderStatus);
    Backend.onConfig(renderConfig);
    Backend.onSeats(renderSeats);
    Backend.onTrips(renderTrips);
    Backend.onSentryEvents(renderSentry);
    if (Backend.onCharging) Backend.onCharging(renderCharging);
    document.body.dataset.mode = Backend.mode;
  }

  if (Backend.requiresAuth) {
    setupAuthUI();
    Backend.onAuth(user => {
      if (user) {
        $('#authGate').hidden = true;
        $('#acctCard').hidden = false;
        $('#acctEmail').textContent = user.email || user.displayName || '(Google)';
        Backend.init().then(startSubscriptions);
      } else {
        $('#authGate').hidden = false;
      }
    });
  } else {
    Backend.init().then(startSubscriptions);
    if (Backend.mode === 'demo') setTimeout(() => Backend.sendCommand('drive', { on: true }), 4000);
  }

  function setupAuthUI() {
    let mode = 'login';
    const err = m => { $('#authError').textContent = m || ''; };
    $('#authToggle').addEventListener('click', () => {
      mode = (mode === 'login') ? 'register' : 'login';
      $('#authSub').textContent = mode === 'login' ? 'Entra para controlar tu coche' : 'Crea tu cuenta';
      $('#authSubmit').textContent = mode === 'login' ? 'Entrar' : 'Crear cuenta';
      $('#authToggle').innerHTML = mode === 'login' ? '¿No tienes cuenta? <b>Crear una</b>' : '¿Ya tienes cuenta? <b>Entrar</b>';
      err('');
    });
    $('#authSubmit').addEventListener('click', () => {
      const email = $('#authEmail').value.trim(), pw = $('#authPass').value;
      if (!email || !pw) return err('Pon correo y contraseña');
      err('');
      (mode === 'login' ? Backend.login(email, pw) : Backend.register(email, pw)).catch(e => err(authMsg(e)));
    });
    $('#authPass').addEventListener('keydown', e => { if (e.key === 'Enter') $('#authSubmit').click(); });
    $('#googleBtn').addEventListener('click', () => { err(''); Backend.loginGoogle().catch(e => err(authMsg(e))); });
    $('#authForgot').addEventListener('click', () => {
      const email = $('#authEmail').value.trim();
      if (!email) return err('Escribe tu correo arriba y vuelve a pulsar');
      Backend.resetPassword(email).then(() => err('Te envié un correo para restablecerla ✓')).catch(e => err(authMsg(e)));
    });
    const lo = $('#logoutBtn'); if (lo) lo.addEventListener('click', () => Backend.logout().then(() => location.reload()));
  }

  function authMsg(e) {
    const c = (e && e.code) || '';
    if (c.indexOf('wrong-password') >= 0 || c.indexOf('invalid-credential') >= 0) return 'Correo o contraseña incorrectos';
    if (c.indexOf('user-not-found') >= 0) return 'No existe esa cuenta';
    if (c.indexOf('email-already-in-use') >= 0) return 'Ese correo ya está registrado';
    if (c.indexOf('weak-password') >= 0) return 'Contraseña muy corta (mínimo 6)';
    if (c.indexOf('invalid-email') >= 0) return 'Correo no válido';
    if (c.indexOf('popup-closed') >= 0 || c.indexOf('cancelled-popup') >= 0) return '';
    if (c.indexOf('operation-not-allowed') >= 0) return 'Ese método de acceso no está habilitado todavía';
    return (e && e.message) || 'Error';
  }

  // Service worker (sólo si se sirve por http/https).
  if ('serviceWorker' in navigator && location.protocol.startsWith('http'))
    navigator.serviceWorker.register('sw.js').catch(() => {});
})();
