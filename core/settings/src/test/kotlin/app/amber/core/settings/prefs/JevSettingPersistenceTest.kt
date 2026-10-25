package app.amber.core.settings.prefs

import android.app.Application
import app.amber.core.agent.utils.JsonInstant
import app.amber.core.jev.JevMode
import app.amber.core.jev.JevPurpose
import app.amber.core.jev.JevSetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * AgentPrefs reads Jev through [decodeJsonOrNull] → [JsonInstant.decodeFromString].
 * Removing SCREEN_AUTOMATION from the enum must not drop the whole setting.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class JevSettingPersistenceTest {

    @Test
    fun agentPrefsPathMigratesLegacyActiveWithoutFailingDecode() {
        val raw = """{"enabled":true,"purposes":{"SCREEN_AUTOMATION":"ACTIVE","TOOL_DISCOVERY":"SHADOW"},"apiKeyMask":"ab…yz"}"""
        val decoded = requireNotNull(raw.decodeJsonOrNull<JevSetting>())
        assertEquals(JevMode.SHADOW, decoded.modeFor(JevPurpose.TOOL_DISCOVERY))
        assertEquals(JevMode.ACTIVE, decoded.modeFor(JevPurpose.PHONE_VERIFICATION))
        assertEquals(JevMode.ACTIVE, decoded.modeFor(JevPurpose.PHONE_ACTIONS))
        assertEquals("ab…yz", decoded.apiKeyMask)
        val encoded = JsonInstant.encodeToString(decoded)
        assertFalse(encoded.contains("SCREEN_AUTOMATION"))
        assertEquals(JevPurpose.VISIBLE_COUNT, JevPurpose.entries.size)
        assertFalse(JevPurpose.entries.any { it.name == "SCREEN_AUTOMATION" })
    }

    @Test
    fun agentPrefsPathKeepsLegacyOffClosed() {
        val raw = """{"enabled":true,"purposes":{"SCREEN_AUTOMATION":"OFF","WEB_AUTOMATION":"ACTIVE"}}"""
        val decoded = requireNotNull(raw.decodeJsonOrNull<JevSetting>())
        assertEquals(JevMode.OFF, decoded.modeFor(JevPurpose.PHONE_VERIFICATION))
        assertEquals(JevMode.OFF, decoded.modeFor(JevPurpose.PHONE_ACTIONS))
        assertEquals(JevMode.ACTIVE, decoded.modeFor(JevPurpose.WEB_AUTOMATION))
    }

    @Test
    fun absentLegacyKeyDoesNotReopenPhonePurposes() {
        val raw = """{"enabled":true,"purposes":{"TOOL_DISCOVERY":"ACTIVE"}}"""
        val decoded = requireNotNull(raw.decodeJsonOrNull<JevSetting>())
        assertEquals(JevMode.OFF, decoded.modeFor(JevPurpose.PHONE_VERIFICATION))
        assertEquals(JevMode.OFF, decoded.modeFor(JevPurpose.PHONE_ACTIONS))
    }

    @Test
    fun explicitPhoneKeysWinWhenCoexistingWithLegacy() {
        val raw = """{"enabled":true,"purposes":{"SCREEN_AUTOMATION":"ACTIVE","PHONE_VERIFICATION":"OFF","PHONE_ACTIONS":"SHADOW"}}"""
        val decoded = requireNotNull(raw.decodeJsonOrNull<JevSetting>())
        assertEquals(JevMode.OFF, decoded.modeFor(JevPurpose.PHONE_VERIFICATION))
        assertEquals(JevMode.SHADOW, decoded.modeFor(JevPurpose.PHONE_ACTIONS))
    }

    @Test
    fun unknownPurposeKeyDoesNotDropTheWholeSetting() {
        val raw = """{"enabled":true,"purposes":{"SCREEN_AUTOMATION":"SHADOW","NOT_A_PURPOSE":"ACTIVE","MEMORY_RECALL":"ACTIVE"}}"""
        val decoded = requireNotNull(raw.decodeJsonOrNull<JevSetting>())
        assertNotNull(decoded)
        assertEquals(JevMode.ACTIVE, decoded.modeFor(JevPurpose.MEMORY_RECALL))
        assertEquals(JevMode.SHADOW, decoded.modeFor(JevPurpose.PHONE_VERIFICATION))
        assertEquals(JevMode.SHADOW, decoded.modeFor(JevPurpose.PHONE_ACTIONS))
        assertTrue(decoded.enabled)
    }

    @Test
    fun jsonInstantMatchesAgentPrefsDecoder() {
        val raw = """{"enabled":true,"purposes":{"SCREEN_AUTOMATION":"ACTIVE"}}"""
        val viaPrefs = raw.decodeJsonOrNull<JevSetting>()
        val viaInstant = JsonInstant.decodeFromString<JevSetting>(raw)
        assertEquals(viaInstant, viaPrefs)
    }
}
