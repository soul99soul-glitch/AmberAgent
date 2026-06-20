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
import app.amber.agent.data.db.entity.ConversationEntity
import app.amber.agent.data.db.entity.ConversationCompactEntity
import app.amber.agent.data.db.entity.ConversationContextEventEntity
import app.amber.agent.data.db.entity.BoardFocusRuleEntity
import app.amber.agent.data.db.entity.BoardItemEntity
import app.amber.agent.data.db.entity.BoardSignalEntity
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
    ],
    version = 8
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



    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // board_task and board_task_event tables removed in v8.
                // Historical migrations preserved for reference but no-op for new installs.
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // opportunity and reference_anchor tables removed in v8.
                // Historical migration preserved for reference but no-op for new installs.
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // board_task table removed in v8; no-op for historical migration.
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

        // Council Room: adds the optional serialized CouncilRoom JSON column to
        // the conversation table. NULL by default — only populated when the user
        // opens the full-featured room for that conversation. The legacy
        // ModelCouncil batch tool path is unaffected (it persists out-of-band).
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // DEFAULT NULL is required: the entity declares
                // @ColumnInfo(defaultValue = "NULL"), so Room's expected schema has
                // `DEFAULT NULL`. Omitting it here makes the migrated column's default
                // 'undefined', which fails Room's post-migration schema validation
                // (crash on first launch when upgrading an existing pre-council DB).
                db.execSQL("ALTER TABLE `conversationentity` ADD COLUMN `council_state` TEXT DEFAULT NULL")
            }
        }

        // Adds the `pinned` column to deep_read_cache for manual retention of
        // magazine articles (Deep Read quality hardening, cluster B). BOOLEAN is
        // stored as INTEGER. SQLite allows NOT NULL on ADD COLUMN only with a
        // non-null DEFAULT; DEFAULT 0 backfills existing rows as unpinned.
        // Pattern follows MIGRATION_4_5 (NOT NULL DEFAULT), not MIGRATION_5_6.
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `deep_read_cache` ADD COLUMN `pinned` INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        // Removes board_task and board_task_event tables, plus opportunity/reference_anchor
        // tables that were only used by the task flow feature.
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS `board_task`")
                db.execSQL("DROP TABLE IF EXISTS `board_task_event`")
                db.execSQL("DROP TABLE IF EXISTS `opportunity`")
                db.execSQL("DROP TABLE IF EXISTS `reference_anchor`")
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
