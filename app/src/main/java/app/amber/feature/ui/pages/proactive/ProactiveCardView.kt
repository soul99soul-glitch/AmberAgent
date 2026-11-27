package app.amber.feature.ui.pages.proactive

import android.text.format.DateUtils
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.amber.agent.R
import app.amber.feature.macgateway.CloudProactiveRepository
import app.amber.feature.macgateway.ProactiveCard
import app.amber.feature.macgateway.ProactiveDetailRequest
import app.amber.feature.macgateway.ProactiveFeedbackAction
import app.amber.feature.ui.pages.chat.LocalChatTheme
import app.amber.feature.ui.theme.LocalAmberTokens
import com.composables.icons.lucide.CircleAlert
import com.composables.icons.lucide.CircleCheck
import com.composables.icons.lucide.Clock
import com.composables.icons.lucide.EyeOff
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Sparkles
import com.composables.icons.lucide.Zap
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext

/**
 * Optional opener provided by [app.amber.feature.ui.pages.personalagent.PersonalAgentPage]
 * so tapping a proactive card in the cloud main timeline opens the top-level [ProactiveDetailSheet].
 */
val LocalProactiveCardDetailOpener = compositionLocalOf<((String) -> Unit)?> { null }

enum class ProactiveCardActionKind(val wire: String) {
    ACT("act"),
    SNOOZE("snooze"),
    MUTE("mute"),
}

enum class ProactiveCardUrgency {
    NORMAL,
    URGENT,
    LOW,
}

enum class ProactiveCardFeedbackBadge {
    OPENED,
    REPLIED,
    SNOOZED,
    MUTED,
    DISMISSED,
}

enum class ProactiveCardSourceFallback {
    EXPLICIT,
    WATCHTOWER,
    WEBHOOK,
    PULSE,
    COMMITMENT,
    SNOOZE,
    AGENT,
    RAW_CATEGORY,
}

@Immutable
data class ProactiveCardPresentation(
    val eventId: String,
    val signalId: String,
    val sourceLabel: String,
    val sourceFallback: ProactiveCardSourceFallback,
    val category: String,
    val title: String,
    val body: String,
    val urgency: ProactiveCardUrgency,
    val isUrgent: Boolean,
    val actions: List<ProactiveCardActionKind>,
    val feedbackBadge: ProactiveCardFeedbackBadge?,
    val receivedAtMs: Long,
)

/**
 * Pure value mapper from [ProactiveCard] to [ProactiveCardPresentation].
 * Handles unknown urgency/action values safely and derives fallback source labels from `category`.
 */
object ProactiveCardPresentationMapper {
    fun map(card: ProactiveCard): ProactiveCardPresentation {
        val explicitSource = card.meta.sourceLabel.trim()
        val (resolvedSource, fallbackKind) = if (explicitSource.isNotEmpty()) {
            explicitSource to ProactiveCardSourceFallback.EXPLICIT
        } else {
            fallbackSource(card.meta.category)
        }
        val urgency = parseUrgency(card.meta.urgency)
        return ProactiveCardPresentation(
            eventId = card.eventId,
            signalId = card.signalId,
            sourceLabel = resolvedSource,
            sourceFallback = fallbackKind,
            category = card.meta.category.trim(),
            title = card.meta.title.trim(),
            body = card.text,
            urgency = urgency,
            isUrgent = urgency == ProactiveCardUrgency.URGENT,
            actions = parseActions(card.meta.actions),
            feedbackBadge = parseFeedbackBadge(card.feedbackState),
            receivedAtMs = card.receivedAt,
        )
    }

    fun parseUrgency(raw: String?): ProactiveCardUrgency = when (raw?.trim()?.lowercase()) {
        "urgent", "time-sensitive", "time_sensitive" -> ProactiveCardUrgency.URGENT
        "low", "passive" -> ProactiveCardUrgency.LOW
        else -> ProactiveCardUrgency.NORMAL
    }

