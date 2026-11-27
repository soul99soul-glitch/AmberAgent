package app.amber.feature.ui.pages.chat

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.amber.core.model.Conversation
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.uuid.Uuid
import kotlinx.coroutines.launch

@Immutable
data class SendStageIntent(
    val host: String, // "local" | "cloud"
    val conversationId: String,
    val sendRevision: Long,
    val messageId: Uuid,
)

@Stable
class ChatSendStageState {
    private var _pendingIntent by mutableStateOf<SendStageIntent?>(null)
    internal var admittingMessageId: Uuid? by mutableStateOf(null)
        private set
    internal var releasedMessageId: Uuid? by mutableStateOf(null)
        private set
    private var _activeIntent by mutableStateOf<SendStageIntent?>(null)
    private var _stagedMessageKey by mutableStateOf<Any?>(null)
    private var _isActive by mutableStateOf(false)
    private var _spacerPx by mutableIntStateOf(0)

    private var _viewportHeightPx: Int = 0
    private var _turnHeightPx: Int = 0
    private var _stagedItemSizePx: Int = 0
    private var _hasMeasuredTurn: Boolean = false
    private var _initialScrollPending: Boolean = false

    val isActive: Boolean
        get() = _isActive

    internal val pendingIntent: SendStageIntent?
        get() = _pendingIntent

    internal val activeIntent: SendStageIntent?
        get() = _activeIntent

    internal val stagedMessageKey: Any?
        get() = _stagedMessageKey

    internal val spacerPx: Int
        get() = _spacerPx

    internal val viewportHeightPx: Int
        get() = _viewportHeightPx

    internal val turnHeightPx: Int
        get() = _turnHeightPx

    internal val stagedItemSizePx: Int
        get() = _stagedItemSizePx

    internal val isPending: Boolean
        get() = _pendingIntent != null

    internal fun beginLocalAdmission(messageId: Uuid) {
        admittingMessageId = messageId
    }

    internal fun endLocalAdmission(messageId: Uuid) {
        if (admittingMessageId == messageId) admittingMessageId = null
    }

    fun onSubmitAccepted(intent: SendStageIntent) {
        releasedMessageId = null
        _isActive = false
        _activeIntent = null
        _stagedMessageKey = null
        _initialScrollPending = false
        _hasMeasuredTurn = false
        _turnHeightPx = 0
        _stagedItemSizePx = 0
        _spacerPx = 0
        _pendingIntent = intent
    }

    fun onUserNodeAppeared(messageKey: Any) {
        val pending = _pendingIntent ?: return
        _pendingIntent = null
        _activeIntent = pending
        _stagedMessageKey = messageKey
        _hasMeasuredTurn = false
        _turnHeightPx = 0
        _stagedItemSizePx = 0
        _initialScrollPending = true
        _isActive = true
    }

    fun onUserDrag() {
        (admittingMessageId ?: _pendingIntent?.messageId)?.let { releasedMessageId = it }
        admittingMessageId = null
        _pendingIntent = null
        _activeIntent = null
        _stagedMessageKey = null
        _initialScrollPending = false
        _isActive = false
        // Intentionally freeze _spacerPx on drag handoff so exiting the stage
        // does not collapse bottom space on the first drag frame; reset() clears it.
    }

    fun reset() {
        admittingMessageId = null
        releasedMessageId = null
        _pendingIntent = null
        _activeIntent = null
        _stagedMessageKey = null
        _initialScrollPending = false
        _isActive = false
        _hasMeasuredTurn = false
        _turnHeightPx = 0
        _stagedItemSizePx = 0
        _spacerPx = 0
    }

    internal fun consumeInitialScroll(): Boolean {
        if (!_isActive || !_initialScrollPending) return false
        _initialScrollPending = false
        return true
    }

