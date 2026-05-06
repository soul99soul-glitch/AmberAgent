package me.rerere.rikkahub.data.agent.office

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

const val DEFAULT_FEISHU_OFFICE_PACKAGE = "com.ss.android.lark.saxmsa667"

@Serializable
data class FeishuOfficeEnhancementSetting(
    val enabled: Boolean = false,
    val targetPackage: String = DEFAULT_FEISHU_OFFICE_PACKAGE,
    val defaultTemplate: FeishuOfficeAnalysisTemplate = FeishuOfficeAnalysisTemplate.WHITEPAPER_SCORE,
    val includeNotificationsByDefault: Boolean = true,
    val includeUsageByDefault: Boolean = true,
    val includeCurrentScreenByDefault: Boolean = false,
    val includeMcpHintsByDefault: Boolean = true,
    val defaultOutputDir: String = "officepro",
    val maxWorkspaceDocs: Int = 6,
    val maxReportChars: Int = 20_000,
    val workDashboard: FeishuWorkDashboardSetting = FeishuWorkDashboardSetting(),
)

@Serializable
data class FeishuWorkDashboardSetting(
    val enabled: Boolean = true,
    val defaultProjectKeywords: List<String> = listOf("Q 代", "MiClaw", "Lhasa", "AI 办公"),
    val projects: List<FeishuWorkProject> = defaultFeishuWorkProjects(),
    val defaultOutputDir: String = "officepro",
    val includeNotifications: Boolean = true,
    val includeCurrentScreen: Boolean = false,
    val includeMcpSources: Boolean = true,
    val includeModelCouncil: Boolean = false,
    val maxSourceDocs: Int = 6,
    val maxReportChars: Int = 20_000,
)

@Serializable
data class FeishuWorkProject(
    val id: String,
    val name: String,
    val keywords: List<String>,
    val currentGoal: String = "",
    val coreSellingPoints: List<String> = emptyList(),
    val risks: List<String> = emptyList(),
    val openQuestions: List<String> = emptyList(),
    val keyDecisions: List<String> = emptyList(),
    val recentChanges: List<String> = emptyList(),
    val sourceRefs: List<String> = emptyList(),
    val updatedAtMs: Long = 0L,
)

fun defaultFeishuWorkProjects(): List<FeishuWorkProject> = listOf(
    FeishuWorkProject(
        id = "q-generation",
        name = "Q 代",
        keywords = listOf("Q 代", "Xiaomi 18", "Q2", "Q3", "Q5", "Q235"),
        currentGoal = "沉淀 Xiaomi 18 / Q 代产品简介、白皮书和卖点共识。",
    ),
    FeishuWorkProject(
        id = "miclaw",
        name = "MiClaw",
        keywords = listOf("MiClaw", "miclaw", "AI Agent", "AgenticOS"),
        currentGoal = "统一 MiClaw / AgenticOS 的 AI First 产品叙事和明星场景。",
    ),
    FeishuWorkProject(
        id = "lhasa",
        name = "Lhasa",
        keywords = listOf("Lhasa", "AI 手机", "Magic V6", "Find N6"),
        currentGoal = "回答 Lhasa 为什么是 AI 手机，并沉淀对标与卖点表达。",
    ),
    FeishuWorkProject(
        id = "ai-office",
        name = "AI 办公",
        keywords = listOf("AI 办公", "办公", "飞书", "小米办公 Pro", "AmberAgent"),
        currentGoal = "把 AI 工具链转化为可日用的产品市场工作流。",
    ),
)

@Serializable
enum class FeishuOfficeAnalysisTemplate(
    val wireName: String,
    val zhLabel: String,
    val prompt: String,
) {
    @SerialName("whitepaper_score")
    WHITEPAPER_SCORE(
        wireName = "whitepaper_score",
        zhLabel = "白皮书质量评分",
        prompt = "按叙事完整度、卖点清晰度、证据强度、竞品差异和 AI First 一致性评分，给出风险、修改建议和下一步。",
    ),

    @SerialName("competitor_digest")
    COMPETITOR_DIGEST(
        wireName = "competitor_digest",
        zhLabel = "竞品简报消化",
        prompt = "提炼友商主打表达、对 Q 代 / MiClaw / Lhasa 的威胁、可反击表达，以及需要补证据的问题。",
    ),

    @SerialName("todo_radar")
    TODO_RADAR(
        wireName = "todo_radar",
        zhLabel = "文档待办雷达",
        prompt = "抽取待确认、风险、负责人、截止时间、下一步动作，并按紧急程度排序。",
    );

    companion object {
        fun fromWireName(raw: String?): FeishuOfficeAnalysisTemplate =
            entries.firstOrNull { it.wireName == raw } ?: WHITEPAPER_SCORE
    }
}

