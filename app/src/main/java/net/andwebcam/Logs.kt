package net.andwebcam

import android.os.Build
import android.util.Log
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * The app's own log, readable over HTTP.
 *
 * logd hands an unprivileged process only the entries belonging to its uid, so
 * running logcat from inside the app returns this app's messages and nothing
 * of anybody else's - including the entries left by a previous process, which
 * is exactly what a crash needs. No adb, no debug build, no root.
 */
object Logs {
    private const val TAG = "andWebCam"

    /** Cap on what one request may return; a phone left for a week logs a lot. */
    private const val MAX_CHARS = 512 * 1024

    /**
     * @param lines how many lines to return, the newest
     * @param all   include the framework chatter this process also produces
     *              (view layout, renderer, ART), which is only occasionally useful
     */
    fun dump(lines: Int, all: Boolean): String {
        val sb = StringBuilder()
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        sb.append("andWebCam ${BuildConfig.VERSION_NAME} (${BuildConfig.BUILD_ID})")
            .append(" on ").append(Build.MODEL)
            .append(", ").append(Build.VERSION.RELEASE)
            .append(" - read at ").append(now)
        if (!all) sb.append(" - andWebCam lines only; add ?all=1 for everything")
        sb.append('\n').append("---\n")

        val kept = collect(all, lines)
        if (kept == null) {
            return sb.append("could not read the log; see the app's log for the error\n").toString()
        }

        for (line in kept) {
            if (sb.length + line.length > MAX_CHARS) {
                sb.append("... (truncated; ask for fewer lines)\n")
                break
            }
            sb.append(line).append('\n')
        }
        return sb.toString()
    }

    /**
     * The newest [limit] lines this process owns, oldest first; null when the
     * log could not be read.
     *
     * The whole buffer is read on purpose: with -t the window is the newest
     * lines of the *device* log, and a quiet app's own lines fall outside it
     * within seconds. The uid filter above keeps this to our own entries, so
     * it is a few thousand lines even on a phone that has been up for days.
     */
    private fun collect(all: Boolean, limit: Int): List<String>? {
        val kept = ArrayDeque<String>(limit)

        var p: Process? = null
        try {
            p = ProcessBuilder(
                "/system/bin/logcat", "-d", "-b", "all", "-v", "threadtime",
            ).redirectErrorStream(true).start()

            p.inputStream.bufferedReader().useLines { seq ->
                for (line in seq) {
                    // One line per socket this app has ever made, which drowns
                    // everything worth reading.
                    if (line.contains("TrafficStats: tagSocket")) continue
                    if (!all && !interesting(line)) continue
                    kept.addLast(line)
                    if (kept.size > limit) kept.removeFirst()
                }
            }
            p.waitFor(5, TimeUnit.SECONDS)
        } catch (e: Exception) {
            Log.w(TAG, "log dump failed: ${e.message}")
            return null
        } finally {
            try { p?.destroy() } catch (_: Exception) {}
        }
        return kept.toList()
    }

    /** The whole filtered buffer, newest last: what the on-disk copy appends from. */
    fun allLines(): List<String> = collect(false, 100_000) ?: emptyList()

    /** `11-18 22:34:53.202 17479 17479 I net.andwebcam: ...` */
    private val entry = Regex("""^\d\d-\d\d \d\d:\d\d:\d\d\.\d+\s+\d+\s+\d+\s+[VDIWEF]\s+([^:]*):""")

    /** Log tags that are always worth sending. */
    private val ourTags = setOf("andWebCam", "AndroidRuntime", "System.err", "libc", "DEBUG")

    /**
     * The system's events about our process: when it started, when it was
     * killed and why, when it crashed, whether the foreground service came up.
     * These are what a "it just went away overnight" report needs.
     */
    private val eventTags = setOf(
        "am_proc_start", "am_proc_bound", "am_proc_died", "am_kill", "am_anr",
        "am_crash", "am_wtf", "am_low_memory", "am_foreground_service_start",
    )

    /**
     * Our own messages, crash traces, and the system's events about this
     * package. The uid filter has already removed everybody else's lines; this
     * removes this process's own framework noise, which is most of the volume.
     */
    private fun interesting(line: String): Boolean {
        val tag = entry.find(line)?.groupValues?.get(1)?.trim() ?: return false
        if (tag in ourTags) return true
        return tag in eventTags && line.contains("andwebcam", ignoreCase = true)
    }
}
