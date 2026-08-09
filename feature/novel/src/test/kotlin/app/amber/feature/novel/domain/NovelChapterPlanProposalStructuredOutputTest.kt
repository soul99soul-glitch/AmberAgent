package app.amber.feature.novel.domain

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NovelChapterPlanProposalStructuredOutputTest {
    @Test
    fun proposalDecodesTheExactSchemaOneContract() {
        val decoded = NovelStructuredOutputDecoder.decodeChapterPlanProposal(validJson)

        assertEquals(NovelChapterPlanProposalV1.CURRENT_SCHEMA_VERSION, decoded.schemaVersion)
        assertEquals("推进追查并迫使主角选择", decoded.goalAndConflict)
        assertEquals(listOf("夺回信物"), decoded.mustHappen)
        assertEquals(listOf("主角死亡"), decoded.mustNotHappen)
    }

    @Test
    fun proposalRejectsDuplicateMissingUnknownAndWrongWireTypes() {
        val invalidPayloads = listOf(
            validJson.replaceFirst("{", "{\"schemaVersion\":1,"),
            validJson.dropLast(1) + ",\"unknown\":true}",
            validJson.replace(",\n  \"visibleFacts\":[\"主角已知线索来源\"]", ""),
            validJson.replace("\"schemaVersion\":1", "\"schemaVersion\":\"1\""),
            validJson.replace("\"outlinePlacement\":\"第 4 章\"", "\"outlinePlacement\":4"),
            validJson.replace(
                "\"goalAndConflict\":\"推进追查并迫使主角选择\"",
                "\"goalAndConflict\":true",
            ),
            validJson.replace("\"mustHappen\":[\"夺回信物\"]", "\"mustHappen\":{}"),
            validJson.replace("\"mustNotHappen\":[\"主角死亡\"]", "\"mustNotHappen\":[1]"),
            validJson.replace("\"endingHook\":\"门外响起脚步声\"", "\"endingHook\":null"),
            validJson.replace(
                "\"visibleFacts\":[\"主角已知线索来源\"]",
                "\"visibleFacts\":\"主角已知线索来源\"",
            ),
        )

        invalidPayloads.forEach(::assertFailure)
    }

    @Test
    fun proposalRejectsBlankRequirementsAndIosLimitViolations() {
        val valid = NovelChapterPlanProposalV1(
            outlinePlacement = "第 4 章",
            goalAndConflict = "推进追查并迫使主角选择",
            mustHappen = listOf("夺回信物"),
            mustNotHappen = listOf("主角死亡"),
            endingHook = "门外响起脚步声",
            visibleFacts = listOf("主角已知线索来源"),
        )
        val invalidValues = listOf(
            valid.copy(goalAndConflict = " "),
            valid.copy(mustHappen = emptyList()),
            valid.copy(mustHappen = listOf(" ")),
            valid.copy(mustNotHappen = listOf(" ")),
            valid.copy(visibleFacts = listOf(" ")),
            valid.copy(outlinePlacement = "位".repeat(501)),
            valid.copy(goalAndConflict = "推".repeat(8_001)),
            valid.copy(endingHook = "钩".repeat(4_001)),
            valid.copy(mustHappen = List(33) { "事件$it" }),
            valid.copy(mustNotHappen = List(33) { "禁令$it" }),
            valid.copy(visibleFacts = List(33) { "事实$it" }),
        )

        invalidValues.forEach { value ->
            assertFailure(Json.encodeToString(NovelChapterPlanProposalV1.serializer(), value))
        }
    }

    private fun assertFailure(payload: String) {
        assertTrue(
            "Expected chapter-plan proposal to fail closed",
            runCatching {
                NovelStructuredOutputDecoder.decodeChapterPlanProposal(payload)
            }.isFailure,
        )
    }

    private companion object {
        val validJson = """
            {
              "schemaVersion":1,
              "outlinePlacement":"第 4 章",
              "goalAndConflict":"推进追查并迫使主角选择",
              "mustHappen":["夺回信物"],
              "mustNotHappen":["主角死亡"],
              "endingHook":"门外响起脚步声",
              "visibleFacts":["主角已知线索来源"]
            }
        """.trimIndent()
    }
}
