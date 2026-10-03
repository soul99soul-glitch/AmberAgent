package app.amber.agent.data.sync

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import java.io.File

/** In-memory SAF graph with disk-backed streams; only backup tests use this provider. */
internal class BackupArtifactTreeProvider(private val backingRoot: File) : ContentProvider() {
    private data class Doc(val id: String, val parent: String?, val name: String, val mime: String)
    private val docs = linkedMapOf("root" to Doc("root", null, "Test workspace", DocumentsContract.Document.MIME_TYPE_DIR))
    private var sequence = 0
    override fun onCreate(): Boolean = true
    override fun getType(uri: Uri): String? = docs[DocumentsContract.getDocumentId(uri)]?.mime
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val columns = projection ?: arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE)
        val id = DocumentsContract.getDocumentId(uri)
        val selected = if (uri.pathSegments.lastOrNull() == "children") docs.values.filter { it.parent == id } else listOfNotNull(docs[id])
        return MatrixCursor(columns).apply {
            selected.forEach { doc -> addRow(columns.map<String, Any?> { column ->
                when (column) {
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID -> doc.id
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME -> doc.name
                    DocumentsContract.Document.COLUMN_MIME_TYPE -> doc.mime
                    DocumentsContract.Document.COLUMN_SIZE -> file(doc.id).takeIf { it.isFile }?.length() ?: 0L
                    DocumentsContract.Document.COLUMN_FLAGS -> DocumentsContract.Document.FLAG_SUPPORTS_WRITE or DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE
                    else -> 0L
                }
            }.toTypedArray()) }
        }
    }
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method != "android:createDocument" || extras == null) return null
        val parent = extras.getParcelable<Uri>("uri") ?: return null
        val id = "doc-${++sequence}"
        docs[id] = Doc(id, DocumentsContract.getDocumentId(parent), extras.getString(DocumentsContract.Document.COLUMN_DISPLAY_NAME).orEmpty(), extras.getString(DocumentsContract.Document.COLUMN_MIME_TYPE).orEmpty())
        return Bundle().apply { putParcelable("uri", DocumentsContract.buildDocumentUriUsingTree(parent, id)) }
    }
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val id = DocumentsContract.getDocumentId(uri)
        require(docs[id] != null)
        val target = file(id).apply { parentFile!!.mkdirs(); if (!exists()) createNewFile() }
        return ParcelFileDescriptor.open(target, ParcelFileDescriptor.parseMode(mode))
    }
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
    private fun file(id: String) = File(backingRoot, id)
}
