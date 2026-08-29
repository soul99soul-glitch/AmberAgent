package app.amber.feature.board.hotlist.deepread

import java.util.Locale

data class DeepReadProgressSnapshot(
    val percent: Int,
    val label: String,
) {
    val fraction: Float = (percent / 100f).coerceIn(0f, 1f)
}

fun DeepReadOutput?.deepReadProgressSnapshot(
    running: Boolean,
    locale: Locale = Locale.CHINESE,
): DeepReadProgressSnapshot {
    val output = this?.withInferredSectionStates()
    if (output?.isComplete() == true || output?.generationPhase == DeepReadGenerationPhase.COMPLETE) {
        return DeepReadProgressSnapshot(100, locale.progressLabel("complete"))
    }
    if (output == null) {
        return if (running) {
            DeepReadProgressSnapshot(6, locale.progressLabel("preparing"))
        } else {
            DeepReadProgressSnapshot(0, locale.progressLabel("not_started"))
        }
    }
    if (output.verificationState.status == DeepReadSectionStatus.RUNNING ||
        output.generationPhase == DeepReadGenerationPhase.VERIFYING
    ) {
        return DeepReadProgressSnapshot(96, locale.progressLabel("verifying"))
    }
    if (output.sectionsReady()) {
        return DeepReadProgressSnapshot(94, locale.progressLabel("finishing"))
    }
    return when (output.generationPhase) {
        DeepReadGenerationPhase.COLLECTING -> DeepReadProgressSnapshot(10, locale.progressLabel("collecting"))
        DeepReadGenerationPhase.PLANNING -> DeepReadProgressSnapshot(24, locale.progressLabel("planning"))
        DeepReadGenerationPhase.WRITING -> writingProgress(output, locale)
        DeepReadGenerationPhase.IDLE -> when {
            !running -> DeepReadProgressSnapshot(0, locale.progressLabel("not_started"))
            output.hasVisibleProgress() -> writingProgress(output, locale)
            else -> DeepReadProgressSnapshot(6, locale.progressLabel("preparing"))
        }
        DeepReadGenerationPhase.VERIFYING -> DeepReadProgressSnapshot(96, locale.progressLabel("verifying"))
        DeepReadGenerationPhase.COMPLETE -> DeepReadProgressSnapshot(100, locale.progressLabel("complete"))
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

private fun writingProgress(output: DeepReadOutput, locale: Locale): DeepReadProgressSnapshot {
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
    val label = activeStage?.let {
        if (locale.isChineseLocale()) "分段写作：${it.progressLabel(locale)}"
        else "Writing section: ${it.progressLabel(locale)}"
    } ?: locale.progressLabel("writing")
    return DeepReadProgressSnapshot(percent.coerceIn(WRITING_BASE_PERCENT, WRITING_MAX_PERCENT), label)
}

private fun DeepReadGenerationStage.progressLabel(locale: Locale): String = when (this) {
    DeepReadGenerationStage.OVERVIEW -> if (locale.isChineseLocale()) "概览" else "Overview"
    DeepReadGenerationStage.NARRATIVE -> if (locale.isChineseLocale()) "叙事" else "Narrative"
    DeepReadGenerationStage.ANALYSIS -> if (locale.isChineseLocale()) "分析" else "Analysis"
    DeepReadGenerationStage.EXTENDED_READING -> if (locale.isChineseLocale()) "扩展阅读" else "Extended reading"
}

private fun Locale.isChineseLocale(): Boolean = language.equals("zh", ignoreCase = true)

private fun Locale.progressLabel(key: String): String = if (isChineseLocale()) {
    when (key) {
        "complete" -> "已完成"
        "preparing" -> "正在准备"
        "not_started" -> "未开始"
        "verifying" -> "正在补漏"
        "finishing" -> "正在收尾"
        "collecting" -> "正在收集资料"
        "planning" -> "正在规划结构"
        "writing" -> "正在分段写作"
        else -> key
    }
} else {
    when (key) {
        "complete" -> "Complete"
        "preparing" -> "Preparing"
        "not_started" -> "Not started"
        "verifying" -> "Checking sources"
        "finishing" -> "Finishing"
        "collecting" -> "Collecting sources"
        "planning" -> "Planning structure"
        "writing" -> "Writing sections"
        else -> key
    }
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
