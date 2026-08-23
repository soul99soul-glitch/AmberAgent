package app.amber.core.sync.provider

import android.app.Application
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * P7-01 验收：SAF 本地文件夹 Provider 的持久 URI permission 存储。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PersistedFolderStoreTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
    }

    @Test
    fun `save then read round trips across store instances`() {
        val uri = "content://com.android.externalstorage.documents/tree/primary%3ADocuments%2FAmber"
        PersistedFolderStore(context).save(uri, "Amber 备份")

        // 重启后（新 store 实例）仍能读回。
        val folder = PersistedFolderStore(context).read()
        assertEquals(uri, folder?.uri)
        assertEquals("Amber 备份", folder?.displayName)
    }

    @Test
    fun `prefs cleared externally makes read return null`() {
        PersistedFolderStore(context).save("content://tree/one", "第一个文件夹")
        // 外部清除预置数据（等价于应用数据被清除/损坏）。
        context.getSharedPreferences("amber_sync_local_folder", Context.MODE_PRIVATE)
            .edit().clear().apply()

        assertNull(PersistedFolderStore(context).read())
    }
}
