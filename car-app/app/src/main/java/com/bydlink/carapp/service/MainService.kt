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
import com.bydlink.carapp.camera.CameraController
import com.bydlink.carapp.camera.LiveStreamer
import com.bydlink.carapp.camera.SentryEngine
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

    private lateinit var gateway: CarGateway
    private lateinit var client: CarClient
    private lateinit var location: LocationReader
    private val camera by lazy { CameraController(applicationContext) }
    private val sentry by lazy { SentryEngine() }
    private val streamer by lazy { LiveStreamer(applicationContext) }

    private var wakeLock: PowerManager.WakeLock? = null
    private var running = false
    private var sentryOn = true
    private var streaming = false

    // Config que mantiene y publica el coche (la lee el móvil).
    private val config: MutableMap<String, Any?> = defaultConfig()

    override fun onCreate() {
        super.onCreate()
        startAsForeground()
        acquireWakeLock()
        scheduleKeepAliveJob()

        gateway = if (BuildConfigCompat.USE_BYD_SDK) BydGateway(applicationContext) else MockCarGateway()
        location = LocationReader(applicationContext).also { it.start() }
        client = CarClient(BuildConfigCompat.CAR_ID)

        // La cámara alimenta centinela y streaming.
        camera.onFrame = { frame, w, h ->
            if (sentryOn && (config.sentryMap()["on"] == true)) {
                sentry.sensitivity = (config.sentryMap()["sensitivity"] as? Number)?.toInt() ?: 5
                if (sentry.process(frame, w, h)) onMotionDetected()
            }
            if (streaming) streamer.pushFrame(frame, w, h)
        }

        client.connect(
            onReady = {
                client.publishConfig(config)
                startPublishing()
                client.onCommand { cmd -> handleCommand(cmd) }
                updateCameraRunning()   // arranca el centinela si procede
            },
            onError = { it.printStackTrace() }
        )
    }

    // ---------------- Telemetría ----------------
    private fun startPublishing() {
        running = true
        Thread {
            while (running) {
                try {
                    (gateway as? BydGateway)?.lastLocation = location.last
                    client.publishState(gateway.readState())
                } catch (e: Throwable) { e.printStackTrace() }
                Thread.sleep(2000)
            }
        }.start()
    }

    // ---------------- Centinela ----------------
    private fun onMotionDetected() {
        try {
            client.pushSentryEvent(camera.currentCamera, "v" + System.currentTimeMillis())
            // TODO: guardar un clip .mp4 (MediaCodec) + miniatura en /sdcard/BYDLink/Sentry/...
        } catch (e: Throwable) { e.printStackTrace() }
    }

    private fun updateCameraRunning() {
        val wantCamera = (sentryOn && config.sentryMap()["on"] == true) || streaming
        if (wantCamera) {
            if (!camera.available()) return            // este coche no expone cámaras BMM
            camera.start(camera.currentCamera.ifEmpty { "front" })
        } else {
            camera.stop(); sentry.reset()
        }
    }

    // ---------------- Comandos ----------------
    private fun handleCommand(cmd: Command) {
        try {
            val p = cmd.payload
            when (cmd.action) {
                "setLock" -> {
                    val locked = p["locked"] as? Boolean ?: true
                    gateway.setLock(locked)
                    // Automatización: subir ventanas al cerrar.
                    if (locked && (config.automationsMap()["autoCloseWindowsOnLock"] == true)) {
                        val st = gateway.readState().windows
                        if (st.fl || st.fr || st.rl || st.rr || st.sunroof) gateway.setWindows(false)
                    }
                }
                "openWindows" -> gateway.setWindows(true)
                "closeWindows" -> gateway.setWindows(false)
                "setClimate" -> gateway.setClimate(p["on"] as? Boolean ?: false, (p["temp"] as? Number)?.toInt() ?: 21)
                "setSeatComfort" -> gateway.setSeatComfort((p["driver"] as? Number)?.toInt(), (p["passenger"] as? Number)?.toInt())
                "loadSeatMemory" -> gateway.loadSeatMemory((p["slot"] as? Number)?.toInt() ?: 0)

                "setSentryConfig" -> { mergeInto(config.sentryMap(), p); applySentry(); publishConfig() }
                "setDashcamConfig" -> { mergeInto(config.dashcamMap(), p); publishConfig() }
                "setAutomation" -> { (p["key"] as? String)?.let { config.automationsMap()[it] = (p["value"] as? Boolean ?: false) }; publishConfig() }
                "setConfig" -> { deepMerge(config, p); applySentry(); publishConfig() }

                "startLiveCam" -> { streaming = true; (p["camera"] as? String)?.let { camera.switchTo(it) } ?: updateCameraRunning(); startSignaling(p["session"] as? String) }
                "switchLiveCam" -> (p["camera"] as? String)?.let { camera.switchTo(it) }
                "stopLiveCam" -> { streaming = false; streamer.stop(); updateCameraRunning() }
            }
            client.respond(cmd.id, true)
        } catch (e: Throwable) {
            client.respond(cmd.id, false, mapOf("error" to (e.message ?: "error")))
        }
    }

    private fun startSignaling(session: String?) {
        if (session == null) return
        try { streamer.start(client.signalingRef(session)) } catch (e: Throwable) { e.printStackTrace() }
        updateCameraRunning()
    }

    private fun applySentry() { sentryOn = config.sentryMap()["on"] == true; updateCameraRunning() }
    private fun publishConfig() = client.publishConfig(config)

    // ---------------- Mantenerse vivo ----------------
    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "bydlink:service").apply { setReferenceCounted(false); acquire() }
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

    private fun startAsForeground() {
        val chId = "bydlink"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(NotificationChannel(chId, "BYDLink", NotificationManager.IMPORTANCE_LOW))
        }
        val notif: Notification = androidx.core.app.NotificationCompat.Builder(this, chId)
            .setContentTitle("BYDLink activo").setContentText("Vigilando el coche")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation).setOngoing(true).build()
        startForeground(1, notif)
    }

    override fun onDestroy() {
        running = false
        try { gateway.stop() } catch (e: Throwable) {}
        try { camera.stop() } catch (e: Throwable) {}
        try { streamer.stop() } catch (e: Throwable) {}
        try { location.stop() } catch (e: Throwable) {}
        try { wakeLock?.release() } catch (e: Throwable) {}
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
        "automations" to mutableMapOf<String, Any?>("autoCloseWindowsOnLock" to true)
    )
}
