package app.amber.core.sync.provider

import android.content.Context
import android.util.Log
import io.ktor.client.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import app.amber.core.settings.prefs.SettingsAggregator
import app.amber.core.settings.WebDavConfig
import app.amber.core.sync.core.SYNC_ARCHIVE_EXTENSION
import app.amber.core.sync.core.SyncArchiveManager
import app.amber.core.sync.core.SyncCrypto
import app.amber.core.sync.core.SyncExportRequest
import app.amber.core.sync.webdav.WebDavClient
import app.amber.core.sync.webdav.WebDavResourceInfo
import java.io.File

private const val TAG = "WebDavSyncProvider"

/**
 * P7-01 WebDAV SyncProvider —— 完整实现列举 / 上传 / 下载 / 预览 / 删除。
 *
 * 远端布局（配置路径下）：
 * - `<snapshotId>.amberbackup` —— 加密归档数据体。
 * - `<snapshotId>.snapshot.json` —— 统一快照 manifest sidecar。
 *
 * 安全约束（P7-01 安全清单）：
 * - 凭据来自 [SettingsAggregator] 的 webDavConfig —— 设置层已按 reference
 *   从 SecretStore rehydrate，明文不落 DataStore。
 * - 上传先写 `<temp>.tmp`，两个文件都成功后 MOVE publish 为最终名；
 *   任一步失败即删除临时文件，远端不会出现半成品最终名。
 * - 下载后先比对 contentSha256（外层 digest），再检查归档头部
 *   （manifest.json + payload.enc 加密头）；校验失败抛错并删除临时文件。
 */
