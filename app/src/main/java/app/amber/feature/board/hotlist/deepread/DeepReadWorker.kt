package app.amber.feature.board.hotlist.deepread

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import app.amber.feature.board.hotlist.HotListRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import java.io.IOException

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
        val route = deepReadWorkerRoute(inputData.getString(KEY_STAGE))
        if (route is DeepReadWorkerRoute.Invalid) return Result.failure()
        val notifier = get<DeepReadNotifier>()
        val repository = get<HotListRepository>()

        try {
            setForeground(createForegroundInfo(notifier, topicId, title, sourceUrl))
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Throwable) {
            if (canRetryDeepReadWorker(runAttemptCount)) return Result.retry()
            val reason = error.message ?: error::class.java.simpleName
            val cached = repository.materializeFreshDeepRead(topicId, title)?.withInferredSectionStates()
            repository.saveDeepRead(
                topicId = topicId,
                title = title,
                output = failureOutput(cached, reason),
            )
            runCatching {
                notifier.notifyFailed(
                    topicId = topicId,
                    title = title,
                    sourceUrl = sourceUrl,
                    reason = reason,
                )
            }
            return Result.failure()
        }

        return coroutineScope {
            val progressJob = launchProgressNotifications(repository, notifier, topicId, title, sourceUrl)
            try {
                runGeneration(route, topicId, title, sourceUrl, force, repository, notifier)
            } finally {
                progressJob.cancelAndJoin()
            }
        }
    }

    private suspend fun runGeneration(
        route: DeepReadWorkerRoute,
        topicId: String,
        title: String,
        sourceUrl: String?,
        force: Boolean,
        repository: HotListRepository,
        notifier: DeepReadNotifier,
    ): Result {
        return try {
            val manager = get<DeepReadAgentRunManager>()
            val output = when (route) {
                DeepReadWorkerRoute.All -> manager.run(
                    topicId = topicId,
                    topicTitle = title,
                    force = effectiveDeepReadForce(force, runAttemptCount),
                    seedUrl = sourceUrl,
                    deferMissingStages = false,
                    propagateFailuresWithPartial = true,
                )

                is DeepReadWorkerRoute.Section -> manager.runSection(
                    topicId = topicId,
                    topicTitle = title,
                    stage = route.stage,
                    seedUrl = sourceUrl,
                    propagateFailuresWithPartial = true,
                )

                is DeepReadWorkerRoute.Invalid -> return Result.failure()
            }
                .getOrThrow()
                .withInferredSectionStates()
            notifier.notifyCompleted(
                topicId = topicId,
                title = title,
                sourceUrl = sourceUrl,
                complete = output.isComplete(),
            )
            Result.success()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Throwable) {
            val reason = error.message ?: error::class.java.simpleName
            val cached = repository.materializeFreshDeepRead(topicId, title)?.withInferredSectionStates()
            if (shouldRetryDeepReadWorkerError(error, runAttemptCount)) {
                repository.saveDeepRead(
                    topicId = topicId,
                    title = title,
                    output = retryableOutput(cached),
                )
                return Result.retry()
            }
            val exhaustedRetry = isRetryableDeepReadWorkerError(error)
            repository.saveDeepRead(
                topicId = topicId,
                title = title,
                output = if (exhaustedRetry || shouldWriteDeepReadFailureOutput(cached, error)) {
                    failureOutput(cached, reason)
                } else {
                    idleOutput(cached)
                },
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

    private fun CoroutineScope.launchProgressNotifications(
        repository: HotListRepository,
        notifier: DeepReadNotifier,
        topicId: String,
        title: String,
        sourceUrl: String?,
    ) = launch {
        var lastProgress = null.deepReadProgressSnapshot(running = true)
        try {
            repository.observeDeepRead(topicId).collect { output ->
                if (!output.shouldNotifyRunningDeepReadProgress()) return@collect
                val progress = output.deepReadProgressSnapshot(running = true)
                if (shouldNotifyDeepReadProgress(lastProgress, progress)) {
                    lastProgress = progress
                    try {
                        notifier.notifyRunningProgress(
                            topicId = topicId,
                            title = title,
                            sourceUrl = sourceUrl,
                            progress = progress,
                        )
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (_: Throwable) {
                        // Notification progress is best-effort; Room remains the source of truth.
                    }
                }
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Throwable) {
            // Notification progress is best-effort; Room remains the source of truth.
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
            verificationState = cached?.verificationState ?: DeepReadSectionState(),
        )
    }

    companion object {
        const val KEY_TOPIC_ID = "topic_id"
        const val KEY_TITLE = "title"
        const val KEY_SOURCE_URL = "source_url"
        const val KEY_FORCE = "force"
        const val KEY_STAGE = "stage"
    }
}

internal sealed interface DeepReadWorkerRoute {
    data object All : DeepReadWorkerRoute
    data class Section(val stage: DeepReadGenerationStage) : DeepReadWorkerRoute
    data class Invalid(val rawStage: String) : DeepReadWorkerRoute
}

internal fun deepReadWorkerRoute(rawStage: String?): DeepReadWorkerRoute {
    val stageName = rawStage?.takeIf { it.isNotBlank() } ?: return DeepReadWorkerRoute.All
    val stage = DeepReadGenerationStage.entries.firstOrNull { it.name == stageName }
    return if (stage != null) {
        DeepReadWorkerRoute.Section(stage)
    } else {
        DeepReadWorkerRoute.Invalid(stageName)
    }
}

internal fun effectiveDeepReadForce(force: Boolean, runAttemptCount: Int): Boolean =
    force && runAttemptCount == 0

internal fun canRetryDeepReadWorker(runAttemptCount: Int): Boolean =
    runAttemptCount < MAX_DEEP_READ_WORKER_RETRY_ATTEMPTS

internal fun shouldRetryDeepReadWorkerError(error: Throwable, runAttemptCount: Int): Boolean =
    isRetryableDeepReadWorkerError(error) && canRetryDeepReadWorker(runAttemptCount)

internal fun isRetryableDeepReadWorkerError(error: Throwable): Boolean {
    if (error is CancellationException) return false
    if (error is IOException) return true
    val message = error.deepReadErrorText()
    return message.contains("没有抓到足够的来源") ||
        RETRYABLE_ERROR_MARKERS.any { marker -> message.contains(marker, ignoreCase = true) }
}

internal fun shouldWriteDeepReadFailureOutput(cached: DeepReadOutput?, error: Throwable): Boolean =
    cached?.hasAnyReadySection() != true || isPermanentDeepReadWorkerError(error)

private fun isPermanentDeepReadWorkerError(error: Throwable): Boolean {
    val message = error.deepReadErrorText()
    return PERMANENT_ERROR_MARKERS.any { marker -> message.contains(marker, ignoreCase = true) }
}

private fun retryableOutput(cached: DeepReadOutput?): DeepReadOutput =
    idleOutput(cached).copy(
        sectionStates = DeepReadGenerationStage.entries.associateWith { stage ->
            val current = cached?.sectionStates?.get(stage)
            if (current?.status == DeepReadSectionStatus.READY) {
                current
            } else {
                DeepReadSectionState()
            }
        },
    )

private fun idleOutput(cached: DeepReadOutput?): DeepReadOutput =
    (cached ?: DeepReadOutput()).copy(
        generationPhase = DeepReadGenerationPhase.IDLE,
        generationComplete = false,
        sectionStates = DeepReadGenerationStage.entries.associateWith { stage ->
            val current = cached?.sectionStates?.get(stage)
            when (current?.status) {
                DeepReadSectionStatus.READY,
                DeepReadSectionStatus.FAILED -> current

                else -> DeepReadSectionState()
            }
        },
        verificationState = cached?.verificationState ?: DeepReadSectionState(),
    )

private fun Throwable.deepReadErrorText(): String =
    listOfNotNull(
        message,
        localizedMessage,
        this::class.simpleName,
        this::class.qualifiedName,
    ).joinToString(" ")

private val RETRYABLE_ERROR_MARKERS = listOf(
    "timed out",
    "timeout",
    "SocketTimeout",
    "deadline exceeded",
    "Read timed out",
    "connection reset",
    "temporarily unavailable",
    "HTTP 408",
    "HTTP 409",
    "HTTP 429",
    "HTTP 500",
    "HTTP 502",
    "HTTP 503",
    "HTTP 504",
)

private const val MAX_DEEP_READ_WORKER_RETRY_ATTEMPTS = 3

private val PERMANENT_ERROR_MARKERS = listOf(
    "请先配置今日看板模型",
    "请先配置主聊天模型",
    "深度阅读需要配置支持工具调用的模型",
    "invalid api key",
    "HTTP 400",
    "HTTP 401",
    "HTTP 403",
    "insufficient_quota",
    "content_policy",
    "context_length",
)
