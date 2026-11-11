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
    private const val KEY_SERVICE_NAME = "service_name"
    private const val KEY_FACING = "facing"
    private const val KEY_RESOLUTION = "resolution"
    private const val KEY_FPS = "fps"
    private const val KEY_QUALITY = "quality"
    private const val KEY_TORCH = "torch"
    private const val KEY_ZOOM = "zoom"
    private const val KEY_ORIENTATION = "orientation"
    private const val KEY_ANON_VIEW = "anon_view"
    private const val KEY_AUTO_START_STREAM = "auto_start_stream"
    private const val KEY_START_ON_BOOT = "start_on_boot"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_BATTERY_ASKED = "battery_asked"

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
        }
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

    private fun edit(block: SharedPreferences.Editor.() -> Unit) =
        sp.edit().apply(block).apply()

    // ---- general settings -------------------------------------------------

    var port: Int
        get() = sp.getInt(KEY_PORT, 8443)
        set(v) = edit { putInt(KEY_PORT, v.coerceIn(1, 65535)) }

    /** mDNS hostname stem; the advertised name is "<serviceName>.local". */
    var serviceName: String
        get() = sp.getString(KEY_SERVICE_NAME, "andwebcam")!!.ifBlank { "andwebcam" }
        set(v) = edit { putString(KEY_SERVICE_NAME, sanitizeName(v)) }

    var facing: String
        get() = sp.getString(KEY_FACING, "back")!!
        set(v) = edit { putString(KEY_FACING, if (v == "front") "front" else "back") }

    /** "WxH" or empty for auto. */
    var resolution: String
        get() = sp.getString(KEY_RESOLUTION, "")!!
        set(v) = edit { putString(KEY_RESOLUTION, v) }

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

    /** Whether streaming is enabled automatically when the service starts. */
    var autoStartStreaming: Boolean
        get() = sp.getBoolean(KEY_AUTO_START_STREAM, true)
        set(v) = edit { putBoolean(KEY_AUTO_START_STREAM, v) }

    var startOnBoot: Boolean
        get() = sp.getBoolean(KEY_START_ON_BOOT, true)
        set(v) = edit { putBoolean(KEY_START_ON_BOOT, v) }

    var enabled: Boolean
        get() = sp.getBoolean(KEY_ENABLED, true)
        set(v) = edit { putBoolean(KEY_ENABLED, v) }

    /** True once the battery-optimisation exemption dialog has been shown. */
    var batteryPromptAsked: Boolean
        get() = sp.getBoolean(KEY_BATTERY_ASKED, false)
        set(v) = edit { putBoolean(KEY_BATTERY_ASKED, v) }

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
