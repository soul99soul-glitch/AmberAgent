package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val Migration_29_30 = object : Migration(29, 30) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `mini_app_audit_log` (
                `id` TEXT NOT NULL PRIMARY KEY,
                `appId` TEXT NOT NULL,
                `method` TEXT NOT NULL,
                `permission` TEXT NOT NULL,
                `summary` TEXT NOT NULL,
                `payloadHash` TEXT NOT NULL,
                `createdAt` INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_mini_app_audit_log_appId` ON `mini_app_audit_log` (`appId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_mini_app_audit_log_createdAt` ON `mini_app_audit_log` (`createdAt`)")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `mini_app_shared_data` (
                `namespace` TEXT NOT NULL,
                `key` TEXT NOT NULL,
                `value` TEXT NOT NULL,
                `lastWriterId` TEXT NOT NULL,
                `updatedAt` INTEGER NOT NULL,
                PRIMARY KEY(`namespace`, `key`)
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_mini_app_shared_data_namespace` ON `mini_app_shared_data` (`namespace`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_mini_app_shared_data_updatedAt` ON `mini_app_shared_data` (`updatedAt`)")
    }
}
