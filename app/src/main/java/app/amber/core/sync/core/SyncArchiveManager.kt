package app.amber.core.sync.core

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import app.amber.agent.R
import app.amber.core.settings.prefs.NativePathPrefs
import app.amber.ai.provider.providers.openai.OpenAICodexAuthStore
import app.amber.ai.provider.providers.google.GoogleGeminiAuthStore
import app.amber.ai.provider.providers.google.AntigravityAuthStore
import app.amber.ai.provider.providers.grok.GrokAuthStore
import app.amber.agent.BuildConfig
import app.amber.feature.webmount.oauth.WebMountOAuthTokenStore
import app.amber.core.settings.Settings
import app.amber.core.settings.prefs.SettingsAggregator
import app.amber.core.settings.secret.SecretRedactor
import app.amber.agent.data.db.AppDatabase
import app.amber.agent.data.db.canonicalizeAmberOwnership
import app.amber.core.model.AMBER_AGENT_ID
import app.amber.agent.data.db.fts.MessageFtsManager
import app.amber.agent.data.workspace.ArtifactBackupContentStore
import app.amber.core.files.FileFolders
import app.amber.core.files.FilesManager
import app.amber.feature.novelworkspace.NovelWorkspaceProjectRepository
import app.amber.feature.workspace.WorkspaceManager
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipInputStream
import kotlinx.coroutines.withContext
import app.amber.core.sync.core.SyncArchiveCodec.Companion.MAX_CONFIG_ENTRY_BYTES
import app.amber.core.sync.core.SyncArchiveCodec.Companion.SETTINGS_ENTRY
import app.amber.core.sync.core.SyncArchiveCodec.Companion.SECRETS_ENTRY

