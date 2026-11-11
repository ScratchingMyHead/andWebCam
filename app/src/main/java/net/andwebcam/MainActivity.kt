package net.andwebcam

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
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
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var statusView: TextView
    private lateinit var urlView: TextView
    private lateinit var credsView: TextView
    private lateinit var errorView: TextView
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
        requestCameraPermission()
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
        if (requestCode == 1 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            CamService.reload(this)
        }
    }

    private fun requestCameraPermission() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 1)
        }
    }

    private fun update() {
        val name = "${Prefs.serviceName}.local"
        val url = "https://$name:${Prefs.port}/"
        statusView.text = if (State.running) {
            "Running  -  ${State.framesPerSec} fps  -  ${State.clients} viewer(s)"
        } else {
            "Stopped"
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

        val scroll = ScrollView(this)
        scroll.addView(root, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        return scroll
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
