package com.bydlink.carapp.auto

import android.content.Context
import android.util.Log
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.ValueEventListener
import org.json.JSONObject
import java.security.KeyPair
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Avisos al móvil con la web cerrada (ver [WebPush]). El coche tiene sus propias claves VAPID (se crean una vez
 * y se guardan aquí, la privada nunca sale del coche) y publica la pública en `cars/{id}/push/key`. Cada móvil
 * que activa los avisos deja su suscripción en `cars/{id}/push/subs/{subId}` (ver protocol/automatizaciones.md).
 */
class Push(ctx: Context, carId: String) {

    private companion object {
        const val TAG = "BYDLink"
        // Quién manda los avisos (lo pide VAPID): la web de BYDLink, nunca el correo de nadie.
        const val SUBJECT = "https://app.bydhack.com"
    }

    private val prefs = ctx.applicationContext.getSharedPreferences("push", Context.MODE_PRIVATE)
    private val ref = com.bydlink.carapp.App.db.getReference("cars/$carId/push")
    private val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "push") }
    private val subs = ConcurrentHashMap<String, Map<String, Any?>>()

    /** Si cambiaran (datos de la app borrados), los móviles tendrían que volver a activar los avisos. */
    private val keys: KeyPair by lazy {
        val pub = prefs.getString("pub", null); val priv = prefs.getString("priv", null)
        if (pub != null && priv != null) {
            try { return@lazy WebPush.keyPair(B64.decode(priv), B64.decode(pub)) }
            catch (e: Throwable) { Log.w(TAG, "avisos: las claves guardadas no valen, se crean otras", e) }
        }
        WebPush.newKeyPair().also { kp ->
            prefs.edit().putString("pub", B64.url(WebPush.rawPublic(kp.public as ECPublicKey)))
                .putString("priv", B64.url(WebPush.rawPrivate(kp.private as ECPrivateKey))).apply()
        }
    }
    private val publicKey: String get() = B64.url(WebPush.rawPublic(keys.public as ECPublicKey))

    fun start() {
        exec.execute { try { ref.child("key").setValue(publicKey) } catch (e: Throwable) { Log.w(TAG, "avisos: no se pudo publicar la clave", e) } }
        ref.child("subs").addValueEventListener(object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) {
                val now = HashMap<String, Map<String, Any?>>()
                s.children.forEach { c ->
                    @Suppress("UNCHECKED_CAST")
                    (c.value as? Map<String, Any?>)?.let { now[c.key ?: return@forEach] = it }
                }
                subs.keys.retainAll(now.keys); subs.putAll(now)
            }
            override fun onCancelled(e: DatabaseError) { Log.w(TAG, "avisos: sin acceso a las suscripciones (${e.message})") }
        })
    }

    /** Cuántos móviles quieren avisos de este tipo. */
    fun count(kind: String): Int = subs.values.count { wants(it, kind) }

    private fun wants(s: Map<String, Any?>, kind: String) = when (kind) {
        "sentry" -> s["sentry"] != false
        "autos" -> s["autos"] != false
        else -> true
    }

    /** Lo mismo que [send] pero sin esperar (para el centinela). */
    fun notify(title: String, body: String, kind: String, tag: String? = null, url: String = "./") {
        if (subs.isEmpty()) return
        exec.execute { send(title, body, kind, tag, url) }
    }

    /**
     * Manda el aviso a los móviles suscritos que lo quieran ([kind]: "sentry", "autos" o "test"; [only] = solo
     * esa suscripción). Espera a que el servicio de avisos responda. Devuelve {sent, failed, error?}.
     */
    fun send(title: String, body: String, kind: String, tag: String? = null, url: String = "./", only: String? = null): Map<String, Any?> {
        val targets = subs.entries.filter { (id, s) -> if (only != null) id == only else wants(s, kind) }
        if (targets.isEmpty())
            return mapOf("sent" to 0, "failed" to 0, "error" to if (only != null) "Este móvil no tiene los avisos activados." else "Ningún móvil tiene los avisos activados.")
        val payload = JSONObject().apply {
            put("t", title.take(80)); put("b", body.take(300)); put("k", kind); put("u", url)
            put("ts", System.currentTimeMillis()); tag?.let { put("tag", it) }
        }.toString().toByteArray()   // con estos topes de texto nunca pasa de WebPush.MAX_PAYLOAD
        val ttl = when (kind) { "sentry" -> 6 * 3600; "test" -> 600; else -> 24 * 3600 }
        val urgency = if (kind == "sentry") "high" else "normal"
        var sent = 0; var failed = 0; var lastErr: String? = null
        for ((id, s) in targets) {
            val sub = WebPush.Sub(s["endpoint"] as? String ?: continue, s["p256dh"] as? String ?: continue, s["auth"] as? String ?: continue)
            if (!WebPush.allowed(sub.endpoint)) { failed++; lastErr = "Dirección de avisos no admitida"; continue }
            val code = try { WebPush.send(sub, payload, keys, SUBJECT, ttl, urgency) } catch (e: Throwable) {
                Log.w(TAG, "avisos: fallo al mandar a $id", e); -1
            }
            when (code) {
                200, 201, 202 -> { sent++; if (s["error"] != null) ref.child("subs/$id/error").removeValue() }
                404, 410 -> { failed++; lastErr = "La suscripción del móvil ha caducado."; ref.child("subs/$id").removeValue() }
                401, 403 -> { failed++; lastErr = "Hay que volver a activar los avisos en el móvil."; ref.child("subs/$id/error").setValue("clave") }
                else -> { failed++; lastErr = if (code < 0) "Sin conexión con el servicio de avisos." else "El servicio de avisos ha respondido $code." }
            }
            Log.i(TAG, "avisos: $kind → $id: $code")
        }
        return mapOf("sent" to sent, "failed" to failed, "error" to lastErr.takeIf { sent == 0 })
    }
}
