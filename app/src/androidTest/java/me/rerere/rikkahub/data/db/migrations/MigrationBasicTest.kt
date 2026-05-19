package me.rerere.rikkahub.data.db.migrations

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.rikkahub.data.db.AppDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MigrationBasicTest {
    private val testDb = "migration-basic-test"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    fun migrate6To7ValidatesSchema() {
        helper.createDatabase(testDb, 6).close()
        helper.runMigrationsAndValidate(testDb, 7, true, Migration_6_7).close()
    }

    @Test
    fun migrate13To14ValidatesSchema() {
        helper.createDatabase(testDb, 13).close()
        helper.runMigrationsAndValidate(testDb, 14, true, Migration_13_14).close()
    }

    @Test
    fun migrate14To15ValidatesSchema() {
        helper.createDatabase(testDb, 14).close()
        helper.runMigrationsAndValidate(testDb, 15, true, Migration_14_15).close()
    }

    @Test
    fun migrate15To16ValidatesSchema() {
        helper.createDatabase(testDb, 15).close()
        helper.runMigrationsAndValidate(testDb, 16, true, Migration_15_16).close()
    }

    @Test
    fun migrate18To19AddsConversationContextTables() {
        helper.createDatabase(testDb, 18).close()
        val db = helper.runMigrationsAndValidate(testDb, 19, true, Migration_18_19)
        db.query("SELECT COUNT(*) FROM conversation_compact").close()
        db.query("SELECT COUNT(*) FROM conversation_context_event").close()
        db.close()
    }

    @Test
    fun migrate26To27AddsTodayBoardHotlistTables() {
        helper.createDatabase(testDb, 26).close()
        val db = helper.runMigrationsAndValidate(testDb, 27, true, Migration_26_27)
        db.query("SELECT COUNT(*) FROM hot_list_cache").close()
        db.query("SELECT COUNT(*) FROM hot_topic_cache").close()
        db.query("SELECT COUNT(*) FROM deep_read_cache").close()
        db.query("SELECT COUNT(*) FROM hot_list_source").close()
        db.close()
    }

    @Test
    fun migrate27To28AddsMiniAppTable() {
        helper.createDatabase(testDb, 27).close()
        val db = helper.runMigrationsAndValidate(testDb, 28, true, Migration_27_28)
        db.query("SELECT COUNT(*) FROM mini_app").close()
        db.close()
    }

    @Test
    fun migrate28To29AddsMiniAppPermissionAndVersionTables() {
        val legacy = helper.createDatabase(testDb, 28)
        legacy.execSQL(
            """
            INSERT INTO mini_app (
                id, title, description, htmlContent, sourceConversationId, sourceMessageId,
                iconEmoji, category, permissionsJson, pinned, runCount, boardSummary,
                version, htmlHash, createdAt, updatedAt
            ) VALUES (
                'app-1', 'Legacy', 'Old app', '<!DOCTYPE html><html></html>', NULL, NULL,
                NULL, 'tool', '[]', 0, 0, NULL, 3, 'hash-1', 1000, 1000
            )
            """.trimIndent()
        )
        legacy.close()
        val db = helper.runMigrationsAndValidate(testDb, 29, true, Migration_28_29)
        db.query("SELECT COUNT(*) FROM mini_app_grant").close()
        db.query("SELECT appId, versionNumber, htmlHash FROM mini_app_version WHERE appId = 'app-1'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("app-1", cursor.getString(0))
            assertEquals(3, cursor.getInt(1))
            assertEquals("hash-1", cursor.getString(2))
        }
        db.close()
    }
}
