package app.amber.feature.ui.pages.sessionhome

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
    fun cornerHasZeroCurvatureAtStraightEdgesAndC2Join() {
        val first = amberContinuousCornerSegments[0]
        val second = amberContinuousCornerSegments[1]
        assertOffsetEquals(first.end, second.start)

        val firstEndTangent = derivative(first.control2, first.end, scale = 3f)
        val secondStartTangent = derivative(second.start, second.control1, scale = 3f)
        assertOffsetEquals(firstEndTangent, secondStartTangent)

        val firstEndAcceleration = secondDerivative(first.control1, first.control2, first.end)
        val secondStartAcceleration = secondDerivative(second.start, second.control1, second.control2)
        assertOffsetEquals(firstEndAcceleration, secondStartAcceleration)

        val firstStartTangent = derivative(first.start, first.control1, scale = 3f)
        val firstStartAcceleration = secondDerivative(first.start, first.control1, first.control2)
        assertEquals(0f, cross(firstStartTangent, firstStartAcceleration), 0.0001f)

        val secondEndTangent = derivative(second.control2, second.end, scale = 3f)
        val secondEndAcceleration = secondDerivative(second.control1, second.control2, second.end)
        assertEquals(0f, cross(secondEndTangent, secondEndAcceleration), 0.0001f)
    }

    @Test
    fun capsuleFlankStartsFlatAndEndsAtUnitCircleCurvature() {
        val top = amberCapsuleTopFlank.single()
        val topTangent = derivative(top.start, top.control1, scale = 3f)
        val topAcceleration = secondDerivative(top.start, top.control1, top.control2)
        assertEquals(0f, cross(topTangent, topAcceleration), 0.0001f)

        val endTangent = derivative(top.control2, top.end, scale = 3f)
        val endAcceleration = secondDerivative(top.control1, top.control2, top.end)
        val endTangentLength = length(endTangent)
        assertEquals(1f, cross(endTangent, endAcceleration) / (endTangentLength * endTangentLength * endTangentLength), 0.0001f)
        assertEquals(0.9396926f, endTangent.x / endTangentLength, 0.0001f)
        assertEquals(0.34202014f, endTangent.y / endTangentLength, 0.0001f)
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
