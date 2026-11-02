/* BYDLink · lógica de la interfaz del móvil */
(function () {
  'use strict';
  const $ = (s, r) => (r || document).querySelector(s);
  const $$ = (s, r) => Array.from((r || document).querySelectorAll(s));
  const B = window.Backend;
  const ONLINE_MS = 90e3;           // sin datos en este tiempo → coche desconectado
  const WEB_VERSION = '2.18';        // cambiarla junto con CACHE de sw.js
  const CAR_APP_MIN = '2.0';        // la app del coche más antigua que no hace falta actualizar

  const st = {
    user: null, cars: [], carId: null, started: false,
    meta: null, s: null, config: null, seats: null,
    lastRx: 0, rxCount: 0, net: true, denied: false,
    climate: { on: false, temp: 21 }, view: 'home'
  };

  // ───────── utilidades ─────────
  const num = (v, min, max) => (typeof v === 'number' && isFinite(v) && v >= min && v <= max) ? v : null;
  const esc = s => String(s == null ? '' : s).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]);
  const LOC = ((window.I18N && I18N.locale) || 'es-ES');   // formato de fechas y números del idioma elegido
  const fmt = (n, d) => n.toLocaleString(LOC, { maximumFractionDigits: d || 0, minimumFractionDigits: d || 0 });
  const icon = (id, cls) => '<svg' + (cls ? ' class="' + cls + '"' : '') + '><use href="#i-' + id + '"/></svg>';
  function lsGet(k) { try { return localStorage.getItem(k); } catch (e) { return null; } }
  function lsSet(k, v) { try { localStorage.setItem(k, v); } catch (e) {} }
  function busyBtn(b, on) { b.classList.toggle('loading', on); b.disabled = on; }
  // "1.10" > "1.9": compara número a número.
  function verLess(a, b) {
    const x = String(a).split('.').map(Number), y = String(b).split('.').map(Number);
    for (let i = 0; i < Math.max(x.length, y.length); i++) {
      const p = x[i] || 0, q = y[i] || 0;
      if (p !== q) return p < q;
    }
    return false;
  }

  function ago(ts) {
    if (!ts) return '';
    const d = Math.max(0, Date.now() - ts) / 1000;
    if (d < 45) return 'ahora mismo';
    if (d < 3600) return 'hace ' + Math.round(d / 60) + ' min';
    if (d < 86400) return 'hace ' + Math.round(d / 3600) + ' h';
    const days = Math.round(d / 86400);
    return days === 1 ? 'ayer' : 'hace ' + days + ' días';
  }
  function dur(min) {
    if (min < 60) return Math.round(min) + ' min';
    const h = Math.floor(min / 60), m = Math.round(min % 60);
    return h + ' h' + (m ? ' ' + m + ' min' : '');
  }
  function dayLabel(ts) {
    const d = new Date(ts), t = new Date();
    const y = new Date(); y.setDate(t.getDate() - 1);
    if (d.toDateString() === t.toDateString()) return 'Hoy';
    if (d.toDateString() === y.toDateString()) return 'Ayer';
    return d.toLocaleDateString(LOC, { weekday: 'long', day: 'numeric', month: 'long' });
  }
  const hhmm = ts => new Date(ts).toLocaleTimeString(LOC, { hour: '2-digit', minute: '2-digit' });
  const shortDate = ts => {
    const d = new Date(ts);
    return (d.toDateString() === new Date().toDateString() ? 'Hoy' : d.toLocaleDateString(LOC, { day: 'numeric', month: 'short' })) + ' · ' + hhmm(ts);
  };

  // El coche manda valores "centinela" cuando un sensor no está disponible (65535,
  // -21474826…, 0). Aquí se descartan: mejor "—" que un dato falso.
  function clean(r) {
    r = r || {};
    const ty = r.tyres || {};
    const L = r.location;
    const loc = L && num(L.lat, -90, 90) != null && num(L.lng, -180, 180) != null && !(L.lat === 0 && L.lng === 0) ? L : null;
    const charging = r.charging === true;
    return {
      ts: num(r.ts, 1, 9e15) || 0,
      soc: num(r.soc, 0, 100),
      rangeKm: num(r.rangeKm, 1, 1500),
      charging,
      // kW con decimal (chargeKw: en el Surf se calcula de los kWh cargados); si no, el redondeado de antes.
      chargingPower: charging ? (num(r.chargeKw, 0.1, 400) || num(r.chargingPower, 0.1, 400)) : null,
      chargedKwh: charging ? num(r.chargedKwh, 0.01, 500) : null,
      chargeRestMin: charging ? num(r.chargeRestMin, 1, 2880) : null,
      gear: ['P', 'R', 'N', 'D'].indexOf(r.gear) >= 0 ? r.gear : null,
      speed: num(r.speed, 0, 300),
      locked: typeof r.locked === 'boolean' ? r.locked : null,
      doors: r.doors || {}, windows: r.windows || {},
      tyres: { fl: num(ty.fl, 0.8, 5), fr: num(ty.fr, 0.8, 5), rl: num(ty.rl, 0.8, 5), rr: num(ty.rr, 0.8, 5) },
      outTemp: num(r.outTemp, -50, 70),
      tempUnit: r.tempUnit === 'F' ? 'F' : 'C',
      odometer: num(r.odometer, 1, 3e6),
      power: num(r.power, -500, 500),
      consumption: num(r.consumptionKwh100, 1, 60),
      voltage: num(r.voltage, 50, 1000),
      location: loc
    };
  }

  const lastSeen = () => Math.max((st.s && st.s.ts) || 0, (st.meta && st.meta.lastSeen) || 0);
  function isOnline() {
    if (B.mode !== 'firebase') return true;
    // Un dato recibido en directo (no el primero, que puede ser antiguo) o una marca de tiempo reciente.
    if (st.rxCount > 1 && Date.now() - st.lastRx < 20e3) return true;
    const t = lastSeen();
    return t > 0 && Math.abs(Date.now() - t) < ONLINE_MS;
  }
  const carDisplayName = () => {
    const c = st.cars.find(x => x.id === st.carId);
    return (c && c.name) || (st.meta && st.meta.name) || 'Mi BYD';
  };

  // ───────── pantallas, pestañas ─────────
  function show(name) { ['Loading', 'Auth', 'Pair', 'App'].forEach(n => { $('#scr' + n).hidden = n !== name; }); }

  function go(view) {
    if (!$('.view[data-view="' + view + '"]')) view = 'home';
    st.view = view;
    $$('.view').forEach(v => { v.hidden = v.dataset.view !== view; });
    $$('.tab').forEach(t => t.classList.toggle('active', t.dataset.goto === view || (view === 'charge' && t.dataset.goto === 'home') || (view === 'history' && t.dataset.goto === 'map')));
    if (view === 'map') MapV.ensure();
    if (view !== 'cam') Cam.stop();
    window.scrollTo(0, 0);
    try { history.replaceState(null, '', location.pathname + location.search + '#' + view); } catch (e) {}
  }
  document.addEventListener('click', e => {
    const g = e.target.closest('[data-goto]');
    if (g) { e.preventDefault(); go(g.dataset.goto); }
  });

  // ───────── avisos y hoja inferior ─────────
  function toast(msg, kind) {
    $$('#toasts .toast').forEach(t => t.remove());   // solo el último aviso
    const el = document.createElement('div');
    el.className = 'toast ' + (kind || '');
    el.innerHTML = (kind === 'ok' ? icon('check') : kind === 'err' ? icon('alert') : '') + '<span>' + esc(msg) + '</span>';
    $('#toasts').appendChild(el);
    requestAnimationFrame(() => el.classList.add('in'));
    setTimeout(() => { el.classList.remove('in'); setTimeout(() => el.remove(), 250); }, kind === 'err' ? 4500 : 2600);
  }

  const Sheet = {
    open(html, mount) {
      $('#sheetBody').innerHTML = html;
      $('#sheet').hidden = false;
      requestAnimationFrame(() => requestAnimationFrame(() => $('#sheet').classList.add('open')));
      if (mount) mount($('#sheetBody'));
    },
    close() {
      const s = $('#sheet');
      s.classList.remove('open');
      setTimeout(() => { if (!s.classList.contains('open')) { s.hidden = true; $('#sheetBody').innerHTML = ''; } }, 240);
    }
  };
  $('#sheet').addEventListener('click', e => { if (e.target.closest('[data-close]')) Sheet.close(); });
  document.addEventListener('keydown', e => { if (e.key === 'Escape' && !$('#sheet').hidden) Sheet.close(); });

  function confirmSheet(o) {
    return new Promise(resolve => {
      Sheet.open(
        '<h3>' + esc(o.title) + '</h3><p class="sub">' + esc(o.text || '') + '</p>' +
        '<div class="actions"><button class="btn ' + (o.danger ? 'btn-danger-soft' : 'btn-primary') + ' btn-block" data-ok>' + esc(o.ok) + '</button>' +
        '<button class="btn btn-soft btn-block" data-close>Cancelar</button></div>',
        body => {
          $('[data-ok]', body).addEventListener('click', () => { Sheet.close(); resolve(true); });
          $$('[data-close]', body).forEach(b => b.addEventListener('click', () => resolve(false)));
        });
    });
  }

  // ───────── comandos al coche ─────────
  const busy = {}, busyPayload = {}, queued = {};
  function command(action, payload, opt) {
    opt = opt || {};
    if (!isOnline()) {
      toast('El coche está desconectado. Prueba cuando esté encendido o tenga cobertura.', 'err');
      return Promise.resolve({ ok: false, error: 'offline' });
    }
    const key = opt.key || action;
    if (busy[key]) {
      // La misma orden repetida (doble toque) se ignora. Una distinta (otra temperatura, otro nivel de
      // ventilador) se manda en cuanto el coche responda a la anterior; si llegan varias, solo la última.
      if (busyPayload[key] === JSON.stringify(payload) && !queued[key]) return busy[key];
      const q = queued[key] || (queued[key] = {});
      q.action = action; q.payload = payload; q.opt = opt;
      return q.p || (q.p = busy[key].then(() => { delete queued[key]; return command(q.action, q.payload, q.opt); }));
    }
    busyPayload[key] = JSON.stringify(payload);
    if (opt.el) opt.el.classList.add('busy');
    const p = B.sendCommand(action, payload).then(res => {
      res = res || { ok: false };
      if (res.ok) { if (opt.ok) toast(opt.ok, 'ok'); }
      else if (res.error === 'timeout') toast('El coche no ha respondido. Puede estar dormido o sin cobertura.', 'err');
      else toast('No se ha podido' + (opt.what ? ' ' + opt.what : '') + (res.error ? ': ' + res.error : '.'), 'err');
      return res;
    }).catch(e => { toast('Error: ' + ((e && e.message) || e), 'err'); return { ok: false }; })
      .then(res => { delete busy[key]; if (opt.el) opt.el.classList.remove('busy'); return res; });
    busy[key] = p;
    return p;
  }

  // Módulos extra (extras.js: antirrobo, batería, más mandos) se enganchan aquí: reciben el
  // estado crudo del coche en cada actualización y usan las mismas utilidades que esta app.
  const hooks = [];
  // ¿La app del coche es al menos la versión v? (sin dato, o en la demo, se da por buena)
  const carHas = v => { const a = st.meta && st.meta.appVersion; return !a || !/^\d/.test(a) || !verLess(a, v); };

  // ───────── multimodelo: qué tiene este coche ─────────
  // El coche publica cars/{id}/caps (detectado + aprendido al usar los mandos); capsOverride corrige a mano.
  // false = no lo tiene → se oculta; true o sin dato → se enseña.
  const caps = () => Object.assign({}, st.caps || {}, st.capsOverride || {});
  const can = k => {
    if (k === 'seats') return can('seatHeat') || can('seatVent') || can('seatMemory');
    if (k === 'seatComfort') return can('seatHeat') || can('seatVent');
    return caps()[k] !== false;
  };
  function applyCaps() { $$('[data-cap]').forEach(el => el.classList.toggle('cap-off', !can(el.dataset.cap))); }
  window.BYDApp = { st, B, command, toast, Sheet, icon, esc, fmt, hhmm, ago, isOnline, carHas, can, applyCaps, onStatus: f => { hooks.push(f); if (st.raw) f(st.raw); } };

  // ───────── render: inicio ─────────
  const DOOR = { fl: 'delantera izquierda', fr: 'delantera derecha', rl: 'trasera izquierda', rr: 'trasera derecha' };
  const GEAR = { P: 'P · Aparcado', R: 'R · Atrás', N: 'N · Punto muerto', D: 'D · Conducción' };

  function renderStatus() {
    const s = st.s;
    if (!s) return;
    const soc = s.soc != null ? Math.round(s.soc) : null;
    const lvl = soc == null ? '' : soc <= 15 ? 'low' : soc <= 30 ? 'mid' : '';

    // batería
    $('#socVal').textContent = soc != null ? soc : '--';
    $('#socFill').style.width = (soc || 0) + '%';
    $('#rangeVal').textContent = s.rangeKm != null ? fmt(s.rangeKm) : '--';
    const bc = $('#batteryCard');
    bc.classList.toggle('low', lvl === 'low'); bc.classList.toggle('mid', lvl === 'mid');
    bc.classList.toggle('charging', s.charging);
    $('#topSocTxt').textContent = soc != null ? soc + '%' : '--%';
    $('#topSocFill').style.width = (soc || 0) + '%';
    $('#topSoc').className = 'top-soc ' + lvl;

    let drive, ico = 'car';
    if (s.charging) {
      ico = 'bolt';
      drive = 'Cargando' + (s.chargingPower ? ' · ' + fmt(s.chargingPower, s.chargingPower < 10 ? 1 : 0) + ' kW' : '') +
        (s.chargeRestMin ? ' · ' + dur(s.chargeRestMin) + ' para llenar' : '');
    } else if (s.speed > 0) drive = 'En marcha · ' + Math.round(s.speed) + ' km/h';
    else if (!s.gear || s.gear === 'P') drive = 'Aparcado';
    else drive = 'Detenido · marcha ' + s.gear;
    $('#driveTxt').textContent = drive;
    $('#driveIco').setAttribute('href', '#i-' + ico);

    // coche: puertas, capó, maletero, ventanas
    const d = s.doors, w = s.windows;
    $$('.car-svg [data-door]').forEach(el => el.classList.toggle('open', d[el.dataset.door] === true));
    $$('.car-svg [data-win]').forEach(el => el.classList.toggle('open', w[el.dataset.win] === true));
    const badge = $('#lockBadge');
    badge.classList.toggle('unlocked', s.locked === false);
    badge.classList.toggle('unknown', s.locked == null);
    $('#lockBadgeIco').setAttribute('href', s.locked === false ? '#i-unlock' : '#i-lock');

    // neumáticos
    $$('.tp').forEach(el => {
      const v = s.tyres[el.dataset.tyre];
      $('b', el).textContent = v != null ? fmt(v, 1) : '--';
      el.classList.toggle('warn', v != null && (v < 2.0 || v > 3.0));
      el.classList.toggle('na', v == null);
    });

    // resumen
    const openDoors = Object.keys(DOOR).filter(k => d[k] === true);
    const openWins = ['fl', 'fr', 'rl', 'rr', 'sunroof'].filter(k => w[k] === true);
    const lowTyres = Object.keys(s.tyres).filter(k => s.tyres[k] != null && s.tyres[k] < 2.0);
    const issues = [];
    if (openDoors.length === 1) issues.push('Puerta ' + DOOR[openDoors[0]] + ' abierta');
    else if (openDoors.length > 1) issues.push(openDoors.length + ' puertas abiertas');
    if (d.trunk === true) issues.push('Maletero abierto');
    if (d.hood === true) issues.push('Capó abierto');
    if (openWins.length) issues.push(openWins.length === 1 ? (w.sunroof ? 'Techo abierto' : '1 ventana abierta') : openWins.length + ' ventanas abiertas');
    if (lowTyres.length) issues.push('Presión baja en ' + (lowTyres.length === 1 ? 'un neumático' : lowTyres.length + ' neumáticos'));
    const sum = $('#carSummary');
    if (issues.length) { sum.textContent = issues.join(' · '); sum.className = 'car-summary alert'; }
    else {
      sum.textContent = s.locked === true ? 'Cerrado · todo en orden' : s.locked === false ? 'Abierto · puertas y ventanas cerradas' : 'Puertas y ventanas cerradas';
      sum.className = 'car-summary ok';
    }

    // mandos
    const lockEl = $('#ctlLock');
    $('#ctlLockV').textContent = s.locked === true ? 'Cerrado' : s.locked === false ? 'Abierto' : '—';
    $('#ctlLockIco').setAttribute('href', s.locked === false ? '#i-unlock' : '#i-lock');
    lockEl.classList.toggle('on', s.locked === true);
    lockEl.classList.toggle('attn', s.locked === false);
    const wc = openWins.length;
    $('#ctlWindowsV').textContent = wc ? (wc === 1 ? '1 abierta' : wc + ' abiertas') : 'Cerradas';
    $('#ctlWindows').classList.toggle('attn', wc > 0);

    // datos
    setFact('#fTemp', s.outTemp != null ? fmt(s.outTemp) + ' °' + s.tempUnit : null);
    // temperatura exterior también a la vista, con aviso de posible hielo
    const oc = $('#outChip');
    if (oc) {
      oc.hidden = s.outTemp == null;
      if (s.outTemp != null) {
        const ice = s.tempUnit === 'F' ? s.outTemp <= 37 : s.outTemp <= 3;
        $('#outTxt').textContent = fmt(s.outTemp) + '° fuera' + (ice ? ' · posible hielo' : '');
        $('#outIco').setAttribute('href', ice ? '#i-snow' : '#i-heat');
        oc.classList.toggle('warn', ice);
      }
    }
    setFact('#fGear', s.gear ? GEAR[s.gear] : null);
    setFact('#fOdo', s.odometer != null ? fmt(s.odometer) + ' km' : null);
    setFact('#fPower', s.power != null ? (s.power > 0 ? '+' : '') + fmt(s.power) + ' kW' : null);
    setFact('#fCons', s.consumption != null ? fmt(s.consumption, 1) + ' kWh/100' : null);
    setFact('#fVolt', s.voltage != null ? fmt(s.voltage) + ' V' : null);

    renderLocation();
    renderCharge();
    if (st.view === 'map') MapV.update(false);
  }
  function setFact(sel, v) { const el = $(sel); el.textContent = v || '—'; el.classList.toggle('na', !v); }

  function renderClimate() {
    const on = st.climate.on;
    $('#ctlClimateV').textContent = on ? 'Encendido · ' + st.climate.temp + '°' : 'Apagado';
    $('#ctlClimate').classList.toggle('on-blue', on);
  }
  function renderSentryCtl() {
    const on = !!(st.config && st.config.sentry && st.config.sentry.on);
    $('#ctlSentryV').textContent = st.config ? (on ? 'Vigilando' : 'Apagado') : '—';
    $('#ctlSentry').classList.toggle('on', on);
  }
  function renderSeats() {
    const se = st.seats || {};
    $$('.seat-seg').forEach(seg => {
      const v = seg.dataset.seat === 'driver' ? se.driverStage : se.passengerStage;
      $$('button', seg).forEach(b => b.classList.toggle('active', +b.dataset.v === (v || 0)));
    });
  }

  // tiempo y conexión (se refresca cada pocos segundos)
  function renderConn() {
    const online = isOnline();
    const seen = lastSeen();
    const conn = $('#carConn');
    conn.classList.toggle('online', online && !!st.s);
    let txt;
    if (B.mode === 'demo') txt = 'Demo · datos simulados';
    else if (!st.s && !st.meta) txt = st.rxCount ? 'Sin datos todavía' : 'Conectando…';
    else if (online) txt = 'En línea · ' + ago(seen);
    else txt = 'Desconectado' + (seen ? ' · ' + ago(seen) : '');
    $('#carConnTxt').textContent = txt;

    const offline = B.mode === 'firebase' && st.rxCount > 0 && !online && !st.denied;
    $('#bnOffline').hidden = !offline || !st.net;
    // Si el coche avisó de que se quitaba el contacto, no es "sin cobertura": está apagado (dormido).
    const acc = st.meta && st.meta.acc, asleep = !online && acc && acc.on === false && acc.ts;
    $('#bnOfflineTitle').textContent = asleep ? 'Coche apagado' : 'Coche desconectado';
    $('#bnOfflineTxt').textContent = asleep
      ? 'Apagado desde las ' + hhmm(acc.ts) + ' (' + ago(acc.ts) + '). Se conectará al encenderlo.'
      : st.s
      ? 'Datos de ' + ago(seen) + '. Los mandos funcionarán cuando vuelva a conectarse.'
      : 'Aún no ha enviado datos. Abre BYDLink en la pantalla del coche.';
    $('#bnNet').hidden = st.net;
    $('#bnDenied').hidden = !st.denied;
    $('#controls').classList.toggle('offline', B.mode === 'firebase' && !online);

    $('#factsWhen').textContent = st.s && st.s.ts ? 'Actualizado ' + ago(st.s.ts) : '';
    if (st.s && st.s.location) $('#locWhen').textContent = 'Posición ' + ago(st.s.location.ts || st.s.ts);
    $('#infoConn').textContent = B.mode === 'demo' ? 'Demo' : online ? 'En línea' : 'Desconectado';
    $('#infoSeen').textContent = seen ? shortDate(seen) : '—';
  }

  function renderMeta() {
    $('#carName').textContent = carDisplayName();
    document.title = carDisplayName() + ' · BYDLink';
    renderPower();
    const v = st.meta && st.meta.appVersion, u = (st.meta && st.meta.update) || {};
    const upd = {
      descargando: 'descargando la ' + u.version + (u.progress != null ? ' (' + u.progress + ' %)' : ''),
      esperando: 'la ' + u.version + ' se instalará cuando esté parado',
      instalando: 'instalando la ' + u.version,
      lista: 'la ' + u.version + ' espera a que pulses «Instalar» en el coche',
      error: 'no se pudo actualizar a la ' + u.version
    }[u.state];
    $('#infoVer').textContent = !v ? '—'
      : upd ? v + ' · ' + upd
      : verLess(v, CAR_APP_MIN) ? v + ' · antigua, instala la ' + CAR_APP_MIN
      : (u.state === 'aldia' || u.state === 'actualizada') ? v + ' · al día' : v;
    // Aviso: el coche acaba de actualizarse (una vez por versión).
    if (u.state === 'actualizada' && u.version && Date.now() - (u.ts || 0) < 15 * 60e3) {
      const k = 'bydlink_upd_' + st.carId;
      if (lsGet(k) !== u.version) { lsSet(k, u.version); toast('Tu coche se ha actualizado a BYDLink ' + u.version, 'ok'); }
    }
    $('#infoId').textContent = st.carId || '—';
  }

  // ───────── ubicación ─────────
  const Geo = {
    key: null, addr: null, lastFetch: 0,
    cache() { try { return JSON.parse(lsGet('bydlink_geo') || '{}'); } catch (e) { return {}; } },
    lookup(lat, lng, cb) {
      const key = lat.toFixed(3) + ',' + lng.toFixed(3);
      if (key === this.key) return cb(this.addr);
      const c = this.cache();
      if (c[key]) { this.key = key; this.addr = c[key]; return cb(c[key]); }
      if (Date.now() - this.lastFetch < 15e3) return cb(null);
      this.lastFetch = Date.now();
      fetch('https://nominatim.openstreetmap.org/reverse?format=jsonv2&zoom=18&accept-language=es&lat=' + lat + '&lon=' + lng)
        .then(r => r.json()).then(j => {
          const a = j.address || {};
          const street = a.road ? a.road + (a.house_number ? ' ' + a.house_number : '') : (a.pedestrian || a.neighbourhood || '');
          const town = a.city || a.town || a.village || a.municipality || a.suburb || '';
          const line = [street, town].filter(Boolean).join(', ') || j.display_name || null;
          if (!line) return;
          this.key = key; this.addr = line;
          const cc = this.cache(); const ks = Object.keys(cc); if (ks.length > 40) delete cc[ks[0]];
          cc[key] = line; lsSet('bydlink_geo', JSON.stringify(cc));
          cb(line);
        }).catch(() => {});
    }
  };
  function renderLocation() {
    const L = st.s && st.s.location;
    $('#locActions').hidden = !L;
    if (!L) { $('#locAddr').textContent = 'Sin ubicación todavía'; $('#locWhen').textContent = ''; return; }
    const coords = fmt(L.lat, 5) + ', ' + fmt(L.lng, 5);
    $('#locNav').href = 'https://www.google.com/maps/dir/?api=1&destination=' + L.lat + ',' + L.lng;
    if (!Geo.addr || Geo.key !== L.lat.toFixed(3) + ',' + L.lng.toFixed(3)) $('#locAddr').textContent = coords;
    Geo.lookup(L.lat, L.lng, a => { if (a) $('#locAddr').textContent = a; });
  }

  // ───────── mapa ─────────
  const MapV = {
    map: null, marker: null, line: null,
    ensure() {
      if (typeof L === 'undefined') { $('#mapEmpty').hidden = false; $('#mapEmpty p').textContent = 'No se pudo cargar el mapa. Revisa tu conexión.'; return; }
      if (!this.map) {
        this.map = L.map('map', { zoomControl: false, attributionControl: true }).setView([40.4, -3.7], 5);
        this.map.attributionControl.setPrefix(false);
        L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
          maxZoom: 19, attribution: '© <a href="https://www.openstreetmap.org/copyright" target="_blank" rel="noopener">OpenStreetMap</a>'
        }).addTo(this.map);
        this.update(true);
      }
      setTimeout(() => { this.map.invalidateSize(); this.update(!this.marker); }, 60);
    },
    update(center) {
      if (!this.map) return;
      const loc = st.s && st.s.location;
      $('#mapEmpty').hidden = !!loc;
      $('#mapCenter').hidden = !loc;
      if (!loc) return;
      const ll = [loc.lat, loc.lng];
      if (!this.marker) {
        this.marker = L.marker(ll, { icon: L.divIcon({ className: 'car-pin', html: '<span></span>', iconSize: [40, 40], iconAnchor: [20, 20] }) }).addTo(this.map);
        center = true;
      } else this.marker.setLatLng(ll);
      if (center) this.map.setView(ll, 16);
    },
    trip(points) {
      if (!this.map) return;
      if (this.line) this.map.removeLayer(this.line);
      const ll = (points || []).filter(p => p && p.lat != null).map(p => [p.lat, p.lng]);
      if (!ll.length) { toast('Este viaje no tiene recorrido guardado.'); return; }
      this.line = L.polyline(ll, { color: getComputedStyle(document.documentElement).getPropertyValue('--blue').trim() || '#60a5fa', weight: 5, opacity: .9 }).addTo(this.map);
      this.map.fitBounds(this.line.getBounds().pad(0.2));
    }
  };
  $('#mapCenter').addEventListener('click', () => MapV.update(true));

  function renderTrips(trips) {
    const box = $('#tripList');
    if (!trips || !trips.length) {
      box.innerHTML = '<div class="empty">' + icon('route') + '<b>Aún no hay viajes</b>Cuando la app del coche guarde recorridos, aparecerán aquí.</div>';
      return;
    }
    box.innerHTML = '';
    trips.forEach(t => {
      const el = document.createElement('button');
      el.type = 'button'; el.className = 'item';
      el.innerHTML = '<span class="item-ico blue">' + icon('route') + '</span>' +
        '<span class="item-main"><b>' + esc(window.BYDHistory ? window.BYDHistory.tripTitle(t) : (t.startAddr || 'Inicio') + ' → ' + (t.endAddr || 'Fin')) + '</b>' +
        '<small>' + (t.start ? shortDate(t.start) : '') + (t.durationSec ? ' · ' + dur(t.durationSec / 60) : '') + '</small></span>' +
        '<span class="item-end">' + (t.distanceKm != null ? fmt(t.distanceKm, 1) + ' km' : '') + '</span>';
      el.addEventListener('click', () => {
        $$('#tripList .item').forEach(x => x.classList.remove('active')); el.classList.add('active');
        MapV.ensure(); window.scrollTo({ top: 0, behavior: 'smooth' });
        B.getTripPoints(t.id).then(pts => MapV.trip(pts));
      });
      box.appendChild(el);
    });
  }

  // ───────── centinela ─────────
  const CAM = { front: 'Frontal', back: 'Trasera', left: 'Izquierda', right: 'Derecha', mosaic: '4 en 1' };
  function renderSentry() {
    const sc = (st.config && st.config.sentry) || {};
    const on = !!sc.on;
    $('#sentrySwitch').checked = on;
    $('#sentryHero').classList.toggle('on', on);
    $('#sentryTxt').textContent = !st.config ? 'Sin datos del coche' : on ? 'Vigilando el coche' : 'Apagado';
    if (sc.sensitivity && document.activeElement !== $('#sensRange')) {
      $('#sensRange').value = sc.sensitivity; $('#sensOut').textContent = sc.sensitivity;
    }
    renderSentryCtl();
  }
  function renderEvents(events) {
    const box = $('#eventList');
    if (!events || !events.length) {
      box.innerHTML = '<div class="empty">' + icon('shield') + '<b>Sin detecciones</b>Si el centinela ve movimiento cerca del coche, lo verás aquí.</div>';
      return;
    }
    let html = '', day = '';
    events.slice(0, 60).forEach(ev => {
      const dl = ev.ts ? dayLabel(ev.ts) : '';
      if (dl !== day) { day = dl; html += '<div class="day">' + esc(dl) + '</div>'; }
      const lead = ev.hasThumb && ev.id
        ? '<img class="ev-thumb" data-thumb="' + esc(ev.id) + '" alt="">'
        : '<span class="item-ico red">' + icon('eye') + '</span>';
      const vid = ev.clip && ev.videoId ? ' data-vid="' + esc(ev.videoId) + '"' : '';
      html += '<div class="item' + (ev.hasThumb ? ' tappable' : '') + '"' + (ev.hasThumb ? ' data-ev="' + esc(ev.id) + '"' : '') + vid + '>' + lead +
        '<span class="item-main"><b>Movimiento detectado</b><small>Cámara ' + esc((CAM[ev.camera] || ev.camera || '').toLowerCase()) + '</small></span>' +
        '<span class="item-end">' + (ev.ts ? hhmm(ev.ts) : '') + '</span></div>';
    });
    box.innerHTML = html;
    // Miniaturas: van aparte (no engordan la lista); se piden solo las primeras y se guardan.
    $$('img[data-thumb]', box).slice(0, 16).forEach(img => thumb(img.dataset.thumb).then(t => { if (t) img.src = t; }));
  }
  const thumbCache = new Map();
  function thumb(key) {
    if (!B.sentryThumb) return Promise.resolve(null);
    if (!thumbCache.has(key)) thumbCache.set(key, B.sentryThumb(key).then(t => t && t.jpeg ? 'data:image/jpeg;base64,' + t.jpeg : null, () => null));
    return thumbCache.get(key);
  }
  $('#eventList').addEventListener('click', e => {
    const it = e.target.closest('[data-ev]'); if (!it) return;
    thumb(it.dataset.ev).then(src => {
      if (!src) return toast('Esta detección no tiene imagen.', 'err');
      Sheet.open('<h3>Movimiento detectado</h3><p class="sub">' + esc(it.querySelector('small').textContent) + ' · ' + esc(it.querySelector('.item-end').textContent) + '</p>' +
        '<img class="photo-img" src="' + src + '" alt="Imagen del centinela">' +
        '<div class="actions">' + (it.dataset.vid ? '<button type="button" class="btn btn-primary btn-block" data-video="' + esc(it.dataset.vid) + '">' + icon('video') + 'Ver el vídeo</button>' : '') +
        '<a class="btn btn-soft btn-block" download="centinela-' + esc(it.dataset.ev) + '.jpg" href="' + src + '">' + icon('download') + 'Guardar imagen</a></div>');
    });
  });
  $('#sentrySwitch').addEventListener('change', e => {
    const on = e.target.checked;
    command('setSentryConfig', { on }, { ok: on ? 'Centinela activado' : 'Centinela apagado', what: 'cambiar el centinela' })
      .then(r => { if (!r.ok) e.target.checked = !on; });
  });
  $('#sensRange').addEventListener('input', e => { $('#sensOut').textContent = e.target.value; });
  $('#sensRange').addEventListener('change', e => {
    const prev = (st.config && st.config.sentry && st.config.sentry.sensitivity) || 5;
    command('setSentryConfig', { sensitivity: +e.target.value }, { ok: 'Sensibilidad guardada', what: 'guardar la sensibilidad' })
      .then(r => { if (!r.ok) { e.target.value = prev; $('#sensOut').textContent = prev; } });
  });

  // ───────── carga ─────────
  function renderCharge() {
    const s = st.s; if (!s) return;
    $('#chargeNowSoc').textContent = s.soc != null ? Math.round(s.soc) : '--';
    $('#chargeNow').classList.toggle('charging', s.charging);
    $('#chargeNowTxt').textContent = s.charging
      ? 'Cargando' + (s.chargingPower ? ' a ' + fmt(s.chargingPower, 1) + ' kW' : '') +
        (s.chargedKwh ? ' · ' + fmt(s.chargedKwh, 2) + ' kWh cargados' : '') + (s.chargeRestMin ? ' · lleno en ' + dur(s.chargeRestMin) : '')
      : 'No está cargando' + (s.rangeKm != null ? ' · ' + fmt(s.rangeKm) + ' km de autonomía' : '');
  }
  // El coste lo calcula history.js con los precios y lugares del usuario; sin él, el que traiga la carga.
  const costOf = s => window.BYDHistory ? window.BYDHistory.cost(s) : s.costEur;
  function renderSessions(list) {
    st.sessions = list;
    const has = list && list.length;
    $('#chgKpis').hidden = !has; $('#chgChartCard').hidden = !has;
    if (!has) {
      $('#chgList').innerHTML = '<div class="empty">' + icon('bolt') + '<b>Aún no hay cargas registradas</b>Cada carga completa aparecerá aquí con su energía y coste.</div>';
      return;
    }
    const kwh = list.reduce((a, s) => a + (s.kwh || 0), 0);
    const cost = list.reduce((a, s) => a + (costOf(s) || 0), 0);
    $('#chgCount').textContent = list.length;
    $('#chgKwh').textContent = fmt(kwh, kwh < 100 ? 1 : 0);
    $('#chgCost').textContent = fmt(cost, 2) + ' €';
    const asc = list.slice().sort((a, b) => (a.start || 0) - (b.start || 0)).slice(-24);
    const max = Math.max.apply(null, asc.map(s => s.kwh || 0)) || 1;
    $('#chgChart').innerHTML = asc.map(s => '<i class="' + (s.type === 'DC' ? 'dc' : '') + '" style="height:' +
      Math.max(5, Math.round(((s.kwh || 0) / max) * 100)) + '%" title="' + fmt(s.kwh || 0, 1) + ' kWh"></i>').join('');
    $('#chgList').innerHTML = list.map(s =>
      '<div class="item"><span class="item-ico ' + (s.type === 'DC' ? 'dcb' : 'ac') + '">' + esc(s.type || 'AC') + '</span>' +
      '<span class="item-main"><b>' + esc(s.place || 'Carga') + ' · ' + (s.socStart || 0) + ' → ' + (s.socEnd || 0) + '%</b>' +
      '<small>' + (s.start ? shortDate(s.start) : '') + (s.durationMin ? ' · ' + dur(s.durationMin) : '') + '</small></span>' +
      '<span class="item-end">' + fmt(s.kwh || 0, 1) + ' kWh' + (costOf(s) != null ? '<small>' + fmt(costOf(s), 2) + ' €</small>' : '') + '</span></div>'
    ).join('');
  }
  window.BYDApp.renderSessions = () => renderSessions(st.sessions);

  // ───────── mandos (inicio) ─────────
  $('#ctlLock').addEventListener('click', async () => {
    const s = st.s; if (!s) return toast('Aún no hay datos del coche.');
    if (s.locked === false) {
      command('setLock', { locked: true }, { el: $('#ctlLock'), ok: 'Coche cerrado', what: 'cerrar' });
      return;
    }
    if (!isOnline()) return command('setLock', {});
    const yes = await confirmSheet({ title: '¿Abrir el coche?', text: 'Se desbloquearán las puertas. Hazlo solo si estás cerca o sabes quién va a entrar.', ok: 'Abrir el coche', danger: true });
    if (yes) command('setLock', { locked: false }, { el: $('#ctlLock'), ok: 'Coche abierto', what: 'abrir' });
  });

  $('#ctlClimate').addEventListener('click', () => {
    // Estado real del clima si el coche lo publica (status.climate); si no, lo último que pedimos.
    const real = (st.raw && st.raw.climate) || {};
    let t = real.temp != null ? real.temp : st.climate.temp;
    t = Math.max(17, Math.min(33, t || 21));   // el climatizador de BYD va de 17 a 33
    const on = real.on != null ? real.on : st.climate.on;
    const x = { fan: real.auto ? 0 : (real.fan || 0), recirc: !!real.recirc, defrostFront: !!real.defrostFront, defrostRear: !!real.defrostRear };
    const fanBtns = [0, 1, 2, 3, 4, 5, 6, 7].map(n => '<button type="button" data-fan="' + n + '"' + (x.fan === n ? ' class="active"' : '') + '>' + (n ? n : 'Auto') + '</button>').join('');
    const tog = (k, label, ico) => '<button type="button" class="chip' + (x[k] ? ' on' : '') + '" data-tog="' + k + '">' + icon(ico) + label + '</button>';
    // Salida del aire: cara=1, pies=2, parabrisas=4 (se combinan). El coche lo llama modo 1..7.
    const MODE_OF = { 1: 1, 3: 2, 2: 3, 6: 4, 4: 5, 7: 6, 5: 7 };
    const BITS_OF = {}; Object.keys(MODE_OF).forEach(b => { BITS_OF[MODE_OF[b]] = +b; });
    let vents = BITS_OF[real.mode] || 0, ventsChanged = false;
    const vent = (bit, label) => '<button type="button" class="chip' + (vents & bit ? ' on' : '') + '" data-vent="' + bit + '">' + label + '</button>';
    Sheet.open(
      '<h3>Climatización</h3><p class="sub">' + (on ? 'Encendido' + (real.temp != null ? ' a ' + real.temp + '°' : '') : 'Elige la temperatura y enciéndela para que el coche esté a gusto cuando llegues.') + '</p>' +
      '<div class="temp"><button class="round" data-d="-1" aria-label="Bajar">' + icon('minus') + '</button>' +
      '<output id="tempOut">' + t + '°</output><button class="round" data-d="1" aria-label="Subir">' + icon('plus') + '</button></div>' +
      '<p class="sheet-label">Ventilador</p><div class="seg fan-seg">' + fanBtns + '</div>' +
      '<p class="sheet-label">Salida del aire</p><div class="chips">' + vent(4, '↑ Parabrisas') + vent(1, '→ Cara') + vent(2, '↓ Pies') + '</div>' +
      '<div class="chips">' + tog('recirc', 'Recircular', 'refresh') + tog('defrostFront', 'Desempañar delante', 'window') + tog('defrostRear', 'Desempañar detrás', 'heat') + '</div>' +
      '<div class="actions"><button class="btn btn-primary btn-block" data-on>' + icon('power') + (on ? 'Aplicar' : 'Encender') + '</button>' +
      (on ? '<button class="btn btn-soft btn-block" data-off>Apagar</button>' : '') + '</div>',
      body => {
        $$('[data-d]', body).forEach(b => b.addEventListener('click', () => {
          t = Math.max(17, Math.min(33, t + (+b.dataset.d))); $('#tempOut', body).textContent = t + '°';
        }));
        $$('[data-fan]', body).forEach(b => b.addEventListener('click', () => {
          x.fan = +b.dataset.fan; $$('[data-fan]', body).forEach(o => o.classList.toggle('active', o === b));
        }));
        $$('[data-tog]', body).forEach(b => b.addEventListener('click', () => {
          x[b.dataset.tog] = !x[b.dataset.tog]; b.classList.toggle('on', x[b.dataset.tog]);
        }));
        $$('[data-vent]', body).forEach(b => b.addEventListener('click', () => {
          const next = vents ^ (+b.dataset.vent);
          if (!next) return toast('El aire tiene que salir por algún sitio.');
          vents = next; ventsChanged = true;
          $$('[data-vent]', body).forEach(o => o.classList.toggle('on', !!(vents & +o.dataset.vent)));
        }));
        const send = turnOn => {
          Sheet.close();
          const payload = turnOn
            ? Object.assign({ on: true, temp: t, auto: x.fan === 0, recirc: x.recirc, defrostFront: x.defrostFront, defrostRear: x.defrostRear },
                x.fan ? { fan: x.fan } : {}, ventsChanged && MODE_OF[vents] ? { mode: MODE_OF[vents] } : {})   // Firebase no admite campos undefined
            : { on: false };
          command('setClimate', payload, { el: $('#ctlClimate'), ok: turnOn ? 'Clima encendido a ' + t + '°' : 'Clima apagado', what: 'cambiar el clima' })
            .then(r => {
              if (!r.ok) return;
              if (r.data && r.data.applied === false) toast('El coche ha recibido la orden pero no confirma el cambio.', 'err');
              st.climate = { on: turnOn, temp: t }; lsSet('bydlink_climate_' + st.carId, JSON.stringify(st.climate)); renderClimate();
            });
        };
        $('[data-on]', body).addEventListener('click', () => send(true));
        const off = $('[data-off]', body); if (off) off.addEventListener('click', () => send(false));
      });
  });

  $('#ctlWindows').addEventListener('click', () => {
    const w = (st.s && st.s.windows) || {}, pct = (st.raw && st.raw.windowsPct) || {};
    const names = { fl: 'Del. izq.', fr: 'Del. der.', rl: 'Tras. izq.', rr: 'Tras. der.', sunroof: 'Techo' };
    if (!can('sunroof')) delete names.sunroof;
    const AREA = { fl: 1, fr: 2, rl: 3, rr: 4 };
    const state = k => pct[k] != null ? (pct[k] > 0 ? 'Abierta ' + pct[k] + ' %' : 'Cerrada') : (w[k] ? 'Abierta' : 'Cerrada');
    const move = can('windows');   // en el Surf solo se puede ver si están abiertas, no moverlas
    Sheet.open(
      '<h3>Ventanas</h3><p class="sub">' + (move ? 'Toca una ventana para abrirla o cerrarla.' : 'Tu coche no deja mover las ventanas desde la app: aquí ves si alguna se ha quedado abierta.') + '</p>' +
      '<div class="win-mini">' + Object.keys(names).map(k =>
        '<button type="button" data-win="' + k + '" class="' + (w[k] ? 'open' : '') + '"' + (move ? '' : ' disabled') + '><b>' + state(k) + '</b>' + names[k] + '</button>').join('') + '</div>' +
      (!move ? '' :
      '<div class="actions"><button class="btn btn-primary btn-block" data-close-all>Subir todas</button>' +
      '<button class="btn btn-soft btn-block" data-open-all>Bajar todas</button></div>'),
      body => {
        if (!move) return;
        $('[data-close-all]', body).addEventListener('click', () => { Sheet.close(); command('closeWindows', {}, { el: $('#ctlWindows'), ok: 'Subiendo ventanas', what: 'subir las ventanas' }); });
        $('[data-open-all]', body).addEventListener('click', () => { Sheet.close(); command('openWindows', {}, { el: $('#ctlWindows'), ok: 'Bajando ventanas', what: 'bajar las ventanas' }); });
        $$('[data-win]', body).forEach(b => b.addEventListener('click', () => {
          const k = b.dataset.win, open = !w[k];
          Sheet.close();
          const done = { ok: (open ? 'Abriendo ' : 'Cerrando ') + (k === 'sunroof' ? 'el techo' : 'la ventana'), what: 'mover la ventana', el: $('#ctlWindows') };
          if (k === 'sunroof') command('setSunroof', { open }, done);
          else command('setWindow', { area: AREA[k], open }, done);
        }));
      });
  });

  $('#ctlSentry').addEventListener('click', () => {
    if (!st.config) return toast('Aún no hay datos del coche.');
    const on = !(st.config.sentry && st.config.sentry.on);
    command('setSentryConfig', { on }, { el: $('#ctlSentry'), ok: on ? 'Centinela activado' : 'Centinela apagado', what: 'cambiar el centinela' });
  });

  $$('.seat-seg').forEach(seg => seg.addEventListener('click', e => {
    const b = e.target.closest('button'); if (!b) return;
    const v = +b.dataset.v, who = seg.dataset.seat;
    const prev = st.seats ? Object.assign({}, st.seats) : null;
    st.seats = Object.assign({}, st.seats, who === 'driver' ? { driverStage: v } : { passengerStage: v });
    renderSeats();
    seg.classList.add('busy');
    command('setSeatComfort', who === 'driver' ? { driver: v } : { passenger: v }, { key: 'seat-' + who, what: 'cambiar el asiento' })
      .then(r => { seg.classList.remove('busy'); if (!r.ok) { st.seats = prev; renderSeats(); } });
  }));

  // ───────── cámaras ─────────
  // Dos modos: "ligero" (JPEG por Firebase cada ~0,5 s: funciona con cualquier red) y "HD" (WebRTC
  // directo coche↔móvil: más fluido, pero sin servidor TURN puede no conectar fuera de casa).
  const Cam = {
    cur: null, timer: null, q: lsGet('bydlink_camq') || 'jpeg',
    unsub: null, beat: null, lastFrame: 0, startedAt: 0, ageTimer: null,
    state(s, msg) {
      const box = $('#camBox');
      box.dataset.state = s; box.dataset.q = this.q;
      const t = { idle: 'Elige una cámara para verla en directo', connecting: 'Conectando con el coche…', lost: 'Se ha cortado la señal…' };
      $('#camMsg').textContent = msg || t[s] || '';
      $('#camStop').hidden = s === 'idle';
      if (s === 'error' || s === 'idle') clearTimeout(this.timer);
    },
    setQuality(q) {
      if (q === this.q) return;
      const cam = this.cur;
      this.stop();
      this.q = q; lsSet('bydlink_camq', q);
      $$('#camQuality button').forEach(b => b.classList.toggle('active', b.dataset.q === q));
      $('#camBox').dataset.q = q;
      if (cam) this.start(cam);
    },
    start(cam) {
      const switching = this.cur && this.cur !== cam && $('#camBox').dataset.state === 'live';
      this.cur = cam;
      $$('#camSeg button').forEach(b => b.classList.toggle('active', b.dataset.cam === cam));
      $('#camName').textContent = 'Cámara ' + (CAM[cam] || cam).toLowerCase();
      if (!isOnline()) { this.halt(); this.state('error', 'El coche está desconectado. Enciéndelo o espera a que tenga cobertura.'); return; }
      if (!carHas('2.2')) { this.halt(); this.state('error', 'Para ver las cámaras, actualiza la app del coche a la versión 2.2 (Ajustes → App del coche).'); return; }
      if (!switching) this.state('connecting');
      clearTimeout(this.timer);
      this.timer = setTimeout(() => {
        if ($('#camBox').dataset.state === 'connecting') {
          if (this.q === 'hd') return this.toLight(cam);
          this.halt();
          this.state('error', 'No llega imagen del coche. Puede estar dormido o sin cobertura.');
        }
      }, this.q === 'hd' ? 15000 : 20000);
      if (this.q === 'hd') return this.startHd(cam, switching);
      if (switching && this.unsub) {
        this.startedAt = Date.now();
        return B.sendCommand('switchLiveJpeg', { camera: cam }).then(r => { if (this.cur === cam && !r.ok) toast(r.error || 'No se ha podido cambiar de cámara.', 'err'); });
      }
      this.startJpeg(cam);
    },
    startJpeg(cam) {
      this.startedAt = Date.now();
      if (!this.unsub && B.camLive) {
        this.unsub = B.camLive(f => {
          if (!this.cur || !f || !f.jpeg || this.q !== 'jpeg') return;
          if (f.camera && f.camera !== this.cur) return;                 // aún llega la cámara anterior
          if (f.ts && f.ts < this.startedAt - 15e3) return;              // imagen vieja de otra sesión
          $('#camImg').src = 'data:image/jpeg;base64,' + f.jpeg;
          this.lastFrame = Date.now();
          if ($('#camBox').dataset.state !== 'live') { clearTimeout(this.timer); this.state('live'); }
        });
      }
      if (B.camViewer) B.camViewer();
      clearInterval(this.beat);
      this.beat = setInterval(() => { if (B.camViewer) B.camViewer(); }, 8000);   // latido: el coche para solo si nadie mira
      clearInterval(this.ageTimer);
      this.ageTimer = setInterval(() => {
        const age = this.lastFrame ? Math.round((Date.now() - this.lastFrame) / 1000) : 0;
        $('#camAge').textContent = age >= 3 ? 'hace ' + age + ' s' : '';
        if (this.lastFrame && age > 15 && $('#camBox').dataset.state === 'live') this.state('error', 'Se ha cortado la imagen del coche.');
      }, 1000);
      B.sendCommand('startLiveJpeg', { camera: cam, fps: 2 }).then(r => {
        if (this.cur === cam && !r.ok) { this.halt(); this.state('error', r.error || 'El coche no ha podido abrir la cámara.'); }
      });
    },
    /** El HD no conecta: con datos móviles en el coche y en el móvil la conexión directa casi nunca pasa. */
    toLight(cam) {
      if (this.q !== 'hd' || this.cur !== cam) return;
      toast('El vídeo HD no conecta en esta red (pasa con datos móviles). Te pongo el modo Ligero.');
      this.setQuality('jpeg');
    },
    startHd(cam, switching) {
      const onState = (s, msg) => { if (!this.cur) return; if (s === 'error') return this.toLight(cam); this.state(s, msg); };
      if (switching && LiveCam.camera) LiveCam.switchCam(cam, $('#camVideo'), onState);
      else LiveCam.start(cam, $('#camVideo'), onState);
    },
    /** Corta lo que haya en marcha sin cambiar la pantalla. */
    halt() {
      clearTimeout(this.timer); clearInterval(this.beat); clearInterval(this.ageTimer);
      if (this.unsub) { this.unsub(); this.unsub = null; }
      if (this.q === 'jpeg' && this.cur) B.sendCommand('stopLiveJpeg', {}).catch(() => {});
      LiveCam.stop();
      const v = $('#camVideo'); v.pause(); v.srcObject = null;
      $('#camAge').textContent = '';
    },
    stop() {
      if (!this.cur) return;
      this.halt();
      this.cur = null;
      $$('#camSeg button').forEach(b => b.classList.remove('active'));
      this.state('idle');
    },
    photo() {
      if (!isOnline()) return toast('El coche está desconectado.', 'err');
      const cam = this.cur || 'front', since = Date.now(), btn = $('#camPhoto');
      busyBtn(btn, true);
      let done = false, unsub = null, to = null;
      const finish = () => { done = true; busyBtn(btn, false); if (unsub) unsub(); clearTimeout(to); };
      to = setTimeout(() => { if (!done) { finish(); toast('El coche no ha mandado la foto. Inténtalo otra vez.', 'err'); } }, 25000);
      unsub = B.camPhoto ? B.camPhoto(p => {
        if (done || !p || !p.jpeg || (p.ts && p.ts < since - 2000)) return;
        finish();
        const src = 'data:image/jpeg;base64,' + p.jpeg, when = p.ts ? shortDate(p.ts) : '';
        Sheet.open('<h3>Foto · cámara ' + esc((CAM[p.camera || cam] || cam).toLowerCase()) + '</h3><p class="sub">' + esc(when) + '</p>' +
          '<img class="photo-img" src="' + src + '" alt="Foto del coche">' +
          '<div class="actions"><a class="btn btn-primary btn-block" download="bydlink-' + (p.ts || since) + '.jpg" href="' + src + '">' + icon('download') + 'Guardar foto</a></div>');
      }) : null;
      B.sendCommand('takePhoto', { camera: cam }).then(r => { if (!done && !r.ok) { finish(); toast(r.error || 'El coche no ha podido hacer la foto.', 'err'); } });
    }
  };
  $('#camVideo').addEventListener('playing', () => { if (Cam.cur && Cam.q === 'hd') { clearTimeout(Cam.timer); Cam.state('live'); } });
  $('#camSeg').addEventListener('click', e => { const b = e.target.closest('button'); if (b) Cam.start(b.dataset.cam); });
  $('#camQuality').addEventListener('click', e => { const b = e.target.closest('button'); if (b) Cam.setQuality(b.dataset.q); });
  $$('#camQuality button').forEach(b => b.classList.toggle('active', b.dataset.q === Cam.q));
  $('#camBox').dataset.q = Cam.q;
  $('#camRetry').addEventListener('click', () => { if (Cam.cur) { const c = Cam.cur; Cam.halt(); $('#camBox').dataset.state = 'idle'; Cam.start(c); } });
  $('#camStop').addEventListener('click', () => Cam.stop());
  $('#camPhoto').addEventListener('click', () => Cam.photo());
  // Prueba de la cámara del coche (la hace en un proceso aparte y deja el resultado en diag/cam).
  function showCamDiag() {
    if (!B.camDiag) return;
    B.camDiag().then(d => {
      $('#camDiag').textContent = d ? 'Última prueba' + (d.ts ? ' (' + shortDate(d.ts) + ')' : '') + ': ' + (d.verdictMsg || d.verdict) : '';
    }).catch(() => {});
  }
  $('#camTest').addEventListener('click', () => {
    command('camTest', {}, { el: $('#camTest'), what: 'probar la cámara' }).then(r => {
      if (!r.ok) return;
      toast('Probando la cámara del coche (1–2 minutos)…', 'ok');
      setTimeout(showCamDiag, 90e3);
    });
  });
  // Solo las cámaras que tiene este coche (p. ej. el Dolphin Surf solo tiene trasera).
  function applyCamViews() {
    if (!B.camViews) return;
    B.camViews().then(list => {
      if (!Array.isArray(list) || !list.length) return;
      const ids = list.map(c => c && c.id).filter(Boolean);
      $$('#camSeg button').forEach(b => { b.hidden = ids.indexOf(b.dataset.cam) < 0; });
      $('#camSeg').classList.toggle('single', ids.length === 1);
      if (ids.length === 1 && !Cam.cur) $('#camMsg').textContent = 'Tu coche tiene cámara ' + (CAM[ids[0]] || ids[0]).toLowerCase() + ': tócala para verla';
    }).catch(() => {});
  }
  document.addEventListener('click', e => { if (e.target.closest('.tab[data-goto="cam"]')) { showCamDiag(); applyCamViews(); } });
  if ((location.hash || '') === '#cam') setTimeout(applyCamViews, 1500);
  $('#camFull').addEventListener('click', () => {
    const v = $('#camVideo'), box = $('#camBox');
    if (box.requestFullscreen) box.requestFullscreen().catch(() => {});
    else if (v.webkitEnterFullscreen && Cam.q === 'hd') v.webkitEnterFullscreen();
  });
  document.addEventListener('visibilitychange', () => { if (document.hidden) Cam.stop(); });

  // ───────── ajustes ─────────
  function renderCars() {
    const box = $('#carList');
    box.innerHTML = '';
    st.cars.forEach(c => {
      const el = document.createElement('button');
      el.type = 'button'; el.className = 'item';
      const cur = c.id === st.carId;
      el.innerHTML = '<span class="item-ico ' + (cur ? 'green' : '') + '">' + icon('car') + '</span>' +
        '<span class="item-main"><b>' + esc(c.name) + '</b><small>' + (cur ? 'Coche actual' : 'Toca para usarlo') + '</small></span>' +
        (cur ? '<span class="badge">Actual</span>' : '') + icon('right');
      el.addEventListener('click', () => carSheet(c));
      box.appendChild(el);
    });
    $('#addCarBtn').hidden = B.mode !== 'firebase';
  }
  function carSheet(c) {
    const cur = c.id === st.carId;
    Sheet.open(
      '<h3>' + esc(c.name) + '</h3><p class="sub mono">' + esc(c.id) + '</p>' +
      '<div class="actions">' +
      (!cur ? '<button class="btn btn-primary btn-block" data-use>' + icon('car') + 'Usar este coche</button>' : '') +
      '<button class="btn btn-soft btn-block" data-rename>' + icon('pencil') + 'Cambiar nombre</button>' +
      (B.mode === 'firebase' ? '<button class="btn btn-danger-soft btn-block" data-unpair>Desvincular de mi cuenta</button>' : '') +
      '</div>',
      body => {
        const use = $('[data-use]', body);
        if (use) use.addEventListener('click', () => { B.saveCar(c.id); location.hash = 'home'; location.reload(); });
        $('[data-rename]', body).addEventListener('click', () => renameSheet(c));
        const un = $('[data-unpair]', body);
        if (un) un.addEventListener('click', async () => {
          Sheet.close();
          const yes = await confirmSheet({ title: '¿Desvincular «' + c.name + '»?', text: 'Dejarás de verlo y controlarlo desde esta cuenta. Podrás volver a vincularlo con el código del coche.', ok: 'Desvincular', danger: true });
          if (!yes) return;
          B.unpair(c.id).then(() => { if (c.id === st.carId) { try { localStorage.removeItem('bydlink_car'); } catch (e) {} } location.reload(); })
            .catch(e => toast('No se pudo desvincular: ' + e.message, 'err'));
        });
      });
  }
  function renameSheet(c) {
    Sheet.open(
      '<h3>Nombre del coche</h3><p class="sub">Solo lo ves tú, en esta cuenta.</p>' +
      '<input type="text" id="renameIn" maxlength="30" value="' + esc(c.name) + '" aria-label="Nombre">' +
      '<div class="actions"><button class="btn btn-primary btn-block" data-save>Guardar</button><button class="btn btn-soft btn-block" data-close>Cancelar</button></div>',
      body => {
        const inp = $('#renameIn', body); setTimeout(() => inp.focus(), 280);
        const save = () => {
          const name = inp.value.trim().slice(0, 30); if (!name) return;
          B.renameCar(c.id, name).then(() => {
            c.name = name; Sheet.close(); renderCars(); renderMeta(); toast('Nombre guardado', 'ok');
          }).catch(e => toast('No se pudo guardar: ' + e.message, 'err'));
        };
        $('[data-save]', body).addEventListener('click', save);
        inp.addEventListener('keydown', e => { if (e.key === 'Enter') save(); });
      });
  }
  $('#addCarBtn').addEventListener('click', () => showPair(true));
  $('#carIdBtn').addEventListener('click', () => {
    if (st.cars.length > 1) {
      Sheet.open('<h3>Cambiar de coche</h3><p class="sub">Elige qué coche quieres ver.</p><div class="list" id="pickList"></div>', body => {
        const box = $('#pickList', body);
        st.cars.forEach(c => {
          const b = document.createElement('button'); b.type = 'button'; b.className = 'item' + (c.id === st.carId ? ' active' : '');
          b.innerHTML = '<span class="item-ico">' + icon('car') + '</span><span class="item-main"><b>' + esc(c.name) + '</b></span>' + (c.id === st.carId ? icon('check') : '');
          b.addEventListener('click', () => { if (c.id !== st.carId) { B.saveCar(c.id); location.reload(); } else Sheet.close(); });
          box.appendChild(b);
        });
      });
    } else go('settings');
  });

  $$('[data-auto]').forEach(inp => inp.addEventListener('change', () => {
    const v = inp.checked;
    command('setAutomation', { key: inp.dataset.auto, value: v }, { ok: 'Guardado', what: 'guardar' })
      .then(r => { if (!r.ok) inp.checked = !v; });
  }));
  // ───────── con el coche apagado (config.power + meta/power, ver PowerKeeper en el coche) ─────────
  function renderPower() {
    const c = (st.config && st.config.power) || {}, m = (st.meta && st.meta.power) || null;
    const on = c.keepAwake !== false;
    $('#pwKeep').checked = on;
    $('#pwOpts').hidden = !on;
    if (c.maxHours != null) $('#pwHours').value = String(c.maxHours);
    if (c.minSoc != null) $('#pwSoc').value = String(c.minSoc);
    const v = m && typeof m.aux12v === 'number' ? ' · 12 V: <b>' + fmt(m.aux12v, 1) + ' V</b>' : '';
    const why = { '12V bajos': 'la batería de 12 V está baja', 'batería baja': 'la batería principal está baja',
      'tiempo máximo': 'se ha cumplido el tiempo máximo', 'desactivado': 'está desactivado' };
    $('#pwStatus').innerHTML = !m ? '' :
      m.accOn ? 'Coche encendido' + v :
      m.keepAwake ? 'Conectado aunque está apagado' + (m.offSince ? ' (desde las ' + hhmm(m.offSince) + ')' : '') + v :
      'Se ha dejado dormir: ' + esc(why[m.reason] || m.reason || 'apagado') + v;
  }
  function savePower(patch) {
    const cur = Object.assign({}, (st.config && st.config.power) || {}, patch);
    command('setConfig', { power: cur }, { ok: 'Guardado', what: 'cambiar este ajuste' });
  }
  $('#pwKeep').addEventListener('change', e => { $('#pwOpts').hidden = !e.target.checked; savePower({ keepAwake: e.target.checked }); });
  $('#pwHours').addEventListener('change', e => savePower({ maxHours: +e.target.value }));
  $('#pwSoc').addEventListener('change', e => savePower({ minSoc: +e.target.value }));

  function renderConfig() {
    renderPower();
    const a = (st.config && st.config.automations) || {};
    $$('[data-auto]').forEach(inp => { if (typeof a[inp.dataset.auto] === 'boolean') inp.checked = a[inp.dataset.auto]; });
    renderSentry();
    if (st.config && typeof st.config._climateOn === 'boolean') { st.climate.on = st.config._climateOn; renderClimate(); }
  }

  $('#logoutBtn').addEventListener('click', () => {
    if (!B.logout) return;
    B.logout().then(() => { location.hash = ''; location.reload(); });
  });
  document.addEventListener('click', e => { if (e.target.closest('[data-action="pair-again"]')) showPair(true); });

  // ───────── acceso ─────────
  function setupAuth() {
    let mode = 'login';
    const msg = (m, ok) => { const el = $('#authMsg'); el.textContent = m || ''; el.classList.toggle('ok', !!ok); };
    const busyBtn = (b, on) => { b.classList.toggle('loading', on); b.disabled = on; };
    function setMode(m) {
      mode = m;
      $('#authTitle').textContent = m === 'login' ? 'Entra en tu cuenta' : 'Crea tu cuenta';
      $('#authSubmit').textContent = m === 'login' ? 'Entrar' : 'Crear cuenta';
      $('#authSwitchTxt').textContent = m === 'login' ? '¿Aún no tienes cuenta?' : '¿Ya tienes cuenta?';
      $('#authToggle').textContent = m === 'login' ? 'Crear cuenta' : 'Entrar';
      $('#authPass').autocomplete = m === 'login' ? 'current-password' : 'new-password';
      $('#authForgot').hidden = m !== 'login';
      msg('');
    }
    $('#authToggle').addEventListener('click', () => setMode(mode === 'login' ? 'register' : 'login'));
    $('#pwToggle').addEventListener('click', () => {
      const p = $('#authPass'), show = p.type === 'password';
      p.type = show ? 'text' : 'password';
      $('#pwToggle use').setAttribute('href', show ? '#i-eye-off' : '#i-eye');
      $('#pwToggle').setAttribute('aria-label', show ? 'Ocultar contraseña' : 'Mostrar contraseña');
    });
    $('#authForm').addEventListener('submit', e => {
      e.preventDefault();
      const email = $('#authEmail').value.trim(), pw = $('#authPass').value;
      if (!email || !/^\S+@\S+\.\S+$/.test(email)) return msg('Escribe un correo válido.');
      if (pw.length < 6) return msg('La contraseña debe tener al menos 6 caracteres.');
      msg(''); busyBtn($('#authSubmit'), true);
      (mode === 'login' ? B.login(email, pw) : B.register(email, pw))
        .catch(err => msg(authMsg(err)))
        .then(() => busyBtn($('#authSubmit'), false));
    });
    $('#googleBtn').addEventListener('click', () => {
      msg(''); busyBtn($('#googleBtn'), true);
      B.loginGoogle().catch(err => msg(authMsg(err))).then(() => busyBtn($('#googleBtn'), false));
    });
    $('#authForgot').addEventListener('click', () => {
      const email = $('#authEmail').value.trim();
      if (!email) { msg('Escribe tu correo arriba y vuelve a pulsar.'); $('#authEmail').focus(); return; }
      B.resetPassword(email).then(() => msg('Te hemos enviado un correo para cambiar la contraseña.', true)).catch(err => msg(authMsg(err)));
    });
    // Al volver de Google (redirección en el móvil): el botón en "cargando" hasta que la sesión está lista.
    let backFromGoogle = false;
    try { backFromGoogle = sessionStorage.getItem('bydlink_google') === '1'; sessionStorage.removeItem('bydlink_google'); } catch (e) {}
    if (backFromGoogle) busyBtn($('#googleBtn'), true);
    if (B.redirectResult) B.redirectResult().catch(err => msg(authMsg(err))).then(() => { if (backFromGoogle) busyBtn($('#googleBtn'), false); });
  }
  function authMsg(e) {
    const c = (e && e.code) || '';
    if (/wrong-password|invalid-credential|invalid-login/.test(c)) return 'Correo o contraseña incorrectos.';
    if (/user-not-found/.test(c)) return 'No hay ninguna cuenta con ese correo.';
    if (/email-already-in-use/.test(c)) return 'Ese correo ya tiene cuenta. Pulsa «Entrar».';
    if (/weak-password/.test(c)) return 'Contraseña demasiado corta (mínimo 6 caracteres).';
    if (/invalid-email/.test(c)) return 'Ese correo no es válido.';
    if (/too-many-requests/.test(c)) return 'Demasiados intentos. Espera unos minutos y vuelve a probar.';
    if (/network-request-failed/.test(c)) return 'Sin conexión a internet.';
    if (/popup-closed|cancelled-popup/.test(c)) return '';
    if (/unauthorized-domain/.test(c)) return 'El acceso con Google no está habilitado en esta dirección.';
    if (/operation-not-allowed/.test(c)) return 'Ese método de acceso no está habilitado todavía.';
    if (/user-disabled/.test(c)) return 'Esta cuenta está desactivada.';
    return (e && e.message) || 'Algo ha fallado. Inténtalo de nuevo.';
  }

  // ───────── vincular ─────────
  // El QR de la pantalla del coche abre esta web con ?code=XXXXXX. El código se guarda en la
  // pestaña mientras la persona inicia sesión (también tras volver de Google) y se vincula solo.
  const normCode = v => { v = String(v || '').toUpperCase().replace(/[^A-Z0-9]/g, ''); return v.length === 6 ? v : ''; };
  const Pending = {
    code: '',
    init() {
      const q = normCode(new URLSearchParams(location.search).get('code'));
      try { if (q) sessionStorage.setItem('bydlink_pair', q); this.code = q || normCode(sessionStorage.getItem('bydlink_pair')); }
      catch (e) { this.code = q; }
    },
    clear() {
      this.code = '';
      try { sessionStorage.removeItem('bydlink_pair'); } catch (e) {}
      try { history.replaceState(null, '', location.pathname + searchWithout('code') + location.hash); } catch (e) {}
    }
  };
  function searchWithout(key) {
    const p = new URLSearchParams(location.search); p.delete(key);
    const q = p.toString(); return q ? '?' + q : '';
  }
  // Acepta el enlace del QR (…?code=ABC123) o el código a secas.
  function codeFromQr(txt) {
    txt = String(txt || '').trim();
    try { const u = new URL(txt); if (/^https?:$/.test(u.protocol)) return normCode(u.searchParams.get('code')); } catch (e) {}
    return /^[A-Za-z0-9]{6}$/.test(txt) ? txt.toUpperCase() : '';
  }

  function showPair(fromApp) {
    show('Pair');
    $('#pairBack').hidden = !fromApp || !st.cars.length;
    $('#pairAcct').textContent = st.user ? 'Sesión iniciada como ' + (st.user.email || st.user.displayName || '') : '';
    $('#pairMsg').textContent = '';
    $('#pairSubmit').disabled = $('#pairCode').value.length !== 6;
  }
  function pairError(err) {
    if (/PERMISSION_DENIED|permission/i.test(String(err && (err.code || err.message))))
      return 'Ese código no sirve para vincular. Usa el que aparece ahora en la pantalla del coche.';
    if (/network|offline|disconnect/i.test(String(err && (err.code || err.message)))) return 'Sin conexión a internet. Vuelve a probar.';
    return (err && err.message) || 'No se pudo vincular.';
  }
  function doPair(code) {
    const btn = $('#pairSubmit'), m = $('#pairMsg');
    $('#pairCode').value = code;
    btn.classList.add('loading'); btn.disabled = true;
    m.className = 'form-msg'; m.textContent = '';
    return B.pair(code).then(id => {
      const had = st.cars.some(c => c.id === id);
      Pending.clear();
      B.saveCar(id);
      m.className = 'form-msg ok'; m.textContent = had ? 'Este coche ya estaba en tu cuenta.' : '¡Coche vinculado!';
      setTimeout(() => {
        try { history.replaceState(null, '', location.pathname + searchWithout('code') + '#home'); } catch (e) {}
        if (st.started) { location.reload(); return; }   // ya había otro coche abierto
        B.listCars().then(cars => { st.cars = cars; startCar(id); }, () => location.reload());
        btn.classList.remove('loading');
      }, 700);
    }).catch(err => {
      Pending.clear();
      m.className = 'form-msg'; m.textContent = pairError(err);
      btn.classList.remove('loading'); btn.disabled = $('#pairCode').value.length !== 6;
    });
  }
  function setupPair() {
    const inp = $('#pairCode');
    inp.addEventListener('input', () => {
      const v = inp.value.toUpperCase().replace(/[^A-Z0-9]/g, '').slice(0, 6);
      if (v !== inp.value) inp.value = v;
      $('#pairSubmit').disabled = v.length !== 6;
      $('#pairMsg').textContent = '';
    });
    $('#pairForm').addEventListener('submit', e => { e.preventDefault(); if (inp.value.length === 6) doPair(inp.value); });
    $('#scanBtn').addEventListener('click', () => Scan.open());
    $('#pairBack').addEventListener('click', () => { show('App'); });
    $('#pairLogout').addEventListener('click', () => B.logout().then(() => location.reload()));
  }

  // ───────── lector de QR ─────────
  // BarcodeDetector donde exista (Android); si no (iPhone, ordenador), jsQR bajo demanda.
  const JSQR = { src: 'https://cdn.jsdelivr.net/npm/jsqr@1.4.0/dist/jsQR.js',
    sri: 'sha384-b5Ya4Bq3qCyz39m2ISh+4DxjAIljdeFwK/BsXLuj9gugaNwAcj/ia15fxNZL9Nlx' };
  const Scan = {
    stream: null, timer: 0, det: null, cv: null, lib: null,
    open() {
      if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia)
        return toast('Este navegador no deja usar la cámara. Escribe el código.', 'err');
      $('#scanner').hidden = false;
      $('#scanMsg').textContent = 'Apunta al código QR de la pantalla del coche';
      const ready = this.decoder();
      navigator.mediaDevices.getUserMedia({ video: { facingMode: { ideal: 'environment' } }, audio: false })
        .then(s => {
          if ($('#scanner').hidden) { s.getTracks().forEach(t => t.stop()); return; }
          this.stream = s;
          const v = $('#scanVideo'); v.srcObject = s;
          return Promise.all([v.play(), ready]).then(() => this.tick());
        })
        .catch(err => {
          this.close();
          const n = err && err.name;
          toast(n === 'NotAllowedError' ? 'Sin permiso para la cámara. Permítelo en el navegador o escribe el código.'
            : n === 'NotFoundError' ? 'No se ha encontrado ninguna cámara. Escribe el código.'
            : (err && err.message) || 'No se pudo abrir la cámara.', 'err');
        });
    },
    decoder() {
      if (this.det || window.jsQR) return Promise.resolve();
      const fallback = () => this.lib || (this.lib = new Promise((ok, ko) => {
        const s = document.createElement('script');
        s.src = JSQR.src; s.integrity = JSQR.sri; s.crossOrigin = 'anonymous';
        s.onload = ok; s.onerror = () => { this.lib = null; ko(new Error('No se pudo cargar el lector de QR. Revisa tu conexión.')); };
        document.head.appendChild(s);
      }));
      if (!('BarcodeDetector' in window)) return fallback();
      return BarcodeDetector.getSupportedFormats().then(f => {
        if (f.indexOf('qr_code') >= 0) this.det = new BarcodeDetector({ formats: ['qr_code'] });
        else return fallback();
      }, fallback);
    },
    read() {
      const v = $('#scanVideo');
      if (v.readyState < 2 || !v.videoWidth) return Promise.resolve(null);
      if (this.det) return this.det.detect(v).then(r => (r[0] && r[0].rawValue) || null, () => null);
      const k = Math.min(1, 720 / Math.max(v.videoWidth, v.videoHeight));
      const w = Math.round(v.videoWidth * k), h = Math.round(v.videoHeight * k);
      const cv = this.cv || (this.cv = document.createElement('canvas'));
      cv.width = w; cv.height = h;
      const ctx = cv.getContext('2d', { willReadFrequently: true });
      ctx.drawImage(v, 0, 0, w, h);
      const r = window.jsQR(ctx.getImageData(0, 0, w, h).data, w, h, { inversionAttempts: 'attemptBoth' });
      return Promise.resolve(r ? r.data : null);
    },
    tick() {
      if (!this.stream) return;
      this.read().then(txt => {
        if (!this.stream) return;
        const code = txt && codeFromQr(txt);
        if (code) {
          if (navigator.vibrate) try { navigator.vibrate(40); } catch (e) {}
          this.close();
          doPair(code);
          return;
        }
        if (txt) $('#scanMsg').textContent = 'Ese QR no es de BYDLink. Apunta al de la pantalla del coche.';
        this.timer = setTimeout(() => this.tick(), 150);
      });
    },
    close() {
      clearTimeout(this.timer);
      if (this.stream) this.stream.getTracks().forEach(t => t.stop());
      this.stream = null;
      $('#scanVideo').srcObject = null;
      $('#scanner').hidden = true;
    }
  };
  $('#scanClose').addEventListener('click', () => Scan.close());
  document.addEventListener('keydown', e => { if (e.key === 'Escape' && !$('#scanner').hidden) Scan.close(); });
  document.addEventListener('visibilitychange', () => { if (document.hidden && Scan.stream) Scan.close(); });

  // ───────── arranque ─────────
  function enter() {
    show('Loading');
    B.listCars().then(cars => {
      st.cars = cars;
      if (Pending.code && B.mode === 'firebase') { showPair(false); return doPair(Pending.code); }   // viene del QR del coche
      if (!cars.length) return showPair(false);
      let id = B.savedCar();
      if (!cars.some(c => c.id === id)) id = cars[0].id;
      startCar(id);
    }).catch(err => {
      console.error(err);
      show('Pair'); $('#pairMsg').textContent = 'No se pudieron cargar tus coches. Revisa tu conexión y recarga.';
    });
  }

  function startCar(id) {
    st.carId = id;
    B.useCar(id); B.saveCar(id);
    try { const c = JSON.parse(lsGet('bydlink_climate_' + id) || 'null'); if (c) st.climate = c; } catch (e) {}
    show('App');
    $('#bnDemo').hidden = B.mode !== 'demo';
    $('#acctCard').hidden = !st.user;
    $('#acctEmail').textContent = st.user ? (st.user.email || st.user.displayName || '—') : '—';
    renderMeta(); renderCars(); renderClimate(); renderSeats(); renderConn();
    renderTrips([]); renderEvents([]); renderSessions([]);
    go((location.hash || '#home').slice(1));
    if (st.started) return;
    st.started = true;
    B.watch({
      meta: v => { st.meta = v || {}; renderMeta(); renderConn(); },
      status: v => {
        st.rxCount++;
        if (st.rxCount > 1) st.lastRx = Date.now();
        if (v) { st.raw = v; st.s = clean(v); renderStatus(); hooks.forEach(f => { try { f(v); } catch (e) { console.error(e); } }); }
        renderConn();
      },
      config: v => { st.config = v || null; renderConfig(); },
      seats: v => { if (v) { st.seats = v; renderSeats(); } },
      caps: v => { st.caps = v || null; applyCaps(); },
      capsOverride: v => { st.capsOverride = v || null; applyCaps(); },
      trips: renderTrips,
      sentry: renderEvents,
      charging: renderSessions,
      net: on => { st.net = on; renderConn(); },
      error: e => {
        if (e && /permission/i.test(e.code || e.message || '')) { st.denied = true; renderConn(); }
      }
    });
    setInterval(renderConn, 5000);
  }

  $$('[data-ver]').forEach(e => { e.textContent = WEB_VERSION; });
  setupAuth();
  setupPair();
  Pending.init();
  if (B.requiresAuth) {
    B.onAuth(user => {
      if (user && user.isAnonymous) { B.logout(); return; }
      if (!user) {
        if (st.started) { location.reload(); return; }
        st.user = null;
        $('#authPair').hidden = !Pending.code;
        show('Auth'); return;
      }
      if (st.user && st.user.uid === user.uid) return;
      st.user = user;
      enter();
    });
  } else {
    enter();
  }

  // Service worker: si llega una versión nueva, recargar una vez para usarla.
  if ('serviceWorker' in navigator && location.protocol.indexOf('http') === 0) {
    const hadController = !!navigator.serviceWorker.controller;
    let reloaded = false;
    navigator.serviceWorker.addEventListener('controllerchange', () => {
      if (hadController && !reloaded) { reloaded = true; location.reload(); }
    });
    navigator.serviceWorker.register('sw.js').catch(() => {});
  }
})();
