/* BYDLink · funciones extra del coche: antirrobo, batería, maletero, localizar, memorias de asiento
 * y datos ampliados. Se engancha a app.js (window.BYDApp) y usa el estado crudo que publica el coche
 * (ver protocol/telemetria.md). Lo que el coche no publica (null) no se enseña. */
(function () {
  'use strict';
  const A = window.BYDApp;
  if (!A) return;
  const { st, command, toast, Sheet, icon, esc, fmt, hhmm } = A;
  // Mandos nuevos: solo con la app del coche 2.2 o más nueva (si no, avisa en vez de fingir que funciona).
  const NEED = '2.2';
  const needs = fn => function () {
    if (!A.carHas(NEED)) return toast('Para esto, actualiza la app del coche a la versión ' + NEED + '.', 'err');
    return fn.apply(this, arguments);
  };
  const $ = (s, r) => (r || document).querySelector(s);
  const $$ = (s, r) => Array.from((r || document).querySelectorAll(s));
  const has = v => v !== null && v !== undefined;
  const DOOR = { fl: 'delantera izquierda', fr: 'delantera derecha', rl: 'trasera izquierda', rr: 'trasera derecha', trunk: 'del maletero', hood: 'del capó' };
  const POWER = { OFF: 'Apagado', ACC: 'Accesorios', ON: 'Encendido', OK: 'Listo para circular', FAKE_OK: 'Listo' };
  const BMS = { 0: 'Lista', 1: 'Cargando', 2: 'Carga terminada', 3: 'Descargando', 9: 'Carga programada', 15: 'En reposo' };
  const GUN = { none: 'Sin cargador', AC: 'Cargador AC', DC: 'Cargador rápido DC', AC_DC: 'AC + DC', V2L: 'V2L (dando corriente)' };
  let raw = {}, prev = null;

  // ───────── inicio: dos mandos más ─────────
  const ctl = (id, ico, k) => '<button type="button" class="ctl" id="' + id + '"><span class="ctl-ico">' + icon(ico) +
    '</span><span class="ctl-k">' + k + '</span><span class="ctl-v" id="' + id + 'V">—</span></button>';
  $('#controls').insertAdjacentHTML('beforeend', ctl('ctlTrunk', 'car', 'Maletero') + ctl('ctlFind', 'pin', 'Localizar'));
  $('#ctlFindV').textContent = 'Claxon y luces';
  $('#ctlFind').dataset.cap = 'locate';           // multimodelo: el Surf no deja claxon ni emergencias

  $('#ctlTrunk').addEventListener('click', needs(() => {
    const open = raw.doors && raw.doors.trunk === true;
    if (open) return command('closeTrunk', {}, { el: $('#ctlTrunk'), ok: 'Cerrando el maletero', what: 'cerrar el maletero' }).then(applied);
    Sheet.open('<h3>¿Abrir el maletero?</h3><p class="sub">Solo con el coche parado.</p><div class="actions">' +
      '<button class="btn btn-primary btn-block" data-ok>Abrir maletero</button><button class="btn btn-soft btn-block" data-close>Cancelar</button></div>',
      b => $('[data-ok]', b).addEventListener('click', () => {
        Sheet.close();
        command('openTrunk', {}, { el: $('#ctlTrunk'), ok: 'Abriendo el maletero', what: 'abrir el maletero' }).then(applied);
      }));
  }));
  $('#ctlFind').addEventListener('click', needs(locateSheet));

  function locateSheet() {
    Sheet.open('<h3>Localizar el coche</h3><p class="sub">Para encontrarlo en un aparcamiento o espantar a alguien que se acerca.</p><div class="actions">' +
      '<button class="btn btn-primary btn-block" data-whistle>' + icon('alert') + 'Claxon y luces</button>' +
      '<button class="btn btn-soft btn-block" data-flash>' + icon('sparkle') + 'Solo luces</button></div>',
      b => {
        $('[data-whistle]', b).addEventListener('click', () => { Sheet.close(); command('locateCar', { whistle: true }, { ok: 'Pitando y encendiendo luces', what: 'localizar el coche' }).then(applied); });
        $('[data-flash]', b).addEventListener('click', () => { Sheet.close(); command('flashLights', {}, { ok: 'Encendiendo las luces', what: 'encender las luces' }).then(applied); });
      });
  }
  function applied(r) {
    if (r && r.ok && r.data && r.data.applied === false) toast(r.data.error || 'El coche ha recibido la orden pero no confirma que lo haya hecho.', 'err');
    return r;
  }

  // ───────── inicio: más datos ─────────
  const facts = $('dl.facts');
  const fact = (id, k) => '<div data-x="' + id + '" hidden><dt>' + k + '</dt><dd id="' + id + '">—</dd></div>';
  if (facts) facts.insertAdjacentHTML('beforeend',
    fact('xPower', 'Estado') + fact('x12v', 'Batería 12 V') + fact('xSoh', 'Salud batería') + fact('xBattT', 'Temp. batería') + fact('xIn', 'Temp. interior') +
    fact('xKwh', 'Energía restante') + fact('xTrip', 'Último viaje') + fact('xTotal', 'Energía gastada') + fact('xBrake', 'Freno de mano'));
  const setFact = (id, v) => { const row = $('[data-x="' + id + '"]'); if (!row) return; row.hidden = !has(v); if (has(v)) $('#' + id).textContent = v; };

  // ───────── asientos: memorias ─────────
  const seatCard = $('.seat-seg[data-seat="passenger"]') && $('.seat-seg[data-seat="passenger"]').closest('.card');
  if (seatCard) {
    seatCard.insertAdjacentHTML('beforeend', '<div class="seat-row" data-cap="seatMemory"><span class="seat-name">Memoria</span><div class="seg mem-seg">' +
      [1, 2, 3].map(n => '<button type="button" data-mem="' + n + '">' + n + '</button>').join('') + '</div></div>' +
      '<p class="hint" data-cap="seatMemory">Recupera una posición guardada en el coche (solo parado). La posición exacta no se puede mover desde fuera: BYD no lo permite.</p>');
    $$('[data-mem]', seatCard).forEach(b => b.addEventListener('click', needs(() =>
      command('loadSeatMemory', { slot: +b.dataset.mem }, { el: b, ok: 'Recuperando la memoria ' + b.dataset.mem, what: 'recuperar la memoria del asiento' }).then(applied))));
  }

  // ───────── centinela: antirrobo ─────────
  const sentryView = $('.view[data-view="sentry"]');
  sentryView.insertAdjacentHTML('afterbegin',
    '<div class="card" id="theftCard"><h2 class="card-h">Antirrobo</h2>' +
    '<div class="theft-state" id="theftState"></div>' +
    '<div class="theft-acts">' +
    '<button type="button" class="btn btn-soft" id="tLocate" data-cap="locate">' + icon('pin') + 'Localizar</button>' +
    '<button type="button" class="btn btn-soft" id="tLock">' + icon('lock') + 'Cerrar el coche</button></div>' +
    '<button type="button" class="btn btn-danger-soft btn-block" id="tOff">' + icon('power') + 'Apagar el coche</button>' +
    '<p class="hint">Apagar solo funciona con el coche parado y en P: nunca se apaga un coche en marcha. Si te lo roban, localízalo en el mapa y avisa a la policía.</p></div>');
  $('#tLocate').addEventListener('click', needs(locateSheet));
  $('#tLock').addEventListener('click', () => command('setLock', { locked: true }, { el: $('#tLock'), ok: 'Cerrando el coche', what: 'cerrar el coche' }).then(applied));
  $('#tOff').addEventListener('click', needs(() => {
    const moving = (raw.speed || 0) > 0 || (raw.gear && raw.gear !== 'P');
    if (moving) return toast('No se puede apagar: el coche está en marcha. Por seguridad solo se apaga parado y en P.', 'err');
    Sheet.open('<h3>Apagar el coche</h3><p class="sub">Solo funciona con el coche parado y en P. Escribe <b>APAGAR</b> para confirmar.</p>' +
      '<input type="text" id="offTxt" autocomplete="off" autocapitalize="characters" placeholder="APAGAR" class="sheet-input">' +
      '<div class="actions"><button class="btn btn-danger-soft btn-block" data-ok disabled>Apagar ahora</button><button class="btn btn-soft btn-block" data-close>Cancelar</button></div>',
      b => {
        const inp = $('#offTxt', b), ok = $('[data-ok]', b);
        inp.addEventListener('input', () => { ok.disabled = inp.value.trim().toUpperCase() !== 'APAGAR'; });
        ok.addEventListener('click', () => {
          Sheet.close();
          command('powerOff', { confirm: 'APAGAR' }, { el: $('#tOff'), ok: 'Orden de apagado enviada', what: 'apagar el coche' }).then(applied);
        });
        setTimeout(() => inp.focus(), 300);
      });
  }));

  function renderTheft() {
    const parts = [];
    // raw.alarm: pendiente de calibrar en el coche (con el coche encendido también vale 1), no se enseña aún.
    if (raw.locked === true) parts.push('<span class="pill ok">' + icon('lock') + 'Cerrado</span>');
    if (raw.locked === false) parts.push('<span class="pill warn">' + icon('unlock') + 'Abierto</span>');
    const open = raw.doors ? Object.keys(DOOR).filter(k => raw.doors[k] === true) : [];
    if (open.length) parts.push('<span class="pill warn">Puerta ' + esc(open.map(k => DOOR[k]).join(', ')) + ' abierta</span>');
    if (has(raw.powerState)) parts.push('<span class="pill">' + esc(POWER[raw.powerState] || raw.powerState) + '</span>');
    $('#theftState').innerHTML = parts.join('') || '<span class="pill">Sin datos todavía</span>';
  }

  // Avisos mientras la app está abierta: alarma, o abren una puerta con el coche cerrado.
  function alerts(cur) {
    if (!prev) return;
    if (prev.locked === true && cur.doors && prev.doors) {
      const k = Object.keys(DOOR).find(d => cur.doors[d] === true && prev.doors[d] !== true);
      if (k) { buzz(); toast('Se ha abierto la puerta ' + DOOR[k] + ' con el coche cerrado', 'err'); }
    }
  }
  function buzz() { try { if (navigator.vibrate) navigator.vibrate([300, 120, 300]); } catch (e) {} }

  // ───────── carga: tarjeta de batería ─────────
  const chargeView = $('.view[data-view="charge"]');
  const cn = $('#chargeNow');
  (cn || chargeView.firstElementChild).insertAdjacentHTML('afterend',
    '<div class="card" id="battCard"><h2 class="card-h">Batería</h2><dl class="kv" id="battKv"></dl>' +
    '<div id="limitBox" data-cap="chargeLimit"><p class="sheet-label">Límite de carga: <b id="limitOut">—</b></p>' +
    '<div class="seg" id="limitSeg">' + [60, 70, 80, 90, 100].map(p => '<button type="button" data-limit="' + p + '">' + p + ' %</button>').join('') + '</div>' +
    '<p class="hint" id="limitHint" hidden>El coche no confirma el límite: se comprobará en la próxima carga.</p></div>' +
    '<button type="button" class="btn btn-soft btn-block" id="preheatBtn">' + icon('heat') + 'Precalentar la batería</button>' +
    '<p class="hint">Precalentar ayuda a cargar más rápido en frío.</p></div>');
  $('#preheatBtn').addEventListener('click', needs(() => command('batteryPreheat', { on: true }, { el: $('#preheatBtn'), ok: 'Precalentando la batería', what: 'precalentar la batería' }).then(applied)));
  // Las mismas opciones que los Ajustes de BYD: 60/70/80/90 % o sin límite (100 %).
  $$('[data-limit]').forEach(b => b.addEventListener('click', needs(() => {
    const p = +b.dataset.limit;
    command('setChargeLimit', { percent: p }, { el: b, ok: p === 100 ? 'Sin límite de carga' : 'Límite de carga al ' + p + ' %', what: 'cambiar el límite' })
      .then(r => { if (r && r.ok && r.data && r.data.confirmed === false) toast('Enviado. El coche no confirma el límite; se verá en la próxima carga.'); return applied(r); });
  })));

  function renderBattery() {
    const rows = [];
    const row = (k, v) => { if (has(v)) rows.push('<div><dt>' + k + '</dt><dd>' + esc(v) + '</dd></div>'); };
    row('Estado', has(raw.bmsState) ? (BMS[raw.bmsState] || 'Código ' + raw.bmsState) : null);
    row('Cargador', has(raw.chargingGun) ? (GUN[raw.chargingGun] || raw.chargingGun) : null);
    row('Salud (SOH)', has(raw.soh) ? raw.soh + ' %' : null);
    row('Temperatura celdas', has(raw.batteryTempMin) && has(raw.batteryTempMax) ? raw.batteryTempMin + '–' + raw.batteryTempMax + ' °C' : null);
    row('Batería 12 V', typeof raw.aux12v === 'number' ? fmt(raw.aux12v, 1) + ' V' : null);
    $('#battKv').innerHTML = rows.join('') || '<div><dt>Datos</dt><dd>El coche aún no los ha enviado</dd></div>';
    const lim = raw.chargeLimit;
    $('#limitOut').textContent = has(lim) ? (lim === 100 ? 'sin límite' : lim + ' %') : 'sin datos';
    $$('[data-limit]').forEach(b => b.classList.toggle('active', +b.dataset.limit === lim));
    $('#limitHint').hidden = !(has(lim) && raw.chargeLimitConfirmed === false);
  }

  // ───────── neumáticos con aviso ─────────
  function renderTyres() {
    const ts = raw.tyresState || {}, tt = raw.tyreTemps || {};
    ['fl', 'fr', 'rl', 'rr'].forEach(k => {
      const el = $('.tp-' + k); if (!el) return;
      el.classList.toggle('alert', ts[k] === 1 || ts[k] === 2);
      el.title = ts[k] === 2 ? 'Presión baja' : ts[k] === 1 ? 'Presión alta' : '';
      // temperatura de la rueda (si el coche la da), debajo de la presión
      let t = $('.tp-t', el);
      if (!t) { t = document.createElement('small'); t.className = 'tp-t'; t.style.cssText = 'display:block;font-size:.72em;opacity:.7'; el.appendChild(t); }
      t.textContent = has(tt[k]) ? tt[k] + '°' : '';
    });
  }

  A.onStatus(v => {
    raw = v || {};
    $('#ctlTrunkV').textContent = raw.doors && has(raw.doors.trunk) ? (raw.doors.trunk ? 'Abierto' : 'Cerrado') : '—';
    setFact('xPower', has(raw.powerState) ? (POWER[raw.powerState] || raw.powerState) : null);
    setFact('x12v', typeof raw.aux12v === 'number' ? fmt(raw.aux12v, 1) + ' V' : null);
    setFact('xSoh', has(raw.soh) ? raw.soh + ' %' : null);
    setFact('xBattT', has(raw.batteryTempMax) ? (has(raw.batteryTempMin) ? raw.batteryTempMin + '–' : '') + raw.batteryTempMax + ' °C' : null);
    setFact('xIn', has(raw.inTemp) ? raw.inTemp + ' °C' : null);
    setFact('xKwh', typeof raw.energyLeftKwh === 'number' ? fmt(raw.energyLeftKwh, 1) + ' kWh' : null);
    setFact('xTrip', typeof raw.lastTripKwh100 === 'number' ? fmt(raw.lastTripKwh100, 1) + ' kWh/100 km' : null);
    setFact('xTotal', typeof raw.totalEnergyKwh === 'number' ? fmt(Math.round(raw.totalEnergyKwh), 0) + ' kWh' : null);
    setFact('xBrake', typeof raw.parkBrake === 'boolean' ? (raw.parkBrake ? 'Echado' : 'Quitado') : null);
    renderTheft(); renderBattery(); renderTyres();
    alerts(raw);
    prev = JSON.parse(JSON.stringify(raw));
  });
  if (A.applyCaps) A.applyCaps();   // lo creado aquí también se oculta según el modelo
  void st;
})();
