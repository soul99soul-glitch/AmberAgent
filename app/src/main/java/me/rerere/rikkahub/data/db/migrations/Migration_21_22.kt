package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val Migration_21_22 = object : Migration(21, 22) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `feishu_watched_doc` (
                `id` TEXT NOT NULL,
                `doc_url` TEXT NOT NULL,
                `doc_title` TEXT NOT NULL,
                `enabled` INTEGER NOT NULL DEFAULT 1,
                `change_threshold` INTEGER NOT NULL DEFAULT 500,
                `check_interval_min` INTEGER NOT NULL DEFAULT 90,
                `last_checked_at` INTEGER,
                `last_changed_at` INTEGER,
                `notify_enabled` INTEGER NOT NULL DEFAULT 1,
                `created_at` INTEGER NOT NULL,
                `updated_at` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_feishu_watched_doc_enabled` ON `feishu_watched_doc` (`enabled`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_feishu_watched_doc_last_checked_at` ON `feishu_watched_doc` (`last_checked_at`)")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `feishu_doc_snapshot` (
                `id` TEXT NOT NULL,
                `watched_doc_id` TEXT NOT NULL,
                `plain_text` TEXT NOT NULL,
                `content_hash` TEXT NOT NULL,
                `heading_list_json` TEXT NOT NULL DEFAULT '[]',
                `captured_at` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_feishu_doc_snapshot_watched_doc_id` ON `feishu_doc_snapshot` (`watched_doc_id`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_feishu_doc_snapshot_captured_at` ON `feishu_doc_snapshot` (`captured_at`)")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `feishu_doc_change` (
                `id` TEXT NOT NULL,
                `watched_doc_id` TEXT NOT NULL,
                `from_snapshot_id` TEXT,
                `to_snapshot_id` TEXT NOT NULL,
                `added_chars` INTEGER NOT NULL DEFAULT 0,
                `removed_chars` INTEGER NOT NULL DEFAULT 0,
                `effective_change` INTEGER NOT NULL DEFAULT 0,
                `changed_sections_json` TEXT NOT NULL DEFAULT '[]',
                `diff_summary` TEXT,
                `ai_analysis_json` TEXT,
                `status` TEXT NOT NULL DEFAULT 'new',
                `notified` INTEGER NOT NULL DEFAULT 0,
                `created_at` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_feishu_doc_change_watched_doc_id` ON `feishu_doc_change` (`watched_doc_id`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_feishu_doc_change_status` ON `feishu_doc_change` (`status`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_feishu_doc_change_created_at` ON `feishu_doc_change` (`created_at`)")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `feishu_doc_dependency` (
                `id` TEXT NOT NULL,
                `upstream_url` TEXT NOT NULL,
                `downstream_url` TEXT NOT NULL,
                `upstream_label` TEXT NOT NULL DEFAULT '',
                `downstream_label` TEXT NOT NULL DEFAULT '',
                `relation_note` TEXT NOT NULL DEFAULT '',
                `enabled` INTEGER NOT NULL DEFAULT 1,
                `created_at` INTEGER NOT NULL,
                `updated_at` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_feishu_doc_dependency_upstream_url` ON `feishu_doc_dependency` (`upstream_url`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_feishu_doc_dependency_downstream_url` ON `feishu_doc_dependency` (`downstream_url`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_feishu_doc_dependency_enabled` ON `feishu_doc_dependency` (`enabled`)")
    }
}
