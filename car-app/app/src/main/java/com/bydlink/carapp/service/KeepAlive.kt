package com.bydlink.carapp.service

import android.app.ActivityManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import com.bydlink.carapp.BuildConfigCompat
import com.bydlink.carapp.car.CarShell

/**
 * Que BYDLink arranque sola y no se cierre nunca (ver PERSISTENCIA.md). Por el ADB del propio coche:
 *  - permisos del coche, cámara y ubicación (pm grant),
 *  - sin ahorro de batería ni restricciones en segundo plano ni hibernación,
 *  - permiso para instalar sus propias actualizaciones,
 *  - y un VIGILANTE: un pequeño script que corre como usuario `shell` (fuera de la app) y la vuelve
 *    a abrir si el sistema o DiLink la cierran, incluso con "forzar detención". Se para solo si se
 *    desinstala BYDLink. Al reiniciar el coche desaparece y la app lo vuelve a lanzar al arrancar.
 */
object KeepAlive {
    private const val TAG = "BYDLink"
    private const val SCRIPT = "/data/local/tmp/bydlink-watchdog.sh"
    private const val PIDFILE = "/data/local/tmp/bydlink-watchdog.pid"

    /** Aplica todo por ADB. Devuelve false si el ADB del coche no está disponible. */
    fun prepare(ctx: Context): Boolean {
        val sh = CarShell(ctx.filesDir)
        try {
            if (!sh.connect(BuildConfigCompat.adbHost(ctx)) || !sh.works()) {
                Log.w(TAG, "mantener viva: sin ADB")
                return false
            }
            val pkg = ctx.packageName
            // Solo lo que falte: se ejecuta en cada arranque y algunos cambios reinician la app.
            (DEV_PERMS + CarShell.PERMS)
                .filter { defined(ctx, it) && ctx.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
                .forEach { sh.shell("pm grant $pkg $it") }
            val pm = ctx.getSystemService(PowerManager::class.java)
            val restricted = Build.VERSION.SDK_INT >= 28 && ctx.getSystemService(ActivityManager::class.java).isBackgroundRestricted
            if (!pm.isIgnoringBatteryOptimizations(pkg) || restricted) sh.keepAlive(pkg)   // Doze, segundo plano, hibernación
            if (Build.VERSION.SDK_INT >= 28 &&
                ctx.getSystemService(UsageStatsManager::class.java).appStandbyBucket > UsageStatsManager.STANDBY_BUCKET_ACTIVE)
                sh.shell("am set-standby-bucket $pkg active")
            startWatchdog(sh, pkg)
            dilinkWhitelist(sh, pkg)
            // Ver la música que suena (tarjeta Música del lanzador): acceso de "oyente de notificaciones".
            val listener = "$pkg/${MediaListener::class.java.name}"
            if (sh.shell("settings get secure enabled_notification_listeners")?.contains(listener) != true)
                sh.shell("cmd notification allow_listener $listener")
            // Si el usuario eligió BYDLink como pantalla de inicio y DiLink la ha perdido (actualización, reinicio), volver a ponerla.
            com.bydlink.carapp.ui.LauncherMode.reassert(ctx, sh)
            // Lo último: cambiar este permiso hace que Android reinicie la app (el vigilante ya está en marcha).
            if (Build.VERSION.SDK_INT >= 26 && !ctx.packageManager.canRequestPackageInstalls())
                sh.shell("cmd appops set $pkg REQUEST_INSTALL_PACKAGES allow")
            Log.i(TAG, "mantener viva: aplicado (vigilante ${if (watchdogRunning(sh)) "activo" else "NO activo"})")
            return true
        } catch (e: Throwable) {
            Log.e(TAG, "mantener viva: fallo", e); return false
        } finally {
            sh.close()
        }
    }

    private val DEV_PERMS = listOf("android.permission.WRITE_SECURE_SETTINGS", "android.permission.READ_LOGS")

    /** Los permisos de BYD no existen en un Android normal: no tiene sentido pedirlos. */
    private fun defined(ctx: Context, perm: String) =
        try { ctx.packageManager.getPermissionInfo(perm, 0); true } catch (e: Throwable) { false }

    /**
     * Lista blanca de arranque de DiLink (lo que el usuario haría a mano en «Desactivar apps en segundo
     * plano»): el proveedor de `com.byd.appstartmanagement` y el ajuste global `ssc_whitelist`, como SentryEV.
     * Solo si el gestor de BYD existe y BYDLink aún no está dentro.
     */
    private fun dilinkWhitelist(sh: CarShell, pkg: String) {
        if (sh.shell("pm path $DILINK_PKG")?.contains("package:") != true) return
        if (dilinkWhitelisted(sh, pkg)) return
        sh.shell("content call --uri content://$DILINK_PKG.whitelist/whitelist --method add --arg $pkg")
        val cur = sh.shell("settings get global ssc_whitelist")?.trim().orEmpty()
        if (pkg !in cur.split(',')) {
            val list = if (cur.isEmpty() || cur == "null") pkg else "$cur,$pkg"
            sh.shell("settings put global ssc_whitelist \"$list\"")
        }
        Log.i(TAG, "lista blanca de arranque de DiLink: ${if (dilinkWhitelisted(sh, pkg)) "dentro" else "no se pudo"}")
    }

    fun dilinkWhitelisted(sh: CarShell, pkg: String): Boolean =
        sh.shell("settings get global ssc_whitelist")?.trim()?.split(',')?.contains(pkg) == true

    private const val DILINK_PKG = "com.byd.appstartmanagement"

    fun watchdogRunning(sh: CarShell): Boolean =
        sh.shell("p=\$(cat $PIDFILE 2>/dev/null) && kill -0 \$p 2>/dev/null && echo si")?.trim() == "si"

    /** Versión del script: si el que corre es de otra, se sustituye (el bucle ya cargado no relee el fichero). */
    private const val WATCHDOG_VERSION = "2"
    private const val WATCHDOG_LOG = "/data/local/tmp/bydlink-watchdog.log"

    private fun startWatchdog(sh: CarShell, pkg: String) {
        if (watchdogRunning(sh)) {
            if (sh.shell("head -1 $SCRIPT 2>/dev/null")?.trim() == "# bydlink-watchdog v$WATCHDOG_VERSION") return
            sh.shell("kill \$(cat $PIDFILE) 2>/dev/null; rm -f $PIDFILE")
        }
        val act = "$pkg/.ui.WakeActivity"
        val svc = "$pkg/.service.MainService"
        // Cada 5 s: si la app no está viva, abrir WakeActivity (el ssc de BYD filtra servicios, no actividades).
        // Al quitar el contacto DiLink fuerza la detención de todas las apps y el coche se duerme a los ~10 s si
        // nadie lo retiene: hay que volver antes. Se va si se desinstala (pm path falla 6 veces seguidas; durante
        // una actualización falla solo un momento).
        val script = """
            # bydlink-watchdog v$WATCHDOG_VERSION
            echo $$ > $PIDFILE
            LOG=$WATCHDOG_LOG
            gone=0
            while true; do
              sleep 5
              if ! pidof $pkg >/dev/null 2>&1; then
                if pm path $pkg >/dev/null 2>&1; then gone=0; else gone=${'$'}((gone+1)); [ ${'$'}gone -ge 6 ] && break; continue; fi
                acc=${'$'}(service call accmodemanager 3 2>/dev/null | grep -c "00000000 00000001")
                am start -n $act >/dev/null 2>&1 || am start-foreground-service -n $svc >/dev/null 2>&1
                sleep 2
                echo "${'$'}(date '+%m-%d %H:%M:%S') relanzada acc=${'$'}acc pid=${'$'}(pidof $pkg)" >> ${'$'}LOG
                [ ${'$'}(wc -l < ${'$'}LOG) -gt 300 ] && tail -150 ${'$'}LOG > ${'$'}LOG.tmp && mv ${'$'}LOG.tmp ${'$'}LOG
              fi
            done
            rm -f $PIDFILE
        """.trimIndent()
        sh.shell("cat > $SCRIPT <<'EOF'\n$script\nEOF\nchmod 755 $SCRIPT")
        // setsid: en su propia sesión, para que siga vivo cuando se cierre esta conexión ADB.
        sh.shell("(setsid sh $SCRIPT || nohup sh $SCRIPT) </dev/null >/dev/null 2>&1 &")
    }
}
