package app.amber.feature.ui.pages.chat

import android.os.Trace
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember

import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.unit.Constraints
import app.amber.agent.BuildConfig
import kotlinx.coroutines.launch

/**
 * Idle pinned viewports may animate *streaming* height. Discrete jumps
 * after generation (settled tree, async image decode) must use reverseLayout's
 * native bottom pin: [animateContentSize] interpolates the measured height
 * and the last token overflows the timeline until the spring finishes
 * (device productionPinned: RICH_TAIL 2511 > timeline 2056, then 1741).
 */
@Composable
internal fun Modifier.streamingTailLayout(
    state: LazyListState,
    lazyIndex: Int,
    followBottomPx: Int,
    animate: Boolean,
    itemKey: Any?,
): Modifier {
    val sendStage = LocalChatSendStage.current
    val animateHeight by remember(state, followBottomPx, animate, sendStage) {
        derivedStateOf {
            animate && shouldAnimateStreamingTail(
                state.firstVisibleItemIndex, state.firstVisibleItemScrollOffset,
                followBottomPx, state.isScrollInProgress,
                sendStageActive = sendStage?.isActive == true,
            )
        }
    }
    return holdReadingOnTailGrowth(state, lazyIndex, followBottomPx, itemKey, sendStage).then(
        if (animateHeight) Modifier.animateContentSize(
            spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium),
        ) else Modifier,
    )
}

internal fun shouldAnimateStreamingTail(
    index: Int,
    offset: Int,
    threshold: Int,
    scrolling: Boolean,
    sendStageActive: Boolean = false,
): Boolean = !sendStageActive && !scrolling && index == 0 && offset <= threshold

/**
 * First-visible (index, offset) the idle hold should compensate against.
 *
 * LazyList publishes [publishedIndex]/[publishedOffset] a frame late.
 * Device completion on 392288d7 logged `first=0/0` while
 * `visibleItemsInfo` already listed the growing message at index 2;
 * treating that published pair as pinned dropped the last 19-char
 * (66–132px) streaming delta. Prefer an in-flight [requestedIndex]
 * that is still in [visibleItems], then the lowest index actually in
 * layoutInfo (with that item's layout offset), then the published pair.
 *
 * Same-index published offset can lag too: TimelineTail stays at index 0,
 * so a short off-bottom drag (88px) still matches published index 0 while
 * `firstVisibleItemScrollOffset` is still 0. Using the published offset
 * then looks pinned and eats the settle-height delta (4d
 * `productionVisibleReplySwapsUnderOlderReadingAnchor`: 359→275, 84px).
 */
internal fun readingAnchorFromLayout(
    visibleItems: List<LazyListItemInfo>,
    viewportStartOffset: Int,
    publishedIndex: Int,
    publishedOffset: Int,
    requestedIndex: Int,
    requestedOffset: Int,
): Pair<Int, Int> {
    if (requestedIndex >= 0 && visibleItems.any { it.index == requestedIndex }) {
        return requestedIndex to requestedOffset
    }
    val layoutFirst = visibleItems.minByOrNull { it.index } ?: return publishedIndex to publishedOffset
    val layoutOffset = (viewportStartOffset - layoutFirst.offset).coerceAtLeast(0)
    if (layoutFirst.index != publishedIndex) {
        return layoutFirst.index to layoutOffset
    }
    // Same index: firstVisibleItemScrollOffset is the requestScrollToItem
    // coordinate (content padding included). Always-using layoutOffset
    // over-compensated by the reverseLayout bottom padding (handover
    // geometry test: 106→114). Only override when the published pair
    // still looks pinned while layout already shows the item scrolled in.
    val publishedLooksPinned = publishedIndex == 0 && publishedOffset <= 2
    val layoutScrolledIntoItem = layoutFirst.offset < viewportStartOffset
    if (publishedLooksPinned && layoutScrolledIntoItem) {
        return layoutFirst.index to layoutOffset
    }
    return publishedIndex to publishedOffset
}

/** Hold iff the reader is not pinned to the reverseLayout bottom and send-stage is not owning scroll. */
internal fun shouldHoldReadingOnTailGrowth(
    firstIndex: Int,
    firstOffset: Int,
    followBottomPx: Int,
    growingItemVisible: Boolean,
    sendStageActive: Boolean = false,
): Boolean {
    if (sendStageActive) return false
    if (firstIndex == 0 && firstOffset <= followBottomPx) return false
    return growingItemVisible
}

/**
 * Skip [LazyListState.layoutInfo] on the last-message always-on hold when
 * the item height has not changed. Reading layoutInfo every measure was the
 * short-scene P95 suspect (4c item 2).
 */
