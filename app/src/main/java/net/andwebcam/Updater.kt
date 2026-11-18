package net.andwebcam

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

/**
 * Installs an APK uploaded through the web page.
 *
 * PackageInstaller is the only install route an ordinary app has; there is no
 * permission that would let an app write another one. When this app is the
 * device owner the system installs the update without asking (that is how the
 * phone is set up here), otherwise the usual "Install?" dialog appears on the
 * phone's own screen and somebody has to tap it. Either way the result comes
 * back through [UpdateReceiver].
 *
 * The uploaded file is validated before a session is opened: it has to be an
 * APK of this package, signed with the same key as the installed copy, and not
 * older. All three are things Android would refuse at the very end of the
 * process with a much less useful message.
 */
object Updater {
    private const val TAG = "andWebCam"
    const val ACTION_STATUS = "net.andwebcam.UPDATE_STATUS"
    const val EXTRA_ERROR = "error"

    class Bad(val reason: String) : Exception(reason)

    /** An APK copied into a PackageInstaller session, waiting for commit. */
    class Staged internal constructor(
        private val installer: PackageInstaller,
        private val sessionId: Int,
        val label: String,
    ) {
        /** Hands the session to the system. Returns an error, or null if it started. */
        fun commit(ctx: Context): String? {
            val session = try {
                installer.openSession(sessionId)
            } catch (e: Exception) {
                return "Could not open the install session: ${e.message}"
            }
            val status = Intent(ctx, UpdateReceiver::class.java).setAction(ACTION_STATUS)
            val pi = android.app.PendingIntent.getBroadcast(
                ctx, sessionId, status,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                    android.app.PendingIntent.FLAG_MUTABLE,
            )
            return try {
                session.commit(pi.intentSender)
                null
            } catch (e: Exception) {
                try { session.abandon() } catch (_: Exception) {}
                "${e.javaClass.simpleName}: ${e.message}"
            } finally {
                try { session.close() } catch (_: Exception) {}
            }
        }

        fun discard() {
            try { installer.abandonSession(sessionId) } catch (_: Exception) {}
        }
    }

    /**
     * Checks the APK and copies it into an install session.
     *
     * @throws Bad with a sentence that can be shown to the person who uploaded it.
     */
    fun stage(ctx: Context, apk: File): Staged {
        val pm = ctx.packageManager
        @Suppress("DEPRECATION")
        val info = pm.getPackageArchiveInfo(
            apk.absolutePath,
            PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_ACTIVITIES,
        ) ?: throw Bad("That file is not an APK Android can read.")

        if (info.packageName != ctx.packageName) {
            throw Bad("That APK is ${info.packageName}, not andWebCam.")
        }

        @Suppress("DEPRECATION")
        val installed = pm.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNING_CERTIFICATES)

        if (info.longVersionCode < installed.longVersionCode) {
            throw Bad(
                "That APK is version ${info.versionName} (${info.longVersionCode}), older than " +
                    "the installed ${installed.versionName} (${installed.longVersionCode}). " +
                    "Android will not install an older version over a newer one."
            )
        }

        val newKeys = fingerprints(info)
        val oldKeys = fingerprints(installed)
        if (newKeys.isEmpty()) {
            throw Bad("That APK carries no signature.")
        }
        if (oldKeys.isNotEmpty() && newKeys.intersect(oldKeys).isEmpty()) {
            throw Bad(
                "That APK is signed with a different key than the installed app, so Android " +
                    "will refuse it as an update. Use a build signed with the same key."
            )
        }

        val label = "${info.versionName} (${info.longVersionCode})"

        val installer = pm.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setSize(apk.length())
        params.setInstallReason(PackageManager.INSTALL_REASON_USER)
        try {
            params.setAppPackageName(info.packageName)
        } catch (_: Exception) {
        }

        val sessionId = try {
            installer.createSession(params)
        } catch (e: Exception) {
            throw Bad("Could not start an install session: ${e.message}")
        }

        try {
            installer.openSession(sessionId).use { session ->
                session.openWrite("base.apk", 0, apk.length()).use { out ->
                    FileInputStream(apk).use { it.copyTo(out, 64 * 1024) }
                    session.fsync(out)
                }
                // Closing the write stream is what tells the system the bytes
                // are complete.
            }
        } catch (e: Exception) {
            try { installer.abandonSession(sessionId) } catch (_: Exception) {}
            throw Bad("Could not hand the APK to the installer: ${e.message}")
        }

        Log.i(TAG, "update $label staged as session $sessionId")
        return Staged(installer, sessionId, label)
    }

    /** Hex SHA-256 of every signing certificate in a package. */
    private fun fingerprints(info: android.content.pm.PackageInfo): Set<String> {
        val si = info.signingInfo ?: return emptySet()
        val certs = if (si.hasMultipleSigners()) si.apkContentsSigners else si.signingCertificateHistory
        return certs.map {
            MessageDigest.getInstance("SHA-256").digest(it.toByteArray())
                .joinToString(":") { b -> "%02X".format(b) }
        }.toSet()
    }
}
