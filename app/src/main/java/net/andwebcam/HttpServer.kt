package net.andwebcam

import android.content.Context
import android.util.Base64
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
                } catch (_: Exception) {
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

            method == "GET" && path == "/video" -> {
                if (role == Role.NONE) unauthorized(output) else serveVideo(output)
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
        val PAGE: String = """
<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>andWebCam</title>
<style>
 :root { color-scheme: dark }
 * { box-sizing: border-box }
 body { margin:0; font-family:system-ui,-apple-system,sans-serif; background:#111; color:#eee }
 header { padding:10px 14px; background:#1b1b1b; display:flex; gap:12px; align-items:center; flex-wrap:wrap }
 header h1 { font-size:16px; margin:0 }
 .muted { color:#999; font-size:12px }
 .grow { flex:1 }
 main { display:flex; gap:14px; padding:14px; flex-wrap:wrap; align-items:flex-start }
 .video { flex:1 1 460px; min-width:280px }
 img { width:100%; background:#000; border-radius:8px; display:block }
 .panel { flex:0 1 320px; background:#1b1b1b; border-radius:8px; padding:14px }
 .panel h2 { font-size:13px; text-transform:uppercase; letter-spacing:.06em; color:#8ab; margin:0 0 8px }
 .row { margin:10px 0; display:flex; flex-direction:column; gap:4px }
 label { font-size:13px; color:#bbb }
 select,input[type=text],input[type=password],input[type=number] { background:#0d0d0d; color:#eee; border:1px solid #333; border-radius:6px; padding:7px }
 input[type=range] { width:100% }
 button { background:#2b6cb0; color:#fff; border:0; border-radius:6px; padding:8px 12px; cursor:pointer; font-size:13px }
 button.secondary { background:#333 }
 .hidden { display:none !important }
 .badge { font-size:11px; padding:3px 8px; border-radius:10px; background:#2b6cb0 }
 .badge.anon { background:#555 }
 .badge.locked { background:#7a2f2f }
 .err { color:#f66; font-size:12px; min-height:1em }
 dl { margin:0; font-size:13px }
 dt { color:#888 } dd { margin:0 0 6px }
</style>
</head>
<body>
<header>
  <h1>andWebCam</h1>
  <span id="who" class="badge locked">...</span>
  <span id="stats" class="muted"></span>
  <span class="grow"></span>
  <button id="loginBtn" class="secondary">Log in</button>
  <button id="logoutBtn" class="secondary hidden">Log out</button>
</header>
<main>
  <div class="video">
    <img id="vid" src="/video" alt="camera stream">
    <p id="verr" class="err"></p>
  </div>
  <div class="panel">
    <h2>Status</h2>
    <dl>
      <dt>Address</dt><dd id="i_url">-</dd>
      <dt>Camera</dt><dd id="i_cam">-</dd>
      <dt>Resolution</dt><dd id="i_res">-</dd>
      <dt>Frame rate</dt><dd id="i_fps">-</dd>
      <dt>Torch</dt><dd id="i_torch">-</dd>
      <dt>Viewers</dt><dd id="i_view">-</dd>
    </dl>
  </div>
  <div class="panel hidden" id="admin">
    <h2>Controls</h2>
    <div class="row">
      <label>Camera</label>
      <select id="s_facing"><option value="back">Back</option><option value="front">Front</option></select>
    </div>
    <div class="row">
      <label>Resolution</label>
      <select id="s_res"></select>
    </div>
    <div class="row">
      <label>Frame rate: <span id="v_fps"></span> fps</label>
      <select id="s_fps"></select>
    </div>
    <div class="row">
      <label>JPEG quality: <span id="v_q"></span></label>
      <input id="s_q" type="range" min="10" max="100" step="5">
    </div>
    <div class="row">
      <label>Zoom: <span id="v_zoom"></span>x</label>
      <input id="s_zoom" type="range" min="1" max="4" step="0.1">
    </div>
    <div class="row">
      <label>Orientation</label>
      <select id="s_orient">
        <option value="0">Landscape</option>
        <option value="90">Portrait</option>
        <option value="180">Landscape (180)</option>
        <option value="270">Portrait (270)</option>
      </select>
    </div>
    <div class="row">
      <label><input id="s_torch" type="checkbox"> Torch / LED</label>
    </div>
    <div class="row">
      <label><input id="s_anon" type="checkbox"> Allow anonymous view-only access</label>
    </div>
    <h2 style="margin-top:16px">Service</h2>
    <div class="row">
      <label>Name (host becomes NAME.local)</label>
      <input id="s_name" type="text">
    </div>
    <div class="row">
      <label>HTTPS port</label>
      <input id="s_port" type="number" min="1" max="65535">
    </div>
    <button id="apply">Apply service settings</button>
    <p class="muted">Changing the name or port restarts the server; reconnect at the new address.</p>
    <h2 style="margin-top:16px">Admin password</h2>
    <div class="row">
      <label>New password</label>
      <input id="p_new" type="password">
    </div>
    <button id="pwset">Change password</button>
    <p class="muted">Once changed, the phone screen stops displaying the password.</p>
    <p id="pwmsg" class="err"></p>
  </div>
  <div class="panel hidden" id="loginForm">
    <h2>Log in</h2>
    <div class="row">
      <label>Username</label>
      <input id="l_user" type="text" value="admin">
    </div>
    <div class="row">
      <label>Password</label>
      <input id="l_pass" type="password">
    </div>
    <button id="doLogin">Log in</button>
    <p id="loginErr" class="err"></p>
  </div>
</main>
<script>
function el(id) { return document.getElementById(id); }
function admin() { return window.S && window.S.admin; }

function post(path, obj) {
  return fetch(path, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(obj)
  }).then(function(r) { return r.json().catch(function(){ return {}; }); });
}

function setSetting(key, value) {
  var o = {}; o[key] = value;
  post('/api/settings', o).then(function(r) {
    if (r.ok === false) { alert('Not authorized'); }
    setTimeout(refresh, 250);
  });
}

function refresh() {
  fetch('/api/status').then(function(r) { return r.json(); }).then(function(s) {
    window.S = s;
    el('who').textContent = s.admin ? 'admin' : (s.anonView ? 'view-only' : 'locked');
    el('who').className = 'badge ' + (s.admin ? '' : (s.anonView ? 'anon' : 'locked'));
    el('stats').textContent = (s.ip || '') + '  ' + (s.fpsActive || 0) + ' fps  ' + (s.clients || 0) + ' viewer(s)';
    el('i_url').textContent = s.url || '-';
    el('i_cam').textContent = s.facing || '-';
    el('i_res').textContent = (s.width || '?') + 'x' + (s.height || '?');
    el('i_fps').textContent = (s.fpsActive || 0) + ' fps (target ' + (s.fps || '?') + ')';
    el('i_torch').textContent = s.torch ? 'on' : 'off';
    el('i_view').textContent = (s.clients || 0);

    el('loginBtn').classList.toggle('hidden', !!s.admin);
    el('logoutBtn').classList.toggle('hidden', !s.admin);
    el('admin').classList.toggle('hidden', !s.admin);
    if (!s.admin) return;

    if (el('s_res').options.length !== (s.resolutions || []).length) {
      var rs = s.resolutions || [];
      el('s_res').innerHTML = '';
      rs.forEach(function(x) { var o = document.createElement('option'); o.value = x; o.textContent = x; el('s_res').appendChild(o); });
    }
    if (el('s_fps').options.length !== (s.fpsOptions || []).length) {
      el('s_fps').innerHTML = '';
      (s.fpsOptions || []).forEach(function(x) { var o = document.createElement('option'); o.value = x; o.textContent = x; el('s_fps').appendChild(o); });
    }
    if (document.activeElement !== el('s_facing')) el('s_facing').value = s.facing || 'back';
    if (document.activeElement !== el('s_res')) el('s_res').value = (s.width || '') + 'x' + (s.height || '');
    if (document.activeElement !== el('s_fps')) el('s_fps').value = String(s.fps || 15);
    if (document.activeElement !== el('s_q')) { el('s_q').value = s.quality || 80; el('v_q').textContent = s.quality || 80; }
    if (document.activeElement !== el('s_zoom')) { el('s_zoom').max = String(s.maxZoom || 4); el('s_zoom').value = s.zoom || 1; el('v_zoom').textContent = (s.zoom || 1).toFixed ? (s.zoom || 1).toFixed(1) : s.zoom; }
    if (document.activeElement !== el('s_orient')) el('s_orient').value = String(s.orientation || 0);
    if (document.activeElement !== el('s_name')) el('s_name').value = s.serviceName || 'andwebcam';
    if (document.activeElement !== el('s_port')) el('s_port').value = s.port || 8443;
    if (document.activeElement !== el('s_torch')) el('s_torch').checked = !!s.torch && !!s.torchAvailable;
    el('s_torch').disabled = !s.torchAvailable;
    if (document.activeElement !== el('s_anon')) el('s_anon').checked = !!s.anonView;
  }).catch(function() {
    el('verr').textContent = 'Cannot reach server';
  });
}

el('s_facing').onchange = function() { setSetting('facing', this.value); };
el('s_res').onchange = function() { setSetting('resolution', this.value); };
el('s_fps').onchange = function() { setSetting('fps', this.value); };
el('s_q').oninput = function() { el('v_q').textContent = this.value; };
el('s_q').onchange = function() { setSetting('quality', this.value); };
el('s_zoom').oninput = function() { el('v_zoom').textContent = parseFloat(this.value).toFixed(1); };
el('s_zoom').onchange = function() { setSetting('zoom', this.value); };
el('s_orient').onchange = function() { setSetting('orientation', this.value); };
el('s_torch').onchange = function() { setSetting('torch', this.checked); };
el('s_anon').onchange = function() { setSetting('anonView', this.checked); };
el('apply').onclick = function() {
  post('/api/settings', { serviceName: el('s_name').value, port: el('s_port').value }).then(function(r) {
    if (r.ok === false) alert('Not authorized');
  });
};
el('pwset').onclick = function() {
  var p = el('p_new').value;
  if (p.length < 4) { el('pwmsg').textContent = 'Too short'; return; }
  post('/api/password', { password: p }).then(function(r) {
    el('pwmsg').textContent = r.ok ? 'Password changed.' : 'Failed.';
    el('p_new').value = '';
    setTimeout(refresh, 300);
  });
};
el('loginBtn').onclick = function() { el('loginForm').classList.toggle('hidden'); };
el('logoutBtn').onclick = function() { post('/api/logout', {}).then(function() { location.reload(); }); };
el('doLogin').onclick = function() {
  var u = el('l_user').value, p = el('l_pass').value;
  post('/api/login', { user: u, pass: p }).then(function(r) {
    if (r.ok) { location.reload(); }
    else { el('loginErr').textContent = 'Wrong username or password'; }
  });
};

refresh();
setInterval(refresh, 2000);
</script>
</body>
</html>
"""
    }
}
