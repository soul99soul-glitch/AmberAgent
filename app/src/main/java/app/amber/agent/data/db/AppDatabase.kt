package app.amber.agent.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
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
    version = 1
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
