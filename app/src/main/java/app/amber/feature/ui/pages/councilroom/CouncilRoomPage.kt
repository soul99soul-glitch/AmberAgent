package app.amber.feature.ui.pages.councilroom

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.amber.agent.Screen
import app.amber.feature.modelcouncil.CouncilParticipantStatus
import app.amber.feature.modelcouncil.CouncilRoom
import app.amber.feature.modelcouncil.CouncilRoomMode
import app.amber.feature.modelcouncil.running
import app.amber.feature.modelcouncil.terminal
import app.amber.feature.ui.components.nav.BackButton
import app.amber.feature.ui.components.ui.workspaceColors
import app.amber.feature.ui.context.LocalNavController
import app.amber.feature.ui.pages.chat.LocalChatTheme
import app.amber.feature.ui.theme.LocalAmberTokens
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowReloadHorizontal
import me.rerere.hugeicons.stroke.BubbleChat
import me.rerere.hugeicons.stroke.Settings03
import me.rerere.hugeicons.stroke.UserGroup
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * Host-led Council Room page — the chat surface of the room.
 *
 * Visual direction follows the Android Council design: a Material top app bar
 * (back · tappable title that opens the mode menu · members icon · overflow),
 * the merged group-chat timeline as the primary body, and the pill composer at
 * the bottom. The member roster + synthesis live in a bottom sheet
 * ([CouncilMembersSheet]), opened from the people icon.
 */
