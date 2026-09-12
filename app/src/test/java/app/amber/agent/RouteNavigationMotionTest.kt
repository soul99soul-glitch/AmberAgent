package app.amber.agent

import android.app.Application
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "w320dp-h200dp")
class RouteNavigationMotionTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun pushUsesActualRouteTransformToEnterFromTheRightWithoutScaling() {
        compose.mainClock.autoAdvance = false
        val target = mutableStateOf(0)
        val positions = ConcurrentHashMap<Int, Rect>()
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                RouteMotionFixture(target.value, pop = false) { page, bounds -> positions[page] = bounds }
            }
        }
        compose.onNodeWithTag("route-page-0").assertIsDisplayed()

        compose.runOnIdle { target.value = 1 }
        val firstFrame = advanceOneFrame()
        compose.waitForIdle()
        val enteringAtStart = awaitPosition(positions, 1, firstFrame)
        assertTrue("push page must enter from the right", enteringAtStart.left > 0f)
        assertEquals(320f, enteringAtStart.width, 1f)

        compose.mainClock.advanceTimeBy(96)
        val enteringMidway = positions.requirePage(1)
        val leavingMidway = positions.requirePage(0)
        assertTrue("push page must move monotonically toward the final position", enteringMidway.left < enteringAtStart.left)
        assertTrue("push page must still be inside the right half", enteringMidway.left > 0f)
        assertTrue("the previous page must leave through the left edge", leavingMidway.left < 0f)
        assertEquals(320f, enteringMidway.width, 1f)
        assertEquals(320f, leavingMidway.width, 1f)
    }

    @Test
    fun popUsesActualRouteTransformToExitToTheRightWithoutScaling() {
        compose.mainClock.autoAdvance = false
        val target = mutableStateOf(1)
        val positions = ConcurrentHashMap<Int, Rect>()
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                RouteMotionFixture(target.value, pop = true, predictive = false) { page, bounds ->
                    positions[page] = bounds
                }
            }
        }
        compose.onNodeWithTag("route-page-1").assertIsDisplayed()

        compose.runOnIdle { target.value = 0 }
        val firstFrame = advanceOneFrame()
        compose.waitForIdle()
        awaitPosition(positions, 0, firstFrame)
        compose.mainClock.advanceTimeBy(96)
        val returningMidway = positions.requirePage(0)
        val leavingMidway = positions.requirePage(1)
        assertTrue("pop page must enter from the left", returningMidway.left < 0f)
        assertTrue("current page must leave through the right edge", leavingMidway.left > 0f)
        assertEquals(320f, returningMidway.width, 1f)
        assertEquals(320f, leavingMidway.width, 1f)
    }

    @Test
    fun predictivePopUsesLinearActualTransformAtTheGestureMidpoint() {
        compose.mainClock.autoAdvance = false
        val target = mutableStateOf(1)
        val positions = ConcurrentHashMap<Int, Rect>()
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                RouteMotionFixture(target.value, pop = true, predictive = true) { page, bounds ->
                    positions[page] = bounds
                }
            }
        }
        compose.onNodeWithTag("route-page-1").assertIsDisplayed()

        compose.runOnIdle { target.value = 0 }
        val firstFrame = advanceOneFrame()
        compose.waitForIdle()
        awaitPosition(positions, 0, firstFrame)
        var returningAtMidpoint = positions.requirePage(0)
        repeat(20) {
            if (abs(returningAtMidpoint.left + 160f) <= 24f) return@repeat
            advanceOneFrame()
            returningAtMidpoint = positions.requirePage(0)
        }
        val leavingAtMidpoint = positions.requirePage(1)
        assertEquals(-160f, returningAtMidpoint.left, 24f)
        assertEquals(160f, leavingAtMidpoint.left, 24f)
        assertEquals(320f, returningAtMidpoint.width, 1f)
        assertEquals(320f, leavingAtMidpoint.width, 1f)
    }

    private fun advanceOneFrame(): Long {
        val before = compose.mainClock.currentTime
        compose.mainClock.advanceTimeByFrame()
        return compose.mainClock.currentTime - before
    }

    private fun awaitPosition(
        positions: ConcurrentHashMap<Int, Rect>,
        page: Int,
        elapsedMillis: Long,
    ): Rect {
        var elapsed = elapsedMillis
        repeat(20) {
            positions[page]?.let { return it }
            elapsed += advanceOneFrame()
        }
        throw IllegalArgumentException("page $page has not been positioned after $elapsed ms")
    }

    private fun ConcurrentHashMap<Int, Rect>.requirePage(page: Int): Rect =
        requireNotNull(this[page]) { "page $page has not been positioned" }
}

@Composable
private fun RouteMotionFixture(
    target: Int,
    pop: Boolean,
    predictive: Boolean = false,
    onPositioned: (Int, Rect) -> Unit,
) {
    Box(
        modifier = Modifier
            .width(320.dp)
            .height(200.dp)
            .testTag("route-motion-root"),
    ) {
        AnimatedContent(
            targetState = target,
            modifier = Modifier.fillMaxSize(),
            transitionSpec = {
                if (pop) routePopTransition(predictive = predictive) else routePushTransition()
            },
            label = "route-motion-test",
        ) { page ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(if (page == 0) Color.Red else Color.Blue)
                    .onGloballyPositioned { coordinates -> onPositioned(page, coordinates.visualBounds()) }
                    .testTag("route-page-$page"),
            )
        }
    }
}

private fun LayoutCoordinates.visualBounds(): Rect {
    val topLeft = localToRoot(Offset.Zero)
    val bottomRight = localToRoot(Offset(size.width.toFloat(), size.height.toFloat()))
    return Rect(topLeft, bottomRight)
}
