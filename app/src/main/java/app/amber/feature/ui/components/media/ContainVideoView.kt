package app.amber.feature.ui.components.media

import android.content.Context
import android.media.MediaFormat
import android.media.MediaPlayer
import android.widget.VideoView

/**
 * [VideoView] that measures with contain-fit of a known content size.
 *
 * Stock [VideoView] preserves aspect in `onMeasure`, but Compose `AndroidView`
 * then lays the view out to the parent's exact size. If that parent is a
 * hardcoded 16:9 box, a portrait surface is stretched. This view measures to
 * [containFitVideo] of the prepared display size so the measured box matches
 * the Compose player slot (which is itself contain-fitted into the dialog).
 */
internal class ContainVideoView(context: Context) : VideoView(context) {
    private var contentWidth = 0
    private var contentHeight = 0
    private var boundPlayer: MediaPlayer? = null

    fun setContentSize(width: Int, height: Int) {
        if (width == contentWidth && height == contentHeight) return
        contentWidth = width
        contentHeight = height
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val maxWidth = MeasureSpec.getSize(widthMeasureSpec).coerceAtLeast(0)
        val maxHeight = MeasureSpec.getSize(heightMeasureSpec).coerceAtLeast(0)
        val content = resolvedVideoContentSize(contentWidth, contentHeight, rotationDegrees = 0)
        val fitted = containFitVideo(content.width, content.height, maxWidth, maxHeight)
        setMeasuredDimension(fitted.width, fitted.height)
    }

    fun applyTrackMetrics(player: MediaPlayer): VideoTrackMetrics {
        val next = videoTrackMetricsFrom(player)
        val size = displaySizeFromPlayerMetrics(next)
        setContentSize(size.width, size.height)
        return next
    }

    fun bindPreparedPlayer(player: MediaPlayer) {
        boundPlayer?.setOnVideoSizeChangedListener(null)
        boundPlayer = player
    }

    /**
     * Drop listeners, controller, and the bound [MediaPlayer] before
     * [stopPlayback]. Safe to call more than once.
     */
    fun releasePlayback() {
        boundPlayer?.setOnVideoSizeChangedListener(null)
        boundPlayer = null
        setOnPreparedListener(null)
        setOnErrorListener(null)
        setOnCompletionListener(null)
        setMediaController(null)
        runCatching { stopPlayback() }
    }
}

internal fun videoTrackMetricsFrom(player: MediaPlayer): VideoTrackMetrics {
    val reportedWidth = player.videoWidth
    val reportedHeight = player.videoHeight
    val tracks = runCatching { player.trackInfo }.getOrNull()
        ?: return VideoTrackMetrics(reportedWidth, reportedHeight)
    for (info in tracks) {
        if (info.trackType != MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_VIDEO) continue
        val format = info.format ?: continue
        return VideoTrackMetrics(
            reportedWidth = reportedWidth,
            reportedHeight = reportedHeight,
            codedWidth = format.optionalInt(MediaFormat.KEY_WIDTH),
            codedHeight = format.optionalInt(MediaFormat.KEY_HEIGHT),
            rotationDegrees = format.optionalInt(MediaFormat.KEY_ROTATION),
        )
    }
    return VideoTrackMetrics(reportedWidth, reportedHeight)
}

private fun MediaFormat.optionalInt(key: String): Int {
    return runCatching {
        if (containsKey(key)) getInteger(key) else 0
    }.getOrDefault(0)
}
