package app.amber.feature.ui.pages.councilroom

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.amber.feature.modelcouncil.CouncilParticipant
import app.amber.feature.modelcouncil.CouncilParticipantKind
import app.amber.feature.modelcouncil.CouncilParticipantStatus
import app.amber.feature.modelcouncil.CouncilRoom
import app.amber.feature.ui.components.ui.SubAgentAvatar
import app.amber.feature.ui.components.ui.WorkspaceStatusPill
import app.amber.feature.ui.components.ui.WorkspaceTone
import app.amber.feature.ui.components.ui.workspaceColors

/**
 * 成员视图 — roster with pixel avatars and live status pills.
 *
 * Host is pinned to the top (amber pill). DISMISSED guests are hidden. A footer
 * shows the roster count vs capacity.
 */
@Composable
fun CouncilRosterTab(
    room: CouncilRoom,
    modifier: Modifier = Modifier,
) {
    val workspace = workspaceColors()
    val visible = room.participants.filter { it.status != CouncilParticipantStatus.DISMISSED }
    val (host, guests) = visible.partition { it.kind == CouncilParticipantKind.HOST }

    LazyColumn(modifier = modifier) {
        items(host, key = { it.id }) { participant ->
            RosterRow(participant, isHost = true)
            HorizontalDivider(color = workspace.hairline)
        }
        items(guests, key = { it.id }) { participant ->
            RosterRow(participant, isHost = false)
        }
        item {
            val active = visible.size
            Text(
                text = "成员 $active / ${room.maxParticipants}",
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                style = MaterialTheme.typography.labelSmall,
                color = workspace.muted,
            )
        }
    }
}

@Composable
private fun RosterRow(participant: CouncilParticipant, isHost: Boolean) {
    val workspace = workspaceColors()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SubAgentAvatar(
            id = participant.id,
            name = participant.name,
            avatarSize = 36.dp,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = participant.name.ifBlank { participant.id },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = workspace.ink,
            )
            if (participant.role.isNotBlank() && participant.role != participant.name) {
                Text(
                    text = participant.role,
                    style = MaterialTheme.typography.bodySmall,
                    color = workspace.muted,
                )
            }
        }
        ParticipantStatusPill(participant, isHost)
    }
}

/**
 * Status pill mapping. Host is always "主持" (amber); guests map by their live
 * [CouncilParticipantStatus]. SPEAKING uses the Success tone (green) to signal
 * an active turn.
 */
@Composable
private fun ParticipantStatusPill(participant: CouncilParticipant, isHost: Boolean) {
    if (isHost) {
        WorkspaceStatusPill(text = "主持", tone = WorkspaceTone.Warning)
        return
    }
    val (label, tone) = when (participant.status) {
        CouncilParticipantStatus.SPEAKING -> "发言中" to WorkspaceTone.Success
        CouncilParticipantStatus.WAITING -> "等待" to WorkspaceTone.Neutral
        CouncilParticipantStatus.SPOKEN -> "已发言" to WorkspaceTone.Neutral
        CouncilParticipantStatus.INVITED -> "已邀请" to WorkspaceTone.Accent
        CouncilParticipantStatus.IDLE -> "未发言" to WorkspaceTone.Neutral
        CouncilParticipantStatus.DISMISSED -> "已移除" to WorkspaceTone.Danger
    }
    WorkspaceStatusPill(text = label, tone = tone)
}
