package app.amber.feature.ui.pages.sessionhome

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.AnchoredDraggableDefaults
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.amber.agent.R
import app.amber.core.model.Conversation
import app.amber.feature.ui.components.ds.AmberContinuousShape
import app.amber.feature.ui.components.ds.AmberDepthStyle
import app.amber.feature.ui.components.ds.AmberFeedback
import app.amber.feature.ui.components.ds.amberPressHighlight
import app.amber.feature.ui.components.ds.amberRim
import app.amber.feature.ui.components.ds.amberShadow
import app.amber.feature.ui.theme.JetBrainsMonoFamily
import app.amber.feature.ui.theme.LocalAmberTokens
import app.amber.feature.ui.theme.LocalThemePack
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Pin
import com.composables.icons.lucide.Trash2
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Delete settle/collapse. M3 SwipeToDismissBox uses default `spring()`
 * (stiffness 1500, ~100ms); that's the snappy leave this replaces.
 */
internal object HomeSessionSwipeMotion {
    const val CollapseMs = 280
    const val FadeMs = 220
    val SnapSpec = spring<Float>(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessLow,
    )
    val EaseOut = AmberFeedback.EaseOut
}

private enum class HomeSessionSwipeValue { Settled, Pin, Delete }

private class HomeSessionPinGesture {
    var settling = false
    var handled = false
}

/**
 * 会话行：标题 + 最后消息预览 +（消息数 / 时间）。
 * 向结束方向滑动置顶，向开始方向滑动删除（RTL 镜像）。删除松手后沿手势方向离开并收起。
 */
