package app.amber.feature.ui.pages.chat

import app.amber.ai.core.MessageRole
import app.amber.ai.ui.UIMessage
import app.amber.ai.ui.UIMessagePart
import app.amber.core.model.MessageNode
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.uuid.Uuid

class ChatTimelinePrewarmSelectionTest {
    @Test
    fun firstScreenCoversUserAndShortAssistantBodies() {
        val nodes = listOf(
            UIMessage.user("你好，帮我看看这个").toMessageNode(),
            UIMessage.assistant("短回复").toMessageNode(),
            UIMessage.assistant("另一段正文").toMessageNode(),
            UIMessage.user("补充一句").toMessageNode(),
        )

        val selection = selectFirstScreenPrewarmTexts(
            messageNodes = nodes,
            regexes = emptyList(),
            loadingLastMessage = false,
            budget = TimelinePrewarmBudget(maxNodes = 8, maxTexts = 24, maxChars = 100_000),
        )

        // Newest-first (reverseLayout): the bottom of the list mounts first.
        assertEquals(
            listOf("补充一句", "另一段正文", "短回复", "你好，帮我看看这个"),
            selection.texts,
        )
        assertEquals(-1, selection.nextOlderNodeIndex)
    }

    @Test
    fun generatingAssistantTailIsNotPrewarmedButSettledUserTailIs() {
        val older = UIMessage.assistant("older settled body").toMessageNode()
        val tail = UIMessage.assistant("partial stream").toMessageNode()
        val loading = selectFirstScreenPrewarmTexts(
            messageNodes = listOf(older, tail),
            regexes = emptyList(),
            loadingLastMessage = true,
            budget = TimelinePrewarmBudget(8, 24, 100_000),
        )
        assertEquals(listOf("older settled body"), loading.texts)

        val userTail = UIMessage.user("fully sent user body").toMessageNode()
        val waitingForAssistant = selectFirstScreenPrewarmTexts(
            messageNodes = listOf(older, userTail),
            regexes = emptyList(),
            loadingLastMessage = true,
            budget = TimelinePrewarmBudget(8, 24, 100_000),
        )
        assertEquals(listOf("fully sent user body", "older settled body"), waitingForAssistant.texts)
    }

    @Test
    fun firstScreenStopsAtNodeAndTextBudgetsAndLeavesOlderNodesForBackground() {
        val nodes = (0 until 10).map { index ->
            UIMessage.user("body-$index").toMessageNode()
        }

        val selection = selectFirstScreenPrewarmTexts(
            messageNodes = nodes,
            regexes = emptyList(),
            loadingLastMessage = false,
            budget = TimelinePrewarmBudget(maxNodes = 3, maxTexts = 16, maxChars = 100_000),
        )
        assertEquals(listOf("body-9", "body-8", "body-7"), selection.texts)
        assertEquals(6, selection.nextOlderNodeIndex)

        val background = selectBackgroundPrewarmTexts(
            messageNodes = nodes,
            regexes = emptyList(),
            loadingLastMessage = false,
            alreadyWarmed = selection.texts.toSet(),
            startIndex = selection.nextOlderNodeIndex,
            budget = TimelinePrewarmBudget(maxNodes = 3, maxTexts = 16, maxChars = 100_000),
        )
        assertEquals(listOf("body-6", "body-5", "body-4"), background)
    }

