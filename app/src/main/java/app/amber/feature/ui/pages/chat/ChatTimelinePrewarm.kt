package app.amber.feature.ui.pages.chat

import androidx.compose.foundation.lazy.LazyListState
import app.amber.ai.ui.UIMessagePart
import app.amber.core.model.MessageNode
import app.amber.core.model.AssistantRegex
import app.amber.feature.ui.components.message.MessageRenderCache
import app.amber.feature.ui.components.message.chatMessageTimelinePrewarmTexts
import app.amber.feature.ui.components.richtext.prewarmMarkdownContent
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.yield

internal const val MarkdownPrewarmBeforeItems = 4
internal const val MarkdownPrewarmAfterItems = 8
internal const val MarkdownPrewarmMaxTexts = 32

internal fun LazyListState.markdownPrewarmTexts(
    messageNodes: List<MessageNode>,
    regexes: List<AssistantRegex>,
    loadingLastMessage: Boolean,
    timelinePlan: ChatTimelinePlan,
): List<String> {
    if (messageNodes.isEmpty()) return emptyList()
    val visibleMessageIndexes = layoutInfo.visibleItemsInfo
        .mapNotNull { timelinePlan.messageIndexAt(it.index) }
        .filter { it in messageNodes.indices }
        .distinct()
    if (visibleMessageIndexes.isEmpty()) return emptyList()

    val start = (visibleMessageIndexes.minOrNull()!! - MarkdownPrewarmBeforeItems)
        .coerceAtLeast(0)
    val end = (visibleMessageIndexes.maxOrNull()!! + MarkdownPrewarmAfterItems)
        .coerceAtMost(messageNodes.lastIndex)

    return buildList {
        for (index in start..end) {
            if (loadingLastMessage && index == messageNodes.lastIndex) continue
            addAll(messageNodes[index].markdownPrewarmTexts(regexes))
            if (size >= MarkdownPrewarmMaxTexts) break
        }
    }.take(MarkdownPrewarmMaxTexts)
}

private fun MessageNode.markdownPrewarmTexts(regexes: List<AssistantRegex>): List<String> {
    val message = currentMessage
    return message.parts
        .filterIsInstance<UIMessagePart.Text>()
        .mapNotNull { part ->
            MessageRenderCache.displayMarkdownText(
                text = part.text,
                regexes = regexes,
                role = message.role,
            )
                .takeIf { it.isNotBlank() }
        }
}

/**
 * Bounded work budgets for the entry prewarm. The first-screen budget is what
 * the list mount is allowed to wait for; the background budget only covers
 * history just above the initial viewport and never gates the first frame.
 */
internal data class TimelinePrewarmBudget(
    val maxNodes: Int,
    val maxTexts: Int,
    val maxChars: Int,
) {
    init {
        require(maxNodes > 0) { "maxNodes must be positive" }
        require(maxTexts > 0) { "maxTexts must be positive" }
        require(maxChars > 0) { "maxChars must be positive" }
    }
}

/**
 * Covers the tail that a reverse-layout LazyColumn mounts first (newest
 * message = lazy index 0). Kept small on purpose: the route animation already
 * provides ~320ms of headroom, and a long history must never turn this into a
 * full-conversation wait.
 */
internal val TimelineFirstScreenPrewarmBudget = TimelinePrewarmBudget(
    maxNodes = 8,
    maxTexts = 24,
    maxChars = 200_000,
)

/**
 * Continues upward through loaded history after the first frame has landed.
 * Bounded so scrolling into an old page still finds warm parses without
 * parsing (and evicting) an entire long conversation.
 */
internal val TimelineBackgroundPrewarmBudget = TimelinePrewarmBudget(
    maxNodes = 48,
    maxTexts = 96,
    maxChars = 600_000,
)

