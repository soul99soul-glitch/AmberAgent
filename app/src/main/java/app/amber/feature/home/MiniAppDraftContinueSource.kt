package app.amber.feature.home

import android.content.Context
import app.amber.agent.R
import app.amber.agent.data.db.dao.ConversationDraftDAO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant

/**
 * MiniApp 域的可继续候选：MiniApp host 桥（sendToConversation, mode=draft）
 * 持久化的输入框草稿（conversation_draft 表）。草稿被发送（ChatVM 清空）或
 * 会话被删除（JOIN 过滤）后自动消失。
 */
class MiniAppDraftContinueSource(
    private val context: Context,
    private val draftDao: ConversationDraftDAO,
) : ContinueCandidateSource {

    override fun observe(): Flow<List<ContinueCandidate>> =
        draftDao.observeDraftsWithExistingConversation().map { drafts ->
            drafts.map { draft ->
                ContinueCandidate(
                    sourceKind = ContinueSourceKind.MINIAPP_DRAFT,
                    sourceId = draft.conversationId,
                    route = ContinueRoute.Chat(conversationId = draft.conversationId),
                    title = context.getString(R.string.session_home_feature_mini_apps) +
                        " · " + context.getString(R.string.session_home_status_draft),
                    summary = draft.text.lineSequence().firstOrNull().orEmpty().take(80),
                    lastUpdatedAt = Instant.ofEpochMilli(draft.updatedAtMs),
                    status = ContinueStatus.DRAFT,
                )
            }
        }
}
