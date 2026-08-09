package app.amber.feature.novel.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NovelManualSyncStateDeltaDecoderTest {
    @Test
    fun strictManualSyncAcceptsSummaryOnlyAndEvidenceBackedFacts() {
        val summaryOnly = NovelStructuredOutputDecoder.decodeManualSyncStateDelta(
            text = payload(),
            evidenceSource = canonicalChunk,
        )
        assertEquals("本分块没有新增可持久事实", summaryOnly.stateSummary)
        assertTrue(summaryOnly.events.isEmpty())
        assertTrue(summaryOnly.settingProposals.isEmpty())

        val event = event(
            evidence = "主角在祭坛下找到失落的信物",
        )
        val proposal = proposal(
            evidence = "使者提到北境港口每逢月末都会封航",
        )
        val decoded = NovelStructuredOutputDecoder.decodeManualSyncStateDelta(
            text = payload(events = "[$event]", proposals = "[$proposal]"),
            evidenceSource = canonicalChunk,
        )
        assertEquals(listOf("event-1"), decoded.events.map { it.id })
        assertEquals(listOf("proposal-1"), decoded.settingProposals.map { it.id })
    }

    @Test
    fun strictManualSyncRejectsMissingUnknownDuplicateAndWrongRootTypes() {
        val valid = payload()
        val wrongArrayTypes = listOf(
            "events",
            "characterChanges",
            "relationshipChanges",
            "foreshadowingChanges",
            "unresolvedEntityNames",
            "settingProposals",
        ).map { field -> valid.replace("\"$field\":[]", "\"$field\":{}") }
        val invalidPayloads = listOf(
            valid.replace(",\n  \"settingProposals\":[]", ""),
            valid.dropLast(1) + ",\n  \"unknown\":true\n}",
            valid.replaceFirst("{", "{\n  \"stateSummary\":\"duplicate\","),
            valid.replace("\"schemaVersion\":1", "\"schemaVersion\":\"1\""),
            valid.replace(
                "\"stateSummary\":\"本分块没有新增可持久事实\"",
                "\"stateSummary\":false",
            ),
            valid.replace("\"unresolvedEntityNames\":[]", "\"unresolvedEntityNames\":[1]"),
            valid.replace("\"branchOutlinePatch\":null", "\"branchOutlinePatch\":1"),
        ) + wrongArrayTypes

        invalidPayloads.forEach(::assertFailure)
    }

    @Test
    fun strictManualSyncRejectsMalformedOrUnanchoredFacts() {
        val anchoredEvent = event(evidence = "主角在祭坛下找到失落的信物")
        val anchoredProposal = proposal(evidence = "使者提到北境港口每逢月末都会封航")
        val invalidPayloads = buildList {
            add(
                payload(
                    events = "[${anchoredEvent.replaceFirst("{", "{\"id\":\"duplicate\",")}]",
                ),
            )
            add(
                payload(
                    events = "[${anchoredEvent.replace(
                        ",\"evidence\":\"主角在祭坛下找到失落的信物\"",
                        "",
                    )}]",
                ),
            )
            add(payload(events = "[${anchoredEvent.replace("\"entityReferences\":[]", "\"entityReferences\":{}")}]"))
            add(payload(events = "[${event(evidence = "主角突然登上月球并建立王国")}]"))
            add(payload(proposals = "[${proposal(evidence = "王都法律规定所有人必须飞行")}]"))
            add(payload(unresolved = "[\" \"]"))
            add(payload(branchOutlinePatch = "\" \""))

            listOf("event-1", "fact", "主角找回信物", "主角在祭坛下找到失落的信物")
                .forEach { value ->
                    add(payload(events = "[${anchoredEvent.replace("\"$value\"", "\" \"")}]"))
                }
            listOf(
                "proposal-1",
                "北境港口封航规则",
                "每逢月末封航",
                "使者提到北境港口每逢月末都会封航",
            ).forEach { value ->
                add(payload(proposals = "[${anchoredProposal.replace("\"$value\"", "\" \"")}]"))
            }
        }

        invalidPayloads.forEach(::assertFailure)
    }

    @Test
    fun strictManualSyncRejectsUnsupportedFactArrays() {
        listOf("characterChanges", "relationshipChanges", "foreshadowingChanges")
            .forEach { field ->
                assertFailure(payload().replace("\"$field\":[]", "\"$field\":[{}]"))
            }
    }

    private fun assertFailure(payload: String) {
        assertTrue(
            "Expected strict manual-sync payload to fail closed: $payload",
            runCatching {
                NovelStructuredOutputDecoder.decodeManualSyncStateDelta(
                    text = payload,
                    evidenceSource = canonicalChunk,
                )
            }.isFailure,
        )
    }

    private fun event(evidence: String): String =
        """{"id":"event-1","kind":"fact","summary":"主角找回信物","entityReferences":[],"evidence":"$evidence"}"""

    private fun proposal(evidence: String): String = """
        {"id":"proposal-1","title":"北境港口封航规则","content":"每逢月末封航","evidence":"$evidence"}
    """.trimIndent()

    private fun payload(
        events: String = "[]",
        unresolved: String = "[]",
        branchOutlinePatch: String = "null",
        proposals: String = "[]",
    ): String = """
        {
          "schemaVersion":1,
          "stateSummary":"本分块没有新增可持久事实",
          "events":$events,
          "characterChanges":[],
          "relationshipChanges":[],
          "foreshadowingChanges":[],
          "unresolvedEntityNames":$unresolved,
          "branchOutlinePatch":$branchOutlinePatch,
          "settingProposals":$proposals
        }
    """.trimIndent()

    private companion object {
        const val canonicalChunk =
            "主角在祭坛下找到失落的信物。使者提到北境港口每逢月末都会封航。"
    }
}
