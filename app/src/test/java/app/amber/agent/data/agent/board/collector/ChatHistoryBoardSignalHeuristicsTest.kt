package app.amber.feature.board.collector

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatHistoryBoardSignalHeuristicsTest {
    @Test
    fun dropsLowValueGarbledTestConversation() {
        val score = ChatHistoryBoardSignalHeuristics.relevanceScore(
            title = "清理窗台绿萝相关含乱码的混合语言对话",
            tailTexts = listOf("user: 请继续输出一段混合语言长文，里面包含乱码"),
            nodeCount = 8,
        )

        assertNull(score)
    }

    @Test
    fun dropsLongStreamingTestConversation() {
        val score = ChatHistoryBoardSignalHeuristics.relevanceScore(
            title = "移动端聊天流式渲染长文对话",
            tailTexts = listOf("user: 写一篇两千字长文生成测试，观察流式渲染效果"),
            nodeCount = 12,
        )

        assertNull(score)
    }

    @Test
    fun dropsOrdinarySmallTalk() {
        val score = ChatHistoryBoardSignalHeuristics.relevanceScore(
            title = "随便聊聊",
            tailTexts = listOf("user: 今天天气还不错"),
            nodeCount = 6,
        )

        assertNull(score)
    }

    @Test
    fun dropsWeakKeywordEvenWithDeepConversation() {
        val score = ChatHistoryBoardSignalHeuristics.relevanceScore(
            title = "文档随便看看",
            tailTexts = listOf("user: 这份文档内容挺长，先放着以后再说"),
            nodeCount = 12,
        )

        assertNull(score)
    }

    @Test
    fun doesNotTreatPrInsidePromptOrProviderAsPrKeyword() {
        val score = ChatHistoryBoardSignalHeuristics.relevanceScore(
            title = "provider prompt product process preview",
            tailTexts = listOf("user: provider prompt product process preview"),
            nodeCount = 12,
        )

        assertNull(score)
    }

    @Test
    fun keepsExplicitActionableWorkContext() {
        val score = ChatHistoryBoardSignalHeuristics.relevanceScore(
            title = "发布会待办",
            tailTexts = listOf("user: TODO 明天截止前跟进发布会文档和 bug 修复"),
            nodeCount = 2,
        )

        assertNotNull(score)
        assertTrue(score!! >= 5)
    }

    @Test
    fun keepsShortExplicitBugFix() {
        val score = ChatHistoryBoardSignalHeuristics.relevanceScore(
            title = "修复 bug",
            tailTexts = listOf("user: 修复这个 bug"),
            nodeCount = 1,
        )

        assertNotNull(score)
        assertTrue(score!! >= 5)
    }

    @Test
    fun keepsShortMeetingDecisionFollowUp() {
        val score = ChatHistoryBoardSignalHeuristics.relevanceScore(
            title = "会议决定跟进",
            tailTexts = listOf("user: 跟进今天会议决定"),
            nodeCount = 1,
        )

        assertNotNull(score)
        assertTrue(score!! >= 5)
    }
}
