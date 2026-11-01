package com.bydlink.carapp.auto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.security.Signature
import java.security.interfaces.ECPublicKey
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class WebPushTest {

    // RFC 8291, apéndice A (ejemplo completo de cifrado de un aviso).
    private val plain = "When I grow up, I want to be a watermelon"
    private val asPriv = "yfWPiYE-n46HLnH0KqZOF1fJJU3MYrct3AELtAQ-oRw"
    private val asPub = "BP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A8"
    private val uaPriv = "q1dXpw3UpT5VOmu_cf_v6ih07Aems3njxI-JWgLcM94"
    private val uaPub = "BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4"
    private val salt = "DGv6ra1nlYgDCS1FRnbzlw"
    private val auth = "BTBZMqHH6r4Tts7J_aSIgg"
    private val expected = "DGv6ra1nlYgDCS1FRnbzlwAAEABBBP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A_yl95bQpu6cVPTpK4Mqgkf1CXztLVBSt2Ks3oZwbuwXPXLWyouBWLVWGNWQexSgSxsj_Qulcy4a-fN"

    @Test fun b64IdaYVuelta() {
        for (n in 0..40) {
            val b = ByteArray(n) { (it * 37 + n).toByte() }
            assertArrayEquals(b, B64.decode(B64.url(b)))
        }
        assertEquals("_-8", B64.url(byteArrayOf(0xff.toByte(), 0xef.toByte())))
        assertArrayEquals(B64.decode("/+8="), B64.decode("_-8"))
    }

    @Test fun clavesEnCrudo() {
        val kp = WebPush.keyPair(B64.decode(asPriv), B64.decode(asPub))
        assertEquals(asPub, B64.url(WebPush.rawPublic(kp.public as ECPublicKey)))
    }

    @Test fun cifradoComoElRfc8291() {
        val out = WebPush.encrypt(plain.toByteArray(), B64.decode(uaPub), B64.decode(auth),
            asKeys = WebPush.keyPair(B64.decode(asPriv), B64.decode(asPub)), salt = B64.decode(salt))
        assertEquals(expected, B64.url(out))
    }

    /** Con claves nuevas: lo que cifra el coche lo descifra el "móvil" (receptor escrito aparte, según el RFC). */
    @Test fun elMovilLoDescifra() {
        val ua = WebPush.newKeyPair()
        val secret = ByteArray(16) { it.toByte() }
        val msg = """{"t":"Centinela","b":"Movimiento cerca del coche · ñ"}""".toByteArray()
        val body = WebPush.encrypt(msg, WebPush.rawPublic(ua.public as ECPublicKey), secret)
        val s = body.copyOfRange(0, 16)
        assertEquals(4096, BigInteger(1, body.copyOfRange(16, 20)).toInt())
        val idLen = body[20].toInt()
        val asPublic = body.copyOfRange(21, 21 + idLen)
        val ct = body.copyOfRange(21 + idLen, body.size)
        fun hmac(k: ByteArray, d: ByteArray) = Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(k, "HmacSHA256")); doFinal(d) }
        fun info(x: String) = x.toByteArray() + byteArrayOf(0)
        val ecdh = KeyAgreement.getInstance("ECDH").run { init(ua.private); doPhase(WebPush.publicKey(asPublic), true); generateSecret() }
        val ikm = hmac(hmac(secret, ecdh), info("WebPush: info") + WebPush.rawPublic(ua.public as ECPublicKey) + asPublic + byteArrayOf(1))
        val prk = hmac(s, ikm)
        val cek = hmac(prk, info("Content-Encoding: aes128gcm") + byteArrayOf(1)).copyOf(16)
        val nonce = hmac(prk, info("Content-Encoding: nonce") + byteArrayOf(1)).copyOf(12)
        val dec = Cipher.getInstance("AES/GCM/NoPadding").run { init(Cipher.DECRYPT_MODE, SecretKeySpec(cek, "AES"), GCMParameterSpec(128, nonce)); doFinal(ct) }
        assertEquals(2.toByte(), dec.last())
        assertArrayEquals(msg, dec.copyOf(dec.size - 1))
    }

    @Test fun vapidFirmaBien() {
        val kp = WebPush.newKeyPair()
        val h = WebPush.vapidAuth("https://fcm.googleapis.com/fcm/send/abc", "https://app.bydhack.com", kp, now = 1_700_000_000_000)
        val m = Regex("""^vapid t=([^.]+)\.([^.]+)\.([^,]+), k=(.+)$""").find(h)!!
        val (head, body, sig, k) = m.destructured
        assertEquals("""{"typ":"JWT","alg":"ES256"}""", String(B64.decode(head)))
        assertEquals("""{"aud":"https://fcm.googleapis.com","exp":1700043200,"sub":"https://app.bydhack.com"}""", String(B64.decode(body)))
        assertEquals(B64.url(WebPush.rawPublic(kp.public as ECPublicKey)), k)
        val raw = B64.decode(sig)
        assertEquals(64, raw.size)
        // r||s → DER para comprobarla con Java.
        fun derInt(b: ByteArray): ByteArray { val v = BigInteger(1, b).toByteArray(); return byteArrayOf(2, v.size.toByte()) + v }
        val seq = derInt(raw.copyOfRange(0, 32)) + derInt(raw.copyOfRange(32, 64))
        val der = byteArrayOf(0x30, seq.size.toByte()) + seq
        val ok = Signature.getInstance("SHA256withECDSA").run { initVerify(kp.public); update("$head.$body".toByteArray()); verify(der) }
        assertTrue(ok)
    }

    @Test fun soloServiciosDeAvisos() {
        assertTrue(WebPush.allowed("https://fcm.googleapis.com/fcm/send/x"))
        assertTrue(WebPush.allowed("https://updates.push.services.mozilla.com/wpush/v2/x"))
        assertTrue(WebPush.allowed("https://web.push.apple.com/QH"))
        assertTrue(WebPush.allowed("https://wns2-db5p.notify.windows.com/w/?token=x"))
        assertFalse(WebPush.allowed("http://fcm.googleapis.com/fcm/send/x"))
        assertFalse(WebPush.allowed("https://evil.com/fcm.googleapis.com"))
        assertFalse(WebPush.allowed("https://fcm.googleapis.com.evil.com/x"))
        assertFalse(WebPush.allowed("https://192.168.5.1/x"))
    }
}
