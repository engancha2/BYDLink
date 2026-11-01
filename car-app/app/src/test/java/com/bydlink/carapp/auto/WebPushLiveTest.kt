package com.bydlink.carapp.auto

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Aviso de verdad a un navegador suscrito (solo si existe build/push-live.json, que deja la prueba
 * scratchpad/e2e/push-live.mjs). Comprueba el cifrado y la firma contra el servicio de avisos real.
 */
class WebPushLiveTest {
    @Test fun avisoDeVerdad() {
        val f = File("build/push-live.json")
        assumeTrue("sin suscripción de prueba", f.exists())
        val j = f.readText()
        fun field(k: String) = Regex("\"$k\":\"([^\"]+)\"").find(j)!!.groupValues[1]
        val keys = WebPush.keyPair(B64.decode(field("priv")), B64.decode(field("pub")))
        val sub = WebPush.Sub(field("endpoint"), field("p256dh"), field("auth"))
        val body = """{"t":"BYDLink","b":"Aviso de prueba mandado con el código del coche","k":"test","tag":"live"}""".toByteArray()
        val code = WebPush.send(sub, body, keys, "https://app.bydhack.com", ttlSec = 600, urgency = "high")
        println("servicio de avisos: $code")
        assertTrue("el servicio de avisos ha respondido $code", code in 200..202)
    }
}
