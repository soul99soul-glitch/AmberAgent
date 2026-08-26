package app.amber.core.sync.provider

import android.app.Application
import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import app.amber.ai.provider.providers.google.GoogleGeminiAuthStore
import app.amber.ai.provider.providers.openai.OpenAICodexAuthStore
import app.amber.agent.data.db.AppDatabase
import app.amber.core.files.FileFolders
import app.amber.core.files.FilesManager
import app.amber.core.infra.AppScope
import app.amber.core.repository.FilesRepository
import app.amber.core.settings.WebDavConfig
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
import app.amber.core.sync.core.SyncArchiveManager
import app.amber.core.sync.core.DeviceBoundBackupKey
import app.amber.core.sync.core.SyncCrypto
import app.amber.core.sync.core.SyncExportRequest
import app.amber.core.sync.core.SyncMode
import app.amber.core.utils.JsonInstant
import app.amber.feature.webmount.oauth.WebMountOAuthTokenStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.content.OutgoingContent
import io.ktor.utils.io.jvm.javaio.toInputStream
import java.io.File
import kotlinx.coroutines.Dispatchers
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
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * P7-01 验收：WebDAV 协议请求构造（fake transport）+ 临时名上传流程 +
 * digest 验证失败拒绝恢复。
 *
 * 真实 WebDAV 服务验证做不了（无网络/凭据）——用 ktor MockEngine 充当
 * 假传输层验证协议逻辑（PROPFIND/GET/PUT/MOVE/DELETE 的请求构造与解析）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WebDavSyncProviderTest {

    private lateinit var context: Context
    private lateinit var settingsStore: SettingsAggregator
    private lateinit var archiveManager: SyncArchiveManager
    private lateinit var testRoot: File
    private lateinit var appScope: AppScope
    private lateinit var database: AppDatabase
    private val server = FakeWebDavServer()
    private val mainDispatcher = UnconfinedTestDispatcher()

    private val crypto = SyncCrypto()

    @Before
    fun setUp() = runBlocking {
        Dispatchers.setMain(mainDispatcher)
        context = RuntimeEnvironment.getApplication()
        testRoot = File(context.cacheDir, "webdav-provider-${System.nanoTime()}").apply { mkdirs() }

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
            chatPrefs = ChatPrefs(dataStore, appScope, secretStore),
            extensionPrefs = ExtensionPrefs(dataStore, appScope, secretStore),
            scope = appScope,
            secretRedactor = secretRedactor,
        )
        withTimeout(5_000) { settingsStore.settingsFlow.first { !it.init } }
        settingsStore.update {
            it.copy(
                webDavConfig = WebDavConfig(
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
    }

    @After
    fun tearDown() {
        if (::database.isInitialized) database.close()
        if (::testRoot.isInitialized) testRoot.deleteRecursively()
        Dispatchers.resetMain()
    }

    private fun provider(): WebDavSyncProvider {
        val engine = MockEngine { request -> server.handle(this, request) }
        val client = HttpClient(engine)
        return WebDavSyncProvider(
            context = context,
            settingsStore = settingsStore,
            archiveManager = archiveManager,
            json = JsonInstant,
            httpClient = client,
        )
    }

    private fun uploadRequest(policy: UploadConflictPolicy = UploadConflictPolicy.CREATE_COPY) =
        SyncProviderUploadRequest(mode = SyncMode.FULL, passphrase = "test-passphrase", conflictPolicy = policy)

    @Test
    fun `listSnapshots issues propfind with depth and basic auth and parses sidecars`() = runBlocking {
        val sidecar = encodeSnapshotManifest(
            JsonInstant,
            SyncSnapshotManifest(
                snapshotId = "snap-1",
                createdAt = 1750000000000L,
                appVersionName = "1.8.16",
                appVersionCode = 816,
                deviceLabel = "OPPO PMA110",
                mode = SyncMode.FULL,
                sizeBytes = 4242L,
                contentSha256 = "ab",
                includedDomains = setOf("settings", "secrets", "tables", "files"),
            ),
        )
        server.files["amber_agent_backups/snap-1.snapshot.json"] = sidecar.toByteArray()
        server.files["amber_agent_backups/snap-1.amberbackup"] = ByteArray(4242) { 1 }

        val snapshots = provider().listSnapshots()

        assertEquals(1, snapshots.size)
        val snapshot = snapshots[0]
        assertEquals("snap-1", snapshot.snapshotId)
        assertEquals("snap-1.amberbackup", snapshot.name)
        assertEquals("OPPO PMA110", snapshot.manifest.deviceLabel)
        assertEquals(4242L, snapshot.sizeBytes)
        assertEquals("webdav", snapshot.providerId)

        // ensureCollectionExists 先发 depth=0，list 再发 depth=1。
        val propfinds = server.requests.filter { it.method == HttpMethod("PROPFIND") }
        assertEquals(2, propfinds.size)
        val listPropfind = propfinds.last()
        assertEquals("1", listPropfind.headers["Depth"])
        val auth = listPropfind.headers[HttpHeaders.Authorization]
        assertNotNull("PROPFIND 必须带 basic auth", auth)
        assertTrue(auth!!.startsWith("Basic "))
        assertEquals("webdav-user", decodeBasicAuth(auth).first)
    }

    @Test
    fun `previewSnapshot reads sidecar without downloading archive`() = runBlocking {
        server.files["amber_agent_backups/snap-1.snapshot.json"] = encodeSnapshotManifest(
            JsonInstant,
            SyncSnapshotManifest(snapshotId = "snap-1", createdAt = 42L, mode = SyncMode.STANDARD),
        ).toByteArray()

        val manifest = provider().previewSnapshot("snap-1")

        assertEquals("snap-1", manifest.snapshotId)
        assertEquals(42L, manifest.createdAt)
        assertEquals(SyncMode.STANDARD, manifest.mode)
        assertTrue(server.requests.none { it.method == HttpMethod.Get && it.url.encodedPath.endsWith(".amberbackup") })
    }

    @Test
    fun `uploadSnapshot writes temp names then publishes via move`() = runBlocking {
        val result = provider().uploadSnapshot(uploadRequest())

        // 返回快照元数据齐全。
        assertEquals("webdav", result.providerId)
        assertTrue(result.snapshotId.startsWith("snap-"))
        assertTrue(result.manifest.createdAt > 0L)
        assertTrue(result.manifest.contentSha256.isNotBlank())
        assertTrue(result.sizeBytes > 0L)
        assertEquals(SyncMode.FULL, result.manifest.mode)
        assertTrue("secrets" in result.manifest.includedDomains)

        // 上传路径先写临时名，再 MOVE publish 为最终名。
        val puts = server.requests.filter { it.method == HttpMethod.Put }
        assertEquals(2, puts.size)
        assertTrue(puts.all { it.url.encodedPath.endsWith(".tmp") })

        val moves = server.requests.filter { it.method == HttpMethod("MOVE") }
        assertEquals(2, moves.size)
        moves.forEach { move ->
            val destination = move.headers["Destination"]
            assertNotNull(destination)
            assertTrue(destination!!.endsWith(snapshotArchiveName(result.snapshotId)) ||
                destination.endsWith(snapshotSidecarName(result.snapshotId)))
        }

        // 服务器上只留最终名，没有半成品临时文件。
        assertNotNull(server.files["amber_agent_backups/${snapshotArchiveName(result.snapshotId)}"])
        assertNotNull(server.files["amber_agent_backups/${snapshotSidecarName(result.snapshotId)}"])
        assertTrue(server.files.keys.none { it.endsWith(".tmp") })

        // sidecar 内容可解析且 digest 与数据体一致。
        val storedSidecar = server.files.getValue("amber_agent_backups/${snapshotSidecarName(result.snapshotId)}")
            .decodeToString()
        val decoded = decodeSnapshotManifest(JsonInstant, storedSidecar)
        assertEquals(result.snapshotId, decoded.snapshotId)
        val storedArchive = server.files.getValue("amber_agent_backups/${snapshotArchiveName(result.snapshotId)}")
        assertEquals(crypto.sha256(storedArchive), decoded.contentSha256)
        assertEquals(storedArchive.size.toLong(), decoded.sizeBytes)
    }

    @Test
    fun `uploadSnapshot with overwrite supersedes same-device snapshot`() = runBlocking {
        // 本机 deviceId 为空 → 归档 manifest 记录 "local"（见 createArchiveFile 的 ifBlank）。
        server.files["amber_agent_backups/snap-old.snapshot.json"] = encodeSnapshotManifest(
            JsonInstant,
            SyncSnapshotManifest(snapshotId = "snap-old", createdAt = 1L, deviceId = "local"),
        ).toByteArray()
        server.files["amber_agent_backups/snap-old.amberbackup"] = ByteArray(16) { 0 }

        val result = provider().uploadSnapshot(uploadRequest(policy = UploadConflictPolicy.OVERWRITE))

        // 新快照发布后旧快照被删除。
        assertFalse(server.files.containsKey("amber_agent_backups/snap-old.amberbackup"))
        assertFalse(server.files.containsKey("amber_agent_backups/snap-old.snapshot.json"))
        assertTrue(server.requests.any { it.method == HttpMethod.Delete })
        assertTrue(server.files.containsKey("amber_agent_backups/${snapshotArchiveName(result.snapshotId)}"))
    }

    @Test
    fun `uploadSnapshot rolls back final-name archive when sidecar publish fails`() = runBlocking {
        server.failSidecarPublish = true

        val error = runCatching { provider().uploadSnapshot(uploadRequest()) }.exceptionOrNull()

        // sidecar 发布失败必须让上传整体失败。
        assertNotNull("sidecar 发布失败时上传必须抛错", error)

        // 归档已 move 为最终名 → 回滚删除，不留无 sidecar 的孤儿归档。
        assertTrue(
            "最终名归档必须被回滚删除，实际剩余：${server.files.keys}",
            server.files.keys.none { it.endsWith(SNAPSHOT_ARCHIVE_SUFFIX) },
        )
        // 临时文件（archive/sidecar 的 .tmp）也被清理。
        assertTrue(
            "临时文件必须被清理，实际剩余：${server.files.keys}",
            server.files.keys.none { it.endsWith(".tmp") },
        )
        // 回滚走的是最终名 DELETE（不是只删临时名）。
        val deletedFinalArchive = server.requests
            .filter { it.method == HttpMethod.Delete }
            .any { it.url.encodedPath.endsWith(SNAPSHOT_ARCHIVE_SUFFIX) }
        assertTrue("必须 DELETE 最终名归档", deletedFinalArchive)
    }

    @Test
    fun `downloadSnapshot verifies digest and archive header on intact archive`() = runBlocking {
        val archiveBytes = archiveManager.createArchive(
            SyncExportRequest(mode = SyncMode.FULL, passphrase = "test-passphrase")
        )
        val sidecar = SyncSnapshotManifest(
            snapshotId = "snap-1",
            createdAt = 1750000000000L,
            mode = SyncMode.FULL,
            sizeBytes = archiveBytes.size.toLong(),
            contentSha256 = crypto.sha256(archiveBytes),
        )
        server.files["amber_agent_backups/snap-1.snapshot.json"] = encodeSnapshotManifest(JsonInstant, sidecar).toByteArray()
        server.files["amber_agent_backups/snap-1.amberbackup"] = archiveBytes

        val downloaded = provider().downloadSnapshot("snap-1")

        assertTrue(downloaded.exists())
        assertEquals(crypto.sha256(archiveBytes), crypto.sha256(downloaded))
        downloaded.delete()
        Unit
    }

    @Test
    fun `downloadSnapshot rejects corrupted archive with digest mismatch`() = runBlocking {
        val goodBytes = "good-archive-bytes".toByteArray()
        server.files["amber_agent_backups/snap-1.snapshot.json"] = encodeSnapshotManifest(
            JsonInstant,
            SyncSnapshotManifest(
                snapshotId = "snap-1",
                createdAt = 1L,
                sizeBytes = 100L,
                contentSha256 = crypto.sha256(goodBytes),
            ),
        ).toByteArray()
        server.files["amber_agent_backups/snap-1.amberbackup"] = "corrupted-on-the-wire".toByteArray()

        try {
            provider().downloadSnapshot("snap-1")
            fail("digest 不匹配时必须拒绝恢复")
        } catch (error: Throwable) {
            assertTrue("期望 digest 相关错误，实际：${error.message}", error.message.orEmpty().contains("digest"))
        }
    }

    @Test
    fun `deleteSnapshot removes archive and sidecar`() = runBlocking {
        server.files["amber_agent_backups/snap-1.snapshot.json"] = ByteArray(4) { 0 }
        server.files["amber_agent_backups/snap-1.amberbackup"] = ByteArray(4) { 0 }

        provider().deleteSnapshot("snap-1")

        assertTrue(server.files.isEmpty())
        val deletes = server.requests.filter { it.method == HttpMethod.Delete }
        assertEquals(2, deletes.size)
    }

    private fun decodeBasicAuth(header: String): Pair<String, String> {
        val decoded = java.util.Base64.getDecoder()
            .decode(header.removePrefix("Basic ").trim())
            .toString(Charsets.UTF_8)
        val (user, pass) = decoded.split(":", limit = 2)
        return user to pass
    }

    /** 内存版假 WebDAV 服务器：记录请求、按文件路径存数据体。 */
    class FakeWebDavServer {
        val files = mutableMapOf<String, ByteArray>()
        val requests = mutableListOf<HttpRequestData>()
        /** true 时 sidecar 的 MOVE publish 以 500 失败（归档已发布、sidecar 未发布）。 */
        var failSidecarPublish = false

        suspend fun handle(scope: MockRequestHandleScope, request: HttpRequestData): HttpResponseData {
            requests += request
            val path = request.url.encodedPath.trimStart('/')
            return when (request.method) {
                HttpMethod("PROPFIND") -> scope.respond(multistatus(), HttpStatusCode.MultiStatus)
                HttpMethod.Get -> {
                    val body = files[path]
                    if (body == null) {
                        scope.respond("", HttpStatusCode.NotFound)
                    } else {
                        scope.respond(body, HttpStatusCode.OK)
                    }
                }
                HttpMethod.Put -> {
                    files[path] = request.bodyBytes()
                    scope.respond("", HttpStatusCode.Created)
                }
                HttpMethod("MOVE") -> {
                    val destination = request.headers["Destination"]?.let { Url(it).encodedPath.trimStart('/') }
                    if (failSidecarPublish && destination != null && destination.endsWith(SNAPSHOT_SIDECAR_SUFFIX)) {
                        // 模拟 sidecar 发布失败：不移动文件，返回 500。
                        scope.respond("", HttpStatusCode.InternalServerError)
                    } else {
                        val source = files.remove(path)
                        if (destination != null && source != null) {
                            files[destination] = source
                            scope.respond("", HttpStatusCode.Created)
                        } else {
                            scope.respond("", HttpStatusCode.NotFound)
                        }
                    }
                }
                HttpMethod.Delete -> {
                    files.remove(path)
                    scope.respond("", HttpStatusCode.NoContent)
                }
                else -> scope.respond("", HttpStatusCode.MethodNotAllowed)
            }
        }

        private fun multistatus(): String {
            val entries = files.keys
                .filter { it.endsWith(SNAPSHOT_SIDECAR_SUFFIX) }
                .joinToString("\n") { name ->
                    val body = files.getValue(name)
                    """
                    <D:response>
                      <D:href>/$name</D:href>
                      <D:propstat>
                        <D:prop>
                          <D:displayname>${name.substringAfterLast('/')}</D:displayname>
                          <D:getcontentlength>${body.size}</D:getcontentlength>
                          <D:getlastmodified>Tue, 15 Nov 1994 08:12:31 GMT</D:getlastmodified>
                          <D:resourcetype/>
                        </D:prop>
                        <D:status>HTTP/1.1 200 OK</D:status>
                      </D:propstat>
                    </D:response>
                    """.trimIndent()
                }
            return """<?xml version="1.0" encoding="utf-8"?>
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
                  $entries
                </D:multistatus>
            """.trimIndent()
        }

        private suspend fun HttpRequestData.bodyBytes(): ByteArray =
            when (val body = this.body) {
                is OutgoingContent.ByteArrayContent -> body.bytes()
                is OutgoingContent.ReadChannelContent -> body.readFrom().toInputStream().readBytes()
                else -> ByteArray(0)
            }
    }
}
