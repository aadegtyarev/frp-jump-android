package ru.adsrv.frpjump.files

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import java.io.File
import java.io.IOException

/** A file picked on the phone for upload: its name and size (0 when the app that has it does not say). */
data class PickedFile(val uri: Uri, val name: String, val size: Long)

/**
 * The phone's side of file transfers: the download folder the owner chose
 * with the system folder picker (a document tree kept with a persistable
 * grant), the cache that files to open or share go through, and the files
 * picked for upload.
 */
object PhoneFiles {
    private const val OPEN_DIR = "files-open"
    private const val LISTINGS_DIR = "files-listings"
    private const val FALLBACK_TYPE = "application/octet-stream"

    /** Whether the app may still write into [tree] (the grant survives restarts until the owner revokes it). */
    fun canWrite(context: Context, tree: Uri): Boolean =
        context.contentResolver.persistedUriPermissions.any { it.uri == tree && it.isWritePermission }

    /** Keeps the folder picker's grant across restarts; false when the provider does not allow that. */
    fun keep(context: Context, tree: Uri): Boolean = try {
        context.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        true
    } catch (_: SecurityException) {
        false
    }

    /** The folder's name as the picker showed it, else its last path part. */
    fun folderName(context: Context, tree: Uri): String {
        val doc = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val name = runCatching {
            context.contentResolver.query(doc, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        }.getOrNull()
        return name ?: DocumentsContract.getTreeDocumentId(tree).substringAfterLast(':').substringAfterLast('/')
    }

    /**
     * A new empty file in the download folder [tree], named [name] or, when
     * that is taken, "Keep both" (`name (1).ext`). Returns its URI and the
     * name it got.
     */
    fun createInTree(context: Context, tree: Uri, name: String): Pair<Uri, String> {
        val parentId = DocumentsContract.getTreeDocumentId(tree)
        val taken = childNames(context, tree, parentId)
        val free = keepBothName(name) { it in taken }
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, parentId)
        val doc = DocumentsContract.createDocument(context.contentResolver, parent, mimeType(free), free)
            ?: throw IOException("cannot create $free")
        // The provider may still have changed the name (characters its file system refuses).
        return doc to (displayName(context, doc) ?: free)
    }

    /** Removes a half-written download. */
    fun delete(context: Context, doc: Uri) {
        runCatching { DocumentsContract.deleteDocument(context.contentResolver, doc) }
    }

    /** A fresh file in the app's cache for a file to open or share, in a folder of its own so names never clash. */
    fun openCacheFile(context: Context, name: String): File {
        val dir = File(File(context.cacheDir, OPEN_DIR), System.nanoTime().toString())
        if (!dir.mkdirs() && !dir.isDirectory) throw IOException("cannot make $dir")
        return File(dir, name.replace('/', '_'))
    }

    /** Files handed to other apps more than [maxAgeMs] ago: those apps have long read them. */
    fun pruneOpenCache(context: Context, maxAgeMs: Long, now: Long = System.currentTimeMillis()) {
        File(context.cacheDir, OPEN_DIR).listFiles()?.filter { now - it.lastModified() > maxAgeMs }?.forEach { it.deleteRecursively() }
    }

    /** Where the offline listings of [profile] are kept ([ListingCache]). */
    fun listingsDir(context: Context, profile: String): File = File(File(context.cacheDir, LISTINGS_DIR), profile)

    /** A picked document's name and size, as its provider gives them. */
    fun picked(context: Context, uri: Uri): PickedFile {
        var name: String? = null
        var size = 0L
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use {
                if (it.moveToFirst()) {
                    name = it.getString(0)
                    if (!it.isNull(1)) size = it.getLong(1)
                }
            }
        }
        val clean = name?.substringAfterLast('/')?.takeIf { it.isNotBlank() && it != "." && it != ".." }
        return PickedFile(uri, clean ?: uri.lastPathSegment?.substringAfterLast('/') ?: "upload", size)
    }

    /** The MIME type by [name]'s extension, for other apps to know what they get. */
    fun mimeType(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: FALLBACK_TYPE
    }

    private fun childNames(context: Context, tree: Uri, parentId: String): Set<String> {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        return context.contentResolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
            buildSet { while (c.moveToNext()) c.getString(0)?.let(::add) }
        }.orEmpty()
    }

    private fun displayName(context: Context, doc: Uri): String? = runCatching {
        context.contentResolver.query(doc, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }.getOrNull()
}
