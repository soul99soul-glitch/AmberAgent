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
        return Room.databaseBuilder<AgentRuntimeDatabase>(name = dbFilePath)
            .setDriver(BundledSQLiteDriver())
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
