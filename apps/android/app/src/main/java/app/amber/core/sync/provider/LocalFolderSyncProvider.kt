package app.amber.core.sync.provider

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import app.amber.core.sync.core.SYNC_ARCHIVE_EXTENSION
import app.amber.core.sync.core.SyncArchiveManager
import app.amber.core.sync.core.SyncCrypto
import app.amber.core.sync.core.SyncExportRequest
import java.io.File

private const val TAG = "LocalFolderSyncProvider"

/**
 * P7-01 本地文件夹 SyncProvider —— SAF document tree + 持久 URI permission。
 *
 * 目录布局（用户选择的 tree 下）：
 * - `<snapshotId>.amberbackup` —— 加密归档数据体。
 * - `<snapshotId>.snapshot.json` —— 统一快照 manifest sidecar。
 *
 * 安全约束（P7-01 安全清单）：
 * - 上传先 createDocument 临时名、写完数据体与 sidecar 后 renameDocument
 *   publish 为最终名；失败清理临时文档。
 * - 下载先复制到本地临时文件并校验 contentSha256 + 归档头部，失败即删。
 * - 删除前由 UI 层二次确认（本类只执行）。
 */
class LocalFolderSyncProvider(
    private val context: Context,
    private val archiveManager: SyncArchiveManager,
    private val folderStore: PersistedFolderStore,
    private val json: Json,
) : SyncProvider {

    override val id: String = "local_folder"

    private val crypto = SyncCrypto()

    private fun treeUri(): Uri {
        val uri = folderStore.read()?.uri?.let { runCatching { Uri.parse(it) }.getOrNull() }
            ?: error("尚未选择同步文件夹")
        return uri
    }

    override suspend fun listSnapshots(): List<SyncSnapshot> = withContext(Dispatchers.IO) {
        val treeUri = treeUri()
        val resolver = context.contentResolver
        val children = treeChildDocumentsUri(treeUri)
        val sidecars = mutableListOf<Pair<String, String>>() // documentId to displayName
        resolver.query(
            children,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            ),
            null,
            null,
            null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val documentId = cursor.getString(0) ?: continue
                val displayName = cursor.getString(1) ?: continue
                if (displayName.endsWith(SNAPSHOT_SIDECAR_SUFFIX)) {
                    sidecars += documentId to displayName
                }
            }
        }
        sidecars.mapNotNull { (documentId, displayName) ->
            val snapshotId = displayName.removeSuffix(SNAPSHOT_SIDECAR_SUFFIX)
            if (snapshotId.isBlank()) return@mapNotNull null
            val raw = runCatching {
                resolver.openInputStream(treeDocumentUri(treeUri, documentId))?.use { input ->
                    input.readBytesWithinLimit(MAX_SIDECAR_BYTES, displayName)
                }?.decodeToString()
            }.getOrElse { error ->
                Log.w(TAG, "跳过无法读取的 sidecar $displayName: ${error.message}")
                return@mapNotNull null
            }
            val manifest = runCatching {
                decodeSnapshotManifest(json, raw ?: return@mapNotNull null)
            }.getOrElse { error ->
                Log.w(TAG, "跳过无法解析的 sidecar $displayName: ${error.message}")
                return@mapNotNull null
            }
            SyncSnapshot(
                providerId = id,
                snapshotId = snapshotId,
                name = snapshotArchiveName(snapshotId),
                manifest = manifest,
            )
        }.sortedByDescending { it.manifest.createdAt }
    }

    override suspend fun previewSnapshot(snapshotId: String): SyncSnapshotManifest =
        listSnapshots().firstOrNull { it.snapshotId == snapshotId }?.manifest
            ?: error("同步文件夹里找不到快照 $snapshotId")

    override suspend fun uploadSnapshot(request: SyncProviderUploadRequest): SyncSnapshot =
        withContext(Dispatchers.IO) {
            val treeUri = treeUri()
            val resolver = context.contentResolver
            val archiveFile = tempFile("upload", ".$SYNC_ARCHIVE_EXTENSION")
            val sidecarFile = tempFile("upload", ".json")
            var tempArchiveUri: Uri? = null
            var tempSidecarUri: Uri? = null
            // 归档已 rename publish 为最终名后的引用；sidecar 发布失败时据此回滚。
            var publishedArchiveUri: Uri? = null
            try {
                archiveManager.createArchiveFile(
                    SyncExportRequest(
                        mode = request.mode,
                        passphrase = request.passphrase,
                        encryptionMode = request.encryptionMode,
                    )
                ).let { created ->
                    created.copyTo(archiveFile, overwrite = true)
                    created.delete()
                }
                val contentSha256 = crypto.sha256(archiveFile)
                val preview = archiveManager.inspectArchive(archiveFile)
                val plan = planUpload(
                    existing = listSnapshots(),
                    deviceId = preview.manifest.deviceId,
                    policy = request.conflictPolicy,
                )
                val manifest = snapshotManifestFromArchive(
                    snapshotId = plan.snapshotId,
                    archive = preview.manifest,
                    sizeBytes = archiveFile.length(),
                    contentSha256 = contentSha256,
                )
                sidecarFile.writeText(encodeSnapshotManifest(json, manifest))

                // 临时名写入，全部成功后再 publish（renameDocument 到最终名）。
                tempArchiveUri = DocumentsContract.createDocument(
                    resolver,
                    treeUri,
                    "application/vnd.amberagent.backup+zip",
                    "upload-${System.nanoTime()}.tmp",
                ) ?: error("无法在同步文件夹创建临时文件")
                resolver.openOutputStream(tempArchiveUri, "w")?.use { output ->
                    archiveFile.inputStream().buffered().use { input -> input.copyTo(output) }
                } ?: error("无法写入同步文件夹")
                tempSidecarUri = DocumentsContract.createDocument(
                    resolver,
                    treeUri,
                    "application/json",
                    "upload-${System.nanoTime()}.tmp",
                ) ?: error("无法在同步文件夹创建临时元数据")
                resolver.openOutputStream(tempSidecarUri, "w")?.use { output ->
                    output.write(sidecarFile.readBytes())
                } ?: error("无法写入同步文件夹元数据")

                publishedArchiveUri = DocumentsContract.renameDocument(
                    resolver,
                    tempArchiveUri,
                    snapshotArchiveName(plan.snapshotId),
                ) ?: error("无法在同步文件夹重命名备份文件")
                tempArchiveUri = null
                DocumentsContract.renameDocument(
                    resolver,
                    tempSidecarUri,
                    snapshotSidecarName(plan.snapshotId),
                ) ?: error("无法在同步文件夹重命名元数据")
                tempSidecarUri = null

                plan.supersededSnapshotId?.let { superseded ->
                    runCatching { deleteSnapshot(superseded) }
                        .onFailure { Log.w(TAG, "清理被覆盖的旧快照 $superseded 失败", it) }
                }

                SyncSnapshot(
                    providerId = id,
                    snapshotId = plan.snapshotId,
                    name = snapshotArchiveName(plan.snapshotId),
                    manifest = manifest,
                )
            } catch (error: Throwable) {
                tempArchiveUri?.let { runCatching { DocumentsContract.deleteDocument(resolver, it) } }
                tempSidecarUri?.let { runCatching { DocumentsContract.deleteDocument(resolver, it) } }
                // sidecar 发布失败时归档已 rename 为最终名：无 sidecar 的归档列表
                // 不可见、也无法经 App 清理，回滚删除最终名归档避免孤儿残留。
                publishedArchiveUri?.let { runCatching { DocumentsContract.deleteDocument(resolver, it) } }
                throw error
            } finally {
                archiveFile.delete()
                sidecarFile.delete()
            }
        }

    override suspend fun downloadSnapshot(snapshotId: String): File = withContext(Dispatchers.IO) {
        val treeUri = treeUri()
        val resolver = context.contentResolver
        val documentId = findDocumentId(treeUri, snapshotArchiveName(snapshotId))
            ?: error("同步文件夹里找不到快照 $snapshotId")
        val target = tempFile("download", ".$SYNC_ARCHIVE_EXTENSION")
        try {
            resolver.openInputStream(treeDocumentUri(treeUri, documentId))?.use { input ->
                target.outputStream().buffered().use { output ->
                    input.copyToWithinLimit(output, MAX_DOWNLOAD_BYTES, snapshotId)
                }
            } ?: error("无法读取同步文件夹里的快照")
            val manifest = previewSnapshot(snapshotId)
            if (manifest.contentSha256.isNotBlank()) {
                require(crypto.sha256(target) == manifest.contentSha256) {
                    "快照内容校验失败（digest 不匹配），已拒绝恢复"
                }
            }
            archiveManager.inspectArchive(target, snapshotArchiveName(snapshotId))
            target
        } catch (error: Throwable) {
            target.delete()
            throw error
        }
    }

    override suspend fun deleteSnapshot(snapshotId: String) = withContext(Dispatchers.IO) {
        val treeUri = treeUri()
        val resolver = context.contentResolver
        findDocumentId(treeUri, snapshotArchiveName(snapshotId))?.let { documentId ->
            DocumentsContract.deleteDocument(resolver, treeDocumentUri(treeUri, documentId))
        }
        findDocumentId(treeUri, snapshotSidecarName(snapshotId))?.let { documentId ->
            DocumentsContract.deleteDocument(resolver, treeDocumentUri(treeUri, documentId))
        }
        Unit
    }

    private fun findDocumentId(treeUri: Uri, displayName: String): String? {
        val resolver = context.contentResolver
        val children = treeChildDocumentsUri(treeUri)
        resolver.query(
            children,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            ),
            null,
            null,
            null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val documentId = cursor.getString(0) ?: continue
                if (cursor.getString(1) == displayName) return documentId
            }
        }
        return null
    }

    private fun tempFile(prefix: String, suffix: String): File {
        val dir = File(context.cacheDir, "sync-local-folder").apply { mkdirs() }
        return File.createTempFile("amber-$prefix-", suffix, dir)
    }

    companion object {
        private const val MAX_SIDECAR_BYTES = 1024 * 1024
        private const val MAX_DOWNLOAD_BYTES = 1024L * 1024 * 1024
    }
}

