package com.bydlink.carapp.camera

import android.content.Context
import android.os.SystemClock
import android.os.storage.StorageManager
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Dónde se guardan los vídeos (memoria del coche, tarjeta SD o USB) y cuánto ocupan.
 *
 * - Cada soporte tiene una carpeta `BYDLink/` con `Centinela/`, `Dashcam/` y `Grabaciones/`, y dentro `AAAA-MM/DD/HHmmss_cámara.mp4`.
 * - En la memoria del coche se usa `/sdcard/BYDLink` si se puede escribir ahí (permiso de almacenamiento dado por el ADB):
 *   así los vídeos se ven con el explorador de archivos y no se borran al desinstalar. En la SD y el USB Android 10 solo
 *   deja escribir en la carpeta de la app (`Android/data/com.bydlink.carapp/files/BYDLink`): se ve desde un PC, pero se
 *   borra si se desinstala BYDLink (no al actualizar).
 * - Si el soporte elegido no está (USB quitado), se graba en la memoria del coche.
 * - Límite de espacio: al pasarse (o si quedan menos de 500 MB libres) se borra lo más viejo: primero dashcam, luego
 *   centinela y lo último las grabaciones a mano. Los clips del centinela duran como mucho 30 días.
 *
 * Id de un vídeo: "Carpeta/AAAA-MM/DD/HHmmss_cámara" (los del centinela antiguos no llevan carpeta).
 */
object VideoStore {
    const val SENTRY = "centinela"; const val DASHCAM = "dashcam"; const val MANUAL = "manual"
    private val FOLDER = mapOf(SENTRY to "Centinela", DASHCAM to "Dashcam", MANUAL to "Grabaciones")
    private const val RESERVE = 500L * 1024 * 1024
    private const val SENTRY_DAYS = 30

    class Volume(
        val id: String, val kind: String, val label: String, val root: File,
        val free: Long, val total: Long, val lasting: Boolean
    ) {
        fun toMap(used: Long) = mapOf("id" to id, "kind" to kind, "label" to label, "free" to free, "total" to total,
            "used" to used, "lasting" to lasting, "path" to root.path)
    }

    class Video(val id: String, val kind: String, val camera: String, val file: File, val ts: Long, val bytes: Long, val volume: String) {
        fun toMap() = mapOf("id" to id, "kind" to kind, "camera" to camera, "ts" to ts, "bytes" to bytes, "volume" to volume)
    }

    fun prefs(ctx: Context) = ctx.getSharedPreferences("video", Context.MODE_PRIVATE)

    /** Soporte elegido ("internal" o el id de la SD/USB). */
    fun selectedId(ctx: Context): String = prefs(ctx).getString("store", "internal") ?: "internal"
    fun select(ctx: Context, id: String) { prefs(ctx).edit().putString("store", id).apply(); cache = null }

    /** Límite en GB (0 = automático: el 40 % del soporte, como mucho 8 GB). */
    fun quotaGb(ctx: Context) = prefs(ctx).getInt("quotaGb", 0)
    fun setQuotaGb(ctx: Context, gb: Int) = prefs(ctx).edit().putInt("quotaGb", gb.coerceIn(0, 512)).apply()
    fun quotaBytes(ctx: Context, v: Volume): Long {
        val gb = quotaGb(ctx)
        return if (gb > 0) gb * 1024L * 1024 * 1024 else minOf(8L * 1024 * 1024 * 1024, (v.total * 0.4).toLong())
    }

    // ───────────────────────── soportes ─────────────────────────

    @Volatile private var cache: Pair<Long, List<Volume>>? = null

    /** Soportes donde se puede escribir ahora mismo (el primero, la memoria del coche). Se cachea 15 s. */
    fun volumes(ctx: Context, fresh: Boolean = false): List<Volume> {
        val c = cache
        if (!fresh && c != null && SystemClock.elapsedRealtime() - c.first < 15_000) return c.second
        val list = scan(ctx)
        cache = SystemClock.elapsedRealtime() to list
        return list
    }

