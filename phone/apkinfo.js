/* BYDLink · lee paquete y versión de un APK en el navegador (sin librerías).
 * Un APK es un ZIP; dentro, AndroidManifest.xml va en XML binario de Android (AXML).
 * ApkInfo.read(arrayBuffer) → Promise<{ package, versionCode, versionName }> */
(function (root) {
  'use strict';

  // ── ZIP: buscar AndroidManifest.xml en el directorio central ──
  async function unzipEntry(buf, wanted) {
    const dv = new DataView(buf), u8 = new Uint8Array(buf);
    let eocd = -1;
    for (let i = buf.byteLength - 22; i >= Math.max(0, buf.byteLength - 66000); i--) {
      if (dv.getUint32(i, true) === 0x06054b50) { eocd = i; break; }
    }
    if (eocd < 0) throw new Error('No es un APK (no es un ZIP válido).');
    const count = dv.getUint16(eocd + 10, true);
    let p = dv.getUint32(eocd + 16, true);
    const dec = new TextDecoder();
    for (let n = 0; n < count; n++) {
      if (dv.getUint32(p, true) !== 0x02014b50) break;
      const method = dv.getUint16(p + 10, true), csize = dv.getUint32(p + 20, true);
      const nlen = dv.getUint16(p + 28, true), xlen = dv.getUint16(p + 30, true), clen = dv.getUint16(p + 32, true);
      const local = dv.getUint32(p + 42, true);
      const name = dec.decode(u8.subarray(p + 46, p + 46 + nlen));
      if (name === wanted) {
        const start = local + 30 + dv.getUint16(local + 26, true) + dv.getUint16(local + 28, true);
        const data = u8.slice(start, start + csize);
        if (method === 0) return data.buffer;
        if (method !== 8) throw new Error('Compresión no soportada en el APK.');
        const stream = new Blob([data]).stream().pipeThrough(new DecompressionStream('deflate-raw'));
        return new Response(stream).arrayBuffer();
      }
      p += 46 + nlen + xlen + clen;
    }
    throw new Error('El archivo no tiene AndroidManifest.xml: no es un APK.');
  }

  // ── AXML: tabla de cadenas + atributos de <manifest> ──
  function strings(dv, off) {
    const count = dv.getUint32(off + 8, true), flags = dv.getUint32(off + 16, true);
    const start = off + dv.getUint32(off + 20, true), utf8 = (flags & 0x100) !== 0;
    const hsize = dv.getUint16(off + 2, true), out = [];
    const dec8 = new TextDecoder('utf-8'), dec16 = new TextDecoder('utf-16le');
    for (let i = 0; i < count; i++) {
      let p = start + dv.getUint32(off + hsize + i * 4, true);
      if (utf8) {
        p += (dv.getUint8(p) & 0x80) ? 2 : 1;                 // longitud en caracteres
        let len = dv.getUint8(p);
        if (len & 0x80) { len = ((len & 0x7f) << 8) | dv.getUint8(p + 1); p += 2; } else p += 1;
        out.push(dec8.decode(new Uint8Array(dv.buffer, p, len)));
      } else {
        let len = dv.getUint16(p, true);
        if (len & 0x8000) { len = ((len & 0x7fff) << 16) | dv.getUint16(p + 2, true); p += 4; } else p += 2;
        out.push(dec16.decode(new Uint8Array(dv.buffer, p, len * 2)));
      }
    }
    return out;
  }

  const ATTR_IDS = { 0x0101021b: 'versionCode', 0x0101021c: 'versionName' };

  function manifest(buf) {
    const dv = new DataView(buf);
    let strs = [], ids = [], off = dv.getUint16(2, true);
    while (off + 8 <= dv.byteLength) {
      const type = dv.getUint16(off, true), size = dv.getUint32(off + 4, true);
      if (!size) break;
      if (type === 0x0001) strs = strings(dv, off);
      else if (type === 0x0180) for (let i = 0; i < (size - 8) / 4; i++) ids.push(dv.getUint32(off + 8 + i * 4, true));
      else if (type === 0x0102 && strs[dv.getUint32(off + 20, true)] === 'manifest') {
        const aStart = dv.getUint16(off + 24, true), aSize = dv.getUint16(off + 26, true), n = dv.getUint16(off + 28, true);
        const r = {};
        for (let i = 0; i < n; i++) {
          const a = off + 16 + aStart + i * aSize;
          const ni = dv.getUint32(a + 4, true), raw = dv.getUint32(a + 8, true);
          const type = dv.getUint8(a + 15), data = dv.getUint32(a + 16, true);
          const name = ATTR_IDS[ids[ni]] || strs[ni];
          r[name] = raw !== 0xffffffff ? strs[raw] : type === 0x03 ? strs[data] : data;
        }
        return { package: r.package, versionCode: Number(r.versionCode), versionName: String(r.versionName || '') };
      }
      off += size;
    }
    throw new Error('No se pudo leer la versión del APK.');
  }

  root.ApkInfo = { read: buf => unzipEntry(buf, 'AndroidManifest.xml').then(manifest) };
})(typeof window !== 'undefined' ? window : globalThis);