class SyncArchiveManager(
    private val context: Context,
    private val settingsStore: SettingsAggregator,
    private val database: AppDatabase,
    private val messageFtsManager: MessageFtsManager,
    private val filesManager: FilesManager,
    private val webMountOAuthTokenStore: WebMountOAuthTokenStore,
    private val openAICodexAuthStore: OpenAICodexAuthStore,
    private val googleGeminiAuthStore: GoogleGeminiAuthStore,
    private val antigravityAuthStore: AntigravityAuthStore? = null,
    private val json: Json,
    private val nativePathPrefs: NativePathPrefs,
    private val secretRedactor: SecretRedactor,
    private val deviceBoundBackupKey: DeviceBoundBackupKey,
    private val restoreWriteGate: SyncRestoreWriteGate? = null,
    /** Grok OAuth token store；可选以兼容旧测试构造，生产由 DI 注入。 */
    private val grokAuthStore: GrokAuthStore? = null,
    private val workspaceManager: WorkspaceManager? = null,
) {
    // Re-read the syncCrypto flag on every archive op so DataStore writes
    // take effect without a process restart. The flag check is sub-ms; the
    // crypto cost dominates anyway.
    private val crypto: SyncCrypto
        get() = SyncCrypto(nativeEnabled = nativePathPrefs.flow.value.syncCrypto)
    private val redactor = SyncRedactor(json)
    private val codec = SyncArchiveCodec(context, json)
    private val payloadExporter = SyncPayloadExporter(context, database, json, secretRedactor, codec, workspaceManager)
    private val fileRestoreJournal = SyncFileRestoreJournal(context.filesDir, context.cacheDir, database)

    /** Complete interrupted file/DB recovery before startup opens data consumers. */
    suspend fun recoverInterruptedRestore() = withContext(Dispatchers.IO) {
        val journalRoot = fileRestoreJournal.rootDirectory
        if (!journalRoot.exists()) return@withContext
        val roots = fileRestoreJournal.interruptedRoots().toSet()
        if (restoreWriteGate == null) {
            recoverInterruptedFileRestore()
        } else {
            val adoptedRoots = if (fileRestoreJournal.committedToken() != null) roots else emptySet()
            restoreWriteGate.withRestore(
                affectedFileRoots = roots,
                adoptedFileRoots = adoptedRoots,
            ) { recoverInterruptedFileRestore() }
        }
    }

    suspend fun createArchive(request: SyncExportRequest): ByteArray = withContext(Dispatchers.IO) {
        val archive = createArchiveFile(request)
        try {
            archive.readBytes()
        } finally {
            archive.delete()
        }
    }

    suspend fun createArchiveFile(request: SyncExportRequest): File = withContext(Dispatchers.IO) {
        // P7-02 红线：新备份只用自定义口令或设备绑定加密，历史固定回退口令
        // （NO_PASSPHRASE_FALLBACK）只存在于 v1 旧格式的读取兼容分支。
        val passphrase = when (request.encryptionMode) {
            SyncEncryptionMode.PASSPHRASE -> {
                require(request.passphrase.isNotBlank()) {
                    context.getString(R.string.backup_passphrase_required)
                }
                require(request.passphrase != NO_PASSPHRASE_FALLBACK) {
                    context.getString(R.string.backup_reserved_passphrase)
                }
                request.passphrase
            }
            SyncEncryptionMode.DEVICE_BOUND -> deviceBoundBackupKey.getOrCreate()
        }
        val settings = settingsStore.settingsFlow.value
        val payloadFile = tempSyncFile("payload", ".zip")
        val encryptedPayloadFile = tempSyncFile("payload", ".enc")
        val archiveFile = tempSyncFile("archive", ".$SYNC_ARCHIVE_EXTENSION")
        try {
            payloadExporter.writePayload(settings, request.mode, payloadFile) {
                SyncSecretSnapshot(
                    webMountOauth = webMountOAuthTokenStore.exportRawJsonForSync(),
                    openAICodexOAuth = openAICodexAuthStore.exportRawJsonForSync(),
                    googleGeminiOAuth = googleGeminiAuthStore.exportRawJsonForSync(),
                    grokOAuth = grokAuthStore?.exportRawJsonForSync(),
                    grokOAuthBackup = grokAuthStore?.exportBackupRawJsonForSync(),
                    antigravityOAuth = antigravityAuthStore?.exportRawJsonForSync(),
                )
            }
            val params = crypto.newEncryptionParams()
            val encryptResult = crypto.encrypt(payloadFile, encryptedPayloadFile, passphrase, params)
            val manifest = SyncManifest(
                appVersionName = BuildConfig.VERSION_NAME,
                appVersionCode = BuildConfig.VERSION_CODE.toLong(),
                createdAt = System.currentTimeMillis(),
                deviceId = settings.syncSettings.deviceId.ifBlank { "local" },
                deviceLabel = formatDeviceLabel(),
                mode = request.mode,
                remoteRevision = settings.syncSettings.lastRemoteRevision,
                kdf = params.kdf,
                cipher = params.cipher,
                payloadSha256 = encryptResult.sha256,
                // 文件头只记录加密方式与 KDF 参数，从不记录口令或可逆提示。
                encryptionMode = request.encryptionMode,
            )
            codec.zipArchive(manifest, encryptedPayloadFile, encryptResult, archiveFile)
            archiveFile
        } catch (error: Throwable) {
            archiveFile.delete()
            throw error
        } finally {
            payloadFile.delete()
            encryptedPayloadFile.delete()
        }
    }

    fun inspectArchive(file: File, fileName: String? = file.name): SyncPreview {
        val parsed = codec.parseArchive(file)
        return SyncPreview(
            manifest = parsed.manifest,
            fileName = fileName,
            sizeBytes = file.length(),
        )
    }

    /**
     * P7-02 恢复第一步：验证头部（manifest 解析 + 格式版本）与认证标签
     * （AES-GCM 解密），成功后把解密负载解压到临时文件并读取负载预览
     * （会话/消息/附件/估算空间）。**不写任何数据** —— 调用方展示 preview
     * 后调用 [applyRestore] 才写入，取消时调用 [discardVerification]。
     */
    suspend fun verifyArchive(file: File, request: SyncRestoreRequest): SyncRestoreVerification =
        withContext(Dispatchers.IO) {
            val encryptedPayloadFile = tempSyncFile("restore-payload", ".enc")
            val payloadFile = tempSyncFile("restore-payload", ".zip")
            try {
                val parsed = codec.parseArchive(file, encryptedPayloadFile)
                require(crypto.sha256(encryptedPayloadFile) == parsed.manifest.payloadSha256) {
                    context.getString(R.string.backup_incompatible)
                }
                val passphrase = resolveRestorePassphrase(parsed.manifest, request)
                try {
                    crypto.decrypt(encryptedPayloadFile, payloadFile, passphrase, parsed.manifest)
                } catch (error: Throwable) {
                    // GCM 认证标签失败 = 口令错误或文件损坏；两者对外不区分。
                    throw IllegalArgumentException(
                        context.getString(R.string.backup_incompatible),
                        error,
                    )
                }
                val payloadPreview = codec.readPayloadPreview(payloadFile)
                SyncRestoreVerification(
                    archiveFile = file,
                    encryptedPayloadFile = encryptedPayloadFile,
                    payloadFile = payloadFile,
                    preview = SyncPreview(
                        manifest = parsed.manifest,
                        fileName = file.name,
                        sizeBytes = file.length(),
                    ),
                    payloadPreview = payloadPreview,
                )
            } catch (error: Throwable) {
                encryptedPayloadFile.delete()
                payloadFile.delete()
                throw error
            }
        }

    /**
     * P7-02 恢复第二步：把已验证的解密负载写入本机（仅 EVERYTHING 或
     * CONFIG_ONLY 范围内）。解密已在 [verifyArchive] 完成，这里不做任何
     * 口令相关操作。成功后清理临时文件。
     */
    suspend fun applyRestore(verification: SyncRestoreVerification, request: SyncRestoreRequest): SyncPreview =
        withContext(Dispatchers.IO) {
            try {
                require(verification.consume()) { "恢复验证结果已失效，请重新验证备份" }
                val preservedTables = preservedTablesForRestore(request, verification.payloadPreview)
                requireRestorePayloadCompatible(verification, request, preservedTables)
                val restoreNovelWorkspace = request.scope == RestoreScope.EVERYTHING &&
                    verification.preview.manifest.mode == SyncMode.FULL &&
                    verification.payloadPreview.datasets.any { it.id == NOVEL_WORKSPACE_DATASET }
                val restoreArtifactContent = request.scope == RestoreScope.EVERYTHING &&
                    verification.payloadPreview.datasets.any { it.id == ArtifactBackupContentStore.DATASET }
                val restore = suspend {
                    restorePayload(
                        verification.payloadFile,
                        verification.preview.manifest,
                        request,
                        restoreNovelWorkspace = restoreNovelWorkspace,
                        restoreArtifactContent = restoreArtifactContent,
                        preservedTables = preservedTables,
                    )
                }
                if (restoreWriteGate == null) {
                    restore()
                } else {
                    val interruptedNovelRestore = NOVEL_WORKSPACE_ROOT in fileRestoreJournal.interruptedRoots()
                    val affectedRoots = if (restoreNovelWorkspace || interruptedNovelRestore) {
                        setOf(NOVEL_WORKSPACE_ROOT)
                    } else {
                        emptySet()
                    }
                    val adoptedRoots = if (interruptedNovelRestore && fileRestoreJournal.committedToken() != null) {
                        setOf(NOVEL_WORKSPACE_ROOT)
                    } else {
                        emptySet()
                    }
                    restoreWriteGate.withRestore(
                        affectedFileRoots = affectedRoots,
                        adoptedFileRoots = adoptedRoots,
                    ) { restore() }
                }
                verification.preview
            } finally {
                verification.cleanup()
            }
        }

    /** P7-02：中途取消 —— 解密后未写入，不残留任何临时文件。 */
    fun discardVerification(verification: SyncRestoreVerification) {
        verification.cleanup()
    }

    private fun preservedTablesForRestore(
        request: SyncRestoreRequest,
        payloadPreview: SyncPayloadPreview,
    ): Set<String> = buildSet {
        if (request.preserveConversations) addAll(CONVERSATION_TABLES)
        if (request.preserveGenMedia) addAll(GEN_MEDIA_TABLES)
        // Older backups never exported saved Live cards. Only an advertised
        // dataset may replace local cards, including an explicitly empty table.
        if (payloadPreview.datasets.none { it.id == "table:live_card" }) add("live_card")
    }

    /**
     * EVERYTHING is a replace operation: [restoreTables] deletes every table
     * that is not preserved before inserting archive rows. A v1/iOS payload
     * can still pass outer archive decryption while carrying a different
     * dataset contract, so reject it before any staging or delete begins.
     * CONFIG_ONLY never clears tables or file roots and therefore keeps its
     * existing settings-only compatibility path.
     */
    private fun requireRestorePayloadCompatible(
        verification: SyncRestoreVerification,
        request: SyncRestoreRequest,
        preservedTables: Set<String>,
    ) {
        if (request.scope != RestoreScope.EVERYTHING) return

        val requiredDatasets = buildSet {
            add("settings")
            // Even an empty file tree is represented by this manifest dataset;
            // omitting it would make the full restore clear local file roots.
            add("files")
            SYNC_TABLES
                .filterNot { it in preservedTables }
                .forEach { add("table:$it") }
        }
        val presentDatasets = verification.payloadPreview.datasets
            .asSequence()
            .map { it.id }
            .toSet()
        val missingDatasets = requiredDatasets - presentDatasets
        require(missingDatasets.isEmpty()) {
            context.getString(
                R.string.backup_format_incompatible,
                missingDatasets.sorted().joinToString(),
            )
        }
    }

    /** 单步恢复（旧调用方 / 自动同步路径）：验证通过后立即写入。 */
    suspend fun restoreArchive(file: File, request: SyncRestoreRequest): SyncPreview {
        val verification = verifyArchive(file, request)
        return try {
            applyRestore(verification, request)
        } finally {
            discardVerification(verification)
        }
    }

    fun inspectArchive(bytes: ByteArray, fileName: String? = null): SyncPreview {
        val archiveFile = tempSyncFile("inspect", ".$SYNC_ARCHIVE_EXTENSION")
        return try {
            archiveFile.writeBytes(bytes)
            inspectArchive(archiveFile, fileName)
        } finally {
            archiveFile.delete()
        }
    }

    suspend fun restoreArchive(bytes: ByteArray, request: SyncRestoreRequest): SyncPreview =
        withContext(Dispatchers.IO) {
            val archiveFile = tempSyncFile("restore", ".$SYNC_ARCHIVE_EXTENSION")
            try {
                archiveFile.writeBytes(bytes)
                restoreArchive(archiveFile, request)
            } finally {
                archiveFile.delete()
            }
        }

    /**
     * Build "OPPO PMA110" / "vivo V2509A" style label. We dedupe when MODEL
     * already starts with MANUFACTURER (some OEMs prepend their brand) so
     * we don't end up with "Samsung Samsung SM-X910".
     */
    private fun formatDeviceLabel(): String {
        val manufacturer = android.os.Build.MANUFACTURER.orEmpty()
        val model = android.os.Build.MODEL.orEmpty()
        return when {
            manufacturer.isBlank() && model.isBlank() -> ""
            manufacturer.isBlank() -> model
            model.isBlank() -> manufacturer
            model.startsWith(manufacturer, ignoreCase = true) -> model
            else -> "$manufacturer $model"
        }
    }

    private fun tempSyncFile(prefix: String, suffix: String): File {
        val dir = File(context.cacheDir, "sync").apply { mkdirs() }
        return File.createTempFile("amber-$prefix-", suffix, dir)
    }

    private fun tempSyncDirectory(prefix: String): File =
        tempSyncFile(prefix, ".tmp").apply {
            check(delete() && mkdirs()) { "Unable to create sync staging directory" }
        }.canonicalFile

    private suspend fun restorePayload(
        payloadFile: File,
        manifest: SyncManifest,
        request: SyncRestoreRequest,
        restoreNovelWorkspace: Boolean,
        restoreArtifactContent: Boolean,
        preservedTables: Set<String>,
    ) {
        recoverInterruptedFileRestore()
        val stagedRestoreRoot = tempSyncDirectory("restore-stage")
        try {
            restorePayloadFromStage(payloadFile, manifest, request, restoreNovelWorkspace, restoreArtifactContent, preservedTables, stagedRestoreRoot)
        } finally {
            stagedRestoreRoot.deleteRecursively()
        }
    }

    private suspend fun restorePayloadFromStage(
        payloadFile: File,
        manifest: SyncManifest,
        request: SyncRestoreRequest,
        restoreNovelWorkspace: Boolean,
        restoreArtifactContent: Boolean,
        preservedTables: Set<String>,
        stagedRestoreRoot: File,
    ) {
        val scope = request.scope
        var settingsJson: String? = null
        var secretsJson: String? = null
        val stagedTableFiles = linkedMapOf<String, File>()
        val stagedTablesRoot = File(stagedRestoreRoot, "tables").canonicalFile.apply { mkdirs() }
        val stagedFilesRoot = File(stagedRestoreRoot, "files").canonicalFile.apply { mkdirs() }
        var restoredFileCount = 0
        var restoredFileBytes = 0L
        var restoredTableBytes = 0L

        // CONFIG_ONLY skips both DB-table extraction and file-tree
        // extraction — we only care about the settings entry. Reading the
        // entries unconditionally would burn I/O on archives that contain
        // multi-GB chat_images / upload dirs we're not going to use.
        val skipBulkPayload = scope == RestoreScope.CONFIG_ONLY
        // Same I/O optimization for the preserve toggles: don't stage roots
        // that the restore contract leaves local.
        val skipUpload = scope == RestoreScope.EVERYTHING && request.preserveConversations
        val skipChatImages = scope == RestoreScope.EVERYTHING &&
            request.preserveGenMedia && request.preserveConversations
        val skipImages = scope == RestoreScope.EVERYTHING && request.preserveGenMedia
        ZipInputStream(FileInputStream(payloadFile).buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                codec.requireSafeRelativePath(entry.name)
                if (!entry.isDirectory) {
                    when {
                        entry.name == SETTINGS_ENTRY -> {
                            settingsJson = zip.readBytesWithinLimit(MAX_CONFIG_ENTRY_BYTES, entry.name).decodeToString()
                        }

                        entry.name == SECRETS_ENTRY -> {
                            // Read even when CONFIG_ONLY skips applying — we
                            // ignore the bytes later but reading keeps the
                            // ZipInputStream's position consistent.
                            secretsJson = zip.readBytesWithinLimit(MAX_CONFIG_ENTRY_BYTES, entry.name).decodeToString()
                        }

                        !skipBulkPayload &&
                            entry.name.startsWith("tables/") &&
                            entry.name.endsWith(".jsonl") -> {
                            val table = entry.name.removePrefix("tables/").removeSuffix(".jsonl")
                            val target = File(stagedTablesRoot, "$table.jsonl").canonicalFile
                            require(target.path.startsWith(stagedTablesRoot.path + File.separator)) {
                                "Invalid table path in sync archive: $table"
                            }
                            val append = target.exists() && target.length() > 0
                            target.parentFile?.mkdirs()
                            FileOutputStream(target, true).buffered().use { output ->
                                if (append) output.write('\n'.code)
                                val remaining = MAX_STAGED_TABLE_BYTES - restoredTableBytes
                                require(remaining > 0) {
                                    "Sync archive tables exceed $MAX_STAGED_TABLE_BYTES bytes"
                                }
                                restoredTableBytes += zip.copyToWithinLimit(output, remaining, entry.name)
                            }
                            stagedTableFiles[table] = target
                        }

                        !skipBulkPayload && entry.name.startsWith("files/") -> {
                            val relativePath = entry.name.removePrefix("files/")
                            codec.requireSafeRelativePath(relativePath)
                            val relativeRoot = SYNC_FILE_ROOTS.firstOrNull { relativePath.startsWith("$it/") }
                            require(relativeRoot != null) { "Invalid sync file root: $relativePath" }
                            if (relativeRoot == ArtifactBackupContentStore.RELATIVE_ROOT) {
                                require(ArtifactBackupContentStore.isOwnedLocator(relativePath.removePrefix("$relativeRoot/"))) {
                                    "Invalid artifact content path: $relativePath"
                                }
                            }
                            // Per-root skip for the preserve toggles. We don't
                            // need to drain the entry bytes — ZipInputStream's
                            // closeEntry() (at the bottom of the outer loop)
                            // skips past any unread payload of the current
                            // entry before advancing.
                            val isUpload = relativePath.startsWith(FileFolders.UPLOAD + "/")
                            val isChatImages = relativePath.startsWith(FileFolders.CHAT_IMAGES + "/")
                            val isImages = relativePath.startsWith(FileFolders.IMAGES + "/")
                            if (
                                (skipUpload && isUpload) ||
                                (skipChatImages && isChatImages) ||
                                (skipImages && isImages) ||
                                (!restoreNovelWorkspace && relativeRoot == NOVEL_WORKSPACE_ROOT) ||
                                (!restoreArtifactContent && relativeRoot == ArtifactBackupContentStore.RELATIVE_ROOT)
                            ) {
                                // intentionally drop — local files of this root stay.
                            } else {
                                require(restoredFileCount < MAX_PAYLOAD_FILE_COUNT) {
                                    "Sync archive contains more than $MAX_PAYLOAD_FILE_COUNT files"
                                }
                                val target = File(stagedFilesRoot, relativePath).canonicalFile
                                require(target.path.startsWith(stagedFilesRoot.path + File.separator)) {
                                    "Invalid file path in sync archive: $relativePath"
                                }
                                target.parentFile?.mkdirs()
                                FileOutputStream(target).buffered().use { output ->
                                    val copied = zip.copyToWithinLimit(
                                        output = output,
                                        limit = MAX_PAYLOAD_FILE_ENTRY_BYTES,
                                        entryName = entry.name,
                                    )
                                    require(restoredFileBytes <= MAX_PAYLOAD_FILES_TOTAL_BYTES - copied) {
                                        "Sync archive files exceed $MAX_PAYLOAD_FILES_TOTAL_BYTES bytes"
                                    }
                                    restoredFileBytes += copied
                                    restoredFileCount++
                                }
                            }
                        }
                    }
                }
                zip.closeEntry()
            }
        }

        val restoredSettingsJson = settingsJson ?: error(
            context.getString(R.string.backup_format_incompatible, "settings.json")
        )

        if (scope == RestoreScope.EVERYTHING) {
            val requiredTables = SYNC_TABLES.filterNot { it in preservedTables }.toSet()
            val missingTables = requiredTables - stagedTableFiles.keys
            require(missingTables.isEmpty()) {
                context.getString(
                    R.string.backup_format_incompatible,
                    "tables/${missingTables.sorted().joinToString(",")}.jsonl",
                )
            }
        }

        val currentSettings = settingsStore.settingsFlow.value
        val decodedSettings = redactor.decodeSettingsForRestore(
            settingsJson = restoredSettingsJson,
            mode = manifest.mode,
            localSettings = currentSettings,
        )
        // CONFIG_ONLY only adopts provider credentials and leaves the local
        // Amber profile, conversations, prompts and extensions untouched.
        val finalSettings = when (scope) {
            RestoreScope.EVERYTHING ->
                decodedSettings.copy(syncSettings = currentSettings.syncSettings)
            RestoreScope.CONFIG_ONLY ->
                currentSettings.copy(providers = decodedSettings.providers)
        }

        when (scope) {
            RestoreScope.EVERYTHING -> {
                // Apply the preserve toggles: filter out tables the user
                // chose to keep, and tell replaceFileTreesFromStage which
                // file roots to leave alone. Default behavior (both flags
                // false) is the historical full-replace.
                val filteredTableRows = if (preservedTables.isEmpty()) {
                    stagedTableFiles
                } else {
                    stagedTableFiles.filterKeys { it !in preservedTables }
                }
                val skippedFileRoots = buildSet {
                    if (skipUpload) add(FileFolders.UPLOAD)
                    if (skipChatImages) add(FileFolders.CHAT_IMAGES)
                    if (skipImages) add(FileFolders.IMAGES)
                    // Older FULL packages and STANDARD packages never replace local novels.
                    if (!restoreNovelWorkspace) add(NOVEL_WORKSPACE_ROOT)
                    if (!restoreArtifactContent) add(ArtifactBackupContentStore.RELATIVE_ROOT)
                }
                val mergeLocalRoots = if (request.preserveConversations != request.preserveGenMedia) {
                    setOf(FileFolders.CHAT_IMAGES)
                } else emptySet()
                val fileJournal = fileRestoreJournal.prepare(stagedFilesRoot, skippedFileRoots, mergeLocalRoots)
                var dataCommitted = false
                try {
                    restoreTables(filteredTableRows, preservedTables, fileJournal.token)
                    // The database transaction and file replacement now
                    // describe one imported data set. Keep the journal
                    // until secrets/settings/FTS have finished so a crash
                    // after this point is recovered as a partial commit,
                    // never as an old-file rollback.
                    dataCommitted = true
                    if (restoreNovelWorkspace) restoreWriteGate?.markFileSnapshotAdopted(NOVEL_WORKSPACE_ROOT)
                    restoreWriteGate?.markDataCommitted()
                    fileJournal.markDataCommitted()
                    restoreSecrets(secretsJson)
                    fileJournal.markSecretsApplied()
                    // The restore already owns the write gate. Use the
                    // restore-only reconciliation path so this does not
                    // reacquire the non-reentrant gate.
                    filesManager.syncFolderDuringRestore(FileFolders.UPLOAD)
                    messageFtsManager.rebuildAllFromDatabase()
                    fileJournal.markFtsApplied()
                    applyRestoredSettings(restoredSettingsJson, finalSettings)
                    fileJournal.markSettingsApplied()
                    fileJournal.commit()
                } catch (error: Throwable) {
                    if (!dataCommitted) {
                        runCatching { fileJournal.rollback() }
                            .exceptionOrNull()
                            ?.let(error::addSuppressed)
                        throw error
                    }
                    throw if (error is SyncRestorePartialCommitException) {
                        error
                    } else {
                        SyncRestorePartialCommitException(error)
                    }
                }
            }
            RestoreScope.CONFIG_ONLY -> {
                // No table or file work — the staged file tree we
                // didn't even fill (due to the skipBulkPayload guard
                // earlier) is wiped by the outer `finally` regardless.
                applyRestoredSettings(restoredSettingsJson, finalSettings)
            }
        }

    }

    /**
     * P1-01: 备份不含明文 —— 恢复时先把备份携带的 reference 写回 DataStore，
     * 掩码值走 redact keep 规则找回本机 secret；跨设备恢复时本机没有对应
     * secret 的字段保持未设置，用户重新录入。
     */
    private suspend fun applyRestoredSettings(
        restoredSettingsJson: String,
        finalSettings: Settings,
    ) {
        val restoredRefs = secretRedactor.extractRefsFromSettingsJson(json, restoredSettingsJson)
        settingsStore.restoreSecretRefs(restoredRefs)
        val refsByKey = restoredRefs.associateBy { it.descriptor().key }
        settingsStore.update(
            finalSettings.copy(
                customHeaders = secretRedactor.rehydrateCustomHeaders(finalSettings.customHeaders, refsByKey),
            )
        )
    }

    private fun restoreTables(
        rowsByTable: Map<String, File>,
        preservedTables: Set<String> = emptySet(),
        restoreToken: String? = null,
    ) {
        val db = database.openHelper.writableDatabase
        db.execSQL("PRAGMA foreign_keys=OFF")
        db.beginTransaction()
        try {
            // Wipe only tables we plan to refill from the archive — leave
            // preserved tables intact so the user's local conversations /
            // gen-media survive an EVERYTHING-scope restore.
            SYNC_TABLES.asReversed().forEach { table ->
                if (table in preservedTables) return@forEach
                db.execSQL("DELETE FROM ${table.sqlName()}")
            }
            SYNC_TABLES.forEach { table ->
                if (table in preservedTables) return@forEach
                rowsByTable[table]?.bufferedReader()?.useLines { lines ->
                    lines.filter { it.isNotBlank() }.forEach { line ->
                        val row = json.parseToJsonElement(line).jsonObject
                        db.insert(table, SQLiteDatabase.CONFLICT_REPLACE, row.toContentValues(table))
                    }
                }
            }
            runCatching {
                val resetTables = SYNC_TABLES.filterNot { it in preservedTables }
                if (resetTables.isNotEmpty()) {
                    val names = resetTables.joinToString(",") { "'$it'" }
                    db.execSQL("DELETE FROM sqlite_sequence WHERE name IN ($names)")
                }
            }
            val profileId = AMBER_AGENT_ID.toString()
            canonicalizeAmberOwnership(db, profileId)
            restoreToken?.let { fileRestoreJournal.writeDatabaseMarker(db, it) }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
            db.execSQL("PRAGMA foreign_keys=ON")
        }
    }

    private fun JsonObject.toContentValues(table: String): ContentValues {
        val values = ContentValues()
        entries.forEach { (column, element) ->
            val obj = element.jsonObject
            val type = obj["type"]?.jsonPrimitive?.contentOrNull
            val value = obj["value"]
            when (type) {
                "null" -> values.putNull(column)
                "integer" -> values.put(column, value?.jsonPrimitive?.longOrNull)
                "float" -> values.put(column, value?.jsonPrimitive?.doubleOrNull)
                "blob" -> values.put(column, value?.jsonPrimitive?.contentOrNull?.fromBase64())
                "string" -> values.put(
                    column,
                    codec.rewriteStringForImport(table, column, value?.jsonPrimitive?.contentOrNull.orEmpty())
                )
            }
        }
        return values
    }

    private suspend fun recoverInterruptedFileRestore() {
        val journalRoot = fileRestoreJournal.rootDirectory
        if (!journalRoot.exists()) return
        val committedToken = fileRestoreJournal.committedToken()
        if (committedToken != null) {
            // The DB marker proves that the transaction committed. Any
            // journal state after that point may have stopped in secrets,
            // settings, or FTS; retain the imported data and replay the one
            // derived step that is safe without holding credentials.
            restoreWriteGate?.markDataCommitted()
            messageFtsManager.rebuildAllFromDatabase()
            fileRestoreJournal.completeCommitted(committedToken)
            return
        }
        fileRestoreJournal.rollbackInterrupted()
    }

    private fun restoreSecrets(secretsJson: String?) {
        if (secretsJson.isNullOrBlank()) return
        val snapshot = runCatching { json.decodeFromString<SyncSecretSnapshot>(secretsJson) }
            .getOrElse { error -> throw IllegalArgumentException("同步备份 secrets 数据损坏", error) }
        snapshot.webMountOauth?.let { webMountOAuthTokenStore.restoreRawJsonFromSync(it) }
        snapshot.openAICodexOAuth?.let { openAICodexAuthStore.restoreRawJsonFromSync(it) }
        snapshot.googleGeminiOAuth?.let { googleGeminiAuthStore.restoreRawJsonFromSync(it) }
        snapshot.grokOAuth?.let { grokAuthStore?.restoreRawJsonFromSync(it) }
        snapshot.grokOAuthBackup?.let { grokAuthStore?.restoreBackupRawJsonFromSync(it) }
        snapshot.antigravityOAuth?.let { antigravityAuthStore?.restoreRawJsonFromSync(it) }
    }

    /**
     * P7-02 恢复口令解析：
     * - v1 旧格式且 `!passphraseProtected`：历史固定回退口令 —— 仅受控兼容分支，
     *   不用于新备份。
     * - v2 DEVICE_BOUND：本机 Keystore 保护的设备秘密；非本机（无秘密）拒绝。
     * - 其余（v2 PASSPHRASE / v1 受口令保护）：用户口令必填。
     */
    private fun resolveRestorePassphrase(manifest: SyncManifest, request: SyncRestoreRequest): String = when {
        manifest.archiveVersion == LEGACY_ARCHIVE_VERSION && !manifest.passphraseProtected ->
            NO_PASSPHRASE_FALLBACK

        manifest.encryptionMode == SyncEncryptionMode.DEVICE_BOUND ->
            deviceBoundBackupKey.current()
                ?: throw IllegalStateException(
                    context.getString(R.string.backup_device_bound_restore_hint)
                )

        else -> {
            require(request.passphrase.isNotBlank()) {
                context.getString(R.string.backup_passphrase_required)
            }
            request.passphrase
        }
    }

    private fun String.sqlName(): String = "\"${replace("\"", "\"\"")}\""

    companion object {
        fun hasInterruptedFileRestore(context: Context): Boolean =
            SyncFileRestoreJournal.hasInterruptedRestore(context)

        private const val MAX_PAYLOAD_FILE_ENTRY_BYTES = 512L * 1024 * 1024
        private const val MAX_PAYLOAD_FILES_TOTAL_BYTES = 1024L * 1024 * 1024
        private const val MAX_PAYLOAD_FILE_COUNT = 20_000
        private const val MAX_STAGED_TABLE_BYTES = 4L * 1024 * 1024 * 1024
        internal const val NOVEL_WORKSPACE_ROOT = NovelWorkspaceProjectRepository.RELATIVE_ROOT
        internal const val NOVEL_WORKSPACE_DATASET = "novel-workspace"

        // Subset of SYNC_TABLES whose rows describe local chat history.
        // Restore preserves these when SyncRestoreRequest.preserveConversations
        // is true (default for the simplified UI). Drains from the archive's
        // ZIP entries are still read (so the ZipInputStream stays in sync),
        // they just don't write to the DB.
        val CONVERSATION_TABLES = setOf(
            "conversationentity",
            "message_node",
            "message_node_stat",
            "message_day_stat",
            "conversation_compact",
            "conversation_context_event",
            // Upload rows and their files are one logical attachment graph.
            // Preserving only messages would leave file:// references broken.
            "managed_files",
        )

        // Subset of SYNC_TABLES tied to image-generation gallery state.
        // Restore preserves these when SyncRestoreRequest.preserveGenMedia
        // is true. The companion file-side preservation is the IMAGES +
        // CHAT_IMAGES roots inside replaceFileTreesFromStage.
        val GEN_MEDIA_TABLES = setOf("genmediaentity")

        val SYNC_TABLES = listOf(
            "conversationentity",
            "conversation_draft",
            "artifact",
            "artifact_reference",
            "message_node",
            "message_node_stat",
            "message_day_stat",
            "conversation_compact",
            "conversation_context_event",
            "memoryentity",
            "memory_candidate",
            "memory_event",
            "memory_dream_plan",
            "managed_files",
            "genmediaentity",
            "favorites",
            "feishu_watched_doc",
            "feishu_doc_snapshot",
            "feishu_doc_change",
            "feishu_doc_dependency",
            "doc_subscription",
            "doc_change_log",
            "board_signal",
            "board_item",
            "board_focus_rule",
            "board_weight",
            "daily_review",
            "mini_app",
            "mini_app_grant",
            "mini_app_version",
            "mini_app_audit_log",
            "mini_app_shared_data",
            // Hot-list/provider caches and deep-read article caches are ephemeral;
            // only user-authored custom source configuration belongs in sync.
            "hot_list_source",
            "tool_effect",
            "run_terminal",
            "run_resume",
            "thread_node",
            "thread_message",
            "thread_result",
            "continue_candidate_dismiss",
            "conversation_activity_seen",
            "conversation_activity_baseline",
            "theme_package",
            "live_card",
        )

        internal val SYNC_FILE_ROOTS = listOf(
            FileFolders.UPLOAD,
            FileFolders.SKILLS,
            FileFolders.IMAGES,
            // generate_image tool output. Conversations carry file:// URIs
            // pointing into this directory; without it in the archive,
            // cross-device restores leave all chat-inline images as broken
            // links. Added in the v1.6.x image-gen feature follow-up.
            FileFolders.CHAT_IMAGES,
            NOVEL_WORKSPACE_ROOT,
            ArtifactBackupContentStore.RELATIVE_ROOT,
        )
    }
}

