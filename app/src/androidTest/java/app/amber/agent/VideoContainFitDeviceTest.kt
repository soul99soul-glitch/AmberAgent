package app.amber.agent

import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.amber.feature.ui.components.media.containFitVideo
import app.amber.feature.ui.components.media.videoControlHeightPx
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Supplementary device ART + decoder coverage for contain-fit math and silent
 * pause of the three fixture clips. This is **not** VideoPlaybackDialog evidence.
 * Dialog clicks / pause / resume / close / list position live in
 * [VideoPlaybackDeviceTest].
 */
@RunWith(AndroidJUnit4::class)
class VideoContainFitDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun portraitLandscapeSquareDecodeAndContainFitWithinDisplaySlot() {
        val dm = context.resources.displayMetrics
        val maxW = dm.widthPixels
        val maxH = dm.heightPixels
        val reserved = videoControlHeightPx(maxH, (dm.density * 48f).toInt())
        val availH = (maxH - reserved).coerceAtLeast(1)
        val lines = StringBuilder()
        lines.appendLine("display=${maxW}x$maxH density=${dm.density} reserved=$reserved availH=$availH")
        for ((name, b64, codedW, codedH) in fixtures()) {
            val file = writeClip(name, b64)
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(file.absolutePath)
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)!!.toInt()
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)!!.toInt()
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()
            retriever.release()
            assertTrue("$name duration", durationMs > 0)
            assertEquals("$name width", codedW, width)
            assertEquals("$name height", codedH, height)
            val fitted = containFitVideo(codedW, codedH, maxW, availH)
            assertTrue("$name fitted width ${fitted.width} > slot $maxW", fitted.width in 1..maxW)
            assertTrue("$name fitted height ${fitted.height} > slot $availH", fitted.height in 1..availH)
            val aspect = fitted.width.toFloat() / fitted.height.toFloat()
            val expect = codedW.toFloat() / codedH.toFloat()
            assertEquals("$name contain aspect", expect, aspect, 0.02f)
            lines.appendLine(
                "$name coded=${codedW}x$codedH decoded=${width}x$height durationMs=$durationMs fitted=${fitted.width}x${fitted.height}",
            )
        }
        writeEvidence("video-contain.txt", lines.toString())
    }

    @Test
    fun mutePlayerPausesAndDoesNotAutoResumeUntilStart() {
        val file = writeClip("portrait", AmberS9VideoAssets.PORTRAIT_B64)
        val player = MediaPlayer()
        try {
            player.setDataSource(file.absolutePath)
            player.setVolume(0f, 0f)
            player.prepare()
            player.setVolume(0f, 0f)
            player.start()
            assertTrue("player must start muted", player.isPlaying)
            player.pause()
            Thread.sleep(200)
            assertFalse("pause must stop playback", player.isPlaying)
            Thread.sleep(300)
            assertFalse("paused player must stay silent without start()", player.isPlaying)
            player.start()
            assertTrue("explicit start resumes", player.isPlaying)
            player.pause()
            player.reset()
            assertFalse("reset is silent", player.isPlaying)
            writeEvidence("video-player-pause.txt", "pause_holds_without_autoresume=true muted=true\n")
        } finally {
            player.release()
        }
    }

    private fun fixtures() = listOf(
        Quad("portrait", AmberS9VideoAssets.PORTRAIT_B64, 360, 640),
        Quad("landscape", AmberS9VideoAssets.LANDSCAPE_B64, 640, 360),
        Quad("square", AmberS9VideoAssets.SQUARE_B64, 360, 360),
    )

    private fun writeClip(name: String, b64: String): File {
        val dest = File(context.cacheDir, "amber-s9-$name.mp4")
        dest.writeBytes(Base64.decode(b64, Base64.DEFAULT))
        return dest
    }

    private fun writeEvidence(name: String, text: String) {
        val dir = context.getExternalFilesDir("amber-s9") ?: context.cacheDir
        dir.mkdirs()
        File(dir, name).writeText(text)
    }

    private data class Quad(val name: String, val b64: String, val width: Int, val height: Int)
}
