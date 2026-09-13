package app.amber.feature.ui.components.ai

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.material3.IconButton
import app.amber.core.utils.appLocale
import app.amber.feature.ui.pages.setting.components.ProviderCommandButton
import com.composables.icons.lucide.X
import com.composables.icons.lucide.MessageSquare
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.conflate

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import app.amber.feature.ui.components.richtext.MarkdownBlock
import app.amber.feature.ui.pages.setting.components.ProviderGhostButton
import app.amber.feature.ui.pages.setting.components.ProviderSheetGrabber
import app.amber.feature.ui.theme.LocalAmberType
import kotlinx.coroutines.launch
import java.io.File

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.SideEffect

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items as rowItems
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.amber.agent.R
import app.amber.feature.subagent.SubAgentRunStatus
import app.amber.feature.ui.components.ui.SubAgentAvatar
import app.amber.feature.ui.components.ui.workspaceColors
import app.amber.feature.ui.theme.LocalAmberTokens
import com.composables.icons.lucide.Lucide
import kotlinx.coroutines.delay
import kotlin.uuid.Uuid
import org.koin.compose.koinInject

/**
 * Cross-conversation subagent capsule rail, intended to be mounted immediately above the chat
 * composer. The state holder is process-wide; this composable owns only transient expansion and
 * sheet presentation state.
 */
@Composable
fun SubAgentStatusDock(
    currentConversationId: Uuid,
    modifier: Modifier = Modifier,
    onOpenConversation: (Uuid) -> Unit,
) {
    val state: SubAgentDockState = koinInject()
    val dockState by state.uiState.collectAsState()
    val tasks = if (dockState.enabled) dockState.tasks else emptyList()

    var expanded by rememberSaveable { mutableStateOf(false) }
    var selectedKey by remember { mutableStateOf<SubAgentDockRunKey?>(null) }
    val imeVisible = WindowInsets.isImeVisible
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(imeVisible, tasks.isEmpty()) {
        if (imeVisible) {
            expanded = false
        } else if (tasks.isEmpty()) {
            // Let the dock's exit finish before resetting its row/grid state.
            delay(240)
            expanded = false
        }
    }
    val dockExpanded = expanded && !imeVisible
    val selected = tasks.firstOrNull { it.key == selectedKey }
    fun closeSelected() {
        selectedKey = null
        state.keepDetailsOpen(null)
    }
    DisposableEffect(state) { onDispose { state.keepDetailsOpen(null) } }
    LaunchedEffect(selectedKey, selected == null) {
        if (selectedKey != null && selected == null) closeSelected()
    }
    val nowMs = rememberSubAgentDockNow(tasks)
    SubAgentDockRail(
        tasks = tasks,
        currentConversationId = currentConversationId,
        nowMs = nowMs,
        expanded = dockExpanded,
        modifier = modifier,
        onToggle = {
            if (imeVisible) {
                keyboard?.hide()
                expanded = true
            } else {
                expanded = !expanded
            }
        },
        onSelect = {
            state.keepDetailsOpen(it.key)
            selectedKey = it.key
        },
        onHideAll = { state.dismissAll() },
    )

    selected?.let { task ->
        val context = LocalContext.current
        val detailsFlow = remember(state, task.key, context.filesDir) {
            state.detailsFlow(task.key, File(context.filesDir, "amberagent/subagents/runs"))
        }
        val details by detailsFlow.collectAsState(initial = SubAgentDockDetails())
        SubAgentDockDetailsSheet(
            task = task,
            details = details,
            nowMs = nowMs,
            onDismiss = { closeSelected() },
            onOpenSourceConversation = { sourceConversationId ->
                closeSelected()
                onOpenConversation(sourceConversationId)
            },
            onDismissTask = {
                state.dismiss(task.key)
                closeSelected()
            },
        )
    }
}

