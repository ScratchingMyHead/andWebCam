package net.andwebcam

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.admin.DevicePolicyManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

/**
 * Deliberately minimal: it shows where to connect and, while the generated
 * password is still in use, what that password is. Everything else is
 * configured from the web UI.
 */
class MainActivity : Activity() {
    private companion object {
        const val REQ_PERMISSIONS = 1
        const val REQ_FOLDER = 2
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var enabledSwitch: Switch
    /** Set while the switch is being put back after a cancelled confirmation. */
    private var revertingSwitch = false
    private lateinit var disabledHint: TextView
    private lateinit var connectBlock: View
    private lateinit var restartRow: View
    private lateinit var fingerprintBlock: View
    private lateinit var statusView: TextView
    private lateinit var urlView: TextView
    private lateinit var credsView: TextView
    private lateinit var errorView: TextView
    private lateinit var batteryRow: Button
    private lateinit var batteryHint: TextView
    private lateinit var folderValue: TextView
    private lateinit var folderRow: Button
    private lateinit var folderClear: Button
    /** The folder the displayed name was looked up for, so the one-per-second
     *  repaint does not ask the document provider each time. */
    private var folderShownFor: String? = null
    private var folderShown: String? = null
    private lateinit var ownerRow: View
    private lateinit var ownerMsg: TextView
    private var fingerprint = ""

    private val tick = object : Runnable {
        override fun run() {
            update()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        fingerprint = try { Tls.fingerprint(this) } catch (e: Exception) { "" }
        setContentView(buildUi())
        requestStartupPermissions()
        // Switching it off on the phone has to stay off: opening the app must
        // not bring the service back by itself.
        if (Prefs.enabled) CamService.start(this)
    }

    override fun onResume() {
        super.onResume()
        handler.post(tick)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(tick)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERMISSIONS) {
            if (Prefs.enabled &&
                checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
            ) {
                CamService.reload(this)
            }
            requestBatteryExemption()
        }
    }

    /**
     * Everything the service needs, asked on first launch: camera (and
     * notifications on Android 13+). Battery optimisation is requested
     * separately as a system dialog.
     */
    private fun requestStartupPermissions() {
        val needed = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            needed += Manifest.permission.CAMERA
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            needed += Manifest.permission.POST_NOTIFICATIONS
        }
        if (needed.isEmpty()) {
            requestBatteryExemption()
        } else {
            requestPermissions(needed.toTypedArray(), REQ_PERMISSIONS)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_FOLDER && resultCode == RESULT_OK) {
            data?.data?.let { LogFolder.onPick(this, it) }
            update()
        }
    }

    /** The one-time folder pick; the grant is kept and survives reboots. */
    private fun pickFolder() {
        try {
            startActivityForResult(LogFolder.pickIntent(), REQ_FOLDER)
        } catch (e: Exception) {
            folderValue.text = "No folder picker on this phone: ${e.message}"
        }
    }

