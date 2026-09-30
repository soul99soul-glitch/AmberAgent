package app.amber.feature.ui.pages.chat

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import app.amber.core.recap.RecapFailure
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.amber.agent.R
import app.amber.core.recap.ConversationRecap
import app.amber.core.recap.RecapFreshness
import app.amber.core.recap.RecapNode
import app.amber.core.recap.RecapNodeKind
import app.amber.core.recap.RecapState
import app.amber.feature.ui.theme.LocalAmberTokens
import app.amber.feature.ui.theme.LocalAmberType
import com.composables.icons.lucide.AppWindow
import com.composables.icons.lucide.File
import com.composables.icons.lucide.Globe
import com.composables.icons.lucide.Image
import com.composables.icons.lucide.Layers
import com.composables.icons.lucide.Lucide
import kotlin.uuid.Uuid
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val PanelEnterEasing = CubicBezierEasing(0.2f, 0.85f, 0.25f, 1f)
private const val PANEL_ENTER_MS = 300
private const val PANEL_EXIT_MS = 220

// ── Timeline highlight ────────────────────────────────────────────────────────

/** Briefly tints the message a recap node or shelf item jumped to. */
@Stable
class TimelineHighlightState {
    var nodeId by mutableStateOf<Uuid?>(null)
        private set
    val alpha = Animatable(0f)

    private var generation = 0

    suspend fun flash(target: Uuid) {
        val mine = ++generation
        nodeId = target
        alpha.snapTo(0f)
        alpha.animateTo(1f, tween(180))
        delay(900)
        // A newer flash owns the animation now; do not fade it out from here.
        if (mine != generation) return
        alpha.animateTo(0f, tween(700))
        if (mine == generation) nodeId = null
    }
}

/** A message the recap or the shelf asked the timeline to reveal. */
@Immutable
data class TimelineJumpTarget(
    val nodeId: Uuid,
    /** Selected message id when the jump was offered; a switched variant cancels it. */
    val messageId: String?,
)

/**
 * Scrolls a message into view in the reverse-layout timeline. Short messages sit a third
 * up the viewport; a message taller than the viewport is aligned by its top slice so the
 * reader lands on its beginning rather than its end.
 */
suspend fun LazyListState.revealMessage(bottomIndex: Int, topIndex: Int) {
    val viewport = layoutInfo.viewportSize.height
    val margin = (viewport * 0.08f).toInt()
    animateScrollToItem(bottomIndex, scrollOffset = -(viewport / 3))
    val top = layoutInfo.visibleItemsInfo.firstOrNull { it.index == topIndex }
    // reverseLayout: an item's top edge sits at offset + size from the bottom edge.
    if (top != null && top.offset + top.size <= viewport - margin) return
    if (top == null) scrollToItem(topIndex)
    val size = layoutInfo.visibleItemsInfo.firstOrNull { it.index == topIndex }?.size ?: return
    animateScrollToItem(topIndex, scrollOffset = size - (viewport - margin))
}

val LocalTimelineHighlight = staticCompositionLocalOf<TimelineHighlightState?> { null }

/**
 * Draw-phase only: reading the state here never recomposes the timeline item. A long
 * message is split into stacked [slice]s; those tint edge to edge so the bands join.
 */
@Composable
fun Modifier.timelineHighlight(nodeId: Uuid, slice: Boolean = false): Modifier {
    val state = LocalTimelineHighlight.current ?: return this
    val color = LocalChatTheme.current.accent
    return drawBehind {
        if (state.nodeId == nodeId) {
            val a = state.alpha.value
            if (a > 0f) {
                // Inflate past the bubble so its own rounded corners sit inside the tint.
                val dx = 8.dp.toPx()
                val dy = if (slice) 0f else 6.dp.toPx()
                drawRoundRect(
                    color = color.copy(alpha = 0.12f * a),
                    topLeft = Offset(-dx, -dy),
                    size = Size(size.width + dx * 2, size.height + dy * 2),
                    cornerRadius = CornerRadius(if (slice) 0f else 20.dp.toPx()),
                )
            }
        }
    }
}

