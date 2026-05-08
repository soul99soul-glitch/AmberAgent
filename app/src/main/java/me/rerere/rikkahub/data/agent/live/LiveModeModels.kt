package me.rerere.rikkahub.data.agent.live

import kotlinx.serialization.Serializable

@Serializable
data class LiveModeSetting(
    val enabled: Boolean = false,
    val autoRefresh: Boolean = true,
    val refreshIntervalMs: Long = 1_500L,
    val stableDelayMs: Long = 1_500L,
    val minAnalysisIntervalMs: Long = 10_000L,
    val maxNodes: Int = 180,
    val voiceInputEnabled: Boolean = true,
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
)