@Composable
internal fun HomeSessionRow(
    conversation: Conversation,
    tileColor: Color,
    isLastVisited: Boolean,
    isFirst: Boolean,
    isLast: Boolean,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
    onTogglePin: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalAmberTokens.current
    val density = LocalDensity.current
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val cardRadius = LocalThemePack.current?.design?.components?.cardRadius?.toFloat()?.dp ?: 22.dp
    val newMessageLabel = stringResource(R.string.chat_page_new_message)
    val deleteLabel = stringResource(R.string.delete)
    val pinLabel = stringResource(R.string.history_page_pin)
    val unpinLabel = stringResource(R.string.history_page_unpin)
    val rowInteraction = remember { MutableInteractionSource() }
    val swipeInteraction = remember { MutableInteractionSource() }
    val isDragging by swipeInteraction.collectIsDraggedAsState()
    val latestOnDelete = rememberUpdatedState(onDelete)
    val latestOnTogglePin = rememberUpdatedState(onTogglePin)
    val pinGesture = remember { HomeSessionPinGesture() }

    @Suppress("DEPRECATION")
    val swipeState = remember {
        // Pin is a drag target, not a settled state: vetoing the anchor keeps
        // the row from racing a list reorder the way reset()-after-settle did.
        AnchoredDraggableState(
            initialValue = HomeSessionSwipeValue.Settled,
            confirmValueChange = { value ->
                if (value == HomeSessionSwipeValue.Pin) {
                    // Anchor confirmation can be queried repeatedly by one fling.
                    // Toggle only once after release, never during the drag itself.
                    if (pinGesture.settling && !pinGesture.handled) {
                        pinGesture.handled = true
                        latestOnTogglePin.value()
                    }
                    false
                } else {
                    true
                }
            },
        )
    }
    val visibleState = remember { MutableTransitionState(true) }
    val anchorFling = AnchoredDraggableDefaults.flingBehavior(
        state = swipeState,
        positionalThreshold = { with(density) { 56.dp.toPx() } },
        animationSpec = HomeSessionSwipeMotion.SnapSpec,
    )
    val swipeFling = remember(anchorFling) {
        object : FlingBehavior {
            override suspend fun ScrollScope.performFling(initialVelocity: Float): Float {
                pinGesture.settling = true
                pinGesture.handled = false
                try {
                    return with(anchorFling) { performFling(initialVelocity) }
                } finally {
                    pinGesture.settling = false
                }
            }
        }
    }

    // 只在松手后、已经对准 Delete 锚点时才收起。拖过阈值但手指还在时保持 1:1 跟随。
    LaunchedEffect(isDragging, swipeState.targetValue) {
        if (!isDragging && swipeState.targetValue == HomeSessionSwipeValue.Delete) {
            if (visibleState.targetState) {
                visibleState.targetState = false
                // Send exactly once at release. Disposal/cancellation must never
                // enqueue a fresh deletion after undo or search replacement.
                latestOnDelete.value()
            }
        }
    }

    // 与顶部入口卡同一套连续曲率圆角，列表整体读作一张卡。
    val rowShape = remember(isFirst, isLast, cardRadius) {
        AmberContinuousShape(
            topStart = if (isFirst) cardRadius else 0.dp,
            topEnd = if (isFirst) cardRadius else 0.dp,
            bottomEnd = if (isLast) cardRadius else 0.dp,
            bottomStart = if (isLast) cardRadius else 0.dp,
        )
    }
    val rowSurface = if (isLastVisited) {
        tokens.accent.copy(alpha = if (tokens.isDark) 0.16f else 0.08f)
            .compositeOver(tokens.surface)
    } else {
        tokens.surface
    }
    val offsetX = swipeState.offset.let { if (it.isNaN()) 0f else it }
    // Anchors are logical start/end coordinates because RTL reverses the drag
    // input. graphicsLayer uses physical coordinates, so mirror its output too.
    val physicalOffsetX = if (isRtl) -offsetX else offsetX
    val isDeleteReveal = offsetX < 0f

    AnimatedVisibility(
        visibleState = visibleState,
        modifier = modifier.fillMaxWidth(),
        enter = EnterTransition.None,
        exit = fadeOut(
            animationSpec = tween(
                durationMillis = HomeSessionSwipeMotion.FadeMs,
                easing = HomeSessionSwipeMotion.EaseOut,
            ),
        ) + shrinkVertically(
            animationSpec = tween(
                durationMillis = HomeSessionSwipeMotion.CollapseMs,
                easing = HomeSessionSwipeMotion.EaseOut,
            ),
            shrinkTowards = Alignment.Top,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .amberShadow(
                    rowShape,
                    AmberDepthStyle.Card,
                    drawTop = isFirst,
                    drawBottom = isLast,
                    opaqueContent = rowSurface.alpha >= 1f,
                )
                .clip(rowShape)
                .amberRim(rowShape, AmberDepthStyle.Card, drawTop = isFirst, drawBottom = isLast)
                .onSizeChanged { size ->
                    if (size.width <= 0) return@onSizeChanged
                    val width = size.width.toFloat()
                    swipeState.updateAnchors(
                        DraggableAnchors {
                            HomeSessionSwipeValue.Pin at width
                            HomeSessionSwipeValue.Settled at 0f
                            HomeSessionSwipeValue.Delete at -width
                        },
                    )
                }
                .anchoredDraggable(
                    state = swipeState,
                    reverseDirection = isRtl,
                    orientation = Orientation.Horizontal,
                    enabled = visibleState.targetState,
                    interactionSource = swipeInteraction,
                    flingBehavior = swipeFling,
                ),
        ) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(if (isDeleteReveal) tokens.accent else tokens.surface2)
                    .padding(horizontal = 20.dp),
                contentAlignment = if (isDeleteReveal) Alignment.CenterEnd else Alignment.CenterStart,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        imageVector = if (isDeleteReveal) Lucide.Trash2 else Lucide.Pin,
                        contentDescription = if (isDeleteReveal) {
                            deleteLabel
                        } else {
                            if (conversation.isPinned) unpinLabel else pinLabel
                        },
                        modifier = Modifier.size(17.dp),
                        tint = if (isDeleteReveal) tokens.accentInk else tokens.accent,
                    )
                    Text(
                        text = when {
                            isDeleteReveal -> deleteLabel
                            conversation.isPinned -> unpinLabel
                            else -> pinLabel
                        },
                        fontFamily = homeChromeFontFamily(JetBrainsMonoFamily),
                        fontSize = 10.sp,
                        letterSpacing = 0.4.sp,
                        color = if (isDeleteReveal) tokens.accentInk else tokens.accent,
                    )
                }
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer { translationX = physicalOffsetX }
                    .background(rowSurface),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .amberPressHighlight(rowInteraction, rowShape, scaleOnPress = false)
                        .background(rowSurface)
                        .clickable(
                            interactionSource = rowInteraction,
                            indication = null,
                            enabled = visibleState.targetState,
                            onClick = onOpen,
                        )
                        .padding(horizontal = 14.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(tileColor.copy(alpha = 0.13f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        HomeSessionGlyph(
                            title = conversation.title,
                            modifier = Modifier.size(18.dp),
                            tint = tileColor,
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            text = conversation.title.ifBlank { newMessageLabel },
                            fontSize = 13.5.sp,
                            lineHeight = 18.sp,
                            fontWeight = FontWeight.Medium,
                            letterSpacing = (-0.2).sp,
                            color = tokens.ink,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                text = sessionTimeLabel(conversation.updateAt),
                                fontFamily = homeChromeFontFamily(JetBrainsMonoFamily),
                                fontSize = 11.sp,
                                lineHeight = 14.sp,
                                fontWeight = FontWeight.Normal,
                                color = tokens.ink3,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = "·",
                                fontFamily = homeChromeFontFamily(JetBrainsMonoFamily),
                                fontSize = 11.sp,
                                lineHeight = 14.sp,
                                color = tokens.ink4,
                            )
                            SessionCountBadge(count = conversation.messageCount)
                        }
                    }
                    if (conversation.isPinned) {
                        Spacer(Modifier.width(12.dp))
                        Icon(
                            imageVector = Lucide.Pin,
                            contentDescription = pinLabel,
                            modifier = Modifier.size(14.dp),
                            tint = tileColor,
                        )
                    }
                }
                if (!isLast) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 60.dp, end = 14.dp)
                            .height(0.5.dp)
                            .background(tokens.line.copy(alpha = 0.46f)),
                    )
                }
            }
        }
    }
}

