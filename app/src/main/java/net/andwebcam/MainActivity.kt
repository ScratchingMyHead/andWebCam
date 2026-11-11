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
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Deliberately minimal: it shows where to connect and, while the generated
 * password is still in use, what that password is. Everything else is
 * configured from the web UI.
 */
class MainActivity : Activity() {
    private companion object {
        const val REQ_PERMISSIONS = 1
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var statusView: TextView
    private lateinit var urlView: TextView
    private lateinit var credsView: TextView
    private lateinit var errorView: TextView
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
        CamService.start(this)
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
            if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
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

    /**
     * An unattended stream dies if the system dozes the app, so ask once to be
     * exempt from battery optimisation.
     */
    private fun requestBatteryExemption() {
        if (Prefs.batteryPromptAsked) return
        val pm = getSystemService(PowerManager::class.java) ?: return
        if (pm.isIgnoringBatteryOptimizations(packageName)) return
        Prefs.batteryPromptAsked = true
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:$packageName"))
            )
        } catch (_: Exception) {
            // Some ROMs have no handler for this; not fatal.
        }
    }

    private fun update() {
        val name = "${Prefs.serviceName}.local"
        val url = "https://$name:${Prefs.port}/"
        statusView.text = when {
            !State.running -> "Stopped"
            !State.streaming -> "Running  -  streaming stopped"
            !State.cameraActive -> "Running  -  idle (no viewers)"
            else -> "Running  -  ${State.framesPerSec} fps  -  ${State.clients} viewer(s)"
        }
        urlView.text = url + "\n" + (State.ip ?: "no network address")

        val pw = Prefs.displayedPassword()
        if (pw != null) {
            credsView.visibility = View.VISIBLE
            credsView.text = "Admin login for the web UI\n" +
                "user:  ${Prefs.adminUser}\n" +
                "password:  $pw\n\n" +
                "Change it in the web UI; this will then stop being shown here."
        } else {
            credsView.visibility = View.GONE
        }

        errorView.text = State.lastError?.let { "Last error: $it" } ?: ""
        errorView.visibility = if (State.lastError == null) View.GONE else View.VISIBLE

        val dpm = getSystemService(DevicePolicyManager::class.java)
        ownerRow.visibility =
            if (dpm?.isDeviceOwnerApp(packageName) == true) View.VISIBLE else View.GONE
    }

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

        statusView = text("", 16f, bold = true)
        root.addView(statusView)
        root.addView(spacer())

        root.addView(text("Connect at", 13f))
        urlView = text("", 18f).apply {
            setTextIsSelectable(true)
            typeface = Typeface.MONOSPACE
        }
        root.addView(urlView)
        root.addView(spacer())

        credsView = text("", 15f).apply {
            setTextIsSelectable(true)
            typeface = Typeface.MONOSPACE
        }
        root.addView(credsView)
        root.addView(spacer())

        val restart = Button(this).apply {
            text = "Restart service"
            setOnClickListener { CamService.start(this@MainActivity) }
        }
        root.addView(restart)
        root.addView(spacer())

        errorView = text("", 13f)
        root.addView(errorView)

        if (fingerprint.isNotEmpty()) {
            root.addView(spacer())
            root.addView(text("TLS SHA-256 fingerprint", 13f))
            root.addView(text(fingerprint, 12f).apply { typeface = Typeface.MONOSPACE })
        }

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
