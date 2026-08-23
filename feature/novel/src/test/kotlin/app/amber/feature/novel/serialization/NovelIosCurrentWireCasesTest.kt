package app.amber.feature.novel.serialization

import app.amber.feature.novel.domain.NovelDocumentValidator
import app.amber.feature.novel.model.NovelActiveRunRecord
import app.amber.feature.novel.model.NovelAppliedOperationRecord
import app.amber.feature.novel.model.NovelChapterId
import app.amber.feature.novel.model.NovelChapterRecord
import app.amber.feature.novel.model.NovelInjectionReceiptRecord
import app.amber.feature.novel.model.NovelInjectionReceiptSectionRecord
import app.amber.feature.novel.model.NovelInjectionSectionKind
import app.amber.feature.novel.model.NovelInjectionSelectionReason
import app.amber.feature.novel.model.NovelOperationKind
import app.amber.feature.novel.model.NovelOutcome
import app.amber.feature.novel.model.NovelReceiptId
import app.amber.feature.novel.model.NovelRunKind
import app.amber.feature.novel.model.NovelSettingProposalOrigin
import app.amber.feature.novel.model.NovelSettingProposalRecord
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class NovelIosCurrentWireCasesTest {
    @Test
    fun decodesCurrentIosWireCasesAsTypedValues() {
        val fixture = readFixture()

        val activeRun = decodeActiveRun(fixture)
        assertEquals(NovelRunKind.CharacterProposal, activeRun.kind)
        assertEquals("伊芙", activeRun.contextualCharacterMention)
        assertEquals(null, activeRun.candidateID)

        val proposal = decodeProposal(fixture)
        val origin = proposal.origin as? NovelSettingProposalOrigin.ContextualCharacter
        assertEquals(activeRun.id, origin?.runID)
        assertEquals("伊芙", origin?.sourceMention)
        assertEquals(app.amber.feature.novel.model.NovelMaterialKind.Character, origin?.suggestedKind)

        val archiveSection = decodeArchiveSection(fixture)
        val archiveKind = archiveSection.kind as? NovelInjectionSectionKind.DiscussionArchive
        assertEquals("70707070-7070-4070-8070-707070707070", archiveKind?.messageID?.rawValue)
        assertEquals(12L, archiveKind?.throughSequence)
        assertEquals(NovelInjectionSelectionReason.ArchivedDiscussion, archiveSection.reason)

        val operations = decodeOperations(fixture)
        assertEquals(
            listOf(
                NovelOperationKind.DiscardChapter,
                NovelOperationKind.RestoreChapter,
                NovelOperationKind.DeleteChapterFromManuscript,
            ),
            operations.map { it.kind },
        )
        assertTrue((operations[0].outcome as NovelOutcome.ChapterDiscardStateChanged).isDiscarded)
        assertFalse((operations[1].outcome as NovelOutcome.ChapterDiscardStateChanged).isDiscarded)
        assertEquals(4L, (operations[2].outcome as NovelOutcome.ChapterRemovedFromManuscript).workingRevision)
    }

    @Test
    fun currentIosWireCasesRoundTripInsideProjectWithoutValidatorRejection() {
        val fixture = readFixture()
        val activeRun = decodeActiveRun(fixture)
        val proposal = decodeProposal(fixture)
        val archiveSection = decodeArchiveSection(fixture)
        val operations = decodeOperations(fixture)
        val base = NovelSwiftCompatibleJson.decodeProjectDocument(
            readResourceBytes("novel-v1/projects/minimal-blank.project.json"),
        )
        val document = base.copy(
            project = base.project.copy(revision = 9),
            chapters = listOf(
                NovelChapterRecord(
                    id = NovelChapterId.parse("90909090-9090-4090-8090-909090909090"),
                    createdAt = Instant.ofEpochMilli(721692803000),
                ),
            ),
            activeRuns = listOf(activeRun),
            settingProposals = listOf(proposal),
            injectionReceipts = listOf(
                NovelInjectionReceiptRecord(
                    id = NovelReceiptId.parse("90909090-9090-4090-8090-909090909090"),
                    runID = activeRun.id,
                    projectID = base.project.id,
                    branchID = base.branches.single().id,
                    promptVersion = "discussion-v1",
                    providerID = "provider",
                    ownerProviderID = "provider",
                    modelID = "model",
                    wireModelID = "model",
                    sections = listOf(archiveSection),
                    requestedInputBudgetTokens = 16_000,
                    maxEstimatedInputTokens = 16_000,
                    estimatedInputTokens = archiveSection.estimatedTokens,
                    canonicalInputSHA256 = "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff",
                    createdAt = Instant.ofEpochMilli(721692806000),
                ),
            ),
            appliedOperations = base.appliedOperations + operations,
        )

        NovelDocumentValidator.validate(document)
        val roundTripped = NovelSwiftCompatibleJson.decodeProjectDocument(
            NovelSwiftCompatibleJson.encodeProjectDocument(document),
        )

        assertEquals(document, roundTripped)
        val expectedActiveRun = fixture.getValue("activeRun").jsonObject
        val encodedActiveRun = NovelSwiftCompatibleJson.json
            .encodeToJsonElement(NovelActiveRunRecord.serializer(), activeRun)
            .jsonObject
        assertJsonObjectEquivalent(
            expectedActiveRun,
            encodedActiveRun,
            numericFields = setOf("startedAt", "terminalAt"),
        )
        assertJsonObjectEquivalent(
            fixture.getValue("settingProposal").jsonObject,
            NovelSwiftCompatibleJson.json
                .encodeToJsonElement(NovelSettingProposalRecord.serializer(), proposal)
                .jsonObject,
            numericFields = setOf("createdAt"),
        )
        assertEquals(
            fixture.getValue("archiveSection"),
            NovelSwiftCompatibleJson.json.encodeToJsonElement(
                NovelInjectionReceiptSectionRecord.serializer(),
                archiveSection,
            ),
        )
        val expectedOperations = fixture.getValue("operations").jsonArray
        val encodedOperations = JsonArray(
            operations.map { operation ->
                NovelSwiftCompatibleJson.json.encodeToJsonElement(
                    NovelAppliedOperationRecord.serializer(),
                    operation,
                )
            },
        )
        assertEquals(expectedOperations.size, encodedOperations.size)
        expectedOperations.zip(encodedOperations).forEach { (expected, actual) ->
            assertJsonObjectEquivalent(
                expected.jsonObject,
                actual.jsonObject,
                numericFields = setOf("appliedAt"),
            )
        }
    }

    private fun assertJsonObjectEquivalent(
        expected: JsonObject,
        actual: JsonObject,
        numericFields: Set<String>,
    ) {
        assertEquals(
            JsonObject(expected.filterKeys { it !in numericFields }),
            JsonObject(actual.filterKeys { it !in numericFields }),
        )
        numericFields.forEach { field ->
            assertEquals(
                expected.getValue(field).jsonPrimitive.double,
                actual.getValue(field).jsonPrimitive.double,
                0.0,
            )
        }
    }

    private fun decodeActiveRun(fixture: JsonObject): NovelActiveRunRecord =
        NovelSwiftCompatibleJson.json.decodeFromJsonElement(
            NovelActiveRunRecord.serializer(),
            fixture.getValue("activeRun"),
        )

    private fun decodeProposal(fixture: JsonObject): NovelSettingProposalRecord =
        NovelSwiftCompatibleJson.json.decodeFromJsonElement(
            NovelSettingProposalRecord.serializer(),
            fixture.getValue("settingProposal"),
        )

    private fun decodeArchiveSection(fixture: JsonObject): NovelInjectionReceiptSectionRecord =
        NovelSwiftCompatibleJson.json.decodeFromJsonElement(
            NovelInjectionReceiptSectionRecord.serializer(),
            fixture.getValue("archiveSection"),
        )

    private fun decodeOperations(fixture: JsonObject): List<NovelAppliedOperationRecord> =
        fixture.getValue("operations").jsonArray.map { operation ->
            NovelSwiftCompatibleJson.json.decodeFromJsonElement(
                NovelAppliedOperationRecord.serializer(),
                operation,
            )
        }

    private fun readFixture(): JsonObject = NovelSwiftCompatibleJson.json.parseToJsonElement(
        readResourceBytes("novel-v1/records/ios-current-wire-cases.json").toString(Charsets.UTF_8),
    ).jsonObject

    private fun readResourceBytes(path: String): ByteArray {
        val stream = requireNotNull(requireNotNull(javaClass.classLoader).getResourceAsStream(path)) {
            "Missing test resource: $path"
        }
        return stream.use { it.readBytes() }
    }
}
