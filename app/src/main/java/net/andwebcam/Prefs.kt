package net.andwebcam

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * All persistent configuration.
 *
 * Admin credentials: on first run a random password is generated and kept in
 * plaintext (in private app storage) ONLY until the operator changes it through
 * the web UI. After a change the password is stored as a PBKDF2 hash + salt and
 * the plaintext is erased, which also stops the phone from displaying it.
 */
object Prefs {
    private const val FILE = "andwebcam"

    private const val KEY_PORT = "port"
    private const val KEY_ADMIN_PATH = "admin_path"
    private const val KEY_ADMIN_PATH_SET = "admin_path_set"
    private const val KEY_SERVICE_NAME = "service_name"
    private const val KEY_FACING = "facing"
    private const val KEY_RESOLUTION = "resolution"
    private const val KEY_STREAM_SCALE = "stream_scale"
    private const val KEY_FPS = "fps"
    private const val KEY_QUALITY = "quality"
    private const val KEY_TORCH = "torch"
    private const val KEY_ZOOM = "zoom"
    private const val KEY_ORIENTATION = "orientation"
    private const val KEY_ANON_VIEW = "anon_view"
    private const val KEY_PUBLIC_FEED_URL = "public_feed_url"
    private const val KEY_PUBLIC_FEED_TOKEN = "public_feed_token"
    private const val KEY_VIEWER_PASSWORD = "viewer_password"
    private const val KEY_MDNS = "mdns_enabled"
    private const val KEY_AUDIO = "audio_enabled"
    private const val KEY_AUTO_START_STREAM = "auto_start_stream"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_BATTERY_ASKED = "battery_asked"
    private const val KEY_UPDATE_NOTE = "update_note"
    private const val KEY_LAST_START = "last_start"
    private const val KEY_CLEAN_EXIT = "clean_exit"
    private const val KEY_LOG_FOLDER = "log_folder"
    private const val KEY_LAST_MIRROR_LINE = "last_mirror_line"
    private const val KEY_VIDEO_CAP_PERCENT = "video_cap_percent"
    private const val KEY_MOTION_ENABLED = "motion_enabled"
    private const val KEY_MOTION_ZONES = "motion_zones"
    private const val KEY_MOTION_QUIET = "motion_quiet"
    private const val KEY_MOTION_PREROLL = "motion_preroll"
    private const val KEY_MOTION_LEVEL = "motion_level"
    private const val KEY_MOTION_GRID = "motion_grid"
    private const val KEY_MOTION_DETECTOR = "motion_detector"
    private const val KEY_MOTION_LIGHT_GATE = "motion_light_gate"
    private const val KEY_CERT_ENABLED = "cert_enabled"
    private const val KEY_DDNS_ENABLED = "ddns_enabled"
    private const val KEY_CERT_DOMAIN = "cert_domain"
    private const val KEY_CERT_TOKEN = "cert_token"
    private const val KEY_CERT_METHOD = "cert_method"
    private const val KEY_CERT_PUBLISH = "cert_publish"
    private const val KEY_CERT_STAGING = "cert_staging"
    private const val KEY_ACME_PORT = "acme_port"
    private const val KEY_PUBLIC_PORT = "public_port"

    private const val KEY_ADMIN_USER = "admin_user"
    private const val KEY_PASS_PLAIN = "pass_plain"
    private const val KEY_PASS_SALT = "pass_salt"
    private const val KEY_PASS_HASH = "pass_hash"
    private const val KEY_PASS_IS_DEFAULT = "pass_is_default"

    private const val ITERATIONS = 120_000
    private const val KEY_BITS = 256

    private val rng = SecureRandom()
    private lateinit var sp: SharedPreferences

    @Synchronized
    fun init(ctx: Context) {
        if (!::sp.isInitialized) {
            sp = ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            ensureAdminPassword()
            ensureAdminPath()
            ensurePublicFeedToken()
        }
    }

    private fun ensurePublicFeedToken() {
        if (sp.getString(KEY_PUBLIC_FEED_TOKEN, "")!!.isNotBlank()) return
        val bytes = ByteArray(16)
        rng.nextBytes(bytes)
        sp.edit().putString(KEY_PUBLIC_FEED_TOKEN, bytes.joinToString("") { "%02x".format(it) }).apply()
    }

