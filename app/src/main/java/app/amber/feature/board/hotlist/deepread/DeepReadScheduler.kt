package app.amber.feature.board.hotlist.deepread

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class DeepReadScheduler(
    context: Context,
) {
    private val workManager = WorkManager.getInstance(context)

    fun run(
        topicId: String,
        title: String,
        sourceUrl: String?,
        force: Boolean,
    ) {
        if (topicId.isBlank() || title.isBlank()) return
        workManager.enqueueUniqueWork(
            workName(topicId),
            if (force) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
            request(topicId, title, sourceUrl, force, stage = null),
        )
    }

    fun runSection(
        topicId: String,
        title: String,
        sourceUrl: String?,
        stage: DeepReadGenerationStage,
    ) {
        if (topicId.isBlank() || title.isBlank()) return
        workManager.enqueueUniqueWork(
            workName(topicId),
            ExistingWorkPolicy.KEEP,
            request(topicId, title, sourceUrl, force = false, stage = stage),
        )
    }

    fun observeRunning(topicId: String): Flow<Boolean> =
        workManager.getWorkInfosForUniqueWorkFlow(workName(topicId))
            .map { infos ->
                infos.any { info ->
                    info.state == WorkInfo.State.ENQUEUED ||
                        info.state == WorkInfo.State.RUNNING ||
                        info.state == WorkInfo.State.BLOCKED
                }
            }

    private fun request(
        topicId: String,
        title: String,
        sourceUrl: String?,
        force: Boolean,
        stage: DeepReadGenerationStage?,
    ) = OneTimeWorkRequestBuilder<DeepReadWorker>()
        .setConstraints(
            Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build(),
        )
        .setInputData(
            workDataOf(
                DeepReadWorker.KEY_TOPIC_ID to topicId,
                DeepReadWorker.KEY_TITLE to title,
                DeepReadWorker.KEY_SOURCE_URL to sourceUrl.orEmpty(),
                DeepReadWorker.KEY_FORCE to force,
                DeepReadWorker.KEY_STAGE to stage?.name.orEmpty(),
            )
        )
        .addTag(TAG)
        .addTag(workName(topicId))
        .build()

    companion object {
        const val TAG = "deep_read_generate"
        fun workName(topicId: String): String = "deep_read_generate_$topicId"
    }
}
