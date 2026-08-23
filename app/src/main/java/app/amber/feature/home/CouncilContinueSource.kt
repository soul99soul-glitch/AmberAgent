package app.amber.feature.home

import app.amber.agent.data.db.dao.ConversationDAO
import app.amber.core.utils.JsonInstant
import app.amber.feature.modelcouncil.CouncilRoom
import app.amber.feature.modelcouncil.CouncilRoomStatus
import app.amber.feature.modelcouncil.terminal
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant

/**
 * Council 域的可继续候选：持久化在 conversationentity.council_state 的
 * Council Room JSON。只聚合「非终态」房间——进程死亡后冷加载会修复为
 * INTERRUPTED，用户重新打开房间即可继续；IDLE 房间等待用户启动。
 */
class CouncilContinueSource(
    private val conversationDao: ConversationDAO,
) : ContinueCandidateSource {

    override fun observe(): Flow<List<ContinueCandidate>> =
        conversationDao.observeCouncilStates().map { rows ->
            rows.mapNotNull { row ->
                val room = runCatching { JsonInstant.decodeFromString<CouncilRoom>(row.councilState) }
                    .getOrNull()
                    ?: return@mapNotNull null
                if (room.status.terminal) return@mapNotNull null
                mapRoom(room)
            }
        }

    private fun mapRoom(room: CouncilRoom): ContinueCandidate {
        val status = if (room.status == CouncilRoomStatus.IDLE) {
            ContinueStatus.WAITING_USER
        } else {
            ContinueStatus.FAILED_RESUMABLE
        }
        val guestCount = room.activeGuests.size
        return ContinueCandidate(
            sourceKind = ContinueSourceKind.COUNCIL,
            sourceId = room.conversationId.toString(),
            route = ContinueRoute.CouncilRoom(conversationId = room.conversationId.toString()),
            title = room.objective.ifBlank { "模型议会" },
            summary = "模型议会 · $guestCount 位参与者 · ${room.statusLabel()}",
            lastUpdatedAt = Instant.ofEpochMilli(room.updatedAtMs),
            status = status,
        )
    }
}

private fun CouncilRoom.statusLabel(): String = when (status) {
    CouncilRoomStatus.IDLE -> "未开始"
    CouncilRoomStatus.EXPLORING -> "探索中"
    CouncilRoomStatus.DEBATING -> "讨论中"
    CouncilRoomStatus.FINALIZING -> "汇总中"
    CouncilRoomStatus.INTERRUPTED -> "已中断"
    CouncilRoomStatus.FINALIZED,
    CouncilRoomStatus.CANCELLED,
    CouncilRoomStatus.FAILED,
    -> status.name
}
