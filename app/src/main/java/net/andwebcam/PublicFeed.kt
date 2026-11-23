package net.andwebcam

import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.URL
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import kotlin.math.min

/**
 * Outbound public proxy. The phone opens one control WebSocket to the relay;
 * for every browser request the relay asks for a short-lived request socket,
 * and this class replays that request against the app's own HTTPS server and
 * streams the response back. The whole web UI works remotely, not just video.
 *
 * Only the control socket is long-lived. Reconnection, authentication and
 * liveness live here; the HTTP work happens on a small thread pool so a slow
 * viewer cannot stall the camera or the control channel.
 */
class PublicFeed {
    companion object {
        private const val TAG = "andWebCam"
        private const val RECONNECT_MIN = 1_000L
        private const val RECONNECT_MAX = 300_000L
        private const val PING_MS = 20_000L
        private const val STALE_MS = 60_000L
    }

    private val thread = HandlerThread("andwebcam-feed").apply { start() }
    private val handler = Handler(thread.looper)
    private val workers = Executors.newCachedThreadPool { r ->
        Thread(r, "andwebcam-feed-req").apply { isDaemon = true }
    }
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(0, TimeUnit.MILLISECONDS)
        .build()

    // The app's own loopback certificate is self-signed and regenerated with
    // the hostname, so trusting it here is safe: the connection never leaves
    // the device and the public side still terminates TLS at the relay.
    private val localSsl: SSLContext by lazy {
        val tm = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate?>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate?>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }
        SSLContext.getInstance("TLS").apply { init(null, arrayOf(tm), SecureRandom()) }
    }
    private val localHostname = HostnameVerifier { _, _ -> true }

    @Volatile private var socket: WebSocket? = null
    @Volatile private var enabled = false
    @Volatile private var connected = false
    @Volatile private var id = ""
    @Volatile private var origin = ""     // https://cam2.example.com
    @Volatile private var wsOrigin = ""   // wss://cam2.example.com
    @Volatile private var token = ""
    @Volatile private var password = ""
    @Volatile private var lastError = ""
    @Volatile private var lastPong = 0L

    private var reconnectDelay = RECONNECT_MIN

    /** Blank URL disables the feed. Other fields may change while connected. */
    fun configure(url: String, token: String, password: String) {
        val parsed = parse(url)
        val newEnabled = parsed != null
        val newOrigin = parsed?.first ?: ""
        val newWs = parsed?.second ?: ""
        val newId = parsed?.third ?: ""
        synchronized(this) {
            if (enabled == newEnabled && origin == newOrigin && wsOrigin == newWs &&
                id == newId && this.token == token && this.password == password
            ) return
            // Set the flag synchronously: the service uses it to decide
            // whether the camera must stay open.
            enabled = newEnabled
            origin = newOrigin
            wsOrigin = newWs
            id = newId
            this.token = token
            this.password = password
        }
        handler.post {
            handler.removeCallbacks(connectRunnable)
            handler.removeCallbacks(pingRunnable)
            socket?.cancel()
            socket = null
            connected = false
            lastError = ""
            reconnectDelay = RECONNECT_MIN
            if (enabled) {
                Log.i(TAG, "public proxy enabled: $id -> $origin")
                connect()
            } else {
                Log.i(TAG, "public proxy disabled")
            }
        }
    }

    /** URL -> (https origin, WebSocket origin, camera id). */
    private fun parse(url: String): Triple<String, String, String>? {
        if (url.isBlank()) return null
        val u = try { java.net.URI(url) } catch (_: Exception) { return null }
        if (u.scheme != "https" && u.scheme != "http") return null
        val host = u.host ?: return null
        val port = if (u.port > 0) ":${u.port}" else ""
        // A camera subdomain such as cam2.example.com gives the id directly.
        // On a plain hostname, fall back to a /cam/<id> path.
        val dottedHost = host.count { it == '.' } >= 2 && !host.matches(Regex("^[0-9.]+$"))
        val name = if (dottedHost) {
            host.substringBefore('.')
        } else {
            val parts = u.path.trim('/').split('/').filter { it.isNotEmpty() }
            if (parts.size < 2 || parts[parts.size - 2] != "cam") return null
            parts.last()
        }
        if (!name.matches(Regex("[a-z0-9-]{1,32}"))) return null
        val scheme = if (u.scheme == "https") "https" else "http"
        val wsScheme = if (u.scheme == "https") "wss" else "ws"
        return Triple("$scheme://$host$port", "$wsScheme://$host$port", name)
    }

    private fun authHeader() = "Bearer $token"

    private fun connect() {
        if (!enabled || socket != null) return
        val request = Request.Builder()
            .url("$wsOrigin/ws/")
            .header("Authorization", authHeader())
            .build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                handler.post {
                    if (!enabled || webSocket !== socket) return@post
                    Log.i(TAG, "public proxy control connected as $id")
                    connected = true
                    lastPong = System.currentTimeMillis()
                    reconnectDelay = RECONNECT_MIN
                    webSocket.send(
                        JSONObject()
                            .put("type", "hello")
                            .put("id", id)
                            .put("password", password)
                            .put("version", 1)
                            .toString()
                    )
                    handler.removeCallbacks(pingRunnable)
                    handler.postDelayed(pingRunnable, PING_MS)
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val msg = try { JSONObject(text) } catch (_: Exception) { return }
                when (msg.optString("type")) {
                    "hello_ok" -> handler.post {
                        connected = true
                        lastPong = System.currentTimeMillis()
                    }
                    "error" -> handler.post {
                        lastError = msg.optString("message")
                        Log.w(TAG, "public proxy error: $lastError")
                        webSocket.cancel()
                        gone(webSocket, lastError)
                    }
                    "pong" -> handler.post { lastPong = System.currentTimeMillis() }
                    "open" -> workers.execute { handleOpen(msg) }
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {}

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                handler.post { gone(webSocket, "closed ($code): $reason") }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                handler.post { gone(webSocket, t.message ?: t.javaClass.simpleName) }
            }
        })
    }

    /** Replay one browser request against the app's own HTTPS server. */
    private fun handleOpen(msg: JSONObject) {
        val rid = msg.optLong("id")
        val wsUrl = "$wsOrigin/req/$rid"
        val auth = authHeader()
        val body = ByteArrayOutputStream()
        val opened = CountDownLatch(1)
        val ended = CountDownLatch(1)
        val ref = AtomicReference<WebSocket?>()
        val reqSocket = client.newWebSocket(
            Request.Builder().url(wsUrl).header("Authorization", auth).build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    ref.set(webSocket)
                    opened.countDown()
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    body.write(bytes.toByteArray())
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    val m = try { JSONObject(text) } catch (_: Exception) { return }
                    if (m.optString("type") == "end") ended.countDown()
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = ended.countDown()
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = ended.countDown()
            }
        )

        try {
            if (!opened.await(10, TimeUnit.SECONDS)) return
            if (!ended.await(30, TimeUnit.SECONDS)) return
            val ws = ref.get() ?: return
            val method = msg.optString("method", "GET")
            val path = msg.optString("path", "/")
            val query = msg.optString("query", "")
            val urlText = "https://127.0.0.1:${Prefs.port}$path" +
                (if (query.isNotEmpty()) "?$query" else "")
            val conn = URL(urlText).openConnection() as HttpsURLConnection
            conn.sslSocketFactory = localSsl.socketFactory
            conn.hostnameVerifier = localHostname
            conn.requestMethod = method
            conn.instanceFollowRedirects = false
            conn.doInput = true
            val payload = body.toByteArray()
            conn.doOutput = payload.isNotEmpty()
            if (payload.isNotEmpty()) conn.setFixedLengthStreamingMode(payload.size)
            val headers = msg.optJSONObject("headers")
            headers?.keys()?.forEach { key ->
                when (key.lowercase()) {
                    "host", "connection", "content-length", "transfer-encoding", "accept-encoding" -> {}
                    else -> conn.setRequestProperty(key, headers.optString(key))
                }
            }
            if (payload.isNotEmpty()) conn.outputStream.use { it.write(payload) }

            val status = conn.responseCode
            val responseHeaders = JSONObject()
            conn.headerFields.forEach { (k, values) ->
                if (k != null && values.isNotEmpty()) responseHeaders.put(k, values.first())
            }
            ws.send(
                JSONObject()
                    .put("type", "resp")
                    .put("status", status)
                    .put("headers", responseHeaders)
                    .toString()
            )
            val input = try { conn.inputStream } catch (_: Exception) { conn.errorStream }
            val buffer = ByteArray(16 * 1024)
            input?.use { stream ->
                while (true) {
                    val n = stream.read(buffer)
                    if (n < 0) break
                    ws.send(buffer.toByteString(0, n))
                }
            }
            ws.send(JSONObject().put("type", "end").toString())
        } catch (e: Exception) {
            Log.w(TAG, "public proxy request $rid failed: ${e.message}")
            try {
                ref.get()?.send(
                    JSONObject().put("type", "resp").put("status", 502)
                        .put("headers", JSONObject().put("Content-Type", "text/plain")).toString()
                )
                ref.get()?.send("proxy error".toByteArray().toByteString())
                ref.get()?.send(JSONObject().put("type", "end").toString())
            } catch (_: Exception) {}
        }
        // Do not close here: the relay closes the socket after reading the
        // final "end" message. Cancelling immediately can discard the last
        // response frames before the relay has read them.
    }

    private fun gone(ws: WebSocket, reason: String) {
        if (ws !== socket) return
        socket = null
        connected = false
        lastError = reason
        handler.removeCallbacks(pingRunnable)
        if (!enabled) return
        // Any error is retryable, including a rejected registration: a stale
        // half-open socket on the relay clears itself, so trying again works.
        val delay = (reconnectDelay * (0.8 + Math.random() * 0.4)).toLong()
        reconnectDelay = min(reconnectDelay * 2, RECONNECT_MAX)
        Log.i(TAG, "public proxy reconnecting in ${delay / 1000}s: $reason")
        handler.postDelayed(connectRunnable, delay)
    }

    private val connectRunnable = Runnable { connect() }

    private val pingRunnable = Runnable { pingTick() }

    private fun pingTick() {
        val ws = socket
        if (!enabled || ws == null) return
        if (System.currentTimeMillis() - lastPong > STALE_MS) {
            Log.w(TAG, "public proxy ping timeout")
            ws.cancel()
            gone(ws, "ping timeout")
            return
        }
        ws.send(JSONObject().put("type", "ping").put("t", System.currentTimeMillis()).toString())
        handler.postDelayed(pingRunnable, PING_MS)
    }

    fun isEnabled(): Boolean = enabled
    fun isConnected(): Boolean = connected
    fun cameraId(): String = id
    fun error(): String = lastError

    fun shutdown() {
        handler.post {
            enabled = false
            handler.removeCallbacks(connectRunnable)
            handler.removeCallbacks(pingRunnable)
            socket?.cancel()
            socket = null
            connected = false
        }
        workers.shutdownNow()
    }
}
