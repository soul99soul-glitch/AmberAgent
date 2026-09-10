package app.amber.feature.miniapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import app.amber.core.settings.MiniAppSetting

/**
 * P4 W10/W11 protocol-layer regressions: fixed registry, system gate, new
 * permission parsing and the openURL/share URL validator table.
 */
class MiniAppSystemCapabilityProtocolTest {

    // ------------------------------------------------------------ registry

    @Test
    fun `registry maps every system method to its permission`() {
        assertEquals(MiniAppPermission.Haptics, MiniAppSystemCapabilityRegistry.methodPermissions["haptics.impact"])
        assertEquals(MiniAppPermission.Haptics, MiniAppSystemCapabilityRegistry.methodPermissions["haptics.selection"])
        assertEquals(MiniAppPermission.Device, MiniAppSystemCapabilityRegistry.methodPermissions["device.getInfo"])
        assertEquals(MiniAppPermission.Screen, MiniAppSystemCapabilityRegistry.methodPermissions["screen.setBrightness"])
        assertEquals(MiniAppPermission.Speech, MiniAppSystemCapabilityRegistry.methodPermissions["speech.speak"])
        assertEquals(MiniAppPermission.Share, MiniAppSystemCapabilityRegistry.methodPermissions["share"])
        assertEquals(MiniAppPermission.OpenURL, MiniAppSystemCapabilityRegistry.methodPermissions["openURL"])
        assertFalse(MiniAppSystemCapabilityRegistry.methodPermissions.containsKey("qrcode.generate"))
        assertEquals(15, MiniAppSystemCapabilityRegistry.methodPermissions.size)
        assertTrue(MiniAppSystemCapabilityRegistry.allSystemMethods.contains("qrcode.generate"))
    }

    @Test
    fun `available methods are the registry intersected with handler support`() {
        val partial = handlerOf("haptics.impact", "qrcode.generate")
        assertEquals(setOf("haptics.impact", "qrcode.generate"), MiniAppSystemCapabilityRegistry.availableMethods(partial))
        assertTrue(MiniAppSystemCapabilityRegistry.availableMethods(null).isEmpty())
    }

    @Test
    fun `bridge version only reports system-capabilities when the full set is supported`() {
        val full = handlerOf(*MiniAppSystemCapabilityRegistry.allSystemMethods.toTypedArray())
        assertEquals(
            MiniAppSystemCapabilityRegistry.BRIDGE_VERSION_SYSTEM_CAPABILITIES,
            MiniAppSystemCapabilityRegistry.bridgeVersion(full),
        )
        val partial = handlerOf("haptics.impact", "qrcode.generate")
        assertEquals(MiniAppSystemCapabilityRegistry.BRIDGE_VERSION_BASE, MiniAppSystemCapabilityRegistry.bridgeVersion(partial))
        assertEquals(MiniAppSystemCapabilityRegistry.BRIDGE_VERSION_BASE, MiniAppSystemCapabilityRegistry.bridgeVersion(null))
    }

    // ------------------------------------------------------------ permissions

    @Test
    fun `new system permissions belong to V3 set and enum`() {
        listOf("haptics", "device", "screen", "speech", "share", "openURL").forEach { permission ->
            assertTrue("missing V3 permission $permission", permission in MiniAppV3Permissions)
        }
        assertEquals("haptics", MiniAppPermission.Haptics.value)
        assertEquals("openURL", MiniAppPermission.OpenURL.value)
    }

    @Test
    fun `permission aliases normalize to system permissions`() {
        assertEquals("haptics", MiniAppPermissionAliases["vibrate"])
        assertEquals("haptics", MiniAppPermissionAliases["vibration"])
        assertEquals("haptics", MiniAppPermissionAliases["haptic"])
        assertEquals("haptics", MiniAppPermissionAliases["振动"])
        assertEquals("haptics", MiniAppPermissionAliases["震动"])
        assertEquals("openURL", MiniAppPermissionAliases["openurl"])
    }

    @Test
    fun `output parser accepts new system permissions`() {
        val parser = MiniAppOutputParser()
        val output = parser.parse(
            """
            {"title":"系统验收","description":"Phase4 系统能力验收","icon":"✅","category":"tool",
             "permissions":["haptics","device","screen","speech","share","openURL","vibrate","openurl"],
             "html":"<html><body>ok</body></html>"}
            """.trimIndent(),
        )
        assertEquals(
            setOf("haptics", "device", "screen", "speech", "share", "openURL"),
            output.permissions.toSet(),
        )
    }

    // ------------------------------------------------------------ sandbox gate

    @Test
    fun `system gate is off when systemCapabilitiesEnabled is false`() {
        val sandbox = MiniAppSandbox(
            appId = "app-1",
            declaredPermissions = setOf("haptics", "device"),
            setting = MiniAppSetting(systemCapabilitiesEnabled = false),
        )
        assertTrue(runCatching { sandbox.require(MiniAppPermission.Haptics) }.isFailure)
        assertTrue(runCatching { sandbox.require(MiniAppPermission.Device) }.isFailure)
        assertFalse(sandbox.systemCapabilitiesEnabled())
    }

