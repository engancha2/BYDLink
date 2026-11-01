/* BYDLink · historial de viajes y cargas con gastos.
 * Lee lo que guarda el coche (trips, chargingSessions, tripRoutes) y le aplica los precios que se ponen aquí
 * (cars/{id}/historyCfg): casa por horas 2.0TD o precio único, fuera de casa, carga rápida, lugares (Casa,
 * Trabajo gratis…) y comparación con gasolina. Las correcciones a mano van en cars/{id}/historyEdits.
 * La cuenta del coste es la misma que car-app data/Tariff.kt: si se cambia una, hay que cambiar la otra.
 * Contrato en protocol/historial.md. Funciona con los datos viejos (sin kWh por viaje se estima). */
(function () {
  'use strict';
  const A = window.BYDApp;
  if (!A || !A.B.historyRange) return;
  const { B, toast, Sheet, icon, esc, fmt } = A;
  const $ = (s, r) => (r || document).querySelector(s);
  const $$ = (s, r) => Array.from((r || document).querySelectorAll(s));
  const DAY = 864e5;
  const f1 = v => fmt(v, 1), f2 = v => fmt(v, 2), eur = v => fmt(v, 2) + ' €';
  const numIn = v => { const n = parseFloat(String(v == null ? '' : v).replace(',', '.')); return isFinite(n) && n >= 0 ? n : null; };

  // ───────── precios y coste (igual que data/Tariff.kt) ─────────
  const DEF = { home: { mode: 'periods', flat: 0.16, p1: 0.25, p2: 0.17, p3: 0.11 }, awayAc: 0.35, dc: 0.49, unknownAc: 'home', gas: { on: true, eurL: 1.55, l100: 6 } };
  // Festivos nacionales de fecha fija: en la 2.0TD todo el día es valle, como los fines de semana.
  const HOLI = ['0101', '0106', '0501', '0815', '1012', '1101', '1206', '1208', '1225'];
  const pos = (v, d) => (typeof v === 'number' && isFinite(v) && v >= 0) ? v : d;
  function cfgOf(r) {
    r = r || {};
    const h = r.home || {}, g = r.gas || {};
    const places = Array.isArray(r.places) ? r.places : Object.values(r.places || {});
    return {
      home: { mode: h.mode === 'flat' ? 'flat' : 'periods', flat: pos(h.flat, DEF.home.flat), p1: pos(h.p1, DEF.home.p1), p2: pos(h.p2, DEF.home.p2), p3: pos(h.p3, DEF.home.p3) },
      awayAc: pos(r.awayAc, DEF.awayAc), dc: pos(r.dc, DEF.dc), unknownAc: r.unknownAc === 'away' ? 'away' : 'home',
      places: places.filter(p => p && typeof p.lat === 'number' && typeof p.lng === 'number')
        .map(p => ({ name: String(p.name || 'Lugar'), lat: p.lat, lng: p.lng, r: pos(p.r, 150), price: p.price === 'home' ? 'home' : pos(p.price, 0) })),
      gas: { on: g.on !== false, eurL: pos(g.eurL, DEF.gas.eurL), l100: pos(g.l100, DEF.gas.l100) }
    };
  }
  const p2 = v => String(v).padStart(2, '0');
  const keyOf = t => { const d = new Date(t); return d.getFullYear() + p2(d.getMonth() + 1) + p2(d.getDate()) + p2(d.getHours()); };
  /** Periodo 2.0TD de una hora local "aaaammddhh": 1 punta, 2 llano, 3 valle. */
  function period(key) {
    const dow = new Date(+key.slice(0, 4), +key.slice(4, 6) - 1, +key.slice(6, 8)).getDay(), h = +key.slice(8, 10);
    if (dow === 0 || dow === 6 || HOLI.indexOf(key.slice(4, 8)) >= 0) return 3;
    return h < 8 ? 3 : (h >= 10 && h < 14) || (h >= 18 && h < 22) ? 1 : 2;
  }
  const homePrice = key => H.cfg.home.mode === 'flat' ? H.cfg.home.flat : H.cfg.home['p' + period(key)];
  function distM(a, b, c, d) {
    const r = Math.PI / 180, x = Math.sin((c - a) * r / 2), y = Math.sin((d - b) * r / 2);
    const h = x * x + Math.cos(a * r) * Math.cos(c * r) * y * y;
    return 12742e3 * Math.atan2(Math.sqrt(h), Math.sqrt(1 - h));
  }
  function placeOf(lat, lng) {
    if (lat == null || lng == null) return null;
    let best = null, bd = Infinity;
    H.cfg.places.forEach(p => { const x = distM(lat, lng, p.lat, p.lng); if (x <= p.r && x < bd) { bd = x; best = p; } });
    return best;
  }
  /** "home", "place", "away", "dc" o "free". */
  function kindOf(c, e) {
    if (e && e.kind) return e.kind;
    if (c.type === 'DC') return 'dc';
    const p = placeOf(c.lat, c.lng);
    if (p) return p.price === 'home' ? 'home' : 'place';
    const hasHome = H.cfg.places.some(x => x.price === 'home');
    return (c.lat == null || !hasHome) ? H.cfg.unknownAc : 'away';
  }
  /** kWh por hora local: los que mandó el coche o, si no, repartidos por igual entre el inicio y el final. */
  function hours(c) {
    const kwh = pos(c.kwh, 0), h = c.kwhH, o = {};
    if (h && typeof h === 'object' && Object.keys(h).length) {
      const sum = Object.keys(h).reduce((a, k) => a + pos(h[k], 0), 0);
      Object.keys(h).forEach(k => { o[k] = sum > 0 && kwh > 0 ? pos(h[k], 0) * kwh / sum : pos(h[k], 0); });
      return o;
    }
    if (!c.start) return o;
    const end = Math.max(c.end || (c.start + (c.durationMin || 60) * 6e4), c.start + 6e4), tot = end - c.start;
    const d = new Date(c.start); d.setMinutes(0, 0, 0);
    for (let i = 0; d.getTime() < end && i < 336; i++) {
      const x = d.getTime(); d.setHours(d.getHours() + 1);
      const a = Math.max(x, c.start), b = Math.min(d.getTime(), end);
      if (b > a) o[keyOf(x)] = kwh * (b - a) / tot;
    }
    return o;
  }
  function chargeCost(c, e) {
    if (e && typeof e.costEur === 'number') return e.costEur;
    if (!(e && e.kind) && typeof c.costEur === 'number') return c.costEur;
    const kwh = pos(c.kwh, 0), k = kindOf(c, e);
    if (k === 'free') return 0;
    if (k === 'dc') return kwh * H.cfg.dc;
    if (k === 'away') return kwh * H.cfg.awayAc;
    if (k === 'place') { const p = placeOf(c.lat, c.lng); return kwh * (p && p.price !== 'home' ? p.price : H.cfg.awayAc); }
    const h = hours(c);
    return Object.keys(h).reduce((a, key) => a + h[key] * homePrice(key), 0);
  }
  const KIND = { home: 'Casa', away: 'Fuera de casa', dc: 'Carga rápida', free: 'Gratis', place: 'Lugar' };
  function chargeName(c, e) {
    const k = kindOf(c, e), p = placeOf(c.lat, c.lng);
    if (k === 'place' && p) return p.name;
    if (k === 'home') return p && p.price === 'home' ? p.name : 'Casa';
    if (k === 'dc') return p ? p.name + ' · rápida' : 'Carga rápida';
    if (k === 'free') return p ? p.name + ' · gratis' : 'Gratis';
    return (c.lat != null && Geo.get(c.lat, c.lng)) || KIND.away;
  }

  // ───────── nombres de los sitios (lugares propios o calle, con caché) ─────────
  const Geo = {
    mem: null, q: [], busy: false, onUpdate: null,
    load() { if (!this.mem) { try { this.mem = JSON.parse(localStorage.getItem('bydlink_geo2') || '{}'); } catch (e) { this.mem = {}; } } return this.mem; },
    get(lat, lng) {
      const k = lat.toFixed(3) + ',' + lng.toFixed(3), m = this.load();
      if (k in m) return m[k] || null;
      if (this.q.indexOf(k) < 0 && this.q.length < 40) { this.q.push(k); this.pump(); }
      return null;
    },
    pump() {
      if (this.busy || !this.q.length) return;
      this.busy = true;
      const k = this.q.shift(), [lat, lng] = k.split(',');
      fetch('https://nominatim.openstreetmap.org/reverse?format=jsonv2&zoom=17&accept-language=es&lat=' + lat + '&lon=' + lng)
        .then(r => r.json()).then(j => {
          const a = (j && j.address) || {};
          const name = a.road || a.pedestrian || a.neighbourhood || a.suburb || '';
          const town = a.city || a.town || a.village || a.municipality || '';
          const m = this.load(); m[k] = name && town && name !== town ? name + ', ' + town : (name || town || '');
          const ks = Object.keys(m); if (ks.length > 400) delete m[ks[0]];
          try { localStorage.setItem('bydlink_geo2', JSON.stringify(m)); } catch (e) {}
          if (this.onUpdate) this.onUpdate();
        }).catch(() => {})
        .then(() => setTimeout(() => { this.busy = false; this.pump(); }, 1100));   // como pide Nominatim: 1 por segundo
    }
  };
  // Nombre de un sitio: el lugar propio (Casa…) o la calle; en las listas, solo la calle (sin el pueblo).
  const spot = (lat, lng, short) => {
    if (lat == null || lng == null) return null;
    const p = placeOf(lat, lng); if (p) return p.name;
    const g = Geo.get(lat, lng);
    return g && short ? g.split(', ')[0] : g;
  };
  function tripTitle(t, full) {
    const a = spot(t.startLat, t.startLng, !full), b = spot(t.endLat, t.endLng, !full);
    if (t.startLat != null || t.endLat != null) return (a || 'Otro sitio') + ' → ' + (t.live ? 'en curso' : (b || 'Otro sitio'));
    if (t.startAddr || t.endAddr) return (t.startAddr || 'Inicio') + ' → ' + (t.endAddr || 'Fin');
    return t.live ? 'Viaje en curso' : 'Viaje';
  }

  // ───────── estado y datos ─────────
  const H = { span: 'month', anchor: Date.now(), tab: 'trips', metric: 'km', cfg: cfgOf(null), raw: null, edits: { trips: {}, charges: {} },
    cache: {}, cur: null, sum: null, shown: 40, sel: -1, map: null };
  const edit = (k, id) => (H.edits[k] || {})[id];

  function range(span, anchor) {
    const d = new Date(anchor); d.setHours(0, 0, 0, 0);
    if (span === 'week') d.setDate(d.getDate() - (d.getDay() + 6) % 7);
    else if (span === 'month') d.setDate(1);
    else d.setMonth(0, 1);
    const e = new Date(d);
    if (span === 'week') e.setDate(e.getDate() + 7); else if (span === 'month') e.setMonth(e.getMonth() + 1); else e.setFullYear(e.getFullYear() + 1);
    return { from: d.getTime(), to: e.getTime() };
  }
  function load(span, anchor, fresh) {
    const r = range(span, anchor), key = span + ':' + r.from, c = H.cache[key];
    if (c && !fresh && Date.now() - c.at < 60e3) return Promise.resolve(c);
    // Las cargas de los últimos 90 días sirven para saber cuánto te cuesta el kWh si en el periodo hay pocas.
    const cFrom = Math.min(r.from, r.to - 90 * DAY);
    return Promise.all([B.historyRange('trips', r.from, r.to - 1), B.historyRange('charges', cFrom, r.to - 1)]).then(([t, ch]) => {
      const v = { at: Date.now(), r, trips: (t || []).sort((a, b) => b.start - a.start), charges: (ch || []).sort((a, b) => b.start - a.start) };
      H.cache[key] = v;
      return v;
    });
  }
  const statusAvg = () => { const s = A.st.raw || {}; const v = s.consumptionKwh100; return typeof v === 'number' && v >= 5 && v <= 40 ? v : 14; };
  function summarize(v) {
    const trips = v.trips.filter(t => !(edit('trips', t.id) || {}).skip);
    const all = v.charges.filter(c => !(edit('charges', c.id) || {}).skip);
    all.forEach(c => { c._eur = chargeCost(c, edit('charges', c.id)); c._kind = kindOf(c, edit('charges', c.id)); });
    const charges = all.filter(c => c.start >= v.r.from);
    const priceOf = l => { const k = l.reduce((a, c) => a + pos(c.kwh, 0), 0); return k >= 5 ? l.reduce((a, c) => a + c._eur, 0) / k : null; };
    const eurKwh = priceOf(charges) || priceOf(all) || (H.cfg.home.mode === 'flat' ? H.cfg.home.flat : H.cfg.home.p3);
    const known = trips.filter(t => typeof t.kwh === 'number' && t.distanceKm > 0);
    const kKm = known.reduce((a, t) => a + t.distanceKm, 0), kKwh = known.reduce((a, t) => a + t.kwh, 0);
    const avgK = kKm >= 20 ? kKwh / kKm * 100 : statusAvg();
    trips.forEach(t => {
      t._est = typeof t.kwh !== 'number';
      t._kwh = t._est ? pos(t.distanceKm, 0) * avgK / 100 : t.kwh;
      t._eur = t._kwh * eurKwh;
      t._gas = H.cfg.gas.on ? pos(t.distanceKm, 0) * H.cfg.gas.l100 / 100 * H.cfg.gas.eurL : null;
    });
    const km = trips.reduce((a, t) => a + pos(t.distanceKm, 0), 0), used = trips.reduce((a, t) => a + t._kwh, 0);
    const tripsEur = used * eurKwh, gasEur = H.cfg.gas.on ? km * H.cfg.gas.l100 / 100 * H.cfg.gas.eurL : null;
    return {
      trips, charges, km, used, eurKwh, tripsEur, gasEur,
      chKwh: charges.reduce((a, c) => a + pos(c.kwh, 0), 0), chEur: charges.reduce((a, c) => a + c._eur, 0),
      avg: km >= 1 ? used / km * 100 : null, per100: km >= 1 ? tripsEur / km * 100 : null,
      saved: gasEur != null && km >= 1 ? gasEur - tripsEur : null, estimated: trips.some(t => t._est)
    };
  }

  // ───────── vista ─────────
  document.head.insertAdjacentHTML('beforeend', '<link rel="stylesheet" href="history.css">');
  $('#views').insertAdjacentHTML('beforeend',
    '<section class="view" data-view="history" hidden>' +
    '<button type="button" class="back" data-goto="map">' + icon('left') + 'Mapa</button>' +
    '<div class="seg" id="hsSpan"><button type="button" data-span="week">Semana</button><button type="button" data-span="month">Mes</button><button type="button" data-span="year">Año</button></div>' +
    '<div class="hs-nav"><button type="button" class="icon-btn" id="hsPrev" aria-label="Anterior">' + icon('left') + '</button>' +
    '<button type="button" id="hsTitle" class="hs-title">—</button><button type="button" class="icon-btn" id="hsNext" aria-label="Siguiente">' + icon('right') + '</button></div>' +
    '<div class="hs-kpis" id="hsKpis"></div>' +
    '<div class="card"><div class="hs-head"><h2 class="card-h">Por ' + '<span id="hsPer">día</span></h2>' +
    '<div class="seg hs-mini-seg" id="hsMetric"><button type="button" data-m="km">km</button><button type="button" data-m="kwh">kWh</button><button type="button" data-m="eur">€</button></div></div>' +
    '<div id="hsChart" class="hs-chart"></div><div id="hsAxis" class="hs-axis"></div><div id="hsLegend" class="legend"></div>' +
    '<p class="hint" id="hsChartHint">Toca una barra para ver el detalle.</p></div>' +
    '<div class="card"><div class="seg" id="hsTab"><button type="button" data-tab="trips">Viajes</button><button type="button" data-tab="charges">Cargas</button></div>' +
    '<div id="hsList" class="list"></div><button type="button" class="btn btn-soft btn-block" id="hsMore" hidden>Ver más</button></div>' +
    '<div class="hs-actions"><button type="button" class="btn btn-soft" id="hsPrices">' + icon('sliders') + 'Precios y lugares</button>' +
    '<button type="button" class="btn btn-soft" id="hsCsv">' + icon('download') + 'Exportar</button></div>' +
    '<p class="hint center" id="hsNote"></p></section>');
  const view = $('[data-view="history"]');

  // Accesos: tarjeta del mes en Inicio, botón en la lista de viajes (Mapa) y en Carga.
  const loc = $('[data-view="home"] .loc-card');
  if (loc) loc.insertAdjacentHTML('afterend',
    '<button type="button" class="card hs-month" data-goto="history" id="hsMonth"><span class="hs-month-txt"><span class="card-h" id="hsMonthH">Este mes</span>' +
    '<b id="hsMonthMain">—</b><small id="hsMonthSub">Viajes, cargas y lo que te cuestan</small></span>' + icon('right') + '</button>');
  const tripCard = $('#tripList') && $('#tripList').closest('.card');
  if (tripCard) tripCard.insertAdjacentHTML('beforeend', '<button type="button" class="btn btn-soft btn-block" data-goto="history">' + icon('route') + 'Historial y gastos</button>');
  const chgCard = $('#chgList') && $('#chgList').closest('.card');
  if (chgCard) chgCard.insertAdjacentHTML('beforeend', '<button type="button" class="btn btn-soft btn-block" data-goto="history" data-hs-tab="charges">' + icon('bolt') + 'Historial completo y gastos</button>');

  document.addEventListener('click', e => {
    const g = e.target.closest('[data-goto="history"]'); if (!g) return;
    if (g.dataset.hsTab) H.tab = g.dataset.hsTab;
    // app.js ya ha cambiado de vista; la pestaña que se marca es Mapa
    $$('.tab').forEach(t => t.classList.toggle('active', t.dataset.goto === 'map'));
  });
  new MutationObserver(() => { if (!view.hidden) open(); }).observe(view, { attributes: true, attributeFilter: ['hidden'] });
  // Si se abrió la app directamente en #history (recargar estando aquí), app.js aún no conocía esta vista.
  const nav = performance.getEntriesByType && performance.getEntriesByType('navigation')[0];
  if (nav && /#history$/.test(nav.name) && view.hidden) setTimeout(() => { const t = document.createElement('a'); t.dataset.goto = 'history'; document.body.appendChild(t); t.click(); t.remove(); }, 0);
  new MutationObserver(() => { if ($('#sheet').hidden && H.map) { H.map.remove(); H.map = null; } }).observe($('#sheet'), { attributes: true, attributeFilter: ['hidden'] });

  function open() {
    $$('.tab').forEach(t => t.classList.toggle('active', t.dataset.goto === 'map'));
    render(); refresh();
  }
  function refresh(fresh) {
    const span = H.span, anchor = H.anchor;
    $('#hsList').innerHTML = H.cur ? $('#hsList').innerHTML : '<div class="empty"><div class="spinner sm"></div>Cargando el historial…</div>';
    load(span, anchor, fresh).then(v => {
      if (span !== H.span || anchor !== H.anchor) return;
      H.cur = v; H.sel = -1; render();
    }).catch(e => {
      console.error(e);
      $('#hsList').innerHTML = '<div class="empty">' + icon('alert') + '<b>No se pudo cargar el historial</b>Revisa la conexión y vuelve a intentarlo.</div>';
    });
  }

  const MONTHS = ['enero', 'febrero', 'marzo', 'abril', 'mayo', 'junio', 'julio', 'agosto', 'septiembre', 'octubre', 'noviembre', 'diciembre'];
  function title(r) {
    const a = new Date(r.from), b = new Date(r.to - 1);
    if (H.span === 'year') return String(a.getFullYear());
    if (H.span === 'month') return MONTHS[a.getMonth()] + ' de ' + a.getFullYear();
    const m = d => d.toLocaleDateString('es-ES', { month: 'short' }).replace('.', '');
    if (a.getFullYear() !== b.getFullYear()) return a.getDate() + ' ' + m(a) + ' ' + a.getFullYear() + ' – ' + b.getDate() + ' ' + m(b) + ' ' + b.getFullYear();
    return a.getDate() + (a.getMonth() !== b.getMonth() ? ' ' + m(a) : '') + ' – ' + b.getDate() + ' ' + m(b) + ' ' + b.getFullYear();
  }

  function render() {
    $$('#hsSpan button').forEach(b => b.classList.toggle('active', b.dataset.span === H.span));
    $$('#hsTab button').forEach(b => b.classList.toggle('active', b.dataset.tab === H.tab));
    $$('#hsMetric button').forEach(b => b.classList.toggle('active', b.dataset.m === H.metric));
    const r = range(H.span, H.anchor);
    $('#hsTitle').textContent = title(r);
    const now = r.from <= Date.now() && Date.now() < r.to;
    $('#hsTitle').classList.toggle('past', !now);
    $('#hsTitle').title = now ? '' : 'Volver a hoy';
    $('#hsNext').disabled = r.to > Date.now();
    $('#hsPer').textContent = H.span === 'year' ? 'mes' : 'día';
    const v = H.cur && H.cur.r.from === r.from && H.span === spanOf(H.cur) ? H.cur : null;
    if (!v) { $('#hsKpis').innerHTML = kpis(null); return; }
    const s = H.sum = summarize(v);
    $('#hsKpis').innerHTML = kpis(s);
    chart(s, r);
    list(s);
    $('#hsTab [data-tab="trips"]').textContent = 'Viajes · ' + s.trips.length;
    $('#hsTab [data-tab="charges"]').textContent = 'Cargas · ' + s.charges.length;
    $('#hsNote').textContent = (A.carHas('2.27') ? '' : 'Con la app del coche 2.27 o más nueva cada viaje guarda su consumo y su ruta, y cada carga el coste por horas. ') +
      (s.estimated ? 'Los viajes sin dato de energía se calculan con tu consumo medio. ' : '') +
      'Coste de los viajes: la energía gastada a ' + fmt(s.eurKwh, 3) + ' €/kWh, lo que te cuesta de media cargar.';
  }
  const spanOf = v => { const d = (v.r.to - v.r.from) / DAY; return d <= 7.1 ? 'week' : d <= 31.1 ? 'month' : 'year'; };

  function kpis(s) {
    const k = (val, lab, sub, cls) => '<div class="card kpi' + (cls ? ' ' + cls : '') + '"><b>' + val + '</b><span>' + lab + '</span>' + (sub ? '<small>' + sub + '</small>' : '') + '</div>';
    if (!s) return k('--', 'kilómetros') + k('--', 'en cargas') + k('--', 'kWh/100 km') + k('--', '€ / 100 km') + k('--', 'kWh gastados') + k('--', 'ahorrado');
    return k(fmt(s.km, s.km > 0 && s.km < 100 ? 1 : 0), 'kilómetros', s.trips.length + (s.trips.length === 1 ? ' viaje' : ' viajes')) +
      k(eur(s.chEur), 'en cargas', f1(s.chKwh) + ' kWh') +
      k(s.avg != null ? f1(s.avg) : '--', 'kWh/100 km', 'consumo medio') +
      k(s.per100 != null ? f2(s.per100) + ' €' : '--', 'cada 100 km', fmt(s.eurKwh, 3) + ' €/kWh') +
      k(f1(s.used), 'kWh gastados', eur(s.tripsEur) + ' en viajes') +
      (s.saved != null ? k(eur(s.saved), 'ahorrado', 'gasolina ' + eur(s.gasEur), 'good') : k(String(s.charges.length), 'cargas', ''));
  }

  // ───────── gráfica ─────────
  function buckets(s, r) {
    const out = [];
    if (H.span === 'year') {
      const y = new Date(r.from).getFullYear();
      for (let m = 0; m < 12; m++) out.push({ from: new Date(y, m, 1).getTime(), to: new Date(y, m + 1, 1).getTime(), label: 'EFMAMJJASOND'[m], title: MONTHS[m] + ' de ' + y });
    } else {
      const d = new Date(r.from);
      while (d.getTime() < r.to) {
        const from = d.getTime(), day = d.getDate(), wd = (d.getDay() + 6) % 7;
        const t = d.toLocaleDateString('es-ES', { weekday: 'long', day: 'numeric', month: 'long' });
        d.setDate(d.getDate() + 1);
        out.push({ from, to: d.getTime(), label: H.span === 'week' ? 'LMXJVSD'[wd] : ([1, 5, 10, 15, 20, 25, 30].indexOf(day) >= 0 ? String(day) : ''), title: t });
      }
    }
    out.forEach(b => {
      const tr = s.trips.filter(t => t.start >= b.from && t.start < b.to), ch = s.charges.filter(c => c.start >= b.from && c.start < b.to);
      b.km = tr.reduce((a, t) => a + pos(t.distanceKm, 0), 0); b.n = tr.length; b.used = tr.reduce((a, t) => a + t._kwh, 0);
      b.kwh = ch.reduce((a, c) => a + pos(c.kwh, 0), 0); b.eur = ch.reduce((a, c) => a + c._eur, 0);
      b.home = ch.filter(c => c._kind === 'home').reduce((a, c) => a + pos(c.kwh, 0), 0);
      b.dc = ch.filter(c => c._kind === 'dc').reduce((a, c) => a + pos(c.kwh, 0), 0);
      b.other = b.kwh - b.home - b.dc;
    });
    return out;
  }
  function chart(s, r) {
    const bs = H.bs = buckets(s, r), m = H.metric;
    const val = b => m === 'km' ? b.km : m === 'kwh' ? b.kwh : b.eur;
    const max = Math.max.apply(null, bs.map(val)) || 1;
    const pct = v => (v / max * 100).toFixed(1) + '%';
    $('#hsChart').style.setProperty('--n', bs.length);
    $('#hsChart').innerHTML = bs.map((b, i) => {
      const inner = m === 'kwh'
        ? '<i class="dc" style="height:' + pct(b.dc) + '"></i><i class="other" style="height:' + pct(b.other) + '"></i><i style="height:' + pct(b.home) + '"></i>'
        : '<i class="' + (m === 'km' ? 'km' : '') + '" style="height:' + pct(val(b)) + '"></i>';
      return '<button type="button" class="hs-bar' + (i === H.sel ? ' sel' : '') + '" data-i="' + i + '" aria-label="' + esc(b.title) + '">' + inner + '</button>';
    }).join('') + '<span class="hs-max">' + (m === 'eur' ? eur(max) : f1(max) + (m === 'km' ? ' km' : ' kWh')) + '</span>';
    $('#hsAxis').style.setProperty('--n', bs.length);
    $('#hsAxis').innerHTML = bs.map(b => '<span>' + b.label + '</span>').join('');
    $('#hsLegend').innerHTML = m === 'kwh' ? '<span><i></i>Casa</span><span><i class="other"></i>Fuera / lugares</span><span><i class="dc"></i>Rápida</span>' : '';
    hint();
  }
  function hint() {
    const b = H.bs && H.bs[H.sel];
    $('#hsChartHint').textContent = !b ? 'Toca una barra para ver el detalle.' :
      b.title.charAt(0).toUpperCase() + b.title.slice(1) + ': ' + (b.n ? f1(b.km) + ' km en ' + b.n + (b.n === 1 ? ' viaje' : ' viajes') : 'sin viajes') +
      (b.kwh ? ' · cargados ' + f1(b.kwh) + ' kWh por ' + eur(b.eur) : '');
  }

  // ───────── listas ─────────
  const hm = ts => new Date(ts).toLocaleTimeString('es-ES', { hour: '2-digit', minute: '2-digit' });
  const durTxt = min => min < 60 ? Math.round(min) + ' min' : Math.floor(min / 60) + ' h' + (Math.round(min % 60) ? ' ' + Math.round(min % 60) + ' min' : '');
  function dayTitle(ts) {
    const d = new Date(ts), t = new Date(), y = new Date(); y.setDate(t.getDate() - 1);
    if (d.toDateString() === t.toDateString()) return 'Hoy';
    if (d.toDateString() === y.toDateString()) return 'Ayer';
    return d.toLocaleDateString('es-ES', { weekday: 'long', day: 'numeric', month: 'long' }) + (d.getFullYear() !== t.getFullYear() ? ' de ' + d.getFullYear() : '');
  }
  function list(s) {
    const items = H.tab === 'trips' ? s.trips : s.charges;
    const box = $('#hsList');
    if (!items.length) {
      box.innerHTML = '<div class="empty">' + icon(H.tab === 'trips' ? 'route' : 'bolt') + '<b>' + (H.tab === 'trips' ? 'No hay viajes' : 'No hay cargas') + ' en este periodo</b>' +
        (H.tab === 'trips' ? 'La app del coche los guarda sola cada vez que conduces.' : 'Cada vez que cargues aparecerá aquí con su energía y su coste.') + '</div>';
      $('#hsMore').hidden = true;
      return;
    }
    let html = '', day = '';
    items.slice(0, H.shown).forEach(x => {
      const dl = dayTitle(x.start);
      if (dl !== day) { day = dl; html += '<div class="day">' + esc(dl) + '</div>'; }
      html += H.tab === 'trips' ? tripItem(x) : chargeItem(x);
    });
    box.innerHTML = html;
    $('#hsMore').hidden = items.length <= H.shown;
  }
  function tripItem(t) {
    const sub = [hm(t.start), t.durationSec ? durTxt(t.durationSec / 60) : null, t.kwh100 != null ? f1(t.kwh100) + ' kWh/100 km' : null].filter(Boolean).join(' · ');
    return '<button type="button" class="item" data-trip="' + esc(t.id) + '"><span class="item-ico blue">' + icon('route') + '</span>' +
      '<span class="item-main"><b>' + esc(tripTitle(t)) + '</b><small>' + (t.live ? '<span class="hs-live">en curso</span> ' : '') + esc(sub) + '</small></span>' +
      '<span class="item-end">' + f1(pos(t.distanceKm, 0)) + ' km<small>' + (t._est ? '≈ ' : '') + eur(t._eur || 0) + '</small></span></button>';
  }
  function chargeItem(c) {
    const e = edit('charges', c.id), dc = c.type === 'DC';
    const sub = [hm(c.start), c.durationMin ? durTxt(c.durationMin) : null, c.socStart != null && c.socEnd != null ? c.socStart + ' → ' + c.socEnd + ' %' : null].filter(Boolean).join(' · ');
    return '<button type="button" class="item" data-chg="' + esc(c.id) + '"><span class="item-ico ' + (dc ? 'dcb' : 'ac') + '">' + (dc ? 'DC' : 'AC') + '</span>' +
      '<span class="item-main"><b>' + esc(chargeName(c, e)) + '</b><small>' + (c.live ? '<span class="hs-live">ahora</span> ' : '') + esc(sub) + '</small></span>' +
      '<span class="item-end">+' + f1(pos(c.kwh, 0)) + ' kWh<small>' + eur(c._eur || 0) + '</small></span></button>';
  }
  Geo.onUpdate = (() => { let t = 0; return () => { clearTimeout(t); t = setTimeout(() => { if (!view.hidden && H.sum) list(H.sum); }, 500); }; })();

  // ───────── controles ─────────
  $('#hsSpan').addEventListener('click', e => { const b = e.target.closest('[data-span]'); if (!b || b.dataset.span === H.span) return; H.span = b.dataset.span; H.shown = 40; render(); refresh(); });
  $('#hsPrev').addEventListener('click', () => shift(-1));
  $('#hsTitle').addEventListener('click', () => { if (!$('#hsTitle').classList.contains('past')) return; H.anchor = Date.now(); H.shown = 40; render(); refresh(); });
  $('#hsNext').addEventListener('click', () => shift(1));
  function shift(dir) {
    const d = new Date(range(H.span, H.anchor).from);
    if (H.span === 'week') d.setDate(d.getDate() + 7 * dir); else if (H.span === 'month') d.setMonth(d.getMonth() + dir); else d.setFullYear(d.getFullYear() + dir);
    if (d.getTime() > Date.now()) return;
    H.anchor = d.getTime(); H.shown = 40; render(); refresh();
  }
  $('#hsMetric').addEventListener('click', e => { const b = e.target.closest('[data-m]'); if (!b) return; H.metric = b.dataset.m; render(); });
  $('#hsTab').addEventListener('click', e => { const b = e.target.closest('[data-tab]'); if (!b) return; H.tab = b.dataset.tab; H.shown = 40; render(); });
  $('#hsMore').addEventListener('click', () => { H.shown += 60; render(); });
  $('#hsChart').addEventListener('click', e => { const b = e.target.closest('[data-i]'); if (!b) return; H.sel = +b.dataset.i === H.sel ? -1 : +b.dataset.i; $$('#hsChart .hs-bar').forEach((x, i) => x.classList.toggle('sel', i === H.sel)); hint(); });
  $('#hsList').addEventListener('click', e => {
    const t = e.target.closest('[data-trip]'), c = e.target.closest('[data-chg]');
    if (t) { const x = H.sum.trips.find(v => v.id === t.dataset.trip); if (x) openTrip(x); }
    if (c) { const x = H.sum.charges.find(v => v.id === c.dataset.chg); if (x) openCharge(x); }
  });
  $('#hsPrices').addEventListener('click', () => openSettings());
  $('#hsCsv').addEventListener('click', openExport);

  // ───────── detalle de un viaje ─────────
  const stat = (k, v) => v == null ? '' : '<div><span>' + k + '</span><b>' + v + '</b></div>';
  function openTrip(t) {
    const e = edit('trips', t.id) || {};
    const when = new Date(t.start).toLocaleDateString('es-ES', { weekday: 'long', day: 'numeric', month: 'long' }) + ' · ' + hm(t.start) + (t.end ? ' – ' + hm(t.end) : '');
    const src = { contador: 'medida por el coche', bateria: 'medida por la batería', soc: 'calculada por el % de batería' }[t.kwhSrc] || 'estimada con tu consumo medio';
    Sheet.open('<h3>' + esc(tripTitle(t, true)) + '</h3><p class="sub">' + esc(when.charAt(0).toUpperCase() + when.slice(1)) + (t.live ? ' · en curso' : '') + '</p>' +
      '<div class="hs-map" id="hsMap"><p class="hint">Cargando el recorrido…</p></div>' +
      '<div class="hs-stats">' +
      stat('Distancia', f1(pos(t.distanceKm, 0)) + ' km') +
      stat('Duración', t.durationSec ? durTxt(t.durationSec / 60) + (t.driveSec ? ' (' + durTxt(t.driveSec / 60) + ' en marcha)' : '') : null) +
      stat('Energía', (t._est ? '≈ ' : '') + f2(t._kwh || 0) + ' kWh') +
      stat('Consumo', t.kwh100 != null ? f1(t.kwh100) + ' kWh/100 km' : t.distanceKm >= 1 && t._kwh ? '≈ ' + f1(t._kwh / t.distanceKm * 100) + ' kWh/100 km' : null) +
      stat('Velocidad media', t.avgKmh != null ? fmt(t.avgKmh) + ' km/h' : null) +
      stat('Velocidad máxima', t.maxKmh != null ? fmt(t.maxKmh) + ' km/h' : null) +
      stat('Batería', t.socStart != null && t.socEnd != null ? t.socStart + ' → ' + t.socEnd + ' %' : null) +
      stat('Temperatura fuera', t.outTemp != null ? t.outTemp + ' °C' : null) +
      stat('Coste', (t._est ? '≈ ' : '') + eur(t._eur || 0)) +
      stat('Con gasolina', t._gas != null ? eur(t._gas) + ' (ahorras ' + eur(t._gas - (t._eur || 0)) + ')' : null) +
      '</div><p class="hint">Energía ' + src + '. Coste a ' + fmt(H.sum ? H.sum.eurKwh : 0, 3) + ' €/kWh, lo que te cuesta de media cargar.</p>' +
      '<div class="actions"><button type="button" class="btn btn-soft btn-block" id="hsSkip">' + (e.skip ? 'Volver a contar este viaje' : 'No contar este viaje') + '</button>' +
      '<button type="button" class="btn btn-soft btn-block" data-close>Cerrar</button></div>',
      body => {
        $('#hsSkip', body).addEventListener('click', () => {
          B.saveHistoryEdit('trips', t.id, e.skip ? null : { skip: true })
            .then(() => { toast(e.skip ? 'Viaje contado otra vez' : 'Este viaje ya no cuenta en los totales', 'ok'); Sheet.close(); })
            .catch(() => toast('No se ha podido guardar.', 'err'));
        });
        routeOf(t).then(ll => drawMap($('#hsMap', body), ll));
      });
  }
  function decode(s) {
    const out = []; let i = 0, lat = 0, lng = 0;
    const next = () => { let r = 0, sh = 0, b; do { b = s.charCodeAt(i++) - 63; r |= (b & 31) << sh; sh += 5; } while (b >= 32); return r & 1 ? ~(r >> 1) : r >> 1; };
    while (i < s.length) { lat += next(); lng += next(); out.push([lat / 1e5, lng / 1e5]); }
    return out;
  }
  function routeOf(t) {
    return B.tripRoute(t.id).then(enc => {
      if (typeof enc === 'string' && enc) return decode(enc);
      return B.getTripPoints(t.id).then(pts => (Array.isArray(pts) ? pts : Object.values(pts || {})).filter(p => p && p.lat != null).map(p => [p.lat, p.lng]));
    }).catch(() => []);
  }
  function drawMap(el, ll) {
    if (!el) return;
    if (!ll || ll.length < 2 || typeof L === 'undefined') { el.innerHTML = '<p class="hint">' + (typeof L === 'undefined' ? 'No se pudo cargar el mapa.' : 'Este viaje no tiene recorrido guardado.') + '</p>'; return; }
    el.innerHTML = '';
    if (H.map) { H.map.remove(); H.map = null; }
    const m = H.map = L.map(el, { zoomControl: false, attributionControl: true });
    m.attributionControl.setPrefix(false);
    L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', { maxZoom: 19, attribution: '© <a href="https://www.openstreetmap.org/copyright" target="_blank" rel="noopener">OpenStreetMap</a>' }).addTo(m);
    const css = getComputedStyle(document.documentElement);
    const line = L.polyline(ll, { color: css.getPropertyValue('--blue').trim() || '#60a5fa', weight: 5, opacity: 0.9 }).addTo(m);
    L.circleMarker(ll[0], { radius: 6, color: '#fff', weight: 2, fillColor: css.getPropertyValue('--accent').trim() || '#34d399', fillOpacity: 1 }).addTo(m);
    L.circleMarker(ll[ll.length - 1], { radius: 6, color: '#fff', weight: 2, fillColor: css.getPropertyValue('--danger').trim() || '#f87171', fillOpacity: 1 }).addTo(m);
    setTimeout(() => { m.invalidateSize(); m.fitBounds(line.getBounds().pad(0.15)); }, 260);
  }

  // ───────── detalle de una carga ─────────
  function openCharge(c) {
    const e = edit('charges', c.id) || {}, k = kindOf(c, e);
    const when = new Date(c.start).toLocaleDateString('es-ES', { weekday: 'long', day: 'numeric', month: 'long' }) + ' · ' + hm(c.start) + (c.end ? ' – ' + hm(c.end) : '');
    let periods = '';
    if (k === 'home' && H.cfg.home.mode === 'periods' && typeof e.costEur !== 'number' && !(typeof c.costEur === 'number' && !e.kind)) {
      const h = hours(c), by = { 1: [0, 0], 2: [0, 0], 3: [0, 0] };
      Object.keys(h).forEach(key => { const p = period(key); by[p][0] += h[key]; by[p][1] += h[key] * homePrice(key); });
      periods = '<p class="sheet-label">Por horas' + (c.kwhH ? '' : ' (repartido por el tiempo de carga)') + '</p><div class="hs-stats">' +
        [[3, 'Valle'], [2, 'Llano'], [1, 'Punta']].map(([p, n]) => by[p][0] > 0.005 ? stat(n, f1(by[p][0]) + ' kWh · ' + eur(by[p][1])) : '').join('') + '</div>';
    }
    const chips = [['home', 'Casa'], ['away', 'Fuera'], ['dc', 'Rápida'], ['free', 'Gratis']].map(([v, n]) =>
      '<button type="button" class="chip' + (e.kind === v ? ' on' : '') + '" data-kind="' + v + '">' + n + '</button>').join('');
    Sheet.open('<h3>' + esc(chargeName(c, e)) + '</h3><p class="sub">' + esc(when.charAt(0).toUpperCase() + when.slice(1)) + (c.live ? ' · cargando ahora' : '') + '</p>' +
      '<div class="hs-stats">' +
      stat('Energía', '+' + f1(pos(c.kwh, 0)) + ' kWh') +
      stat('Batería', c.socStart != null && c.socEnd != null ? c.socStart + ' → ' + c.socEnd + ' %' : null) +
      stat('Duración', c.durationMin ? durTxt(c.durationMin) + (c.chargeMin && c.chargeMin < c.durationMin - 4 ? ' (' + durTxt(c.chargeMin) + ' cargando)' : '') : null) +
      stat('Potencia', c.avgKw != null ? f1(c.avgKw) + ' kW de media' + (c.maxKw ? ', ' + f1(c.maxKw) + ' máx.' : '') : null) +
      stat('Tipo', c.type === 'DC' ? 'DC · rápida' : 'AC · normal') +
      stat('Coste', eur(c._eur || 0) + (pos(c.kwh, 0) > 0 ? ' (' + fmt((c._eur || 0) / c.kwh, 3) + ' €/kWh)' : '')) +
      '</div>' + periods +
      '<p class="sheet-label">¿Dónde fue? (cambia el precio)</p><div class="chips">' + chips + '</div>' +
      '<label class="field hs-field"><span>O pon lo que pagaste (€)</span><input type="text" inputmode="decimal" id="hsCost" placeholder="Automático" value="' + (typeof e.costEur === 'number' ? fmt(e.costEur, 2) : '') + '"></label>' +
      (c.lat != null && !placeOf(c.lat, c.lng) ? '<button type="button" class="link strong" id="hsAddHere">Guardar este sitio como lugar (Casa, Trabajo…)</button>' : '') +
      '<div class="actions"><button type="button" class="btn btn-primary btn-block" id="hsSaveChg">Guardar</button>' +
      '<button type="button" class="btn btn-soft btn-block" id="hsSkipChg">' + (e.skip ? 'Volver a contar esta carga' : 'No contar esta carga') + '</button></div>',
      body => {
        let kind = e.kind || null;
        $$('[data-kind]', body).forEach(b => b.addEventListener('click', () => {
          kind = kind === b.dataset.kind ? null : b.dataset.kind;
          $$('[data-kind]', body).forEach(x => x.classList.toggle('on', x.dataset.kind === kind));
        }));
        const save = v => B.saveHistoryEdit('charges', c.id, v).then(() => { toast('Carga guardada', 'ok'); Sheet.close(); }).catch(() => toast('No se ha podido guardar.', 'err'));
        $('#hsSaveChg', body).addEventListener('click', () => {
          const raw = $('#hsCost', body).value.trim(), cost = raw ? numIn(raw) : null;
          if (raw && cost == null) return toast('Escribe el importe con números, por ejemplo 4,50.', 'err');
          const v = {}; if (kind) v.kind = kind; if (cost != null) v.costEur = cost; if (e.skip) v.skip = true;
          save(Object.keys(v).length ? v : null);
        });
        $('#hsSkipChg', body).addEventListener('click', () => {
          if (!e.skip) return save(Object.assign({}, e, { skip: true }));
          const v = Object.assign({}, e); delete v.skip;
          save(Object.keys(v).length ? v : null);
        });
        const add = $('#hsAddHere', body); if (add) add.addEventListener('click', () => openSettings({ lat: c.lat, lng: c.lng }));
      });
  }

  // ───────── precios y lugares ─────────
  function openSettings(here) {
    const w = JSON.parse(JSON.stringify(H.cfg));
    const inp = (lab, key, val) => '<label class="field"><span>' + lab + '</span><input type="text" inputmode="decimal" data-k="' + key + '" value="' + fmt(val, val < 1 ? 3 : 2) + '"></label>';
    Sheet.open('<h3>Precios y lugares</h3><p class="sub">Con esto se calcula lo que te cuesta cada carga y cada viaje. Pon los precios de tu factura, con impuestos.</p>' +
      '<p class="sheet-label">En casa</p><div class="seg" id="hsMode"><button type="button" data-mode="periods">Por horas (2.0TD)</button><button type="button" data-mode="flat">Precio único</button></div>' +
      '<div class="hs-grid" data-for="periods">' + inp('Valle €/kWh', 'p3', w.home.p3) + inp('Llano €/kWh', 'p2', w.home.p2) + inp('Punta €/kWh', 'p1', w.home.p1) + '</div>' +
      '<p class="hint" data-for="periods">Valle: de 0 a 8 h y todo el día los fines de semana y festivos nacionales. Punta: de 10 a 14 h y de 18 a 22 h. El resto, llano.</p>' +
      '<div class="hs-grid" data-for="flat">' + inp('Precio €/kWh', 'flat', w.home.flat) + '</div>' +
      '<p class="sheet-label">Fuera de casa</p><div class="hs-grid">' + inp('Normal (AC) €/kWh', 'awayAc', w.awayAc) + inp('Rápida (DC) €/kWh', 'dc', w.dc) + '</div>' +
      '<p class="sheet-label">Tus lugares</p><div class="list" id="hsPlaces"></div>' +
      '<div class="hs-newplace" id="hsNew" hidden><label class="field"><span>Nombre</span><input type="text" id="hsPlName" maxlength="30"></label>' +
      '<div class="seg" id="hsPlPrice"><button type="button" data-p="home">Precio de casa</button><button type="button" data-p="free">Gratis</button><button type="button" data-p="fixed">Precio fijo</button></div>' +
      '<div class="hs-grid"><label class="field" id="hsPlFixedF" hidden><span>€/kWh</span><input type="text" inputmode="decimal" id="hsPlFixed" value="0,30"></label>' +
      '<label class="field"><span>Radio (metros)</span><input type="text" inputmode="numeric" id="hsPlR" value="150"></label></div>' +
      '<button type="button" class="btn btn-soft btn-block" id="hsPlOk">Añadir lugar</button></div>' +
      '<button type="button" class="btn btn-ghost btn-block" id="hsPlAdd">' + icon('pin') + 'Añadir el sitio donde está el coche</button>' +
      '<p class="sheet-label">Cargas antiguas sin ubicación</p><div class="seg" id="hsUnk"><button type="button" data-u="home">Como en casa</button><button type="button" data-u="away">Como fuera</button></div>' +
      '<p class="sheet-label">Comparar con gasolina</p><label class="hs-row"><span>Calcular lo que te ahorras</span><label class="switch"><input type="checkbox" id="hsGas"' + (w.gas.on ? ' checked' : '') + '><span></span></label></label>' +
      '<div class="hs-grid" data-for="gas">' + inp('Gasolina €/litro', 'eurL', w.gas.eurL) + inp('Coche de gasolina l/100 km', 'l100', w.gas.l100) + '</div>' +
      '<div class="actions"><button type="button" class="btn btn-primary btn-block" id="hsSave">Guardar</button><button type="button" class="btn btn-soft btn-block" data-close>Cancelar</button></div>',
      body => {
        let newAt = null, newPrice = 'home';
        const sync = () => {
          $$('#hsMode button', body).forEach(b => b.classList.toggle('active', b.dataset.mode === w.home.mode));
          $$('[data-for="periods"]', body).forEach(x => { x.hidden = w.home.mode !== 'periods'; });
          $$('[data-for="flat"]', body).forEach(x => { x.hidden = w.home.mode !== 'flat'; });
          $$('#hsUnk button', body).forEach(b => b.classList.toggle('active', b.dataset.u === w.unknownAc));
          $$('#hsPlPrice button', body).forEach(b => b.classList.toggle('active', b.dataset.p === newPrice));
          $('#hsPlFixedF', body).hidden = newPrice !== 'fixed';
          $('[data-for="gas"]', body).hidden = !$('#hsGas', body).checked;
          $('#hsPlaces', body).innerHTML = w.places.length ? w.places.map((p, i) =>
            '<div class="item"><span class="item-ico green">' + icon('pin') + '</span><span class="item-main"><b>' + esc(p.name) + '</b><small>' +
            (p.price === 'home' ? 'precio de casa' : p.price === 0 ? 'gratis' : fmt(p.price, 3) + ' €/kWh') + ' · ' + fmt(p.r) + ' m</small></span>' +
            '<button type="button" class="icon-btn" data-del="' + i + '" aria-label="Quitar">' + icon('x') + '</button></div>').join('') :
            '<p class="hint">Aún no hay lugares. Añade Casa para que se distingan las cargas de casa de las de fuera.</p>';
        };
        const startNew = at => {
          newAt = at;
          $('#hsPlName', body).value = w.places.some(p => p.price === 'home') ? '' : 'Casa';
          newPrice = w.places.some(p => p.price === 'home') ? 'free' : 'home';
          $('#hsNew', body).hidden = false; $('#hsPlAdd', body).hidden = true; sync();
          $('#hsPlName', body).focus();
        };
        $('#hsMode', body).addEventListener('click', e => { const b = e.target.closest('[data-mode]'); if (b) { w.home.mode = b.dataset.mode; sync(); } });
        $('#hsUnk', body).addEventListener('click', e => { const b = e.target.closest('[data-u]'); if (b) { w.unknownAc = b.dataset.u; sync(); } });
        $('#hsPlPrice', body).addEventListener('click', e => { const b = e.target.closest('[data-p]'); if (b) { newPrice = b.dataset.p; sync(); } });
        $('#hsGas', body).addEventListener('change', sync);
        $('#hsPlaces', body).addEventListener('click', e => { const b = e.target.closest('[data-del]'); if (b) { w.places.splice(+b.dataset.del, 1); sync(); } });
        $('#hsPlAdd', body).addEventListener('click', () => {
          const L0 = A.st.s && A.st.s.location;
          if (!L0) return toast('El coche aún no ha enviado su ubicación.', 'err');
          startNew({ lat: L0.lat, lng: L0.lng });
        });
        $('#hsPlOk', body).addEventListener('click', () => {
          const name = $('#hsPlName', body).value.trim(), r = numIn($('#hsPlR', body).value), fx = numIn($('#hsPlFixed', body).value);
          if (!name) return toast('Ponle un nombre al lugar.', 'err');
          if (newPrice === 'fixed' && fx == null) return toast('Escribe el precio por kWh.', 'err');
          w.places.push({ name, lat: +newAt.lat.toFixed(5), lng: +newAt.lng.toFixed(5), r: Math.min(2000, Math.max(30, r || 150)), price: newPrice === 'home' ? 'home' : newPrice === 'free' ? 0 : fx });
          $('#hsNew', body).hidden = true; $('#hsPlAdd', body).hidden = false; sync();
        });
        $('#hsSave', body).addEventListener('click', () => {
          const get = k => numIn(($('[data-k="' + k + '"]', body) || {}).value);
          const out = {
            home: { mode: w.home.mode, flat: get('flat'), p1: get('p1'), p2: get('p2'), p3: get('p3') }, awayAc: get('awayAc'), dc: get('dc'),
            unknownAc: w.unknownAc, places: w.places, gas: { on: $('#hsGas', body).checked, eurL: get('eurL'), l100: get('l100') }
          };
          const bad = [out.home.flat, out.home.p1, out.home.p2, out.home.p3, out.awayAc, out.dc, out.gas.eurL, out.gas.l100].some(v => v == null || v > 50);
          if (bad) return toast('Revisa los precios: escribe números, por ejemplo 0,15.', 'err');
          B.saveHistoryCfg(out).then(() => { toast('Precios guardados', 'ok'); Sheet.close(); }).catch(() => toast('No se han podido guardar los precios.', 'err'));
        });
        sync();
        if (here) startNew(here);
      });
  }

  // ───────── exportar ─────────
  function openExport() {
    if (!H.sum) return toast('Espera a que se cargue el historial.');
    Sheet.open('<h3>Exportar ' + esc($('#hsTitle').textContent) + '</h3><p class="sub">Un archivo CSV que se abre con Excel, Numbers o Google Sheets.</p>' +
      '<div class="actions"><button type="button" class="btn btn-primary btn-block" data-x="trips">' + icon('download') + 'Viajes (' + H.sum.trips.length + ')</button>' +
      '<button type="button" class="btn btn-primary btn-block" data-x="charges">' + icon('download') + 'Cargas (' + H.sum.charges.length + ')</button>' +
      '<button type="button" class="btn btn-soft btn-block" data-close>Cerrar</button></div>',
      body => $$('[data-x]', body).forEach(b => b.addEventListener('click', () => exportCsv(b.dataset.x))));
  }
  function exportCsv(kind) {
    const dt = ts => { if (!ts) return ''; const d = new Date(ts); return p2(d.getDate()) + '/' + p2(d.getMonth() + 1) + '/' + d.getFullYear() + ' ' + p2(d.getHours()) + ':' + p2(d.getMinutes()); };
    const rows = kind === 'trips'
      ? [['Salida', 'Llegada', 'Desde', 'Hasta', 'Km', 'Minutos', 'kWh', 'kWh/100 km', 'Energía', 'Vel. media km/h', 'Vel. máx. km/h', 'Batería salida %', 'Batería llegada %', 'Temp. fuera °C', 'Coste €', 'Gasolina €']]
        .concat(H.sum.trips.map(t => [dt(t.start), dt(t.end), spot(t.startLat, t.startLng) || t.startAddr || '', spot(t.endLat, t.endLng) || t.endAddr || '', pos(t.distanceKm, 0),
          t.durationSec ? Math.round(t.durationSec / 60) : null, t._kwh, t.distanceKm >= 1 ? t._kwh / t.distanceKm * 100 : null, t._est ? 'estimada' : 'medida',
          t.avgKmh, t.maxKmh, t.socStart, t.socEnd, t.outTemp, t._eur, t._gas]))
      : [['Inicio', 'Fin', 'Lugar', 'Tipo', 'Batería inicio %', 'Batería fin %', 'kWh', 'Minutos', 'kW medio', 'kW máx.', 'Precio', 'Coste €']]
        .concat(H.sum.charges.map(c => [dt(c.start), dt(c.end), chargeName(c, edit('charges', c.id)), c.type || 'AC', c.socStart, c.socEnd, pos(c.kwh, 0), c.durationMin,
          c.avgKw, c.maxKw, KIND[c._kind] || c._kind, c._eur]));
    const cell = v => v == null ? '' : typeof v === 'number' ? String(Math.round(v * 1000) / 1000).replace('.', ',') : '"' + String(v).replace(/"/g, '""') + '"';
    const text = '﻿' + rows.map(r => r.map(cell).join(';')).join('\r\n');
    const a = document.createElement('a');
    a.href = URL.createObjectURL(new Blob([text], { type: 'text/csv;charset=utf-8' }));
    a.download = 'bydlink-' + (kind === 'trips' ? 'viajes' : 'cargas') + '-' + $('#hsTitle').textContent.replace(/[^\wñáéíóú]+/gi, '-').toLowerCase() + '.csv';
    document.body.appendChild(a); a.click();
    setTimeout(() => { URL.revokeObjectURL(a.href); a.remove(); }, 1500);
    toast('Archivo descargado', 'ok');
  }

  // ───────── resumen del mes en Inicio ─────────
  function renderMonth() {
    if (!$('#hsMonth')) return;
    const r = range('month', Date.now()), v = H.cache['month:' + r.from];
    if (!v) return;
    let s = summarize(v), label = 'Este mes';
    if (!s.trips.length && !s.charges.length) {   // primeros días del mes: se enseña el anterior
      const pr = range('month', r.from - DAY), pv = H.cache['month:' + pr.from];
      if (!pv) load('month', pr.from).then(renderMonth).catch(() => {});
      else { const ps = summarize(pv); if (ps.trips.length || ps.charges.length) { s = ps; label = 'El mes pasado · ' + MONTHS[new Date(pr.from).getMonth()]; } }
    }
    $('#hsMonthH').textContent = label;
    $('#hsMonthMain').textContent = fmt(s.km, s.km > 0 && s.km < 100 ? 1 : 0) + ' km · ' + eur(s.chEur) + ' en cargas';
    $('#hsMonthSub').textContent = (s.avg != null ? f1(s.avg) + ' kWh/100 km · ' : '') + (s.per100 != null ? f2(s.per100) + ' € cada 100 km' : 'Viajes, cargas y lo que te cuestan') +
      (s.saved != null && s.saved > 0 ? ' · ahorras ' + eur(s.saved) : '');
  }
  function monthLoad(fresh) { load('month', Date.now(), fresh).then(renderMonth).catch(() => {}); }

  // ───────── arranque ─────────
  let started = false;
  function start() {
    if (started) return; started = true;
    B.historyCfg(v => { H.raw = v; H.cfg = cfgOf(v); after(); });
    B.historyEdits(v => { H.edits = { trips: (v && v.trips) || {}, charges: (v && v.charges) || {} }; after(); });
    monthLoad();
    setInterval(() => { if (!document.hidden) { monthLoad(true); if (!view.hidden) refresh(true); } }, 5 * 60e3);
  }
  function after() {
    renderMonth();
    if (!view.hidden) render();
    if (A.renderSessions) A.renderSessions();   // el coste de la lista de Carga también usa estos precios
  }
  A.onStatus(start);
  if (B.mode === 'demo') setTimeout(start, 300);

  // Para app.js: coste de una carga y nombre de un viaje con estos precios y lugares.
  window.BYDHistory = { cost: c => chargeCost(c, edit('charges', c.id)), tripTitle };
})();
