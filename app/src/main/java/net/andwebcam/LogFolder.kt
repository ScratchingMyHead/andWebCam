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
 * The on-disk copy of the log, in a folder the user has chosen.
 *
 * /api/log only works while a live process serves it: an update that crashes
 * before the server starts leaves nothing to download. The copy written here
 * keeps growing while the service runs, is completed when the service stops,
 * and the crash handler adds the stack trace when the process dies any other
 * way - so even a bad update leaves a readable log in a folder any file
 * browser (or FTP server) on the phone can reach.
 *
 * The folder is granted once through the system document picker; the grant
 * survives reboots and the same grant is what video recording will use.
 */
object LogFolder {
    private const val TAG = "andWebCam"

    /** The app writes into its own subdirectory, so the chosen folder stays tidy. */
    private const val SUBDIR = "andwebcam"
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
        get() = Prefs.logFolder?.let { Uri.parse(it) }

    /** The chosen folder's name, for the page and the phone's screen. */
    fun describe(ctx: Context): String? {
        val t = tree ?: return null
        try {
            ctx.contentResolver.query(
                t, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null,
            )?.use { c ->
                if (c.moveToFirst()) return c.getString(0)
            }
        } catch (_: Exception) {
        }
        // A tree id looks like "primarystore:/Internal/Download".
        val path = DocumentsContract.getTreeDocumentId(t).substringAfter(':')
        return path.trim('/').ifBlank { null }
    }

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
        Log.i(TAG, "log folder set: ${describe(ctx) ?: uri}")
        mirror(ctx)
    }

    /** Stop mirroring. The files already written are left where they are. */
    fun clear(ctx: Context) {
        tree?.let {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            try {
                ctx.contentResolver.releasePersistableUriPermission(it, flags)
            } catch (_: Exception) {
            }
        }
        Prefs.logFolder = null
        Log.i(TAG, "log folder cleared")
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

        val dir = ensureDir(cr, t) ?: return
        // The mime type must be */* and not text/plain: the provider insists
        // that a created file's extension matches its mime type, and would
        // otherwise rename .log to .log.txt. */* keeps the name intact.
        ensureDoc(cr, t, dir, LOG_NAME, "*/*")
        ensureDoc(cr, t, dir, LOG_PREV, "*/*")
        val logDoc = findDoc(cr, t, dir, LOG_NAME)
        val prevDoc = findDoc(cr, t, dir, LOG_PREV)
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

    private fun writeCrash(ctx: Context, name: String, text: String): Boolean {
        val data = text.toByteArray(Charsets.UTF_8)
        val cr = ctx.contentResolver
        tree?.let { t ->
            val dir = ensureDir(cr, t)
            if (dir != null) {
                val doc = findDoc(cr, t, dir, name) ?: createDoc(cr, dir, name, "text/plain")
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
        val names = listNames(cr, t, dir).filter { it.startsWith("crash-") }.sorted().toMutableList()
        while (names.size > 5) {
            val n = names.removeAt(0)
            findDoc(cr, t, dir, n)?.let { d ->
                try { DocumentsContract.deleteDocument(cr, d) } catch (_: Exception) {}
            }
        }
    }

    // ---- document navigation ---------------------------------------------
    // No AndroidX here, so the DocumentsContract plumbing is written out by
    // hand: a tree is a directory document, and files are found by name among
    // its children.

    private fun ensureDir(cr: ContentResolver, t: Uri): Uri? {
        val treeId = DocumentsContract.getTreeDocumentId(t)
        findDoc(cr, t, t, SUBDIR)?.let { return it }
        // The create call wants a document uri, and the bare tree uri is not
        // one: the tree root's own document is the parent to create under.
        val rootDoc = DocumentsContract.buildDocumentUriUsingTree(t, treeId)
        return createDoc(cr, rootDoc, SUBDIR, DocumentsContract.Document.MIME_TYPE_DIR)
    }

    private fun findDoc(cr: ContentResolver, t: Uri, parent: Uri, name: String): Uri? {
        // A document uri queried directly yields the document itself; its
        // children are listed by the children-collection uri.
        val children = childUri(t, parent)
        val proj = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        )
        try {
            cr.query(children, proj, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    if (c.getString(1) == name) {
                        return DocumentsContract.buildDocumentUriUsingTree(t, c.getString(0))
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "cannot list the log folder: ${e.message}")
        }
        return null
    }

    private fun createDoc(
        cr: ContentResolver,
        parent: Uri,
        name: String,
        mime: String,
    ): Uri? = try {
        // The platform signature takes the mime type before the display name.
        DocumentsContract.createDocument(cr, parent, mime, name)
    } catch (e: Exception) {
        Log.w(TAG, "cannot create $name: ${e.message}")
        null
    }

    private fun ensureDoc(cr: ContentResolver, t: Uri, parent: Uri, name: String, mime: String) {
        if (findDoc(cr, t, parent, name) == null) createDoc(cr, parent, name, mime)
    }

    /**
     * The children-collection uri for a tree or a document uri.
     * isTreeUri() is no use here: it also matches the 4-segment
     * tree/.../document/... form, so branch on the path shape.
     */
    private fun childUri(t: Uri, parent: Uri): Uri {
        val segs = parent.pathSegments
        val id = if (segs.size >= 4 && segs[2] == "document") segs[3]
            else DocumentsContract.getTreeDocumentId(parent)
        return DocumentsContract.buildChildDocumentsUriUsingTree(t, id)
    }

    private fun listNames(cr: ContentResolver, t: Uri, parent: Uri): List<String> {
        val names = mutableListOf<String>()
        try {
            cr.query(
                childUri(t, parent),
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null, null, null,
            )?.use { c ->
                while (c.moveToNext()) names.add(c.getString(0))
            }
        } catch (_: Exception) {
        }
        return names
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
