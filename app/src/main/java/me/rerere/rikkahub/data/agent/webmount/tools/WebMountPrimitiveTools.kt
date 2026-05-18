package me.rerere.rikkahub.data.agent.webmount.tools

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.agent.AgentToolActivityStore
import me.rerere.rikkahub.data.agent.tools.boolean
import me.rerere.rikkahub.data.agent.tools.long
import me.rerere.rikkahub.data.agent.tools.requiredString
import me.rerere.rikkahub.data.agent.tools.string
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import me.rerere.rikkahub.data.agent.webmount.core.WebMountManager
import me.rerere.rikkahub.data.agent.webmount.cookie.WebMountCookieProvider
import me.rerere.rikkahub.data.agent.webmount.oauth.WebMountOAuthTokenStore
import me.rerere.rikkahub.data.agent.webmount.primitives.SessionHandle
import me.rerere.rikkahub.data.agent.webmount.primitives.WebViewPool
import me.rerere.rikkahub.data.agent.webmount.primitives.WebViewScreenshot
import me.rerere.rikkahub.data.agent.webmount.profile.ProfileBridge
import me.rerere.rikkahub.data.agent.webmount.profile.ProfileRegistry
import me.rerere.rikkahub.data.agent.webmount.profile.SiteProfileEntry
import me.rerere.rikkahub.data.agent.webmount.usersites.AuthKind
import me.rerere.rikkahub.data.agent.webmount.usersites.UserSite
import me.rerere.rikkahub.data.agent.webmount.usersites.UserSiteRegistry
import me.rerere.rikkahub.data.agent.webmount.usersites.collectSiteUrls
import me.rerere.rikkahub.data.agent.webmount.usersites.loginCookieCandidatesFor

/**
 * Browser Primitives tool catalog.
 *
 * Each `wm_*` tool is a thin shim over [WebViewPool]: parse the agent's
 * JSON input, call into the pool / session, format the result as JSON text.
 *
 * Current catalog (set by [getTools]):
 *  - Navigation/state: `wm_open`, `wm_state`, `wm_stations`,
 *    `wm_back`, `wm_forward`, `wm_scroll`
 *  - Reading: `wm_extract`, `wm_wait`, `wm_find`, `wm_screenshot`
 *  - Interaction: `wm_click`, `wm_type`, `wm_keys`, `wm_select`
 *  - Tabs: `wm_tab_list`, `wm_tab_new`, `wm_tab_close`
 *  - Escape hatch (separate toggle): `wm_eval`
 *
 * **Default OFF**: the LocalTools aggregator only includes these when the
 * user enables `LocalToolOption.WebMount` per assistant. `wm_eval` requires
 * an additional `LocalToolOption.WebMountEval` opt-in (Phase 2 M2.0.2)
 * and is flagged `Tool.mandatoryApproval = true` (M2.0.1) so ordinary
 * auto-approval and run-trust cannot bypass the prompt. The separate
 * high-risk auto-approval setting remains the user's explicit unattended
 * execution override.
 */