enum class FeishuDocumentWarroomTemplate(
    val wireName: String,
    val zhLabel: String,
    val focus: String,
    val mustFix: String,
    val optionalImprove: String,
    val risk: String,
    val evidenceGap: String,
    val commentDraft: String,
    val revisionDraft: String,
    val councilObjective: String,
) {
    WHITEPAPER_SCORE(
        wireName = "whitepaper_score",
        zhLabel = "白皮书质量评分",
        focus = "判断叙事完整度、卖点清晰度、证据强度、竞品差异和 AI First 一致性。",
        mustFix = "标出会导致评审人无法理解、无法相信或无法转述的硬伤。",
        optionalImprove = "补充能提升白皮书说服力的结构、措辞和证据组织建议。",
        risk = "重点检查证据不足、结论跳跃、竞品对比缺失和 AI First 叙事不一致。",
        evidenceGap = "列出需要补充的数据、用户案例、竞品材料、负责人确认或版本来源。",
        commentDraft = "生成一段适合贴到文档评论里的质量评分反馈，语气直接但不冒犯。",
        revisionDraft = "给出可写入正文的章节级修订建议草稿。",
        councilObjective = "请从产品市场、工程实现、风险审查、反对者和裁判视角评审这份白皮书是否能进入正式评审。",
    ),
    SELLING_POINT_CONSENSUS(
        wireName = "selling_point_consensus",
        zhLabel = "卖点共识审查",
        focus = "判断卖点是否能被产品、市场、销售、发布会和老板评审共同复用。",
        mustFix = "标出概念混乱、主张过多、表达不可传播或和项目目标冲突的卖点。",
        optionalImprove = "沉淀更短、更稳定、更像对外话术的卖点表达。",
        risk = "重点检查内部术语过重、用户价值不清、证据和场景脱节。",
        evidenceGap = "列出每个核心卖点缺少的证据、场景、对比对象和口径 owner。",
        commentDraft = "生成一段“卖点共识待确认”的文档评论草稿。",
        revisionDraft = "给出卖点排序、标题和关键句的修订建议草稿。",
        councilObjective = "请多视角判断这些卖点是否足够聚焦、可传播，并找出最可能被挑战的地方。",
    ),
    COMPETITOR_THREAT(
        wireName = "competitor_threat",
        zhLabel = "竞品威胁评估",
        focus = "判断友商表达、功能和叙事对当前项目的威胁，以及可反击的表达空间。",
        mustFix = "标出低估竞品、反击证据不足或直接复述友商框架的问题。",
        optionalImprove = "补充更锋利的对标维度、反击表达和差异化证据组织。",
        risk = "重点检查竞品材料来源不完整、过度乐观和对用户感知差异的忽略。",
        evidenceGap = "列出需要继续查证的竞品功能、价格、发布会话术、评测和用户反馈。",
        commentDraft = "生成一段适合评论的竞品威胁提醒和补证据请求。",
        revisionDraft = "给出对标段落、反击话术和风险提示的修订草稿。",
        councilObjective = "请从支持者、反对者、产品市场和风险审查视角评估这份竞品判断是否站得住。",
    ),
    BOSS_REVIEW_REHEARSAL(
        wireName = "boss_review_rehearsal",
        zhLabel = "老板评审预演",
        focus = "预判老板或高层评审会追问什么，以及当前材料是否能正面回答。",
        mustFix = "标出会被一问就穿的目标、证据、ROI、差异化和时间线问题。",
        optionalImprove = "补充更像评审现场回答的短句、备选口径和追问预案。",
        risk = "重点检查过度铺陈、重点不前置、没有结论、没有取舍和责任边界不清。",
        evidenceGap = "列出评审前必须补齐的数字、案例、owner、竞品事实和决策请求。",
        commentDraft = "生成一段“评审前建议补齐”的评论草稿。",
        revisionDraft = "给出开场结论、三条主张和追问答复的修订草稿。",
        councilObjective = "请模拟老板评审，分别提出最尖锐追问、最强辩护和最终是否建议过会。",
    ),
    LAUNCH_TALKING_POINTS(
        wireName = "launch_talking_points",
        zhLabel = "发布会话术提炼",
        focus = "把复杂材料压缩成发布会、媒体沟通和销售培训可复用的表达。",
        mustFix = "标出不适合对外、太内部、太长、缺少用户利益点或容易被误解的表达。",
        optionalImprove = "提炼 3 条以内主话术、短标题、证据句和可讲故事的场景。",
        risk = "重点检查夸大、合规风险、技术承诺过满和竞品攻击过度。",
        evidenceGap = "列出发布前需要法务、PR、产品和工程确认的事实。",
        commentDraft = "生成一段适合给文档作者的发布会话术建议。",
        revisionDraft = "给出可直接放入发布会简报的标题、主张和支撑句草稿。",
        councilObjective = "请用产品市场、反对者和裁判视角判断这些话术是否清楚、可信、可传播。",
    ),
    RISK_OPEN_ITEMS(
        wireName = "risk_open_items",
        zhLabel = "风险与待确认抽取",
        focus = "从文档中抽取待确认、风险、TBD、遗留问题、负责人和下一步动作。",
        mustFix = "标出没有 owner、没有截止时间、影响范围不清或会阻塞决策的问题。",
        optionalImprove = "合并重复项，按紧急度和影响面排序，并给出催办话术。",
        risk = "重点检查风险被轻描淡写、责任归属不清和外部依赖未显式记录。",
        evidenceGap = "列出每个问题还需要补的来源、负责人确认和状态证据。",
        commentDraft = "生成一段可以贴到文档里的待确认清单评论草稿。",
        revisionDraft = "给出表格化的遗留问题清单草稿，可后续转任务/Base。",
        councilObjective = "请从风险审查、工程实现和裁判视角确认这些遗留问题是否完整、可执行。",
    ),
    COMMENT_DRAFT(
        wireName = "comment_draft",
        zhLabel = "评论草稿生成",
        focus = "把分析结论转成可复制到飞书文档评论或群聊里的短反馈草稿。",
        mustFix = "确保评论指出具体问题、具体位置和具体请求，不泛泛而谈。",
        optionalImprove = "提供强/中/弱三种语气版本，方便按协作关系选择。",
        risk = "重点检查措辞是否越权、过度武断、泄露内部推理或暗示已自动发送。",
        evidenceGap = "列出评论前还需要确认的上下文、来源和责任人。",
        commentDraft = "生成可复制的评论草稿，但必须明确不自动发送。",
        revisionDraft = "给出评论对应的正文修改建议草稿。",
        councilObjective = "请多视角判断评论是否清楚、克制、可执行，并避免误伤协作关系。",
    );

    companion object {
        fun fromWireName(raw: String?): FeishuDocumentWarroomTemplate =
            entries.firstOrNull { it.wireName == raw } ?: WHITEPAPER_SCORE

        fun fromAnalysisTemplate(template: FeishuOfficeAnalysisTemplate): FeishuDocumentWarroomTemplate =
            when (template) {
                FeishuOfficeAnalysisTemplate.WHITEPAPER_SCORE -> WHITEPAPER_SCORE
                FeishuOfficeAnalysisTemplate.COMPETITOR_DIGEST -> COMPETITOR_THREAT
                FeishuOfficeAnalysisTemplate.TODO_RADAR -> RISK_OPEN_ITEMS
            }
    }
}

enum class FeishuOfficeCapabilityLevel(val wireName: String) {
    DISABLED("disabled"),
    NOT_INSTALLED("not_installed"),
    APP_ONLY("app_only"),
    SIGNALS("signals"),
    SCREEN_READ("screen_read"),
    FULL_READ_SIGNALS("full_read_signals"),
}

data class FeishuOfficePackageCandidate(
    val packageName: String,
    val label: String,
    val installed: Boolean,
    val launchable: Boolean,
)

data class FeishuOfficeEnhancementState(
    val enabled: Boolean,
    val targetPackage: String,
    val defaultTemplate: FeishuOfficeAnalysisTemplate,
    val includeNotificationsByDefault: Boolean,
    val includeUsageByDefault: Boolean,
    val includeCurrentScreenByDefault: Boolean,
    val includeMcpHintsByDefault: Boolean,
    val defaultOutputDir: String,
    val maxWorkspaceDocs: Int,
    val maxReportChars: Int,
    val installed: Boolean,
    val launchable: Boolean,
    val label: String?,
    val accessibilityReady: Boolean,
    val notificationReady: Boolean,
    val usageReady: Boolean,
    val capability: FeishuOfficeCapabilityLevel,
    val lastKnownTitle: String?,
    val lastError: String?,
    val updatedAtMs: Long,
    val workDashboard: FeishuWorkDashboardSetting = FeishuWorkDashboardSetting(),
)

data class FeishuOfficeNotificationSummary(
    val postedAtMs: Long,
    val title: String,
    val text: String,
)

data class FeishuOfficeUsageSummary(
    val packageName: String,
    val label: String,
    val lastTimeUsedMs: Long,
    val totalTimeForegroundMs: Long,
)

data class FeishuOfficeWorkspaceSnippet(
    val path: String,
    val content: String,
    val totalChars: Int,
    val truncated: Boolean,
)

data class FeishuOfficeScreenSnapshot(
    val titleGuess: String?,
    val visibleText: String,
    val uiTree: String,
)

data class FeishuOfficeContextBundle(
    val state: FeishuOfficeEnhancementState,
    val notifications: List<FeishuOfficeNotificationSummary>,
    val usageStats: List<FeishuOfficeUsageSummary>,
    val screen: FeishuOfficeScreenSnapshot?,
    val workspaceSnippets: List<FeishuOfficeWorkspaceSnippet>,
    val screenError: String?,
    val mcpHints: List<String>,
    val capturedAtMs: Long,
)