    fun parseActions(rawActions: List<String>): List<ProactiveCardActionKind> {
        val seen = LinkedHashSet<ProactiveCardActionKind>()
        for (raw in rawActions) {
            when (raw.trim().lowercase()) {
                ProactiveCardActionKind.ACT.wire -> seen.add(ProactiveCardActionKind.ACT)
                ProactiveCardActionKind.SNOOZE.wire -> seen.add(ProactiveCardActionKind.SNOOZE)
                ProactiveCardActionKind.MUTE.wire -> seen.add(ProactiveCardActionKind.MUTE)
            }
        }
        return seen.toList()
    }

    fun parseFeedbackBadge(feedbackState: String?): ProactiveCardFeedbackBadge? =
        when (ProactiveFeedbackAction.fromWire(feedbackState)) {
            ProactiveFeedbackAction.OPENED -> ProactiveCardFeedbackBadge.OPENED
            ProactiveFeedbackAction.REPLIED -> ProactiveCardFeedbackBadge.REPLIED
            ProactiveFeedbackAction.SNOOZE -> ProactiveCardFeedbackBadge.SNOOZED
            ProactiveFeedbackAction.MUTE_TOPIC -> ProactiveCardFeedbackBadge.MUTED
            ProactiveFeedbackAction.DISMISS -> ProactiveCardFeedbackBadge.DISMISSED
            null -> null
        }

    fun fallbackSource(category: String): Pair<String, ProactiveCardSourceFallback> {
        val normalized = category.trim()
        return when (normalized.lowercase()) {
            "watchtower", "monitor", "web_monitor", "github_release" ->
                "Watchtower" to ProactiveCardSourceFallback.WATCHTOWER
            "webhook", "inbound_webhook" ->
                "Webhook" to ProactiveCardSourceFallback.WEBHOOK
            "pulse", "daily_pulse", "digest", "morning_brief" ->
                "Daily Pulse" to ProactiveCardSourceFallback.PULSE
            "commitment", "followup", "follow_up", "reminder" ->
                "Reminder" to ProactiveCardSourceFallback.COMMITMENT
            "snooze" ->
                "Snoozed" to ProactiveCardSourceFallback.SNOOZE
            "agent", "proactive", "" ->
                "Amber" to ProactiveCardSourceFallback.AGENT
            else ->
                normalized to ProactiveCardSourceFallback.RAW_CATEGORY
        }
    }
}

sealed interface ProactiveCardActionOutcome {
    data class Success(val action: ProactiveCardActionKind) : ProactiveCardActionOutcome
    data class Failure(val action: ProactiveCardActionKind, val detail: String? = null) : ProactiveCardActionOutcome
}

/**
 * Timeline wrapper wired directly from `ChatTimelineItems`.
 * Resolves [CloudProactiveRepository] safely via Koin [GlobalContext] so unit/Robolectric tests
 * without Koin stay crash-free.
 */
@Composable
internal fun TimelineProactiveCardItem(
    card: ProactiveCard,
    conversationId: String,
    modifier: Modifier = Modifier,
) {
    val koin = remember { GlobalContext.getOrNull() }
    val repository = remember(koin) { koin?.getOrNull<CloudProactiveRepository>() }
    val hostDetailOpener = LocalProactiveCardDetailOpener.current
    var localDetailSignalId by remember(card.signalId) { mutableStateOf<String?>(null) }

    ProactiveCardView(
        card = card,
        modifier = modifier,
        maxBodyLines = 6,
        onOpenDetail = { selected ->
            if (hostDetailOpener != null) hostDetailOpener(selected.signalId) else localDetailSignalId = selected.signalId
        },
        onAct = { target ->
            repository?.act(target, conversationId = conversationId) ?: false
        },
        onSnooze = { target ->
            repository?.sendFeedback(
                gatewayId = target.gatewayId,
                deviceId = target.deviceId,
                signalId = target.signalId,
                action = ProactiveFeedbackAction.SNOOZE,
                snoozeMinutes = DEFAULT_SNOOZE_MINUTES,
            ) ?: false
        },
        onMute = { target ->
            repository?.sendFeedback(
                gatewayId = target.gatewayId,
                deviceId = target.deviceId,
                signalId = target.signalId,
                action = ProactiveFeedbackAction.MUTE_TOPIC,
                muteHours = DEFAULT_MUTE_HOURS,
            ) ?: false
        },
    )

    val fallbackSignal = localDetailSignalId
    if (fallbackSignal != null && repository != null) {
        ProactiveDetailSheet(
            request = ProactiveDetailRequest(fallbackSignal, card.gatewayId, card.deviceId),
            conversationId = conversationId,
            repository = repository,
            onDismiss = { localDetailSignalId = null },
        )
    }
}

