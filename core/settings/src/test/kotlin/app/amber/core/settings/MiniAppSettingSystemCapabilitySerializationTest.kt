package app.amber.core.settings

import app.amber.core.agent.utils.JsonInstant
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P4 W10: the system-capabilities gate must default on for fresh installs,
 * stay absent-tolerant for legacy persisted settings (older JSON has no
 * `systemCapabilitiesEnabled` field), and round-trip explicit values.
 */
class MiniAppSettingSystemCapabilitySerializationTest {

    @Test
    fun `defaults to enabled`() {
        val setting = MiniAppSetting()
        assertTrue(setting.systemCapabilitiesEnabled)
    }

    @Test
    fun `legacy json without the field decodes as enabled`() {
        val setting = JsonInstant.decodeFromString<MiniAppSetting>(
            """
            {"enabled": true, "networkEnabled": true, "locationEnabled": false}
            """.trimIndent()
        )
        assertTrue(setting.systemCapabilitiesEnabled)
        assertFalse(setting.locationEnabled)
    }

    @Test
    fun `explicit false survives round trip`() {
        val setting = MiniAppSetting(systemCapabilitiesEnabled = false)
        val decoded = JsonInstant.decodeFromString<MiniAppSetting>(
            JsonInstant.encodeToString(setting)
        )
        assertFalse(decoded.systemCapabilitiesEnabled)
        // Other fields keep their values through the same round trip.
        assertEquals(setting.enabled, decoded.enabled)
        assertEquals(setting.networkEnabled, decoded.networkEnabled)
    }
}
