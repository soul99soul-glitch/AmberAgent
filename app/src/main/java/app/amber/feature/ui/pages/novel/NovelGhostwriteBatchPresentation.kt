package app.amber.feature.ui.pages.novel

import app.amber.feature.novel.NovelGhostwriteBatchController
import app.amber.feature.novel.model.NovelGhostwriteJobPhase
import app.amber.feature.novel.model.NovelGhostwriteJobStatus
import app.amber.feature.novel.model.NovelGhostwriteJobV1

internal fun parseNovelGhostwriteBatchTarget(raw: String): Int? = raw.trim().toIntOrNull()
    ?.takeIf {
        it in NovelGhostwriteJobV1.MIN_TARGET_CHAPTER_COUNT..
            NovelGhostwriteJobV1.MAX_TARGET_CHAPTER_COUNT
    }

internal fun NovelGhostwriteJobV1.batchProgressLabel(): String =
    "$completedChapterCount/$targetChapterCount"

internal fun NovelGhostwriteJobV1.batchStatusLabel(): String = when (status) {
    NovelGhostwriteJobStatus.Pending -> "等待后台启动"
    NovelGhostwriteJobStatus.Running -> phase.batchPhaseLabel()
    NovelGhostwriteJobStatus.Paused -> "已暂停"
    NovelGhostwriteJobStatus.Completed -> "已完成"
    NovelGhostwriteJobStatus.Failed -> "已失败"
    NovelGhostwriteJobStatus.Cancelled -> "已取消"
}

internal fun NovelGhostwriteJobV1.batchCanResume(): Boolean =
    status == NovelGhostwriteJobStatus.Paused &&
        NovelGhostwriteBatchController.isResumablePauseReason(statusReasonCode)

internal fun NovelGhostwriteJobPhase.batchPhaseLabel(): String = when (this) {
    NovelGhostwriteJobPhase.AwaitingPlan -> "等待章节计划"
    NovelGhostwriteJobPhase.Planning -> "生成章节计划"
    NovelGhostwriteJobPhase.PlanPrepared -> "准备写入章节计划"
    NovelGhostwriteJobPhase.GenerationPrepared -> "准备生成正文"
    NovelGhostwriteJobPhase.Generating -> "生成正文"
    NovelGhostwriteJobPhase.CandidateReady -> "候选正文已生成"
    NovelGhostwriteJobPhase.Validating -> "校验正文"
    NovelGhostwriteJobPhase.CorrectionReady -> "准备自纠正"
    NovelGhostwriteJobPhase.CollectPrepared -> "准备收录"
    NovelGhostwriteJobPhase.CollectedNeedsSync -> "正文已收录，等待同步"
    NovelGhostwriteJobPhase.Syncing -> "同步剧情状态"
    NovelGhostwriteJobPhase.ClearPlanPrepared -> "准备清理已完成计划"
    NovelGhostwriteJobPhase.ChapterCommitPrepared -> "准备提交本章"
    NovelGhostwriteJobPhase.ChapterCommitted -> "本章已提交"
}

internal fun humanizeNovelGhostwriteBatchReason(reasonCode: String?): String? = when (reasonCode) {
    null -> null
    "user_paused" -> "已由你暂停，可继续批次"
    "user_cancelled" -> "批次已由你取消"
    "quality_circuit_breaker" -> "同一章连续校验失败，自动循环已停止，避免污染后续章节"
    "infra_retry_exhausted" -> "后台服务连续失败，已暂停，可在环境恢复后继续"
    "foreground_notification_unavailable" -> "通知不可用；请开启通知后继续批次"
    "foreground_start_failed" -> "系统未允许后台服务启动；请检查通知和省电设置"
    "foreground_update_failed" -> "后台通知更新失败，批次已安全暂停"
    "canonical_continuity_conflict" -> "正史连续性存在阻塞冲突，需要人工处理"
    "orphan_candidate_recovery" -> "发现进程中断前遗留的候选正文，未自动收录，请人工处理"
    "external_project_drift" -> "项目内容已在批次外变化，批次已停止以避免串章"
    "plan_mismatch" -> "章节计划已变化，批次已停止以避免使用错误上下文"
    else -> "任务原因：${reasonCode.safeBatchReasonCode()}"
}

private fun String.safeBatchReasonCode(): String = trim()
    .map { character -> if (character.isISOControl()) ' ' else character }
    .joinToString("")
    .take(MAX_REASON_CODE_DISPLAY_CHARACTERS)

private const val MAX_REASON_CODE_DISPLAY_CHARACTERS = 120