data class FeishuOfficeDashboardSummary(
    val capability: FeishuOfficeCapabilityLevel,
    val missingPermissions: List<String>,
    val notificationCount: Int,
    val recentTitle: String?,
    val suggestedActions: List<String>,
    val updatedAtMs: Long,
)

data class FeishuOfficeReportDraft(
    val markdown: String,
    val totalChars: Int,
    val truncated: Boolean,
)

data class FeishuOfficeReportResult(
    val path: String,
    val title: String,
    val template: FeishuOfficeAnalysisTemplate,
    val truncated: Boolean,
    val writtenAtMs: Long,
    val totalChars: Int,
)

enum class FeishuWorkSourceType(val wireName: String) {
    NOTIFICATION("notification"),
    SCREEN("screen"),
    WORKSPACE("workspace"),
    MCP_HINT("mcp_hint"),
}

enum class FeishuWorkSkillRisk(val wireName: String) {
    READ_ONLY("read_only"),
    WRITE_WORKSPACE("write_workspace"),
    FEISHU_DRAFT("feishu_draft"),
}

data class FeishuWorkSource(
    val type: FeishuWorkSourceType,
    val title: String,
    val sourceRef: String,
    val snippet: String,
    val capturedAtMs: Long,
    val truncated: Boolean,
)

data class FeishuWorkSkillDefinition(
    val id: String,
    val name: String,
    val description: String,
    val recommendedMcpCategories: List<String>,
    val outputKind: String,
    val risk: FeishuWorkSkillRisk,
)

data class FeishuWorkReport(
    val title: String,
    val skillId: String,
    val project: String?,
    val sources: List<FeishuWorkSource>,
    val markdown: String,
    val outputPath: String,
    val truncated: Boolean,
)

enum class FeishuWorkDraftType(val wireName: String) {
    TASK("task"),
    BASE_RECORD("base_record"),
    REPLY("reply"),
}

data class FeishuWorkDraft(
    val type: FeishuWorkDraftType,
    val title: String,
    val target: String,
    val markdown: String,
    val payloadJson: String,
    val sources: List<FeishuWorkSource>,
    val requiresApproval: Boolean,
    val approvalNote: String,
)

object FeishuOfficeEnhancementPlanner {
    fun capability(
        enabled: Boolean,
        installed: Boolean,
        accessibilityReady: Boolean,
        notificationReady: Boolean,
        usageReady: Boolean,
    ): FeishuOfficeCapabilityLevel = when {
        !enabled -> FeishuOfficeCapabilityLevel.DISABLED
        !installed -> FeishuOfficeCapabilityLevel.NOT_INSTALLED
        accessibilityReady && notificationReady && usageReady -> FeishuOfficeCapabilityLevel.FULL_READ_SIGNALS
        accessibilityReady -> FeishuOfficeCapabilityLevel.SCREEN_READ
        notificationReady || usageReady -> FeishuOfficeCapabilityLevel.SIGNALS
        else -> FeishuOfficeCapabilityLevel.APP_ONLY
    }

    fun chooseBestCandidate(
        candidates: List<FeishuOfficePackageCandidate>,
        preferredPackage: String = DEFAULT_FEISHU_OFFICE_PACKAGE,
    ): FeishuOfficePackageCandidate? =
        candidates.firstOrNull { it.packageName == preferredPackage }
            ?: candidates.firstOrNull { it.packageName.contains("lark", ignoreCase = true) }
            ?: candidates.firstOrNull()

    fun extractVisibleText(uiTree: String, maxChars: Int = 6_000): String =
        uiTree.lineSequence()
            .mapNotNull(::extractNodeText)
            .distinct()
            .joinToString("\n")
            .take(maxChars)

    fun guessTitle(uiTree: String): String? =
        uiTree.lineSequence()
            .mapNotNull(::extractNodeText)
            .firstOrNull { it.length in 2..80 }

    fun buildContextDigest(
        template: FeishuOfficeAnalysisTemplate,
        bundle: FeishuOfficeContextBundle,
        maxChars: Int,
    ): String = buildContextDigest(
        template = template,
        state = bundle.state,
        notifications = bundle.notifications,
        usageStats = bundle.usageStats,
        screen = bundle.screen,
        workspaceSnippets = bundle.workspaceSnippets,
        screenError = bundle.screenError,
        mcpHints = bundle.mcpHints,
        capturedAtMs = bundle.capturedAtMs,
        maxChars = maxChars,
    )

    fun buildContextDigest(
        template: FeishuOfficeAnalysisTemplate,
        state: FeishuOfficeEnhancementState,
        notifications: List<FeishuOfficeNotificationSummary>,
        usageStats: List<FeishuOfficeUsageSummary>,
        screen: FeishuOfficeScreenSnapshot?,
        workspaceSnippets: List<FeishuOfficeWorkspaceSnippet>,
        maxChars: Int,
    ): String = buildContextDigest(
        template = template,
        state = state,
        notifications = notifications,
        usageStats = usageStats,
        screen = screen,
        workspaceSnippets = workspaceSnippets,
        screenError = null,
        mcpHints = if (state.includeMcpHintsByDefault) defaultMcpHints() else emptyList(),
        capturedAtMs = System.currentTimeMillis(),
        maxChars = maxChars,
    )

    fun buildDashboardSummary(bundle: FeishuOfficeContextBundle): FeishuOfficeDashboardSummary {
        val state = bundle.state
        val missingPermissions = buildList {
            if (!state.enabled) add("enabled")
            if (!state.installed) add("installed")
            if (!state.accessibilityReady) add("accessibility")
            if (!state.notificationReady) add("notification_access")
            if (!state.usageReady) add("usage_access")
        }
        val recentTitle = bundle.screen?.titleGuess
            ?: state.lastKnownTitle
            ?: bundle.notifications.firstOrNull()?.title?.takeIf { it.isNotBlank() }
        val suggestedActions = buildList {
            if (!state.enabled) add("在设置中启用飞书办公增强模式。")
            if (!state.installed) add("确认目标包名，或先安装/登录小米办公 Pro。")
            if (!state.accessibilityReady) add("开启无障碍权限，以读取当前屏幕和文档标题。")
            if (!state.notificationReady) add("开启通知读取权限，以汇总 @我、评论和待办提醒。")
            if (!state.usageReady) add("开启使用情况权限，以增强最近工作信号。")
            if (bundle.notifications.isNotEmpty()) add("优先处理最近 ${bundle.notifications.size} 条办公通知。")
            if (bundle.screen != null) add("可基于当前屏幕生成摘要、风险或待办。")
            if (bundle.workspaceSnippets.isNotEmpty()) add("可基于已导入 workspace 文档生成分析报告。")
            if (state.includeMcpHintsByDefault) add("如已配置飞书 MCP，可调用 mcp_list/tools_list 后用飞书 MCP 补充云文档、日程、任务、会议纪要等来源。")
            if (isEmpty()) add("上下文已就绪，可生成工作摘要或分析报告。")
        }
        return FeishuOfficeDashboardSummary(
            capability = state.capability,
            missingPermissions = missingPermissions,
            notificationCount = bundle.notifications.size,
            recentTitle = recentTitle,
            suggestedActions = suggestedActions,
            updatedAtMs = bundle.capturedAtMs,
        )
    }

