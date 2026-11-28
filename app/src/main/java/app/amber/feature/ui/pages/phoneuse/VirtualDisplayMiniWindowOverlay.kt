package app.amber.feature.ui.pages.phoneuse

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
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
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import app.amber.core.automation.display.ActiveVirtualDisplayRun
import app.amber.core.automation.display.VirtualDisplayRunControlState
import app.amber.core.automation.display.VirtualDisplayRunRegistry
import app.amber.core.automation.display.VirtualDisplaySessionState
import app.amber.feature.ui.pages.personalagent.PersonalAgentTopChromeHeight
import app.amber.feature.ui.theme.LocalAmberTokens
import app.amber.feature.ui.theme.LocalAmberType
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Maximize
import com.composables.icons.lucide.Pause
import com.composables.icons.lucide.Play
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** Bounded preview refresh interval (200ms = max 5fps) matching [PhoneUseLivePage]. */
private const val MINI_WINDOW_FRAME_INTERVAL_MS = 200L
private val MiniWindowWidth = 136.dp
private val MiniWindowEdgeMargin = 12.dp
private val MiniWindowCornerRadius = 18.dp
private val MiniWindowHeaderHeight = 30.dp
private val MiniWindowControlsHeight = 36.dp

internal fun resolveVirtualDisplayAppLabel(
    context: Context,
    packageName: String,
): String {
    val trimmed = packageName.trim()
    if (trimmed.isEmpty()) return ""
    return runCatching {
        val info = context.packageManager.getApplicationInfo(trimmed, 0)
        context.packageManager.getApplicationLabel(info).toString().trim()
    }.getOrNull()?.takeIf { it.isNotEmpty() } ?: trimmed
}

internal fun clampMiniWindowOffset(
    x: Float,
    y: Float,
    windowWidthPx: Float,
    windowHeightPx: Float,
    containerWidthPx: Float,
    containerHeightPx: Float,
    edgeMarginPx: Float,
    topSafePx: Float,
    bottomSafePx: Float,
): Pair<Float, Float> {
    val maxX = (containerWidthPx - windowWidthPx - edgeMarginPx).coerceAtLeast(edgeMarginPx)
    val maxY = (containerHeightPx - windowHeightPx - bottomSafePx).coerceAtLeast(topSafePx)
    return x.coerceIn(edgeMarginPx, maxX) to y.coerceIn(topSafePx, maxY)
}

internal fun snapMiniWindowHorizontalEdge(
    currentX: Float,
    windowWidthPx: Float,
    containerWidthPx: Float,
    edgeMarginPx: Float,
): Float {
    val maxX = (containerWidthPx - windowWidthPx - edgeMarginPx).coerceAtLeast(edgeMarginPx)
    val centerX = currentX + windowWidthPx * 0.5f
    return if (centerX < containerWidthPx * 0.5f) edgeMarginPx else maxX
}

/**
 * In-app draggable Android freeform / picture-in-picture style live preview window for an active
 * virtual display automation run. Renders strictly inside the app window (no `SYSTEM_ALERT_WINDOW`
 * required) so the user can watch the agent operate the target app while scrolling or chatting.
 */
@Composable
fun VirtualDisplayMiniWindowOverlay(
    onOpenLive: (runId: String) -> Unit,
    modifier: Modifier = Modifier,
    activeRunOverride: ActiveVirtualDisplayRun? = null,
) {
    val activeRuns by VirtualDisplayRunRegistry.activeRuns.collectAsStateWithLifecycle()
    val activeRun = activeRunOverride ?: remember(activeRuns) {
        activeRuns.values.lastOrNull()
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val containerWidthPx = with(density) { maxWidth.toPx() }
        val containerHeightPx = with(density) { maxHeight.toPx() }
        val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        val edgeMarginPx = with(density) { MiniWindowEdgeMargin.toPx() }
        val topSafePx = with(density) { (statusTop + PersonalAgentTopChromeHeight + 10.dp).toPx() }
        val bottomSafePx = with(density) { (navBottom + 132.dp).toPx() }

        AnimatedVisibility(
            visible = activeRun != null,
            enter = fadeIn(tween(200)) + scaleIn(initialScale = 0.92f, animationSpec = tween(220)),
            exit = fadeOut(tween(160)) + scaleOut(targetScale = 0.92f, animationSpec = tween(160)),
        ) {
            val run = activeRun ?: return@AnimatedVisibility
            VirtualDisplayMiniWindowFloatingHost(
                activeRun = run,
                onOpenLive = onOpenLive,
                containerWidthPx = containerWidthPx,
                containerHeightPx = containerHeightPx,
                edgeMarginPx = edgeMarginPx,
                topSafePx = topSafePx,
                bottomSafePx = bottomSafePx,
            )
        }
    }
}

