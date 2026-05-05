package me.rerere.rikkahub.data.agent.office

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FeishuOfficeEnhancementPlannerTest {
    @Test
    fun choosesDefaultPackageWhenInstalled() {
        val candidates = listOf(
            FeishuOfficePackageCandidate("com.example.other", "Other", installed = true, launchable = true),
            FeishuOfficePackageCandidate(DEFAULT_FEISHU_OFFICE_PACKAGE, "小米办公 Pro", installed = true, launchable = true),
        )

        val selected = FeishuOfficeEnhancementPlanner.chooseBestCandidate(candidates)

        assertEquals(DEFAULT_FEISHU_OFFICE_PACKAGE, selected!!.packageName)
    }

    @Test
    fun capabilityReflectsPartialPermissions() {
        assertEquals(
            FeishuOfficeCapabilityLevel.DISABLED,
            FeishuOfficeEnhancementPlanner.capability(
                enabled = false,
                installed = true,
                accessibilityReady = true,
                notificationReady = true,
                usageReady = true,
            )
        )
        assertEquals(
            FeishuOfficeCapabilityLevel.SCREEN_READ,
            FeishuOfficeEnhancementPlanner.capability(
                enabled = true,
                installed = true,
                accessibilityReady = true,
                notificationReady = false,
                usageReady = false,
            )
        )
        assertEquals(
            FeishuOfficeCapabilityLevel.FULL_READ_SIGNALS,
            FeishuOfficeEnhancementPlanner.capability(
                enabled = true,
                installed = true,
                accessibilityReady = true,
                notificationReady = true,
                usageReady = true,
            )
        )
    }

    @Test
    fun contextDigestIsBoundedAndStable() {
        val state = FeishuOfficeEnhancementState(
            enabled = true,
            targetPackage = DEFAULT_FEISHU_OFFICE_PACKAGE,
            defaultTemplate = FeishuOfficeAnalysisTemplate.WHITEPAPER_SCORE,
            includeNotificationsByDefault = true,
            includeUsageByDefault = true,
            includeCurrentScreenByDefault = true,
            includeMcpHintsByDefault = true,
            defaultOutputDir = "officepro",
            maxWorkspaceDocs = 6,
            maxReportChars = 20_000,
            installed = true,
            launchable = true,
            label = "小米办公 Pro",
            accessibilityReady = true,
            notificationReady = true,
            usageReady = true,
            capability = FeishuOfficeCapabilityLevel.FULL_READ_SIGNALS,
            lastKnownTitle = "Q 代卖点共识",
            lastError = null,
            updatedAtMs = 1L,
        )
        val digest = FeishuOfficeEnhancementPlanner.buildContextDigest(
            template = FeishuOfficeAnalysisTemplate.COMPETITOR_DIGEST,
            state = state,
            notifications = listOf(
                FeishuOfficeNotificationSummary(
                    postedAtMs = 2L,
                    title = "@我",
                    text = "请确认白皮书风险".repeat(200),
                )
            ),
            usageStats = emptyList(),
            screen = FeishuOfficeScreenSnapshot(
                titleGuess = "文档",
                visibleText = "当前屏幕正文".repeat(300),
                uiTree = "",
            ),
            workspaceSnippets = emptyList(),
            maxChars = 4_000,
        )

        assertTrue(digest.length <= 4_060)
        assertTrue(digest.contains("competitor_digest"))
        assertTrue(digest.contains("当前屏幕"))
        assertFalse(digest.contains("repeat("))
    }

    @Test
    fun dashboardSummaryReflectsMissingPermissionsAndMcpHints() {
        val bundle = FeishuOfficeContextBundle(
            state = testState(
                accessibilityReady = false,
                notificationReady = false,
                usageReady = true,
                capability = FeishuOfficeCapabilityLevel.SIGNALS,
            ),
            notifications = emptyList(),
            usageStats = listOf(FeishuOfficeUsageSummary(DEFAULT_FEISHU_OFFICE_PACKAGE, "小米办公 Pro", 3L, 4L)),
            screen = null,
            workspaceSnippets = emptyList(),
            screenError = "no accessibility",
            mcpHints = FeishuOfficeEnhancementPlanner.defaultMcpHints(),
            capturedAtMs = 5L,
        )

        val summary = FeishuOfficeEnhancementPlanner.buildDashboardSummary(bundle)
        val digest = FeishuOfficeEnhancementPlanner.buildContextDigest(
            template = FeishuOfficeAnalysisTemplate.TODO_RADAR,
            bundle = bundle,
            maxChars = 4_000,
        )

        assertTrue(summary.missingPermissions.contains("accessibility"))
        assertTrue(summary.suggestedActions.any { it.contains("飞书 MCP") })
        assertTrue(digest.contains("飞书 MCP 补强建议"))
        assertTrue(digest.contains("screen_error"))
    }

    @Test
    fun reportMarkdownHasStableSectionsAndTruncates() {
        val draft = FeishuOfficeEnhancementPlanner.buildReportMarkdown(
            title = "Q 代白皮书",
            template = FeishuOfficeAnalysisTemplate.WHITEPAPER_SCORE,
            digest = "上下文".repeat(10_000),
            capturedAtMs = 6L,
            maxChars = 8_000,
        )

        assertTrue(draft.markdown.contains("# Q 代白皮书"))
        assertTrue(draft.markdown.contains("## 结论摘要"))
        assertTrue(draft.markdown.contains("## 原始上下文摘录"))
        assertTrue(draft.truncated)
    }

    @Test
    fun extractsVisibleTextFromAccessibilityDump() {
        val ui = """
            - android.widget.FrameLayout bounds=Rect(0, 0 - 100, 100)
              - android.widget.TextView | 标题一 bounds=Rect(0, 0 - 100, 40)
              - android.widget.Button | 搜索 bounds=Rect(0, 40 - 100, 80)
        """.trimIndent()

        val visible = FeishuOfficeEnhancementPlanner.extractVisibleText(ui)

        assertTrue(visible.contains("标题一"))
        assertTrue(visible.contains("搜索"))
    }

    private fun testState(
        accessibilityReady: Boolean = true,
        notificationReady: Boolean = true,
        usageReady: Boolean = true,
        capability: FeishuOfficeCapabilityLevel = FeishuOfficeCapabilityLevel.FULL_READ_SIGNALS,
    ) = FeishuOfficeEnhancementState(
        enabled = true,
        targetPackage = DEFAULT_FEISHU_OFFICE_PACKAGE,
        defaultTemplate = FeishuOfficeAnalysisTemplate.WHITEPAPER_SCORE,
        includeNotificationsByDefault = true,
        includeUsageByDefault = true,
        includeCurrentScreenByDefault = true,
        includeMcpHintsByDefault = true,
        defaultOutputDir = "officepro",
        maxWorkspaceDocs = 6,
        maxReportChars = 20_000,
        installed = true,
        launchable = true,
        label = "小米办公 Pro",
        accessibilityReady = accessibilityReady,
        notificationReady = notificationReady,
        usageReady = usageReady,
        capability = capability,
        lastKnownTitle = "Q 代卖点共识",
        lastError = null,
        updatedAtMs = 1L,
    )
}
