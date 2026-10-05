package app.amber.feature.ui.pages.chat

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import kotlinx.coroutines.launch

/**
 * 离底阅读时钉住正在流式增高的尾消息上方的已读内容。
 *
 * reverseLayout 下 LazyList 保持最底可见 item 的底边不动：尾消息（或它下方的
 * 尾部 item）作锚时，每次增高 ΔH 都会把已读内容顶上去 ΔH——上滑后仍被持续带走。
 * 这里在布局完成后（onGloballyPositioned）记下 ΔH，分发结束后反向滚动 ΔH：
 * dispatchRawDelta 不经 scroll mutex，不取消进行中的拖动/惯性。不能在回调里直接滚：
 * 补偿越出乐观平移时 LazyList 会同步 forceRemeasure、移除挤出视口的 item 节点，
 * 而 OnPositionedDispatcher 正在遍历同一批 children → NPE（Issue #21）。
 * 代价是补偿滞后一帧；animateContentSize 下每帧 ΔH 很小。
 * 近底（≤ [followBottomPx]）时不补偿，保留原生钉底跟随。
 */
internal fun Modifier.holdReadingOnTailGrowth(
    state: LazyListState,
    lazyIndex: Int,
    followBottomPx: Int,
): Modifier = this then HoldReadingOnTailGrowthElement(state, lazyIndex, followBottomPx)

private data class HoldReadingOnTailGrowthElement(
    val state: LazyListState,
    val lazyIndex: Int,
    val followBottomPx: Int,
) : ModifierNodeElement<HoldReadingOnTailGrowthNode>() {
    override fun create() = HoldReadingOnTailGrowthNode(state, lazyIndex, followBottomPx)

    override fun update(node: HoldReadingOnTailGrowthNode) {
        node.state = state
        node.lazyIndex = lazyIndex
        node.followBottomPx = followBottomPx
    }
}

private class HoldReadingOnTailGrowthNode(
    var state: LazyListState,
    var lazyIndex: Int,
    var followBottomPx: Int,
) : Modifier.Node(), GlobalPositionAwareModifierNode {
    private var lastHeight = -1
    private var pendingDelta = 0
    private var applyScheduled = false

    // LazyList 复用 slot 时上一 item 的高度不是本 item 的增高基线。
    override fun onReset() {
        lastHeight = -1
        pendingDelta = 0
    }

    // 节点 detach 会取消 coroutineScope，未执行的补偿随之作废。
    override fun onDetach() {
        pendingDelta = 0
        applyScheduled = false
    }

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        val height = coordinates.size.height
        val previous = lastHeight
        lastHeight = height
        if (previous < 0) return
        val nearBottom = state.firstVisibleItemIndex == 0 &&
            state.firstVisibleItemScrollOffset <= followBottomPx
        // 增长 item 已在锚点之下时，按 key 锚定本就不会牵动可见内容。
        if (nearBottom || lazyIndex < state.firstVisibleItemIndex) return
        val delta = height - previous
        if (delta == 0) return
        pendingDelta += delta
        if (applyScheduled) return
        applyScheduled = true
        coroutineScope.launch {
            applyScheduled = false
            val pending = pendingDelta
            pendingDelta = 0
            if (pending != 0) state.dispatchRawDelta(pending.toFloat())
        }
    }
}