// ── Island ────────────────────────────────────────────────────────────────────

/**
 * The status island in the header. Generating: shows the live status, tap jumps to
 * the newest content, long-press stops. Idle: shows the title and opens the recap;
 * below the recap threshold it bulges and shows a short hint instead.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatIsland(
    title: String,
    generating: Boolean,
    status: String?,
    recapEligible: Boolean,
    recapOpen: Boolean,
    enabled: Boolean,
    onOpenRecap: () -> Unit,
    onJumpToLive: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalAmberTokens.current
    val theme = LocalChatTheme.current
    val type = LocalAmberType.current
    val scope = rememberCoroutineScope()
    val bulge = remember { Animatable(1f) }
    var hintKey by remember { mutableIntStateOf(0) }
    var showHint by remember { mutableStateOf(false) }
    LaunchedEffect(hintKey) {
        if (hintKey > 0) {
            showHint = true
            delay(1800)
            showHint = false
        }
    }
    val generatingLabel = stringResource(R.string.chat_island_generating)
    val hint = stringResource(R.string.chat_island_recap_hint)
    val text = when {
        generating -> status?.takeIf { it.isNotBlank() } ?: generatingLabel
        showHint -> hint
        else -> title
    }
    val a11y = when {
        generating -> stringResource(R.string.chat_island_a11y_generating, text)
        recapEligible -> stringResource(R.string.chat_island_a11y_recap, title)
        else -> title
    }
    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = bulge.value
                scaleY = bulge.value
            }
            .widthIn(min = 72.dp, max = 260.dp)
            .heightIn(min = 32.dp)
            .clip(CircleShape)
            .background(
                when {
                    recapOpen -> theme.accentTint
                    enabled -> tokens.surface2
                    else -> Color.Transparent
                }
            )
            // surface2 is ~1:1 against the light canvas; the hairline keeps the pill legible.
            .border(1.dp, if (enabled && !recapOpen) tokens.line2 else Color.Transparent, CircleShape)
            .combinedClickable(
                enabled = enabled,
                role = Role.Button,
                onClick = {
                    when {
                        generating -> onJumpToLive()
                        recapEligible -> onOpenRecap()
                        else -> {
                            hintKey += 1
                            scope.launch {
                                bulge.animateTo(1.07f, spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMedium))
                                bulge.animateTo(1f, spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMediumLow))
                            }
                        }
                    }
                },
                onLongClick = if (generating) onStop else null,
            )
            .semantics { contentDescription = a11y }
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            if (generating) {
                val pulse = rememberInfiniteTransition(label = "islandPulse")
                val dotAlpha by pulse.animateFloat(
                    initialValue = 0.35f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
                    label = "islandDot",
                )
                Box(
                    Modifier
                        .size(6.dp)
                        .graphicsLayer { alpha = dotAlpha }
                        .clip(CircleShape)
                        .background(theme.accent)
                )
            }
            Text(
                text = text,
                style = type.sessionTitle.copy(fontSize = 14.sp, lineHeight = 18.sp),
                color = if ((showHint && !generating) || !enabled) tokens.ink2 else tokens.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Header shelf button; replaces the context ring. */
