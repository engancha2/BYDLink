package com.bydlink.carapp.ui

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.bydlink.carapp.BuildConfigCompat
import com.bydlink.carapp.car.BmmCameraLoader
import com.bydlink.carapp.car.BydReflect
import com.bydlink.carapp.car.CarShell

/**
 * Pantalla de la app del coche: diagnóstico (qué soporta este vehículo) + atajos para
 * activar la depuración ADB / abrir ajustes de desarrollador (como hacía Electro).
 */
class MainActivity : AppCompatActivity() {

    private lateinit var out: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(36, 48, 36, 24) }

        fun btn(label: String, onClick: () -> Unit) {
            root.addView(Button(this).apply { text = label; setOnClickListener { onClick() } })
        }
        btn("Abrir opciones de desarrollador") { openDevOptions() }
        btn("Abrir ajustes del sistema") { open(Settings.ACTION_SETTINGS) }
        btn("Intentar activar ADB") { tryEnableAdb() }
        btn("Permitir ADB desde el PC (red)") { networkAdb() }
        btn("Reintentar diagnóstico") { out.text = ""; header(); Thread { runDiagnostics() }.start() }

        out = TextView(this).apply { textSize = 14f; setPadding(0, 24, 0, 0); gravity = Gravity.START }
        root.addView(ScrollView(this).apply { addView(out) })
        setContentView(root)

        header()
        Thread { runDiagnostics() }.start()
    }

    private fun header() {
        log("BYDLink — app del coche")
        log("ID del coche: ${BuildConfigCompat.carId(this)}\n")
    }

    private fun open(action: String) {
        try { startActivity(Intent(action)) } catch (e: Throwable) { line("No se pudo abrir: ${e.message}") }
    }
    private fun openDevOptions() {
        try { startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }
        catch (e: Throwable) { open(Settings.ACTION_SETTINGS) }
        line("\nEn Opciones de desarrollador activa **Depuración USB** y, si existe, **Depuración inalámbrica**.")
    }

    private fun tryEnableAdb() {
        var appOk = false
        try { Settings.Global.putInt(contentResolver, Settings.Global.ADB_ENABLED, 1); appOk = true } catch (e: Throwable) {}
        try { Settings.Global.putInt(contentResolver, "adb_wifi_enabled", 1) } catch (e: Throwable) {}
        line("\n=== Activar ADB ===")
        line(if (appOk) "La app ha activado ADB ✓ (tenía permiso)."
             else "La app NO puede activarlo directamente (falta WRITE_SECURE_SETTINGS). Pulsa \"Abrir opciones de desarrollador\" y actívalo a mano.")
        // Si ya hubiera ADB local disponible, intentamos dejarlo fijo por shell.
        Thread {
            val sh = CarShell(filesDir)
            if (sh.connect()) {
                sh.shell("settings put global adb_enabled 1")
                sh.shell("settings put global adb_wifi_enabled 1")
                sh.close()
                line("ADB local accesible: dejado activado por shell ✓")
            }
        }.start()
    }

    /** Abre el ADB del coche hacia la red (para conectar desde el PC). */
    private fun networkAdb() {
        com.bydlink.carapp.net.AdbRelay.ensure()
        line("\nPuente ADB activo en el puerto 5556.  Desde el PC:  adb connect IP:5556")
        Thread { val sh = CarShell(filesDir); if (sh.connect()) { sh.openNetworkAdb(); sh.close() } }.start()
    }

    private fun runDiagnostics() {
        // 1) ADB local → conceder permisos + abrir ADB en red
        line("Probando ADB local (localhost:5555)…")
        val shell = CarShell(filesDir)
        if (shell.connect()) {
            line("  ADB conectado ✓   id: " + (shell.shell("id")?.trim() ?: "?"))
            line("  Concediendo permisos (pm grant)…")
            shell.grantPermissions(packageName)
            line("  Abriendo ADB en la red para el PC…")
            shell.openNetworkAdb()
            com.bydlink.carapp.net.AdbRelay.ensure()
            line("  Puente ADB activo en 5556 ✓  (PC:  adb connect IP:5556)")
            shell.close()
        } else {
            line("  ADB no disponible. Usa los botones de arriba para activarlo.")
        }

        // 2) Cámara BMM (ya con permisos concedidos)
        val cam = BmmCameraLoader(applicationContext)
        val jar = cam.available()
        line("\nCámara BMM (bmmcamera.jar): " + (jar ?: "NO encontrado en las rutas conocidas"))
        if (jar != null && cam.load()) {
            line("  Cargado desde: ${cam.loadedFrom()}")
            line("  getCameraNumbers() = ${cam.cameraNumbers()}")
        }

        // 3) SDK BYD
        line("\nSDK BYD (android.hardware.bydauto.*): " + if (BydReflect.sdkPresent()) "presente ✓" else "NO en classpath")

        line("\nListo. Hazme una foto de esta pantalla.")
    }

    private fun line(s: String) = runOnUiThread { out.append(s + "\n") }
    private fun log(s: String) = runOnUiThread { out.append(s + "\n") }
}
