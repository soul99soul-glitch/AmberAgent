package app.amber.feature.ui.components.media

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoDisplaySizeTest {

    @Test
    fun landscapeUsesReportedSizeWithoutRotation() {
        assertEquals(VideoPxSize(1920, 1080), effectiveVideoSize(1920, 1080, 0))
        assertEquals(VideoPxSize(1920, 1080), displaySizeFromPlayerMetrics(VideoTrackMetrics(1920, 1080)))
    }

    @Test
    fun portraitUsesReportedSizeWithoutRotation() {
        assertEquals(VideoPxSize(1080, 1920), effectiveVideoSize(1080, 1920, 0))
    }

    @Test
    fun squareKeepsAxes() {
        assertEquals(VideoPxSize(1080, 1080), effectiveVideoSize(1080, 1080, 0))
        assertEquals(VideoPxSize(1080, 1080), effectiveVideoSize(1080, 1080, 180))
    }

    @Test
    fun rotation90SwapsAxesOnce() {
        assertEquals(VideoPxSize(1080, 1920), effectiveVideoSize(1920, 1080, 90))
        assertEquals(VideoPxSize(1920, 1080), effectiveVideoSize(1080, 1920, 90))
        assertEquals(VideoPxSize(1080, 1920), effectiveVideoSize(1920, 1080, 270))
    }

    @Test
    fun rotationNormalizesNegativeAndWrapAround() {
        assertEquals(270, normalizeRotationDegrees(-90))
        assertEquals(90, normalizeRotationDegrees(450))
        assertEquals(0, normalizeRotationDegrees(360))
        assertEquals(90, normalizeRotationDegrees(91))
        assertEquals(0, normalizeRotationDegrees(44))
        assertEquals(VideoPxSize(1080, 1920), effectiveVideoSize(1920, 1080, -90))
        assertEquals(VideoPxSize(1080, 1920), effectiveVideoSize(1920, 1080, 450))
    }

    @Test
    fun alreadySwappedPlayerDoesNotRotateAgain() {
        val rotation = rotationToApplyOnce(
            reportedWidth = 1080,
            reportedHeight = 1920,
            codedWidth = 1920,
            codedHeight = 1080,
            rotationDegrees = 90,
        )
        assertEquals(0, rotation)
        assertEquals(
            VideoPxSize(1080, 1920),
            displaySizeFromPlayerMetrics(
                VideoTrackMetrics(
                    reportedWidth = 1080,
                    reportedHeight = 1920,
                    codedWidth = 1920,
                    codedHeight = 1080,
                    rotationDegrees = 90,
                ),
            ),
        )
    }

    @Test
    fun codedMatchesReportedAppliesRotationOnce() {
        assertEquals(
            VideoPxSize(1080, 1920),
            displaySizeFromPlayerMetrics(
                VideoTrackMetrics(
                    reportedWidth = 1920,
                    reportedHeight = 1080,
                    codedWidth = 1920,
                    codedHeight = 1080,
                    rotationDegrees = 90,
                ),
            ),
        )
    }

    @Test
    fun zeroNegativeAndUnknownFallBackTo16By9() {
        assertEquals(VideoPxSize(0, 0), effectiveVideoSize(0, 1080, 0))
        assertEquals(VideoPxSize(0, 0), effectiveVideoSize(1920, 0, 90))
        assertEquals(VideoPxSize(0, 0), effectiveVideoSize(-1920, 1080, 0))
        assertEquals(VideoPxSize(0, 0), effectiveVideoSize(1920, -1080, 0))
        assertEquals(
            VideoPxSize(FALLBACK_VIDEO_ASPECT_WIDTH, FALLBACK_VIDEO_ASPECT_HEIGHT),
            resolvedVideoContentSize(0, 0, 0),
        )
        assertEquals(
            VideoPxSize(FALLBACK_VIDEO_ASPECT_WIDTH, FALLBACK_VIDEO_ASPECT_HEIGHT),
            displaySizeFromPlayerMetrics(VideoTrackMetrics(0, 0)),
        )
        assertEquals(
            VideoPxSize(FALLBACK_VIDEO_ASPECT_WIDTH, FALLBACK_VIDEO_ASPECT_HEIGHT),
            resolvedVideoContentSize(-8, 4, 90),
        )
    }

    @Test
    fun landscapeFitsWidthOnPortraitPhone() {
        val fitted = containFitVideo(1920, 1080, availableWidth = 1080, availableHeight = 1920)
        assertEquals(VideoPxSize(1080, 607), fitted)
        assertFits(fitted, 1080, 1920)
        assertAspectNear(1920, 1080, fitted)
    }

    @Test
    fun portraitFitsHeightOnLandscapeOrShortWindow() {
        val fitted = containFitVideo(1080, 1920, availableWidth = 1080, availableHeight = 800)
        assertEquals(VideoPxSize(450, 800), fitted)
        assertFits(fitted, 1080, 800)
        assertAspectNear(1080, 1920, fitted)
        val naiveHeight = 1080 * 1920 / 1080
        assertTrue("width*ratio must not be used as unconstrained height", fitted.height < naiveHeight)
    }

    @Test
    fun portraitFillsPortraitSlot() {
        val fitted = containFitVideo(1080, 1920, 1080, 1920)
        assertEquals(VideoPxSize(1080, 1920), fitted)
    }

    @Test
    fun squareFitsTheShorterSide() {
        val fitted = containFitVideo(1000, 1000, 500, 800)
        assertEquals(VideoPxSize(500, 500), fitted)
        assertFits(fitted, 500, 800)
    }

    @Test
    fun unknownSizeFits16By9IntoAvailable() {
        val fitted = containFitVideo(0, 0, 1080, 1920)
        assertEquals(VideoPxSize(1080, 607), fitted)
    }

    @Test
    fun emptyAvailableYieldsZero() {
        assertEquals(VideoPxSize(0, 0), containFitVideo(1920, 1080, 0, 1080))
        assertEquals(VideoPxSize(0, 0), containFitVideo(1920, 1080, 1080, 0))
        assertEquals(VideoPxSize(0, 0), containFitVideo(1920, 1080, -10, 800))
    }

    @Test
    fun extremeSizesStayInsideSlotWithoutOverflow() {
        val wide = containFitVideo(Int.MAX_VALUE, 1, 100, 100)
        assertFits(wide, 100, 100)
        val tall = containFitVideo(1, Int.MAX_VALUE, 100, 100)
        assertFits(tall, 100, 100)
        val huge = containFitVideo(Int.MAX_VALUE, Int.MAX_VALUE, 1080, 1920)
        assertFits(huge, 1080, 1920)
    }

    @Test
    fun insetsAndPaddingShrinkTheSlotSoPortraitCannotOverflow() {
        val available = videoDialogAvailablePx(
            windowWidth = 1080,
            windowHeight = 1920,
            insetLeft = 0,
            insetTop = 80,
            insetRight = 0,
            insetBottom = 120,
            extraPaddingPx = 16,
        )
        assertEquals(VideoPxSize(1048, 1688), available)
        val fitted = fitPlayerInDialog(
            metrics = VideoTrackMetrics(1080, 1920),
            windowWidth = 1080,
            windowHeight = 1920,
            insetTop = 80,
            insetBottom = 120,
            extraPaddingPx = 16,
        )
        assertFits(fitted, available.width, available.height)
        assertTrue(fitted.height <= 1688)
        assertTrue(fitted.width <= 1048)
        val unconstrained = 1048 * 1920 / 1080
        assertTrue(fitted.height < unconstrained)
    }

    @Test
    fun landscapeWindowFitsPortraitByHeight() {
        val fitted = fitPlayerInDialog(
            metrics = VideoTrackMetrics(1080, 1920),
            windowWidth = 1920,
            windowHeight = 1080,
            extraPaddingPx = 16,
        )
        assertFits(fitted, 1920 - 32, 1080 - 32)
        assertEquals(1048, fitted.height)
        assertEquals(589, fitted.width)
    }

    @Test
    fun smallWindowFitsLandscapeWithoutExceeding() {
        val fitted = fitPlayerInDialog(
            metrics = VideoTrackMetrics(1920, 1080),
            windowWidth = 400,
            windowHeight = 300,
            insetLeft = 20,
            insetRight = 20,
            extraPaddingPx = 8,
        )
        assertFits(fitted, 400 - 40 - 16, 300 - 16)
    }

    @Test
    fun portraitWithControlBarStaysWithinShortSlot() {
        val availableHeight = 800
        val control = 48
        val fitted = fitPlayerInDialog(
            metrics = VideoTrackMetrics(1080, 1920),
            windowWidth = 1080,
            windowHeight = availableHeight,
            controlHeightPx = control,
        )
        assertFits(fitted, 1080, availableHeight - control)
        assertTrue(fitted.height + control <= availableHeight)
        assertEquals(48, videoControlHeightPx(availableHeight, 48))
    }

    @Test
    fun extremeShortSlotKeepsControlReachable() {
        assertEquals(40, videoControlHeightPx(40, 48))
        val fitted = fitPlayerInDialog(
            metrics = VideoTrackMetrics(1080, 1920),
            windowWidth = 1080,
            windowHeight = 40,
            controlHeightPx = 48,
        )
        assertEquals(0, fitted.height)
        assertTrue(fitted.height + videoControlHeightPx(40, 48) <= 40)
    }

    private fun assertFits(size: VideoPxSize, availableWidth: Int, availableHeight: Int) {
        assertTrue(size.width in 0..availableWidth)
        assertTrue(size.height in 0..availableHeight)
    }

    private fun assertAspectNear(contentWidth: Int, contentHeight: Int, fitted: VideoPxSize) {
        val left = fitted.width.toLong() * contentHeight
        val right = fitted.height.toLong() * contentWidth
        assertTrue(abs(left - right) <= contentWidth.coerceAtLeast(contentHeight))
    }
}