@Composable
fun ChatShelfButton(
    open: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalAmberTokens.current
    val theme = LocalChatTheme.current
    val label = stringResource(R.string.chat_shelf)
    Box(
        modifier = modifier
            .size(48.dp)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(if (open) theme.accentTint else Color.Transparent),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Lucide.Layers,
                contentDescription = null,
                tint = if (open) theme.accent else tokens.ink,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

// ── Panel shell ───────────────────────────────────────────────────────────────

/**
 * Arrowless panel under the header, shared by the recap and the shelf. Rendered in the
 * content area (below the header), so the header stays tappable while it is open; a
 * transparent scrim over the timeline closes it.
 */
@Composable
fun ChatTopPanel(
    visible: Boolean,
    onDismiss: () -> Unit,
    transformOriginX: Float,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val state = remember { MutableTransitionState(false) }
    state.targetState = visible
    if (!state.currentState && !state.targetState && state.isIdle) return
    val theme = LocalChatTheme.current
    // Theme packs may give popovers a translucent fill; the panel sits over live text, so
    // flatten it onto an opaque surface.
    val popoverBg = (if (theme.popoverBg != Color.Unspecified) theme.popoverBg else theme.surface)
        .compositeOver(theme.surface.copy(alpha = 1f))
    val shadow = if (theme.isDark) Color.Black.copy(alpha = 0.22f) else theme.ink.copy(alpha = 0.14f)
    val panelShape = MaterialTheme.shapes.extraLarge
    val screenCap = (LocalConfiguration.current.screenHeightDp * 0.62f).dp
    BackHandler(enabled = visible, onBack = onDismiss)
    // The caller insets this box by the header and the composer, so the panel never
    // slides under the bottom bar (landscape, keyboard open).
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val maxPanelHeight = minOf(screenCap, maxHeight - PANEL_TOP_GAP - PANEL_BOTTOM_GAP)
        if (visible) {
            Box(
                Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss,
                    )
            )
        }
        AnimatedVisibility(
            visibleState = state,
            enter = fadeIn(tween(PANEL_ENTER_MS, easing = PanelEnterEasing)) +
                scaleIn(
                    tween(PANEL_ENTER_MS, easing = PanelEnterEasing),
                    initialScale = 0.9f,
                    transformOrigin = TransformOrigin(transformOriginX, 0f),
                ),
            exit = fadeOut(tween(PANEL_EXIT_MS, easing = LinearOutSlowInEasing)) +
                scaleOut(
                    tween(PANEL_EXIT_MS, easing = FastOutSlowInEasing),
                    targetScale = 0.96f,
                    transformOrigin = TransformOrigin(transformOriginX, 0f),
                ),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(start = PANEL_SIDE_GAP, end = PANEL_SIDE_GAP, top = PANEL_TOP_GAP),
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxPanelHeight)
                    .shadow(12.dp, panelShape, clip = false, ambientColor = shadow, spotColor = shadow),
                shape = panelShape,
                color = popoverBg,
                border = BorderStroke(1.dp, theme.surfaceEdge),
            ) {
                val scroll = rememberScrollState()
                Box {
                    // The scroll container spans the panel; only the content is inset.
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(scroll),
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
                            content = content,
                        )
                    }
                    if (scroll.canScrollForward) {
                        // Hints that more content continues below the cut.
                        Box(
                            Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .height(24.dp)
                                .background(
                                    Brush.verticalGradient(listOf(popoverBg.copy(alpha = 0f), popoverBg))
                                )
                        )
                    }
                }
            }
        }
    }
}

private val PANEL_TOP_GAP = 6.dp
// Matches the composer and suggestion chips below, so the panel sits inside the page gutter.
private val PANEL_SIDE_GAP = 16.dp
private val PANEL_BOTTOM_GAP = 8.dp

// ── Recap ─────────────────────────────────────────────────────────────────────

