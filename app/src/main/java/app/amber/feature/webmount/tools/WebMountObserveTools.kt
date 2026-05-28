package app.amber.feature.webmount.tools

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import app.amber.feature.tools.long
import app.amber.feature.tools.requiredString

internal fun createObserveTool(deps: WebMountDeps): Tool = Tool(
    name = "wm_observe",
    description = """
        Efficient all-in-one WebMount page observation. Returns semantic page state, readable text,
        visible interactive nodes, visual candidates, and redacted network request templates. Prefer
        this over separate wm_state + wm_extract + wm_visual_snapshot calls when orienting on a page.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("session_id", stringProp("Session id returned by wm_open."))
                put("max_text_chars", integerProp("Readable text budget. Default 12000, cap 60000."))
                put("max_nodes", integerProp("Interactive node budget. Default 80, cap 300."))
                put("network_max", integerProp("Network template budget. Default 50, cap 200."))
                put("max_visual_candidates", integerProp("Visual candidate budget. Default 30, cap 120."))
            },
            required = listOf("session_id"),
        )
    },
    execute = { input ->
        deps.track("wm_observe", "WebMount 观察", input) {
            val sessionId = input.requiredString("session_id")
            val handle = deps.pool.peek(sessionId) ?: error("session not found: $sessionId")
            val state = handle.callBridge("semantic_state", buildJsonObject {}, timeoutMs = 3_000L)
            val cachedCandidate = WebMountPageSnapshotCache.get(sessionId, "observe", state)
            val restoredRefs = cachedCandidate?.let { observed ->
                runCatching {
                    handle.callBridge(
                        "restore_snapshot_refs",
                        observed as? JsonObject ?: buildJsonObject {},
                        timeoutMs = 3_000L,
                    )
                }.getOrNull()
            }
            val cached = cachedCandidate?.takeIf { restoredRefs?.intField("missing") == 0 }
            val observed = cached ?: run {
                val args = buildJsonObject {
                    put("max_text_chars", (input.long("max_text_chars") ?: 12_000L).coerceIn(1_000L, 60_000L))
                    put("max_nodes", (input.long("max_nodes") ?: 80L).coerceIn(1L, 300L))
                    put("max_visual_candidates", (input.long("max_visual_candidates") ?: 30L).coerceIn(0L, 120L))
                }
                handle.callBridge("observe", args, timeoutMs = 12_000L).also {
                    WebMountPageSnapshotCache.put(sessionId, "observe", state, it)
                }
            }
            val currentUrl = handle.loadState.value.currentUrl
            val networkMax = (input.long("network_max") ?: 50L).coerceIn(0L, 200L).toInt()
            val payload = buildJsonObject {
                put("session_id", sessionId)
                put("cached", cached != null)
                restoredRefs?.let { put("ref_restore", it) }
                put("network_coverage", handle.bridgeInjectionCoverage)
                put("observation", observed)
                put("network", handle.networkLog.inspect(currentUrl, networkMax))
            }
            listOf(UIMessagePart.Text(payload.toString()))
        }
    },
)

private fun JsonElement?.intField(name: String): Int? =
    runCatching { (this?.jsonObject?.get(name) as? JsonPrimitive)?.intOrNull }.getOrNull()
