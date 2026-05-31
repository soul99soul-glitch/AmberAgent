package app.amber.feature.board

import kotlinx.coroutines.flow.Flow
import app.amber.agent.data.db.dao.BoardFocusRuleDAO
import app.amber.agent.data.db.dao.BoardItemDAO
import app.amber.agent.data.db.dao.BoardSignalDAO
import app.amber.agent.data.db.dao.BoardWeightDAO
import app.amber.agent.data.db.dao.DailyReviewDAO
import app.amber.agent.data.db.entity.BoardFocusRuleEntity
import app.amber.agent.data.db.entity.BoardItemEntity
import app.amber.agent.data.db.entity.BoardSignalEntity
import app.amber.agent.data.db.entity.BoardWeightEntity
import app.amber.agent.data.db.entity.DailyReviewEntity
import java.time.ZoneId

/**
 * Thin aggregator over the four Board DAOs. Deliberately avoids business logic — things
 * like dedup, scoring, and nightly cleanup live in their own components and only use this
 * repository for raw persistence.
 *
 * Kept narrow on purpose: public methods map 1:1 to what callers need. We'd rather grow
 * this surface as features land than expose a large generic API that encourages leakage.
 */
class BoardRepository(
    private val signalDao: BoardSignalDAO,
    private val itemDao: BoardItemDAO,
    private val focusRuleDao: BoardFocusRuleDAO,
    private val weightDao: BoardWeightDAO,
    private val dailyReviewDao: DailyReviewDAO,
) {
    // ---- Signals --------------------------------------------------------------------

    suspend fun saveSignal(signal: BoardSignalEntity) = signalDao.insert(signal)

    suspend fun saveSignalIfAbsent(signal: BoardSignalEntity): Boolean =
        signalDao.insertIfAbsent(signal) != -1L

    suspend fun findSignalBySourceRef(sourceType: String, sourceRef: String): BoardSignalEntity? =
        signalDao.findBySourceRef(sourceType, sourceRef)

    suspend fun findSignalByContentHash(
        contentHash: String,
        sourceType: String,
        sinceMs: Long,
    ): BoardSignalEntity? = signalDao.findDuplicateByHash(contentHash, sourceType, sinceMs)

    suspend fun getUnprocessedSignals(limit: Int = 200): List<BoardSignalEntity> =
        signalDao.getUnprocessed(limit)

    suspend fun countUnprocessedSignals(): Int = signalDao.countUnprocessed()

    suspend fun markSignalsProcessed(ids: List<String>, now: Long = System.currentTimeMillis()) {
        if (ids.isEmpty()) return
        signalDao.markProcessed(ids, now)
    }

    suspend fun pruneProcessedSignalsBefore(olderThanMs: Long): Int =
        signalDao.pruneProcessedBefore(olderThanMs)

    // ---- Items ----------------------------------------------------------------------

    suspend fun saveItems(items: List<BoardItemEntity>) {
        if (items.isEmpty()) return
        itemDao.insertAll(items)
    }

    fun observeItems(boardDate: String): Flow<List<BoardItemEntity>> =
        itemDao.flowByDate(boardDate)

    suspend fun getItems(boardDate: String): List<BoardItemEntity> = itemDao.getByDate(boardDate)

    suspend fun getActiveItems(boardDate: String): List<BoardItemEntity> =
        itemDao.getActiveByDate(boardDate)

    suspend fun markItemCompleted(id: String, now: Long = System.currentTimeMillis()) {
        itemDao.markCompleted(id, now)
    }

    suspend fun markItemsCompletedBySource(
        sourceType: String,
        sourceRef: String,
        boardDate: String = todayBoardDate(),
        now: Long = System.currentTimeMillis(),
    ) {
        itemDao.markCompletedBySource(sourceType, sourceRef, boardDate, now)
    }

    suspend fun markItemDismissed(id: String, now: Long = System.currentTimeMillis()) {
        itemDao.markDismissed(id, now)
    }

    suspend fun markItemsDismissedBySource(
        sourceType: String,
        sourceRef: String,
        boardDate: String = todayBoardDate(),
        now: Long = System.currentTimeMillis(),
    ) {
        itemDao.markDismissedBySource(sourceType, sourceRef, boardDate, now)
    }

    suspend fun getItem(id: String): BoardItemEntity? = itemDao.getById(id)

    suspend fun purgeItemsBefore(keepFromDate: String): Int = itemDao.deleteBefore(keepFromDate)

    // ---- Focus rules ----------------------------------------------------------------

    fun observeFocusRules(): Flow<List<BoardFocusRuleEntity>> = focusRuleDao.flowAll()

    suspend fun getActiveFocusRules(): List<BoardFocusRuleEntity> = focusRuleDao.getActive()

    suspend fun upsertFocusRule(rule: BoardFocusRuleEntity) = focusRuleDao.upsert(rule)

    suspend fun deleteFocusRule(id: String) = focusRuleDao.deleteById(id)

    // ---- Weights --------------------------------------------------------------------

    suspend fun getWeight(sourceType: String, keyword: String): BoardWeightEntity? =
        weightDao.get(sourceType, keyword)

    suspend fun getAllWeights(): List<BoardWeightEntity> = weightDao.getAll()

    suspend fun upsertWeight(weight: BoardWeightEntity) = weightDao.upsert(weight)

    suspend fun getHardMutes(hardMuteThreshold: Int = -10): List<BoardWeightEntity> =
        weightDao.getHardMutes(hardMuteThreshold)

    suspend fun clearWeights() = weightDao.clear()

    // ---- Helpers --------------------------------------------------------------------

    /**
     * Determine which board-date bucket an item should land in, given a trigger time.
     * We use the Chinese-calendar-style "day starts at 04:00" cutoff — see
     * [TODAY_BOARD_DAY_CUTOFF_HOUR] for rationale.
     */
    fun resolveBoardDate(
        triggerTimeMs: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): String {
        val instant = java.time.Instant.ofEpochMilli(triggerTimeMs).atZone(zone)
        val adjusted = if (instant.hour < TODAY_BOARD_DAY_CUTOFF_HOUR) {
            instant.toLocalDate().minusDays(1)
        } else {
            instant.toLocalDate()
        }
        return adjusted.toString() // ISO yyyy-MM-dd
    }

    /** Convenience: today's board date from the system clock + default zone. */
    fun todayBoardDate(): String = resolveBoardDate()

    // ---- Daily Review ---------------------------------------------------------------

    fun observeDailyReview(boardDate: String): Flow<DailyReviewEntity?> =
        dailyReviewDao.observeByDate(boardDate)

    suspend fun getDailyReview(boardDate: String): DailyReviewEntity? =
        dailyReviewDao.getByDate(boardDate)

    suspend fun saveDailyReview(entity: DailyReviewEntity) = dailyReviewDao.upsert(entity)

    suspend fun pruneDailyReviews(keepFromDate: String) = dailyReviewDao.deleteOlderThan(keepFromDate)

    suspend fun getCompletedItems(boardDate: String): List<BoardItemEntity> =
        itemDao.getCompletedByDate(boardDate)
}
