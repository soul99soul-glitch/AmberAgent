package app.amber.feature.tools

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import app.amber.ai.core.Tool

/**
 * http_request invocation policy: public GET/HEAD keeps the read-only fast path,
 * while loopback/private-network targets escalate to High risk so they are only
 * auto-approved through the explicit high-risk toggle.
 */
class HttpRequestPolicyTest {

    private val tool = Tool(
        name = "http_request",
        description = "",
        execute = { emptyList() },
    )

    private fun policy(method: String, url: String): ToolInvocationPolicy = tool.invocationPolicy(
        buildJsonObject {
            put("method", method)
            put("url", url)
        }
    )

    @Test
    fun `public get stays read-only and auto-approvable`() {
        val policy = policy("GET", "https://example.com/page")
        assertFalse(policy.needsApproval)
        assertEquals(ToolRisk.Normal, policy.risk)
        assertTrue(policy.autoApprovable)
    }

    @Test
    fun `private and loopback targets escalate to high risk`() {
        listOf(
            "http://localhost:8080/admin",
            "http://127.0.0.1/x",
            "http://10.0.0.5/",
            "http://192.168.1.1/config",
            "http://172.16.0.9/",
            "http://169.254.169.254/latest/meta-data",
            "http://[::1]/",
            "http://[fd12:3456::1]/x",
            "http://router.local/",
        ).forEach { url ->
            val policy = policy("GET", url)
            assertTrue("$url should need approval", policy.needsApproval)
            assertEquals("$url should be high risk", ToolRisk.High, policy.risk)
            assertFalse("$url should not be plain auto-approvable", policy.autoApprovable)
        }
    }

    @Test
    fun `public ip and hostname stay public`() {
        listOf(
            "https://93.184.216.34/page",
            "https://api.openai.com/v1/responses",
        ).forEach { url ->
            val policy = policy("GET", url)
            assertFalse("$url should not need approval", policy.needsApproval)
            assertEquals("$url should be normal risk", ToolRisk.Normal, policy.risk)
        }
    }

    @Test
    fun `write methods remain high risk regardless of host`() {
        val policy = policy("POST", "https://example.com/api")
        assertTrue(policy.needsApproval)
        assertEquals(ToolRisk.High, policy.risk)
        assertTrue(policy.mutates)
    }
}
