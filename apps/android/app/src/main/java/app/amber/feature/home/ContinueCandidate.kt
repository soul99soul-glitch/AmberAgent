package app.amber.feature.home

import java.time.Instant

/**
 * P8-08 — 首页「继续」聚合的候选模型。
 *
 * 一个候选代表一个「用户可继续」的任务项目，全部来自各域的持久投影
 * （Council Room 持久 JSON、DeepRead 缓存、MiniApp 草稿表、图像生成运行账本），
 * 不依赖当前进程内运行状态。候选被点击后通过 [ContinueRoute] 精确路由到任务焦点。
 */
enum class ContinueSourceKind {
    COUNCIL,
    DEEP_READ,
    MINIAPP_DRAFT,
    IMAGE_GENERATION,
}

enum class ContinueStatus {
    /** 草稿：已落盘、等待用户确认后继续（如 MiniApp 写的输入框草稿）。 */
    DRAFT,

    /** 暂停：用户或系统暂停，可恢复（如 Novel 批次）。 */
    PAUSED,

    /** 等待用户：需要用户操作才能推进（如待启动的 Novel 批次）。 */
    WAITING_USER,

    /** 中断但可恢复：上次运行被打断/失败，仍可继续（如 Council 中断房间、未完成的深度阅读）。 */
    FAILED_RESUMABLE,
}

/** 点击候选后跳转的目标；各域各自精确聚焦到任务所在页面。 */
sealed interface ContinueRoute {
    data class CouncilRoom(val conversationId: String) : ContinueRoute

    data class DeepRead(val topicId: String, val title: String) : ContinueRoute

    data class Chat(val conversationId: String) : ContinueRoute
}

data class ContinueCandidate(
    val sourceKind: ContinueSourceKind,
    val sourceId: String,
    val route: ContinueRoute,
    val title: String,
    val summary: String,
    val lastUpdatedAt: Instant,
    val status: ContinueStatus,
    /** 用户固定项标记：>0 的候选归入「用户固定项」排序组。 */
    val priority: Int = 0,
    /** 持久运行账本仍显示为 active；状态桶保持旧 UI 可承载的 resumable 语义。 */
    val isRunning: Boolean = false,
)

/**
 * P8-08 排序规则（计划 §P8-08）：
 * 1. waitingUser
 * 2. failedResumable
 * 3. 最近 paused/draft（按 lastUpdatedAt 倒序）
 * 4. 用户固定项（priority > 0，固定组内 priority 高者在前）
 *
 * 同一组内先按 priority 倒序（固定项优先于普通项），再按最近更新时间倒序，
 * 最后用 (sourceKind, sourceId) 保证稳定顺序。
 */
internal fun sortContinueCandidates(candidates: List<ContinueCandidate>): List<ContinueCandidate> =
    candidates.sortedWith(
        compareBy<ContinueCandidate> { statusTier(it) }
            .thenByDescending { it.priority }
            .thenByDescending { it.lastUpdatedAt }
            .thenBy { it.sourceKind.name }
            .thenBy { it.sourceId },
    )

private fun statusTier(candidate: ContinueCandidate): Int {
    if (candidate.priority > 0) return 3
    if (candidate.isRunning) return 1
    return when (candidate.status) {
        ContinueStatus.WAITING_USER -> 0
        ContinueStatus.FAILED_RESUMABLE -> 1
        ContinueStatus.PAUSED,
        ContinueStatus.DRAFT,
        -> 2
    }
}