    internal fun updateViewportHeight(viewportHeightPx: Int): Int {
        val clamped = viewportHeightPx.coerceAtLeast(0)
        _viewportHeightPx = clamped
        if (_isActive && _hasMeasuredTurn) {
            reconcileSpacerLocked()
        }
        return _spacerPx
    }

    internal fun updateTurnHeight(
        turnHeightPx: Int,
        stagedItemSizePx: Int? = null,
    ): Int {
        if (!_isActive) return _spacerPx
        _turnHeightPx = turnHeightPx.coerceAtLeast(0)
        if (stagedItemSizePx != null && stagedItemSizePx > 0) {
            _stagedItemSizePx = stagedItemSizePx
        }
        _hasMeasuredTurn = true
        reconcileSpacerLocked()
        return _spacerPx
    }

    internal fun onTurnExceededViewport() {
        if (!_isActive) return
        _spacerPx = 0
        _initialScrollPending = false
        _activeIntent = null
        _stagedMessageKey = null
        _isActive = false
    }

    private fun reconcileSpacerLocked() {
        if (_viewportHeightPx > 0 && _turnHeightPx >= _viewportHeightPx) {
            onTurnExceededViewport()
            return
        }
        val next = computeSendStageSpacerPx(
            viewportHeightPx = _viewportHeightPx,
            turnHeightPx = _turnHeightPx,
        )
        if (_spacerPx != next) {
            _spacerPx = next
        }
    }
}

internal val LocalChatSendStage = compositionLocalOf<ChatSendStageState?> { null }

internal fun computeSendStageSpacerPx(
    viewportHeightPx: Int,
    turnHeightPx: Int,
): Int {
    val h = viewportHeightPx.coerceAtLeast(0)
    val t = turnHeightPx.coerceAtLeast(0)
    return (h - t).coerceAtLeast(0)
}

internal fun matchesStagedMessageKey(itemKey: Any, stagedMessageKey: Any): Boolean {
    if (itemKey == stagedMessageKey) return true
    val prefix = "$stagedMessageKey:"
    return itemKey is String && itemKey.startsWith(prefix)
}

internal sealed interface TurnHeightMeasurement {
    data class Measured(
        val heightPx: Int,
        val stagedItemSizePx: Int? = null,
    ) : TurnHeightMeasurement
    data object Overflowed : TurnHeightMeasurement
    data object NotFound : TurnHeightMeasurement
}

internal fun measureTurnHeight(
    visibleItems: List<LazyListItemInfo>,
    firstVisibleItemIndex: Int,
    stagedMessageKey: Any?,
    stagedLazyTopIndex: Int?,
    viewportHeightPx: Int = 0,
    currentSpacerPx: Int = 0,
    lastStagedItemSizePx: Int = 0,
): TurnHeightMeasurement {
    if (stagedMessageKey == null || visibleItems.isEmpty()) {
        return TurnHeightMeasurement.NotFound
    }
    val bottomAnchorOffset = visibleItems
        .firstOrNull { it.index == 0 }
        ?.offset
        ?: 0
    val stagedTopItem = visibleItems
        .filter { matchesStagedMessageKey(it.key, stagedMessageKey) }
        .maxByOrNull { it.index }
    if (stagedTopItem != null) {
        val turnHeightPx = (stagedTopItem.offset + stagedTopItem.size - bottomAnchorOffset)
            .coerceAtLeast(0)
        return TurnHeightMeasurement.Measured(
            heightPx = turnHeightPx,
            stagedItemSizePx = stagedTopItem.size,
        )
    }
    val highestVisibleItem = visibleItems.maxByOrNull { it.index } ?: return TurnHeightMeasurement.NotFound
    if (firstVisibleItemIndex == 0 && stagedLazyTopIndex != null && stagedLazyTopIndex > highestVisibleItem.index) {
        val visibleSubTurnHeightPx = (highestVisibleItem.offset + highestVisibleItem.size - bottomAnchorOffset)
            .coerceAtLeast(0)
        // If currentSpacerPx > 0, a large single-frame chunk below the staged user message can
        // temporarily clip the user message above (H - spacer) even though total T < H.
        // Shrink the spacer using the observed sub-turn height + cached staged user height
        // before declaring true viewport overflow.
        if (currentSpacerPx > 0 && viewportHeightPx > 0) {
            val estimatedTurnHeightPx = visibleSubTurnHeightPx + lastStagedItemSizePx.coerceAtLeast(0)
            if (estimatedTurnHeightPx < viewportHeightPx) {
                return TurnHeightMeasurement.Measured(heightPx = estimatedTurnHeightPx)
            }
        }
        return TurnHeightMeasurement.Overflowed
    }
    return TurnHeightMeasurement.NotFound
}