/**
 * Restore data (SQLite + file roots) is committed, but a post-commit side
 * effect such as secrets/settings or FTS failed. The journal keeps this state
 * durable so the next restore operation can rebuild derived data before a
 * retry. Callers must re-verify the encrypted source before applying again.
 */
class SyncRestorePartialCommitException(cause: Throwable) :
    IllegalStateException(
        "备份数据已写入，但设置、令牌或搜索索引收尾失败；请重新验证同一备份后重试",
        cause,
    )

/**
 * P7-02 两阶段恢复的验证结果：头部 + 认证标签已验证、负载已解密并暂存，
 * 但**尚未写入**。调用方在展示恢复 preview 后选择 [SyncArchiveManager.applyRestore]
 * 或 [SyncArchiveManager.discardVerification]（中途退出不残留）。
 */
class SyncRestoreVerification internal constructor(
    /** 归档文件（调用方持有生命周期）。 */
    val archiveFile: File,
    /** 解出的加密负载临时文件。 */
    internal val encryptedPayloadFile: File,
    /** 已解密的 zip 临时文件。 */
    internal val payloadFile: File,
    /** 头部预览（manifest + 文件信息）。 */
    val preview: SyncPreview,
    /** 解密后才可见的负载预览（会话/消息/附件/估算空间）。 */
    val payloadPreview: SyncPayloadPreview,
) {
    private val usable = AtomicBoolean(true)

    /** Consume the plaintext verification exactly once before any writes. */
    internal fun consume(): Boolean = usable.compareAndSet(true, false)

    /** 删除解密暂存文件（幂等；apply 后与中途取消都会调用）。 */
    internal fun cleanup() {
        usable.set(false)
        encryptedPayloadFile.delete()
        payloadFile.delete()
    }
}
