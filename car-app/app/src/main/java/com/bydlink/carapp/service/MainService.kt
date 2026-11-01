package com.bydlink.carapp.service

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import com.bydlink.carapp.BuildConfigCompat
import com.bydlink.carapp.camera.CameraCommands
import com.bydlink.carapp.car.BydGateway
import com.bydlink.carapp.car.CarGateway
import com.bydlink.carapp.car.LocationReader
import com.bydlink.carapp.car.MockCarGateway
import com.bydlink.carapp.model.Command
import com.bydlink.carapp.net.CarClient

/**
 * Servicio en primer plano. Integra todo y está pensado para NO MORIR (ver PERSISTENCIA.md):
 * telemetría + control (SDK BYD), GPS, cámara + centinela + streaming WebRTC, y los comandos
 * del móvil por Firebase.
 */
class MainService : Service() {

    private companion object { const val TAG = "BYDLink" }

    private lateinit var gateway: CarGateway
    private lateinit var client: CarClient
    private lateinit var location: LocationReader
    // Cámara (centinela, foto, directo, test): solo con el coche parado en P y a 0 km/h.
    private val camera by lazy {
        CameraCommands(applicationContext) { lastState.let { it != null && it.gear == "P" && it.speed == 0 } }
    }
    // Mandos del coche (con sus comprobaciones de seguridad) y grabador de viajes/cargas.
    private val carCommands by lazy {
        com.bydlink.carapp.car.CarCommands(applicationContext, gateway, { lastState },
            { config.automationsMap()["autoCloseWindowsOnLock"] == true })
    }
    private val recorder by lazy { com.bydlink.carapp.data.Recorder(BuildConfigCompat.carId(applicationContext)) }

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null
    private var running = false
    private var sentryOn = true
    @Volatile private var lastState: com.bydlink.carapp.model.CarState? = null
    @Volatile private var powerKeeper: PowerKeeper? = null

    // Config que mantiene y publica el coche (la lee el móvil).
    private val config: MutableMap<String, Any?> = defaultConfig()

    override fun onCreate() {
        super.onCreate()
        startAsForeground()
        acquireWakeLock()
        scheduleKeepAliveJob()
        listenAcc()
        guardAdb()
        com.bydlink.carapp.net.AdbRelay.ensure()   // puente ADB para depurar desde el PC (5556)
        // Permisos, exenciones de batería y el vigilante por ADB (si hay), sin esperar a abrir la pantalla.
        // Se repite cada 10 min: si algo tumba al vigilante (p. ej. reiniciar adbd), vuelve a lanzarlo.
        Thread {
            Thread.sleep(8_000)
            while (true) { KeepAlive.prepare(applicationContext); Thread.sleep(10 * 60_000L) }
        }.apply { isDaemon = true }.start()

        gateway = if (BuildConfigCompat.USE_BYD_SDK) BydGateway(applicationContext) else MockCarGateway()
        location = LocationReader(applicationContext).also { it.start() }
        client = CarClient(BuildConfigCompat.carId(applicationContext), BuildConfigCompat.appVersion(applicationContext))
        // Leer el coche desde ya (el lanzador lo necesita aunque no haya cobertura); a la nube, cuando haya sesión.
        startPublishing()
        // Mandos desde el propio lanzador: mismas reglas de seguridad que desde el móvil (CarCommands).
        StateHub.commander = { action, payload -> carCommands.handle(action, payload) }
        StateHub.camera = camera

        client.connect(
            onReady = {
                // Cada paso por separado: si uno falla (p. ej. la cámara), los demás siguen.
                fun step(name: String, f: () -> Unit) =
                    try { f(); android.util.Log.i(TAG, "listo: $name") } catch (e: Throwable) { android.util.Log.e(TAG, "falló: $name", e) }
                step("config") { client.publishConfig(config) }
                step("telemetría") { cloudReady = true; StateHub.cloudOk = true }
                step("comandos") { client.onCommand { cmd -> handleCommand(cmd) } }
                step("cámara") { updateCameraRunning() }   // arranca el centinela si procede
                step("vinculación") { publishPairing() }
                // Informe de lo que permite este coche, una vez por versión (para ir ampliando funciones).
                step("sondeo") { com.bydlink.carapp.car.Prober.autoSendIfNeeded(applicationContext, BuildConfigCompat.carId(applicationContext)) }
                // Qué tiene este modelo (la web oculta lo que no hay). En su hilo: lee bastantes cosas del SDK.
                step("capacidades") {
                    if (BuildConfigCompat.USE_BYD_SDK) Thread {
                        try { com.bydlink.carapp.car.Capabilities.publish(applicationContext, BuildConfigCompat.carId(applicationContext)) }
                        catch (e: Throwable) { android.util.Log.e(TAG, "capacidades: fallo", e) }
                    }.start()
                }
                step("energía") {
                    @Suppress("UNCHECKED_CAST")
                    powerKeeper = PowerKeeper(applicationContext, BuildConfigCompat.carId(applicationContext),
                        config = { config["power"] as? Map<String, Any?> ?: emptyMap() },
                        hold = { holdAwake(it) },
                        sdkOk = { sdkStuckSince == 0L })
                }
                // Actualizaciones automáticas: solo se instalan con el coche parado.
                step("actualizaciones") {
                    com.bydlink.carapp.update.Updater(applicationContext, BuildConfigCompat.carId(applicationContext)) {
                        val s = lastState
                        s == null || (s.speed !in 1..300 && s.gear != "D" && s.gear != "R")
                    }.start()
                }
            },
            onError = { it.printStackTrace() }
        )
    }

