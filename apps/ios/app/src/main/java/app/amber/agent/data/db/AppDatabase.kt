package app.amber.agent.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import app.amber.ai.core.TokenUsage
import app.amber.agent.data.db.dao.ConversationDAO
import app.amber.agent.data.db.dao.ConversationCompactDAO
import app.amber.agent.data.db.dao.ConversationContextEventDAO
import app.amber.agent.data.db.dao.BoardFocusRuleDAO
import app.amber.agent.data.db.dao.BoardItemDAO
import app.amber.agent.data.db.dao.BoardSignalDAO
import app.amber.agent.data.db.dao.BoardTaskDao
import app.amber.agent.data.db.dao.BoardTaskEventDao
import app.amber.agent.data.db.dao.BoardWeightDAO
import app.amber.agent.data.db.dao.FeishuDocChangeDAO
import app.amber.agent.data.db.dao.FeishuDocDependencyDAO
import app.amber.agent.data.db.dao.FeishuDocSnapshotDAO
import app.amber.agent.data.db.dao.FeishuWatchedDocDAO
import app.amber.agent.data.db.dao.FavoriteDAO
import app.amber.agent.data.db.dao.GenMediaDAO
import app.amber.agent.data.db.dao.HotListDAO
import app.amber.agent.data.db.dao.ManagedFileDAO
import app.amber.agent.data.db.dao.MemoryCandidateDAO
import app.amber.agent.data.db.dao.MemoryDAO
import app.amber.agent.data.db.dao.MemoryDreamPlanDAO
import app.amber.agent.data.db.dao.MemoryEventDAO
import app.amber.agent.data.db.dao.MessageNodeDAO
import app.amber.agent.data.db.dao.MessageStatsDAO
import app.amber.agent.data.db.dao.MiniAppAuditLogDAO
import app.amber.agent.data.db.dao.MiniAppDAO
import app.amber.agent.data.db.dao.MiniAppGrantDAO
import app.amber.agent.data.db.dao.MiniAppSharedDataDAO
import app.amber.agent.data.db.dao.MiniAppVersionDAO
import app.amber.agent.data.db.dao.OpportunityDao
import app.amber.agent.data.db.dao.ReferenceAnchorDao
import app.amber.agent.data.db.entity.ConversationEntity
import app.amber.agent.data.db.entity.ConversationCompactEntity
import app.amber.agent.data.db.entity.ConversationContextEventEntity
import app.amber.agent.data.db.entity.BoardFocusRuleEntity
import app.amber.agent.data.db.entity.BoardItemEntity
import app.amber.agent.data.db.entity.BoardSignalEntity
import app.amber.agent.data.db.entity.BoardTaskEntity
import app.amber.agent.data.db.entity.BoardTaskEventEntity
import app.amber.agent.data.db.entity.BoardWeightEntity
import app.amber.agent.data.db.entity.DailyReviewEntity
import app.amber.agent.data.db.entity.DocSubscriptionEntity
import app.amber.agent.data.db.entity.DocChangeLogEntity
import app.amber.agent.data.db.dao.DailyReviewDAO
import app.amber.agent.data.db.dao.DocSubscriptionDAO
import app.amber.agent.data.db.dao.DocChangeLogDAO
import app.amber.agent.data.db.entity.FeishuDocChangeEntity
import app.amber.agent.data.db.entity.FeishuDocDependencyEntity
import app.amber.agent.data.db.entity.FeishuDocSnapshotEntity
import app.amber.agent.data.db.entity.FeishuWatchedDocEntity
import app.amber.agent.data.db.entity.FavoriteEntity
import app.amber.agent.data.db.entity.GenMediaEntity
import app.amber.agent.data.db.entity.DeepReadCacheEntity
import app.amber.agent.data.db.entity.HotListCacheEntity
import app.amber.agent.data.db.entity.HotListSourceEntity
import app.amber.agent.data.db.entity.HotTopicCacheEntity
import app.amber.agent.data.db.entity.ManagedFileEntity
import app.amber.agent.data.db.entity.MemoryCandidateEntity
import app.amber.agent.data.db.entity.MemoryDreamPlanEntity
import app.amber.agent.data.db.entity.MemoryEntity
import app.amber.agent.data.db.entity.MemoryEventEntity
import app.amber.agent.data.db.entity.MessageDayStatEntity
import app.amber.agent.data.db.entity.MessageNodeEntity
import app.amber.agent.data.db.entity.MessageNodeStatEntity
import app.amber.agent.data.db.entity.MiniAppAuditLogEntity
import app.amber.agent.data.db.entity.MiniAppEntity
import app.amber.agent.data.db.entity.MiniAppGrantEntity
import app.amber.agent.data.db.entity.MiniAppSharedDataEntity
import app.amber.agent.data.db.entity.MiniAppVersionEntity
import app.amber.agent.data.db.entity.OpportunityEntity
import app.amber.agent.data.db.entity.ReferenceAnchorEntity
import app.amber.core.utils.JsonInstant

