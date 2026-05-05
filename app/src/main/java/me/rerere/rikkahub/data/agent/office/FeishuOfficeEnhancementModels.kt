package me.rerere.rikkahub.data.agent.office

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

const val DEFAULT_FEISHU_OFFICE_PACKAGE = "com.ss.android.lark.saxmsa667"

@Serializable
data class FeishuOfficeEnhancementSetting(
    val enabled: Boolean = false,
    val targetPackage: String = DEFAULT_FEISHU_OFFICE_PACKAGE,
    val defaultTemplate: FeishuOfficeAnalysisTemplate = FeishuOfficeAnalysisTemplate.WHITEPAPER_SCORE,
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
        state: FeishuOfficeEnhancementState,
        notifications: List<FeishuOfficeNotificationSummary>,
        usageStats: List<FeishuOfficeUsageSummary>,
        screen: FeishuOfficeScreenSnapshot?,
        workspaceSnippets: List<FeishuOfficeWorkspaceSnippet>,
        maxChars: Int,
    ): String {
        val boundedMax = maxChars.coerceIn(4_000, 30_000)
        val raw = buildString {
            appendLine("# 飞书办公增强上下文")
            appendLine()
            appendLine("template: ${template.wireName} / ${template.zhLabel}")
            appendLine("analysis_prompt: ${template.prompt}")
            appendLine("target_package: ${state.targetPackage}")
            appendLine("capability: ${state.capability.wireName}")
            appendLine("installed: ${state.installed}")
            appendLine("accessibility_ready: ${state.accessibilityReady}")
            appendLine("notification_ready: ${state.notificationReady}")
            appendLine("usage_ready: ${state.usageReady}")
            state.lastKnownTitle?.let { appendLine("last_known_title: ${it.take(120)}") }
            state.lastError?.let { appendLine("last_error: ${it.take(180)}") }
            appendLine()
            appendLine("## 当前屏幕")
            if (screen == null) {
                appendLine("未读取当前屏幕。")
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
