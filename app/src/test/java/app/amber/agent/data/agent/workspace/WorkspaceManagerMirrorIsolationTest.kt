package app.amber.agent.data.agent.workspace

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import app.amber.feature.workspace.WorkspaceManager
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.io.File
import java.io.FileNotFoundException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WorkspaceManagerMirrorIsolationTest {
    private lateinit var context: Context
    private lateinit var manager: WorkspaceManager
    private lateinit var provider: WorkspaceTreeProvider

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("amberagent_workspace", Context.MODE_PRIVATE).edit().clear().commit()
        manager = WorkspaceManager(context)
        manager.mirrorDir.deleteRecursively()

        provider = WorkspaceTreeProvider()
        provider.attachInfo(context, ProviderInfo().apply { authority = AUTHORITY })
        ShadowContentResolver.registerProviderInternal(AUTHORITY, provider)
    }

    @After
    fun tearDown() {
        context.getSharedPreferences("amberagent_workspace", Context.MODE_PRIVATE).edit().clear().commit()
        manager.mirrorDir.deleteRecursively()
    }

    @Test
    fun switchingOrClearingWorkspaceCannotReuseThePreviousMirror() = runBlocking {
        val unboundUpload = manager.mirrorDir.resolve("uploads/shared.txt").apply {
            parentFile?.mkdirs()
            writeText("shared")
        }

        manager.setWorkspace(treeUri("root-a:"))
        assertTrue("first workspace keeps uploads staged before selection", unboundUpload.exists())
        assertTrue(unboundUpload.delete())
        assertTrue(unboundUpload.parentFile?.delete() == true)

        val workspaceAFile = manager.mirrorDir.resolve("only-in-a.txt").apply { writeText("a") }
        manager.setWorkspace(treeUri("root-b:"))
        assertNotNull("switching must flush workspace A before clearing its mirror", provider.findByName("only-in-a.txt"))
        assertFalse("switching workspace must clear workspace A files", workspaceAFile.exists())
        assertTrue(manager.mirrorDir.isDirectory)

        val lease = manager.acquireTerminalMirror(refreshFromWorkspace = false)
        val secondLeaseError = runCatching {
            manager.acquireTerminalMirror(refreshFromWorkspace = false)
        }.exceptionOrNull()
        assertTrue(secondLeaseError?.message.orEmpty().contains("already using"))
        val switchError = runCatching { manager.setWorkspace(treeUri("root-a:")) }.exceptionOrNull()
        assertTrue(switchError?.message.orEmpty().contains("active terminal"))
        lease.release(syncBack = false)

        val workspaceBFile = manager.mirrorDir.resolve("only-in-b.txt").apply { writeText("b") }
        manager.clearWorkspace()
        assertNotNull("clearing must flush workspace B before clearing its mirror", provider.findByName("only-in-b.txt"))
        assertFalse("clearing workspace must clear workspace B files", workspaceBFile.exists())
        assertTrue(manager.mirrorDir.isDirectory)
    }

    private fun treeUri(rootId: String): Uri =
        Uri.parse("content://$AUTHORITY/tree/${Uri.encode(rootId)}")

    private class WorkspaceTreeProvider : ContentProvider() {
        private data class Doc(val id: String, val displayName: String, val mimeType: String)

        private val docs = LinkedHashMap<String, Doc>()
        private val backingDir = File.createTempFile("workspace-switch-", "").apply {
            delete()
            mkdirs()
        }
        private val backingFiles = mutableMapOf<String, File>()
        private var nextId = 1

        override fun onCreate(): Boolean = true

        override fun getType(uri: Uri): String = DocumentsContract.Document.MIME_TYPE_DIR

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor {
            val columns = projection ?: DEFAULT_COLUMNS
            val cursor = MatrixCursor(columns)
            if (uri.pathSegments.lastOrNull() == "children") {
                docs.values.forEach { cursor.addRow(rowFor(it, columns)) }
            } else {
                val documentId = runCatching { DocumentsContract.getDocumentId(uri) }
                    .getOrElse { DocumentsContract.getTreeDocumentId(uri) }
                val doc = docs[documentId] ?: Doc(
                    id = documentId,
                    displayName = documentId.removeSuffix(":"),
                    mimeType = DocumentsContract.Document.MIME_TYPE_DIR,
                )
                cursor.addRow(rowFor(doc, columns))
            }
            return cursor
        }

        override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
            if (method != METHOD_CREATE_DOCUMENT || extras == null) return null
            val parentUri = extras.getParcelable<Uri>(EXTRA_URI) ?: return null
            val doc = Doc(
                id = "doc-${nextId++}",
                displayName = extras.getString(DocumentsContract.Document.COLUMN_DISPLAY_NAME).orEmpty(),
                mimeType = extras.getString(DocumentsContract.Document.COLUMN_MIME_TYPE).orEmpty(),
            )
            docs[doc.id] = doc
            val uri = DocumentsContract.buildDocumentUriUsingTree(parentUri, doc.id)
            return Bundle().apply { putParcelable(EXTRA_URI, uri) }
        }

        override fun insert(uri: Uri, values: ContentValues?): Uri? = null

        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

        override fun update(
            uri: Uri,
            values: ContentValues?,
            selection: String?,
            selectionArgs: Array<out String>?,
        ): Int = 0

        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            val documentId = DocumentsContract.getDocumentId(uri)
            docs[documentId] ?: throw FileNotFoundException(documentId)
            val file = backingFiles.getOrPut(documentId) {
                backingDir.resolve("$documentId.bin").apply { createNewFile() }
            }
            return ParcelFileDescriptor.open(
                file,
                ParcelFileDescriptor.MODE_READ_WRITE or
                    ParcelFileDescriptor.MODE_CREATE or
                    ParcelFileDescriptor.MODE_TRUNCATE,
            )
        }

        fun findByName(name: String): String? = docs.values.firstOrNull { it.displayName == name }?.id

        private fun rowFor(doc: Doc, columns: Array<out String>): Array<Any?> = columns.map { column ->
            when (column) {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID -> doc.id
                DocumentsContract.Document.COLUMN_DISPLAY_NAME -> doc.displayName
                DocumentsContract.Document.COLUMN_MIME_TYPE -> doc.mimeType
                DocumentsContract.Document.COLUMN_FLAGS -> 0
                DocumentsContract.Document.COLUMN_SIZE -> 0L
                DocumentsContract.Document.COLUMN_LAST_MODIFIED -> 0L
                else -> null
            }
        }.toTypedArray()
    }

    private companion object {
        const val AUTHORITY = "workspace.switch"
        const val METHOD_CREATE_DOCUMENT = "android:createDocument"
        const val EXTRA_URI = "uri"
        val DEFAULT_COLUMNS = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_FLAGS,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
    }
}
