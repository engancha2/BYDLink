package com.bydlink.carapp.update

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Base64
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.pm.PackageInfoCompat
import com.bydlink.carapp.BuildConfigCompat
import com.bydlink.carapp.car.CarShell
import com.bydlink.carapp.ui.UpdatesActivity
import com.google.android.gms.tasks.Tasks
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.ServerValue
import com.google.firebase.database.ValueEventListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Actualizaciones automáticas de la app del coche.
 *
 * El panel de admin publica /updates/latest = {versionCode, versionName, size, sha256, chunks, notes, ts}
 * y el APK troceado en /updates/files/{versionCode}/{i} (base64), o bien una `url` de descarga directa.
 * El coche: ve una versión más nueva → la descarga → comprueba tamaño, SHA-256, que es esta app, que es
 * más nueva y que está firmada con la misma clave → espera a estar parado → la instala por el ADB local
 * (sin preguntar; el sistema reabre la app ya actualizada). Sin ADB, o si se ha desactivado la instalación
 * automática, la deja lista para instalar desde la pantalla «Actualizaciones».
 *
 * Avisa en la pantalla del coche (notificación + mensaje al terminar) y publica el progreso en
 * /cars/{carId}/meta/update (lo ven el panel de admin y la web del móvil). El estado también se guarda en
 * las preferencias para la pantalla «Actualizaciones».
 */
class Updater(private val ctx: Context, carId: String, private val canInstall: () -> Boolean) {

    private val db = com.bydlink.carapp.App.db
    private val statusRef = db.getReference("cars/$carId/meta/update")
    private val latestRef = db.getReference("updates/latest")
    private val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val myCode = BuildConfigCompat.versionCode(ctx)
    @Volatile private var busy = false

    init { instance = this }

