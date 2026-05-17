package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val Migration_25_26 = object : Migration(25, 26) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_message_node_conversation_id_node_index` " +
                "ON `message_node` (`conversation_id`, `node_index`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_ConversationEntity_assistant_id_is_pinned_update_at` " +
                "ON `ConversationEntity` (`assistant_id`, `is_pinned`, `update_at`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_ConversationEntity_is_pinned_update_at` " +
                "ON `ConversationEntity` (`is_pinned`, `update_at`)"
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `message_node_stat` (
                `node_id` TEXT NOT NULL,
                `conversation_id` TEXT NOT NULL,
                `total_messages` INTEGER NOT NULL,
                `prompt_tokens` INTEGER NOT NULL,
                `completion_tokens` INTEGER NOT NULL,
                `cached_tokens` INTEGER NOT NULL,
                PRIMARY KEY(`node_id`),
                FOREIGN KEY(`node_id`) REFERENCES `message_node`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_message_node_stat_conversation_id` ON `message_node_stat` (`conversation_id`)")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `message_day_stat` (
                `node_id` TEXT NOT NULL,
                `day` TEXT NOT NULL,
                `count` INTEGER NOT NULL,
                PRIMARY KEY(`node_id`, `day`),
                FOREIGN KEY(`node_id`) REFERENCES `message_node`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_message_day_stat_day` ON `message_day_stat` (`day`)")

        db.execSQL(
            """
            INSERT OR REPLACE INTO `message_node_stat` (
                `node_id`,
                `conversation_id`,
                `total_messages`,
                `prompt_tokens`,
                `completion_tokens`,
                `cached_tokens`
            )
            SELECT
                mn.`id`,
                mn.`conversation_id`,
                COUNT(j.value),
                COALESCE(SUM(CAST(json_extract(j.value, '$.usage.promptTokens') AS INTEGER)), 0),
                COALESCE(SUM(CAST(json_extract(j.value, '$.usage.completionTokens') AS INTEGER)), 0),
                COALESCE(SUM(CAST(json_extract(j.value, '$.usage.cachedTokens') AS INTEGER)), 0)
            FROM `message_node` mn, json_each(mn.`messages`) j
            GROUP BY mn.`id`, mn.`conversation_id`
            """.trimIndent()
        )

        db.execSQL(
            """
            INSERT OR REPLACE INTO `message_day_stat` (`node_id`, `day`, `count`)
            SELECT
                mn.`id`,
                substr(json_extract(j.value, '$.createdAt'), 1, 10) AS day,
                COUNT(*)
            FROM `message_node` mn, json_each(mn.`messages`) j
            WHERE json_extract(j.value, '$.role') = 'user'
                AND substr(json_extract(j.value, '$.createdAt'), 1, 10) IS NOT NULL
                AND substr(json_extract(j.value, '$.createdAt'), 1, 10) != ''
            GROUP BY mn.`id`, day
            """.trimIndent()
        )
    }
}
