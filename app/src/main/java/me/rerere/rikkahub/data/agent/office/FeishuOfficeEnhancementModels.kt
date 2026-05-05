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
    val includeCurrentScreenByDefault: Boolean = true,
    val includeMcpHintsByDefault: Boolean = true,
    val defaultOutputDir: String = "officepro",
    val maxWorkspaceDocs: Int = 6,
    val maxReportChars: Int = 20_000,
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
