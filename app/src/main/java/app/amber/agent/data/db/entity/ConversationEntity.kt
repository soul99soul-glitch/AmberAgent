package app.amber.agent.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    indices = [
        Index(value = ["assistant_id", "is_pinned", "update_at"]),
        Index(value = ["is_pinned", "update_at"]),
    ]
)
data class ConversationEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo("assistant_id", defaultValue = "0950e2dc-9bd5-4801-afa3-aa887aa36b4e")
    val assistantId: String,
    @ColumnInfo("title")
    val title: String,
    @ColumnInfo("nodes")
    val nodes: String,
    @ColumnInfo("create_at")
    val createAt: Long,
    @ColumnInfo("update_at")
    val updateAt: Long,
    @ColumnInfo("suggestions", defaultValue = "[]")
    val chatSuggestions: String,
    @ColumnInfo("is_pinned", defaultValue = "0")
    val isPinned: Boolean,
    @ColumnInfo("auto_approve_tools", defaultValue = "0")
    val autoApproveToolCalls: Boolean = false,
    /**
     * Serialized [app.amber.feature.modelcouncil.CouncilRoom] JSON for the
     * full-featured "host-led council room" feature. NULL when no room exists
     * for this conversation. One conversation has at most one room.
     *
     * This is deliberately decoupled from the legacy ModelCouncil batch tool
     * path (which persists out-of-band under amberagent/model-council/runs/).
     */
    @ColumnInfo("council_state", defaultValue = "NULL")
    val councilState: String? = null,
)