internal fun idleHoldShouldReadLayout(
    lastHeight: Int,
    height: Int,
    pendingIdleDelta: Int,
): Boolean {
    if (lastHeight < 0) return false
    return height != lastHeight || pendingIdleDelta != 0
}

/**
 * Drop the in-flight idle [requestedIndex] once height has been stable
 * for [dropAfterStableFrames] measures. Late-frame reconcile needs the
 * target for one positioned pass after growth; keeping it forever yanks
 * a later [androidx.compose.foundation.lazy.LazyListState.scrollToItem]
 * back to the in-growth offset (4c `cancelRegenerateSwitchHistoryAndCopy`
 * could not reach `DEVICE_FINAL_COPY_MARKER`).
 */
internal fun idleHoldShouldDropSettledTarget(
    lastHeight: Int,
    height: Int,
    pendingIdleDelta: Int,
    stableFrameCount: Int,
    firstVisibleIndex: Int,
    firstVisibleOffset: Int,
    requestedIndex: Int,
    requestedOffset: Int,
    dropAfterStableFrames: Int = 2,
): Boolean {
    if (requestedIndex < 0) return false
    if (lastHeight < 0) return false
    if (height != lastHeight || pendingIdleDelta != 0) return false
    if (stableFrameCount < dropAfterStableFrames) return false
    // Drop only when the list left the idle target (scrollToItem(0) or
    // another item). Keeping the target while still off-bottom lets
    // late-frame reconcile cover image/math height that arrives after
    // two placeholder frames (4c image-math 664px).
    if (firstVisibleIndex != requestedIndex) return true
    return requestedOffset > 2 && firstVisibleOffset <= 2
}

/**
 * A non-animated [androidx.compose.foundation.lazy.LazyListState.scrollToItem]
 * to the same index with a different offset must keep that target. The idle
 * hold's in-flight [requestedOffset] would otherwise yank it back during
 * growth (late-frame reconcile dispatches the missed delta).
 *
 * Lag after our own [androidx.compose.foundation.lazy.LazyListState.requestScrollToItem]
 * still shows [lastSeenOffset]. An external jump lands on a third value.
 */
internal fun idleHoldShouldAdoptExternalScroll(
    requestedIndex: Int,
    requestedOffset: Int,
    firstVisibleIndex: Int,
    firstVisibleOffset: Int,
    lastSeenOffset: Int,
    applyingHold: Boolean,
): Boolean {
    if (applyingHold) return false
    if (requestedIndex < 0 || lastSeenOffset < 0) return false
    if (firstVisibleIndex != requestedIndex) return false
    if (firstVisibleOffset == requestedOffset) return false
    if (firstVisibleOffset == lastSeenOffset) return false
    return true
}

/**
 * User drag/fling owns [androidx.compose.foundation.lazy.LazyListState.isScrollInProgress]
 * when this node did not start the scroll with [androidx.compose.foundation.lazy.LazyListState.requestScrollToItem]
 * or a late-frame [androidx.compose.foundation.lazy.LazyListState.dispatchRawDelta].
 *
 * An outstanding idle target is not part of the gate: 4c device
 * `idleHoldThenImmediateDragMovesByInjectedDistance` injected 80px after
 * slop and the list offset moved 80px, but tying ownership to
 * `requestedIndex < 0` kept the idle path live and the older-item Y
 * lagged 10px while height was still changing.
 */
internal fun userGestureOwnsScroll(
    scrollInProgress: Boolean,
    programmaticScroll: Boolean,
): Boolean = scrollInProgress && !programmaticScroll

/**
 * Idle hold target after a height delta. Growing adds to the first-visible
 * offset. Shrinking below 0 walks toward newer items (lower index in
 * reverseLayout). Growing past the first-visible item's size walks toward
 * older items (higher index) so a small pinned-off tail does not clamp
 * away thousands of pixels of compensation.
 */
internal fun compensatedScrollTarget(
    firstVisibleIndex: Int,
    firstVisibleOffset: Int,
    delta: Int,
    itemSize: (Int) -> Int?,
): Pair<Int, Int> {
    var index = firstVisibleIndex
    var offset = firstVisibleOffset + delta
    while (offset < 0 && index > 0) {
        val prevSize = itemSize(index - 1) ?: break
        if (prevSize <= 0) break
        index -= 1
        offset += prevSize
    }
    while (true) {
        val size = itemSize(index) ?: break
        if (size <= 0 || offset < size) break
        offset -= size
        index += 1
    }
    return index to offset.coerceAtLeast(0)
}

