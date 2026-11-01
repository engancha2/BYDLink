/* BYDLink · botones del volante: el coche aprende los botones que se pulsan (cars/{id}/keys/learned) y aquí se les asigna
 * una acción de BYDLink por tipo de pulsación (corta, larga, doble). Lo demás sigue haciendo lo de siempre.
 * Ver protocol/volante.md. Necesita la app del coche 2.27. */
(function () {
  'use strict';
  const A = window.BYDApp;
  if (!A) return;
  const { B, command, toast, Sheet, esc, ago } = A;
  const $ = (s, r) => (r || document).querySelector(s);
  const $$ = (s, r) => Array.from((r || document).querySelectorAll(s));
  const NEED = '2.27';
  // Las mismas acciones que el coche (keys/WheelKeys.ACTIONS), más "app:<paquete>".
  const ACTIONS = {
    'page:inicio': 'Abrir BYDLink', 'page:camara': 'Abrir la cámara', 'page:clima': 'Abrir el clima', 'page:energia': 'Abrir energía',
    'page:mapa': 'Abrir el mapa', 'page:musica': 'Abrir la música', 'page:coche': 'Abrir el coche',
    clip: 'Guardar vídeo (lo último + 1 min)', photo: 'Foto de la cámara', sentry: 'Centinela sí / no', climate: 'Aire sí / no',
    'media:next': 'Siguiente canción', 'media:prev': 'Canción anterior', 'media:playpause': 'Play / pausa'
  };
  const PRESS = { short: 'Corta', long: 'Larga', double: 'Doble' };
  const view = $('[data-view="settings"]');
  if (!view || !B.keysWatch) return;

  let k = null, started = false;
  const anchor = $('#powerCard', view);
  const html = '<div class="card" id="keysCard"><div class="vid-head"><h2 class="card-h">Botones del volante</h2>' +
    '<button type="button" class="link strong" id="keysSet">Configurar</button></div><p class="hint" id="keysTxt">Esperando al coche…</p></div>';
  if (anchor) anchor.insertAdjacentHTML('beforebegin', html); else view.insertAdjacentHTML('beforeend', html);

  function start() {
    if (started) return; started = true;
    B.keysWatch(v => { k = v || {}; render(); if ($('#keysSheet')) renderSheet(); });
  }
  A.onStatus(start);

  const conf = () => (k && k.config) || {};
  const stat = () => (k && k.status) || {};
  const learned = () => Object.values((k && k.learned) || {}).sort((a, b) => (b.last || 0) - (a.last || 0));
  const label = a => !a ? 'Lo normal' : ACTIONS[a] || (a.indexOf('app:') === 0 ? 'Abrir ' + appName(a.slice(4)) : a);
  function appName(pkg) { const x = ((k && k.apps) || []).find(p => p.pkg === pkg); return x ? x.label : pkg; }
  const learning = () => !!stat().learning && (!stat().learnUntil || stat().learnUntil > Date.now());

  function render() {
    const b = conf().bindings || {}, n = Object.keys(b).length;
    $('#keysTxt').innerHTML = !k || !k.status ? 'El coche aún no ha mandado sus botones (necesita la versión ' + NEED + ').' :
      (conf().enabled ? '<b>Activados</b>' : 'Desactivados') + ' · ' + (n ? n + (n === 1 ? ' botón asignado' : ' botones asignados') : 'ningún botón asignado') +
      (learning() ? ' · <b>aprendiendo</b>' : '') + (conf().enabled && !stat().service ? '<br><span class="warn-txt">El coche está activando el servicio de teclas…</span>' : '');
  }

  function options(cur) {
    let o = '<option value="">Lo normal</option>';
    Object.keys(ACTIONS).forEach(a => { o += '<option value="' + a + '"' + (a === cur ? ' selected' : '') + '>' + esc(ACTIONS[a]) + '</option>'; });
    const apps = (k && k.apps) || [];
    if (apps.length) {
      o += '<optgroup label="Abrir una app">';
      apps.forEach(p => { const v = 'app:' + p.pkg; o += '<option value="' + esc(v) + '"' + (v === cur ? ' selected' : '') + '>' + esc(p.label) + '</option>'; });
      o += '</optgroup>';
    } else if (cur && cur.indexOf('app:') === 0) o += '<option value="' + esc(cur) + '" selected>' + esc(label(cur)) + '</option>';
    return o;
  }

  function renderSheet() {
    const box = $('#keysSheet');
    if (!box) return;
    const b = conf().bindings || {};
    const keys = learned();
    const log = ((k && k.log) || []).slice(0, 8);
    const src = s => ({ android: 'tecla', sdk: 'SDK', 'sdk-voz': 'SDK', getevent: 'hardware', accion: 'acción' })[s] || s;
    $('#keysOn', box).checked = !!conf().enabled;
    $('#keysLearn', box).textContent = learning() ? 'Dejar de aprender' : 'Aprender botones (5 min)';
    $('#keysLearn', box).classList.toggle('btn-primary', !learning());
    $('#keysLearn', box).classList.toggle('btn-soft', learning());
    $('#keysState', box).innerHTML = learning()
      ? '<b>Aprendiendo:</b> pulsa en el coche los botones del volante uno a uno (corto y largo). Siguen haciendo lo de siempre.'
      : 'Servicio de teclas: <b>' + (stat().service ? 'activo' : conf().enabled ? 'activándose…' : 'apagado') + '</b> · eventos del coche: <b>' +
        (stat().sdk === 'ok' ? 'sí' : 'no') + '</b>';
    $('#keysLog', box).innerHTML = !log.length ? '' : '<p class="sub">Lo último que ha llegado</p>' + log.map(e =>
      '<div class="key-log' + (e.src === 'accion' ? ' act' : '') + '"><b>' + esc(e.name) + '</b> <small>' + esc(src(e.src)) + ' · ' + esc(e.info || '') + ' · ' + esc(ago(e.ts)) + '</small></div>').join('');
    $('#keysList', box).innerHTML = !keys.length
      ? '<div class="empty"><b>Aún no hay botones</b>Pulsa «Aprender botones» y luego, en el coche, los botones del volante.</div>'
      : keys.map(x => {
        const presses = String(x.id).charAt(0) === 'k' ? ['short', 'long', 'double'] : ['short', 'double'];
        return '<div class="key-row"><div class="key-name"><b>' + esc(x.name) + '</b><small>' + esc(x.id) + ' · ' + (x.count || 0) + ' veces</small></div>' +
          presses.map(p => '<label class="pw-opt key-press"><span>' + PRESS[p] + '</span><select data-key="' + esc(x.id) + '" data-press="' + p + '">' +
            options((b[x.id] || {})[p]) + '</select></label>').join('') + '</div>';
      }).join('');
    $$('select[data-key]', box).forEach(s => s.addEventListener('change', () =>
      command('setKeyConfig', { key: s.dataset.key, press: s.dataset.press, action: s.value || null },
        { what: 'guardar el botón', key: 'key-' + s.dataset.key + '-' + s.dataset.press, ok: s.value ? 'Listo: ' + label(s.value) : 'Ese botón vuelve a lo normal' })));
  }

  $('#keysSet').addEventListener('click', () => {
    if (!A.carHas(NEED)) return toast('Para usar los botones del volante, actualiza la app del coche a la versión ' + NEED + '.', 'err');
    start();
    Sheet.open('<h3>Botones del volante</h3>' +
      '<p class="sub">Asigna a un botón del volante algo de BYDLink. Lo que no asignes sigue igual que siempre.</p>' +
      '<div id="keysSheet">' +
      '<label class="row-switch"><span><b>Usar los botones del volante</b><small>BYDLink solo se queda con los botones que asignes.</small></span>' +
      '<span class="switch"><input type="checkbox" id="keysOn"><span></span></span></label>' +
      '<button type="button" class="btn btn-primary btn-block" id="keysLearn" style="margin-top:14px">Aprender botones (5 min)</button>' +
      '<p class="hint" id="keysState" style="margin-top:8px"></p>' +
      '<div id="keysList" class="list"></div><div id="keysLog"></div></div>' +
      '<div class="actions"><button type="button" class="btn btn-soft btn-block" data-close>Listo</button></div>',
      body => {
        renderSheet();
        $('#keysOn', body).addEventListener('change', e =>
          command('setKeyConfig', { enabled: e.target.checked }, { what: 'cambiar los botones', ok: e.target.checked ? 'Botones del volante activados' : 'Botones del volante desactivados' }));
        $('#keysLearn', body).addEventListener('click', () =>
          command('keyLearn', { on: !learning(), minutes: 5 }, { what: 'aprender botones', ok: learning() ? 'Ya no aprende' : 'Pulsa ahora los botones en el coche' }));
      });
  });
})();
