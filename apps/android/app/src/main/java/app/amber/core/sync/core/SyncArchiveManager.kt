package app.amber.core.sync.core

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.core.net.toUri
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import app.amber.core.settings.prefs.NativePathPrefs
import app.amber.ai.provider.providers.openai.OpenAICodexAuthStore
import app.amber.ai.provider.providers.google.GoogleGeminiAuthStore
import app.amber.agent.BuildConfig
import app.amber.feature.webmount.oauth.WebMountOAuthTokenStore
import app.amber.core.settings.Settings
import app.amber.core.settings.prefs.SettingsAggregator
import app.amber.core.settings.secret.SecretRedactor
import app.amber.agent.data.db.AppDatabase
import app.amber.agent.data.db.fts.MessageFtsManager
import app.amber.core.files.FileFolders
import app.amber.core.files.FilesManager
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class SyncArchiveManager(
    private val context: Context,
    private val settingsStore: SettingsAggregator,
    private val database: AppDatabase,
    private val messageFtsManager: MessageFtsManager,
    private val filesManager: FilesManager,
    private val webMountOAuthTokenStore: WebMountOAuthTokenStore,
    private val openAICodexAuthStore: OpenAICodexAuthStore,
    private val googleGeminiAuthStore: GoogleGeminiAuthStore,
    private val json: Json,
    private val nativePathPrefs: NativePathPrefs,
    private val secretRedactor: SecretRedactor,
    private val deviceBoundBackupKey: DeviceBoundBackupKey,
) {
    // Re-read the syncCrypto flag on every archive op so DataStore writes
    // take effect without a process restart. The flag check is sub-ms; the
    // crypto cost dominates anyway.
    private val crypto: SyncCrypto
        get() = SyncCrypto(nativeEnabled = nativePathPrefs.flow.value.syncCrypto)
    private val redactor = SyncRedactor(json)

    suspend fun createArchive(request: SyncExportRequest): ByteArray {
        val archive = createArchiveFile(request)
        return try {
            archive.readBytes()
        } finally {
            archive.delete()
        }
    }

    suspend fun createArchiveFile(request: SyncExportRequest): File {
        // P7-02 红线：新备份只用自定义口令或设备绑定加密，历史固定回退口令
        // （NO_PASSPHRASE_FALLBACK）只存在于 v1 旧格式的读取兼容分支。
        val passphrase = when (request.encryptionMode) {
            SyncEncryptionMode.PASSPHRASE -> {
                require(request.passphrase.isNotBlank()) { "自定义备份口令不能为空" }
                require(request.passphrase != NO_PASSPHRASE_FALLBACK) { "这个口令是内部保留值，请换一个口令" }
                request.passphrase
            }
            SyncEncryptionMode.DEVICE_BOUND -> deviceBoundBackupKey.getOrCreate()
        }
        val settings = settingsStore.settingsFlow.value
        val payloadFile = tempSyncFile("payload", ".zip")
        val encryptedPayloadFile = tempSyncFile("payload", ".enc")
        val archiveFile = tempSyncFile("archive", ".$SYNC_ARCHIVE_EXTENSION")
        return try {
            buildPayload(settings, request.mode, payloadFile)
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
            zipArchive(manifest, encryptedPayloadFile, encryptResult, archiveFile)
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
        val parsed = parseArchive(file)
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
    suspend fun verifyArchive(file: File, request: SyncRestoreRequest): SyncRestoreVerification {
        val encryptedPayloadFile = tempSyncFile("restore-payload", ".enc")
        val payloadFile = tempSyncFile("restore-payload", ".zip")
        return try {
            val parsed = parseArchive(file, encryptedPayloadFile)
            require(crypto.sha256(encryptedPayloadFile) == parsed.manifest.payloadSha256) {
                "备份文件校验失败"
            }
            val passphrase = resolveRestorePassphrase(parsed.manifest, request)
            try {
                crypto.decrypt(encryptedPayloadFile, payloadFile, passphrase, parsed.manifest)
            } catch (error: Throwable) {
                // GCM 认证标签失败 = 口令错误或文件损坏；两者对外不区分。
                throw IllegalArgumentException("备份口令错误或备份文件已损坏", error)
            }
            val payloadPreview = readPayloadPreview(payloadFile)
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
    suspend fun applyRestore(verification: SyncRestoreVerification, request: SyncRestoreRequest): SyncPreview {
        try {
            requireRestorePayloadCompatible(verification, request)
            restorePayload(verification.payloadFile, verification.preview.manifest, request)
            return verification.preview
        } finally {
            verification.cleanup()
        }
    }

    /** P7-02：中途取消 —— 解密后未写入，不残留任何临时文件。 */
    fun discardVerification(verification: SyncRestoreVerification) {
        verification.cleanup()
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
    ) {
        if (request.scope != RestoreScope.EVERYTHING) return

        val preservedTables = buildSet {
            if (request.preserveConversations) addAll(CONVERSATION_TABLES)
            if (request.preserveGenMedia) addAll(GEN_MEDIA_TABLES)
        }
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
            "同步备份 v${verification.preview.manifest.archiveVersion} 缺少完整恢复所需数据集，" +
                "已拒绝 EVERYTHING 恢复以避免清空缺失表：${missingDatasets.sorted().joinToString()}"
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

    suspend fun restoreArchive(bytes: ByteArray, request: SyncRestoreRequest): SyncPreview {
        val archiveFile = tempSyncFile("restore", ".$SYNC_ARCHIVE_EXTENSION")
        return try {
            archiveFile.writeBytes(bytes)
            restoreArchive(archiveFile, request)
        } finally {
            archiveFile.delete()
        }
    }

    private fun buildPayload(
        settings: Settings,
        mode: SyncMode,
        outputFile: File,
    ) {
        outputFile.parentFile?.mkdirs()
        val summaries = mutableListOf<SyncDatasetSummary>()
        ZipOutputStream(FileOutputStream(outputFile).buffered()).use { zip ->
            // P1-01: 备份导出默认不含 secret —— 只序列化持久化形式（掩码 + reference），
            // 明文永远不进入导出 JSON。refs 随 settings 一起写入，恢复端靠它找回本机 secret。
            // TODO(P7-02): 自定义口令/版本化加密头与“含 secrets 的备份”属于 P7-02 范围。
            val redacted = secretRedactor.redactForExport(
                providers = settings.providers,
                assistants = settings.assistants,
                searchServices = settings.searchServices,
                mcpServers = settings.mcpServers,
                webDavConfig = settings.webDavConfig,
                s3Config = settings.s3Config,
                ttsProviders = settings.ttsProviders,
            )
            val redactedSettings = settings.copy(
                providers = redacted.providers,
                assistants = redacted.assistants,
                searchServices = redacted.searchServices,
                mcpServers = redacted.mcpServers,
                webDavConfig = redacted.webDavConfig,
                s3Config = redacted.s3Config,
                ttsProviders = redacted.ttsProviders,
            )
            val settingsJson = redactor.encodeSettings(redactedSettings, mode)
            writeTextEntry(
                zip,
                SETTINGS_ENTRY,
                secretRedactor.settingsJsonWithRefs(json, settingsJson, redacted.refs.values.toList()),
            )
            summaries += SyncDatasetSummary("settings", recordCount = 1)

            val secretsJson = redactor.encodeSecrets(
                SyncSecretSnapshot(
                    webMountOauth = webMountOAuthTokenStore.exportRawJsonForSync(),
                    openAICodexOAuth = openAICodexAuthStore.exportRawJsonForSync(),
                    googleGeminiOAuth = googleGeminiAuthStore.exportRawJsonForSync(),
                ),
                mode,
            )
            writeTextEntry(zip, SECRETS_ENTRY, secretsJson)
            summaries += SyncDatasetSummary("secrets", recordCount = if (mode == SyncMode.FULL) 1 else 0)

            val db = database.openHelper.writableDatabase
            // Keep every exported table on one SQLite read snapshot. File roots
            // are external resources and intentionally remain outside this DB
            // transaction; the current ownership graph has no shared writer lock
            // that could make DB + files one atomic snapshot.
            db.beginTransactionNonExclusive()
            try {
                SYNC_TABLES.forEach { table ->
                    val rowCount = writeTableEntry(zip, db, table)
                    summaries += SyncDatasetSummary("table:$table", recordCount = rowCount)
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }

            val fileSummary = writeFileTrees(zip)
            summaries += fileSummary
            writeTextEntry(zip, PAYLOAD_MANIFEST_ENTRY, json.encodeToString(SyncPayloadManifest(summaries)))
        }
    }

    private fun writeTableEntry(
        zip: ZipOutputStream,
        db: androidx.sqlite.db.SupportSQLiteDatabase,
        table: String,
    ): Int {
        var count = 0
        zip.putNextEntry(ZipEntry("tables/$table.jsonl"))
        val cursor = db.query("SELECT * FROM ${table.sqlName()}")
        cursor.use {
            while (it.moveToNext()) {
                if (count > 0) zip.write('\n'.code)
                val row = cursorRowToJson(table, it).toString()
                zip.write(row.toByteArray())
                count += 1
            }
        }
        zip.closeEntry()
        return count
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
    ) {
        recoverInterruptedFileRestore()
        val scope = request.scope
        var settingsJson: String? = null
        var secretsJson: String? = null
        val stagedTableFiles = linkedMapOf<String, File>()
        val stagedRestoreRoot = tempSyncDirectory("restore-stage")
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
        val skipChatImages = scope == RestoreScope.EVERYTHING && request.preserveGenMedia
        val skipImages = scope == RestoreScope.EVERYTHING && request.preserveGenMedia
        val preserveConversationTables =
            scope == RestoreScope.EVERYTHING && request.preserveConversations
        val preserveGenMediaTables =
            scope == RestoreScope.EVERYTHING && request.preserveGenMedia
        ZipInputStream(FileInputStream(payloadFile).buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                requireSafeRelativePath(entry.name)
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
                            requireSafeRelativePath(relativePath)
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
                                (skipImages && isImages)
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

        val restoredSettingsJson = settingsJson ?: error("同步备份缺少 settings.json")

        val currentSettings = settingsStore.settingsFlow.value
        val decodedSettings = redactor.decodeSettingsForRestore(
            settingsJson = restoredSettingsJson,
            mode = manifest.mode,
            localSettings = currentSettings,
        )
        // [Review #1 fix] CONFIG_ONLY only adopts the providers list from
        // the backup. Replacing the whole Settings object would orphan
        // local conversations whose `assistantId` points at assistants that
        // exist only in the backup's `assistants` list, leaving "assistant
        // not found" / silent misroute behaviour. With this contract, the
        // user's pain ("don't make me re-input provider configs") is fully
        // solved while keeping their custom assistants, quick messages,
        // lorebooks, and conversation references intact.
        val finalSettings = when (scope) {
            RestoreScope.EVERYTHING ->
                decodedSettings.copy(syncSettings = currentSettings.syncSettings)
            RestoreScope.CONFIG_ONLY ->
                currentSettings.copy(providers = decodedSettings.providers)
        }

        try {
            when (scope) {
                RestoreScope.EVERYTHING -> {
                    // Apply the preserve toggles: filter out tables the user
                    // chose to keep, and tell replaceFileTreesFromStage which
                    // file roots to leave alone. Default behavior (both flags
                    // false) is the historical full-replace.
                    val skippedTables = buildSet {
                        if (preserveConversationTables) addAll(CONVERSATION_TABLES)
                        if (preserveGenMediaTables) addAll(GEN_MEDIA_TABLES)
                    }
                    val filteredTableRows = if (skippedTables.isEmpty()) {
                        stagedTableFiles
                    } else {
                        stagedTableFiles.filterKeys { it !in skippedTables }
                    }
                    val skippedFileRoots = buildSet {
                        if (skipUpload) add(FileFolders.UPLOAD)
                        if (skipChatImages) add(FileFolders.CHAT_IMAGES)
                        if (skipImages) add(FileFolders.IMAGES)
                    }
                    val fileJournal = prepareFileTreeRestore(stagedFilesRoot, skippedFileRoots)
                    try {
                        restoreTables(filteredTableRows, skippedTables, fileJournal.token)
                        fileJournal.commit()
                    } catch (error: Throwable) {
                        runCatching { fileJournal.rollback() }
                            .exceptionOrNull()
                            ?.let(error::addSuppressed)
                        throw error
                    }
                }
                RestoreScope.CONFIG_ONLY -> {
                    // No table or file work — the staged file tree we
                    // didn't even fill (due to the skipBulkPayload guard
                    // earlier) is wiped by the outer `finally` regardless.
                }
            }
        } finally {
            stagedRestoreRoot.deleteRecursively()
        }
        if (scope == RestoreScope.EVERYTHING) {
            // Secrets (WebMount + Codex OAuth tokens) are session-bound.
            // Restoring them on CONFIG_ONLY would clobber a freshly-paired
            // OAuth session with an old token from the backup. Only do it
            // when the user explicitly chose the full migrate-everything
            // path. See Review Risk #4.
            restoreSecrets(secretsJson)
            // FTS index + file dir sync only need to run after a full
            // table / file replace.
            filesManager.syncFolder(FileFolders.UPLOAD)
            messageFtsManager.rebuildAllFromDatabase()
        }
        // P1-01: 备份不含明文 —— 恢复时先把备份携带的 reference 写回 DataStore，
        // 掩码值走 redact keep 规则找回本机 secret；跨设备恢复时本机没有对应
        // secret 的字段保持未设置，用户重新录入。
        settingsStore.restoreSecretRefs(
            secretRedactor.extractRefsFromSettingsJson(json, restoredSettingsJson)
        )
        settingsStore.update(finalSettings)
    }

    private fun cursorRowToJson(table: String, cursor: Cursor): JsonObject = buildJsonObject {
        cursor.columnNames.forEachIndexed { index, name ->
            val value = when (cursor.getType(index)) {
                Cursor.FIELD_TYPE_NULL -> typedValue("null", JsonNull)
                Cursor.FIELD_TYPE_INTEGER -> typedValue("integer", JsonPrimitive(cursor.getLong(index)))
                Cursor.FIELD_TYPE_FLOAT -> typedValue("float", JsonPrimitive(cursor.getDouble(index)))
                Cursor.FIELD_TYPE_BLOB -> typedValue("blob", JsonPrimitive(cursor.getBlob(index).toBase64()))
                else -> {
                    val raw = cursor.getString(index)
                    typedValue("string", JsonPrimitive(normalizeStringForExport(table, name, raw)))
                }
            }
            put(name, value)
        }
    }

    private fun typedValue(type: String, value: JsonElement): JsonObject = buildJsonObject {
        put("type", JsonPrimitive(type))
        put("value", value)
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
            restoreToken?.let { writeDatabaseRestoreMarker(db, it) }
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
                    rewriteStringForImport(table, column, value?.jsonPrimitive?.contentOrNull.orEmpty())
                )
            }
        }
        return values
    }

    private fun writeFileTrees(zip: ZipOutputStream): SyncDatasetSummary {
        var count = 0
        var bytes = 0L
        SYNC_FILE_ROOTS.forEach { relativeRoot ->
            val root = File(context.filesDir, relativeRoot)
            if (!root.exists()) return@forEach
            root.walkTopDown()
                .filter { it.isFile }
                .forEach { file ->
                    val relativePath = file.relativeTo(context.filesDir).invariantSeparatorsPath
                    writeFileTreeEntry(zip, "files/$relativePath", file)
                    count += 1
                    bytes += file.length()
                }
        }
        return SyncDatasetSummary("files", recordCount = count, byteCount = bytes)
    }

    private fun writeFileTreeEntry(zip: ZipOutputStream, name: String, file: File) {
        // Images, audio, video, and pre-compressed archives carry incompressible
        // payloads — DEFLATE just spends CPU for no gain. STORE them straight; the
        // extra CRC32 pass is still cheaper than a wasted DEFLATE pass.
        if (shouldStoreUncompressed(name)) {
            writeStoredFileEntry(
                zip = zip,
                name = name,
                file = file,
                sizeBytes = file.length(),
                crc32 = computeCrc32(file),
            )
        } else {
            writeFileEntry(zip, name, file)
        }
    }

    private fun prepareFileTreeRestore(
        stageRoot: File,
        preservedRoots: Set<String> = emptySet(),
    ): FileTreeRestoreJournal {
        val filesDir = context.filesDir.canonicalFile
        val replacedRoots = SYNC_FILE_ROOTS.filterNot { it in preservedRoots }
        val targets = replacedRoots.associateWith { relativeRoot ->
            File(filesDir, relativeRoot).canonicalFile.also { target ->
                require(target.path.startsWith(filesDir.path + File.separator)) {
                    "Invalid sync file root: $relativeRoot"
                }
            }
        }
        val stagedRoots = stageRoot.listFiles().orEmpty().associateBy { staged ->
            staged.name.also { relativeRoot ->
                require(relativeRoot in SYNC_FILE_ROOTS) {
                    "Invalid staged sync root: $relativeRoot"
                }
            }
        }
        val journalRoot = File(context.cacheDir, FILE_RESTORE_JOURNAL_DIR).canonicalFile
        val backupRoot = File(journalRoot, "backup").canonicalFile
        val restoreToken = UUID.randomUUID().toString()
        journalRoot.deleteRecursively()
        backupRoot.mkdirs()
        writeJournalText(File(journalRoot, FILE_RESTORE_ROOTS), replacedRoots.joinToString("\n"))
        writeJournalText(File(journalRoot, FILE_RESTORE_TOKEN), restoreToken)
        writeJournalText(File(journalRoot, FILE_RESTORE_STATE), FILE_RESTORE_PENDING)
        val journal = FileTreeRestoreJournal(
            filesDir = filesDir,
            journalRoot = journalRoot,
            backupRoot = backupRoot,
            replacedRoots = replacedRoots,
            token = restoreToken,
        )
        try {
            replacedRoots.forEach { relativeRoot ->
                val target = targets.getValue(relativeRoot)
                if (target.exists()) {
                    val backup = File(backupRoot, relativeRoot).canonicalFile
                    backup.parentFile?.mkdirs()
                    if (!target.renameTo(backup)) {
                        target.copyRecursively(backup, overwrite = true)
                        target.deleteRecursively()
                    }
                }
            }
            replacedRoots.forEach { relativeRoot ->
                stagedRoots[relativeRoot]?.copyRecursively(
                    target = File(filesDir, relativeRoot),
                    overwrite = true,
                )
            }
            writeJournalText(File(journalRoot, FILE_RESTORE_STATE), FILE_RESTORE_FILES_REPLACED)
            return journal
        } catch (error: Throwable) {
            runCatching { journal.rollback() }
                .exceptionOrNull()
                ?.let(error::addSuppressed)
            throw error
        }
    }

    private fun recoverInterruptedFileRestore() {
        val journalRoot = File(context.cacheDir, FILE_RESTORE_JOURNAL_DIR).canonicalFile
        if (!journalRoot.exists()) return
        val token = File(journalRoot, FILE_RESTORE_TOKEN).takeIf { it.isFile }?.readText()
        if (token != null && readDatabaseRestoreMarker() == token) {
            check(journalRoot.deleteRecursively()) { "Unable to clean completed restore journal" }
            clearDatabaseRestoreMarker(token)
            return
        }
        val roots = File(journalRoot, FILE_RESTORE_ROOTS)
            .takeIf { it.isFile }
            ?.readLines()
            .orEmpty()
            .filter { it in SYNC_FILE_ROOTS }
        FileTreeRestoreJournal(
            filesDir = context.filesDir.canonicalFile,
            journalRoot = journalRoot,
            backupRoot = File(journalRoot, "backup").canonicalFile,
            replacedRoots = roots,
            token = token.orEmpty(),
        ).rollback()
    }

    private fun writeDatabaseRestoreMarker(db: androidx.sqlite.db.SupportSQLiteDatabase, token: String) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS $RESTORE_MARKER_TABLE " +
                "(id INTEGER PRIMARY KEY CHECK (id = 1), token TEXT NOT NULL)"
        )
        db.execSQL(
            "INSERT OR REPLACE INTO $RESTORE_MARKER_TABLE(id, token) VALUES (1, ?)",
            arrayOf(token),
        )
    }

    private fun readDatabaseRestoreMarker(): String? {
        val db = database.openHelper.writableDatabase
        val exists = db.query(
            "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?",
            arrayOf(RESTORE_MARKER_TABLE),
        ).use { it.moveToFirst() }
        if (!exists) return null
        return db.query("SELECT token FROM $RESTORE_MARKER_TABLE WHERE id = 1").use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }

    private fun clearDatabaseRestoreMarker(token: String) {
        runCatching {
            database.openHelper.writableDatabase.execSQL(
                "DELETE FROM $RESTORE_MARKER_TABLE WHERE id = 1 AND token = ?",
                arrayOf(token),
            )
        }
    }

    private fun writeJournalText(file: File, value: String) {
        file.parentFile?.mkdirs()
        FileOutputStream(file).use { output ->
            output.write(value.toByteArray())
            output.fd.sync()
        }
    }

    private inner class FileTreeRestoreJournal(
        private val filesDir: File,
        private val journalRoot: File,
        private val backupRoot: File,
        private val replacedRoots: List<String>,
        val token: String,
    ) {
        fun commit() {
            if (journalRoot.deleteRecursively()) {
                clearDatabaseRestoreMarker(token)
            }
        }

        fun rollback() {
            replacedRoots.forEach { relativeRoot ->
                File(filesDir, relativeRoot).deleteRecursively()
            }
            backupRoot.listFiles().orEmpty().forEach { backup ->
                if (backup.name !in replacedRoots) return@forEach
                backup.copyRecursively(File(filesDir, backup.name), overwrite = true)
            }
            journalRoot.deleteRecursively()
        }
    }

    private fun restoreSecrets(secretsJson: String?) {
        if (secretsJson.isNullOrBlank()) return
        val snapshot = runCatching { json.decodeFromString<SyncSecretSnapshot>(secretsJson) }.getOrNull() ?: return
        snapshot.webMountOauth?.let { webMountOAuthTokenStore.restoreRawJsonFromSync(it) }
        snapshot.openAICodexOAuth?.let { openAICodexAuthStore.restoreRawJsonFromSync(it) }
        snapshot.googleGeminiOAuth?.let { googleGeminiAuthStore.restoreRawJsonFromSync(it) }
    }

    private fun zipArchive(
        manifest: SyncManifest,
        encryptedPayloadFile: File,
        encryptResult: EncryptResult,
        archiveFile: File,
    ) {
        archiveFile.parentFile?.mkdirs()
        ZipOutputStream(FileOutputStream(archiveFile).buffered()).use { zip ->
            // Put the small manifest first so preview can read metadata without inflating the payload entry first.
            writeTextEntry(zip, SYNC_MANIFEST_ENTRY, json.encodeToString(manifest))
            // payload.enc is AES-GCM ciphertext; DEFLATE would burn CPU for zero gain.
            // We already have size + CRC32 from the encrypt pass, so STORE it straight.
            writeStoredFileEntry(
                zip = zip,
                name = SYNC_PAYLOAD_ENTRY,
                file = encryptedPayloadFile,
                sizeBytes = encryptResult.sizeBytes,
                crc32 = encryptResult.crc32,
            )
        }
    }

    private fun parseArchive(file: File, payloadTarget: File? = null): ParsedSyncArchive {
        var manifestJson: String? = null
        var hasEncryptedPayload = false
        ZipInputStream(FileInputStream(file).buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                requireSafeRelativePath(entry.name)
                if (!entry.isDirectory) {
                    when (entry.name) {
                        SYNC_MANIFEST_ENTRY -> {
                            manifestJson = zip.readBytesWithinLimit(MAX_MANIFEST_ENTRY_BYTES, entry.name).decodeToString()
                        }

                        SYNC_PAYLOAD_ENTRY -> {
                            hasEncryptedPayload = true
                            if (payloadTarget != null) {
                                payloadTarget.parentFile?.mkdirs()
                                FileOutputStream(payloadTarget).buffered().use { output ->
                                    zip.copyToWithinLimit(output, MAX_PAYLOAD_ENTRY_BYTES, entry.name)
                                }
                            }
                        }
                    }
                }
                if (payloadTarget == null && manifestJson != null && hasEncryptedPayload) break
                zip.closeEntry()
            }
        }
        val manifestJsonValue = manifestJson ?: error("同步备份缺少 manifest.json")
        require(hasEncryptedPayload) { "同步备份缺少 payload.enc" }
        val manifest = json.decodeFromString<SyncManifest>(manifestJsonValue)
        // P7-02：v2 = 当前新格式；v1 = 只读迁移（含历史固定口令格式的受控兼容分支）。
        require(
            manifest.archiveVersion == CURRENT_ARCHIVE_VERSION ||
                manifest.archiveVersion == LEGACY_ARCHIVE_VERSION
        ) {
            "不支持的同步备份版本：${manifest.archiveVersion}"
        }
        return ParsedSyncArchive(manifest = manifest)
    }

    private fun normalizeStringForExport(table: String, column: String, value: String): String =
        if (table == "message_node" && column == "messages") {
            value.replace(filesUriPrefix(), AMBER_FILE_PREFIX)
        } else {
            value
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
                ?: throw IllegalStateException("设备绑定备份只能在其创建设备上恢复")

        else -> {
            require(request.passphrase.isNotBlank()) { "同步口令不能为空" }
            request.passphrase
        }
    }

    /**
     * 解密成功后读取负载内的 payload_manifest.json（缺失时按空预览处理）。
     * EVERYTHING 会在 [requireRestorePayloadCompatible] 中拒绝该空预览；
     * CONFIG_ONLY 仍可沿用 settings-only 迁移兼容路径。
     */
    private fun readPayloadPreview(payloadFile: File): SyncPayloadPreview {
        ZipInputStream(FileInputStream(payloadFile).buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                requireSafeRelativePath(entry.name)
                if (!entry.isDirectory && entry.name == PAYLOAD_MANIFEST_ENTRY) {
                    val raw = zip.readBytesWithinLimit(MAX_CONFIG_ENTRY_BYTES, entry.name).decodeToString()
                    return runCatching { json.decodeFromString<SyncPayloadPreview>(raw) }
                        .getOrElse { SyncPayloadPreview() }
                }
                zip.closeEntry()
            }
        }
        return SyncPayloadPreview()
    }

    private fun rewriteStringForImport(table: String, column: String, value: String): String =
        if (table == "message_node" && column == "messages") {
            value.replace(AMBER_FILE_PREFIX, filesUriPrefix())
        } else {
            value
        }

    private fun filesUriPrefix(): String =
        context.filesDir.toUri().toString().trimEnd('/') + "/"

    private fun requireSafeRelativePath(path: String) {
        require(path.isNotBlank()) { "Invalid archive path" }
        require('\\' !in path) { "Invalid archive path: $path" }
        require(!path.startsWith('/')) { "Invalid archive path: $path" }
        require(!path.contains("//")) { "Invalid archive path: $path" }
        require(!Regex("^[A-Za-z]:").containsMatchIn(path)) { "Invalid archive path: $path" }
        require(path.split('/').none { it == "." || it == ".." }) { "Invalid archive path: $path" }
    }

    private fun String.sqlName(): String = "\"${replace("\"", "\"\"")}\""

    private fun writeTextEntry(zip: ZipOutputStream, name: String, text: String) =
        writeBytesEntry(zip, name, text.toByteArray())

    private fun writeFileEntry(zip: ZipOutputStream, name: String, file: File) {
        zip.putNextEntry(ZipEntry(name))
        file.inputStream().use { input -> input.copyTo(zip) }
        zip.closeEntry()
    }

    private fun writeStoredFileEntry(
        zip: ZipOutputStream,
        name: String,
        file: File,
        sizeBytes: Long,
        crc32: Long,
    ) {
        val entry = ZipEntry(name).apply {
            method = ZipEntry.STORED
            size = sizeBytes
            compressedSize = sizeBytes
            crc = crc32
        }
        zip.putNextEntry(entry)
        file.inputStream().buffered().use { input -> input.copyTo(zip) }
        zip.closeEntry()
    }

    private fun computeCrc32(file: File): Long {
        val crc = CRC32()
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) crc.update(buffer, 0, read)
            }
        }
        return crc.value
    }

    private fun shouldStoreUncompressed(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in STORE_UNCOMPRESSED_EXTENSIONS
    }

    private fun writeBytesEntry(zip: ZipOutputStream, name: String, bytes: ByteArray) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(bytes)
        zip.closeEntry()
    }

    private data class ParsedSyncArchive(
        val manifest: SyncManifest,
    )

    companion object {
        private const val MAX_MANIFEST_ENTRY_BYTES = 1024 * 1024
        private const val MAX_CONFIG_ENTRY_BYTES = 8 * 1024 * 1024
        private const val MAX_PAYLOAD_ENTRY_BYTES = 1024L * 1024 * 1024
        private const val MAX_PAYLOAD_FILE_ENTRY_BYTES = 512L * 1024 * 1024
        private const val MAX_PAYLOAD_FILES_TOTAL_BYTES = 1024L * 1024 * 1024
        private const val MAX_PAYLOAD_FILE_COUNT = 20_000
        private const val MAX_STAGED_TABLE_BYTES = 4L * 1024 * 1024 * 1024
        private const val PAYLOAD_MANIFEST_ENTRY = "payload_manifest.json"
        private const val SETTINGS_ENTRY = "settings.json"
        private const val SECRETS_ENTRY = "secrets.json"
        private const val AMBER_FILE_PREFIX = "amber-file://"
        private const val FILE_RESTORE_JOURNAL_DIR = "sync-restore-file-journal"
        private const val FILE_RESTORE_STATE = "state"
        private const val FILE_RESTORE_ROOTS = "roots"
        private const val FILE_RESTORE_TOKEN = "token"
        private const val FILE_RESTORE_PENDING = "pending"
        private const val FILE_RESTORE_FILES_REPLACED = "files_replaced"
        private const val RESTORE_MARKER_TABLE = "amber_sync_restore_marker"

        // Pre-compressed/lossy formats: DEFLATE has no headroom and just burns CPU.
        // Listed by extension so the check stays cheap and stable across content
        // types we cannot otherwise detect from a File handle.
        private val STORE_UNCOMPRESSED_EXTENSIONS = setOf(
            "jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "avif",
            "mp3", "m4a", "aac", "ogg", "opus", "flac",
            "mp4", "m4v", "mov", "webm", "mkv",
            "pdf", "zip", "gz", "tgz", "7z", "rar", "xz", "br", "zst",
        )

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
            "theme_package",
        )

        private val SYNC_FILE_ROOTS = listOf(
            FileFolders.UPLOAD,
            FileFolders.SKILLS,
            FileFolders.IMAGES,
            // generate_image tool output. Conversations carry file:// URIs
            // pointing into this directory; without it in the archive,
            // cross-device restores leave all chat-inline images as broken
            // links. Added in the v1.6.x image-gen feature follow-up.
            FileFolders.CHAT_IMAGES,
        )
    }
}

private fun ZipInputStream.readBytesWithinLimit(limit: Int, entryName: String): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0
    while (true) {
        val read = read(buffer)
        if (read < 0) break
        require(total <= limit - read) { "Sync archive entry $entryName exceeds $limit bytes" }
        output.write(buffer, 0, read)
        total += read
    }
    return output.toByteArray()
}

private fun ZipInputStream.copyToWithinLimit(output: java.io.OutputStream, limit: Long, entryName: String): Long {
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0L
    while (true) {
        val read = read(buffer)
        if (read < 0) break
        require(total <= limit - read) { "Sync archive entry $entryName exceeds $limit bytes" }
        output.write(buffer, 0, read)
        total += read
    }
    return total
}

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
    /** 删除解密暂存文件（幂等；apply 后与中途取消都会调用）。 */
    internal fun cleanup() {
        encryptedPayloadFile.delete()
        payloadFile.delete()
    }
}