/**
 * 离底阅读时钉住正在流式增高的尾消息上方的已读内容。
 *
 * reverseLayout 下 LazyList 保持最底可见 item 的底边不动：尾消息（或它下方的
 * 尾部 item）作锚时，每次增高 ΔH 都会把已读内容顶上去 ΔH——上滑后仍被持续带走。
 * 空闲时在 **measure** 里 `requestScrollToItem`（测量阶段可调用）。真机上
 * LazyList 常把新 offset 晚一帧才反映到 `firstVisibleItemScrollOffset`，
 * 所以 [onGloballyPositioned] 之后用协程 `dispatchRawDelta` 补上差值。
 * 这是**晚一帧补偿**，不能写成同帧。不能在 measure 里 `dispatchRawDelta`：会在
 * 测量中 forceRemeasure，Robolectric 上抛 IllegalArgumentException。
 * 不再额外 `withFrameNanos`：同一夹具在 b4477f42b 上增高中间帧 0px，
 * 多等一帧 vsync 后 HEAD 中间帧 216px。
 * 拖动/惯性中不调用 `requestScrollToItem`（它会取消手势）；手势期间高度
 * 变化在 onGloballyPositioned 之后用协程 `dispatchRawDelta` 补偿，不取消
 * 手势。不能在 OnPositioned 回调里同步滚：补偿越出乐观平移时 LazyList
 * 会同步 forceRemeasure、移除挤出视口的 item 节点，而
 * OnPositionedDispatcher 正在遍历同一批 children → NPE（Issue #21）。
 * 高度未变时不读 `layoutInfo`，避免末条常驻 hold 每帧 measure。
 * 补偿只随自然高度变化发生；离底与手势期间不叠加高度动画。
 * 近底（≤ [followBottomPx]）时不补偿，保留原生钉底跟随。
 */
internal fun Modifier.holdReadingOnTailGrowth(
    state: LazyListState,
    lazyIndex: Int,
    followBottomPx: Int,
    itemKey: Any? = null,
    sendStage: ChatSendStageState? = null,
): Modifier = this then HoldReadingOnTailGrowthElement(state, lazyIndex, followBottomPx, itemKey, sendStage)

private data class HoldReadingOnTailGrowthElement(
    val state: LazyListState,
    val lazyIndex: Int,
    val followBottomPx: Int,
    val itemKey: Any? = null,
    val sendStage: ChatSendStageState? = null,
) : ModifierNodeElement<HoldReadingOnTailGrowthNode>() {
    override fun create() = HoldReadingOnTailGrowthNode(state, lazyIndex, followBottomPx, itemKey, sendStage)

    override fun update(node: HoldReadingOnTailGrowthNode) {
        node.state = state
        node.lazyIndex = lazyIndex
        node.followBottomPx = followBottomPx
        node.itemKey = itemKey
        node.sendStage = sendStage
    }
}