    private fun ensureAdminPassword() {
        if (!sp.contains(KEY_PASS_IS_DEFAULT)) {
            sp.edit()
                .putString(KEY_ADMIN_USER, "admin")
                .putString(KEY_PASS_PLAIN, randomPassword(10))
                .putBoolean(KEY_PASS_IS_DEFAULT, true)
                .apply()
        }
    }

    private fun ensureAdminPath() {
        if (sp.getBoolean(KEY_ADMIN_PATH_SET, false)) return
        val existing = sp.getString(KEY_ADMIN_PATH, "") ?: ""
        // A deliberate blank is just as much a user choice as a path, so
        // never replace it with a fresh random one on a later start.
        if (existing.isEmpty()) {
            sp.edit().putBoolean(KEY_ADMIN_PATH_SET, true).commit()
            return
        }
        if (existing.length != 8) {
            val bytes = ByteArray(4)
            rng.nextBytes(bytes)
            sp.edit().putString(KEY_ADMIN_PATH, bytes.joinToString("") { "%02x".format(it) }).apply()
        }
        // From now on the value is ours to change, including deliberately blank.
        sp.edit().putBoolean(KEY_ADMIN_PATH_SET, true).commit()
    }

    private fun edit(block: SharedPreferences.Editor.() -> Unit) =
        sp.edit().apply(block).apply()

    // ---- general settings -------------------------------------------------

    var port: Int
        get() = sp.getInt(KEY_PORT, 8443)
        set(v) = edit { putInt(KEY_PORT, v.coerceIn(1, 65535)) }

    /**
     * Where the admin UI lives. A random string by default; blank means the
     * admin page takes over "/" and the bare webcam view is not served.
     */
    var adminPath: String
        get() = sp.getString(KEY_ADMIN_PATH, "")!!
        // commit(), not apply(): this is the setting people edit from the
        // phone and then kill the service; losing the write brings back a
        // random path and locks them out of the URL they just memorised.
        set(v) {
            sp.edit().apply {
                val clean = v.trim().trim('/').lowercase()
                putString(
                    KEY_ADMIN_PATH,
                    if (clean.isEmpty() || clean.matches(Regex("[a-z0-9-]{1,32}"))) clean else adminPath
                )
                putBoolean(KEY_ADMIN_PATH_SET, true)
            }.commit()
        }

    /** Never sent to anonymous viewers. */
    val adminUrl: String get() = if (adminPath.isEmpty()) "/" else "/$adminPath"

    /** mDNS hostname stem; the advertised name is "<serviceName>.local". */
    var serviceName: String
        get() = sp.getString(KEY_SERVICE_NAME, "andwebcam")!!.ifBlank { "andwebcam" }
        set(v) = edit { putString(KEY_SERVICE_NAME, sanitizeName(v)) }

    /** Advertise the camera on the LAN over mDNS. */
    var mdnsEnabled: Boolean
        get() = sp.getBoolean(KEY_MDNS, true)
        set(v) = edit { putBoolean(KEY_MDNS, v) }

    /**
     * Live sound and sound in recordings, one switch. On: the Sound button is
     * offered and recordings carry an AAC track, with the microphone open
     * whenever streaming is on so a recording's pre-roll can include sound.
     * Off: no sound anywhere, and nothing is captured.
     */
    var audioEnabled: Boolean
        get() = sp.getBoolean(KEY_AUDIO, false)
        set(v) = edit { putBoolean(KEY_AUDIO, v) }

    var facing: String
        get() = sp.getString(KEY_FACING, "back")!!
        set(v) = edit { putString(KEY_FACING, if (v == "front") "front" else "back") }

    /** "WxH" or empty for auto. */
    var resolution: String
        get() = sp.getString(KEY_RESOLUTION, "")!!
        set(v) = edit { putString(KEY_RESOLUTION, v) }

    /**
     * Percentage of the camera's resolution sent to viewers. The recorder
     * and motion analyser always use the full-resolution frames; only the
     * stream is downscaled, and only while somebody is watching.
     */
    var streamScale: Int
        get() = sp.getInt(KEY_STREAM_SCALE, 50)
        set(v) = edit { putInt(KEY_STREAM_SCALE, if (v in listOf(25, 50, 75, 100)) v else 50) }

    var fps: Int
        get() = sp.getInt(KEY_FPS, 15)
        set(v) = edit { putInt(KEY_FPS, v.coerceIn(1, 60)) }

