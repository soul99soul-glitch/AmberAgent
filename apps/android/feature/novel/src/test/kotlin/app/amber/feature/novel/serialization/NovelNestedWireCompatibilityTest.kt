package app.amber.feature.novel.serialization

import app.amber.feature.novel.model.NovelCharacterIdentityClarificationRecord
import app.amber.feature.novel.model.NovelAskUserPrompt
import app.amber.feature.novel.model.NovelSessionMessageInteraction
import app.amber.feature.novel.model.NovelSessionMessageRecord
import app.amber.feature.novel.model.NovelStateSnapshotRecord
import app.amber.feature.novel.model.NovelOperationId
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class NovelNestedWireCompatibilityTest {
    @Test
    fun decodesIosAskUserAndIdentityClarificationRecords() {
        val fixture = readFixture()

        val askUser = decodeMessage(fixture, "askUserMessage")
        val prompt = (askUser.interaction as NovelSessionMessageInteraction.AskUser).prompt
        assertEquals("要写入这次修改吗？", prompt.question)
        assertEquals(listOf("写入正文", "拒绝这次修改"), prompt.options)
        assertEquals("第三章", prompt.chapterRevision?.chapterTitle)
        assertEquals("新段落。", prompt.chapterRevision?.newText)
        assertEquals(null, prompt.manuscriptRevert)
        assertEquals(null, prompt.workspacePlot)

        val workspacePlot = decodeMessage(fixture, "workspacePlotMessage")
        val workspacePlotPrompt = (workspacePlot.interaction as NovelSessionMessageInteraction.AskUser).prompt
        assertEquals("plot/outline.md", workspacePlotPrompt.workspacePlot?.path)
        assertEquals("第一幕：城门相遇。\n第二幕：秘密浮现。", workspacePlotPrompt.workspacePlot?.body)
        assertEquals("同步最新剧情节点。", workspacePlotPrompt.workspacePlot?.reason)

        val answer = decodeMessage(fixture, "askUserAnswerMessage")
        val response = (answer.interaction as NovelSessionMessageInteraction.AskUserAnswer).response
        assertEquals(askUser.id, response.promptMessageID)
        assertEquals("写入正文", response.answer)

        val revert = decodeMessage(fixture, "manuscriptRevertMessage")
        val revertPrompt = (revert.interaction as NovelSessionMessageInteraction.AskUser).prompt
        assertEquals(null, revertPrompt.chapterRevision)
        assertEquals(2, revertPrompt.manuscriptRevert?.chapterCount)
        assertEquals(listOf(3, 4), revertPrompt.manuscriptRevert?.chapterOrdinals)

        val state = NovelSwiftCompatibleJson.json.decodeFromJsonElement(
            NovelStateSnapshotRecord.serializer(),
            fixture.getValue("stateSnapshot"),
        )
        assertEquals("伊芙", state.characterIdentityClarifications.single().mention)
        assertEquals("伊芙是城门守卫的女儿。", state.characterIdentityClarifications.single().clarification)
    }

    @Test
    fun nestedIosRecordsRoundTripWithoutDroppingPayloads() {
        val fixture = readFixture()
        val askUser = decodeMessage(fixture, "askUserMessage")
        val answer = decodeMessage(fixture, "askUserAnswerMessage")
        val revert = decodeMessage(fixture, "manuscriptRevertMessage")
        val workspacePlot = decodeMessage(fixture, "workspacePlotMessage")
        val state = NovelSwiftCompatibleJson.json.decodeFromJsonElement(
            NovelStateSnapshotRecord.serializer(),
            fixture.getValue("stateSnapshot"),
        )

        val base = NovelSwiftCompatibleJson.decodeProjectDocument(
            readResourceBytes("novel-v1/projects/minimal-blank.project.json"),
        )
        val document = base.copy(
            sessions = listOf(base.sessions.single().copy(messages = listOf(askUser, answer, revert, workspacePlot))),
            stateSnapshots = listOf(state),
        )
        val roundTripped = NovelSwiftCompatibleJson.decodeProjectDocument(
            NovelSwiftCompatibleJson.encodeProjectDocument(document),
        )

        assertEquals(document, roundTripped)
        val wire = NovelSwiftCompatibleJson.json
            .encodeToJsonElement(NovelSessionMessageRecord.serializer(), askUser)
        assertEquals(
            fixture.getValue("askUserMessage").jsonObject.getValue("interaction"),
            wire.jsonObject.getValue("interaction"),
        )
        val workspacePlotWire = NovelSwiftCompatibleJson.json
            .encodeToJsonElement(NovelSessionMessageRecord.serializer(), workspacePlot)
        assertEquals(
            fixture.getValue("workspacePlotMessage").jsonObject.getValue("interaction"),
            workspacePlotWire.jsonObject.getValue("interaction"),
        )
        assertTrue(
            NovelSwiftCompatibleJson.encodeProjectDocument(document)
                .decodeToString()
                .contains("characterIdentityClarifications"),
        )
    }

    @Test
    fun askUserPromptAllowsMissingOptionalPayloadAndUnknownFields() {
        val prompt = NovelSwiftCompatibleJson.json.decodeFromJsonElement(
            NovelAskUserPrompt.serializer(),
            NovelSwiftCompatibleJson.json.parseToJsonElement(
                """
                {
                  "question": "需要确认吗？",
                  "options": ["确认"],
                  "futurePromptField": "ignored"
                }
                """.trimIndent(),
            ),
        )
        assertEquals(null, prompt.chapterRevision)
        assertEquals(null, prompt.manuscriptRevert)
        assertEquals(null, prompt.workspacePlot)

        val workspacePlot = NovelSwiftCompatibleJson.json.decodeFromJsonElement(
            NovelAskUserPrompt.serializer(),
            NovelSwiftCompatibleJson.json.parseToJsonElement(
                """
                {
                  "question": "将剧情写入工作区？",
                  "options": ["写入剧情", "拒绝这次修改"],
                  "workspacePlot": {
                    "path": "plot/outline.md",
                    "body": "第一幕。",
                    "futurePlotField": true
                  }
                }
                """.trimIndent(),
            ),
        )
        assertEquals("plot/outline.md", workspacePlot.workspacePlot?.path)
        assertEquals("第一幕。", workspacePlot.workspacePlot?.body)
        assertEquals(null, workspacePlot.workspacePlot?.reason)
    }

    @Test
    fun stateSnapshotCacheFingerprintIncludesIdentityClarifications() {
        val base = NovelSwiftCompatibleJson.decodeProjectDocument(
            readResourceBytes("novel-v1/projects/minimal-blank.project.json"),
        )
        val cache = NovelSectionEncodeCache()
        encodeProjectDocumentCached(base, cache)

        val clarification = NovelCharacterIdentityClarificationRecord(
            mention = "伊芙",
            clarification = "伊芙是城门守卫的女儿。",
            operationID = NovelOperationId.parse("B0B0B0B0-B0B0-40B0-80B0-B0B0B0B0B0B0"),
            createdAt = Instant.ofEpochMilli(721692803000),
        )
        val updated = base.copy(
            stateSnapshots = listOf(
                base.stateSnapshots.single().copy(characterIdentityClarifications = listOf(clarification)),
            ),
        )
        val cached = encodeProjectDocumentCached(updated, cache)
        assertArrayEquals(NovelSwiftCompatibleJson.encodeProjectDocument(updated), cached.bytes)
        assertTrue("changed nested state must invalidate its cache", cached.freshSectionCount > 0)
    }

    private fun decodeMessage(fixture: JsonObject, key: String): NovelSessionMessageRecord =
        NovelSwiftCompatibleJson.json.decodeFromJsonElement(
            NovelSessionMessageRecord.serializer(),
            fixture.getValue(key),
        )

    private fun readFixture(): JsonObject =
        NovelSwiftCompatibleJson.json.parseToJsonElement(
            readResourceBytes("novel-v1/records/ios-nested-interactions.json")
                .toString(Charsets.UTF_8),
        ).jsonObject

    private fun readResourceBytes(path: String): ByteArray {
        val stream = requireNotNull(requireNotNull(javaClass.classLoader).getResourceAsStream(path)) {
            "Missing test resource: $path"
        }
        return stream.use { it.readBytes() }
    }
}
