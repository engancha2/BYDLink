package com.bydlink.carapp.service

import android.app.job.JobParameters
import android.app.job.JobService
import android.content.Intent
import androidx.core.content.ContextCompat

/**
 * Job periódico (cada ~15 min) que se asegura de que el servicio principal sigue vivo.
 * Es la red de seguridad contra el modo hibernación / que el sistema mate el servicio.
 */
class KeepAliveJobService : JobService() {
    override fun onStartJob(params: JobParameters?): Boolean {
        ContextCompat.startForegroundService(this, Intent(this, MainService::class.java))
        return false // trabajo instantáneo, nada en segundo plano
    }
    override fun onStopJob(params: JobParameters?): Boolean = true // reprogramar si lo paran
}