class WebMountPrimitiveTools(
    private val pool: WebViewPool,
    private val activityStore: AgentToolActivityStore,
    private val manager: WebMountManager,
    private val profileRegistry: ProfileRegistry,
    private val cookieProvider: WebMountCookieProvider,
    private val profileBridge: ProfileBridge,
    private val userSiteRegistry: UserSiteRegistry,
    private val oauthStore: WebMountOAuthTokenStore,
) {
    private val deps = WebMountDeps(pool, activityStore)

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
    private fun applicableProfileJson(url: String): JsonObject? {
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

    /**
     * Phase 2 M2.0.2: `wm_eval` is now gated by a separate
     * [me.rerere.rikkahub.data.ai.tools.LocalToolOption.WebMountEval] toggle
     * because it can execute arbitrary JS in a logged-in WebView — a strictly
     * stronger capability than the rest of the primitives. Pass
     * `includeEval = true` only when that secondary toggle is on.
     */
    fun getTools(includeEval: Boolean = false): List<Tool> = listOfNotNull(
        openTool,
        stateTool,
        extractTool,
        getTool,
        waitTool,
        clickTool,
        typeTool,
        if (includeEval) evalTool else null,
        scrollTool,
        backTool,
        forwardTool,
        keysTool,
        selectTool,
        findTool,
        tabListTool,
        tabNewTool,
        tabCloseTool,
        screenshotTool,
        stationsTool,
        signedFetchTool,
        siteAddTool,
        siteRemoveTool,
        profileSynthesizeTool,
    )

    // -------------------------------------------------------------- wm_open

    private val openTool = Tool(
        name = "wm_open",
        description = """
            Open a URL in a pooled headless WebView. Re-uses an existing session if `session_id` is provided
            and still alive, otherwise allocates a new one. Returns the session id, the load status, the
            current URL, and the latest title. Use `wait="load"` (default) to block until onPageFinished;
            `wait="none"` returns immediately after issuing the navigation. The headless WebView reuses the
            app-wide cookie jar, so any sites the user has logged into through other in-app WebViews are
            already authenticated here.
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("url", stringProp("Absolute http(s) URL to navigate to."))
                    put("session_id", stringProp("Reuse an existing session. Omit to allocate a new one."))
                    put("wait", stringProp("'load' (default) waits for onPageFinished; 'none' returns immediately."))
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
                val wait = input.string("wait")?.lowercase() ?: "load"
                require(wait in setOf("load", "none")) { "wait must be one of: load, none" }
                val timeoutMs = (input.long("timeout_ms") ?: SessionHandle.DEFAULT_LOAD_TIMEOUT_MS)
                    .coerceIn(1_000L, 60_000L)
                val sessionId = input.string("session_id")
                val handle = if (sessionId != null) pool.acquire(sessionId) else pool.acquireNew()
                val payload: JsonObject = if (wait == "none") {
                    // Issue load but don't wait. Still routes through SessionHandle
                    // so _loadState is flipped to LOADING synchronously — without
                    // this, a follow-up wm_state would briefly see stale "ready"
                    // state from the prior navigation.
                    val state = handle.loadUrlNoWait(url)
                    // M2.1 review W-1: wait=none can't see the post-redirect URL
                    // yet; best-effort attach using the requested URL with a flag.
                    val profile = applicableProfileJson(state.currentUrl ?: url)
                    buildJsonObject {
                        put("session_id", handle.sessionId)
                        put("status", state.status.wireName)
                        put("url", state.currentUrl ?: url)
                        put("requested_url", url)
                        put("waited", false)
                        profile?.let { put("applicable_profile", it) }
                    }
                } else {
                    val state = handle.loadUrl(url, timeoutMs)
                    // M2.1 review W-1 fix: prefer committed URL so redirect chains
                    // (http→https, root→www) get the *actual* origin's profile,
                    // not a stale match against the requested URL.
                    val profile = applicableProfileJson(state.currentUrl ?: url)
                    buildJsonObject {
                        put("session_id", handle.sessionId)
                        put("status", state.status.wireName)
                        put("url", state.currentUrl ?: url)
                        put("title", state.title)
                        put("requested_url", url)
                        put("load_progress", state.progress)
                        put("error", state.error)
                        put("waited", true)
                        profile?.let { put("applicable_profile", it) }
                    }
                }
                listOf(UIMessagePart.Text(payload.toString()))
            }
        },
    )

    // ------------------------------------------------------------- wm_state

    private val stateTool = Tool(
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
                val handle = pool.peek(sessionId)
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
                            put("url", ls.currentUrl)
                            put("title", ls.title)
                            put("ready_state", "unknown")
                            put("bridge_error", error.message ?: error.toString())
                        }
                    }
                val ls = handle.loadState.value
                val networkSnap = handle.networkLog.snapshot(networkSince, networkMax)
                val currentUrl = ls.currentUrl
                val profile = if (currentUrl != null) applicableProfileJson(currentUrl) else null
                val merged = buildJsonObject {
                    put("session_id", sessionId)
                    put("status", ls.status.wireName)
                    put("load_progress", ls.progress)
                    put("requested_url", ls.requestedUrl)
                    put("committed_url", ls.committedUrl)
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

    // ------------------------------------------------------------ wm_extract

    private val extractTool = Tool(
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
                val handle = pool.peek(sessionId) ?: error("session not found: $sessionId")
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

    // --------------------------------------------------------------- wm_get

    private val getTool = Tool(
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
                val handle = pool.peek(sessionId) ?: error("session not found: $sessionId")
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

    // -------------------------------------------------------- wm_signed_fetch

    /**
     * Phase 2 M2.2 — Profile-driven signed fetch.
     *
     * Issue a request to a site's API endpoint with the profile's signing
     * script applied to it (e.g. Bilibili WBI). The fetch runs IN-PAGE via
     * the host shim so the user's cookies are attached and the Referer
     * header is correct.
     *
     * Flow:
     *  1. Look up profile for the current WebView's origin (or use
     *     `profile_id` override if supplied).
     *  2. Verify profile declares `scripts.sign_request` with the
     *     necessary `call_page_fn:<x>` permission.
     *  3. Call ProfileBridge.callSign — origin check, rate limit, shim
     *     injection, evalSilent. Returns the response envelope as JSON.
     */
    private val signedFetchTool = Tool(
        name = "wm_signed_fetch",
        description = """
            Issue a profile-signed fetch from inside a WebMount session. Use this when an API
            endpoint requires per-request signing (e.g. Bilibili WBI w_rid params). The signing
            algorithm is provided by a host-defined shim associated with the applicable profile;
            the fetch runs in-page so the user's cookies are sent automatically. Returns the
            response status/headers/body (text). For GET/HEAD this is read-only; POST/PUT/PATCH/
            DELETE require explicit human approval per call.
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("session_id", stringProp("Session id returned by wm_open."))
                    put("url", stringProp("Absolute http(s) URL to fetch (must be in the profile's origins)."))
                    put("method", stringProp("HTTP method, default GET. POST/PUT/PATCH/DELETE need approval."))
                    put("body", stringProp("Request body (string or JSON). Ignored for GET/HEAD."))
                    put("extra_params", buildJsonObject {
                        put("type", "object")
                        put("description", "Extra query params merged into the URL before signing.")
                    })
                    put("profile_id", stringProp("Override profile lookup (default: derive from session's current origin)."))
                    put("sign_script", stringProp("Which entry in profile.scripts to call. Default 'sign_request'."))
                    put("timeout_ms", integerProp("Sign+fetch timeout. Default 15000, clamped to [1000, 60000]."))
                },
                required = listOf("session_id", "url"),
            )
        },
        execute = { input ->
            deps.track("wm_signed_fetch", "WebMount 签名请求", input) {
                val sessionId = input.requiredString("session_id")
                val url = input.requiredString("url")
                require(url.startsWith("http://") || url.startsWith("https://")) {
                    "wm_signed_fetch only supports http(s) URLs"
                }
                val method = (input.string("method") ?: "GET").uppercase()
                val body = input.string("body")
                val extraParams = (input.jsonObject)["extra_params"] as? JsonObject
                val timeout = (input.long("timeout_ms") ?: 15_000L).coerceIn(1_000L, 60_000L)
                val scriptKey = input.string("sign_script") ?: "sign_request"
                val profileOverride = input.string("profile_id")

                val handle = pool.peek(sessionId) ?: error("session not found: $sessionId")
                val currentUrl = handle.loadState.value.currentUrl ?: url
                val currentOrigin = ProfileRegistry.extractOrigin(currentUrl)
                    ?: error("session has no committed URL yet — call wm_open first")
                val entry = profileOverride?.let { profileRegistry.byId(it) }
                    ?: profileRegistry.forUrl(currentUrl)
                    ?: error("no profile applies to $currentUrl (or override $profileOverride)")

                val args = listOf<JsonElement>(
                    JsonPrimitive(url),
                    JsonPrimitive(method),
                    body?.let { JsonPrimitive(it) } ?: JsonNull,
                    extraParams ?: buildJsonObject {},
                )
                // Holistic review B-2 fix: pass the outbound URL's host so
                // ProfileBridge can enforce send_signed:<host> + origin.
                val requestedHost = ProfileRegistry.extractOrigin(url)
                val result = profileBridge.callSign(
                    handle = handle,
                    entry = entry,
                    currentOrigin = currentOrigin,
                    scriptKey = scriptKey,
                    args = args,
                    timeoutMs = timeout,
                    requestedUrlHost = requestedHost,
                )
                val response = when (result) {
                    is ProfileBridge.SignResult.Success -> buildJsonObject {
                        put("ok", true)
                        put("session_id", sessionId)
                        put("profile_id", entry.profile.id)
                        put("response", result.value)
                    }
                    is ProfileBridge.SignResult.Error -> buildJsonObject {
                        put("ok", false)
                        put("session_id", sessionId)
                        put("profile_id", entry.profile.id)
                        put("error", result.message)
                    }
                    is ProfileBridge.SignResult.RateLimited -> buildJsonObject {
                        put("ok", false)
                        put("session_id", sessionId)
                        put("profile_id", entry.profile.id)
                        put("error", result.message)
                        put("rate_limited", true)
                    }
                }
                listOf(UIMessagePart.Text(response.toString()))
            }
        },
    )

    // --------------------------------------------------------------- wm_wait

    private val waitTool = Tool(
        name = "wm_wait",
        description = """
            Block until a condition holds on the current page, or a timeout elapses. Two kinds:
            `selector` (default) waits for at least one element matching the given selector to appear
            (and be visible unless visible_only=false); `ready_state` waits until document.readyState
            reaches the requested state ('interactive' or 'complete'). Useful after wm_open wait="none"
            or after clicking a link that triggers an SPA navigation.
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("session_id", stringProp("Session id returned by wm_open."))
                    put("until", stringProp("'selector' (default) | 'ready_state'."))
                    put("value", stringProp("selector: CSS or 'text=...'/'xpath=...'; ready_state: target state."))
                    put("timeout_ms", integerProp("Max wait in ms. Default 10000, clamped to [200, 60000]."))
                    put("visible_only", booleanProp("selector: require visible match (default true)."))
                },
                required = listOf("session_id", "value"),
            )
        },
        execute = { input ->
            deps.track("wm_wait", "WebMount 等待", input) {
                val sessionId = input.requiredString("session_id")
                val handle = pool.peek(sessionId) ?: error("session not found: $sessionId")
                val until = input.string("until") ?: "selector"
                require(until in setOf("selector", "ready_state")) { "until must be 'selector' or 'ready_state'" }
                val value = input.requiredString("value")
                val timeout = (input.long("timeout_ms") ?: 10_000L).coerceIn(200L, 60_000L)
                val args = buildJsonObject {
                    put("until", until)
                    put("value", value)
                    put("timeout_ms", timeout)
                    input.boolean("visible_only")?.let { put("visible_only", it) }
                }
                // Allow the bridge a small grace window beyond its own JS-side timeout
                // so it surfaces the explicit "wait timed out" error rather than our
                // generic bridge-timeout one.
                val payload = handle.callBridge("wait", args, timeoutMs = timeout + 3_000L)
                val merged = buildJsonObject {
                    put("session_id", sessionId)
                    put("ok", true)
                    put("result", payload)
                }
                listOf(UIMessagePart.Text(merged.toString()))
            }
        },
    )

    // ------------------------------------------------------------- wm_click

    private val clickTool = Tool(
        name = "wm_click",
        description = """
            Click an element in a WebMount session. Prefer `target` with a ref returned by wm_extract;
            selector remains available for legacy CSS / text=... / xpath=... calls. The bridge focuses
            the element, dispatches mousedown/mouseup, then calls .click() so default actions fire.
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("session_id", stringProp("Session id returned by wm_open."))
                    put("target", stringProp("Node ref returned by wm_extract, or a selector fallback."))
                    put("selector", stringProp("Legacy CSS / text=... / xpath=... selector for the target element."))
                    put("visible_only", booleanProp("Require the element to be visible (default true)."))
                },
                required = listOf("session_id"),
            )
        },
        needsApproval = true,
        execute = { input ->
            deps.track("wm_click", "WebMount 点击", input) {
                val sessionId = input.requiredString("session_id")
                val target = input.string("target")
                val selector = input.string("selector")
                require(target != null || selector != null) { "wm_click requires target or selector" }
                val handle = pool.peek(sessionId) ?: error("session not found: $sessionId")
                val args = buildJsonObject {
                    target?.let { put("target", it) }
                    selector?.let { put("selector", it) }
                    input.boolean("visible_only")?.let { put("visible_only", it) }
                }
                val payload = handle.callBridge("click", args, timeoutMs = 5_000L)
                val merged = buildJsonObject {
                    put("session_id", sessionId)
                    put("result", payload)
                }
                listOf(UIMessagePart.Text(merged.toString()))
            }
        },
    )

    // -------------------------------------------------------------- wm_type

    private val typeTool = Tool(
        name = "wm_type",
        description = """
            Type text into an editable element (input/textarea/[contenteditable]) in a WebMount session.
            Prefer `target` with a ref returned by wm_extract; selector remains available for legacy calls.
            Fires `input` and `change` events so frontend frameworks observe the new value. Pass
            `press_enter=true` to dispatch a synthetic Enter after typing (useful for search boxes that
            submit on Enter). `clear=true` empties the field first.
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("session_id", stringProp("Session id returned by wm_open."))
                    put("target", stringProp("Node ref returned by wm_extract, or a selector fallback."))
                    put("selector", stringProp("Legacy selector for the editable element."))
                    put("text", stringProp("UTF-8 text to type."))
                    put("clear", booleanProp("Empty the field before typing (default false)."))
                    put("press_enter", booleanProp("Dispatch Enter keydown after typing (default false)."))
                },
                required = listOf("session_id", "text"),
            )
        },
        needsApproval = true,
        execute = { input ->
            deps.track("wm_type", "WebMount 输入", input) {
                val sessionId = input.requiredString("session_id")
                val target = input.string("target")
                val selector = input.string("selector")
                require(target != null || selector != null) { "wm_type requires target or selector" }
                val text = input.requiredString("text")
                val handle = pool.peek(sessionId) ?: error("session not found: $sessionId")
                val args = buildJsonObject {
                    target?.let { put("target", it) }
                    selector?.let { put("selector", it) }
                    put("text", text)
                    input.boolean("clear")?.let { put("clear", it) }
                    input.boolean("press_enter")?.let { put("press_enter", it) }
                }
                val payload = handle.callBridge("type", args, timeoutMs = 5_000L)
                val merged = buildJsonObject {
                    put("session_id", sessionId)
                    put("result", payload)
                }
                listOf(UIMessagePart.Text(merged.toString()))
            }
        },
    )

    // -------------------------------------------------------------- wm_eval

    private val evalTool = Tool(
        name = "wm_eval",
        description = """
            ⚠️ HIGH RISK. Evaluate arbitrary JavaScript in the WebMount session and return the
            result. The script runs INSIDE the page's origin with full DOM access — it can read
            any data the user has on that site (cookies, sessionStorage, localStorage), perform
            same-origin fetches with credentials, and mutate the page. Ordinary auto-approval and
            in-run trust cannot bypass its approval gate; only explicit high-risk auto-approval
            can run it unattended. Prefer the specific primitives (wm_click / wm_type / wm_extract /
            wm_find) when they suffice. The expression's return value is JSON-serialized;
            non-serializable values fall back to String() coercion.
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("session_id", stringProp("Session id returned by wm_open."))
                    put("expression", stringProp("JS expression to evaluate. Wrapped in `return (...)` if it's an expression; statements are also accepted."))
                    put("timeout_ms", integerProp("Max time the JS engine may take. Default 5000, clamped to [200, 30000]."))
                },
                required = listOf("session_id", "expression"),
            )
        },
        needsApproval = true,
        allowsAutoApproval = false,
        mandatoryApproval = true,
        execute = { input ->
            deps.track("wm_eval", "WebMount JS 执行", input.safeEvalPreview()) {
                val sessionId = input.requiredString("session_id")
                val expression = input.requiredString("expression")
                val handle = pool.peek(sessionId) ?: error("session not found: $sessionId")
                val timeout = (input.long("timeout_ms") ?: 5_000L).coerceIn(200L, 30_000L)
                val args = buildJsonObject {
                    put("expression", expression)
                }
                val payload = handle.callBridge("eval", args, timeoutMs = timeout)
                val merged = buildJsonObject {
                    put("session_id", sessionId)
                    put("result", payload)
                }
                listOf(UIMessagePart.Text(merged.toString()))
            }
        },
    )

    // ------------------------------------------------------------ wm_scroll

    private val scrollTool = Tool(
        name = "wm_scroll",
        description = """
            Scroll a WebMount session. Three mutually exclusive modes (in priority order):
            (1) `target`/`selector` scrolls the matched element into view; (2) `to` accepts "top" |
            "bottom", or absolute coordinates via `to_x` + `to_y`; (3) `by_x` + `by_y` scrolls
            relative to the current position. Reports the post-scroll {x, y}.
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("session_id", stringProp("Session id."))
                    put("target", stringProp("Node ref returned by wm_extract, or a selector fallback."))
                    put("selector", stringProp("Legacy selector to scroll into view (CSS / text= / xpath=)."))
                    put("to", stringProp("'top' | 'bottom' for shorthand absolute scrolls."))
                    put("to_x", integerProp("Absolute x (used with to_y)."))
                    put("to_y", integerProp("Absolute y."))
                    put("by_x", integerProp("Relative horizontal delta."))
                    put("by_y", integerProp("Relative vertical delta."))
                },
                required = listOf("session_id"),
            )
        },
        execute = { input ->
            deps.track("wm_scroll", "WebMount 滚动", input) {
                val sessionId = input.requiredString("session_id")
                val handle = pool.peek(sessionId) ?: error("session not found: $sessionId")
                val args = buildJsonObject {
                    val selector = input.string("selector")
                    val target = input.string("target")
                    val to = input.string("to")
                    val toX = input.long("to_x")
                    val toY = input.long("to_y")
                    val byX = input.long("by_x")
                    val byY = input.long("by_y")
                    when {
                        target != null -> put("target", target)
                        selector != null -> put("selector", selector)
                        to != null -> put("to", to)
                        toX != null && toY != null -> put("to", buildJsonObject {
                            put("x", toX)
                            put("y", toY)
                        })
                        byX != null || byY != null ->
                            put("by", buildJsonArray {
                                add(JsonPrimitive(byX ?: 0L))
                                add(JsonPrimitive(byY ?: 0L))
                            })
                        else -> error("wm_scroll requires selector / to / to_x+to_y / by_x+by_y")
                    }
                }
                val payload = handle.callBridge("scroll", args, timeoutMs = 5_000L)
                listOf(UIMessagePart.Text(buildJsonObject {
                    put("session_id", sessionId)
                    put("result", payload)
                }.toString()))
            }
        },
    )

    // ----------------------------------------------- wm_back / wm_forward

    private val backTool = Tool(
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
                val handle = pool.peek(sessionId) ?: error("session not found: $sessionId")
                val payload = handle.callBridge("back", buildJsonObject {}, timeoutMs = 3_000L)
                listOf(UIMessagePart.Text(buildJsonObject {
                    put("session_id", sessionId)
                    put("result", payload)
                }.toString()))
            }
        },
    )

    private val forwardTool = Tool(
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
                val handle = pool.peek(sessionId) ?: error("session not found: $sessionId")
                val payload = handle.callBridge("forward", buildJsonObject {}, timeoutMs = 3_000L)
                listOf(UIMessagePart.Text(buildJsonObject {
                    put("session_id", sessionId)
                    put("result", payload)
                }.toString()))
            }
        },
    )

    // -------------------------------------------------------------- wm_keys

    private val keysTool = Tool(
        name = "wm_keys",
        description = """
            Dispatch a synthetic keyboard event in a WebMount session. Useful for Enter / Escape / Tab /
            arrow keys after wm_type when the field doesn't auto-submit. Modifiers can be combined
            (ctrl, shift, alt, meta). If `target` or `selector` is provided, focus moves to that element first;
            otherwise the event targets the currently-focused element.
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("session_id", stringProp("Session id."))
                    put("key", stringProp("Key name: 'Enter', 'Escape', 'Tab', 'ArrowDown', 'a', etc."))
                    put("target", stringProp("Optional node ref returned by wm_extract, or selector fallback."))
                    put("selector", stringProp("Optional legacy selector to focus before sending the key."))
                    put("ctrl", booleanProp("Hold Ctrl while pressing (default false)."))
                    put("shift", booleanProp("Hold Shift (default false)."))
                    put("alt", booleanProp("Hold Alt (default false)."))
                    put("meta", booleanProp("Hold Meta / Cmd (default false)."))
                },
                required = listOf("session_id", "key"),
            )
        },
        needsApproval = true,
        execute = { input ->
            deps.track("wm_keys", "WebMount 键盘", input) {
                val sessionId = input.requiredString("session_id")
                val key = input.requiredString("key")
                val handle = pool.peek(sessionId) ?: error("session not found: $sessionId")
                val mods = buildJsonObject {
                    input.boolean("ctrl")?.let { put("ctrl", it) }
                    input.boolean("shift")?.let { put("shift", it) }
                    input.boolean("alt")?.let { put("alt", it) }
                    input.boolean("meta")?.let { put("meta", it) }
                }
                val args = buildJsonObject {
                    put("key", key)
                    input.string("target")?.let { put("target", it) }
                    input.string("selector")?.let { put("selector", it) }
                    put("modifiers", mods)
                }
                val payload = handle.callBridge("keys", args, timeoutMs = 5_000L)
                listOf(UIMessagePart.Text(buildJsonObject {
                    put("session_id", sessionId)
                    put("result", payload)
                }.toString()))
            }
        },
    )

    // ------------------------------------------------------------ wm_select

    private val selectTool = Tool(
        name = "wm_select",
        description = """
            Choose an option in a <select> dropdown. Matches `value` against both option.value and the
            visible option text. Prefer `target` with a ref returned by wm_extract; selector remains
            available for legacy calls. Fires input + change events so frontend frameworks observe the new selection.
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("session_id", stringProp("Session id."))
                    put("target", stringProp("Node ref returned by wm_extract, or selector fallback."))
                    put("selector", stringProp("Legacy selector for the <select> element."))
                    put("value", stringProp("Option value or visible text to choose."))
                },
                required = listOf("session_id", "value"),
            )
        },
        needsApproval = true,
        execute = { input ->
            deps.track("wm_select", "WebMount 选择", input) {
                val sessionId = input.requiredString("session_id")
                val target = input.string("target")
                val selector = input.string("selector")
                require(target != null || selector != null) { "wm_select requires target or selector" }
                val value = input.requiredString("value")
                val handle = pool.peek(sessionId) ?: error("session not found: $sessionId")
                val args = buildJsonObject {
                    target?.let { put("target", it) }
                    selector?.let { put("selector", it) }
                    put("value", value)
                }
                val payload = handle.callBridge("select", args, timeoutMs = 5_000L)
                listOf(UIMessagePart.Text(buildJsonObject {
                    put("session_id", sessionId)
                    put("result", payload)
                }.toString()))
            }
        },
    )

    // -------------------------------------------------------------- wm_find

    private val findTool = Tool(
        name = "wm_find",
        description = """
            Search the page text for a substring and return up to N visible matches with their CSS path,
            bounding rect, and a short text preview. Use this to locate elements the agent then clicks
            via wm_click (e.g. find "Login" → click that path). Case-insensitive by default.
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("session_id", stringProp("Session id."))
                    put("text", stringProp("Text substring to search for."))
                    put("case_sensitive", booleanProp("Default false."))
                    put("max", integerProp("Max matches to return. Default 20, cap 100."))
                },
                required = listOf("session_id", "text"),
            )
        },
        execute = { input ->
            deps.track("wm_find", "WebMount 查找", input) {
                val sessionId = input.requiredString("session_id")
                val text = input.requiredString("text")
                val handle = pool.peek(sessionId) ?: error("session not found: $sessionId")
                val args = buildJsonObject {
                    put("text", text)
                    input.boolean("case_sensitive")?.let { put("case_sensitive", it) }
                    input.long("max")?.let { put("max", it) }
                }
                val payload = handle.callBridge("find", args, timeoutMs = 10_000L)
                listOf(UIMessagePart.Text(buildJsonObject {
                    put("session_id", sessionId)
                    put("result", payload)
                }.toString()))
            }
        },
    )

    // ----------------------------------------------------------- wm_tab_*

    private val tabListTool by lazy { createTabListTool(deps) }
    private val tabNewTool by lazy { createTabNewTool(deps) }
    private val tabCloseTool by lazy { createTabCloseTool(deps) }

    // ---------------------------------------------------------- wm_screenshot

    private val screenshotTool by lazy { createScreenshotTool(deps) }

    // -------------------------------------------------------- wm_stations

    /**
     * Phase 2 M2.0.4 — `wm_stations` introspection.
     *
     * Returns a flat snapshot of every WebMount station the user has
     * configured (HN / Reddit / 飞书 / GitHub / Bilibili / 知乎 / 掘金 today;
     * future additions automatic). The agent uses this to decide whether to
     * prefer adapter tools (already authenticated) or fall back to generic
     * `wm_*` primitives.
     *
     * The output schema reserves `applicable_profile` / `has_profile` /
     * `login_indicator` fields — populated by M2.1 (Site Profile mechanism)
     * once that ships. Today they are always null/false so callers can
     * already key on them without breaking when M2.1 lands.
     */
    private val stationsTool = Tool(
        name = "wm_stations",
        description = """
            List every website in the user's WebMount Stations list — both built-in (HN, Reddit,
            GitHub, Bilibili, 掘金, 知乎, 飞书云文档) and any user-added custom sites. For each
            entry: id, display_name, url, auth_kind (anonymous / cookie / oauth), and a
            `login_status` field with three explicit values:
              • "logged_in"  — probed and confirmed signed in
              • "logged_out" — probed and confirmed signed out
              • "unknown"    — the site has no configured login-cookie name, so the registry
                CANNOT determine sign-in state. Do NOT assume the user is signed out — they
                might be perfectly signed in via the WebView. When unknown, attempt the action
                anyway (wm_open + wm_extract) and only report "not signed in" if the page
                itself shows a login wall.
            Built-in sites with a native adapter also include status / capability / message.
            Read-only and side-effect-free.
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("auth_kind_filter", stringProp("Optional filter: 'anonymous' | 'cookie' | 'oauth'."))
                    put("status_filter", stringProp("Optional native-station status filter: read_only / read_write / login_required / degraded / error / not_configured / probing. Only applies to sites with a native adapter."))
                },
                required = emptyList(),
            )
        },
        execute = { input ->
            deps.track("wm_stations", "WebMount 站点列表", input) {
                val authKindFilter = input.string("auth_kind_filter")?.lowercase()?.takeIf { it.isNotBlank() }
                val rawStatusFilter = input.string("status_filter")?.lowercase()?.takeIf { it.isNotBlank() }
                val validStatuses = me.rerere.rikkahub.data.agent.webmount.core.WebMountStatus.entries
                    .map { it.wireName }
                    .toSet()
                val statusFilter = rawStatusFilter?.takeIf { it in validStatuses }
                val unknownStatusFilter = rawStatusFilter != null && statusFilter == null
                // Plan v2: source from the user's site list (single source of truth). Each
                // UserSite is decorated with the native adapter's station state (if any) +
                // a cookie-presence probe.
                val sites = userSiteRegistry.sites.value
                    .asSequence()
                    .filter { site -> authKindFilter == null || site.authKind.name.lowercase() == authKindFilter }
                    .filter { site ->
                        if (statusFilter == null) return@filter true
                        val state = site.nativeAdapterId?.let { manager.states.value[it] }
                        state?.status?.wireName == statusFilter
                    }
                    .toList()
                val payload = buildJsonObject {
                    put("count", sites.size)
                    if (unknownStatusFilter) {
                        put(
                            "warning",
                            "Unknown status_filter '$rawStatusFilter'. Valid values: " +
                                validStatuses.sorted().joinToString(", ") +
                                ". Returning unfiltered results."
                        )
                    }
                    put(
                        "stations",
                        buildJsonArray {
                            sites.forEach { site ->
                                val state = site.nativeAdapterId?.let { manager.states.value[it] }
                                // B-3 fix: profile lookup falls back to site.id
                                // so synthesized profiles (keyed under user_<slug>)
                                // are picked up too. Without the fallback,
                                // wm_profile_synthesize was effectively invisible
                                // to subsequent wm_stations calls.
                                val profileEntry = profileRegistry.byId(site.nativeAdapterId ?: site.id)
                                val loginCookie = site.loginCookieName ?: profileEntry?.profile?.hints?.loginCookie
                                val loginCookieNames = buildList {
                                    loginCookie?.let { add(it) }
                                    addAll(loginCookieCandidatesFor(site))
                                }.distinct()
                                val urls = collectSiteUrls(site, manager, profileRegistry)
                                val loggedIn = if (loginCookieNames.isNotEmpty()) {
                                    val bundle = cookieProvider.getCookies(endpoints = emptyList(), extraUrls = urls)
                                    loginCookieNames.any { bundle.value(it) != null }
                                } else null
                                val adapter = site.nativeAdapterId?.let { manager.adapterOf(it) }
                                val loginUrl = adapter?.primaryLoginUrl() ?: site.homepageUrl
                                // B-4 fix: OAuth sites also report needs_login
                                // when no token is stored, with a login_helper
                                // pointing the agent at the same deep link.
                                val oauthProviderId = site.oauthProviderId ?: site.id
                                val oauthTokenMissing = site.authKind == AuthKind.OAUTH &&
                                    oauthStore.getToken(oauthProviderId) == null
                                // Three-state login signal:
                                //   "logged_in"  — probed and the cookie / token is present
                                //   "logged_out" — probed and absent
                                //   "unknown"    — couldn't probe (no cookie name configured)
                                //
                                // The pre-fix code conflated "unknown" with "logged_out" and
                                // emitted `needs_login: true` whenever the probe came back
                                // null — so a user who added a site manually without filling
                                // the cookie-name field got told "未登录" by the agent even
                                // when they were signed in. Now `needs_login` requires
                                // positive evidence of being logged out.
                                val loginStatus = when (site.authKind) {
                                    AuthKind.COOKIE -> when (loggedIn) {
                                        true -> "logged_in"
                                        false -> "logged_out"
                                        null -> "unknown"
                                    }
                                    AuthKind.OAUTH -> when {
                                        !oauthTokenMissing -> "logged_in"
                                        oauthTokenMissing -> "logged_out"
                                        else -> "unknown"
                                    }
                                    AuthKind.ANONYMOUS -> "logged_in" // public — always usable
                                }
                                val needsLogin = loginStatus == "logged_out"
                                add(buildJsonObject {
                                    put("id", site.id)
                                    put("display_name", site.displayName)
                                    put("url", site.homepageUrl)
                                    put("auth_kind", site.authKind.name.lowercase())
                                    put("user_added", site.nativeAdapterId == null)
                                    site.nativeAdapterId?.let { put("native_adapter_id", it) }
                                    state?.let {
                                        put("status", it.status.wireName)
                                        put("capability", it.capability.wireName)
                                        // W-1 fix: drop the misleading `enabled`
                                        // field. Plan v2 gates by UserSiteRegistry
                                        // membership, not WebMountManager.setEnabled,
                                        // so this old per-station flag no longer
                                        // tracks whether tools are exposed.
                                        it.message?.let { msg -> put("message", msg) }
                                        if (it.updatedAtMillis > 0) put("updated_at_ms", it.updatedAtMillis)
                                    }
                                    profileEntry?.let {
                                        put("has_profile", true)
                                        put("applicable_profile_id", it.profile.id)
                                        put("profile_trust", it.trust.name.lowercase())
                                    }
                                    loggedIn?.let { put("logged_in", it) }
                                    // Explicit three-state — agent should NOT assume the
                                    // user is logged out when login_status == "unknown".
                                    put("login_status", loginStatus)
                                    if (site.authKind == AuthKind.OAUTH) {
                                        put("oauth_token_present", !oauthTokenMissing)
                                    }
                                    if (needsLogin) {
                                        put("needs_login", true)
                                        put("login_helper", buildJsonObject {
                                            put("station_id", site.id)
                                            put("intent", "amberagent://webmount/login?station=${site.id}")
                                            put("login_url", loginUrl)
                                            put("label", site.displayName)
                                            put("auth_kind", site.authKind.name.lowercase())
                                        })
                                    }
                                })
                            }
                        }
                    )
                }
                listOf(UIMessagePart.Text(payload.toString()))
            }
        },
    )

    // ----------------------------------------------------------- wm_site_add

    private val siteAddTool = Tool(
        name = "wm_site_add",
        description = """
            Add a website to the user's WebMount Stations list so the agent (and the user, via
            the settings page) can manage it. The site becomes available immediately — use
            wm_open + wm_extract on it. Setting `needs_login=true` (default) adds a Sign-in
            button on the settings page; the agent can prompt the user to log in there.
            Reversible — the user can delete the site at any time. Idempotent on duplicate id.
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("name", stringProp("Display name shown to the user (e.g. '微博', 'Hacker News')."))
                    put("url", stringProp("Homepage / sign-in URL (http or https)."))
                    put("needs_login", booleanProp("True (default) means the site needs a sign-in cookie. Set false for fully public read-only sites."))
                    put("cookie_name", stringProp("Optional: name of the cookie set after login (e.g. SESSDATA). Lets the settings page show 'Signed in' status. Login still works without it."))
                },
                required = listOf("name", "url"),
            )
        },
        execute = { input ->
            deps.track("wm_site_add", "WebMount 新增网站", input) {
                val name = input.requiredString("name").trim()
                require(name.isNotBlank()) { "name must not be blank" }
                val url = input.requiredString("url").trim()
                require(url.startsWith("http://") || url.startsWith("https://")) {
                    "url must be an http(s) URL"
                }
                val needsLogin = input.boolean("needs_login") ?: true
                val cookieName = input.string("cookie_name")?.takeIf { it.isNotBlank() && needsLogin }
                val id = "user_" + name
                    .lowercase(java.util.Locale.ROOT)
                    .replace(Regex("[^a-z0-9]+"), "_")
                    .trim('_')
                    .ifBlank { "site" }
                    .take(40)
                val site = UserSite(
                    id = id,
                    displayName = name,
                    homepageUrl = url,
                    authKind = if (needsLogin) AuthKind.COOKIE else AuthKind.ANONYMOUS,
                    loginCookieName = cookieName,
                    nativeAdapterId = null,
                    iconKey = null,
                )
                val ok = userSiteRegistry.add(site)
                val payload = buildJsonObject {
                    put("ok", ok)
                    put("site_id", id)
                    put("display_name", name)
                    put("url", url)
                    put("auth_kind", site.authKind.name.lowercase())
                    if (!ok) put("error", "A site with id '$id' already exists. Pick a different name or remove the existing entry first.")
                }
                listOf(UIMessagePart.Text(payload.toString()))
            }
        },
    )

    // -------------------------------------------------------- wm_site_remove

    private val siteRemoveTool = Tool(
        name = "wm_site_remove",
        description = """
            Remove a website from the user's WebMount Stations list. Also clears the site's
            cookies, OAuth credentials + tokens, and any agent-synthesized profile so
            'delete' is honest. Pass the `site_id` from wm_stations. Built-in seed sites
            (hackernews / reddit / github / bilibili / juejin / zhihu / feishu_docs) can be
            re-added later via the settings page's "Restore examples" button — NOT via
            wm_site_add, which always creates a custom `user_<slug>` entry without native
            adapter wiring. Requires explicit human approval per invocation.
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("site_id", stringProp("The site id from wm_stations (e.g. 'bilibili' or 'user_weibo')."))
                },
                required = listOf("site_id"),
            )
        },
        execute = { input ->
            deps.track("wm_site_remove", "WebMount 删除网站", input) {
                val siteId = input.requiredString("site_id")
                val site = userSiteRegistry.byId(siteId)
                if (site == null) {
                    return@track listOf(UIMessagePart.Text(buildJsonObject {
                        put("ok", false)
                        put("site_id", siteId)
                        put("error", "No site with id '$siteId' is registered.")
                    }.toString()))
                }
                val removed = userSiteRegistry.remove(siteId)
                if (!removed) {
                    return@track listOf(UIMessagePart.Text(buildJsonObject {
                        put("ok", false)
                        put("site_id", siteId)
                        put("error", "Failed to remove site '$siteId' (registry rejected the change).")
                    }.toString()))
                }
                // Mirror the settings page's delete behavior so "remove" wipes
                // ALL data tied to this site — cookies + OAuth + profile.
                // B-2 fix: use shared collectSiteUrls so synthesized-profile
                // extra origins are included in the cookie-clear set.
                // B-5 fix: drop the per-authKind guards. Both clearToken and
                // clearCookiesFor are no-ops when nothing exists, so unconditional
                // calls are safe AND prevent leaks when a site's kind flipped.
                val urls = collectSiteUrls(site, manager, profileRegistry)
                val cookiesCleared = cookieProvider.clearCookiesFor(urls)
                val providerId = site.oauthProviderId ?: siteId
                val hadOauthToken = oauthStore.getToken(providerId) != null
                val hadOauthCreds = oauthStore.getCredentials(providerId) != null
                oauthStore.clearToken(providerId)
                oauthStore.clearCredentials(providerId)
                val oauthCleared = hadOauthToken || hadOauthCreds
                // Also drop the synthesized profile, if any, so the agent's
                // "knowledge" of this site doesn't outlive the site itself.
                val profileRemoved = profileRegistry.remove(siteId)
                val payload = buildJsonObject {
                    put("ok", true)
                    put("site_id", siteId)
                    put("display_name", site.displayName)
                    put("cookies_cleared", cookiesCleared)
                    put("oauth_cleared", oauthCleared)
                    put("profile_removed", profileRemoved)
                }
                listOf(UIMessagePart.Text(payload.toString()))
            }
        },
    )

    // --------------------------------------------------------- wm_profile_synthesize

    /**
     * Phase 2 plan-v2 follow-up — let the agent generate a Site Profile for
     * a user-added site so subsequent `wm_open` / `wm_state` calls return the
     * site's hints (login_cookie / selectors / rate-limit shape) inline via
     * the `applicable_profile` field, matching the experience users get for
     * the 12 built-in profiles.
     *
     * Idempotent: re-running with refined hints overwrites the previously
     * synthesized profile (same id namespace). Refuses to shadow built-in
     * profile ids — agent should improve the built-in profiles via PR
     * instead of overriding them at runtime.
     */
    private val profileSynthesizeTool = Tool(
        name = "wm_profile_synthesize",
        description = """
            Build and persist a Site Profile for a user-added site based on what the agent
            has learned about it (login cookie name, content selectors, additional origins).
            The profile is saved under the user-imported namespace and surfaces inline in
            future `wm_open` / `wm_state` outputs as `applicable_profile.hints`, so the
            agent's next pass on this site is one tool call instead of N. Idempotent —
            re-call with refined hints to overwrite. Refuses built-in profile ids.
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("site_id", stringProp("The site id from wm_stations (must be a user-added site, e.g. 'user_weibo')."))
                    put("login_cookie", stringProp("Optional: name of the cookie set after login (e.g. SUB for Weibo)."))
                    put("interactive_selectors", buildJsonObject {
                        put("type", "object")
                        put("description", "Optional friendly-name → CSS selector map (e.g. { hot_list: '.HotItem', post_title: 'h3.title' }).")
                    })
                    put("extra_origins", buildJsonObject {
                        put("type", "array")
                        put("items", buildJsonObject { put("type", "string") })
                        put("description", "Optional additional origins to claim beyond the site's homepage (e.g. ['https://m.weibo.cn', 'https://weibo.cn']).")
                    })
                    put("notes", stringProp("Optional free-form note shown in the settings audit UI."))
                },
                required = listOf("site_id"),
            )
        },
        execute = { input ->
            deps.track("wm_profile_synthesize", "WebMount 生成站点 Profile", input) {
                val siteId = input.requiredString("site_id")
                val userSite = userSiteRegistry.byId(siteId)
                    ?: error("No site with id '$siteId' is registered. Add it with wm_site_add first or pick an existing id from wm_stations.")
                // Block synthesis for native-adapter sites — they already have
                // a curated built-in profile; agent shouldn't override at runtime.
                if (userSite.nativeAdapterId != null) {
                    return@track listOf(UIMessagePart.Text(buildJsonObject {
                        put("ok", false)
                        put("site_id", siteId)
                        put("error", "Site '$siteId' is backed by a native adapter — its profile is curated and built-in. Synthesis is only for user-added sites.")
                    }.toString()))
                }
                val loginCookie = input.string("login_cookie")?.takeIf { it.isNotBlank() }
                val rawSelectors = (input.jsonObject)["interactive_selectors"] as? JsonObject
                val selectors: Map<String, String> = rawSelectors?.let { obj ->
                    obj.entries.mapNotNull { entry ->
                        val v = entry.value as? JsonPrimitive ?: return@mapNotNull null
                        val content = v.contentOrNull ?: return@mapNotNull null
                        entry.key to content
                    }.toMap()
                } ?: emptyMap()
                val extraOriginsRaw = (input.jsonObject)["extra_origins"] as? kotlinx.serialization.json.JsonArray
                val extraOrigins = extraOriginsRaw?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                    .orEmpty()
                val notes = input.string("notes")
                val homepageOrigin = ProfileRegistry.extractOrigin(userSite.homepageUrl)
                    ?: error("Cannot derive origin from homepage URL '${userSite.homepageUrl}'")
                val origins = (listOf(homepageOrigin) + extraOrigins.mapNotNull(ProfileRegistry::extractOrigin))
                    .distinct()
                val permissions = buildList {
                    if (loginCookie != null) {
                        add("read_cookie:$loginCookie")
                        add("detect_login")
                    }
                }
                // Build profile JSON using the same schema as built-in profiles.
                val profileJson = buildJsonObject {
                    put("id", siteId)
                    put("name", userSite.displayName)
                    put("version", 1)
                    put("origins", buildJsonArray { origins.forEach { add(JsonPrimitive(it)) } })
                    put("capabilities", buildJsonArray { add(JsonPrimitive("read")) })
                    val hints = buildJsonObject {
                        if (loginCookie != null) {
                            put("login_cookie", loginCookie)
                        }
                        if (selectors.isNotEmpty()) {
                            put("interactive_selectors", buildJsonObject {
                                selectors.forEach { (k, v) -> put(k, v) }
                            })
                        }
                    }
                    put("hints", hints)
                    put("permissions", buildJsonArray { permissions.forEach { add(JsonPrimitive(it)) } })
                    put("notes", notes ?: "Synthesized by agent for ${userSite.displayName}")
                }.toString()
                val result = profileRegistry.importJson(profileJson)
                val response = when (result) {
                    is me.rerere.rikkahub.data.agent.webmount.profile.ImportResult.Imported ->
                        buildJsonObject {
                            put("ok", true)
                            put("profile_id", result.profile.id)
                            put("origins_count", result.profile.origins.size)
                            put("selectors_count", selectors.size)
                            put("login_cookie_set", loginCookie != null)
                            put("sha256", result.sha256Hex.take(16))
                        }
                    is me.rerere.rikkahub.data.agent.webmount.profile.ImportResult.ConflictWithBuiltIn ->
                        buildJsonObject {
                            put("ok", false)
                            put("site_id", siteId)
                            put("error", "Profile id '${result.id}' collides with a built-in profile. Built-ins are curated and can't be overridden at runtime.")
                        }
                    is me.rerere.rikkahub.data.agent.webmount.profile.ImportResult.ParseError ->
                        buildJsonObject {
                            put("ok", false)
                            put("site_id", siteId)
                            put("error", "Synthesized JSON failed to parse: ${result.message}")
                        }
                    is me.rerere.rikkahub.data.agent.webmount.profile.ImportResult.ValidationError ->
                        buildJsonObject {
                            put("ok", false)
                            put("site_id", siteId)
                            put("error", "Synthesized profile failed validation: ${result.message}")
                        }
                }
                listOf(UIMessagePart.Text(response.toString()))
            }
        },
    )

    // ------------------------------------------------------------- helpers

    private fun JsonElement.safeUrlPreview(): JsonObject =
        buildJsonObject {
            put("url", string("url").orEmpty())
            put("session_id", string("session_id"))
            put("wait", string("wait"))
        }

    private fun JsonElement.safeEvalPreview(): JsonObject =
        buildJsonObject {
            put("session_id", string("session_id"))
            put("expression_chars", string("expression")?.length ?: 0)
        }
}
