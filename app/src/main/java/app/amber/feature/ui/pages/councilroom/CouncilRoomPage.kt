package app.amber.feature.ui.pages.councilroom

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.amber.agent.R
import app.amber.agent.Screen
import app.amber.feature.modelcouncil.CouncilParticipantStatus
import app.amber.feature.modelcouncil.CouncilRoom
import app.amber.feature.modelcouncil.CouncilRoomMode
import app.amber.feature.modelcouncil.running
import app.amber.feature.modelcouncil.terminal
import app.amber.feature.ui.components.nav.BackButton
import app.amber.feature.ui.components.ui.workspaceColors
import app.amber.feature.ui.context.LocalNavController
import app.amber.feature.ui.context.LocalToaster
import app.amber.feature.ui.pages.chat.LocalChatTheme
import app.amber.feature.ui.theme.LocalAmberTokens
import com.dokar.sonner.ToastType
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.ArrowDown
import com.composables.icons.lucide.RefreshCw
import com.composables.icons.lucide.MessageCircle
import com.composables.icons.lucide.Settings
import com.composables.icons.lucide.Users
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
    val isRestarting by vm.isRestarting.collectAsStateWithLifecycle()
    val reopenToken by vm.reopenToken.collectAsStateWithLifecycle()
    val answerError by vm.answerError.collectAsStateWithLifecycle()
    val chatTheme = LocalChatTheme.current
    val toaster = LocalToaster.current

    LaunchedEffect(answerError) {
        val msg = answerError ?: return@LaunchedEffect
        toaster.show(msg, type = ToastType.Error)
        vm.consumeAnswerError()
    }

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
            // Content state drives both the AnimatedContent transition and the
            // composition `key` for a live room. restart() flips isRestarting →
            // the placeholder crossfades in; once the new room lands isRestarting
            // clears and the live body crossfades in with a fresh composition
            // (keyed on reopenToken so timeline's remember state fully resets).
            val contentState = when {
                isRestarting -> CouncilContentState.Restarting
                room == null -> CouncilContentState.Loading
                room!!.status.terminal ->
                    CouncilContentState.Terminal(room!!)
                else -> CouncilContentState.Live(room!!)
            }
            AnimatedContent(
                targetState = contentState,
                transitionSpec = {
                    // Any state change: old fades out + settles down slightly,
                    // new fades in + rises up slightly — reads as a "reset → new
                    // begin" without a jarring hard cut. Short enough (~450ms)
                    // to feel snappy on restart, long enough to register.
                    (fadeIn(tween(350)) +
                        slideInVertically(tween(450)) { full -> full / 8 }) togetherWith
                        (fadeOut(tween(250)) +
                            slideOutVertically(tween(350)) { full -> -full / 8 })
                },
                contentKey = { it::class },
                label = "council-content",
            ) { state ->
                when (state) {
                    CouncilContentState.Loading -> CouncilRoomLoading()
                    CouncilContentState.Restarting -> CouncilRestartingPlaceholder()
                    is CouncilContentState.Terminal ->
                        // Read-only view of a finished/stopped council. The only mutating
                        // action offered here is "restart" (discard + reopen fresh).
                        CouncilRoomBody(state.room, vm = null, onRestart = vm::restart)
                    is CouncilContentState.Live -> key(reopenToken) {
                        // key(reopenToken) forces a brand-new composition on every
                        // restart, so the timeline's remember'd state (entries,
                        // poppedKeys, listState, scroll position) all reset to the
                        // fresh room instead of carrying the old deliberation over.
                        // INTERRUPTED (cold recovery / ask_user pause) needs a restart
                        // exit; terminal rooms use the Terminal branch above.
                        val canRestart =
                            state.room.status == app.amber.feature.modelcouncil.CouncilRoomStatus.INTERRUPTED
                        CouncilRoomBody(
                            state.room,
                            vm = vm,
                            onRestart = if (canRestart) vm::restart else null,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Sealed content state for the [CouncilRoomPage] body. Using a sealed type as
 * the [AnimatedContent] targetState (with `contentKey = { it::class }`) means a
 * transition fires exactly when the *kind* of content changes — initial load,
 * restart-in-progress, finished room, live room — not on every room emit.
 */
private sealed interface CouncilContentState {
    /** Cold-loading the room from storage on first open. */
    data object Loading : CouncilContentState
    /** restart() is closing the old room and opening a new one. */
    data object Restarting : CouncilContentState
    /** A finished/stopped council — read-only, restart is the only action. */
    data class Terminal(val room: CouncilRoom) : CouncilContentState
    /** An active deliberation. key(reopenToken) in the caller resets remember. */
    data class Live(val room: CouncilRoom) : CouncilContentState
}

@Composable
private fun CouncilRestartingPlaceholder() {
    val chatTheme = LocalChatTheme.current
    val workspace = workspaceColors()
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(
                color = chatTheme.accent,
                strokeWidth = 2.4.dp,
                modifier = Modifier.size(28.dp),
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.council_room_restarting),
                style = MaterialTheme.typography.bodyMedium,
                color = workspace.muted,
            )
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
                    text = stringResource(R.string.council_room_chat_title),
                    color = chatTheme.ink,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (modeControlsEnabled) {
                    Icon(
                        imageVector = Lucide.ArrowDown,
                        contentDescription = stringResource(R.string.council_room_switch_mode),
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
                imageVector = Lucide.Users,
                contentDescription = stringResource(R.string.council_room_members_and_synthesis),
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
                imageVector = Lucide.Settings,
                contentDescription = stringResource(R.string.council_room_settings),
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
            title = { Text(stringResource(R.string.council_room_restart_title)) },
            text = { Text(stringResource(R.string.council_room_restart_message)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmRestart = false
                    onRestart()
                }) { Text(stringResource(R.string.council_room_restart)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmRestart = false }) { Text(stringResource(R.string.cancel)) }
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
            text = stringResource(R.string.council_room_subtitle, memberCount, round),
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
                    icon = Lucide.MessageCircle,
                    title = stringResource(R.string.council_room_mode_explore),
                    subtitle = stringResource(R.string.council_room_mode_explore_description),
                    active = current == CouncilRoomMode.EXPLORE,
                    onClick = { onSelect(CouncilRoomMode.EXPLORE) },
                )
                CouncilModeRow(
                    icon = Lucide.RefreshCw,
                    title = stringResource(R.string.council_room_mode_debate),
                    subtitle = stringResource(R.string.council_room_mode_debate_description),
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

@Composable
fun CouncilRoomMode.label(): String = when (this) {
    CouncilRoomMode.EXPLORE -> stringResource(R.string.council_room_mode_explore)
    CouncilRoomMode.DEBATE -> stringResource(R.string.council_room_mode_debate)
    CouncilRoomMode.SYNTHESIZE -> stringResource(R.string.council_room_mode_synthesize)
}