    var quality: Int
        get() = sp.getInt(KEY_QUALITY, 80)
        set(v) = edit { putInt(KEY_QUALITY, v.coerceIn(10, 100)) }

    var torch: Boolean
        get() = sp.getBoolean(KEY_TORCH, false)
        set(v) = edit { putBoolean(KEY_TORCH, v) }

    var zoom: Float
        get() = sp.getFloat(KEY_ZOOM, 1.0f)
        set(v) = edit { putFloat(KEY_ZOOM, v.coerceAtLeast(1.0f)) }

    /** Rotation applied to the outgoing JPEG, in degrees clockwise. */
    var orientation: Int
        get() = sp.getInt(KEY_ORIENTATION, 0)
        set(v) = edit { putInt(KEY_ORIENTATION, ((v % 360) + 360) % 360) }

    /** Anonymous, view-only access to the stream and status. Off by default. */
    var anonView: Boolean
        get() = sp.getBoolean(KEY_ANON_VIEW, false)
        set(v) = edit { putBoolean(KEY_ANON_VIEW, v) }

    /**
     * Outbound public feed. The URL is the public page path, e.g.
     * "https://cam2.example.com/"; blank disables the feed. The token
     * authenticates this phone to the relay; the viewing password protects
     * the public page, and blank means no password.
     */
    var publicFeedUrl: String
        get() = sp.getString(KEY_PUBLIC_FEED_URL, "")!!.trim()
        set(v) = edit { putString(KEY_PUBLIC_FEED_URL, v.trim()) }

    var publicFeedToken: String
        get() = sp.getString(KEY_PUBLIC_FEED_TOKEN, "")!!.trim()
        set(v) = edit { putString(KEY_PUBLIC_FEED_TOKEN, v.trim()) }

    var viewerPassword: String
        get() = sp.getString(KEY_VIEWER_PASSWORD, "")!!
        set(v) = edit { putString(KEY_VIEWER_PASSWORD, v) }

    /** Whether streaming is enabled automatically when the service starts. */
    var autoStartStreaming: Boolean
        get() = sp.getBoolean(KEY_AUTO_START_STREAM, true)
        set(v) = edit { putBoolean(KEY_AUTO_START_STREAM, v) }

    /**
     * Whether the service may run at all. Turned off and on from the phone
     * screen only: a browser that could switch an unattended webcam off would
     * need somebody to walk to the phone to switch it back on.
     */
    var enabled: Boolean
        get() = sp.getBoolean(KEY_ENABLED, true)
        set(v) = edit { putBoolean(KEY_ENABLED, v) }

    /**
     * The outcome of the last software update, kept across the restart that a
     * successful one causes so the page can say what happened.
     */
    var updateNote: String
        get() = sp.getString(KEY_UPDATE_NOTE, "")!!
        set(v) = edit { putString(KEY_UPDATE_NOTE, v.take(300)) }

    /**
     * When the service last started, and whether the run before it ended on
     * purpose. A phone that restarts itself at 3am leaves no other trace that
     * the page can see, so the next start reports it.
     */
    var lastStart: Long
        get() = sp.getLong(KEY_LAST_START, 0L)
        set(v) { sp.edit().putLong(KEY_LAST_START, v).commit() }

    var cleanExit: Boolean
        get() = sp.getBoolean(KEY_CLEAN_EXIT, true)
        // commit(), not apply(): the process is about to end either way and the
        // background write may never reach the disk.
        set(v) { sp.edit().putBoolean(KEY_CLEAN_EXIT, v).commit() }

    /** True once the battery-optimisation exemption dialog has been shown. */
    var batteryPromptAsked: Boolean
        get() = sp.getBoolean(KEY_BATTERY_ASKED, false)
        set(v) = edit { putBoolean(KEY_BATTERY_ASKED, v) }

    /**
     * The folder (a SAF tree URI) that holds the on-disk copy of the log, or
     * null while none has been chosen. The grant survives reboots; the lines
     * already copied are remembered in [lastMirrorLine].
     */
    var logFolder: String?
        get() = sp.getString(KEY_LOG_FOLDER, null)
        set(v) {
            if (v == null) edit { remove(KEY_LOG_FOLDER); remove(KEY_LAST_MIRROR_LINE) }
            else edit { putString(KEY_LOG_FOLDER, v) }
        }

