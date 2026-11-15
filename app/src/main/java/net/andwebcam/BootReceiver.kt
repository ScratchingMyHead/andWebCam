package net.andwebcam

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Starts the service at boot so the phone can be left unattended, unless it was
 * switched off on the phone screen - off means off, including here.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED || action == "android.intent.action.QUICKBOOT_POWERON") {
            Prefs.init(context)
            if (Prefs.enabled) CamService.start(context)
        }
    }
}
