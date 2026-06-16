package app.amber.feature.ui.pages.councilroom

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.amber.feature.modelcouncil.CouncilParticipantStatus
import app.amber.feature.modelcouncil.CouncilRoom
import app.amber.feature.modelcouncil.CouncilRoomMode
import app.amber.feature.modelcouncil.CouncilRoomStatus
import app.amber.feature.modelcouncil.running
import app.amber.feature.modelcouncil.terminal
import app.amber.feature.ui.components.nav.BackButton
import app.amber.feature.ui.components.ui.workspaceColors
import app.amber.feature.ui.pages.chat.LocalChatTheme
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.StopCircle
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * Host-led Council Room page — a parallel subsystem to the main chat.
 *
 * Visual direction: keep the Android Amber chat surface (quiet canvas, large
 * spacing, pill composer) while borrowing the iOS Council timeline hierarchy:
 * meeting title, live status, host controls, and a three-part room switcher.
 */
@Composable
fun CouncilRoomPage(
    conversationId: String,
    vm: CouncilRoomVM = koinViewModel(parameters = { parametersOf(conversationId) }),
) {
    val room by vm.room.collectAsStateWithLifecycle()
    val chatTheme = LocalChatTheme.current

    Scaffold(containerColor = chatTheme.bg) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(chatTheme.bg),
        ) {
            when {
                room == null -> CouncilRoomLoading()
                room!!.status.terminal || room!!.status == CouncilRoomStatus.INTERRUPTED -> CouncilRoomTerminalView(room!!)
                else -> CouncilRoomActiveContent(room!!, vm)
            }
        }
    }
}

@Composable
private fun CouncilRoomLoading() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = LocalChatTheme.current.accent)
    }
}

@Composable
private fun CouncilRoomActiveContent(room: CouncilRoom, vm: CouncilRoomVM) {
    Column(modifier = Modifier.fillMaxSize()) {
        CouncilRoomHeader(room = room, onClose = { vm.close() })
        HostControlBar(room = room, vm = vm)
        CouncilRoomTabs(room = room, vm = vm)
    }
}

@Composable
private fun CouncilRoomTerminalView(room: CouncilRoom) {
    Column(modifier = Modifier.fillMaxSize()) {
        CouncilRoomHeader(room = room, onClose = null)
        CouncilRoomTabs(room = room, vm = null)
    }
}

@Composable
private fun CouncilRoomHeader(
    room: CouncilRoom,
    onClose: (() -> Unit)?,
) {
    val chatTheme = LocalChatTheme.current
    val workspace = workspaceColors()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, top = 14.dp, end = 16.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Surface(
            modifier = Modifier
                .size(46.dp)
                .shadow(2.dp, CircleShape, clip = false),
            shape = CircleShape,
            color = chatTheme.surface,
        ) {
            Box(contentAlignment = Alignment.Center) { BackButton() }
        }
        Column(
            modifier = Modifier.weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "议会聊天",
                color = chatTheme.ink,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                modifier = Modifier.padding(top = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .background(statusDotColor(room.status), CircleShape),
                )
                Text(
                    text = room.subtitle(),
                    color = workspace.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Surface(
            modifier = Modifier.size(46.dp),
            shape = CircleShape,
            color = chatTheme.surface,
            enabled = onClose != null && room.status.running,
            onClick = { onClose?.invoke() },
        ) {
            Box(contentAlignment = Alignment.Center) {
                if (onClose != null && room.status.running) {
                    Icon(
                        imageVector = HugeIcons.StopCircle,
                        contentDescription = "停止议会",
                        tint = workspace.red,
                    )
                } else {
                    Text("···", color = workspace.muted, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

private fun CouncilRoom.subtitle(): String {
    val statusLabel = when (status) {
        CouncilRoomStatus.IDLE -> "待开始"
        CouncilRoomStatus.EXPLORING -> "讨论中"
        CouncilRoomStatus.DEBATING -> "辩论中"
        CouncilRoomStatus.FINALIZING -> "综合中"
        CouncilRoomStatus.FINALIZED -> "已综合"
        CouncilRoomStatus.CANCELLED -> "已停止"
        CouncilRoomStatus.FAILED -> "已失败"
        CouncilRoomStatus.INTERRUPTED -> "已中断"
    }
    val modeLabel = when (mode) {
        CouncilRoomMode.EXPLORE -> "Explore"
        CouncilRoomMode.DEBATE -> "Debate"
        CouncilRoomMode.SYNTHESIZE -> "Synthesize"
    }
    val memberCount = participants.count { it.status != CouncilParticipantStatus.DISMISSED }
    return "$statusLabel · 主持 ${host?.name?.ifBlank { "Host" } ?: "Host"} · $memberCount 位成员 · $modeLabel"
}

@Composable
private fun statusDotColor(status: CouncilRoomStatus) = when (status) {
    CouncilRoomStatus.EXPLORING,
    CouncilRoomStatus.DEBATING -> LocalChatTheme.current.accent
    CouncilRoomStatus.FINALIZING -> workspaceColors().amber
    CouncilRoomStatus.FINALIZED -> workspaceColors().green
    CouncilRoomStatus.IDLE,
    CouncilRoomStatus.CANCELLED,
    CouncilRoomStatus.FAILED,
    CouncilRoomStatus.INTERRUPTED -> workspaceColors().faint
}
