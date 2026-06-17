package app.amber.feature.board

import kotlinx.serialization.Serializable

/** Context shared by Board collectors during a manual or scheduled collection pass. */
@Serializable
data class BoardCollectContext(
    val assistantId: String,
    val now: Long,
    val anchorTime: Long,
    val limit: Int = 50,
)

/** Platform-neutral signal emitted before any platform-specific persistence or deduplication. */
@Serializable
data class BoardSignal(
    val sourceType: String,
    val sourceRef: String,
    val title: String,
    val content: String,
    val signalTime: Long,
    val metadataJson: String = "{}",
)

interface BoardSignalCollectorInterface {
    val sourceType: String

    suspend fun collect(context: BoardCollectContext): List<BoardSignal>
}

interface BoardAgentInterface {
    suspend fun generate(signals: List<BoardSignal>, setting: TodayBoardSetting): String
}