internal data class TimelinePrewarmSelection(
    val texts: List<String>,
    /**
     * Node to resume from for background warmup, or -1 when the whole list was
     * scanned. It points at the partially covered node when a text budget
     * stopped in the middle of its parts; [selectBackgroundPrewarmTexts] skips
     * [texts] via `alreadyWarmed` and picks up the remaining parts there.
     */
    val nextOlderNodeIndex: Int,
)

/**
 * Selects the newest-first bodies that the first mounted viewport needs.
 *
 * Pure and ordered; callers run it on `Dispatchers.Default` because
 * [MessageRenderCache.displayMarkdownText] performs the visual regex pass.
 */
internal fun selectFirstScreenPrewarmTexts(
    messageNodes: List<MessageNode>,
    regexes: List<AssistantRegex>,
    loadingLastMessage: Boolean,
    budget: TimelinePrewarmBudget = TimelineFirstScreenPrewarmBudget,
): TimelinePrewarmSelection = collectTimelinePrewarmTexts(
    messageNodes = messageNodes,
    regexes = regexes,
    loadingLastMessage = loadingLastMessage,
    budget = budget,
    excluded = emptySet(),
    startIndex = messageNodes.lastIndex,
)

/**
 * Selects bodies from older, not-yet-warmed nodes. [alreadyWarmed] protects
 * the first-screen cache entries from being parsed twice when the first pass
 * stopped in the middle of a node.
 */
internal fun selectBackgroundPrewarmTexts(
    messageNodes: List<MessageNode>,
    regexes: List<AssistantRegex>,
    loadingLastMessage: Boolean,
    alreadyWarmed: Set<String>,
    startIndex: Int = messageNodes.lastIndex,
    budget: TimelinePrewarmBudget = TimelineBackgroundPrewarmBudget,
): List<String> = collectTimelinePrewarmTexts(
    messageNodes = messageNodes,
    regexes = regexes,
    loadingLastMessage = loadingLastMessage,
    budget = budget,
    excluded = alreadyWarmed,
    startIndex = startIndex,
).texts

private fun collectTimelinePrewarmTexts(
    messageNodes: List<MessageNode>,
    regexes: List<AssistantRegex>,
    loadingLastMessage: Boolean,
    budget: TimelinePrewarmBudget,
    excluded: Set<String>,
    startIndex: Int,
): TimelinePrewarmSelection {
    if (messageNodes.isEmpty() || startIndex < 0) {
        return TimelinePrewarmSelection(emptyList(), -1)
    }
    val selected = LinkedHashSet<String>()
    var chars = 0
    var scanned = 0
    var index = startIndex.coerceAtMost(messageNodes.lastIndex)
    while (index >= 0 && scanned < budget.maxNodes) {
        if (selected.size >= budget.maxTexts) break
        val nodeTexts = messageNodes[index].chatMessageTimelinePrewarmTexts(
            regexes = regexes,
            loading = loadingLastMessage,
            lastMessage = index == messageNodes.lastIndex,
        )
        var stoppedInsideNode = false
        for (text in nodeTexts) {
            if (selected.size >= budget.maxTexts) {
                stoppedInsideNode = true
                break
            }
            if (text in excluded || text in selected) continue
            if (chars + text.length > budget.maxChars) continue
            selected += text
            chars += text.length
        }
        scanned++
        if (stoppedInsideNode) {
            // Resume on THIS node next time (not the older one): the unselected
            // parts still belong to it. `excluded` skips what is already warm.
            break
        }
        index--
    }
    return TimelinePrewarmSelection(
        texts = selected.toList(),
        nextOlderNodeIndex = if (index < 0) -1 else index,
    )
}

/**
 * Parses [texts] in order on the caller's (background) dispatcher. [yield]
 * after each body keeps cancellation responsive when a conversation switch or
 * a session restore invalidates the prewarm.
 */
internal suspend fun prewarmMarkdownTexts(
    texts: Iterable<String>,
    prepare: (String) -> Unit = ::prewarmMarkdownContent,
) {
    for (text in texts) {
        currentCoroutineContext().ensureActive()
        prepare(text)
        yield()
    }
}
