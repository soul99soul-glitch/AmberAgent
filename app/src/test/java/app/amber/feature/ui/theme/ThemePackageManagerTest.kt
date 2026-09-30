package app.amber.feature.ui.theme

import android.app.Application
import android.content.Context
import androidx.room.Room
import app.amber.agent.data.db.AppDatabase
import app.amber.agent.data.db.dao.ThemePackageDAO
import app.amber.agent.data.db.entity.ThemePackageEntity
import app.amber.core.settings.ChatFontFamily
import app.amber.core.settings.DisplaySetting
import app.amber.core.settings.Settings
import app.amber.core.sync.core.SyncRestoreWriteEpoch
import app.amber.core.sync.core.SyncRestoreWriteGate
import app.amber.core.sync.core.SyncRestoreWriteRejectedException
import app.amber.core.utils.JsonInstant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
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
    fun `default terracotta preset restores iOS paper colors without replacing reading preferences`() = runTest {
        val initial = customDisplay().copy(appliedThemePackageId = "custom")
        val store = FakeThemeSettingsStore(Settings(displaySetting = initial))
        val manager = ThemePackageManager(dao = db.themePackageDao(), settingsStore = store)

        assertEquals(ThemePackageApplyResult.Applied, manager.applyBuiltin("WARM"))
        val applied = store.current.displaySetting
        assertEquals(DisplaySetting().amberBaseFamily, applied.amberBaseFamily)
        assertEquals(SIT_TERRACOTTA_ACCENT_HEX, applied.accentColor)
        assertEquals(initial.chatFontFamily, applied.chatFontFamily)
        assertEquals(initial.fontSizeRatio, applied.fontSizeRatio)
        assertEquals(initial.showUserAvatar, applied.showUserAvatar)
        assertNull(applied.appliedThemePackageId)
        assertEquals(ThemePackageApplyResult.AlreadyApplied, manager.applyBuiltin("WARM"))

        assertEquals(androidx.compose.ui.graphics.Color(0xFFEFE7D6), baseTokens(AmberBase.LIGHT).bg)
        assertEquals(androidx.compose.ui.graphics.Color(0xFFFFFDF7), baseTokens(AmberBase.LIGHT).surface)
        assertEquals(androidx.compose.ui.graphics.Color(0xFF14110E), baseTokens(AmberBase.DARK).bg)
    }

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
    fun `remove active package clears marker and preserves applied display settings`() = runTest {
        val store = FakeThemeSettingsStore(Settings(displaySetting = DisplaySetting()))
        val manager = ThemePackageManager(dao = db.themePackageDao(), settingsStore = store)
        val imported = manager.importPackage(exportJson(customDisplay())) as ThemePackageImportResult.Preview
        assertNull(db.themePackageDao().getById(imported.pkg.id))
        assertEquals(ThemePackageApplyResult.Applied, manager.applyPrepared(imported.pkg.id, imported.candidateDigest))
        assertNotNull(db.themePackageDao().getById(imported.pkg.id))
        val activeDisplay = store.current.displaySetting
        assertEquals(imported.pkg.id, activeDisplay.appliedThemePackageId)

        assertTrue(manager.remove(imported.pkg.id))

        assertNull(db.themePackageDao().getById(imported.pkg.id))
        assertEquals(
            activeDisplay.copy(appliedThemePackageId = null),
            store.current.displaySetting,
        )
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
    fun `discard during package write restores prior row and keeps replacement try-on`() = runTest {
        val initial = Settings(displaySetting = DisplaySetting(amberBaseFamily = "WARM"))
        val store = FakeThemeSettingsStore(initial)
        val delegate = db.themePackageDao()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val dao = PausingUpsertThemePackageDao(delegate, entered, release)
        val manager = ThemePackageManager(dao = dao, settingsStore = store)
        val previousEntity = ThemePackageEntity(
            id = "custom-theme",
            name = "已有主题",
            json = exportJson(customDisplay().copy(accentColor = "#12AB34")),
            importedAtMs = 7L,
        )
        delegate.upsert(previousEntity)
        val imported = manager.importPackage(exportJson(customDisplay())) as ThemePackageImportResult.Preview

        val applying = async { manager.applyPrepared(imported.pkg.id, imported.candidateDigest) }
        entered.await()

        assertTrue(manager.discardTryOn(imported.pkg.id, imported.candidateDigest))
        val replacement = manager.importPackage(
            exportJson(customDisplay().copy(accentColor = "#A12B34")),
        ) as ThemePackageImportResult.Preview
        store.current = store.current.copy(enableWebSearch = true)
        release.complete(Unit)

        assertEquals(ThemePackageApplyResult.NotPrepared, applying.await())
        assertEquals(previousEntity, delegate.getById(previousEntity.id))
        assertEquals(replacement.candidateDigest, manager.tryOn.value?.candidateDigest)
        assertTrue(store.current.enableWebSearch)
        assertEquals(initial.displaySetting, store.current.displaySetting)
    }

    @Test
    fun `apply prepared merges the latest non-theme settings after package write pauses`() = runTest {
        val initial = Settings(displaySetting = DisplaySetting(amberBaseFamily = "WARM"))
        val store = FakeThemeSettingsStore(initial)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val dao = PausingUpsertThemePackageDao(db.themePackageDao(), entered, release)
        val manager = ThemePackageManager(dao = dao, settingsStore = store)
        val imported = manager.importPackage(exportJson(customDisplay())) as ThemePackageImportResult.Preview

        val applying = async { manager.applyPrepared(imported.pkg.id, imported.candidateDigest) }
        entered.await()
        store.current = store.current.copy(enableWebSearch = true, titlePrompt = "latest prompt")
        release.complete(Unit)

        assertEquals(ThemePackageApplyResult.Applied, applying.await())
        assertTrue(store.current.enableWebSearch)
        assertEquals("latest prompt", store.current.titlePrompt)
        assertEquals(imported.pkg.id, store.current.displaySetting.appliedThemePackageId)
        assertEquals(customDisplay().accentColor, store.current.displaySetting.accentColor)
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

    @Test
    fun `stale prepared theme writer is rejected after restore`() = runTest {
        val initial = Settings(displaySetting = DisplaySetting(amberBaseFamily = "WARM"))
        val store = FakeThemeSettingsStore(initial)
        val gate = SyncRestoreWriteGate()
        val manager = ThemePackageManager(
            dao = db.themePackageDao(),
            settingsStore = store,
            restoreWriteGate = gate,
        )
        val imported = manager.importPackage(exportJson(customDisplay())) as ThemePackageImportResult.Preview
        val staleEpoch = gate.currentEpoch()

        gate.withRestore { gate.markDataCommitted() }

        val failure = runCatching {
            withContext(SyncRestoreWriteEpoch(staleEpoch)) {
                manager.applyPrepared(imported.pkg.id, imported.candidateDigest)
            }
        }.exceptionOrNull()

        assertTrue(failure is SyncRestoreWriteRejectedException)
        assertNull(db.themePackageDao().getById(imported.pkg.id))
        assertEquals(initial, store.current)
        assertNotNull(manager.tryOn.value)
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

        override suspend fun update(transform: (Settings) -> Settings) {
            flow.value = transform(flow.value)
        }
    }

    private class PausingUpsertThemePackageDao(
        private val delegate: ThemePackageDAO,
        private val entered: CompletableDeferred<Unit>,
        private val release: CompletableDeferred<Unit>,
    ) : ThemePackageDAO {
        private var pauseNextUpsert = true

        override fun observeAll(): Flow<List<ThemePackageEntity>> = delegate.observeAll()

        override suspend fun getById(id: String): ThemePackageEntity? = delegate.getById(id)

        override suspend fun upsert(entity: ThemePackageEntity) {
            delegate.upsert(entity)
            if (pauseNextUpsert) {
                pauseNextUpsert = false
                entered.complete(Unit)
                release.await()
            }
        }

        override suspend fun delete(id: String): Int = delegate.delete(id)
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
