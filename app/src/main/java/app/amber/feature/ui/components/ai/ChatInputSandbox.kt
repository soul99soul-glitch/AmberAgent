package app.amber.feature.ui.components.ai

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.koinInject
import app.amber.agent.R
import app.amber.core.automation.PhoneAutomationTakeoverController
import app.amber.core.automation.PhoneTakeoverPhase
import app.amber.feature.runtime.SandboxActivityUiState
import app.amber.feature.runtime.ToolActivityStatus
import app.amber.feature.ui.components.ui.workspaceColors

// Chat composer activity hosts; state remains in the existing runtime / WebView stores.

@Composable
internal fun SandboxPeekBar(
    activity: SandboxActivityUiState,
    onOpen: () -> Unit,
    onCancel: (() -> Unit)?,
    onPrevious: (() -> Unit)?,
    onNext: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    // V3 convo-tool-result.jsx ToolResultPreview spec:
    //   设计稿 asymmetric padding (start=14 end=32 top=10 bottom=8); 父级 ChatInput
    //   已加 horizontal=8 padding + spacedBy 8dp, 我们只控 horizontal (start=6 end=24).
    //   bottom 给 0 (让卡片紧贴下面的 ChatInput 输入框), top 维持轻量缓冲.
    //   gap=10, align Bottom; 缩略图 72×96 (3:4 竖向); ResultPill 22dp 高 999 圆角
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 6.dp, end = 24.dp, top = 4.dp, bottom = 0.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (activity.status == ToolActivityStatus.RUNNING && activity.toolName.startsWith("screen_")) {
            SandboxPhoneTakeoverBanner(activity = activity)
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            AgentOperationPreviewPeek(
                activity = activity,
                onOpen = onOpen,
                modifier = Modifier
                    .width(72.dp)
                    .height(96.dp),
            )
            SandboxStepPeek(
                activity = activity,
                onOpen = onOpen,
                onCancel = onCancel,
                onPrevious = onPrevious,
                onNext = onNext,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
internal fun SandboxPhoneTakeoverBanner(
    activity: SandboxActivityUiState,
    modifier: Modifier = Modifier,
    takeoverController: PhoneAutomationTakeoverController = koinInject(),
) {
    val takeoverState by takeoverController.state.collectAsStateWithLifecycle()
    val isRunningScreenTool = activity.status == ToolActivityStatus.RUNNING && activity.toolName.startsWith("screen_")
    if (!isRunningScreenTool && !takeoverState.isActive) return

    val effectivePhase = if (takeoverState.isActive) takeoverState.phase else PhoneTakeoverPhase.AUTOMATED
    val canRequestTakeover = effectivePhase == PhoneTakeoverPhase.AUTOMATED && isRunningScreenTool
    val canRequestResume = effectivePhase == PhoneTakeoverPhase.PAUSED || effectivePhase == PhoneTakeoverPhase.PAUSING
    // RESUMING has no resume action; without an escape the banner would sit on "re-reading screen"
    // until another screen tool happens to arrive.
    val canEndTakeover = effectivePhase == PhoneTakeoverPhase.RESUMING
    val workspace = workspaceColors()
    val statusText = when (effectivePhase) {
        PhoneTakeoverPhase.PAUSING -> stringResource(R.string.phone_takeover_status_pausing)
        PhoneTakeoverPhase.PAUSED -> stringResource(R.string.phone_takeover_status_paused)
        PhoneTakeoverPhase.RESUMING -> stringResource(R.string.phone_takeover_status_resuming)
        else -> stringResource(R.string.phone_takeover_status_automated)
    }
    val subtitleText = when {
        !takeoverState.interceptedSensitiveAction.isNullOrBlank() ->
            stringResource(R.string.phone_takeover_intercepted_format, takeoverState.interceptedSensitiveAction!!)
        effectivePhase == PhoneTakeoverPhase.PAUSED || effectivePhase == PhoneTakeoverPhase.PAUSING ->
            stringResource(R.string.phone_takeover_paused_hint)
        else -> null
    }
    val dotColor = when (effectivePhase) {
        PhoneTakeoverPhase.PAUSED, PhoneTakeoverPhase.PAUSING -> workspace.amber
        else -> Color(0xFF34C96E)
    }

    val onActionClick: (() -> Unit)? = when {
        canRequestTakeover -> {
            {
                if (!takeoverController.state.value.isActive) {
                    takeoverController.startSession(
                        runId = activity.toolCallId.ifBlank { "manual_takeover" },
                        packageName = "",
                        goal = activity.title,
                        maxSteps = 1,
                    )
                }
                takeoverController.requestUserTakeover(reason = "user_requested")
            }
        }
        canRequestResume -> {
            { takeoverController.requestResume() }
        }
        canEndTakeover -> {
            { takeoverController.finishSession() }
        }
        else -> null
    }
    val actionLabel = when {
        canRequestTakeover -> stringResource(R.string.phone_takeover_action_take_over)
        canRequestResume -> stringResource(R.string.phone_takeover_action_resume)
        canEndTakeover -> stringResource(R.string.task_bubble_stop)
        else -> null
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = workspace.paper,
        contentColor = workspace.ink,
        shadowElevation = 0.dp,
        border = BorderStroke(1.dp, workspace.hairline),
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            val fontScale = maxOf(LocalConfiguration.current.fontScale, LocalDensity.current.fontScale)
            val useStackedLayout = maxWidth < 280.dp || fontScale >= 1.3f
            if (useStackedLayout) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(dotColor),
                        )
                        Text(
                            text = statusText,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Medium,
                            color = workspace.ink,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (!subtitleText.isNullOrBlank()) {
                        Text(
                            text = subtitleText,
                            style = MaterialTheme.typography.bodySmall,
                            color = workspace.muted,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (actionLabel != null && onActionClick != null) {
                        Surface(
                            onClick = onActionClick,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp),
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            ) {
                                Text(
                                    text = actionLabel,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(dotColor),
                            )
                            Text(
                                text = statusText,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Medium,
                                color = workspace.ink,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (!subtitleText.isNullOrBlank()) {
                            Text(
                                text = subtitleText,
                                style = MaterialTheme.typography.bodySmall,
                                color = workspace.muted,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    if (actionLabel != null && onActionClick != null) {
                        Surface(
                            onClick = onActionClick,
                            modifier = Modifier.heightIn(min = 48.dp),
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                            ) {
                                Text(
                                    text = actionLabel,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
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
internal fun SandboxActivitySheet(
    activity: SandboxActivityUiState,
    onDismiss: () -> Unit,
    onCancel: (() -> Unit)?,
    onPrevious: (() -> Unit)?,
    onNext: (() -> Unit)?,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        BackHandler { onDismiss() }
        Column(
            modifier = Modifier
                .fillMaxHeight(0.86f)
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val previewUrl = activity.operationPreviewUrl()
            SandboxSheetHeader(
                activity = activity,
                isWebPreview = previewUrl != null,
                onCancel = onCancel,
                onPrevious = onPrevious,
                onNext = onNext,
            )
            if (activity.status == ToolActivityStatus.RUNNING && activity.toolName.startsWith("screen_")) {
                SandboxPhoneTakeoverBanner(activity = activity)
            }

            if (previewUrl != null) {
                SandboxWebActivityContent(
                    activity = activity,
                    url = previewUrl,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                )
            } else {
                SandboxToolActivityContent(
                    activity = activity,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                )
            }
        }
    }
}
