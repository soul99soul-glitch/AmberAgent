package app.amber.feature.board.hotlist.deepread

data class DeepReadProgressSnapshot(
    val percent: Int,
    val label: String,
) {
    val fraction: Float = (percent / 100f).coerceIn(0f, 1f)
}

fun DeepReadOutput?.deepReadProgressSnapshot(running: Boolean): DeepReadProgressSnapshot {
    val output = this?.withInferredSectionStates()
    if (output?.isComplete() == true || output?.generationPhase == DeepReadGenerationPhase.COMPLETE) {
        return DeepReadProgressSnapshot(100, "已完成")
    }
    if (output == null) {
        return if (running) {
            DeepReadProgressSnapshot(6, "正在准备")
        } else {
            DeepReadProgressSnapshot(0, "未开始")
        }
    }
    if (output.verificationState.status == DeepReadSectionStatus.RUNNING ||
        output.generationPhase == DeepReadGenerationPhase.VERIFYING
    ) {
        return DeepReadProgressSnapshot(96, "正在补漏")
    }
    if (output.sectionsReady()) {
        return DeepReadProgressSnapshot(94, "正在收尾")
    }
    return when (output.generationPhase) {
        DeepReadGenerationPhase.COLLECTING -> DeepReadProgressSnapshot(10, "正在收集资料")
        DeepReadGenerationPhase.PLANNING -> DeepReadProgressSnapshot(24, "正在规划结构")
        DeepReadGenerationPhase.WRITING -> writingProgress(output)
        DeepReadGenerationPhase.IDLE -> when {
            !running -> DeepReadProgressSnapshot(0, "未开始")
            output.hasVisibleProgress() -> writingProgress(output)
            else -> DeepReadProgressSnapshot(6, "正在准备")
        }
        DeepReadGenerationPhase.VERIFYING -> DeepReadProgressSnapshot(96, "正在补漏")
        DeepReadGenerationPhase.COMPLETE -> DeepReadProgressSnapshot(100, "已完成")
    }
}

internal fun shouldNotifyDeepReadProgress(
    previous: DeepReadProgressSnapshot,
    next: DeepReadProgressSnapshot,
): Boolean = previous != next

internal fun DeepReadOutput?.shouldNotifyRunningDeepReadProgress(): Boolean {
    val output = this?.withInferredSectionStates() ?: return false
    if (output.isComplete() || output.generationPhase == DeepReadGenerationPhase.COMPLETE) return false
    if (output.sectionsReady()) return true
    if (output.verificationState.status == DeepReadSectionStatus.RUNNING) return true
    if (output.generationPhase.isActiveProgressPhase()) return true
    return DeepReadGenerationStage.entries.any { output.statusOf(it) == DeepReadSectionStatus.RUNNING }
}

private fun DeepReadGenerationPhase.isActiveProgressPhase(): Boolean =
    this == DeepReadGenerationPhase.COLLECTING ||
        this == DeepReadGenerationPhase.PLANNING ||
        this == DeepReadGenerationPhase.WRITING ||
        this == DeepReadGenerationPhase.VERIFYING

private fun DeepReadOutput.hasVisibleProgress(): Boolean =
    DeepReadGenerationStage.entries.any { stage ->
        statusOf(stage) == DeepReadSectionStatus.READY ||
            statusOf(stage) == DeepReadSectionStatus.RUNNING
    }

private fun writingProgress(output: DeepReadOutput): DeepReadProgressSnapshot {
    var percent = WRITING_BASE_PERCENT
    DeepReadGenerationStage.entries.forEach { stage ->
        val weight = SECTION_WEIGHTS.getValue(stage)
        percent += when (output.statusOf(stage)) {
            DeepReadSectionStatus.READY -> weight
            DeepReadSectionStatus.RUNNING -> (weight * RUNNING_SECTION_WEIGHT).toInt()
            DeepReadSectionStatus.PENDING,
            DeepReadSectionStatus.FAILED -> 0
        }
    }
    val activeStage = DeepReadGenerationStage.entries.firstOrNull { output.statusOf(it) == DeepReadSectionStatus.RUNNING }
        ?: DeepReadGenerationStage.entries.firstOrNull { output.statusOf(it) != DeepReadSectionStatus.READY }
    val label = activeStage?.let { "分段写作：${it.progressLabel()}" } ?: "正在分段写作"
    return DeepReadProgressSnapshot(percent.coerceIn(WRITING_BASE_PERCENT, WRITING_MAX_PERCENT), label)
}

private fun DeepReadGenerationStage.progressLabel(): String = when (this) {
    DeepReadGenerationStage.OVERVIEW -> "概览"
    DeepReadGenerationStage.NARRATIVE -> "叙事"
    DeepReadGenerationStage.ANALYSIS -> "分析"
    DeepReadGenerationStage.EXTENDED_READING -> "扩展阅读"
}

private const val WRITING_BASE_PERCENT = 28
private const val WRITING_MAX_PERCENT = 93
private const val RUNNING_SECTION_WEIGHT = 0.45f

private val SECTION_WEIGHTS = mapOf(
    DeepReadGenerationStage.OVERVIEW to 16,
    DeepReadGenerationStage.NARRATIVE to 16,
    DeepReadGenerationStage.ANALYSIS to 20,
    DeepReadGenerationStage.EXTENDED_READING to 14,
)