    fun workSkillDefinitions(): List<FeishuWorkSkillDefinition> = listOf(
        FeishuWorkSkillDefinition(
            id = "daily_radar",
            name = "今日飞书雷达",
            description = "汇总办公通知、使用信号、当前屏幕和 MCP 补强建议，形成当天优先级。",
            recommendedMcpCategories = listOf("task", "calendar", "minutes", "im", "doc"),
            outputKind = "daily_radar_markdown",
            risk = FeishuWorkSkillRisk.READ_ONLY,
        ),
        FeishuWorkSkillDefinition(
            id = "project_briefing",
            name = "项目 Briefing",
            description = "围绕 Q 代、MiClaw、Lhasa、AI 办公等项目生成评审前 10 分钟 briefing。",
            recommendedMcpCategories = listOf("doc", "wiki", "minutes", "task", "base"),
            outputKind = "project_briefing_markdown",
            risk = FeishuWorkSkillRisk.READ_ONLY,
        ),
        FeishuWorkSkillDefinition(
            id = "document_warroom",
            name = "文档作战室",
            description = "基于当前屏幕、导入文档和 MCP 线索生成白皮书/卖点/竞品分析草稿。",
            recommendedMcpCategories = listOf("doc", "drive", "wiki", "minutes", "im"),
            outputKind = "document_warroom_markdown",
            risk = FeishuWorkSkillRisk.READ_ONLY,
        ),
    )

    fun resolveProject(
        setting: FeishuWorkDashboardSetting,
        raw: String?,
    ): FeishuWorkProject {
        val query = raw?.trim().orEmpty()
        if (query.isBlank()) {
            return setting.projects.firstOrNull() ?: defaultFeishuWorkProjects().first()
        }
        return setting.projects.firstOrNull { project ->
            project.id.equals(query, ignoreCase = true) ||
                project.name.equals(query, ignoreCase = true) ||
                project.keywords.any { keyword -> keyword.equals(query, ignoreCase = true) }
        } ?: FeishuWorkProject(
            id = query.toProjectId(),
            name = query.take(80),
            keywords = listOf(query.take(80)),
            currentGoal = "围绕「${query.take(80)}」沉淀项目上下文。",
        )
    }

    fun mergeProject(
        existing: FeishuWorkProject?,
        id: String?,
        name: String,
        keywords: List<String>,
        currentGoal: String?,
        coreSellingPoints: List<String>,
        risks: List<String>,
        openQuestions: List<String>,
        keyDecisions: List<String>,
        recentChanges: List<String>,
        sourceRefs: List<String>,
        nowMs: Long,
    ): FeishuWorkProject {
        val cleanName = name.trim().take(80).ifBlank { existing?.name ?: "未命名项目" }
        return FeishuWorkProject(
            id = id?.trim()?.takeIf { it.isNotBlank() } ?: existing?.id ?: cleanName.toProjectId(),
            name = cleanName,
            keywords = mergeList(existing?.keywords.orEmpty(), keywords, 24)
                .ifEmpty { listOf(cleanName) },
            currentGoal = currentGoal?.trim()?.take(500)
                ?: existing?.currentGoal.orEmpty(),
            coreSellingPoints = mergeList(existing?.coreSellingPoints.orEmpty(), coreSellingPoints, 24),
            risks = mergeList(existing?.risks.orEmpty(), risks, 24),
            openQuestions = mergeList(existing?.openQuestions.orEmpty(), openQuestions, 24),
            keyDecisions = mergeList(existing?.keyDecisions.orEmpty(), keyDecisions, 24),
            recentChanges = mergeList(existing?.recentChanges.orEmpty(), recentChanges, 24),
            sourceRefs = mergeList(existing?.sourceRefs.orEmpty(), sourceRefs, 32),
            updatedAtMs = nowMs,
        )
    }

    fun buildProjectKnowledgeMarkdown(project: FeishuWorkProject): String = buildString {
        appendLine("# ${project.name} 项目知识包")
        appendLine()
        appendLine("id: ${project.id}")
        appendLine("updated_at_ms: ${project.updatedAtMs}")
        appendLine()
        appendLine("## 关键词")
        project.keywords.forEach { appendLine("- $it") }
        appendLine()
        appendLine("## 当前目标")
        appendLine()
        appendLine(project.currentGoal.ifBlank { "暂无。可通过 officepro_project_update 补充。" })
        appendLine()
        appendListSection("核心卖点", project.coreSellingPoints)
        appendListSection("关键风险", project.risks)
        appendListSection("未确认问题", project.openQuestions)
        appendListSection("关键决策", project.keyDecisions)
        appendListSection("最近变化", project.recentChanges)
        appendListSection("来源引用", project.sourceRefs)
    }

    fun buildProjectContextReport(
        project: FeishuWorkProject,
        bundle: FeishuOfficeContextBundle,
        maxChars: Int,
    ): FeishuWorkReport {
        val sources = buildWorkSources(bundle, bundle.state.workDashboard.maxSourceDocs)
        val digest = buildContextDigest(bundle.state.defaultTemplate, bundle, maxChars.coerceAtMost(12_000))
        val markdown = boundedMarkdown(maxChars) {
            append(buildProjectKnowledgeMarkdown(project))
            appendLine()
            appendLine("## 当前捕获来源")
            appendSources(sources)
            appendLine()
            appendLine("## 上下文摘录")
            appendLine()
            appendLine(digest)
        }
        return FeishuWorkReport(
            title = "${project.name} 项目上下文",
            skillId = "project_context",
            project = project.name,
            sources = sources,
            markdown = markdown.text,
            outputPath = defaultWorkReportPath(bundle.state.workDashboard.defaultOutputDir, "project-context", bundle.capturedAtMs),
            truncated = markdown.truncated,
        )
    }

    fun buildWorkSources(
        bundle: FeishuOfficeContextBundle,
        maxSources: Int,
    ): List<FeishuWorkSource> {
        val boundedMax = maxSources.coerceIn(1, 20)
        return buildList {
            bundle.screen?.let { screen ->
                add(
                    FeishuWorkSource(
                        type = FeishuWorkSourceType.SCREEN,
                        title = screen.titleGuess?.takeIf { it.isNotBlank() } ?: "当前小米办公 Pro 屏幕",
                        sourceRef = bundle.state.targetPackage,
                        snippet = screen.visibleText.take(1_500),
                        capturedAtMs = bundle.capturedAtMs,
                        truncated = screen.visibleText.length > 1_500,
                    )
                )
            }
            bundle.notifications.take(6).forEach { item ->
                add(
                    FeishuWorkSource(
                        type = FeishuWorkSourceType.NOTIFICATION,
                        title = item.title.ifBlank { "小米办公 Pro 通知" }.take(120),
                        sourceRef = item.postedAtMs.toString(),
                        snippet = item.text.take(500),
                        capturedAtMs = item.postedAtMs,
                        truncated = item.text.length > 500,
                    )
                )
            }
            bundle.workspaceSnippets.forEach { snippet ->
                add(
                    FeishuWorkSource(
                        type = FeishuWorkSourceType.WORKSPACE,
                        title = snippet.path.substringAfterLast('/').take(120),
                        sourceRef = snippet.path,
                        snippet = snippet.content.take(1_500),
                        capturedAtMs = bundle.capturedAtMs,
                        truncated = snippet.truncated || snippet.content.length > 1_500,
                    )
                )
            }
            bundle.mcpHints.take(6).forEachIndexed { index, hint ->
                add(
                    FeishuWorkSource(
                        type = FeishuWorkSourceType.MCP_HINT,
                        title = "飞书 MCP 补强建议 ${index + 1}",
                        sourceRef = "mcp_hint:${index + 1}",
                        snippet = hint.take(500),
                        capturedAtMs = bundle.capturedAtMs,
                        truncated = hint.length > 500,
                    )
                )
            }
        }.take(boundedMax)
    }

