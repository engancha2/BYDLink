package com.bydlink.carapp.ui

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import java.util.Locale

/**
 * Android Auto y CarPlay: qué hay en este coche para abrirlos. Mira también las apps de sistema desactivadas u ocultas
 * (DiLink esconde la de fábrica si la región del coche no es la europea), por palabras en el paquete o en el nombre.
 * BYDLink solo las abre: nunca descarga ni instala ninguna.
 */
internal object Projection {
    enum class State { READY, HIDDEN }
    data class Found(val pkg: String, val label: String, val state: State, val system: Boolean, val launch: Intent?)

    /** Palabras de apps que proyectan el móvil. Las "mixtas" (adaptadores y apps chinas de interconexión) valen para los dos. */
    private val AA_PKG = listOf("androidauto", "android.auto", "openheadunit", "headunit", "diauto")
    private val CP_PKG = listOf("carplay")
    private val BOTH_PKG = listOf("carlink", "zlink", "easyconn", "autokit", "phonelink", "phonemirror", "manstep")
    private val AA_LABEL = listOf("android auto", "diauto", "headunit")
    private val CP_LABEL = listOf("carplay", "car play")
    private val BOTH_LABEL = listOf("carlink", "zlink", "easyconn", "autokit", "phone link", "phonelink", "手机互联", "互联", "proyección del móvil", "projection")
    /** El Android Auto del MÓVIL no proyecta nada en el coche: no cuenta. */
    private val SKIP = setOf("com.google.android.projection.gearhead")

    /** (android auto, carplay) de este coche. Llamar fuera del hilo principal (recorre todos los paquetes). */
    fun scan(ctx: Context): Pair<List<Found>, List<Found>> {
        val pm = ctx.packageManager
        @Suppress("DEPRECATION")
        val flags = PackageManager.MATCH_DISABLED_COMPONENTS or PackageManager.MATCH_UNINSTALLED_PACKAGES or
            PackageManager.MATCH_DISABLED_UNTIL_USED_COMPONENTS
        val apps = try { pm.getInstalledApplications(flags) } catch (e: Throwable) { emptyList<ApplicationInfo>() }
        val aa = ArrayList<Found>(); val cp = ArrayList<Found>()
        for (ai in apps) {
            val pkg = ai.packageName
            if (pkg == ctx.packageName || pkg in SKIP) continue
            val p = pkg.lowercase(Locale.ROOT)
            val label = try { ai.loadLabel(pm).toString() } catch (e: Throwable) { pkg }
            val l = label.lowercase(Locale.getDefault())
            val isAa = AA_PKG.any { it in p } || AA_LABEL.any { it in l }
            val isCp = CP_PKG.any { it in p } || CP_LABEL.any { it in l }
            val both = BOTH_PKG.any { it in p } || BOTH_LABEL.any { it in l }
            if (!isAa && !isCp && !both) continue
            val launch = try { pm.getLaunchIntentForPackage(pkg) } catch (e: Throwable) { null }
            val installed = ai.flags and ApplicationInfo.FLAG_INSTALLED != 0
            val ready = installed && ai.enabled && launch != null
            val f = Found(pkg, label, if (ready) State.READY else State.HIDDEN, ai.flags and ApplicationInfo.FLAG_SYSTEM != 0, launch)
            if (isAa || both) aa += f
            if (isCp || both) cp += f
        }
        val order = compareBy<Found>({ it.state != State.READY }, { !it.system }, { it.label.lowercase() })
        return aa.sortedWith(order) to cp.sortedWith(order)
    }
}
