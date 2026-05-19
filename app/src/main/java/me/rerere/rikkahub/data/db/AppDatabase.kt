package me.rerere.rikkahub.data.db

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import me.rerere.ai.core.TokenUsage
import me.rerere.rikkahub.data.db.dao.ConversationDAO
import me.rerere.rikkahub.data.db.dao.ConversationCompactDAO
import me.rerere.rikkahub.data.db.dao.ConversationContextEventDAO
import me.rerere.rikkahub.data.db.dao.BoardFocusRuleDAO
import me.rerere.rikkahub.data.db.dao.BoardItemDAO
import me.rerere.rikkahub.data.db.dao.BoardSignalDAO
import me.rerere.rikkahub.data.db.dao.BoardWeightDAO
import me.rerere.rikkahub.data.db.dao.FeishuDocChangeDAO
import me.rerere.rikkahub.data.db.dao.FeishuDocDependencyDAO
import me.rerere.rikkahub.data.db.dao.FeishuDocSnapshotDAO
import me.rerere.rikkahub.data.db.dao.FeishuWatchedDocDAO
import me.rerere.rikkahub.data.db.dao.FavoriteDAO
import me.rerere.rikkahub.data.db.dao.GenMediaDAO
import me.rerere.rikkahub.data.db.dao.HotListDAO
import me.rerere.rikkahub.data.db.dao.ManagedFileDAO
import me.rerere.rikkahub.data.db.dao.MemoryCandidateDAO
import me.rerere.rikkahub.data.db.dao.MemoryDAO
import me.rerere.rikkahub.data.db.dao.MemoryDreamPlanDAO
import me.rerere.rikkahub.data.db.dao.MemoryEventDAO
import me.rerere.rikkahub.data.db.dao.MessageNodeDAO
import me.rerere.rikkahub.data.db.dao.MessageStatsDAO
import me.rerere.rikkahub.data.db.entity.ConversationEntity
import me.rerere.rikkahub.data.db.entity.ConversationCompactEntity
import me.rerere.rikkahub.data.db.entity.ConversationContextEventEntity
import me.rerere.rikkahub.data.db.entity.BoardFocusRuleEntity
import me.rerere.rikkahub.data.db.entity.BoardItemEntity
import me.rerere.rikkahub.data.db.entity.BoardSignalEntity
import me.rerere.rikkahub.data.db.entity.BoardWeightEntity
import me.rerere.rikkahub.data.db.entity.DailyReviewEntity
import me.rerere.rikkahub.data.db.entity.DocSubscriptionEntity
import me.rerere.rikkahub.data.db.entity.DocChangeLogEntity
import me.rerere.rikkahub.data.db.dao.DailyReviewDAO
import me.rerere.rikkahub.data.db.dao.DocSubscriptionDAO
import me.rerere.rikkahub.data.db.dao.DocChangeLogDAO
import me.rerere.rikkahub.data.db.entity.FeishuDocChangeEntity
import me.rerere.rikkahub.data.db.entity.FeishuDocDependencyEntity
import me.rerere.rikkahub.data.db.entity.FeishuDocSnapshotEntity
import me.rerere.rikkahub.data.db.entity.FeishuWatchedDocEntity
import me.rerere.rikkahub.data.db.entity.FavoriteEntity
import me.rerere.rikkahub.data.db.entity.GenMediaEntity
import me.rerere.rikkahub.data.db.entity.DeepReadCacheEntity
import me.rerere.rikkahub.data.db.entity.HotListCacheEntity
import me.rerere.rikkahub.data.db.entity.HotListSourceEntity
import me.rerere.rikkahub.data.db.entity.HotTopicCacheEntity
import me.rerere.rikkahub.data.db.entity.ManagedFileEntity
import me.rerere.rikkahub.data.db.entity.MemoryCandidateEntity
import me.rerere.rikkahub.data.db.entity.MemoryDreamPlanEntity
import me.rerere.rikkahub.data.db.entity.MemoryEntity
import me.rerere.rikkahub.data.db.entity.MemoryEventEntity
import me.rerere.rikkahub.data.db.entity.MessageDayStatEntity
import me.rerere.rikkahub.data.db.entity.MessageNodeEntity
import me.rerere.rikkahub.data.db.entity.MessageNodeStatEntity
import me.rerere.rikkahub.data.db.migrations.Migration_16_17
import me.rerere.rikkahub.data.db.migrations.Migration_8_9
import me.rerere.rikkahub.utils.JsonInstant

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
    ],
    version = 27,
    autoMigrations = [
        AutoMigration(from = 1, to = 2),
        AutoMigration(from = 2, to = 3),
        AutoMigration(from = 3, to = 4),
        AutoMigration(from = 4, to = 5),
        AutoMigration(from = 5, to = 6),
        AutoMigration(from = 7, to = 8),
        AutoMigration(from = 8, to = 9, spec = Migration_8_9::class),
        AutoMigration(from = 9, to = 10),
        AutoMigration(from = 10, to = 11),
        AutoMigration(from = 12, to = 13),
        AutoMigration(from = 16, to = 17, spec = Migration_16_17::class),
        AutoMigration(from = 17, to = 18),
        AutoMigration(from = 23, to = 24),
        AutoMigration(from = 24, to = 25),
    ]
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