@Composable
internal fun SubAgentDockRail(
    tasks: List<SubAgentDockTask>,
    currentConversationId: Uuid,
    nowMs: Long,
    expanded: Boolean,
    modifier: Modifier = Modifier,
    onToggle: () -> Unit,
    onSelect: (SubAgentDockTask) -> Unit,
    onHideAll: () -> Unit = {},
) {
    var retainedTasks by remember { mutableStateOf(tasks) }
    SideEffect { if (tasks.isNotEmpty()) retainedTasks = tasks }
    val visibleTasks = if (tasks.isNotEmpty()) tasks else retainedTasks
    val currentExpanded by rememberUpdatedState(expanded)
    val currentOnToggle by rememberUpdatedState(onToggle)
    val workspace = workspaceColors()
    val gridState = rememberLazyGridState()
    val stripState = rememberLazyListState()
    val railBackground = LocalAmberTokens.current.bg
    val gridCollapseScroll = remember(expanded, gridState) {
        object : NestedScrollConnection {
            private var requested = false
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                // At the top, a downward drag returns to the strip. Else the grid scrolls
                // normally, so a long task list remains reachable without accidental collapse.
                if (currentExpanded && !requested && source == NestedScrollSource.UserInput &&
                    available.y > 0f && !gridState.canScrollBackward
                ) {
                    requested = true
                    currentOnToggle()
                    return Offset(0f, available.y)
                }
                return Offset.Zero
            }
        }
    }
    val runningCount = visibleTasks.count { it.status == SubAgentDockStatus.RUNNING }
    val otherConversationCount = visibleTasks.count {
        it.sourceConversationId != null && it.sourceConversationId != currentConversationId
    }
    val countDescription = buildString {
        append(stringResource(R.string.subagent_dock_header_running, runningCount))
        if (otherConversationCount > 0) {
            append(" · ")
            append(stringResource(R.string.subagent_dock_header_other_conversations, otherConversationCount))
        }
    }
    val toggleDescription = stringResource(if (expanded) R.string.subagent_dock_collapse else R.string.subagent_dock_expand)
    val gestureDescription = stringResource(R.string.subagent_dock_swipe_hint)
    val pillHeight = with(LocalDensity.current) {
        maxOf(48.dp, 18.sp.toDp() + 16.sp.toDp() + 11.dp)
    }
    AnimatedVisibility(
        visible = tasks.isNotEmpty(),
        modifier = modifier.fillMaxWidth(),
        enter = fadeIn(tween(220)) + slideInVertically(tween(240, easing = FastOutSlowInEasing)) { it / 4 } +
            expandVertically(tween(240, easing = FastOutSlowInEasing), expandFrom = Alignment.Bottom),
        exit = fadeOut(tween(140)) + slideOutVertically(tween(180, easing = FastOutSlowInEasing)) { it / 5 } +
            shrinkVertically(tween(180, easing = FastOutSlowInEasing), shrinkTowards = Alignment.Bottom),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().background(LocalAmberTokens.current.bg).padding(bottom = 7.dp),
        ) {
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(tween(220, easing = FastOutSlowInEasing), expandFrom = Alignment.Bottom) + fadeIn(tween(180)),
                exit = shrinkVertically(tween(180, easing = FastOutSlowInEasing), shrinkTowards = Alignment.Bottom) + fadeOut(tween(120)),
            ) {
                val visibleRows = minOf(3, (visibleTasks.size + 1) / 2).coerceAtLeast(1)
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    state = gridState,
                    modifier = Modifier.fillMaxWidth().height(minOf(210.dp, (pillHeight + 8.dp) * visibleRows))
                        .nestedScroll(gridCollapseScroll).testTag("subagentDockGrid"),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    gridItems(items = visibleTasks, key = { it.stableLazyKey() }) { task ->
                        SubAgentDockPill(
                            task = task, nowMs = nowMs,
                            modifier = Modifier.fillMaxWidth().height(pillHeight).animateItem(
                                fadeInSpec = tween(160), placementSpec = tween(220, easing = FastOutSlowInEasing), fadeOutSpec = tween(120),
                            ),
                            onClick = { onSelect(task) },
                        )
                    }
                }
            }
            // One full-width scrolling track; controls never create fixed clipping edges around the task pills.
            Row(
                modifier = Modifier.fillMaxWidth().height(pillHeight + 8.dp)
                    .semantics { contentDescription = gestureDescription }
                    .pointerInput(Unit) {
                        val threshold = 24.dp.toPx()
                        var verticalTravel = 0f
                        detectVerticalDragGestures(
                            onDragStart = { verticalTravel = 0f },
                            onVerticalDrag = { change, amount ->
                                change.consume()
                                verticalTravel += amount
                            },
                            onDragEnd = {
                                if ((!currentExpanded && verticalTravel < -threshold) ||
                                    (currentExpanded && verticalTravel > threshold)
                                ) currentOnToggle()
                            },
                            onDragCancel = { verticalTravel = 0f },
                        )
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AnimatedContent(
                    targetState = expanded,
                    modifier = Modifier.fillMaxWidth(),
                    transitionSpec = { fadeIn(tween(160)) togetherWith fadeOut(tween(100)) },
                    label = "subagentDockStrip",
                ) { showHandle ->
                    if (showHandle) {
                        Row(
                            Modifier.fillMaxWidth().height(pillHeight + 8.dp).padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            SubAgentDockControls(runningCount, countDescription, toggleDescription, onToggle, onHideAll)
                            Box(Modifier.weight(1f).height(pillHeight), contentAlignment = Alignment.Center) {
                                Box(Modifier.size(32.dp, 3.dp).background(workspace.muted.copy(alpha = 0.35f), CircleShape))
                            }
                        }
                    } else {
                        LazyRow(
                            state = stripState,
                            modifier = Modifier.fillMaxWidth().height(pillHeight + 8.dp)
                                .testTag("subagentDockPills")
                                .drawWithCache {
                                    val edge = minOf(12.dp.toPx(), size.width / 4f)
                                    val transparent = railBackground.copy(alpha = 0f)
                                    val left = Brush.horizontalGradient(listOf(railBackground, transparent), 0f, edge)
                                    val right = Brush.horizontalGradient(listOf(transparent, railBackground), size.width - edge, size.width)
                                    onDrawWithContent {
                                        drawContent()
                                        if (stripState.canScrollBackward) drawRect(left, size = Size(edge, size.height))
                                        if (stripState.canScrollForward) {
                                            drawRect(right, topLeft = Offset(size.width - edge, 0f), size = Size(edge, size.height))
                                        }
                                    }
                                },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            item(key = "subagent-dock-controls") {
                                SubAgentDockControls(runningCount, countDescription, toggleDescription, onToggle, onHideAll)
                            }
                            rowItems(items = visibleTasks, key = { it.stableLazyKey() }) { task ->
                                SubAgentDockPill(
                                    task = task, nowMs = nowMs,
                                    modifier = Modifier.width(166.dp).height(pillHeight).animateItem(
                                        fadeInSpec = tween(160), placementSpec = tween(220, easing = FastOutSlowInEasing), fadeOutSpec = tween(120),
                                    ),
                                    onClick = { onSelect(task) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SubAgentDockControls(
    runningCount: Int,
    countDescription: String,
    toggleDescription: String,
    onToggle: () -> Unit,
    onHideAll: () -> Unit,
) {
    val workspace = workspaceColors()
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(
            onClick = onToggle,
            modifier = Modifier.size(48.dp).semantics {
                contentDescription = countDescription
                stateDescription = toggleDescription
            },
        ) {
            Box(
                Modifier.size(32.dp).background(
                    if (runningCount > 0) workspace.amber.copy(alpha = 0.10f) else workspace.row,
                    CircleShape,
                ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    runningCount.toString(),
                    style = LocalAmberType.current.meta.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
                    color = if (runningCount > 0) workspace.amber else workspace.muted,
                )
            }
        }
        IconButton(onClick = onHideAll, modifier = Modifier.size(48.dp)) {
            Surface(
                modifier = Modifier.size(32.dp),
                shape = CircleShape,
                color = workspace.row,
                border = BorderStroke(1.dp, workspace.hairline.copy(alpha = 0.4f)),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Lucide.X, contentDescription = stringResource(R.string.subagent_dock_dismiss_all), modifier = Modifier.size(16.dp), tint = workspace.muted)
                }
            }
        }
    }
}

@Composable
private fun SubAgentDockPill(
    task: SubAgentDockTask,
    nowMs: Long,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val workspace = workspaceColors()
    val pillShape = RoundedCornerShape(999.dp)
    val shadowInk = if (LocalAmberTokens.current.isDark) Color.Black else workspace.ink
    Surface(
        onClick = onClick,
        modifier = modifier
            .heightIn(min = 48.dp)
            .shadow(
                elevation = 2.dp,
                shape = pillShape,
                ambientColor = shadowInk.copy(alpha = 0.08f),
                spotColor = shadowInk.copy(alpha = 0.12f),
            ),
        shape = pillShape,
        color = workspace.paper,
        contentColor = workspace.ink,
        border = BorderStroke(1.dp, workspace.hairline.copy(alpha = 0.48f)),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier.padding(start = 9.dp, end = 12.dp, top = 5.dp, bottom = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .background(workspace.row, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                SubAgentAvatar(
                    id = task.key.taskId,
                    name = task.title,
                    avatarSize = 28.dp,
                    status = task.status.toAvatarStatus(),
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                Text(
                    text = task.title,
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                    ),
                    fontWeight = FontWeight.SemiBold,
                    color = workspace.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(5.dp)
                            .background(task.status.dotColor(), CircleShape),
                    )
                    Text(
                        text = "${task.status.label()} · ${formatSubAgentDockElapsed(task.elapsedAt(nowMs))}",
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                        ),
                        color = workspace.muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

private data class DockOverviewPresentation(
    val details: SubAgentDockDetails,
    val updating: Boolean,
    val failed: Boolean,
    val retry: () -> Unit,
)

@OptIn(kotlinx.coroutines.FlowPreview::class)
@Composable
private fun rememberDockOverview(
    details: SubAgentDockDetails,
    key: SubAgentDockRunKey,
): DockOverviewPresentation {
    val locale = LocalContext.current.appLocale()
    val localizer: SubAgentDockLocalizer = koinInject()
    val readable = remember(details) { details.readableOverview() }
    val latest by rememberUpdatedState(readable)
    var translated by remember(key, locale) { mutableStateOf<SubAgentDockDetails?>(null) }
    var translatedSource by remember(key, locale) { mutableStateOf<SubAgentDockDetails?>(null) }
    var busy by remember(key, locale) { mutableStateOf(false) }
    var failed by remember(key, locale) { mutableStateOf(false) }
    var retry by remember(key, locale) { mutableIntStateOf(0) }
    LaunchedEffect(key, locale, retry, localizer) {
        snapshotFlow { latest }
            .sample(1_000L)
            .conflate()
            .collect { snapshot ->
                if (!needsChineseLocalization(snapshot, locale)) {
                    translated = snapshot
                    translatedSource = snapshot
                    failed = false
                    busy = false
                } else {
                    busy = true
                    failed = false
                    val result = localizer.localize(snapshot, locale)
                    result.onSuccess {
                        translated = it
                        translatedSource = snapshot
                    }
                    failed = result.isFailure
                    busy = false
                }
            }
    }
    val needsTranslation = needsChineseLocalization(readable, locale)
    val visible = if (!needsTranslation) readable else translated?.takeIf { it.available }
        ?: readable.copy(summary = null, stages = emptyList())
    return DockOverviewPresentation(
        details = visible,
        updating = needsTranslation && !failed && (busy || translatedSource != readable),
        failed = needsTranslation && failed,
        retry = { retry++ },
    )
}

@Composable
private fun SubAgentDockDetailsSheet(
    task: SubAgentDockTask,
    details: SubAgentDockDetails,
    nowMs: Long,
    onDismiss: () -> Unit,
    onOpenSourceConversation: (Uuid) -> Unit,
    onDismissTask: () -> Unit,
) {
    val workspace = workspaceColors()
    val overview = rememberDockOverview(details, task.key)
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var fullOutput by rememberSaveable(task.key.taskId, task.key.createdAtMs) { mutableStateOf(false) }
    fun hideThen(action: () -> Unit) {
        scope.launch {
            sheetState.hide()
            action()
        }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = LocalAmberTokens.current.surface,
        dragHandle = { ProviderSheetGrabber() },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.84f)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SubAgentAvatar(
                    id = task.key.taskId,
                    name = task.title,
                    avatarSize = 32.dp,
                    status = task.status.toAvatarStatus(),
                )
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        text = task.title,
                        style = LocalAmberType.current.sessionTitle,
                        color = workspace.ink,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "${task.status.label()} · ${formatSubAgentDockElapsed(task.elapsedAt(nowMs))}",
                        style = LocalAmberType.current.secondary,
                        color = workspace.muted,
                    )
                }
                IconButton(onClick = { hideThen(onDismiss) }, modifier = Modifier.size(40.dp)) {
                    Icon(Lucide.X, contentDescription = stringResource(R.string.subagent_dock_close), modifier = Modifier.size(18.dp), tint = workspace.muted)
                }
            }
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(workspace.hairline.copy(alpha = 0.32f)))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                OutlinedButton(
                    onClick = { fullOutput = !fullOutput },
                    shape = CircleShape,
                    border = BorderStroke(1.dp, workspace.amber.copy(alpha = 0.16f)),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 7.dp),
                ) {
                    Text(
                        stringResource(if (fullOutput) R.string.subagent_dock_back_to_summary else R.string.subagent_dock_view_original),
                        style = LocalAmberType.current.secondary,
                        color = workspace.muted,
                    )
                }
            }
            if (fullOutput) {
                SubAgentDockOutput(
                    task = task,
                    details = details,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
            } else {
                SubAgentDockOverview(
                    details = overview.details,
                    active = task.status.keepsDockVisible,
                    updating = overview.updating,
                    translationFailed = overview.failed,
                    onRetryTranslation = overview.retry,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
            }
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(workspace.hairline.copy(alpha = 0.32f)))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                task.sourceConversationId?.let { sourceId ->
                    ProviderCommandButton(
                        text = stringResource(R.string.subagent_dock_source_conversation),
                        imageVector = Lucide.MessageSquare,
                        modifier = Modifier.weight(1f),
                        onClick = { hideThen { onOpenSourceConversation(sourceId) } },
                    )
                }
                if (task.status.canDismiss) {
                    ProviderCommandButton(
                        text = stringResource(R.string.subagent_dock_dismiss),
                        accent = false,
                        modifier = Modifier.weight(1f),
                        onClick = { hideThen(onDismissTask) },
                    )
                }
            }
        }
    }
}

@Composable
internal fun SubAgentDockOverview(
    details: SubAgentDockDetails,
    active: Boolean = true,
    updating: Boolean = false,
    translationFailed: Boolean = false,
    onRetryTranslation: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val workspace = workspaceColors()
    val readable = remember(details) { details.readableOverview() }
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        if (!readable.available) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 1.5.dp)
                    Text(stringResource(R.string.subagent_dock_loading_details), color = workspace.muted)
                }
            }
        } else {
            if (updating || translationFailed) {
                item("localization") {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            stringResource(if (translationFailed) R.string.subagent_dock_translation_failed else R.string.subagent_dock_translating),
                            modifier = Modifier.weight(1f), style = LocalAmberType.current.secondary, color = workspace.muted,
                        )
                        if (translationFailed) {
                            TextButton(onClick = onRetryTranslation) { Text(stringResource(R.string.retry)) }
                        }
                    }
                }
            }
            readable.summary?.let { summary ->
                item("summary") {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            stringResource(if (active) R.string.subagent_dock_current_progress else R.string.subagent_dock_result_summary),
                            style = LocalAmberType.current.secondary, color = workspace.muted,
                        )
                        SelectionContainer {
                            Text(summary, style = LocalAmberType.current.body.copy(lineHeight = 21.sp), color = workspace.ink)
                        }
                    }
                }
            }
            if (readable.stages.isNotEmpty()) {
                item("progress-heading") {
                    Text(stringResource(R.string.subagent_dock_recent_progress), style = LocalAmberType.current.secondary, color = workspace.muted)
                }
                itemsIndexed(readable.stages) { _, stage -> DockProgressRow(stage, active) }
            } else if (readable.summary.isNullOrBlank() && !updating && !translationFailed) {
                item("waiting") {
                    Text(
                        stringResource(if (active) R.string.subagent_dock_waiting_progress else R.string.subagent_dock_no_output),
                        style = LocalAmberType.current.body, color = workspace.muted,
                    )
                }
            }
        }
    }
}

