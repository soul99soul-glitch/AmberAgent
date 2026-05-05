package me.rerere.rikkahub.data.db.migrations

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.rikkahub.data.db.AppDatabase
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
}
