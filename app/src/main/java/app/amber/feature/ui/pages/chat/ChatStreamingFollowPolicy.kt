package app.amber.feature.ui.pages.chat

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow

internal object TimelineFollowEndSettlePolicy {
    // Wide enough to cover the end-of-stream virtualization relayout: the
    // finished message re-keys into header/blocks/footer items, and building
    // that plan parses the full markdown — on long messages the displaced
    // layout can land ~6-10 frames after loading flips. The loop exits after
    // RequiredStableBottomFrames quiet frames, so the cap only matters while
    // the layout is actually still moving.
    const val MaxSettleFrames = 16
    const val RequiredStableBottomFrames = 2

    // Minimum iterations before the settle loop may exit on stability.
    // When loading → false, the Markdown composable triggers an asynchronous
    // full re-parse via withContext(Dispatchers.Default) inside a LaunchedEffect.
    // The re-parse + layout propagation can take 4-8 frames, but the settle
    // sees "stable distance = 0" in the frames BEFORE the new layout lands and
    // exits after just 2 consecutive stable frames — leaving the post-re-parse
    // viewport displacement uncorrected.  This guard forces the loop to keep
    // running (and scrolling) through the pre-re-parse quiet window.
    // Set to 8 to cover the upper bound of re-parse latency; the real exit
    // condition is bottom-item height change-detection (see settleAfterGenerationEnd).
    const val MinSettleFrames = 8

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

    fun hasEnoughStableBottomFrames(stableBottomFrames: Int): Boolean =
        stableBottomFrames >= RequiredStableBottomFrames
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
