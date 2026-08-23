package app.amber.feature.ui.pages.backup

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import app.amber.ai.provider.providers.google.GoogleGeminiAuthStore
import app.amber.ai.provider.providers.openai.OpenAICodexAuthStore
import app.amber.agent.data.db.AppDatabase
import app.amber.core.files.FileFolders
import app.amber.core.files.FilesManager
import app.amber.core.infra.AppScope
import app.amber.core.repository.FilesRepository
import app.amber.core.settings.Capability
import app.amber.core.settings.prefs.AgentPrefs
import app.amber.core.settings.prefs.AssistantPrefs
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
import app.amber.core.sync.core.SyncArchiveManager
import app.amber.core.sync.core.SyncCrypto
import app.amber.core.sync.core.SyncExportRequest
import app.amber.core.sync.core.SyncMode
import app.amber.core.sync.google.GoogleDriveAppDataClient
import app.amber.core.sync.google.GoogleDriveSyncRepository
import app.amber.core.sync.google.GoogleOAuthConfigGate
import app.amber.core.sync.local.LocalBackupRepository
import app.amber.core.sync.provider.LocalFolderSyncProvider
import app.amber.core.sync.provider.PersistedFolderStore
import app.amber.core.sync.provider.SyncSnapshot
import app.amber.core.sync.provider.SyncSnapshotManifest
import app.amber.core.sync.provider.WebDavSyncProvider
import app.amber.core.sync.provider.encodeSnapshotManifest
import app.amber.core.utils.JsonInstant
import app.amber.core.utils.UiState
import app.amber.feature.webmount.oauth.WebMountOAuthTokenStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * P7-02 两阶段恢复：verify 后取消（dismissVerifiedRestore）必须清理 App
 * 自己创建的归档临时副本 —— 本地导入的 cacheDir/sync-local 拷贝与
 * provider 下载的 cacheDir 拷贝，同时绝不删除用户原始选择的文件。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BackupVMRestoreCleanupTest {

    private lateinit var context: Context
    private lateinit var settingsStore: SettingsAggregator
    private lateinit var archiveManager: SyncArchiveManager
    private lateinit var localBackupRepository: LocalBackupRepository
    private lateinit var testRoot: File
    private lateinit var appScope: AppScope
    private lateinit var database: AppDatabase
    private val mainDispatcher = UnconfinedTestDispatcher()
    private val crypto = SyncCrypto(nativeEnabled = false)
    private val server = FakeWebDavServer()

    @Before
    fun setUp() = runBlocking {
        Dispatchers.setMain(mainDispatcher)
        context = RuntimeEnvironment.getApplication()
        testRoot = File(context.cacheDir, "backup-vm-cleanup-${System.nanoTime()}").apply { mkdirs() }

        appScope = AppScope()
        val dataStore = PreferenceDataStoreFactory.create {
            File(testRoot, "settings.preferences_pb")
        }
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
        settingsStore = SettingsAggregator(
            dataStore = dataStore,
            uiPrefs = UIPrefs(dataStore, appScope),
            searchPrefs = SearchPrefs(dataStore, appScope, secretStore),
            agentPrefs = AgentPrefs(dataStore, appScope),
            providerPrefs = ProviderPrefs(dataStore, appScope, secretStore),
            chatPrefs = ChatPrefs(dataStore, appScope),
            extensionPrefs = ExtensionPrefs(dataStore, appScope, secretStore),
            assistantPrefs = AssistantPrefs(dataStore, appScope, secretStore),
            scope = appScope,
            secretRedactor = secretRedactor,
        )
        withTimeout(5_000) { settingsStore.settingsFlow.first { !it.init } }
        settingsStore.update {
            it.copy(
                webDavConfig = it.webDavConfig.copy(
                    url = "https://dav.example.com",
                    username = "webdav-user",
                    password = "webdav-pass",
                    path = "amber_agent_backups",
                ),
            )
        }

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
        val filesManager = FilesManager(
            context = context,
            repository = FilesRepository(database.managedFileDao()),
            appScope = appScope,
        )
        archiveManager = SyncArchiveManager(
            context = context,
            settingsStore = settingsStore,
            database = database,
            messageFtsManager = app.amber.agent.data.db.fts.MessageFtsManager(database),
            filesManager = filesManager,
            webMountOAuthTokenStore = WebMountOAuthTokenStore(context),
            openAICodexAuthStore = OpenAICodexAuthStore(context),
            googleGeminiAuthStore = GoogleGeminiAuthStore(context),
            json = JsonInstant,
            nativePathPrefs = nativePathPrefs,
            secretRedactor = secretRedactor,
            deviceBoundBackupKey = DeviceBoundBackupKey(secretStore),
        )
        localBackupRepository = LocalBackupRepository(context, archiveManager)
    }

    @After
    fun tearDown() {
        if (::database.isInitialized) database.close()
        if (::testRoot.isInitialized) testRoot.deleteRecursively()
        Dispatchers.resetMain()
    }

    private fun buildViewModel(): BackupVM {
        val dataStore = PreferenceDataStoreFactory.create {
            File(testRoot, "flags.preferences_pb")
        }
        val capabilityFlags = app.amber.core.settings.CapabilityFlags(dataStore)
        runBlocking { capabilityFlags.setEnabled(Capability.SyncProviderV2, true) }
        val engine = MockEngine { request -> server.handle(this, request) }
        return BackupVM(
            settingsStore = settingsStore,
            localBackupRepository = localBackupRepository,
            googleDriveSyncRepository = GoogleDriveSyncRepository(
                context = context,
                driveClient = GoogleDriveAppDataClient(OkHttpClient(), JsonInstant),
                archiveManager = archiveManager,
            ),
            googleOAuthConfigGate = GoogleOAuthConfigGate(context),
            archiveManager = archiveManager,
            webDavSyncProvider = WebDavSyncProvider(
                context = context,
                settingsStore = settingsStore,
                archiveManager = archiveManager,
                json = JsonInstant,
                httpClient = HttpClient(engine),
            ),
            localFolderSyncProvider = LocalFolderSyncProvider(
                context = context,
                archiveManager = archiveManager,
                folderStore = PersistedFolderStore(context),
                json = JsonInstant,
            ),
            persistedFolderStore = PersistedFolderStore(context),
            capabilityFlags = capabilityFlags,
        )
    }

    @Test
    fun `verify then dismiss deletes local temp copy but keeps user file`() = runBlocking {
        val vm = buildViewModel()
        val archiveBytes = archiveManager.createArchive(
            SyncExportRequest(mode = SyncMode.FULL, passphrase = "test-passphrase")
        )
        // 用户原始选择的文件（不在 cacheDir/sync-local 下）。
        val userFile = File(testRoot, "user-selected.amberbackup").apply { writeBytes(archiveBytes) }

        vm.inspectImport(Uri.fromFile(userFile))
        vm.restorePendingLocal("test-passphrase")
        awaitUntil({ vm.pendingVerifiedRestore.value != null }, "本地验证未完成")

        val verification = vm.pendingVerifiedRestore.value!!
        val tempCopy = verification.archiveFile
        assertTrue("verify 必须在 cacheDir/sync-local 创建副本", localBackupRepository.isOwnedTempCopy(tempCopy))
        assertTrue("副本应存在", tempCopy.exists())

        vm.dismissVerifiedRestore()

        assertFalse("verify 后取消必须删除本地临时副本", tempCopy.exists())
        assertTrue("用户原始选择的文件绝不能删除", userFile.exists())
        assertNull(vm.pendingVerifiedRestore.value)
        // cacheDir/sync-local 里不留任何 amber-import-* 残留。
        val syncLocalDir = File(context.cacheDir, "sync-local")
        assertTrue(
            "sync-local 不应残留导入副本",
            syncLocalDir.listFiles().orEmpty().none { it.name.startsWith("amber-import-") },
        )
    }

    @Test
    fun `verify then dismiss deletes provider download copy`() = runBlocking {
        val vm = buildViewModel()
        awaitUntil({ vm.providerV2Enabled.value }, "SyncProviderV2 未启用")

        val archiveBytes = archiveManager.createArchive(
            SyncExportRequest(mode = SyncMode.FULL, passphrase = "test-passphrase")
        )
        server.files["amber_agent_backups/snap-1.amberbackup"] = archiveBytes
        server.files["amber_agent_backups/snap-1.snapshot.json"] = encodeSnapshotManifest(
            JsonInstant,
            SyncSnapshotManifest(
                snapshotId = "snap-1",
                createdAt = 42L,
                mode = SyncMode.FULL,
                sizeBytes = archiveBytes.size.toLong(),
                contentSha256 = crypto.sha256(archiveBytes),
            ),
        ).toByteArray()

        vm.downloadWebDavSnapshot(
            SyncSnapshot(
                providerId = "webdav",
                snapshotId = "snap-1",
                name = "snap-1.amberbackup",
                manifest = SyncSnapshotManifest(snapshotId = "snap-1", createdAt = 42L, mode = SyncMode.FULL),
            )
        )
        awaitUntil({ vm.pendingImportPreview.value != null }, "WebDAV 下载未完成")

        vm.restorePendingProvider("test-passphrase")
        awaitUntil({ vm.pendingVerifiedRestore.value != null }, "provider 验证未完成")

        val downloaded = vm.pendingVerifiedRestore.value!!.archiveFile
        assertTrue("下载副本应存在", downloaded.exists())
        assertTrue(
            "下载副本应在 App 缓存目录，实际：${downloaded.absolutePath}",
            downloaded.absolutePath.contains(File.separator + "sync-webdav" + File.separator),
        )

        vm.dismissVerifiedRestore()

        assertFalse("取消后必须删除 provider 下载副本", downloaded.exists())
        assertNull(vm.pendingVerifiedRestore.value)
    }

    private suspend fun awaitUntil(condition: () -> Boolean, what: String) {
        withTimeout(30_000) {
            while (!condition()) {
                delay(25)
            }
        }
    }

    /** 内存版假 WebDAV 服务器：GET 足够驱动下载 + sidecar 预览。 */
    private class FakeWebDavServer {
        val files = mutableMapOf<String, ByteArray>()

        suspend fun handle(scope: MockRequestHandleScope, request: HttpRequestData): HttpResponseData {
            val path = request.url.encodedPath.trimStart('/')
            return when (request.method) {
                HttpMethod("PROPFIND") -> scope.respond(emptyMultistatus(), HttpStatusCode.MultiStatus)
                HttpMethod.Get -> {
                    val body = files[path]
                    if (body == null) {
                        scope.respond("", HttpStatusCode.NotFound)
                    } else {
                        scope.respond(body, HttpStatusCode.OK)
                    }
                }
                else -> scope.respond("", HttpStatusCode.MethodNotAllowed)
            }
        }

        private fun emptyMultistatus(): String =
            """<?xml version="1.0" encoding="utf-8"?>
            <D:multistatus xmlns:D="DAV:">
              <D:response>
                <D:href>/amber_agent_backups/</D:href>
                <D:propstat>
                  <D:prop>
                    <D:displayname>amber_agent_backups</D:displayname>
                    <D:resourcetype><D:collection/></D:resourcetype>
                  </D:prop>
                  <D:status>HTTP/1.1 200 OK</D:status>
                </D:propstat>
              </D:response>
            </D:multistatus>""".trimIndent()
    }
}