    @Test
    fun backgroundResumesThePartiallyWarmedNodeInsteadOfSkippingIt() {
        val latest = MessageNode(
            id = Uuid.random(),
            messages = listOf(
                UIMessage(
                    role = MessageRole.ASSISTANT,
                    parts = listOf(
                        UIMessagePart.Text("part-a"),
                        UIMessagePart.Text("part-b"),
                        UIMessagePart.Text("part-c"),
                    ),
                ),
            ),
        )
        val older = UIMessage.user("older").toMessageNode()
        val nodes = listOf(older, latest)

        val first = selectFirstScreenPrewarmTexts(
            messageNodes = nodes,
            regexes = emptyList(),
            loadingLastMessage = false,
            budget = TimelinePrewarmBudget(maxNodes = 8, maxTexts = 2, maxChars = 1_000),
        )
        assertEquals(listOf("part-a", "part-b"), first.texts)
        // The cursor points back at the partially covered tail node.
        assertEquals(1, first.nextOlderNodeIndex)

        val rest = selectBackgroundPrewarmTexts(
            messageNodes = nodes,
            regexes = emptyList(),
            loadingLastMessage = false,
            alreadyWarmed = first.texts.toSet(),
            startIndex = first.nextOlderNodeIndex,
            budget = TimelinePrewarmBudget(maxNodes = 8, maxTexts = 8, maxChars = 1_000),
        )
        assertEquals(listOf("part-c", "older"), rest)
    }

    @Test
    fun textAndCharBudgetsAreApplied() {
        val nodes = listOf(
            UIMessage.user("1234567890").toMessageNode(),
            UIMessage.user("abcdefghij").toMessageNode(),
            UIMessage.user("ABCDEFGHIJ").toMessageNode(),
        )

        val textCapped = selectFirstScreenPrewarmTexts(
            messageNodes = nodes,
            regexes = emptyList(),
            loadingLastMessage = false,
            budget = TimelinePrewarmBudget(maxNodes = 8, maxTexts = 2, maxChars = 100_000),
        )
        assertEquals(listOf("ABCDEFGHIJ", "abcdefghij"), textCapped.texts)

        val charCapped = selectFirstScreenPrewarmTexts(
            // Newest last: its 20-char body is over the 12-char budget, so the
            // next-newest 10-char body is the only one that fits.
            messageNodes = listOf(
                UIMessage.user("abcdefghij").toMessageNode(),
                UIMessage.user("1234567890").toMessageNode(),
                UIMessage.user("A".repeat(20)).toMessageNode(),
            ),
            regexes = emptyList(),
            loadingLastMessage = false,
            budget = TimelinePrewarmBudget(maxNodes = 8, maxTexts = 8, maxChars = 12),
        )
        assertEquals(listOf("1234567890"), charCapped.texts)
    }

    @Test
    fun duplicateBodiesAreWarmedOncePerPlan() {
        val nodes = listOf(
            UIMessage.assistant("same body").toMessageNode(),
            UIMessage.user("same body").toMessageNode(),
        )
        val selection = selectFirstScreenPrewarmTexts(
            messageNodes = nodes,
            regexes = emptyList(),
            loadingLastMessage = false,
            budget = TimelinePrewarmBudget(8, 24, 100_000),
        )
        assertEquals(listOf("same body"), selection.texts)
    }

    @Test
    fun userRouteTagsAreStrippedToMatchTheRenderedMarkdownKey() {
        val nodes = listOf(
            UIMessage.user("[ROUTE:svg]\n画一只猫").toMessageNode(),
        )
        val selection = selectFirstScreenPrewarmTexts(
            messageNodes = nodes,
            regexes = emptyList(),
            loadingLastMessage = false,
            budget = TimelinePrewarmBudget(8, 24, 100_000),
        )
        assertEquals(listOf("画一只猫"), selection.texts)
    }

    @Test
    fun prewarmLoopStopsOnCancellation() = runTest {
        val prepared = mutableListOf<String>()
        var job: Job? = null
        job = launch {
            prewarmMarkdownTexts(listOf("a", "b", "c", "d")) { text ->
                prepared += text
                if (prepared.size == 2) job?.cancel()
            }
        }
        job.join()
        assertEquals(listOf("a", "b"), prepared)
    }

    private fun UIMessage.toMessageNode(): MessageNode {
        require(role == MessageRole.USER || role == MessageRole.ASSISTANT)
        return MessageNode(id = Uuid.random(), messages = listOf(this))
    }
}
