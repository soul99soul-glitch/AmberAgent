package app.amber.feature.ui.components.media

import android.content.Context
import android.content.Intent
import android.widget.VideoView
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import androidx.core.net.toFile
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.amber.agent.R
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Pause
import com.composables.icons.lucide.Play

/**
 * Dialog video player: created only after the user taps a timeline chip.
 *
 * Display size follows prepared `videoWidth`/`videoHeight` (and later size
 * changes), contain-fitted into the dialog's inset-padded slot minus a play/
 * pause bar. Unknown sizes use 16:9.
 *
 * Original dialog had no MediaController and no Compose transport. This adds
 * a play/pause/resume bar. `ON_STOP` pauses and never auto-starts on
 * `ON_START`; if prepare finished in the background the user taps play.
 *
 * Each [VideoView] owns a [VideoPlaybackSession]. Fail / close / retry / URL
 * change / `onRelease` all [VideoPlaybackSession.release] that instance so a
 * late `onPrepared` cannot read or start a stopped player.
 */
@Composable
internal fun VideoPlaybackDialog(url: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val density = LocalDensity.current
    var failed by remember(url) { mutableStateOf(false) }
    var retryNonce by remember(url) { mutableIntStateOf(0) }
    var metrics by remember(url, retryNonce) { mutableStateOf(VideoTrackMetrics(0, 0)) }
    var playing by remember(url, retryNonce) { mutableStateOf(false) }
    var prepared by remember(url, retryNonce) { mutableStateOf(false) }
    val playerView = remember(url, retryNonce) { mutableStateOf<VideoView?>(null) }
    val session = remember(url, retryNonce) {
        VideoPlaybackSession(
            initiallyForeground = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED),
        )
    }

    DisposableEffect(lifecycleOwner, url, retryNonce) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> session.onHostStarted()
                Lifecycle.Event.ON_STOP -> {
                    session.onHostStopped()
                    runCatching { playerView.value?.pause() }
                    playing = false
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            session.release()
            val view = playerView.value
            playerView.value = null
            if (view is ContainVideoView) {
                view.releasePlayback()
            } else {
                view?.setOnPreparedListener(null)
                view?.setOnErrorListener(null)
                view?.setOnCompletionListener(null)
                runCatching { view?.stopPlayback() }
            }
        }
    }

    val content = displaySizeFromPlayerMetrics(metrics)
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ),
            contentAlignment = Alignment.Center,
        ) {
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(16.dp),
                contentAlignment = Alignment.Center,
            ) {
                val preferredControlPx = with(density) { 48.dp.roundToPx() }
                val reserved = videoControlHeightPx(constraints.maxHeight, preferredControlPx)
                val fitted = containFitVideo(
                    contentWidth = content.width,
                    contentHeight = content.height,
                    availableWidth = constraints.maxWidth,
                    availableHeight = (constraints.maxHeight - reserved).coerceAtLeast(0),
                )
                val panelWidth = fitted.width.coerceAtLeast(reserved.coerceAtMost(constraints.maxWidth))
                val panelHeight = fitted.height + reserved
                Surface(
                    modifier = Modifier
                        .size(
                            width = with(density) { panelWidth.toDp() },
                            height = with(density) { panelHeight.toDp() },
                        )
                        .clipToBounds()
                        .then(Modifier.consumeClicks()),
                    color = Color.Black,
                ) {
                    if (failed) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = stringResource(R.string.chat_video_playback_failed),
                                    color = Color.White,
                                )
                                TextButton(
                                    onClick = {
                                        failed = false
                                        metrics = VideoTrackMetrics(0, 0)
                                        playing = false
                                        prepared = false
                                        retryNonce++
                                    },
                                ) {
                                    Text(stringResource(R.string.retry))
                                }
                                val external = remember(url) {
                                    runCatching {
                                        Intent(Intent.ACTION_VIEW).apply {
                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                            data = videoPlaybackUri(context, url)
                                        }
                                    }.getOrNull()
                                }
                                if (external != null) {
                                    TextButton(onClick = { context.startActivity(Intent.createChooser(external, null)) }) {
                                        Text(stringResource(R.string.chat_video_open_external))
                                    }
                                }
                            }
                        }
                    } else {
                        Column(modifier = Modifier.fillMaxSize()) {
                            key(url, retryNonce) {
                                AndroidView(
                                    modifier = Modifier
                                        .size(
                                            width = with(density) { fitted.width.toDp() },
                                            height = with(density) { fitted.height.toDp() },
                                        )
                                        .then(Modifier.consumeClicks()),
                                    factory = { ctx ->
                                        ContainVideoView(ctx).apply {
                                            playerView.value = this
                                            setOnPreparedListener { player ->
                                                post {
                                                    when (
                                                        val action = session.dispatchPrepared {
                                                            bindPreparedPlayer(player)
                                                            player.isLooping = false
                                                            player.setOnVideoSizeChangedListener { changed, _, _ ->
                                                                post {
                                                                    val next = session.dispatchVideoSizeChanged {
                                                                        applyTrackMetrics(changed)
                                                                    } ?: return@post
                                                                    metrics = next
                                                                }
                                                            }
                                                            applyTrackMetrics(player)
                                                        }
                                                    ) {
                                                        VideoPreparedDispatch.Ignored -> Unit
                                                        is VideoPreparedDispatch.Ready -> {
                                                            metrics = action.metrics
                                                            prepared = true
                                                            if (action.startPlayback) {
                                                                start()
                                                                playing = true
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                            setOnErrorListener { _, _, _ ->
                                                if (session.dispatchError()) {
                                                    failed = true
                                                    playing = false
                                                }
                                                true
                                            }
                                            setOnCompletionListener {
                                                if (session.isAlive()) playing = false
                                            }
                                            runCatching { setVideoURI(videoPlaybackUri(ctx, url)) }
                                                .onFailure {
                                                    if (session.dispatchError()) failed = true
                                                }
                                        }
                                    },
                                    update = { view ->
                                        view.setContentSize(content.width, content.height)
                                    },
                                    onRelease = { view ->
                                        session.release()
                                        view.releasePlayback()
                                        if (playerView.value === view) playerView.value = null
                                    },
                                )
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(with(density) { reserved.toDp() })
                                    .then(Modifier.consumeClicks()),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center,
                            ) {
                                IconButton(
                                    enabled = prepared,
                                    onClick = {
                                        val view = playerView.value ?: return@IconButton
                                        if (playing) {
                                            if (session.dispatchUserPause()) {
                                                runCatching { view.pause() }
                                                playing = false
                                            }
                                        } else if (session.dispatchUserPlay()) {
                                            runCatching { view.start() }
                                            playing = true
                                        }
                                    },
                                ) {
                                    Icon(
                                        imageVector = if (playing) Lucide.Pause else Lucide.Play,
                                        contentDescription = stringResource(
                                            if (playing) R.string.chat_video_pause else R.string.chat_video_play,
                                        ),
                                        tint = Color.White,
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

internal fun videoPlaybackUri(context: Context, url: String) =
    if (url.startsWith("http")) {
        url.toUri()
    } else {
        FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            url.toUri().toFile(),
        )
    }

@Composable
private fun Modifier.consumeClicks(): Modifier = clickable(
    interactionSource = remember { MutableInteractionSource() },
    indication = null,
    onClick = {},
)
