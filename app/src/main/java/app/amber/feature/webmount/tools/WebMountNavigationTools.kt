package app.amber.feature.webmount.tools

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import app.amber.ai.core.InputSchema
import app.amber.ai.core.Tool
import app.amber.ai.ui.UIMessagePart
import app.amber.core.agent.utils.boolean
import app.amber.core.agent.utils.long
import app.amber.core.agent.utils.requiredString
import app.amber.core.agent.utils.string
import app.amber.feature.webmount.core.WebMountManager
import app.amber.feature.webmount.cookie.WebMountCookieProvider
import app.amber.feature.webmount.primitives.SessionHandle
import app.amber.feature.webmount.profile.ProfileRegistry
import app.amber.feature.webmount.profile.SiteProfileEntry

internal fun createOpenTool(
    deps: WebMountDeps,
    profileRegistry: ProfileRegistry,
    cookieProvider: WebMountCookieProvider,
    manager: WebMountManager,
): Tool = Tool(
    name = "wm_open",
    description = """
        Open a URL in a pooled headless WebView. Re-uses an existing session if `session_id` is provided
        and still alive, otherwise allocates a new one. Returns the session id, the load status, the
        current URL, and the latest title. Use `wait="semantic_idle"` (default) to wait for
        a semantic page settle after onPageFinished; `wait="load"` only waits for onPageFinished;
        `wait="none"` returns immediately after issuing the navigation. The headless WebView reuses the
        app-wide cookie jar, so any sites the user has logged into through other in-app WebViews are
        already authenticated here.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("url", stringProp("Absolute http(s) URL to navigate to."))
                put("session_id", stringProp("Reuse an existing session. Omit to allocate a new one."))
                put("wait", stringProp("'semantic_idle' (default) | 'load' | 'dom_stable' | 'network_idle' | 'none'."))
                put("timeout_ms", integerProp("Load timeout in ms. Default 30000, clamped to [1000, 60000]."))
            },
            required = listOf("url"),
        )
    },
    execute = { input ->
        deps.track("wm_open", "WebMount 打开", input.safeUrlPreview()) {
            val url = input.requiredString("url")
            require(url.startsWith("http://") || url.startsWith("https://")) {
                "wm_open only supports http(s) URLs"
            }
            val wait = input.string("wait")?.lowercase() ?: "semantic_idle"
            require(wait in setOf("semantic_idle", "dom_stable", "network_idle", "load", "none")) {
                "wait must be one of: semantic_idle, dom_stable, network_idle, load, none"
            }
            val timeoutMs = (input.long("timeout_ms") ?: SessionHandle.DEFAULT_LOAD_TIMEOUT_MS)
                .coerceIn(1_000L, 60_000L)
            val sessionId = input.string("session_id")
            val handle = if (sessionId != null) deps.pool.acquire(sessionId) else deps.pool.acquireNew()
            val payload: JsonObject = if (wait == "none") {
                // Issue load but don't wait. Still routes through SessionHandle
                // so _loadState is flipped to LOADING synchronously — without
                // this, a follow-up wm_state would briefly see stale "ready"
                // state from the prior navigation.
                val state = handle.loadUrlNoWait(url)
                // M2.1 review W-1: wait=none can't see the post-redirect URL
                // yet; best-effort attach using the requested URL with a flag.
                val profile = applicableProfileJson(profileRegistry, cookieProvider, manager, state.currentUrl ?: url)
                buildJsonObject {
                    put("session_id", handle.sessionId)
                    put("status", state.status.wireName)
                    put("url", redactWebMountUrl(state.currentUrl ?: url).orEmpty())
                    put("requested_url", redactWebMountUrl(url).orEmpty())
                    put("waited", false)
                    profile?.let { put("applicable_profile", it) }
                }
            } else {
                val state = handle.loadUrl(url, timeoutMs)
                val semanticWait = if (wait in setOf("semantic_idle", "dom_stable", "network_idle")) {
                    runCatching {
                        handle.callBridge(
                            "wait",
                            buildJsonObject {
                                put("until", wait)
                                put("timeout_ms", (timeoutMs / 2).coerceIn(700L, 8_000L))
                            },
                            timeoutMs = (timeoutMs / 2).coerceIn(700L, 8_000L) + 1_500L,
                        )
                    }.getOrNull()
                } else {
                    null
                }
                // M2.1 review W-1 fix: prefer committed URL so redirect chains
                // (http→https, root→www) get the *actual* origin's profile,
                // not a stale match against the requested URL.
                val profile = applicableProfileJson(profileRegistry, cookieProvider, manager, state.currentUrl ?: url)
                buildJsonObject {
                    put("session_id", handle.sessionId)
                    put("status", state.status.wireName)
                    put("url", redactWebMountUrl(state.currentUrl ?: url).orEmpty())
                    put("title", state.title)
                    put("requested_url", redactWebMountUrl(url).orEmpty())
                    put("load_progress", state.progress)
                    put("error", state.error)
                    put("waited", true)
                    put("wait_mode", wait)
                    put("network_coverage", handle.bridgeInjectionCoverage)
                    semanticWait?.let { put("semantic_wait", it) }
                    profile?.let { put("applicable_profile", it) }
                }
            }
            listOf(UIMessagePart.Text(payload.toString()))
        }
    },
)

internal fun createStateTool(
    deps: WebMountDeps,
    profileRegistry: ProfileRegistry,
    cookieProvider: WebMountCookieProvider,
    manager: WebMountManager,
): Tool = Tool(
    name = "wm_state",
    description = """
        Snapshot the live state of a WebMount session: URL, title, document.readyState, viewport,
        scroll position, console message tail, and (since M1.4.4) the per-session network event log.
        Pass `network_since` = the highest `seq` you've already seen to receive only newer entries;
        use this to poll for XHR/fetch traffic after wm_click or page navigation. Useful for adapter
        authors mapping page actions to backend endpoints.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("session_id", stringProp("Session id returned by wm_open."))
                put("include_console", booleanProp("Include recent console messages (default true)."))
                put("console_tail", integerProp("How many console entries to include. Default 16, max 64."))
                put("network_since", integerProp("Return network events with seq > this. Default 0 = include everything in the ring."))
                put("network_max", integerProp("Cap on network events returned. Default 50, hard cap 200."))
            },
            required = listOf("session_id"),
        )
    },
    execute = { input ->
        deps.track("wm_state", "WebMount 状态", input) {
            val sessionId = input.requiredString("session_id")
            val handle = deps.pool.peek(sessionId)
                ?: error("session not found: $sessionId")
            val includeConsole = input.boolean("include_console") ?: true
            val consoleTail = (input.long("console_tail") ?: 16L).coerceIn(0L, 64L).toInt()
            val networkSince = (input.long("network_since") ?: 0L).coerceAtLeast(0L)
            val networkMax = (input.long("network_max") ?: 50L).coerceIn(0L, 200L).toInt()
            val args = buildJsonObject {
                put("include_console", includeConsole)
                put("console_tail", consoleTail)
            }
            val bridgePayload: JsonElement = runCatching { handle.callBridge("state", args) }
                .getOrElse { error ->
                    // Bridge failure is recoverable — surface partial state from Kotlin side.
                    val ls = handle.loadState.value
                    return@getOrElse buildJsonObject {
                        put("url", redactWebMountUrl(ls.currentUrl))
                        put("title", ls.title)
                        put("ready_state", "unknown")
                        put("bridge_error", error.message ?: error.toString())
                    }
                }
            val ls = handle.loadState.value
            val networkSnap = handle.networkLog.snapshot(networkSince, networkMax)
            val currentUrl = ls.currentUrl
            val profile = if (currentUrl != null) applicableProfileJson(profileRegistry, cookieProvider, manager, currentUrl) else null
            val merged = buildJsonObject {
                put("session_id", sessionId)
                put("status", ls.status.wireName)
                put("load_progress", ls.progress)
                put("requested_url", redactWebMountUrl(ls.requestedUrl))
                put("committed_url", redactWebMountUrl(ls.committedUrl))
                put("error", ls.error)
                put("updated_at_ms", ls.updatedAtMs)
                put("page", bridgePayload)
                put("network", networkSnap)
                put("network_total_events", handle.networkLog.totalEvents)
                profile?.let { put("applicable_profile", it) }
            }
            listOf(UIMessagePart.Text(merged.toString()))
        }
    },
)