@Composable
fun ProactiveCardView(
    card: ProactiveCard,
    modifier: Modifier = Modifier,
    maxBodyLines: Int = 6,
    showDetailHint: Boolean = true,
    onOpenDetail: ((ProactiveCard) -> Unit)? = null,
    onAct: (suspend (ProactiveCard) -> Boolean)? = null,
    onSnooze: (suspend (ProactiveCard) -> Boolean)? = null,
    onMute: (suspend (ProactiveCard) -> Boolean)? = null,
) {
    val presentation = remember(card) { ProactiveCardPresentationMapper.map(card) }
    val chatTheme = LocalChatTheme.current
    val tokens = LocalAmberTokens.current
    val scheme = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()

    var pendingAction by remember(card.eventId) { mutableStateOf<ProactiveCardActionKind?>(null) }
    var lastOutcome by remember(card.eventId) { mutableStateOf<ProactiveCardActionOutcome?>(null) }

    val sourceBadgeText = localizedSourceLabel(presentation)
    val borderColor = if (presentation.isUrgent) {
        scheme.error.copy(alpha = 0.5f)
    } else {
        chatTheme.surfaceEdge
    }

    fun execute(action: ProactiveCardActionKind, handler: (suspend (ProactiveCard) -> Boolean)?) {
        if (pendingAction != null) return
        if (handler == null) {
            lastOutcome = ProactiveCardActionOutcome.Failure(action)
            return
        }
        pendingAction = action
        lastOutcome = null
        scope.launch {
            val outcome = try {
                if (handler(card)) {
                    ProactiveCardActionOutcome.Success(action)
                } else {
                    ProactiveCardActionOutcome.Failure(action)
                }
            } catch (error: Throwable) {
                ProactiveCardActionOutcome.Failure(action, error.message)
            }
            pendingAction = null
            lastOutcome = outcome
        }
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("proactive-card-${card.signalId}")
            .then(
                if (onOpenDetail != null) {
                    Modifier.clickable(role = Role.Button) { onOpenDetail(card) }
                } else {
                    Modifier
                },
            ),
        shape = RoundedCornerShape(16.dp),
        color = chatTheme.surface,
        contentColor = chatTheme.ink,
        border = BorderStroke(if (presentation.isUrgent) 1.5.dp else 1.dp, borderColor),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Header: Source label + Urgent badge + Relative time
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FlowRow(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = chatTheme.accentTint,
                        contentColor = chatTheme.accent,
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Lucide.Sparkles,
                                contentDescription = null,
                                modifier = Modifier.size(12.dp),
                            )
                            Text(
                                text = sourceBadgeText,
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }

                    if (presentation.isUrgent) {
                        Surface(
                            modifier = Modifier.testTag("proactive-card-urgent-badge"),
                            shape = RoundedCornerShape(999.dp),
                            color = scheme.errorContainer,
                            contentColor = scheme.onErrorContainer,
                            border = BorderStroke(1.dp, scheme.error.copy(alpha = 0.35f)),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    imageVector = Lucide.Zap,
                                    contentDescription = null,
                                    modifier = Modifier.size(12.dp),
                                )
                                Text(
                                    text = stringResource(R.string.proactive_card_urgent_badge),
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }

                if (presentation.receivedAtMs > 0L) {
                    Text(
                        text = formatProactiveRelativeTime(presentation.receivedAtMs),
                        style = MaterialTheme.typography.labelSmall,
                        color = tokens.ink3,
                        maxLines = 1,
                    )
                }
            }

            // Title
            if (presentation.title.isNotEmpty()) {
                Text(
                    text = presentation.title,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = chatTheme.ink,
                )
            }

            // Body
            if (presentation.body.isNotBlank()) {
                Text(
                    text = presentation.body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = chatTheme.inkSoft,
                    maxLines = maxBodyLines,
                    overflow = if (maxBodyLines == Int.MAX_VALUE) TextOverflow.Clip else TextOverflow.Ellipsis,
                )
            }

            if (showDetailHint && onOpenDetail != null) {
                Text(
                    text = stringResource(R.string.proactive_card_view_detail),
                    style = MaterialTheme.typography.labelMedium,
                    color = chatTheme.accent,
                )
            }

            // Execution outcome or persisted feedback status
            ProactiveCardStatusBanner(
                outcome = lastOutcome,
                persistedBadge = presentation.feedbackBadge,
            )

            // Action buttons strictly filtered by meta.actions
            if (presentation.actions.isNotEmpty()) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    presentation.actions.forEach { action ->
                        val isRunning = pendingAction == action
                        val enabled = pendingAction == null
                        when (action) {
                            ProactiveCardActionKind.ACT -> {
                                Button(
                                    onClick = { execute(ProactiveCardActionKind.ACT, onAct) },
                                    enabled = enabled,
                                    modifier = Modifier
                                        .defaultMinSize(minHeight = 48.dp)
                                        .heightIn(min = 48.dp)
                                        .testTag("proactive-card-action-act"),
                                    shape = RoundedCornerShape(12.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = scheme.primary,
                                        contentColor = scheme.onPrimary,
                                    ),
                                ) {
                                    if (isRunning) {
                                        CircularProgressIndicator(
                                            modifier = Modifier
                                                .padding(end = 6.dp)
                                                .size(14.dp),
                                            strokeWidth = 2.dp,
                                            color = scheme.onPrimary,
                                        )
                                    }
                                    Text(
                                        text = stringResource(R.string.proactive_card_action_act),
                                        style = MaterialTheme.typography.labelLarge,
                                    )
                                }
                            }

                            ProactiveCardActionKind.SNOOZE -> {
                                OutlinedButton(
                                    onClick = { execute(ProactiveCardActionKind.SNOOZE, onSnooze) },
                                    enabled = enabled,
                                    modifier = Modifier
                                        .defaultMinSize(minHeight = 48.dp)
                                        .heightIn(min = 48.dp)
                                        .testTag("proactive-card-action-snooze"),
                                    shape = RoundedCornerShape(12.dp),
                                    border = BorderStroke(1.dp, chatTheme.surfaceEdge),
                                ) {
                                    if (isRunning) {
                                        CircularProgressIndicator(
                                            modifier = Modifier
                                                .padding(end = 6.dp)
                                                .size(14.dp),
                                            strokeWidth = 2.dp,
                                        )
                                    } else {
                                        Icon(
                                            imageVector = Lucide.Clock,
                                            contentDescription = null,
                                            modifier = Modifier
                                                .padding(end = 6.dp)
                                                .size(15.dp),
                                        )
                                    }
                                    Text(
                                        text = stringResource(R.string.proactive_card_action_snooze),
                                        style = MaterialTheme.typography.labelLarge,
                                    )
                                }
                            }

                            ProactiveCardActionKind.MUTE -> {
                                OutlinedButton(
                                    onClick = { execute(ProactiveCardActionKind.MUTE, onMute) },
                                    enabled = enabled,
                                    modifier = Modifier
                                        .defaultMinSize(minHeight = 48.dp)
                                        .heightIn(min = 48.dp)
                                        .testTag("proactive-card-action-mute"),
                                    shape = RoundedCornerShape(12.dp),
                                    border = BorderStroke(1.dp, chatTheme.surfaceEdge),
                                ) {
                                    if (isRunning) {
                                        CircularProgressIndicator(
                                            modifier = Modifier
                                                .padding(end = 6.dp)
                                                .size(14.dp),
                                            strokeWidth = 2.dp,
                                        )
                                    } else {
                                        Icon(
                                            imageVector = Lucide.EyeOff,
                                            contentDescription = null,
                                            modifier = Modifier
                                                .padding(end = 6.dp)
                                                .size(15.dp),
                                        )
                                    }
                                    Text(
                                        text = stringResource(R.string.proactive_card_action_mute),
                                        style = MaterialTheme.typography.labelLarge,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProactiveCardStatusBanner(
    outcome: ProactiveCardActionOutcome?,
    persistedBadge: ProactiveCardFeedbackBadge?,
) {
    val scheme = MaterialTheme.colorScheme
    val chatTheme = LocalChatTheme.current
    when (outcome) {
        is ProactiveCardActionOutcome.Failure -> {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("proactive-card-status-error"),
                shape = RoundedCornerShape(10.dp),
                color = scheme.errorContainer,
                contentColor = scheme.onErrorContainer,
                border = BorderStroke(1.dp, scheme.error.copy(alpha = 0.35f)),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Lucide.CircleAlert,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = outcome.detail?.takeIf { it.isNotBlank() }
                            ?: stringResource(R.string.proactive_card_action_failed),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        is ProactiveCardActionOutcome.Success -> {
            val textRes = when (outcome.action) {
                ProactiveCardActionKind.ACT -> R.string.proactive_card_status_act_sent
                ProactiveCardActionKind.SNOOZE -> R.string.proactive_card_status_snoozed
                ProactiveCardActionKind.MUTE -> R.string.proactive_card_status_muted
            }
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("proactive-card-status-success"),
                shape = RoundedCornerShape(10.dp),
                color = scheme.primaryContainer.copy(alpha = 0.65f),
                contentColor = scheme.onPrimaryContainer,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Lucide.CircleCheck,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = stringResource(textRes),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        null -> {
            val badgeRes = when (persistedBadge) {
                ProactiveCardFeedbackBadge.REPLIED -> R.string.proactive_card_status_act_sent
                ProactiveCardFeedbackBadge.SNOOZED -> R.string.proactive_card_status_snoozed
                ProactiveCardFeedbackBadge.MUTED -> R.string.proactive_card_status_muted
                ProactiveCardFeedbackBadge.DISMISSED -> R.string.proactive_card_status_dismissed
                ProactiveCardFeedbackBadge.OPENED, null -> null
            }
            if (badgeRes != null) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = chatTheme.accentTint.copy(alpha = 0.6f),
                    contentColor = chatTheme.inkSoft,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Lucide.CircleCheck,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = chatTheme.accent,
                        )
                        Text(
                            text = stringResource(badgeRes),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun localizedSourceLabel(presentation: ProactiveCardPresentation): String =
    when (presentation.sourceFallback) {
        ProactiveCardSourceFallback.EXPLICIT,
        ProactiveCardSourceFallback.RAW_CATEGORY,
        -> presentation.sourceLabel
        ProactiveCardSourceFallback.WATCHTOWER -> stringResource(R.string.proactive_card_source_watchtower)
        ProactiveCardSourceFallback.WEBHOOK -> stringResource(R.string.proactive_card_source_webhook)
        ProactiveCardSourceFallback.PULSE -> stringResource(R.string.proactive_card_source_pulse)
        ProactiveCardSourceFallback.COMMITMENT -> stringResource(R.string.proactive_card_source_commitment)
        ProactiveCardSourceFallback.SNOOZE -> stringResource(R.string.proactive_card_source_snooze)
        ProactiveCardSourceFallback.AGENT -> stringResource(R.string.proactive_card_source_agent)
    }

@Composable
internal fun formatProactiveRelativeTime(epochMs: Long): String {
    val now = System.currentTimeMillis()
    val clamped = minOf(epochMs, now)
    if (now - clamped < DateUtils.MINUTE_IN_MILLIS) {
        return stringResource(R.string.workspace_time_just_now)
    }
    return DateUtils.getRelativeTimeSpanString(clamped, now, DateUtils.MINUTE_IN_MILLIS).toString()
}

internal const val DEFAULT_SNOOZE_MINUTES = 60
internal const val DEFAULT_MUTE_HOURS = 168
