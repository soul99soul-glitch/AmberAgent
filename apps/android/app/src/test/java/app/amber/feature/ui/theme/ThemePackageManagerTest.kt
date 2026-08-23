package app.amber.feature.ui.theme

import android.app.Application
import android.content.Context
import androidx.room.Room
import app.amber.agent.data.db.AppDatabase
import app.amber.core.settings.ChatFontFamily
import app.amber.core.settings.DisplaySetting
import app.amber.core.settings.Settings
import app.amber.core.utils.JsonInstant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.IOException

/**
 * P8-09 验收测试（主题库管理层，Room + 内存设置存储）：
 * - prepare → apply → 导出 round-trip；
 * - 内置主题不可被导入包覆盖（builtin: id 拒绝入库）；
 * - 应用失败回退到上一个可用主题；
 * - remove 从主题库移除；
 * - 未知 token 保留在库中原始 JSON。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ThemePackageManagerTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun exportJson(displaySetting: DisplaySetting): String {
        val pkg = ThemePackageExporter.export(displaySetting)
        return JsonInstant.encodeToString(ThemePackage.serializer(), pkg)
    }

    private fun customDisplay(): DisplaySetting = DisplaySetting(
        amberBaseFamily = "SAGE",
        accentColor = "#4F86D6",
        chatFontFamily = ChatFontFamily.SERIF,
        fontSizeRatio = 1.25f,
        showUserAvatar = false,
        showAssistantBubble = true,
    )

    @Test
    fun `prepare apply export round-trips the current custom theme`() = runTest {
        val initial = customDisplay()
        val store = FakeThemeSettingsStore(Settings(displaySetting = initial))
        val manager = ThemePackageManager(dao = db.themePackageDao(), settingsStore = store)
        val exportedJson = exportJson(initial)

        val imported = manager.importPackage(exportedJson) as ThemePackageImportResult.Preview
        assertEquals(ThemePackageExporter.EXPORTED_PACKAGE_ID, imported.pkg.id)
        assertNull(db.themePackageDao().getById(imported.pkg.id))
        assertEquals(initial, store.current.displaySetting)

        assertEquals(ThemePackageApplyResult.Applied, manager.applyPrepared(imported.pkg.id, imported.candidateDigest))

        val after = store.current.displaySetting
        assertEquals("SAGE", after.amberBaseFamily)
        assertEquals("#4F86D6", after.accentColor)
        assertEquals(ChatFontFamily.SERIF, after.chatFontFamily)
        assertEquals(1.25f, after.fontSizeRatio)
        assertFalse(after.showUserAvatar)
        assertTrue(after.showAssistantBubble)
        assertEquals(imported.pkg.id, after.appliedThemePackageId)

        // round-trip：再导出 == 原导出包
        assertEquals(exportJson(initial), exportJson(after))
    }

    @Test
    fun `built-in themes cannot be overridden by an imported package`() = runTest {
        val store = FakeThemeSettingsStore(Settings(displaySetting = DisplaySetting()))
        val manager = ThemePackageManager(dao = db.themePackageDao(), settingsStore = store)
        val json = JsonInstant.encodeToString(
            ThemePackage.serializer(),
            ThemePackage(schemaVersion = 1, id = "builtin:WARM", name = "伪内置", colors = mapOf("baseFamily" to "SAGE")),
        )

        val result = manager.importPackage(json)

        assertTrue(result is ThemePackageImportResult.Rejected)
        assertNull(db.themePackageDao().getById("builtin:WARM"))
    }

    @Test
    fun `apply failure rolls back to the previous working theme`() = runTest {
        val initial = Settings(displaySetting = DisplaySetting(amberBaseFamily = "WARM"))
        val store = FailingThemeSettingsStore(initial)
        val manager = ThemePackageManager(dao = db.themePackageDao(), settingsStore = store)
        val exportedJson = exportJson(customDisplay())
        val imported = manager.importPackage(exportedJson) as ThemePackageImportResult.Preview

        assertNull(db.themePackageDao().getById(imported.pkg.id))
        val result = manager.applyPrepared(imported.pkg.id, imported.candidateDigest)

        assertEquals(ThemePackageApplyResult.Reverted, result)
        // 回退后仍为上一个可用主题
        assertEquals(initial, store.current)
        assertTrue(store.restoreAttempted)
    }

    @Test
    fun `remove deletes the package from the library`() = runTest {
        val store = FakeThemeSettingsStore(Settings(displaySetting = DisplaySetting()))
        val manager = ThemePackageManager(dao = db.themePackageDao(), settingsStore = store)
        val imported = manager.importPackage(exportJson(customDisplay())) as ThemePackageImportResult.Preview
        assertNull(db.themePackageDao().getById(imported.pkg.id))
        assertEquals(ThemePackageApplyResult.Applied, manager.applyPrepared(imported.pkg.id, imported.candidateDigest))
        assertNotNull(db.themePackageDao().getById(imported.pkg.id))

        assertTrue(manager.remove(imported.pkg.id))

        assertNull(db.themePackageDao().getById(imported.pkg.id))
    }

    @Test
    fun `unknown tokens are preserved verbatim in the stored package json`() = runTest {
        val store = FakeThemeSettingsStore(Settings(displaySetting = DisplaySetting()))
        val manager = ThemePackageManager(dao = db.themePackageDao(), settingsStore = store)
        val json = JsonInstant.encodeToString(
            ThemePackage.serializer(),
            ThemePackage(
                schemaVersion = 1,
                id = "pkg-with-unknown",
                name = "带未知 token 的包",
                colors = mapOf("baseFamily" to "WARM", "futureToken" to "#ABCDEF"),
            ),
        )

        val imported = manager.importPackage(json) as ThemePackageImportResult.Preview

        assertEquals(listOf("颜色:futureToken"), imported.unknownTokens)
        assertNull(db.themePackageDao().getById(imported.pkg.id))
        assertEquals(ThemePackageApplyResult.Applied, manager.applyPrepared(imported.pkg.id, imported.candidateDigest))
        // 库中原始 JSON 原样保留未知 token
        val stored = db.themePackageDao().getById("pkg-with-unknown")!!
        assertTrue(stored.json.contains("futureToken"))
        assertTrue(stored.json.contains("#ABCDEF"))
    }

    @Test
    fun `discard try-on leaves settings and library unchanged`() = runTest {
        val initial = Settings(displaySetting = DisplaySetting(amberBaseFamily = "WARM"))
        val store = FakeThemeSettingsStore(initial)
        val manager = ThemePackageManager(dao = db.themePackageDao(), settingsStore = store)

        val imported = manager.importPackage(exportJson(customDisplay())) as ThemePackageImportResult.Preview

        assertEquals(initial, store.current)
        assertEquals(imported.candidate, manager.tryOn.value?.candidate)
        assertNull(db.themePackageDao().getById(imported.pkg.id))

        assertTrue(manager.discardTryOn(imported.pkg.id, imported.candidateDigest))

        assertNull(manager.tryOn.value)
        assertEquals(initial, store.current)
        assertNull(db.themePackageDao().getById(imported.pkg.id))
    }

    @Test
    fun `prepared candidate requires matching package id and digest`() = runTest {
        val store = FakeThemeSettingsStore(Settings(displaySetting = DisplaySetting()))
        val manager = ThemePackageManager(dao = db.themePackageDao(), settingsStore = store)
        val imported = manager.importPackage(exportJson(customDisplay())) as ThemePackageImportResult.Preview

        assertEquals(
            ThemePackageApplyResult.NotPrepared,
            manager.applyPrepared(imported.pkg.id, "wrong-digest"),
        )
        assertTrue(manager.tryOn.value != null)
        assertFalse(manager.discardTryOn(imported.pkg.id, "wrong-digest"))
        assertTrue(manager.tryOn.value != null)
    }

    private class FakeThemeSettingsStore(initial: Settings) : ThemeSettingsStore {
        val flow = MutableStateFlow(initial)

        var current: Settings
            get() = flow.value
            set(value) {
                flow.value = value
            }

        override val settingsFlow: Flow<Settings> = flow

        override suspend fun update(settings: Settings) {
            flow.value = settings
        }
    }

    private class FailingThemeSettingsStore(initial: Settings) : ThemeSettingsStore {
        val flow = MutableStateFlow(initial)
        private var updateCount = 0

        val restoreAttempted: Boolean
            get() = updateCount >= 2

        var current: Settings
            get() = flow.value
            set(value) {
                flow.value = value
            }

        override val settingsFlow: Flow<Settings> = flow

        override suspend fun update(settings: Settings) {
            updateCount++
            // 第一次写入（应用）失败；第二次（回退）成功
            if (updateCount == 1) throw IOException("settings write failed")
            flow.value = settings
        }
    }
}
