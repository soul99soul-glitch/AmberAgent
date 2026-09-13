package app.amber.feature.ui.pages.councilroom

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.amber.agent.R
import app.amber.feature.modelcouncil.CouncilParticipant
import app.amber.feature.modelcouncil.CouncilParticipantKind
import app.amber.feature.modelcouncil.CouncilParticipantStatus
import app.amber.feature.modelcouncil.CouncilRoom
import app.amber.feature.modelcouncil.CouncilRoomStatus
import app.amber.feature.ui.components.richtext.MarkdownBlock
import app.amber.feature.ui.components.ui.SubAgentAvatar
import app.amber.feature.ui.components.ui.WorkspaceStatusPill
import app.amber.feature.ui.components.ui.WorkspaceTone
import app.amber.feature.ui.components.ui.workspaceColors
import app.amber.feature.ui.pages.chat.LocalChatTheme
import app.amber.feature.ui.theme.LocalAmberType
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.RefreshCw
import com.composables.icons.lucide.X

/**
 * Council members & synthesis as a bottom sheet (opened from the room top bar's
 * people icon). Replaces the old full-screen tabbed page.
 *
 * Design after auto-orchestration: the chronological turns already live in the
 * main chat stream, and the host runs synthesis automatically — so this surface
 * is intentionally just two things:
 *  - the roster, where each member's live status IS the progress (no separate
 *    "进展" timeline, which duplicated the chat), with the round in the header;
 *  - the synthesis result (or an honest one-liner while it isn't ready — no fake
 *    shimmer placeholder, no manual "请求综合" button).
 */
