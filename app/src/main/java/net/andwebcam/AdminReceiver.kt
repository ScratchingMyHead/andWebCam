package net.andwebcam

import android.app.admin.DeviceAdminReceiver

/**
 * There is no user-facing admin UI here. The receiver exists so the app can
 * become device owner and then use DevicePolicyManager.reboot() to power-cycle
 * the phone from the web UI. Becoming device owner is a provisioning step (see
 * the developer notes in README.md); it needs the app to be an active device
 * admin, which is what the manifest receiver + device_admin.xml declare.
 */
class AdminReceiver : DeviceAdminReceiver()