internal fun findStagedLazyTopIndex(
    conversation: Conversation,
    timelinePlan: ChatTimelinePlan,
    stagedMessageKey: Any?,
): Int? {
    val key = stagedMessageKey ?: return null
    val messageIndex = conversation.messageNodes.indexOfLast { it.id == key }
    if (messageIndex < 0) return null
    return timelinePlan.lazyItemMessageIndexes
        .lastIndexOf(messageIndex)
        .takeIf { it >= 0 }
}

/**
 * Dynamic bottom content padding for the reverseLayout timeline. Reading
 * [ChatSendStageState.spacerPx] inside [calculateBottomPadding] keeps spacer
 * updates strictly inside LazyColumn's measure pass with zero recomposition.
 */
internal class SendStageContentPaddingValues(
    private val start: Dp,
    private val top: Dp,
    private val end: Dp,
    private val baseBottom: Dp,
    private val density: Density,
    private val sendStage: ChatSendStageState?,
) : PaddingValues {
    override fun calculateLeftPadding(layoutDirection: LayoutDirection): Dp =
        if (layoutDirection == LayoutDirection.Ltr) start else end

    override fun calculateTopPadding(): Dp = top

    override fun calculateRightPadding(layoutDirection: LayoutDirection): Dp =
        if (layoutDirection == LayoutDirection.Ltr) end else start

    override fun calculateBottomPadding(): Dp {
        val extraPx = sendStage?.spacerPx ?: 0
        if (extraPx <= 0) return baseBottom
        val extraDp = with(density) { extraPx.toDp() }
        return baseBottom + extraDp
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SendStageContentPaddingValues) return false
        return start == other.start &&
            top == other.top &&
            end == other.end &&
            baseBottom == other.baseBottom &&
            density == other.density &&
            sendStage === other.sendStage
    }

    override fun hashCode(): Int {
        var result = start.hashCode()
        result = 31 * result + top.hashCode()
        result = 31 * result + end.hashCode()
        result = 31 * result + baseBottom.hashCode()
        result = 31 * result + density.hashCode()
        result = 31 * result + System.identityHashCode(sendStage)
        return result
    }
}

internal fun Modifier.chatSendStageLayout(
    listState: LazyListState,
    sendStage: ChatSendStageState?,
    topPaddingPx: Int,
    baseBottomPaddingPx: Int,
    stagedLazyTopIndex: Int?,
): Modifier = if (sendStage == null) {
    this
} else {
    this then ChatSendStageLayoutElement(
        listState = listState,
        sendStage = sendStage,
        topPaddingPx = topPaddingPx,
        baseBottomPaddingPx = baseBottomPaddingPx,
        stagedLazyTopIndex = stagedLazyTopIndex,
    )
}

private data class ChatSendStageLayoutElement(
    val listState: LazyListState,
    val sendStage: ChatSendStageState,
    val topPaddingPx: Int,
    val baseBottomPaddingPx: Int,
    val stagedLazyTopIndex: Int?,
) : ModifierNodeElement<ChatSendStageLayoutNode>() {
    override fun create() = ChatSendStageLayoutNode(
        listState = listState,
        sendStage = sendStage,
        topPaddingPx = topPaddingPx,
        baseBottomPaddingPx = baseBottomPaddingPx,
        stagedLazyTopIndex = stagedLazyTopIndex,
    )

    override fun update(node: ChatSendStageLayoutNode) {
        node.listState = listState
        node.sendStage = sendStage
        node.topPaddingPx = topPaddingPx
        node.baseBottomPaddingPx = baseBottomPaddingPx
        node.stagedLazyTopIndex = stagedLazyTopIndex
    }
}

