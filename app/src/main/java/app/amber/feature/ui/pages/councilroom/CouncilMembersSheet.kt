package app.amber.feature.ui.pages.councilroom

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.RefreshCw

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
        containerColor = chatTheme.bg,
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
        ) {
            item(key = "header") {
                Column(modifier = Modifier.padding(bottom = 6.dp)) {
                    Text(
                        text = room.objective.ifBlank { defaultObjective },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = chatTheme.ink,
                    )
                    Text(
                        text = membersSubtitle(room),
                        style = MaterialTheme.typography.labelMedium,
                        color = workspace.muted,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }

            item(key = "roster-eyebrow") {
                Text(
                    text = stringResource(R.string.council_room_members_heading),
                    modifier = Modifier.padding(top = 10.dp, bottom = 8.dp),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = workspace.muted,
                )
            }

            items(members, key = { it.id }) { participant ->
                MemberRow(
                    participant = participant,
                    isHost = participant.kind == CouncilParticipantKind.HOST,
                )
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
                HorizontalDivider(
                    color = workspace.hairline,
                    modifier = Modifier.padding(vertical = 14.dp),
                )
                Text(
                    text = stringResource(R.string.council_room_synthesis_heading),
                    modifier = Modifier.padding(bottom = 9.dp),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = workspace.muted,
                )
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

@Composable
private fun membersSubtitle(room: CouncilRoom): String {
    val hostName = room.host?.name?.ifBlank { "Host" } ?: "Host"
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
    val modelLabel = participant.modelName
        .ifBlank { participant.externalModel }
        .ifBlank { participant.providerName }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        SubAgentAvatar(id = participant.id, name = participant.name, avatarSize = 34.dp)
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Text(
                    text = participant.name.ifBlank { participant.id },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = workspace.ink,
                )
                if (participant.role.isNotBlank() && participant.role != participant.name) {
                    CouncilRolePill(text = participant.role, isHost = isHost)
                }
            }
            if (modelLabel.isNotBlank()) {
                Text(
                    text = modelLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = workspace.faint,
                )
            }
        }
        MemberStatusPill(participant, isHost)
    }
}

@Composable
private fun MemberStatusPill(participant: CouncilParticipant, isHost: Boolean) {
    if (isHost) {
        WorkspaceStatusPill(text = stringResource(R.string.council_room_host_status), tone = WorkspaceTone.Warning)
        return
    }
    val (label, tone) = when (participant.status) {
        CouncilParticipantStatus.SPEAKING -> stringResource(R.string.council_room_status_speaking) to WorkspaceTone.Success
        CouncilParticipantStatus.WAITING -> stringResource(R.string.council_room_status_waiting) to WorkspaceTone.Neutral
        CouncilParticipantStatus.SPOKEN -> stringResource(R.string.council_room_status_spoken) to WorkspaceTone.Neutral
        CouncilParticipantStatus.INVITED -> stringResource(R.string.council_room_status_invited) to WorkspaceTone.Accent
        CouncilParticipantStatus.IDLE -> stringResource(R.string.council_room_status_idle) to WorkspaceTone.Neutral
        CouncilParticipantStatus.DISMISSED -> stringResource(R.string.council_room_status_dismissed) to WorkspaceTone.Danger
    }
    WorkspaceStatusPill(text = label, tone = tone)
}