private class HoldReadingOnTailGrowthNode(
    var state: LazyListState,
    var lazyIndex: Int,
    var followBottomPx: Int,
    var itemKey: Any? = null,
    var sendStage: ChatSendStageState? = null,
) : Modifier.Node(), LayoutModifierNode, GlobalPositionAwareModifierNode {
    private var lastHeight = -1
    private var applyingHold = false
    private var pendingDelta = 0
    private var pendingIdleDelta = 0
    private var applyScheduled = false
    private var reconcileScheduled = false
    // LazyList publishes firstVisibleItemScrollOffset a frame late.
    // Subsequent measure passes that re-read state overwrite the in-flight
    // requestScrollToItem. Remember the last requested target on this node
    // and add the next delta to that, not to stale state.
    private var requestedIndex = -1
    private var requestedOffset = -1
    private var lastPublishedOffset = -1
    // requestScrollToItem flags isScrollInProgress. That is not a user
    // gesture: skipping idle hold for it drops the next height delta
    // (device fling generating −144px after the same idle path as drag).
    private var programmaticScroll = false
    private var stableIdleFrames = 0

    // LazyList 复用 slot 时上一 item 的高度不是本 item 的增高基线。
    override fun onReset() {
        lastHeight = -1
        pendingDelta = 0
        pendingIdleDelta = 0
        reconcileScheduled = false
        requestedIndex = -1
        requestedOffset = -1
        lastPublishedOffset = -1
        programmaticScroll = false
        stableIdleFrames = 0
    }

    override fun onDetach() {
        pendingDelta = 0
        applyScheduled = false
        reconcileScheduled = false
        requestedIndex = -1
        requestedOffset = -1
        lastPublishedOffset = -1
        programmaticScroll = false
        stableIdleFrames = 0
    }

    override fun MeasureScope.measure(
        measurable: Measurable,
        constraints: Constraints,
    ): MeasureResult {
        val placeable = measurable.measure(constraints)
        val height = placeable.height
        if (sendStage?.isActive == true) {
            // Single scroll owner: while send-stage owns the viewport, keep the height baseline
            // fresh so drag handoff starts cleanly, but never write scroll state.
            lastHeight = height
            pendingDelta = 0
            pendingIdleDelta = 0
            requestedIndex = -1
            requestedOffset = -1
            programmaticScroll = false
            stableIdleFrames = 0
            return layout(placeable.width, placeable.height) {
                placeable.place(0, 0)
            }
        }
        if (!state.isScrollInProgress) programmaticScroll = false
        val publishedIndex = state.firstVisibleItemIndex
        val publishedOffset = state.firstVisibleItemScrollOffset
        if (idleHoldShouldAdoptExternalScroll(
                requestedIndex = requestedIndex,
                requestedOffset = requestedOffset,
                firstVisibleIndex = publishedIndex,
                firstVisibleOffset = publishedOffset,
                lastSeenOffset = lastPublishedOffset,
                applyingHold = applyingHold,
            )
        ) {
            requestedOffset = publishedOffset
        }
        // Idle / our own requestScrollToItem: compensate in measure.
        // A real user drag/fling owns isScrollInProgress without
        // programmaticScroll; requestScrollToItem would cancel it.
        // Height-stable frames skip layoutInfo (short P95).
        if (userGestureOwnsScroll()) {
            requestedIndex = -1
            stableIdleFrames = 0
        } else if (idleHoldShouldReadLayout(lastHeight, height, pendingIdleDelta)) {
            stableIdleFrames = 0
            applyIdleHold(height)
        } else if (lastHeight < 0) {
            lastHeight = height
        } else {
            stableIdleFrames += 1
            if (idleHoldShouldDropSettledTarget(
                    lastHeight,
                    height,
                    pendingIdleDelta,
                    stableIdleFrames,
                    state.firstVisibleItemIndex,
                    state.firstVisibleItemScrollOffset,
                    requestedIndex,
                    requestedOffset,
                )
            ) {
                requestedIndex = -1
                requestedOffset = -1
            }
        }
        lastPublishedOffset = publishedOffset
        return layout(placeable.width, placeable.height) {
            placeable.place(0, 0)
        }
    }

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        if (sendStage?.isActive == true) {
            lastHeight = coordinates.size.height
            pendingDelta = 0
            requestedIndex = -1
            requestedOffset = -1
            programmaticScroll = false
            return
        }
        // Device production completion (392288d7): requestScrollToItem(2, 171)
        // during measure of the growing first-visible item did not stick
        // (scrollValue stayed 1105 while the token moved 132px). After
        // layout, dispatch the missed offset on the next frame. This is
        // late-frame compensation, not same-frame.
        if (!userGestureOwnsScroll() && requestedIndex >= 0 && !applyingHold) {
            reconcileIdleRequest()
        }
        if (applyingHold || programmaticScroll || !state.isScrollInProgress) return
        // Real gesture: drop the idle target so the next idle hold
        // starts from the post-gesture first-visible item, not the
        // pre-drag requestScrollToItem offset.
        requestedIndex = -1
        requestedOffset = -1
        programmaticScroll = false
        val height = coordinates.size.height
        val previous = lastHeight
        lastHeight = height
        if (previous < 0 || !shouldHold()) return
        val delta = height - previous
        if (delta == 0) return
        pendingDelta += delta
        if (applyScheduled) return
        applyScheduled = true
        coroutineScope.launch {
            applyScheduled = false
            val pending = pendingDelta
            pendingDelta = 0
            if (pending != 0 && shouldHold()) {
                state.dispatchRawDelta(pending.toFloat())
            }
        }
    }

    private fun reconcileIdleRequest() {
        if (requestedIndex < 0) return
        val publishedIndex = state.firstVisibleItemIndex
        val publishedOffset = state.firstVisibleItemScrollOffset
        if (publishedIndex != requestedIndex) return
        if (idleHoldShouldAdoptExternalScroll(
                requestedIndex = requestedIndex,
                requestedOffset = requestedOffset,
                firstVisibleIndex = publishedIndex,
                firstVisibleOffset = publishedOffset,
                lastSeenOffset = lastPublishedOffset,
                applyingHold = applyingHold,
            )
        ) {
            requestedOffset = publishedOffset
            lastPublishedOffset = publishedOffset
            return
        }
        val missed = requestedOffset - publishedOffset
        if (missed == 0 || reconcileScheduled) return
        reconcileScheduled = true
        coroutineScope.launch {
            try {
                // Round3 applied dispatchRawDelta in this coroutine without
                // waiting an extra vsync. withFrameNanos here sampled as a
                // 216px intermediate jump on the same fixture that is 0px
                // on b4477f42b. Keep the coroutine so we are not inside
                // OnPositionedDispatcher (Issue #21).
                if (sendStage?.isActive == true || userGestureOwnsScroll() || requestedIndex < 0) return@launch
                if (state.firstVisibleItemIndex != requestedIndex) return@launch
                val stillMissed = requestedOffset - state.firstVisibleItemScrollOffset
                if (stillMissed == 0) return@launch
                programmaticScroll = true
                state.dispatchRawDelta(stillMissed.toFloat())
            } finally {
                reconcileScheduled = false
            }
        }
    }

    private fun applyIdleHold(height: Int) {
        val tracing = BuildConfig.DEBUG && runCatching {
            Trace.beginSection("AmberTailIdleHold")
            true
        }.getOrDefault(false)
        try {
            applyIdleHoldBody(height)
        } finally {
            if (tracing) runCatching { Trace.endSection() }
        }
    }

    private fun applyIdleHoldBody(height: Int) {
        val previous = lastHeight
        val holding = shouldHold()
        if (previous < 0 || !holding) {
            lastHeight = height
            requestedIndex = -1
            requestedOffset = -1
            programmaticScroll = false
            return
        }
        val delta = height - previous
        lastHeight = height
        if (delta == 0 && pendingIdleDelta == 0) return
        if (userGestureOwnsScroll()) return
        if (applyingHold) {
            // requestScrollToItem remeasures this node. Fold the nested
            // height into a follow-up compensation instead of dropping it
            // by overwriting lastHeight.
            pendingIdleDelta += delta
            return
        }
        applyingHold = true
        var remaining = 0
        try {
            val visible = state.layoutInfo.visibleItemsInfo
            val anchor = readingAnchorFromLayout(
                visibleItems = visible,
                viewportStartOffset = state.layoutInfo.viewportStartOffset,
                publishedIndex = state.firstVisibleItemIndex,
                publishedOffset = state.firstVisibleItemScrollOffset,
                requestedIndex = requestedIndex,
                requestedOffset = requestedOffset,
            )
            var index = anchor.first
            var offset = anchor.second
            remaining = delta + pendingIdleDelta
            pendingIdleDelta = 0
            while (remaining != 0) {
                if (userGestureOwnsScroll()) break
                val vis = state.layoutInfo.visibleItemsInfo
                val sizes = vis.associate { it.index to it.size }
                val growingIndex = itemKey?.let { key ->
                    vis.firstOrNull { it.key == key }?.index
                } ?: lazyIndex
                val next = compensatedScrollTarget(
                    firstVisibleIndex = index,
                    firstVisibleOffset = offset,
                    delta = remaining,
                    itemSize = { itemIndex ->
                        if (itemIndex == growingIndex) lastHeight else sizes[itemIndex]
                    },
                )
                index = next.first
                offset = next.second
                requestedIndex = index
                requestedOffset = offset
                programmaticScroll = true
                state.requestScrollToItem(index, offset)
                remaining = pendingIdleDelta
                pendingIdleDelta = 0
            }
        } finally {
            applyingHold = false
            if (remaining != 0) pendingIdleDelta += remaining
        }
    }

    private fun userGestureOwnsScroll(): Boolean =
        userGestureOwnsScroll(state.isScrollInProgress, programmaticScroll)

    private fun shouldHold(): Boolean {
        if (sendStage?.isActive == true) return false
        val visible = state.layoutInfo.visibleItemsInfo
        val anchor = readingAnchorFromLayout(
            visibleItems = visible,
            viewportStartOffset = state.layoutInfo.viewportStartOffset,
            publishedIndex = state.firstVisibleItemIndex,
            publishedOffset = state.firstVisibleItemScrollOffset,
            requestedIndex = requestedIndex,
            requestedOffset = requestedOffset,
        )
        val growingVisible = itemKey?.let { key -> visible.any { it.key == key } }
            ?: (visible.any { it.index == lazyIndex } ||
                (requestedIndex >= 0 && visible.any { it.index == requestedIndex }))
        return shouldHoldReadingOnTailGrowth(
            firstIndex = anchor.first,
            firstOffset = anchor.second,
            followBottomPx = followBottomPx,
            growingItemVisible = growingVisible,
            sendStageActive = sendStage?.isActive == true,
        )
    }
}
