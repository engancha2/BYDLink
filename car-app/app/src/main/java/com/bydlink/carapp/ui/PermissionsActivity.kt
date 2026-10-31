package com.bydlink.carapp.ui

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import com.bydlink.carapp.App
import com.bydlink.carapp.BuildConfigCompat
import com.bydlink.carapp.car.CarShell
import com.bydlink.carapp.service.KeepAlive
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.ValueEventListener

/**
 * Página de permisos y arranque: qué necesita BYDLink para arrancar sola al encender el coche y no
 * cerrarse nunca, qué falta, y un botón para arreglar cada cosa (o todo de golpe por ADB).
 */
class PermissionsActivity : AppCompatActivity() {

    private enum class St { OK, FALTA, NO_APLICA, MIRANDO }
    private class Btn(val label: String, val run: () -> Unit)
    private class Item(val title: String, val st: St, val detail: String, vararg val actions: Btn)

    private lateinit var list: LinearLayout
    private lateinit var summary: TextView
    private lateinit var fixBtn: Button
    private val ui = Handler(Looper.getMainLooper())
    @Volatile private var adbOk: Boolean? = null
    @Volatile private var watchdogOk: Boolean? = null
    @Volatile private var dilinkOk: Boolean? = null
    @Volatile private var fixing = false
    private var online: Boolean? = null
    private var shown = ""
    private val connRef by lazy { App.db.getReference(".info/connected") }
    private val connListener = object : ValueEventListener {
        override fun onDataChange(s: DataSnapshot) { online = s.getValue(Boolean::class.java) == true; render() }
        override fun onCancelled(e: DatabaseError) {}
    }
    private val tick = object : Runnable { override fun run() { render(); ui.postDelayed(this, 3000) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(20), dp(24), dp(24)) }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(TextView(this).apply {
            text = "Permisos y arranque"; textSize = 26f; setTextColor(Color.WHITE); setTypeface(typeface, Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(Button(this).apply { text = "Volver"; isAllCaps = false; setOnClickListener { finish() } })
        root.addView(head)
        root.addView(TextView(this).apply {
            text = "Para que BYDLink arranque sola al encender el coche, no se cierre nunca y pueda vigilarlo."
            textSize = 16f; setTextColor(TEXT); setPadding(0, dp(6), 0, dp(14))
        })
        summary = TextView(this).apply { textSize = 18f; setTypeface(typeface, Typeface.BOLD); setPadding(0, 0, 0, dp(10)) }
        root.addView(summary)
        fixBtn = Button(this).apply {
            text = "Arreglarlo todo"; textSize = 18f; isAllCaps = false; setTextColor(0xFF05240F.toInt())
            background = GradientDrawable().apply { setColor(ACCENT); cornerRadius = dp(14).toFloat() }
            setOnClickListener { fixAll() }
        }
        root.addView(fixBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(60)))
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(8), 0, 0) }
        root.addView(list)
        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() {
        super.onResume()
        connRef.addValueEventListener(connListener)
        ui.post(tick)
        probe()
    }

    override fun onPause() {
        super.onPause()
        connRef.removeEventListener(connListener)
        ui.removeCallbacks(tick)
    }

