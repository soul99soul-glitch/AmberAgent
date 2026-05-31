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

    companion object {
        private const val TEST_DB = "app-database-migration-test"
    }
}
