package com.bydlink.carapp.net

import android.util.Log
import com.google.firebase.database.ServerValue

/**
 * Emparejamiento multiusuario. El coche:
 *  1. se da acceso a sí mismo (/access/{carId}/{suUid}) para conservar permisos tras vincular,
 *  2. publica un código corto en /pairing/{code} → {carId}.
 * El móvil escanea el QR (o escribe el código), lee el carId y se añade a /access/{carId}/{suUid}.
 *
 * El código es FIJO: se reutiliza el guardado para que el QR de la pantalla no cambie en cada arranque.
 */
class PairingManager(private val carId: String) {

    private val db = com.bydlink.carapp.App.db
    var code: String = ""
        private set

    fun publish(uid: String, existing: String?, onCode: (String) -> Unit) {
        try {
            val c = existing?.takeIf { VALID.matches(it) } ?: genCode()
            code = c
            // El valor es el propio código: las reglas lo aceptan si /pairing/{código} es de este coche,
            // así el coche recupera su acceso aunque cambie su sesión anónima.
            db.getReference("access/$carId/$uid").setValue(c)
                .addOnFailureListener { Log.e(TAG, "no se pudo guardar el acceso del coche", it) }
            db.getReference("pairing/$c").setValue(
                mapOf("carId" to carId, "ts" to ServerValue.TIMESTAMP)
            ).addOnFailureListener { Log.e(TAG, "no se pudo publicar el código $c", it) }
            Log.i(TAG, "código de vinculación $c (coche $carId)")
            onCode(c)
        } catch (e: Throwable) { e.printStackTrace() }
    }

    private fun genCode(): String {
        val sb = StringBuilder()
        repeat(6) { sb.append(CHARS[(Math.random() * CHARS.length).toInt()]) }
        return sb.toString()
    }

    private companion object {
        const val TAG = "BYDLink"
        const val CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"   // sin caracteres ambiguos
        val VALID = Regex("^[A-Z0-9]{6}$")
    }
}