    /** El soporte donde se graba ahora (el elegido, o la memoria del coche si no está). */
    fun current(ctx: Context): Volume? {
        val vs = volumes(ctx)
        return vs.firstOrNull { it.id == selectedId(ctx) } ?: vs.firstOrNull { it.kind == "internal" } ?: vs.firstOrNull()
    }

    /** ¿El soporte elegido no está conectado? */
    fun missing(ctx: Context) = selectedId(ctx) != "internal" && volumes(ctx).none { it.id == selectedId(ctx) }

    private fun scan(ctx: Context): List<Volume> {
        val out = LinkedHashMap<String, Volume>()
        val seen = HashSet<String>()
        val sm = ctx.getSystemService(StorageManager::class.java)
        val dirs = try { ctx.getExternalFilesDirs(null).filterNotNull() } catch (e: Throwable) { emptyList() }
        for (dir in dirs) {
            try {
                val mount = mountOf(dir) ?: continue
                val sv = try { sm?.getStorageVolume(dir) } catch (e: Throwable) { null }
                val primary = sv?.isPrimary ?: mount.path.startsWith("/storage/emulated")
                val desc = try { sv?.getDescription(ctx) } catch (e: Throwable) { null }
                val kind = when {
                    primary -> "internal"
                    desc?.contains("usb", true) == true || mount.name.contains("usb", true) || mount.name.contains("udisk", true) -> "usb"
                    else -> "sd"
                }
                val pub = File(mount, "BYDLink")
                val (root, lasting) = if (writable(pub)) pub to true else File(dir, "BYDLink") to false
                if (!writable(root)) continue
                val id = if (primary) "internal" else (sv?.uuid ?: mount.name)
                seen += mount.name
                out[id] = Volume(id, kind, label(kind, desc), root, root.usableSpace, mount.totalSpace.takeIf { it > 0 } ?: root.totalSpace, lasting)
            } catch (e: Throwable) { /* siguiente */ }
        }
        // Algunos DiLink montan el USB fuera de la lista normal de Android (lo usa su reproductor).
        for (base in listOf("/storage", "/mnt/media_rw", "/mnt/usb", "/mnt/udisk", "/mnt/usb_storage")) {
            val entries = try { File(base).listFiles() } catch (e: Throwable) { null } ?: continue
            for (m in entries) {
                if (m.name in setOf("emulated", "self", "sdcard0") || m.name in seen || !m.isDirectory) continue
                val root = File(m, "BYDLink")
                if (!writable(root)) continue
                seen += m.name
                val kind = if (m.name.contains("sd", true) && !m.name.contains("usb", true)) "sd" else "usb"
                out[m.name] = Volume(m.name, kind, label(kind, null), root, root.usableSpace, m.totalSpace, true)
            }
        }
        if (out.values.none { it.kind == "internal" }) {
            // Sin almacenamiento externo (raro): la memoria privada de la app.
            val root = File(ctx.filesDir, "BYDLink")
            if (writable(root)) out["internal"] = Volume("internal", "internal", "Memoria del coche", root, root.usableSpace, root.totalSpace, false)
        }
        return out.values.sortedBy { when (it.kind) { "internal" -> 0; "sd" -> 1; else -> 2 } }
    }

    private fun label(kind: String, desc: String?) = when (kind) {
        "internal" -> "Memoria del coche"
        "usb" -> desc?.takeIf { it.isNotBlank() } ?: "Memoria USB"
        else -> desc?.takeIf { it.isNotBlank() } ?: "Tarjeta SD"
    }

    /** "/storage/XXXX-XXXX" a partir de ".../Android/data/paquete/files". */
    private fun mountOf(dir: File): File? {
        val p = dir.absolutePath
        val i = p.indexOf("/Android/data/")
        return if (i > 0) File(p.substring(0, i)) else dir.parentFile
    }

    /** ¿Se puede escribir de verdad? (también en una subcarpeta: ver [newFile]). */
    private fun writable(d: File): Boolean = try {
        val sub = File(d, ".prueba")
        (sub.isDirectory || sub.mkdirs()) && File(sub, ".w").let { it.writeText("1"); it.delete() } && sub.delete()
    } catch (e: Throwable) { false }

