package app.amber.feature.runtime

import app.amber.ai.core.Tool
import app.amber.ai.ui.ToolApprovalState
import app.amber.ai.ui.UIMessagePart
import app.amber.feature.tools.Capability
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Step 6 — separate sandbox from approval: the per-run [ExecutionPolicy] gate
 * enforced at the dispatcher boundary on every execution, independent of
 * approval. Pins the family → argument mapping, subdomain semantics, `..`
 * rejection, fail-closed parsing, the permissive pass-through, and that a
 * policy denial never executes the tool body.
 */
class ExecutionPolicyGateTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun dispatcher() = AgentToolDispatcher(json, PermissionDecisionResolver())

    private fun toolCall(toolName: String, input: String) = UIMessagePart.Tool(
        toolCallId = "call_1",
        toolName = toolName,
        input = input,
        approvalState = ToolApprovalState.Auto,
    )

    private fun toolDef(name: String, counter: AtomicInteger? = null): Tool = Tool(
        name = name,
        description = "",
        execute = { _: JsonElement ->
            counter?.incrementAndGet()
            listOf(UIMessagePart.Text("""{"status":"ok"}"""))
        },
    )

    private fun execute(
        toolName: String,
        input: String,
        policy: ExecutionPolicy,
        counter: AtomicInteger = AtomicInteger(0),
    ): Pair<String, AtomicInteger> = runBlocking {
        val result = dispatcher().execute(
            tool = toolCall(toolName, input),
            toolDef = toolDef(toolName, counter),
            autoApproveTools = true,
            autoApproveHighRiskTools = true,
            executionPolicy = policy,
        )!!
        val text = (result.output.single() as UIMessagePart.Text).text
        text to counter
    }

    private fun statusOf(text: String): String =
        json.parseToJsonElement(text).jsonObject["status"]!!.jsonPrimitive.content

    /** Allowed case: ok status and the tool body runs exactly once. */
    private fun assertAllowed(tool: String, input: String, policy: ExecutionPolicy, label: String = tool) {
        val (text, executions) = execute(tool, input, policy)
        assertEquals("tool $label must run", "ok", statusOf(text))
        assertEquals("tool $label must run exactly once", 1, executions.get())
    }

    /**
     * Denied case: policy_denied and the tool body never runs. Returns the raw
     * payload text so callers can add message-shape assertions.
     */
    private fun assertGated(tool: String, input: String, policy: ExecutionPolicy, label: String = tool): String {
        val (text, executions) = execute(tool, input, policy)
        assertEquals("tool $label must be gated", "policy_denied", statusOf(text))
        assertEquals("tool $label body must never run", 0, executions.get())
        return text
    }

    // ── file family ──────────────────────────────────────────────────────────

    @Test
    fun `workspace path under an allowed root executes`() {
        assertAllowed(
            "file_read",
            """{"path":"notes/a.md"}""",
            ExecutionPolicy(allowedPathRoots = listOf("/workspace")),
        )
    }

    @Test
    fun `nested root allows only paths contained under it`() {
        val policy = ExecutionPolicy(allowedPathRoots = listOf("/workspace/notes"))
        assertAllowed("file_read", """{"path":"notes/a.md"}""", policy)
        assertGated("file_read", """{"path":"reports/b.md"}""", policy)
    }

    @Test
    fun `traversal with dot-dot escapes is denied`() {
        val policy = ExecutionPolicy(allowedPathRoots = listOf("/workspace"))
        // WorkspacePaths.normalize throws on '..'; the gate must deny, not crash.
        for (path in listOf("../../etc/passwd", "/workspace/../../etc/passwd")) {
            val text = assertGated("file_read", """{"path":"$path"}""", policy, label = "file_read '$path'")
            assertTrue(text.contains("fail-closed") || text.contains("outside this run"))
        }
    }

    @Test
    fun `absolute non-workspace path is allowed under a matching external root`() {
        val policy = ExecutionPolicy(allowedPathRoots = listOf("/tmp/amber-sandbox"))
        assertAllowed("external_file_read", """{"path":"/tmp/amber-sandbox/sub/file.txt"}""", policy)
        assertGated("external_file_read", """{"path":"/tmp/other/file.txt"}""", policy)
    }

    @Test
    fun `file_move checks both source and target`() {
        val policy = ExecutionPolicy(allowedPathRoots = listOf("/workspace/notes"))
        assertAllowed("file_move", """{"source_path":"notes/a.md","target_path":"notes/b.md"}""", policy)
        assertGated("file_move", """{"source_path":"notes/a.md","target_path":"data/b.md"}""", policy)
    }

    @Test
    fun `missing or blank required path is denied fail-closed while optional path defaults to the root`() {
        val policy = ExecutionPolicy(allowedPathRoots = listOf("/workspace"))
        for (input in listOf("{}", """{"path":"  "}""", "not-json")) {
            assertGated("file_read", input, policy, label = "file_read '$input'")
        }
        // file_list documents a default (workspace root): absent path stays allowed.
        assertAllowed("file_list", "{}", policy)
    }

    @Test
    fun `artifact write targets are gated including their schema defaults`() {
        // archive_extract's default destination /workspace/extracted and
        // pdf_render_page's default /workspace/previews stay inside the full root.
        val whole = ExecutionPolicy(allowedPathRoots = listOf("/workspace"))
        listOf(
            "archive_extract" to """{"path":"a.zip"}""",
            "pdf_render_page" to """{"path":"a.pdf"}""",
        ).forEach { (tool, input) -> assertAllowed(tool, input, whole) }

        // A narrowed root must not inherit the ungated defaults: the schema
        // default write roots are outside /workspace/notes → fail closed.
        val narrowed = ExecutionPolicy(allowedPathRoots = listOf("/workspace/notes"))
        listOf(
            "archive_extract" to """{"path":"notes/a.zip"}""",
            "pdf_render_page" to """{"path":"notes/a.pdf"}""",
        ).forEach { (tool, input) -> assertGated(tool, input, narrowed) }

        // Explicit destinations behave like any path argument.
        assertAllowed("archive_extract", """{"path":"notes/a.zip","destination_path":"notes/out"}""", narrowed)
        assertGated("archive_extract", """{"path":"notes/a.zip","destination_path":"data/out"}""", narrowed)
    }

    @Test
    fun `download_file write target is gated while its url stays under the domain dimension`() {
        // Path dimension alone: the default /workspace/downloads stays inside /workspace.
        assertAllowed(
            "download_file",
            """{"url":"https://example.com/file.bin"}""",
            ExecutionPolicy(allowedPathRoots = listOf("/workspace")),
        )
        val narrowed = ExecutionPolicy(allowedPathRoots = listOf("/workspace/notes"))
        assertAllowed("download_file", """{"url":"https://example.com/file.bin","workspace_path":"notes/file.bin"}""", narrowed)
        assertGated("download_file", """{"url":"https://example.com/file.bin","workspace_path":"data/file.bin"}""", narrowed)

        // One tool, two dimensions: the url check still applies alongside the
        // path check in the same call.
        val bothDimensions = ExecutionPolicy(
            allowedPathRoots = listOf("/workspace"),
            allowedDomains = listOf("example.com"),
        )
        assertGated("download_file", """{"url":"https://attacker.io/file.bin","workspace_path":"f.bin"}""", bothDimensions)
    }

    @Test
    fun `family tools with workspace path arguments are gated`() {
        val workspace = ExecutionPolicy(allowedPathRoots = listOf("/workspace"))
        // Required / workspace-anchored arguments run under the full root.
        listOf(
            "skill_import" to """{"workspace_path":"skills/pkg","preview_digest":"d"}""",
            "skill_preview" to """{"workspace_path":"skills/pkg"}""",
            "share_file" to """{"path":"reports/a.md"}""",
            "model_council_make_report" to """{"run_id":"r","output_path":"model-council/out.md"}""",
            "officepro_daily_radar" to """{"workspace_paths":["docs/a.md"]}""",
            "officepro_make_report" to """{"workspace_paths":["docs/a.md"],"output_path":"officepro/out.md"}""",
        ).forEach { (tool, input) -> assertAllowed(tool, input, workspace) }

        val narrowed = ExecutionPolicy(allowedPathRoots = listOf("/workspace/notes"))
        listOf(
            "skill_import" to """{"workspace_path":"skills/pkg","preview_digest":"d"}""",
            "share_file" to """{"path":"reports/a.md"}""",
            "model_council_make_report" to """{"run_id":"r","output_path":"model-council/out.md"}""",
            "officepro_daily_radar" to """{"workspace_paths":["docs/a.md"]}""",
            "officepro_make_report" to """{"workspace_paths":["notes/a.md"],"output_path":"officepro/out.md"}""",
        ).forEach { (tool, input) -> assertGated(tool, input, narrowed) }

        // Paths inside the narrowed root pass.
        listOf(
            "skill_import" to """{"workspace_path":"notes/pkg","preview_digest":"d"}""",
            "share_file" to """{"path":"notes/a.md"}""",
            "officepro_daily_radar" to """{"workspace_paths":["notes/a.md"]}""",
        ).forEach { (tool, input) -> assertAllowed(tool, input, narrowed) }
    }

    @Test
    fun `absent optional path arguments check their schema defaults or touch nothing`() {
        // soul_preview / soul_import default to SOUL.md at the workspace root.
        assertAllowed(
            "soul_import",
            """{"preview_digest":"d"}""",
            ExecutionPolicy(allowedPathRoots = listOf("/workspace")),
        )
        val narrowed = ExecutionPolicy(allowedPathRoots = listOf("/workspace/notes"))
        assertGated("soul_preview", "{}", narrowed)

        // skill_validate can run on an installed name alone: an absent
        // workspace_path touches nothing under /workspace, so a narrowed root
        // does not deny it. But a present workspace_path is checked.
        assertAllowed("skill_validate", """{"name":"pkg"}""", narrowed)
        assertGated("skill_validate", """{"workspace_path":"data/x"}""", narrowed)

        // officepro arrays: absent or empty lists read no paths.
        assertAllowed("officepro_capture_context", "{}", narrowed, label = "officepro_capture_context(absent paths)")
        assertAllowed("officepro_capture_context", """{"workspace_paths":[]}""", narrowed, label = "officepro_capture_context(empty list)")

        // model_council's default output root /workspace/model-council is
        // outside the narrowed root → denied.
        assertGated("model_council_make_report", """{"run_id":"r"}""", narrowed)
    }

    @Test
    fun `unmodeled root domains are denied while the path dimension is active`() {
        val narrowed = ExecutionPolicy(allowedPathRoots = listOf("/workspace"))
        listOf(
            "icloud_list" to "{}",
            "icloud_stat" to """{"path":"notes.md"}""",
            "icloud_read" to """{"path":"notes.md"}""",
            "icloud_write" to """{"path":"notes.md","content":"x"}""",
            "icloud_search" to """{"query":"x"}""",
            "novel_workspace_read" to """{"path":"chapters/1.md"}""",
            "novel_workspace_write" to """{"path":"inbox/a.md","content":"x"}""",
            "use_skill" to """{"name":"pkg"}""",
        ).forEach { (tool, input) -> assertGated(tool, input, narrowed) }

        // Permissive passes them through untouched (v1 guardrail).
        listOf(
            "icloud_read" to """{"path":"notes.md"}""",
            "novel_workspace_write" to """{"path":"inbox/a.md","content":"x"}""",
            "use_skill" to """{"name":"pkg"}""",
        ).forEach { (tool, input) -> assertAllowed(tool, input, ExecutionPolicy.permissive()) }
    }

    // ── network family ───────────────────────────────────────────────────────

    @Test
    fun `subdomain matches the parent domain but a lookalike does not`() {
        val policy = ExecutionPolicy(allowedDomains = listOf("example.com"))
        listOf(
            "https://api.example.com/v1/items",
            "https://example.com/",
        ).forEach { url -> assertAllowed("http_request", """{"url":"$url"}""", policy, label = "http_request '$url'") }
        listOf(
            "https://evil-example.com/",
            "https://attacker.example.com.evil.io/",
        ).forEach { url -> assertGated("http_request", """{"url":"$url"}""", policy, label = "http_request '$url'") }
    }

    @Test
    fun `every url-carrying tool family member is gated on the domain dimension`() {
        val policy = ExecutionPolicy(allowedDomains = listOf("example.com"))
        for (tool in listOf(
            "download_file",
            "scrape_web",
            "webview_open",
            "webview_open_link",
            "screen_open_url",
        )) {
            assertGated(tool, """{"url":"https://blocked.io/page"}""", policy)
        }
    }

    @Test
    fun `optional url tools skip absent and non-web arguments but gate http urls`() {
        val policy = ExecutionPolicy(allowedDomains = listOf("example.com"))
        // intent_open: absent data_uri and non-web schemes do nothing outbound.
        listOf(
            """{"action":"dial"}""",
            """{"action":"dial","data_uri":"tel:+123456"}""",
            """{"action":"sendto","data_uri":"mailto:a@b.c"}""",
        ).forEach { input -> assertAllowed("intent_open", input, policy, label = "intent_open '$input'") }
        // http(s) data URIs are gated like any outbound URL.
        assertGated("intent_open", """{"action":"view","data_uri":"https://evil.io/"}""", policy)
        assertAllowed("intent_open", """{"action":"view","data_uri":"https://sub.example.com/"}""", policy)

        // officepro_open: app deep links skip the dimension; http(s) is gated.
        assertAllowed("officepro_open", """{"url":"feishu://doc/1"}""", policy)
        assertGated("officepro_open", """{"url":"https://evil.io/"}""", policy)

        // deep_read_open: absent source_url skips; a present source_url is gated.
        assertAllowed("deep_read_open", """{"topic_title":"t"}""", policy)
        assertGated("deep_read_open", """{"source_url":"https://evil.io/x"}""", policy)
        assertAllowed("deep_read_open", """{"source_url":"https://example.com/x"}""", policy)
    }

    @Test
    fun `url without a parseable host is denied fail-closed`() {
        assertGated(
            "http_request",
            """{"url":"not a url"}""",
            ExecutionPolicy(allowedDomains = listOf("example.com")),
        )
    }

    // ── shell family ─────────────────────────────────────────────────────────

    @Test
    fun `allowShell false denies terminal tools and never executes the body`() {
        val policy = ExecutionPolicy(allowShell = false)
        for (tool in listOf(
            "terminal_execute",
            "terminal_job_start",
            "terminal_job_wait",
            "terminal_session_start",
            "terminal_session_exec",
            "terminal_install_packages",
            "terminal_workspace_flush",
        )) {
            assertGated(tool, """{"command":"ls"}""", policy)
        }
    }

    // ── system capability family ─────────────────────────────────────────────

    @Test
    fun `capability not in the allowlist is denied while allowed ones and unmapped tools pass`() {
        val policy = ExecutionPolicy(
            allowedSystemCapabilities = setOf(Capability.FILESYSTEM_READ, Capability.FILESYSTEM_WRITE),
        )
        assertAllowed("file_read", """{"path":"a.md"}""", policy)
        // The denial message names the missing capability.
        assertTrue(
            assertGated("http_request", """{"url":"https://example.com/"}""", policy)
                .contains(Capability.NETWORK_CONNECT.id),
        )
        // No mapped capability → the dimension does not apply.
        assertAllowed("get_time_info", "{}", policy)
    }

    @Test
    fun `narrowed system dimension also covers the device permission tools`() {
        val policy = ExecutionPolicy(allowedSystemCapabilities = setOf(Capability.FILESYSTEM_READ))
        val smsDenied = assertGated("sms_send", """{"phone_number":"+861000000","message":"hi"}""", policy)
        assertTrue(smsDenied.contains(Capability.SMS_SEND.id))
        val captureDenied = assertGated("screen_screenshot", "{}", policy)
        assertTrue(captureDenied.contains(Capability.SCREEN_CAPTURE.id))

        // Naming the capability lets the tool through.
        assertAllowed(
            "sms_send",
            """{"phone_number":"+861000000","message":"hi"}""",
            ExecutionPolicy(allowedSystemCapabilities = setOf(Capability.SMS_SEND)),
        )
        // Permissive stays a pass-through (v1 guardrail).
        assertAllowed("sms_send", """{"phone_number":"+861000000","message":"hi"}""", ExecutionPolicy.permissive())
    }

    // ── guardrail: permissive policy is a pure pass-through ──────────────────

    @Test
    fun `permissive policy passes every family`() {
        val policy = ExecutionPolicy.permissive()
        listOf(
            "file_read" to """{"path":"../../etc/passwd"}""",
            "external_file_read" to """{"path":"/data/data/whatever"}""",
            "http_request" to """{"url":"https://any-host.invalid/"}""",
            "terminal_execute" to """{"command":"rm -rf /"}""",
            "mcp_call_tool" to """{"server":"s","tool":"t"}""",
        ).forEach { (tool, input) -> assertAllowed(tool, input, policy) }
    }

    @Test
    fun `null dimensions each stay unrestricted`() {
        val halfNarrowed = ExecutionPolicy(allowedPathRoots = listOf("/workspace/notes"))
        // allowedDomains / allowShell / allowedSystemCapabilities untouched.
        assertAllowed("http_request", """{"url":"https://anything.example/"}""", halfNarrowed)
        assertAllowed("terminal_execute", """{"command":"ls"}""", halfNarrowed)
    }

    // ── denial shape at the boundary ─────────────────────────────────────────

    @Test
    fun `denial output is the structured policy_denied payload with the permission trace`() = runBlocking {
        val policy = ExecutionPolicy(allowShell = false)
        val result = dispatcher().execute(
            tool = toolCall("terminal_execute", """{"command":"ls"}"""),
            toolDef = toolDef("terminal_execute"),
            autoApproveTools = true,
            autoApproveHighRiskTools = true,
            executionPolicy = policy,
        )!!
        val text = (result.output.single() as UIMessagePart.Text).text
        val payload = json.parseToJsonElement(text).jsonObject
        assertEquals("policy_denied", payload["status"]!!.jsonPrimitive.content)
        val message = payload["message"]!!.jsonPrimitive.content
        assertTrue(message.contains("terminal_execute"))
        assertTrue("the permission trace rides along like any other denial", payload.containsKey("permission_trace"))
    }
}
