package com.bydlink.carapp.net

import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue

/**
 * Emparejamiento multiusuario. El coche:
 *  1. se da acceso a sí mismo (/access/{carId}/{suUid}) para conservar permisos tras vincular,
 *  2. publica un código corto en /pairing/{code} → {carId}.
 * El móvil introduce el código, lee el carId y se añade a /access/{carId}/{suUid}.
 */
class PairingManager(private val carId: String) {

    private val db = FirebaseDatabase.getInstance()
    var code: String = ""
        private set

    fun publish(uid: String, onCode: (String) -> Unit) {
        try {
            // El coche conserva acceso aunque luego se vincule un móvil.
            db.getReference("access/$carId/$uid").setValue(true)
            val c = genCode()
            code = c
            db.getReference("pairing/$c").setValue(
                mapOf("carId" to carId, "ts" to ServerValue.TIMESTAMP)
            )
            onCode(c)
        } catch (e: Throwable) { e.printStackTrace() }
    }

    private fun genCode(): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"   // sin caracteres ambiguos
        val sb = StringBuilder()
        repeat(6) { sb.append(chars[(Math.random() * chars.length).toInt()]) }
        return sb.toString()
    }
}
