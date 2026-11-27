package app.amber.feature.ui.pages.personalagent

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import app.amber.feature.ui.components.ds.AmberGlassRole
import app.amber.feature.ui.components.ds.amberGlass
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.amber.agent.R
import app.amber.core.service.LocalAsyncTaskCoordinator
import app.amber.feature.task.AgentTaskScheduler
import app.amber.feature.macgateway.CloudTaskSession
import app.amber.feature.macgateway.GatewayEndpointRole
import app.amber.feature.macgateway.MacGatewayRepository
import app.amber.feature.ui.components.ai.SubAgentDockState
import app.amber.feature.ui.pages.proactive.CloudProactiveCenterBody
import app.amber.feature.ui.pages.proactive.CloudProactiveCenterVM
import app.amber.feature.ui.pages.setting.AgentRuntimeTasksListBody
import app.amber.feature.ui.theme.LocalAmberTokens
import app.amber.feature.ui.theme.LocalAmberType
import com.composables.icons.lucide.Activity
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.RefreshCw
import com.composables.icons.lucide.SlidersHorizontal
import com.composables.icons.lucide.X
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import kotlin.math.roundToInt

private val ModeSheetEasing = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)

/**
 * Large floating Tasks / Center overlay panel anchored above the live chat root,
 * aligned with iOS `ChatViewComposer` inline Duet panels:
 * - Leaves the chat top chrome and timeline visible beneath a dismissible scrim
 * - Supports downward drag-to-dismiss on the handle/header, scrim tap, close button, and system Back
 * - Keeps the bottom `对话 / 任务 / 中心` switcher docked continuously at the bottom
 */
