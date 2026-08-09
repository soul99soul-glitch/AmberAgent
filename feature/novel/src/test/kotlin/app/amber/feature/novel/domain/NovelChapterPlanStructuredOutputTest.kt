package app.amber.feature.novel.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NovelChapterPlanStructuredOutputTest {
    @Test
    fun acceptanceV2_decodesAcceptedResultAndIndependentRepetition() {
        val decoded = NovelStructuredOutputDecoder.decodeChapterPlanAcceptance(
            """
            {
              "schemaVersion": 2,
              "accepted": true,
              "missingMustHappen": [],
              "forbiddenViolations": [],
              "obviousRepetition": ["再次夺回同一信物"],
              "summary": "合同满足，但有明确复读。"
            }
            """.trimIndent(),
        )

        assertTrue(decoded.accepted)
        assertEquals(listOf("再次夺回同一信物"), decoded.obviousRepetition)
    }

    @Test
    fun acceptanceV1_decodesWithoutRepetitionField() {
        val decoded = NovelStructuredOutputDecoder.decodeChapterPlanAcceptance(
            """
            {
              "schemaVersion": 1,
              "accepted": true,
              "missingMustHappen": [],
              "forbiddenViolations": [],
              "summary": "合同满足。"
            }
            """.trimIndent(),
        )

        assertTrue(decoded.accepted)
        assertTrue(decoded.obviousRepetition.isEmpty())
    }

    @Test
    fun acceptanceV2_decodesRejectedResultWithEvidence() {
        val decoded = NovelStructuredOutputDecoder.decodeChapterPlanAcceptance(
            """
            {
              "schemaVersion": 2,
              "accepted": false,
              "missingMustHappen": ["夺回信物"],
              "forbiddenViolations": ["主角提前离城"],
              "obviousRepetition": [],
              "summary": "合同未满足。"
            }
            """.trimIndent(),
        )

        assertFalse(decoded.accepted)
        assertEquals(listOf("夺回信物"), decoded.missingMustHappen)
        assertEquals(listOf("主角提前离城"), decoded.forbiddenViolations)
    }

    @Test
    fun acceptance_rejectsSemanticContradictions() {
        assertFailure(
            """
            {
              "schemaVersion": 2,
              "accepted": true,
              "missingMustHappen": ["夺回信物"],
              "forbiddenViolations": [],
              "obviousRepetition": [],
              "summary": "矛盾"
            }
            """.trimIndent(),
        )
        assertFailure(
            """
            {
              "schemaVersion": 2,
              "accepted": false,
              "missingMustHappen": [],
              "forbiddenViolations": [],
              "obviousRepetition": ["复读"],
              "summary": "没有合同违规"
            }
            """.trimIndent(),
        )
    }

    @Test
    fun acceptance_rejectsSchemaDriftWrongTypesAndBlankValues() {
        assertFailure(
            validAcceptanceJson.replace("\"schemaVersion\": 2", "\"schemaVersion\": 3"),
        )
        assertFailure(
            validAcceptanceJson.replace("\"accepted\": true", "\"accepted\": \"true\""),
        )
        assertFailure(
            validAcceptanceJson.replace("\"schemaVersion\": 2", "\"schemaVersion\": \"2\""),
        )
        assertFailure(
            validAcceptanceJson.replace("\"missingMustHappen\": []", "\"missingMustHappen\": [1]"),
        )
        assertFailure(
            validAcceptanceJson.replace("\"summary\": \"x\"", "\"summary\": true"),
        )
        assertFailure(
            validAcceptanceJson.replace("\"summary\": \"x\"", "\"summary\": \" \""),
        )
        assertFailure(
            validAcceptanceJson.replace("\"obviousRepetition\": []", "\"obviousRepetition\": [\" \"]"),
        )
    }

    @Test
    fun acceptance_rejectsMissingUnknownAndVersionMismatchedFields() {
        assertFailure(
            validAcceptanceJson.replace("  \"obviousRepetition\": [],\n", ""),
        )
        assertFailure(
            validAcceptanceJson.replace("\"schemaVersion\": 2", "\"schemaVersion\": 1"),
        )
        assertFailure(
            validAcceptanceJson.replace("\n}", ",\n  \"extra\": 1\n}"),
        )
        assertFailure(
            validAcceptanceJson.replace("\"accepted\": true", "\"accepted\": true,\n  \"accepted\": false"),
        )
    }

    private fun assertFailure(json: String) {
        assertTrue(runCatching { NovelStructuredOutputDecoder.decodeChapterPlanAcceptance(json) }.isFailure)
    }

    private val validAcceptanceJson = """
        {
          "schemaVersion": 2,
          "accepted": true,
          "missingMustHappen": [],
          "forbiddenViolations": [],
          "obviousRepetition": [],
          "summary": "x"
        }
    """.trimIndent()
}
