package me.rerere.rikkahub.data.agent.webmount.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SiteProfileTest {

    @Test
    fun parsesAndValidatesMinimalProfile() {
        val raw = """
            {
              "id": "test_site",
              "name": "Test",
              "origins": ["https://test.example"]
            }
        """.trimIndent()
        val profile = profileJson.decodeFromString(SiteProfile.serializer(), raw)
        profile.validate()
        assertEquals("test_site", profile.id)
        assertEquals(1, profile.version)
        assertEquals(SiteProfile.DEFAULT_RATE_LIMIT_PER_SEC, profile.rateLimitPerSec)
        assertTrue(profile.permissions.isEmpty())
    }

    @Test
    fun snakeCaseKeysMapToCamelCaseFields() {
        val raw = """
            {
              "id": "snake",
              "name": "Snake",
              "origins": ["https://snake.example"],
              "hints": {
                "login_cookie": "z_c0",
                "rate_limit": {"http_status": 412, "map_to": "DEGRADED"},
                "interactive_selectors": {"foo": ".bar"}
              },
              "permissions": ["detect_login"],
              "rate_limit_per_sec": 5
            }
        """.trimIndent()
        val profile = profileJson.decodeFromString(SiteProfile.serializer(), raw)
        profile.validate()
        assertEquals("z_c0", profile.hints.loginCookie)
        assertEquals(412, profile.hints.rateLimit?.httpStatus)
        assertEquals("DEGRADED", profile.hints.rateLimit?.mapTo)
        assertEquals(".bar", profile.hints.interactiveSelectors["foo"])
        assertEquals(5, profile.rateLimitPerSec)
    }

    @Test
    fun scriptsCallPageFnMustBeDeclaredInPermissions() {
        val raw = """
            {
              "id": "missing_perm",
              "name": "Missing perm",
              "origins": ["https://x.example"],
              "scripts": {
                "sign_request": {
                  "call_page_fn": "window.__sign",
                  "args": ["url"]
                }
              },
              "permissions": []
            }
        """.trimIndent()
        val profile = profileJson.decodeFromString(SiteProfile.serializer(), raw)
        try {
            profile.validate()
            fail("expected IllegalArgumentException for missing call_page_fn permission")
        } catch (expected: IllegalArgumentException) {
            assertTrue(
                "error mentions missing permission",
                expected.message?.contains("call_page_fn:__sign") == true
            )
        }
    }

    @Test
    fun originsMustHaveScheme() {
        val raw = """
            {
              "id": "bad_origin",
              "name": "Bad origin",
              "origins": ["test.example"]
            }
        """.trimIndent()
        val profile = profileJson.decodeFromString(SiteProfile.serializer(), raw)
        try {
            profile.validate()
            fail("expected IllegalArgumentException for schemeless origin")
        } catch (expected: IllegalArgumentException) {
            // ok
        }
    }

    @Test
    fun loginCookieRequiresDetectLoginPermission() {
        val raw = """
            {
              "id": "no_detect_login",
              "name": "No detect login",
              "origins": ["https://x.example"],
              "hints": {"login_cookie": "x"},
              "permissions": ["read_cookie:x"]
            }
        """.trimIndent()
        val profile = profileJson.decodeFromString(SiteProfile.serializer(), raw)
        try {
            profile.validate()
            fail("expected IllegalArgumentException — login_cookie set without detect_login permission")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("detect_login"))
        }
    }

    @Test
    fun rateLimitClampedToRange() {
        val tooHigh = SiteProfile(
            id = "rate",
            name = "Rate",
            origins = listOf("https://x.example"),
            rateLimitPerSec = 100,
        )
        try {
            tooHigh.validate()
            fail("expected validation error for rate_limit_per_sec=100")
        } catch (expected: IllegalArgumentException) {
            // ok
        }
    }

    @Test
    fun permissionParserHandlesAllVariants() {
        assertEquals(
            ProfilePermission.CallPageFn("__amberSign"),
            ProfilePermission.parse("call_page_fn:__amberSign", "p")
        )
        assertEquals(
            ProfilePermission.CallPageFn("__amberSign"),
            // window. prefix is stripped
            ProfilePermission.parse("call_page_fn:window.__amberSign", "p")
        )
        assertEquals(
            ProfilePermission.ReadCookie("z_c0"),
            ProfilePermission.parse("read_cookie:z_c0", "p")
        )
        assertEquals(
            ProfilePermission.SendSigned("https://api.bilibili.com"),
            ProfilePermission.parse("send_signed:https://api.bilibili.com", "p")
        )
        assertEquals(ProfilePermission.DetectLogin, ProfilePermission.parse("detect_login", "p"))
        assertEquals(ProfilePermission.DetectRateLimit, ProfilePermission.parse("detect_rate_limit", "p"))
    }

    @Test
    fun unknownPermissionRejected() {
        try {
            ProfilePermission.parse("eat_lunch:noodles", "p")
            fail("expected IllegalStateException for unknown permission verb")
        } catch (expected: IllegalStateException) {
            // ok
        }
    }

    @Test
    fun readOnlyClassification() {
        assertTrue(ProfilePermission.ReadCookie("x").isReadOnly())
        assertTrue(ProfilePermission.DetectLogin.isReadOnly())
        assertTrue(ProfilePermission.DetectRateLimit.isReadOnly())
        assertTrue(!ProfilePermission.CallPageFn("y").isReadOnly())
        assertTrue(!ProfilePermission.SendSigned("https://x").isReadOnly())
    }
}