@Composable
internal fun PersonalAgentModeSheetOverlay(
    activeTab: PersonalAgentModeTab,
    onSelectTab: (PersonalAgentModeTab) -> Unit,
    onOpenTaskSettings: () -> Unit,
    bottomBar: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val visible = activeTab != PersonalAgentModeTab.CHAT
    val tokens = LocalAmberTokens.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val dismissThresholdPx = with(density) { 84.dp.toPx() }
    val dragOffsetY = remember { Animatable(0f) }

    LaunchedEffect(activeTab) {
        dragOffsetY.snapTo(0f)
    }

    BackHandler(enabled = visible) {
        onSelectTab(PersonalAgentModeTab.CHAT)
    }

    Box(modifier = modifier.fillMaxSize()) {
        // Scrim over the underlying conversation; tapping exposed chat area dismisses back to Chat.
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(durationMillis = 180)),
            exit = fadeOut(tween(durationMillis = 150)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = if (tokens.isDark) 0.48f else 0.22f))
                    .testTag("personal-agent-mode-sheet-scrim")
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { onSelectTab(PersonalAgentModeTab.CHAT) },
                    ),
            )
        }

        AnimatedVisibility(
            visible = visible,
            enter = slideInVertically(
                animationSpec = tween(durationMillis = 260, easing = ModeSheetEasing),
                initialOffsetY = { it / 2 },
            ) + fadeIn(tween(durationMillis = 180)),
            exit = slideOutVertically(
                animationSpec = tween(durationMillis = 200, easing = ModeSheetEasing),
                targetOffsetY = { it / 2 },
            ) + fadeOut(tween(durationMillis = 140)),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            BoxWithConstraints(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.BottomCenter,
            ) {
                val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
                // Reserve top clearance so the underlying chat top chrome stays visible.
                val topClearance = statusTop + PersonalAgentTopChromeHeight + 12.dp
                val maxPanelHeight = minOf(
                    (maxHeight - topClearance).coerceAtLeast(320.dp),
                    maxHeight * 0.80f,
                )
                val sheetShape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
                val shadowColor = if (tokens.isDark) Color.Black.copy(alpha = 0.36f) else tokens.ink.copy(alpha = 0.16f)

                val dragState = rememberDraggableState { delta ->
                    scope.launch {
                        val next = (dragOffsetY.value + delta).coerceAtLeast(0f)
                        dragOffsetY.snapTo(next)
                    }
                }

                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(maxPanelHeight)
                        .offset { IntOffset(0, dragOffsetY.value.roundToInt()) }
                        .amberGlass(sheetShape, AmberGlassRole.Panel, fill = tokens.bg)
                        .testTag("personal-agent-mode-sheet"),
                    shape = sheetShape,
                    color = Color.Transparent,
                    contentColor = tokens.ink,
                    tonalElevation = 0.dp,
                    shadowElevation = 0.dp,
                ) {
                    // Only initialize proactive center VM when the Center tab is actually viewed.
                    val proactiveVm: CloudProactiveCenterVM? = if (activeTab == PersonalAgentModeTab.CENTER) {
                        koinViewModel()
                    } else {
                        null
                    }
                    val proactiveSession = proactiveVm?.session?.collectAsStateWithLifecycle()?.value
                    val proactiveUiState = proactiveSession?.uiState?.collectAsStateWithLifecycle()?.value

                    Column(modifier = Modifier.fillMaxSize()) {
                        // Draggable handle + header
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .draggable(
                                    state = dragState,
                                    orientation = Orientation.Vertical,
                                    onDragStopped = { velocity ->
                                        if (dragOffsetY.value > dismissThresholdPx || velocity > 900f) {
                                            onSelectTab(PersonalAgentModeTab.CHAT)
                                        } else {
                                            scope.launch {
                                                dragOffsetY.animateTo(0f, tween(durationMillis = 180, easing = ModeSheetEasing))
                                            }
                                        }
                                    },
                                )
                                .padding(top = 10.dp, bottom = 8.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.CenterHorizontally)
                                    .width(36.dp)
                                    .height(4.dp)
                                    .clip(CircleShape)
                                    .background(tokens.ink3.copy(alpha = 0.36f)),
                            )
                            Spacer(Modifier.height(10.dp))
                            ModeSheetHeaderRow(
                                activeTab = activeTab,
                                showCenterRefresh = activeTab == PersonalAgentModeTab.CENTER && proactiveUiState?.isPaired == true,
                                centerRefreshEnabled = proactiveUiState?.let { !it.loading && !it.refreshing } ?: false,
                                onRefreshCenter = { proactiveSession?.refresh() },
                                onOpenTaskSettings = {
                                    onSelectTab(PersonalAgentModeTab.CHAT)
                                    onOpenTaskSettings()
                                },
                                onClose = { onSelectTab(PersonalAgentModeTab.CHAT) },
                            )
                        }

                        HorizontalDivider(thickness = 0.5.dp, color = tokens.line)

                        // Panel content switched smoothly without nesting a second Scaffold or route
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                        ) {
                            AnimatedContent(
                                targetState = activeTab,
                                transitionSpec = {
                                    fadeIn(tween(durationMillis = 150)) togetherWith fadeOut(tween(durationMillis = 120))
                                },
                                label = "personalAgentModeSheetBody",
                            ) { tab ->
                                when (tab) {
                                    PersonalAgentModeTab.TASKS -> {
                                        PersonalAgentTasksPanelBody(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .testTag("personal-agent-tasks-panel"),
                                        )
                                    }
                                    PersonalAgentModeTab.CENTER -> {
                                        val session = proactiveSession
                                        if (session != null) {
                                            key(session) {
                                                CloudProactiveCenterBody(
                                                    vm = session,
                                                    contentPadding = PaddingValues(bottom = 12.dp),
                                                    modifier = Modifier
                                                        .fillMaxSize()
                                                        .testTag("personal-agent-center-panel"),
                                                )
                                            }
                                        }
                                    }
                                    PersonalAgentModeTab.CHAT -> Unit
                                }
                            }
                        }

                        HorizontalDivider(thickness = 0.5.dp, color = tokens.line)

                        // Docked bottom mode switcher aligned with the idle chat bottom bar
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(tokens.bg)
                                .navigationBarsPadding()
                                .padding(horizontal = 16.dp)
                                .padding(bottom = 8.dp),
                        ) {
                            bottomBar()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ModeSheetHeaderRow(
    activeTab: PersonalAgentModeTab,
    showCenterRefresh: Boolean,
    centerRefreshEnabled: Boolean,
    onRefreshCenter: () -> Unit,
    onOpenTaskSettings: () -> Unit,
    onClose: () -> Unit,
) {
    val tokens = LocalAmberTokens.current
    val type = LocalAmberType.current
    val titleRes = if (activeTab == PersonalAgentModeTab.CENTER) {
        R.string.proactive_center_title
    } else {
        R.string.personal_agent_tab_tasks
    }
    val subtitleRes = if (activeTab == PersonalAgentModeTab.CENTER) {
        R.string.personal_agent_center_subtitle
    } else {
        R.string.personal_agent_tasks_subtitle
    }
    val closeA11y = stringResource(R.string.personal_agent_panel_close_a11y)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = stringResource(titleRes),
                style = type.screenTitle.copy(fontSize = 18.sp, lineHeight = 24.sp),
                color = tokens.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(subtitleRes),
                style = type.meta.copy(fontSize = 12.sp, lineHeight = 16.sp),
                color = tokens.ink3,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (activeTab == PersonalAgentModeTab.TASKS) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .testTag("personal-agent-tasks-settings")
                    .clickable(role = Role.Button, onClick = onOpenTaskSettings)
                    .semantics { contentDescription = "任务设置" },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Lucide.SlidersHorizontal,
                    contentDescription = null,
                    tint = tokens.ink2,
                    modifier = Modifier.size(17.dp),
                )
            }
        } else if (showCenterRefresh) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .testTag("proactive-center-refresh")
                    .clickable(enabled = centerRefreshEnabled, role = Role.Button, onClick = onRefreshCenter)
                    .semantics { contentDescription = "刷新" },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Lucide.RefreshCw,
                    contentDescription = null,
                    tint = if (centerRefreshEnabled) tokens.ink2 else tokens.ink4,
                    modifier = Modifier.size(17.dp),
                )
            }
        }

        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .testTag("personal-agent-mode-sheet-close")
                .clickable(role = Role.Button, onClick = onClose)
                .semantics { contentDescription = closeA11y },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Lucide.X,
                contentDescription = null,
                tint = tokens.ink3,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun PersonalAgentTasksPanelBody(
    modifier: Modifier = Modifier,
) {
    val taskScheduler: AgentTaskScheduler = koinInject()
    val macGatewayRepository: MacGatewayRepository = koinInject()
    val localAsyncTaskCoordinator: LocalAsyncTaskCoordinator = koinInject()
    val subAgentDockState: SubAgentDockState = koinInject()
    val macSlot = remember(macGatewayRepository) { macGatewayRepository.slot(GatewayEndpointRole.MAC) }

    val scheduledTasks by taskScheduler.tasksFlow.collectAsStateWithLifecycle()
    val cloudDialogue by macGatewayRepository.cloudDialogueState.collectAsStateWithLifecycle()
    val macDialogue by macSlot.cloudDialogueState.collectAsStateWithLifecycle()
    val localAsync by localAsyncTaskCoordinator.uiState.collectAsStateWithLifecycle()
    val subAgentUi by subAgentDockState.uiState.collectAsStateWithLifecycle()

    val activeCloudTasks = remember(cloudDialogue.tasks) { cloudDialogue.tasks.filter { it.active } }
    val activeMacTasks = remember(macDialogue.tasks) { macDialogue.tasks.filter { it.active } }
    val activeLocalTasks = remember(localAsync.tasks) { localAsync.tasks.filter { it.active } }
    val activeSubAgents = remember(subAgentUi) {
        if (subAgentUi.enabled) subAgentUi.tasks.filter { it.status.keepsDockVisible } else emptyList()
    }
    val hasActiveRuntimeWork = activeCloudTasks.isNotEmpty() ||
        activeMacTasks.isNotEmpty() ||
        activeLocalTasks.isNotEmpty() ||
        activeSubAgents.isNotEmpty()

    AgentRuntimeTasksListBody(
        tasks = scheduledTasks,
        showHarnessSettings = false,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        modifier = modifier,
        showEmptyStateWhenNoTasks = !hasActiveRuntimeWork,
        leadingItems = if (hasActiveRuntimeWork) {
            {
                item(key = "active-runtime-header") {
                    Text(
                        text = stringResource(R.string.personal_agent_tasks_active_section),
                        style = LocalAmberType.current.meta.copy(fontSize = 12.sp, fontWeight = FontWeight.Medium),
                        color = LocalAmberTokens.current.ink3,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                    )
                }
                activeCloudTasks.forEach { task ->
                    item(key = "cloud-${task.workItemId}") {
                        ActiveSessionTaskCard(sourceLabel = "Cloud", task = task)
                    }
                }
                activeMacTasks.forEach { task ->
                    item(key = "mac-${task.workItemId}") {
                        ActiveSessionTaskCard(sourceLabel = "Mac", task = task)
                    }
                }
                activeLocalTasks.forEach { task ->
                    item(key = "local-${task.workItemId}") {
                        ActiveSessionTaskCard(sourceLabel = "本机", task = task)
                    }
                }
                activeSubAgents.forEach { task ->
                    item(key = "subagent-${task.key.taskId}-${task.key.createdAtMs}") {
                        ActiveSimpleTaskCard(
                            title = task.title.ifBlank { task.key.taskId },
                            meta = "子代理 · ${task.status.name.lowercase()}",
                        )
                    }
                }
                if (scheduledTasks.isNotEmpty()) {
                    item(key = "scheduled-runtime-header") {
                        Text(
                            text = stringResource(R.string.personal_agent_tasks_history_section),
                            style = LocalAmberType.current.meta.copy(fontSize = 12.sp, fontWeight = FontWeight.Medium),
                            color = LocalAmberTokens.current.ink3,
                            modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 8.dp, bottom = 2.dp),
                        )
                    }
                }
            }
        } else {
            null
        },
    )
}

@Composable
private fun ActiveSessionTaskCard(
    sourceLabel: String,
    task: CloudTaskSession,
) {
    ActiveSimpleTaskCard(
        title = task.title.ifBlank { task.workItemId },
        meta = buildString {
            append(sourceLabel)
            append(" · ")
            append(if (task.active) "running" else (task.outcome ?: "finished"))
            task.progressText?.takeIf { it.isNotBlank() }?.let {
                append(" · ")
                append(it)
            }
        },
    )
}

@Composable
private fun ActiveSimpleTaskCard(
    title: String,
    meta: String,
) {
    val tokens = LocalAmberTokens.current
    val type = LocalAmberType.current
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = tokens.surface,
        contentColor = tokens.ink,
        border = BorderStroke(0.5.dp, tokens.floatingOutline),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(tokens.accent.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Lucide.Activity,
                    contentDescription = null,
                    tint = tokens.accent,
                    modifier = Modifier.size(16.dp),
                )
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = title,
                    style = type.body.copy(fontSize = 14.sp, lineHeight = 19.sp, fontWeight = FontWeight.Medium),
                    color = tokens.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = meta,
                    style = type.meta.copy(fontSize = 12.sp, lineHeight = 16.sp),
                    color = tokens.ink3,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
