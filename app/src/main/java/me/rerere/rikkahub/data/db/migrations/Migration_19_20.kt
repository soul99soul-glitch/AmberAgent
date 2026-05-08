package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val Migration_19_20 = object : Migration(19, 20) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `MemoryEntity` ADD COLUMN `scope` TEXT NOT NULL DEFAULT 'long_term'")
        db.execSQL("ALTER TABLE `MemoryEntity` ADD COLUMN `kind` TEXT NOT NULL DEFAULT 'note'")
        db.execSQL("ALTER TABLE `MemoryEntity` ADD COLUMN `source_conversation_id` TEXT")
        db.execSQL("ALTER TABLE `MemoryEntity` ADD COLUMN `source_message_ids_json` TEXT NOT NULL DEFAULT '[]'")
        db.execSQL("ALTER TABLE `MemoryEntity` ADD COLUMN `expires_at` INTEGER")
        db.execSQL("ALTER TABLE `MemoryEntity` ADD COLUMN `confidence` REAL NOT NULL DEFAULT 1.0")
        db.execSQL("ALTER TABLE `MemoryEntity` ADD COLUMN `pinned` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `MemoryEntity` ADD COLUMN `archived` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `MemoryEntity` ADD COLUMN `created_at` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `MemoryEntity` ADD COLUMN `updated_at` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `MemoryEntity` ADD COLUMN `last_used_at` INTEGER")

        db.execSQL("UPDATE `MemoryEntity` SET scope = 'core', kind = 'note' WHERE assistant_id = '__global__'")
        db.execSQL("UPDATE `MemoryEntity` SET scope = 'short_term', kind = 'project' WHERE assistant_id = '__short_term__'")
        db.execSQL("UPDATE `MemoryEntity` SET scope = 'long_term', kind = 'note' WHERE assistant_id = '__long_term__'")
        db.execSQL(
            """
            UPDATE `MemoryEntity`
            SET created_at = CAST(strftime('%s','now') AS INTEGER) * 1000,
                updated_at = CAST(strftime('%s','now') AS INTEGER) * 1000
            WHERE created_at = 0
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `memory_candidate` (
                `id` TEXT NOT NULL,
                `content` TEXT NOT NULL,
                `scope` TEXT NOT NULL,
                `kind` TEXT NOT NULL,
                `source_conversation_id` TEXT,
                `source_message_ids_json` TEXT NOT NULL,
                `expires_at` INTEGER,
                `confidence` REAL NOT NULL,
                `reason` TEXT NOT NULL,
                `sensitive` INTEGER NOT NULL,
                `status` TEXT NOT NULL,
                `created_at` INTEGER NOT NULL,
                `updated_at` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `memory_event` (
                `id` TEXT NOT NULL,
                `event_type` TEXT NOT NULL,
                `conversation_id` TEXT,
                `memory_id` INTEGER,
                `candidate_id` TEXT,
                `model_id` TEXT,
                `message` TEXT NOT NULL,
                `duration_ms` INTEGER,
                `message_count` INTEGER,
                `created_at` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_memory_event_conversation_id` ON `memory_event` (`conversation_id`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_memory_event_memory_id` ON `memory_event` (`memory_id`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_memory_event_candidate_id` ON `memory_event` (`candidate_id`)")
    }
}