    private fun confirmClearFolder() {
        AlertDialog.Builder(this)
            .setTitle("Clear the log folder?")
            .setMessage(
                "andWebCam stops copying its log there. The files already written " +
                    "are left where they are, and choosing again starts a fresh log."
            )
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Clear") { _, _ ->
                LogFolder.clear(this)
                update()
            }
            .show()
    }

    /**
     * An unattended stream dies if the system dozes the app, so ask once to be
     * exempt from battery optimisation.
     */
    private fun requestBatteryExemption(force: Boolean = false) {
        val pm = getSystemService(PowerManager::class.java) ?: return
        if (pm.isIgnoringBatteryOptimizations(packageName)) return
        if (!force) {
            if (Prefs.batteryPromptAsked) return
            Prefs.batteryPromptAsked = true
        }
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:$packageName"))
            )
        } catch (_: Exception) {
            // Some ROMs have no handler for the per-app prompt; fall back to
            // the list, where the app can be found by hand.
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Switching off is the one action that cannot be undone from a browser, and
     * the one thing worth saying out loud is that it stays off after a restart.
     *
     * The switch goes straight back to on while the question is open: nothing
     * has changed yet, and leaving it looking off lets the once-a-second repaint
     * put it back underneath the dialog - which asks the question a second time.
     */
    private fun confirmDisable() {
        showSwitch(true)
        AlertDialog.Builder(this)
            .setTitle("Turn off andWebCam?")
            .setMessage(
                "The service stops now, its notification goes away, and it will not " +
                    "start when the phone is turned on again. Only this screen can " +
                    "turn it back on."
            )
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Turn off") { _, _ -> setEnabledState(false) }
            .show()
    }

    /** Move the switch without it being read as the user having moved it. */
    private fun showSwitch(on: Boolean) {
        if (enabledSwitch.isChecked == on) return
        revertingSwitch = true
        enabledSwitch.isChecked = on
        revertingSwitch = false
    }

    /**
     * The one place that switches the whole thing off. Off means stopped now,
     * no start at boot, no maintenance alarm and no notification, and only this
     * switch turns it back on.
     */
    private fun setEnabledState(on: Boolean) {
        Log.i("andWebCam", "service ${if (on) "enabled" else "disabled"} on the phone")
        Prefs.enabled = on
        showSwitch(on)
        if (on) CamService.start(this) else CamService.stop(this)
        update()
    }

    private fun update() {
        val name = "${Prefs.serviceName}.local"
        val url = "https://$name:${Prefs.port}/"
        val enabled = Prefs.enabled
        showSwitch(enabled)
        statusView.text = when {
            !enabled -> "Disabled"
            !State.running -> "Stopped"
            !State.streaming -> "Running  -  streaming stopped"
            !State.cameraActive -> "Running  -  idle"
            else -> "Running  -  ${State.framesPerSec} fps"
        }
        urlView.text = url + "\n" + (State.ip ?: "no network address")

        // Nothing to connect to and nothing to capture while it is off, so
        // everything that belongs to a running service goes away with it.
        connectBlock.visibility = shown(enabled)
        restartRow.visibility = shown(enabled)
        fingerprintBlock.visibility = shown(enabled)
        disabledHint.visibility = shown(!enabled)

        val pw = Prefs.displayedPassword()
        if (pw != null && enabled) {
            credsView.visibility = View.VISIBLE
            credsView.text = "Admin login for the web UI\n" +
                "user:  ${Prefs.adminUser}\n" +
                "password:  $pw\n\n" +
                "Change it in the web UI; this will then stop being shown here."
        } else {
            credsView.visibility = View.GONE
        }

        errorView.text = State.lastError?.let { "Last error: $it" } ?: ""
        errorView.visibility = if (State.lastError == null || !enabled) View.GONE else View.VISIBLE

        val dpm = getSystemService(DevicePolicyManager::class.java)
        ownerRow.visibility =
            if (dpm?.isDeviceOwnerApp(packageName) == true) View.VISIBLE else View.GONE

        val exempt = getSystemService(PowerManager::class.java)
            ?.isIgnoringBatteryOptimizations(packageName) == true
        batteryRow.visibility = shown(enabled && !exempt)
        batteryHint.visibility = batteryRow.visibility

        val picked = Prefs.logFolder
        if (picked != folderShownFor) {
            folderShown = LogFolder.describe(this)
            folderShownFor = picked
        }
        folderValue.text = if (picked == null) "No log folder chosen"
        else "Log folder: ${folderShown ?: "chosen"}"
        folderRow.visibility = shown(enabled)
        folderClear.visibility = shown(enabled && picked != null)
    }

    private fun shown(on: Boolean): Int = if (on) View.VISIBLE else View.GONE

    /**
     * Giving up device ownership is the only way a normally installed copy can be
     * uninstalled, so it is offered on the phone itself rather than over the web.
     * Restart phone stops working once it is done.
     */
    private fun removeOwnership() {
        val dpm = getSystemService(DevicePolicyManager::class.java)
        ownerMsg.text = try {
            dpm.clearDeviceOwnerApp(packageName)
            "Ownership removed. andWebCam can now be uninstalled."
        } catch (e: Exception) {
            "Could not remove ownership: ${e.message}"
        }
        ownerMsg.visibility = View.VISIBLE
        update()
    }

    private fun confirmRemoveOwnership() {
        AlertDialog.Builder(this)
            .setTitle("Remove ownership?")
            .setMessage("Restart phone will stop working. andWebCam can then be uninstalled normally.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Remove") { _, _ -> removeOwnership() }
            .show()
    }

    private fun buildUi(): View {
        val pad = dp(20)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        root.addView(text("andWebCam", 24f, bold = true))
        root.addView(text("LAN webcam service", 13f))
        root.addView(spacer())

        // The only way to switch it off, and deliberately not in the web UI:
        // a browser that could turn an unattended webcam off would need
        // somebody to walk to the phone to turn it back on.
        enabledSwitch = Switch(this).apply {
            text = "Enabled"
            isChecked = Prefs.enabled
            setOnCheckedChangeListener { _, on ->
                if (revertingSwitch) return@setOnCheckedChangeListener
                if (on) setEnabledState(true) else confirmDisable()
            }
        }
        root.addView(enabledSwitch)
        disabledHint = text("Off. Nothing runs, and it will not start with the phone.", 13f).apply {
            visibility = View.GONE
        }
        root.addView(disabledHint)
        root.addView(spacer())

        statusView = text("", 16f, bold = true)
        root.addView(statusView)
        root.addView(spacer())

        urlView = text("", 18f).apply {
            setTextIsSelectable(true)
            typeface = Typeface.MONOSPACE
        }
        connectBlock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(text("Connect at", 13f))
            addView(urlView)
        }
        root.addView(connectBlock)

        credsView = text("", 15f).apply {
            setTextIsSelectable(true)
            typeface = Typeface.MONOSPACE
        }
        root.addView(credsView)
        root.addView(spacer())

        restartRow = Button(this).apply {
            text = "Restart service"
            setOnClickListener { CamService.start(this@MainActivity) }
        }
        root.addView(restartRow)
        root.addView(spacer())

        // Samsung and its friends suspend an app that is not exempt from
        // battery optimisation, and the Wi-Fi radio goes with it: the phone is
        // then unreachable until the screen is turned on. Hidden once granted.
        batteryRow = Button(this).apply {
            text = "Allow background operation"
            setOnClickListener { requestBatteryExemption(force = true) }
        }
        root.addView(batteryRow)
        batteryHint = text("Battery optimisation is on, so the phone may drop off the network while it sleeps.", 13f)
        root.addView(batteryHint)
        root.addView(spacer())

        // The one-time folder pick that holds the on-disk copy of the log; it
        // is the same grant video recording will use later.
        folderValue = text("", 13f)
        root.addView(folderValue)
        folderRow = Button(this).apply {
            text = "Choose log folder"
            setOnClickListener { pickFolder() }
        }
        root.addView(folderRow)
        folderClear = Button(this).apply {
            text = "Clear log folder"
            setOnClickListener { confirmClearFolder() }
        }
        root.addView(folderClear)
        root.addView(spacer())

        errorView = text("", 13f)
        root.addView(errorView)

        fingerprintBlock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(spacer())
            if (fingerprint.isNotEmpty()) {
                addView(text("TLS SHA-256 fingerprint", 13f))
                addView(text(fingerprint, 12f).apply { typeface = Typeface.MONOSPACE })
            }
        }
        root.addView(fingerprintBlock)

        // Only shown while this app is the phone's device owner.
        ownerMsg = text("", 13f).apply { visibility = View.GONE }
        ownerRow = buildOwnerRow()
        root.addView(spacer())
        root.addView(ownerRow)
        root.addView(ownerMsg)

        val scroll = ScrollView(this)
        scroll.addView(root, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        return scroll
    }

    private fun buildOwnerRow(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(Button(this).apply {
            text = "Remove ownership"
            setOnClickListener { confirmRemoveOwnership() }
        })
        row.addView(text("disables restart and allows uninstall", 13f).apply {
            setPadding(dp(12), 0, 0, 0)
        })
        return row
    }

    private fun text(s: String, size: Float, bold: Boolean = false): TextView =
        TextView(this).apply {
            text = s
            setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    private fun spacer(): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(10))
    }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()
}
