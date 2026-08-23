package app.amber.feature.novel.serialization

import app.amber.feature.novel.model.NovelCandidateStatus
import app.amber.feature.novel.model.NovelCheckpointKind
import app.amber.feature.novel.model.NovelGenerationGranularity
import app.amber.feature.novel.model.NovelMaterialKind
import app.amber.feature.novel.model.NovelOutcome
import app.amber.feature.novel.model.NovelPendingOperationKind
import app.amber.feature.novel.model.NovelPendingOperationStatus
import app.amber.feature.novel.model.NovelProjectCreationMode
import app.amber.feature.novel.model.NovelProjectDocumentV1
import app.amber.feature.novel.model.NovelProjectModelPolicy
import app.amber.feature.novel.model.NovelRunStatus
import app.amber.feature.novel.model.NovelSessionCursor
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NovelProjectCodecTest {
    @Test
    fun decodeMinimalBlankProject() {
        val doc = NovelSwiftCompatibleJson.decodeProjectDocument(
            readResourceBytes("novel-v1/projects/minimal-blank.project.json"),
        )
        assertEquals(1, doc.schemaVersion)
        assertEquals("Minimal Blank", doc.project.name)
        assertEquals(NovelProjectCreationMode.Blank, doc.project.creationMode)
        assertEquals(NovelProjectModelPolicy.Global, doc.project.modelPolicy)
        // Pre-S3 documents omit stateSyncModelPolicy → null → fall back to writing model.
        assertEquals(null, doc.project.stateSyncModelPolicy)
        assertEquals(doc.project.modelPolicy, doc.project.effectiveStateSyncModelPolicy())
        assertEquals(NovelGenerationGranularity.WholeChapter, doc.project.lastGenerationGranularity)
        assertEquals(1, doc.branches.size)
        assertEquals(1, doc.checkpoints.size)
        assertEquals(NovelSessionCursor.Empty, doc.checkpoints.first().sessionCursor)
        assertEquals(1, doc.appliedOperations.size)
        assertTrue(doc.appliedOperations.first().outcome is NovelOutcome.ProjectCreated)
        assertTrue(doc.factAttempts.isEmpty())
        assertTrue(doc.polishTransactions.isEmpty())
    }

    @Test
    fun decodeLegacyMissingDefaults() {
        val doc = NovelSwiftCompatibleJson.decodeProjectDocument(
            readResourceBytes("novel-v1/projects/legacy-missing-v1-defaults.project.json"),
        )
        // decodeIfPresent defaults should materialize empty arrays for missing keys.
        assertTrue(doc.factAttempts.isEmpty())
        assertTrue(doc.polishTransactions.isEmpty())
        assertTrue(doc.polishAttempts.isEmpty())
        assertTrue(doc.polishAssessments.isEmpty())
        assertEquals(1, doc.materials.size)
        assertEquals(NovelMaterialKind.World, doc.materials.first().kind)
        // isDeleted default false when omitted in legacy material? fixture includes? check defaults
        assertEquals(false, doc.materials.first().isDeleted)
    }

    @Test
    fun decodeFullTwoBranchProject() {
        val doc = NovelSwiftCompatibleJson.decodeProjectDocument(
            readResourceBytes("novel-v1/projects/full-two-branch.project.json"),
        )
        assertEquals(2, doc.branches.size)
        assertEquals(1, doc.chapters.size)
        assertEquals(1, doc.chapterVersions.size)
        assertEquals(
            "ABABABAB-ABAB-4BAB-8BAB-ABABABABABAB",
            doc.chapterVersions.first().factCompatibilityID.toString().uppercase(),
        )
        assertEquals(NovelProjectModelPolicy.Fixed("provider-stable", "model-stable"), doc.project.modelPolicy)
        assertEquals(NovelCandidateStatus.Collected, doc.candidates.first().status)
        assertEquals(NovelCheckpointKind.Collection, doc.checkpoints[1].kind)
        assertTrue(doc.checkpoints[1].sessionCursor is NovelSessionCursor.Through)
        assertEquals(2L, (doc.checkpoints[1].sessionCursor as NovelSessionCursor.Through).sequence)
        assertTrue(doc.appliedOperations[1].outcome is NovelOutcome.CandidateCollected)
    }

    @Test
    fun decodeInterruptedPendingProject() {
        val doc = NovelSwiftCompatibleJson.decodeProjectDocument(
            readResourceBytes("novel-v1/projects/interrupted-pending.project.json"),
        )
        assertEquals(1, doc.activeRuns.size)
        assertEquals(NovelRunStatus.Interrupted, doc.activeRuns.first().status)
        assertEquals(1, doc.pendingOperations.size)
        assertEquals(NovelPendingOperationKind.Collection, doc.pendingOperations.first().kind)
        assertEquals(NovelPendingOperationStatus.Retryable, doc.pendingOperations.first().status)
        assertTrue(doc.pendingOperations.first().collectionTarget != null)
    }

    @Test
    fun roundTripMinimalProjectPreservesLogicalFields() {
        val original = NovelSwiftCompatibleJson.decodeProjectDocument(
            readResourceBytes("novel-v1/projects/minimal-blank.project.json"),
        )
        val reencoded = NovelSwiftCompatibleJson.encodeProjectDocument(original)
        val roundTripped = NovelSwiftCompatibleJson.decodeProjectDocument(reencoded)
        assertEquals(original, roundTripped)
    }

    @Test
    fun roundTripPreservesStateSyncModelPolicy() {
        val original = NovelSwiftCompatibleJson.decodeProjectDocument(
            readResourceBytes("novel-v1/projects/minimal-blank.project.json"),
        )
        val withSync = original.copy(
            project = original.project.copy(
                stateSyncModelPolicy = NovelProjectModelPolicy.Fixed("provider-sync", "model-sync"),
            ),
        )
        val roundTripped = NovelSwiftCompatibleJson.decodeProjectDocument(
            NovelSwiftCompatibleJson.encodeProjectDocument(withSync),
        )
        assertEquals(
            NovelProjectModelPolicy.Fixed("provider-sync", "model-sync"),
            roundTripped.project.stateSyncModelPolicy,
        )
        assertEquals(
            NovelProjectModelPolicy.Fixed("provider-sync", "model-sync"),
            roundTripped.project.effectiveStateSyncModelPolicy(),
        )
        // Writing model must remain independent.
        assertEquals(original.project.modelPolicy, roundTripped.project.modelPolicy)
    }

    @Test
    fun decodePackageAndInnerProject() {
        val envelopeBytes = readResourceBytes("novel-v1/packages/minimal-blank.ambernovel.json")
        val envelope = NovelSwiftCompatibleJson.decodePackageEnvelope(envelopeBytes)
        assertEquals(NovelSwiftWireContract.PACKAGE_FORMAT, envelope.format)
        assertEquals(1, envelope.envelopeVersion)
        val document = NovelSwiftCompatibleJson.decodeProjectDocumentFromPackage(envelopeBytes)
        assertEquals("Minimal Blank", document.project.name)
    }

    @Test
    fun higherSchemaPackageIsRejected() {
        val bytes = readResourceBytes("novel-v1/packages/higher-schema-reject.ambernovel.json")
        try {
            NovelSwiftCompatibleJson.decodeProjectDocumentFromPackage(bytes)
            org.junit.Assert.fail("Expected higher schema rejection")
        } catch (error: IllegalArgumentException) {
            assertTrue(error.message.orEmpty().contains("schema") || error.message.orEmpty().contains("Unsupported"))
        }
    }

    @Test
    fun encodePackageRoundTrip() {
        val document = NovelSwiftCompatibleJson.decodeProjectDocument(
            readResourceBytes("novel-v1/projects/minimal-blank.project.json"),
        )
        val envelope = NovelSwiftCompatibleJson.encodePackageFromDocument(document)
        val encoded = NovelSwiftCompatibleJson.encodePackageEnvelope(envelope)
        val decodedDoc = NovelSwiftCompatibleJson.decodeProjectDocumentFromPackage(encoded)
        assertEquals(document, decodedDoc)
    }

    @Test
    fun wireShapeEnumsEncodeAsSwiftAssociatedObjects() {
        val global = NovelSwiftCompatibleJson.json.encodeToString(
            NovelProjectModelPolicy.Serializer,
            NovelProjectModelPolicy.Global,
        )
        assertEquals("""{"global":{}}""", global)
        val fixed = NovelSwiftCompatibleJson.json.encodeToString(
            NovelProjectModelPolicy.Serializer,
            NovelProjectModelPolicy.Fixed(providerID = "p", modelID = "m"),
        )
        // key order may be sorted by kotlinx map order of buildJsonObject insertion
        assertTrue(fixed.contains("\"fixed\""))
        assertTrue(fixed.contains("\"providerID\":\"p\""))
        assertTrue(fixed.contains("\"modelID\":\"m\""))

        val custom = NovelSwiftCompatibleJson.json.encodeToString(
            NovelMaterialKind.Serializer,
            NovelMaterialKind.Custom("Place"),
        )
        assertEquals("""{"custom":{"_0":"Place"}}""", custom)

        val fixture = NovelSwiftCompatibleJson.json.parseToJsonElement(
            readResourceBytes("novel-v1/encoding/swift-wire-shapes.json").decodeToString(),
        ).jsonObject.getValue("material_kind_relationship")
        assertEquals(
            NovelMaterialKind.Relationship,
            NovelSwiftCompatibleJson.json.decodeFromJsonElement(
                NovelMaterialKind.Serializer,
                fixture,
            ),
        )
        assertEquals(
            """{"relationship":{}}""",
            NovelSwiftCompatibleJson.json.encodeToString(
                NovelMaterialKind.Serializer,
                NovelMaterialKind.Relationship,
            ),
        )

        val emptyCursor = NovelSwiftCompatibleJson.json.encodeToString(
            NovelSessionCursor.Serializer,
            NovelSessionCursor.Empty,
        )
        assertEquals("""{"empty":{}}""", emptyCursor)
    }

    private fun readResourceBytes(path: String): ByteArray {
        val stream = requireNotNull(requireNotNull(javaClass.classLoader).getResourceAsStream(path)) {
            "Missing test resource: $path"
        }
        return stream.use { it.readBytes() }
    }
}