private class ChatSendStageLayoutNode(
    var listState: LazyListState,
    var sendStage: ChatSendStageState,
    var topPaddingPx: Int,
    var baseBottomPaddingPx: Int,
    var stagedLazyTopIndex: Int?,
) : Modifier.Node(), LayoutModifierNode {
    override fun onAttach() {
        coroutineScope.launch {
            snapshotFlow {
                val stage = sendStage
                if (!stage.isActive) return@snapshotFlow null
                val layoutInfo = listState.layoutInfo
                measureTurnHeight(
                    visibleItems = layoutInfo.visibleItemsInfo,
                    firstVisibleItemIndex = listState.firstVisibleItemIndex,
                    stagedMessageKey = stage.stagedMessageKey,
                    stagedLazyTopIndex = stagedLazyTopIndex,
                    viewportHeightPx = stage.viewportHeightPx,
                    currentSpacerPx = stage.spacerPx,
                    lastStagedItemSizePx = stage.stagedItemSizePx,
                )
            }
                .distinctUntilChanged()
                .collect { measurement ->
                    when (measurement) {
                        is TurnHeightMeasurement.Measured ->
                            sendStage.updateTurnHeight(measurement.heightPx, measurement.stagedItemSizePx)
                        TurnHeightMeasurement.Overflowed -> sendStage.onTurnExceededViewport()
                        TurnHeightMeasurement.NotFound, null -> Unit
                    }
                }
        }
    }

    override fun MeasureScope.measure(
        measurable: Measurable,
        constraints: Constraints,
    ): MeasureResult {
        // Pre-measure: update readable viewport height H so IME inset animation frames
        // resize the spacer synchronously in the exact same LazyColumn measure pass.
        if (constraints.hasBoundedHeight) {
            val readableHeightPx = (constraints.maxHeight - topPaddingPx - baseBottomPaddingPx)
                .coerceAtLeast(0)
            sendStage.updateViewportHeight(readableHeightPx)
        }
        val placeable = measurable.measure(constraints)
        if (!constraints.hasBoundedHeight) {
            val readableHeightPx = (placeable.height - topPaddingPx - baseBottomPaddingPx)
                .coerceAtLeast(0)
            sendStage.updateViewportHeight(readableHeightPx)
        }
        if (sendStage.isActive) {
            // Read layoutInfo outside snapshot observation so wrapping LazyColumn never
            // self-subscribes to the MutableState LazyColumn writes on every measure pass.
            val measurement = Snapshot.withoutReadObservation {
                val layoutInfo = listState.layoutInfo
                measureTurnHeight(
                    visibleItems = layoutInfo.visibleItemsInfo,
                    firstVisibleItemIndex = listState.firstVisibleItemIndex,
                    stagedMessageKey = sendStage.stagedMessageKey,
                    stagedLazyTopIndex = stagedLazyTopIndex,
                    viewportHeightPx = sendStage.viewportHeightPx,
                    currentSpacerPx = sendStage.spacerPx,
                    lastStagedItemSizePx = sendStage.stagedItemSizePx,
                )
            }
            when (measurement) {
                is TurnHeightMeasurement.Measured ->
                    sendStage.updateTurnHeight(measurement.heightPx, measurement.stagedItemSizePx)
                TurnHeightMeasurement.Overflowed -> sendStage.onTurnExceededViewport()
                TurnHeightMeasurement.NotFound -> Unit
            }
        }
        return layout(placeable.width, placeable.height) {
            placeable.place(0, 0)
        }
    }
}
