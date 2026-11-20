package net.andwebcam

import android.content.Context
import android.util.Base64
import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
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
         * Validates an uploaded APK and copies it into an install session.
         * Returns null when it is staged and ready to commit, or a sentence
         * explaining why it was refused.
         */
        fun stageUpdate(apk: File): String?

        /**
         * Commits the staged APK. On success this process is replaced, so the
         * call does not return; an error is returned as a message.
         */
        fun commitUpdate(): String?

        /**
         * Runs the camera with no processing and no streaming and reports the raw
         * output rate. Diagnostic only; never surfaced in the page.
         */
        fun diag(mode: String, seconds: Int, resolution: String): String

        /** Starts a recording session; null when accepted, else why not. */
        fun startRecording(): String?

        /** Stops the running session; the file is finalized asynchronously. */
        fun stopRecording()

        /** Arms or disarms motion activation; null when accepted, else why not. */
        fun setMotion(on: Boolean): String?

        /**
         * The motion pane's data: enabled flag, the quiet period, and each
         * zone with its points, sensitivity and live level.
         */
        fun motionInfo(): Map<String, Any?>

        /** Replaces the zones; null when accepted, else why not. */
        fun saveMotionZones(zones: String): String?

        /** Seconds of quiet after the last motion before a recording ends. */
        fun setMotionQuiet(seconds: Int)

        /** The recordings' share of the free space, in percent. Null on success. */
        fun setRecordingCap(percent: Int): String?

        /** The recording pane's data: storage figures and the finished videos. */
        fun recordingInfo(): Map<String, Any?>

        /** Deletes one finished video; null when done. */
        fun deleteRecording(name: String): String?

        /** A finished video for playback, or null when there is none. */
        fun videoFile(name: String): Recorder.File?
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

    /** Rate limit for "request failed" lines; see [logFailure]. */
    private val failureGate = Any()
    private var failureWindow = 0L
    private var failureCount = 0
    private var failureSkipped = 0

    /** Room to spare over the current APK; a 200 MB upload is not an update. */
    private val MAX_UPDATE_BYTES = 64L * 1024 * 1024

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
                    logFailure(from, e)
                } finally {
                    val ms = System.currentTimeMillis() - started
                    if (ms > 5000) Log.w("andWebCam", "slow request from $from: ${ms}ms")
                    try { c.close() } catch (_: Exception) {}
                }
            }
        }
    }

    /**
     * A client that cannot complete the handshake - a browser sitting on the
     * certificate warning retries every couple of seconds - would otherwise
     * fill the log with two lines per attempt and bury everything else. Five a
     * minute is plenty to see that it is happening.
     */
    private fun logFailure(from: String, e: Exception) {
        val now = System.currentTimeMillis()
        synchronized(failureGate) {
            if (now - failureWindow > 60_000) {
                if (failureSkipped > 0) {
                    Log.w("andWebCam", "$failureSkipped further failed requests were not logged")
                }
                failureWindow = now
                failureSkipped = 0
                failureCount = 0
            }
            if (++failureCount > 5) {
                failureSkipped++
                return
            }
        }
        Log.w("andWebCam", "request from $from failed: ${e.message}")
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
        val query = target.substringAfter('?', "")

        val headers = HashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val i = line.indexOf(':')
            if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
        }

        val from = try { sock.inetAddress?.hostAddress ?: "" } catch (_: Exception) { "" }
        val who = authWho(headers, from)
        val role = who.role

        // An APK is far larger than any JSON body and must not be held in
        // memory, so this one route reads its own body, before the generic
        // read below has consumed it.
        if (method == "POST" && path == "/api/update") {
            handleUpdate(input, output, headers, role)
            return
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

        when {
            method == "GET" && path == "/cert" -> {
                respond(output, 200, "application/x-pem-file", Tls.certPem(ctx))
            }

            method == "GET" && path == "/favicon.ico" -> {
                respond(output, 204, "text/plain", ByteArray(0))
            }

            method == "POST" && path == "/api/login" -> {
                val wait = loginWait(from)
                if (wait > 0) {
                    val secs = (wait + 999) / 1000
                    respond(
                        output, 429, "application/json",
                        jsonBytes(
                            mapOf(
                                "ok" to false,
                                "error" to "too many failed logins from this address; try again in $secs seconds",
                            )
                        ),
                        listOf("Retry-After: $secs"),
                    )
                    return
                }
                val vals = parseFlatJson(String(body))
                val user = vals["user"] ?: ""
                val pass = vals["pass"] ?: ""
                if (Prefs.verifyAdmin(user, pass)) {
                    loginOk(from)
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
                    loginFail(from)
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
                            )
                        )
                    )
                } else {
                    val m = host.statusJson()
                    // Which exact build runs is the admin's business: viewers
                    // and the signed-out page get everything but that.
                    if (role != Role.ADMIN) {
                        m.remove("version")
                        m.remove("build")
                    }
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

            method == "GET" && path == "/api/log" -> {
                if (role != Role.ADMIN) unauthorized(output) else {
                    val n = queryParam(query, "lines")?.toIntOrNull()?.coerceIn(10, 10_000) ?: 500
                    val all = queryParam(query, "all") in listOf("1", "true", "yes")
                    val text = Logs.dump(n, all)
                    respond(
                        output, 200, "text/plain; charset=utf-8", text.toByteArray(),
                        listOf("Content-Disposition: inline; filename=\"andwebcam-log.txt\""),
                    )
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

            // The pane's actions; only the pane itself uses them, so the
            // viewer role gets nothing from this area but the files.
            method == "POST" && path == "/api/record/start" -> {
                if (role != Role.ADMIN) unauthorized(output) else {
                    val err = host.startRecording()
                    respond(
                        output, 200, "application/json",
                        jsonBytes(mapOf("ok" to (err == null), "error" to err)),
                    )
                }
            }

            method == "POST" && path == "/api/record/stop" -> {
                if (role != Role.ADMIN) unauthorized(output) else {
                    host.stopRecording()
                    respond(output, 200, "application/json", jsonBytes(mapOf("ok" to true)))
                }
            }

            method == "POST" && path == "/api/record/cap" -> {
                if (role != Role.ADMIN) unauthorized(output) else {
                    val vals = parseFlatJson(String(body))
                    val err = host.setRecordingCap(vals["percent"]?.toIntOrNull() ?: 0)
                    respond(
                        output, 200, "application/json",
                        jsonBytes(mapOf("ok" to (err == null), "error" to err)),
                    )
                }
            }

            method == "POST" && path == "/api/record/delete" -> {
                if (role != Role.ADMIN) unauthorized(output) else {
                    val vals = parseFlatJson(String(body))
                    val err = host.deleteRecording(vals["name"] ?: "")
                    respond(
                        output, 200, "application/json",
                        jsonBytes(mapOf("ok" to (err == null), "error" to err)),
                    )
                }
            }

            method == "GET" && path == "/api/record/list" -> {
                if (role != Role.ADMIN) unauthorized(output) else {
                    respond(output, 200, "application/json", jsonBytes(host.recordingInfo()))
                }
            }

            // Motion activation: arm it, read the zones with their live
            // levels, replace the zones, set the quiet period. All admin; the
            // zones' shape belongs to whoever draws them.
            method == "POST" && path == "/api/motion/set" -> {
                if (role != Role.ADMIN) unauthorized(output) else {
                    val vals = parseFlatJson(String(body))
                    val err = host.setMotion(vals["enabled"]?.toBoolean() ?: false)
                    respond(
                        output, 200, "application/json",
                        jsonBytes(mapOf("ok" to (err == null), "error" to err)),
                    )
                }
            }

            method == "GET" && path == "/api/motion/list" -> {
                if (role != Role.ADMIN) unauthorized(output) else {
                    respond(output, 200, "application/json", jsonBytes(host.motionInfo()))
                }
            }

            method == "POST" && path == "/api/motion/zones" -> {
                if (role != Role.ADMIN) unauthorized(output) else {
                    val err = host.saveMotionZones(String(body))
                    respond(
                        output, 200, "application/json",
                        jsonBytes(mapOf("ok" to (err == null), "error" to err)),
                    )
                }
            }

            method == "POST" && path == "/api/motion/quiet" -> {
                if (role != Role.ADMIN) unauthorized(output) else {
                    val vals = parseFlatJson(String(body))
                    host.setMotionQuiet(vals["seconds"]?.toIntOrNull() ?: 30)
                    respond(output, 200, "application/json", jsonBytes(mapOf("ok" to true)))
                }
            }

            // Play a finished recording. The list is admin-only, but a video
            // URL shared with a viewer plays like a stream frame does. The
            // name may sit in the path - a browser names a download after the
            // last segment - or in the query, as links used to carry it.
            method == "GET" && (path == "/video/file" || path.startsWith("/video/file/")) -> {
                if (role == Role.NONE) unauthorized(output) else {
                    val name = if (path.length > "/video/file/".length && path.startsWith("/video/file/")) {
                        path.substring("/video/file/".length)
                    } else {
                        queryParam(query, "name") ?: ""
                    }
                    val file = host.videoFile(java.net.URLDecoder.decode(name, "UTF-8"))
                    if (file == null) respond(output, 404, "text/plain", "no such video".toByteArray())
                    else serveVideoFile(output, file, headers)
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
            "X-Content-Type-Options: nosniff\r\n" +
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

    /**
     * A finished recording, with byte ranges: a browser player that cannot
     * seek would have to download the whole file before the first frame, and
     * some start from the end of it, so a request for a range is answered
     * with one.
     */
    private fun serveVideoFile(output: BufferedOutputStream, file: Recorder.File, headers: Map<String, String>) {
        val stream = file.open()
        if (stream == null) {
            respond(output, 503, "text/plain", "the video could not be opened".toByteArray())
            return
        }
        stream.use { s ->
            val total = file.size
            var start = 0L
            var end = total - 1
            var partial = false
            val r = headers["range"]
            if (r != null && r.startsWith("bytes=", ignoreCase = true)) {
                val spec = r.substring(6)
                val dash = spec.indexOf('-')
                val a = if (dash > 0) spec.substring(0, dash).trim() else ""
                val b = spec.substring(dash + 1).trim()
                val aL = a.toLongOrNull()
                val bL = b.toLongOrNull()
                when {
                    aL == null && bL != null -> start = (total - bL).coerceAtLeast(0)
                    aL != null -> {
                        start = aL
                        if (bL != null) end = bL.coerceAtMost(total - 1)
                    }
                }
                if (total <= 0 || start > end || start >= total) {
                    respond(
                        output, 416, "text/plain", ByteArray(0),
                        listOf("Accept-Ranges: bytes", "Content-Range: bytes */${total.coerceAtLeast(0)}"),
                    )
                    return
                }
                partial = true
            }
            val length = end - start + 1
            val sb = StringBuilder()
            sb.append("HTTP/1.1 ").append(if (partial) 206 else 200)
                .append(if (partial) " Partial Content" else " OK").append("\r\n")
            sb.append("Content-Type: video/mp4\r\n")
            // Naming the file here as well as in the URL: a downloader that
            // only looks at headers still gets the video's own name.
            sb.append("Content-Disposition: attachment; filename=\"${file.name}\"\r\n")
            sb.append("Content-Length: $length\r\n")
            sb.append("Accept-Ranges: bytes\r\n")
            sb.append("Cache-Control: no-store\r\n")
            sb.append("X-Content-Type-Options: nosniff\r\n")
            if (partial) sb.append("Content-Range: bytes $start-$end/$total\r\n")
            sb.append("Connection: close\r\n\r\n")
            try {
                output.write(sb.toString().toByteArray())
                output.flush()
            } catch (e: Exception) {
                return
            }
            val buf = ByteArray(64 * 1024)
            var remaining = length
            var skipped = 0L
            try {
                while (skipped < start) {
                    val n = s.skip(start - skipped)
                    if (n <= 0) break
                    skipped += n
                }
                while (remaining > 0) {
                    val n = s.read(buf, 0, minOf(buf.size, remaining.toInt()))
                    if (n < 0) break
                    output.write(buf, 0, n)
                    remaining -= n
                }
            } catch (e: Exception) {
                return
            }
            output.flush()
        }
    }

    // ---- auth ---------------------------------------------------------

    /** Client address -> [failure count, time of the last failed attempt]. */
    private val loginFails = HashMap<String, LongArray>()
    private val loginLock = Any()

    /**
     * Milliseconds the address still has to wait before its next credential
     * attempt: after five failures the pause doubles with each further one,
     * capped at about half an hour, so online guessing cannot run at
     * connection speed. Zero when the attempt may proceed.
     */
    private fun loginWait(from: String): Long {
        if (from.isEmpty()) return 0
        val st = synchronized(loginLock) { loginFails[from] } ?: return 0
        val count = st[0].toInt()
        if (count < 5) return 0
        val required = 2000L shl minOf(count - 5, 10)
        return (required - (System.currentTimeMillis() - st[1])).coerceAtLeast(0)
    }

    private fun loginFail(from: String) {
        if (from.isEmpty()) return
        synchronized(loginLock) {
            val st = loginFails[from]
            val count = (st?.get(0)?.toInt() ?: 0) + 1
            loginFails[from] = longArrayOf(count.toLong(), System.currentTimeMillis())
            if (loginFails.size > 1024) loginFails.clear()
        }
    }

    private fun loginOk(from: String) {
        if (from.isEmpty()) return
        synchronized(loginLock) { loginFails.remove(from) }
    }

    private fun authWho(headers: Map<String, String>, from: String): Who {
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
            // A run of bad passwords from one address buys growing pauses, so
            // guessing cannot run at connection speed.
            if (loginWait(from) > 0) {
                return if (Prefs.anonView) Who(Role.ANON, "anon") else Who(Role.NONE, "none")
            }
            try {
                val dec = String(Base64.decode(auth.substring(6).trim(), Base64.DEFAULT))
                val i = dec.indexOf(':')
                if (i >= 0 && Prefs.verifyAdmin(dec.substring(0, i), dec.substring(i + 1))) {
                    if (basicOk.size >= basicMax) {
                        basicOk.minByOrNull { it.value }?.let { basicOk.remove(it.key) }
                    }
                    basicOk[key] = System.currentTimeMillis()
                    loginOk(from)
                    return Who(Role.ADMIN, "basic")
                }
            } catch (_: Exception) {
            }
            loginFail(from)
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

    private fun queryParam(query: String, name: String): String? {
        if (query.isEmpty()) return null
        for (part in query.split('&')) {
            val kv = part.split('=', limit = 2)
            if (kv[0] == name) {
                return if (kv.size > 1) java.net.URLDecoder.decode(kv[1], "UTF-8") else ""
            }
        }
        return null
    }

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

    // ---- software update ----------------------------------------------

    /**
     * POST /api/update with the APK as the raw request body.
     *
     * The body is written straight to a file rather than to memory: an APK is
     * a few megabytes and the generic path above only accepts small JSON.
     * Content-Length is required, which is what curl and the page's fetch()
     * both send.
     */
    private fun handleUpdate(
        input: InputStream,
        output: BufferedOutputStream,
        headers: Map<String, String>,
        role: Role,
    ) {
        if (role != Role.ADMIN) {
            unauthorized(output)
            return
        }
        val length = headers["content-length"]?.toLongOrNull() ?: -1L
        if (length <= 0) {
            respond(
                output, 411, "application/json",
                jsonBytes(
                    mapOf(
                        "ok" to false,
                        "error" to "Content-Length is required: send the APK as the request body.",
                    )
                )
            )
            return
        }
        if (length > MAX_UPDATE_BYTES) {
            respond(
                output, 413, "application/json",
                jsonBytes(
                    mapOf(
                        "ok" to false,
                        "error" to "The upload is larger than ${MAX_UPDATE_BYTES / (1024 * 1024)} MB.",
                    )
                )
            )
            return
        }

        val apk = try {
            File.createTempFile("update", ".apk", ctx.cacheDir)
        } catch (e: Exception) {
            respond(
                output, 500, "application/json",
                jsonBytes(mapOf("ok" to false, "error" to "No room to store the upload: ${e.message}"))
            )
            return
        }
        try {
            FileOutputStream(apk).use { out ->
                val buf = ByteArray(64 * 1024)
                var left = length
                while (left > 0) {
                    val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                    if (n <= 0) throw java.io.IOException("the upload ended after ${length - left} bytes")
                    out.write(buf, 0, n)
                    left -= n
                }
            }
            val err = host.stageUpdate(apk)
            apk.delete()
            if (err != null) {
                respond(output, 200, "application/json", jsonBytes(mapOf("ok" to false, "error" to err)))
                return
            }
        } catch (e: Exception) {
            apk.delete()
            respond(
                output, 200, "application/json",
                jsonBytes(mapOf("ok" to false, "error" to "Upload failed: ${e.message}"))
            )
            return
        }

        Prefs.updateNote = "Installing..."
        respond(
            output, 200, "application/json",
            jsonBytes(
                mapOf(
                    "ok" to true,
                    "message" to "Installing. The phone restarts the app when it is done, " +
                        "so this page may stop answering for a moment.",
                )
            )
        )
        // Committing replaces this very APK, which kills this process. Let the
        // answer above leave the phone first, or the browser shows a failure
        // for an update that worked.
        Thread {
            try { Thread.sleep(1500) } catch (_: InterruptedException) {}
            val err = host.commitUpdate()
            if (err != null) Prefs.updateNote = "Update failed: $err"
        }.apply { isDaemon = true }.start()
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
            403 -> "Forbidden"; 404 -> "Not Found"; 429 -> "Too Many Requests"
            500 -> "Internal Server Error"; 416 -> "Range Not Satisfiable"
            411 -> "Length Required"; 413 -> "Payload Too Large"
            503 -> "Service Unavailable"
            else -> "OK"
        }
        val sb = StringBuilder()
        sb.append("HTTP/1.1 $code $reason\r\n")
        sb.append("Content-Type: $contentType\r\n")
        sb.append("Content-Length: ${body.size}\r\n")
        sb.append("Cache-Control: no-store\r\n")
        // Everything is same-origin and the page is inline script and style:
        // these headers mainly say so, and keep the page out of other sites.
        sb.append("X-Content-Type-Options: nosniff\r\n")
        sb.append("X-Frame-Options: DENY\r\n")
        sb.append("Referrer-Policy: no-referrer\r\n")
        sb.append("Strict-Transport-Security: max-age=31536000\r\n")
        sb.append(
            "Content-Security-Policy: default-src 'self'; " +
                "script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; " +
                "img-src 'self' data:; media-src 'self'; connect-src 'self'; frame-ancestors 'none'\r\n"
        )
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
