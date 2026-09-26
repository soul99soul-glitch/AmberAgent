package app.amber.feature.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TerminalRuntimeInternalsTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `job log trims after growing to amortized threshold and keeps tail`() {
        val maxBytes = 512
        val file = temporaryFolder.newFile("terminal.log")
        val log = TerminalJobLog(file, maxBytes)

        log.append("a".repeat(maxBytes))
        log.append("b".repeat(maxBytes / 2))

        assertEquals(maxBytes + maxBytes / 2L, file.length())
        assertFalse(file.readText().contains("terminal log truncated"))

        log.append("c")

        val contents = file.readText()
        assertTrue(file.length() <= maxBytes)
        assertTrue(contents.startsWith("\n... [terminal log truncated; omitted "))
        assertTrue(contents.endsWith("b".repeat(maxBytes / 2) + "c"))
    }
}
