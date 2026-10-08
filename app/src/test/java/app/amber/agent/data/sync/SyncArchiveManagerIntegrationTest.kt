package app.amber.agent.data.sync

import android.app.Application
import android.content.Context
import android.content.pm.ProviderInfo
import android.net.Uri
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import app.amber.ai.provider.providers.TestAndroidKeyStore
import app.amber.ai.provider.providers.google.GoogleGeminiAuthStore
import app.amber.ai.provider.providers.grok.GrokAuthStore
import app.amber.ai.provider.providers.grok.GROK_CLI_PROXY_BASE_URL
import app.amber.ai.provider.providers.grok.GrokOAuthTokens
import app.amber.ai.provider.providers.openai.OpenAICodexAuthStore
import app.amber.agent.data.db.AppDatabase
import app.amber.agent.data.db.entity.LiveCardEntity
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
import app.amber.core.sync.core.SyncDatasetSummary
import app.amber.core.sync.core.SyncArchiveManager
import app.amber.core.sync.core.SyncEncryptionParams
import app.amber.core.sync.core.SyncExportRequest
import app.amber.core.sync.core.SyncManifest
import app.amber.core.sync.core.SyncMode
import app.amber.core.sync.core.SyncPayloadManifest
import app.amber.core.sync.core.SyncRestorePartialCommitException
import app.amber.core.sync.core.SyncRestoreRequest
import app.amber.core.sync.core.SyncRestoreWriteGate
import app.amber.core.utils.JsonInstant
import app.amber.agent.data.workspace.ArtifactRepository
import app.amber.feature.workspace.WorkspaceManager
import app.amber.feature.novelworkspace.NovelWorkspaceProjectRepository
import app.amber.feature.novel.workspace.NovelWorkspaceRestoreBridge
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
import org.junit.Assert.assertArrayEquals
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
import org.robolectric.shadows.ShadowContentResolver
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
        context.getSharedPreferences("amberagent_workspace", Context.MODE_PRIVATE).edit().clear().commit()
        testRoot = File(context.cacheDir, "sync-archive-integration-${System.nanoTime()}").apply { mkdirs() }
        testFileRoots()
            .forEach { File(context.filesDir, it).deleteRecursively() }
        File(context.cacheDir, "sync-restore-file-journal").deleteRecursively()

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
            context.getSharedPreferences("amberagent_workspace", Context.MODE_PRIVATE).edit().clear().commit()
            testFileRoots()
                .forEach { File(context.filesDir, it).deleteRecursively() }
            File(context.cacheDir, "sync-restore-file-journal").deleteRecursively()
        }
        // The production preference collectors intentionally terminate the process when their
        // app-wide scope fails, so this integration fixture must not cancel that scope in @After.
        kotlinx.coroutines.Dispatchers.resetMain()
    }

    @Test
    fun savedLiveCardsRoundTripAndConfigurationRestorePreservesLocalCards() = runBlocking {
        val dao = database.liveCardDao()
        val archivedCard = liveCard("archived")
        val localCard = liveCard("local").copy(id = 42L)
        for (mode in SyncMode.entries) {
            dao.insert(archivedCard)
            val archive = manager.createArchive(SyncExportRequest(mode = mode, passphrase = "test-passphrase"))
            val payload = readArchivePayload(archive, "test-passphrase")
            assertEquals(1, payloadManifest(payload).datasets.single { it.id == "table:live_card" }.recordCount)

            dao.deleteById(archivedCard.id)
            dao.insert(localCard)
            manager.restoreArchive(archive, restoreRequest(scope = RestoreScope.CONFIG_ONLY))
            assertEquals(listOf(localCard), dao.latestFlow(50).first())

            manager.restoreArchive(archive, restoreRequest().copy(preserveConversations = true, preserveGenMedia = true))
            assertEquals(listOf(archivedCard), dao.latestFlow(50).first())

            dao.deleteById(archivedCard.id)
            val emptyArchive = manager.createArchive(SyncExportRequest(mode = mode, passphrase = "test-passphrase"))
            dao.insert(localCard)
            manager.restoreArchive(emptyArchive, restoreRequest())
            assertTrue(dao.latestFlow(50).first().isEmpty())
        }
    }

    @Test
    fun legacyBackupWithoutLiveCardsPreservesLocalCards() = runBlocking {
        val attachment = File(context.filesDir, "${FileFolders.UPLOAD}/legacy-card-backup.txt").apply {
            parentFile!!.mkdirs()
            writeText("archived attachment")
        }
        val archive = manager.createArchive(SyncExportRequest(mode = SyncMode.STANDARD, passphrase = "test-passphrase"))
        val legacyArchive = rewritePayload(archive, "test-passphrase") { entries ->
            entries.remove("tables/live_card.jsonl")
            val manifest = payloadManifest(entries)
            entries["payload_manifest.json"] = JsonInstant.encodeToString(
                manifest.copy(datasets = manifest.datasets.filterNot { it.id == "table:live_card" }),
            ).toByteArray()
        }
        val localCard = liveCard("local")
        database.liveCardDao().insert(localCard)
        attachment.writeText("local attachment")

        manager.restoreArchive(legacyArchive, restoreRequest())

        assertEquals(listOf(localCard), database.liveCardDao().latestFlow(50).first())
        assertEquals("archived attachment", attachment.readText())
    }

    @Test
    fun declaredLiveCardsDatasetWithoutTableRejectsRestoreBeforeChangingLocalCards() = runBlocking {
        val archive = manager.createArchive(SyncExportRequest(mode = SyncMode.STANDARD, passphrase = "test-passphrase"))
        val truncatedArchive = rewritePayload(archive, "test-passphrase") { entries ->
            entries.remove("tables/live_card.jsonl")
            val manifest = payloadManifest(entries)
            entries["payload_manifest.json"] = JsonInstant.encodeToString(
                manifest.copy(datasets = manifest.datasets.filterNot { it.id == "table:live_card" } +
                    SyncDatasetSummary("table:live_card", recordCount = 0)),
            ).toByteArray()
        }
        val localCard = liveCard("local")
        database.liveCardDao().insert(localCard)

        val error = runCatching { manager.restoreArchive(truncatedArchive, restoreRequest()) }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertTrue(error!!.message.orEmpty().contains("tables/live_card.jsonl"))
        assertEquals(listOf(localCard), database.liveCardDao().latestFlow(50).first())
    }

    @Test
    fun fullBackupRoundTripsNativeNovelFilesAndReplacesOnlyItsExactRoot() = runBlocking {
        val archivedNovel = nativeNovelFiles()
        writeFiles(novelRoot(), archivedNovel)
        val upload = File(context.filesDir, "${FileFolders.UPLOAD}/attachment.txt").apply {
            parentFile!!.mkdirs()
            writeText("attachment")
        }
        val sibling = File(context.filesDir, "$AMBER_SIBLING_ROOT/keep.txt").apply {
            parentFile!!.mkdirs()
            writeText("unrelated Amber data")
        }
        val similarlyNamedSibling = File(context.filesDir, "$NOVEL_SIBLING_ROOT/keep.txt").apply {
            parentFile!!.mkdirs()
            writeText("not the novel root")
        }
        val archive = manager.createArchive(
            SyncExportRequest(mode = SyncMode.FULL, passphrase = "test-passphrase"),
        )
        val payload = readArchivePayload(archive, "test-passphrase")
        assertEquals(
            archivedNovel.keys.map { "files/${NovelWorkspaceProjectRepository.RELATIVE_ROOT}/$it" }.toSet() +
                "files/${FileFolders.UPLOAD}/attachment.txt",
            payload.keys.filter { it.startsWith("files/") }.toSet(),
        )
        val datasets = payloadManifest(payload).datasets
        val novelMarker = datasets.single { it.id == "novel-workspace" }
        assertEquals(0, novelMarker.recordCount)
        assertEquals(0L, novelMarker.byteCount)
        val filesSummary = datasets.single { it.id == "files" }
        assertEquals(archivedNovel.size + 1, filesSummary.recordCount)
        assertEquals(archivedNovel.values.sumOf { it.size.toLong() } + upload.length(), filesSummary.byteCount)

        novelRoot().deleteRecursively()
        writeFiles(novelRoot(), mapOf("stale-project/.amber/sessions.json" to "local stale".toByteArray()))
        sibling.writeText("keep current sibling")
        similarlyNamedSibling.writeText("keep current similarly named sibling")

        manager.restoreArchive(archive, restoreRequest())

        assertFiles(novelRoot(), archivedNovel)
        assertEquals("keep current sibling", sibling.readText())
        assertEquals("keep current similarly named sibling", similarlyNamedSibling.readText())
    }

    @Test
    fun emptyFullBackupMarksNovelDatasetAndClearsLocalNovelOnEverythingRestore() = runBlocking {
        val archive = manager.createArchive(
            SyncExportRequest(mode = SyncMode.FULL, passphrase = "test-passphrase"),
        )
        val payload = readArchivePayload(archive, "test-passphrase")
        assertTrue(payloadManifest(payload).datasets.any { it.id == "novel-workspace" })
        assertFalse(payload.keys.any { it.startsWith("files/${NovelWorkspaceProjectRepository.RELATIVE_ROOT}/") })
        val sibling = File(context.filesDir, "$AMBER_SIBLING_ROOT/keep.txt").apply {
            parentFile!!.mkdirs()
            writeText("keep sibling")
        }
        writeFiles(novelRoot(), nativeNovelFiles())

        manager.restoreArchive(archive, restoreRequest())

        assertFiles(novelRoot(), emptyMap())
        assertEquals("keep sibling", sibling.readText())
    }

    @Test
    fun standardBackupExcludesNativeNovelAndEverythingRestorePreservesLocalNovel() = runBlocking {
        writeFiles(novelRoot(), nativeNovelFiles())
        val archive = manager.createArchive(
            SyncExportRequest(mode = SyncMode.STANDARD, passphrase = "test-passphrase"),
        )
        val payload = readArchivePayload(archive, "test-passphrase")
        assertFalse(payloadManifest(payload).datasets.any { it.id == "novel-workspace" })
        assertFalse(payload.keys.any { it.startsWith("files/${NovelWorkspaceProjectRepository.RELATIVE_ROOT}/") })
        val localNovel = mapOf("local-project/.amber/sessions.json" to "new local conversation".toByteArray())
        novelRoot().deleteRecursively()
        writeFiles(novelRoot(), localNovel)

        manager.restoreArchive(archive, restoreRequest())

        assertFiles(novelRoot(), localNovel)
    }

    @Test
    fun configOnlyRestoreFromFullBackupPreservesLocalNovel() = runBlocking {
        writeFiles(novelRoot(), nativeNovelFiles())
        val archive = manager.createArchive(
            SyncExportRequest(mode = SyncMode.FULL, passphrase = "test-passphrase"),
        )
        val localNovel = mapOf("local-project/.amber/turn-outputs/run.json" to "local interrupted draft".toByteArray())
        novelRoot().deleteRecursively()
        writeFiles(novelRoot(), localNovel)

        manager.restoreArchive(archive, restoreRequest(scope = RestoreScope.CONFIG_ONLY))

        assertFiles(novelRoot(), localNovel)
    }

    @Test
    fun legacyFullBackupWithoutNovelMarkerIgnoresNativeEntriesAndPreservesLocalNovel() = runBlocking {
        writeFiles(novelRoot(), nativeNovelFiles())
        val currentArchive = manager.createArchive(
            SyncExportRequest(mode = SyncMode.FULL, passphrase = "test-passphrase"),
        )
        val legacyArchive = rewritePayload(currentArchive, "test-passphrase") { entries ->
            val manifest = payloadManifest(entries)
            entries["payload_manifest.json"] = JsonInstant.encodeToString(
                manifest.copy(datasets = manifest.datasets.filterNot { it.id == "novel-workspace" }),
            ).toByteArray()
        }
        // Retaining the native entries proves that marker absence controls adoption,
        // rather than merely testing an archive with no novel bytes to restore.
        assertTrue(readArchivePayload(legacyArchive, "test-passphrase").keys.any {
            it.startsWith("files/${NovelWorkspaceProjectRepository.RELATIVE_ROOT}/")
        })
        val localNovel = mapOf("local-project/.amber/settings.json" to "local model selection".toByteArray())
        novelRoot().deleteRecursively()
        writeFiles(novelRoot(), localNovel)

        manager.restoreArchive(legacyArchive, restoreRequest())

        assertFiles(novelRoot(), localNovel)
    }

    @Test
    fun interruptedFileRestoreRollsBackNestedNovelRootBeforeStandardRestore() = runBlocking {
        val archive = manager.createArchive(
            SyncExportRequest(mode = SyncMode.STANDARD, passphrase = "test-passphrase"),
        )
        val previousNovel = nativeNovelFiles()
        val journalRoot = File(context.cacheDir, "sync-restore-file-journal").apply { mkdirs() }
        writeFiles(File(journalRoot, "backup/${NovelWorkspaceProjectRepository.RELATIVE_ROOT}"), previousNovel)
        File(journalRoot, "roots").writeText(NovelWorkspaceProjectRepository.RELATIVE_ROOT)
        File(journalRoot, "token").writeText("interrupted-before-database-commit")
        File(journalRoot, "state").writeText("files_replaced")
        writeFiles(novelRoot(), mapOf("partial-project/project.md" to "uncommitted import".toByteArray()))
        val sibling = File(context.filesDir, "$AMBER_SIBLING_ROOT/keep.txt").apply {
            parentFile!!.mkdirs()
            writeText("keep sibling")
        }

        manager.restoreArchive(archive, restoreRequest())

        assertFiles(novelRoot(), previousNovel)
        assertEquals("keep sibling", sibling.readText())
        assertFalse(journalRoot.exists())
    }

    @Test
    fun nativeJournalRollbackBeforeConfigurationRestorePreservesAutomaticMigrationPolicy() = runBlocking {
        val archive = manager.createArchive(
            SyncExportRequest(mode = SyncMode.STANDARD, passphrase = "test-passphrase"),
        )
        val previousNovel = nativeNovelFiles()
        val journalRoot = File(context.cacheDir, "sync-restore-file-journal").apply { mkdirs() }
        writeFiles(File(journalRoot, "backup/${NovelWorkspaceProjectRepository.RELATIVE_ROOT}"), previousNovel)
        File(journalRoot, "roots").writeText(NovelWorkspaceProjectRepository.RELATIVE_ROOT)
        File(journalRoot, "token").writeText("uncommitted-native-import")
        File(journalRoot, "state").writeText("files_replaced")
        writeFiles(novelRoot(), mapOf("partial-project/project.md" to "uncommitted import".toByteArray()))
        val repository = NovelWorkspaceProjectRepository(novelRoot())

        NovelWorkspaceRestoreBridge(restoreWriteGate, novelRoot()) {}.use {
            manager.restoreArchive(archive, restoreRequest(scope = RestoreScope.CONFIG_ONLY))
        }

        assertTrue(repository.allowsAutomaticMigration())
        assertFiles(novelRoot(), previousNovel)
        assertFalse(journalRoot.exists())
    }

    @Test
    fun committedNativeJournalRecoveryBeforeConfigurationRestoreRecordsSnapshotAdoption() = runBlocking {
        val archive = manager.createArchive(
            SyncExportRequest(mode = SyncMode.STANDARD, passphrase = "test-passphrase"),
        )
        val importedNovel = nativeNovelFiles()
        writeFiles(novelRoot(), importedNovel)
        val journalRoot = File(context.cacheDir, "sync-restore-file-journal").apply { mkdirs() }
        File(journalRoot, "roots").writeText(NovelWorkspaceProjectRepository.RELATIVE_ROOT)
        File(journalRoot, "token").writeText("committed-native-import")
        File(journalRoot, "state").writeText("data_committed")
        database.openHelper.writableDatabase.execSQL(
            "CREATE TABLE IF NOT EXISTS amber_sync_restore_marker " +
                "(id INTEGER PRIMARY KEY CHECK (id = 1), token TEXT NOT NULL)",
        )
        database.openHelper.writableDatabase.execSQL(
            "INSERT OR REPLACE INTO amber_sync_restore_marker(id, token) VALUES (1, ?)",
            arrayOf("committed-native-import"),
        )
        val repository = NovelWorkspaceProjectRepository(novelRoot())

        NovelWorkspaceRestoreBridge(restoreWriteGate, novelRoot()) {}.use {
            manager.restoreArchive(archive, restoreRequest(scope = RestoreScope.CONFIG_ONLY))
        }

        assertFalse(repository.allowsAutomaticMigration())
        val marker = File(novelRoot(), ".native-restored")
        assertTrue(marker.isFile)
        assertFiles(novelRoot(), importedNovel + (".native-restored" to marker.readBytes()))
        assertFalse(journalRoot.exists())
    }

    @Test
    fun committedNonNovelJournalCannotAdoptPlannedNovelSnapshotWhenSettingsDecodeFails() = runBlocking {
        writeFiles(novelRoot(), nativeNovelFiles())
        val archive = manager.createArchive(
            SyncExportRequest(mode = SyncMode.FULL, passphrase = "test-passphrase"),
        )
        val invalidArchive = rewritePayload(archive, "test-passphrase") { entries ->
            entries["settings.json"] = "{invalid settings".toByteArray()
        }
        val localNovel = mapOf("local-project/project.md" to "Keep the original local novel".toByteArray())
        novelRoot().deleteRecursively()
        writeFiles(novelRoot(), localNovel)
        val previousUpload = File(context.filesDir, "${FileFolders.UPLOAD}/committed-before.txt").apply {
            parentFile!!.mkdirs()
            writeText("Previously committed upload")
        }
        val journalRoot = File(context.cacheDir, "sync-restore-file-journal").apply { mkdirs() }
        File(journalRoot, "roots").writeText(FileFolders.UPLOAD)
        File(journalRoot, "token").writeText("previous-upload-commit")
        File(journalRoot, "state").writeText("data_committed")
        database.openHelper.writableDatabase.execSQL(
            "CREATE TABLE IF NOT EXISTS amber_sync_restore_marker " +
                "(id INTEGER PRIMARY KEY CHECK (id = 1), token TEXT NOT NULL)",
        )
        database.openHelper.writableDatabase.execSQL(
            "INSERT OR REPLACE INTO amber_sync_restore_marker(id, token) VALUES (1, ?)",
            arrayOf("previous-upload-commit"),
        )
        val repository = NovelWorkspaceProjectRepository(novelRoot())

        val error = NovelWorkspaceRestoreBridge(restoreWriteGate, novelRoot()) {}.use {
            runCatching { manager.restoreArchive(invalidArchive, restoreRequest()) }.exceptionOrNull()
        }

        assertTrue(error is kotlinx.serialization.SerializationException)
        assertFiles(novelRoot(), localNovel)
        assertTrue(repository.allowsAutomaticMigration())
        assertEquals("Previously committed upload", previousUpload.readText())
        assertFalse(journalRoot.exists())
    }

    @Test
    fun pendingFileRestorePreservesRootsNotYetBackedUpIncludingMissingInitialState() = runBlocking {
        val archive = manager.createArchive(
            SyncExportRequest(mode = SyncMode.STANDARD, passphrase = "test-passphrase"),
        )
        val previousUpload = mapOf("old.txt" to "before restore".toByteArray())
        val untouchedNovel = nativeNovelFiles()
        for (state in listOf("pending", null)) {
            val journalRoot = File(context.cacheDir, "sync-restore-file-journal").apply { mkdirs() }
            writeFiles(File(journalRoot, "backup/${FileFolders.UPLOAD}"), previousUpload)
            File(journalRoot, "roots").writeText(
                "${FileFolders.UPLOAD}\n${NovelWorkspaceProjectRepository.RELATIVE_ROOT}",
            )
            File(journalRoot, "token").writeText("interrupted-during-root-backup")
            if (state != null) File(journalRoot, "state").writeText(state)
            File(context.filesDir, FileFolders.UPLOAD).deleteRecursively()
            writeFiles(novelRoot(), untouchedNovel)

            manager.restoreArchive(archive, restoreRequest(scope = RestoreScope.CONFIG_ONLY))

            assertFiles(File(context.filesDir, FileFolders.UPLOAD), previousUpload)
            assertFiles(novelRoot(), untouchedNovel)
            assertFalse(journalRoot.exists())
        }
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
    fun mixedPreservationKeepsLocalAndImportedChatImageFilesWithoutOverwritingLocalCollisions() = runBlocking {
        val imageRoot = File(context.filesDir, FileFolders.CHAT_IMAGES)
        writeFiles(imageRoot, mapOf("archive/image.png" to "archive".toByteArray(), "same/image.png" to "archive collision".toByteArray()))
        val archive = manager.createArchive(SyncExportRequest(mode = SyncMode.FULL, passphrase = "test-passphrase"))
        for ((preserveChats, preserveGallery) in listOf(true to false, false to true, false to false, true to true)) {
            imageRoot.deleteRecursively()
            writeFiles(imageRoot, mapOf("local/image.png" to "local".toByteArray(), "same/image.png" to "local collision".toByteArray()))
            manager.restoreArchive(archive, restoreRequest().copy(preserveConversations = preserveChats, preserveGenMedia = preserveGallery))
            val mixed = preserveChats != preserveGallery
            assertEquals("local images: chats=$preserveChats gallery=$preserveGallery", preserveChats || preserveGallery, File(imageRoot, "local/image.png").exists())
            assertEquals("imported images: chats=$preserveChats gallery=$preserveGallery", mixed || (!preserveChats && !preserveGallery), File(imageRoot, "archive/image.png").exists())
            assertEquals(if (preserveChats || preserveGallery) "local collision" else "archive collision", File(imageRoot, "same/image.png").readText())
        }
    }

    @Test
    fun fullBackupCarriesArtifactBodiesWithoutOtherWorkspaceFiles() = runBlocking {
        val workspace = WorkspaceManager(context)
        val repository = ArtifactRepository(database.artifactDao(), workspace, database.messageNodeDao(), database.conversationDao())
        val artifact = repository.saveDeepRead("backup-topic", "Stored result", "{\"body\":\"durable content\"}")
        File(workspace.mirrorDir, "private-unrelated.txt").apply { parentFile!!.mkdirs(); writeText("not part of artifact backup") }
        val archive = manager.createArchive(SyncExportRequest(mode = SyncMode.FULL, passphrase = "test-passphrase"))
        val payload = readArchivePayload(archive, "test-passphrase")
        assertFalse(payload.keys.any { it.contains("private-unrelated") })
        assertFalse(payload.values.any { it.decodeToString() == "not part of artifact backup" })
        workspace.mirrorDir.deleteRecursively()
        database.openHelper.writableDatabase.execSQL("DELETE FROM artifact")

        manager.restoreArchive(archive, restoreRequest())

        val restored = requireNotNull(repository.get(artifact.artifactId))
        assertEquals("{\"body\":\"durable content\"}", repository.readContent(restored))
        assertTrue(requireNotNull(repository.contentFile(restored)).isFile)
        assertFalse(File(workspace.mirrorDir, "private-unrelated.txt").exists())
    }

    @Test
    fun failedSettingsDecodeOrMissingTableLeavesNoPlaintextStageDirectories() = runBlocking {
        val archive = manager.createArchive(SyncExportRequest(mode = SyncMode.FULL, passphrase = "test-passphrase"))
        val syncCache = File(context.cacheDir, "sync")
        val originalStages = syncCache.listFiles().orEmpty().filter { it.name.startsWith("amber-restore-stage-") }.toSet()
        val malformedSettings = rewritePayload(archive, "test-passphrase") { it["settings.json"] = "invalid json".toByteArray() }
        val missingTable = rewritePayload(archive, "test-passphrase") { it.remove("tables/artifact.jsonl") }
        for (invalidArchive in listOf(malformedSettings, missingTable)) {
            val error = runCatching { manager.restoreArchive(invalidArchive, restoreRequest()) }.exceptionOrNull()
            assertTrue("invalid archive must fail before committing", error != null)
            val stages = syncCache.listFiles().orEmpty().filter { it.name.startsWith("amber-restore-stage-") }.toSet()
            assertEquals("failed validation leaked decrypted stage files", originalStages, stages)
        }
    }

    @Test
    fun restoredArtifactBodyOverridesOldSafContentWithoutWritingIntoTheUserWorkspace() = runBlocking {
        val provider = BackupArtifactTreeProvider(File(testRoot, "saf-files"))
        provider.attachInfo(context, ProviderInfo().apply { authority = "sync.artifact.backup" })
        ShadowContentResolver.registerProviderInternal("sync.artifact.backup", provider)
        val workspace = WorkspaceManager(context)
        workspace.setWorkspace(Uri.parse("content://sync.artifact.backup/tree/root"))
        val repository = ArtifactRepository(database.artifactDao(), workspace, database.messageNodeDao(), database.conversationDao())
        val saved = repository.saveDeepRead("saf-topic", "SAF result", "{\"value\":\"archive body\"}")
        workspace.writeText("unrelated.txt", "external user data")
        val archive = manager.createArchive(SyncExportRequest(mode = SyncMode.FULL, passphrase = "test-passphrase"))
        workspace.writeText(saved.contentLocator, "{\"value\":\"current external body\"}")
        workspace.mirrorDir.deleteRecursively()

        manager.restoreArchive(archive, restoreRequest())

        val restored = requireNotNull(repository.get(saved.artifactId))
        assertEquals("{\"value\":\"archive body\"}", repository.readContent(restored))
        assertEquals("{\"value\":\"current external body\"}", workspace.readText(saved.contentLocator))
        assertEquals("external user data", workspace.readText("unrelated.txt"))
        val edited = repository.saveDeepRead("saf-topic", "Edited imported result", "{\"value\":\"edited body\"}")
        assertEquals("{\"value\":\"edited body\"}", repository.readContent(edited))
        assertTrue(repository.delete(edited.artifactId))
        assertTrue(repository.contentFile(edited) == null)
    }

    @Test
    fun artifactOwnedBodyRollsBackWithDatabaseAndSurvivesSettingsOnlyAndLegacyRestores() = runBlocking {
        val workspace = WorkspaceManager(context)
        val repository = ArtifactRepository(database.artifactDao(), workspace, database.messageNodeDao(), database.conversationDao())
        val saved = repository.saveDeepRead("rollback-topic", "Result", "archive body")
        val archive = manager.createArchive(SyncExportRequest(mode = SyncMode.FULL, passphrase = "test-passphrase"))
        workspace.mirrorDir.deleteRecursively()
        manager.restoreArchive(archive, restoreRequest())
        repository.saveDeepRead("rollback-topic", "Local result", "local updated body")

        val badTable = rewritePayload(archive, "test-passphrase") { it["tables/artifact.jsonl"] = "not json".toByteArray() }
        assertTrue(runCatching { manager.restoreArchive(badTable, restoreRequest()) }.exceptionOrNull() != null)
        assertEquals("local updated body", repository.readContent(requireNotNull(repository.get(saved.artifactId))))
        assertFalse(File(context.cacheDir, "sync-restore-file-journal").exists())

        manager.restoreArchive(archive, restoreRequest(scope = RestoreScope.CONFIG_ONLY))
        assertEquals("local updated body", repository.readContent(requireNotNull(repository.get(saved.artifactId))))
        val legacy = rewritePayload(archive, "test-passphrase") { entries ->
            entries.keys.filter { it.startsWith("files/amberagent/artifact-content/") }.forEach(entries::remove)
            val manifest = payloadManifest(entries)
            entries["payload_manifest.json"] = JsonInstant.encodeToString(manifest.copy(datasets = manifest.datasets.filterNot { it.id == "artifact-content" })).toByteArray()
        }
        manager.restoreArchive(legacy, restoreRequest())
        assertEquals("local updated body", repository.readContent(requireNotNull(repository.get(saved.artifactId))))
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
        val archivedNovel = nativeNovelFiles()
        writeFiles(novelRoot(), archivedNovel)
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
        novelRoot().deleteRecursively()
        val localNovel = mapOf("old-local-project/project.md" to "before restore".toByteArray())
        writeFiles(novelRoot(), localNovel)

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
        assertFiles(novelRoot(), archivedNovel)
        assertFiles(File(journalRoot, "backup/${NovelWorkspaceProjectRepository.RELATIVE_ROOT}"), localNovel)
        val importedRows = database.managedFileDao().listByFolder(FileFolders.UPLOAD).first()
        assertEquals(listOf("upload/partial-commit.txt"), importedRows.map { it.relativePath })

        recreateFtsTables()
        val reverified = manager.verifyArchive(archiveFile(archive), request)
        withTimeout(10_000) { manager.applyRestore(reverified, request) }
        assertTrue(!journalRoot.exists())
        assertFiles(novelRoot(), archivedNovel)
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
    ): ByteArray = rewritePayload(archive, passphrase) { entries ->
        entries["secrets.json"] = secretsJson.toByteArray()
    }

    private fun rewritePayload(
        archive: ByteArray,
        passphrase: String,
        update: (MutableMap<String, ByteArray>) -> Unit,
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
        update(payloadEntries)
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

    private fun readArchivePayload(archive: ByteArray, passphrase: String): LinkedHashMap<String, ByteArray> {
        val entries = readZipEntries(archive)
        val manifest = JsonInstant.decodeFromString<SyncManifest>(
            entries.getValue(SYNC_MANIFEST_ENTRY).decodeToString(),
        )
        val payload = SyncCrypto(nativeEnabled = false).decrypt(
            entries.getValue(SYNC_PAYLOAD_ENTRY),
            passphrase,
            manifest,
        )
        return readZipEntries(payload)
    }

    private fun payloadManifest(entries: Map<String, ByteArray>): SyncPayloadManifest =
        JsonInstant.decodeFromString(entries.getValue("payload_manifest.json").decodeToString())

    private fun restoreRequest(scope: RestoreScope = RestoreScope.EVERYTHING) = SyncRestoreRequest(
        passphrase = "test-passphrase",
        scope = scope,
        preserveConversations = false,
        preserveGenMedia = false,
    )

    private fun novelRoot(): File = NovelWorkspaceProjectRepository.defaultRoot(context.filesDir)

    private fun nativeNovelFiles(): Map<String, ByteArray> = mapOf(
        "test-project/manifest.yaml" to "format: amber.novel.workspace".toByteArray(),
        "test-project/project.md" to "# 原始小说".toByteArray(),
        "test-project/branches/main/chapters/001.md" to "# 第一章\n完整正文".toByteArray(),
        "test-project/.amber/commits.json" to "{\"head\":\"committed-head\"}".toByteArray(),
        "test-project/.amber/sessions.json" to "{\"messages\":[\"作者讨论\"]}".toByteArray(),
        "test-project/.amber/proposals.json" to "[{\"baseHead\":\"committed-head\"}]".toByteArray(),
        "test-project/.amber/jobs/job.json" to "{\"state\":\"paused\"}".toByteArray(),
        "test-project/.amber/history/chapter-hash.md" to "历史章节正文".toByteArray(),
        "test-project/.amber/turn-outputs/run.json" to "{\"text\":\"尚未收录的候选稿\"}".toByteArray(),
        "test-project/.amber/settings.json" to "{\"reviewModelId\":\"review-model\"}".toByteArray(),
        "test-project/.amber/raw-state.bin" to byteArrayOf(0, 1, 2, 127, -1),
    )

    private fun writeFiles(root: File, files: Map<String, ByteArray>) {
        files.forEach { (path, bytes) ->
            File(root, path).apply {
                parentFile!!.mkdirs()
                writeBytes(bytes)
            }
        }
    }

    private fun assertFiles(root: File, expected: Map<String, ByteArray>) {
        val actualFiles = root.walkTopDown().filter { it.isFile }.associateBy {
            it.relativeTo(root).invariantSeparatorsPath
        }
        assertEquals(expected.keys, actualFiles.keys)
        expected.forEach { (path, bytes) -> assertArrayEquals(path, bytes, actualFiles.getValue(path).readBytes()) }
    }

    private fun testFileRoots() = listOf(
        FileFolders.UPLOAD,
        FileFolders.SKILLS,
        FileFolders.IMAGES,
        FileFolders.CHAT_IMAGES,
        NovelWorkspaceProjectRepository.RELATIVE_ROOT,
        AMBER_SIBLING_ROOT,
        NOVEL_SIBLING_ROOT,
        "amberagent/workspace-mirror",
        "amberagent/artifact-content",
    )

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

    private fun liveCard(title: String) = LiveCardEntity(
        id = 41L,
        packageName = "app.example.reader",
        appLabel = "Reader",
        title = title,
        actionLabel = "继续阅读",
        watching = "保存的阅读建议",
        keyPointsJson = "[\"关键点\"]",
        suggestionsJson = "[\"建议\"]",
        screenSignature = "saved-screen-$title",
        createdAt = 100L,
    )

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
        private const val AMBER_SIBLING_ROOT = "amberagent/sync-archive-test-sibling"
        private const val NOVEL_SIBLING_ROOT = "amberagent/novel-workspace-test-sibling"

        @JvmStatic
        @BeforeClass
        fun installTestKeyStore() = TestAndroidKeyStore.install()

        @JvmStatic
        @AfterClass
        fun restoreTestKeyStore() = TestAndroidKeyStore.restore()
    }
}
