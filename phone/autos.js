/* BYDLink · automatizaciones ("cuando pase esto, haz esto") y avisos al móvil con la web cerrada.
 * Las reglas se guardan en cars/{id}/automations y las cumple EL COCHE, aunque el móvil esté apagado; los avisos
 * los manda el propio coche por Web Push (sin servidor). Necesita la app del coche 2.27
 * (ver protocol/automatizaciones.md). */
(function () {
  'use strict';
  const A = window.BYDApp;
  if (!A) return;
  const { st, B, command, toast, Sheet, icon, esc, ago } = A;
  const $ = (s, r) => (r || document).querySelector(s);
  const $$ = (s, r) => Array.from((r || document).querySelectorAll(s));
  const NEED = '2.28';
  const card = $('#autoCard');
  if (!card || !B.autoWatch) return;
  function lsGet(k) { try { return localStorage.getItem(k); } catch (e) { return null; } }
  function lsSet(k, v) { try { if (v == null) localStorage.removeItem(k); else localStorage.setItem(k, v); } catch (e) {} }

  // ───────── catálogo: qué se puede elegir ─────────
  const DAYS = ['L', 'M', 'X', 'J', 'V', 'S', 'D'];
  const daysTxt = d => {
    d = (d || []).slice().sort();
    if (!d.length || d.length === 7) return '';
    if (d.join() === '1,2,3,4,5') return ' entre semana';
    if (d.join() === '6,7') return ' el fin de semana';
    return ' (' + d.map(n => DAYS[n - 1]).join(', ') + ')';
  };
  const placeTxt = o => o.label || 'el sitio elegido';   // los resúmenes son texto: se escapan al pintarlos
  const deg = v => String(v).replace('.', ',') + ' °C';
  const MODES = { off: 'Apagada', parked: 'Aparcado', drive: 'En marcha', always: 'Siempre' };

  // Cuando…  (w = lo fijo de la regla; f = lo que se elige; s = texto del resumen)
  const WHEN = [
    { k: 'acc_off', t: 'Apagas el coche', w: { type: 'acc', on: false }, s: () => 'apagas el coche' },
    { k: 'acc_on', t: 'Das el contacto', w: { type: 'acc', on: true }, s: () => 'das el contacto' },
    { k: 'lock_on', t: 'Cierras el coche', w: { type: 'lock', locked: true }, s: () => 'cierras el coche' },
    { k: 'lock_off', t: 'Abres el coche', w: { type: 'lock', locked: false }, s: () => 'abres el coche' },
    { k: 'chg_on', t: 'Empieza la carga', w: { type: 'charge', charging: true }, s: () => 'empieza la carga' },
    { k: 'chg_off', t: 'Termina la carga', w: { type: 'charge', charging: false }, s: () => 'termina la carga' },
    { k: 'soc_below', t: 'La batería baja a…', w: { type: 'soc', op: 'below' }, f: ['pct'], d: { value: 20 }, s: o => 'la batería baja al ' + o.value + ' %' },
    { k: 'soc_above', t: 'La batería llega a…', w: { type: 'soc', op: 'above' }, f: ['pct'], d: { value: 80 }, s: o => 'la batería llega al ' + o.value + ' %' },
    { k: 'time', t: 'A una hora', w: { type: 'time' }, f: ['at', 'days'], d: { at: '08:00', days: [] }, s: o => 'son las ' + o.at + daysTxt(o.days) },
    { k: 'arrive', t: 'El coche llega a un sitio', w: { type: 'place', event: 'arrive' }, f: ['place'], d: { radius: 200 }, s: o => 'el coche llega a ' + placeTxt(o) },
    { k: 'leave', t: 'El coche sale de un sitio', w: { type: 'place', event: 'leave' }, f: ['place'], d: { radius: 200 }, s: o => 'el coche sale de ' + placeTxt(o) },
    { k: 'tin_above', t: 'Dentro del coche hace calor', w: { type: 'temp', where: 'in', op: 'above' }, f: ['temp'], d: { value: 35 }, s: o => 'dentro hace ' + deg(o.value) + ' o más' },
    { k: 'tin_below', t: 'Dentro del coche hace frío', w: { type: 'temp', where: 'in', op: 'below' }, f: ['temp'], d: { value: 5 }, s: o => 'dentro hace ' + deg(o.value) + ' o menos' },
    { k: 'tout_above', t: 'Fuera hace calor', w: { type: 'temp', where: 'out', op: 'above' }, f: ['temp'], d: { value: 30 }, s: o => 'fuera hace ' + deg(o.value) + ' o más' },
    { k: 'tout_below', t: 'Fuera hace frío', w: { type: 'temp', where: 'out', op: 'below' }, f: ['temp'], d: { value: 3 }, s: o => 'fuera hace ' + deg(o.value) + ' o menos' },
    { k: 'sentry', t: 'El centinela ve movimiento', w: { type: 'sentry' }, s: () => 'el centinela ve movimiento' },
    { k: 'v12', t: 'La batería de 12 V baja', w: { type: 'aux12v' }, f: ['volt'], d: { value: 12 }, s: o => 'la batería de 12 V baja a ' + String(o.value).replace('.', ',') + ' V' }
  ];
  // Solo si…
  const ONLY = [
    { k: 'between', t: 'Entre dos horas', c: { type: 'time' }, f: ['from', 'to', 'days'], d: { from: '22:00', to: '07:00', days: [] }, s: o => 'entre las ' + o.from + ' y las ' + o.to + daysTxt(o.days) },
    { k: 'soc_min', t: 'La batería está por encima de…', c: { type: 'soc', op: 'above' }, f: ['pct'], d: { value: 30 }, s: o => 'la batería está al ' + o.value + ' % o más' },
    { k: 'soc_max', t: 'La batería está por debajo de…', c: { type: 'soc', op: 'below' }, f: ['pct'], d: { value: 80 }, s: o => 'la batería está al ' + o.value + ' % o menos' },
    { k: 'in_place', t: 'El coche está en un sitio', c: { type: 'place', inside: true }, f: ['place'], d: { radius: 200 }, s: o => 'el coche está en ' + placeTxt(o) },
    { k: 'out_place', t: 'El coche no está en un sitio', c: { type: 'place', inside: false }, f: ['place'], d: { radius: 200 }, s: o => 'el coche no está en ' + placeTxt(o) },
    { k: 'parked', t: 'El coche está aparcado', c: { type: 'state', key: 'parked', value: true }, s: () => 'está aparcado' },
    { k: 'charging', t: 'Está cargando', c: { type: 'state', key: 'charging', value: true }, s: () => 'está cargando' },
    { k: 'notcharging', t: 'No está cargando', c: { type: 'state', key: 'charging', value: false }, s: () => 'no está cargando' },
    { k: 'locked', t: 'Está cerrado', c: { type: 'state', key: 'locked', value: true }, s: () => 'está cerrado' },
    { k: 'cold', t: 'Fuera hace frío', c: { type: 'temp', where: 'out', op: 'below' }, f: ['temp'], d: { value: 5 }, s: o => 'fuera hace ' + deg(o.value) + ' o menos' },
    { k: 'hot', t: 'Fuera hace calor', c: { type: 'temp', where: 'out', op: 'above' }, f: ['temp'], d: { value: 28 }, s: o => 'fuera hace ' + deg(o.value) + ' o más' }
  ];
  // Haz…  (a = orden del coche; p = parámetros fijos; f = los que se eligen)
  const DO = [
    { k: 'notify', t: 'Avisarme en el móvil', a: 'notify', f: ['text'], d: { text: '' }, s: o => 'te avisa en el móvil' + (o.text ? ': «' + o.text + '»' : '') },
    { k: 'ac_on', t: 'Encender el aire', a: 'setClimate', p: { on: true }, f: ['actemp'], d: { temp: 21 }, s: o => 'enciende el aire a ' + o.temp + '°', cap: 'climate' },
    { k: 'ac_off', t: 'Apagar el aire', a: 'setClimate', p: { on: false }, s: () => 'apaga el aire', cap: 'climate' },
    { k: 'sentry_on', t: 'Activar el centinela', a: 'setSentryConfig', p: { on: true }, s: () => 'activa el centinela' },
    { k: 'sentry_off', t: 'Desactivar el centinela', a: 'setSentryConfig', p: { on: false }, s: () => 'desactiva el centinela' },
    { k: 'rec', t: 'Grabar un vídeo', a: 'recordVideo', f: ['seconds'], d: { seconds: 60 }, s: o => 'graba ' + (o.seconds >= 60 ? (o.seconds / 60) + ' min' : o.seconds + ' s') + ' de vídeo' },
    { k: 'photo', t: 'Hacer una foto', a: 'takePhoto', s: () => 'hace una foto' },
    { k: 'dash', t: 'Cambiar la grabación continua', a: 'setVideoConfig', f: ['mode'], d: { mode: 'parked' }, s: o => 'pone la grabación continua en «' + (MODES[o.mode] || o.mode) + '»' },
    { k: 'limit', t: 'Poner el límite de carga', a: 'setChargeLimit', f: ['percent'], d: { percent: 80 }, s: o => 'pone el límite de carga al ' + o.percent + ' %', cap: 'chargeLimit' }
  ];
  const COOL = [[1, '1 minuto'], [5, '5 minutos'], [15, '15 minutos'], [30, '30 minutos'], [60, '1 hora'], [180, '3 horas'], [720, '12 horas'], [1440, '1 día']];

  const sub = (o, base) => Object.keys(base).every(k => o && o[k] === base[k]);
  const findWhen = w => WHEN.find(e => sub(w, e.w));
  const findOnly = c => ONLY.find(e => sub(c, e.c));
  const findDo = a => DO.find(e => a && a.action === e.a && sub(a.payload || {}, e.p || {}));
  const cap1 = s => s.charAt(0).toUpperCase() + s.slice(1);
  function summary(r) {
    const w = findWhen(r.when || {});
    const o = (r.only || []).map(c => { const e = findOnly(c); return e ? e.s(c) : null; }).filter(Boolean);
    const d = (r.do || []).map(a => { const e = findDo(a); return e ? e.s(Object.assign({}, a.payload)) : null; }).filter(Boolean);
    return 'Cuando ' + (w ? w.s(r.when) : '…') + (o.length ? ', si ' + o.join(' y ') : '') + ' → ' + (d.join(', ') || '…');
  }

  // Plantillas listas: abren el editor ya relleno, para retocar y guardar.
  const TEMPLATES = [
    { name: 'Al apagar el coche, activa el centinela', when: { type: 'acc', on: false }, do: [{ action: 'setSentryConfig', payload: { on: true } }] },
    { name: 'Si la batería baja del 20 %, avísame', when: { type: 'soc', op: 'below', value: 20 }, do: [{ action: 'notify', payload: { text: 'Al coche le queda poca batería.' } }] },
    { name: 'A las 8:00 entre semana, pon el aire a 21°', when: { type: 'time', at: '08:00', days: [1, 2, 3, 4, 5] },
      do: [{ action: 'setClimate', payload: { on: true, temp: 21 } }] },
    { name: 'Al terminar la carga, avísame', when: { type: 'charge', charging: false }, do: [{ action: 'notify', payload: { text: 'Ha terminado la carga.' } }] },
    { name: 'Al llegar a casa, desactiva el centinela', when: { type: 'place', event: 'arrive', radius: 200, label: 'casa' }, do: [{ action: 'setSentryConfig', payload: { on: false } }] },
    { name: 'Si el centinela ve algo, graba 2 minutos', when: { type: 'sentry' }, do: [{ action: 'recordVideo', payload: { seconds: 120 } }, { action: 'notify', payload: { text: 'Grabando 2 minutos.' } }] },
    { name: 'Si dentro hace mucho calor, avísame', when: { type: 'temp', where: 'in', op: 'above', value: 40 }, only: [{ type: 'state', key: 'parked', value: true }], do: [{ action: 'notify', payload: { text: 'Dentro del coche hace mucho calor.' } }] },
    { name: 'Si la batería de 12 V baja, avísame', when: { type: 'aux12v', value: 12 }, do: [{ action: 'notify', payload: { text: 'La batería pequeña (12 V) está baja.' } }] },
    { name: 'Al llegar al 80 %, avísame para desenchufar', when: { type: 'soc', op: 'above', value: 80 }, only: [{ type: 'state', key: 'charging', value: true }], do: [{ action: 'notify', payload: { text: 'El coche ya está al 80 %: puedes desenchufarlo.' } }] }
  ];

  // ───────── estado ─────────
  let rules = {}, states = {}, started = false;
  const clean = o => JSON.parse(JSON.stringify(o));

  function start() {
    if (started) return; started = true;
    B.autoWatch(v => { rules = v || {}; render(); });
    if (B.autoStateWatch) B.autoStateWatch(v => { states = v || {}; render(); });
    pushRefresh();
  }
  A.onStatus(() => { start(); render(); renderPush(); });
  // Al tocar un aviso con la web ya abierta, el service worker cambia el # de la dirección: ir a esa sección.
  window.addEventListener('hashchange', () => {
    const v = location.hash.slice(1), t = document.querySelector('.tab[data-goto="' + v.replace(/[^a-z]/g, '') + '"]');
    if (t && st.view !== v) t.click();
  });

  // ───────── tarjeta de Ajustes ─────────
  $('.card-h', card).insertAdjacentHTML('afterend', '<p class="hint ar-intro">«Cuando pase esto, haz esto». Las cumple el propio coche, aunque tengas el móvil apagado.</p>');
  card.insertAdjacentHTML('beforeend',
    '<div id="arList" class="list"></div>' +
    '<button type="button" class="btn btn-soft btn-block" id="arNew">' + icon('plus') + 'Nueva automatización</button>' +
    '<button type="button" class="link strong ar-log-btn" id="arLogBtn">Ver lo que han hecho</button>');
  card.insertAdjacentHTML('afterend',
    '<div class="card" id="pushCard"><h2 class="card-h">Avisos en este móvil</h2><div id="pushBody"><p class="hint">Comprobando…</p></div></div>');

  function render() {
    const box = $('#arList'); if (!box) return;
    const ids = Object.keys(rules).sort((a, b) => ((rules[a] && rules[a].created) || 0) - ((rules[b] && rules[b].created) || 0));
    const old = !A.carHas(NEED);
    let html = old ? '<p class="warn-txt ar-old">Para usar automatizaciones, actualiza la app del coche a la versión ' + NEED + '.</p>' : '';
    if (!ids.length) html += '<div class="empty">' + icon('sparkle') + '<b>Aún no tienes ninguna</b>Elige una de las listas o crea la tuya.</div>';
    ids.forEach(id => {
      const r = rules[id] || {};
      const s = states[id];
      html += '<div class="item ar-item' + (r.on === false ? ' ar-off' : '') + '">' +
        '<button type="button" class="item-main ar-edit" data-edit="' + esc(id) + '"><b>' + esc(r.name || 'Automatización') + '</b>' +
        '<small>' + esc(summary(r)) + '</small>' +
        (s && s.last ? '<small class="ar-last ' + (s.ok ? 'ok' : 'bad') + '">' + (s.ok ? '✓' : '✗') + ' Última vez ' + esc(ago(s.last)) + '</small>' : '') +
        '</button>' +
        '<label class="switch"><input type="checkbox" data-toggle="' + esc(id) + '"' + (r.on === false ? '' : ' checked') + '><span></span></label></div>';
    });
    box.innerHTML = html;
    $('#arNew').disabled = old;
  }

  card.addEventListener('click', e => {
    const ed = e.target.closest('[data-edit]');
    if (ed) { const r = rules[ed.dataset.edit]; if (r) editor(ed.dataset.edit, clean(r)); return; }
    if (e.target.closest('#arNew')) { if (A.carHas(NEED)) templates(); return; }
    if (e.target.closest('#arLogBtn')) showLog();
  });
  card.addEventListener('change', e => {
    const t = e.target.closest('[data-toggle]'); if (!t) return;
    B.autoToggle(t.dataset.toggle, t.checked).then(() => toast(t.checked ? 'Automatización encendida' : 'Automatización apagada', 'ok'))
      .catch(err => { t.checked = !t.checked; toast('No se ha podido guardar: ' + ((err && err.message) || err), 'err'); });
  });

  function templates() {
    let html = '<h3>Nueva automatización</h3><p class="sub">Elige una ya hecha (podrás cambiarla antes de guardar) o empieza de cero.</p><div class="list">';
    TEMPLATES.forEach((t, i) => {
      html += '<button type="button" class="item tappable" data-tpl="' + i + '"><span class="item-ico green">' + icon('sparkle') + '</span>' +
        '<span class="item-main"><b>' + esc(t.name) + '</b></span>' + icon('right') + '</button>';
    });
    html += '</div><div class="actions"><button type="button" class="btn btn-primary btn-block" data-blank>' + icon('plus') + 'Empezar de cero</button>' +
      '<button type="button" class="btn btn-soft btn-block" data-close>Cancelar</button></div>';
    Sheet.open(html, b => {
      $$('[data-tpl]', b).forEach(x => x.addEventListener('click', () => {
        const t = clean(TEMPLATES[+x.dataset.tpl]);
        editor(null, Object.assign({ on: true, only: [], cooldownMin: 5 }, t));
      }));
      $('[data-blank]', b).addEventListener('click', () => editor(null, { name: '', on: true, when: { type: 'acc', on: false }, only: [], do: [{ action: 'notify', payload: { text: '' } }], cooldownMin: 5 }));
    });
  }

  // ───────── editor ─────────
  const opt = (v, t, sel) => '<option value="' + esc(v) + '"' + (sel ? ' selected' : '') + '>' + esc(t) + '</option>';
  const range = (a, b, s) => { const r = []; for (let x = a; x <= b + 1e-9; x += s) r.push(Math.round(x * 10) / 10); return r; };
  const sel = (key, list, v, label) => '<label class="ar-f"><span>' + label + '</span><select data-f="' + key + '" data-num>' +
    list.map(x => opt(x[0], x[1], String(x[0]) === String(v))).join('') + '</select></label>';

  function fields(names, o) {
    return (names || []).map(n => {
      switch (n) {
        case 'pct': return sel('value', range(5, 100, 5).map(x => [x, x + ' %']), o.value, 'Batería');
        case 'temp': return sel('value', range(-15, 50, 1).map(x => [x, x + ' °C']), o.value, 'Temperatura');
        case 'volt': return sel('value', [11.5, 11.8, 12, 12.2, 12.4].map(x => [x, String(x).replace('.', ',') + ' V']), o.value, 'Tensión');
        case 'at': return '<label class="ar-f"><span>Hora</span><input type="time" data-f="at" value="' + esc(o.at || '08:00') + '"></label>';
        case 'from': return '<label class="ar-f"><span>Desde</span><input type="time" data-f="from" value="' + esc(o.from || '22:00') + '"></label>';
        case 'to': return '<label class="ar-f"><span>Hasta</span><input type="time" data-f="to" value="' + esc(o.to || '07:00') + '"></label>';
        case 'days': {
          const d = o.days || [];
          return '<div class="ar-f ar-wide"><span>Días <small>(ninguno = todos)</small></span><div class="chips ar-days" data-f="days">' +
            DAYS.map((x, i) => '<button type="button" class="chip' + (d.indexOf(i + 1) >= 0 ? ' on' : '') + '" data-d="' + (i + 1) + '">' + x + '</button>').join('') + '</div></div>';
        }
        case 'place':
          return '<div class="ar-f ar-wide ar-place" data-f="place"><span>Sitio</span>' +
            '<input type="text" data-p="label" maxlength="30" placeholder="Nombre, p. ej. Casa" value="' + esc(o.label || '') + '">' +
            '<input type="hidden" data-p="lat" value="' + esc(o.lat != null ? o.lat : '') + '"><input type="hidden" data-p="lng" value="' + esc(o.lng != null ? o.lng : '') + '">' +
            '<div class="row2"><button type="button" class="btn btn-soft" data-here="car">' + icon('car') + 'Donde está el coche</button>' +
            '<button type="button" class="btn btn-soft" data-here="me">' + icon('pin') + 'Donde estoy yo</button></div>' +
            '<small class="hint" data-p="where">' + (o.lat != null ? 'Elegido: ' + (+o.lat).toFixed(5) + ', ' + (+o.lng).toFixed(5) : 'Aún sin elegir') + '</small>' +
            sel('radius', [[100, '100 m'], [200, '200 m'], [500, '500 m'], [1000, '1 km'], [2000, '2 km']], o.radius || 200, 'Alrededor') + '</div>';
        case 'text': return '<label class="ar-f ar-wide"><span>Texto del aviso <small>(opcional)</small></span><input type="text" data-f="text" maxlength="120" placeholder="Lo que quieres que diga" value="' + esc(o.text || '') + '"></label>';
        case 'actemp': return sel('temp', range(17, 33, 1).map(x => [x, x + '°']), o.temp, 'Temperatura');
        case 'minutes': return sel('minutes', [10, 15, 20, 30].map(x => [x, x + ' min']), o.minutes, 'Durante');
        case 'seconds': return sel('seconds', [[30, '30 s'], [60, '1 min'], [120, '2 min'], [300, '5 min']], o.seconds, 'Duración');
        case 'mode': return '<label class="ar-f"><span>Modo</span><select data-f="mode">' + Object.keys(MODES).map(k => opt(k, MODES[k], o.mode === k)).join('') + '</select></label>';
        case 'percent': return sel('percent', [60, 70, 80, 90, 100].map(x => [x, x === 100 ? 'Sin límite' : x + ' %']), o.percent, 'Límite');
        default: return '';
      }
    }).join('');
  }
  function readFields(el, out) {
    $$('[data-f]', el).forEach(f => {
      const k = f.dataset.f;
      if (k === 'days') { const d = $$('.chip.on', f).map(c => +c.dataset.d); out.days = d.length === 7 ? [] : d; return; }
      if (k === 'place') {
        const lat = parseFloat($('[data-p="lat"]', f).value), lng = parseFloat($('[data-p="lng"]', f).value);
        if (isFinite(lat) && isFinite(lng)) { out.lat = lat; out.lng = lng; } else { delete out.lat; delete out.lng; }
        out.label = $('[data-p="label"]', f).value.trim();
        return;
      }
      if (f.closest('[data-f="place"]') && k !== 'radius') return;
      out[k] = f.hasAttribute('data-num') ? +f.value : f.value.trim();
    });
    return out;
  }

  const visible = e => !e.cap || A.can(e.cap);
  function partHtml(kind, e, o) {
    const list = kind === 'only' ? ONLY : DO;
    const opts = list.filter(x => visible(x) || x === e).map(x => opt(x.k, x.t, x === e)).join('');
    return '<div class="ar-part" data-kind="' + kind + '"><div class="ar-part-top"><select data-pick>' + opts + '</select>' +
      '<button type="button" class="icon-btn" data-del title="Quitar">' + icon('x') + '</button></div>' +
      '<div class="ar-fields">' + fields(e.f, Object.assign({}, e.d, o)) + '</div>' + (e.warn ? '<p class="hint warn-txt">' + esc(e.warn) + '</p>' : '') + '</div>';
  }

  function editor(id, r) {
    const we = findWhen(r.when || {}) || WHEN[0];
    const onlyHtml = (r.only || []).map(c => { const e = findOnly(c); return e ? partHtml('only', e, c) : ''; }).join('');
    const doHtml = (r.do || []).map(a => { const e = findDo(a); return e ? partHtml('do', e, a.payload || {}) : ''; }).join('');
    const addOpts = (list, ph) => '<option value="">' + ph + '</option>' + list.filter(visible).map(x => opt(x.k, x.t)).join('');
    Sheet.open(
      '<div class="ar-editor"><h3>' + (id ? 'Editar automatización' : 'Nueva automatización') + '</h3>' +
      '<label class="ar-f ar-wide"><span>Nombre</span><input type="text" id="arName" maxlength="60" placeholder="Ponle un nombre" value="' + esc(r.name || '') + '"></label>' +
      '<p class="sheet-label ar-h">Cuando…</p>' +
      '<div class="ar-part" data-kind="when"><select data-pick id="arWhen">' + WHEN.map(x => opt(x.k, x.t, x === we)).join('') + '</select>' +
      '<div class="ar-fields">' + fields(we.f, Object.assign({}, we.d, r.when)) + '</div></div>' +
      '<p class="sheet-label ar-h">Solo si… <small>(opcional)</small></p><div id="arOnly">' + onlyHtml + '</div>' +
      '<select class="ar-add" data-add="only">' + addOpts(ONLY, '+ Añadir una condición') + '</select>' +
      '<p class="sheet-label ar-h">Haz…</p><div id="arDo">' + doHtml + '</div>' +
      '<select class="ar-add" data-add="do">' + addOpts(DO, '+ Añadir algo que hacer') + '</select>' +
      '<p class="hint">Ojo: en algunos coches (como el Dolphin Surf) el aire solo se enciende con el coche encendido.</p>' +
      '<label class="ar-f ar-wide"><span>No repetir antes de</span><select id="arCool">' + COOL.map(x => opt(x[0], x[1], x[0] === (r.cooldownMin || 5))).join('') + '</select></label>' +
      '<div class="actions"><button type="button" class="btn btn-primary btn-block" id="arSave">' + icon('check') + 'Guardar</button>' +
      (id ? '<button type="button" class="btn btn-soft btn-block" id="arTry">Probar ahora</button>' +
            '<button type="button" class="btn btn-danger-soft btn-block" id="arDelete">Borrar</button>' : '') +
      '<button type="button" class="btn btn-soft btn-block" data-close>Cancelar</button></div></div>',
      body => mountEditor($('.ar-editor', body), id, r));
  }

  // b = el editor (no #sheetBody, que se reutiliza entre hojas: ahí los eventos se acumularían)
  function mountEditor(b, id, r) {
    b.addEventListener('change', e => {
      const pick = e.target.closest('[data-pick]');
      if (pick) {
        const part = pick.closest('.ar-part'), kind = part.dataset.kind;
        const e2 = (kind === 'when' ? WHEN : kind === 'only' ? ONLY : DO).find(x => x.k === pick.value);
        if (kind === 'when') $('.ar-fields', part).innerHTML = fields(e2.f, Object.assign({}, e2.d));
        else part.outerHTML = partHtml(kind, e2, {});
        return;
      }
      const add = e.target.closest('[data-add]');
      if (add && add.value) {
        const kind = add.dataset.add, list = kind === 'only' ? ONLY : DO;
        const e2 = list.find(x => x.k === add.value);
        $(kind === 'only' ? '#arOnly' : '#arDo', b).insertAdjacentHTML('beforeend', partHtml(kind, e2, {}));
        add.value = '';
      }
    });
    b.addEventListener('click', e => {
      const del = e.target.closest('[data-del]');
      if (del) { del.closest('.ar-part').remove(); return; }
      const day = e.target.closest('.ar-days .chip');
      if (day) { day.classList.toggle('on'); return; }
      const here = e.target.closest('[data-here]');
      if (here) { pickPlace(here.closest('.ar-place'), here.dataset.here); return; }
    });
    $('#arSave', b).addEventListener('click', () => save(b, id, r));
    const tryB = $('#arTry', b);
    if (tryB) tryB.addEventListener('click', () => {
      command('runAutomation', { id }, { el: tryB, key: 'runAutomation', what: 'probarla' }).then(res => {
        if (!res.ok) return;
        const rs = (res.data && res.data.results) || [];
        const bad = rs.filter(x => !x.ok);
        toast(!rs.length ? 'Hecho' : bad.length ? (bad[0].label || bad[0].action) + ': ' + (bad[0].msg || 'no se ha podido') : 'Hecho: ' + rs.map(x => x.label).join(', '), bad.length ? 'err' : 'ok');
      });
    });
    const delB = $('#arDelete', b);
    if (delB) delB.addEventListener('click', () => {
      B.autoDelete(id).then(() => { Sheet.close(); toast('Automatización borrada', 'ok'); })
        .catch(err => toast('No se ha podido borrar: ' + ((err && err.message) || err), 'err'));
    });
  }

  function pickPlace(el, how) {
    const set = (lat, lng) => {
      $('[data-p="lat"]', el).value = lat; $('[data-p="lng"]', el).value = lng;
      $('[data-p="where"]', el).textContent = 'Elegido: ' + lat.toFixed(5) + ', ' + lng.toFixed(5);
    };
    if (how === 'car') {
      const L = st.s && st.s.location;
      if (!L) return toast('Aún no sé dónde está el coche.', 'err');
      set(L.lat, L.lng);
    } else {
      if (!navigator.geolocation) return toast('Este móvil no da su ubicación.', 'err');
      navigator.geolocation.getCurrentPosition(p => set(p.coords.latitude, p.coords.longitude),
        () => toast('No se ha podido saber dónde estás (¿permiso de ubicación?).', 'err'), { enableHighAccuracy: true, timeout: 15000 });
    }
  }

  function save(b, id, r) {
    const part = $('.ar-part[data-kind="when"]', b);
    const we = WHEN.find(x => x.k === $('#arWhen', b).value);
    const when = readFields(part, Object.assign({}, we.w));
    const only = $$('#arOnly .ar-part', b).map(p => { const e = ONLY.find(x => x.k === $('[data-pick]', p).value); return readFields(p, Object.assign({}, e.c)); });
    const acts = $$('#arDo .ar-part', b).map(p => {
      const e = DO.find(x => x.k === $('[data-pick]', p).value);
      return { action: e.a, payload: readFields(p, Object.assign({}, e.p || {})) };
    });
    const missing = [when].concat(only).find(o => o.type === 'place' && o.lat == null);
    if (missing) return toast('Elige el sitio («Donde está el coche» o «Donde estoy yo»).', 'err');
    if (!acts.length) return toast('Añade al menos una cosa que hacer.', 'err');
    const rule = {
      name: $('#arName', b).value.trim() || cap1(summary({ when, only, do: acts }).replace(/^Cuando /, '')).slice(0, 60),
      on: r.on !== false, when, only, do: acts, cooldownMin: +$('#arCool', b).value,
      created: r.created || Date.now(), updated: Date.now()
    };
    $('#arSave', b).disabled = true;
    B.autoSave(id, rule).then(() => {
      Sheet.close();
      toast(id ? 'Guardada' : 'Automatización creada: el coche la cumplirá en cuanto tenga conexión', 'ok');
    }).catch(err => { $('#arSave', b).disabled = false; toast('No se ha podido guardar: ' + ((err && err.message) || err), 'err'); });
  }

  // ───────── registro ─────────
  function showLog() {
    Sheet.open('<h3>Lo que han hecho</h3><p class="sub">Las últimas veces que ha saltado una automatización.</p><div class="list" id="arLog"><p class="hint">Cargando…</p></div>' +
      '<div class="actions"><button type="button" class="btn btn-soft btn-block" data-close>Cerrar</button></div>');
    B.autoLog(40).then(items => {
      const box = $('#arLog'); if (!box) return;
      if (!items.length) { box.innerHTML = '<div class="empty">' + icon('clock') + '<b>Aún no ha saltado ninguna</b>Aquí verás cada vez que el coche haga algo solo.</div>'; return; }
      box.innerHTML = items.map(l => {
        const rs = l.results || [];
        return '<div class="item"><span class="item-ico ' + (l.ok ? 'green' : 'red') + '">' + icon(l.ok ? 'check' : 'alert') + '</span>' +
          '<span class="item-main"><b>' + esc(l.name || 'Automatización') + '</b><small>' + esc(l.why || '') + (l.manual ? ' · prueba' : '') + '</small>' +
          rs.map(x => '<small class="ar-last ' + (x.ok ? 'ok' : 'bad') + '">' + (x.ok ? '✓ ' : '✗ ') + esc(x.label || x.action) + (x.msg && !x.ok ? ': ' + esc(x.msg) : x.msg ? ' · ' + esc(x.msg) : '') + '</small>').join('') +
          '</span><span class="item-end"><small>' + esc(ago(l.ts)) + '</small></span></div>';
      }).join('');
    }).catch(() => { const box = $('#arLog'); if (box) box.innerHTML = '<p class="hint">No se ha podido leer el registro.</p>'; });
  }

  // ───────── avisos en este móvil (Web Push que manda el coche) ─────────
  const pushOk = 'serviceWorker' in navigator && 'PushManager' in window && 'Notification' in window;
  const ios = /iPhone|iPad|iPod/.test(navigator.userAgent);
  const P = { id: null, sub: null, rec: null, busy: false };
  const lsKey = () => 'bl-push-' + (B.carId || 'x');
  const b64u = s => { const b = atob((s + '='.repeat((4 - s.length % 4) % 4)).replace(/-/g, '+').replace(/_/g, '/')); return Uint8Array.from(b, c => c.charCodeAt(0)); };
  function sameKey(sub, key) {
    const k = sub && sub.options && sub.options.applicationServerKey;
    if (!k || !key) return false;
    const a = new Uint8Array(k), b = b64u(key);
    return a.length === b.length && a.every((x, i) => x === b[i]);
  }
  async function subIdOf(endpoint) {
    const h = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(endpoint));
    return Array.from(new Uint8Array(h)).map(x => x.toString(16).padStart(2, '0')).join('').slice(0, 24);
  }
  function deviceName() {
    const u = navigator.userAgent;
    const os = /Android/.test(u) ? 'Android' : /iPhone|iPad|iPod/.test(u) ? 'iPhone' : /Windows/.test(u) ? 'Windows' : /Mac/.test(u) ? 'Mac' : 'Móvil';
    const br = /Edg\//.test(u) ? 'Edge' : /SamsungBrowser/.test(u) ? 'Samsung' : /Firefox|FxiOS/.test(u) ? 'Firefox' : /Chrome|CriOS/.test(u) ? 'Chrome' : /Safari/.test(u) ? 'Safari' : '';
    return os + (br ? ' · ' + br : '');
  }

  async function pushRefresh() {
    P.id = lsGet(lsKey()); P.sub = null; P.rec = null;
    if (pushOk && B.mode !== 'demo') {
      try { const reg = await navigator.serviceWorker.getRegistration(); P.sub = reg ? await reg.pushManager.getSubscription() : null; } catch (e) {}
    }
    if (P.id) { try { P.rec = await B.pushGet(P.id); } catch (e) {} }
    renderPush();
  }

  function renderPush() {
    const box = $('#pushBody'); if (!box) return;
    const active = P.id && P.rec && !P.rec.error && (B.mode === 'demo' || (P.sub && P.sub.endpoint === P.rec.endpoint));
    let html;
    if (!pushOk && B.mode !== 'demo') {
      html = '<p class="hint">' + (ios ? 'En iPhone los avisos solo funcionan con BYDLink en la pantalla de inicio: ábrela en Safari, pulsa Compartir → «Añadir a pantalla de inicio» y entra desde ese icono.'
        : 'Este navegador no puede recibir avisos. Prueba con Chrome, Edge, Firefox o Samsung Internet.') + '</p>';
    } else if (B.mode !== 'demo' && Notification.permission === 'denied') {
      html = '<p class="hint">Has bloqueado los avisos de esta web. Permítelos en los ajustes del navegador (candado junto a la dirección → Notificaciones) y vuelve aquí.</p>';
    } else if (active) {
      const r = P.rec;
      html = '<p class="hint ok-txt">' + icon('check') + 'Activados en este móvil' + (B.mode === 'demo' ? ' (demo)' : '') + '. Te llegan aunque tengas la web cerrada.</p>' +
        '<label class="row-switch"><span><b>Cuando el centinela vea algo</b><small>Movimiento cerca del coche aparcado.</small></span>' +
        '<span class="switch"><input type="checkbox" data-pk="sentry"' + (r.sentry !== false ? ' checked' : '') + '><span></span></span></label>' +
        '<label class="row-switch"><span><b>De mis automatizaciones</b><small>Las que tienen «Avisarme en el móvil».</small></span>' +
        '<span class="switch"><input type="checkbox" data-pk="autos"' + (r.autos !== false ? ' checked' : '') + '><span></span></span></label>' +
        '<div class="row2 push-acts"><button type="button" class="btn btn-soft" id="pushTest">Probar un aviso</button>' +
        '<button type="button" class="btn btn-danger-soft" id="pushOff">Quitar avisos</button></div>';
    } else {
      html = (P.rec && P.rec.error ? '<p class="warn-txt">Los avisos dejaron de llegar a este móvil: vuelve a activarlos.</p>' : '') +
        '<p class="hint">Recibe en este móvil los avisos del centinela y de tus automatizaciones, aunque no tengas la web abierta.</p>' +
        '<button type="button" class="btn btn-primary btn-block" id="pushOn">' + icon('sparkle') + 'Activar avisos en este móvil</button>' +
        (A.carHas(NEED) ? '' : '<p class="hint warn-txt">Necesita la app del coche ' + NEED + '.</p>');
    }
    box.innerHTML = html;
  }

  $('#pushCard').addEventListener('click', e => {
    if (e.target.closest('#pushOn')) enablePush(e.target.closest('#pushOn'));
    else if (e.target.closest('#pushTest')) command('pushTest', { sub: P.id }, { el: e.target.closest('#pushTest'), key: 'pushTest', ok: 'Aviso de prueba enviado: te llegará en unos segundos', what: 'mandar el aviso' })
      .then(res => { if (B.mode === 'demo' && res.ok) demoNotify(); });
    else if (e.target.closest('#pushOff')) disablePush();
  });
  $('#pushCard').addEventListener('change', e => {
    const t = e.target.closest('[data-pk]'); if (!t || !P.id) return;
    const v = {}; v[t.dataset.pk] = t.checked;
    B.pushUpdate(P.id, v).then(() => { Object.assign(P.rec, v); toast('Guardado', 'ok'); }).catch(() => { t.checked = !t.checked; toast('No se ha podido guardar.', 'err'); });
  });

  async function enablePush(btn) {
    if (P.busy) return;
    if (!A.carHas(NEED)) return toast('Para recibir avisos, actualiza la app del coche a la versión ' + NEED + '.', 'err');
    P.busy = true; if (btn) btn.classList.add('busy');
    try {
      if (B.mode === 'demo') {
        P.id = 'demo'; lsSet(lsKey(), 'demo');
        await B.pushSave('demo', { endpoint: 'demo', p256dh: 'demo', auth: 'demo', name: deviceName(), sentry: true, autos: true });
        P.rec = await B.pushGet('demo');
        toast('En la demo no se mandan avisos de verdad, pero así queda.', 'ok');
        return;
      }
      if (await Notification.requestPermission() !== 'granted') { toast('Sin tu permiso el móvil no puede mostrar avisos.', 'err'); return; }
      const key = await B.pushKey();
      if (!key) { toast('El coche aún no ha preparado los avisos: espera a que se conecte con la versión ' + NEED + '.', 'err'); return; }
      const reg = await navigator.serviceWorker.ready;
      let s = await reg.pushManager.getSubscription();
      if (s && !sameKey(s, key)) { await s.unsubscribe().catch(() => {}); s = null; }
      if (!s) s = await reg.pushManager.subscribe({ userVisibleOnly: true, applicationServerKey: b64u(key) });
      const j = s.toJSON();
      const id = await subIdOf(j.endpoint);
      await B.pushSave(id, { endpoint: j.endpoint, p256dh: j.keys.p256dh, auth: j.keys.auth, key, name: deviceName(), sentry: true, autos: true });
      lsSet(lsKey(), id);
      P.id = id; P.sub = s; P.rec = await B.pushGet(id);
      command('pushTest', { sub: id }, { key: 'pushTest', ok: 'Avisos activados: te hemos mandado uno de prueba', what: 'mandar el aviso de prueba' });
    } catch (err) {
      toast('No se han podido activar los avisos: ' + ((err && err.message) || err), 'err');
    } finally {
      P.busy = false; if (btn) btn.classList.remove('busy');
      renderPush();
    }
  }

  async function disablePush() {
    const id = P.id;
    try { if (P.sub) await P.sub.unsubscribe(); } catch (e) {}
    try { if (id) await B.pushRemove(id); } catch (e) {}
    lsSet(lsKey(), null);
    P.id = null; P.sub = null; P.rec = null;
    toast('Avisos quitados de este móvil', 'ok');
    renderPush();
  }

  function demoNotify() {
    if (!('serviceWorker' in navigator) || !('Notification' in window) || Notification.permission !== 'granted') return;
    navigator.serviceWorker.ready.then(reg => reg.showNotification('BYDLink', { body: 'Así te llegarán los avisos del coche.', icon: './icon.svg', tag: 'test' })).catch(() => {});
  }
})();