/** 消息数与时间保持相同的字号、字重和灰度。 */
@Composable
private fun SessionCountBadge(count: Int) {
    val tokens = LocalAmberTokens.current
    Text(
        text = stringResource(R.string.session_home_message_count, if (count > 99) "99+" else count.toString()),
        fontFamily = homeChromeFontFamily(JetBrainsMonoFamily),
        fontSize = 11.sp,
        lineHeight = 14.sp,
        fontWeight = FontWeight.Normal,
        color = tokens.ink3,
        maxLines = 1,
    )
}

internal fun homeConversationTileColor(
    tokens: app.amber.feature.ui.theme.AmberTokens,
    isLastVisited: Boolean,
): Color {
    return if (isLastVisited) tokens.accent else tokens.ink3
}

/** 相对时间：今天 → HH:mm，昨天 → 昨天，更早 → M月d日（跨年带年份）。 */
@Composable
private fun sessionTimeLabel(instant: Instant): String {
    val zone = ZoneId.systemDefault()
    val dateTime = instant.atZone(zone)
    val today = LocalDate.now(zone)
    val date = dateTime.toLocalDate()
    return when {
        date == today -> String.format("%02d:%02d", dateTime.hour, dateTime.minute)
        date == today.minusDays(1) -> stringResource(R.string.chat_page_yesterday)
        date.year == today.year -> stringResource(
            R.string.session_home_date_month_day,
            date.monthValue,
            date.dayOfMonth,
        )
        else -> stringResource(
            R.string.session_home_date_year_month_day,
            date.year,
            date.monthValue,
            date.dayOfMonth,
        )
    }
}
