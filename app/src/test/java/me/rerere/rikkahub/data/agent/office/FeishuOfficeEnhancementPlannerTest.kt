package app.amber.feature.office

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
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
    fun workDashboardReportsRemainStructuredWhenSourcesAreSparse() {
        val bundle = FeishuOfficeContextBundle(
            state = testState(),
            notifications = emptyList(),
            usageStats = emptyList(),
            screen = null,
            workspaceSnippets = emptyList(),
            screenError = null,
            mcpHints = emptyList(),
            capturedAtMs = 7L,
        )

        val radar = FeishuOfficeEnhancementPlanner.buildDailyRadarReport(bundle, maxChars = 8_000)
        val briefing = FeishuOfficeEnhancementPlanner.buildProjectBriefingReport("Q 代", bundle, maxChars = 8_000)
        val warroom = FeishuOfficeEnhancementPlanner.buildDocumentWarroomReport(
            template = FeishuOfficeAnalysisTemplate.WHITEPAPER_SCORE,
            bundle = bundle,
            includeModelCouncil = true,
            maxChars = 8_000,
        )

        assertEquals("daily_radar", radar.skillId)
        assertEquals("project_briefing", briefing.skillId)
        assertEquals("document_warroom", warroom.skillId)
        assertTrue(radar.markdown.contains("今日飞书雷达"))
        assertTrue(briefing.markdown.contains("Q 代 项目 Briefing"))
        assertTrue(warroom.markdown.contains("Model Council"))
        assertTrue(warroom.markdown.contains("model_council_start"))
    }

    @Test
    fun documentWarroomTemplatesCoverV5Scenarios() {
        assertEquals(
            listOf(
                "whitepaper_score",
                "selling_point_consensus",
                "competitor_threat",
                "boss_review_rehearsal",
                "launch_talking_points",
                "risk_open_items",
                "comment_draft",
            ),
            FeishuDocumentWarroomTemplate.entries.map { it.wireName },
        )
        assertEquals(
            FeishuDocumentWarroomTemplate.COMPETITOR_THREAT,
            FeishuDocumentWarroomTemplate.fromAnalysisTemplate(FeishuOfficeAnalysisTemplate.COMPETITOR_DIGEST),
        )
        assertEquals(
            FeishuDocumentWarroomTemplate.RISK_OPEN_ITEMS,
            FeishuDocumentWarroomTemplate.fromAnalysisTemplate(FeishuOfficeAnalysisTemplate.TODO_RADAR),
        )
    }

    @Test
    fun documentWarroomV5ReportHasDraftAndCouncilHandoffSections() {
        val bundle = FeishuOfficeContextBundle(
            state = testState(),
            notifications = listOf(FeishuOfficeNotificationSummary(2L, "@我", "请补老板评审追问")),
            usageStats = emptyList(),
            screen = FeishuOfficeScreenSnapshot("老板评审预演", "正文里有卖点、风险和证据缺口", ""),
            workspaceSnippets = listOf(FeishuOfficeWorkspaceSnippet("docs/review.md", "评审材料正文", 6, false)),
            screenError = null,
            mcpHints = FeishuOfficeEnhancementPlanner.defaultMcpHints(),
            capturedAtMs = 8L,
        )

        val report = FeishuOfficeEnhancementPlanner.buildDocumentWarroomReport(
            template = FeishuOfficeAnalysisTemplate.WHITEPAPER_SCORE,
            bundle = bundle,
            includeModelCouncil = true,
            maxChars = 12_000,
            warroomTemplate = FeishuDocumentWarroomTemplate.BOSS_REVIEW_REHEARSAL,
        )

        assertTrue(report.markdown.contains("老板评审预演"))
        assertTrue(report.markdown.contains("## 必改项"))
        assertTrue(report.markdown.contains("## 可选优化项"))
        assertTrue(report.markdown.contains("## 风险项"))
        assertTrue(report.markdown.contains("## 证据缺口"))
        assertTrue(report.markdown.contains("## 可复制评论草稿"))
        assertTrue(report.markdown.contains("## 可写入飞书文档的修订建议草稿"))
        assertTrue(report.markdown.contains("model_council_start"))
        assertTrue(report.markdown.contains("产品市场 / 工程实现 / 风险审查 / 反对者 / 裁判"))
        assertTrue(report.markdown.contains("不自动发送、不自动评论"))
    }

    @Test
    fun v6OpenItemsAndMeetingClosureReportsStayDraftOnly() {
        val bundle = FeishuOfficeContextBundle(
            state = testState(),
            notifications = listOf(FeishuOfficeNotificationSummary(2L, "待办", "Q 代风险 owner 未确认")),
            usageStats = emptyList(),
            screen = null,
            workspaceSnippets = listOf(FeishuOfficeWorkspaceSnippet("docs/open.md", "TBD: 负责人和时间", 12, false)),
            screenError = null,
            mcpHints = FeishuOfficeEnhancementPlanner.defaultMcpHints(),
            capturedAtMs = 9L,
        )

        val openItems = FeishuOfficeEnhancementPlanner.buildOpenItemsRadarReport(
            project = "Q 代",
            bundle = bundle,
            maxChars = 12_000,
        )
        val closure = FeishuOfficeEnhancementPlanner.buildMeetingClosureReport(
            meetingKeyword = "卖点评审",
            dateLabel = "今天",
            bundle = bundle,
            maxChars = 12_000,
        )

        assertEquals("open_items_radar", openItems.skillId)
        assertEquals("meeting_closure", closure.skillId)
        assertTrue(openItems.markdown.contains("officepro_create_task_draft"))
        assertTrue(openItems.markdown.contains("真正写入飞书任务必须二次审批"))
        assertTrue(closure.markdown.contains("会议闭环"))
        assertTrue(closure.markdown.contains("写回任务或 Base 记录必须二次审批"))
    }

    @Test
    fun v6DraftPayloadsAreValidJsonAndRequireApproval() {
        val bundle = FeishuOfficeContextBundle(
            state = testState(),
            notifications = emptyList(),
            usageStats = emptyList(),
            screen = null,
            workspaceSnippets = emptyList(),
            screenError = null,
            mcpHints = emptyList(),
            capturedAtMs = 10L,
        )

        val task = FeishuOfficeEnhancementPlanner.buildTaskDraft(
            title = "确认 Q 代白皮书证据",
            owner = "PMM",
            due = "本周五",
            project = "Q 代",
            sourceRef = "docs/q.md",
            details = "补齐证据和 owner",
            bundle = bundle,
            maxChars = 8_000,
        )
        val base = FeishuOfficeEnhancementPlanner.buildBaseRecordDraft(
            project = "Q 代",
            baseName = "卖点风险库",
            tableName = "风险",
            recordType = "risk",
            fieldsJson = """{"风险":"证据不足"}""",
            bundle = bundle,
            maxChars = 8_000,
        )
        val reply = FeishuOfficeEnhancementPlanner.buildReplyDraft(
            audience = "文档 owner",
            tone = "克制",
            objective = "请补齐证据",
            sourceRef = "docs/q.md",
            bundle = bundle,
            maxChars = 8_000,
        )

        listOf(task, base, reply).forEach { draft ->
            assertTrue(draft.requiresApproval)
            assertTrue(draft.approvalNote.contains("再次审批"))
            Json.parseToJsonElement(draft.payloadJson).jsonObject
            assertTrue(draft.markdown.contains("不自动") || draft.markdown.contains("不调用飞书 MCP") || draft.markdown.contains("必须由用户再次审批"))
        }
        assertEquals(FeishuWorkDraftType.TASK, task.type)
        assertEquals(FeishuWorkDraftType.BASE_RECORD, base.type)
        assertEquals(FeishuWorkDraftType.REPLY, reply.type)
    }

    @Test
    fun workSourcesAreBoundedAndTyped() {
        val bundle = FeishuOfficeContextBundle(
            state = testState(),
            notifications = listOf(FeishuOfficeNotificationSummary(2L, "@我", "请确认风险")),
            usageStats = emptyList(),
            screen = FeishuOfficeScreenSnapshot("Q 代卖点", "正文".repeat(1000), ""),
            workspaceSnippets = listOf(FeishuOfficeWorkspaceSnippet("docs/q.md", "文档".repeat(1000), 2000, true)),
            screenError = null,
            mcpHints = FeishuOfficeEnhancementPlanner.defaultMcpHints(),
            capturedAtMs = 7L,
        )

        val sources = FeishuOfficeEnhancementPlanner.buildWorkSources(bundle, maxSources = 3)

        assertEquals(3, sources.size)
        assertEquals(FeishuWorkSourceType.SCREEN, sources.first().type)
        assertTrue(sources.any { it.truncated })
    }

    @Test
    fun workSkillPackHasStableV3Definitions() {
        val skills = FeishuOfficeEnhancementPlanner.workSkillDefinitions()

        assertEquals(
            listOf("daily_radar", "project_briefing", "document_warroom"),
            skills.map { it.id },
        )
        assertTrue(skills.all { it.risk == FeishuWorkSkillRisk.READ_ONLY })
        assertTrue(skills.any { "task" in it.recommendedMcpCategories })
    }

    @Test
    fun defaultProjectsCoverCoreWorkstreams() {
        val projects = defaultFeishuWorkProjects()

        assertEquals(listOf("Q 代", "MiClaw", "Lhasa", "AI 办公"), projects.map { it.name })
        assertTrue(projects.first { it.name == "Q 代" }.keywords.any { it.contains("Xiaomi 18") })
    }

    @Test
    fun projectMergePreservesExistingKnowledgeAndAddsNewSignals() {
        val existing = defaultFeishuWorkProjects().first()

        val merged = FeishuOfficeEnhancementPlanner.mergeProject(
            existing = existing,
            id = existing.id,
            name = existing.name,
            keywords = listOf("Q 代", "Q5"),
            currentGoal = "更新白皮书",
            coreSellingPoints = listOf("AI First"),
            risks = listOf("证据不足"),
            openQuestions = listOf("负责人是谁"),
            keyDecisions = listOf("沿用 Q 代命名"),
            recentChanges = listOf("新增 briefing"),
            sourceRefs = listOf("workspace/q.md"),
            nowMs = 9L,
        )

        assertEquals(existing.id, merged.id)
        assertEquals("更新白皮书", merged.currentGoal)
        assertTrue("Xiaomi 18" in merged.keywords)
        assertTrue("AI First" in merged.coreSellingPoints)
        assertEquals(9L, merged.updatedAtMs)
    }

    @Test
    fun projectContextReportIncludesKnowledgeAndCapturedSources() {
        val project = defaultFeishuWorkProjects().first()
        val bundle = FeishuOfficeContextBundle(
            state = testState(),
            notifications = listOf(FeishuOfficeNotificationSummary(2L, "@我", "请确认 Q 代风险")),
            usageStats = emptyList(),
            screen = null,
            workspaceSnippets = listOf(FeishuOfficeWorkspaceSnippet("docs/q.md", "白皮书正文", 5, false)),
            screenError = null,
            mcpHints = emptyList(),
            capturedAtMs = 7L,
        )

        val report = FeishuOfficeEnhancementPlanner.buildProjectContextReport(project, bundle, maxChars = 8_000)

        assertEquals("project_context", report.skillId)
        assertTrue(report.markdown.contains("Q 代 项目知识包"))
        assertTrue(report.markdown.contains("当前捕获来源"))
        assertTrue(report.sources.any { it.type == FeishuWorkSourceType.WORKSPACE })
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