internal fun createExtractTool(deps: WebMountDeps): Tool = Tool(
    name = "wm_extract",
    description = """
        Extract structured information from the current page of a WebMount session. Four modes:
        `readable` (default) returns innerText + outbound links (compatible with the legacy webview_read
        payload); `interactive` returns only clickable elements (a, button, input, [role=button|link|tab|menuitem]);
        `snapshot` returns a flattened tree of semantically interesting nodes with role / accessible name / rect /
        visibility. interactive/snapshot nodes include stable `ref`, `css`, and `fingerprint`; prefer passing
        those refs to wm_click / wm_type / wm_get instead of inventing selectors. `html` returns the outerHTML
        of one selector. All modes cap output size to keep the agent's context manageable.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("session_id", stringProp("Session id returned by wm_open."))
                put("mode", stringProp("'readable' (default) | 'interactive' | 'snapshot' | 'html'."))
                put("max_chars", integerProp("readable/html: max characters to return."))
                put("max_links", integerProp("readable: max links to return (default 20)."))
                put("max_nodes", integerProp("interactive/snapshot: max nodes to return."))
                put("visible_only", booleanProp("interactive/snapshot: skip hidden nodes (default true)."))
                put("root_selector", stringProp("snapshot: limit the walk to a subtree. Default body."))
                put("selector", stringProp("html: which element to serialize."))
            },
            required = listOf("session_id"),
        )
    },
    execute = { input ->
        deps.track("wm_extract", "WebMount 提取", input) {
            val sessionId = input.requiredString("session_id")
            val handle = deps.pool.peek(sessionId) ?: error("session not found: $sessionId")
            val args = buildJsonObject {
                input.string("mode")?.let { put("mode", it) }
                input.long("max_chars")?.let { put("max_chars", it) }
                input.long("max_links")?.let { put("max_links", it) }
                input.long("max_nodes")?.let { put("max_nodes", it) }
                input.boolean("visible_only")?.let { put("visible_only", it) }
                input.string("root_selector")?.let { put("root_selector", it) }
                input.string("selector")?.let { put("selector", it) }
            }
            val payload = handle.callBridge("extract", args, timeoutMs = 15_000L)
            val merged = buildJsonObject {
                put("session_id", sessionId)
                put("result", payload)
            }
            listOf(UIMessagePart.Text(merged.toString()))
        }
    },
)

internal fun createGetTool(deps: WebMountDeps): Tool = Tool(
    name = "wm_get",
    description = """
        Read one element from a WebMount session without running arbitrary JavaScript. Prefer this
        after wm_extract: pass the returned node `ref` as `target`, then choose kind=`text`, `value`,
        `attr`, or `html`. The old selector grammar is still accepted as a fallback, but refs are
        more stable across small DOM changes.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("session_id", stringProp("Session id returned by wm_open."))
                put("target", stringProp("Node ref returned by wm_extract, or a selector fallback."))
                put("selector", stringProp("Legacy CSS / text=... / xpath=... selector fallback."))
                put("kind", stringProp("'text' (default) | 'value' | 'attr' | 'html'."))
                put("attr_name", stringProp("Attribute name when kind='attr', e.g. href or aria-label."))
                put("max_chars", integerProp("Maximum characters to return. Default 20000, hard cap 100000."))
                put("visible_only", booleanProp("Require the target to be visible (default true)."))
            },
            required = listOf("session_id"),
        )
    },
    execute = { input ->
        deps.track("wm_get", "WebMount 读取节点", input) {
            val sessionId = input.requiredString("session_id")
            val handle = deps.pool.peek(sessionId) ?: error("session not found: $sessionId")
            val args = buildJsonObject {
                input.string("target")?.let { put("target", it) }
                input.string("selector")?.let { put("selector", it) }
                input.string("kind")?.let { put("kind", it) }
                input.string("attr_name")?.let { put("attr_name", it) }
                input.long("max_chars")?.let { put("max_chars", it) }
                input.boolean("visible_only")?.let { put("visible_only", it) }
            }
            val payload = handle.callBridge("get", args, timeoutMs = 5_000L)
            listOf(UIMessagePart.Text(buildJsonObject {
                put("session_id", sessionId)
                put("result", payload)
            }.toString()))
        }
    },
)