    fun buildDailyRadarReport(
        bundle: FeishuOfficeContextBundle,
        maxChars: Int,
    ): FeishuWorkReport {
        val summary = buildDashboardSummary(bundle)
        val sources = buildWorkSources(bundle, bundle.state.workDashboard.maxSourceDocs)
        val markdown = boundedMarkdown(maxChars) {
            appendLine("# 今日飞书雷达")
            appendLine()
            appendLine("## 结论摘要")
            appendLine()
            appendLine("- 能力等级：${summary.capability.wireName}")
            appendLine("- 办公通知：${summary.notificationCount} 条")
            appendLine("- 最近标题：${summary.recentTitle ?: "暂无"}")
            appendLine()
            appendLine("## 建议动作")
            summary.suggestedActions.forEach { appendLine("- $it") }
            appendLine()
            appendLine("## 风险与缺口")
            if (summary.missingPermissions.isEmpty()) {
                appendLine("- 权限信号齐备，可继续拉取 MCP/Workspace 来源。")
            } else {
                summary.missingPermissions.forEach { appendLine("- 缺少：$it") }
            }
            appendLine()
            appendSources(sources)
        }
        return FeishuWorkReport(
            title = "今日飞书雷达",
            skillId = "daily_radar",
            project = null,
            sources = sources,
            markdown = markdown.text,
            outputPath = defaultWorkReportPath(bundle.state.workDashboard.defaultOutputDir, "daily-radar", bundle.capturedAtMs),
            truncated = markdown.truncated,
        )
    }

    fun buildProjectBriefingReport(
        project: String,
        bundle: FeishuOfficeContextBundle,
        maxChars: Int,
    ): FeishuWorkReport {
        val safeProject = project.trim().ifBlank {
            bundle.state.workDashboard.defaultProjectKeywords.firstOrNull().orEmpty().ifBlank { "未命名项目" }
        }.take(80)
        val digest = buildContextDigest(bundle.state.defaultTemplate, bundle, maxChars.coerceAtMost(12_000))
        val sources = buildWorkSources(bundle, bundle.state.workDashboard.maxSourceDocs)
        val markdown = boundedMarkdown(maxChars) {
            appendLine("# $safeProject 项目 Briefing")
            appendLine()
            appendLine("## 结论摘要")
            appendLine()
            appendLine("- 这是基于当前 OfficePro 信号、workspace 文档和 MCP 提示生成的评审前 briefing 草稿。")
            appendLine("- 请优先核对项目目标、最新变化、风险和下一步动作。")
            appendLine()
            appendLine("## 当前目标")
            appendLine()
            appendLine("- 围绕「$safeProject」补齐产品市场判断、卖点证据和协作待办。")
            appendLine()
            appendLine("## 关键证据")
            appendSources(sources)
            appendLine()
            appendLine("## 风险与待确认")
            appendLine()
            appendLine("- MCP 只作为补强提示；若需要完整云文档正文，主 Agent 应先确认 MCP 工具在线并按来源读取。")
            appendLine("- 当前屏幕只包含可见内容，不等于完整文档。")
            appendLine()
            appendLine("## 下一步动作")
            appendLine()
            appendLine("- 让 Agent 基于这份 briefing 继续生成评审问题清单或白皮书修改建议。")
            appendLine()
            appendLine("## 原始上下文摘录")
            appendLine()
            appendLine(digest)
        }
        return FeishuWorkReport(
            title = "$safeProject 项目 Briefing",
            skillId = "project_briefing",
            project = safeProject,
            sources = sources,
            markdown = markdown.text,
            outputPath = defaultWorkReportPath(bundle.state.workDashboard.defaultOutputDir, "project-briefing", bundle.capturedAtMs),
            truncated = markdown.truncated,
        )
    }

    fun buildDocumentWarroomReport(
        template: FeishuOfficeAnalysisTemplate,
        bundle: FeishuOfficeContextBundle,
        includeModelCouncil: Boolean,
        maxChars: Int,
        warroomTemplate: FeishuDocumentWarroomTemplate = FeishuDocumentWarroomTemplate.fromAnalysisTemplate(template),
    ): FeishuWorkReport {
        val title = bundle.screen?.titleGuess
            ?: bundle.workspaceSnippets.firstOrNull()?.path?.substringAfterLast('/')
            ?: "${warroomTemplate.zhLabel} 文档作战室"
        val digest = buildContextDigest(template, bundle, maxChars.coerceAtMost(12_000))
        val sources = buildWorkSources(bundle, bundle.state.workDashboard.maxSourceDocs)
        val markdown = boundedMarkdown(maxChars) {
            appendLine("# $title")
            appendLine()
            appendLine("> 作战室模板：${warroomTemplate.zhLabel} / ${warroomTemplate.wireName}")
            appendLine("> 分析焦点：${warroomTemplate.focus}")
            appendLine()
            appendLine("## 必改项")
            appendLine()
            appendLine("- ${warroomTemplate.mustFix}")
            appendLine("- 待 Agent 基于下方上下文把问题定位到具体章节、句子、证据或协作动作。")
            appendLine()
            appendLine("## 可选优化项")
            appendLine()
            appendLine("- ${warroomTemplate.optionalImprove}")
            appendLine("- 可继续要求 Agent 输出改写稿、老板评审预演、竞品反击话术或评论草稿。")
            appendLine()
            appendLine("## 风险项")
            appendLine()
            appendLine("- ${warroomTemplate.risk}")
            appendLine("- 当前屏幕只代表可见区域；完整文档建议通过 MCP 或 workspace 导入补齐。")
            appendLine()
            appendLine("## 证据缺口")
            appendLine()
            appendLine("- ${warroomTemplate.evidenceGap}")
            appendSources(sources)
            appendLine()
            appendLine("## 可复制评论草稿")
            appendLine()
            appendLine("- ${warroomTemplate.commentDraft}")
            appendLine("- 本工具只生成草稿，不自动发送、不自动评论。真正写回飞书必须另走审批。")
            appendLine()
            appendLine("## 可写入飞书文档的修订建议草稿")
            appendLine()
            appendLine("- ${warroomTemplate.revisionDraft}")
            appendLine("- 建议主 Agent 先把建议整理成 diff / 评论草稿，再由用户确认是否复制或写回。")
            appendLine()
            appendLine("## Model Council 交接")
            if (includeModelCouncil) {
                appendLine("- 可基于本报告调用 model_council_start，让产品市场、工程实现、风险审查、反对者和裁判多视角评审。")
                appendLine("- objective: ${warroomTemplate.councilObjective}")
                appendLine("- mode: debate")
                appendLine("- suggested_roles: 产品市场 / 工程实现 / 风险审查 / 反对者 / 裁判")
                appendLine("- boundary: Council 成员只做纯文本评审，不直接读取屏幕、写文件或写回飞书。")
            } else {
                appendLine("- 未启用。可在设置里打开 Model Council 建议，或由主 Agent 单独发起多模型评审。")
            }
            appendLine()
            appendLine("## 原始上下文摘录")
            appendLine()
            appendLine(digest)
        }
        return FeishuWorkReport(
            title = title.take(120),
            skillId = "document_warroom",
            project = null,
            sources = sources,
            markdown = markdown.text,
            outputPath = defaultWorkReportPath(bundle.state.workDashboard.defaultOutputDir, "document-warroom", bundle.capturedAtMs),
            truncated = markdown.truncated,
        )
    }

