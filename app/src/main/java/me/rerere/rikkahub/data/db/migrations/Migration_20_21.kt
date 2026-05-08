package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val Migration_20_21 = object : Migration(20, 21) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `memory_dream_plan` (
                `id` TEXT NOT NULL,
                `plan_json` TEXT NOT NULL,
                `status` TEXT NOT NULL,
                `source` TEXT NOT NULL,
                `merge_count` INTEGER NOT NULL,
                `promote_count` INTEGER NOT NULL,
                `archive_count` INTEGER NOT NULL,
                `ignore_candidate_count` INTEGER NOT NULL,
                `created_at` INTEGER NOT NULL,
                `applied_at` INTEGER,
                `dismissed_at` INTEGER,
                PRIMARY KEY(`id`)
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_memory_dream_plan_status` ON `memory_dream_plan` (`status`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_memory_dream_plan_source` ON `memory_dream_plan` (`source`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_memory_dream_plan_created_at` ON `memory_dream_plan` (`created_at`)")
    }
}