internal fun createBackTool(deps: WebMountDeps): Tool = Tool(
    name = "wm_back",
    description = "Step the WebMount session's history one page backwards (window.history.back).",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("session_id", stringProp("Session id."))
            },
            required = listOf("session_id"),
        )
    },
    execute = { input ->
        deps.track("wm_back", "WebMount 后退", input) {
            val sessionId = input.requiredString("session_id")
            val handle = deps.pool.peek(sessionId) ?: error("session not found: $sessionId")
            val payload = handle.callBridge("back", buildJsonObject {}, timeoutMs = 3_000L)
            listOf(UIMessagePart.Text(buildJsonObject {
                put("session_id", sessionId)
                put("result", payload)
            }.toString()))
        }
    },
)

internal fun createForwardTool(deps: WebMountDeps): Tool = Tool(
    name = "wm_forward",
    description = "Step the WebMount session's history one page forwards (window.history.forward).",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("session_id", stringProp("Session id."))
            },
            required = listOf("session_id"),
        )
    },
    execute = { input ->
        deps.track("wm_forward", "WebMount 前进", input) {
            val sessionId = input.requiredString("session_id")
            val handle = deps.pool.peek(sessionId) ?: error("session not found: $sessionId")
            val payload = handle.callBridge("forward", buildJsonObject {}, timeoutMs = 3_000L)
            listOf(UIMessagePart.Text(buildJsonObject {
                put("session_id", sessionId)
                put("result", payload)
            }.toString()))
        }
    },
)

