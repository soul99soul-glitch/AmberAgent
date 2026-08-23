package app.amber.feature.ui.pages.chat

import app.amber.core.model.Conversation
import java.time.LocalDate

/**
 * Represents different types of items in the conversation list
 *
 * 原 ConversationList UI 随 ChatDrawer 侧边栏一起废弃删除（会话列表入口由
 * Session 首页承担）；这个分页 item 模型被 SessionHomePage 的 LazyPagingItems
 * 复用，保留在此。
 */
sealed class ConversationListItem {
    data class DateHeader(
        val date: LocalDate,
        val label: String
    ) : ConversationListItem()
    data object PinnedHeader : ConversationListItem()
    data class Item(
        val conversation: Conversation
    ) : ConversationListItem()
}
