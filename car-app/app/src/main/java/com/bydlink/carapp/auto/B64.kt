package com.bydlink.carapp.auto

/**
 * Base64 "url" sin relleno (el de Web Push y los JWT). Propio porque java.util.Base64 pide Android 8 y
 * android.util.Base64 no existe en los tests del PC. Al leer acepta también el Base64 normal y con '='.
 */
object B64 {
    private const val ABC = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    fun url(b: ByteArray): String {
        val sb = StringBuilder((b.size * 4 + 2) / 3)
        var i = 0
        while (i < b.size) {
            val n = (b[i].toInt() and 0xff shl 16) or
                ((if (i + 1 < b.size) b[i + 1].toInt() and 0xff else 0) shl 8) or
                (if (i + 2 < b.size) b[i + 2].toInt() and 0xff else 0)
            sb.append(ABC[n shr 18 and 63]).append(ABC[n shr 12 and 63])
            if (i + 1 < b.size) sb.append(ABC[n shr 6 and 63])
            if (i + 2 < b.size) sb.append(ABC[n and 63])
            i += 3
        }
        return sb.toString()
    }

    fun decode(s: String): ByteArray {
        val clean = s.trim().trimEnd('=').replace('+', '-').replace('/', '_')
        val out = java.io.ByteArrayOutputStream(clean.length * 3 / 4)
        var acc = 0; var bits = 0
        for (c in clean) {
            val v = ABC.indexOf(c)
            require(v >= 0) { "Base64 no válido" }
            acc = (acc shl 6) or v; bits += 6
            if (bits >= 8) { bits -= 8; out.write(acc shr bits and 0xff) }
        }
        return out.toByteArray()
    }
}