@Composable
private fun DockProgressRow(stage: SubAgentDockStage, active: Boolean) {
    val workspace = workspaceColors()
    val running = active && stage.isRunning
    val neutral = stage.kind == SubAgentDockStageKind.NEXT_STEP || stage.kind == SubAgentDockStageKind.PREVIOUS_RESULT
    val label = stringResource(when (stage.kind) {
        SubAgentDockStageKind.FINDING -> R.string.subagent_dock_finding
        SubAgentDockStageKind.EVIDENCE -> R.string.subagent_dock_evidence
        SubAgentDockStageKind.RISK -> R.string.subagent_dock_risk
        SubAgentDockStageKind.NEXT_STEP -> R.string.subagent_dock_next_step
        SubAgentDockStageKind.PREVIOUS_RESULT -> R.string.subagent_dock_previous_result
        else -> R.string.subagent_dock_progress_note
    })
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            Modifier.padding(top = 7.dp).size(5.dp).background(
                when {
                    stage.kind == SubAgentDockStageKind.RISK -> workspace.amber
                    running || neutral -> workspace.muted
                    else -> workspace.green
                }, CircleShape,
            )
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                Text(
                    stage.title.ifBlank { label }, modifier = Modifier.weight(1f),
                    style = LocalAmberType.current.body.copy(fontWeight = FontWeight.Medium), color = workspace.ink,
                )
                if (!neutral && stage.kind != SubAgentDockStageKind.RISK) {
                    Text(
                        stringResource(if (running) R.string.subagent_dock_in_progress else R.string.chat_message_subagent_status_completed),
                        style = LocalAmberType.current.secondary, color = workspace.muted,
                    )
                }
            }
            SelectionContainer {
                Text(stage.text, style = LocalAmberType.current.secondary.copy(fontSize = 13.sp, lineHeight = 19.sp), color = workspace.muted)
            }
        }
    }
}

