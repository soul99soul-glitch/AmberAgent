package app.amber.agent.data.sync

import android.app.Application
import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import app.amber.ai.provider.providers.google.GoogleGeminiAuthStore
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
import app.amber.core.sync.core.SyncArchiveManager
import app.amber.core.sync.core.SyncExportRequest
import app.amber.core.sync.core.SyncMode
import app.amber.core.sync.core.SyncRestoreRequest
import app.amber.core.utils.JsonInstant
import app.amber.feature.webmount.oauth.WebMountOAuthTokenStore
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SyncArchiveManagerIntegrationTest {
    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var appScope: AppScope
    private lateinit var manager: SyncArchiveManager
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
        database.openHelper.writableDatabase.execSQL(
            """
            CREATE TABLE IF NOT EXISTS conversation_title_fts(
                title TEXT,
                conversation_id TEXT,
                update_at TEXT
            )
            """.trimIndent()
        )
        val filesManager = FilesManager(
            context = context,
            repository = FilesRepository(database.managedFileDao()),
            appScope = appScope,
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

    private fun managedFile(name: String, size: Long) = ManagedFileEntity(
        folder = FileFolders.UPLOAD,
        relativePath = "${FileFolders.UPLOAD}/$name",
        displayName = name,
        mimeType = "text/plain",
        sizeBytes = size,
        createdAt = 1L,
        updatedAt = 1L,
    )
}
