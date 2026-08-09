package app.amber.feature.novel.background

import app.amber.feature.novel.model.NovelGhostwriteJobId

/** App-side execution port owned by the foreground [NovelGhostwriteWorker]. */
interface NovelGhostwriteBatchRunning {
    suspend fun run(
        jobId: NovelGhostwriteJobId,
        workId: String,
    ): BatchRunResult
}

sealed interface BatchRunResult {
    data object Completed : BatchRunResult
    data object Paused : BatchRunResult
    data object Retry : BatchRunResult
    data object Failed : BatchRunResult
}
