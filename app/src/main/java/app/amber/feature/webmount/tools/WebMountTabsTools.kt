package app.amber.feature.webmount.tools

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import app.amber.ai.core.InputSchema
import app.amber.ai.core.Tool
import app.amber.ai.ui.UIMessagePart
import app.amber.core.agent.utils.requiredString
import app.amber.core.agent.utils.string
import app.amber.feature.webmount.primitives.WebMountOwner
import java.util.UUID

internal fun createTabListTool(deps: WebMountDeps): Tool = Tool(
    name = "wm_tab_list",
    description = """
        List every live WebMount session in the pool with {session_id, url, title, status}.
        Use this before wm_tab_close to see which sessions are open and what they hold.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(properties = buildJsonObject {})
    },
    execute = { input ->
        deps.track("wm_tab_list", "WebMount 列出会话", input) {
            input.agentScopeFailure()?.let { return@track it }
            val conversationId = input.webMountConversationId()
            val runId = input.webMountRunId()
            // A human-held session is never enumerated. If it belongs to the
            // current conversation, report the owner conflict without
            // disclosing its URL/title or another conversation's sessions.
            val humanOwned = deps.owner.sessions.value.firstOrNull { record ->
                record.owner == WebMountOwner.HUMAN &&
                    (record.conversationId == null || record.conversationId == conversationId)
            }
            if (humanOwned != null) {
                return@track listOf(UIMessagePart.Text(buildJsonObject {
                    put("ok", false)
                    put("error", "owner_conflict")
                    put("owner_conflict", true)
                    put("current_owner", "human")
                }.toString()))
            }
            val sessions = deps.owner.sessions.value
                .filter { record ->
                    record.conversationId == conversationId && record.runId == runId
                }
                .map { record ->
                buildJsonObject {
                    put("session_id", record.sessionId)
                    put("url", record.redactedUrl)
                    put("title", record.title)
                    put("status", record.status)
                    put("owner", record.owner.name.lowercase())
                    put("needs_reopen", record.needsReopen)
                    put("last_activity_ms", record.lastActivityMs)
                }
            }
            val payload = buildJsonObject {
                put("ok", true)
                put("count", sessions.size)
                put("sessions", buildJsonArray { sessions.forEach { add(it) } })
            }
            listOf(UIMessagePart.Text(payload.toString()))
        }
    },
)

internal fun createTabNewTool(deps: WebMountDeps): Tool = Tool(
    name = "wm_tab_new",
    description = """
        Allocate a brand-new WebMount session without navigating. Returns the freshly-issued
        session_id. The session is empty (about:blank) — the agent typically follows with
        wm_open to load the first URL. Useful when the agent wants to work on two pages in
        parallel; tools on different sessions run concurrently (per-session parallel groups).
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(properties = buildJsonObject {})
    },
    execute = { input ->
        deps.track("wm_tab_new", "WebMount 新建会话", buildJsonObject {}) {
            input.agentScopeFailure()?.let { return@track it }
            val sessionId = "wm_" + UUID.randomUUID().toString().substring(0, 12)
            deps.withAgentSession(input = input, sessionId = sessionId) { lease ->
                val payload = buildJsonObject {
                    put("session_id", lease.sessionId)
                    put("action_id", UUID.randomUUID().toString())
                    put("dispatched", true)
                    put("status", "verified")
                    put("snapshot_id_before", null as String?)
                    put("snapshot_id_after", null as String?)
                    put("page_changed", false)
                    put("goal_verified", true)
                    put("session_status", lease.handle.loadState.value.status.wireName)
                    putWebMountWindowState(lease.handle)
                }
                listOf(UIMessagePart.Text(payload.toString()))
            }
        }
    },
)

internal fun createTabCloseTool(deps: WebMountDeps): Tool = Tool(
    name = "wm_tab_close",
    description = """
        Destroy a WebMount session and release its WebView. After this, the session_id is no longer
        valid — subsequent calls referencing it fail. Use to free memory when the agent is done
        with a long-running session; the pool also LRU-evicts automatically at its capacity.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("session_id", stringProp("Session id to close."))
                put("reason", stringProp("Optional reason recorded in logs."))
            },
            required = listOf("session_id"),
        )
    },
    // Closing its own session is not a security action — auto-approvable to
    // avoid training users to click "Approve" reflexively on a confirmation
    // that doesn't carry real risk. Reverses the M1.4 review over-correction.
    execute = { input ->
        deps.track("wm_tab_close", "WebMount 关闭会话", input) {
            val sessionId = input.requiredString("session_id")
            val reason = input.string("reason") ?: "agent requested"
            deps.withAgentSession(input, sessionId) { lease ->
                val actionId = UUID.randomUUID().toString()
                val closed = deps.owner.closeIfLeaseActive(
                    leaseId = lease.leaseId,
                    conversationId = input.webMountConversationId(),
                    runId = input.webMountRunId(),
                    reason = reason,
                )
                if (!closed) {
                    return@withAgentSession listOf(UIMessagePart.Text(buildJsonObject {
                        put("session_id", sessionId)
                        put("action_id", actionId)
                        put("dispatched", false)
                        put("status", "failed")
                        put("snapshot_id_before", null as String?)
                        put("snapshot_id_after", null as String?)
                        put("page_changed", false)
                        put("goal_verified", false)
                        put("error", "run_mismatch")
                        put("auto_retry_after_event", false)
                    }.toString()))
                }
                val payload = buildJsonObject {
                    put("session_id", sessionId)
                    put("action_id", actionId)
                    put("dispatched", true)
                    put("status", "verified")
                    put("snapshot_id_before", null as String?)
                    put("snapshot_id_after", null as String?)
                    put("page_changed", false)
                    put("goal_verified", true)
                    put("closed", true)
                    put("reason", reason)
                    put("requires_human", false)
                    put("resume_condition", "continue")
                }
                listOf(UIMessagePart.Text(payload.toString()))
            }
        }
    },
)
