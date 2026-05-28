package app.amber.feature.webmount.tools

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import app.amber.core.agent.utils.requiredString
import app.amber.core.agent.utils.string

internal fun createTabListTool(deps: WebMountDeps): Tool = Tool(
    name = "wm_tab_list",
    description = """
        List every live WebMount session in the pool with {session_id, url, title, status}.
        Use this before wm_tab_close to see which sessions are open and what they hold.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(properties = buildJsonObject {})
    },
    execute = { _ ->
        deps.track("wm_tab_list", "WebMount 列出会话", buildJsonObject {}) {
            val sessions = deps.pool.listSessions().map { handle ->
                val ls = handle.loadState.value
                buildJsonObject {
                    put("session_id", handle.sessionId)
                    put("url", ls.currentUrl ?: ls.requestedUrl)
                    put("title", ls.title)
                    put("status", ls.status.wireName)
                    put("destroyed", handle.destroyed)
                }
            }
            val payload = buildJsonObject {
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
    execute = { _ ->
        deps.track("wm_tab_new", "WebMount 新建会话", buildJsonObject {}) {
            val handle = deps.pool.acquireNew()
            val payload = buildJsonObject {
                put("session_id", handle.sessionId)
                put("status", handle.loadState.value.status.wireName)
            }
            listOf(UIMessagePart.Text(payload.toString()))
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
            deps.pool.release(sessionId, reason)
            val payload = buildJsonObject {
                put("session_id", sessionId)
                put("closed", true)
                put("reason", reason)
            }
            listOf(UIMessagePart.Text(payload.toString()))
        }
    },
)
