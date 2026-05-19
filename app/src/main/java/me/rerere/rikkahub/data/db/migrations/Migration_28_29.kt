package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val Migration_28_29 = object : Migration(28, 29) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `mini_app_grant` (
                `appId` TEXT NOT NULL,
                `permission` TEXT NOT NULL,
                `decision` TEXT NOT NULL,
                `updatedAt` INTEGER NOT NULL,
                PRIMARY KEY(`appId`, `permission`)
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_mini_app_grant_appId` ON `mini_app_grant` (`appId`)")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `mini_app_version` (
                `appId` TEXT NOT NULL,
                `versionNumber` INTEGER NOT NULL,
                `htmlContent` TEXT NOT NULL,
                `htmlHash` TEXT NOT NULL,
                `changeNote` TEXT,
                `createdAt` INTEGER NOT NULL,
                PRIMARY KEY(`appId`, `versionNumber`)
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_mini_app_version_appId` ON `mini_app_version` (`appId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_mini_app_version_createdAt` ON `mini_app_version` (`createdAt`)")
        db.execSQL(
            """
            INSERT OR IGNORE INTO `mini_app_version` (
                `appId`, `versionNumber`, `htmlContent`, `htmlHash`, `changeNote`, `createdAt`
            )
            SELECT
                `id`,
                `version`,
                `htmlContent`,
                COALESCE(`htmlHash`, ''),
                'Initial import',
                `createdAt`
            FROM `mini_app`
            """.trimIndent()
        )
    }
}
