package app.amber.feature.board

import kotlin.time.Clock

object IosBoardFactory {
    fun createCollectors(setting: TodayBoardSetting): List<BoardSignalCollectorInterface> = listOf(
        TimeAnchorBoardSignalCollector(triggerHourProvider = { setting.triggerHours }),
    )

    fun createAgent(
        baseUrl: String,
        apiKey: String,
        modelId: String,
        chatCompletionsPath: String = "/chat/completions",
    ): BoardAgentInterface = IosBoardAgent(
        baseUrl = baseUrl,
        apiKey = apiKey,
        modelId = modelId,
        chatCompletionsPath = chatCompletionsPath,
    )

    fun createTimeCollectContext(
        assistantId: String,
        anchorTime: Long = 0L,
        limit: Int = 50,
    ): BoardCollectContext {
        val now = Clock.System.now().toEpochMilliseconds()
        return BoardCollectContext(
            assistantId = assistantId,
            now = now,
            anchorTime = if (anchorTime > 0L) anchorTime else now,
            limit = limit,
        )
    }
}