@Composable
private fun SubAgentDockOutput(
    task: SubAgentDockTask,
    details: SubAgentDockDetails,
    modifier: Modifier = Modifier,
) {
    val workspace = workspaceColors()
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    var followLatest by rememberSaveable(task.key.taskId, task.key.createdAtMs) { mutableStateOf(true) }
    val showingPrevious = details.output.isBlank() && !details.previousOutput.isNullOrBlank()
    val text = if (showingPrevious) details.previousOutput.orEmpty() else details.output
    LaunchedEffect(scroll) {
        snapshotFlow { scroll.value to scroll.maxValue }.collect { (value, max) ->
            if (value >= max - 8) followLatest = true
        }
    }
    LaunchedEffect(text, scroll.maxValue, followLatest, task.status) {
        if (followLatest && task.status.keepsDockVisible && !showingPrevious) {
            scroll.animateScrollTo(scroll.maxValue, tween(100))
        }
    }
    Box(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .pointerInput(task.key) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        followLatest = false
                    }
                }
                .verticalScroll(scroll)
                .padding(vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (showingPrevious) {
                Text(stringResource(R.string.subagent_dock_previous_result), color = workspace.muted)
                Text(stringResource(if (task.status.keepsDockVisible) R.string.subagent_dock_waiting_progress else R.string.subagent_dock_no_output), color = workspace.muted)
            }
            if (text.isBlank()) {
                Text(
                    stringResource(if (task.status.keepsDockVisible) R.string.chat_message_subagent_waiting_output else R.string.subagent_dock_no_output),
                    style = LocalAmberType.current.body,
                    color = workspace.muted,
                )
            } else {
                SelectionContainer {
                    MarkdownBlock(
                        content = text,
                        modifier = Modifier.fillMaxWidth(),
                        style = MaterialTheme.typography.bodyMedium.copy(color = workspace.ink),
                        streaming = task.status.keepsDockVisible && !showingPrevious,
                    )
                }
            }
        }
        if (task.status.keepsDockVisible && !followLatest && !showingPrevious) {
            ProviderGhostButton(
                text = stringResource(R.string.subagent_dock_follow_latest),
                modifier = Modifier.align(Alignment.BottomEnd),
                onClick = {
                    followLatest = true
                    scope.launch { scroll.animateScrollTo(scroll.maxValue, tween(100)) }
                },
            )
        }
    }
}

