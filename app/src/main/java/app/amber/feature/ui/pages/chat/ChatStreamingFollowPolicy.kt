package app.amber.feature.ui.pages.chat

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow

internal object TimelineFollowEndSettlePolicy {
    const val MaxSettleFrames = 4

    fun effectPlan(
        wasActiveGeneration: Boolean,
        activeGeneration: Boolean,
        autoScrollEnabled: Boolean,
    ): GenerationEndEffectPlan = when {
        !autoScrollEnabled -> GenerationEndEffectPlan(
            runEndSettleBeforeIdle = false,
            enterIdleAfterEndSettle = true,
        )

        !activeGeneration -> GenerationEndEffectPlan(
            runEndSettleBeforeIdle = wasActiveGeneration,
            enterIdleAfterEndSettle = true,
        )

        else -> GenerationEndEffectPlan(
            runEndSettleBeforeIdle = false,
            enterIdleAfterEndSettle = false,
        )
    }

    fun canSettleNow(
        followMode: TimelineFollowMode,
        userScrollInTimeline: Boolean,
        scrollInProgress: Boolean,
    ): Boolean = followMode == TimelineFollowMode.FollowingBottom &&
        !userScrollInTimeline &&
        !scrollInProgress

    fun canAttemptSettle(
        followMode: TimelineFollowMode,
        userScrollInTimeline: Boolean,
    ): Boolean = followMode == TimelineFollowMode.FollowingBottom &&
        !userScrollInTimeline

    fun isCloseEnoughToBottom(
        distancePx: Int?,
        bottomBufferPx: Int,
    ): Boolean = distancePx != null && distancePx <= bottomBufferPx
}

internal data class GenerationEndEffectPlan(
    val runEndSettleBeforeIdle: Boolean,
    val enterIdleAfterEndSettle: Boolean,
)

internal fun createStreamingBottomFollowEvents(): MutableSharedFlow<String> =
    MutableSharedFlow(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
