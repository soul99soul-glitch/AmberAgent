package app.amber.feature.live

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** 填入"覆盖现有输入"二次确认的有效窗口（域仲裁与 UI 倒计时下划线共用，两侧保持镜像）。 */
const val FILL_CONFIRM_WINDOW_MS: Long = 5_000L

@Serializable
enum class LiveAnalysisMode {
    /** 保守：只读无障碍 UI 树文字 */
    @SerialName("conservative")
    CONSERVATIVE,

    /** 激进：截屏喂视觉模型，UI 树作辅助 */
    @SerialName("aggressive")
    AGGRESSIVE,
}

@Serializable
data class LiveModeSetting(
    val enabled: Boolean = false,
    val refreshIntervalMs: Long = 1_500L,
    val stableDelayMs: Long = 1_500L,
    val minAnalysisIntervalMs: Long = 10_000L,
    val maxNodes: Int = 180,
    val analysisMode: LiveAnalysisMode = LiveAnalysisMode.CONSERVATIVE,
    /** 伴随模型 Uuid 字符串；null = 跟随当前聊天模型 */
    val companionModelId: String? = null,
    /** 伴随期间显示悬浮气泡（无障碍 overlay，零额外权限） */
    val bubbleEnabled: Boolean = true,
    /** 用户自定义场景映射：包名 → 场景 wire 名（chat/reading/other）；覆盖内置表。 */
    val sceneOverrides: Map<String, String> = emptyMap(),
    /** 有限自动建议（蓝图 §7.4 P2）：逐 App 开启，默认空=默认关；仅这些包名允许自动分析。 */
    val autoSuggestPackages: Set<String> = emptySet(),
)

data class LiveScreenSnapshot(
    val packageName: String,
    val appLabel: String,
    val title: String,
    val uiTree: String,
    val visibleText: String,
    val contentText: String = visibleText,
    val windowDebugLabel: String = "",
    val nodeCount: Int,
    /** 候选窗口的 AccessibilityWindowInfo.id；-1 = fallback 路径（无窗口对象）。 */
    val windowId: Int = -1,
    val capturedAtMillis: Long = System.currentTimeMillis(),
) {
    val stableHash: String = LiveUiTreeProcessor.stableHash(
        packageName = packageName,
        title = title,
        uiTree = uiTree,
        contentText = contentText,
    )
}

data class LiveWindowCandidate(
    val type: Int,
    val packageName: String,
    val appLabel: String,
    val title: String,
    val area: Int,
    val visibleTextLength: Int,
    val visibleTextCount: Int,
    val nodeCount: Int,
    val layer: Int = 0,
    val active: Boolean = false,
    val focused: Boolean = false,
    val ownApp: Boolean = false,
    val splitDivider: Boolean = false,
    val systemLike: Boolean = false,
) {
    fun isEligible(): Boolean =
        !ownApp &&
            !splitDivider &&
            !systemLike &&
            packageName.isNotBlank() &&
            area >= MIN_WINDOW_AREA &&
            nodeCount > 0 &&
            (visibleTextCount >= MIN_VISIBLE_TEXT_COUNT || visibleTextLength >= MIN_VISIBLE_TEXT_LENGTH)

    fun selectionScore(): Int {
        if (!isEligible()) return Int.MIN_VALUE
        return (if (active) 1_500 else 0) +
            (if (focused) 800 else 0) +
            (area / 8_000).coerceAtMost(700) +
            (visibleTextLength / 20).coerceAtMost(500) +
            (visibleTextCount * 28).coerceAtMost(600) +
            (nodeCount * 2).coerceAtMost(300) +
            (layer * 4)
    }

    fun debugLabel(): String =
        listOf(
            appLabel.ifBlank { packageName },
            title,
            "文本$visibleTextCount",
            "面积$area",
        ).filter { it.isNotBlank() }.joinToString(" · ")

    companion object {
        private const val MIN_WINDOW_AREA = 20_000
        private const val MIN_VISIBLE_TEXT_COUNT = 2
        private const val MIN_VISIBLE_TEXT_LENGTH = 12
    }
}

@Serializable
data class LiveModeCard(
    val watching: String = "",
    val keyPoints: List<String> = emptyList(),
    val suggestions: List<String> = emptyList(),
    val followUps: List<String> = emptyList(),
    val rawText: String = "",
    val generatedAtMillis: Long = System.currentTimeMillis(),
)

data class LiveModeUiState(
    val active: Boolean = false,
    val paused: Boolean = false,
    val analyzing: Boolean = false,
    val needsAccessibility: Boolean = false,
    val noModelConfigured: Boolean = false,
    val currentPackage: String = "",
    val currentAppLabel: String = "",
    val currentTitle: String = "",
    val currentFocus: String = "",
    val requestedAction: String = "",
    val completedAction: String = "",
    val statusText: String = "点击开始伴随",
    val error: String? = null,
    val card: LiveModeCard? = null,
    val lastUpdatedAtMillis: Long = 0L,
    val nextAnalysisAfterMillis: Long = 0L,
    val lastSnapshotHash: String? = null,
    /** 当前卡片生成时对应的屏幕签名；与 lastSnapshotHash 不一致即卡片已脱离现场。 */
    val cardSignature: String? = null,
    /** 分析进行中的流式累积文本（即时预览用；分析结束即清）。 */
    val streamingText: String? = null,
    /** 当前卡片场景是否允许填入（CHAT 白名单+未熔断；Manager 在快照更新时计算，UI 只读）。 */
    val fillAllowed: Boolean = false,
    /** 当前卡片是否来自自动建议（P2 指标口径：viewed 只在自动结果上记）。 */
    val lastResultAuto: Boolean = false,
) {
    /** 卡片生成后屏幕又发生了变化（蓝图 v3 D2：仅失效呈现，不产生自动触发）。 */
    val cardStale: Boolean
        get() = card != null && cardSignature != null &&
            lastSnapshotHash != null && lastSnapshotHash != cardSignature
}
