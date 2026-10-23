package app.amber.feature.ui.pages.sessionhome

import app.amber.agent.Screen
import app.amber.feature.home.ContinueRoute

/** P8-08：继续路由 → 应用内页面（点击准确路由到任务焦点）。 */
internal fun ContinueRoute.toScreen(): Screen = when (this) {
    is ContinueRoute.CouncilRoom -> Screen.CouncilRoom(conversationId = conversationId)
    is ContinueRoute.DeepRead -> Screen.DeepRead(
        topicId = topicId,
        title = title,
        sourceUrl = sourceUrl,
    )
    is ContinueRoute.Chat -> Screen.Chat(
        id = conversationId,
        messageId = messageId,
        toolCallId = toolCallId,
    )
    is ContinueRoute.ImageGeneration -> Screen.Chat(
        id = conversationId,
        messageId = messageId,
        toolCallId = toolCallId,
    )
    is ContinueRoute.MiniAppRunner -> Screen.MiniAppRunner(appId = appId)
    is ContinueRoute.NovelWorkspace -> Screen.NovelMarkdown(
        projectId = projectId,
        branchSlug = branchSlug,
        jobId = jobId,
    )
}

/** Chat-backed Continue routes must still own a persisted conversation at click time. */
internal suspend fun canOpenContinueRoute(
    route: ContinueRoute,
    conversationExists: suspend (String) -> Boolean,
): Boolean {
    val conversationId = when (route) {
        is ContinueRoute.Chat -> route.conversationId
        is ContinueRoute.ImageGeneration -> route.conversationId
        is ContinueRoute.CouncilRoom -> route.conversationId
        else -> return true
    }
    return conversationExists(conversationId)
}

/** Include Council: its conversation is also opened from Home's resume rail. */
internal fun ContinueRoute.conversationIdOrNull(): String? = when (this) {
    is ContinueRoute.Chat -> conversationId
    is ContinueRoute.ImageGeneration -> conversationId
    is ContinueRoute.CouncilRoom -> conversationId
    else -> null
}

internal fun filterHomeContinueCandidates(
    candidates: List<app.amber.feature.home.ContinueCandidate>,
    hidden: Set<kotlin.uuid.Uuid>,
): List<app.amber.feature.home.ContinueCandidate> {
    val hiddenStrings = hidden.map { it.toString() }.toSet()
    return candidates.filter { it.route.conversationIdOrNull() !in hiddenStrings }
}

internal suspend fun canOpenHomeContinueRoute(
    route: ContinueRoute,
    hiddenIds: () -> Set<kotlin.uuid.Uuid>,
    conversationExists: suspend (String) -> Boolean,
): Boolean {
    fun hidden() = hiddenIds().any { it.toString() == route.conversationIdOrNull() }
    // Recheck after the database suspension, including a swipe on the other pane.
    return !hidden() && canOpenContinueRoute(route, conversationExists) && !hidden()
}
