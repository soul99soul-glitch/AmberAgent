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
    private val openTool by lazy { createOpenTool(deps, profileRegistry, cookieProvider, manager) }
    private val stateTool by lazy { createStateTool(deps, profileRegistry, cookieProvider, manager) }
    private val extractTool by lazy { createExtractTool(deps) }
    private val getTool by lazy { createGetTool(deps) }
    private val backTool by lazy { createBackTool(deps) }
    private val forwardTool by lazy { createForwardTool(deps) }

    private val waitTool by lazy { createWaitTool(deps) }
    private val clickTool by lazy { createClickTool(deps) }
    private val typeTool by lazy { createTypeTool(deps) }
    private val evalTool by lazy { createEvalTool(deps) }
    private val scrollTool by lazy { createScrollTool(deps) }
    private val keysTool by lazy { createKeysTool(deps) }
    private val selectTool by lazy { createSelectTool(deps) }
    private val findTool by lazy { createFindTool(deps) }

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

}
