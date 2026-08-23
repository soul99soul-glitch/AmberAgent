package app.amber.agent.data.sync

import app.amber.core.sync.core.SyncArchiveManager
import app.amber.core.sync.core.SyncDatasetSummary
import app.amber.core.sync.core.SyncPayloadManifest
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncArchiveTableCoverageTest {
    @Test
    fun syncTablesCoverDurableAppDatabaseTables() {
        val schemaTables = appDatabaseSchemaTables()
            .map { it.lowercase() }
            .filterNot { it in ephemeralTables }
            .toSet()
        val syncTables = SyncArchiveManager.SYNC_TABLES
            .map { it.lowercase() }
            .toSet()

        assertEquals(emptySet<String>(), schemaTables - syncTables)
        assertEquals(emptySet<String>(), syncTables - schemaTables)
    }

    @Test
    fun preservedConversationTablesIncludeDerivedStats() {
        assertTrue("message_node_stat" in SyncArchiveManager.CONVERSATION_TABLES)
        assertTrue("message_day_stat" in SyncArchiveManager.CONVERSATION_TABLES)
    }

    @Test
    fun payloadManifestMustCoverEveryDurableDataset() {
        val complete = completePayloadManifest()
        SyncArchiveManager.validatePayloadManifestForRestore(complete)

        val missingTable = complete.copy(
            datasets = complete.datasets.filterNot { it.id == "table:message_node" },
        )
        val error = runCatching {
            SyncArchiveManager.validatePayloadManifestForRestore(missingTable)
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertTrue(error?.message.orEmpty().contains("table:message_node"))
    }

    @Test
    fun payloadManifestRejectsDuplicateDatasetIds() {
        val complete = completePayloadManifest()
        val duplicate = complete.copy(
            datasets = complete.datasets.dropLast(1) + SyncDatasetSummary("settings", 1),
        )

        assertTrue(
            runCatching { SyncArchiveManager.validatePayloadManifestForRestore(duplicate) }
                .exceptionOrNull() is IllegalArgumentException,
        )
    }

    @Test
    fun configOnlyManifestDoesNotRequireUnrelatedTablesOrSecrets() {
        SyncArchiveManager.validatePayloadManifestForRestore(
            SyncPayloadManifest(datasets = listOf(SyncDatasetSummary("settings", 1))),
            requireComplete = false,
        )
    }

    @Test
    fun fullRestoreRejectsTruncatedTableRowsBeforeMutation() {
        val manifest = completePayloadManifest().copy(
            datasets = completePayloadManifest().datasets.map { dataset ->
                if (dataset.id == "table:message_node") dataset.copy(recordCount = 2) else dataset
            },
        )
        val actualRows = SyncArchiveManager.SYNC_TABLES.associateWith { table ->
            if (table == "message_node") 1 else 0
        }

        val error = runCatching {
            SyncArchiveManager.validatePayloadContentForRestore(
                manifest = manifest,
                tableRowCounts = actualRows,
                fileCount = 0,
                fileBytes = 0L,
            )
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertTrue(error?.message.orEmpty().contains("message_node"))
    }

    @Test
    fun fullRestoreRejectsMissingFilePayloadDeclaredByManifest() {
        val manifest = completePayloadManifest().copy(
            datasets = completePayloadManifest().datasets.map { dataset ->
                if (dataset.id == "files") dataset.copy(recordCount = 1, byteCount = 8L) else dataset
            },
        )

        val error = runCatching {
            SyncArchiveManager.validatePayloadContentForRestore(
                manifest = manifest,
                tableRowCounts = SyncArchiveManager.SYNC_TABLES.associateWith { 0 },
                fileCount = 0,
                fileBytes = 0L,
            )
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertTrue(error?.message.orEmpty().contains("文件"))
    }

    @Test
    fun fullRestoreRejectsTruncatedFileBytesBeforeMutation() {
        val manifest = completePayloadManifest().copy(
            datasets = completePayloadManifest().datasets.map { dataset ->
                if (dataset.id == "files") dataset.copy(recordCount = 1, byteCount = 8L) else dataset
            },
        )

        val error = runCatching {
            SyncArchiveManager.validatePayloadContentForRestore(
                manifest = manifest,
                tableRowCounts = SyncArchiveManager.SYNC_TABLES.associateWith { 0 },
                fileCount = 1,
                fileBytes = 7L,
            )
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertTrue(error?.message.orEmpty().contains("文件"))
    }

    private fun completePayloadManifest() = SyncPayloadManifest(
        datasets = listOf(
            SyncDatasetSummary("settings", 1),
            SyncDatasetSummary("secrets", 1),
            SyncDatasetSummary("files", 0),
        ) + SyncArchiveManager.SYNC_TABLES.map { table ->
            SyncDatasetSummary("table:$table", 0)
        },
    )

    private fun appDatabaseSchemaTables(): List<String> {
        val schema = listOf(
            File("schemas/app.amber.agent.data.db.AppDatabase/4.json"),
            File("app/schemas/app.amber.agent.data.db.AppDatabase/4.json"),
        ).firstOrNull { it.exists() } ?: error("AppDatabase schema 4.json not found")

        val root = Json.parseToJsonElement(schema.readText()).jsonObject
        return root.getValue("database")
            .jsonObject
            .getValue("entities")
            .jsonArray
            .map { it.jsonObject.getValue("tableName").jsonPrimitive.content }
    }

    private companion object {
        val ephemeralTables = setOf(
            "hot_list_cache",
            "hot_topic_cache",
            "deep_read_cache",
        )
    }
}
