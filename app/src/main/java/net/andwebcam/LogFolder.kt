package net.andwebcam

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The on-disk copy of the log, in the storage folder's own "logs"
 * subdirectory.
 *
 * /api/log only works while a live process serves it: an update that crashes
 * before the server starts leaves nothing to download. The copy written here
 * keeps growing while the service runs, is completed when the service stops,
 * and the crash handler adds the stack trace when the process dies any other
 * way - so even a bad update leaves a readable log in a folder any file
 * browser (or FTP server) on the phone can reach.
 *
 * The storage folder is granted once through the system document picker - it
 * is where recordings go, so the pane asks for it - and the grant survives
 * reboots. The log's place inside it is not configurable: always "logs",
 * next to the recordings' "video".
 */
object LogFolder {
    private const val TAG = "andWebCam"

    /** The app writes into its own subdirectory, so the chosen folder stays tidy. */
    private const val SUBDIR = "logs"

    /** Where builds before the "logs" rename kept the same files. */
    private const val OLD_SUBDIR = "andwebcam"
    private const val LOG_NAME = "andwebcam.log"
    private const val LOG_PREV = "andwebcam.log.1"

    /** Rotate at 1 MB per file: about two megabytes of history in total. */
    private const val MAX_BYTES = 1_048_576

    private val exec = Executors.newSingleThreadExecutor { r ->
        Thread(r, "andwebcam-mirror").apply { isDaemon = true }
    }
    private val busy = AtomicBoolean(false)
    private val lock = Any()

    /** The chosen tree as a URI, or null while no folder has been chosen. */
    val tree: Uri?
        get() = Saf.tree

    /** The chosen folder's name, for the page and the phone's screen. */
    fun describe(ctx: Context): String? = Saf.describe(ctx)