@Composable
private fun VirtualDisplayMiniWindowFloatingHost(
    activeRun: ActiveVirtualDisplayRun,
    onOpenLive: (runId: String) -> Unit,
    containerWidthPx: Float,
    containerHeightPx: Float,
    edgeMarginPx: Float,
    topSafePx: Float,
    bottomSafePx: Float,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val tokens = LocalAmberTokens.current
    val amberType = LocalAmberType.current

    val appLabel = remember(activeRun.packageName, context) {
        resolveVirtualDisplayAppLabel(context, activeRun.packageName)
    }
    var minimized by remember(activeRun.runId) { mutableStateOf(false) }
    var previewImage by remember(activeRun.runId) { mutableStateOf<ImageBitmap?>(null) }
    var windowSize by remember { mutableStateOf(IntSize.Zero) }

    val initialWidthPx = with(density) { MiniWindowWidth.toPx() }
    val initialX = (containerWidthPx - initialWidthPx - edgeMarginPx).coerceAtLeast(edgeMarginPx)
    val offsetX = remember(activeRun.runId) { Animatable(initialX) }
    val offsetY = remember(activeRun.runId) { Animatable(topSafePx) }

    // Keep window inside safe bounds whenever container dimensions or minimized size change.
    LaunchedEffect(windowSize, containerWidthPx, containerHeightPx, topSafePx, bottomSafePx) {
        val w = if (windowSize.width > 0) windowSize.width.toFloat() else initialWidthPx
        val h = if (windowSize.height > 0) windowSize.height.toFloat() else with(density) { 264.dp.toPx() }
        val (clampedX, clampedY) = clampMiniWindowOffset(
            x = offsetX.value,
            y = offsetY.value,
            windowWidthPx = w,
            windowHeightPx = h,
            containerWidthPx = containerWidthPx,
            containerHeightPx = containerHeightPx,
            edgeMarginPx = edgeMarginPx,
            topSafePx = topSafePx,
            bottomSafePx = bottomSafePx,
        )
        if (clampedX != offsetX.value) offsetX.snapTo(clampedX)
        if (clampedY != offsetY.value) offsetY.snapTo(clampedY)
    }

    val controlState by activeRun.controlState.collectAsStateWithLifecycle()
    val sessionState = activeRun.sessionState?.collectAsStateWithLifecycle()?.value
        ?: VirtualDisplaySessionState.OPEN
    val frameSource = activeRun.frameSource
    val blankFrames = remember(frameSource) {
        frameSource?.latestStats?.map { it?.unobservable == true }?.distinctUntilChanged()
    }
    val isUnobservable = sessionState == VirtualDisplaySessionState.UNOBSERVABLE ||
        blankFrames?.collectAsStateWithLifecycle(initialValue = false)?.value == true

    LaunchedEffect(lifecycle, frameSource, isUnobservable, minimized) {
        if (frameSource == null || isUnobservable || minimized) {
            previewImage = null
            return@LaunchedEffect
        }
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            var lastSequence = -1L
            try {
                while (isActive && !frameSource.isClosed) {
                    val stats = frameSource.latestStats.value
                    if (stats?.unobservable == true) {
                        previewImage = null
                    } else if (stats != null && stats.sequence != lastSequence) {
                        previewImage = withContext(Dispatchers.Default) {
                            frameSource.copyLatestBitmap()?.asImageBitmap()
                        }
                        lastSequence = stats.sequence
                    }
                    delay(MINI_WINDOW_FRAME_INTERVAL_MS)
                }
            } finally {
                previewImage = null
            }
        }
    }

    val isPaused = controlState == VirtualDisplayRunControlState.PAUSED
    val isRunning = controlState == VirtualDisplayRunControlState.RUNNING
    val actionsEnabled = isRunning || isPaused
    val statusDotColor = when {
        isUnobservable || controlState == VirtualDisplayRunControlState.STOPPED ->
            if (tokens.isDark) Color(0xFFEF5350) else Color(0xFFB3261E)
        isPaused -> if (tokens.isDark) Color(0xFFFFB74D) else Color(0xFFD97706)
        else -> tokens.accent
    }
    val stopAccentColor = if (tokens.isDark) Color(0xFFEF5350) else Color(0xFFC62828)

    val dragModifier = Modifier
        .offset { IntOffset(offsetX.value.roundToInt(), offsetY.value.roundToInt()) }
        .onSizeChanged { windowSize = it }
        .pointerInput(activeRun.runId, containerWidthPx, containerHeightPx, edgeMarginPx, topSafePx, bottomSafePx) {
            detectDragGestures(
                onDrag = { change, dragAmount ->
                    change.consume()
                    val w = if (windowSize.width > 0) windowSize.width.toFloat() else initialWidthPx
                    val h = if (windowSize.height > 0) windowSize.height.toFloat() else with(density) { 264.dp.toPx() }
                    val (nx, ny) = clampMiniWindowOffset(
                        x = offsetX.value + dragAmount.x,
                        y = offsetY.value + dragAmount.y,
                        windowWidthPx = w,
                        windowHeightPx = h,
                        containerWidthPx = containerWidthPx,
                        containerHeightPx = containerHeightPx,
                        edgeMarginPx = edgeMarginPx,
                        topSafePx = topSafePx,
                        bottomSafePx = bottomSafePx,
                    )
                    scope.launch {
                        offsetX.snapTo(nx)
                        offsetY.snapTo(ny)
                    }
                },
                onDragEnd = {
                    val w = if (windowSize.width > 0) windowSize.width.toFloat() else initialWidthPx
                    val targetX = snapMiniWindowHorizontalEdge(
                        currentX = offsetX.value,
                        windowWidthPx = w,
                        containerWidthPx = containerWidthPx,
                        edgeMarginPx = edgeMarginPx,
                    )
                    scope.launch {
                        offsetX.animateTo(
                            targetValue = targetX,
                            animationSpec = spring(
                                dampingRatio = Spring.DampingRatioNoBouncy,
                                stiffness = 340f,
                            ),
                        )
                    }
                },
            )
        }

    if (minimized) {
        Surface(
            modifier = dragModifier
                .height(34.dp)
                .widthIn(max = 168.dp)
                .clip(CircleShape)
                .clickable(role = Role.Button) { minimized = false }
                .testTag("vd-mini-window-pill"),
            shape = CircleShape,
            color = tokens.surface.copy(alpha = 0.96f),
            contentColor = tokens.ink,
            shadowElevation = 8.dp,
            tonalElevation = 0.dp,
            border = BorderStroke(0.75.dp, tokens.line),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                MiniWindowStatusDot(color = statusDotColor, pulsing = isRunning)
                Text(
                    text = appLabel,
                    style = amberType.secondary.copy(
                        fontSize = 12.sp,
                        lineHeight = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                    ),
                    color = tokens.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Icon(
                    imageVector = Lucide.Maximize,
                    contentDescription = "展开小窗",
                    tint = tokens.ink3,
                    modifier = Modifier.size(12.dp),
                )
            }
        }
    } else {
        val image = previewImage
        val frameAspect = if (image != null && image.height > 0) {
            (image.width.toFloat() / image.height.toFloat()).coerceIn(0.45f, 0.85f)
        } else {
            9f / 16f
        }
        val previewHeight = (MiniWindowWidth / frameAspect).coerceIn(176.dp, 212.dp)

        Surface(
            modifier = dragModifier
                .width(MiniWindowWidth)
                .testTag("vd-mini-window"),
            shape = RoundedCornerShape(MiniWindowCornerRadius),
            color = tokens.surface.copy(alpha = 0.96f),
            contentColor = tokens.ink,
            shadowElevation = 10.dp,
            tonalElevation = 0.dp,
            border = BorderStroke(0.75.dp, tokens.line),
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Compact top header: breathing status dot + resolved app name + minimize
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(MiniWindowHeaderHeight)
                        .padding(start = 9.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    MiniWindowStatusDot(color = statusDotColor, pulsing = isRunning)
                    Text(
                        text = appLabel,
                        style = amberType.secondary.copy(
                            fontSize = 11.5.sp,
                            lineHeight = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                        ),
                        color = tokens.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("vd-mini-window-title"),
                    )
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .clickable(role = Role.Button) { minimized = true }
                            .semantics { contentDescription = "最小化虚拟屏小窗" }
                            .testTag("vd-mini-window-minimize"),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Lucide.ChevronDown,
                            contentDescription = null,
                            tint = tokens.ink3,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }

                // Live bounded <=5fps preview area; tap opens fullscreen live page.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(previewHeight)
                        .padding(horizontal = 4.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF141416))
                        .clickable(role = Role.Button) { onOpenLive(activeRun.runId) }
                        .testTag("vd-mini-window-preview"),
                    contentAlignment = Alignment.Center,
                ) {
                    when {
                        isUnobservable -> {
                            Text(
                                text = "画面受保护",
                                style = amberType.meta.copy(fontSize = 10.5.sp, lineHeight = 14.sp),
                                color = Color.White.copy(alpha = 0.78f),
                            )
                        }
                        image != null -> {
                            Image(
                                bitmap = image,
                                contentDescription = "$appLabel 实时画面",
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                        else -> {
                            Text(
                                text = if (isPaused) "已暂停" else "连接画面…",
                                style = amberType.meta.copy(fontSize = 10.5.sp, lineHeight = 14.sp),
                                color = Color.White.copy(alpha = 0.72f),
                            )
                        }
                    }
                }

                // Bottom compact action icons: Pause/Resume, Fullscreen, Emergency Stop
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(MiniWindowControlsHeight)
                        .padding(horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .clickable(enabled = actionsEnabled, role = Role.Button) {
                                if (isPaused) activeRun.resume() else activeRun.pause()
                            }
                            .semantics { contentDescription = if (isPaused) "继续虚拟屏任务" else "暂停虚拟屏任务" }
                            .testTag("vd-mini-window-pause-resume"),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = if (isPaused) Lucide.Play else Lucide.Pause,
                            contentDescription = null,
                            tint = if (actionsEnabled) tokens.ink2 else tokens.ink4,
                            modifier = Modifier.size(14.dp),
                        )
                    }

                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .clickable(role = Role.Button) { onOpenLive(activeRun.runId) }
                            .semantics { contentDescription = "放大查看实时画面" }
                            .testTag("vd-mini-window-expand"),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Lucide.Maximize,
                            contentDescription = null,
                            tint = tokens.ink2,
                            modifier = Modifier.size(13.dp),
                        )
                    }

                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .clickable(enabled = actionsEnabled, role = Role.Button) {
                                scope.launch { activeRun.emergencyStop() }
                            }
                            .semantics { contentDescription = "紧急停止虚拟屏任务" }
                            .testTag("vd-mini-window-stop"),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(9.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(
                                    if (actionsEnabled) stopAccentColor else tokens.ink4,
                                ),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MiniWindowStatusDot(
    color: Color,
    pulsing: Boolean,
    modifier: Modifier = Modifier,
) {
    if (!pulsing) {
        Box(
            modifier = modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(color),
        )
        return
    }
    val transition = rememberInfiniteTransition(label = "vd_mini_window_dot")
    val pulse by transition.animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 720, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse",
    )
    Box(
        modifier = modifier
            .size(6.dp)
            .graphicsLayer {
                alpha = pulse
                val scale = 0.85f + 0.22f * pulse
                scaleX = scale
                scaleY = scale
            }
            .clip(CircleShape)
            .background(color),
    )
}
