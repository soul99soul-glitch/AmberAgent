package app.amber.agent.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppDatabaseMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
    )

    @Test
    fun migration_4_5_adds_memory_supersede_columns_and_preserves_rows() {
        val createdAt = 3_000L
        val version4 = helper.createDatabase(TEST_DB, 4)
        version4.execSQL(
            """
            INSERT INTO memoryentity (
                id, assistant_id, content, scope, kind, source_conversation_id,
                source_message_ids_json, expires_at, confidence, pinned, archived,
                created_at, updated_at, last_used_at
            ) VALUES (
                1, '__long_term__', '用户偏好中文简洁回复。', 'long_term', 'user', NULL,
                '[]', NULL, 0.9, 0, 0, $createdAt, $createdAt, NULL
            )
            """.trimIndent()
        )
        version4.execSQL(
            """
            INSERT INTO memory_dream_plan (
                id, plan_json, status, source, merge_count, promote_count,
                archive_count, ignore_candidate_count, created_at, applied_at, dismissed_at
            ) VALUES (
                'plan-1', '{}', 'pending', 'auto', 0, 0, 0, 0, $createdAt, NULL, NULL
            )
            """.trimIndent()
        )
        version4.close()

        val db = helper.runMigrationsAndValidate(
            TEST_DB,
            5,
            true,
            AppDatabase.MIGRATION_4_5,
        )

        assertEquals(1, db.countRows("memoryentity", "id = 1"))
        assertEquals("[]", db.stringValue("memoryentity", "supersedes_ids_json", "id = 1"))
        assertEquals(1, db.countRows("memory_dream_plan", "id = 'plan-1'"))
        assertEquals(0, db.intValue("memory_dream_plan", "supersede_count", "id = 'plan-1'"))
        db.close()
    }

    @Test
    fun migration_15_16_folds_profile_history_without_touching_memory_buckets() {
        val oldProfile = "0950e2dc-9bd5-4801-afa3-aa887aa36b4e"
        val customProfile = "11111111-1111-1111-1111-111111111111"
        val amberAgentId = "7def1f55-3dd9-4a09-a95a-7d0c2554b346"
        val version15 = helper.createDatabase(TEST_DB, 15)
        version15.execSQL(
            "INSERT INTO conversationentity " +
                "(id, assistant_id, title, nodes, create_at, update_at, suggestions, is_pinned, auto_approve_tools, council_state) " +
                "VALUES ('old-conversation', '$oldProfile', 'old', '[]', 1, 2, '[]', 0, 0, " +
                "'{\"host_assistant_id\":\"$oldProfile\",\"objective\":\"keep\"}')"
        )
        version15.execSQL(
            "INSERT INTO conversationentity " +
                "(id, assistant_id, title, nodes, create_at, update_at, suggestions, is_pinned, auto_approve_tools, council_state) " +
                "VALUES ('custom-conversation', '$customProfile', 'custom', '[]', 3, 4, '[]', 1, 0, NULL)"
        )
        listOf(customProfile, "__global__", "__short_term__", "__long_term__")
            .forEachIndexed { index, owner ->
                version15.execSQL(
                    "INSERT INTO memoryentity " +
                        "(id, assistant_id, content, scope, kind, source_conversation_id, " +
                        "source_message_ids_json, supersedes_ids_json, expires_at, confidence, pinned, archived, " +
                        "created_at, updated_at, last_used_at, revision, source_run_id, source_trigger) " +
                        "VALUES (${index + 1}, '$owner', 'memory-$index', 'long_term', 'note', NULL, " +
                        "'[]', '[]', NULL, 1.0, 0, 0, 1, 1, NULL, 1, NULL, NULL)"
                )
            }
        version15.close()

        val db = helper.runMigrationsAndValidate(
            TEST_DB,
            16,
            true,
            AppDatabase.MIGRATION_15_16,
        )

        assertEquals(2, db.countRows("conversationentity", "assistant_id = '$amberAgentId'"))
        assertEquals(0, db.countRows("conversationentity", "assistant_id != '$amberAgentId'"))
        val council = db.stringValue("conversationentity", "council_state", "id = 'old-conversation'")
        assertTrue(council.contains(amberAgentId))
        assertTrue(council.contains("keep"))
        assertEquals(1, db.countRows("memoryentity", "assistant_id = '$amberAgentId'"))
        assertEquals(1, db.countRows("memoryentity", "assistant_id = '__global__'"))
        assertEquals(1, db.countRows("memoryentity", "assistant_id = '__short_term__'"))
        assertEquals(1, db.countRows("memoryentity", "assistant_id = '__long_term__'"))
        db.close()
    }

    private fun SupportSQLiteDatabase.countRows(table: String, where: String): Int {
        query("SELECT COUNT(*) FROM $table WHERE $where").use { cursor ->
            assertTrue(cursor.moveToFirst())
            return cursor.getInt(0)
        }
    }

    private fun SupportSQLiteDatabase.stringValue(table: String, column: String, where: String): String {
        query("SELECT $column FROM $table WHERE $where LIMIT 1").use { cursor ->
            assertTrue(cursor.moveToFirst())
            return cursor.getString(0)
        }
    }

    private fun SupportSQLiteDatabase.intValue(table: String, column: String, where: String): Int {
        query("SELECT $column FROM $table WHERE $where LIMIT 1").use { cursor ->
            assertTrue(cursor.moveToFirst())
            return cursor.getInt(0)
        }
    }

    companion object {
        private const val TEST_DB = "app-database-migration-test"
    }
}
