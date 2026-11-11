package net.andwebcam

import android.content.Context
import android.util.Base64
import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import javax.net.ssl.SSLServerSocket

/**
 * Minimal HTTPS server: MJPEG stream, status/settings API and the control page.
 *
 * Auth model:
 *  - cookie session (POST /api/login) or HTTP Basic, both granting ADMIN
 *  - when Prefs.anonView is on, unauthenticated requests get ANON (view only)
 *  - when it is off, everything except /cert and /api/login requires auth
 */
class HttpServer(
    private val ctx: Context,
    private val hub: StreamHub,
    private val host: Host,
) {
    interface Host {
        fun statusJson(): MutableMap<String, Any?>
        fun applySettings(vals: Map<String, String>)
        fun changePassword(newPass: String)
        fun isStreaming(): Boolean

        /** Returns null on success, or an error message. */
        fun reboot(): String?
    }

    private enum class Role { ADMIN, ANON, NONE }

    private val ssl = Tls.sslContext(ctx)
    private val pool = Executors.newCachedThreadPool()
    private val sessions = ConcurrentHashMap<String, Long>()
    private val rng = SecureRandom()

    @Volatile private var socket: SSLServerSocket? = null
    @Volatile private var running = false
    private var acceptThread: Thread? = null

    val isRunning: Boolean get() = running

    fun start(port: Int): Boolean {
        stop()
        return try {
            val s = ssl.serverSocketFactory.createServerSocket() as SSLServerSocket
            s.reuseAddress = true
            s.bind(InetSocketAddress(port))
            socket = s
            running = true
            acceptThread = Thread({ acceptLoop(s) }, "andwebcam-http").apply {
                isDaemon = true
                start()
            }
            true
        } catch (e: Exception) {
            State.lastError = "Server start failed: ${e.message}"
            false
        }
    }

    fun stop() {
        running = false
        try { socket?.close() } catch (_: Exception) {}
        socket = null
    }

    // ------------------------------------------------------------------

    private fun acceptLoop(s: SSLServerSocket) {
        while (running) {
            val c = try {
                s.accept()
            } catch (e: Exception) {
                if (running) continue else break
            }
            pool.execute {
                try {
                    handle(c)
                } catch (e: Exception) {
                    Log.w("andWebCam", "request failed: ${e.message}")
                } finally {
                    try { c.close() } catch (_: Exception) {}
                }
            }
        }
    }

    private fun handle(sock: Socket) {
        val input = BufferedInputStream(sock.getInputStream())
        val output = BufferedOutputStream(sock.getOutputStream())

        val requestLine = readLine(input) ?: return
        val parts = requestLine.split(" ")
        if (parts.size < 2) return
        val method = parts[0].uppercase()
        val target = parts[1]
        val path = target.substringBefore('?')

        val headers = HashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val i = line.indexOf(':')
            if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
        }

        var body = ByteArray(0)
        val cl = headers["content-length"]?.toIntOrNull() ?: 0
        if (cl in 1..(1 shl 20)) {
            body = ByteArray(cl)
            readFully(input, body)
        }

        val role = authRole(headers)

        when {
            method == "GET" && path == "/cert" -> {
                respond(output, 200, "application/x-pem-file", Tls.certPem(ctx))
            }

            method == "GET" && path == "/favicon.ico" -> {
                respond(output, 204, "text/plain", ByteArray(0))
            }

            method == "POST" && path == "/api/login" -> {
                val vals = parseFlatJson(String(body))
                val user = vals["user"] ?: ""
                val pass = vals["pass"] ?: ""
                if (Prefs.verifyAdmin(user, pass)) {
                    val token = newToken()
                    sessions[token] = System.currentTimeMillis() + 24 * 3600_000L
                    respond(
                        output, 200, "application/json",
                        jsonBytes(mapOf("ok" to true)),
                        listOf("Set-Cookie: sid=$token; Path=/; HttpOnly; SameSite=Strict")
                    )
                } else {
                    respond(output, 401, "application/json", jsonBytes(mapOf("ok" to false, "error" to "bad credentials")))
                }
            }

            method == "POST" && path == "/api/logout" -> {
                parseSid(headers["cookie"])?.let { sessions.remove(it) }
                respond(
                    output, 200, "application/json",
                    jsonBytes(mapOf("ok" to true)),
                    listOf("Set-Cookie: sid=; Path=/; HttpOnly; Max-Age=0")
                )
            }

            method == "GET" && path == "/api/status" -> {
                if (role == Role.NONE) unauthorized(output) else {
                    val m = host.statusJson()
                    m["admin"] = role == Role.ADMIN
                    m["anonView"] = Prefs.anonView
                    m["host"] = "${Prefs.serviceName}.local"
                    m["url"] = State.url
                    respond(output, 200, "application/json", jsonBytes(m))
                }
            }

            method == "POST" && path == "/api/settings" -> {
                if (role != Role.ADMIN) unauthorized(output) else {
                    val vals = parseFlatJson(String(body))
                    host.applySettings(vals)
                    respond(output, 200, "application/json", jsonBytes(mapOf("ok" to true)))
                }
            }

            method == "POST" && path == "/api/password" -> {
                if (role != Role.ADMIN) unauthorized(output) else {
                    val vals = parseFlatJson(String(body))
                    val np = vals["password"] ?: ""
                    if (np.length < 4) {
                        respond(output, 200, "application/json", jsonBytes(mapOf("ok" to false, "error" to "too short")))
                    } else {
                        host.changePassword(np)
                        respond(output, 200, "application/json", jsonBytes(mapOf("ok" to true)))
                    }
                }
            }

            method == "POST" && path == "/api/reboot" -> {
                if (role != Role.ADMIN) unauthorized(output) else {
                    val err = host.reboot()
                    if (err == null) {
                        respond(output, 200, "application/json", jsonBytes(mapOf("ok" to true)))
                    } else {
                        respond(output, 200, "application/json", jsonBytes(mapOf("ok" to false, "error" to err)))
                    }
                }
            }

            method == "GET" && path == "/video" -> {
                when {
                    role == Role.NONE -> unauthorized(output)
                    !host.isStreaming() -> respond(output, 503, "text/plain", "streaming stopped".toByteArray())
                    else -> serveVideo(output)
                }
            }

            method == "GET" && path == "/" -> {
                if (role == Role.NONE) unauthorized(output)
                else respond(output, 200, "text/html; charset=utf-8", PAGE.toByteArray())
            }

            else -> respond(output, 404, "text/plain", "not found".toByteArray())
        }
    }

    private fun serveVideo(output: BufferedOutputStream) {
        val header = "HTTP/1.1 200 OK\r\n" +
            "Content-Type: multipart/x-mixed-replace; boundary=${StreamHub.BOUNDARY}\r\n" +
            "Cache-Control: no-store\r\n" +
            "Connection: close\r\n\r\n"
        try {
            output.write(header.toByteArray())
            output.flush()
        } catch (e: Exception) {
            return
        }
        val client = hub.add(output)
        try {
            client.done.await()
        } catch (_: InterruptedException) {
        }
    }

    // ---- auth ---------------------------------------------------------

    private fun authRole(headers: Map<String, String>): Role {
        parseSid(headers["cookie"])?.let { sid ->
            val exp = sessions[sid]
            if (exp != null && exp > System.currentTimeMillis()) return Role.ADMIN
        }
        val auth = headers["authorization"]
        if (auth != null && auth.startsWith("Basic ", ignoreCase = true)) {
            try {
                val dec = String(Base64.decode(auth.substring(6).trim(), Base64.DEFAULT))
                val i = dec.indexOf(':')
                if (i >= 0 && Prefs.verifyAdmin(dec.substring(0, i), dec.substring(i + 1))) return Role.ADMIN
            } catch (_: Exception) {
            }
        }
        return if (Prefs.anonView) Role.ANON else Role.NONE
    }

    private fun parseSid(cookie: String?): String? {
        if (cookie == null) return null
        for (part in cookie.split(';')) {
            val kv = part.trim()
            if (kv.startsWith("sid=")) return kv.substring(4)
        }
        return null
    }

    private fun newToken(): String {
        val b = ByteArray(24)
        rng.nextBytes(b)
        return b.joinToString("") { "%02x".format(it) }
    }

    private fun unauthorized(output: BufferedOutputStream) {
        respond(
            output, 401, "text/plain", "authentication required".toByteArray(),
            listOf("WWW-Authenticate: Basic realm=\"andWebCam\"")
        )
    }

    // ---- io helpers ---------------------------------------------------

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b == -1) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString()
            if (b != '\r'.code) sb.append(b.toChar())
        }
    }

    private fun readFully(input: InputStream, out: ByteArray) {
        var off = 0
        while (off < out.size) {
            val n = input.read(out, off, out.size - off)
            if (n < 0) break
            off += n
        }
    }

    private fun respond(
        output: BufferedOutputStream,
        code: Int,
        contentType: String,
        body: ByteArray,
        extra: List<String> = emptyList(),
    ) {
        val reason = when (code) {
            200 -> "OK"; 204 -> "No Content"; 401 -> "Unauthorized"
            403 -> "Forbidden"; 404 -> "Not Found"; 500 -> "Internal Server Error"
            503 -> "Service Unavailable"
            else -> "OK"
        }
        val sb = StringBuilder()
        sb.append("HTTP/1.1 $code $reason\r\n")
        sb.append("Content-Type: $contentType\r\n")
        sb.append("Content-Length: ${body.size}\r\n")
        sb.append("Cache-Control: no-store\r\n")
        for (h in extra) sb.append(h).append("\r\n")
        sb.append("Connection: close\r\n\r\n")
        output.write(sb.toString().toByteArray())
        if (body.isNotEmpty()) output.write(body)
        output.flush()
    }

    private fun jsonBytes(m: Map<String, Any?>): ByteArray = jsonValue(m).toByteArray()

    // ------------------------------------------------------------------
    // Tiny JSON. Flat objects only; enough for this API.

    private fun jsonValue(v: Any?): String = when (v) {
        null -> "null"
        is String -> "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
        is Boolean -> v.toString()
        is Int, is Long, is Double, is Float -> v.toString()
        is List<*> -> v.joinToString(",", "[", "]") { jsonValue(it) }
        is Map<*, *> -> v.entries.joinToString(",", "{", "}") { (k, vv) -> jsonValue(k.toString()) + ":" + jsonValue(vv) }
        else -> jsonValue(v.toString())
    }

    private class JsonReader(val s: String) {
        var i = 0
        fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }

        fun readString(): String {
            val sb = StringBuilder()
            i++
            while (i < s.length) {
                val ch = s[i++]
                when (ch) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (i >= s.length) return sb.toString()
                        val e = s[i++]
                        sb.append(
                            when (e) {
                                'n' -> '\n'; 't' -> '\t'; 'r' -> '\r'; 'b' -> '\b'
                                'f' -> '\u000C'; else -> e
                            }
                        )
                    }
                    else -> sb.append(ch)
                }
            }
            return sb.toString()
        }

        fun skipNested() {
            var depth = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '"') { readString(); continue }
                i++
                if (c == '{' || c == '[') depth++
                else if (c == '}' || c == ']') { depth--; if (depth == 0) return }
            }
        }

        fun readValue(): String {
            ws()
            if (i >= s.length) return ""
            return when (s[i]) {
                '"' -> readString()
                '{', '[' -> { val start = i; skipNested(); s.substring(start, i) }
                else -> {
                    val start = i
                    while (i < s.length && s[i] != ',' && s[i] != '}') i++
                    s.substring(start, i).trim()
                }
            }
        }
    }

    private fun parseFlatJson(s: String): MutableMap<String, String> {
        val out = mutableMapOf<String, String>()
        val r = JsonReader(s)
        r.ws()
        if (r.i >= s.length || s[r.i] != '{') return out
        r.i++
        while (r.i < s.length) {
            r.ws()
            if (r.i >= s.length || s[r.i] == '}') break
            if (s[r.i] != '"') break
            val key = r.readString()
            r.ws()
            if (r.i < s.length && s[r.i] == ':') r.i++
            out[key] = r.readValue()
            r.ws()
            if (r.i < s.length && s[r.i] == ',') { r.i++; continue }
            if (r.i < s.length && s[r.i] == '}') break
        }
        return out
    }

    companion object {
        val PAGE: String get() = WebUi.PAGE
    }
}