    fun buildOpenItemsRadarReport(
        project: String?,
        bundle: FeishuOfficeContextBundle,
        maxChars: Int,
    ): FeishuWorkReport {
        val safeProject = project?.trim()?.takeIf { it.isNotBlank() }?.take(80)
        val sources = buildWorkSources(bundle, bundle.state.workDashboard.maxSourceDocs)
        val digest = buildContextDigest(FeishuOfficeAnalysisTemplate.TODO_RADAR, bundle, maxChars.coerceAtMost(12_000))
        val title = buildString {
            append(safeProject ?: "飞书")
            append(" 遗留问题雷达")
        }
        val markdown = boundedMarkdown(maxChars) {
            appendLine("# $title")
            appendLine()
            appendLine("## 结论摘要")
            appendLine()
            appendLine("- 这是把通知、当前屏幕、workspace 文档和 MCP 提示汇总后的遗留问题雷达草稿。")
            appendLine("- 请让主 Agent 基于下方上下文继续提炼 owner、deadline、阻塞关系和优先级。")
            appendLine()
            appendLine("## 遗留问题候选")
            appendLine()
            appendLine("- 待 Agent 从原始上下文中抽取：TBD、待确认、风险、遗留问题、待办、owner、deadline。")
            appendLine()
            appendLine("## 建议任务草稿")
            appendLine()
            appendLine("- 可调用 officepro_create_task_draft 生成飞书任务草稿；真正写入飞书任务必须二次审批。")
            appendLine()
            appendLine("## Base 记录草稿")
            appendLine()
            appendLine("- 可调用 officepro_create_base_record_draft 生成 Base 记录草稿；真正写入 Base 必须二次审批。")
            appendLine()
            appendLine("## 催办 / 回复草稿")
            appendLine()
            appendLine("- 可调用 officepro_reply_draft 生成评论或群聊回复草稿；不会自动发送。")
            appendLine()
            appendLine("## 来源")
            appendSources(sources)
            appendLine()
            appendLine("## 原始上下文摘录")
            appendLine()
            appendLine(digest)
        }
        return FeishuWorkReport(
            title = title,
            skillId = "open_items_radar",
            project = safeProject,
            sources = sources,
            markdown = markdown.text,
            outputPath = defaultWorkReportPath(bundle.state.workDashboard.defaultOutputDir, "open-items-radar", bundle.capturedAtMs),
            truncated = markdown.truncated,
        )
    }

    fun buildMeetingClosureReport(
        meetingKeyword: String?,
        dateLabel: String?,
        bundle: FeishuOfficeContextBundle,
        maxChars: Int,
    ): FeishuWorkReport {
        val safeMeeting = meetingKeyword?.trim()?.takeIf { it.isNotBlank() }?.take(100) ?: "会议"
        val safeDate = dateLabel?.trim()?.takeIf { it.isNotBlank() }?.take(40) ?: "当前时间范围"
        val sources = buildWorkSources(bundle, bundle.state.workDashboard.maxSourceDocs)
        val digest = buildContextDigest(FeishuOfficeAnalysisTemplate.TODO_RADAR, bundle, maxChars.coerceAtMost(12_000))
        val markdown = boundedMarkdown(maxChars) {
            appendLine("# $safeMeeting 会议闭环")
            appendLine()
            appendLine("> 时间范围：$safeDate")
            appendLine()
            appendLine("## 会议结论")
            appendLine()
            appendLine("- 待 Agent 根据会议纪要、妙记、任务和文档来源提炼结论。")
            appendLine()
            appendLine("## 待办与负责人")
            appendLine()
            appendLine("- 待抽取 owner、deadline、状态、阻塞项和下一步。")
            appendLine()
            appendLine("## 决策与风险")
            appendLine()
            appendLine("- 待区分已决策、待确认、风险、依赖和需要升级的问题。")
            appendLine()
            appendLine("## 催办 / 同步话术")
            appendLine()
            appendLine("- 可调用 officepro_reply_draft 生成克制的催办或同步草稿；不会自动发送。")
            appendLine()
            appendLine("## 飞书 MCP 补强建议")
            appendLine()
            appendLine("- 优先用 lark-calendar / lark-minutes / lark-task / lark-im 补齐会议、纪要、待办和群聊上下文。")
            appendLine("- 写回任务或 Base 记录必须二次审批。")
            appendLine()
            appendLine("## 来源")
            appendSources(sources)
            appendLine()
            appendLine("## 原始上下文摘录")
            appendLine()
            appendLine(digest)
        }
        return FeishuWorkReport(
            title = "$safeMeeting 会议闭环",
            skillId = "meeting_closure",
            project = null,
            sources = sources,
            markdown = markdown.text,
            outputPath = defaultWorkReportPath(bundle.state.workDashboard.defaultOutputDir, "meeting-closure", bundle.capturedAtMs),
            truncated = markdown.truncated,
        )
    }

    fun buildTaskDraft(
        title: String,
        owner: String?,
        due: String?,
        project: String?,
        sourceRef: String?,
        details: String?,
        bundle: FeishuOfficeContextBundle,
        maxChars: Int,
    ): FeishuWorkDraft {
        val sources = buildWorkSources(bundle, bundle.state.workDashboard.maxSourceDocs)
        val safeTitle = title.trim().ifBlank { "待办任务草稿" }.take(120)
        val safeOwner = owner.orEmpty().trim().take(80)
        val safeDue = due.orEmpty().trim().take(60)
        val safeProject = project.orEmpty().trim().take(80)
        val safeSource = sourceRef.orEmpty().trim().take(160)
        val safeDetails = details.orEmpty().trim().take(4_000)
        val markdown = boundedMarkdown(maxChars) {
            appendLine("# 飞书任务草稿：$safeTitle")
            appendLine()
            appendLine("- 项目：${safeProject.ifBlank { "未指定" }}")
            appendLine("- 负责人：${safeOwner.ifBlank { "待确认" }}")
            appendLine("- 截止时间：${safeDue.ifBlank { "待确认" }}")
            appendLine("- 来源：${safeSource.ifBlank { "当前 OfficePro 上下文" }}")
            appendLine()
            appendLine("## 任务描述")
            appendLine()
            appendLine(safeDetails.ifBlank { "待 Agent 基于上下文补齐任务描述、验收标准和依赖。" })
            appendLine()
            appendLine("## 验收标准")
            appendLine()
            appendLine("- 目标、owner、deadline 和交付物均已确认。")
            appendLine("- 若写入飞书任务，必须由用户再次审批。")
            appendLine()
            appendLine("## 来源")
            appendSources(sources)
        }
        return FeishuWorkDraft(
            type = FeishuWorkDraftType.TASK,
            title = safeTitle,
            target = "feishu_task",
            markdown = markdown.text,
            payloadJson = jsonObjectString(
                "type" to "task",
                "title" to safeTitle,
                "owner" to safeOwner,
                "due" to safeDue,
                "project" to safeProject,
                "source_ref" to safeSource,
                "details" to safeDetails,
            ),
            sources = sources,
            requiresApproval = true,
            approvalNote = "这是飞书任务写入前草稿。真正创建任务必须由用户再次审批。",
        )
    }

