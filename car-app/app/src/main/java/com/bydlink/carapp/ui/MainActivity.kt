package com.bydlink.carapp.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.bydlink.carapp.BuildConfigCompat
import com.bydlink.carapp.car.BmmCameraLoader
import com.bydlink.carapp.car.BydReflect
import com.bydlink.carapp.car.CarShell

/**
 * Pantalla de la app del coche: arriba el QR para vincular el móvil; debajo diagnóstico
 * (qué soporta este vehículo) + atajos para activar la depuración ADB / abrir ajustes de desarrollador.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var out: TextView
    private lateinit var qrImg: ImageView
    private lateinit var qrWait: TextView
    private lateinit var codeTxt: TextView
    private var shownCode: String? = null
    private lateinit var updBtn: Button
    private lateinit var permBtn: Button
    private lateinit var updRow: Button
    private val ui = Handler(Looper.getMainLooper())

    /** El servicio publica el código al conectar con Firebase; lo vamos mirando mientras la pantalla está abierta. */
    private val pollCode = object : Runnable {
        override fun run() {
            val c = BuildConfigCompat.pairCode(applicationContext)
            if (c != null && c != shownCode) showCode(c)
            // Actualización descargada que no se pudo instalar sola (sin ADB): botón para instalarla.
            val ready = com.bydlink.carapp.update.Updater.readyToInstall(applicationContext)
            updBtn.visibility = if (ready != null) View.VISIBLE else View.GONE
            if (ready != null) updBtn.text = "Instalar actualización $ready"
            val s = com.bydlink.carapp.update.Updater.state(applicationContext)
            val v = s.version ?: s.latestName ?: ""
            updRow.text = when (s.state) {
                "descargando" -> "⬇  Actualizaciones: descargando la $v · ${s.progress.coerceAtLeast(0)} %  ›"
                "esperando" -> "⬇  Actualizaciones: la $v se instalará al estar parado  ›"
                "instalando" -> "⬇  Actualizaciones: instalando la $v…  ›"
                "lista" -> "⬇  Actualizaciones: la $v está lista para instalar  ›"
                "error" -> "⚠  Actualizaciones: no se pudo actualizar a la $v  ›"
                else -> "Actualizaciones: al día (versión ${BuildConfigCompat.appVersion(applicationContext)})  ›"
            }
            updRow.setTextColor(when (s.state) { "descargando", "esperando", "instalando", "lista", "error" -> 0xFFF5B740.toInt(); else -> TEXT })
            val pend = PermissionsActivity.pendingQuick(applicationContext)
            permBtn.text = if (pend == 0) "Permisos y arranque: revisar  ›" else "⚠  Permisos y arranque: falta algo, toca para arreglarlo  ›"
            permBtn.setTextColor(if (pend == 0) TEXT else 0xFFF5B740.toInt())
            ui.postDelayed(this, 2000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(24), dp(24), dp(16)) }
        root.addView(pairCard())
        updBtn = Button(this).apply {
            visibility = View.GONE; textSize = 18f; setTextColor(0xFF05240F.toInt())
            background = GradientDrawable().apply { setColor(ACCENT); cornerRadius = dp(14).toFloat() }
            setOnClickListener { com.bydlink.carapp.update.Updater.installNow(this@MainActivity) }
        }
        root.addView(updBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64)).apply { topMargin = dp(16) })
        permBtn = Button(this).apply {
            textSize = 17f; isAllCaps = false; setTextColor(TEXT)
            background = GradientDrawable().apply { setColor(CARD); cornerRadius = dp(14).toFloat() }
            setOnClickListener { startActivity(Intent(this@MainActivity, PermissionsActivity::class.java)) }
        }
        root.addView(permBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(60)).apply { topMargin = dp(12) })
        updRow = Button(this).apply {
            textSize = 17f; isAllCaps = false; setTextColor(TEXT)
            background = GradientDrawable().apply { setColor(CARD); cornerRadius = dp(14).toFloat() }
            setOnClickListener { startActivity(Intent(this@MainActivity, UpdatesActivity::class.java)) }
        }
        root.addView(updRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(60)).apply { topMargin = dp(12) })

        // Pantalla de inicio de BYDLink (opcional): verla o ponerla/quitarla como la de la casita.
        val homeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val homeBtn = Button(this).apply {
            textSize = 17f; isAllCaps = false; setTextColor(TEXT)
            background = GradientDrawable().apply { setColor(CARD); cornerRadius = dp(14).toFloat() }
        }
        fun homeText() { homeBtn.text = if (LauncherMode.isOn(this)) "Quitar BYDLink como pantalla de inicio" else "Usar BYDLink como pantalla de inicio" }
        homeText()
        homeBtn.setOnClickListener {
            homeBtn.isEnabled = false
            Thread {
                val msg = LauncherMode.set(applicationContext, !LauncherMode.isOn(this))
                runOnUiThread { homeBtn.isEnabled = true; homeText(); android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_LONG).show() }
            }.start()
        }
        homeRow.addView(homeBtn, LinearLayout.LayoutParams(0, dp(60), 1f))
        homeRow.addView(Button(this).apply {
            text = "Ver"; textSize = 17f; isAllCaps = false; setTextColor(TEXT)
            background = GradientDrawable().apply { setColor(CARD); cornerRadius = dp(14).toFloat() }
            setOnClickListener { startActivity(Intent(this@MainActivity, HomeActivity::class.java)) }
        }, LinearLayout.LayoutParams(dp(110), dp(60)).apply { leftMargin = dp(12) })
        root.addView(homeRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(60)).apply { topMargin = dp(12) })

        root.addView(TrText(this).apply {
            text = "Diagnóstico y herramientas"; textSize = 15f; setTextColor(MUTED); setPadding(dp(4), dp(28), 0, dp(6))
        })

        fun btn(label: String, onClick: () -> Unit) {
            root.addView(Button(this).apply { text = label; setOnClickListener { onClick() } })
        }
        btn("Abrir opciones de desarrollador") { openDevOptions() }
        btn("Abrir ajustes del sistema") { if (!SystemScreens.systemSettings(this)) line("No se pudo abrir los ajustes.") }
        btn("Intentar activar ADB") { tryEnableAdb() }
        btn("Permitir ADB desde el PC (red)") { networkAdb() }
        btn("SONDEO COMPLETO (enviar)") { sendProbe() }
        btn("Reintentar diagnóstico") { out.text = ""; header(); Thread { runDiagnostics() }.start() }

        out = TrText(this).apply { textSize = 14f; setTextColor(TEXT); setPadding(0, dp(16), 0, 0); gravity = Gravity.START }
        root.addView(out)
        setContentView(ScrollView(this).apply { addView(root) })

        header()
        Thread { runDiagnostics() }.start()
    }

    override fun onResume() {
        super.onResume()
        ui.post(pollCode)
        // Recién actualizada: enseñar las novedades una vez.
        com.bydlink.carapp.update.Updater.takeNews(applicationContext)?.let { (v, notes) ->
            android.app.AlertDialog.Builder(this)
                .setTitle("BYDLink se ha actualizado a la $v")
                .setMessage(notes?.takeIf { it.isNotBlank() } ?: "Ya tienes la versión $v.")
                .setPositiveButton("Vale", null)
                .setNeutralButton("Ver actualizaciones") { _, _ -> startActivity(Intent(this, UpdatesActivity::class.java)) }
                .show()
        }
    }
    override fun onPause() { super.onPause(); ui.removeCallbacks(pollCode) }

    // ---------------- Vincular con el móvil (QR) ----------------
    private fun pairCard(): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(24), dp(24), dp(28), dp(24))
            background = GradientDrawable().apply { setColor(CARD); cornerRadius = dp(20).toFloat() }
        }
        // El QR va siempre oscuro sobre blanco: es lo que mejor leen las cámaras.
        val qrBox = FrameLayout(this).apply {
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(14).toFloat() }
        }
        qrImg = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        qrWait = TrText(this).apply {
            text = "Conectando…"; textSize = 16f; setTextColor(0xFF5B6570.toInt()); gravity = Gravity.CENTER
        }
        qrBox.addView(qrImg, FrameLayout.LayoutParams(dp(QR_DP), dp(QR_DP)))
        qrBox.addView(qrWait, FrameLayout.LayoutParams(dp(QR_DP), dp(QR_DP)))
        card.addView(qrBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(28), 0, 0, 0) }
        fun t(s: String, size: Float, color: Int, top: Int = 0, bold: Boolean = false) = TrText(this).apply {
            text = s; textSize = size; setTextColor(color); setPadding(0, dp(top), 0, 0)
            if (bold) setTypeface(typeface, Typeface.BOLD)
            setLineSpacing(0f, 1.15f)
        }.also { col.addView(it) }
        t("BYDLink · versión ${BuildConfigCompat.appVersion(this)}", 14f, ACCENT, bold = true)
        t("Vincula tu móvil", 26f, Color.WHITE, top = 4, bold = true)
        t("Apunta la cámara del móvil a este código QR y entra con tu cuenta. El coche quedará vinculado solo.", 17f, TEXT, top = 10)
        t("¿Sin cámara? Entra en ${BuildConfigCompat.PHONE_URL.removePrefix("https://").trimEnd('/')} y escribe:", 15f, MUTED, top = 20)
        codeTxt = t("······", 40f, ACCENT, top = 4).apply {
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD); letterSpacing = 0.12f
        }
        card.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return card
    }

    private fun showCode(code: String) {
        val bmp = try { qrBitmap(BuildConfigCompat.pairUrl(code), dp(QR_DP)) } catch (e: Throwable) { null }
        shownCode = code
        codeTxt.text = code
        qrImg.setImageBitmap(bmp)
        qrWait.visibility = if (bmp != null) View.GONE else View.VISIBLE
        if (bmp == null) qrWait.text = "No se pudo dibujar el QR.\nUsa el código."
    }

    /** QR nítido: un píxel por módulo y luego escalado entero sin suavizado. */
    private fun qrBitmap(text: String, sizePx: Int): Bitmap {
        val hints = mapOf(EncodeHintType.MARGIN to 1, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M)
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, hints)
        val w = m.width
        val px = IntArray(w * w) { if (m[it % w, it / w]) Color.BLACK else Color.WHITE }
        val small = Bitmap.createBitmap(px, w, w, Bitmap.Config.ARGB_8888)
        val k = maxOf(1, sizePx / w)
        return Bitmap.createScaledBitmap(small, w * k, w * k, false)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun header() {
        log("BYDLink — app del coche · versión ${BuildConfigCompat.appVersion(this)}")
        log("ID del coche: ${BuildConfigCompat.carId(this)}\n")
    }

    private fun open(action: String) {
        try { startActivity(Intent(action)) } catch (e: Throwable) { line("No se pudo abrir: ${e.message}") }
    }
    private fun openDevOptions() {
        if (!SystemScreens.devOptions(this)) return line("No se pudo abrir las opciones de desarrollador.")
        line("\nEn BYD se entra con su código de verificación. Dentro, activa la depuración USB (ADB) y, si existe, la inalámbrica.")
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

    /** Ejecuta el sondeo de solo-lectura y lo envía por Firebase (+ archivo en /sdcard). */
    private fun sendProbe() {
        line("\nEjecutando sondeo completo…")
        Thread {
            val result = try { com.bydlink.carapp.car.Prober.run(applicationContext) } catch (e: Throwable) { "ERR: ${e.message}" }
            try { java.io.File(getExternalFilesDir(null), "probe.txt").writeText(result) } catch (e: Throwable) {}
            try { java.io.File("/sdcard/BYDLink-probe.txt").writeText(result) } catch (e: Throwable) {}
            val carId = BuildConfigCompat.carId(this)
            val write = {
                com.bydlink.carapp.App.db.getReference("cars/$carId/probe").setValue(result)
                line("  Sondeo enviado ✓ (${result.length} caracteres). Avísame.")
            }
            val auth = com.google.firebase.auth.FirebaseAuth.getInstance()
            if (auth.currentUser != null) write()
            else auth.signInAnonymously().addOnSuccessListener { write() }
                .addOnFailureListener { line("  Sin conexión. Guardado en /sdcard/BYDLink-probe.txt") }
        }.start()
    }

    private fun runDiagnostics() {
        // 1) ADB local → conceder permisos + abrir ADB en red
        line("Probando ADB local (localhost:5555)…")
        val shell = CarShell(filesDir)
        if (shell.connect(BuildConfigCompat.adbHost(this)) && shell.works()) {
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

    private companion object {
        const val QR_DP = 240
        val CARD = 0xFF151B23.toInt()
        val TEXT = 0xFFC9D1D9.toInt()
        val MUTED = 0xFF8B96A1.toInt()
        val ACCENT = 0xFF2FD27A.toInt()
    }
}
