package me.rerere.rikkahub.data.agent.board.hotlist.deepread

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import me.rerere.rikkahub.data.agent.board.hotlist.HotListRepository
import org.koin.core.component.KoinComponent
import org.koin.core.component.get

class DeepReadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params), KoinComponent {
    override suspend fun doWork(): Result {
        val topicId = inputData.getString(KEY_TOPIC_ID)?.takeIf { it.isNotBlank() }
            ?: return Result.failure()
        val title = inputData.getString(KEY_TITLE)?.takeIf { it.isNotBlank() }
            ?: return Result.failure()
        val sourceUrl = inputData.getString(KEY_SOURCE_URL)?.takeIf { it.isNotBlank() }
        val force = inputData.getBoolean(KEY_FORCE, false)
        val notifier = get<DeepReadNotifier>()

        setForeground(createForegroundInfo(notifier, topicId, title, sourceUrl))

        return runCatching {
            val output = get<DeepReadAgentRunManager>()
                .run(topicId = topicId, topicTitle = title, force = force, seedUrl = sourceUrl)
                .getOrThrow()
                .withInferredSectionStates()
            notifier.notifyCompleted(
                topicId = topicId,
                title = title,
                sourceUrl = sourceUrl,
                complete = output.isComplete(),
            )
            Result.success()
        }.getOrElse { error ->
            val reason = error.message ?: error::class.java.simpleName
            val repository = get<HotListRepository>()
            val cached = repository.materializeFreshDeepRead(topicId, title)?.withInferredSectionStates()
            repository.saveDeepRead(
                topicId = topicId,
                title = title,
                output = failureOutput(cached, reason),
            )
            notifier.notifyFailed(
                topicId = topicId,
                title = title,
                sourceUrl = sourceUrl,
                reason = reason,
            )
            Result.failure()
        }
    }

    private fun createForegroundInfo(
        notifier: DeepReadNotifier,
        topicId: String,
        title: String,
        sourceUrl: String?,
    ): ForegroundInfo {
        val notification = notifier.buildRunningNotification(topicId, title, sourceUrl)
        val notificationId = DeepReadNotifier.notificationId(topicId)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            ForegroundInfo(notificationId, notification)
        }
    }

    private fun failureOutput(cached: DeepReadOutput?, reason: String): DeepReadOutput {
        val failureMessage = reason.take(500)
        return (cached ?: DeepReadOutput()).copy(
            generationPhase = DeepReadGenerationPhase.IDLE,
            generationComplete = false,
            sectionStates = DeepReadGenerationStage.entries.associateWith { stage ->
                val current = cached?.sectionStates?.get(stage)
                if (current?.status == DeepReadSectionStatus.READY) {
                    current
                } else {
                    DeepReadSectionState(
                        status = DeepReadSectionStatus.FAILED,
                        errorMessage = failureMessage,
                    )
                }
            },
            verificationState = if (cached?.verificationState?.status == DeepReadSectionStatus.READY) {
                cached.verificationState
            } else {
                DeepReadSectionState(
                    status = DeepReadSectionStatus.FAILED,
                    errorMessage = failureMessage,
                )
            },
        )
    }

    companion object {
        const val KEY_TOPIC_ID = "topic_id"
        const val KEY_TITLE = "title"
        const val KEY_SOURCE_URL = "source_url"
        const val KEY_FORCE = "force"
    }
}
