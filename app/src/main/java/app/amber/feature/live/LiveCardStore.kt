package app.amber.feature.live

import app.amber.agent.data.db.AppDatabase
import app.amber.agent.data.db.entity.LiveCardEntity
import app.amber.core.utils.JsonInstant
import kotlinx.coroutines.flow.Flow

/**
 * 用户保存的伴随卡片存储 owner（蓝图 §7.3 P1-3）：
 * 写读删都过这里，UI 不直接碰 DAO。保留策略见 LiveCardEntity KDoc。
 */
class LiveCardStore(private val db: AppDatabase) {

    private val dao get() = db.liveCardDao()

    val savedCards: Flow<List<LiveCardEntity>> = dao.latestFlow(SAVED_CARDS_LIMIT)

    suspend fun save(state: LiveModeUiState): Boolean {
        val card = state.card ?: return false
        if (card.watching.isBlank()) return false
        return runCatching {
            val signature = state.cardSignature ?: ""
            if (dao.countSame(signature, card.watching) > 0) return@runCatching false
            dao.insert(
                LiveCardEntity(
                    packageName = state.currentPackage,
                    appLabel = state.currentAppLabel,
                    title = state.currentTitle,
                    actionLabel = state.completedAction,
                    watching = card.watching,
                    keyPointsJson = JsonInstant.encodeToString(card.keyPoints),
                    suggestionsJson = JsonInstant.encodeToString(card.suggestions),
                    screenSignature = signature,
                    createdAt = System.currentTimeMillis(),
                ),
            )
            true
        }.getOrDefault(false)
    }

    suspend fun delete(id: Long) {
        runCatching { dao.deleteById(id) }
    }

    private companion object {
        const val SAVED_CARDS_LIMIT = 50
    }
}