    /**
     * The last log line already copied to the on-disk file: the next mirror
     * appends the lines that come after it, so consecutive runs grow the same
     * file instead of duplicating it.
     */
    var lastMirrorLine: String?
        get() = sp.getString(KEY_LAST_MIRROR_LINE, null)
        set(v) {
            if (v == null) edit { remove(KEY_LAST_MIRROR_LINE) }
            else edit { putString(KEY_LAST_MIRROR_LINE, v.take(512)) }
        }

    /**
     * How much of the free space the recordings may occupy, as a percentage.
     * The oldest videos are deleted first as the total approaches it, and a
     * running recording stops cleanly when even deleting everything would
     * not keep it under.
     */
    var videoCapPercent: Int
        get() = sp.getInt(KEY_VIDEO_CAP_PERCENT, 10)
        set(v) = edit { putInt(KEY_VIDEO_CAP_PERCENT, v.coerceIn(1, 100)) }

    /**
     * Motion activation. Enabled survives service restarts and reboots: the
     * service resumes monitoring on start when a storage location is chosen.
     * The zones are a compact string in the camera's normalized coordinates -
     * "aspect;sens:x,y,x,y,...;sens:..." - so the same zones work at any
     * resolution with the same aspect ratio.
     */
    var motionEnabled: Boolean
        get() = sp.getBoolean(KEY_MOTION_ENABLED, false)
        set(v) = edit { putBoolean(KEY_MOTION_ENABLED, v) }

    var motionZones: String?
        get() = sp.getString(KEY_MOTION_ZONES, null)
        set(v) {
            if (v == null) edit { remove(KEY_MOTION_ZONES) } else edit { putString(KEY_MOTION_ZONES, v) }
        }

    /** Seconds of quiet after the last motion before a motion recording ends. */
    var motionQuiet: Int
        get() = sp.getInt(KEY_MOTION_QUIET, 30)
        set(v) = edit { putInt(KEY_MOTION_QUIET, v.coerceIn(5, 300)) }

    /** Seconds of pre-trigger frames the recorder keeps ready. 0 disables it. */
    var motionPreRoll: Int
        get() = sp.getInt(KEY_MOTION_PREROLL, 10)
        set(v) = edit { putInt(KEY_MOTION_PREROLL, v.coerceIn(0, 30)) }

    /**
     * How much a cell's brightness must shift against its background before
     * the cell counts as changed, as a 0-100 sensitivity: higher is more
     * sensitive. The stored value is the sensitivity; the engine maps it to
     * the actual grey-level threshold.
     */
    var motionLevel: Int
        get() = sp.getInt(KEY_MOTION_LEVEL, 85)
        set(v) = edit { putInt(KEY_MOTION_LEVEL, v.coerceIn(0, 100)) }

    /**
     * Motion grid size: the number of cells across the long edge of the
     * frame. 32 is the long-standing default; tiny values give coarse
     * blocks, large values give fine cells that see more detail (and more
     * sensor noise).
     */
    var motionGrid: Int
        get() = sp.getInt(KEY_MOTION_GRID, 32)
        set(v) = edit { putInt(KEY_MOTION_GRID, v.coerceIn(4, 160)) }

    /** Which frame analyser feeds the motion pane and the zone triggers. */
    var motionDetector: String
        get() = sp.getString(KEY_MOTION_DETECTOR, "grid").takeIf { it in listOf("grid", "opencv") } ?: "grid"
        set(v) = edit { if (v in listOf("grid", "opencv")) putString(KEY_MOTION_DETECTOR, v) }

    /** OpenCV mode: reject changes that keep the background's texture. */
    var motionLightGate: Boolean
        get() = sp.getBoolean(KEY_MOTION_LIGHT_GATE, true)
        set(v) = edit { putBoolean(KEY_MOTION_LIGHT_GATE, v) }

    // ---- public certificate -----------------------------------------------

    /** Issue and serve a Let's Encrypt certificate. */
    var certEnabled: Boolean
        get() = sp.getBoolean(KEY_CERT_ENABLED, true)
        set(v) = edit { putBoolean(KEY_CERT_ENABLED, v) }

    /** Keep the DuckDNS A record pointed at this phone. Independent of the cert. */
    var ddnsEnabled: Boolean
        get() = sp.getBoolean(KEY_DDNS_ENABLED, true)
        set(v) = edit { putBoolean(KEY_DDNS_ENABLED, v) }

