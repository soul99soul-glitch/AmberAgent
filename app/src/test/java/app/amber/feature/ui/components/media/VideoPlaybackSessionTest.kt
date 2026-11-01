package app.amber.feature.ui.components.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoPlaybackSessionTest {

    @Test
    fun normalPlayPausesOnStopAndNeedsManualResume() {
        val session = VideoPlaybackSession(initiallyForeground = true)
        var starts = 0
        val ready = session.dispatchPrepared { VideoTrackMetrics(1920, 1080) } as VideoPreparedDispatch.Ready
        if (ready.startPlayback) starts++
        assertEquals(1, starts)
        session.onHostStopped()
        session.onHostStarted()
        assertEquals("must not auto-start when returning to foreground", 1, starts)
        if (session.dispatchUserPlay()) starts++
        assertEquals(2, starts)
    }

    @Test
    fun preparedWhileForegroundAutoStarts() {
        val session = VideoPlaybackSession(initiallyForeground = true)
        val action = session.dispatchPrepared { VideoTrackMetrics(1920, 1080) }
        val ready = action as VideoPreparedDispatch.Ready
        assertEquals(VideoTrackMetrics(1920, 1080), ready.metrics)
        assertTrue(ready.startPlayback)
        assertTrue(session.isPrepared())
    }

    @Test
    fun preparedAfterStopDoesNotAutoStart() {
        val session = VideoPlaybackSession(initiallyForeground = true)
        session.onHostStopped()
        val ready = session.dispatchPrepared { VideoTrackMetrics(1080, 1920) } as VideoPreparedDispatch.Ready
        assertFalse(ready.startPlayback)
        assertTrue(session.isPrepared())
    }

    @Test
    fun hostStartAfterPreparedInBackgroundDoesNotAutoStartUserCanResume() {
        val session = VideoPlaybackSession(initiallyForeground = true)
        session.onHostStopped()
        val ready = session.dispatchPrepared { VideoTrackMetrics(1920, 1080) } as VideoPreparedDispatch.Ready
        assertFalse(ready.startPlayback)
        session.onHostStarted()
        assertTrue(session.isForeground())
        assertTrue(session.dispatchUserPlay())
    }

    @Test
    fun reprepareAfterStopDoesNotAutoStart() {
        val session = VideoPlaybackSession(initiallyForeground = true)
        val first = session.dispatchPrepared { VideoTrackMetrics(1920, 1080) } as VideoPreparedDispatch.Ready
        assertTrue(first.startPlayback)
        session.onHostStopped()
        session.onHostStarted()
        val second = session.dispatchPrepared { VideoTrackMetrics(1920, 1080) } as VideoPreparedDispatch.Ready
        assertFalse("surface recreate after ON_STOP must not auto-start", second.startPlayback)
        assertTrue(session.dispatchUserPlay())
    }

    @Test
    fun stopThenForegroundAllowsALaterPrepareToAutoStart() {
        val session = VideoPlaybackSession(initiallyForeground = true)
        session.onHostStopped()
        session.onHostStarted()
        val ready = session.dispatchPrepared { VideoTrackMetrics(16, 9) } as VideoPreparedDispatch.Ready
        assertTrue(ready.startPlayback)
    }

    @Test
    fun constructedWhileStoppedDoesNotAutoStartUntilUserPlay() {
        val session = VideoPlaybackSession(initiallyForeground = false)
        val ready = session.dispatchPrepared { VideoTrackMetrics(8, 8) } as VideoPreparedDispatch.Ready
        assertFalse(ready.startPlayback)
        session.onHostStarted()
        assertTrue(session.dispatchUserPlay())
    }

    @Test
    fun userPlayWhileBackgroundIsIgnored() {
        val session = VideoPlaybackSession(initiallyForeground = true)
        session.dispatchPrepared { VideoTrackMetrics(8, 8) }
        session.onHostStopped()
        assertFalse(session.dispatchUserPlay())
        assertTrue(session.dispatchUserPause())
    }

    @Test
    fun releaseThenLatePreparedDoesNotReadPlayerOrStart() {
        val session = VideoPlaybackSession(initiallyForeground = true)
        session.release()
        var reads = 0
        val action = session.dispatchPrepared {
            reads++
            error("released MediaPlayer must not be read")
        }
        assertEquals(VideoPreparedDispatch.Ignored, action)
        assertEquals(0, reads)
        assertFalse(session.dispatchUserPlay())
    }

    @Test
    fun releaseThenLateSizeDoesNotReadPlayer() {
        val session = VideoPlaybackSession(initiallyForeground = true)
        session.dispatchPrepared { VideoTrackMetrics(1920, 1080) }
        session.release()
        var reads = 0
        val metrics = session.dispatchVideoSizeChanged {
            reads++
            error("released MediaPlayer must not be read")
        }
        assertNull(metrics)
        assertEquals(0, reads)
    }

    @Test
    fun lateErrorOnReleasedSessionDoesNotApplyAndDoesNotAffectNewSession() {
        val old = VideoPlaybackSession(initiallyForeground = true)
        val next = VideoPlaybackSession(initiallyForeground = true)
        old.release()
        assertFalse("old view error must not mark UI failed", old.dispatchError())
        assertTrue("new url/retry session still accepts its own error", next.dispatchError())
        val ready = next.dispatchPrepared { VideoTrackMetrics(640, 360) } as VideoPreparedDispatch.Ready
        assertTrue(ready.startPlayback)
    }

    @Test
    fun queuedPreparedAfterReleaseDoesNotReadPlayer() {
        val session = VideoPlaybackSession(initiallyForeground = true)
        val queued = mutableListOf<() -> Unit>()
        var started = false
        queued += {
            when (val action = session.dispatchPrepared { error("released MediaPlayer") }) {
                VideoPreparedDispatch.Ignored -> Unit
                is VideoPreparedDispatch.Ready -> started = action.startPlayback
            }
        }
        session.release()
        queued.forEach { it() }
        assertFalse(started)
        assertFalse(session.dispatchUserPlay())
    }

    @Test
    fun releaseIsIdempotentAndNewSessionCanStart() {
        val old = VideoPlaybackSession(initiallyForeground = true)
        old.release()
        old.release()
        assertEquals(
            VideoPreparedDispatch.Ignored,
            old.dispatchPrepared { error("old") },
        )
        val next = VideoPlaybackSession(initiallyForeground = true)
        val ready = next.dispatchPrepared { VideoTrackMetrics(320, 240) } as VideoPreparedDispatch.Ready
        assertTrue(ready.startPlayback)
        assertTrue(next.dispatchUserPause())
    }
}
