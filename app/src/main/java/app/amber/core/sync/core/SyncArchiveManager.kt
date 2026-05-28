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
import app.amber.agent.BuildConfig
import app.amber.feature.webmount.oauth.WebMountOAuthTokenStore
import app.amber.core.settings.Settings
import app.amber.core.settings.prefs.SettingsAggregator
import app.amber.agent.data.db.AppDatabase
import app.amber.agent.data.db.fts.MessageFtsManager
import app.amber.core.files.FileFolders
import app.amber.core.files.FilesManager
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
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
    private val json: Json,
    private val nativePathPrefs: NativePathPrefs,
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
        require(request.passphrase.isNotBlank()) { "同步口令不能为空" }
        val settings = settingsStore.settingsFlow.value
        val payloadFile = tempSyncFile("payload", ".zip")
        val encryptedPayloadFile = tempSyncFile("payload", ".enc")
        val archiveFile = tempSyncFile("archive", ".$SYNC_ARCHIVE_EXTENSION")
        return try {
            buildPayload(settings, request.mode, payloadFile)
            val params = crypto.newEncryptionParams()
            val encryptResult = crypto.encrypt(payloadFile, encryptedPayloadFile, request.passphrase, params)
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
                // If the BackupVM substituted the fixed NO_PASSPHRASE_FALLBACK
                // string, the user picked the no-password path — stamp the
                // manifest so the restore UI can skip the passphrase dialog
                // and auto-inject the same fallback. Real passphrases stay
                // marked as protected (the default).
                passphraseProtected = request.passphrase != NO_PASSPHRASE_FALLBACK,
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

    suspend fun restoreArchive(file: File, request: SyncRestoreRequest): SyncPreview {
        require(request.passphrase.isNotBlank()) { "同步口令不能为空" }
        val encryptedPayloadFile = tempSyncFile("restore-payload", ".enc")
        val payloadFile = tempSyncFile("restore-payload", ".zip")
        return try {
            val parsed = parseArchive(file, encryptedPayloadFile)
            require(crypto.sha256(encryptedPayloadFile) == parsed.manifest.payloadSha256) {
                "备份文件校验失败"
            }
            crypto.decrypt(encryptedPayloadFile, payloadFile, request.passphrase, parsed.manifest)
            restorePayload(payloadFile, parsed.manifest, request)
            SyncPreview(
                manifest = parsed.manifest,
                fileName = file.name,
                sizeBytes = file.length(),
            )
        } finally {
            encryptedPayloadFile.delete()
            payloadFile.delete()
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
            writeTextEntry(zip, SETTINGS_ENTRY, redactor.encodeSettings(settings, mode))
            summaries += SyncDatasetSummary("settings", recordCount = 1)

            val secretsJson = redactor.encodeSecrets(
                SyncSecretSnapshot(
                    webMountOauth = webMountOAuthTokenStore.exportRawJsonForSync(),
                    openAICodexOAuth = openAICodexAuthStore.exportRawJsonForSync(),
                ),
                mode,
            )
            writeTextEntry(zip, SECRETS_ENTRY, secretsJson)
            summaries += SyncDatasetSummary("secrets", recordCount = if (mode == SyncMode.FULL) 1 else 0)

            val db = database.openHelper.writableDatabase
            SYNC_TABLES.forEach { table ->
                val rowCount = writeTableEntry(zip, db, table)
                summaries += SyncDatasetSummary("table:$table", recordCount = rowCount)
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

    private suspend fun restorePayload(
        payloadFile: File,
        manifest: SyncManifest,
        request: SyncRestoreRequest,
    ) {
        val scope = request.scope
        var settingsJson: String? = null
        var secretsJson: String? = null
        val tableRows = linkedMapOf<String, MutableList<JsonObject>>()
        val stagedFilesRoot = File(context.cacheDir, "sync-restore-stage").canonicalFile
        stagedFilesRoot.deleteRecursively()
        stagedFilesRoot.mkdirs()

        // CONFIG_ONLY skips both DB-table extraction and file-tree
        // extraction — we only care about the settings entry. Reading the
        // entries unconditionally would burn I/O on archives that contain
        // multi-GB chat_images / upload dirs we're not going to use.
        val skipBulkPayload = scope == RestoreScope.CONFIG_ONLY
        // Same I/O optimization for the preserve toggles: when the user opted
        // to keep their local chat_images / images, don't even bother staging
        // the bytes from the archive — they'd be wiped by the outer finally
        // block anyway.
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
                            settingsJson = zip.readBytes().decodeToString()
                        }

                        entry.name == SECRETS_ENTRY -> {
                            // Read even when CONFIG_ONLY skips applying — we
                            // ignore the bytes later but reading keeps the
                            // ZipInputStream's position consistent.
                            secretsJson = zip.readBytes().decodeToString()
                        }

                        !skipBulkPayload &&
                            entry.name.startsWith("tables/") &&
                            entry.name.endsWith(".jsonl") -> {
                            val table = entry.name.removePrefix("tables/").removeSuffix(".jsonl")
                            val rows = tableRows.getOrPut(table) { mutableListOf() }
                            zip.readBytes()
                                .decodeToString()
                                .lineSequence()
                                .filter { it.isNotBlank() }
                                .forEach { rows += json.parseToJsonElement(it).jsonObject }
                        }

                        !skipBulkPayload && entry.name.startsWith("files/") -> {
                            val relativePath = entry.name.removePrefix("files/")
                            requireSafeRelativePath(relativePath)
                            // Per-root skip for the preserve toggles. We don't
                            // need to drain the entry bytes — ZipInputStream's
                            // closeEntry() (at the bottom of the outer loop)
                            // skips past any unread payload of the current
                            // entry before advancing.
                            val isChatImages = relativePath.startsWith(FileFolders.CHAT_IMAGES + "/")
                            val isImages = relativePath.startsWith(FileFolders.IMAGES + "/")
                            if ((skipChatImages && isChatImages) || (skipImages && isImages)) {
                                // intentionally drop — local files of this root stay.
                            } else {
                                val target = File(stagedFilesRoot, relativePath).canonicalFile
                                require(target.path.startsWith(stagedFilesRoot.path + File.separator)) {
                                    "Invalid file path in sync archive: $relativePath"
                                }
                                target.parentFile?.mkdirs()
                                FileOutputStream(target).buffered().use { output ->
                                    zip.copyTo(output)
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
                        tableRows
                    } else {
                        tableRows.filterKeys { it !in skippedTables }
                    }
                    val skippedFileRoots = buildSet {
                        if (skipChatImages) add(FileFolders.CHAT_IMAGES)
                        if (skipImages) add(FileFolders.IMAGES)
                    }
                    restoreTables(filteredTableRows, skippedTables) {
                        replaceFileTreesFromStage(stagedFilesRoot, skippedFileRoots)
                    }
                }
                RestoreScope.CONFIG_ONLY -> {
                    // No table or file work — the staged file tree we
                    // didn't even fill (due to the skipBulkPayload guard
                    // earlier) is wiped by the outer `finally` regardless.
                }
            }
        } finally {
            stagedFilesRoot.deleteRecursively()
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
        rowsByTable: Map<String, List<JsonObject>>,
        preservedTables: Set<String> = emptySet(),
        afterTablesRestored: () -> Unit,
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
                rowsByTable[table].orEmpty().forEach { row ->
                    db.insert(table, SQLiteDatabase.CONFLICT_REPLACE, row.toContentValues(table))
                }
            }
            afterTablesRestored()
            runCatching {
                val resetTables = SYNC_TABLES.filterNot { it in preservedTables }
                if (resetTables.isNotEmpty()) {
                    val names = resetTables.joinToString(",") { "'$it'" }
                    db.execSQL("DELETE FROM sqlite_sequence WHERE name IN ($names)")
                }
            }
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

    private fun replaceFileTreesFromStage(
        stageRoot: File,
        preservedRoots: Set<String> = emptySet(),
    ) {
        val filesDir = context.filesDir.canonicalFile
        val backupRoot = File(context.cacheDir, "sync-restore-backup").canonicalFile
        backupRoot.deleteRecursively()
        backupRoot.mkdirs()
        try {
            SYNC_FILE_ROOTS.forEach { relativeRoot ->
                if (relativeRoot in preservedRoots) return@forEach
                val target = File(filesDir, relativeRoot).canonicalFile
                require(target.path.startsWith(filesDir.path + File.separator)) {
                    "Invalid sync file root: $relativeRoot"
                }
                if (target.exists()) {
                    val backup = File(backupRoot, relativeRoot).canonicalFile
                    backup.parentFile?.mkdirs()
                    if (!target.renameTo(backup)) {
                        target.copyRecursively(backup, overwrite = true)
                        target.deleteRecursively()
                    }
                }
            }
            stageRoot.listFiles().orEmpty().forEach { staged ->
                val relativeRoot = staged.name
                require(relativeRoot in SYNC_FILE_ROOTS) {
                    "Invalid staged sync root: $relativeRoot"
                }
                if (relativeRoot in preservedRoots) return@forEach
                staged.copyRecursively(File(filesDir, relativeRoot), overwrite = true)
            }
            backupRoot.deleteRecursively()
        } catch (error: Throwable) {
            SYNC_FILE_ROOTS.forEach { relativeRoot ->
                if (relativeRoot in preservedRoots) return@forEach
                File(filesDir, relativeRoot).deleteRecursively()
            }
            backupRoot.listFiles().orEmpty().forEach { backup ->
                if (backup.name in preservedRoots) return@forEach
                backup.copyRecursively(File(filesDir, backup.name), overwrite = true)
            }
            throw error
        } finally {
            backupRoot.deleteRecursively()
        }
    }

    private fun restoreSecrets(secretsJson: String?) {
        if (secretsJson.isNullOrBlank()) return
        val snapshot = runCatching { json.decodeFromString<SyncSecretSnapshot>(secretsJson) }.getOrNull() ?: return
        snapshot.webMountOauth?.let { webMountOAuthTokenStore.restoreRawJsonFromSync(it) }
        snapshot.openAICodexOAuth?.let { openAICodexAuthStore.restoreRawJsonFromSync(it) }
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
                            manifestJson = zip.readBytes().decodeToString()
                        }

                        SYNC_PAYLOAD_ENTRY -> {
                            hasEncryptedPayload = true
                            if (payloadTarget != null) {
                                payloadTarget.parentFile?.mkdirs()
                                FileOutputStream(payloadTarget).buffered().use { output ->
                                    zip.copyTo(output)
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
        require(manifest.archiveVersion == CURRENT_ARCHIVE_VERSION) {
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
        private const val PAYLOAD_MANIFEST_ENTRY = "payload_manifest.json"
        private const val SETTINGS_ENTRY = "settings.json"
        private const val SECRETS_ENTRY = "secrets.json"
        private const val AMBER_FILE_PREFIX = "amber-file://"

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
            "conversation_compact",
            "conversation_context_event",
        )

        // Subset of SYNC_TABLES tied to image-generation gallery state.
        // Restore preserves these when SyncRestoreRequest.preserveGenMedia
        // is true. The companion file-side preservation is the IMAGES +
        // CHAT_IMAGES roots inside replaceFileTreesFromStage.
        val GEN_MEDIA_TABLES = setOf("genmediaentity")

        val SYNC_TABLES = listOf(
            "conversationentity",
            "message_node",
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
            "board_signal",
            "board_item",
            "board_focus_rule",
            "board_weight",
            // Hot-list/provider caches and deep-read article caches are ephemeral;
            // only user-authored custom source configuration belongs in sync.
            "hot_list_source",
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