/**
 * SAF document tree 的 URI 纯函数助手 —— 与 ContentResolver 解耦，便于
 * JVM/Robolectric 单测覆盖（DocumentsContract 的静态方法只做 Uri 运算）。
 */

/** tree 下子文档列表的查询 Uri。 */
fun treeChildDocumentsUri(treeUri: Uri): Uri =
    DocumentsContract.buildChildDocumentsUriUsingTree(
        treeUri,
        DocumentsContract.getTreeDocumentId(treeUri),
    )

/** tree 下指定 documentId 的文档 Uri。 */
fun treeDocumentUri(treeUri: Uri, documentId: String): Uri =
    DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)

private fun java.io.InputStream.readBytesWithinLimit(limit: Int, entryName: String): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0
    while (true) {
        val read = read(buffer)
        if (read < 0) break
        require(total <= limit - read) { "同步文件夹条目 $entryName 超过 $limit 字节" }
        output.write(buffer, 0, read)
        total += read
    }
    return output.toByteArray()
}

private fun java.io.InputStream.copyToWithinLimit(output: java.io.OutputStream, limit: Long, name: String) {
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0L
    while (true) {
        val read = read(buffer)
        if (read < 0) break
        require(total <= limit - read) { "快照 $name 超过 $limit 字节下载上限" }
        output.write(buffer, 0, read)
        total += read
    }
}
