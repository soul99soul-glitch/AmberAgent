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