    @Test
    fun `system permissions enabled by default and require declaration`() {
        val sandbox = MiniAppSandbox(appId = "app-1", declaredPermissions = setOf("haptics"))
        assertTrue(sandbox.systemCapabilitiesEnabled())
        sandbox.require(MiniAppPermission.Haptics)
        assertTrue(runCatching { sandbox.require(MiniAppPermission.Screen) }.isFailure)
    }

    // ------------------------------------------------------------ openURL table

    @Test
    fun `openURL accepts public https mailto and tel`() {
        assertEquals("https://example.com/path", MiniAppOpenUrlValidator.validate("https://example.com/path").url)
        assertEquals("mailto:someone@example.com", MiniAppOpenUrlValidator.validate("mailto:someone@example.com").url)
        assertEquals("tel:+8613800138000", MiniAppOpenUrlValidator.validate("tel:+8613800138000").url)
    }

    @Test
    fun `openURL rejects local private and dangerous targets`() {
        val rejected = listOf(
            "https://127.0.0.1/private",
            "https://127.1/",
            "https://2130706433/",
            "https://0x7f000001/",
            "https://0177.0.0.1/",
            "https://%31%32%37.0.0.1/",
            "https://%6cocalhost/",
            "https://１２７.０.０.１/",
            "https://192.168.1/",
            "https://256.0.0.1/",
            "https://localhost/admin",
            "https://192.168.1.1/router",
            "https://10.0.0.5/internal",
            "https://172.16.0.1/x",
            "https://169.254.169.254/metadata",
            "https://[::1]/x",
            "https://[fe80::1]/x",
            "https://[fd00::1]/x",
            "https://example.com:8443@evil.com/x",
            "https://user:pass@example.com/",
            "http://example.com/insecure",
            "javascript:alert(1)",
            "file:///etc/passwd",
            "intent://x/#Intent;scheme=http;end",
            "content://contacts/",
            "",
            "   ",
        )
        rejected.forEach { url ->
            assertTrue("expected rejection for $url", runCatching { MiniAppOpenUrlValidator.validate(url) }.isFailure)
        }
    }

    @Test
    fun `openURL rejects control characters whitespace and oversized input`() {
        val rejected = listOf(
            "https://example.com/a b",
            "\nhttps://example.com/",
            "https://example.com/\t",
            "https://example.com/\u0000",
            "https://example.com/" + "a".repeat(4_100),
            // IPv6 literal followed by a non-port suffix or invalid port.
            "https://[2001:4860::8888]evil/x",
            "https://[2001:4860::8888]:abc/x",
            "https://[2001:4860::8888]:/x",
            "https://[2001:4860::8888]:99999/x",
            // Invalid ports on regular hosts.
            "https://example.com:port/x",
            "https://example.com:/x",
            "https://example.com:0/x",
        )
        rejected.forEach { url ->
            assertTrue("expected rejection for ${url.take(40)}", runCatching { MiniAppOpenUrlValidator.validate(url) }.isFailure)
        }
    }

    @Test
    fun `openURL accepts valid ports and public IPv6 with port`() {
        assertEquals(
            "https://example.com:8443/path",
            MiniAppOpenUrlValidator.validate("https://example.com:8443/path").url,
        )
        assertEquals(
            "https://[2001:4860::8888]:8443/",
            MiniAppOpenUrlValidator.validate("https://[2001:4860::8888]:8443/").url,
        )
    }


    @Test
    fun `public host check rejects private ranges and accepts public hosts`() {
        assertFalse(MiniAppOpenUrlValidator.publicHostAllowed("127.0.0.1"))
        assertFalse(MiniAppOpenUrlValidator.publicHostAllowed("10.1.2.3"))
        assertFalse(MiniAppOpenUrlValidator.publicHostAllowed("192.168.0.1"))
        assertFalse(MiniAppOpenUrlValidator.publicHostAllowed("172.31.255.1"))
        assertFalse(MiniAppOpenUrlValidator.publicHostAllowed("169.254.1.1"))
        assertFalse(MiniAppOpenUrlValidator.publicHostAllowed("100.64.1.1"))
        assertFalse(MiniAppOpenUrlValidator.publicHostAllowed("198.18.0.1"))
        assertFalse(MiniAppOpenUrlValidator.publicHostAllowed("224.0.0.1"))
        assertFalse(MiniAppOpenUrlValidator.publicHostAllowed("localhost"))
        assertFalse(MiniAppOpenUrlValidator.publicHostAllowed("myhost.local"))
        assertTrue(MiniAppOpenUrlValidator.publicHostAllowed("example.com"))
        assertTrue(MiniAppOpenUrlValidator.publicHostAllowed("8.8.8.8"))
        assertTrue(MiniAppOpenUrlValidator.publicHostAllowed("2001:4860::8888"))
    }

    private fun handlerOf(vararg methods: String): MiniAppSystemCapabilityHandler =
        object : MiniAppSystemCapabilityHandler {
            override val supportedMethods: Set<String> = methods.toSet()
            override suspend fun dispatch(method: String, params: kotlinx.serialization.json.JsonObject) =
                throw UnsupportedOperationException("not used in this test")
        }
}
