package com.bydlink.carapp.auto

import java.math.BigInteger
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPrivateKeySpec
import java.security.spec.ECPublicKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Avisos al móvil con la app cerrada, mandados DESDE EL COCHE sin servidor propio: Web Push estándar
 * (RFC 8030), cifrado aes128gcm (RFC 8291) e identificación VAPID con ES256 (RFC 8292). El móvil se
 * suscribe en la web con la clave pública del coche y el coche manda cada aviso al servicio de avisos
 * del navegador (Google, Mozilla, Apple, Microsoft), que lo entrega aunque la web no esté abierta.
 * Solo usa la criptografía estándar de Java/Android (también corre en los tests del PC).
 */
object WebPush {

    /** Una suscripción del móvil: dirección del servicio de avisos y sus dos claves (Base64 url). */
    data class Sub(val endpoint: String, val p256dh: String, val auth: String)

    // Solo se manda a los servicios de avisos de los navegadores (nunca a una dirección cualquiera).
    private val HOSTS = listOf("fcm.googleapis.com", "push.services.mozilla.com", "push.apple.com", "notify.windows.com")
    fun allowed(endpoint: String): Boolean = try {
        val u = URL(endpoint)
        u.protocol == "https" && HOSTS.any { u.host == it || u.host.endsWith(".$it") }
    } catch (e: Throwable) { false }

    /** Tamaño máximo del texto del aviso (el servicio admite 4096 bytes ya cifrados). */
    const val MAX_PAYLOAD = 3000

    // Parámetros de la curva P-256 sacados de una clave generada: vale en cualquier Android y en el JVM.
    private val curve: ECParameterSpec by lazy { (newKeyPair().public as ECPublicKey).params }

    fun newKeyPair(): KeyPair =
        KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    /** Clave pública "en crudo" (65 bytes: 0x04 + X + Y), como la piden los navegadores. */
    fun rawPublic(k: ECPublicKey): ByteArray = byteArrayOf(4) + fixed(k.w.affineX) + fixed(k.w.affineY)
    fun rawPrivate(k: ECPrivateKey): ByteArray = fixed(k.s)

