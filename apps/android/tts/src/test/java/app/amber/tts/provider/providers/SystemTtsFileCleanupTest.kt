package app.amber.tts.provider.providers

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SystemTtsFileCleanupTest {

    @Test
    fun `temporary synthesis file is deleted during cleanup`() {
        val file = File.createTempFile("amber-system-tts", ".wav")
        assertTrue(file.exists())

        deleteSystemTtsTempFile(file)

        assertFalse(file.exists())
    }
}
