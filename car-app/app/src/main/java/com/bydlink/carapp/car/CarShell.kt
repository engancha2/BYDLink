package com.bydlink.carapp.car

import dadb.AdbKeyPair
import dadb.Dadb
import java.io.File

/**
 * Acceso ADB al PROPIO coche (localhost:5555) con la librería `dadb`, tal y como hacen
 * Vektra, SentryEV y SealNav. Permite auto-concederse permisos (`pm grant`) y nivel
 * `shell` SIN firma de sistema — la pieza que hace el método model-agnostic.
 *
 * Requisito (una vez, lo prepara el usuario): activar "Depuración ADB por red / inalámbrica"
 * en la pantalla del coche. La primera conexión puede mostrar un popup de autorización ADB
 * que hay que aceptar (SentryEV avisa con "toque ABRIR ADB").
 *
 * Nota: las firmas exactas de `dadb` pueden variar según versión; revisar al compilar.
 */
class CarShell(private val filesDir: File) {

    private var dadb: Dadb? = null

    /** Conecta al ADB local del coche. Devuelve true si lo consigue. */
    fun connect(host: String = "localhost", port: Int = 5555): Boolean = try {
        val keyPair = loadOrCreateKeyPair()
        dadb = Dadb.create(host, port, keyPair)
        true
    } catch (e: Throwable) {
        e.printStackTrace(); false
    }

    /** Ejecuta un comando de shell y devuelve su salida (o null si falla). */
    fun shell(cmd: String): String? = try {
        dadb?.shell(cmd)?.allOutput
    } catch (e: Throwable) {
        e.printStackTrace(); null
    }

    /** true si el ADB responde de verdad (`connect()` no conecta hasta el primer comando). */
    fun works(): Boolean = shell("echo ok")?.trim() == "ok"

    /** Instala (o actualiza) un APK sin preguntar, como `adb install -r`. */
    fun install(apk: File): Boolean = try {
        dadb?.install(apk, "-r"); dadb != null
    } catch (e: Throwable) {
        e.printStackTrace(); false
    }

    /**
     * Concede a la app los permisos necesarios para cámaras y SDK BYD.
     * Los permisos COMMON son runtime (grantables por `pm grant`); los de firma GET/SET
     * se saltan en cliente con un Context de permisos (ver BydPermissionContext en byd-apps).
     */
    fun grantPermissions(pkg: String) {
        val perms = listOf("android.permission.WRITE_SECURE_SETTINGS", "android.permission.READ_LOGS") + PERMS
        perms.forEach { shell("pm grant $pkg $it") }
        keepAlive(pkg)
    }

    /** Abre el ADB del coche hacia la RED (0.0.0.0:5555) para poder depurar desde el PC. */
    fun openNetworkAdb() {
        // Reiniciar adbd cierra también el vigilante (ver KeepAlive): solo si de verdad hace falta.
        if (shell("getprop service.adb.tcp.port")?.trim() == "5555") return
        shell("setprop service.adb.tcp.port 5555")
        shell("settings put global adb_enabled 1")
        shell("setprop ctl.restart adbd")   // reinicia adbd escuchando en todas las interfaces
    }

    /**
     * Exenciones para que la app NO muera con el modo hibernación / Doze / "app no usada"
     * (lo que hace Electro para seguir vigilando con el coche aparcado). Vía shell local.
     */
    fun keepAlive(pkg: String) {
        shell("cmd appops set $pkg RUN_IN_BACKGROUND allow")
        shell("cmd appops set $pkg RUN_ANY_IN_BACKGROUND allow")
        // Lista blanca de Doze (no entra en reposo profundo).
        shell("dumpsys deviceidle whitelist +$pkg")
        // Exención de hibernación de apps (Android 12+/API 31; varias variantes según versión).
        shell("cmd app_hibernation set-state --package $pkg --user 0 false")
        shell("cmd app_hibernation set-state --global $pkg false")
        shell("settings put global app_hibernation_enabled 0")
        // No desactivar por inactividad.
        shell("am set-inactive $pkg false")
        // Autoarranque: algunos DiLink tienen su propia lista (como applyBydAutostartWhitelist
        // de SentryEV); si existe, se añade aquí de forma específica del fabricante.
    }

    /** Comprueba si existe el framework de cámara BMM (y en qué ruta). */
    fun findBmmJar(): String? {
        for (p in BmmCameraLoader.JAR_PATHS) {
            val out = shell("ls $p 2>/dev/null")
            if (!out.isNullOrBlank() && !out.contains("No such")) return p
        }
        return null
    }

    private fun loadOrCreateKeyPair(): AdbKeyPair {
        val priv = File(filesDir, "adbkey")
        val pub = File(filesDir, "adbkey.pub")
        if (!priv.exists() || !pub.exists()) {
            // Genera un par de claves RSA y lo guarda. (API a confirmar según versión de dadb.)
            AdbKeyPair.generate(priv, pub)
        }
        return AdbKeyPair.read(priv, pub)
    }

    companion object {
        /** Permisos runtime que la app necesita (también los muestra la página de permisos). */
        val PERMS = listOf(
            "android.permission.CAMERA",
            "android.permission.READ_EXTERNAL_STORAGE",
            "android.permission.WRITE_EXTERNAL_STORAGE",
            "android.permission.ACCESS_FINE_LOCATION",
            "android.permission.POST_NOTIFICATIONS",
            "android.permission.BYDAUTO_AC_COMMON",
            "android.permission.BYDAUTO_BODYWORK_COMMON",
            "android.permission.BYDAUTO_DOOR_LOCK_COMMON",
            "android.permission.BYDAUTO_PANORAMA_COMMON",
            "android.permission.BYDAUTO_TYRE_COMMON",
            "android.permission.BYDAUTO_CHARGING_COMMON",
            "android.permission.BYDAUTO_INSTRUMENT_COMMON",
            "android.permission.BYDAUTO_GEARBOX_COMMON",
            "android.permission.BYDAUTO_SPEED_COMMON",
            "android.permission.BYDAUTO_STATISTIC_COMMON"
        )
    }

    fun close() {
        try { dadb?.close() } catch (e: Throwable) { /* ignore */ }
        dadb = null
    }
}
