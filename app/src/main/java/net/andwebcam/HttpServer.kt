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
 *  - the page itself is served without a session (it carries the login form),
 *    but everything it displays comes from the API, which stays gated
 *  - logging out also stops the browser's cached Basic password from being
 *    accepted, via a cookie: otherwise the browser simply signs itself back
 *    in on the next request
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

        /** One JPEG at the current settings, or null if none could be taken. */
        fun snapshot(): ByteArray?

        /** Returns null on success, or an error message. */
        fun reboot(): String?

        /**
         * Runs the camera with no processing and no streaming and reports the raw
         * output rate. Diagnostic only; never surfaced in the page.
         */
        fun diag(mode: String, seconds: Int, resolution: String): String
    }

    private enum class Role { ADMIN, ANON, NONE }

    /** How this request was authenticated, for the status page and the log. */
    private class Who(val role: Role, val method: String)

    private val ssl = Tls.sslContext(ctx)
    private val pool = Executors.newCachedThreadPool()
    private val sessions = ConcurrentHashMap<String, Long>()
    private val rng = SecureRandom()

    /**
     * Successful Basic credentials, hashed, kept for the life of the service.
     * Verifying the password costs ~4.4 s of CPU (120k PBKDF2 iterations)
     * against 0.03 s for a cookie request, so a client polling with -u, or
     * ffmpeg reconnecting, used to pin a core.
     *
     * Keeping them indefinitely widens nothing: the map is keyed by the
     * credential itself, which Basic auth already treats as valid until the
     * password changes - and changing the password clears this. The timestamp
     * is only there to evict the oldest entry if the map ever fills.
     */
    private val basicOk = ConcurrentHashMap<String, Long>()
    private val basicMax = 32

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
                val from = try { c.inetAddress?.hostAddress ?: "?" } catch (_: Exception) { "?" }
                val started = System.currentTimeMillis()
                try {
                    // A client that connects and then stalls must not hold a
                    // thread for ever. Cleared again before streaming, where
                    // writes may legitimately block.
                    try { c.soTimeout = 15000 } catch (_: Exception) {}
                    handle(c)
                } catch (e: Exception) {
                    Log.w("andWebCam", "request from $from failed: ${e.message}")
                } finally {
                    val ms = System.currentTimeMillis() - started
                    if (ms > 5000) Log.w("andWebCam", "slow request from $from: ${ms}ms")
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

        // The request has been read; from here on a blocked write is normal
        // (a viewer that reads slowly) so the socket timeout goes away.
        try { sock.soTimeout = 0 } catch (_: Exception) {}

        val who = authWho(headers)
        val role = who.role

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
                        listOf(
                            "Set-Cookie: sid=$token; Path=/; HttpOnly; SameSite=Strict",
                            // A proper login re-enables Basic for this browser.
                            "Set-Cookie: nobasic=; Path=/; HttpOnly; SameSite=Strict; Max-Age=0",
                        )
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
                    listOf(
                        "Set-Cookie: sid=; Path=/; HttpOnly; SameSite=Strict; Max-Age=0",
                        // Without this a browser that saved the HTTP Basic
                        // password signs itself straight back in on the reload,
                        // which makes the button look broken.
                        "Set-Cookie: nobasic=1; Path=/; HttpOnly; SameSite=Strict; Max-Age=31536000",
                    )
                )
            }

            method == "GET" && path == "/api/status" -> {
                if (role == Role.NONE) {
                    // Signed out: enough for the page to offer the login form,
                    // and nothing about the camera.
                    respond(
                        output, 200, "application/json",
                        jsonBytes(
                            mapOf(
                                "locked" to true,
                                "admin" to false,
                                "anonView" to false,
                                "auth" to who.method,
                                "version" to BuildConfig.VERSION_NAME,
                                "build" to BuildConfig.BUILD_ID,
                            )
                        )
                    )
                } else {
                    val m = host.statusJson()
                    m["admin"] = role == Role.ADMIN
                    m["anonView"] = Prefs.anonView
                    m["auth"] = who.method
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
                        basicOk.clear()      // old credentials must stop working
                        sessions.clear()
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

            method == "POST" && path == "/api/diag" -> {
                if (role != Role.ADMIN) unauthorized(output) else {
                    val vals = parseFlatJson(String(body))
                    val mode = vals["mode"] ?: "jpeg"
                    val seconds = (vals["seconds"]?.toIntOrNull() ?: 6).coerceIn(1, 60)
                    val report = host.diag(mode, seconds, vals["resolution"] ?: "")
                    respond(output, 200, "application/json", jsonBytes(mapOf("report" to report)))
                }
            }

            method == "GET" && path == "/video" -> {
                when {
                    role == Role.NONE -> unauthorized(output)
                    !host.isStreaming() -> respond(output, 503, "text/plain", "streaming stopped".toByteArray())
                    else -> serveVideo(output)
                }
            }

            // Whoever may watch the stream may also save a frame from it.
            // Stopped streaming means no frame: capturing one anyway opened the
            // camera for a single frame that was often still black.
            method == "GET" && path == "/snapshot" -> {
                if (role == Role.NONE) unauthorized(output) else if (!host.isStreaming()) {
                    respond(output, 503, "text/plain", "streaming stopped".toByteArray())
                } else {
                    val jpeg = host.snapshot()
                    if (jpeg == null) {
                        respond(output, 503, "text/plain", "no frame available".toByteArray())
                    } else {
                        respond(
                            output, 200, "image/jpeg", jpeg,
                            listOf("Content-Disposition: attachment; filename=\"${snapshotName()}\""),
                        )
                    }
                }
            }

            method == "GET" && path == "/" -> {
                // Served without a session: this is the login form's shell.
                // Everything it can show comes from the gated API.
                respond(output, 200, "text/html; charset=utf-8", PAGE.toByteArray())
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

    private fun authWho(headers: Map<String, String>): Who {
        val cookie = headers["cookie"]
        parseSid(cookie)?.let { sid ->
            val exp = sessions[sid]
            if (exp != null && exp > System.currentTimeMillis()) return Who(Role.ADMIN, "session")
        }
        // "Sign out" has to hold even when the browser has the Basic password
        // cached: it will keep sending it, so it is ignored until the user logs
        // in through the form again.
        val auth = headers["authorization"]
        if (!hasCookie(cookie, "nobasic") && auth != null && auth.startsWith("Basic ", ignoreCase = true)) {
            val key = basicKey(auth)
            if (basicOk.containsKey(key)) return Who(Role.ADMIN, "basic")
            try {
                val dec = String(Base64.decode(auth.substring(6).trim(), Base64.DEFAULT))
                val i = dec.indexOf(':')
                if (i >= 0 && Prefs.verifyAdmin(dec.substring(0, i), dec.substring(i + 1))) {
                    if (basicOk.size >= basicMax) {
                        basicOk.minByOrNull { it.value }?.let { basicOk.remove(it.key) }
                    }
                    basicOk[key] = System.currentTimeMillis()
                    return Who(Role.ADMIN, "basic")
                }
            } catch (_: Exception) {
            }
        }
        return if (Prefs.anonView) Who(Role.ANON, "anon") else Who(Role.NONE, "none")
    }

    private fun basicKey(auth: String): String {
        val d = java.security.MessageDigest.getInstance("SHA-256").digest(auth.toByteArray())
        return Base64.encodeToString(d, Base64.NO_WRAP)
    }

    private fun cookieValue(cookie: String?, name: String): String? {
        if (cookie == null) return null
        for (part in cookie.split(';')) {
            val kv = part.trim()
            if (kv.startsWith("$name=")) return kv.substring(name.length + 1)
        }
        return null
    }

    private fun parseSid(cookie: String?): String? = cookieValue(cookie, "sid")

    private fun hasCookie(cookie: String?, name: String): Boolean = cookieValue(cookie, name) != null

    private fun snapshotName(): String =
        "andWebCam-" +
            java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
                .format(java.util.Date()) + ".jpg"

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

    /**
     * Bounded on purpose: an endless stream with no line ending in it used to
     * grow the builder without limit and spin a pool thread at 100% CPU.
     */
    private fun readLine(input: InputStream, max: Int = 8192): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b == -1) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString()
            if (b != '\r'.code) {
                if (sb.length >= max) throw java.io.IOException("line too long")
                sb.append(b.toChar())
            }
        }
    }

    private fun readFully(input: InputStream, out: ByteArray) {
        var off = 0
        while (off < out.size) {
            val n = input.read(out, off, out.size - off)
            if (n <= 0) break          // 0 would spin; -1 is the end
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
