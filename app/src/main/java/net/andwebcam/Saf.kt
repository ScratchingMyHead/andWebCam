package net.andwebcam

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.StatFs
import android.provider.DocumentsContract
import android.util.Log

/**
 * Navigation over the SAF tree the user has chosen, shared by everything
 * that writes into it: the on-disk log (LogFolder) and video recording.
 *
 * No AndroidX here, so the DocumentsContract plumbing is written out by
 * hand: a tree is a directory document, its children are listed through a
 * children-collection uri, and everything is addressed by (tree, parent
 * document, name).
 */
object Saf {
    private const val TAG = "andWebCam"

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

    /**
     * The document of the subdirectory [name] under the tree root, created
     * if it is not there yet. The create call wants a document uri, and the
     * bare tree uri is not one: the tree root's own document is the parent
     * to create under.
     */
    fun ensureDir(cr: ContentResolver, t: Uri, name: String): Uri? {
        findDoc(cr, t, t, name)?.let { return it }
        val rootDoc = DocumentsContract.buildDocumentUriUsingTree(
            t, DocumentsContract.getTreeDocumentId(t)
        )
        return createDoc(cr, rootDoc, name, DocumentsContract.Document.MIME_TYPE_DIR)
    }

    /**
     * The document named [name] among the children of [parent], or null.
     * [parent] is the tree uri for the root or a document uri below it; a
     * document uri queried directly yields the document itself, its
     * children live behind the children-collection uri.
     */
    fun findDoc(cr: ContentResolver, t: Uri, parent: Uri, name: String): Uri? {
        val proj = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        )
        try {
            cr.query(childUri(t, parent), proj, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    if (c.getString(1) == name) {
                        return DocumentsContract.buildDocumentUriUsingTree(t, c.getString(0))
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "cannot list the folder: ${e.message}")
        }
        return null
    }

    fun createDoc(cr: ContentResolver, parent: Uri, name: String, mime: String): Uri? = try {
        // The platform signature takes the mime type before the display name.
        // Pass "*/*" for names whose extension does not match the mime type:
        // the provider would otherwise rename them (andwebcam.log becomes
        // andwebcam.log.txt).
        DocumentsContract.createDocument(cr, parent, mime, name)
    } catch (e: Exception) {
        Log.w(TAG, "cannot create $name: ${e.message}")
        null
    }

    /** A child of [parent]: its name, document, and what the provider reports. */
    class Child(val name: String, val doc: Uri, val size: Long, val modifiedMs: Long)

    fun listChildren(cr: ContentResolver, t: Uri, parent: Uri): List<Child> {
        val out = mutableListOf<Child>()
        // dateModified has no public DocumentsContract constant; the framework
        // providers still expose the column, and a provider without it just
        // yields 0, which nothing here depends on.
        val dateCol = "dateModified"
        try {
            cr.query(
                childUri(t, parent),
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_SIZE,
                    dateCol,
                ),
                null, null, null,
            )?.use { c ->
                val iDate = c.getColumnIndex(dateCol)
                while (c.moveToNext()) {
                    out.add(
                        Child(
                            c.getString(1),
                            DocumentsContract.buildDocumentUriUsingTree(t, c.getString(0)),
                            c.getLong(2),
                            if (iDate >= 0) c.getLong(iDate) else 0L,
                        )
                    )
                }
            }
        } catch (_: Exception) {
        }
        return out
    }

    fun deleteDoc(cr: ContentResolver, doc: Uri): Boolean = try {
        DocumentsContract.deleteDocument(cr, doc)
        true
    } catch (e: Exception) {
        Log.w(TAG, "cannot delete ${doc.lastPathSegment}: ${e.message}")
        false
    }

    /**
     * Rename [doc] to [newName] in place and return the renamed document.
     * API 29 and up have a proper call; below it, a copy under the new name
     * and a delete of the original (a copy left half-finished is removed).
     */
    fun renameDoc(
        cr: ContentResolver,
        t: Uri,
        parent: Uri,
        doc: Uri,
        newName: String,
    ): Uri? {
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                return DocumentsContract.renameDocument(cr, doc, newName)
            } catch (e: Exception) {
                Log.w(TAG, "cannot rename to $newName: ${e.message}")
                return null
            }
        }
        val target = createDoc(cr, parent, newName, "*/*") ?: return null
        val inStream = try {
            cr.openInputStream(doc)
        } catch (e: Exception) {
            deleteDoc(cr, target)
            return null
        } ?: run {
            deleteDoc(cr, target)
            return null
        }
        try {
            inStream.use { i ->
                cr.openOutputStream(target)?.use { o ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = i.read(buf)
                        if (n < 0) break
                        o.write(buf, 0, n)
                    }
                } ?: throw java.io.IOException("cannot open the new name")
            }
            DocumentsContract.deleteDocument(cr, doc)
            return target
        } catch (e: Exception) {
            Log.w(TAG, "cannot rename to $newName: ${e.message}")
            deleteDoc(cr, target)
            return null
        }
    }

    /**
     * The free space of the volume the tree sits on, or null when it cannot
     * be determined. SAF has no "free space of this tree" API, so the number
     * is the volume's: exact for a folder on internal storage, and unknown
     * (rather than wrong) for one on removable storage.
     */
    fun freeBytes(t: Uri): Long? {
        val id = DocumentsContract.getTreeDocumentId(t)
        val path = if (id.startsWith("primary")) "/storage/emulated/0" else return null
        return try {
            val sf = StatFs(path)
            sf.availableBlocks.toLong() * sf.blockSizeLong
        } catch (_: Exception) {
            null
        }
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
}