@Database(
    entities = [
        ConversationEntity::class,
        MemoryEntity::class,
        GenMediaEntity::class,
        MessageNodeEntity::class,
        ManagedFileEntity::class,
        FavoriteEntity::class,
        ConversationCompactEntity::class,
        ConversationContextEventEntity::class,
        MemoryCandidateEntity::class,
        MemoryEventEntity::class,
        MemoryDreamPlanEntity::class,
        FeishuWatchedDocEntity::class,
        FeishuDocSnapshotEntity::class,
        FeishuDocChangeEntity::class,
        FeishuDocDependencyEntity::class,
        BoardSignalEntity::class,
        BoardItemEntity::class,
        BoardTaskEntity::class,
        BoardTaskEventEntity::class,
        BoardFocusRuleEntity::class,
        BoardWeightEntity::class,
        DailyReviewEntity::class,
        DocSubscriptionEntity::class,
        DocChangeLogEntity::class,
        MessageNodeStatEntity::class,
        MessageDayStatEntity::class,
        HotListCacheEntity::class,
        HotTopicCacheEntity::class,
        DeepReadCacheEntity::class,
        HotListSourceEntity::class,
        MiniAppEntity::class,
        MiniAppGrantEntity::class,
        MiniAppVersionEntity::class,
        MiniAppAuditLogEntity::class,
        MiniAppSharedDataEntity::class,
        OpportunityEntity::class,
        ReferenceAnchorEntity::class,
    ],
    version = 5
)
@TypeConverters(TokenUsageConverter::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDAO

    abstract fun conversationCompactDao(): ConversationCompactDAO

    abstract fun conversationContextEventDao(): ConversationContextEventDAO

    abstract fun memoryDao(): MemoryDAO

    abstract fun memoryCandidateDao(): MemoryCandidateDAO

    abstract fun memoryEventDao(): MemoryEventDAO

    abstract fun memoryDreamPlanDao(): MemoryDreamPlanDAO

    abstract fun genMediaDao(): GenMediaDAO

    abstract fun messageNodeDao(): MessageNodeDAO

    abstract fun messageStatsDao(): MessageStatsDAO

    abstract fun managedFileDao(): ManagedFileDAO

    abstract fun favoriteDao(): FavoriteDAO

    abstract fun feishuWatchedDocDao(): FeishuWatchedDocDAO

    abstract fun feishuDocSnapshotDao(): FeishuDocSnapshotDAO

    abstract fun feishuDocChangeDao(): FeishuDocChangeDAO

    abstract fun feishuDocDependencyDao(): FeishuDocDependencyDAO

    abstract fun boardSignalDao(): BoardSignalDAO

    abstract fun boardItemDao(): BoardItemDAO

    abstract fun boardTaskDao(): BoardTaskDao

    abstract fun boardTaskEventDao(): BoardTaskEventDao

    abstract fun boardFocusRuleDao(): BoardFocusRuleDAO

    abstract fun boardWeightDao(): BoardWeightDAO

    abstract fun dailyReviewDao(): DailyReviewDAO

    abstract fun hotListDao(): HotListDAO

    abstract fun docSubscriptionDao(): DocSubscriptionDAO

    abstract fun docChangeLogDao(): DocChangeLogDAO

    abstract fun miniAppDao(): MiniAppDAO

    abstract fun miniAppGrantDao(): MiniAppGrantDAO

    abstract fun miniAppVersionDao(): MiniAppVersionDAO

    abstract fun miniAppAuditLogDao(): MiniAppAuditLogDAO

    abstract fun miniAppSharedDataDao(): MiniAppSharedDataDAO

    abstract fun opportunityDao(): OpportunityDao

    abstract fun referenceAnchorDao(): ReferenceAnchorDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `board_task` (
                        `id` TEXT NOT NULL,
                        `source_type` TEXT NOT NULL,
                        `source_ref` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `summary` TEXT NOT NULL,
                        `state` TEXT NOT NULL,
                        `risk_level` TEXT NOT NULL,
                        `chip_text` TEXT NOT NULL,
                        `display_board_date` TEXT NOT NULL,
                        `created_at` INTEGER NOT NULL,
                        `updated_at` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_board_task_source_type_source_ref` " +
                        "ON `board_task` (`source_type`, `source_ref`)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_board_task_state` ON `board_task` (`state`)")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_board_task_display_board_date` " +
                        "ON `board_task` (`display_board_date`)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_board_task_updated_at` ON `board_task` (`updated_at`)")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `board_task_event` (
                        `id` TEXT NOT NULL,
                        `task_id` TEXT NOT NULL,
                        `type` TEXT NOT NULL,
                        `message` TEXT NOT NULL,
                        `metadata_json` TEXT NOT NULL,
                        `ts` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_board_task_event_task_id_ts` " +
                        "ON `board_task_event` (`task_id`, `ts`)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_board_task_event_type` ON `board_task_event` (`type`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_board_task_event_ts` ON `board_task_event` (`ts`)")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `opportunity` (
                        `id` TEXT NOT NULL,
                        `dedupe_key` TEXT NOT NULL,
                        `opportunity_type` TEXT NOT NULL,
                        `source_type` TEXT NOT NULL,
                        `source_ref` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `summary` TEXT NOT NULL,
                        `evidence_json` TEXT NOT NULL,
                        `score_json` TEXT NOT NULL,
                        `confidence` REAL NOT NULL,
                        `status` TEXT NOT NULL,
                        `suggested_actions_json` TEXT NOT NULL,
                        `due_at` INTEGER,
                        `trigger_at` INTEGER,
                        `dispatched_task_id` TEXT,
                        `dismissed_reason` TEXT,
                        `mute_scope` TEXT,
                        `expires_at` INTEGER,
                        `created_at` INTEGER NOT NULL,
                        `updated_at` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_opportunity_dedupe_key` " +
                        "ON `opportunity` (`dedupe_key`)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_opportunity_status` ON `opportunity` (`status`)")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_opportunity_opportunity_type` " +
                        "ON `opportunity` (`opportunity_type`)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_opportunity_trigger_at` ON `opportunity` (`trigger_at`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_opportunity_due_at` ON `opportunity` (`due_at`)")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `reference_anchor` (
                        `id` TEXT NOT NULL,
                        `dedupe_key` TEXT NOT NULL,
                        `dependency_id` TEXT NOT NULL,
                        `my_doc_ref` TEXT NOT NULL,
                        `my_claim_text` TEXT NOT NULL,
                        `upstream_doc_ref` TEXT NOT NULL,
                        `upstream_hint` TEXT NOT NULL,
                        `baseline_value` TEXT NOT NULL,
                        `last_value` TEXT,
                        `evidence_json` TEXT NOT NULL,
                        `score_json` TEXT NOT NULL,
                        `match_confidence` REAL NOT NULL,
                        `status` TEXT NOT NULL,
                        `confirmation_mode` TEXT NOT NULL,
                        `last_checked_at` INTEGER,
                        `created_at` INTEGER NOT NULL,
                        `updated_at` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_reference_anchor_dedupe_key` " +
                        "ON `reference_anchor` (`dedupe_key`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_reference_anchor_dependency_id` " +
                        "ON `reference_anchor` (`dependency_id`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_reference_anchor_my_doc_ref` " +
                        "ON `reference_anchor` (`my_doc_ref`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_reference_anchor_upstream_doc_ref` " +
                        "ON `reference_anchor` (`upstream_doc_ref`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_reference_anchor_status` " +
                        "ON `reference_anchor` (`status`)"
                )
                db.execSQL(
                    """
                    INSERT OR IGNORE INTO `opportunity` (
                        `id`,
                        `dedupe_key`,
                        `opportunity_type`,
                        `source_type`,
                        `source_ref`,
                        `title`,
                        `summary`,
                        `evidence_json`,
                        `score_json`,
                        `confidence`,
                        `status`,
                        `suggested_actions_json`,
                        `due_at`,
                        `trigger_at`,
                        `dispatched_task_id`,
                        `dismissed_reason`,
                        `mute_scope`,
                        `expires_at`,
                        `created_at`,
                        `updated_at`
                    )
                    SELECT
                        `id`,
                        'legacy_board_task|' || `source_type` || ':' || `source_ref`,
                        'legacy_board_task',
                        `source_type`,
                        `source_ref`,
                        `title`,
                        `summary`,
                        '{"origin":"migrated_board_task"}',
                        '{"total":50,"confidence":0.5}',
                        0.5,
                        'suggested',
                        '["派发","查看依据"]',
                        NULL,
                        `updated_at`,
                        NULL,
                        NULL,
                        NULL,
                        NULL,
                        `created_at`,
                        `updated_at`
                    FROM `board_task`
                    WHERE `state` = 'suggested'
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    UPDATE `board_task`
                    SET `state` = 'dismissed',
                        `chip_text` = '已忽略'
                    WHERE `state` = 'suggested'
                    """.trimIndent()
                )
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `board_task` ADD COLUMN `artifact_json` TEXT")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `memoryentity` ADD COLUMN `supersedes_ids_json` " +
                        "TEXT NOT NULL DEFAULT '[]'"
                )
                db.execSQL(
                    "ALTER TABLE `memory_dream_plan` ADD COLUMN `supersede_count` " +
                        "INTEGER NOT NULL DEFAULT 0"
                )
            }
        }
    }
}

object TokenUsageConverter {
    @TypeConverter
    fun fromTokenUsage(usage: TokenUsage?): String {
        return JsonInstant.encodeToString(usage)
    }

    @TypeConverter
    fun toTokenUsage(usage: String): TokenUsage? {
        return JsonInstant.decodeFromString(usage)
    }
}
