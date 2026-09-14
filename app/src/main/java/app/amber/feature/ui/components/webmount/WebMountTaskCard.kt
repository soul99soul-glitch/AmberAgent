package app.amber.feature.ui.components.webmount

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateDp
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.amber.agent.R
import app.amber.feature.webmount.primitives.WebMountOwner
import app.amber.feature.webmount.primitives.WebMountSessionMetadata
import com.composables.icons.lucide.ChevronUp
import com.composables.icons.lucide.Globe
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.X

private val TaskMotionEasing = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)

/** One persistent heading; only the page details unfold below it. */
@Composable
fun WebMountTaskCard(
    sessions: List<WebMountSessionMetadata>,
    onOpenSession: (sessionId: String, reopen: Boolean) -> Unit,
    currentActivity: String? = null,
    onDismiss: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    if (sessions.isEmpty()) return

    var expanded by rememberSaveable { mutableStateOf(true) }
    val transition = updateTransition(expanded, label = "browserTaskExpansion")
    val arrowRotation by transition.animateFloat(
        transitionSpec = { tween(260, easing = TaskMotionEasing) },
        label = "arrowRotation",
    ) { if (it) 180f else 0f }
    val cornerRadius by transition.animateDp(
        transitionSpec = { tween(260, easing = TaskMotionEasing) },
        label = "cornerRadius",
    ) { if (it) 14.dp else 18.dp }
    val activity = currentActivity?.takeIf { it.isNotBlank() }
    val title = activity ?: if (sessions.size == 1) {
        sessionDisplayTitle(sessions.first())
    } else {
        stringResource(R.string.parity_webmount_task_card_title) + " · " + sessions.size
    }
    val toggleDescription = stringResource(
        if (expanded) R.string.code_block_collapse else R.string.code_block_expand,
    )
    val swipeThresholdPx = with(LocalDensity.current) { 12.dp.toPx() }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(cornerRadius),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 36.dp)
                    .pointerInput(expanded, swipeThresholdPx) {
                        var dragDistance = 0f
                        detectVerticalDragGestures(
                            onDragStart = { dragDistance = 0f },
                            onVerticalDrag = { change, amount ->
                                dragDistance += amount
                                change.consume()
                            },
                            onDragEnd = {
                                if ((expanded && dragDistance >= swipeThresholdPx) ||
                                    (!expanded && dragDistance <= -swipeThresholdPx)
                                ) expanded = !expanded
                                dragDistance = 0f
                            },
                            onDragCancel = { dragDistance = 0f },
                        )
                    }
                    .clickable(onClickLabel = toggleDescription) { expanded = !expanded }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Lucide.Globe,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp),
                )
                Box(Modifier.weight(1f)) {
                    // Expansion never changes this content or its horizontal position.
                    AnimatedContent(
                        targetState = title,
                        transitionSpec = {
                            (slideInVertically(tween(200)) { it / 2 } + fadeIn(tween(160))) togetherWith
                                (slideOutVertically(tween(160)) { -it / 2 } + fadeOut(tween(100)))
                        },
                        label = "browserTaskStep",
                    ) { text ->
                        Text(
                            text = text,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Icon(
                    imageVector = Lucide.ChevronUp,
                    contentDescription = toggleDescription,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp).rotate(arrowRotation),
                )
            }
            transition.AnimatedVisibility(
                visible = { it },
                enter = expandVertically(
                    animationSpec = tween(260, easing = TaskMotionEasing),
                    expandFrom = Alignment.Top,
                ) + fadeIn(tween(180, delayMillis = 40)),
                exit = shrinkVertically(
                    animationSpec = tween(220, easing = TaskMotionEasing),
                    shrinkTowards = Alignment.Top,
                ) + fadeOut(tween(120)),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 240.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(bottom = 4.dp),
                ) {
                    sessions.forEachIndexed { index, session ->
                        if (index > 0) {
                            HorizontalDivider(Modifier.padding(start = 36.dp, end = 12.dp, top = 4.dp, bottom = 4.dp))
                        }
                        SessionDetails(
                            session = session,
                            showTitle = sessions.size > 1 || activity != null,
                            onOpen = { onOpenSession(session.sessionId, session.needsReopen) },
                            onDismiss = onDismiss.takeIf { index == sessions.lastIndex },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionDetails(
    session: WebMountSessionMetadata,
    showTitle: Boolean,
    onOpen: () -> Unit,
    onDismiss: (() -> Unit)?,
) {
    val title = sessionDisplayTitle(session)
    val address = session.redactedUrl?.takeIf { it.isNotBlank() && it != title }
        ?.removePrefix("https://")?.removePrefix("http://")
    val showStatus = session.needsReopen || session.owner != WebMountOwner.NONE || session.status != "ready"

    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 36.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Column(
            modifier = Modifier.weight(1f).padding(vertical = 2.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            if (showTitle) {
                Text(title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            address?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (showStatus) {
                Text(
                    webMountSessionStatusText(session),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (session.status == "failed" && !session.needsReopen) {
                        MaterialTheme.colorScheme.error
                    } else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 36.dp) {
            TextButton(onClick = onOpen, modifier = Modifier.heightIn(min = 36.dp)) {
                Text(
                    stringResource(if (session.needsReopen) R.string.parity_webmount_session_reopen else R.string.parity_webmount_session_watch),
                    maxLines = 1,
                    softWrap = false,
                )
            }
            onDismiss?.let { dismiss ->
                IconButton(onClick = dismiss, modifier = Modifier.size(36.dp)) {
                    Icon(Lucide.X, stringResource(R.string.chat_page_close), modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

@Composable
private fun sessionDisplayTitle(session: WebMountSessionMetadata): String = session.title
    ?.takeIf { it.isNotBlank() }
    ?: session.redactedUrl?.takeIf { it.isNotBlank() }
    ?: stringResource(R.string.parity_webmount_task_card_title)

@Composable
internal fun webMountSessionStatusText(session: WebMountSessionMetadata): String {
    if (session.needsReopen) {
        return stringResource(R.string.parity_webmount_session_needs_reopen)
    }
    return when (session.owner) {
        WebMountOwner.AGENT -> stringResource(R.string.parity_webmount_session_agent_owned)
        WebMountOwner.HUMAN -> stringResource(R.string.parity_webmount_session_human_owned)
        WebMountOwner.NONE -> when (session.status) {
            "loading" -> stringResource(R.string.parity_webmount_session_loading)
            "ready" -> stringResource(R.string.parity_webmount_session_ready)
            "failed" -> stringResource(R.string.parity_webmount_session_failed)
            else -> stringResource(R.string.parity_webmount_session_available)
        }
    }
}
