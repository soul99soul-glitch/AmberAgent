package app.amber.feature.home

import android.content.Context
import app.amber.agent.R
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
    private val context: Context,
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
        val councilLabel = context.getString(R.string.session_home_feature_council)
        return ContinueCandidate(
            sourceKind = ContinueSourceKind.COUNCIL,
            sourceId = room.conversationId.toString(),
            route = ContinueRoute.CouncilRoom(conversationId = room.conversationId.toString()),
            title = room.objective.ifBlank { councilLabel },
            summary = context.getString(R.string.council_room_subtitle, guestCount, room.round) +
                " · " + room.statusLabel(context),
            lastUpdatedAt = Instant.ofEpochMilli(room.updatedAtMs),
            status = status,
        )
    }
}

private fun CouncilRoom.statusLabel(context: Context): String = when (status) {
    CouncilRoomStatus.IDLE -> context.getString(R.string.council_room_status_idle)
    CouncilRoomStatus.EXPLORING -> context.getString(R.string.council_room_mode_explore)
    CouncilRoomStatus.DEBATING -> context.getString(R.string.council_room_mode_debate)
    CouncilRoomStatus.FINALIZING -> context.getString(R.string.council_room_synthesizing)
    CouncilRoomStatus.INTERRUPTED -> context.getString(R.string.chat_message_council_status_interrupted)
    CouncilRoomStatus.FINALIZED -> context.getString(R.string.chat_message_council_status_completed)
    CouncilRoomStatus.CANCELLED -> context.getString(R.string.chat_message_council_status_cancelled)
    CouncilRoomStatus.FAILED -> context.getString(R.string.chat_message_council_status_failed)
}