    // ───────────────────────── ficheros ─────────────────────────

    private var lastCleanup = 0L

    /** Fichero nuevo para un vídeo de tipo [kind] de la cámara [camera], en el soporte elegido. */
    fun newFile(ctx: Context, kind: String, camera: String): File? {
        val now = Date()
        val sub = SimpleDateFormat("yyyy-MM", Locale.US).format(now) + "/" + SimpleDateFormat("dd", Locale.US).format(now)
        val name = SimpleDateFormat("HHmmss", Locale.US).format(now) + "_$camera.mp4"
        // El elegido y, si falla, las carpetas de la memoria del coche (pública, de la app, privada).
        val roots = (listOfNotNull(current(ctx)?.root) + internalRoots(ctx)).distinctBy { canon(it) }
        for (root in roots) {
            try {
                val d = File(File(root, FOLDER[kind] ?: "Grabaciones"), sub)
                d.mkdirs()
                val f = File(d, name)
                // Crear el fichero de verdad: en Android 10, recién dado el permiso de almacenamiento, mkdirs dice que sí
                // y luego abrir falla (ENOENT) hasta que la app se reinicia.
                FileOutputStream(f).close()
                val v = volumes(ctx).firstOrNull { canon(root).startsWith(canon(it.root)) }
                if (v != null && (v.free < RESERVE || SystemClock.elapsedRealtime() - lastCleanup > 60_000)) {
                    lastCleanup = SystemClock.elapsedRealtime(); cleanup(ctx, v)
                }
                return f
            } catch (e: Throwable) { CamLog.w("vídeo: no se puede grabar en $root (${e.message})"); cache = null }
        }
        CamLog.w("vídeo: no hay dónde guardar")
        return null
    }

    /** Carpetas BYDLink de la memoria del coche (pública, de la app y privada): se miran todas al listar y buscar. */
    private fun internalRoots(ctx: Context) = listOfNotNull(
        File("/sdcard/BYDLink"), ctx.getExternalFilesDir(null)?.let { File(it, "BYDLink") }, File(ctx.filesDir, "BYDLink"))

    private fun canon(f: File) = try { f.canonicalPath } catch (e: Throwable) { f.absolutePath }

    /** (soporte, carpeta BYDLink) de todo lo que existe ahora. */
    private fun allRoots(ctx: Context): List<Pair<String, File>> =
        (volumes(ctx).map { it.id to it.root } + internalRoots(ctx).map { "internal" to it })
            .filter { it.second.isDirectory }.distinctBy { canon(it.second) }

    /** Todos los vídeos (los más nuevos primero). */
    fun list(ctx: Context, limit: Int = 300): List<Video> {
        val out = ArrayList<Video>()
        for ((vol, root) in allRoots(ctx)) for ((kind, folder) in FOLDER) collect(File(root, folder), kind, folder, vol, out)
        legacyBases(ctx).forEach { collect(it, SENTRY, null, "internal", out) }
        return out.sortedByDescending { it.ts }.take(limit)
    }

    private fun collect(base: File, kind: String, folder: String?, vol: String, out: MutableList<Video>) {
        val months = base.listFiles() ?: return
        for (m in months) for (d in m.listFiles() ?: continue) for (f in d.listFiles() ?: continue) {
            if (!f.name.endsWith(".mp4") || f.length() == 0L) continue
            val rel = "${m.name}/${d.name}/${f.nameWithoutExtension}"
            out += Video(if (folder != null) "$folder/$rel" else rel, kind, f.nameWithoutExtension.substringAfter('_', "back"), f, f.lastModified(), f.length(), vol)
        }
    }

    /** Dónde guardaba el centinela antes de la 2.25. */
    private fun legacyBases(ctx: Context) = listOfNotNull(File("/sdcard/BYDLink/Sentry"), ctx.getExternalFilesDir("Sentry"), File(ctx.filesDir, "Sentry"))
        .filter { it.isDirectory }

