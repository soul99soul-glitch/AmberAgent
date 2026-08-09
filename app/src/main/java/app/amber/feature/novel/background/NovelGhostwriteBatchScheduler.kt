package app.amber.feature.novel.background

import android.content.Context
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.await
import androidx.work.workDataOf
import app.amber.feature.novel.model.NovelGhostwriteJobId
import app.amber.feature.novel.model.NovelGhostwriteJobStatus
import app.amber.feature.novel.model.NovelGhostwriteJobV1

/** Schedules one unique WorkManager owner for each durable ghostwrite job. */
class NovelGhostwriteBatchScheduler(
    context: Context,
) {
    private val workManager = WorkManager.getInstance(context)

    suspend fun start(job: NovelGhostwriteJobV1) {
        enqueue(job, startPolicy(job))
    }

    suspend fun resume(job: NovelGhostwriteJobV1) {
        enqueue(job, resumePolicy(job))
    }

    suspend fun cancel(jobId: NovelGhostwriteJobId) {
        workManager.cancelUniqueWork(uniqueWorkName(jobId)).await()
    }

    private suspend fun enqueue(
        job: NovelGhostwriteJobV1,
        policy: ExistingWorkPolicy,
    ) {
        require(!job.isTerminal) { "A terminal ghostwrite job cannot be scheduled." }
        workManager.enqueueUniqueWork(
            uniqueWorkName(job.id),
            policy,
            workRequest(job),
        ).await()
    }

    companion object {
        internal const val TAG_BATCH = "novel_ghostwrite_batch"

        internal fun uniqueWorkName(jobId: NovelGhostwriteJobId): String =
            "novel_ghostwrite:${jobId}"

        internal fun startPolicy(job: NovelGhostwriteJobV1): ExistingWorkPolicy {
            require(job.status == NovelGhostwriteJobStatus.Pending) {
                "Only a new pending ghostwrite job can be started."
            }
            return ExistingWorkPolicy.KEEP
        }

        internal fun resumePolicy(job: NovelGhostwriteJobV1): ExistingWorkPolicy {
            require(job.status == NovelGhostwriteJobStatus.Paused) {
                "Only a durable paused ghostwrite job can be resumed."
            }
            // A worker can still be unwinding just after persisting Paused. Append
            // behind that owner; replace only a failed/cancelled prerequisite chain.
            return ExistingWorkPolicy.APPEND_OR_REPLACE
        }

        internal fun workRequest(job: NovelGhostwriteJobV1): OneTimeWorkRequest =
            OneTimeWorkRequestBuilder<NovelGhostwriteWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .setInputData(workInputData(job))
                .addTag(TAG_BATCH)
                .addTag("$TAG_BATCH:${job.id}")
                .build()

        internal fun workInputData(job: NovelGhostwriteJobV1): Data =
            workDataOf(
                NovelGhostwriteWorker.KEY_JOB_ID to job.id.toString(),
                NovelGhostwriteWorker.KEY_SCHEDULED_EXECUTION_EPOCH to job.executionEpoch,
            )
    }
}
