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
    fun migration_1_2_creates_board_task_tables() {
        helper.createDatabase(TEST_DB, 1).close()

        val db = helper.runMigrationsAndValidate(
            TEST_DB,
            2,
            true,
            AppDatabase.MIGRATION_1_2,
        )

        assertTrue(db.hasTable("board_task"))
        assertTrue(db.hasTable("board_task_event"))
        db.close()
    }

    @Test
    fun migration_2_3_creates_opportunity_tables_and_archives_suggested_tasks() {
        val createdAt = 1_000L
        val version2 = helper.createDatabase(TEST_DB, 2)
        version2.execSQL(
            """
            INSERT INTO board_task (
                id, source_type, source_ref, title, summary, state, risk_level,
                chip_text, display_board_date, created_at, updated_at
            ) VALUES (
                'suggested-task', 'calendar', 'meeting-1', 'Prepare', '', 'suggested', 'low',
                '建议处理', '2026-05-30', $createdAt, $createdAt
            )
            """.trimIndent()
        )
        version2.execSQL(
            """
            INSERT INTO board_task (
                id, source_type, source_ref, title, summary, state, risk_level,
                chip_text, display_board_date, created_at, updated_at
            ) VALUES (
                'active-task', 'calendar', 'meeting-2', 'Prepare active', '', 'in_progress', 'low',
                '正在处理', '2026-05-30', $createdAt, $createdAt
            )
            """.trimIndent()
        )
        version2.execSQL(
            """
            INSERT INTO board_task_event (
                id, task_id, type, message, metadata_json, ts
            ) VALUES (
                'suggested-event', 'suggested-task', 'created', 'created', '{}', $createdAt
            )
            """.trimIndent()
        )
        version2.close()

        val db = helper.runMigrationsAndValidate(
            TEST_DB,
            3,
            true,
            AppDatabase.MIGRATION_2_3,
        )

        assertTrue(db.hasTable("opportunity"))
        assertTrue(db.hasTable("reference_anchor"))
        assertEquals(1, db.countRows("opportunity", "id = 'suggested-task' AND status = 'suggested'"))
        assertEquals("dismissed", db.stringValue("board_task", "state", "id = 'suggested-task'"))
        assertEquals(1, db.countRows("board_task_event", "task_id = 'suggested-task'"))
        assertEquals(1, db.countRows("board_task", "id = 'active-task'"))
        db.close()
    }

    @Test
    fun migration_3_4_adds_artifact_json_and_preserves_board_task_rows() {
        val createdAt = 2_000L
        val version3 = helper.createDatabase(TEST_DB, 3)
        version3.execSQL(
            """
            INSERT INTO board_task (
                id, source_type, source_ref, title, summary, state, risk_level,
                chip_text, display_board_date, created_at, updated_at
            ) VALUES (
                'task-keep', 'opportunity', 'opp-1', 'Prepare', 'summary', 'waiting_user', 'low',
                '等待确认', '2026-05-31', $createdAt, $createdAt
            )
            """.trimIndent()
        )
        version3.close()

        val db = helper.runMigrationsAndValidate(
            TEST_DB,
            4,
            true,
            AppDatabase.MIGRATION_3_4,
        )

        // Existing row survives the column add, and the new column defaults to NULL.
        assertEquals(1, db.countRows("board_task", "id = 'task-keep'"))
        assertEquals(1, db.countRows("board_task", "id = 'task-keep' AND artifact_json IS NULL"))
        db.close()
    }

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

    private fun SupportSQLiteDatabase.hasTable(table: String): Boolean {
        query(
            "SELECT name FROM sqlite_master WHERE type='table' AND name=?",
            arrayOf(table),
        ).use { cursor ->
            return cursor.moveToFirst()
        }
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
