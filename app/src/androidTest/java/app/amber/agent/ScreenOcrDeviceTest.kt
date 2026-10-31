package app.amber.agent

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.platform.app.InstrumentationRegistry
import app.amber.core.automation.AndroidOnDeviceOcrRecognizer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Device-level proof that the bundled ML Kit recognizer actually reads pixels.
 *
 * The JVM `ScreenOcrDependencyTest` only pins the classes on the classpath; this test runs the
 * real on-device recognizer used by `DefaultScreenOcrEngine`, so a silent fallback to
 * accessibility metadata cannot pass for working OCR.
 */
class ScreenOcrDeviceTest {

    @Test
    fun bundledMlKitRecognizesTextFromScreenshotBitmap() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bitmap = Bitmap.createBitmap(900, 400, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(Color.WHITE)
            val paint = Paint().apply {
                color = Color.BLACK
                textSize = 96f
                isAntiAlias = true
                isFakeBoldText = true
            }
            drawText("AMBER 2026", 60f, 230f, paint)
        }
        val blocks = try {
            AndroidOnDeviceOcrRecognizer.recognize(
                context = context,
                bitmap = bitmap,
                snapshot = null,
                displayWidth = bitmap.width,
                displayHeight = bitmap.height,
            )
        } finally {
            bitmap.recycle()
        }
        val recognized = blocks.joinToString(" | ") { it.text }
        assertTrue(
            "bundled ML Kit OCR returned no matching text; blocks=$recognized",
            recognized.contains("AMBER", ignoreCase = true),
        )
    }
}
