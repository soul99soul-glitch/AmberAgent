package app.amber.agent.data.sync

import app.amber.core.sync.core.SyncArchiveManager
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
