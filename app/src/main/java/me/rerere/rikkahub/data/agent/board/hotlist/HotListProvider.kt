package me.rerere.rikkahub.data.agent.board.hotlist

interface HotListProvider {
    val id: String
    val displayName: String
    val isBuiltIn: Boolean

    suspend fun fetch(limit: Int = 50): HotListResult
}