@Composable
fun ColumnScope.ChatRecapPanelContent(
    title: String,
    state: RecapState,
    freshness: RecapFreshness?,
    isJumpable: (RecapNode) -> Boolean,
    onRename: () -> Unit,
    onRefresh: () -> Unit,
    onJump: (RecapNode) -> Unit,
    onUseNextStep: (String) -> Unit,
) {
    val tokens = LocalAmberTokens.current
    val theme = LocalChatTheme.current
    val type = LocalAmberType.current
    val recap = state.recap
    val renameLabel = stringResource(R.string.chat_recap_rename_title)
    Text(
        text = title,
        style = type.screenTitle,
        color = tokens.ink,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(role = Role.Button, onClick = onRename)
            .semantics { contentDescription = "$renameLabel: $title" }
            .padding(vertical = 4.dp),
    )
    if (recap != null && freshness == RecapFreshness.STALE && !state.generating && state.failure == null) {
        Text(
            text = stringResource(R.string.chat_recap_stale),
            style = type.meta.copy(fontSize = 12.sp, fontWeight = FontWeight.Medium),
            color = theme.accent,
            modifier = Modifier
                .padding(top = 2.dp)
                .clip(RoundedCornerShape(8.dp))
                .clickable(role = Role.Button, onClick = onRefresh)
                .padding(vertical = 6.dp),
        )
    }
    Spacer(Modifier.height(8.dp))
    when {
        recap == null && state.ineligible -> RecapNoteRow(stringResource(R.string.chat_island_recap_hint))
        recap == null && state.failure != null -> RecapFailedRow(state.failure, onRetry = onRefresh)
        recap == null -> RecapLoadingRow()
        else -> {
            if (state.generating) RecapLoadingRow()
            state.failure?.let { RecapFailedRow(it, onRetry = onRefresh) }
            RecapBody(recap, isJumpable, onJump, onUseNextStep)
        }
    }
}

@Composable
private fun RecapLoadingRow(showLabel: Boolean = true) {
    val theme = LocalChatTheme.current
    Row(
        modifier = Modifier.padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        CircularProgressIndicator(
            color = theme.accent,
            trackColor = theme.accent.copy(alpha = 0.16f),
            strokeWidth = 2.dp,
            modifier = Modifier.size(16.dp),
        )
        if (showLabel) {
            Text(
                text = stringResource(R.string.chat_recap_loading),
                style = LocalAmberType.current.secondary,
                color = LocalAmberTokens.current.ink2,
            )
        }
    }
}

@Composable
private fun RecapNoteRow(text: String) {
    Text(
        text = text,
        style = LocalAmberType.current.secondary,
        color = LocalAmberTokens.current.ink2,
        modifier = Modifier.padding(vertical = 8.dp),
    )
}

@Composable
private fun RecapFailedRow(failure: RecapFailure, onRetry: () -> Unit) {
    val theme = LocalChatTheme.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(
                if (failure == RecapFailure.NO_MODEL) R.string.chat_recap_no_model else R.string.chat_recap_failed
            ),
            style = LocalAmberType.current.secondary,
            color = LocalAmberTokens.current.ink2,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onRetry) {
            Text(stringResource(R.string.chat_recap_retry), color = theme.accent)
        }
    }
}

@Composable
private fun RecapBody(
    recap: ConversationRecap,
    isJumpable: (RecapNode) -> Boolean,
    onJump: (RecapNode) -> Unit,
    onUseNextStep: (String) -> Unit,
) {
    val tokens = LocalAmberTokens.current
    val type = LocalAmberType.current
    Text(
        text = recap.overview,
        style = type.body.copy(fontSize = 15.sp, lineHeight = 23.sp),
        color = tokens.ink,
    )
    if (recap.nodes.isNotEmpty()) {
        RecapSectionLabel(stringResource(R.string.chat_recap_nodes))
        recap.nodes.forEach { node ->
            val jumpable = isJumpable(node)
            RecapNodeRow(node = node, enabled = jumpable, onClick = { onJump(node) })
        }
    }
    if (recap.nextSteps.isNotEmpty()) {
        // Node rows already end with their own vertical padding.
        RecapSectionLabel(
            stringResource(R.string.chat_recap_next),
            top = if (recap.nodes.isNotEmpty()) 8.dp else 18.dp,
        )
        recap.nextSteps.forEach { step ->
            Text(
                text = step,
                style = type.body.copy(fontSize = 14.sp, lineHeight = 20.sp),
                color = tokens.ink,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(role = Role.Button) { onUseNextStep(step) }
                    .padding(vertical = 9.dp),
            )
        }
    }
}

@Composable
private fun RecapSectionLabel(text: String, top: Dp = 18.dp) {
    Text(
        text = text,
        style = LocalAmberType.current.meta.copy(fontSize = 12.sp, fontWeight = FontWeight.Medium),
        color = LocalAmberTokens.current.ink3,
        modifier = Modifier.padding(top = top, bottom = 4.dp),
    )
}

