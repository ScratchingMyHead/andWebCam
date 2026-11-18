package net.andwebcam

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log

/**
 * The result of an install started by [Updater].
 *
 * On success this process is replaced, so the broadcast arrives in the new
 * build's process: the note it writes is the first thing the web page shows.
 * A failure arrives in the old process, and is recorded in the same place.
 */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Updater.ACTION_STATUS) return
        Prefs.init(ctx)
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)

        when (status) {
            PackageInstaller.STATUS_SUCCESS -> {
                val v = try {
                    ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
                } catch (_: Exception) {
                    null
                }
                Prefs.updateNote = "Installed ${v ?: "the update"}."
                Log.i("andWebCam", "update installed: ${v ?: "?"}")
            }

            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // Not the device owner: the system wants a tap on the phone.
                Prefs.updateNote = "Waiting for somebody to confirm the install on the phone."
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT) as? Intent
                if (confirm != null) {
                    try {
                        ctx.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    } catch (e: Exception) {
                        Log.w("andWebCam", "could not show the install dialog: ${e.message}")
                        Prefs.updateNote = "The install needs confirming on the phone, " +
                            "but its screen could not be opened."
                    }
                }
            }

            else -> {
                val why = message?.takeIf { it.isNotBlank() } ?: "unknown reason ($status)"
                Prefs.updateNote = "Update failed: $why"
                Log.w("andWebCam", "update failed: $why")
            }
        }
    }
}
