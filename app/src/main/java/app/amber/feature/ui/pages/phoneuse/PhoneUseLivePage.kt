package app.amber.feature.ui.pages.phoneuse

import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import app.amber.agent.R
import app.amber.core.automation.display.VirtualDisplayRunControlState
import app.amber.core.automation.display.VirtualDisplayRunRegistry
import app.amber.core.automation.display.VirtualDisplaySessionState
import app.amber.feature.ui.components.ds.amberCanvas
import app.amber.feature.ui.components.nav.BackButton
import app.amber.feature.ui.components.ui.WorkspaceTopBar
import app.amber.feature.ui.components.ui.workspaceColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Minimum interval between preview frame refreshes (200ms = max 5fps per R3 L9). */
private const val PREVIEW_FRAME_INTERVAL_MS = 200L

@Composable
fun PhoneUseLivePage(runId: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val activeRuns by VirtualDisplayRunRegistry.activeRuns.collectAsStateWithLifecycle()
    val activeRun = activeRuns[runId]

    var previewImage by remember(activeRun) { mutableStateOf<ImageBitmap?>(null) }
    var takeoverPromptPackage by remember(runId) { mutableStateOf<String?>(null) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    val controlState = activeRun?.controlState?.collectAsStateWithLifecycle()?.value
        ?: VirtualDisplayRunControlState.STOPPED
    val sessionState = activeRun?.sessionState?.collectAsStateWithLifecycle()?.value
        ?: VirtualDisplaySessionState.CLOSED
    val frameSource = activeRun?.frameSource
    val blankFrames = remember(frameSource) {
        frameSource?.latestStats?.map { it?.unobservable == true }?.distinctUntilChanged()
    }
    val isUnobservable = sessionState == VirtualDisplaySessionState.UNOBSERVABLE ||
        blankFrames?.collectAsStateWithLifecycle(initialValue = false)?.value == true

    LaunchedEffect(lifecycle, frameSource, isUnobservable) {
        if (frameSource == null || isUnobservable) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            var lastSequence = -1L
            try {
                while (isActive && !frameSource.isClosed) {
                    val stats = frameSource.latestStats.value
                    if (stats?.unobservable == true) {
                        previewImage = null
                    } else if (stats != null && stats.sequence != lastSequence) {
                        previewImage = withContext(Dispatchers.Default) {
                            frameSource.copyLatestBitmap()?.asImageBitmap()
                        }
                        lastSequence = stats.sequence
                    }
                    delay(PREVIEW_FRAME_INTERVAL_MS)
                }
            } finally {
                // Compose may still draw the previous frame. Release the reference and let the
                // renderer determine when the bitmap's backing storage is no longer in use.
                previewImage = null
            }
        }
    }

    Scaffold(
        topBar = {
            WorkspaceTopBar(
                title = stringResource(R.string.phone_use_vd_live_title),
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
            )
        },
        modifier = Modifier
            .nestedScroll(scrollBehavior.nestedScrollConnection)
            .amberCanvas(),
        containerColor = Color.Transparent,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (activeRun == null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.phone_use_vd_live_no_active_run),
                        style = MaterialTheme.typography.bodyMedium,
                        color = workspaceColors().muted,
                    )
                }
            } else {
                val statusLabel = when {
                    sessionState == VirtualDisplaySessionState.UNOBSERVABLE || isUnobservable ->
                        stringResource(R.string.phone_use_vd_live_status_unobservable)
                    sessionState == VirtualDisplaySessionState.OUTCOME_UNKNOWN ->
                        stringResource(R.string.phone_use_vd_live_status_outcome_unknown)
                    controlState == VirtualDisplayRunControlState.PAUSED ->
                        stringResource(R.string.phone_use_vd_live_status_paused)
                    controlState == VirtualDisplayRunControlState.TAKEN_OVER ->
                        stringResource(R.string.phone_use_vd_live_status_taken_over)
                    controlState == VirtualDisplayRunControlState.STOPPED ->
                        stringResource(R.string.phone_use_vd_live_status_stopped)
                    else ->
                        stringResource(R.string.phone_use_vd_live_status_running)
                }

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = workspaceColors().paper,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = activeRun.packageName,
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = statusLabel,
                                style = MaterialTheme.typography.labelMedium,
                                color = if (isUnobservable || sessionState == VirtualDisplaySessionState.OUTCOME_UNKNOWN) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.primary
                                },
                            )
                        }
                        Text(
                            text = activeRun.goal,
                            style = MaterialTheme.typography.bodySmall,
                            color = workspaceColors().muted,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = stringResource(
                                R.string.phone_use_vd_live_preview_meta,
                                activeRun.session.displayId,
                                activeRun.session.generation,
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = workspaceColors().muted,
                        )
                    }
                }

                // In-app bounded ≤5fps preview surface (never a system floating video window).
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.Black.copy(alpha = 0.88f)),
                    contentAlignment = Alignment.Center,
                ) {
                    val image = previewImage
                    when {
                        isUnobservable -> {
                            Text(
                                text = stringResource(R.string.phone_use_vd_live_preview_unobservable),
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White,
                                modifier = Modifier.padding(24.dp),
                            )
                        }
                        image != null -> {
                            val aspect = (image.width.toFloat() / image.height.coerceAtLeast(1).toFloat())
                                .coerceIn(0.35f, 1.5f)
                            Image(
                                bitmap = image,
                                contentDescription = stringResource(R.string.phone_use_vd_live_title),
                                contentScale = ContentScale.Fit,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .aspectRatio(aspect, matchHeightConstraintsFirst = true),
                            )
                        }
                        else -> {
                            Text(
                                text = stringResource(R.string.phone_use_vd_live_preview_waiting),
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White.copy(alpha = 0.72f),
                                modifier = Modifier.padding(24.dp),
                            )
                        }
                    }
                }

                // Controls: Pause/Resume, Takeover, Emergency Stop (≥48dp touch target height).
                val actionsEnabled = controlState == VirtualDisplayRunControlState.RUNNING ||
                    controlState == VirtualDisplayRunControlState.PAUSED
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = {
                            if (controlState == VirtualDisplayRunControlState.PAUSED) {
                                activeRun.resume()
                            } else {
                                activeRun.pause()
                            }
                        },
                        enabled = actionsEnabled,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp),
                    ) {
                        Text(
                            text = if (controlState == VirtualDisplayRunControlState.PAUSED) {
                                stringResource(R.string.phone_use_vd_live_btn_resume)
                            } else {
                                stringResource(R.string.phone_use_vd_live_btn_pause)
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    OutlinedButton(
                        onClick = {
                            val targetPkg = activeRun.packageName
                            scope.launch {
                                activeRun.takeover()
                                previewImage = null
                                takeoverPromptPackage = targetPkg
                            }
                        },
                        enabled = actionsEnabled,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.phone_use_vd_live_btn_takeover),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    Button(
                        onClick = {
                            scope.launch {
                                activeRun.emergencyStop()
                                previewImage = null
                            }
                        },
                        enabled = controlState != VirtualDisplayRunControlState.STOPPED,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError,
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.phone_use_vd_live_btn_emergency_stop),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }

    // R3 L8: After takeover releases the virtual display, let the user choose whether to open
    // the target app on Display 0 (explicitly not promising lossless state migration).
    val promptPkg = takeoverPromptPackage
    if (promptPkg != null) {
        AlertDialog(
            onDismissRequest = { takeoverPromptPackage = null },
            title = { Text(stringResource(R.string.phone_use_vd_live_takeover_dialog_title)) },
            text = {
                Text(stringResource(R.string.phone_use_vd_live_takeover_dialog_body, promptPkg))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        takeoverPromptPackage = null
                        val intent = context.packageManager.getLaunchIntentForPackage(promptPkg)
                        if (intent != null) {
                            runCatching {
                                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            }
                        }
                    },
                ) {
                    Text(stringResource(R.string.phone_use_vd_live_takeover_open_main))
                }
            },
            dismissButton = {
                TextButton(onClick = { takeoverPromptPackage = null }) {
                    Text(stringResource(R.string.phone_use_vd_live_takeover_stay))
                }
            },
        )
    }
}
