package net.andwebcam

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.view.WindowManager

/**
 * Full-screen black form that keeps the display on. Launched from the web UI
 * ("Wake screen" button) when the phone's screen is off and a VNC viewer
 * can't connect because the display is powered down.
 *
 * FLAG_TURN_SCREEN_ON turns the display on; FLAG_KEEP_SCREEN_ON keeps it on.
 * Tapping anywhere dismisses the form and reveals whatever is behind it.
 */
class BlackScreenActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                WindowManager.LayoutParams.FLAG_FULLSCREEN
        )

        // Black background: nothing showing on the screen.
        val view = View(this).apply { setBackgroundColor(0xFF000000.toInt()) }
        setContentView(view)

        // A tap anywhere dismisses the form.
        view.setOnClickListener { finish() }
    }

    override fun onUserLeaveHint() {
        // The user is navigating away: don't hang around.
        super.onUserLeaveHint()
        finish()
    }
}
