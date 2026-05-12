package me.rerere.rikkahub.data.sync.core

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
import me.rerere.ai.provider.providers.openai.OpenAICodexAuthStore
import me.rerere.rikkahub.BuildConfig
import me.rerere.rikkahub.data.agent.webmount.oauth.WebMountOAuthTokenStore
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.fts.MessageFtsManager
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.rikkahub.data.files.FilesManager
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class SyncArchiveManager(
    private val context: Context,
    private val settingsStore: SettingsStore,
    private val database: AppDatabase,
    private val messageFtsManager: MessageFtsManager,
    private val filesManager: FilesManager,
    private val webMountOAuthTokenStore: WebMountOAuthTokenStore,
    private val openAICodexAuthStore: OpenAICodexAuthStore,
    private val json: Json,
) {
    private val crypto = SyncCrypto()
    private val redactor = SyncRedactor(json)

    suspend fun createArchive(request: SyncExportRequest): ByteArray {
        require(request.passphrase.isNotBlank()) { "同步口令不能为空" }
        val settings = settingsStore.settingsFlow.value
        val builtPayload = buildPayload(settings, request.mode)
        val params = crypto.newEncryptionParams()
        val encryptedPayload = crypto.encrypt(builtPayload.bytes, request.passphrase, params)
        val manifest = SyncManifest(
            appVersionName = BuildConfig.VERSION_NAME,
            appVersionCode = BuildConfig.VERSION_CODE.toLong(),
            createdAt = System.currentTimeMillis(),
            deviceId = settings.syncSettings.deviceId.ifBlank { "local" },
            mode = request.mode,
            remoteRevision = settings.syncSettings.lastRemoteRevision,
            kdf = params.kdf,
            cipher = params.cipher,
            payloadSha256 = crypto.sha256(encryptedPayload),
        )
        return zipArchive(manifest, encryptedPayload)
    }

    fun inspectArchive(bytes: ByteArray, fileName: String? = null): SyncPreview {
        val parsed = parseArchive(bytes)
        return SyncPreview(
            manifest = parsed.manifest,
            fileName = fileName,
            sizeBytes = bytes.size.toLong(),
        )
    }

    suspend fun restoreArchive(bytes: ByteArray, request: SyncRestoreRequest): SyncPreview {
        require(request.passphrase.isNotBlank()) { "同步口令不能为空" }
        val parsed = parseArchive(bytes)
        require(crypto.sha256(parsed.encryptedPayload) == parsed.manifest.payloadSha256) {
            "备份文件校验失败"
        }
        val payload = crypto.decrypt(parsed.encryptedPayload, request.passphrase, parsed.manifest)
        restorePayload(payload, parsed.manifest)
        return SyncPreview(
            manifest = parsed.manifest,
            sizeBytes = bytes.size.toLong(),
        )
    }

    private fun buildPayload(
        settings: Settings,
        mode: SyncMode,
    ): BuiltSyncPayload = ByteArrayOutputStream().use { output ->
        val summaries = mutableListOf<SyncDatasetSummary>()
        ZipOutputStream(output).use { zip ->
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
                val rows = exportTable(db, table)
                writeTextEntry(
                    zip = zip,
                    name = "tables/$table.jsonl",
                    text = rows.joinToString(separator = "\n") { row -> row.toString() },
                )
                summaries += SyncDatasetSummary("table:$table", recordCount = rows.size)
            }

            val fileSummary = writeFileTrees(zip)
            summaries += fileSummary
            writeTextEntry(zip, PAYLOAD_MANIFEST_ENTRY, json.encodeToString(SyncPayloadManifest(summaries)))
        }
        BuiltSyncPayload(bytes = output.toByteArray())
    }

    private suspend fun restorePayload(payload: ByteArray, manifest: SyncManifest) {
        val entries = unzipPayload(payload)
        val settingsJson = entries[SETTINGS_ENTRY]?.decodeToString()
            ?: error("同步备份缺少 settings.json")
        val secretsJson = entries[SECRETS_ENTRY]?.decodeToString()
        val tableRows = entries
            .filterKeys { it.startsWith("tables/") && it.endsWith(".jsonl") }
            .mapKeys { it.key.removePrefix("tables/").removeSuffix(".jsonl") }
            .mapValues { (_, bytes) ->
                bytes.decodeToString()
                    .lineSequence()
                    .filter { it.isNotBlank() }
                    .map { json.parseToJsonElement(it).jsonObject }
                    .toList()
            }

        val currentSettings = settingsStore.settingsFlow.value
        val restoredSettings = redactor.decodeSettingsForRestore(
            settingsJson = settingsJson,
            mode = manifest.mode,
            localSettings = currentSettings,
        ).copy(syncSettings = currentSettings.syncSettings)
        val stagedFilesRoot = stageFiles(entries)
        try {
            restoreTables(tableRows) {
                replaceFileTreesFromStage(stagedFilesRoot)
            }
        } finally {
            stagedFilesRoot.deleteRecursively()
        }
        restoreSecrets(secretsJson)
        filesManager.syncFolder(FileFolders.UPLOAD)
        messageFtsManager.rebuildAllFromDatabase()
        settingsStore.update(restoredSettings)
    }

    private fun exportTable(db: androidx.sqlite.db.SupportSQLiteDatabase, table: String): List<JsonObject> {
        val rows = mutableListOf<JsonObject>()
        val cursor = db.query("SELECT * FROM ${table.sqlName()}")
        cursor.use {
            while (it.moveToNext()) {
                rows += cursorRowToJson(table, it)
            }
        }
        return rows
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
        afterTablesRestored: () -> Unit,
    ) {
        val db = database.openHelper.writableDatabase
        db.execSQL("PRAGMA foreign_keys=OFF")
        db.beginTransaction()
        try {
            SYNC_TABLES.asReversed().forEach { table ->
                db.execSQL("DELETE FROM ${table.sqlName()}")
            }
            SYNC_TABLES.forEach { table ->
                rowsByTable[table].orEmpty().forEach { row ->
                    db.insert(table, SQLiteDatabase.CONFLICT_REPLACE, row.toContentValues(table))
                }
            }
            afterTablesRestored()
            runCatching {
                val names = SYNC_TABLES.joinToString(",") { "'$it'" }
                db.execSQL("DELETE FROM sqlite_sequence WHERE name IN ($names)")
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
                    writeFileEntry(zip, "files/$relativePath", file)
                    count += 1
                    bytes += file.length()
                }
        }
        return SyncDatasetSummary("files", recordCount = count, byteCount = bytes)
    }

    private fun stageFiles(entries: Map<String, ByteArray>): File {
        val stageRoot = File(context.cacheDir, "sync-restore-stage").canonicalFile
        stageRoot.deleteRecursively()
        stageRoot.mkdirs()
        val filesDir = context.filesDir.canonicalFile
        entries
            .filterKeys { it.startsWith("files/") }
            .forEach { (entryName, bytes) ->
                val relativePath = entryName.removePrefix("files/")
                requireSafeRelativePath(relativePath)
                val target = File(stageRoot, relativePath).canonicalFile
                require(target.path.startsWith(stageRoot.path + File.separator)) {
                    "Invalid file path in sync archive: $relativePath"
                }
                target.parentFile?.mkdirs()
                target.writeBytes(bytes)
            }
        return stageRoot
    }

    private fun replaceFileTreesFromStage(stageRoot: File) {
        val filesDir = context.filesDir.canonicalFile
        val backupRoot = File(context.cacheDir, "sync-restore-backup").canonicalFile
        backupRoot.deleteRecursively()
        backupRoot.mkdirs()
        try {
            SYNC_FILE_ROOTS.forEach { relativeRoot ->
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
                staged.copyRecursively(File(filesDir, relativeRoot), overwrite = true)
            }
            backupRoot.deleteRecursively()
        } catch (error: Throwable) {
            SYNC_FILE_ROOTS.forEach { relativeRoot ->
                File(filesDir, relativeRoot).deleteRecursively()
            }
            backupRoot.listFiles().orEmpty().forEach { backup ->
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

    private fun zipArchive(manifest: SyncManifest, encryptedPayload: ByteArray): ByteArray =
        ByteArrayOutputStream().use { output ->
            ZipOutputStream(output).use { zip ->
                writeBytesEntry(zip, SYNC_PAYLOAD_ENTRY, encryptedPayload)
                writeTextEntry(zip, SYNC_MANIFEST_ENTRY, json.encodeToString(manifest))
            }
            output.toByteArray()
        }

    private fun parseArchive(bytes: ByteArray): ParsedSyncArchive {
        val entries = unzipPayload(bytes)
        val manifestJson = entries[SYNC_MANIFEST_ENTRY]?.decodeToString()
            ?: error("同步备份缺少 manifest.json")
        val encryptedPayload = entries[SYNC_PAYLOAD_ENTRY]
            ?: error("同步备份缺少 payload.enc")
        val manifest = json.decodeFromString<SyncManifest>(manifestJson)
        require(manifest.archiveVersion == CURRENT_ARCHIVE_VERSION) {
            "不支持的同步备份版本：${manifest.archiveVersion}"
        }
        return ParsedSyncArchive(manifest = manifest, encryptedPayload = encryptedPayload)
    }

    private fun unzipPayload(bytes: ByteArray): Map<String, ByteArray> {
        val entries = linkedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                requireSafeRelativePath(entry.name)
                if (!entry.isDirectory) {
                    entries[entry.name] = zip.readBytes()
                }
                zip.closeEntry()
            }
        }
        return entries
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

    private fun writeBytesEntry(zip: ZipOutputStream, name: String, bytes: ByteArray) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(bytes)
        zip.closeEntry()
    }

    private data class ParsedSyncArchive(
        val manifest: SyncManifest,
        val encryptedPayload: ByteArray,
    )

    private data class BuiltSyncPayload(
        val bytes: ByteArray,
    )

    companion object {
        private const val PAYLOAD_MANIFEST_ENTRY = "payload_manifest.json"
        private const val SETTINGS_ENTRY = "settings.json"
        private const val SECRETS_ENTRY = "secrets.json"
        private const val AMBER_FILE_PREFIX = "amber-file://"

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
        )

        private val SYNC_FILE_ROOTS = listOf(
            FileFolders.UPLOAD,
            FileFolders.SKILLS,
            "images",
        )
    }
}
