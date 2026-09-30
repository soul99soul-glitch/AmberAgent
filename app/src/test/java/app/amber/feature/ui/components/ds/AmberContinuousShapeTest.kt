package app.amber.feature.ui.components.ds

import android.app.Application
import android.graphics.Region
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.junit.Assert.assertEquals
import org.junit.Test

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class)
class AmberContinuousShapeTest {
    @Test
    fun assembledCapsuleFitsItsExactVisualBounds() {
        val outline = AmberContinuousShape(44.dp).createOutline(
            Size(120f, 40f), LayoutDirection.Ltr, Density(1f),
        ) as Outline.Generic
        // Path.getBounds includes conic control points; check the actual filled outline instead.
        val filled = Region().apply {
            setPath(outline.path.asAndroidPath(), Region(-20, -20, 140, 60))
        }
        val bounds = filled.bounds
        assertEquals(0, bounds.left)
        assertEquals(0, bounds.top)
        assertEquals(120, bounds.right)
        assertEquals(40, bounds.bottom)
    }

    @Test
    fun roundedRectFitsItsBoundsAndCutsTheVertex() {
        val outline = AmberContinuousShape(20.dp).createOutline(
            Size(200f, 120f), LayoutDirection.Ltr, Density(1f),
        ) as Outline.Generic
        val filled = Region().apply {
            setPath(outline.path.asAndroidPath(), Region(-20, -20, 220, 140))
        }
        assertEquals(android.graphics.Rect(0, 0, 200, 120), filled.bounds)
        assertEquals(true, filled.contains(100, 0))
        assertEquals(false, filled.contains(0, 0))
    }

    @Test
    fun smoothedCornerMeetsArcTangentiallyAndLeavesEdgeWithZeroCurvature() {
        val params = amberCornerParams(radius = 20f, budget = 100f)
        assertEquals(32f, params.extent, 0.0001f)
        // First cubic: both control points on the edge, so curvature at the edge is zero.
        val start = Offset(0f, 0f)
        val c1 = Offset(params.a, 0f)
        val c2 = Offset(params.a + params.b, 0f)
        val arcStart = Offset(params.a + params.b + params.c, params.d)
        val startTangent = derivative(start, c1, scale = 3f)
        val startAcceleration = secondDerivative(start, c1, c2)
        assertEquals(0f, cross(startTangent, startAcceleration), 0.0001f)
        // G1 into the arc: cubic end tangent points the way the arc starts (rotated by 45°·s).
        val endTangent = derivative(c2, arcStart, scale = 3f)
        val arcAngle = Math.toRadians(45.0 * AMBER_CORNER_SMOOTHING)
        val length = length(endTangent)
        assertEquals(kotlin.math.cos(arcAngle).toFloat(), endTangent.x / length, 0.0001f)
        assertEquals(kotlin.math.sin(arcAngle).toFloat(), endTangent.y / length, 0.0001f)
    }

    @Test
    fun crampedCornerFallsBackToCircularWithoutOverrunningTheEdge() {
        val params = amberCornerParams(radius = 20f, budget = 20f)
        assertEquals(20f, params.extent, 0.0001f)
        assertEquals(90f, params.arcSweepDegrees, 0.0001f)
        assertEquals(0f, params.a + params.b + params.c + params.d, 0.0001f)
    }

    @Test
    fun capsuleFlankStartsFlatAndMatchesNoseArcCurvature() {
        val flank = amberCapsuleFlank
        // P0..P2 on the edge ⇒ zero curvature where the flank leaves the straight edge.
        assertEquals(0f, flank.p0.y, 0.0001f)
        assertEquals(0f, flank.p1.y, 0.0001f)
        assertEquals(0f, flank.p2.y, 0.0001f)

        val endTangent = derivative(flank.p2, flank.p3, scale = 3f)
        val endAcceleration = secondDerivative(flank.p1, flank.p2, flank.p3)
        val endTangentLength = length(endTangent)
        val curvature = cross(endTangent, endAcceleration) /
            (endTangentLength * endTangentLength * endTangentLength)
        assertEquals(1f / flank.noseRadius, curvature, 0.0001f)
        val phi = Math.toRadians(flank.joinDegrees.toDouble())
        assertEquals(kotlin.math.cos(phi).toFloat(), endTangent.x / endTangentLength, 0.0001f)
        assertEquals(kotlin.math.sin(phi).toFloat(), endTangent.y / endTangentLength, 0.0001f)
        // The flank ends on the nose circle, whose rightmost point is the pill tip.
        val centre = Offset(-flank.noseRadius, 1f)
        assertEquals(flank.noseRadius, length(flank.p3 - centre), 0.0001f)
        // Eases in before the plain semicircle would, while the nose stays within 3% of it.
        assertEquals(true, flank.extent > 1.1f)
        assertEquals(true, flank.noseRadius >= 0.97f)
    }

    private fun derivative(start: Offset, end: Offset, scale: Float) =
        Offset((end.x - start.x) * scale, (end.y - start.y) * scale)

    private fun secondDerivative(start: Offset, middle: Offset, end: Offset) =
        Offset(
            (end.x - 2f * middle.x + start.x) * 6f,
            (end.y - 2f * middle.y + start.y) * 6f,
        )

    private fun cross(first: Offset, second: Offset) =
        first.x * second.y - first.y * second.x

    private fun length(value: Offset) =
        kotlin.math.sqrt(value.x * value.x + value.y * value.y)

    private fun assertOffsetEquals(expected: Offset, actual: Offset) {
        assertEquals(expected.x, actual.x, 0.0001f)
        assertEquals(expected.y, actual.y, 0.0001f)
    }
}
