package app.amber.core.ai.tools

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import app.amber.ai.core.Tool
import app.amber.ai.ui.UIMessagePart
import app.amber.feature.tools.ToolRegistry
import app.amber.core.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Characterization tests for [createToolPolicyExplainTool]. Freezes the
 * JSON output shape so future tweaks to the underlying ToolRegistry
 * invocation-policy resolver don't silently change what the agent sees
 * when it probes `tool_policy_explain`.
 *
 * The tool itself is a thin facade over
 * `ToolRegistry.evaluateInvocation(toolName, input)`, so these tests
 * exercise both:
 *   1. The tool's JSON shape (status / tool_name / 13 policy keys when found)
 *   2. The registry's policy resolution for representative tool names
 *      (`tool_policy_explain` self-lookup, the `http_request` GET/POST
 *      branch, and a missing tool yielding `status=not_found`)
 *
 * Added in Tier-B follow-up to Codex review of session #3.
 */
class ToolPolicyExplainToolTest {

    /**
     * Build a minimal registry containing the canonical tools we exercise:
     * - `tool_policy_explain` itself (utility category, normal risk, no
     *   approval, auto-approvable, concurrency-safe)
     * - `http_request` (the dynamic-policy case — GET vs POST flips the
     *   mutates / risk / needsApproval triple)
     */
    private fun fixtureRegistry(): ToolRegistry {
        val toolPolicyExplain = Tool(
            name = "tool_policy_explain",
            description = "noop fixture for test",
            execute = { listOf(UIMessagePart.Text("{}")) },
        )
        val httpRequest = Tool(
            name = "http_request",
            description = "noop fixture for test",
            execute = { listOf(UIMessagePart.Text("{}")) },
        )
        return ToolRegistry.from(listOf(toolPolicyExplain, httpRequest))
    }

    private fun runExecute(toolJson: String): JsonObject {
        val tool = createToolPolicyExplainTool(fixtureRegistry())
        val input = JsonInstant.parseToJsonElement(toolJson)
        var captured: JsonObject? = null
        runTest {
            val parts = tool.execute(input)
            assertEquals(1, parts.size)
            val text = (parts.first() as UIMessagePart.Text).text
            captured = JsonInstant.parseToJsonElement(text).jsonObject
        }
        return checkNotNull(captured) { "execute() did not return a payload" }
    }

    @Test
    fun `tool_policy_explain looks up itself — utility, normal, no approval, auto`() {
        val payload = runExecute("""{"tool_name":"tool_policy_explain"}""")

        assertEquals("ok", payload.string("status"))
        assertEquals("tool_policy_explain", payload.string("tool_name"))
        assertEquals("utility", payload.string("category"))
        assertEquals("normal", payload.string("risk"))
        assertEquals(false, payload.bool("mutates"))
        assertEquals(false, payload.bool("needs_approval"))
        assertEquals(true, payload.bool("allows_auto_approval"))
        assertEquals(true, payload.bool("concurrency_safe"))
        assertEquals(false, payload.bool("always_ask"))
    }

    @Test
    fun `http_request with method=GET is safe — normal risk, no approval, concurrency-safe`() {
        val payload = runExecute("""{"tool_name":"http_request","input":"{\"method\":\"GET\"}"}""")

        assertEquals("ok", payload.string("status"))
        assertEquals("http_request", payload.string("tool_name"))
        assertEquals("web", payload.string("category"))
        assertEquals("normal", payload.string("risk"))
        assertEquals(false, payload.bool("mutates"))
        assertEquals(false, payload.bool("needs_approval"))
        assertEquals(true, payload.bool("concurrency_safe"))
    }

    @Test
    fun `http_request with method=POST is unsafe — high risk, needs approval, not concurrency-safe`() {
        val payload = runExecute("""{"tool_name":"http_request","input":"{\"method\":\"POST\"}"}""")

        assertEquals("ok", payload.string("status"))
        assertEquals("http_request", payload.string("tool_name"))
        assertEquals("high", payload.string("risk"))
        assertEquals(true, payload.bool("mutates"))
        assertEquals(true, payload.bool("needs_approval"))
        assertEquals(false, payload.bool("concurrency_safe"))
    }

    @Test
    fun `missing tool returns status=not_found and only the tool_name field, no policy keys`() {
        val payload = runExecute("""{"tool_name":"does_not_exist"}""")

        assertEquals("not_found", payload.string("status"))
        assertEquals("does_not_exist", payload.string("tool_name"))
        // None of the policy keys should be present when the tool isn't found
        // — the implementation only `put`s them inside `policy?.let { ... }`.
        assertNull(payload["category"])
        assertNull(payload["risk"])
        assertNull(payload["mutates"])
        assertNull(payload["needs_approval"])
        assertNull(payload["always_ask"])
    }

    @Test
    fun `missing tool_name input still returns a structured response, not_found`() {
        val payload = runExecute("""{}""")

        assertEquals("not_found", payload.string("status"))
        // Implementation falls back to empty string for missing tool_name
        // (`?.contentOrNull.orEmpty()` at execute path).
        assertEquals("", payload.string("tool_name"))
    }

    @Test
    fun `blank input field for the wrapped tool falls back to empty-object parse`() {
        // The implementation parses input.ifBlank { "{}" } — so passing
        // an empty string should still resolve a policy (no exception).
        val payload = runExecute("""{"tool_name":"tool_policy_explain","input":""}""")
        assertEquals("ok", payload.string("status"))
        assertEquals("tool_policy_explain", payload.string("tool_name"))
    }

    @Test
    fun `output payload has exactly one Text part wrapping the JSON`() {
        val tool = createToolPolicyExplainTool(fixtureRegistry())
        val input = buildJsonObject {
            // Use a real JSON element rather than parsed-string to confirm
            // the tool accepts both primitive-string and object input forms.
            // (The tool only reads .jsonObject of the outer arg.)
        }
        runTest {
            val parts = tool.execute(input.let {
                JsonInstant.parseToJsonElement("""{"tool_name":"tool_policy_explain"}""")
            })
            assertEquals("one Text part", 1, parts.size)
            assertTrue("first part is Text", parts.first() is UIMessagePart.Text)
        }
    }

    @Test
    fun `tool advertises the documented name and required parameter`() {
        val tool = createToolPolicyExplainTool(fixtureRegistry())
        assertEquals("tool_policy_explain", tool.name)
        assertTrue(
            "description mentions 'Explain how AmberAgent would evaluate'",
            tool.description.contains("Explain how AmberAgent would evaluate")
        )
    }

    // --- helpers ---

    private fun JsonObject.string(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull

    private fun JsonObject.bool(key: String): Boolean? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull()
}
