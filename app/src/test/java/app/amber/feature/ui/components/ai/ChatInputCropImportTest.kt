package app.amber.feature.ui.components.ai

import android.app.Application
import androidx.core.net.toFile
import androidx.core.net.toUri
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ChatInputCropImportTest {
    @Test
    fun `crop output survives suspended copy and is deleted when import finishes`() = runTest {
        for (cancelImport in listOf(false, true)) {
            val source = File.createTempFile("crop_output_", ".png")
            val destination = File.createTempFile("upload_", ".png")
            try {
                source.writeText("cropped image bytes")
                val continueCopy = CompletableDeferred<Unit>()
                lateinit var importJob: Job
                importCroppedImage(source.toUri()) { uri ->
                    launch {
                        withContext(NonCancellable) {
                            continueCopy.await()
                            uri.toFile().copyTo(destination, overwrite = true)
                        }
                    }.also { importJob = it }
                }
                runCurrent()
                if (cancelImport) importJob.cancel()
                runCurrent()
                assertTrue(source.exists())

                continueCopy.complete(Unit)
                importJob.join()
                assertEquals("cropped image bytes", destination.readText())
                assertFalse(source.exists())
            } finally {
                source.delete()
                destination.delete()
            }
        }
    }

    @Test
    fun `already cancelled import still releases crop output`() {
        val source = File.createTempFile("crop_output_", ".png")
        try {
            importCroppedImage(source.toUri()) { Job().apply { cancel() } }
            assertFalse(source.exists())
        } finally {
            source.delete()
        }
    }
}
