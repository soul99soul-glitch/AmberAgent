package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Today Board schema: 4 tables supporting signal ingestion, per-day board items, user
 * focus rules, and feedback weights for Level-1 learning.
 *
 * Schema follows the same handwritten-SQL pattern used by the Feishu Doc Radar migration
 * (Migration_21_22). Indices mirror DAO query patterns so common reads stay cheap even
 * as the signal table grows.
 */
val Migration_22_23 = object : Migration(22, 23) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // board_signal — raw captures from all collectors
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `board_signal` (
                `id` TEXT NOT NULL,
                `source_type` TEXT NOT NULL,
                `source_ref` TEXT NOT NULL,
                `title` TEXT NOT NULL,
                `content` TEXT NOT NULL,
                `content_hash` TEXT NOT NULL,
                `signal_time` INTEGER NOT NULL,
                `metadata_json` TEXT NOT NULL DEFAULT '{}',
                `processed` INTEGER NOT NULL DEFAULT 0,
                `processed_at` INTEGER,
                `created_at` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_board_signal_source_type` ON `board_signal` (`source_type`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_board_signal_processed` ON `board_signal` (`processed`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_board_signal_created_at` ON `board_signal` (`created_at`)")
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_board_signal_source_type_source_ref` " +
                "ON `board_signal` (`source_type`, `source_ref`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_board_signal_content_hash_source_type` " +
                "ON `board_signal` (`content_hash`, `source_type`)"
        )

        // board_item — agent-produced entries rendered on the board
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `board_item` (
                `id` TEXT NOT NULL,
                `title` TEXT NOT NULL,
                `source_type` TEXT NOT NULL,
                `source_ref` TEXT NOT NULL,
                `source_content` TEXT NOT NULL,
                `urgency` TEXT NOT NULL,
                `category` TEXT NOT NULL,
                `reason` TEXT NOT NULL,
                `suggestion` TEXT NOT NULL,
                `signal_time` INTEGER NOT NULL,
                `status` TEXT NOT NULL DEFAULT 'active',
                `completed_at` INTEGER,
                `dismissed_at` INTEGER,
                `board_date` TEXT NOT NULL,
                `created_at` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_board_item_board_date` ON `board_item` (`board_date`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_board_item_status` ON `board_item` (`status`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_board_item_urgency` ON `board_item` (`urgency`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_board_item_category` ON `board_item` (`category`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_board_item_created_at` ON `board_item` (`created_at`)")

        // board_focus_rule — natural-language user focus rules, fed into agent prompt
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `board_focus_rule` (
                `id` TEXT NOT NULL,
                `content` TEXT NOT NULL,
                `active` INTEGER NOT NULL DEFAULT 1,
                `sort_order` INTEGER NOT NULL DEFAULT 0,
                `created_at` INTEGER NOT NULL,
                `updated_at` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_board_focus_rule_active` ON `board_focus_rule` (`active`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_board_focus_rule_sort_order` ON `board_focus_rule` (`sort_order`)")

        // board_weight — Level-1 feedback weights; composite PK is (source_type, keyword)
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `board_weight` (
                `source_type` TEXT NOT NULL,
                `keyword` TEXT NOT NULL,
                `weight` INTEGER NOT NULL,
                `dismiss_count_7d` INTEGER NOT NULL DEFAULT 0,
                `last_action_at` INTEGER NOT NULL,
                PRIMARY KEY(`source_type`, `keyword`)
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_board_weight_source_type` ON `board_weight` (`source_type`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_board_weight_weight` ON `board_weight` (`weight`)")
    }
}