@Composable
fun CouncilRoomPage(
    conversationId: String,
    vm: CouncilRoomVM = koinViewModel(parameters = { parametersOf(conversationId) }),
) {
    val room by vm.room.collectAsStateWithLifecycle()
    val chatTheme = LocalChatTheme.current

    // Only consume the status-bar inset here; the composer handles the bottom
    // (ime + nav) itself, so the keyboard lifts the input above it.
    Scaffold(
        containerColor = chatTheme.bg,
        contentWindowInsets = WindowInsets.statusBars,
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(chatTheme.bg),
        ) {
            when {
                room == null -> CouncilRoomLoading()
                room!!.status.terminal ->
                    // Read-only view of a finished/stopped council. The only mutating
                    // action offered here is "restart" (discard + reopen fresh).
                    CouncilRoomBody(room!!, vm = null, onRestart = vm::restart)
                else -> CouncilRoomBody(room!!, vm = vm, onRestart = null)
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
private fun CouncilRoomBody(room: CouncilRoom, vm: CouncilRoomVM?, onRestart: (() -> Unit)?) {
    var modeMenuOpen by remember { mutableStateOf(false) }
    val modeControlsEnabled = vm != null &&
        room.status.running &&
        room.mode != CouncilRoomMode.SYNTHESIZE
    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            CouncilRoomTopBar(
                room = room,
                onRestart = onRestart,
                modeControlsEnabled = modeControlsEnabled,
                modeMenuOpen = modeMenuOpen,
                onToggleMode = { modeMenuOpen = !modeMenuOpen },
            )
            CouncilTimelineTab(
                room = room,
                vm = vm,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )
        }
        // Mode panel — a roller-blind dropping from just below the top bar, in the
        // app's TopModelMenu idiom (scrim + accent-selected rows).
        if (modeControlsEnabled) {
            CouncilModePanel(
                open = modeMenuOpen,
                current = room.mode,
                onSelect = { mode ->
                    modeMenuOpen = false
                    vm.switchMode(mode)
                },
                onClose = { modeMenuOpen = false },
                modifier = Modifier.padding(top = 57.dp),
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Top app bar — back · title (+ mode menu) · members · overflow
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun CouncilRoomTopBar(
    room: CouncilRoom,
    onRestart: (() -> Unit)?,
    modeControlsEnabled: Boolean,
    modeMenuOpen: Boolean,
    onToggleMode: () -> Unit,
) {
    val chatTheme = LocalChatTheme.current
    val workspace = workspaceColors()
    val nav = LocalNavController.current
    var membersSheetOpen by remember { mutableStateOf(false) }
    var confirmRestart by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(start = 4.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BackButton()

        // Title block — tappable to toggle the mode panel (only while live).
        val titleInteraction = remember { MutableInteractionSource() }
        val chevronRotation by animateFloatAsState(
            targetValue = if (modeMenuOpen) 180f else 0f,
            label = "council-mode-chevron",
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .councilPressBounce(titleInteraction)
                .clip(RoundedCornerShape(10.dp))
                .then(
                    if (modeControlsEnabled) {
                        Modifier.clickable(interactionSource = titleInteraction, indication = null) {
                            onToggleMode()
                        }
                    } else {
                        Modifier
                    },
                )
                .padding(horizontal = 6.dp, vertical = 4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "议会聊天",
                    color = chatTheme.ink,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (modeControlsEnabled) {
                    Icon(
                        imageVector = HugeIcons.ArrowDown01,
                        contentDescription = "切换模式",
                        tint = chatTheme.inkFaint,
                        modifier = Modifier
                            .padding(start = 3.dp)
                            .size(17.dp)
                            .rotate(chevronRotation),
                    )
                }
            }
            CouncilRoomSubtitle(room)
        }

        val membersInteraction = remember { MutableInteractionSource() }
        IconButton(
            onClick = { membersSheetOpen = true },
            interactionSource = membersInteraction,
            modifier = Modifier.councilPressBounce(membersInteraction),
        ) {
            Icon(
                imageVector = HugeIcons.UserGroup,
                contentDescription = "成员与综合",
                tint = workspace.muted,
            )
        }

        val settingsInteraction = remember { MutableInteractionSource() }
        IconButton(
            onClick = { nav.navigate(Screen.SettingExperimentalModelCouncil) },
            interactionSource = settingsInteraction,
            modifier = Modifier.councilPressBounce(settingsInteraction),
        ) {
            Icon(
                imageVector = HugeIcons.Settings03,
                contentDescription = "议会设置",
                tint = workspace.muted,
            )
        }
    }
    HorizontalDivider(color = chatTheme.hair)

    if (membersSheetOpen) {
        CouncilMembersSheet(
            room = room,
            onDismiss = { membersSheetOpen = false },
            // "重新开始" now lives inside the members sheet (only meaningful once
            // the room is terminal). onRestart is non-null exactly then, so the
            // sheet surfaces the action; tapping it opens the confirm dialog here.
            onRequestRestart = onRestart?.let { { confirmRestart = true } },
        )
    }

    if (confirmRestart && onRestart != null) {
        AlertDialog(
            onDismissRequest = { confirmRestart = false },
            title = { Text("重新开始议会？") },
            text = { Text("当前这场讨论的记录会被清空，议会将以最新的成员设置重新开始。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmRestart = false
                    onRestart()
                }) { Text("重新开始") }
            },
            dismissButton = {
                TextButton(onClick = { confirmRestart = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun CouncilRoomSubtitle(room: CouncilRoom) {
    val chatTheme = LocalChatTheme.current
    val workspace = workspaceColors()
    val memberCount = room.participants.count { it.status != CouncilParticipantStatus.DISMISSED }
    val round = room.round.coerceAtLeast(1)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = room.mode.label(),
            color = chatTheme.accent,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
        Text(
            text = " · $memberCount 位成员 · 第 $round 轮",
            color = workspace.muted,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private val CouncilRollerEasing = CubicBezierEasing(0.2f, 0.85f, 0.25f, 1f)

/**
 * Mode panel — a "roller-blind" dropping from just below the top bar, mirroring
 * the chat TopModelMenu idiom: a full-area scrim + a surface2 panel that expands
 * from the top, with accent-highlighted rows. Only the two real discussion modes;
 * 议会启动设置 now lives on the top-bar settings button.
 */
@Composable
private fun CouncilModePanel(
    open: Boolean,
    current: CouncilRoomMode,
    onSelect: (CouncilRoomMode) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val chatTheme = LocalChatTheme.current
    val tokens = LocalAmberTokens.current
    Box(modifier = modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = open,
            enter = fadeIn(tween(260)),
            exit = fadeOut(tween(260)),
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(chatTheme.sheetBackdrop)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onClose,
                    ),
            )
        }
        AnimatedVisibility(
            visible = open,
            enter = expandVertically(tween(320, easing = CouncilRollerEasing), expandFrom = Alignment.Top),
            exit = shrinkVertically(tween(320, easing = CouncilRollerEasing), shrinkTowards = Alignment.Top),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(tokens.surface2)
                    .drawBehind {
                        val y = size.height - 1.dp.toPx()
                        drawRect(
                            color = chatTheme.hair,
                            topLeft = Offset(0f, y),
                            size = Size(size.width, 1.dp.toPx()),
                        )
                    }
                    .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                CouncilModeRow(
                    icon = HugeIcons.BubbleChat,
                    title = "自由群聊",
                    subtitle = "成员各自发散、互相补充",
                    active = current == CouncilRoomMode.EXPLORE,
                    onClick = { onSelect(CouncilRoomMode.EXPLORE) },
                )
                CouncilModeRow(
                    icon = HugeIcons.ArrowReloadHorizontal,
                    title = "辩论",
                    subtitle = "逐位轮流、互相质疑与补充",
                    active = current == CouncilRoomMode.DEBATE,
                    onClick = { onSelect(CouncilRoomMode.DEBATE) },
                )
            }
        }
    }
}

@Composable
private fun CouncilModeRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    active: Boolean,
    onClick: () -> Unit,
) {
    val chatTheme = LocalChatTheme.current
    val tokens = LocalAmberTokens.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .then(if (active) Modifier.background(chatTheme.accentSoft) else Modifier)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (active) chatTheme.accent else tokens.ink3,
            modifier = Modifier.size(20.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (active) chatTheme.accent else chatTheme.ink,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = tokens.ink3,
            )
        }
    }
}

fun CouncilRoomMode.label(): String = when (this) {
    CouncilRoomMode.EXPLORE -> "自由群聊"
    CouncilRoomMode.DEBATE -> "辩论"
    CouncilRoomMode.SYNTHESIZE -> "综合"
}
