package app.amber.feature.ui.pages.councilroom

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.amber.feature.modelcouncil.COUNCIL_ROOM_HOST_ID
import app.amber.feature.modelcouncil.COUNCIL_ROOM_USER_ID
import app.amber.feature.modelcouncil.CouncilRoom
import app.amber.feature.modelcouncil.CouncilRoomMode
import app.amber.feature.modelcouncil.CouncilRoomStatus
import app.amber.feature.modelcouncil.HostAction
import app.amber.feature.ui.components.ui.workspaceColors
import app.amber.feature.ui.pages.chat.LocalChatTheme

/**
 * Host controls as lightweight meeting actions, visually closer to the chat
 * composer chrome than a toolbar.
 */
@Composable
fun HostControlBar(
    room: CouncilRoom,
    vm: CouncilRoomVM,
    modifier: Modifier = Modifier,
) {
    if (room.status == CouncilRoomStatus.FINALIZING) return

    val firstGuest = room.activeGuests.firstOrNull()
    val seedMessage = room.messages.lastOrNull { it.authorId == COUNCIL_ROOM_USER_ID }
        ?: room.messages.lastOrNull { it.authorId != COUNCIL_ROOM_HOST_ID }
    val hasGuestMessage = room.messages.any {
        it.authorId != COUNCIL_ROOM_USER_ID && it.authorId != COUNCIL_ROOM_HOST_ID
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CouncilActionChip(
            text = "邀请发言",
            selected = room.mode == CouncilRoomMode.EXPLORE,
            enabled = firstGuest != null,
            onClick = {
                val guest = firstGuest ?: return@CouncilActionChip
                vm.triggerHostAction(HostAction.InviteNext(guest.id))
            },
        )
        CouncilActionChip(
            text = "自由回应",
            enabled = seedMessage != null && firstGuest != null,
            onClick = {
                val seed = seedMessage ?: return@CouncilActionChip
                vm.triggerHostAction(HostAction.LetGuestsRespond(seed.id))
            },
        )
        if (room.mode != CouncilRoomMode.DEBATE) {
            CouncilActionChip(
                text = "进入辩论",
                onClick = { vm.switchMode(CouncilRoomMode.DEBATE) },
            )
        } else {
            CouncilActionChip(
                text = "回到探索",
                selected = true,
                onClick = { vm.switchMode(CouncilRoomMode.EXPLORE) },
            )
        }
        CouncilActionChip(
            text = "综合结论",
            emphasized = true,
            enabled = hasGuestMessage,
            onClick = { vm.requestSynthesize() },
        )
        CouncilActionChip(
            text = "结束",
            danger = true,
            onClick = { vm.close() },
        )
    }
}

@Composable
private fun CouncilActionChip(
    text: String,
    onClick: () -> Unit,
    selected: Boolean = false,
    emphasized: Boolean = false,
    danger: Boolean = false,
    enabled: Boolean = true,
) {
    val chatTheme = LocalChatTheme.current
    val workspace = workspaceColors()
    val contentColor = when {
        danger -> workspace.red
        selected || emphasized -> chatTheme.accent
        else -> chatTheme.inkSoft
    }
    val background = when {
        selected || emphasized -> chatTheme.accentSoft
        else -> chatTheme.surface
    }

    Surface(
        shape = RoundedCornerShape(999.dp),
        color = background.copy(alpha = if (enabled) 1f else 0.54f),
        contentColor = contentColor.copy(alpha = if (enabled) 1f else 0.44f),
        border = BorderStroke(1.dp, if (selected || emphasized) chatTheme.accentTint else chatTheme.surfaceEdge),
        enabled = enabled,
        onClick = onClick,
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected || emphasized) FontWeight.SemiBold else FontWeight.Medium,
        )
    }
}
