package app.amber.feature.ui.components.media

import java.util.concurrent.atomic.AtomicBoolean

/**
 * One session per `VideoView` instance.
 *
 * All player callbacks (`onPrepared`, size, error) and user play/pause go
 * through this object. [release] is idempotent and is the single way to drop
 * a view (fail / close / retry / URL change / `onRelease` / `onDispose`).
 * After release, dispatch methods do not invoke the player-read lambdas and
 * do not request `start()`, so a queued `post { }` cannot touch a stopped
 * `MediaPlayer`.
 *
 * A new view must construct a **new** session. Releasing this one never
 * blocks a later session.
 *
 * Auto-start happens only on the first [dispatchPrepared] while the host is
 * in the foreground. A later prepare (surface recreate after `ON_STOP`) does
 * not start. [onHostStarted] does not start playback; the user resumes with
 * [dispatchUserPlay].
 */
internal class VideoPlaybackSession(
    initiallyForeground: Boolean,
) {
    private val alive = AtomicBoolean(true)
    private val foreground = AtomicBoolean(initiallyForeground)
    private val prepared = AtomicBoolean(false)

    fun isAlive(): Boolean = alive.get()

    fun isForeground(): Boolean = foreground.get()

    fun isPrepared(): Boolean = prepared.get()

    fun onHostStarted() {
        if (!alive.get()) return
        foreground.set(true)
    }

    fun onHostStopped() {
        if (!alive.get()) return
        foreground.set(false)
    }

    fun release() {
        alive.set(false)
    }

    /**
     * @param readMetrics called only when this session is still alive.
     *   Do player I/O (width/height, bind size listener) inside this lambda.
     */
    fun dispatchPrepared(readMetrics: () -> VideoTrackMetrics): VideoPreparedDispatch {
        if (!alive.get()) return VideoPreparedDispatch.Ignored
        val metrics = readMetrics()
        if (!alive.get()) return VideoPreparedDispatch.Ignored
        val firstPrepare = !prepared.getAndSet(true)
        return VideoPreparedDispatch.Ready(
            metrics = metrics,
            startPlayback = firstPrepare && foreground.get(),
        )
    }

    fun dispatchVideoSizeChanged(readMetrics: () -> VideoTrackMetrics): VideoTrackMetrics? {
        if (!alive.get()) return null
        val metrics = readMetrics()
        if (!alive.get()) return null
        return metrics
    }

    /** `true` when the current UI may show the failed state. */
    fun dispatchError(): Boolean = alive.get()

    /** `true` when the view should `start()` from a foreground user tap. */
    fun dispatchUserPlay(): Boolean = alive.get() && foreground.get() && prepared.get()

    /** `true` when the view should `pause()` from a user tap. */
    fun dispatchUserPause(): Boolean = alive.get()
}

internal sealed class VideoPreparedDispatch {
    data object Ignored : VideoPreparedDispatch()
    data class Ready(
        val metrics: VideoTrackMetrics,
        val startPlayback: Boolean,
    ) : VideoPreparedDispatch()
}
