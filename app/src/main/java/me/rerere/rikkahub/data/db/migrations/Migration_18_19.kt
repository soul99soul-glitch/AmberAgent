package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val Migration_18_19 = object : Migration(18, 19) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `conversation_compact` (
                `id` TEXT NOT NULL,
                `conversation_id` TEXT NOT NULL,
                `summary` TEXT NOT NULL,
                `level` INTEGER NOT NULL,
                `source_start_index` INTEGER NOT NULL,
                `source_end_index` INTEGER NOT NULL,
                `source_message_ids_json` TEXT NOT NULL,
                `token_estimate` INTEGER NOT NULL,
                `created_at` INTEGER NOT NULL,
                `updated_at` INTEGER NOT NULL,
                `status` TEXT NOT NULL,
                PRIMARY KEY(`id`),
                FOREIGN KEY(`conversation_id`) REFERENCES `ConversationEntity`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_conversation_compact_conversation_id` ON `conversation_compact` (`conversation_id`)")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `conversation_context_event` (
                `id` TEXT NOT NULL,
                `conversation_id` TEXT NOT NULL,
                `event_type` TEXT NOT NULL,
                `summary_id` TEXT,
                `message` TEXT NOT NULL,
                `created_at` INTEGER NOT NULL,
                PRIMARY KEY(`id`),
                FOREIGN KEY(`conversation_id`) REFERENCES `ConversationEntity`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_conversation_context_event_conversation_id` ON `conversation_context_event` (`conversation_id`)")
    }
}
