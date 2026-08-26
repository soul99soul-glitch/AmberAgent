package app.amber.core.sync.provider

import android.app.Application
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import app.amber.ai.provider.providers.google.GoogleGeminiAuthStore
import app.amber.ai.provider.providers.openai.OpenAICodexAuthStore
import app.amber.agent.data.db.AppDatabase
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
import app.amber.core.sync.core.SyncArchiveManager
import app.amber.core.sync.core.SyncCrypto
import app.amber.core.sync.core.SyncExportRequest
import app.amber.core.sync.core.SyncMode
import app.amber.core.utils.JsonInstant
import app.amber.feature.webmount.oauth.WebMountOAuthTokenStore
import java.io.File
import java.io.FileNotFoundException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

/**
 * P7-01 本地文件夹上传：用内存版 SAF DocumentsProvider（注册到
 * ShadowContentResolver）驱动真实 ContentResolver 路径，验证临时名写
 * 入 → renameDocument publish → sidecar 发布失败时回滚最终名归档。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class LocalFolderSyncProviderUploadTest {

    private lateinit var context: Context
    private lateinit var settingsStore: SettingsAggregator
    private lateinit var archiveManager: SyncArchiveManager
    private lateinit var testRoot: File
    private lateinit var appScope: AppScope
    private lateinit var database: AppDatabase
    private lateinit var fakeFolder: FakeSyncFolderProvider
    private val mainDispatcher = UnconfinedTestDispatcher()

    private val treeUri = Uri.parse("content://test.documents/tree/root%3A")

    @Before
    fun setUp() = runBlocking {
        Dispatchers.setMain(mainDispatcher)
        context = RuntimeEnvironment.getApplication()
        testRoot = File(context.cacheDir, "local-folder-upload-${System.nanoTime()}").apply { mkdirs() }

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

        fakeFolder = FakeSyncFolderProvider()
        // registerProviderInternal 不负责 attach：手动挂上 ProviderInfo，
        // 否则 ContentProvider.validateIncomingAuthority 会因 authority 为 null 拒绝。
        fakeFolder.attachInfo(
            context,
            android.content.pm.ProviderInfo().apply { authority = "test.documents" },
        )
        ShadowContentResolver.registerProviderInternal("test.documents", fakeFolder)
    }

    @After
    fun tearDown() {
        if (::database.isInitialized) database.close()
        if (::testRoot.isInitialized) testRoot.deleteRecursively()
        Dispatchers.resetMain()
    }

    private fun provider(): LocalFolderSyncProvider {
        val folderStore = PersistedFolderStore(context)
        folderStore.save(treeUri.toString(), "fake folder")
        return LocalFolderSyncProvider(
            context = context,
            archiveManager = archiveManager,
            folderStore = folderStore,
            json = JsonInstant,
        )
    }

    private fun uploadRequest(policy: UploadConflictPolicy = UploadConflictPolicy.CREATE_COPY) =
        SyncProviderUploadRequest(mode = SyncMode.FULL, passphrase = "test-passphrase", conflictPolicy = policy)

    @Test
    fun `uploadSnapshot writes temp docs then publishes via rename`() = runBlocking {
        val result = provider().uploadSnapshot(uploadRequest())

        // 返回快照元数据齐全。
        assertTrue(result.snapshotId.startsWith("snap-"))
        assertTrue(result.manifest.contentSha256.isNotBlank())

        // 文件夹里只留最终名归档 + sidecar，没有临时名。
        val names = fakeFolder.docs.values.map { it.displayName }
        assertEquals(listOf(snapshotArchiveName(result.snapshotId), snapshotSidecarName(result.snapshotId)), names)
        assertTrue(names.none { it.endsWith(".tmp") })
    }

    @Test
    fun `uploadSnapshot rolls back final-name archive when sidecar publish fails`() = runBlocking {
        fakeFolder.failSidecarRename = true

        val error = runCatching { provider().uploadSnapshot(uploadRequest()) }.exceptionOrNull()

        // sidecar 发布失败必须让上传整体失败。
        assertNotNull("sidecar 发布失败时上传必须抛错", error)

        // 归档已 rename 为最终名 → 回滚删除，不留无 sidecar 的孤儿归档。
        assertTrue(
            "最终名归档必须被回滚删除，实际剩余：${fakeFolder.docs.values.map { it.displayName }}",
            fakeFolder.docs.isEmpty(),
        )
    }

    /**
     * 内存版 SAF provider：document 树 root: 下以文档 id 存 displayName/mime，
     * 每个文档一个临时文件承载数据体。按 API 34 的传输协议实现
     * （create/rename 走 provider.call，query/delete 走 ContentResolver），
     * 并支持按需注入 renameDocument 失败。
     */
    class FakeSyncFolderProvider : android.content.ContentProvider() {
        data class Doc(val id: String, var displayName: String, val mimeType: String)

        val docs = LinkedHashMap<String, Doc>()
        /** true 时把文档改名为 *.snapshot.json 抛 FileNotFoundException。 */
        var failSidecarRename = false
        private val backingDir = File.createTempFile("fake-saf-", "").apply { delete(); mkdirs() }
        private var nextId = 1
        private val backingFiles = mutableMapOf<String, File>()
        private val treeUri = Uri.parse("content://test.documents/tree/root%3A")

        override fun onCreate(): Boolean = true

        override fun getType(uri: Uri): String? = null

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor {
            val columns = projection ?: DEFAULT_COLUMNS
            val cursor = MatrixCursor(columns)
            if (isChildrenUri(uri)) {
                docs.values.forEach { doc -> cursor.addRow(rowFor(doc, columns)) }
            } else {
                docs[documentIdFrom(uri)]?.let { doc -> cursor.addRow(rowFor(doc, columns)) }
            }
            return cursor
        }

        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            val docId = documentIdFrom(uri)
            docs[docId] ?: throw FileNotFoundException(docId)
            val file = backingFiles.getOrPut(docId) {
                File(backingDir, "doc-$docId.bin").apply { createNewFile() }
            }
            return ParcelFileDescriptor.open(
                file,
                ParcelFileDescriptor.MODE_READ_WRITE or
                    ParcelFileDescriptor.MODE_CREATE or
                    ParcelFileDescriptor.MODE_TRUNCATE,
            )
        }

        override fun call(method: String, arg: String?, extras: android.os.Bundle?): android.os.Bundle? {
            val extrasNonNull = extras ?: return null
            return when (method) {
                METHOD_CREATE_DOCUMENT -> {
                    val id = createDocument(
                        extrasNonNull.getString(DocumentsContract.Document.COLUMN_MIME_TYPE).orEmpty(),
                        extrasNonNull.getString(DocumentsContract.Document.COLUMN_DISPLAY_NAME).orEmpty(),
                    )
                    android.os.Bundle().apply { putParcelable(EXTRA_URI, treeDocumentUri(id)) }
                }
                METHOD_RENAME_DOCUMENT -> {
                    val docId = extrasNonNull.getParcelable<Uri>(EXTRA_URI)
                        ?.let { documentIdFrom(it) } ?: error("缺少 rename 文档 uri")
                    val displayName =
                        extrasNonNull.getString(DocumentsContract.Document.COLUMN_DISPLAY_NAME).orEmpty()
                    if (failSidecarRename && displayName.endsWith(SNAPSHOT_SIDECAR_SUFFIX)) {
                        throw FileNotFoundException("模拟 sidecar 重命名失败")
                    }
                    val doc = docs[docId] ?: throw FileNotFoundException(docId)
                    doc.displayName = displayName
                    android.os.Bundle().apply { putParcelable(EXTRA_URI, treeDocumentUri(docId)) }
                }
                METHOD_DELETE_DOCUMENT -> {
                    val docId = extrasNonNull.getParcelable<Uri>(EXTRA_URI)
                        ?.let { documentIdFrom(it) } ?: error("缺少 delete 文档 uri")
                    docs.remove(docId)
                    backingFiles.remove(docId)
                    android.os.Bundle()
                }
                else -> null
            }
        }

        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
            val docId = documentIdFrom(uri)
            return if (docs.remove(docId) != null) {
                backingFiles.remove(docId)
                1
            } else {
                0
            }
        }

        override fun insert(uri: Uri, values: android.content.ContentValues?): Uri? = null

        override fun update(
            uri: Uri,
            values: android.content.ContentValues?,
            selection: String?,
            selectionArgs: Array<out String>?,
        ): Int = 0

        private fun createDocument(mimeType: String, displayName: String): String {
            val id = "doc-${nextId++}"
            docs[id] = Doc(id, displayName, mimeType)
            return id
        }

        private fun treeDocumentUri(documentId: String): Uri =
            DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)

        private fun isChildrenUri(uri: Uri): Boolean = uri.pathSegments.lastOrNull() == "children"

        private fun documentIdFrom(uri: Uri): String {
            val segments = uri.pathSegments
            val last = segments.lastOrNull()
                ?: error("无法解析文档 uri: $uri")
            return last.takeIf { it != "children" } ?: error("无法解析文档 uri: $uri")
        }

        private fun rowFor(doc: Doc, columns: Array<out String>): Array<Any?> = columns.map { column ->
            when (column) {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID -> doc.id
                DocumentsContract.Document.COLUMN_DISPLAY_NAME -> doc.displayName
                DocumentsContract.Document.COLUMN_MIME_TYPE -> doc.mimeType
                DocumentsContract.Document.COLUMN_SIZE -> 0L
                DocumentsContract.Document.COLUMN_LAST_MODIFIED -> 0L
                DocumentsContract.Document.COLUMN_FLAGS -> 0
                else -> null
            }
        }.toTypedArray()

        companion object {
            // API 34 的 DocumentsContract 传输常量（@hide，无法直接引用）。
            private const val METHOD_CREATE_DOCUMENT = "android:createDocument"
            private const val METHOD_RENAME_DOCUMENT = "android:renameDocument"
            private const val METHOD_DELETE_DOCUMENT = "android:deleteDocument"
            private const val EXTRA_URI = "uri"

            val DEFAULT_COLUMNS = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                DocumentsContract.Document.COLUMN_FLAGS,
            )
        }
    }
}
