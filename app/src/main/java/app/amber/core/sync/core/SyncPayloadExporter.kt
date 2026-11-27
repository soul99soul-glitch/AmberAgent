package app.amber.core.sync.core

import android.content.Context
import android.database.Cursor
import app.amber.agent.data.db.AppDatabase
import app.amber.agent.data.workspace.ArtifactBackupContentStore
import app.amber.core.settings.Settings
import app.amber.core.settings.secret.SecretRedactor
import app.amber.core.sync.core.SyncArchiveCodec.Companion.PAYLOAD_MANIFEST_ENTRY
import app.amber.core.sync.core.SyncArchiveCodec.Companion.SECRETS_ENTRY
import app.amber.core.sync.core.SyncArchiveCodec.Companion.SETTINGS_ENTRY
import app.amber.core.sync.core.SyncArchiveManager.Companion.NOVEL_WORKSPACE_DATASET
import app.amber.core.sync.core.SyncArchiveManager.Companion.NOVEL_WORKSPACE_ROOT
import app.amber.core.sync.core.SyncArchiveManager.Companion.SYNC_FILE_ROOTS
import app.amber.feature.workspace.WorkspaceManager
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** Exports settings, one SQLite read snapshot and the owned external content. */
internal class SyncPayloadExporter(
    private val context: Context,
    private val database: AppDatabase,
    private val json: Json,
    private val secretRedactor: SecretRedactor,
    private val codec: SyncArchiveCodec,
    private val workspaceManager: WorkspaceManager?,
    private val restoreWriteGate: SyncRestoreWriteGate?,
) {
    private val redactor = SyncRedactor(json)

    suspend fun writePayload(
        settings: Settings,
        mode: SyncMode,
        outputFile: File,
        secrets: () -> SyncSecretSnapshot,
    ) {
        outputFile.parentFile?.mkdirs()
        val summaries = mutableListOf<SyncDatasetSummary>()
        ZipOutputStream(FileOutputStream(outputFile).buffered()).use { zip ->
            // P1-01: 备份导出默认不含 secret —— 只序列化持久化形式（掩码 + reference），
            // 明文永远不进入导出 JSON。refs 随 settings 一起写入，恢复端靠它找回本机 secret。
            // TODO(P7-02): 自定义口令/版本化加密头与“含 secrets 的备份”属于 P7-02 范围。
            val redacted = secretRedactor.redactForExport(
                providers = settings.providers,
                customHeaders = settings.customHeaders,
                searchServices = settings.searchServices,
                mcpServers = settings.mcpServers,
                webDavConfig = settings.webDavConfig,
                s3Config = settings.s3Config,
            )
            val redactedSettings = settings.copy(
                providers = redacted.providers,
                customHeaders = redacted.customHeaders,
                searchServices = redacted.searchServices,
                mcpServers = redacted.mcpServers,
                webDavConfig = redacted.webDavConfig,
                s3Config = redacted.s3Config,
            )
            val settingsJson = redactor.encodeSettings(redactedSettings, mode)
            codec.writeTextEntry(
                zip,
                SETTINGS_ENTRY,
                secretRedactor.settingsJsonWithRefs(json, settingsJson, redacted.refs.values.toList()),
            )
            summaries += SyncDatasetSummary("settings", recordCount = 1)

            val secretsJson = redactor.encodeSecrets(
                secrets(),
                mode,
            )
            codec.writeTextEntry(zip, SECRETS_ENTRY, secretsJson)
            summaries += SyncDatasetSummary("secrets", recordCount = if (mode == SyncMode.FULL) 1 else 0)

            val db = database.openHelper.writableDatabase
            val artifactLocators = mutableListOf<String>()
            // Keep every exported table on one SQLite read snapshot. File roots
            // are external resources and intentionally remain outside this DB
            // transaction; the current ownership graph has no shared writer lock
            // that could make DB + files one atomic snapshot.
            db.beginTransactionNonExclusive()
            try {
                SyncArchiveManager.SYNC_TABLES.forEach { table ->
                    val rowCount = writeTableEntry(zip, db, table)
                    summaries += SyncDatasetSummary("table:$table", recordCount = rowCount)
                }
                db.query("SELECT content_locator FROM artifact").use { cursor ->
                    while (cursor.moveToNext()) artifactLocators += cursor.getString(0)
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }

            val artifactSummary = writeArtifactBodies(zip, artifactLocators)
            val fileSummary = writeFileTrees(zip, mode)
            summaries += fileSummary.copy(
                recordCount = fileSummary.recordCount + artifactSummary.recordCount,
                byteCount = fileSummary.byteCount + artifactSummary.byteCount,
            )
            // File totals already include these bytes; this dataset declares ownership.
            summaries += artifactSummary.copy(byteCount = 0)
            if (mode == SyncMode.FULL) {
                // Explicit replace contract, including an empty native workspace. File totals
                // already include these bytes, so this dataset is only a presence marker.
                summaries += SyncDatasetSummary(NOVEL_WORKSPACE_DATASET, recordCount = 0, byteCount = 0)
            }
            codec.writeTextEntry(zip, PAYLOAD_MANIFEST_ENTRY, json.encodeToString(SyncPayloadManifest(summaries)))
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

    private fun cursorRowToJson(table: String, cursor: Cursor): JsonObject = buildJsonObject {
        cursor.columnNames.forEachIndexed { index, name ->
            val value = when (cursor.getType(index)) {
                Cursor.FIELD_TYPE_NULL -> typedValue("null", JsonNull)
                Cursor.FIELD_TYPE_INTEGER -> typedValue("integer", JsonPrimitive(cursor.getLong(index)))
                Cursor.FIELD_TYPE_FLOAT -> typedValue("float", JsonPrimitive(cursor.getDouble(index)))
                Cursor.FIELD_TYPE_BLOB -> typedValue("blob", JsonPrimitive(cursor.getBlob(index).toBase64()))
                else -> {
                    val raw = cursor.getString(index)
                    typedValue("string", JsonPrimitive(codec.normalizeStringForExport(table, name, raw)))
                }
            }
            put(name, value)
        }
    }

    private fun typedValue(type: String, value: JsonElement): JsonObject = buildJsonObject {
        put("type", JsonPrimitive(type))
        put("value", value)
    }

    private suspend fun writeArtifactBodies(zip: ZipOutputStream, locators: List<String>): SyncDatasetSummary {
        val content = ArtifactBackupContentStore(workspaceManager ?: WorkspaceManager(context))
        var count = 0
        var bytes = 0L
        locators.distinct().forEach { locator ->
            val body = content.readForBackup(locator) ?: return@forEach
            codec.writeBytesEntry(zip, "files/${ArtifactBackupContentStore.RELATIVE_ROOT}/$locator", body)
            count++
            bytes += body.size
        }
        return SyncDatasetSummary(ArtifactBackupContentStore.DATASET, count, bytes)
    }

    private suspend fun writeFileTrees(zip: ZipOutputStream, mode: SyncMode): SyncDatasetSummary {
        var count = 0
        var bytes = 0L
        SYNC_FILE_ROOTS.forEach { relativeRoot ->
            if (relativeRoot == NOVEL_WORKSPACE_ROOT || relativeRoot == ArtifactBackupContentStore.RELATIVE_ROOT) return@forEach
            val root = File(context.filesDir, relativeRoot)
            if (!root.exists()) return@forEach
            root.walkTopDown()
                .filter { it.isFile }
                .forEach { file ->
                    val relativePath = file.relativeTo(context.filesDir).invariantSeparatorsPath
                    codec.writeFileTreeEntry(zip, "files/$relativePath", file)
                    count += 1
                    bytes += file.length()
                }
        }
        if (mode == SyncMode.FULL) {
            val snapshot = createNovelSnapshotDirectory()
            val snapshotTree = File(snapshot, "workspace")
            try {
                // The retired workspace can still change during archive restore.
                val root = File(context.filesDir, NOVEL_WORKSPACE_ROOT)
                if (restoreWriteGate == null) {
                    if (root.exists()) root.copyRecursively(snapshotTree, overwrite = true)
                } else {
                    restoreWriteGate.withCurrentWriterOrCancel {
                        if (root.exists()) root.copyRecursively(snapshotTree, overwrite = true)
                    }
                }
                snapshotTree.walkTopDown().filter { it.isFile }.forEach { file ->
                    val relativePath = file.relativeTo(snapshotTree).invariantSeparatorsPath
                    codec.writeFileTreeEntry(zip, "files/$NOVEL_WORKSPACE_ROOT/$relativePath", file)
                    count += 1
                    bytes += file.length()
                }
            } finally {
                snapshot.deleteRecursively()
            }
        }
        return SyncDatasetSummary("files", recordCount = count, byteCount = bytes)
    }

    private fun createNovelSnapshotDirectory(): File {
        val dir = File(context.cacheDir, "sync").apply { mkdirs() }
        return File.createTempFile("amber-novel-snapshot-", ".tmp", dir).apply {
            check(delete() && mkdirs()) { "Unable to create sync staging directory" }
        }.canonicalFile
    }

    private fun String.sqlName(): String = "\"${replace("\"", "\"\"")}\""
}
