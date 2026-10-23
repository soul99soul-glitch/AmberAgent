package app.amber.feature.ui.components.ai

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WebPreviewThumbnailsTest {
    private val context: Application = ApplicationProvider.getApplicationContext()

    @Test
    fun cacheFileIsStablePerNormalizedUrl() {
        val page = WebPreviewThumbnails.fileFor(context, "https://example.com/page/")

        assertEquals(page, WebPreviewThumbnails.fileFor(context, " https://example.com/page#section "))
        assertNotEquals(page, WebPreviewThumbnails.fileFor(context, "https://example.com/other"))
        assertTrue(page.name.matches(Regex("preview-[0-9a-f]{32}\\.webp")))
    }

    @Test
    fun writtenThumbnailIsFoundForTheSameUrlOnly() {
        val url = "https://example.com/cached"
        assertNull(WebPreviewThumbnails.cached(context, url))

        val written = WebPreviewThumbnails.write(context, url, solidBitmap())

        assertEquals(written, WebPreviewThumbnails.cached(context, "$url/"))
        assertTrue(written.length() > 0L)
        assertFalse(java.io.File(written.parentFile, "${written.name}.tmp").exists())
        assertNull(WebPreviewThumbnails.cached(context, "https://example.com/elsewhere"))
    }

    @Test
    fun cacheDirectoryKeepsOnlyTheNewestFiles() {
        val directory = WebPreviewThumbnails.directory(context).apply { mkdirs() }
        repeat(60) { index ->
            java.io.File(directory, "webview-$index.png").apply {
                writeText("legacy $index")
                setLastModified(1_000L + index)
            }
        }

        val written = WebPreviewThumbnails.write(context, "https://example.com/newest", solidBitmap())
        val remaining = directory.listFiles().orEmpty().map { it.name }.toSet()

        assertEquals(48, remaining.size)
        assertTrue(written.name in remaining)
        assertFalse("webview-0.png" in remaining)
        assertTrue("webview-59.png" in remaining)
    }

    private fun solidBitmap(): Bitmap =
        Bitmap.createBitmap(8, 6, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.DKGRAY) }
}