@Composable
fun CouncilMembersSheet(
    room: CouncilRoom,
    onDismiss: () -> Unit,
    // Non-null only when the room is terminal (finished/stopped). When present,
    // the sheet shows a prominent solid-accent "重新开始" button at the bottom;
    // tapping it routes back to the page's confirm dialog.
    onRequestRestart: (() -> Unit)? = null,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val workspace = workspaceColors()
    val chatTheme = LocalChatTheme.current
    val members = room.participants.filter { it.status != CouncilParticipantStatus.DISMISSED }
    val defaultObjective = stringResource(R.string.council_room_default_objective)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp),
        containerColor = chatTheme.containerHighest,
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
        ) {
            item(key = "header") {
                Column(modifier = Modifier.padding(bottom = 8.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = "议题",
                            style = LocalAmberType.current.eyebrow,
                            fontWeight = FontWeight.SemiBold,
                            color = chatTheme.accent,
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier.size(40.dp),
                        ) {
                            Icon(
                                imageVector = Lucide.X,
                                contentDescription = stringResource(R.string.cancel),
                                tint = workspace.muted,
                            )
                        }
                    }
                    Text(
                        text = room.objective.ifBlank { defaultObjective },
                        style = LocalAmberType.current.screenTitle,
                        color = chatTheme.ink,
                    )
                    Text(
                        text = membersSubtitle(room),
                        style = LocalAmberType.current.meta,
                        color = workspace.faint,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }

            item(key = "members-eyebrow") {
                CouncilSheetEyebrow(text = stringResource(R.string.council_room_members_heading))
            }
            item(key = "members-card") {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    color = chatTheme.surface,
                    border = androidx.compose.foundation.BorderStroke(1.dp, chatTheme.surfaceEdge),
                ) {
                    Column {
                        members.forEachIndexed { index, participant ->
                            if (index > 0) {
                                HorizontalDivider(color = workspace.hairline)
                            }
                            MemberRow(
                                participant = participant,
                                isHost = participant.kind == CouncilParticipantKind.HOST,
                            )
                        }
                    }
                }
            }

            // Restart action sits BETWEEN the member roster and the synthesis block,
            // so it's reachable right after the (short) member list — no need to
            // scroll past the (often long) synthesis to find it. Terminal rooms only
            // (onRequestRestart is non-null exactly then). Filled accent + onAccent
            // text tracks the theme automatically.
            if (onRequestRestart != null) {
                item(key = "restart") {
                    val restartInteraction = remember { MutableInteractionSource() }
                    Surface(
                        onClick = onRequestRestart,
                        interactionSource = restartInteraction,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 18.dp),
                        shape = RoundedCornerShape(14.dp),
                        color = chatTheme.accent,
                        contentColor = chatTheme.onAccent,
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                imageVector = Lucide.RefreshCw,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Text(
                                text = stringResource(R.string.council_room_restart),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
            }

            item(key = "synthesis") {
                CouncilSheetEyebrow(text = stringResource(R.string.council_room_synthesis_heading))
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    color = chatTheme.surface,
                    border = androidx.compose.foundation.BorderStroke(1.dp, chatTheme.surfaceEdge),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        when {
                            room.synthesis.isNotBlank() -> {
                                MarkdownBlock(
                                    content = room.synthesis,
                                    style = MaterialTheme.typography.bodyMedium.copy(color = workspace.ink),
                                )
                            }

                            room.status == CouncilRoomStatus.FINALIZING -> {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = chatTheme.accent)
                                    Text(
                                        text = stringResource(R.string.council_room_synthesizing),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = workspace.muted,
                                    )
                                }
                            }

                            else -> {
                                Text(
                                    text = stringResource(R.string.council_room_synthesis_in_progress),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = workspace.muted,
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
private fun CouncilSheetEyebrow(text: String) {
    val chatTheme = LocalChatTheme.current
    val type = LocalAmberType.current
    val label = text.removePrefix("//").trim()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 24.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "//",
            style = type.eyebrow,
            fontWeight = FontWeight.SemiBold,
            color = chatTheme.accent,
        )
        Text(
            text = label,
            style = type.eyebrow,
            fontWeight = FontWeight.SemiBold,
            color = chatTheme.inkFaint,
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .height(1.dp)
                .background(chatTheme.surfaceEdge),
        )
    }
}

@Composable
private fun membersSubtitle(room: CouncilRoom): String {
    val hostName = room.host?.name?.ifBlank {
        stringResource(R.string.council_room_host_status)
    } ?: stringResource(R.string.council_room_host_status)
    val memberCount = room.participants.count { it.status != CouncilParticipantStatus.DISMISSED }
    val spoken = room.activeGuests.count { it.status == CouncilParticipantStatus.SPOKEN }
    val total = room.activeGuests.size
    val round = room.round.coerceAtLeast(1)
    return stringResource(
        R.string.council_room_members_subtitle,
        hostName,
        memberCount,
        round,
        spoken,
        total,
    )
}

@Composable
private fun MemberRow(participant: CouncilParticipant, isHost: Boolean) {
    val workspace = workspaceColors()
    val type = LocalAmberType.current
    val chatTheme = LocalChatTheme.current
    val modelLabel = participant.modelName
        .ifBlank { participant.externalModel }
        .ifBlank { participant.providerName }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(
                    if (participant.status == CouncilParticipantStatus.SPEAKING) {
                        chatTheme.accent.copy(alpha = 0.13f)
                    } else {
                        workspace.row
                    },
                )
                .border(
                    1.dp,
                    if (participant.status == CouncilParticipantStatus.SPEAKING) {
                        chatTheme.accent.copy(alpha = 0.38f)
                    } else {
                        workspace.hairline
                    },
                    CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            SubAgentAvatar(id = participant.id, name = participant.name, avatarSize = 28.dp)
        }
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Text(
                    text = participant.name.ifBlank { participant.id },
                    style = type.body,
                    fontWeight = FontWeight.SemiBold,
                    color = workspace.ink,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
                if (participant.role.isNotBlank() && participant.role != participant.name) {
                    CouncilRolePill(text = participant.role, isHost = isHost)
                }
            }
            if (modelLabel.isNotBlank()) {
                Text(
                    text = modelLabel,
                    style = type.meta,
                    color = workspace.faint,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
        }
        MemberStatusPill(participant, isHost)
    }
}

@Composable
private fun MemberStatusPill(participant: CouncilParticipant, isHost: Boolean) {
    if (participant.status == CouncilParticipantStatus.SPEAKING) {
        WorkspaceStatusPill(
            text = stringResource(R.string.council_room_status_speaking),
            tone = WorkspaceTone.Accent,
        )
        return
    }
    if (isHost) {
        WorkspaceStatusPill(
            text = stringResource(R.string.council_room_host_status),
            tone = WorkspaceTone.Warning,
        )
        return
    }
    val (label, tone) = when (participant.status) {
        CouncilParticipantStatus.SPEAKING -> stringResource(R.string.council_room_status_speaking) to WorkspaceTone.Accent
        CouncilParticipantStatus.WAITING -> stringResource(R.string.council_room_status_waiting) to WorkspaceTone.Neutral
        CouncilParticipantStatus.SPOKEN -> stringResource(R.string.council_room_status_spoken) to WorkspaceTone.Neutral
        CouncilParticipantStatus.INVITED -> stringResource(R.string.council_room_status_invited) to WorkspaceTone.Accent
        CouncilParticipantStatus.IDLE -> stringResource(R.string.council_room_status_idle) to WorkspaceTone.Neutral
        CouncilParticipantStatus.DISMISSED -> stringResource(R.string.council_room_status_dismissed) to WorkspaceTone.Danger
    }
    WorkspaceStatusPill(text = label, tone = tone)
}
