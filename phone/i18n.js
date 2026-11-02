/* BYDLink · idiomas de la web.
 * El texto de la app está escrito en español; cada idioma es un diccionario español → idioma (i18n/<lang>.json).
 * Se traduce toda la página (textos y placeholder/title/aria-label/alt) y todo lo que se pinta después
 * (MutationObserver), incluidos los textos con datos: "Hace 5 min" → "5 min ago" con plantillas "Hace {0} min".
 * Idioma: el elegido en Ajustes; si no, el del móvil si está entre los nuestros; si no, inglés. */
(function () {
  'use strict';
  const LANGS = { en: 'English', es: 'Español', pt: 'Português', de: 'Deutsch', fr: 'Français', it: 'Italiano', nl: 'Nederlands',
    ro: 'Română', tr: 'Türkçe', ar: 'العربية', zh: '中文', ja: '日本語', ko: '한국어' };
  // Formato de fechas y números de cada idioma (árabe con cifras occidentales, como el resto de la app).
  const LOCALES = { en: 'en-GB', es: 'es-ES', pt: 'pt-BR', de: 'de-DE', fr: 'fr-FR', it: 'it-IT', nl: 'nl-NL', ro: 'ro-RO',
    tr: 'tr-TR', ar: 'ar-u-nu-latn', zh: 'zh-CN', ja: 'ja-JP', ko: 'ko-KR' };
  const get = k => { try { return localStorage.getItem(k); } catch (e) { return null; } };
  const set = k => v => { try { localStorage.setItem(k, v); } catch (e) {} };
  const saveLang = set('bydlink_lang');
  function detect() {
    const saved = get('bydlink_lang'); if (saved && LANGS[saved]) return saved;
    for (const l of (navigator.languages || [navigator.language || 'en'])) { const c = String(l).slice(0, 2).toLowerCase(); if (LANGS[c]) return c; }
    return 'en';
  }
  const lang = detect();
  const locale = LOCALES[lang] || 'en-GB';
  const root = document.documentElement;
  root.lang = lang; root.dir = lang === 'ar' ? 'rtl' : 'ltr';

  let dict = new Map(), pats = [];
  const norm = s => s.replace(/\s+/g, ' ').trim();
  function compile(obj) {
    dict = new Map(); pats = []; memo.clear();
    for (const [k, v] of Object.entries(obj)) {
      if (!v) continue;
      dict.set(k, v);   // las plantillas también, para t('Hace {0} min', n)
      if (/\{\d+\}/.test(k)) {
        const parts = k.split(/\{(\d+)\}/);   // literal, índice, literal, …
        let re = '^', order = [];
        parts.forEach((p, i) => { if (i % 2) { re += '(.+?)'; order.push(+p); } else re += p.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'); });
        pats.push({ re: new RegExp(re + '$'), out: v, order });
      }
    }
    pats.sort((a, b) => b.re.source.length - a.re.source.length);   // primero las más concretas
  }
  const upper1 = s => s.charAt(0).toUpperCase() + s.slice(1);
  const lower1 = s => /^\p{Lu}\p{Ll}/u.test(s) ? s.charAt(0).toLowerCase() + s.slice(1) : s;
  const UNIT = '(?:%|°C?|km\\/h|km|kWh\\/100 km|kWh|kW|V|h|min|s|bar|€\\/kWh|€|MB|GB|KB)(?!\\p{L})';
  const NUM_FIRST = new RegExp('^([+\\-]?\\d[\\d.,:]*\\s*(?:' + UNIT + ')?)(\\s*)(\\p{L}.*)$', 'u');
  const NUM_BARE = /^([+\-]?\d[\d.,:]*)(\s*)(\p{L}.*)$/u;
  const NUM_LAST = new RegExp('^(.+?)(\\s*)([+\\-]?\\d[\\d.,:]*\\s*(?:' + UNIT + ')?)$', 'u');
  const EDGE = /^([·•●✓⚠⬇↑↓›‹→\-–—«“"'(\[\s]*)(.*?)([\s:.;,…!?»”"')\]›]*)$/u;
  const UNITS = new Set(['km', 'min', 'kwh', 'kw', 'bar', 'mb', 'gb', 'kb']);
  // un resto sin traducir vale si es un dato: un nombre propio, algo entre comillas o solo cifras y unidades
  const dataLike = s => /^[\p{Lu}«“"'(]/u.test(s) || (/\d/.test(s) && !(s.match(/\p{L}{2,}/gu) || []).some(w => !UNITS.has(w.toLowerCase())));
  /** La palabra tal cual o con otra mayúscula inicial ("viajes" con la clave "Viajes"). */
  function word(k) {
    let v = dict.get(k);
    if (v != null) return v;
    const up = upper1(k);
    if (up !== k && (v = dict.get(up)) != null) return lang === 'de' ? v : lower1(v);
    const low = k.charAt(0).toLowerCase() + k.slice(1);
    if (low !== k && (v = dict.get(low)) != null) return upper1(v);
    return null;
  }
  const cap = (s, d) => /\p{L}/u.test(s) ? (one(s, true, d + 1) ?? s) : s;   // lo que llena un hueco también se traduce ("Neón Rosa")
  /** Un trozo. loose: además prueba "trozo conocido + resto" ("Actualizado" + "hace 5 min"). null si no hay. */
  function one(k, loose, d) {
    d = d || 0;
    if (d > 8) return null;
    let v = word(k);
    if (v != null) return v;
    for (const p of pats) {
      const m = p.re.exec(k);
      if (m) { const got = {}; p.order.forEach((n, i) => { got[n] = cap(m[i + 1], d); }); return p.out.replace(/\{(\d+)\}/g, (_, n) => got[n] != null ? got[n] : ''); }
    }
    // número (con su unidad) + texto ("1 ventana abierta", "0,00 € en cargas", "331 km de autonomía") o texto + número
    for (const re of [NUM_FIRST, NUM_BARE]) {
      const a = re.exec(k);
      if (a && (v = word(a[3]) ?? one(a[3], loose, d + 1)) != null) return a[1] + a[2] + (lang === 'de' || /^\p{Lu}/u.test(a[3]) ? v : lower1(v));
    }
    const b = NUM_LAST.exec(k);
    if (b && (v = word(b[1])) != null) return v + b[2] + b[3];
    if (loose) {   // trozo conocido + resto: "Actualizado hace 5 min", "te avisa en el móvil: «…»"
      const w = k.split(' ');
      for (let i = w.length - 1; i >= 1; i--) {
        let pre = w.slice(0, i).join(' '), tail = '';
        let r = word(pre);
        if (r == null && /[:,]$/.test(pre)) { r = word(pre.slice(0, -1)); tail = pre.slice(-1); }
        if (r == null) continue;
        const rest = w.slice(i).join(' '), rr = one(rest, true, d + 1);
        // el resto vale si también se traduce o si es un dato (cifras, un nombre propio, algo entre comillas)
        if (rr != null || dataLike(rest)) return r + tail + ' ' + (rr ?? rest);
      }
    }
    // signos en los bordes: "· 21 GB libres", "Límite de carga:", "«Texto.»", "✓ Última vez…"
    const e = EDGE.exec(k);
    if (e && (e[1] || e[3]) && e[2]) {
      if ((v = one(e[2], loose, d + 1)) != null) return e[1] + v + e[3];
      const p = /^[.…!?]/.exec(e[3]);   // el punto suele ser parte de la frase
      if (p && (v = one(e[2] + p[0], loose, d + 1)) != null) return e[1] + v + e[3].slice(1);
    }
    return null;
  }
  const memo = new Map();
  /** Traduce un texto en español (o lo devuelve igual). Acepta valores para {0}, {1}… */
  function t(s, ...vals) {
    if (s == null) return s;
    const k = norm(String(s)); if (!k) return s;
    let v = memo.get(k);
    if (v == null) {
      v = one(k, false);
      if (v == null && / [·→] /.test(k)) {   // "Hoy · 9:12", "Casa → Trabajo", "Cuando … → …": trozo a trozo
        let hit = false;
        const parts = k.split(/( [·→] )/).map((p, i) => { if (i % 2) return p; const r = one(p, true); if (r != null) { hit = true; return r; } return p; });
        if (hit) v = parts.join('');
      }
      if (v == null) v = one(k, true);
      if (v == null) v = k;
      if (memo.size > 3000) memo.clear();
      memo.set(k, v);
    }
    return vals.length ? v.replace(/\{(\d+)\}/g, (_, n) => vals[n] != null ? vals[n] : '') : v;
  }

  const SKIP = { SCRIPT: 1, STYLE: 1, TEXTAREA: 1, CODE: 1, PRE: 1, NOSCRIPT: 1 };
  const ATTRS = ['placeholder', 'title', 'aria-label', 'alt'];
  const doneTxt = new WeakMap(), doneAttr = new WeakMap();   // lo que ya pusimos nosotros: no se vuelve a traducir
  function textNode(n) {
    const raw = n.nodeValue; if (doneTxt.get(n) === raw) return;
    const k = norm(raw); if (!k || !/\p{L}/u.test(k)) return;
    const v = t(k); if (v === k) return;
    const out = raw.match(/^\s*/)[0] + v + raw.match(/\s*$/)[0];
    doneTxt.set(n, out); n.nodeValue = out;
  }
  function attrs(el) {
    if (!el.getAttribute) return;
    let seen = doneAttr.get(el);
    for (const a of ATTRS) {
      const s = el.getAttribute(a); if (!s || (seen && seen[a] === s)) continue;
      const v = t(s); if (v === norm(s)) continue;
      if (!seen) doneAttr.set(el, seen = {});
      seen[a] = v; el.setAttribute(a, v);
    }
  }
  function walk(node) {
    if (node.nodeType === 3) { if (!(node.parentNode && SKIP[node.parentNode.nodeName])) textNode(node); return; }
    if (node.nodeType !== 1 || SKIP[node.nodeName] || (node.closest && node.closest('[data-no-i18n]'))) return;
    attrs(node);
    node.querySelectorAll('[placeholder],[title],[aria-label],[alt]').forEach(attrs);
    const w = document.createTreeWalker(node, NodeFilter.SHOW_TEXT, { acceptNode: x => (x.parentNode && (SKIP[x.parentNode.nodeName] || (x.parentNode.closest && x.parentNode.closest('[data-no-i18n]')))) ? NodeFilter.FILTER_REJECT : NodeFilter.FILTER_ACCEPT });
    const list = []; while (w.nextNode()) list.push(w.currentNode);
    list.forEach(textNode);
  }
  let observer = null;
  function start() {
    walk(document.body);
    document.title = t(document.title);
    observer = new MutationObserver(muts => {
      for (const m of muts) {
        if (m.type === 'characterData') { if (!(m.target.parentNode && m.target.parentNode.closest && m.target.parentNode.closest('[data-no-i18n]'))) textNode(m.target); }
        else if (m.type === 'attributes') attrs(m.target);
        else m.addedNodes.forEach(walk);
      }
    });
    observer.observe(document.body, { subtree: true, childList: true, characterData: true, attributes: true, attributeFilter: ATTRS });
  }
  function reveal() { root.classList.remove('i18n-wait'); }

  /** Selector de idioma de Ajustes (lo pinta aquí para que funcione en cualquier página que tenga #langGrid). */
  function renderPicker() {
    const grid = document.getElementById('langGrid'); if (!grid) return;
    grid.setAttribute('data-no-i18n', '');
    grid.innerHTML = Object.entries(LANGS).map(([c, n]) =>
      `<button type="button" class="lang-opt" role="radio" lang="${c}" dir="${c === 'ar' ? 'rtl' : 'ltr'}" data-lang="${c}" aria-checked="${c === lang}">${n}</button>`).join('');
    grid.addEventListener('click', e => {
      const b = e.target.closest('.lang-opt'); if (!b || b.dataset.lang === lang) return;
      saveLang(b.dataset.lang); location.reload();
    });
  }

  window.I18N = { lang, locale, LANGS, t, use: compile };
  const ready = () => {
    renderPicker();
    if (lang === 'es') { reveal(); return; }
    const timer = setTimeout(reveal, 2500);   // si el diccionario no llega, mejor en español que en blanco
    fetch('i18n/' + lang + '.json').then(r => r.ok ? r.json() : {}).then(obj => { compile(obj); start(); })
      .catch(() => {}).finally(() => { clearTimeout(timer); reveal(); });
  };
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', ready); else ready();
})();