    /** The DNS name the Let's Encrypt certificate is issued for. */
    var certDomain: String
        get() = sp.getString(KEY_CERT_DOMAIN, "")!!.trim().lowercase()
        set(v) = edit { putString(KEY_CERT_DOMAIN, v.trim().lowercase()) }

    /** DuckDNS API token. Never sent to the browser; the page only sees a flag. */
    var certToken: String
        get() = sp.getString(KEY_CERT_TOKEN, "")!!.trim()
        set(v) = edit { putString(KEY_CERT_TOKEN, v.trim()) }

    /** "dns-01" (DuckDNS TXT) or "http-01" (port-80 forward). */
    var certMethod: String
        get() = sp.getString(KEY_CERT_METHOD, "dns-01")!!
        set(v) = edit { putString(KEY_CERT_METHOD, if (v == "http-01") v else "dns-01") }

    /** "lan", "wan" or "none": which address DuckDNS should publish. */
    var certPublish: String
        get() = sp.getString(KEY_CERT_PUBLISH, "lan")!!
        set(v) = edit { putString(KEY_CERT_PUBLISH, if (v in listOf("lan", "wan", "none")) v else "lan") }

    var certStaging: Boolean
        get() = sp.getBoolean(KEY_CERT_STAGING, false)
        set(v) = edit { putBoolean(KEY_CERT_STAGING, v) }

    /** Internal port the router forwards external 80 to, for http-01. */
    var acmePort: Int
        get() = sp.getInt(KEY_ACME_PORT, 8080)
        set(v) = edit { putInt(KEY_ACME_PORT, v.coerceIn(1, 65535)) }

    /** Extra HTTPS listener presenting the Let's Encrypt certificate. */
    var publicPort: Int
        get() = sp.getInt(KEY_PUBLIC_PORT, 8444)
        set(v) = edit { putInt(KEY_PUBLIC_PORT, v.coerceIn(1, 65535)) }

    // ---- admin credentials ------------------------------------------------

    val adminUser: String get() = sp.getString(KEY_ADMIN_USER, "admin")!!

    /** True while the randomly generated first-run password is still in use. */
    val passwordIsDefault: Boolean get() = sp.getBoolean(KEY_PASS_IS_DEFAULT, true)

    /** Only non-null while the generated password has not been replaced. */
    fun displayedPassword(): String? =
        if (passwordIsDefault) sp.getString(KEY_PASS_PLAIN, null) else null

    fun verifyAdmin(user: String, pass: String): Boolean {
        if (user != adminUser) return false
        if (passwordIsDefault) {
            val plain = sp.getString(KEY_PASS_PLAIN, null) ?: return false
            return constantTimeEquals(plain.toByteArray(), pass.toByteArray())
        }
        val saltB64 = sp.getString(KEY_PASS_SALT, null) ?: return false
        val hashB64 = sp.getString(KEY_PASS_HASH, null) ?: return false
        val salt = Base64.decode(saltB64, Base64.NO_WRAP)
        val expected = Base64.decode(hashB64, Base64.NO_WRAP)
        return constantTimeEquals(expected, pbkdf2(pass, salt))
    }

    /** Replace the admin password and stop displaying the generated one. */
    fun setAdminPassword(newPass: String) {
        val salt = ByteArray(16).also { rng.nextBytes(it) }
        val hash = pbkdf2(newPass, salt)
        edit {
            putString(KEY_PASS_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
            putString(KEY_PASS_HASH, Base64.encodeToString(hash, Base64.NO_WRAP))
            putBoolean(KEY_PASS_IS_DEFAULT, false)
            remove(KEY_PASS_PLAIN)
        }
    }

    // ---- helpers ----------------------------------------------------------

    private fun pbkdf2(pass: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pass.toCharArray(), salt, ITERATIONS, KEY_BITS)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    }

    private fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var r = 0
        for (i in a.indices) r = r or (a[i].toInt() xor b[i].toInt())
        return r == 0
    }

    private fun randomPassword(len: Int): String {
        // Ambiguous characters (0/O, 1/l/I) deliberately omitted for typing.
        val alphabet = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val sb = StringBuilder(len)
        repeat(len) { sb.append(alphabet[rng.nextInt(alphabet.length)]) }
        return sb.toString()
    }

    private fun sanitizeName(v: String): String =
        v.lowercase().filter { it.isLetterOrDigit() || it == '-' }.ifBlank { "andwebcam" }
}