    fun start() {
        // La versión recién instalada confirma que la actualización ha ido bien.
        val installing = prefs.getLong(K_INSTALLING, 0)
        if (installing != 0L) {
            prefs.edit().remove(K_INSTALLING).remove(K_READY_PATH).remove(K_READY_NAME).apply()
            if (installing == myCode) {
                File(ctx.cacheDir, DIR).deleteRecursively()
                val v = BuildConfigCompat.appVersion(ctx)
                addHistory(ctx, v)
                prefs.edit().putString(K_NEWS, v).apply()        // la pantalla enseña las novedades una vez
                report("actualizada", v)
                notifyDone(v)
            }
        }
        latestRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) { (s.value as? Map<*, *>)?.let { offer(it) } ?: upToDate() }
            override fun onCancelled(e: DatabaseError) { Log.e(TAG, "no se pueden leer las actualizaciones: ${e.message}") }
        })
    }

    /** «Buscar ahora»: olvida los fallos anteriores y vuelve a mirar la versión publicada. */
    fun checkNow() {
        prefs.edit().apply { prefs.all.keys.filter { it.startsWith("updFail") }.forEach { remove(it) } }.apply()
        latestRef.get().addOnSuccessListener { s -> (s.value as? Map<*, *>)?.let { offer(it) } ?: upToDate() }
            .addOnFailureListener { local("error", null, "Sin conexión para buscar actualizaciones") }
    }

    private fun upToDate() {
        prefs.edit().putLong(K_CHECKED, System.currentTimeMillis()).apply()
        // "actualizada" ya significa al día: no pisarla (el móvil avisa al verla).
        if (!busy && prefs.getString(K_STATE, null) !in setOf("aldia", "actualizada")) report("aldia", BuildConfigCompat.appVersion(ctx))
    }

    private fun offer(u: Map<*, *>) {
        val code = (u["versionCode"] as? Number)?.toLong() ?: return
        val name = u["versionName"] as? String ?: code.toString()
        prefs.edit().putLong(K_CHECKED, System.currentTimeMillis())
            .putString(K_LATEST_NAME, name).putLong(K_LATEST_CODE, code)
            .putString(K_LATEST_NOTES, u["notes"] as? String).putLong(K_LATEST_TS, (u["ts"] as? Number)?.toLong() ?: 0L)
            .apply()
        Log.i(TAG, "publicada $name ($code); instalada $myCode" + if (busy) " (ya en marcha)" else "")
        if (code <= myCode) { upToDate(); return }
        if (busy) return
        val failKey = "updFail$code"
        val fails = prefs.getInt(failKey, 0)
        if (fails >= MAX_TRIES) return
        busy = true
        Thread {
            try {
                notifyProgress(name, "Descargando…", 0)
                val apk = download(u, code, name)
                val realCode = verify(apk)
                prefs.edit().putString(K_READY_PATH, apk.path).putString(K_READY_NAME, name).apply()
                if (!autoInstall(ctx)) {
                    report("lista", name, "Lista para instalar: pulsa «Instalar ahora» en Actualizaciones")
                    notifyReady(name)
                    return@Thread
                }
                if (!canInstall()) {
                    report("esperando", name, "Se instalará cuando el coche esté parado")
                    notifyProgress(name, "Se instalará cuando el coche esté parado", null)
                    while (!canInstall()) Thread.sleep(30_000)
                }
                if (!installByAdb(ctx, apk, realCode, name)) {
                    report("lista", name, "Sin ADB: pulsa «Instalar ahora» en Actualizaciones")
                    notifyReady(name)
                }
            } catch (e: Throwable) {
                Log.e(TAG, "actualización $name fallida", e)
                prefs.edit().putInt(failKey, fails + 1).apply()
                report("error", name, e.message ?: e.toString())
                notify(name, "No se pudo actualizar a la $name", e.message ?: "Error desconocido", done = true)
                if (fails + 1 < MAX_TRIES) Thread { Thread.sleep(RETRY_MS); offer(u) }.start()
            } finally {
                busy = false
            }
        }.start()
    }

    // ---------------- Descarga ----------------
    private fun download(u: Map<*, *>, code: Long, name: String): File {
        val dir = File(ctx.cacheDir, DIR).apply { mkdirs() }
        val out = File(dir, "bydlink-$code.apk")
        dir.listFiles()?.forEach { if (it != out) it.delete() }
        val size = (u["size"] as? Number)?.toLong() ?: -1L
        val sha = u["sha256"] as? String
        if (out.exists() && out.length() == size && sha != null && sha256(out).equals(sha, true)) return out   // ya bajada

        report("descargando", name, progress = 0)
        val url = u["url"] as? String
        if (url != null) fromUrl(url, out, size, name)
        else fromChunks(code, (u["chunks"] as? Number)?.toInt() ?: error("La actualización no trae el archivo"), out, name)

        if (size >= 0 && out.length() != size) error("Descarga incompleta (${out.length()} de $size bytes)")
        if (sha != null && !sha256(out).equals(sha, true)) error("El archivo descargado está dañado")
        return out
    }

    /**
     * Por trozos, con reintentos y guardando cada trozo en disco: con mala cobertura (WiFi débil, 4G flojo) el
     * siguiente intento sigue donde se quedó en vez de empezar de cero.
     */
    private fun fromChunks(code: Long, n: Int, out: File, name: String) {
        val parts = File(ctx.cacheDir, "$DIR-trozos-$code").apply { mkdirs() }
        ctx.cacheDir.listFiles()?.forEach { if (it.name.startsWith("$DIR-trozos-") && it != parts) it.deleteRecursively() }
        for (i in 0 until n) {
            val f = File(parts, "$i")
            if (f.exists() && f.length() > 0) { progress(name, (i + 1) * 100 / n); continue }
            var fallo: Throwable? = null
            for (intento in 1..4) {
                try {
                    val b64 = Tasks.await(db.getReference("updates/files/$code/$i").get(), 120, TimeUnit.SECONDS)
                        .getValue(String::class.java) ?: error("Falta el trozo ${i + 1} de $n")
                    val tmp = File(parts, "$i.tmp")
                    tmp.writeBytes(Base64.decode(b64, Base64.DEFAULT)); tmp.renameTo(f)
                    fallo = null; break
                } catch (e: Throwable) { fallo = e; Thread.sleep(5_000L * intento) }
            }
            fallo?.let { throw IllegalStateException("No se pudo bajar el trozo ${i + 1} de $n (${it.message})") }
            progress(name, (i + 1) * 100 / n)
        }
        out.outputStream().buffered().use { os -> for (i in 0 until n) File(parts, "$i").inputStream().use { it.copyTo(os) } }
        parts.deleteRecursively()
    }

    private fun fromUrl(url: String, out: File, size: Long, name: String) {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 20_000; c.readTimeout = 60_000
        try {
            if (c.responseCode !in 200..299) error("Descarga fallida (HTTP ${c.responseCode})")
            val total = if (size > 0) size else c.contentLengthLong
            c.inputStream.use { inp ->
                out.outputStream().use { os ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L; var last = 0
                    while (true) {
                        val r = inp.read(buf); if (r < 0) break
                        os.write(buf, 0, r); done += r
                        val p = if (total > 0) (done * 100 / total).toInt() else 0
                        if (p >= last + 5) { last = p; progress(name, p) }
                    }
                }
            }
        } finally { c.disconnect() }
    }

    private fun progress(name: String, p: Int) {
        report("descargando", name, progress = p)
        notifyProgress(name, "Descargando… $p %", p)
    }

    // ---------------- Comprobaciones ----------------
    /** Devuelve el versionCode real del APK (no el que dice el panel). */
    private fun verify(apk: File): Long {
        val pm = ctx.packageManager
        val info = pm.getPackageArchiveInfo(apk.path, SIG_FLAGS) ?: error("El archivo no es un APK válido")
        if (info.packageName != ctx.packageName) error("El APK es de otra app (${info.packageName})")
        val newCode = PackageInfoCompat.getLongVersionCode(info)
        if (newCode <= myCode) error("El APK no es más nuevo que el instalado (código $newCode)")
        // Android también lo exige al instalar; aquí se descarta antes y con un motivo claro.
        val theirs = signers(info)
        val mine = signers(pm.getPackageInfo(ctx.packageName, SIG_FLAGS))
        if (theirs.isNotEmpty() && mine.isNotEmpty() && theirs != mine) error("Firma distinta: este APK no es de BYDLink")
        return newCode
    }

    // ---------------- Avisos ----------------
    private fun report(state: String, version: String? = null, msg: String? = null, progress: Int? = null) {
        if (state != "descargando" || progress == 0 || progress == 100) Log.i(TAG, "estado: $state $version ${msg ?: ""}")
        local(state, version, msg, progress)
        try {
            statusRef.setValue(mapOf("state" to state, "version" to version, "msg" to msg,
                "progress" to progress, "ts" to ServerValue.TIMESTAMP))
        } catch (e: Throwable) { e.printStackTrace() }
    }

    private fun local(state: String, version: String?, msg: String?, progress: Int? = null) =
        prefs.edit().putString(K_STATE, state).putString(K_STATE_VER, version).putString(K_STATE_MSG, msg)
            .putInt(K_PROGRESS, progress ?: -1).apply()

    private fun notifyProgress(name: String, text: String, p: Int?) =
        notify(name, "Actualizando BYDLink a la $name", text, progress = p ?: -1, ongoing = true)

    private fun notifyReady(name: String) =
        notify(name, "BYDLink $name lista para instalar", "Toca para abrir Actualizaciones y pulsa «Instalar ahora»", done = true)

    private fun notifyDone(v: String) {
        notify(v, "BYDLink se ha actualizado a la $v",
            prefs.getString(K_LATEST_NOTES, null)?.takeIf { prefs.getString(K_LATEST_NAME, null) == v } ?: "Toca para ver las novedades",
            done = true)
        Handler(Looper.getMainLooper()).post {
            try { Toast.makeText(ctx, "BYDLink se ha actualizado a la $v", Toast.LENGTH_LONG).show() } catch (e: Throwable) {}
        }
    }

    private fun notify(name: String, title: String, text: String, progress: Int = -2, ongoing: Boolean = false, done: Boolean = false) {
        try {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            // Importancia alta: el aviso aparece encima de lo que haya en la pantalla del coche.
            if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CH, "Actualizaciones", NotificationManager.IMPORTANCE_HIGH))
            val pi = PendingIntent.getActivity(ctx, 3, Intent(ctx, UpdatesActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val b = NotificationCompat.Builder(ctx, CH)
                .setSmallIcon(if (done) android.R.drawable.stat_sys_download_done else android.R.drawable.stat_sys_download)
                .setContentTitle(title).setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setOnlyAlertOnce(!done).setOngoing(ongoing).setAutoCancel(done)
                .setContentIntent(pi)
            if (progress >= -1) b.setProgress(100, progress.coerceAtLeast(0), progress < 0)
            nm.notify(NOTI_ID, b.build())
        } catch (e: Throwable) { e.printStackTrace() }
    }

    /** Estado que muestra la pantalla «Actualizaciones». */
    data class State(
        val state: String?, val version: String?, val msg: String?, val progress: Int,
        val latestName: String?, val latestCode: Long, val latestNotes: String?, val latestTs: Long, val checkedAt: Long
    )

    companion object {
        private const val TAG = "BYDLink"
        private const val PREFS = "bydlink"
        private const val DIR = "update"
        private const val CH = "bydlink-avisos"
        private const val NOTI_ID = 3
        private const val K_INSTALLING = "updInstalling"
        private const val K_READY_PATH = "updReadyPath"
        private const val K_READY_NAME = "updReadyName"
        private const val K_STATE = "updState"
        private const val K_STATE_VER = "updStateVer"
        private const val K_STATE_MSG = "updStateMsg"
        private const val K_PROGRESS = "updProgress"
        private const val K_LATEST_NAME = "updLatestName"
        private const val K_LATEST_CODE = "updLatestCode"
        private const val K_LATEST_NOTES = "updLatestNotes"
        private const val K_LATEST_TS = "updLatestTs"
        private const val K_CHECKED = "updChecked"
        private const val K_NEWS = "updNews"
        private const val K_HISTORY = "updHistory"
        private const val K_AUTO = "updAuto"
        private const val MAX_TRIES = 3
        private const val RETRY_MS = 10 * 60_000L

        /** El actualizador que corre en el servicio (misma app, mismo proceso). */
        @Volatile var instance: Updater? = null
            private set

        @Suppress("DEPRECATION")
        private val SIG_FLAGS = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES

        @Suppress("DEPRECATION")
        private fun signers(i: PackageInfo): Set<String> = try {
            (if (Build.VERSION.SDK_INT >= 28) i.signingInfo?.apkContentsSigners else i.signatures)
                ?.map { it.toCharsString() }?.toSet() ?: emptySet()
        } catch (e: Throwable) { emptySet() }

        private fun sha256(f: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            f.inputStream().use { s -> val b = ByteArray(64 * 1024); while (true) { val r = s.read(b); if (r < 0) break; md.update(b, 0, r) } }
            return md.digest().joinToString("") { "%02x".format(it) }
        }

        private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        fun state(ctx: Context): State = prefs(ctx).let { p ->
            State(p.getString(K_STATE, null), p.getString(K_STATE_VER, null), p.getString(K_STATE_MSG, null), p.getInt(K_PROGRESS, -1),
                p.getString(K_LATEST_NAME, null), p.getLong(K_LATEST_CODE, 0), p.getString(K_LATEST_NOTES, null),
                p.getLong(K_LATEST_TS, 0), p.getLong(K_CHECKED, 0))
        }

        fun autoInstall(ctx: Context) = prefs(ctx).getBoolean(K_AUTO, true)
        fun setAutoInstall(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean(K_AUTO, on).apply()

        /** Versiones instaladas por actualización, de la más reciente a la más antigua: (versión, cuándo). */
        fun history(ctx: Context): List<Pair<String, Long>> = try {
            val a = JSONArray(prefs(ctx).getString(K_HISTORY, "[]"))
            (0 until a.length()).map { a.getJSONObject(it).let { o -> o.getString("v") to o.getLong("ts") } }
        } catch (e: Throwable) { emptyList() }

        private fun addHistory(ctx: Context, v: String) {
            val a = JSONArray().put(JSONObject().put("v", v).put("ts", System.currentTimeMillis()))
            history(ctx).take(19).forEach { (hv, ts) -> a.put(JSONObject().put("v", hv).put("ts", ts)) }
            prefs(ctx).edit().putString(K_HISTORY, a.toString()).apply()
        }

        /** Novedades de la versión recién instalada, una sola vez: (versión, notas o null). */
        fun takeNews(ctx: Context): Pair<String, String?>? {
            val p = prefs(ctx)
            val v = p.getString(K_NEWS, null) ?: return null
            p.edit().remove(K_NEWS).apply()
            return v to p.getString(K_LATEST_NOTES, null)?.takeIf { p.getString(K_LATEST_NAME, null) == v }
        }

        /** Nombre de la versión descargada y comprobada que espera a instalarse a mano, o null. */
        fun readyToInstall(ctx: Context): String? {
            val p = prefs(ctx)
            val path = p.getString(K_READY_PATH, null) ?: return null
            return if (File(path).exists()) p.getString(K_READY_NAME, "") else null
        }

        /** Instala por el ADB del coche, sin preguntar. Si va bien, el sistema cierra y reabre la app ya actualizada. */
        private fun installByAdb(ctx: Context, apk: File, code: Long, name: String): Boolean {
            instance?.report("instalando", name)
            instance?.notifyProgress(name, "Instalando…", null)
            prefs(ctx).edit().putLong(K_INSTALLING, code).apply()
            val sh = CarShell(ctx.filesDir)
            val ok = sh.connect(BuildConfigCompat.adbHost(ctx)) && sh.install(apk)
            sh.close()
            if (!ok) prefs(ctx).edit().remove(K_INSTALLING).apply()
            return ok
        }

        /** «Instalar ahora» desde la pantalla: por ADB si se puede; si no, con la confirmación de Android. */
        fun installNow(a: Activity) {
            val p = prefs(a)
            val apk = File(p.getString(K_READY_PATH, null) ?: return)
            val name = p.getString(K_READY_NAME, "") ?: ""
            Thread {
                val code = a.packageManager.getPackageArchiveInfo(apk.path, 0)?.let { PackageInfoCompat.getLongVersionCode(it) } ?: 0L
                if (!installByAdb(a.applicationContext, apk, code, name)) a.runOnUiThread {
                    try { installWithPrompt(a) } catch (e: Throwable) { Toast.makeText(a, "No se pudo instalar: ${e.message}", Toast.LENGTH_LONG).show() }
                }
            }.start()
        }

        /** Instalación con confirmación del sistema (cuando no hay ADB). */
        fun installWithPrompt(a: Activity) {
            val p = prefs(a)
            val apk = File(p.getString(K_READY_PATH, null) ?: return)
            if (Build.VERSION.SDK_INT >= 26 && !a.packageManager.canRequestPackageInstalls()) {
                // Android pide permitir "instalar apps desconocidas" a BYDLink una sola vez.
                a.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + a.packageName)))
                return
            }
            val pi = a.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                .apply { setAppPackageName(a.packageName) }
            val id = pi.createSession(params)
            pi.openSession(id).use { s ->
                s.openWrite("bydlink.apk", 0, apk.length()).use { os -> apk.inputStream().use { it.copyTo(os) }; s.fsync(os) }
                val code = a.packageManager.getPackageArchiveInfo(apk.path, 0)?.let { PackageInfoCompat.getLongVersionCode(it) } ?: 0L
                p.edit().putLong(K_INSTALLING, code).apply()
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                val status = PendingIntent.getBroadcast(a, id, Intent(a, InstallResultReceiver::class.java), flags)
                s.commit(status.intentSender)
            }
        }
    }
}
