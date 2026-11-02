package app.amber.feature.ui.components.media

import android.app.Application
import android.view.View
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ContainVideoViewTest {

    @Test
    fun measureContainFitsPortraitIntoLandscapeExactConstraints() {
        val view = ContainVideoView(RuntimeEnvironment.getApplication())
        view.setContentSize(1080, 1920)
        view.measure(
            View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
        )
        assertEquals(607, view.measuredWidth)
        assertEquals(1080, view.measuredHeight)
    }

    @Test
    fun measureDoesNotUseWidthTimesRatioWhenHeightIsShort() {
        val view = ContainVideoView(RuntimeEnvironment.getApplication())
        view.setContentSize(1080, 1920)
        view.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY),
        )
        assertEquals(450, view.measuredWidth)
        assertEquals(800, view.measuredHeight)
    }

    @Test
    fun unknownContentMeasuresAs16By9InsideConstraints() {
        val view = ContainVideoView(RuntimeEnvironment.getApplication())
        view.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
        )
        assertEquals(1080, view.measuredWidth)
        assertEquals(607, view.measuredHeight)
    }

    @Test
    fun releasePlaybackClearsListenersAndIsIdempotent() {
        val view = ContainVideoView(RuntimeEnvironment.getApplication())
        view.setOnPreparedListener { }
        view.setOnErrorListener { _, _, _ -> true }
        view.setOnCompletionListener { }
        view.releasePlayback()
        view.releasePlayback()
    }
}