    @Deprecated("API antigua; basta con repintar")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        render()
    }

    /** Lo que depende del ADB se mira en segundo plano (tarda un poco). */
    private fun probe() = Thread {
        val sh = CarShell(filesDir)
        try {
            val ok = sh.connect(BuildConfigCompat.adbHost(this)) && sh.works()
            adbOk = ok
            watchdogOk = ok && KeepAlive.watchdogRunning(sh)
            dilinkOk = ok && KeepAlive.dilinkWhitelisted(sh, packageName)
        } finally { sh.close() }
        ui.post { render() }
    }.start()

    private fun fixAll() {
        if (fixing) return
        fixing = true; render()
        Thread {
            val ok = KeepAlive.prepare(applicationContext)
            ui.post {
                fixing = false
                Toast.makeText(this, if (ok) "Aplicado ✓" else "No hay ADB: actívalo primero (punto «ADB del coche»)", Toast.LENGTH_LONG).show()
                probe()
            }
        }.start()
    }

    private fun items(): List<Item> {
        val adb = adbOk
        val prefs = getSharedPreferences("bydlink", Context.MODE_PRIVATE)
        val out = mutableListOf<Item>()

        out += Item("Conexión a internet",
            when (online) { true -> St.OK; false -> St.FALTA; null -> St.MIRANDO },
            when (online) { true -> "Conectada: el móvil puede ver y controlar el coche."
                            false -> "Sin conexión. El coche necesita datos móviles o WiFi."
                            null -> "Comprobando…" })

        out += if (adb == true) Item("ADB del coche", St.OK, "Activo: BYDLink se da sus permisos, se mantiene viva y se actualiza sola.")
        else Item("ADB del coche", if (adb == null) St.MIRANDO else St.FALTA,
            "Activa «Depuración USB» en Opciones de desarrollador (y «Depuración inalámbrica» si aparece). " +
            "Si sale un aviso para permitir la depuración, acéptalo.",
            Btn("Opciones de desarrollador") { openDevOptions() })

        val perms = applicablePerms(this)
        val missing = perms.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        out += if (missing.isEmpty()) Item("Permisos: cámara, ubicación y datos del coche", St.OK, "${perms.size} de ${perms.size} concedidos.")
        else Item("Permisos: cámara, ubicación y datos del coche", St.FALTA, "${perms.size - missing.size} de ${perms.size} concedidos.",
            if (adb == true) Btn("Conceder") { fixAll() } else Btn("Pedir") { requestPermissions(missing.toTypedArray(), 1) })

        out += if (batteryOk(this)) Item("Sin ahorro de batería", St.OK, "Android no la duerme para ahorrar batería.")
        else Item("Sin ahorro de batería", St.FALTA, "Android puede dormirla o cerrarla para ahorrar batería.",
            if (adb == true) Btn("Arreglar") { fixAll() }
            else Btn("Permitir") { open(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))) })

        out += if (!backgroundRestricted(this)) Item("Funcionar en segundo plano", St.OK, "Puede seguir trabajando con la pantalla en otra app.")
        else Item("Funcionar en segundo plano", St.FALTA, "Android le tiene restringido el segundo plano.",
            if (adb == true) Btn("Arreglar") { fixAll() } else Btn("Ajustes de la app") { openAppDetails() })

        out += when {
            adb == null -> Item("Vigilante", St.MIRANDO, "Comprobando…")
            adb == false -> Item("Vigilante", St.FALTA, "Necesita el ADB del coche.")
            watchdogOk == true -> Item("Vigilante", St.OK, "Si el sistema o DiLink la cierran (también al apagar el coche), la vuelve a abrir en unos segundos.")
            else -> Item("Vigilante", St.FALTA, "No está en marcha.", Btn("Activar") { fixAll() })
        }

        out += when {
            !installed(this, DILINK_PKG) -> Item("Arranque automático de DiLink", St.NO_APLICA, "Este equipo no tiene el gestor de arranque de BYD.")
            dilinkOk == true -> Item("Arranque automático de DiLink", St.OK,
                "BYDLink está en la lista de arranque de DiLink (puesto automáticamente).", Btn("Revisar") { openDilink() })
            prefs.getBoolean(K_DILINK_OK, false) -> Item("Arranque automático de DiLink", St.OK,
                "Hecho: BYDLink está en OFF en «Desactivar apps en segundo plano». Revísalo tras actualizar el coche.",
                Btn("Revisar") { openDilink() })
            else -> Item("Arranque automático de DiLink", St.FALTA,
                "En el ajuste de BYD «Desactivar apps en segundo plano», busca BYDLink y déjalo en OFF (OFF = puede arrancar sola).",
                Btn("Abrir ajuste") { openDilink() },
                Btn("Ya está en OFF") { prefs.edit().putBoolean(K_DILINK_OK, true).apply(); render() })
        }

        out += if (NotificationManagerCompat.from(this).areNotificationsEnabled()) Item("Notificaciones", St.OK, "Puede avisar en la pantalla del coche.")
        else Item("Notificaciones", St.FALTA, "Necesarias para el aviso fijo que la mantiene viva.",
            if (adb == true) Btn("Arreglar") { fixAll() } else Btn("Permitir") { openAppDetails() })

        val canInstall = Build.VERSION.SDK_INT < 26 || packageManager.canRequestPackageInstalls()
        out += when {
            adb == true -> Item("Actualizaciones automáticas", St.OK, "Se instalan solas en cuanto el coche está parado.")
            canInstall -> Item("Actualizaciones automáticas", St.OK, "Se instalan con un toque en la pantalla de BYDLink.")
            else -> Item("Actualizaciones automáticas", St.FALTA, "Permite que BYDLink instale sus actualizaciones.",
                Btn("Permitir") { open(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))) })
        }
        return out
    }

    private fun render() {
        if (!::list.isInitialized) return
        val its = items()
        val key = its.joinToString("|") { it.title + it.st + it.detail + it.actions.joinToString { b -> b.label } } + fixing
        if (key == shown) return                     // sin cambios: no repintar (no se pierden toques)
        shown = key
        val pend = its.count { it.st == St.FALTA }
        summary.text = when {
            fixing -> "Aplicando…"
            pend > 0 -> "Faltan $pend ${if (pend == 1) "cosa" else "cosas"} por arreglar"
            its.any { it.st == St.MIRANDO } -> "Comprobando…"
            else -> "Todo listo ✓  BYDLink arrancará sola y no se cerrará."
        }
        summary.setTextColor(if (pend > 0) AMBER else ACCENT)
        fixBtn.isEnabled = !fixing
        fixBtn.alpha = if (fixing) .5f else 1f
        list.removeAllViews()
        its.forEach { list.addView(row(it), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) }) }
    }

    private fun row(it: Item): View {
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(14), dp(14), dp(14))
            background = GradientDrawable().apply { setColor(CARD); cornerRadius = dp(14).toFloat() }
        }
        r.addView(TextView(this).apply {
            text = "●"; textSize = 22f; setPadding(0, 0, dp(16), 0)
            setTextColor(when (it.st) { St.OK -> ACCENT; St.FALTA -> AMBER; else -> MUTED })
        })
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(TextView(this).apply { text = it.title; textSize = 18f; setTextColor(Color.WHITE); setTypeface(typeface, Typeface.BOLD) })
        col.addView(TextView(this).apply { text = it.detail; textSize = 14.5f; setTextColor(MUTED); setPadding(0, dp(3), 0, 0); setLineSpacing(0f, 1.1f) })
        r.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        it.actions.forEach { b ->
            r.addView(Button(this).apply { text = b.label; isAllCaps = false; setOnClickListener { b.run() } },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(10) })
        }
        return r
    }

    private fun open(i: Intent) = try { startActivity(i) } catch (e: Throwable) { openAppDetails() }
    private fun openAppDetails() {
        if (!SystemScreens.appDetails(this)) Toast.makeText(this, "No se pudo abrir los ajustes", Toast.LENGTH_SHORT).show()
    }
    private fun openDevOptions() {
        if (!SystemScreens.devOptions(this)) Toast.makeText(this, "No se pudo abrir las opciones de desarrollador", Toast.LENGTH_SHORT).show()
    }
    /** Ajuste de BYD "Desactivar apps en segundo plano" (gestor de arranque de DiLink). */
    private fun openDilink() = try {
        startActivity(Intent(Intent.ACTION_MAIN).setClassName(DILINK_PKG, "$DILINK_PKG.frame.AppStartManagement"))
    } catch (e: Throwable) { openAppDetails() }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val DILINK_PKG = "com.byd.appstartmanagement"
        private const val K_DILINK_OK = "dilinkAutostartOk"
        private val CARD = 0xFF151B23.toInt()
        private val TEXT = 0xFFC9D1D9.toInt()
        private val MUTED = 0xFF8B96A1.toInt()
        private val ACCENT = 0xFF2FD27A.toInt()
        private val AMBER = 0xFFF5B740.toInt()

        /** Permisos que existen en este equipo (los de BYD no existen en un Android normal). */
        private fun applicablePerms(ctx: Context) = CarShell.PERMS.filter {
            it != "android.permission.POST_NOTIFICATIONS" &&
                try { ctx.packageManager.getPermissionInfo(it, 0); true } catch (e: Throwable) { false }
        }
        private fun batteryOk(ctx: Context) = ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)
        private fun backgroundRestricted(ctx: Context) =
            Build.VERSION.SDK_INT >= 28 && ctx.getSystemService(ActivityManager::class.java).isBackgroundRestricted
        private fun installed(ctx: Context, pkg: String) =
            try { ctx.packageManager.getPackageInfo(pkg, 0); true } catch (e: Throwable) { false }

        /** Lo que falta y se puede saber al instante (sin ADB). Para el resumen de la pantalla principal. */
        fun pendingQuick(ctx: Context): Int {
            var n = applicablePerms(ctx).count { ctx.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }.coerceAtMost(1)
            if (!batteryOk(ctx)) n++
            if (backgroundRestricted(ctx)) n++
            if (!NotificationManagerCompat.from(ctx).areNotificationsEnabled()) n++
            if (installed(ctx, DILINK_PKG) && !ctx.getSharedPreferences("bydlink", Context.MODE_PRIVATE).getBoolean(K_DILINK_OK, false)) n++
            return n
        }
    }
}
