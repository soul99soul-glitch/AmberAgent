package app.amber.feature.board.hotlist

interface HotListProvider {
    val id: String
    val displayName: String
    val isBuiltIn: Boolean

    suspend fun fetch(limit: Int = 50): HotListResult
}
