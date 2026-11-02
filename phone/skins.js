/* BYDLink · Apariencia de la app del móvil: el estilo de siempre ("Clásico") o las skins del lanzador del coche.
 * Se guarda en este navegador (localStorage). El <head> de index.html la aplica antes de pintar para que no parpadee. */
(function () {
  'use strict';
  const SKINS = {
    clasico:    ['Clásico', 'El estilo de siempre', ['#1b222d', '#0a0d12', '#34d399']],
    oceano:     ['Océano', 'Oscura · azul marino', ['#0a3346', '#02131c', '#57f0e0']],
    aurora:     ['Aurora', 'Oscura · menta', ['#0d1a31', '#040811', '#74e1ce']],
    horizon:    ['Horizon', 'Oscura · atardecer', ['#5a2c33', '#0a0d1a', '#f2c27b']],
    pulse:      ['Pulse', 'Oscura · eléctrica', ['#0f2216', '#050b07', '#c6f36b']],
    porcelana:  ['Porcelana', 'Clara · luz de día', ['#f7f9fb', '#e9eef4', '#2557ff']],
    forma:      ['Forma', 'Clara · cálida', ['#f6f2ec', '#e6dfd4', '#6a55e0']],
    neon:       ['Neón', 'Magenta y cian', ['#1a0b2e', '#07030f', '#ff2bd6']],
    acido:      ['Ácido', 'Verde y amarillo', ['#0c1d0d', '#030803', '#39ff14']],
    mango:      ['Mango', 'Naranja y rosa', ['#2a0e07', '#0f0603', '#ff7a00']],
    laser:      ['Láser', 'Azul y violeta', ['#0b1446', '#03041a', '#00b3ff']],
    rubi:       ['Rubí', 'Rojo y naranja', ['#2a0714', '#0e0307', '#ff1f4b']],
    sorbete:    ['Sorbete', 'Clara · rosa', ['#fff8fc', '#ffe6f2', '#ff2d95']],
    medianoche: ['Medianoche', 'Negro profundo y el neón que elijas', ['#07070a', '#000000', '#ff2bd6']],
  };
  const NEONS = { rosa: ['Rosa', '#ff2bd6', '#2a0022'], verde: ['Verde', '#39ff14', '#062200'], naranja: ['Naranja', '#ff7a00', '#2a1000'],
    rojo: ['Rojo', '#ff1f4b', '#2a0008'], azul: ['Azul', '#00a2ff', '#001a2b'], violeta: ['Violeta', '#a64dff', '#1a0033'],
    amarillo: ['Amarillo', '#f5ff00', '#242600'], cian: ['Cian', '#00f0ff', '#002a2c'] };
  const NEON_SKINS = ['neon', 'acido', 'mango', 'laser', 'rubi', 'medianoche'];
  const GROUPS = [['Del lanzador del coche', ['oceano', 'aurora', 'horizon', 'pulse', 'porcelana', 'forma']], ['Flúor', ['neon', 'acido', 'mango', 'laser', 'rubi', 'sorbete']]];
  const get = k => { try { return localStorage.getItem(k); } catch (e) { return null; } };
  const set = (k, v) => { try { localStorage.setItem(k, v); } catch (e) {} };
  const root = document.documentElement;
  const metas = Array.from(document.querySelectorAll('meta[name="theme-color"]'));
  const metaDefaults = metas.map(m => m.getAttribute('content'));
  let skin = SKINS[get('bydlink_skin')] ? get('bydlink_skin') : 'clasico';
  let neon = NEONS[get('bydlink_neon')] ? get('bydlink_neon') : 'rosa';

  function apply() {
    if (skin === 'clasico') { root.removeAttribute('data-skin'); root.removeAttribute('data-neon'); }
    else { root.setAttribute('data-skin', skin); if (NEON_SKINS.includes(skin)) root.setAttribute('data-neon', ''); else root.removeAttribute('data-neon'); }
    root.style.setProperty('--neon', NEONS[neon][1]); root.style.setProperty('--neon-ink', NEONS[neon][2]);
    // Barra del sistema (PWA) del color del fondo de la skin.
    const bg = skin === 'clasico' ? null : getComputedStyle(root).getPropertyValue('--bg').trim();
    metas.forEach((m, i) => m.setAttribute('content', bg || metaDefaults[i]));
  }

  /** Cambia con un círculo que se abre desde el dedo (si el navegador sabe hacerlo y no se pidió menos movimiento). */
  function change(ev, fn) {
    const reduce = matchMedia('(prefers-reduced-motion: reduce)').matches;
    if (!document.startViewTransition || reduce || !ev) { fn(); return; }
    const x = ev.clientX, y = ev.clientY, r = Math.hypot(Math.max(x, innerWidth - x), Math.max(y, innerHeight - y));
    const vt = document.startViewTransition(fn);
    vt.ready.then(() => root.animate({ clipPath: [`circle(0px at ${x}px ${y}px)`, `circle(${r}px at ${x}px ${y}px)`] },
      { duration: 520, easing: 'cubic-bezier(.77,0,.175,1)', pseudoElement: '::view-transition-new(root)' })).catch(() => {});
  }

  function card(id, wide) {
    const k = SKINS[id];
    const acc = id === 'medianoche' ? NEONS[neon][1] : k[2][2];
    const prev = `<span class="skin-prev" style="background:radial-gradient(90% 80% at 50% 0%,${acc}44,transparent 70%),linear-gradient(180deg,${k[2][0]},${k[2][1]});--acc:${acc}"></span>`;
    const text = wide ? `<span><b>${k[0]}</b><small>${k[1]}</small></span>` : `<b>${k[0]}</b><small>${k[1]}</small>`;
    return `<button type="button" class="skin-opt${wide ? ' wide' : ''}" role="radio" data-skin="${id}" aria-checked="${id === skin}">${prev}${text}</button>`;
  }

  function render() {
    const grid = document.getElementById('skinGrid'); if (!grid) return;
    grid.innerHTML = card('clasico', true)
      + GROUPS.map(([g, ids]) => `<p class="skin-h">${g}</p>` + ids.map(id => card(id, false)).join('')).join('')
      + `<p class="skin-h">Medianoche</p>` + card('medianoche', true);
    const row = document.getElementById('neonRow');
    row.innerHTML = Object.entries(NEONS).map(([id, n]) =>
      `<button type="button" class="neon-sw" role="radio" data-neon="${id}" aria-label="Neón ${n[0]}" aria-checked="${skin === 'medianoche' && id === neon}" style="--c:${n[1]}"></button>`).join('');
  }

  function init() {
    apkCard();
    const grid = document.getElementById('skinGrid'); if (!grid) return;
    render();
    grid.addEventListener('click', e => {
      const b = e.target.closest('.skin-opt'); if (!b || b.dataset.skin === skin) return;
      change(e, () => { skin = b.dataset.skin; set('bydlink_skin', skin); apply(); render(); });
    });
    document.getElementById('neonRow').addEventListener('click', e => {
      const b = e.target.closest('.neon-sw'); if (!b) return;
      if (b.dataset.neon === neon && skin === 'medianoche') return;
      change(e, () => { neon = b.dataset.neon; skin = 'medianoche'; set('bydlink_neon', neon); set('bydlink_skin', skin); apply(); render(); });
    });
  }

  // App para Android: se ofrece en Ajustes fuera de la propia app (y no en iPhone/iPad, donde no sirve). Dentro de la
  // app (TWA) se sabe por el referrer de la primera carga y se recuerda solo en esa pestaña (sessionStorage): la TWA
  // comparte el almacenamiento con Chrome y, guardado para siempre, escondía la tarjeta también en el navegador.
  function apkCard() {
    try { localStorage.removeItem('bydlink_twa'); } catch (e) {}   // la marca antigua (hasta la 2.16)
    let inApp = false;
    try {
      if (document.referrer.indexOf('android-app://') === 0) sessionStorage.setItem('bydlink_twa', '1');
      inApp = sessionStorage.getItem('bydlink_twa') === '1';
    } catch (e) {}
    // App ya instalada (PWA o TWA a pantalla completa): no hace falta ofrecerla.
    if (window.matchMedia && matchMedia('(display-mode: standalone)').matches) inApp = true;
    const card = document.getElementById('apkCard'); if (!card) return;
    card.hidden = inApp || /iPhone|iPad|iPod/i.test(navigator.userAgent);
  }

  apply();
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', init); else init();
})();