class WebDavSyncProvider(
    private val context: Context,
    private val settingsStore: SettingsAggregator,
    private val archiveManager: SyncArchiveManager,
    private val json: Json,
    private val httpClient: HttpClient,
) : SyncProvider {

    override val id: String = "webdav"

    private val crypto = SyncCrypto()

    private fun config(): WebDavConfig {
        val config = settingsStore.settingsFlow.value.webDavConfig
        require(config.url.isNotBlank()) { "WebDAV 未配置服务器地址" }
        require(config.username.isNotBlank() && config.password.isNotBlank()) { "WebDAV 未配置凭据" }
        return config
    }

    private fun client(config: WebDavConfig): WebDavClient = WebDavClient(config, httpClient)

    private fun tempDir(): File = File(context.cacheDir, "sync-webdav").apply { mkdirs() }

    override suspend fun listSnapshots(): List<SyncSnapshot> = withContext(Dispatchers.IO) {
        val client = client(config())
        client.ensureCollectionExists().getOrThrow()
        val resources = client.list().getOrThrow()
        resources
            .filter { !it.isCollection && it.displayName.endsWith(SNAPSHOT_SIDECAR_SUFFIX) }
            .mapNotNull { resource -> loadSidecar(resource) }
            .sortedByDescending { it.manifest.createdAt }
    }

    override suspend fun previewSnapshot(snapshotId: String): SyncSnapshotManifest =
        withContext(Dispatchers.IO) {
            val client = client(config())
            val raw = client.get(snapshotSidecarName(snapshotId)).getOrThrow()
            decodeSnapshotManifest(json, raw.decodeToString())
        }

    override suspend fun uploadSnapshot(request: SyncProviderUploadRequest): SyncSnapshot =
        withContext(Dispatchers.IO) {
            val cfg = config()
            val client = client(cfg)
            // ensureCollectionExists() 默认对配置 path 操作（buildUrl 已含 path），
            // 不要重复传 path，否则会拼出 `<path>/<path>` 的嵌套目录。
            client.ensureCollectionExists().getOrThrow()

            val archiveFile = tempFile("upload", ".$SYNC_ARCHIVE_EXTENSION")
            val sidecarFile = tempFile("upload", ".json")
            val tempArchiveName = "upload-${System.nanoTime()}.tmp"
            val tempSidecarName = "upload-${System.nanoTime()}.tmp"
            // 归档已 MOVE publish 为最终名的快照 id；sidecar 发布失败时据此回滚。
            var publishedArchiveSnapshotId: String? = null
            try {
                archiveManager.createArchiveFile(request.toSyncExportRequest()).let { created ->
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

                // 临时名写入，全部成功后再 publish（MOVE 到最终名）。
                client.put(tempArchiveName, archiveFile).getOrThrow()
                client.put(tempSidecarName, sidecarFile).getOrThrow()
                client.move(tempArchiveName, snapshotArchiveName(plan.snapshotId)).getOrThrow()
                publishedArchiveSnapshotId = plan.snapshotId
                client.move(tempSidecarName, snapshotSidecarName(plan.snapshotId)).getOrThrow()

                // OVERWRITE 语义：新快照已发布，删除同 device 旧快照。
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
                runCatching { cleanupRemoteTemps(client, tempArchiveName, tempSidecarName) }
                // sidecar 发布失败时归档已处于最终名：无 sidecar 的归档列表不可见、
                // 也无法经 App 清理，回滚删除最终名归档避免孤儿残留。
                publishedArchiveSnapshotId?.let { published ->
                    runCatching { client.delete(snapshotArchiveName(published)) }
                }
                throw error
            } finally {
                archiveFile.delete()
                sidecarFile.delete()
            }
        }

    override suspend fun downloadSnapshot(snapshotId: String): File = withContext(Dispatchers.IO) {
        val client = client(config())
        val target = tempFile("download", ".$SYNC_ARCHIVE_EXTENSION")
        try {
            client.downloadToFile(snapshotArchiveName(snapshotId), target).getOrThrow()
            val manifest = previewSnapshot(snapshotId)
            // 外层 content digest 校验。
            if (manifest.contentSha256.isNotBlank()) {
                require(crypto.sha256(target) == manifest.contentSha256) {
                    "快照内容校验失败（digest 不匹配），已拒绝恢复"
                }
            }
            // 归档头部（manifest.json + payload.enc 加密头）校验。
            archiveManager.inspectArchive(target, snapshotArchiveName(snapshotId))
            target
        } catch (error: Throwable) {
            target.delete()
            throw error
        }
    }

    override suspend fun deleteSnapshot(snapshotId: String) = withContext(Dispatchers.IO) {
        val client = client(config())
        client.delete(snapshotArchiveName(snapshotId)).getOrThrow()
        runCatching { client.delete(snapshotSidecarName(snapshotId)) }.getOrNull()
        Unit
    }

    private suspend fun loadSidecar(resource: WebDavResourceInfo): SyncSnapshot? {
        val snapshotId = resource.displayName.removeSuffix(SNAPSHOT_SIDECAR_SUFFIX)
        if (snapshotId.isBlank()) return null
        val manifest = runCatching {
            decodeSnapshotManifest(
                json,
                client(config()).get(resource.displayName).getOrThrow().decodeToString(),
            )
        }.getOrElse { error ->
            Log.w(TAG, "跳过无法解析的 sidecar ${resource.displayName}: ${error.message}")
            return null
        }
        return SyncSnapshot(
            providerId = id,
            snapshotId = snapshotId,
            name = snapshotArchiveName(snapshotId),
            manifest = manifest,
            sizeBytes = manifest.sizeBytes,
        )
    }

    private suspend fun cleanupRemoteTemps(client: WebDavClient, vararg names: String) {
        names.forEach { name ->
            runCatching { client.delete(name) }
        }
    }

    private fun tempFile(prefix: String, suffix: String): File =
        File.createTempFile("amber-webdav-$prefix-", suffix, tempDir())

    private fun SyncProviderUploadRequest.toSyncExportRequest() =
        SyncExportRequest(mode = mode, passphrase = passphrase, encryptionMode = encryptionMode)
}