    fun publicKey(raw: ByteArray): ECPublicKey {
        require(raw.size == 65 && raw[0] == 4.toByte()) { "Clave pública P-256 no válida" }
        val p = ECPoint(BigInteger(1, raw.copyOfRange(1, 33)), BigInteger(1, raw.copyOfRange(33, 65)))
        return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(p, curve)) as ECPublicKey
    }
    fun privateKey(raw: ByteArray): ECPrivateKey =
        KeyFactory.getInstance("EC").generatePrivate(ECPrivateKeySpec(BigInteger(1, raw), curve)) as ECPrivateKey
    fun keyPair(priv: ByteArray, pub: ByteArray) = KeyPair(publicKey(pub), privateKey(priv))

    private fun fixed(n: BigInteger): ByteArray {
        val b = n.toByteArray()
        return when {
            b.size == 32 -> b
            b.size > 32 -> b.copyOfRange(b.size - 32, b.size)
            else -> ByteArray(32 - b.size) + b
        }
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(key, "HmacSHA256")); doFinal(data) }

    /** HKDF-SHA256 de un solo bloque (aquí nunca hacen falta más de 32 bytes). */
    private fun hkdf(salt: ByteArray, ikm: ByteArray, info: ByteArray, len: Int): ByteArray =
        hmac(hmac(salt, ikm), info + byteArrayOf(1)).copyOf(len)

    private fun info(s: String) = s.toByteArray(Charsets.US_ASCII) + byteArrayOf(0)

    /**
     * Cifra el aviso para un móvil (RFC 8291, un solo registro de 4096). La clave efímera [asKeys] y la
     * [salt] son nuevas en cada aviso; solo se fijan en los tests para comparar con el RFC.
     */
    fun encrypt(plain: ByteArray, uaPublic: ByteArray, authSecret: ByteArray,
                asKeys: KeyPair = newKeyPair(),
                salt: ByteArray = ByteArray(16).also { SecureRandom().nextBytes(it) }): ByteArray {
        require(plain.size <= 3993) { "Aviso demasiado largo" }
        val asPublic = rawPublic(asKeys.public as ECPublicKey)
        val ecdh = KeyAgreement.getInstance("ECDH").run {
            init(asKeys.private); doPhase(publicKey(uaPublic), true); generateSecret()
        }
        val ikm = hkdf(authSecret, ecdh, info("WebPush: info") + uaPublic + asPublic, 32)
        val cek = hkdf(salt, ikm, info("Content-Encoding: aes128gcm"), 16)
        val nonce = hkdf(salt, ikm, info("Content-Encoding: nonce"), 12)
        val gcm = Cipher.getInstance("AES/GCM/NoPadding")
        gcm.init(Cipher.ENCRYPT_MODE, SecretKeySpec(cek, "AES"), GCMParameterSpec(128, nonce))
        val ct = gcm.doFinal(plain + byteArrayOf(2))   // 2 = último registro, sin relleno
        val rs = 4096
        val header = salt + byteArrayOf((rs ushr 24).toByte(), (rs ushr 16).toByte(), (rs ushr 8).toByte(), rs.toByte()) +
            byteArrayOf(asPublic.size.toByte()) + asPublic
        return header + ct
    }

    /** Cabecera Authorization VAPID: un JWT ES256 para el servicio de avisos, válido 12 h. */
    fun vapidAuth(endpoint: String, subject: String, keys: KeyPair, now: Long = System.currentTimeMillis()): String {
        val u = URL(endpoint)
        val aud = u.protocol + "://" + u.host + (if (u.port != -1 && u.port != u.defaultPort) ":${u.port}" else "")
        val head = B64.url("""{"typ":"JWT","alg":"ES256"}""".toByteArray())
        val body = B64.url("""{"aud":"$aud","exp":${now / 1000 + 12 * 3600},"sub":"$subject"}""".toByteArray())
        val der = Signature.getInstance("SHA256withECDSA").run {
            initSign(keys.private); update("$head.$body".toByteArray(Charsets.US_ASCII)); sign()
        }
        return "vapid t=$head.$body.${B64.url(derToRaw(der))}, k=${B64.url(rawPublic(keys.public as ECPublicKey))}"
    }

    /** Java firma en DER; los JWT llevan r y s pegados (64 bytes). */
    internal fun derToRaw(der: ByteArray): ByteArray {
        var i = 2
        if (der[1].toInt() and 0x80 != 0) i += der[1].toInt() and 0x7f
        fun int(): ByteArray {
            require(der[i] == 0x02.toByte()) { "Firma no válida" }
            val len = der[i + 1].toInt() and 0xff
            val v = der.copyOfRange(i + 2, i + 2 + len)
            i += 2 + len
            return fixed(BigInteger(1, v))
        }
        return int() + int()
    }

    /**
     * Manda un aviso ya preparado (texto JSON) a un móvil. Devuelve el código HTTP del servicio de avisos:
     * 201 = aceptado; 404/410 = la suscripción ya no existe; 401/403 = la clave del coche no coincide.
     */
    fun send(sub: Sub, payload: ByteArray, vapid: KeyPair, subject: String,
             ttlSec: Int = 86_400, urgency: String = "normal"): Int {
        require(allowed(sub.endpoint)) { "Dirección de avisos no admitida" }
        val body = encrypt(payload, B64.decode(sub.p256dh), B64.decode(sub.auth))
        val c = URL(sub.endpoint).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"; c.doOutput = true
            c.connectTimeout = 15_000; c.readTimeout = 15_000
            c.setRequestProperty("TTL", ttlSec.toString())
            c.setRequestProperty("Urgency", urgency)
            c.setRequestProperty("Content-Type", "application/octet-stream")
            c.setRequestProperty("Content-Encoding", "aes128gcm")
            c.setRequestProperty("Authorization", vapidAuth(sub.endpoint, subject, vapid))
            c.setFixedLengthStreamingMode(body.size)
            c.outputStream.use { it.write(body) }
            return c.responseCode
        } finally { c.disconnect() }
    }
}
