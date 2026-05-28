package app.amber.feature.ui.components.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Forward02
import me.rerere.hugeicons.stroke.Pause
import me.rerere.hugeicons.stroke.Play
import app.amber.feature.ui.context.LocalTTSState
import app.amber.feature.ui.hooks.CustomTtsState
import me.rerere.tts.model.PlaybackState
import me.rerere.tts.model.PlaybackStatus

@Composable
fun TTSController() {
    val context = LocalContext.current
    val ttsState = LocalTTSState.current

    val isSpeaking by ttsState.isSpeaking.collectAsState()
    var isVisible by remember { mutableStateOf(false) }

    LaunchedEffect(isSpeaking) {
        if (isSpeaking) isVisible = true
    }

    FloatingWindow(
        tag = "tts_controller",
        visibility = isVisible
    ) {
        val playbackState by ttsState.playbackState.collectAsState()
        var expand by remember { mutableStateOf(false) }
        val workspace = workspaceColors()
        // Notion-like card: rounded rectangle, paper background, hairline border, no elevation —
        // matches the rest of the experimental UI (settings, workspace tools, subagent cards).
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = workspace.paper,
            contentColor = workspace.ink,
            border = BorderStroke(1.dp, workspace.hairline),
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
            modifier = Modifier.padding(8.dp),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 3.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PlayPauseButton(playbackState = playbackState, ttsState = ttsState, workspace = workspace)

                ControllerIconButton(
                    icon = HugeIcons.Cancel01,
                    tone = WorkspaceTone.Danger,
                    workspace = workspace,
                    onClick = {
                        ttsState.stop()
                        isVisible = false
                    },
                )

                AnimatedVisibility(expand) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SpeedButton(playbackState, ttsState, workspace)
                        ControllerIconButton(
                            icon = HugeIcons.Forward02,
                            tone = WorkspaceTone.Neutral,
                            workspace = workspace,
                            onClick = { ttsState.fastForward(5000) },
                        )
                    }
                }

                ControllerIconButton(
                    icon = if (expand) HugeIcons.ArrowLeft01 else HugeIcons.ArrowRight01,
                    tone = WorkspaceTone.Neutral,
                    workspace = workspace,
                    onClick = { expand = !expand },
                )
            }
        }
    }
}

@Composable
private fun ControllerIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tone: WorkspaceTone,
    workspace: WorkspaceColors,
    onClick: () -> Unit,
) {
    val tint = when (tone) {
        WorkspaceTone.Danger -> workspace.red
        WorkspaceTone.Accent -> workspace.blue
        WorkspaceTone.Success -> workspace.green
        WorkspaceTone.Warning -> workspace.amber
        WorkspaceTone.Neutral -> workspace.muted
    }
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(14.dp),
        )
    }
}

@Composable
private fun PlayPauseButton(
    playbackState: PlaybackState,
    ttsState: CustomTtsState,
    workspace: WorkspaceColors,
) {
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(RoundedCornerShape(6.dp))
            .clickable {
                if (playbackState.status == PlaybackStatus.Playing) ttsState.pause()
                else ttsState.resume()
            },
        contentAlignment = Alignment.Center,
    ) {
        // Thin progress ring around the play/pause icon: continuous chunk progress,
        // no double-ring + no flashy tertiary container background.
        val activePlayback = playbackState.status == PlaybackStatus.Playing ||
            playbackState.status == PlaybackStatus.Buffering ||
            playbackState.status == PlaybackStatus.Paused
        if (activePlayback && playbackState.totalChunks > 0) {
            CircularProgressIndicator(
                progress = {
                    val total = playbackState.totalChunks.coerceAtLeast(1)
                    playbackState.currentChunkIndex.toFloat() / total
                },
                color = workspace.ink,
                trackColor = Color.Transparent,
                strokeWidth = 1.5.dp,
                modifier = Modifier.size(24.dp),
            )
        }
        Icon(
            imageVector = if (playbackState.status == PlaybackStatus.Playing) HugeIcons.Pause else HugeIcons.Play,
            contentDescription = null,
            tint = workspace.ink,
            modifier = Modifier.size(13.dp),
        )
    }
}

@Composable
private fun SpeedButton(
    playbackState: PlaybackState,
    ttsState: CustomTtsState,
    workspace: WorkspaceColors,
) {
    val nextSpeed = when (playbackState.speed) {
        0.8f -> 1.0f
        1.0f -> 1.2f
        1.2f -> 1.5f
        1.5f -> 0.8f
        else -> 1.0f
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable { ttsState.setSpeed(nextSpeed) }
            .padding(horizontal = 6.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "${"%.1f".format(playbackState.speed)}×",
            style = MaterialTheme.typography.labelSmall,
            color = workspace.muted,
        )
    }
}
