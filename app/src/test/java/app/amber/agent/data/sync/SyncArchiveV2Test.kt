package app.amber.agent.data.sync

import android.app.Application
import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import app.amber.ai.provider.providers.google.GoogleGeminiAuthStore
import app.amber.ai.provider.providers.openai.OpenAICodexAuthStore
import app.amber.agent.data.db.AppDatabase
import app.amber.agent.data.db.fts.MessageFtsManager
import app.amber.agent.data.db.entity.ConversationEntity
import app.amber.core.files.FileFolders
import app.amber.core.files.FilesManager
import app.amber.core.infra.AppScope
import app.amber.core.repository.FilesRepository
import app.amber.core.settings.prefs.AgentPrefs
import app.amber.core.settings.prefs.ChatPrefs
import app.amber.core.settings.prefs.ExtensionPrefs
import app.amber.core.settings.prefs.NativePathPrefs
import app.amber.core.settings.prefs.ProviderPrefs
import app.amber.core.settings.prefs.SearchPrefs
import app.amber.core.settings.prefs.SettingsAggregator
import app.amber.core.settings.prefs.UIPrefs
import app.amber.core.settings.secret.SecretCipher
import app.amber.core.settings.secret.SecretRedactor
import app.amber.core.settings.secret.SecretStore
import app.amber.core.settings.secret.SecretStoreBackend
import app.amber.core.sync.core.DeviceBoundBackupKey
import app.amber.core.sync.core.LEGACY_ARCHIVE_VERSION
import app.amber.core.sync.core.NO_PASSPHRASE_FALLBACK
import app.amber.core.sync.core.RestoreScope
import app.amber.core.sync.core.SYNC_MANIFEST_ENTRY
import app.amber.core.sync.core.SYNC_PAYLOAD_ENTRY
import app.amber.core.sync.core.SyncArchiveManager
import app.amber.core.sync.core.SyncCrypto
import app.amber.core.sync.core.SyncEncryptionMode
import app.amber.core.sync.core.SyncExportRequest
import app.amber.core.sync.core.SyncManifest
import app.amber.core.sync.core.SyncMode
import app.amber.core.sync.core.SyncRestoreRequest
import app.amber.core.utils.JsonInstant
import app.amber.feature.webmount.oauth.WebMountOAuthTokenStore
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * P7-02：自定义备份口令与版本化加密头。
 *
 * 覆盖计划测试清单：正确/错误口令、损坏文件、旧格式迁移、中途退出
 * （解密后未写入不残留）、含和不含 secrets 的备份、设备绑定加密。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SyncArchiveV2Test {
    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var appScope: AppScope
    private lateinit var manager: SyncArchiveManager
    private lateinit var settingsStore: SettingsAggregator
    private lateinit var deviceKey: DeviceBoundBackupKey
    private lateinit var filesManager: FilesManager
    private lateinit var nativePathPrefs: NativePathPrefs
    private lateinit var secretRedactor: SecretRedactor
    private lateinit var testRoot: File
    private val mainDispatcher = UnconfinedTestDispatcher()
    private val crypto = SyncCrypto(nativeEnabled = false)

    @Before
    fun setUp() = runBlocking {
        kotlinx.coroutines.Dispatchers.setMain(mainDispatcher)
        context = RuntimeEnvironment.getApplication()
        testRoot = File(context.cacheDir, "sync-archive-v2-${System.nanoTime()}").apply { mkdirs() }
        listOf(FileFolders.UPLOAD, FileFolders.SKILLS, FileFolders.IMAGES, FileFolders.CHAT_IMAGES)
            .forEach { File(context.filesDir, it).deleteRecursively() }

        appScope = AppScope()
        val dataStore = PreferenceDataStoreFactory.create {
            File(testRoot, "settings.preferences_pb")
        }
        val secretStore = SecretStore(
            backend = inMemoryBackend(),
            cipher = stubCipher(),
        )
        secretRedactor = SecretRedactor(secretStore)
        settingsStore = SettingsAggregator(
            dataStore = dataStore,
            uiPrefs = UIPrefs(dataStore, appScope),
            searchPrefs = SearchPrefs(dataStore, appScope, secretStore),
            agentPrefs = AgentPrefs(dataStore, appScope),
            providerPrefs = ProviderPrefs(dataStore, appScope, secretStore),
            chatPrefs = ChatPrefs(dataStore, appScope, secretStore),
            extensionPrefs = ExtensionPrefs(dataStore, appScope, secretStore),
            scope = appScope,
            secretRedactor = secretRedactor,
        )
        withTimeout(5_000) { settingsStore.settingsFlow.first { !it.init } }

        nativePathPrefs = NativePathPrefs(dataStore, appScope)
        nativePathPrefs.update { it.copy(syncCrypto = false) }
        withTimeout(5_000) { nativePathPrefs.flow.first { !it.syncCrypto } }

        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        database.openHelper.writableDatabase.execSQL(
            """
            CREATE TABLE IF NOT EXISTS message_fts(
                text TEXT,
                node_id TEXT,
                message_id TEXT,
                conversation_id TEXT,
                title TEXT,
                update_at TEXT
            )
            """.trimIndent()
        )
        filesManager = FilesManager(
            context = context,
            repository = FilesRepository(database.managedFileDao()),
            appScope = appScope,
        )
        deviceKey = DeviceBoundBackupKey(secretStore)
        manager = buildManager(deviceKey)
    }

    @After
    fun tearDown() {
        if (::database.isInitialized) database.close()
        if (::testRoot.isInitialized) testRoot.deleteRecursively()
        if (::context.isInitialized) {
            listOf(FileFolders.UPLOAD, FileFolders.SKILLS, FileFolders.IMAGES, FileFolders.CHAT_IMAGES)
                .forEach { File(context.filesDir, it).deleteRecursively() }
        }
        kotlinx.coroutines.Dispatchers.resetMain()
    }

    // ---------------- 正确 / 错误口令 ----------------

    @Test
    fun correctPassphraseVerifiesAndWrongPassphraseIsRejected() = runBlocking {
        val archive = manager.createArchive(
            SyncExportRequest(mode = SyncMode.FULL, passphrase = "correct-passphrase-123")
        )
        val manifest = manager.inspectArchive(archive).manifest
        // 新格式：v2 文件头记录 KDF 参数与 cipher，不记录口令。
        assertEquals(2, manifest.archiveVersion)
        assertEquals(SyncEncryptionMode.PASSPHRASE, manifest.encryptionMode)
        assertTrue(manifest.kdf.saltBase64.isNotBlank())
        assertTrue(manifest.cipher.ivBase64.isNotBlank())
        assertEquals("PBKDF2WithHmacSHA256", manifest.kdf.name)

        val verification = manager.verifyArchive(
            archiveFile(archive),
            SyncRestoreRequest(passphrase = "correct-passphrase-123"),
        )
        assertEquals(2, verification.preview.manifest.archiveVersion)
        assertFalse(verification.preview.legacyFormat)
        manager.discardVerification(verification)

        val error = runCatching {
            manager.verifyArchive(
                archiveFile(archive),
                SyncRestoreRequest(passphrase = "wrong-passphrase"),
            )
        }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        assertTrue(error!!.message!!.contains("口令错误") || error.message!!.contains("损坏"))
    }

    @Test
    fun blankPassphraseIsRejectedForPassphraseMode() = runBlocking {
        val error = runCatching {
            manager.createArchive(SyncExportRequest(mode = SyncMode.FULL, passphrase = "  "))
        }.exceptionOrNull()
        assertNotNull(error)
    }

    // ---------------- 损坏文件 ----------------

    @Test
    fun corruptedArchiveIsRejectedWithoutWriting() = runBlocking {
        val archive = manager.createArchive(
            SyncExportRequest(mode = SyncMode.FULL, passphrase = "test-passphrase")
        )
        val corrupted = archive.copyOf().also { bytes ->
            // 翻转 payload 区字节（manifest 之后的任意位置）。
            val mid = bytes.size / 2
            bytes[mid] = (bytes[mid].toInt() xor 0x5A).toByte()
        }
        val file = File(testRoot, "corrupted.amberbackup").apply { writeBytes(corrupted) }
        val error = runCatching {
            manager.verifyArchive(file, SyncRestoreRequest(passphrase = "test-passphrase"))
        }.exceptionOrNull()
        assertNotNull(error)
        // 未写入任何数据。
        assertEquals(0, database.conversationDao().getAllIds().size)
    }

    // ---------------- 旧格式迁移 ----------------

    @Test
    fun legacyV1UnprotectedArchiveIsRestorableWithControlledFallback() = runBlocking {
        val legacy = buildLegacyArchive(
            passphrase = NO_PASSPHRASE_FALLBACK,
            passphraseProtected = false,
        )
        // 旧格式无口令：无需输入，受控兼容分支自动使用历史固定回退口令。
        val verification = manager.verifyArchive(
            legacy,
            SyncRestoreRequest(passphrase = ""),
        )
        assertTrue(verification.preview.legacyFormat)
        assertEquals(LEGACY_ARCHIVE_VERSION, verification.preview.manifest.archiveVersion)
        manager.discardVerification(verification)

        // 迁移路径：旧格式可完整恢复（CONFIG_ONLY 只写 settings）。
        val restored = manager.restoreArchive(
            legacy,
            SyncRestoreRequest(passphrase = "", scope = RestoreScope.CONFIG_ONLY),
        )
        assertTrue(restored.legacyFormat)
        assertEquals("legacy-app", restored.manifest.appVersionName)
    }

    @Test
    fun legacyV1ProtectedArchiveRequiresPassphrase() = runBlocking {
        val legacy = buildLegacyArchive(
            passphrase = "legacy-user-passphrase",
            passphraseProtected = true,
        )
        val error = runCatching {
            manager.verifyArchive(legacy, SyncRestoreRequest(passphrase = ""))
        }.exceptionOrNull()
        assertNotNull(error)

        val verification = manager.verifyArchive(
            legacy,
            SyncRestoreRequest(passphrase = "legacy-user-passphrase"),
        )
        assertTrue(verification.preview.legacyFormat)
        manager.discardVerification(verification)
    }

    @Test
    fun incompatibleLegacyEverythingRestoreFailsClosedBeforeWipingTables() = runBlocking {
        database.conversationDao().insert(
            ConversationEntity(
                id = "local-conversation",
                assistantId = "local-assistant",
                title = "Keep me",
                nodes = "[]",
                createAt = 1L,
                updateAt = 1L,
                chatSuggestions = "[]",
                isPinned = false,
            )
        )
        val legacy = buildLegacyArchive(
            passphrase = NO_PASSPHRASE_FALLBACK,
            passphraseProtected = false,
        )
        val verification = manager.verifyArchive(
            legacy,
            SyncRestoreRequest(
                passphrase = "",
                scope = RestoreScope.EVERYTHING,
                preserveConversations = false,
                preserveGenMedia = false,
            ),
        )

        try {
            val error = runCatching {
                manager.applyRestore(
                    verification,
                    SyncRestoreRequest(
                        passphrase = "",
                        scope = RestoreScope.EVERYTHING,
                        preserveConversations = false,
                        preserveGenMedia = false,
                    ),
                )
            }.exceptionOrNull()
            assertTrue(error is IllegalArgumentException)
            assertTrue(error?.message.orEmpty().contains("缺少完整恢复所需数据集"))
            assertEquals(listOf("local-conversation"), database.conversationDao().getAllIds())
        } finally {
            manager.discardVerification(verification)
        }
    }

    @Test
    fun newExportsNeverProduceLegacyFormat() = runBlocking {
        val passphraseArchive = manager.createArchive(
            SyncExportRequest(mode = SyncMode.FULL, passphrase = "new-format-passphrase")
        )
        val deviceArchive = manager.createArchive(
            SyncExportRequest(
                mode = SyncMode.FULL,
                passphrase = "",
                encryptionMode = SyncEncryptionMode.DEVICE_BOUND,
            )
        )
        assertEquals(2, manager.inspectArchive(passphraseArchive).manifest.archiveVersion)
        assertEquals(2, manager.inspectArchive(deviceArchive).manifest.archiveVersion)
    }

    // ---------------- 中途退出：解密后未写入不残留 ----------------

    @Test
    fun verifyThenDiscardLeavesNoResidueAndNoWrites() = runBlocking {
        val archive = manager.createArchive(
            SyncExportRequest(mode = SyncMode.FULL, passphrase = "test-passphrase")
        )
        val before = settingsStore.settingsFlow.value.syncSettings.deviceId
        val verification = manager.verifyArchive(
            archiveFile(archive),
            SyncRestoreRequest(passphrase = "test-passphrase"),
        )
        // 解密成功：能看到负载预览（settings 数据集）。
        assertTrue(verification.payloadPreview.datasets.any { it.id == "settings" })
        manager.discardVerification(verification)

        // 无残留临时文件。
        val syncCacheDir = File(context.cacheDir, "sync")
        val residue = syncCacheDir.listFiles().orEmpty().filter { it.name.startsWith("amber-restore-payload") }
        assertTrue("解密临时文件未清理", residue.isEmpty())
        // 未写入任何数据。
        assertEquals(before, settingsStore.settingsFlow.value.syncSettings.deviceId)
        assertEquals(0, database.conversationDao().getAllIds().size)
    }

    // ---------------- 含 / 不含 secrets ----------------

    @Test
    fun fullIncludesSecretsAndStandardExcludesThem() = runBlocking {
        val full = manager.createArchive(
            SyncExportRequest(mode = SyncMode.FULL, passphrase = "test-passphrase")
        )
        val fullVerification = manager.verifyArchive(
            archiveFile(full),
            SyncRestoreRequest(passphrase = "test-passphrase"),
        )
        assertTrue(fullVerification.payloadPreview.includesSecrets)
        assertTrue(
            SyncArchiveManager.SYNC_TABLES.all { table ->
                fullVerification.payloadPreview.datasets.any { it.id == "table:$table" }
            }
        )
        manager.discardVerification(fullVerification)

        val standard = manager.createArchive(
            SyncExportRequest(mode = SyncMode.STANDARD, passphrase = "test-passphrase")
        )
        val standardVerification = manager.verifyArchive(
            archiveFile(standard),
            SyncRestoreRequest(passphrase = "test-passphrase"),
        )
        assertFalse(standardVerification.payloadPreview.includesSecrets)
        manager.discardVerification(standardVerification)
    }

    // ---------------- 设备绑定加密 ----------------

    @Test
    fun deviceBoundArchiveRestoresOnlyOnSameDevice() = runBlocking {
        val archive = manager.createArchive(
            SyncExportRequest(mode = SyncMode.FULL, passphrase = "", encryptionMode = SyncEncryptionMode.DEVICE_BOUND)
        )
        val manifest = manager.inspectArchive(archive).manifest
        assertEquals(SyncEncryptionMode.DEVICE_BOUND, manifest.encryptionMode)
        // 文件头不记录口令或可逆提示。
        assertFalse(JsonInstant.encodeToString(manifest).contains("AmberAgent-NoPassphrase"))

        // 同设备：无需口令即可验证。
        val verification = manager.verifyArchive(
            archiveFile(archive),
            SyncRestoreRequest(passphrase = ""),
        )
        assertEquals(SyncEncryptionMode.DEVICE_BOUND, verification.preview.manifest.encryptionMode)
        manager.discardVerification(verification)

        // “另一台设备”：新设备没有本机设备秘密（全新 SecretStore），无法恢复。
        val otherDeviceManager = buildManager(
            DeviceBoundBackupKey(SecretStore(backend = inMemoryBackend(), cipher = stubCipher()))
        )
        val error = runCatching {
            otherDeviceManager.verifyArchive(archiveFile(archive), SyncRestoreRequest(passphrase = ""))
        }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
        assertTrue(error!!.message!!.contains("设备绑定"))
    }

    // ---------------- fixtures ----------------

    private fun buildManager(deviceBoundBackupKey: DeviceBoundBackupKey): SyncArchiveManager =
        SyncArchiveManager(
            context = context,
            settingsStore = settingsStore,
            database = database,
            messageFtsManager = MessageFtsManager(database),
            filesManager = filesManager,
            webMountOAuthTokenStore = WebMountOAuthTokenStore(context),
            openAICodexAuthStore = OpenAICodexAuthStore(context),
            googleGeminiAuthStore = GoogleGeminiAuthStore(context),
            json = JsonInstant,
            nativePathPrefs = nativePathPrefs,
            secretRedactor = secretRedactor,
            deviceBoundBackupKey = deviceBoundBackupKey,
        )

    private fun archiveFile(archive: ByteArray): File =
        File(testRoot, "archive-${System.nanoTime()}.amberbackup").apply { writeBytes(archive) }

    /** 手工构造历史 v1 格式（含固定回退口令格式的受控兼容分支测试样本）。 */
    private fun buildLegacyArchive(
        passphrase: String,
        passphraseProtected: Boolean,
    ): File {
        val payloadBytes = buildLegacyPayload()
        val params = crypto.newEncryptionParams()
        val encrypted = crypto.encrypt(payloadBytes, passphrase, params)
        val manifest = SyncManifest(
            archiveVersion = LEGACY_ARCHIVE_VERSION,
            appVersionName = "legacy-app",
            appVersionCode = 1L,
            createdAt = 1L,
            deviceId = "legacy-device",
            deviceLabel = "",
            mode = SyncMode.STANDARD,
            kdf = params.kdf,
            cipher = params.cipher,
            payloadSha256 = crypto.sha256(encrypted),
            passphraseProtected = passphraseProtected,
        )
        val file = File(testRoot, "legacy-${System.nanoTime()}.amberbackup")
        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            zip.putNextEntry(ZipEntry(SYNC_MANIFEST_ENTRY))
            zip.write(JsonInstant.encodeToString(manifest).toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry(SYNC_PAYLOAD_ENTRY))
            zip.write(encrypted)
            zip.closeEntry()
        }
        return file
    }

    private fun buildLegacyPayload(): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("settings.json"))
            zip.write(JsonInstant.encodeToString(settingsStore.settingsFlow.value).toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("payload_manifest.json"))
            zip.write("""{"datasets":[{"id":"settings","recordCount":1}]}""".toByteArray())
            zip.closeEntry()
        }
        return out.toByteArray()
    }

    private fun inMemoryBackend(): SecretStoreBackend = object : SecretStoreBackend {
        private val map = mutableMapOf<String, String>()
        override fun get(key: String): String? = map[key]
        override fun put(key: String, value: String) {
            map[key] = value
        }

        override fun remove(key: String) {
            map.remove(key)
        }

        override fun keys(): Set<String> = map.keys.toSet()
    }

    private fun stubCipher(): SecretCipher = object : SecretCipher {
        override fun encrypt(plaintext: String): String = "enc:$plaintext"
        override fun decrypt(stored: String): String? = stored.removePrefix("enc:")
    }
}
