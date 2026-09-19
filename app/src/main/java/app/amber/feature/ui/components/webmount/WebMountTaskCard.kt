package app.amber.feature.ui.components.webmount

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateDp
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.amber.agent.R
import app.amber.feature.webmount.primitives.WebMountOwner
import app.amber.feature.webmount.primitives.WebMountSessionMetadata
import com.composables.icons.lucide.Bot
import com.composables.icons.lucide.ChevronUp
import com.composables.icons.lucide.CircleAlert
import com.composables.icons.lucide.Globe
import com.composables.icons.lucide.Hand
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.SquareArrowOutUpRight
import com.composables.icons.lucide.X

private val TaskMotionEasing = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)

/**
 * Browser task card with two forms sharing one chrome:
 * collapsed is a single status line; expanded adds a two-line header
 * (icon tile, title, URL) and one status/action row per session. The
 * transition morphs header content and unfolds the body in one motion.
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
    val single = sessions.size == 1 && activity == null
    val title = activity ?: if (sessions.size == 1) {
        sessionDisplayTitle(sessions.first())
    } else {
        stringResource(R.string.parity_webmount_task_card_title) + " · " + sessions.size
    }
    val headerAddress = if (single) sessionAddress(sessions.first(), title) else null
    val toggleDescription = stringResource(
        if (expanded) R.string.code_block_collapse else R.string.code_block_expand,
    )
    val swipeThresholdPx = with(LocalDensity.current) { 12.dp.toPx() }
    val dividerColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(cornerRadius),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, dividerColor),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 44.dp)
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
                    .padding(start = 12.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AnimatedContent(
                    targetState = expanded,
                    transitionSpec = {
                        (fadeIn(tween(200, delayMillis = 80)) togetherWith
                            fadeOut(tween(120))) using SizeTransform(clip = false)
                    },
                    modifier = Modifier.weight(1f),
                    label = "browserTaskHeader",
                ) { isExpanded ->
                    if (isExpanded) {
                        ExpandedHeader(
                            title = title,
                            address = headerAddress,
                            onDismiss = onDismiss,
                        )
                    } else {
                        CollapsedSummary(sessions = sessions, title = title)
                    }
                }
                CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 40.dp) {
                    IconButton(onClick = { expanded = !expanded }) {
                        Icon(
                            imageVector = Lucide.ChevronUp,
                            contentDescription = toggleDescription,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp).rotate(arrowRotation),
                        )
                    }
                }
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
                Column(modifier = Modifier.fillMaxWidth()) {
                    HorizontalDivider(color = dividerColor)
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 240.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(bottom = 4.dp),
                    ) {
                        sessions.forEachIndexed { index, session ->
                            if (index > 0) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(horizontal = 12.dp),
                                    color = dividerColor,
                                )
                            }
                            SessionDetails(
                                session = session,
                                showIdentity = sessions.size > 1 || activity != null,
                                onOpen = { onOpenSession(session.sessionId, session.needsReopen) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Expanded header: icon tile + title/URL stack + optional card dismiss. */
@Composable
private fun ExpandedHeader(
    title: String,
    address: String?,
    onDismiss: (() -> Unit)?,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Lucide.Globe,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp),
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (address != null) {
                Text(
                    text = address,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (onDismiss != null) {
            CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 36.dp) {
                IconButton(onClick = onDismiss, modifier = Modifier.size(36.dp)) {
                    Icon(
                        imageVector = Lucide.X,
                        contentDescription = stringResource(R.string.chat_page_close),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}

/** Collapsed one-liner: status icon + `status · title · url`, trailing-ellipsized. */
@Composable
private fun CollapsedSummary(
    sessions: List<WebMountSessionMetadata>,
    title: String,
) {
    val session = sessions.firstOrNull()
    val multi = sessions.size > 1
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = if (multi || session == null) Lucide.Globe else sessionStatusIcon(session),
            contentDescription = null,
            tint = if (!multi && session != null && session.status == "failed" && !session.needsReopen) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.primary
            },
            modifier = Modifier.size(15.dp),
        )
        val statusColor = MaterialTheme.colorScheme.onSurface
        val mutedColor = MaterialTheme.colorScheme.onSurfaceVariant
        Text(
            text = buildAnnotatedString {
                if (!multi && session != null) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Medium, color = statusColor)) {
                        append(webMountSessionStatusText(session))
                    }
                    append("  ·  ")
                }
                withStyle(SpanStyle(color = statusColor)) { append(title) }
                if (!multi) {
                    sessionAddress(sessions.first(), title)?.let { address ->
                        append("  ·  ")
                        withStyle(SpanStyle(color = mutedColor)) { append(address) }
                    }
                }
            },
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** One expanded session row: status icon + identity/status stack + open action. */
@Composable
private fun SessionDetails(
    session: WebMountSessionMetadata,
    showIdentity: Boolean,
    onOpen: () -> Unit,
) {
    val failed = session.status == "failed" && !session.needsReopen
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            imageVector = sessionStatusIcon(session),
            contentDescription = null,
            tint = if (failed) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.size(15.dp),
        )
        Column(
            modifier = Modifier.weight(1f).padding(vertical = 2.dp),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            if (showIdentity) {
                val identityTitle = sessionDisplayTitle(session)
                Text(
                    identityTitle,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                sessionAddress(session, identityTitle)?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Text(
                webMountSessionStatusText(session),
                style = MaterialTheme.typography.bodySmall,
                color = if (failed) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 40.dp) {
            TextButton(onClick = onOpen, modifier = Modifier.heightIn(min = 40.dp)) {
                Text(
                    stringResource(
                        if (session.needsReopen) {
                            R.string.parity_webmount_session_reopen
                        } else {
                            R.string.parity_webmount_session_watch
                        },
                    ),
                    maxLines = 1,
                    softWrap = false,
                )
                Spacer(Modifier.width(4.dp))
                Icon(
                    imageVector = Lucide.SquareArrowOutUpRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(13.dp),
                )
            }
        }
    }
}

private fun sessionStatusIcon(session: WebMountSessionMetadata): ImageVector = when {
    session.needsReopen || session.status == "failed" -> Lucide.CircleAlert
    session.owner == WebMountOwner.AGENT -> Lucide.Bot
    session.owner == WebMountOwner.HUMAN -> Lucide.Hand
    else -> Lucide.Hand
}

private fun sessionAddress(session: WebMountSessionMetadata, title: String): String? =
    session.redactedUrl
        ?.takeIf { it.isNotBlank() && it != title }
        ?.removePrefix("https://")
        ?.removePrefix("http://")

@Composable
private fun sessionDisplayTitle(session: WebMountSessionMetadata): String =
    session.title?.takeIf { it.isNotBlank() }
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
