package app.amber.agent.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import app.amber.ai.core.TokenUsage
import app.amber.agent.data.db.dao.ArtifactDAO
import app.amber.agent.data.db.dao.ConversationDAO
import app.amber.agent.data.db.dao.ConversationCompactDAO
import app.amber.agent.data.db.dao.ConversationContextEventDAO
import app.amber.agent.data.db.dao.ConversationDraftDAO
import app.amber.agent.data.db.dao.ContinueCandidateDismissDAO
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
import app.amber.agent.data.db.dao.RunResumeDAO
import app.amber.agent.data.db.dao.RunTerminalDAO
import app.amber.agent.data.db.dao.ThreadGraphDAO
import app.amber.agent.data.db.dao.ThemePackageDAO
import app.amber.agent.data.db.dao.ToolEffectDAO
import app.amber.agent.data.db.entity.ConversationEntity
import app.amber.agent.data.db.entity.ConversationCompactEntity
import app.amber.agent.data.db.entity.ConversationContextEventEntity
import app.amber.agent.data.db.entity.ConversationDraftEntity
import app.amber.agent.data.db.entity.ContinueCandidateDismissEntity
import app.amber.agent.data.db.entity.ArtifactEntity
import app.amber.agent.data.db.entity.ArtifactReferenceEntity
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
import app.amber.agent.data.db.entity.RunResumeEntity
import app.amber.agent.data.db.entity.RunTerminalEntity
import app.amber.agent.data.db.entity.ThreadMessageEntity
import app.amber.agent.data.db.entity.ThreadNodeEntity
import app.amber.agent.data.db.entity.ThreadResultEntity
import app.amber.agent.data.db.entity.ThemePackageEntity
import app.amber.agent.data.db.entity.ToolEffectEntity
import app.amber.core.utils.JsonInstant

