package me.rerere.rikkahub.data.agent.subagent

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SubAgentTranscriptReaderTest {
    @Test
    fun readsDisplayTextFromJsonlInsideRunRoot() {
        val root = tempDir()
        val transcript = File(root, "run.jsonl")
        transcript.writeText(
            """
            {"event":"started","payload":{"status":"running"}}
            {"event":"finished","payload":{"display_text":"Human-readable final answer"}}
            """.trimIndent()
        )

        assertEquals(
            "Human-readable final answer",
            readSubAgentDisplayTextFromTranscript(transcript.absolutePath, root),
        )
    }

    @Test
    fun rejectsTranscriptOutsideRunRoot() {
        val root = tempDir()
        val outside = File(tempDir(), "run.jsonl")
        outside.writeText("""{"payload":{"display_text":"leak"}}""")

        assertEquals("", readSubAgentDisplayTextFromTranscript(outside.absolutePath, root))
    }

    @Test
    fun rejectsNonJsonlTranscript() {
        val root = tempDir()
        val transcript = File(root, "run.txt")
        transcript.writeText("""{"payload":{"display_text":"wrong extension"}}""")

        assertEquals("", readSubAgentDisplayTextFromTranscript(transcript.absolutePath, root))
    }

    @Test
    fun readsOnlyTailOfLargeTranscript() {
        val root = tempDir()
        val transcript = File(root, "run.jsonl")
        transcript.writeText("x".repeat(300_000))
        transcript.appendText("\n")
        transcript.appendText("""{"event":"finished","payload":{"display_text":"Tail answer"}}""")

        assertEquals("Tail answer", readSubAgentDisplayTextFromTranscript(transcript.absolutePath, root))
    }

    @Test
    fun skipsCorruptJsonlLines() {
        val root = tempDir()
        val transcript = File(root, "run.jsonl")
        transcript.writeText(
            """
            {"event":"finished","payload":{"display_text":"Recovered answer"}}
            not-json
            """.trimIndent()
        )

        assertEquals("Recovered answer", readSubAgentDisplayTextFromTranscript(transcript.absolutePath, root))
    }

    private fun tempDir(): File =
        Files.createTempDirectory("subagent-runs").toFile().also { it.deleteOnExit() }
}