    /** El fichero de un id, en cualquier soporte. */
    fun find(ctx: Context, id: String): File? {
        if (id.contains("..")) return null
        val roots = if (FOLDER.values.any { id.startsWith("$it/") }) allRoots(ctx).map { it.second } else legacyBases(ctx)
        return roots.map { File(it, "$id.mp4") }.firstOrNull { it.isFile }
    }

    fun idOf(ctx: Context, f: File): String {
        val folder = f.parentFile?.parentFile?.parentFile?.name
        val rel = "${f.parentFile?.parentFile?.name}/${f.parentFile?.name}/${f.nameWithoutExtension}"
        return if (folder in FOLDER.values) "$folder/$rel" else rel
    }

    fun delete(ctx: Context, id: String): Boolean = find(ctx, id)?.delete() == true

    /** Pasa un vídeo a "Grabaciones" (lo último que se borra). Devuelve el id nuevo. */
    fun keep(ctx: Context, id: String): String? {
        val f = find(ctx, id) ?: return null
        val root = allRoots(ctx).map { it.second }.firstOrNull { canon(f).startsWith(canon(it) + "/") } ?: current(ctx)?.root ?: return null
        val rel = id.substringAfter('/').takeIf { FOLDER.values.any { id.startsWith("$it/") } } ?: id
        val dst = File(File(root, FOLDER.getValue(MANUAL)), "$rel.mp4")
        dst.parentFile?.mkdirs()
        if (f.renameTo(dst)) return idOf(ctx, dst)
        return try { f.copyTo(dst, true); f.delete(); idOf(ctx, dst) } catch (e: Throwable) { null }
    }

    /** Bytes que ocupan los vídeos de BYDLink en un soporte. */
    fun used(v: Volume): Long = FOLDER.values.sumOf { folder -> File(v.root, folder).walkTopDown().filter { it.isFile }.sumOf { it.length() } }

    /** Borra lo que sobre en [v]: lo viejo del centinela y, si se pasa del límite, lo más antiguo por orden de prioridad. */
    fun cleanup(ctx: Context, v: Volume) {
        try {
            val files = FOLDER.entries.flatMap { (kind, folder) ->
                File(v.root, folder).walkTopDown().filter { it.isFile && it.name.endsWith(".mp4") }.map { kind to it }.toList()
            }.toMutableList()
            if (v.kind == "internal") legacyBases(ctx).forEach { b -> b.walkTopDown().filter { it.isFile && it.name.endsWith(".mp4") }.forEach { files += SENTRY to it } }
            val now = System.currentTimeMillis()
            val busy = now - 15_000                                    // lo que se está grabando no se toca
            files.filter { it.first == SENTRY && it.second.lastModified() < now - SENTRY_DAYS * 86_400_000L }
                .forEach { it.second.delete(); files.remove(it) }
            val prio = mapOf(DASHCAM to 0, SENTRY to 1, MANUAL to 2)
            val queue = files.filter { it.second.lastModified() < busy }
                .sortedWith(compareBy({ prio[it.first] }, { it.second.lastModified() })).toMutableList()
            var total = files.sumOf { it.second.length() }
            val quota = quotaBytes(ctx, v)
            var free = v.root.usableSpace
            var removed = 0
            while (queue.isNotEmpty() && (total > quota || free < RESERVE)) {
                val (kind, f) = queue.removeAt(0)
                if (kind == MANUAL && free >= RESERVE) break          // las grabaciones a mano solo si se acaba el sitio
                val len = f.length()
                if (f.delete()) { total -= len; free += len; removed++ }
            }
            if (removed > 0) CamLog.i("vídeo: borrados $removed vídeos viejos en ${v.label} (límite ${quota / 1048576} MB)")
            // carpetas vacías
            FOLDER.values.forEach { folder -> File(v.root, folder).walkBottomUp().filter { it.isDirectory && it != File(v.root, folder) }
                .forEach { if (it.list()?.isEmpty() == true) it.delete() } }
        } catch (e: Throwable) { CamLog.w("vídeo: fallo al limpiar", e) }
    }
}
