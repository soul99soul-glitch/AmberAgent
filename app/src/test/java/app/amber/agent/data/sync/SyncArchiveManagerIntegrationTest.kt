package app.amber.agent.data.sync

import android.app.Application
import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import app.amber.ai.provider.providers.TestAndroidKeyStore
import app.amber.ai.provider.providers.google.GoogleGeminiAuthStore
import app.amber.ai.provider.providers.grok.GrokAuthStore
import app.amber.ai.provider.providers.grok.GROK_CLI_PROXY_BASE_URL
import app.amber.ai.provider.providers.grok.GrokOAuthTokens
import app.amber.ai.provider.providers.openai.OpenAICodexAuthStore
import app.amber.agent.data.db.AppDatabase
import app.amber.agent.data.db.entity.ManagedFileEntity
import app.amber.agent.data.db.fts.MessageFtsManager
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
import app.amber.core.sync.core.RestoreScope
import app.amber.core.sync.core.SYNC_MANIFEST_ENTRY
import app.amber.core.sync.core.SYNC_PAYLOAD_ENTRY
import app.amber.core.sync.core.SyncCrypto
import app.amber.core.sync.core.SyncArchiveManager
import app.amber.core.sync.core.SyncEncryptionParams
import app.amber.core.sync.core.SyncExportRequest
import app.amber.core.sync.core.SyncManifest
import app.amber.core.sync.core.SyncMode
import app.amber.core.sync.core.SyncRestorePartialCommitException
import app.amber.core.sync.core.SyncRestoreRequest
import app.amber.core.sync.core.SyncRestoreWriteGate
import app.amber.core.utils.JsonInstant
import app.amber.feature.webmount.oauth.WebMountOAuthTokenStore
import java.io.File
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.uuid.Uuid

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SyncArchiveManagerIntegrationTest {
    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var appScope: AppScope
    private lateinit var manager: SyncArchiveManager
    private lateinit var grokAuthStore: GrokAuthStore
    private lateinit var restoreWriteGate: SyncRestoreWriteGate
    private lateinit var testRoot: File
    private var blockingSymlink: File? = null
    private val mainDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() = runBlocking {
        kotlinx.coroutines.Dispatchers.setMain(mainDispatcher)
        context = RuntimeEnvironment.getApplication()
        testRoot = File(context.cacheDir, "sync-archive-integration-${System.nanoTime()}").apply { mkdirs() }
        listOf(FileFolders.UPLOAD, FileFolders.SKILLS, FileFolders.IMAGES, FileFolders.CHAT_IMAGES)
            .forEach { File(context.filesDir, it).deleteRecursively() }

        appScope = AppScope()
        val dataStore = PreferenceDataStoreFactory.create {
            File(testRoot, "settings.preferences_pb")
        }
        // P1-01: Robolectric 无真实 AndroidKeyStore，用内存 backend + 桩 cipher
        // （复用 Keystore 包装模式，见 SecretStore 接口拆分）。
        val secretStore = SecretStore(
            backend = object : SecretStoreBackend {
                private val map = mutableMapOf<String, String>()
                override fun get(key: String): String? = map[key]
                override fun put(key: String, value: String) {
                    map[key] = value
                }

                override fun remove(key: String) {
                    map.remove(key)
                }

                override fun keys(): Set<String> = map.keys.toSet()
            },
            cipher = object : SecretCipher {
                override fun encrypt(plaintext: String): String = "enc:$plaintext"
                override fun decrypt(stored: String): String? = stored.removePrefix("enc:")
            },
        )
        val secretRedactor = SecretRedactor(secretStore)
        val settingsStore = SettingsAggregator(
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

        val nativePathPrefs = NativePathPrefs(dataStore, appScope)
        nativePathPrefs.update { it.copy(syncCrypto = false) }
        withTimeout(5_000) { nativePathPrefs.flow.first { !it.syncCrypto } }

        grokAuthStore = GrokAuthStore(context)

        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        recreateFtsTables()
        restoreWriteGate = SyncRestoreWriteGate()
        val filesManager = FilesManager(
            context = context,
            repository = FilesRepository(database.managedFileDao()),
            appScope = appScope,
            restoreWriteGate = restoreWriteGate,
        )
        manager = SyncArchiveManager(
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
            deviceBoundBackupKey = DeviceBoundBackupKey(secretStore),
            restoreWriteGate = restoreWriteGate,
            grokAuthStore = grokAuthStore,
        )
    }

    @After
    fun tearDown() {
        if (::database.isInitialized) database.close()
        blockingSymlink?.let { Files.deleteIfExists(it.toPath()) }
        if (::testRoot.isInitialized) testRoot.deleteRecursively()
        if (::context.isInitialized) {
            listOf(FileFolders.UPLOAD, FileFolders.SKILLS, FileFolders.IMAGES, FileFolders.CHAT_IMAGES)
                .forEach { File(context.filesDir, it).deleteRecursively() }
        }
        // The production preference collectors intentionally terminate the process when their
        // app-wide scope fails, so this integration fixture must not cancel that scope in @After.
        kotlinx.coroutines.Dispatchers.resetMain()
    }

    @Test
    fun preserveConversationsKeepsManagedUploadsTogether() = runBlocking {
        val uploadDir = File(context.filesDir, FileFolders.UPLOAD).apply { mkdirs() }
        val archivedUpload = File(uploadDir, "archived.txt").apply { writeText("from archive") }
        database.managedFileDao().insert(managedFile("archived.txt", archivedUpload.length()))
        val archive = manager.createArchive(
            SyncExportRequest(mode = SyncMode.FULL, passphrase = "test-passphrase")
        )

        database.openHelper.writableDatabase.execSQL("DELETE FROM managed_files")
        uploadDir.deleteRecursively()
        uploadDir.mkdirs()
        val localUpload = File(uploadDir, "local.txt").apply { writeText("keep local") }
        database.managedFileDao().insert(managedFile("local.txt", localUpload.length()))

        manager.restoreArchive(
            archive,
            SyncRestoreRequest(
                passphrase = "test-passphrase",
                scope = RestoreScope.EVERYTHING,
                preserveConversations = true,
                preserveGenMedia = false,
            )
        )

        val rows = database.managedFileDao().listByFolder(FileFolders.UPLOAD).first()
        assertEquals(listOf("upload/local.txt"), rows.map { it.relativePath })
        assertTrue(localUpload.exists())
        assertEquals("keep local", localUpload.readText())
        assertFalse(File(uploadDir, "archived.txt").exists())
    }

    @Test
    fun failedFileReplacementLeavesExistingDatabaseUntouched() = runBlocking {
        val uploadDir = File(context.filesDir, FileFolders.UPLOAD).apply { mkdirs() }
        val archivedUpload = File(uploadDir, "archived.txt").apply { writeText("from archive") }
        database.managedFileDao().insert(managedFile("archived.txt", archivedUpload.length()))
        val archive = manager.createArchive(
            SyncExportRequest(mode = SyncMode.FULL, passphrase = "test-passphrase")
        )

        database.openHelper.writableDatabase.execSQL("DELETE FROM managed_files")
        uploadDir.deleteRecursively()
        uploadDir.mkdirs()
        val localUpload = File(uploadDir, "local.txt").apply { writeText("keep local") }
        database.managedFileDao().insert(managedFile("local.txt", localUpload.length()))

        val outsideRoot = File(testRoot, "outside-skills").apply { mkdirs() }
        blockingSymlink = File(context.filesDir, FileFolders.SKILLS)
        Files.createSymbolicLink(blockingSymlink!!.toPath(), outsideRoot.toPath())

        val error = runCatching {
            manager.restoreArchive(
                archive,
                SyncRestoreRequest(
                    passphrase = "test-passphrase",
                    scope = RestoreScope.EVERYTHING,
                    preserveConversations = false,
                    preserveGenMedia = false,
                )
            )
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        val rows = database.managedFileDao().listByFolder(FileFolders.UPLOAD).first()
        assertEquals(listOf("upload/local.txt"), rows.map { it.relativePath })
        assertTrue(localUpload.exists())
        assertEquals("keep local", localUpload.readText())
    }

    @Test
    fun failedApplyConsumesVerificationAndReverifyCanCompleteFullRestore() = runBlocking {
        val archive = manager.createArchive(
            SyncExportRequest(mode = SyncMode.FULL, passphrase = "test-passphrase")
        )
        val request = SyncRestoreRequest(
            passphrase = "test-passphrase",
            scope = RestoreScope.EVERYTHING,
            preserveConversations = false,
            preserveGenMedia = false,
        )
        val verification = manager.verifyArchive(archiveFile(archive), request)

        val outsideRoot = File(testRoot, "outside-skills").apply { mkdirs() }
        blockingSymlink = File(context.filesDir, FileFolders.SKILLS)
        Files.createSymbolicLink(blockingSymlink!!.toPath(), outsideRoot.toPath())
        val firstError = runCatching {
            withTimeout(10_000) { manager.applyRestore(verification, request) }
        }.exceptionOrNull()
        assertTrue(firstError is IllegalArgumentException)

        Files.deleteIfExists(blockingSymlink!!.toPath())
        blockingSymlink = null
        val reuseError = runCatching {
            manager.applyRestore(verification, request)
        }.exceptionOrNull()
        assertTrue(reuseError is IllegalArgumentException)
        assertTrue(reuseError?.message.orEmpty().contains("失效"))

        val reverified = manager.verifyArchive(archiveFile(archive), request)
        val restored = withTimeout(10_000) { manager.applyRestore(reverified, request) }
        assertEquals(archive.size.toLong(), restored.sizeBytes ?: -1L)
    }

    @Test
    fun partialCommitKeepsJournalAndImportedDataUntilReverifiedRetry() = runBlocking {
        val uploadDir = File(context.filesDir, FileFolders.UPLOAD).apply { mkdirs() }
        val archivedUpload = File(uploadDir, "partial-commit.txt").apply { writeText("from archive") }
        database.managedFileDao().insert(managedFile("partial-commit.txt", archivedUpload.length()))
        val archive = manager.createArchive(
            SyncExportRequest(mode = SyncMode.FULL, passphrase = "test-passphrase")
        )
        val request = SyncRestoreRequest(
            passphrase = "test-passphrase",
            scope = RestoreScope.EVERYTHING,
            preserveConversations = false,
            preserveGenMedia = false,
        )
        val verification = manager.verifyArchive(archiveFile(archive), request)

        // Break the real FTS owner after verification. Restore tables and file
        // roots commit first, so this injects a deterministic post-commit
        // failure and exercises the durable journal recovery path.
        database.openHelper.writableDatabase.execSQL("DROP TABLE message_fts")
        database.openHelper.writableDatabase.execSQL("DROP TABLE conversation_title_fts")
        val error = runCatching {
            withTimeout(10_000) { manager.applyRestore(verification, request) }
        }.exceptionOrNull()
        assertTrue(error is SyncRestorePartialCommitException)

        val journalRoot = File(context.cacheDir, "sync-restore-file-journal")
        assertTrue(journalRoot.exists())
        assertTrue(archivedUpload.exists())
        val importedRows = database.managedFileDao().listByFolder(FileFolders.UPLOAD).first()
        assertEquals(listOf("upload/partial-commit.txt"), importedRows.map { it.relativePath })

        recreateFtsTables()
        val reverified = manager.verifyArchive(archiveFile(archive), request)
        withTimeout(10_000) { manager.applyRestore(reverified, request) }
        assertTrue(!journalRoot.exists())
    }

    @Test
    fun fullRestoreRoundTripsGrokTokenAndEndpointBackup() = runBlocking {
        val providerId = Uuid.random()
        val tokens = GrokOAuthTokens(
            accessToken = "grok-access",
            refreshToken = "grok-refresh",
            expiresAtMillis = 123_456L,
        )
        grokAuthStore.save(providerId, tokens)
        grokAuthStore.saveBackup(providerId, "https://api.x.ai/v1")

        try {
            val archive = manager.createArchive(
                SyncExportRequest(mode = SyncMode.FULL, passphrase = "test-passphrase")
            )
            grokAuthStore.clear(providerId)

            manager.restoreArchive(
                archive,
                SyncRestoreRequest(
                    passphrase = "test-passphrase",
                    scope = RestoreScope.EVERYTHING,
                    preserveConversations = false,
                    preserveGenMedia = false,
                ),
            )

            assertEquals(tokens, grokAuthStore.get(providerId))
            assertEquals("https://api.x.ai/v1", grokAuthStore.getBackup(providerId))
        } finally {
            grokAuthStore.clear(providerId)
        }
    }

    @Test
    fun fullRestoreAcceptsLegacySecretsWithoutGrokEndpointBackupField() = runBlocking {
        val providerId = Uuid.random()
        val tokens = GrokOAuthTokens(
            accessToken = "legacy-access",
            refreshToken = "legacy-refresh",
            expiresAtMillis = 654_321L,
        )
        grokAuthStore.save(providerId, tokens)
        grokAuthStore.saveBackup(providerId, "https://api.x.ai/v1")

        try {
            val currentArchive = manager.createArchive(
                SyncExportRequest(mode = SyncMode.FULL, passphrase = "test-passphrase")
            )
            val legacySecrets = buildJsonObject {
                put("grokOAuth", grokAuthStore.exportRawJsonForSync())
            }.toString()
            val legacyArchive = rewriteSecretsEntry(
                archive = currentArchive,
                passphrase = "test-passphrase",
                secretsJson = legacySecrets,
            )
            grokAuthStore.clear(providerId)

            manager.restoreArchive(
                legacyArchive,
                SyncRestoreRequest(
                    passphrase = "test-passphrase",
                    scope = RestoreScope.EVERYTHING,
                    preserveConversations = false,
                    preserveGenMedia = false,
                ),
            )

            assertEquals(tokens, grokAuthStore.get(providerId))
            // The settings console snapshots the restored provider endpoint.
            // A legacy archive has no original URL; the managed proxy is not one.
            grokAuthStore.saveBackup(providerId, GROK_CLI_PROXY_BASE_URL)
            assertEquals(null, grokAuthStore.getBackup(providerId))
        } finally {
            grokAuthStore.clear(providerId)
        }
    }

    private fun recreateFtsTables() {
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
        database.openHelper.writableDatabase.execSQL(
            """
            CREATE TABLE IF NOT EXISTS conversation_title_fts(
                title TEXT,
                conversation_id TEXT,
                update_at TEXT
            )
            """.trimIndent()
        )
    }

    private fun archiveFile(bytes: ByteArray): File =
        File(testRoot, "integration.amberbackup").apply { writeBytes(bytes) }

    private fun rewriteSecretsEntry(
        archive: ByteArray,
        passphrase: String,
        secretsJson: String,
    ): ByteArray {
        val outerEntries = readZipEntries(archive)
        val manifest = JsonInstant.decodeFromString<SyncManifest>(
            outerEntries.getValue(SYNC_MANIFEST_ENTRY).decodeToString(),
        )
        val crypto = SyncCrypto(nativeEnabled = false)
        val payload = crypto.decrypt(
            outerEntries.getValue(SYNC_PAYLOAD_ENTRY),
            passphrase,
            manifest,
        )
        val payloadEntries = readZipEntries(payload).toMutableMap()
        payloadEntries["secrets.json"] = secretsJson.toByteArray()
        val rewrittenPayload = writeZipEntries(payloadEntries)
        val encryptedPayload = crypto.encrypt(
            rewrittenPayload,
            passphrase,
            SyncEncryptionParams(manifest.kdf, manifest.cipher),
        )
        val rewrittenManifest = manifest.copy(payloadSha256 = crypto.sha256(encryptedPayload))
        return writeZipEntries(
            linkedMapOf(
                SYNC_MANIFEST_ENTRY to JsonInstant.encodeToString(rewrittenManifest).toByteArray(),
                SYNC_PAYLOAD_ENTRY to encryptedPayload,
            ),
        )
    }

    private fun readZipEntries(bytes: ByteArray): LinkedHashMap<String, ByteArray> {
        val entries = linkedMapOf<String, ByteArray>()
        ZipInputStream(bytes.inputStream().buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory) entries[entry.name] = zip.readBytes()
                zip.closeEntry()
            }
        }
        return entries
    }

    private fun writeZipEntries(entries: Map<String, ByteArray>): ByteArray =
        ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip ->
                entries.forEach { (name, bytes) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
        }.toByteArray()

    private fun managedFile(name: String, size: Long) = ManagedFileEntity(
        folder = FileFolders.UPLOAD,
        relativePath = "${FileFolders.UPLOAD}/$name",
        displayName = name,
        mimeType = "text/plain",
        sizeBytes = size,
        createdAt = 1L,
        updatedAt = 1L,
    )

    companion object {
        @JvmStatic
        @BeforeClass
        fun installTestKeyStore() = TestAndroidKeyStore.install()

        @JvmStatic
        @AfterClass
        fun restoreTestKeyStore() = TestAndroidKeyStore.restore()
    }
}