    fun buildBaseRecordDraft(
        project: String?,
        baseName: String?,
        tableName: String?,
        recordType: String?,
        fieldsJson: String?,
        bundle: FeishuOfficeContextBundle,
        maxChars: Int,
    ): FeishuWorkDraft {
        val sources = buildWorkSources(bundle, bundle.state.workDashboard.maxSourceDocs)
        val safeProject = project.orEmpty().trim().take(80)
        val safeBase = baseName.orEmpty().trim().take(120)
        val safeTable = tableName.orEmpty().trim().take(120)
        val safeType = recordType.orEmpty().trim().ifBlank { "open_item" }.take(80)
        val safeFields = fieldsJson.orEmpty().trim().take(8_000)
        val title = "${safeProject.ifBlank { "飞书" }} Base 记录草稿"
        val markdown = boundedMarkdown(maxChars) {
            appendLine("# $title")
            appendLine()
            appendLine("- Base：${safeBase.ifBlank { "待确认" }}")
            appendLine("- 表：${safeTable.ifBlank { "待确认" }}")
            appendLine("- 类型：$safeType")
            appendLine()
            appendLine("## 字段草稿")
            appendLine()
            appendLine("```json")
            appendLine(safeFields.ifBlank { """{"状态":"待确认","来源":"OfficePro 上下文","下一步":"请补齐字段"}""" })
            appendLine("```")
            appendLine()
            appendLine("## 写回边界")
            appendLine()
            appendLine("- 本工具只生成 Base 记录草稿，不调用飞书 MCP 写入。")
            appendLine("- 真正写入 Base 必须二次审批，并由可用的飞书 MCP/Skill 执行。")
            appendLine()
            appendLine("## 来源")
            appendSources(sources)
        }
        return FeishuWorkDraft(
            type = FeishuWorkDraftType.BASE_RECORD,
            title = title,
            target = "feishu_base",
            markdown = markdown.text,
            payloadJson = jsonObjectString(
                "type" to "base_record",
                "project" to safeProject,
                "base_name" to safeBase,
                "table_name" to safeTable,
                "record_type" to safeType,
                "fields_json" to safeFields,
            ),
            sources = sources,
            requiresApproval = true,
            approvalNote = "这是飞书 Base 写入前草稿。真正创建记录必须由用户再次审批。",
        )
    }

    fun buildReplyDraft(
        audience: String?,
        tone: String?,
        objective: String,
        sourceRef: String?,
        bundle: FeishuOfficeContextBundle,
        maxChars: Int,
    ): FeishuWorkDraft {
        val sources = buildWorkSources(bundle, bundle.state.workDashboard.maxSourceDocs)
        val safeAudience = audience.orEmpty().trim().take(80)
        val safeTone = tone.orEmpty().trim().ifBlank { "克制、明确、可执行" }.take(80)
        val safeObjective = objective.trim().ifBlank { "回复当前飞书上下文" }.take(1_000)
        val safeSource = sourceRef.orEmpty().trim().take(160)
        val title = "飞书回复草稿"
        val markdown = boundedMarkdown(maxChars) {
            appendLine("# $title")
            appendLine()
            appendLine("- 对象：${safeAudience.ifBlank { "待确认" }}")
            appendLine("- 语气：$safeTone")
            appendLine("- 来源：${safeSource.ifBlank { "当前 OfficePro 上下文" }}")
            appendLine()
            appendLine("## 可复制回复草稿")
            appendLine()
            appendLine("> 这里先生成回复目标和结构，主 Agent 应基于上下文补齐最终措辞；本工具不自动发送。")
            appendLine()
            appendLine("- 回复目标：$safeObjective")
            appendLine("- 建议结构：先确认事实，再说明判断，再给出下一步请求。")
            appendLine()
            appendLine("## 风险提醒")
            appendLine()
            appendLine("- 发送前请确认对象、权限、语气和是否包含敏感信息。")
            appendLine("- 真正评论、群聊回复或发送必须二次审批。")
            appendLine()
            appendLine("## 来源")
            appendSources(sources)
        }
        return FeishuWorkDraft(
            type = FeishuWorkDraftType.REPLY,
            title = title,
            target = "feishu_reply",
            markdown = markdown.text,
            payloadJson = jsonObjectString(
                "type" to "reply",
                "audience" to safeAudience,
                "tone" to safeTone,
                "objective" to safeObjective,
                "source_ref" to safeSource,
            ),
            sources = sources,
            requiresApproval = true,
            approvalNote = "这是评论/群聊/回复写入前草稿。真正发送必须由用户再次审批。",
        )
    }

    fun buildReportMarkdown(
        title: String,
        template: FeishuOfficeAnalysisTemplate,
        digest: String,
        capturedAtMs: Long,
        maxChars: Int,
    ): FeishuOfficeReportDraft {
        val safeTitle = title.ifBlank { "${template.zhLabel} - 飞书办公增强报告" }.take(120)
        val raw = buildString {
            appendLine("# $safeTitle")
            appendLine()
            appendLine("> 模板：${template.zhLabel} / ${template.wireName}")
            appendLine("> 生成时间：$capturedAtMs")
            appendLine()
            appendLine("## 结论摘要")
            appendLine()
            appendLine("- 待 Agent 基于下方上下文补充结论。")
            appendLine()
            appendLine("## 关键证据")
            appendLine()
            appendLine("- 待从通知、当前屏幕和 workspace 文档中提炼。")
            appendLine()
            appendLine("## 风险与待确认")
            appendLine()
            appendLine("- 待确认信息完整性、证据强度和负责人。")
            appendLine()
            appendLine("## 下一步动作")
            appendLine()
            appendLine("- 基于该草稿继续追问 Agent，或把结果复制回小米办公 Pro。")
            appendLine()
            appendLine("## 原始上下文摘录")
            appendLine()
            appendLine(digest)
        }
        val boundedMax = maxChars.coerceIn(8_000, 50_000)
        val truncated = raw.length > boundedMax
        val markdown = if (truncated) {
            raw.take(boundedMax) + "\n... [report truncated to $boundedMax chars]"
        } else {
            raw
        }
        return FeishuOfficeReportDraft(
            markdown = markdown,
            totalChars = raw.length,
            truncated = truncated,
        )
    }