@Composable
private fun SubAgentDockStatus.label(): String = when (this) {
    SubAgentDockStatus.QUEUED -> stringResource(R.string.subagent_dock_status_queued)
    SubAgentDockStatus.RUNNING -> stringResource(R.string.chat_message_subagent_status_working)
    SubAgentDockStatus.APPROVAL_REQUIRED -> stringResource(R.string.chat_message_subagent_status_waiting_approval)
    SubAgentDockStatus.COMPLETED -> stringResource(R.string.chat_message_subagent_status_completed)
    SubAgentDockStatus.FAILED -> stringResource(R.string.chat_message_subagent_status_failed)
    SubAgentDockStatus.CANCELLED -> stringResource(R.string.chat_message_subagent_status_cancelled)
    SubAgentDockStatus.TIMED_OUT -> stringResource(R.string.chat_message_subagent_status_timed_out)
    SubAgentDockStatus.INTERRUPTED -> stringResource(R.string.chat_message_subagent_status_interrupted)
}

@Composable
private fun SubAgentDockStatus.dotColor() = when (this) {
    SubAgentDockStatus.QUEUED -> workspaceColors().muted
    SubAgentDockStatus.RUNNING -> workspaceColors().blue
    SubAgentDockStatus.APPROVAL_REQUIRED -> workspaceColors().amber
    SubAgentDockStatus.COMPLETED -> workspaceColors().green
    SubAgentDockStatus.FAILED,
    SubAgentDockStatus.TIMED_OUT,
    -> workspaceColors().red
    SubAgentDockStatus.CANCELLED,
    SubAgentDockStatus.INTERRUPTED,
    -> workspaceColors().muted
}

