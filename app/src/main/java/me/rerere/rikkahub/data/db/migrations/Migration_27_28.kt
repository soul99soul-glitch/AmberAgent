package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val Migration_27_28 = object : Migration(27, 28) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `mini_app` (
                `id` TEXT NOT NULL,
                `title` TEXT NOT NULL,
                `description` TEXT NOT NULL,
                `htmlContent` TEXT NOT NULL,
                `sourceConversationId` TEXT,
                `sourceMessageId` TEXT,
                `iconEmoji` TEXT,
                `category` TEXT,
                `permissionsJson` TEXT NOT NULL,
                `pinned` INTEGER NOT NULL,
                `runCount` INTEGER NOT NULL,
                `boardSummary` TEXT,
                `version` INTEGER NOT NULL,
                `htmlHash` TEXT,
                `createdAt` INTEGER NOT NULL,
                `updatedAt` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_mini_app_pinned` ON `mini_app` (`pinned`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_mini_app_updatedAt` ON `mini_app` (`updatedAt`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_mini_app_sourceConversationId` ON `mini_app` (`sourceConversationId`)")
    }
}
