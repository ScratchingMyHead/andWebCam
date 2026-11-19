package net.andwebcam

import android.app.Application
import android.os.Process
import android.util.Log

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)

        // The one log the /api/log endpoint can never serve: a process that dies
        // before it can answer. The handler runs on the dying thread, writes to
        // disk, and only then lets the system kill us.
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, t ->
            Log.e("andWebCam", "uncaught exception on ${thread.name}", t)
            try {
                LogFolder.crashReport(this, thread, t)
            } catch (_: Exception) {
            }
            if (previous != null) previous.uncaughtException(thread, t)
            else Process.killProcess(Process.myPid())
        }
    }
}
