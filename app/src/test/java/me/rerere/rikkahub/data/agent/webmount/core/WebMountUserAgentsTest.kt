package me.rerere.rikkahub.data.agent.webmount.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebMountUserAgentsTest {
    @Test
    fun defaultLoginUserAgentLooksLikeMobileChromeNotWebView() {
        val ua = WebMountUserAgents.loginUserAgent("x_com", "https://x.com/i/flow/login").orEmpty()

        assertTrue(ua.contains("Chrome/"))
        assertTrue(ua.contains("Mobile Safari/537.36"))
        assertFalse(ua.contains("; wv"))
        assertFalse(ua.contains("Version/4.0"))
    }
}
