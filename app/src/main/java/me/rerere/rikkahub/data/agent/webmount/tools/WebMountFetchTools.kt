package me.rerere.rikkahub.data.agent.webmount.tools

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.agent.tools.long
import me.rerere.rikkahub.data.agent.tools.requiredString
import me.rerere.rikkahub.data.agent.tools.string
import me.rerere.rikkahub.data.agent.webmount.primitives.NetworkLog
import me.rerere.rikkahub.data.agent.webmount.profile.ProfileBridge
import me.rerere.rikkahub.data.agent.webmount.profile.ProfileRegistry

internal fun createSignedFetchTool(
    deps: WebMountDeps,
    profileRegistry: ProfileRegistry,
    profileBridge: ProfileBridge,
): Tool = Tool(
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

            val handle = deps.pool.peek(sessionId) ?: error("session not found: $sessionId")
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

internal fun createNetworkInspectTool(deps: WebMountDeps): Tool = Tool(
    name = "wm_network_inspect",
    description = """
        Inspect redacted XHR/fetch request templates observed in a WebMount session. Returns host,
        path with query values redacted, status, counts, and opaque request_template_id values. It
        never exposes cookies, authorization headers, or captured request bodies.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("session_id", stringProp("Session id returned by wm_open."))
                put("max", integerProp("Max templates. Default 50, cap 200."))
            },
            required = listOf("session_id"),
        )
    },
    execute = { input ->
        deps.track("wm_network_inspect", "WebMount 网络观察", input) {
            val sessionId = input.requiredString("session_id")
            val handle = deps.pool.peek(sessionId) ?: error("session not found: $sessionId")
            val max = (input.long("max") ?: 50L).coerceIn(0L, 200L).toInt()
            val payload = buildJsonObject {
                put("session_id", sessionId)
                put("network_coverage", handle.bridgeInjectionCoverage)
                put("result", handle.networkLog.inspect(handle.loadState.value.currentUrl, max))
            }
            listOf(UIMessagePart.Text(payload.toString()))
        }
    },
)

internal fun createFetchReplayTool(deps: WebMountDeps): Tool = Tool(
    name = "wm_fetch_replay",
    description = """
        Replay an observed same-origin GET/HEAD request template from inside the page context. The
        model only sees the opaque template id; cookies and page credentials stay inside WebView.
        POST/GraphQL/write-like requests are rejected here and must become explicit reviewed recipes.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("session_id", stringProp("Session id returned by wm_open."))
                put("request_template_id", stringProp("Opaque id from wm_network_inspect or wm_observe.network."))
                put("max_chars", integerProp("Response text budget. Default 60000, cap 200000."))
            },
            required = listOf("session_id", "request_template_id"),
        )
    },
    needsApproval = true,
    allowsAutoApproval = false,
    mandatoryApproval = true,
    execute = { input ->
        deps.track("wm_fetch_replay", "WebMount 请求重放", input) {
            val sessionId = input.requiredString("session_id")
            val templateId = input.requiredString("request_template_id")
            val handle = deps.pool.peek(sessionId) ?: error("session not found: $sessionId")
            val template = handle.networkLog.template(templateId)
                ?: error("request template not found: $templateId")
            require(template.method in setOf("GET", "HEAD")) {
                "wm_fetch_replay only supports observed GET/HEAD templates"
            }
            val currentUrl = handle.loadState.value.currentUrl
                ?: error("session has no committed URL yet")
            val replayUrl = NetworkLog.resolveUrl(currentUrl, template.rawUrl)
            require(NetworkLog.originOf(currentUrl) == NetworkLog.originOf(replayUrl)) {
                "wm_fetch_replay only supports same-origin requests"
            }
            require(!NetworkLog.isProbablyMutatingReplayUrl(replayUrl)) {
                "wm_fetch_replay refused a GET/HEAD endpoint with mutation-like path hints"
            }
            val maxChars = (input.long("max_chars") ?: 60_000L).coerceIn(1_000L, 200_000L)
            val result = handle.callBridge(
                "fetch_replay",
                buildJsonObject {
                    put("method", template.method)
                    put("url", replayUrl)
                    put("max_chars", maxChars)
                },
                timeoutMs = 30_000L,
            )
            val payload = buildJsonObject {
                put("session_id", sessionId)
                put("request_template_id", templateId)
                put("method", template.method)
                put("host", NetworkLog.originOf(replayUrl).orEmpty())
                put("path", NetworkLog.redactedPath(replayUrl))
                put("result", result)
            }
            listOf(UIMessagePart.Text(payload.toString()))
        }
    },
)

internal fun createRecipeCandidatesTool(deps: WebMountDeps): Tool = Tool(
    name = "wm_recipe_candidates",
    description = """
        Generate read-only recipe candidates from observed network templates without saving or
        mutating profile configuration. Candidates are intentionally conservative: same-origin
        GET/HEAD templates only, validated later by wm_fetch_replay, with DOM/visual fallbacks
        left explicit for the agent.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("session_id", stringProp("Session id returned by wm_open."))
                put("max", integerProp("Max observed templates to inspect. Default 50, cap 200."))
            },
            required = listOf("session_id"),
        )
    },
    execute = { input ->
        deps.track("wm_recipe_candidates", "WebMount Recipe 候选", input) {
            val sessionId = input.requiredString("session_id")
            val handle = deps.pool.peek(sessionId) ?: error("session not found: $sessionId")
            val max = (input.long("max") ?: 50L).coerceIn(0L, 200L).toInt()
            val inspected = handle.networkLog.inspect(handle.loadState.value.currentUrl, max)
            val payload = buildJsonObject {
                put("session_id", sessionId)
                put("saved", false)
                put("candidate_source", "observed_same_origin_network_templates")
                put("runner", "wm_fetch_replay")
                put("fallback_chain", buildJsonArray {
                    add(JsonPrimitive("wm_observe"))
                    add(JsonPrimitive("wm_extract"))
                    add(JsonPrimitive("wm_visual_read"))
                })
                put("network_coverage", handle.bridgeInjectionCoverage)
                put("candidates", inspected)
            }
            listOf(UIMessagePart.Text(payload.toString()))
        }
    },
)
