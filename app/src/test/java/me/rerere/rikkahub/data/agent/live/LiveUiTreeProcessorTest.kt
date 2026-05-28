package app.amber.feature.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveUiTreeProcessorTest {
    @Test
    fun sanitizeTextRedactsSensitiveValues() {
        assertEquals("[已隐藏敏感内容]", LiveUiTreeProcessor.sanitizeText("password: hunter2"))
        assertEquals("[验证码]", LiveUiTreeProcessor.sanitizeText("123456"))
        assertEquals("[邮箱]", LiveUiTreeProcessor.sanitizeText("name@example.com"))
        assertEquals("[手机号]", LiveUiTreeProcessor.sanitizeText("13800138000"))
    }

    @Test
    fun sanitizeUiTreeRedactsTextAndDescriptionFields() {
        val uiTree = """
            - class=TextView | text=password 123456 | bounds=[0,0][10,10]
            - class=TextView | desc=联系 name@example.com | bounds=[0,10][10,20]
        """.trimIndent()

        val sanitized = LiveUiTreeProcessor.sanitizeUiTree(uiTree)

        assertTrue(sanitized.contains("text=[已隐藏敏感内容]"))
        assertTrue(sanitized.contains("desc=联系 [邮箱]"))
        assertFalse(sanitized.contains("123456"))
        assertFalse(sanitized.contains("name@example.com"))
    }

    @Test
    fun stableHashIgnoresVolatileNumbersAndTime() {
        val first = LiveUiTreeProcessor.stableHash(
            packageName = "com.example",
            title = "订单",
            uiTree = "- class=TextView | text=14:20 价格 128 | bounds=[0,0][10,10]",
        )
        val second = LiveUiTreeProcessor.stableHash(
            packageName = "com.example",
            title = "订单",
            uiTree = "- class=TextView | text=14:21 价格 256 | bounds=[0,0][10,10]",
        )
        val third = LiveUiTreeProcessor.stableHash(
            packageName = "com.example",
            title = "订单",
            uiTree = "- class=TextView | text=确认付款 | bounds=[0,0][10,10]",
        )

        assertEquals(first, second)
        assertNotEquals(first, third)
    }

    @Test
    fun shouldAnalyzeWaitsForStabilityAndThrottleWindow() {
        assertFalse(
            LiveUiTreeProcessor.shouldAnalyze(
                previousHash = "a",
                nextHash = "b",
                nowMillis = 1_000,
                changedAtMillis = 200,
                lastAnalysisAtMillis = 0,
                stableDelayMs = 1_500,
                minAnalysisIntervalMs = 8_000,
            )
        )
        assertFalse(
            LiveUiTreeProcessor.shouldAnalyze(
                previousHash = "a",
                nextHash = "b",
                nowMillis = 7_000,
                changedAtMillis = 0,
                lastAnalysisAtMillis = 1_000,
                stableDelayMs = 1_500,
                minAnalysisIntervalMs = 8_000,
            )
        )
        assertTrue(
            LiveUiTreeProcessor.shouldAnalyze(
                previousHash = "a",
                nextHash = "b",
                nowMillis = 10_000,
                changedAtMillis = 0,
                lastAnalysisAtMillis = 1_000,
                stableDelayMs = 1_500,
                minAnalysisIntervalMs = 8_000,
            )
        )
    }

    @Test
    fun shouldAnalyzeSkipsUnchangedHashUnlessForced() {
        assertFalse(
            LiveUiTreeProcessor.shouldAnalyze(
                previousHash = "same",
                nextHash = "same",
                nowMillis = 20_000,
                changedAtMillis = 0,
                lastAnalysisAtMillis = 0,
                stableDelayMs = 1_500,
                minAnalysisIntervalMs = 8_000,
            )
        )
        assertTrue(
            LiveUiTreeProcessor.shouldAnalyze(
                previousHash = "same",
                nextHash = "same",
                nowMillis = 20_000,
                changedAtMillis = 0,
                lastAnalysisAtMillis = 0,
                stableDelayMs = 1_500,
                minAnalysisIntervalMs = 8_000,
                force = true,
            )
        )
    }

    @Test
    fun windowCandidateFiltersDividerOwnAppSystemAndLowContentWindows() {
        val valid = LiveWindowCandidate(
            type = 1,
            packageName = "com.tencent.mm",
            appLabel = "微信",
            title = "群聊",
            area = 900_000,
            visibleTextLength = 80,
            visibleTextCount = 5,
            nodeCount = 60,
        )
        val divider = valid.copy(splitDivider = true)
        val ownApp = valid.copy(packageName = "me.rerere.amberagent.notion", ownApp = true)
        val system = valid.copy(packageName = "com.android.systemui", systemLike = true)
        val blank = valid.copy(visibleTextLength = 0, visibleTextCount = 0)
        val tiny = valid.copy(area = 4_000)

        assertTrue(valid.isEligible())
        assertFalse(divider.isEligible())
        assertFalse(ownApp.isEligible())
        assertFalse(system.isEligible())
        assertFalse(blank.isEligible())
        assertFalse(tiny.isEligible())
    }

    @Test
    fun windowCandidateScorePrefersContentRichWindow() {
        val dividerLike = LiveWindowCandidate(
            type = 1,
            packageName = "android",
            appLabel = "Canvas Window",
            title = "Canvas Window",
            area = 30_000,
            visibleTextLength = 16,
            visibleTextCount = 2,
            nodeCount = 5,
            active = true,
            focused = true,
            systemLike = true,
        )
        val chat = LiveWindowCandidate(
            type = 1,
            packageName = "com.tencent.mm",
            appLabel = "微信",
            title = "相机天塌了",
            area = 900_000,
            visibleTextLength = 160,
            visibleTextCount = 8,
            nodeCount = 80,
        )

        assertFalse(dividerLike.isEligible())
        assertTrue(chat.isEligible())
        assertTrue(chat.selectionScore() > dividerLike.selectionScore())
    }

    @Test
    fun compressContentTextKeepsConversationAndDropsChrome() {
        val visibleText = """
            AI 伴随中
            Canvas Window
            消息
            文件
            相机天塌了🙃
            我们诚恳接受各方批评意见，然后开了评论精选
            有照亮周五的🍵，有追星追出两个爸爸
            按住 说话
            发到聊天
        """.trimIndent()

        val content = LiveUiTreeProcessor.compressContentText(visibleText, "")

        assertTrue(content.contains("相机天塌了"))
        assertTrue(content.contains("我们诚恳接受各方批评意见"))
        assertFalse(content.contains("Canvas Window"))
        assertFalse(content.contains("按住"))
        assertFalse(content.contains("发到聊天"))
        assertFalse(content.lineSequence().any { it == "消息" || it == "文件" })
    }

    @Test
    fun compactAnalysisItemsDropsFillerAndLimitsOutput() {
        val items = LiveUiTreeProcessor.compactAnalysisItems(
            listOf(
                "- 群里核心是在讨论评论精选和请假",
                "- 可以尝试查看分割线两侧是否有内容",
                "- 你可以问我是否需要更多信息",
                "- 有人提到明天还有周五",
                "- 底部 Tab 有消息、文件、云文档",
            ),
            maxItems = 2,
        )

        assertEquals(listOf("群里核心是在讨论评论精选和请假", "有人提到明天还有周五"), items)
    }
}