    /** The system folder picker, reopened at the folder that is already chosen. */
    fun pickIntent(): Intent =
        Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            tree?.let { putExtra(DocumentsContract.EXTRA_INITIAL_URI, it) }
        }

    /** Called with the user's pick: keep the grant, remember it, first copy. */
    fun onPick(ctx: Context, uri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        try {
            ctx.contentResolver.takePersistableUriPermission(uri, flags)
        } catch (e: Exception) {
            Log.w(TAG, "cannot keep the folder grant: ${e.message}")
        }
        Prefs.logFolder = uri.toString()
        Log.i(TAG, "storage folder set: ${describe(ctx) ?: uri}")
        mirror(ctx)
    }

    /** Stop using the folder. The files already written are left where they are. */
    fun clear(ctx: Context) {
        tree?.let {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            try {
                ctx.contentResolver.releasePersistableUriPermission(it, flags)
            } catch (_: Exception) {
            }
        }
        Prefs.logFolder = null
        Log.i(TAG, "storage folder cleared")
    }

    /**
     * Append the lines that have appeared since the last mirror. Runs on the
     * mirror thread; a call while one is running is dropped.
     */
    fun mirror(ctx: Context) {
        if (!busy.compareAndSet(false, true)) return
        exec.execute {
            try {
                synchronized(lock) { append(ctx) }
            } catch (e: Exception) {
                Log.w(TAG, "log mirror failed: ${e.message}")
            } finally {
                busy.set(false)
            }
        }
    }

    /** The same, but blocking: used on the way out, when there is no "next time". */
    fun mirrorNow(ctx: Context) {
        try {
            synchronized(lock) { append(ctx) }
        } catch (e: Exception) {
            Log.w(TAG, "log mirror failed: ${e.message}")
        }
    }

    /**
     * Written by the crash handler on the thread that is dying, before the
     * process goes: the stack trace plus the log up to that moment. Without a
     * chosen folder (or when it cannot be written) it goes to the app's own
     * directory in shared storage, where at least a cable can reach it.
     */
    fun crashReport(ctx: Context, thread: Thread, t: Throwable) {
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val sw = StringWriter()
        sw.append("andWebCam ${BuildConfig.VERSION_NAME} (${BuildConfig.BUILD_ID}) crashed at ")
            .append(now).append('\n')
        sw.append("thread: ").append(thread.name).append('\n')
        t.printStackTrace(PrintWriter(sw))
        sw.append('\n').append("--- log up to the crash ---\n")
        Logs.allLines().takeLast(300).forEach { sw.append(it).append('\n') }

        val name = "crash-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}.txt"
        if (!writeCrash(ctx, name, sw.toString())) {
            // Fixed name on purpose: outside the chosen folder only the newest
            // crash is worth keeping, and it overwrites itself each time.
            writeCrash(ctx, "andwebcam-crash.txt", sw.toString())
        }
    }

    // ---- the actual file work --------------------------------------------
    // The document navigation itself lives in Saf, shared with the recorder.

    /** Append [newLinesSinceLast] to the file, rotating it when it fills up. */
    private fun append(ctx: Context) {
        val t = tree ?: return
        val cr = ctx.contentResolver
        val lines = Logs.allLines()
        if (lines.isEmpty()) return

        val marker = Prefs.lastMirrorLine
        val newLines: List<String> = if (marker == null) {
            lines   // first copy: take everything, so the file starts complete
        } else {
            val i = lines.indexOf(marker)
            if (i < 0) {
                Log.w(TAG, "the log buffer wrapped; the mirrored log may repeat")
                lines
            } else {
                lines.subList(i + 1, lines.size)
            }
        }
        if (newLines.isEmpty()) return

        val dir = Saf.ensureDir(cr, t, SUBDIR) ?: return
        migrateOld(cr, t, dir)
        // The mime type must be */* and not text/plain: the provider insists
        // that a created file's extension matches its mime type, and would
        // otherwise rename .log to .log.txt. */* keeps the name intact.
        if (Saf.findDoc(cr, t, dir, LOG_NAME) == null) Saf.createDoc(cr, dir, LOG_NAME, "*/*")
        if (Saf.findDoc(cr, t, dir, LOG_PREV) == null) Saf.createDoc(cr, dir, LOG_PREV, "*/*")
        val logDoc = Saf.findDoc(cr, t, dir, LOG_NAME)
        val prevDoc = Saf.findDoc(cr, t, dir, LOG_PREV)
        if (logDoc == null || prevDoc == null) return

        val newBytes = (newLines.joinToString("\n") + "\n").toByteArray(Charsets.UTF_8)
        val old = readAll(cr, logDoc) ?: ByteArray(0)
        val full = old + newBytes
        val toWrite = if (full.size > MAX_BYTES) {
            // The file is full: the current contents become the old one, and
            // the file keeps the newest part of the combined text.
            writeAll(cr, prevDoc, old)
            full.copyOfRange(full.size - MAX_BYTES, full.size)
        } else {
            full
        }
        // Only advance the remembered position once the bytes are down: a
        // failed write must be retried on the next mirror, not skipped.
        if (writeAll(cr, logDoc, toWrite)) {
            Prefs.lastMirrorLine = lines.last()
            Log.i(TAG, "mirrored ${newLines.size} lines to ${describe(ctx) ?: "the log folder"}")
        }
    }

    /**
     * Builds before the rename kept the same files in an "andwebcam"
     * subfolder; carry them across once, so the history follows the log into
     * its new place. Skipped as soon as the new log exists.
     */
    private fun migrateOld(cr: ContentResolver, t: Uri, dir: Uri) {
        val oldDir = Saf.ensureDir(cr, t, OLD_SUBDIR) ?: return
        for (name in arrayOf(LOG_NAME, LOG_PREV)) {
            if (Saf.findDoc(cr, t, dir, name) != null) continue
            val src = Saf.findDoc(cr, t, oldDir, name) ?: continue
            val data = readAll(cr, src) ?: continue
            val dst = Saf.findDoc(cr, t, dir, name) ?: Saf.createDoc(cr, dir, name, "*/*") ?: continue
            writeAll(cr, dst, data)
        }
    }

    private fun writeCrash(ctx: Context, name: String, text: String): Boolean {
        val data = text.toByteArray(Charsets.UTF_8)
        val cr = ctx.contentResolver
        tree?.let { t ->
            val dir = Saf.ensureDir(cr, t, SUBDIR)
            if (dir != null) {
                val doc = Saf.findDoc(cr, t, dir, name) ?: Saf.createDoc(cr, dir, name, "text/plain")
                if (doc != null && writeAll(cr, doc, data)) {
                    pruneCrashFiles(cr, t, dir)
                    return true
                }
            }
        }
        return try {
            val dir = ctx.getExternalFilesDir(null) ?: ctx.filesDir
            File(dir, name).writeBytes(data)
            true
        } catch (e: Exception) {
            Log.w(TAG, "cannot write the crash report: ${e.message}")
            false
        }
    }

    /** A crashed-and-restarted build writes one file per death; keep a handful. */
    private fun pruneCrashFiles(cr: ContentResolver, t: Uri, dir: Uri) {
        val names = Saf.listChildren(cr, t, dir)
            .map { it.name }
            .filter { it.startsWith("crash-") }
            .sorted()
            .toMutableList()
        while (names.size > 5) {
            val n = names.removeAt(0)
            Saf.findDoc(cr, t, dir, n)?.let { d ->
                try { DocumentsContract.deleteDocument(cr, d) } catch (_: Exception) {}
            }
        }
    }

    private fun readAll(cr: ContentResolver, doc: Uri): ByteArray? =
        try {
            cr.openInputStream(doc)?.use { it.readBytes() }
        } catch (e: Exception) {
            Log.w(TAG, "cannot read the mirrored log: ${e.message}")
            null
        }

    private fun writeAll(cr: ContentResolver, doc: Uri, data: ByteArray): Boolean =
        try {
            // openOutputStream() on a document truncates, which is the overwrite
            // this needs.
            cr.openOutputStream(doc)?.use { it.write(data) }
            true
        } catch (e: Exception) {
            Log.w(TAG, "cannot write the log file: ${e.message}")
            false
        }
}
