package app.amber.core.agent.store

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask

/**
 * iOS-specific factory for [AgentRuntimeDatabase].
 *
 * Swift must call [createDatabase] instead of `AgentRuntimeDatabaseConstructor.initialize()`
 * to ensure the [BundledSQLiteDriver] and persistent file path are properly configured.
 * The generated constructor alone does not set a driver or a database path.
 */
object IosDatabaseFactory {

    fun createDatabase(): AgentRuntimeDatabase {
        val dbFilePath = documentDirectory() + "/agent_runtime.db"
        return createDatabase(atFilePath = dbFilePath)
    }

    /**
     * 指定路径建库（同一迁移链）。测试用它做隔离库，避免污染生产
     * `Documents/agent_runtime.db`（既有测试先例直接写生产库文件）。
     */
    fun createDatabase(atFilePath: String): AgentRuntimeDatabase {
        return Room.databaseBuilder<AgentRuntimeDatabase>(name = atFilePath)
            .setDriver(BundledSQLiteDriver())
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
            .build()
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun documentDirectory(): String {
        return NSSearchPathForDirectoriesInDomains(
            NSDocumentDirectory,
            NSUserDomainMask,
            true,
        ).first() as String
    }
}
