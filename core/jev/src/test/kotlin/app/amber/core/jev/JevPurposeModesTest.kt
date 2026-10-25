package app.amber.core.jev

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JevPurposeModesTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
    }

    @Test
    fun visiblePurposeCountIsThirteen() {
        assertEquals(JevPurpose.VISIBLE_COUNT, JevPurpose.entries.size)
        assertFalse(JevPurpose.entries.any { it.name == JevPurpose.LEGACY_SCREEN_AUTOMATION })
        assertTrue(JevPurpose.PHONE_VERIFICATION in JevPurpose.entries)
        assertTrue(JevPurpose.PHONE_ACTIONS in JevPurpose.entries)
        assertTrue(JevPurpose.SUBAGENT_INTENT in JevPurpose.entries)
        assertTrue(JevPurpose.APPROVAL_TRIAGE in JevPurpose.entries)
        assertTrue(JevPurpose.SCRIPT_JUDGMENT in JevPurpose.entries)
    }

    @Test
    fun requiredScopesAreTheSingleAvailabilitySource() {
        assertEquals(
            setOf(JevDataScope.TOOL_METADATA, JevDataScope.TASK_TEXT),
            JevPurpose.SUBAGENT_INTENT.requiredScopes,
        )
        assertEquals(
            setOf(JevDataScope.TOOL_METADATA, JevDataScope.TASK_TEXT),
            JevPurpose.APPROVAL_TRIAGE.requiredScopes,
        )
        assertEquals(setOf(JevDataScope.TOOL_OUTPUT), JevPurpose.SCRIPT_JUDGMENT.requiredScopes)
        assertEquals(
            setOf(JevDataScope.SCREEN_CONTENT, JevDataScope.TASK_TEXT),
            JevPurpose.PHONE_VERIFICATION.requiredScopes,
        )
        assertEquals(
            setOf(JevDataScope.SCREEN_CONTENT, JevDataScope.TASK_TEXT),
            JevPurpose.PHONE_ACTIONS.requiredScopes,
        )
    }

    @Test
    fun legacyActiveScreenAutomationFillsBothPhonePurposes() {
        val decoded = json.decodeFromString<JevSetting>(
            """{"enabled":true,"purposes":{"SCREEN_AUTOMATION":"ACTIVE","TOOL_DISCOVERY":"SHADOW"}}""",
        )
        assertEquals(JevMode.SHADOW, decoded.modeFor(JevPurpose.TOOL_DISCOVERY))
        assertEquals(JevMode.ACTIVE, decoded.modeFor(JevPurpose.PHONE_VERIFICATION))
        assertEquals(JevMode.ACTIVE, decoded.modeFor(JevPurpose.PHONE_ACTIONS))
        val encoded = json.encodeToString(JevSetting.serializer(), decoded)
        assertFalse(encoded.contains("SCREEN_AUTOMATION"))
        assertTrue(encoded.contains("PHONE_VERIFICATION"))
        assertTrue(encoded.contains("PHONE_ACTIONS"))
    }

    @Test
    fun legacyOffScreenAutomationDoesNotReopenPhonePurposes() {
        val decoded = json.decodeFromString<JevSetting>(
            """{"enabled":true,"purposes":{"SCREEN_AUTOMATION":"OFF","WEB_AUTOMATION":"ACTIVE"}}""",
        )
        assertEquals(JevMode.OFF, decoded.modeFor(JevPurpose.PHONE_VERIFICATION))
        assertEquals(JevMode.OFF, decoded.modeFor(JevPurpose.PHONE_ACTIONS))
        assertEquals(JevMode.ACTIVE, decoded.modeFor(JevPurpose.WEB_AUTOMATION))
    }

    @Test
    fun absentLegacyScreenAutomationStaysOff() {
        val decoded = json.decodeFromString<JevSetting>(
            """{"enabled":true,"purposes":{"TOOL_DISCOVERY":"ACTIVE"}}""",
        )
        assertEquals(JevMode.ACTIVE, decoded.modeFor(JevPurpose.TOOL_DISCOVERY))
        assertEquals(JevMode.OFF, decoded.modeFor(JevPurpose.PHONE_VERIFICATION))
        assertEquals(JevMode.OFF, decoded.modeFor(JevPurpose.PHONE_ACTIONS))
    }

    @Test
    fun explicitPhoneKeysWinOverLegacyScreenAutomation() {
        val decoded = json.decodeFromString<JevSetting>(
            """{"enabled":true,"purposes":{"SCREEN_AUTOMATION":"ACTIVE","PHONE_VERIFICATION":"OFF","PHONE_ACTIONS":"SHADOW"}}""",
        )
        assertEquals(JevMode.OFF, decoded.modeFor(JevPurpose.PHONE_VERIFICATION))
        assertEquals(JevMode.SHADOW, decoded.modeFor(JevPurpose.PHONE_ACTIONS))
    }

    @Test
    fun unknownPurposeKeysDoNotFailTheWholeSetting() {
        val decoded = json.decodeFromString<JevSetting>(
            """{"enabled":true,"purposes":{"SCREEN_AUTOMATION":"SHADOW","NOT_A_PURPOSE":"ACTIVE","MEMORY_RECALL":"ACTIVE"}}""",
        )
        assertEquals(JevMode.ACTIVE, decoded.modeFor(JevPurpose.MEMORY_RECALL))
        assertEquals(JevMode.SHADOW, decoded.modeFor(JevPurpose.PHONE_VERIFICATION))
        assertEquals(JevMode.SHADOW, decoded.modeFor(JevPurpose.PHONE_ACTIONS))
        assertEquals(JevMode.OFF, decoded.modeFor(JevPurpose.TOOL_DISCOVERY))
    }

    @Test
    fun migrateHelperDocumentsCoexistPriority() {
        val filled = migrateJevPurposeModes(
            known = emptyMap(),
            legacyScreenAutomation = JevMode.ACTIVE,
        )
        assertEquals(JevMode.ACTIVE, filled[JevPurpose.PHONE_VERIFICATION])
        assertEquals(JevMode.ACTIVE, filled[JevPurpose.PHONE_ACTIONS])

        val explicitWins = migrateJevPurposeModes(
            known = mapOf(JevPurpose.PHONE_VERIFICATION to JevMode.OFF),
            legacyScreenAutomation = JevMode.ACTIVE,
        )
        assertEquals(JevMode.OFF, explicitWins[JevPurpose.PHONE_VERIFICATION])
        assertEquals(JevMode.ACTIVE, explicitWins[JevPurpose.PHONE_ACTIONS])

        val absentLegacy = migrateJevPurposeModes(known = emptyMap(), legacyScreenAutomation = null)
        assertTrue(absentLegacy.isEmpty())
    }
}
