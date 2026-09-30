package app.amber.feature.ui.theme

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import app.amber.core.settings.ThemeDesign
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ThemeDesignRenderingTest {
    @Test
    fun `diagonal gradient uses the v1 normalized endpoints on a portrait canvas`() {
        val gradient = ThemeDesign.Gradient(listOf("#000000", "#FFFFFF"), listOf("#000000", "#FFFFFF"), 45.0)
        val bitmap = render(100, 200) {
            drawRect(checkNotNull(themeGradientBrush(gradient, false, size)))
        }

        // iOS UnitPoint endpoints are (14.64, 29.29) and (85.36, 170.71).
        // This sample is ~80% along that gradient; screen-space 45 degrees gives ~61%.
        assertTrue(AndroidColor.red(bitmap.getPixel(14, 170)) in 195..210)
    }

    @Test
    fun `portable marks keep iOS radius stroke and lattice semantics at device density`() {
        fun pattern(kind: String, markSize: Double) = render(288, 288, density = 4f) {
            drawThemePattern(ThemeDesign.Pattern(kind, "#000000", 0.3, 36.0, markSize))
        }

        val dots = pattern("dots", 1.5)
        assertTrue(AndroidColor.alpha(dots.getPixel(148, 144)) > 0) // radius 1.5dp, at x + 1dp
        assertEquals(0, AndroidColor.alpha(dots.getPixel(72, 72))) // no half-cell shift

        val crosses = pattern("crosses", 3.5)
        assertTrue(AndroidColor.alpha(crosses.getPixel(144, 144)) > 0)
        assertEquals(0, AndroidColor.alpha(crosses.getPixel(152, 150))) // 1.12dp stroke, not 3.5dp

        val rings = pattern("rings", 3.0)
        assertTrue(AndroidColor.alpha(rings.getPixel(167, 144)) > 0) // radius 6dp, not 3dp
        assertEquals(0, AndroidColor.alpha(rings.getPixel(155, 144)))
    }

    private fun render(
        width: Int,
        height: Int,
        density: Float = 1f,
        draw: DrawScope.() -> Unit,
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        CanvasDrawScope().draw(
            density = Density(density),
            layoutDirection = LayoutDirection.Ltr,
            canvas = Canvas(bitmap.asImageBitmap()),
            size = Size(width.toFloat(), height.toFloat()),
            block = draw,
        )
        return bitmap
    }
}