    private fun buildContextDigest(
        template: FeishuOfficeAnalysisTemplate,
        state: FeishuOfficeEnhancementState,
        notifications: List<FeishuOfficeNotificationSummary>,
        usageStats: List<FeishuOfficeUsageSummary>,
        screen: FeishuOfficeScreenSnapshot?,
        workspaceSnippets: List<FeishuOfficeWorkspaceSnippet>,
        screenError: String?,
        mcpHints: List<String>,
        capturedAtMs: Long,
        maxChars: Int,
    ): String {
        val boundedMax = maxChars.coerceIn(4_000, 30_000)
        val raw = buildString {
            appendLine("# 飞书办公增强上下文")
            appendLine()
            appendLine("template: ${template.wireName} / ${template.zhLabel}")
            appendLine("analysis_prompt: ${template.prompt}")
            appendLine("captured_at_ms: $capturedAtMs")
            appendLine("target_package: ${state.targetPackage}")
            appendLine("capability: ${state.capability.wireName}")
            appendLine("installed: ${state.installed}")
            appendLine("accessibility_ready: ${state.accessibilityReady}")
            appendLine("notification_ready: ${state.notificationReady}")
            appendLine("usage_ready: ${state.usageReady}")
            appendLine("mcp_hints_enabled: ${state.includeMcpHintsByDefault}")
            state.lastKnownTitle?.let { appendLine("last_known_title: ${it.take(120)}") }
            state.lastError?.let { appendLine("last_error: ${it.take(180)}") }
            appendLine()
            appendLine("## 当前屏幕")
            if (screen == null) {
                appendLine("未读取当前屏幕。")
                screenError?.let { appendLine("screen_error: ${it.take(240)}") }
            } else {
                appendLine("title_guess: ${screen.titleGuess.orEmpty().take(120)}")
                appendLine(screen.visibleText.take(6_000))
            }
            appendLine()
            appendLine("## 小米办公 Pro 通知摘要")
            if (notifications.isEmpty()) {
                appendLine("暂无可用通知，或通知权限未开启。")
            } else {
                notifications.take(12).forEach { item ->
                    appendLine("- ${item.postedAtMs}: ${item.title.take(120)} | ${item.text.take(180)}")
                }
            }
            appendLine()
            appendLine("## 最近使用信号")
            if (usageStats.isEmpty()) {
                appendLine("暂无可用使用情况，或使用情况权限未开启。")
            } else {
                usageStats.take(8).forEach { item ->
                    appendLine("- ${item.label}: last=${item.lastTimeUsedMs}, foreground_ms=${item.totalTimeForegroundMs}")
                }
            }
            appendLine()
            appendLine("## 飞书 MCP 补强建议")
            if (mcpHints.isEmpty()) {
                appendLine("未启用 MCP 提示。")
            } else {
                mcpHints.forEach { hint -> appendLine("- $hint") }
            }
            appendLine()
            appendLine("## Workspace 文档片段")
            if (workspaceSnippets.isEmpty()) {
                appendLine("未提供 workspace 文档路径。可先分享/导出 DOCX/Markdown 到 /workspace，再传入 workspace_paths。")
            } else {
                workspaceSnippets.forEach { snippet ->
                    appendLine("### ${snippet.path}")
                    appendLine("total_chars=${snippet.totalChars}, truncated=${snippet.truncated}")
                    appendLine(snippet.content)
                    appendLine()
                }
            }
        }
        return if (raw.length <= boundedMax) raw else raw.take(boundedMax) + "\n... [context digest truncated to $boundedMax chars]"
    }

    fun defaultMcpHints(): List<String> = listOf(
        "先调用 mcp_list 或 tools_list 确认手机端飞书 MCP 工具是否在线。",
        "若有飞书云文档工具，优先搜索/读取原始云文档，补足屏幕可见内容的缺口。",
        "若有日程、任务、妙记或 IM 工具，可补充会议背景、待办、评论和 @我 线索。",
        "MCP 写回、评论、发送类动作必须单独审批；V2a 只生成分析与草稿。",
    )

    private data class BoundedMarkdown(
        val text: String,
        val truncated: Boolean,
    )

    private fun boundedMarkdown(
        maxChars: Int,
        block: StringBuilder.() -> Unit,
    ): BoundedMarkdown {
        val boundedMax = maxChars.coerceIn(8_000, 50_000)
        val raw = buildString(block)
        val truncated = raw.length > boundedMax
        return BoundedMarkdown(
            text = if (truncated) {
                raw.take(boundedMax) + "\n... [work report truncated to $boundedMax chars]"
            } else {
                raw
            },
            truncated = truncated,
        )
    }

    private fun StringBuilder.appendSources(sources: List<FeishuWorkSource>) {
        if (sources.isEmpty()) {
            appendLine("- 暂无可用来源；可导入 workspace 文档、打开小米办公 Pro 文档，或配置飞书 MCP。")
            return
        }
        sources.forEach { source ->
            appendLine("- [${source.type.wireName}] ${source.title} (${source.sourceRef})")
            source.snippet.takeIf { it.isNotBlank() }?.let { snippet ->
                appendLine("  - ${snippet.replace('\n', ' ').take(240)}")
            }
        }
    }

    private fun defaultWorkReportPath(outputDir: String, slug: String, nowMs: Long): String {
        val date = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
            .format(java.util.Date(nowMs))
        val parts = outputDir.trim()
            .removePrefix("/workspace/")
            .removePrefix("workspace/")
            .trim('/')
            .replace('\\', '/')
            .split("/")
            .filter { it.isNotBlank() && it != "." && it != ".." }
            .take(4)
        val safeDir = parts.joinToString("/").ifBlank { "officepro" }
        return "$safeDir/officepro-$slug-$date.md"
    }

    private fun jsonObjectString(vararg fields: Pair<String, String>): String =
        fields.joinToString(prefix = "{", postfix = "}") { (key, value) ->
            "${key.jsonString()}:${value.jsonString()}"
        }

    private fun String.jsonString(): String =
        buildString {
            append('"')
            this@jsonString.forEach { char ->
                when (char) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> {
                        if (char.code < 0x20) {
                            append("\\u%04x".format(char.code))
                        } else {
                            append(char)
                        }
                    }
                }
            }
            append('"')
        }

    private fun mergeList(
        existing: List<String>,
        incoming: List<String>,
        maxSize: Int,
    ): List<String> = (incoming + existing)
        .map { it.trim().take(500) }
        .filter { it.isNotBlank() }
        .distinct()
        .take(maxSize)

    private fun String.toProjectId(): String {
        val ascii = lowercase()
            .map { char ->
                when {
                    char in 'a'..'z' || char in '0'..'9' -> char
                    char.isWhitespace() || char in listOf('-', '_', '.', '/', '，', ',', '；', ';') -> '-'
                    else -> '-'
                }
            }
            .joinToString("")
            .trim('-')
            .replace(Regex("-+"), "-")
        return ascii.ifBlank { "custom-project" }.take(64)
    }

    private fun StringBuilder.appendListSection(title: String, values: List<String>) {
        appendLine()
        appendLine("## $title")
        if (values.isEmpty()) {
            appendLine()
            appendLine("- 暂无。")
        } else {
            values.forEach { appendLine("- $it") }
        }
    }

    private fun extractNodeText(line: String): String? {
        val withoutBounds = line.substringBefore(" bounds=")
        val parts = withoutBounds.split(" | ")
            .map { it.trim().trimStart('-', ' ') }
            .filter { it.isNotBlank() }
        return parts.asReversed()
            .firstOrNull { part ->
                !part.startsWith("android.", ignoreCase = true) &&
                    !part.contains("/") &&
                    part.any { it.isLetterOrDigit() || it in '\u4e00'..'\u9fff' }
            }
            ?.take(240)
    }
}
