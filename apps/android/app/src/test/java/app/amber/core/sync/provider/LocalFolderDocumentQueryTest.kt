package app.amber.core.sync.provider

import android.app.Application
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * P7-01：SAF document tree 的 Uri 构造纯函数（DocumentsContract 静态方法只做
 * Uri 运算，可脱离 ContentResolver 直接验证）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class LocalFolderDocumentQueryTest {

    private val treeUri = Uri.parse(
        "content://com.android.externalstorage.documents/tree/primary%3ADocuments%2FAmber"
    )

    @Test
    fun `child documents uri points at tree document id`() {
        val children = treeChildDocumentsUri(treeUri)
        assertEquals(
            "content://com.android.externalstorage.documents/tree/primary%3ADocuments%2FAmber/document/primary%3ADocuments%2FAmber/children",
            children.toString(),
        )
    }

    @Test
    fun `document uri resolves under the tree authority`() {
        val document = treeDocumentUri(treeUri, "primary:Documents/Amber/snap-1.amberbackup")
        assertEquals(
            "content://com.android.externalstorage.documents/tree/primary%3ADocuments%2FAmber/document/primary%3ADocuments%2FAmber%2Fsnap-1.amberbackup",
            document.toString(),
        )
    }

    @Test
    fun `round trip keeps the same authority for both helpers`() {
        assertEquals(treeUri.authority, treeChildDocumentsUri(treeUri).authority)
        assertEquals(treeUri.authority, treeDocumentUri(treeUri, "x").authority)
    }
}