@Composable
private fun RecapNodeRow(node: RecapNode, enabled: Boolean, onClick: () -> Unit) {
    val tokens = LocalAmberTokens.current
    val theme = LocalChatTheme.current
    val textStyle = LocalAmberType.current.body.copy(fontSize = 14.sp, lineHeight = 20.sp)
    val firstLineHeight = with(LocalDensity.current) { textStyle.lineHeight.toDp() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // The dot is centred on the first text line at any font scale.
        Box(Modifier.height(firstLineHeight), contentAlignment = Alignment.Center) {
            val dotModifier = Modifier.size(7.dp).clip(CircleShape)
            when (node.kind) {
                RecapNodeKind.FAILURE -> Box(dotModifier.background(MaterialTheme.colorScheme.error))
                RecapNodeKind.DECISION -> Box(dotModifier.background(theme.accent))
                RecapNodeKind.ARTIFACT -> Box(dotModifier.border(1.5.dp, theme.accent, CircleShape))
                RecapNodeKind.MILESTONE -> Box(dotModifier.background(tokens.ink4))
            }
        }
        Text(
            text = node.title,
            style = textStyle,
            color = if (enabled) tokens.ink else tokens.ink3,
        )
    }
}

@Composable
fun ChatTitleRenameDialog(
    title: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember { mutableStateOf(title) }
    val tokens = LocalAmberTokens.current
    val type = LocalAmberType.current
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(14.dp),
        containerColor = tokens.raised,
        title = { Text(stringResource(R.string.chat_recap_rename_title), style = type.sessionTitle) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it.take(80) },
                label = { Text(stringResource(R.string.chat_recap_title_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            )
        },
        confirmButton = {
            TextButton(
                enabled = value.isNotBlank(),
                onClick = { onConfirm(value.trim()) },
            ) { Text(stringResource(R.string.common_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

// ── Shelf ─────────────────────────────────────────────────────────────────────

@Composable
fun ColumnScope.ChatShelfPanelContent(
    /** Null while the full conversation is being read. */
    items: List<ShelfItem>?,
    onOpen: (ShelfItem) -> Unit,
) {
    val tokens = LocalAmberTokens.current
    val type = LocalAmberType.current
    Text(
        text = stringResource(R.string.chat_shelf),
        style = type.screenTitle,
        color = tokens.ink,
        modifier = Modifier.padding(vertical = 4.dp),
    )
    if (items == null) {
        RecapLoadingRow(showLabel = false)
        return
    }
    if (items.isEmpty()) {
        Text(
            text = stringResource(R.string.chat_shelf_empty),
            style = type.secondary,
            color = tokens.ink3,
            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
        )
        return
    }
    Spacer(Modifier.height(4.dp))
    items.forEach { item -> ShelfRow(item, onClick = { onOpen(item) }) }
}

@Composable
private fun ShelfRow(item: ShelfItem, onClick: () -> Unit) {
    val tokens = LocalAmberTokens.current
    val type = LocalAmberType.current
    val (icon, kindLabel) = when (item.kind) {
        ShelfItemKind.IMAGE -> Lucide.Image to stringResource(R.string.chat_shelf_kind_image)
        ShelfItemKind.FILE -> Lucide.File to stringResource(R.string.chat_shelf_kind_file)
        ShelfItemKind.MINI_APP -> Lucide.AppWindow to stringResource(R.string.chat_shelf_kind_app)
        ShelfItemKind.WEB -> Lucide.Globe to stringResource(R.string.chat_shelf_kind_web)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(tokens.surface2),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = tokens.ink2, modifier = Modifier.size(18.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.label.ifBlank { kindLabel },
                style = type.body.copy(fontSize = 14.sp, lineHeight = 19.sp),
                color = tokens.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = kindLabel,
                style = type.meta.copy(fontSize = 11.5.sp),
                color = tokens.ink3,
            )
        }
    }
}
