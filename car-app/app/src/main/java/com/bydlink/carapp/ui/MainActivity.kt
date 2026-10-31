package com.bydlink.carapp.ui

import android.os.Bundle
import android.view.Gravity
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.bydlink.carapp.BuildConfigCompat
import com.bydlink.carapp.car.BmmCameraLoader
import com.bydlink.carapp.car.CarShell
import java.io.File

/**
 * Pantalla de DIAGNÓSTICO: al abrir la app en el coche, comprueba qué soporta este
 * vehículo (ADB local, cámaras BMM, SDK BYD) y lo muestra. Es el "vistazo por ADB" que
 * necesitamos para el Dolphin Surf, pero automático.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var out: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        out = TextView(this).apply { textSize = 15f; setPadding(40, 60, 40, 40); gravity = Gravity.START }
        setContentView(ScrollView(this).apply { addView(out) })
        log("BYDLink — diagnóstico del coche")
        log("ID del coche: ${BuildConfigCompat.carId(this)}\n")
        Thread { runDiagnostics() }.start()
    }

    private fun runDiagnostics() {
        // 1) ¿Existe el framework de cámara BMM? (sin ADB, solo mirando el fichero)
        val cam = BmmCameraLoader(applicationContext)
        val jar = cam.available()
        line("Cámara BMM (bmmcamera.jar): " + (jar ?: "NO encontrado en las rutas conocidas"))
        if (jar != null && cam.load()) {
            val n = cam.cameraNumbers()
            line("  Cargado desde: ${cam.loadedFrom()}")
            line("  getCameraNumbers() = $n" + if (n == 0) "  (puede requerir permiso/ADB)" else "")
        }

        // 2) ¿Está el SDK android.hardware.bydauto.* presente?
        val sdk = try { Class.forName("android.hardware.bydauto.bodywork.BYDAutoBodyworkDevice"); true } catch (e: Throwable) { false }
        line("\nSDK BYD (android.hardware.bydauto.*): " + if (sdk) "presente" else "NO en classpath (se cargará por reflexión)")

        // 3) ADB local (localhost:5555) con dadb → auto-permisos
        line("\nProbando ADB local (localhost:5555)…")
        val shell = CarShell(filesDir)
        if (shell.connect()) {
            line("  ADB conectado ✓")
            val whoami = shell.shell("id")?.trim()
            line("  id: $whoami")
            val jarByAdb = shell.findBmmJar()
            line("  bmmcamera.jar por ADB: " + (jarByAdb ?: "no visible"))
            shell.close()
        } else {
            line("  ADB no disponible. Activa 'Depuración ADB por red' en la pantalla y reabre.")
        }

        // Código de vinculación con el móvil (lo publica el servicio al conectarse a Firebase).
        line("\n=== Vincular con el móvil ===")
        var code: String? = null
        for (i in 0 until 12) { code = BuildConfigCompat.pairCode(applicationContext); if (code != null) break; Thread.sleep(1000) }
        line(if (code != null) "Código: $code\n  → En la app del móvil: Ajustes → Vincular coche → escribe este código."
             else "Generando código… reabre en unos segundos o mira la notificación de BYDLink.")

        line("\nListo. Pásame esta pantalla y ajusto la cámara/permisos a tu Surf.")
    }

    private fun line(s: String) = runOnUiThread { out.append(s + "\n") }
    private fun log(s: String) = runOnUiThread { out.append(s + "\n") }
}
