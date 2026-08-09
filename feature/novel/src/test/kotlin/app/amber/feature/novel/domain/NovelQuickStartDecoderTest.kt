package app.amber.feature.novel.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NovelQuickStartDecoderTest {
    @Test
    fun decode_continuityAudit_consistent() {
        val json = """{"schemaVersion":1,"consistent":true,"issues":[]}"""
        val audit = NovelStructuredOutputDecoder.decodeContinuityAudit(json)
        assertTrue(audit.consistent)
        assertTrue(audit.issues.isEmpty())
    }

    @Test
    fun decode_continuityAudit_withIssues() {
        val json = """
            {"schemaVersion":1,"consistent":false,"issues":[
              {"id":"i1","category":"contradiction","severity":"major","summary":"A vs B",
               "references":[
                 {"chapterOrdinal":1,"chapterTitle":"一","evidence":"alive"},
                 {"chapterOrdinal":2,"chapterTitle":"二","evidence":"dead"}
               ]}
            ]}
        """.trimIndent()
        val audit = NovelStructuredOutputDecoder.decodeContinuityAudit(json)
        assertTrue(!audit.consistent)
        assertEquals(1, audit.issues.size)
        assertEquals(NovelContinuityIssueCategoryV1.Contradiction, audit.issues[0].category)
    }

    @Test
    fun decode_continuityAudit_duplicateOrBlankIssueIdentityFails() {
        val duplicate = """
            {"schemaVersion":1,"consistent":false,"issues":[
              {"id":"same","category":"contradiction","severity":"major","summary":"A vs B",
               "references":[
                 {"chapterOrdinal":1,"chapterTitle":"一","evidence":"A"},
                 {"chapterOrdinal":2,"chapterTitle":"二","evidence":"B"}
               ]},
              {"id":"same","category":"chronology","severity":"minor","summary":"C vs D",
               "references":[
                 {"chapterOrdinal":2,"chapterTitle":"二","evidence":"C"},
                 {"chapterOrdinal":3,"chapterTitle":"三","evidence":"D"}
               ]}
            ]}
        """.trimIndent()
        assertTrue(runCatching { NovelStructuredOutputDecoder.decodeContinuityAudit(duplicate) }.isFailure)

        val blankId = """
            {"schemaVersion":1,"consistent":false,"issues":[
              {"id":" ","category":"contradiction","severity":"major","summary":"A vs B",
               "references":[
                 {"chapterOrdinal":1,"chapterTitle":"一","evidence":"A"},
                 {"chapterOrdinal":2,"chapterTitle":"二","evidence":"B"}
               ]}
            ]}
        """.trimIndent()
        assertTrue(runCatching { NovelStructuredOutputDecoder.decodeContinuityAudit(blankId) }.isFailure)

        val blankSummary = """
            {"schemaVersion":1,"consistent":false,"issues":[
              {"id":"i1","category":"contradiction","severity":"major","summary":" ",
               "references":[
                 {"chapterOrdinal":1,"chapterTitle":"一","evidence":"A"},
                 {"chapterOrdinal":2,"chapterTitle":"二","evidence":"B"}
               ]}
            ]}
        """.trimIndent()
        assertTrue(runCatching { NovelStructuredOutputDecoder.decodeContinuityAudit(blankSummary) }.isFailure)
    }

    @Test
    fun decode_continuityAudit_invalidReferenceFailsClosed() {
        fun result(firstReference: String) = runCatching {
            NovelStructuredOutputDecoder.decodeContinuityAudit(
                """
                {"schemaVersion":1,"consistent":false,"issues":[
                  {"id":"i1","category":"contradiction","severity":"major","summary":"A vs B",
                   "references":[
                     $firstReference,
                     {"chapterOrdinal":2,"chapterTitle":"二","evidence":"B"}
                   ]}
                ]}
                """.trimIndent(),
            )
        }

        assertTrue(
            result("""{"chapterOrdinal":0,"chapterTitle":"一","evidence":"A"}""").isFailure,
        )
        assertTrue(
            result("""{"chapterOrdinal":1,"chapterTitle":" ","evidence":"A"}""").isFailure,
        )
        assertTrue(
            result("""{"chapterOrdinal":1,"chapterTitle":"一","evidence":" "}""").isFailure,
        )

        val tooFewReferences = """
            {"schemaVersion":1,"consistent":false,"issues":[
              {"id":"i1","category":"contradiction","severity":"major","summary":"A vs B",
               "references":[{"chapterOrdinal":1,"chapterTitle":"一","evidence":"A"}]}
            ]}
        """.trimIndent()
        assertTrue(
            runCatching {
                NovelStructuredOutputDecoder.decodeContinuityAudit(tooFewReferences)
            }.isFailure,
        )
    }

    @Test
    fun decode_continuityAudit_rejectsDuplicateDriftAndWrongRootTypes() {
        val invalidPayloads = listOf(
            """{"schemaVersion":1,"consistent":true,"consistent":false,"issues":[]}""",
            """{"schemaVersion":1,"consistent":true,"issues":[],"extra":false}""",
            """{"schemaVersion":1,"consistent":true}""",
            """{"schemaVersion":"1","consistent":true,"issues":[]}""",
            """{"schemaVersion":1,"consistent":"true","issues":[]}""",
            """{"schemaVersion":1,"consistent":true,"issues":{}}""",
        )

        invalidPayloads.forEach { payload ->
            assertTrue(
                "Expected continuity payload to fail closed: $payload",
                runCatching {
                    NovelStructuredOutputDecoder.decodeContinuityAudit(payload)
                }.isFailure,
            )
        }
    }

    @Test
    fun decode_discussionArchive_fencedAndTrims() {
        val wrapped = """
            归档结果如下：
            ```json
            {
              "schemaVersion": 1,
              "decisions": [{
                "topic": " 主角身世揭示时点 ",
                "decision": " 隐瞒到第三章结尾。 ",
                "relatedMaterialID": null
              }],
              "summary": "确认主角隐瞒身世，并在第三章末揭示。"
            }
            ```
        """.trimIndent()
        val decoded = NovelStructuredOutputDecoder.decodeDiscussionArchive(wrapped)
        assertEquals("主角身世揭示时点", decoded.decisions.single().topic)
        assertEquals("隐瞒到第三章结尾。", decoded.decisions.single().decision)
        assertEquals("确认主角隐瞒身世，并在第三章末揭示。", decoded.summary)
    }

    @Test(expected = IllegalArgumentException::class)
    fun decode_discussionArchive_emptyDecisionsFails() {
        NovelStructuredOutputDecoder.decodeDiscussionArchive(
            """{"schemaVersion":1,"decisions":[],"summary":"没有决定"}""",
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun decode_discussionArchive_summaryTooLongFails() {
        val longSummary = "摘".repeat(301)
        NovelStructuredOutputDecoder.decodeDiscussionArchive(
            """{"schemaVersion":1,"decisions":[{"topic":"t","decision":"d"}],"summary":"$longSummary"}""",
        )
    }

    @Test
    fun decode_characterArray() {
        val text = """
            {
              "schemaVersion": 1,
              "overview": "Overview",
              "world": {"title": "World", "content": "Rules"},
              "characters": [
                {"title": "A", "content": "Hero"},
                {"title": "B", "content": "Rival"}
              ],
              "masterOutline": {"title": "Outline", "content": "Arc"},
              "writingRequirements": {"title": "Style", "content": "Third person"}
            }
        """.trimIndent()
        val decoded = NovelStructuredOutputDecoder.decodeQuickStartSuggestions(text)
        assertEquals(2, decoded.characters.size)
        assertEquals("A", decoded.characters[0].title)
        assertEquals("B", decoded.characters[1].title)
    }

    @Test
    fun decode_legacyCharacterObject() {
        val text = """
            {
              "schemaVersion": 1,
              "overview": "Overview",
              "world": {"title": "World", "content": "Rules"},
              "characters": {"title": "Crew", "content": "Everyone"},
              "masterOutline": {"title": "Outline", "content": "Arc"},
              "writingRequirements": {"title": "Style", "content": "Third person"}
            }
        """.trimIndent()
        val decoded = NovelStructuredOutputDecoder.decodeQuickStartSuggestions(text)
        assertEquals(1, decoded.characters.size)
        assertEquals("Crew", decoded.characters.single().title)
    }

    @Test
    fun decode_emptyCharacterArray_fails() {
        val text = """
            {
              "schemaVersion": 1,
              "overview": "Overview",
              "world": {"title": "World", "content": "Rules"},
              "characters": [],
              "masterOutline": {"title": "Outline", "content": "Arc"},
              "writingRequirements": {"title": "Style", "content": "Third person"}
            }
        """.trimIndent()
        val result = runCatching { NovelStructuredOutputDecoder.decodeQuickStartSuggestions(text) }
        assertTrue(result.isFailure)
    }

    @Test
    fun decode_blankCharacterTitle_fails() {
        val text = """
            {
              "schemaVersion": 1,
              "overview": "Overview",
              "world": {"title": "World", "content": "Rules"},
              "characters": [{"title": "", "content": "Hero"}],
              "masterOutline": {"title": "Outline", "content": "Arc"},
              "writingRequirements": {"title": "Style", "content": "Third person"}
            }
        """.trimIndent()
        assertTrue(
            runCatching { NovelStructuredOutputDecoder.decodeQuickStartSuggestions(text) }.isFailure,
        )
    }

    @Test
    fun decode_blankCharacterContent_fails() {
        val text = """
            {
              "schemaVersion": 1,
              "overview": "Overview",
              "world": {"title": "World", "content": "Rules"},
              "characters": [{"title": "A", "content": " "}],
              "masterOutline": {"title": "Outline", "content": "Arc"},
              "writingRequirements": {"title": "Style", "content": "Third person"}
            }
        """.trimIndent()
        assertTrue(
            runCatching { NovelStructuredOutputDecoder.decodeQuickStartSuggestions(text) }.isFailure,
        )
    }
}
