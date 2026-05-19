package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val Migration_26_27 = object : Migration(26, 27) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `hot_list_cache` (
                `provider_id` TEXT NOT NULL,
                `provider_name` TEXT NOT NULL,
                `items_json` TEXT NOT NULL,
                `fetched_at` INTEGER NOT NULL,
                `updated_at` INTEGER NOT NULL,
                `last_error` TEXT,
                PRIMARY KEY(`provider_id`)
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_hot_list_cache_fetched_at` ON `hot_list_cache` (`fetched_at`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_hot_list_cache_provider_id` ON `hot_list_cache` (`provider_id`)")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `hot_topic_cache` (
                `topic_id` TEXT NOT NULL,
                `title` TEXT NOT NULL,
                `sources_json` TEXT NOT NULL,
                `source_count` INTEGER NOT NULL,
                `best_rank` INTEGER NOT NULL,
                `latest_fetched_at` INTEGER NOT NULL,
                `updated_at` INTEGER NOT NULL,
                PRIMARY KEY(`topic_id`)
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_hot_topic_cache_source_count` ON `hot_topic_cache` (`source_count`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_hot_topic_cache_best_rank` ON `hot_topic_cache` (`best_rank`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_hot_topic_cache_latest_fetched_at` ON `hot_topic_cache` (`latest_fetched_at`)")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `deep_read_cache` (
                `topic_id` TEXT NOT NULL,
                `title` TEXT NOT NULL,
                `output_json` TEXT NOT NULL,
                `created_at` INTEGER NOT NULL,
                `expires_at` INTEGER NOT NULL,
                `updated_at` INTEGER NOT NULL,
                PRIMARY KEY(`topic_id`)
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_deep_read_cache_topic_id` ON `deep_read_cache` (`topic_id`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_deep_read_cache_expires_at` ON `deep_read_cache` (`expires_at`)")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `hot_list_source` (
                `id` TEXT NOT NULL,
                `display_name` TEXT NOT NULL,
                `source_type` TEXT NOT NULL,
                `url` TEXT NOT NULL,
                `enabled` INTEGER NOT NULL,
                `field_mapping_json` TEXT NOT NULL,
                `sort_order` INTEGER NOT NULL,
                `created_at` INTEGER NOT NULL,
                `updated_at` INTEGER NOT NULL,
                `last_success_at` INTEGER,
                PRIMARY KEY(`id`)
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_hot_list_source_enabled` ON `hot_list_source` (`enabled`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_hot_list_source_sort_order` ON `hot_list_source` (`sort_order`)")
    }
}
