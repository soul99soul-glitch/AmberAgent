package app.amber.feature.novel.serialization

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.security.MessageDigest
import java.util.Base64

/**
 * Phase 1 green tests for the Swift-compatible codec (evolved from Phase 0 red stubs).
 */
class NovelSwiftCodecRedTest {
    @Test
    fun decodeMinimalBlankProject_succeeds() {
        val bytes = readResourceBytes("novel-v1/projects/minimal-blank.project.json")
        val document = NovelSwiftCompatibleJson.decodeProjectDocument(bytes)
        assertEquals(1, document.schemaVersion)
        assertEquals("Minimal Blank", document.project.name)
    }

    @Test
    fun decodePackageEnvelope_succeeds() {
        val bytes = readResourceBytes("novel-v1/packages/minimal-blank.ambernovel.json")
        val envelope = NovelSwiftCompatibleJson.decodePackageEnvelope(bytes)
        assertEquals(NovelSwiftWireContract.PACKAGE_FORMAT, envelope.format)
        assertEquals(1, envelope.envelopeVersion)
    }

    @Test
    fun canonicalHashAndRoundTrip_succeeds() {
        val projectBytes = readResourceBytes("novel-v1/projects/minimal-blank.project.json")
            .toString(Charsets.UTF_8)
            .trim()
            .toByteArray(Charsets.UTF_8)
        val document = NovelSwiftCompatibleJson.decodeProjectDocument(projectBytes)
        val reencoded = NovelSwiftCompatibleJson.encodeProjectDocument(document)
        val canonical = NovelSwiftCompatibleJson.canonicalJson(
            NovelSwiftCompatibleJson.json.parseToJsonElement(reencoded.decodeToString()),
        )
        val sha = NovelSwiftCompatibleJson.sha256Hex(canonical.toByteArray(Charsets.UTF_8))
        assertEquals(64, sha.length)
        val roundTrip = NovelSwiftCompatibleJson.decodeProjectDocument(reencoded)
        assertEquals(document, roundTrip)
    }

    @Test
    fun packageFixtureChecksumStillValidWithoutCodec() {
        // Fixture integrity does not require the production codec.
        val text = readResourceBytes("novel-v1/packages/minimal-blank.ambernovel.json")
            .toString(Charsets.UTF_8)
        assertTrue(text.contains("\"format\":\"amber.novel.project\""))
        val b64Marker = "\"projectJSONBase64\":\""
        val start = text.indexOf(b64Marker) + b64Marker.length
        val end = text.indexOf('"', start)
        val b64 = text.substring(start, end)
        val projectBytes = Base64.getDecoder().decode(b64)
        val digest = MessageDigest.getInstance("SHA-256").digest(projectBytes)
        val sha = digest.joinToString("") { "%02x".format(it) }
        assertTrue(text.contains("\"projectSHA256\":\"$sha\""))
    }

    private fun readResourceBytes(path: String): ByteArray {
        val stream = requireNotNull(
            requireNotNull(javaClass.classLoader).getResourceAsStream(path),
        ) {
            "Missing test resource: $path"
        }
        return stream.use { it.readBytes() }
    }
}
