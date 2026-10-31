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
import com.bydlink.carapp.car.BydGateway
import com.bydlink.carapp.car.CarGateway
import com.bydlink.carapp.car.MockCarGateway
import com.bydlink.carapp.net.CarClient

/**
 * Servicio en primer plano. Corazón de la app del coche y pensado para NO MORIR:
 *  - foreground service + START_STICKY
 *  - WakeLock parcial → la CPU sigue viva con la pantalla apagada (centinela al aparcar)
 *  - watchdog: se reprograma un reinicio por AlarmManager y un JobScheduler periódico
 *  - onTaskRemoved → se vuelve a lanzar si el sistema lo barre
 *  - (además, por ADB se piden exenciones de Doze/hibernación — ver CarShell)
 */
class MainService : Service() {

    private lateinit var gateway: CarGateway
    private lateinit var client: CarClient
    private var wakeLock: PowerManager.WakeLock? = null
    private var running = false

    override fun onCreate() {
        super.onCreate()
        startAsForeground()
        acquireWakeLock()
        scheduleKeepAliveJob()

        gateway = if (BuildConfigCompat.USE_BYD_SDK) BydGateway(applicationContext) else MockCarGateway()
        client = CarClient(BuildConfigCompat.CAR_ID)
        client.connect(
            onReady = {
                startPublishing()
                client.onCommand { cmd -> handleCommand(cmd) }
            },
            onError = { it.printStackTrace() }
        )
    }

    // -------- Mantenerse vivo --------
    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "bydlink:service").apply {
                setReferenceCounted(false); acquire()
            }
        } catch (e: Throwable) { e.printStackTrace() }
    }

    private fun scheduleKeepAliveJob() {
        try {
            val js = getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            val job = JobInfo.Builder(1001, ComponentName(this, KeepAliveJobService::class.java))
                .setPersisted(true)                 // sobrevive a reinicios
                .setPeriodic(15 * 60 * 1000L)       // cada 15 min (mínimo de Android)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_NONE)
                .build()
            js.schedule(job)
        } catch (e: Throwable) { e.printStackTrace() }
    }

    private fun scheduleRestart() {
        try {
            val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = PendingIntent.getBroadcast(
                this, 0, Intent(this, RestartReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
            )
            am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + 2000, pi)
        } catch (e: Throwable) { e.printStackTrace() }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        scheduleRestart()   // si el usuario/sistema barre la app, relánzala
        super.onTaskRemoved(rootIntent)
    }

    // -------- Lógica --------
    private fun startPublishing() {
        running = true
        Thread {
            while (running) {
                try { client.publishState(gateway.readState()) } catch (e: Throwable) { e.printStackTrace() }
                Thread.sleep(2000)
            }
        }.start()
    }

    private fun handleCommand(cmd: com.bydlink.carapp.model.Command) {
        try {
            when (cmd.action) {
                "setLock"    -> gateway.setLock(cmd.payload["locked"] as? Boolean ?: true)
                "setClimate" -> gateway.setClimate(cmd.payload["on"] as? Boolean ?: false, (cmd.payload["temp"] as? Number)?.toInt() ?: 21)
                "setSeatComfort" -> gateway.setSeatComfort(
                    (cmd.payload["driver"] as? Number)?.toInt(),
                    (cmd.payload["passenger"] as? Number)?.toInt()
                )
                "loadSeatMemory" -> gateway.loadSeatMemory((cmd.payload["slot"] as? Number)?.toInt() ?: 0)
                "startLiveCam", "switchLiveCam", "stopLiveCam" -> {
                    // TODO fase 2: CameraController (BmmCameraLoader) + WebRTC con client.signalingRef(session)
                }
                "openWindows", "closeWindows", "setConfig", "setSentryConfig", "setDashcamConfig", "setAutomation" -> {
                    // TODO: aplicar a la config local (ventanas/centinela/dashcam) y republicar
                }
            }
            client.respond(cmd.id, true)
        } catch (e: Throwable) {
            client.respond(cmd.id, false, mapOf("error" to (e.message ?: "error")))
        }
    }

    private fun startAsForeground() {
        val chId = "bydlink"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(chId, "BYDLink", NotificationManager.IMPORTANCE_LOW))
        }
        val notif: Notification = androidx.core.app.NotificationCompat.Builder(this, chId)
            .setContentTitle("BYDLink activo")
            .setContentText("Vigilando el coche")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .build()
        startForeground(1, notif)
    }

    override fun onDestroy() {
        running = false
        try { gateway.stop() } catch (e: Throwable) {}
        try { wakeLock?.release() } catch (e: Throwable) {}
        scheduleRestart()   // intenta revivir también al ser destruido
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY
}