private fun SubAgentDockStatus.toAvatarStatus(): SubAgentRunStatus? = when (this) {
    SubAgentDockStatus.QUEUED -> null
    SubAgentDockStatus.RUNNING -> SubAgentRunStatus.RUNNING
    SubAgentDockStatus.APPROVAL_REQUIRED -> SubAgentRunStatus.APPROVAL_REQUIRED
    SubAgentDockStatus.COMPLETED -> SubAgentRunStatus.COMPLETED
    SubAgentDockStatus.FAILED -> SubAgentRunStatus.FAILED
    SubAgentDockStatus.CANCELLED -> SubAgentRunStatus.CANCELLED
    SubAgentDockStatus.TIMED_OUT -> SubAgentRunStatus.TIMED_OUT
    SubAgentDockStatus.INTERRUPTED -> SubAgentRunStatus.INTERRUPTED
}

/** Lazy layouts persist item state through Bundle-compatible keys. */
private fun SubAgentDockTask.stableLazyKey(): String =
    "${key.taskId}:${key.createdAtMs}"

@Composable
private fun rememberSubAgentDockNow(tasks: List<SubAgentDockTask>): Long {
    val liveKeys = remember(tasks) {
        tasks.filter { it.status.keepsDockVisible }.map { it.key to it.status }
    }
    var nowMs by remember(liveKeys) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(liveKeys) {
        if (liveKeys.isEmpty()) return@LaunchedEffect
        while (true) {
            delay(1_000L)
            nowMs = System.currentTimeMillis()
        }
    }
    return nowMs
}

internal fun formatSubAgentDockElapsed(elapsedMs: Long): String {
    val seconds = (elapsedMs.coerceAtLeast(0L) / 1_000L)
    return when {
        seconds < 60L -> "${seconds}s"
        seconds < 3_600L -> "${seconds / 60L}m ${seconds % 60L}s"
        else -> "${seconds / 3_600L}h ${(seconds % 3_600L) / 60L}m"
    }
}
