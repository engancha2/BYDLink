package com.bydlink.carapp.ui

import android.content.Context
import android.content.pm.PackageManager
import android.os.Environment
import android.util.Log
import com.bydlink.carapp.BuildConfigCompat
import com.bydlink.carapp.camera.VideoStore
import com.bydlink.carapp.car.CarShell
import java.io.File

/**
 * "Instalar una app": busca archivos .apk en el USB, la tarjeta SD y la carpeta Descargas del coche y los instala con
 * el ADB del propio coche (como las actualizaciones de BYDLink), porque DiLink no deja instalar desde el gestor de
 * archivos. Es el usuario quien elige el archivo y lo confirma; BYDLink no descarga nada.
 */
internal object ApkInstaller {
    private const val TAG = "BYDLink"
    data class Apk(val file: File, val label: String, val pkg: String, val version: String, val installed: String?, val bytes: Long, val where: String)

    /** Carpetas donde buscar (raíz de cada USB/SD + Descargas + la memoria). Llamar fuera del hilo principal. */
    private fun roots(ctx: Context): List<Pair<File, String>> {
        val out = LinkedHashMap<String, Pair<File, String>>()
        fun add(f: File?, name: String) { if (f != null && f.isDirectory && f.canRead()) out.putIfAbsent(f.absolutePath, f to name) }
        try {
            VideoStore.volumes(ctx, fresh = true).filter { it.kind != "internal" }.forEach { v ->
                val p = v.root.absolutePath
                add(File(if ("/Android/data/" in p) p.substringBefore("/Android/data/") else v.root.parent ?: p), v.label)
            }
        } catch (e: Throwable) {}
        File("/storage").listFiles()?.filter { it.name != "self" && it.name != "emulated" }?.forEach { add(it, "USB / SD") }
        listOf("/mnt/usb", "/mnt/udisk", "/mnt/usb_storage", "/mnt/media_rw").forEach { d -> File(d).listFiles()?.forEach { add(it, "USB") }; add(File(d), "USB") }
        @Suppress("DEPRECATION")
        val sd = Environment.getExternalStorageDirectory()
        add(File(sd, Environment.DIRECTORY_DOWNLOADS), "Descargas")
        add(sd, "Memoria del coche")
        return out.values.toList()
    }

    fun find(ctx: Context, max: Int = 60): List<Apk> {
        val pm = ctx.packageManager
        val files = ArrayList<Pair<File, String>>()
        for ((root, name) in roots(ctx)) {
            root.listFiles()?.forEach { f ->
                if (f.isFile && f.name.endsWith(".apk", true)) files += f to name
                else if (f.isDirectory && !f.name.startsWith(".") && f.name != "Android")
                    f.listFiles()?.filter { it.isFile && it.name.endsWith(".apk", true) }?.forEach { files += it to name }
            }
            if (files.size >= max) break
        }
        return files.distinctBy { it.first.canonicalPath }.take(max).mapNotNull { (f, where) ->
            val info = try { pm.getPackageArchiveInfo(f.path, 0) } catch (e: Throwable) { null } ?: return@mapNotNull null
            val ai = info.applicationInfo?.apply { sourceDir = f.path; publicSourceDir = f.path }
            val label = try { ai?.loadLabel(pm)?.toString() } catch (e: Throwable) { null } ?: f.nameWithoutExtension
            val have = try { pm.getPackageInfo(info.packageName, 0).versionName ?: "?" } catch (e: PackageManager.NameNotFoundException) { null }
            Apk(f, label, info.packageName, info.versionName ?: "?", have, f.length(), where)
        }.sortedByDescending { it.file.lastModified() }
    }

    /** Instala por el ADB del coche. Devuelve un texto para el usuario. Llamar fuera del hilo principal. */
    fun install(ctx: Context, apk: Apk): String {
        if (apk.pkg == ctx.packageName) return "Las actualizaciones de BYDLink llegan solas: no hace falta instalarla así."
        val sh = CarShell(ctx.filesDir)
        return try {
            if (!sh.connect(BuildConfigCompat.adbHost(ctx)) || !sh.works()) return "Hace falta el ADB del coche para instalar apps."
            val ok = sh.install(apk.file)
            val now = try { ctx.packageManager.getPackageInfo(apk.pkg, 0).versionName } catch (e: Throwable) { null }
            Log.i(TAG, "instalar ${apk.file.name}: ok=$ok versión=$now")
            if (ok && now != null) "${apk.label} instalada (versión $now)." else "No se pudo instalar ${apk.label}."
        } catch (e: Throwable) {
            Log.w(TAG, "instalar ${apk.file}: $e"); "No se pudo instalar: ${e.message}"
        } finally { sh.close() }
    }
}
