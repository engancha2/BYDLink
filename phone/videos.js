/* BYDLink · vídeos del coche: grabar a mano, grabación continua (dashcam), dónde se guardan (memoria del coche,
 * tarjeta SD o USB) y ver en el móvil los vídeos guardados. El coche los manda por la nube en trozos (cars/{id}/videoDl)
 * y aquí se juntan en un vídeo que se puede ver y guardar. Necesita la app del coche 2.25. */
(function () {
  'use strict';
  const A = window.BYDApp;
  if (!A) return;
  const { B, command, toast, Sheet, icon, esc, hhmm } = A;
  const $ = (s, r) => (r || document).querySelector(s);
  const $$ = (s, r) => Array.from((r || document).querySelectorAll(s));
  const NEED = '2.25';
  const KIND = { centinela: ['Centinela', 'red'], dashcam: ['Dashcam', 'blue'], manual: ['Grabación', 'green'] };
  const MODE = {
    off: ['Apagada', 'El centinela sigue guardando sus clips cuando ve movimiento.'],
    parked: ['Aparcado', 'Graba sin parar con el coche aparcado, en tramos de unos minutos.'],
    drive: ['En marcha', 'En pruebas: graba en marcha por encima de 15 km/h y suelta la cámara por debajo de 8 (nunca con la R).'],
    always: ['Siempre', 'Aparcado y en marcha (en marcha, en pruebas: solo por encima de 15 km/h).']
  };
  const MAX_SEND = 60 * 1048576;
  const view = $('[data-view="cam"]');
  if (!view || !B.videoList) return;               // la demo no tiene vídeos

  const size = b => !b ? '0 MB' : b >= 1073741824 ? (b / 1073741824).toFixed(1).replace('.', ',') + ' GB' : Math.max(1, Math.round(b / 1048576)) + ' MB';
  const day = ts => new Date(ts).toLocaleDateString('es-ES', { weekday: 'long', day: 'numeric', month: 'long' });
  const needs = () => { if (A.carHas(NEED)) return true; toast('Para grabar vídeos, actualiza la app del coche a la versión ' + NEED + '.', 'err'); return false; };

  // ───────── botones de grabar (debajo de la cámara) y tarjeta de vídeos ─────────
  $('.cam-actions', view).insertAdjacentHTML('afterend',
    '<div class="row2 rec-actions">' +
    '<button type="button" id="rec1" class="btn btn-soft"><span class="rec-dot"></span>Grabar 1 min</button>' +
    '<button type="button" id="recLong" class="btn btn-soft">Grabar hasta parar</button></div>' +
    '<p class="hint center rec-txt" id="recTxt" hidden></p>');
  view.insertAdjacentHTML('beforeend',
    '<div class="card" id="vidCard">' +
    '<div class="vid-head"><h2 class="card-h">Vídeos del coche</h2><button type="button" class="link strong" id="vidSet">Ajustes de grabación</button></div>' +
    '<p class="hint" id="vidWhere">Esperando al coche…</p>' +
    '<div id="vidList" class="list"></div>' +
    '<button type="button" class="btn btn-soft btn-block" id="vidMore" hidden>Ver más</button></div>');

  let vs = null, items = [], shown = 25, started = false, recTimer = null;

  function start() {
    if (started) return; started = true;
    B.videoStatus(v => { vs = v; renderStatus(); });
    B.videoList(v => { items = (v && v.items) || []; renderList(); });
  }
  A.onStatus(start);
  document.addEventListener('click', e => { if (e.target.closest('[data-tab="cam"], a[href="#cam"]')) start(); }, true);

  function manualRec() { return ((vs && vs.recording) || []).find(r => r.kind === 'manual'); }

  function renderStatus() {
    const vols = (vs && vs.volumes) || [];
    const cur = vols.find(x => x.id === (vs && vs.current));
    const mode = (vs && vs.mode) || 'off';
    $('#vidWhere').innerHTML = !vs ? 'El coche aún no ha mandado sus vídeos (necesita la versión ' + NEED + ').' :
      'Se guardan en <b>' + esc(cur ? cur.label : 'la memoria del coche') + '</b>' + (cur ? ' · ' + size(cur.free) + ' libres' : '') +
      (vs.missing ? ' <span class="warn-txt">(el soporte elegido no está conectado)</span>' : '') +
      '<br>Grabación continua: <b>' + esc(MODE[mode] ? MODE[mode][0] : mode) + '</b>' +
      (vs.dashcam && vs.dashcam.recording ? ' · grabando ahora' : '');
    const m = manualRec();
    $('#recLong').innerHTML = m ? icon('x') + 'Parar y guardar' : 'Grabar hasta parar';
    $('#recLong').classList.toggle('btn-danger-soft', !!m);
    $('#recLong').classList.toggle('btn-soft', !m);
    clearInterval(recTimer);
    const tick = () => {
      const r = manualRec() || ((vs && vs.recording) || [])[0];
      const t = $('#recTxt');
      if (!r) { t.hidden = true; return; }
      const s = Math.max(0, Math.round((Date.now() - r.since) / 1000));
      t.hidden = false;
      t.innerHTML = '<span class="rec-dot on"></span>' + esc(KIND[r.kind] ? KIND[r.kind][0] : 'Grabando') + ' · ' + Math.floor(s / 60) + ':' + String(s % 60).padStart(2, '0') +
        (cur ? ' · en ' + esc(cur.label) : '');
    };
    tick();
    if ((vs && vs.recording || []).length) recTimer = setInterval(tick, 1000);
  }

  function renderList() {
    const box = $('#vidList');
    if (!items.length) {
      box.innerHTML = '<div class="empty">' + icon('video') + '<b>Aún no hay vídeos</b>Graba uno con los botones de arriba o activa la grabación continua.</div>';
      $('#vidMore').hidden = true; return;
    }
    let html = '', d = '';
    items.slice(0, shown).forEach(v => {
      const dl = day(v.ts);
      if (dl !== d) { d = dl; html += '<div class="day">' + esc(dl) + '</div>'; }
      const k = KIND[v.kind] || ['Vídeo', 'green'];
      html += '<div class="item tappable" data-vidid="' + esc(v.id) + '"><span class="item-ico ' + k[1] + '">' + icon(v.kind === 'centinela' ? 'eye' : 'video') + '</span>' +
        '<span class="item-main"><b>' + k[0] + '</b><small>' + size(v.bytes) + (v.volume && v.volume !== 'internal' ? ' · ' + esc(volName(v.volume)) : '') + '</small></span>' +
        '<span class="item-end">' + hhmm(v.ts) + '</span></div>';
    });
    box.innerHTML = html;
    $('#vidMore').hidden = items.length <= shown;
  }
  const volName = id => { const v = ((vs && vs.volumes) || []).find(x => x.id === id); return v ? v.label : 'SD/USB'; };
  $('#vidMore').addEventListener('click', () => { shown += 25; renderList(); });

  // ───────── grabar ─────────
  $('#rec1').addEventListener('click', () => {
    if (!needs()) return;
    command('recordVideo', { seconds: 60 }, { el: $('#rec1'), ok: 'Grabando 1 minuto en el coche', what: 'grabar' });
  });
  $('#recLong').addEventListener('click', () => {
    if (!needs()) return;
    if (manualRec()) command('stopVideo', {}, { el: $('#recLong'), ok: 'Vídeo guardado en el coche', what: 'parar la grabación' });
    else command('recordVideo', { seconds: 0 }, { el: $('#recLong'), ok: 'Grabando. Pulsa «Parar y guardar» para terminar (máx. 30 min).', what: 'grabar' });
  });

  // ───────── un vídeo: ver, guardar, borrar ─────────
  $('#vidList').addEventListener('click', e => {
    const it = e.target.closest('[data-vidid]'); if (!it) return;
    const v = items.find(x => x.id === it.dataset.vidid); if (!v) return;
    const k = KIND[v.kind] || ['Vídeo'];
    const big = v.bytes > MAX_SEND;
    Sheet.open('<h3>' + k[0] + '</h3><p class="sub">' + esc(day(v.ts)) + ' · ' + hhmm(v.ts) + ' · ' + size(v.bytes) + ' · ' + esc(volName(v.volume || 'internal')) + '</p>' +
      '<div class="actions">' +
      (big ? '<p class="hint">Es muy grande para mandarlo por datos (máx. 60 MB): míralo en la pantalla del coche (página Cámara) o copia el archivo.</p>'
           : '<button type="button" class="btn btn-primary btn-block" data-video="' + esc(v.id) + '">' + icon('video') + 'Ver en el móvil</button>' +
             '<p class="hint center">Usa unos ' + size(v.bytes * 1.37) + ' de datos del coche y del móvil.</p>') +
      (v.kind !== 'manual' ? '<button type="button" class="btn btn-soft btn-block" data-keep>' + icon('check') + 'Guardar para siempre</button>' : '') +
      '<button type="button" class="btn btn-danger-soft btn-block" data-del>Borrar del coche</button>' +
      '<button type="button" class="btn btn-soft btn-block" data-close>Cerrar</button></div>',
      b => {
        const keep = $('[data-keep]', b);
        if (keep) keep.addEventListener('click', () => { Sheet.close(); command('keepVideo', { id: v.id }, { ok: 'Guardado en «Grabaciones»: no se borrará solo', what: 'guardarlo' }); });
        $('[data-del]', b).addEventListener('click', () => { Sheet.close(); command('deleteVideo', { id: v.id }, { ok: 'Vídeo borrado', what: 'borrarlo' }); });
      });
  });

  // Ver: el coche sube el vídeo a trozos y aquí se van bajando mientras tanto.
  let dl = null;
  document.addEventListener('click', e => {
    const b = e.target.closest('[data-video]'); if (!b) return;
    e.preventDefault();
    if (!needs()) return;
    play(b.dataset.video);
  });

  function play(id) {
    start();
    if (dl) { dl.cancel(); }
    const req = 'p' + Date.now().toString(36);
    const v = items.find(x => x.id === id);
    Sheet.open('<h3>' + esc(v ? (KIND[v.kind] || ['Vídeo'])[0] + ' · ' + hhmm(v.ts) : 'Vídeo del coche') + '</h3>' +
      '<p class="sub" id="dlTxt">Pidiendo el vídeo al coche…</p>' +
      '<div class="vid-prog"><i id="dlBar"></i></div><div id="dlBox"></div>' +
      '<div class="actions"><button type="button" class="btn btn-soft btn-block" data-close id="dlClose">Cerrar</button></div>');
    const me = { req, parts: [], next: 0, n: 0, done: 0, busy: false, over: false, url: null };
    dl = me;
    const txt = t => { const el = $('#dlTxt'); if (el && dl === me) el.textContent = t; };
    const bar = f => { const el = $('#dlBar'); if (el && dl === me) el.style.width = Math.round(f * 100) + '%'; };
    let unwatch = null;
    me.cancel = () => {
      me.over = true; if (unwatch) unwatch();
      if (dl === me) dl = null;
      if (me.url) setTimeout(() => URL.revokeObjectURL(me.url), 60000);
    };
    // Si se cierra la hoja, se deja de bajar (el coche lo borra solo de la nube a los 15 min).
    const closer = setInterval(() => {
      if ($('#dlBox')) return;
      clearInterval(closer);
      if (me.url) setTimeout(() => URL.revokeObjectURL(me.url), 2000); else me.cancel();
    }, 700);
    command('getVideo', { id, req }, { what: 'pedir el vídeo' }).then(r => {
      if (me.over) return;
      if (!r.ok) { txt(r.error && r.error !== 'timeout' ? r.error : 'El coche no ha contestado.'); me.cancel(); return; }
      me.n = (r.data && r.data.parts) || 0;
      txt('El coche está mandando el vídeo (' + size(r.data && r.data.bytes) + ')…');
      unwatch = B.videoDlWatch(s => {
        if (me.over || s.req !== req) return;
        if (s.state === 'error') { txt(s.error || 'No se pudo mandar el vídeo.'); me.cancel(); return; }
        if (s.n) me.n = s.n;
        me.done = s.done || 0;
        pump();
      });
    });
    function pump() {
      if (me.busy || me.over) return;
      if (me.next >= me.n && me.n > 0) return finish();
      if (me.next >= me.done) return;
      me.busy = true;
      B.videoDlPart(me.next).then(p => {
        me.busy = false;
        if (me.over) return;
        if (!p) { setTimeout(pump, 800); return; }
        me.parts[me.next] = p; me.next++;
        bar(me.next / me.n);
        txt('Bajando el vídeo… ' + Math.round(me.next * 100 / me.n) + ' %');
        pump();
      }, () => { me.busy = false; setTimeout(pump, 1500); });
    }
    function finish() {
      if (me.over) return;
      me.over = true; if (unwatch) unwatch();
      const chunks = me.parts.map(b64 => { const s = atob(b64), a = new Uint8Array(s.length); for (let i = 0; i < s.length; i++) a[i] = s.charCodeAt(i); return a; });
      const blob = new Blob(chunks, { type: 'video/mp4' });
      me.url = URL.createObjectURL(blob);
      B.videoDlClear();
      txt('Vídeo del coche · ' + size(blob.size));
      bar(1);
      const box = $('#dlBox');
      if (!box) return;
      const name = 'BYDLink-' + id.replace(/[\/]/g, '-') + '.mp4';
      box.innerHTML = '<video class="vid-player" controls playsinline autoplay muted src="' + me.url + '"></video>' +
        '<a class="btn btn-primary btn-block" download="' + esc(name) + '" href="' + me.url + '">' + icon('download') + 'Guardar en el móvil</a>';
      if (dl === me) dl = null;
    }
  }

  // ───────── ajustes: dónde guardar, grabación continua, calidad, espacio ─────────
  $('#vidSet').addEventListener('click', () => {
    if (!needs()) return;
    start();
    if (!vs) return toast('El coche aún no ha mandado sus ajustes de vídeo.', 'err');
    const vols = vs.volumes || [];
    const opt = (k, v, on, label) => '<button type="button" class="chip' + (on ? ' on' : '') + '" data-k="' + k + '" data-v="' + esc(String(v)) + '">' + label + '</button>';
    const mode = vs.mode || 'off';
    Sheet.open('<h3>Ajustes de grabación</h3>' +
      '<p class="sub">Dónde guarda el coche los vídeos</p><div class="chips vid-chips">' +
      vols.map(x => opt('store', x.id, x.id === vs.current, esc(x.label) + ' · ' + size(x.free) + ' libres')).join('') + '</div>' +
      (vols.length <= 1 ? '<p class="hint">Conecta una tarjeta SD o un USB al coche y saldrá aquí.</p>' : '') +
      '<p class="hint">' + (vols.find(x => x.id === vs.current && x.lasting === false) ? 'En la SD o el USB van a la carpeta de la app (Android/data/com.bydlink.carapp/files/BYDLink): se borran si desinstalas BYDLink, no al actualizar.' : 'Carpeta BYDLink del soporte elegido.') + '</p>' +
      '<p class="sub">Grabación continua (dashcam)</p><div class="chips vid-chips">' +
      Object.keys(MODE).map(k => opt('mode', k, k === mode, MODE[k][0])).join('') + '</div><p class="hint" id="modeHint">' + esc((MODE[mode] || MODE.off)[1]) + '</p>' +
      '<p class="sub">Calidad</p><div class="chips vid-chips">' +
      opt('quality', 'normal', vs.quality !== 'alta', 'Normal · 640 px · ~10 MB/min') + opt('quality', 'alta', vs.quality === 'alta', 'Alta · 768 px · ~19 MB/min') + '</div>' +
      '<p class="sub">Espacio máximo para vídeos</p><div class="chips vid-chips">' +
      [0, 4, 8, 16, 32, 64].map(g => opt('quotaGb', g, (vs.quotaGb || 0) === g, g ? g + ' GB' : 'Auto')).join('') + '</div>' +
      '<p class="hint">Al llenarse se borra lo más viejo: primero la dashcam, luego el centinela y lo último tus grabaciones.</p>' +
      '<p class="sub">Fecha, hora y velocidad en el vídeo</p><div class="chips vid-chips">' +
      opt('stamp', 'true', vs.stamp !== false, 'Sí') + opt('stamp', 'false', vs.stamp === false, 'No') + '</div>' +
      '<div class="actions"><button type="button" class="btn btn-soft btn-block" data-close>Listo</button></div>',
      b => $$('[data-k]', b).forEach(c => c.addEventListener('click', () => {
        const k = c.dataset.k; let v = c.dataset.v;
        if (k === 'quotaGb') v = +v; if (k === 'stamp') v = v === 'true';
        $$('[data-k="' + k + '"]', b).forEach(x => x.classList.toggle('on', x === c));
        if (k === 'mode') { const h = $('#modeHint', b); if (h) h.textContent = MODE[v][1]; }
        command('setVideoConfig', { [k]: v }, { what: 'cambiar el ajuste', key: 'vid-' + k });
      })));
  });
})();
