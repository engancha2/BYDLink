package com.bydlink.carapp.net

import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Puente TCP para depurar desde el PC pese a que Android 11+ bloquee el ADB por red.
 * Escucha en 0.0.0.0:[listenPort] (socket normal de la app, permitido con INTERNET) y
 * reenvía todo al ADB interno (127.0.0.1:[targetPort]). Desde el PC:  adb connect IP:5556
 */
class AdbRelay(private val listenPort: Int = 5556, private val targetPort: Int = 5555) {

    private var server: ServerSocket? = null
    @Volatile private var running = false

    fun start() {
        if (running) return
        running = true
        Thread {
            try {
                server = ServerSocket(listenPort, 50, InetAddress.getByName("0.0.0.0"))
                while (running) {
                    val client = server!!.accept()
                    Thread { relay(client) }.start()
                }
            } catch (e: Throwable) { e.printStackTrace(); running = false }
        }.start()
    }

    private fun relay(client: Socket) {
        try {
            val target = Socket("127.0.0.1", targetPort)
            val a = pipe(client.getInputStream(), target.getOutputStream())
            val b = pipe(target.getInputStream(), client.getOutputStream())
            a.join(); b.join()
            runCatching { client.close() }; runCatching { target.close() }
        } catch (e: Throwable) { runCatching { client.close() } }
    }

    private fun pipe(inp: InputStream, outp: OutputStream): Thread = Thread {
        try {
            val buf = ByteArray(32768); var n: Int
            while (inp.read(buf).also { n = it } > 0) { outp.write(buf, 0, n); outp.flush() }
        } catch (e: Throwable) { /* fin de conexión */ }
    }.also { it.start() }

    fun isRunning() = running
    fun stop() { running = false; runCatching { server?.close() } }

    companion object {
        private val shared = AdbRelay()
        /** Arranca el puente ADB (idempotente). Desde el PC: adb connect IP:5556 */
        fun ensure() { shared.start() }
        fun running() = shared.isRunning()
    }
}