@Database(
    entities = [
        ConversationEntity::class,
        ConversationDraftEntity::class,
        ArtifactEntity::class,
        ArtifactReferenceEntity::class,
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
        ToolEffectEntity::class,
        RunTerminalEntity::class,
        RunResumeEntity::class,
        ThreadNodeEntity::class,
        ThreadMessageEntity::class,
        ThreadResultEntity::class,
        ContinueCandidateDismissEntity::class,
        ThemePackageEntity::class,
    ],
    version = 15
)
@TypeConverters(TokenUsageConverter::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun artifactDao(): ArtifactDAO

    abstract fun conversationDao(): ConversationDAO

    abstract fun conversationDraftDao(): ConversationDraftDAO

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

    abstract fun toolEffectDao(): ToolEffectDAO

    abstract fun runTerminalDao(): RunTerminalDAO

    abstract fun runResumeDao(): RunResumeDAO

    abstract fun threadGraphDao(): ThreadGraphDAO

    abstract fun continueCandidateDismissDao(): ContinueCandidateDismissDAO

    abstract fun themePackageDao(): ThemePackageDAO



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

        // P1-02 / P1-03 (capability parity plan): durable tool effect ledger +
        // typed run terminal. Both are additive tables; disabling the feature
        // flags keeps the tables (rollback rules in plan §17.2 — ledger data
        // must survive a flag flip so recovery information is never lost).
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `tool_effect` (" +
                        "`effect_id` TEXT NOT NULL, " +
                        "`run_id` TEXT, " +
                        "`turn_id` INTEGER, " +
                        "`tool_call_id` TEXT NOT NULL, " +
                        "`tool_name` TEXT NOT NULL, " +
                        "`args_digest` TEXT NOT NULL, " +
                        "`approval_digest` TEXT, " +
                        "`effect_class` TEXT NOT NULL, " +
                        "`status` TEXT NOT NULL, " +
                        "`started_at_ms` INTEGER NOT NULL, " +
                        "`finished_at_ms` INTEGER, " +
                        "`result_summary` TEXT, " +
                        "`result_payload` TEXT, " +
                        "`error_category` TEXT, " +
                        "`message_persistence_cursor` TEXT, " +
                        "`created_at_ms` INTEGER NOT NULL, " +
                        "`updated_at_ms` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`effect_id`))"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_tool_effect_run_id` ON `tool_effect` (`run_id`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_tool_effect_tool_call_id` ON `tool_effect` (`tool_call_id`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_tool_effect_status` ON `tool_effect` (`status`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_tool_effect_run_id_tool_call_id` " +
                        "ON `tool_effect` (`run_id`, `tool_call_id`)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `run_terminal` (" +
                        "`run_id` TEXT NOT NULL, " +
                        "`conversation_id` TEXT NOT NULL, " +
                        "`assistant_id` TEXT, " +
                        "`state` TEXT NOT NULL, " +
                        "`pause_reason` TEXT, " +
                        "`started_at_ms` INTEGER NOT NULL, " +
                        "`updated_at_ms` INTEGER NOT NULL, " +
                        "`finished_at_ms` INTEGER, " +
                        "PRIMARY KEY(`run_id`))"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_run_terminal_conversation_id` " +
                        "ON `run_terminal` (`conversation_id`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_run_terminal_state` ON `run_terminal` (`state`)"
                )
            }
        }

        // P2-06 (capability parity plan): memory compare-and-set revision +
        // pollution-provenance markers. All three columns are additive with
        // defaults, so existing rows keep revision=1 and null provenance.
        // Disabling the capability keeps the columns (rollback rules §17.2).
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `memoryentity` ADD COLUMN `revision` " +
                        "INTEGER NOT NULL DEFAULT 1"
                )
                db.execSQL(
                    "ALTER TABLE `memoryentity` ADD COLUMN `source_run_id` " +
                        "TEXT"
                )
                db.execSQL(
                    "ALTER TABLE `memoryentity` ADD COLUMN `source_trigger` " +
                        "TEXT"
                )
            }
        }

        // P3-01 (capability parity plan): Workspace Artifact Registry. Pure
        // additive tables — `artifact` (registry rows) + `artifact_reference`
        // (cross-feature references, FK cascade). No existing table is touched,
        // so rollback (flag off) keeps all artifact data read-only visible
        // (rollback rules §17.2).
        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `artifact` (" +
                        "`artifact_id` TEXT NOT NULL, " +
                        "`workspace_id` TEXT NOT NULL, " +
                        "`type` TEXT NOT NULL, " +
                        "`mime_type` TEXT NOT NULL, " +
                        "`title` TEXT NOT NULL, " +
                        "`source_kind` TEXT, " +
                        "`source_id` TEXT, " +
                        "`source_run_id` TEXT, " +
                        "`source_message_id` TEXT, " +
                        "`content_locator` TEXT NOT NULL, " +
                        "`content_digest` TEXT NOT NULL, " +
                        "`size_bytes` INTEGER NOT NULL, " +
                        "`parser_version` TEXT, " +
                        "`parse_status` TEXT NOT NULL, " +
                        "`parse_error` TEXT, " +
                        "`metadata_json` TEXT, " +
                        "`created_at_ms` INTEGER NOT NULL, " +
                        "`updated_at_ms` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`artifact_id`))"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_artifact_workspace_id` " +
                        "ON `artifact` (`workspace_id`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_artifact_source_message_id` " +
                        "ON `artifact` (`source_message_id`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_artifact_source_kind_source_id` " +
                        "ON `artifact` (`source_kind`, `source_id`)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `artifact_reference` (" +
                        "`artifact_id` TEXT NOT NULL, " +
                        "`ref_kind` TEXT NOT NULL, " +
                        "`ref_id` TEXT NOT NULL, " +
                        "PRIMARY KEY(`artifact_id`, `ref_kind`, `ref_id`), " +
                        "FOREIGN KEY(`artifact_id`) REFERENCES `artifact`(`artifact_id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_artifact_reference_artifact_id` " +
                        "ON `artifact_reference` (`artifact_id`)"
                )
            }
        }

        // P3-03 (capability parity plan): durable composer draft table written
        // by the MiniApp host bridge (`host.sendToConversation`, mode=draft).
        // Pure additive table — disabling anything keeps existing drafts
        // (rollback rules in plan §17.2).
        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `conversation_draft` (" +
                        "`conversation_id` TEXT NOT NULL, " +
                        "`draft_id` TEXT NOT NULL, " +
                        "`text` TEXT NOT NULL, " +
                        "`attachments_json` TEXT NOT NULL, " +
                        "`updated_at_ms` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`conversation_id`))"
                )
            }
        }

        // P4-02 (capability parity plan): persistent thread graph — child
        // thread nodes, queued/delivered/persisted messages and terminal
        // results. Pure additive tables; disabling thread_graph_v2 keeps the
        // tables and their data (rollback rules in plan §17.2).
        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `thread_node` (" +
                        "`thread_id` TEXT NOT NULL, " +
                        "`parent_thread_id` TEXT, " +
                        "`root_run_id` TEXT NOT NULL, " +
                        "`conversation_id` TEXT NOT NULL, " +
                        "`status` TEXT NOT NULL, " +
                        "`task` TEXT NOT NULL, " +
                        "`started_at_ms` INTEGER NOT NULL, " +
                        "`updated_at_ms` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`thread_id`))"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_thread_node_root_run_id` " +
                        "ON `thread_node` (`root_run_id`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_thread_node_parent_thread_id` " +
                        "ON `thread_node` (`parent_thread_id`)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `thread_message` (" +
                        "`message_id` TEXT NOT NULL, " +
                        "`thread_id` TEXT NOT NULL, " +
                        "`sender` TEXT NOT NULL, " +
                        "`recipient` TEXT NOT NULL, " +
                        "`kind` TEXT NOT NULL, " +
                        "`payload` TEXT NOT NULL, " +
                        "`payload_digest` TEXT NOT NULL, " +
                        "`delivery_state` TEXT NOT NULL, " +
                        "`created_at_ms` INTEGER NOT NULL, " +
                        "`updated_at_ms` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`message_id`))"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_thread_message_thread_id` " +
                        "ON `thread_message` (`thread_id`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_thread_message_delivery_state` " +
                        "ON `thread_message` (`delivery_state`)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `thread_result` (" +
                        "`thread_id` TEXT NOT NULL, " +
                        "`final_answer` TEXT NOT NULL, " +
                        "`artifacts_json` TEXT NOT NULL, " +
                        "`terminal_reason` TEXT NOT NULL, " +
                        "`finished_at_ms` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`thread_id`))"
                )
            }
        }

        // P6-01 (capability parity plan): resume cursor for server-side stored
        // OpenAI Responses — one row per runId (responseId + write-ahead
        // sequence + provider binding). Pure additive table; disabling
        // openai_responses_resume keeps the rows (rollback rules §17.2).
        val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `run_resume` (" +
                        "`run_id` TEXT NOT NULL, " +
                        "`response_id` TEXT NOT NULL, " +
                        "`sequence` INTEGER NOT NULL, " +
                        "`provider_id` TEXT NOT NULL, " +
                        "PRIMARY KEY(`run_id`))"
                )
            }
        }

        // P8-08 (capability parity plan): 首页「继续」聚合的暂时隐藏记录 +
        // P8-09 主题库导入包表。均为纯增量表；关闭能力保留数据
        // （rollback rules §17.2）。
        val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `continue_candidate_dismiss` (" +
                        "`source_kind` TEXT NOT NULL, " +
                        "`source_id` TEXT NOT NULL, " +
                        "`dismiss_until_ms` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`source_kind`, `source_id`))"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `theme_package` (" +
                        "`id` TEXT NOT NULL, " +
                        "`name` TEXT NOT NULL, " +
                        "`json` TEXT NOT NULL, " +
                        "`imported_at_ms` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`))"
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
