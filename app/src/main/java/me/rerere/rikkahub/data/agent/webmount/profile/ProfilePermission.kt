package me.rerere.rikkahub.data.agent.webmount.profile

/**
 * Typed wire-grammar for [SiteProfile.permissions]. Profiles declare
 * concrete capabilities (rather than free-form action verbs) so the
 * Registry can audit, the import UI can show a human-readable list, and
 * the runtime can enforce.
 *
 * Wire forms:
 * - `call_page_fn:<name>` — bridge to a page function (also covered by
 *   schema-level validation in [SiteProfile.validate]).
 * - `read_cookie:<name>` — probe a specific cookie's presence. Profiles
 *   never receive the cookie *value* through this permission; the
 *   probe returns a boolean to the hosting code.
 * - `send_signed:<host>` — perform a signed request to the given host.
 *   The host MUST be an https origin contained in the profile's
 *   [SiteProfile.origins] list (cross-origin is forbidden by L3).
 * - `detect_login` — read [ProfileHints.loginCookie] to surface
 *   login status in `wm_state` / `wm_stations` output.
 * - `detect_rate_limit` — match [ProfileHints.rateLimit] against
 *   responses to decide DEGRADED status.
 */
sealed class ProfilePermission {

    abstract val wire: String

    data class CallPageFn(val fnName: String) : ProfilePermission() {
        override val wire: String = "call_page_fn:$fnName"
    }

    data class ReadCookie(val cookieName: String) : ProfilePermission() {
        override val wire: String = "read_cookie:$cookieName"
    }

    data class SendSigned(val host: String) : ProfilePermission() {
        override val wire: String = "send_signed:$host"
    }

    data object DetectLogin : ProfilePermission() {
        override val wire: String = "detect_login"
    }

    data object DetectRateLimit : ProfilePermission() {
        override val wire: String = "detect_rate_limit"
    }

    /** Localized one-line human description for the import-audit UI. */
    fun describeZh(): String = when (this) {
        is CallPageFn -> "桥接调用页面函数 $fnName"
        is ReadCookie -> "探测 cookie '$cookieName' 是否存在 (不读取值)"
        is SendSigned -> "向 $host 发送签名请求"
        is DetectLogin -> "通过 cookie 判断登录态"
        is DetectRateLimit -> "识别响应中的风控/限速标志"
    }

    /** English variant for `values/strings.xml`-style locales. */
    fun describeEn(): String = when (this) {
        is CallPageFn -> "Bridge to in-page function $fnName"
        is ReadCookie -> "Probe whether cookie '$cookieName' exists (value never read)"
        is SendSigned -> "Send a signed request to $host"
        is DetectLogin -> "Detect logged-in state via the cookie hint"
        is DetectRateLimit -> "Recognize rate-limit / anti-bot responses"
    }

    companion object {
        /**
         * Parse a single wire-form permission string. Throws on unknown
         * verbs or missing arguments — fail-loud so a malformed manifest
         * never enters the live registry.
         */
        fun parse(raw: String, profileId: String): ProfilePermission {
            val trimmed = raw.trim()
            return when {
                trimmed.startsWith("call_page_fn:") -> {
                    val name = trimmed.removePrefix("call_page_fn:").trim()
                    require(name.isNotBlank()) {
                        "Profile $profileId: permission '$raw' has empty fn name"
                    }
                    CallPageFn(name.removePrefix("window."))
                }
                trimmed.startsWith("read_cookie:") -> {
                    val name = trimmed.removePrefix("read_cookie:").trim()
                    require(name.isNotBlank()) {
                        "Profile $profileId: permission '$raw' has empty cookie name"
                    }
                    ReadCookie(name)
                }
                trimmed.startsWith("send_signed:") -> {
                    val host = trimmed.removePrefix("send_signed:").trim()
                    require(host.isNotBlank()) {
                        "Profile $profileId: permission '$raw' has empty host"
                    }
                    SendSigned(host)
                }
                trimmed == "detect_login" -> DetectLogin
                trimmed == "detect_rate_limit" -> DetectRateLimit
                else -> error(
                    "Profile $profileId: unknown permission '$raw'. " +
                        "Valid verbs: call_page_fn:<name>, read_cookie:<name>, " +
                        "send_signed:<host>, detect_login, detect_rate_limit"
                )
            }
        }
    }
}
