package net.andwebcam

import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Plain HTTP responder for ACME http-01 challenges.
 *
 * Let's Encrypt connects to port 80 of the public name and asks for
 * /.well-known/acme-challenge/<token>. The router forwards 80 to the port
 * configured here; this little server exists only to answer that one request,
 * so the camera's TLS listener can stay on its own port. TLS-ALPN-01 would
 * need port 443 and a different handshake entirely; http-01 is enough.
 */
object ChallengeServer {
    private const val TAG = "andWebCam-acme"
    private val tokens = ConcurrentHashMap<String, String>()
    private val pool = Executors.newCachedThreadPool { r ->
        Thread(r, "andwebcam-acme-http").apply { isDaemon = true }
    }

    @Volatile private var socket: ServerSocket? = null
    @Volatile var port: Int = 0
        private set

    fun set(token: String, value: String) {
        tokens[token] = value
    }

    fun clear(token: String) {
        tokens.remove(token)
    }

    fun start(wanted: Int): Boolean {
        stop()
        return try {
            val s = ServerSocket()
            s.reuseAddress = true
            s.bind(InetSocketAddress(wanted))
            socket = s
            port = s.localPort
            Thread({ loop(s) }, "andwebcam-acme-listen").apply { isDaemon = true }.start()
            Log.i(TAG, "ACME challenge responder on $port")
            true
        } catch (e: Exception) {
            Log.w(TAG, "could not bind ACME challenge port $wanted: ${e.message}")
            false
        }
    }

    fun stop() {
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        port = 0
        tokens.clear()
    }

    private fun loop(s: ServerSocket) {
        while (!s.isClosed) {
            val c = try { s.accept() } catch (e: Exception) { if (s.isClosed) return else continue }
            pool.execute { answer(c) }
        }
    }

    private fun answer(c: Socket) {
        try {
            c.soTimeout = 8000
            val input = BufferedInputStream(c.getInputStream())
            val line = readLine(input) ?: return
            val path = line.split(' ').getOrNull(1) ?: ""
            // Drain the request headers; without this a client that sends a
            // body would leave it unread and see a reset.
            while (true) {
                val h = readLine(input) ?: break
                if (h.isEmpty()) break
            }
            val prefix = "/.well-known/acme-challenge/"
            val out = BufferedOutputStream(c.getOutputStream())
            if (path.startsWith(prefix)) {
                val token = path.substring(prefix.length).substringBefore('?')
                val value = tokens[token]
                if (value == null) {
                    write(out, 404, "not found")
                } else {
                    write(out, 200, value)
                }
            } else {
                write(out, 404, "not found")
            }
        } catch (e: Exception) {
            // A malformed probe is not worth a log line; the ACME client
            // reports a failed challenge with the real reason.
        } finally {
            try { c.close() } catch (_: Exception) {}
        }
    }

    private fun readLine(input: BufferedInputStream): String? {
        val sb = StringBuilder()
        while (sb.length < 8192) {
            val b = input.read()
            if (b == -1) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString()
            if (b != '\r'.code) sb.append(b.toChar())
        }
        return sb.toString()
    }

    private fun write(out: BufferedOutputStream, code: Int, body: String) {
        val bytes = body.toByteArray()
        val reason = if (code == 200) "OK" else "Not Found"
        val head = "HTTP/1.1 $code $reason\r\n" +
            "Content-Type: text/plain\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Cache-Control: no-store\r\n" +
            "Connection: close\r\n\r\n"
        out.write(head.toByteArray())
        out.write(bytes)
        out.flush()
    }
}
