package app.amber.agent

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.amber.core.agent.utils.JsonInstant
import app.amber.core.jev.JevMode
import app.amber.core.jev.JevPurpose
import app.amber.core.jev.JevSetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

/** Device ART decode of the Jev phone-purpose split used by AgentPrefs. */
@RunWith(AndroidJUnit4::class)
class JevSettingsDeviceTest {
    @Test
    fun legacyScreenAutomationSplitsToPhoneVerificationAndActions() {
        val decoded = JsonInstant.decodeFromString<JevSetting>(
            """{"enabled":true,"purposes":{"SCREEN_AUTOMATION":"ACTIVE","TOOL_DISCOVERY":"SHADOW"},"apiKeyMask":"ab…yz"}""",
        )
        assertEquals(JevMode.SHADOW, decoded.modeFor(JevPurpose.TOOL_DISCOVERY))
        assertEquals(JevMode.ACTIVE, decoded.modeFor(JevPurpose.PHONE_VERIFICATION))
        assertEquals(JevMode.ACTIVE, decoded.modeFor(JevPurpose.PHONE_ACTIONS))
        val encoded = JsonInstant.encodeToString(decoded)
        assertFalse(encoded.contains("SCREEN_AUTOMATION"))
        assertEquals(JevPurpose.VISIBLE_COUNT, JevPurpose.entries.size)
    }

    @Test
    fun explicitPhoneKeysWinAndUnknownKeysDoNotDropTheSetting() {
        val split = JsonInstant.decodeFromString<JevSetting>(
            """{"enabled":true,"purposes":{"SCREEN_AUTOMATION":"ACTIVE","PHONE_VERIFICATION":"OFF","PHONE_ACTIONS":"SHADOW"}}""",
        )
        assertEquals(JevMode.OFF, split.modeFor(JevPurpose.PHONE_VERIFICATION))
        assertEquals(JevMode.SHADOW, split.modeFor(JevPurpose.PHONE_ACTIONS))

        val unknown = JsonInstant.decodeFromString<JevSetting>(
            """{"enabled":true,"purposes":{"SCREEN_AUTOMATION":"SHADOW","NOT_A_PURPOSE":"ACTIVE","APPROVAL_TRIAGE":"ACTIVE"}}""",
        )
        assertEquals(JevMode.ACTIVE, unknown.modeFor(JevPurpose.APPROVAL_TRIAGE))
        assertEquals(JevMode.SHADOW, unknown.modeFor(JevPurpose.PHONE_VERIFICATION))
        assertEquals(JevMode.SHADOW, unknown.modeFor(JevPurpose.PHONE_ACTIONS))
    }
}
