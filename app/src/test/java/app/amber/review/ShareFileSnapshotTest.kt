package app.amber.review

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import app.amber.feature.workspace.WorkspaceManager
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.io.File
import kotlin.coroutines.Continuation

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ShareFileSnapshotTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private lateinit var context: Context
    private lateinit var manager: WorkspaceManager
    private lateinit var sourceRoot: File

    @Before fun setUp() = runBlocking {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("amberagent_workspace", Context.MODE_PRIVATE).edit().clear().commit()
        sourceRoot = temporaryFolder.newFolder("workspace")
        val provider = FileTreeProvider(sourceRoot)
        provider.attachInfo(context, ProviderInfo().apply { authority = AUTHORITY })
        ShadowContentResolver.registerProviderInternal(AUTHORITY, provider)
        manager = WorkspaceManager(context)
        manager.setWorkspace(Uri.parse("content://$AUTHORITY/tree/root"))
    }

    @After fun tearDown() {
        context.getSharedPreferences("amberagent_workspace", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun equalBasenamesProduceIndependentShareSnapshots() = snapshots("left/report.pdf", "right/report.pdf")
    @Test fun chineseNamesProduceIndependentShareSnapshots() = snapshots("中文.pdf", "报道.pdf")
    @Test fun sharingTheSamePathAgainDoesNotModifyTheEarlierSnapshot() = snapshots("report.pdf", "report.pdf")

    private fun snapshots(firstPath: String, secondPath: String) = runBlocking {
        writeSource(firstPath, "Content A")
        val first = cacheForSharing(firstPath)
        writeSource(secondPath, "Content B")
        val second = cacheForSharing(secondPath)

        assertNotEquals("Each grant must identify its own immutable file snapshot", first.canonicalPath, second.canonicalPath)
        assertEquals("Content A", first.readText())
        assertEquals("Content B", second.readText())
    }

    private fun writeSource(path: String, content: String) {
        sourceRoot.resolve(path).apply { parentFile!!.mkdirs(); writeText(content) }
    }

    private suspend fun cacheForSharing(path: String): File {
        val method = Class.forName("app.amber.feature.tools.ShareAccessToolsKt").getDeclaredMethod(
            "cacheWorkspaceFileForSharing", Context::class.java, WorkspaceManager::class.java,
            String::class.java, Continuation::class.java,
        )
        return ReflectionFixture.invokeSuspend(null, method, context, manager, path) as File
    }

    /** Minimal read-only SAF provider; exercises WorkspaceManager's actual capped reader. */
    private class FileTreeProvider(private val root: File) : ContentProvider() {
        override fun onCreate() = true
        override fun getType(uri: Uri): String = mime(file(uri))
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
            val columns = projection ?: arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_FLAGS, DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            )
            val parent = file(uri)
            val files = if (uri.lastPathSegment == "children") parent.listFiles().orEmpty().toList() else listOf(parent)
            return MatrixCursor(columns).apply {
                files.forEach { entry ->
                    addRow(columns.map<String, Any?> { column -> when (column) {
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID -> "root/" + entry.relativeTo(root).path
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME -> entry.name
                        DocumentsContract.Document.COLUMN_MIME_TYPE -> mime(entry)
                        DocumentsContract.Document.COLUMN_SIZE -> entry.length()
                        DocumentsContract.Document.COLUMN_FLAGS -> 0
                        DocumentsContract.Document.COLUMN_LAST_MODIFIED -> entry.lastModified()
                        else -> null
                    } }.toTypedArray())
                }
            }
        }
        private fun file(uri: Uri): File {
            val id = runCatching { DocumentsContract.getDocumentId(uri) }.getOrElse { DocumentsContract.getTreeDocumentId(uri) }
            return root.resolve(id.removePrefix("root").trimStart('/'))
        }
        private fun mime(file: File) = if (file.isDirectory) DocumentsContract.Document.MIME_TYPE_DIR else "application/pdf"
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor = ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.MODE_READ_ONLY)
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
    }

    companion object { private const val AUTHORITY = "review.share.workspace" }
}
