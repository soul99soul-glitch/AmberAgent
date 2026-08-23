package app.amber.feature.novel.serialization

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import java.util.Base64

/**
 * Shared fixtures must be present and exhibit Swift wire-shape markers.
 * Full decode/validate is Phase 1 (custom serializers + document validator).
 */
class NovelFixtureIntegrityTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun encodingShapesFixtureDocumentsSwiftWireMarkers() {
        val text = readResource("novel-v1/encoding/swift-wire-shapes.json")
        val root = json.parseToJsonElement(text).jsonObject

        val typedId = root.getValue("typed_id").jsonObject
        assertEquals(
            "11111111-1111-4111-8111-111111111111",
            typedId.getValue("rawValue").jsonPrimitive.content,
        )
        assertEquals(
            -978_307_200.0,
            root.getValue("date_unix_epoch").jsonPrimitive.double,
            0.0,
        )
        assertEquals(
            721_692_800.0,
            root.getValue("date_fixed").jsonPrimitive.double,
            0.0,
        )
        assertTrue(root.getValue("model_policy_global").jsonObject.containsKey("global"))
        assertTrue(root.getValue("model_policy_fixed").jsonObject.containsKey("fixed"))
        assertEquals(
            "Place",
            root.getValue("material_kind_custom")
                .jsonObject
                .getValue("custom")
                .jsonObject
                .getValue("_0")
                .jsonPrimitive
                .content,
        )
        assertTrue(root.getValue("material_kind_relationship").jsonObject.containsKey("relationship"))
        assertTrue(root.getValue("session_cursor_empty").jsonObject.containsKey("empty"))
        assertTrue(root.getValue("session_cursor_through").jsonObject.containsKey("through"))
    }

    @Test
    fun minimalBlankProjectHasSwiftTypedIdsAndDatesAndEnums() {
        val text = readResource("novel-v1/projects/minimal-blank.project.json")
        val doc = json.parseToJsonElement(text).jsonObject
        assertEquals(1, doc.getValue("schemaVersion").jsonPrimitive.content.toInt())

        val project = doc.getValue("project").jsonObject
        assertEquals(
            "11111111-1111-4111-8111-111111111111",
            project.getValue("id").jsonObject.getValue("rawValue").jsonPrimitive.content,
        )
        assertEquals(721_692_800.0, project.getValue("createdAt").jsonPrimitive.double, 0.0)
        assertTrue(project.getValue("modelPolicy").jsonObject.containsKey("global"))
        assertEquals("blank", project.getValue("creationMode").jsonPrimitive.content)
        assertEquals(
            "wholeChapter",
            project.getValue("lastGenerationGranularity").jsonPrimitive.content,
        )

        val checkpoints = doc.getValue("checkpoints").jsonArray
        assertTrue(checkpoints.isNotEmpty())
        val checkpoint = checkpoints.first().jsonObject
        assertTrue(checkpoint.getValue("sessionCursor").jsonObject.containsKey("empty"))

        // V1 non-optional arrays must be present on new output.
        listOf(
            "factAttempts",
            "polishTransactions",
            "polishAttempts",
            "polishAssessments",
        ).forEach { key ->
            assertTrue("missing $key", doc.containsKey(key))
        }
    }

    @Test
    fun packageEnvelopeMatchesChecksumAndStrictBase64() {
        val text = readResource("novel-v1/packages/minimal-blank.ambernovel.json")
        val envelope = json.parseToJsonElement(text).jsonObject
        assertEquals(
            NovelSwiftWireContract.PACKAGE_FORMAT,
            envelope.getValue("format").jsonPrimitive.content,
        )
        assertEquals(
            NovelSwiftWireContract.ENVELOPE_VERSION,
            envelope.getValue("envelopeVersion").jsonPrimitive.content.toInt(),
        )
        assertEquals(
            NovelSwiftWireContract.PROJECT_SCHEMA_VERSION,
            envelope.getValue("projectSchemaVersion").jsonPrimitive.content.toInt(),
        )

        val b64 = envelope.getValue("projectJSONBase64").jsonPrimitive.content
        assertTrue("Base64 must not contain newlines", !b64.contains('\n') && !b64.contains('\r'))
        val projectBytes = Base64.getDecoder().decode(b64)
        val reencoded = Base64.getEncoder().encodeToString(projectBytes)
        assertEquals(b64, reencoded)
        assertEquals(
            projectBytes.size,
            envelope.getValue("projectByteCount").jsonPrimitive.content.toInt(),
        )
        assertEquals(
            sha256Hex(projectBytes),
            envelope.getValue("projectSHA256").jsonPrimitive.content,
        )
        assertEquals(64, envelope.getValue("projectSHA256").jsonPrimitive.content.length)
        assertEquals(
            envelope.getValue("projectSHA256").jsonPrimitive.content,
            envelope.getValue("projectSHA256").jsonPrimitive.content.lowercase(),
        )
    }

    @Test
    fun threeSharedProjectFixturesExist() {
        listOf(
            "novel-v1/projects/minimal-blank.project.json",
            "novel-v1/projects/full-two-branch.project.json",
            "novel-v1/projects/interrupted-pending.project.json",
            "novel-v1/projects/legacy-missing-v1-defaults.project.json",
        ).forEach { path ->
            assertNotNull(
                "missing $path",
                requireNotNull(javaClass.classLoader).getResource(path),
            )
        }
    }

    @Test
    fun higherSchemaPackageIsMarkedSchema2() {
        val text = readResource("novel-v1/packages/higher-schema-reject.ambernovel.json")
        val envelope = json.parseToJsonElement(text).jsonObject
        assertEquals(2, envelope.getValue("projectSchemaVersion").jsonPrimitive.content.toInt())
    }

    @Test
    fun fullFixtureUsesBareUuidForFactCompatibilityId() {
        val text = readResource("novel-v1/projects/full-two-branch.project.json")
        val doc = json.parseToJsonElement(text).jsonObject
        val versions = doc.getValue("chapterVersions") as JsonArray
        val version = versions.first().jsonObject
        val factIdElement = version.getValue("factCompatibilityID")
        // Bare UUID string — not a typed-id object with rawValue.
        assertTrue(
            "factCompatibilityID must be a bare UUID string, got $factIdElement",
            factIdElement is kotlinx.serialization.json.JsonPrimitive,
        )
        val factId = factIdElement.jsonPrimitive.content
        assertEquals(factId.uppercase(), factId)
        assertEquals("ABABABAB-ABAB-4BAB-8BAB-ABABABABABAB", factId)
    }

    private fun readResource(path: String): String {
        val stream = requireNotNull(
            requireNotNull(javaClass.classLoader).getResourceAsStream(path),
        ) {
            "Missing test resource: $path"
        }
        return stream.bufferedReader().use { it.readText() }
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }
}