/**
 * Build a `applicable_profile` JSON object for the given URL, or null if
 * no profile matches. Encapsulates the lookup + login-cookie probe so
 * `wm_open` / `wm_state` outputs stay consistent. Shape is intentionally
 * conservative — only [SiteProfile] data the agent might reason over.
 *
 * Phase 2 M2.3.1: surfaces `needs_login` and `login_helper` (deep-link
 * intent + visible login URL) when the profile declares a `login_cookie`
 * but the cookie is missing. Agent UI renders the helper intent as a
 * one-tap "Sign in" button.
 */
private fun applicableProfileJson(
    profileRegistry: ProfileRegistry,
    cookieProvider: WebMountCookieProvider,
    manager: WebMountManager,
    url: String,
): JsonObject? {
    val entry: SiteProfileEntry = profileRegistry.forUrl(url) ?: return null
    val profile = entry.profile
    val loginCookie = profile.hints.loginCookie
    val loggedIn = if (loginCookie != null) {
        val bundle = cookieProvider.getCookies(endpoints = emptyList(), extraUrls = profile.origins)
        bundle.value(loginCookie) != null
    } else null
    val station = manager.adapterOf(profile.id)
    val loginUrl = station?.primaryLoginUrl()
    val needsLogin = loginCookie != null && loggedIn == false
    return buildJsonObject {
        put("id", profile.id)
        put("name", profile.name)
        put("trust", entry.trust.name.lowercase())
        put("capabilities", buildJsonArray {
            profile.effectiveCapabilities().forEach { add(JsonPrimitive(it)) }
        })
        loginCookie?.let { put("login_cookie_hint", it) }
        loggedIn?.let { put("logged_in", it) }
        if (needsLogin) {
            put("needs_login", true)
            put("login_helper", buildJsonObject {
                put("station_id", profile.id)
                put("intent", "amberagent://webmount/login?station=${profile.id}")
                loginUrl?.let { put("login_url", it) }
                put("label", profile.name)
            })
        }
        if (profile.hints.interactiveSelectors.isNotEmpty()) {
            put("interactive_selectors", buildJsonObject {
                profile.hints.interactiveSelectors.forEach { (k, v) -> put(k, v) }
            })
        }
        profile.hints.rateLimit?.let { rl ->
            put("rate_limit_hint", buildJsonObject {
                rl.httpStatus?.let { put("http_status", it) }
                rl.bodyPattern?.let { put("body_pattern", it) }
                put("map_to", rl.mapTo)
            })
        }
    }
}

private fun JsonElement.safeUrlPreview(): JsonObject =
    buildJsonObject {
        put("url", redactWebMountUrl(string("url")).orEmpty())
        put("session_id", string("session_id"))
        put("wait", string("wait"))
    }
