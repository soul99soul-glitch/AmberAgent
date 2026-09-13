package app.amber.feature.ui.components.webmount

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.amber.agent.R
import app.amber.feature.webmount.primitives.WebMountOwner
import app.amber.feature.webmount.primitives.WebMountSessionMetadata
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Bot
import com.composables.icons.lucide.CircleAlert
import com.composables.icons.lucide.CircleCheck
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.ChevronUp
import com.composables.icons.lucide.Globe
import com.composables.icons.lucide.Hand
import com.composables.icons.lucide.RefreshCw
import com.composables.icons.lucide.X

/**
 * The compact, conversation-scoped entry point for live WebMount work.
 *
 * The expanded card separates the browser identity, page information, status,
 * and session action. It can be collapsed without releasing a session; the
 * caller owns hiding the card through [onDismiss].
 */
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
    val swipeThresholdPx = with(LocalDensity.current) { 16.dp.toPx() }
    val firstSession = sessions.first()
    val collapsedLabel = currentActivity
        ?.takeIf { it.isNotBlank() }
        ?: sessionDisplayTitle(firstSession)

    Card(
        modifier = modifier.animateContentSize(animationSpec = tween(durationMillis = 220)),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = if (expanded) 8.dp else 0.dp),
        ) {
            WebMountTaskHeader(
                expanded = expanded,
                expandedLabel = currentActivity,
                collapsedLabel = collapsedLabel,
                onToggleExpanded = { expanded = !expanded },
                onDismiss = onDismiss,
                swipeThresholdPx = swipeThresholdPx,
            )

            if (expanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 240.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    WebMountTaskRows(
                        sessions = sessions,
                        onOpenSession = onOpenSession,
                    )
                }
            }
        }
    }
}

@Composable
private fun WebMountTaskHeader(
    expanded: Boolean,
    expandedLabel: String?,
    collapsedLabel: String,
    onToggleExpanded: () -> Unit,
    onDismiss: (() -> Unit)?,
    swipeThresholdPx: Float,
) {
    val cardTitle = stringResource(R.string.parity_webmount_task_card_title)
    val toggleDescription = stringResource(
        if (expanded) R.string.code_block_collapse else R.string.code_block_expand,
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .pointerInput(expanded, swipeThresholdPx) {
                var dragDistance = 0f
                detectVerticalDragGestures(
                    onDragStart = { dragDistance = 0f },
                    onVerticalDrag = { change, dragAmount ->
                        dragDistance += dragAmount
                        change.consume()
                    },
                    onDragEnd = {
                        val shouldToggle = if (expanded) {
                            dragDistance >= swipeThresholdPx
                        } else {
                            dragDistance <= -swipeThresholdPx
                        }
                        if (shouldToggle) {
                            onToggleExpanded()
                        }
                        dragDistance = 0f
                    },
                    onDragCancel = { dragDistance = 0f },
                )
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        WebMountTaskIcon()
        if (expanded) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                Text(
                    text = cardTitle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                WebMountActivityText(activity = expandedLabel)
            }
        } else {
            Box(modifier = Modifier.weight(1f)) {
                WebMountAnimatedText(
                    text = collapsedLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        IconButton(
            onClick = onToggleExpanded,
            modifier = Modifier.size(48.dp),
        ) {
            Icon(
                imageVector = if (expanded) Lucide.ChevronDown else Lucide.ChevronUp,
                contentDescription = toggleDescription,
                modifier = Modifier.size(18.dp),
            )
        }
        onDismiss?.let { dismiss ->
            IconButton(
                onClick = dismiss,
                modifier = Modifier.size(48.dp),
            ) {
                Icon(
                    imageVector = Lucide.X,
                    contentDescription = stringResource(R.string.chat_page_close),
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun WebMountActivityText(activity: String?) {
    activity
        ?.takeIf { it.isNotBlank() }
        ?.let { value ->
            WebMountAnimatedText(
                text = value,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
}

@Composable
private fun WebMountAnimatedText(
    text: String,
    style: TextStyle,
    color: Color,
) {
    AnimatedContent(
        targetState = text,
        transitionSpec = {
            (slideInVertically { height -> height } + fadeIn()) togetherWith
                (slideOutVertically { height -> -height } + fadeOut())
        },
        label = "webMountTaskActivity",
    ) { targetText ->
        Text(
            text = targetText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            softWrap = false,
            style = style,
            color = color,
        )
    }
}

@Composable
private fun WebMountTaskIcon() {
    Surface(
        modifier = Modifier.size(36.dp),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = Lucide.Globe,
                contentDescription = null,
                modifier = Modifier.size(19.dp),
            )
        }
    }
}

@Composable
private fun WebMountTaskRows(
    sessions: List<WebMountSessionMetadata>,
    onOpenSession: (sessionId: String, reopen: Boolean) -> Unit,
) {
    sessions.forEachIndexed { index, session ->
        if (index > 0) {
            HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))
        }
        WebMountTaskRow(
            session = session,
            onClick = {
                onOpenSession(session.sessionId, session.needsReopen)
            },
        )
    }
}

@Composable
private fun WebMountTaskRow(
    session: WebMountSessionMetadata,
    onClick: () -> Unit,
) {
    val title = sessionDisplayTitle(session)
    val url = session.redactedUrl
        ?.takeIf { it.isNotBlank() && it != title }
    val statusText = webMountSessionStatusText(session)
    val showStatus = session.needsReopen ||
        session.owner != WebMountOwner.NONE ||
        session.status != "ready"

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (!showStatus) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Icon(
                        imageVector = Lucide.CircleCheck,
                        contentDescription = statusText,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
            url?.let {
                Text(
                    text = it,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    softWrap = false,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (showStatus) {
                WebMountStatusBadge(
                    session = session,
                    statusText = statusText,
                )
            }
        }
        TextButton(
            onClick = onClick,
            modifier = Modifier.heightIn(min = 48.dp),
        ) {
            Text(
                text = stringResource(
                    if (session.needsReopen) {
                        R.string.parity_webmount_session_reopen
                    } else {
                        R.string.parity_webmount_session_watch
                    },
                ),
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

@Composable
private fun WebMountStatusBadge(
    session: WebMountSessionMetadata,
    statusText: String,
) {
    val (icon, tint) = when {
        session.needsReopen -> Lucide.CircleAlert to MaterialTheme.colorScheme.error
        session.owner == WebMountOwner.AGENT -> Lucide.Bot to MaterialTheme.colorScheme.primary
        session.owner == WebMountOwner.HUMAN -> Lucide.Hand to MaterialTheme.colorScheme.primary
        session.status == "loading" -> Lucide.RefreshCw to MaterialTheme.colorScheme.primary
        session.status == "failed" -> Lucide.CircleAlert to MaterialTheme.colorScheme.error
        else -> Lucide.Globe to MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        shape = RoundedCornerShape(50),
        color = tint.copy(alpha = 0.12f),
        contentColor = tint,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
            )
            Text(
                text = statusText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

private fun sessionDisplayTitle(session: WebMountSessionMetadata): String = session.title
    ?.takeIf { it.isNotBlank() }
    ?: session.redactedUrl
        ?.takeIf { it.isNotBlank() }
    ?: session.sessionId

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