    // ---------------- Telemetría ----------------
    // Con el contacto quitado, cuando el coche apaga sus módulos (~10 s después), algunas llamadas al SDK
    // no vuelven nunca. Por eso se leen en su propio hilo y con tiempo máximo: si no vuelve, la app sigue
    // viva (latido con hora y posición) y no se le pregunta nada más al coche hasta que conteste.
    @Volatile private var sdkThread: Thread? = null
    private val sdkExec = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "sdk-read").also { sdkThread = it } }
    private val powerExec = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "power") }
    private val cmdExec = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "car-cmd") }
    @Volatile private var sdkStuckSince = 0L
    private var powerTask: java.util.concurrent.Future<*>? = null
    /** true en cuanto hay sesión de Firebase: hasta entonces solo se lee el coche (para el lanzador). */
    @Volatile private var cloudReady = false

    private fun startPublishing() {
        if (running) return
        running = true
        Thread {
            var n = 0
            var pending: java.util.concurrent.Future<com.bydlink.carapp.model.CarState>? = null
            var lastBeat = 0L
            while (running) {
                try {
                    (gateway as? BydGateway)?.lastLocation = location.last
                    val f = pending ?: sdkExec.submit(java.util.concurrent.Callable { gateway.readState() }).also { pending = it }
                    val s = try { f.get(4, java.util.concurrent.TimeUnit.SECONDS).also { pending = null } }
                        catch (e: java.util.concurrent.TimeoutException) { null }
                        catch (e: java.util.concurrent.ExecutionException) { pending = null; throw e.cause ?: e }
                    if (s != null) {
                        if (sdkStuckSince > 0) { android.util.Log.i(TAG, "el SDK del coche vuelve a responder"); sdkStuckSince = 0 }
                        lastState = s
                        StateHub.update(s)                          // el lanzador lo lee de aquí, sin nube
                        if (cloudReady) {
                            client.publishState(s)
                            try { recorder.onState(s) } catch (e: Throwable) { e.printStackTrace() }
                        }
                    } else {
                        StateHub.sdkOk = false
                        if (sdkStuckSince == 0L) { sdkStuckSince = System.currentTimeMillis(); if (cloudReady) reportSdkStuck() }
                        val now = System.currentTimeMillis()
                        if (cloudReady && now - lastBeat > 10_000) { client.publishHeartbeat(location.last); lastBeat = now }
                    }
                    if (n++ % 15 == 0) tickPower()   // cada ~30 s: gobernador de energía
                } catch (e: Throwable) { e.printStackTrace() }
                Thread.sleep(2000)
            }
        }.start()
    }

    /** El gobernador también pregunta al coche (12 V): en su hilo, para no frenar la telemetría si se cuelga. */
    private fun tickPower() {
        if (powerTask?.isDone == false) return
        powerTask = powerExec.submit { powerKeeper?.tick(lastState) }
    }

    /** Dónde se ha quedado colgada la lectura (al registro y a la nube, para afinar por modelo). */
    private fun reportSdkStuck() {
        val stack = sdkThread?.stackTrace?.take(14)?.joinToString("\n") { "${it.className}.${it.methodName}:${it.lineNumber}" }
        android.util.Log.w(TAG, "el SDK del coche no responde (coche dormido):\n$stack")
        try {
            com.bydlink.carapp.App.db.getReference("cars/${BuildConfigCompat.carId(applicationContext)}/diag/sdkStuck")
                .setValue(mapOf("ts" to com.google.firebase.database.ServerValue.TIMESTAMP, "stack" to stack))
        } catch (e: Throwable) { e.printStackTrace() }
    }

    // ---------------- Centinela / cámara ----------------
    // Los eventos del centinela (con miniatura y clip) los publica el módulo de cámara (camera/SentryEngine.kt).
    private fun updateCameraRunning() {
        camera.setSentry(sentryOn && config.sentryMap()["on"] == true,
            (config.sentryMap()["sensitivity"] as? Number)?.toInt() ?: 5)
    }

    // ---------------- Comandos ----------------
    private fun handleCommand(cmd: Command) {
        // Comandos de cámara: en su propio hilo (foto/directo pueden tardar unos segundos), con respuesta real.
        if (camera.dispatch(cmd.action, cmd.payload) { ok, data -> client.respond(cmd.id, ok, data) }) return
        try {
            val p = cmd.payload
            when (cmd.action) {
                "setSentryConfig" -> { mergeInto(config.sentryMap(), p); applySentry(); publishConfig() }
                "setDashcamConfig" -> { mergeInto(config.dashcamMap(), p); publishConfig() }
                "setAutomation" -> { (p["key"] as? String)?.let { config.automationsMap()[it] = (p["value"] as? Boolean ?: false) }; publishConfig() }
                "setConfig" -> { deepMerge(config, p); applySentry(); publishConfig() }

                // El resto son mandos del coche (CarCommands comprueba seguridad y lee de vuelta el estado).
                // En su hilo: una llamada al SDK que no vuelve no puede congelar el hilo principal.
                else -> {
                    if (sdkStuckSince > 0) throw IllegalStateException("El coche está dormido y ahora no responde a mandos.")
                    cmdExec.execute {
                        try {
                            val res = carCommands.handle(cmd.action, p) ?: throw IllegalArgumentException("Acción desconocida: ${cmd.action}")
                            client.respond(cmd.id, true, res)
                        } catch (e: Throwable) {
                            client.respond(cmd.id, false, mapOf("error" to (e.message ?: "error")))
                        }
                    }
                    return
                }
            }
            client.respond(cmd.id, true)
        } catch (e: Throwable) {
            client.respond(cmd.id, false, mapOf("error" to (e.message ?: "error")))
        }
    }

    private fun applySentry() { sentryOn = config.sentryMap()["on"] == true; updateCameraRunning() }
    private fun publishConfig() = client.publishConfig(config)

    // ---------------- Mantenerse vivo ----------------
    private fun acquireWakeLock() {
        // WiFi a pleno rendimiento mientras vive el servicio (con el coche aparcado no se corta la conexión).
        try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
            @Suppress("DEPRECATION")
            wifiLock = wm.createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "bydlink:wifi")
                .apply { setReferenceCounted(false); acquire() }
        } catch (e: Throwable) { e.printStackTrace() }
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "bydlink:service").apply { setReferenceCounted(false); acquire() }
        } catch (e: Throwable) { e.printStackTrace() }
    }
    /**
     * Como Electro: si alguien (o DiLink al reiniciarse) apaga la depuración ADB, volver a encenderla. Sin ADB
     * no hay vigilante, ni permisos, ni actualizaciones solas. Se puede quitar con config.adbGuard = false.
     */
    private fun guardAdb() {
        val cr = contentResolver
        val key = android.provider.Settings.Global.ADB_ENABLED
        val fix = {
            if (config["adbGuard"] as? Boolean != false &&
                android.provider.Settings.Global.getInt(cr, key, 1) == 0 &&
                checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                try { android.provider.Settings.Global.putInt(cr, key, 1); android.util.Log.i(TAG, "ADB estaba apagado: reactivado") }
                catch (e: Throwable) { android.util.Log.e(TAG, "no se pudo reactivar ADB", e) }
            }
        }
        try {
            cr.registerContentObserver(android.provider.Settings.Global.getUriFor(key), false,
                object : android.database.ContentObserver(android.os.Handler(android.os.Looper.getMainLooper())) {
                    override fun onChange(selfChange: Boolean) = fix()
                })
        } catch (e: Throwable) { e.printStackTrace() }
        fix()
    }

    /** Lo decide el gobernador de energía: soltarlos deja que el coche apagado se duerma (12 V, tiempo...). */
    private fun holdAwake(on: Boolean) {
        try {
            if (on) { if (wakeLock?.isHeld == false) wakeLock?.acquire(); if (wifiLock?.isHeld == false) wifiLock?.acquire() }
            else { if (wakeLock?.isHeld == true) wakeLock?.release(); if (wifiLock?.isHeld == true) wifiLock?.release() }
        } catch (e: Throwable) { e.printStackTrace() }
    }
    private fun scheduleKeepAliveJob() {
        try {
            val js = getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            js.schedule(JobInfo.Builder(1001, ComponentName(this, KeepAliveJobService::class.java))
                .setPersisted(true).setPeriodic(15 * 60 * 1000L).build())
        } catch (e: Throwable) { e.printStackTrace() }
    }
    private fun scheduleRestart() {
        try {
            val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = PendingIntent.getBroadcast(this, 0, Intent(this, RestartReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0))
            am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + 2000, pi)
        } catch (e: Throwable) { e.printStackTrace() }
    }
    override fun onTaskRemoved(rootIntent: Intent?) { scheduleRestart(); super.onTaskRemoved(rootIntent) }

    private fun buildNotification(text: String): Notification {
        val chId = "bydlink"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(NotificationChannel(chId, "BYDLink", NotificationManager.IMPORTANCE_LOW))
        }
        return androidx.core.app.NotificationCompat.Builder(this, chId)
            .setContentTitle("BYDLink activo").setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation).setOngoing(true).build()
    }
    private fun startAsForeground() = startForeground(1, buildNotification("Vigilando el coche"))
    private fun updateNotification(text: String) {
        try { getSystemService(NotificationManager::class.java).notify(1, buildNotification(text)) } catch (e: Throwable) {}
    }

    // ---------------- Contacto (ACC) ----------------
    // BYD avisa al dar y quitar el contacto. Se publica en meta/acc para que el móvil distinga
    // "coche apagado (dormido)" de "sin cobertura".
    private val accReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val on = when (i.action) {
                "com.byd.action.ACC_ON", "com.byd.action.IGN_ON" -> true
                "com.byd.action.ACC_OFF" -> false
                else -> null   // ACC_MODE_CHANGED: el modo exacto se ve en status.powerState
            }
            android.util.Log.i(TAG, "contacto: ${i.action}")
            try {
                com.bydlink.carapp.App.db.getReference("cars/${BuildConfigCompat.carId(applicationContext)}/meta/acc")
                    .setValue(mapOf("on" to on, "event" to i.action, "ts" to com.google.firebase.database.ServerValue.TIMESTAMP))
            } catch (e: Throwable) { e.printStackTrace() }
        }
    }
    private fun listenAcc() {
        val f = android.content.IntentFilter().apply {
            listOf("com.byd.action.ACC_ON", "com.byd.action.ACC_OFF", "com.byd.action.IGN_ON",
                "com.bydaccmode.ACC_MODE_CHANGED", "com.byd.accmode.ACC_MODE_CHANGED").forEach { addAction(it) }
        }
        // Avisos de otra app (el sistema de BYD): hay que declararlo exportado en Android 14+.
        try { androidx.core.content.ContextCompat.registerReceiver(this, accReceiver, f, androidx.core.content.ContextCompat.RECEIVER_EXPORTED) }
        catch (e: Throwable) { e.printStackTrace() }
    }

    private fun publishPairing() {
        val uid = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid
            ?: return android.util.Log.w(TAG, "vinculación: sin sesión de Firebase").let { }
        com.bydlink.carapp.net.PairingManager(BuildConfigCompat.carId(applicationContext))
            .publish(uid, BuildConfigCompat.pairCode(applicationContext)) { code ->
            BuildConfigCompat.setPairCode(applicationContext, code)
            updateNotification("Vincula el móvil con el código: $code")
        }
    }

    override fun onDestroy() {
        running = false
        try { gateway.stop() } catch (e: Throwable) {}
        try { camera.shutdown() } catch (e: Throwable) {}
        try { location.stop() } catch (e: Throwable) {}
        try { wakeLock?.release() } catch (e: Throwable) {}
        try { wifiLock?.release() } catch (e: Throwable) {}
        try { unregisterReceiver(accReceiver) } catch (e: Throwable) {}
        scheduleRestart()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    // ---------------- Config helpers ----------------
    @Suppress("UNCHECKED_CAST")
    private fun MutableMap<String, Any?>.sentryMap() = this["sentry"] as MutableMap<String, Any?>
    @Suppress("UNCHECKED_CAST")
    private fun MutableMap<String, Any?>.dashcamMap() = this["dashcam"] as MutableMap<String, Any?>
    @Suppress("UNCHECKED_CAST")
    private fun MutableMap<String, Any?>.automationsMap() = this["automations"] as MutableMap<String, Any?>

    private fun mergeInto(dst: MutableMap<String, Any?>, src: Map<String, Any?>) { for ((k, v) in src) dst[k] = v }
    @Suppress("UNCHECKED_CAST")
    private fun deepMerge(dst: MutableMap<String, Any?>, src: Map<String, Any?>) {
        for ((k, v) in src) {
            val cur = dst[k]
            if (v is Map<*, *> && cur is MutableMap<*, *>) deepMerge(cur as MutableMap<String, Any?>, v as Map<String, Any?>)
            else dst[k] = v
        }
    }

    private fun defaultConfig(): MutableMap<String, Any?> = mutableMapOf(
        "sentry" to mutableMapOf<String, Any?>("on" to true, "workMode" to "standard", "sensitivity" to 5, "proximityDetection" to true, "shadowFilter" to true),
        "dashcam" to mutableMapOf<String, Any?>("on" to true, "hasOEM" to false, "oemLimitEnabled" to true, "oemStorageLimit" to 32),
        "notifications" to mutableMapOf<String, Any?>("enabled" to true, "flash" to false, "gif" to true, "sentryMovement" to true, "doorOpenClose" to true),
        "overlays" to mutableMapOf<String, Any?>("soc" to true, "seatbelt" to false, "speed" to true),
        "safeLocations" to mutableMapOf<String, Any?>("enabled" to true),
        "automations" to mutableMapOf<String, Any?>("autoCloseWindowsOnLock" to true),
        // Seguir conectado con el coche apagado, con los límites del gobernador de energía (ver PowerKeeper).
        "power" to mutableMapOf<String, Any?>("keepAwake" to true, "maxHours" to 12, "minSoc" to 20, "min12v" to 12.0, "resume12v" to 12.5)
    )
}
