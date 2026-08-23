package app.amber.feature.novel.serialization

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 0 evidence: kotlinx.serialization defaults do not match Swift Codable wire format.
 * These tests pass by proving incompatibility; Phase 1 custom serializers make round-trips green.
 */
class SwiftEncodingIncompatibilityTest {
    private val defaultJson = Json {
        encodeDefaults = true
        explicitNulls = false
    }

    @Test
    fun typedId_defaultEncodingIsObjectButUuidCaseContractIsUppercase() {
        // Use letters so upper/lower case actually differ on the wire.
        val uuid = "abcdefab-cdef-4abc-8def-abcdefabcdef"
        val encoded = defaultJson.encodeToString(DefaultTypedId(uuid.lowercase()))
        // Default object shape matches Swift RawRepresentable-ish {"rawValue":...}
        assertTrue(encoded.contains("\"rawValue\""))
        // Swift UUID strings are uppercase; a naive lowercase string is not the canonical wire form.
        assertNotEquals(
            """{"rawValue":"${uuid.uppercase()}"}""",
            encoded,
        )
        assertTrue(encoded.contains(uuid.lowercase()))
    }

    @Test
    fun date_unixEpochMustBecomeSwiftReferenceSeconds() {
        val unixEpoch = 0.0
        val swiftSeconds = NovelSwiftWireContract.unixToSwiftDateSeconds(unixEpoch)
        assertEquals(-978_307_200.0, swiftSeconds, 0.0)

        // A naive model that stores Unix seconds is wire-incompatible.
        val naive = defaultJson.encodeToString(DefaultDateHolder(createdAt = unixEpoch))
        assertTrue("naive unix date should not already be Swift reference seconds: $naive", naive.contains("0"))
        assertFalse(naive.contains("-978307200"))
    }

    @Test
    fun associatedEnum_defaultSealedClassUsesClassDiscriminatorNotSwiftKeyedObject() {
        val global = defaultJson.encodeToString(
            DefaultModelPolicyHolder(DefaultModelPolicy.Global),
        )
        val fixed = defaultJson.encodeToString(
            DefaultModelPolicyHolder(
                DefaultModelPolicy.Fixed(providerID = "p", modelID = "m"),
            ),
        )
        val custom = defaultJson.encodeToString(
            DefaultMaterialKindHolder(DefaultMaterialKind.Custom("Place")),
        )

        // kotlinx default sealed polymorphism uses type discriminator keys, not Swift's
        // single-case keyed containers like {"global":{}} / {"fixed":{...}} / {"custom":{"_0":...}}.
        val swiftGlobal = """{"modelPolicy":{"global":{}}}"""
        val swiftFixed =
            """{"modelPolicy":{"fixed":{"modelID":"m","providerID":"p"}}}"""
        val swiftCustom = """{"kind":{"custom":{"_0":"Place"}}}"""
        assertNotEquals("default global sealed encoding must differ from Swift", swiftGlobal, global)
        assertNotEquals("default fixed sealed encoding must differ from Swift", swiftFixed, fixed)
        assertNotEquals("default custom sealed encoding must differ from Swift", swiftCustom, custom)
        assertFalse(
            "Swift custom material uses {\"custom\":{\"_0\":\"Place\"}}",
            custom.contains("\"_0\":\"Place\""),
        )
    }

    @Test
    fun packageContractConstantsMatchIos() {
        assertEquals("amber.novel.project", NovelSwiftWireContract.PACKAGE_FORMAT)
        assertEquals(1, NovelSwiftWireContract.ENVELOPE_VERSION)
        assertEquals(1, NovelSwiftWireContract.PROJECT_SCHEMA_VERSION)
        assertEquals("ambernovel", NovelSwiftWireContract.PACKAGE_EXTENSION)
        assertEquals(
            "application/vnd.amberagent.novel+json",
            NovelSwiftWireContract.PACKAGE_MIME,
        )
        assertEquals(100 * 1024 * 1024, NovelSwiftWireContract.MAX_PROJECT_BYTES)
        assertEquals(140 * 1024 * 1024, NovelSwiftWireContract.MAX_ENVELOPE_BYTES)
    }
}
