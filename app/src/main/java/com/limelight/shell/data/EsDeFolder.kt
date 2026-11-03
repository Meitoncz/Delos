package com.limelight.shell.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.preference.PreferenceManager
import java.io.IOException

/** The ES-DE folder chosen in Android's folder picker: its tree URI and the name shown in Settings. */
data class EsDeFolderChoice(val tree: Uri, val name: String)

/** The remembered folder cannot be used any more (access revoked, folder deleted or its storage gone). */
class EsDeFolderUnavailable(cause: Throwable? = null) : IOException("The ES-DE folder is not available", cause)

/**
 * The folder Delos writes its ES-DE shortcut files (`.art`, see docs/ES-DE.md) to. It is the same for every
 * profile on this device: the tree URI the user picked and Android's persisted access to it, kept in the
 * app's default preferences (like the SteamGridDB key).
 */
class EsDeFolder(context: Context) {
    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver
    private val prefs = PreferenceManager.getDefaultSharedPreferences(appContext)

    /** The chosen folder, or null while none is. Snapshot state, so Settings follows changes. */
    var choice by mutableStateOf(readChoice())
        private set

    private fun readChoice(): EsDeFolderChoice? {
        val tree = prefs.getString(KEY_TREE, null)?.let(Uri::parse) ?: return null
        return EsDeFolderChoice(tree, prefs.getString(KEY_NAME, null).orEmpty().ifEmpty { fallbackName(tree) })
    }

    /** The folder while Android still lets Delos write to it, else null (the user has to choose it again). */
    fun usable(): EsDeFolderChoice? {
        val current = choice ?: return null
        val granted = resolver.persistedUriPermissions.any { it.uri == current.tree && it.isReadPermission && it.isWritePermission }
        return current.takeIf { granted }
    }

    /** Remembers [tree] (picked just now) and keeps Android's access to it. False when the access cannot be kept. */
    fun choose(tree: Uri): Boolean {
        try {
            resolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        } catch (e: SecurityException) {
            return false
        }
        val previous = choice
        if (previous != null && previous.tree != tree) release(previous.tree)
        val name = displayName(documentOf(tree)) ?: fallbackName(tree)
        prefs.edit().putString(KEY_TREE, tree.toString()).putString(KEY_NAME, name).apply()
        choice = EsDeFolderChoice(tree, name)
        return true
    }

    /** Forgets the folder and gives its access back to Android. */
    fun forget() {
        choice?.let { release(it.tree) }
        prefs.edit().remove(KEY_TREE).remove(KEY_NAME).apply()
        choice = null
    }

    private fun release(tree: Uri) {
        try {
            resolver.releasePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        } catch (e: SecurityException) {
            // Not held (any more): nothing to give back
        }
    }

    /**
     * Writes [content] to [fileName] in [folder] and returns the name the file got. A file of that name is
     * overwritten (the Storage Access Framework would otherwise create "name (1).art"). Throws
     * [EsDeFolderUnavailable] when the folder cannot be used, [IOException] when the file cannot be written.
     * Blocking: call it off the main thread.
     */
    @Throws(IOException::class)
    fun write(folder: EsDeFolderChoice, fileName: String, content: String): String {
        val tree = folder.tree
        val parent = documentOf(tree)
        val folderOk = try {
            resolver.query(parent, arrayOf(DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null)?.use {
                it.moveToFirst() && it.getString(0) == DocumentsContract.Document.MIME_TYPE_DIR
            } ?: false
        } catch (e: Exception) {
            throw EsDeFolderUnavailable(e)
        }
        if (!folderOk) throw EsDeFolderUnavailable()

        val existing = findChild(tree, parent, fileName)
        val file = existing
            ?: DocumentsContract.createDocument(resolver, parent, MIME_TYPE, fileName)
            ?: throw IOException("The file could not be created")
        // "wt": a shorter content must not leave the end of the longer file behind
        val output = resolver.openOutputStream(file, "wt") ?: throw IOException("The file could not be opened")
        output.use { it.write(content.toByteArray(Charsets.UTF_8)) }
        return displayName(file) ?: fileName
    }

    private fun documentOf(tree: Uri): Uri = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))

    private fun displayName(document: Uri): String? = try {
        resolver.query(document, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    } catch (e: Exception) {
        null
    }

    private fun findChild(tree: Uri, parent: Uri, name: String): Uri? {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(parent))
        val columns = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        resolver.query(children, columns, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                if (cursor.getString(1) == name) return DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(0))
            }
        }
        return null
    }

    private companion object {
        const val KEY_TREE = "esde_folder_tree"
        const val KEY_NAME = "esde_folder_name"
        const val MIME_TYPE = "application/octet-stream"

        /** The last part of the folder's document id ("primary:ROMs/delos" → "delos"). */
        fun fallbackName(tree: Uri): String =
            DocumentsContract.getTreeDocumentId(tree).substringAfterLast('/').substringAfterLast(':').ifEmpty { "Folder" }
    }
}
