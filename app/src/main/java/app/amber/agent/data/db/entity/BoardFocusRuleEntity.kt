package app.amber.agent.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * User-configured focus rule expressed in natural language.
 *
 * These are injected directly into the BoardAgent system prompt ("the user has asked you to
 * pay attention to: ..."). They are not executed as filters — the agent interprets them
 * when deciding which signals deserve surfacing.
 *
 * The UI lets users add/remove rules as short free-text entries (e.g. "关注老板的消息",
 * "忽略营销推送", "盯着项目 X 进展"). Rule ordering is preserved via [sortOrder] so the
 * user's most important rules appear first in the prompt.
 */
@Entity(
    tableName = "board_focus_rule",
    indices = [
        Index("active"),
        Index("sort_order"),
    ],
)
data class BoardFocusRuleEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo("content")
    val content: String,
    @ColumnInfo("active")
    val active: Boolean = true,
    @ColumnInfo("sort_order")
    val sortOrder: Int = 0,
    @ColumnInfo("created_at")
    val createdAt: Long,
    @ColumnInfo("updated_at")
    val updatedAt: Long,
)
