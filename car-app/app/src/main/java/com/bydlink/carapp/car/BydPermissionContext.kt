package com.bydlink.carapp.car

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager

/**
 * Context que se salta la comprobación de los permisos de FIRMA de BYD (BYDAUTO_*_GET/SET).
 * El SDK BYD, al crear el dispositivo con getInstance(context), guarda ese context y lo usa
 * para enforcar permisos en cada lectura/escritura. Pasándole este wrapper, los GET/SET
 * funcionan sin firma de sistema (técnica de byd-apps/BydPermissionContext). Los COMMON sí
 * se conceden de verdad con pm grant (ADB).
 */
class BydPermissionContext(base: Context) : ContextWrapper(base) {

    private fun isByd(p: String?) = p != null && p.startsWith("android.permission.BYDAUTO_")

    override fun enforceCallingOrSelfPermission(permission: String, message: String?) {
        if (isByd(permission)) return
        super.enforceCallingOrSelfPermission(permission, message)
    }

    override fun checkCallingOrSelfPermission(permission: String): Int {
        if (isByd(permission)) return PackageManager.PERMISSION_GRANTED
        return super.checkCallingOrSelfPermission(permission)
    }

    override fun enforcePermission(permission: String, pid: Int, uid: Int, message: String?) {
        if (isByd(permission)) return
        super.enforcePermission(permission, pid, uid, message)
    }

    override fun checkPermission(permission: String, pid: Int, uid: Int): Int {
        if (isByd(permission)) return PackageManager.PERMISSION_GRANTED
        return super.checkPermission(permission, pid, uid)
    }

    // Fuera de una app (el ayudante de shell, con el Context del sistema) no hay Application: se devuelve
    // este mismo wrapper para que el SDK no reciba null.
    override fun getApplicationContext(): Context = baseContext.applicationContext ?: this
}
