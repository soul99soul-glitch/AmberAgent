package app.amber.feature.ui.pages.sessionhome

import app.amber.agent.Screen
import app.amber.feature.home.ContinueRoute
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionHomeRouteTest {
    @Test
    fun `continue routes preserve their task focus`() {
        assertEquals(
            Screen.DeepRead(
                topicId = "topic-1",
                title = "Topic",
                sourceUrl = "https://example.com/topic",
            ),
            ContinueRoute.DeepRead(
                topicId = "topic-1",
                title = "Topic",
                sourceUrl = "https://example.com/topic",
            ).toScreen(),
        )
        assertEquals(
            Screen.Chat(
                id = "conversation-1",
                messageId = "message-1",
                toolCallId = "call-1",
            ),
            ContinueRoute.Chat(
                conversationId = "conversation-1",
                messageId = "message-1",
                toolCallId = "call-1",
            ).toScreen(),
        )
        assertEquals(
            Screen.Chat(
                id = "conversation-image",
                messageId = "message-image",
                toolCallId = "call-image",
            ),
            ContinueRoute.ImageGeneration(
                conversationId = "conversation-image",
                messageId = "message-image",
                toolCallId = "call-image",
            ).toScreen(),
        )
        assertEquals(
            Screen.MiniAppRunner(appId = "mini-1"),
            ContinueRoute.MiniAppRunner(appId = "mini-1").toScreen(),
        )
        assertEquals(
            Screen.NovelMarkdown(
                projectId = "project-1",
                branchSlug = "branch-a",
                jobId = "job-1",
            ),
            ContinueRoute.NovelWorkspace(
                projectId = "project-1",
                branchSlug = "branch-a",
                jobId = "job-1",
            ).toScreen(),
        )
    }

    @Test
    fun `chat backed Continue routes are blocked when their conversation is gone`() = runTest {
        assertFalse(
            canOpenContinueRoute(ContinueRoute.Chat("deleted")) { false },
        )
        assertTrue(
            canOpenContinueRoute(
                ContinueRoute.ImageGeneration("existing", toolCallId = "call-1"),
            ) { true },
        )
        assertTrue(
            canOpenContinueRoute(ContinueRoute.DeepRead("topic", "Topic")) {
                error("non-chat route must not query conversations")
            },
        )
    }
}
