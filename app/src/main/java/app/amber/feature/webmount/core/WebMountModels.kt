package app.amber.feature.webmount.core

import app.amber.feature.webmount.cookie.EndpointSpec

/**
 * Lifecycle state of a WebMount station as surfaced to the unified panel.
 * Mirrors [me.rerere.rikkahub.data.agent.icloud.ICloudDriveStatus] for the
 * iCloud prototype but adds [DEGRADED] for "auth still valid but the site is
 * rate-limiting / captcha-challenging us".
 */
enum class WebMountStatus(val wireName: String) {
    NOT_CONFIGURED("not_configured"),
    LOGIN_REQUIRED("login_required"),
    PROBING("probing"),
    READ_ONLY("read_only"),
    READ_WRITE("read_write"),
    DEGRADED("degraded"),
    ERROR("error"),
}

enum class WebMountCapability(val wireName: String) {
    NONE("none"),
    READ_ONLY("read_only"),
    READ_WRITE("read_write"),
}

enum class WebMountAuthMethod(val wireName: String) {
    COOKIE("cookie"),
    OAUTH("oauth"),
    ANONYMOUS("anonymous"),
}

/**
 * Adapter-agnostic snapshot a Station card on the panel needs to render.
 * Adapter-specific config (iCloud vault path, GitHub default org, ...) lives on
 * the adapter side and is exposed by adapter-specific Settings pages.
 */
data class WebMountStationState(
    val id: String,
    val displayName: String,
    val authMethods: Set<WebMountAuthMethod>,
    val enabled: Boolean = false,
    val status: WebMountStatus = WebMountStatus.NOT_CONFIGURED,
    val capability: WebMountCapability = WebMountCapability.NONE,
    val message: String? = null,
    val updatedAtMillis: Long = 0L,
)

data class WebMountCookieBundle(
    val header: String,
    val sourceUrls: List<String> = emptyList(),
) {
    val isEmpty: Boolean get() = header.isBlank()

    fun value(name: String): String? =
        header.split(";")
            .map { it.trim() }
            .firstOrNull { it.startsWith("$name=") }
            ?.substringAfter("=")

    fun hasAll(names: Set<String>): Boolean = names.all { value(it) != null }

    companion object {
        val EMPTY = WebMountCookieBundle(header = "")
    }
}

/**
 * Placeholder for M1.5 OAuth token contents. Defined here so adapter signatures
 * stabilize early and the M1.5 OAuth bridge doesn't churn type imports.
 */
data class WebMountOAuthToken(
    val provider: String,
    val accessToken: String,
    val refreshToken: String?,
    val scope: String?,
    val tokenType: String = "Bearer",
    val expiresAtMs: Long,
    val acquiredAtMs: Long,
    val userOpenId: String? = null,
) {
    fun isExpired(nowMs: Long = System.currentTimeMillis(), skewMs: Long = 5 * 60_000L): Boolean =
        expiresAtMs - skewMs <= nowMs

    /**
     * Defensive toString: redacts the secrets so a stray Log.d / crash dump can't
     * leak the token. If you genuinely need the values, access the fields directly.
     */
    override fun toString(): String =
        "WebMountOAuthToken(provider=$provider, accessToken=***redacted***, " +
            "refreshToken=${if (refreshToken == null) "null" else "***redacted***"}, " +
            "scope=$scope, tokenType=$tokenType, expiresAtMs=$expiresAtMs, " +
            "acquiredAtMs=$acquiredAtMs, userOpenId=$userOpenId)"
}

/**
 * Result of a [WebMountAdapter] probe. Translated into a [WebMountStatus] by
 * [WebMountManager] when persisting state.
 */
sealed class WebMountProbeResult {
    data class Success(
        val capability: WebMountCapability,
        val message: String? = null,
    ) : WebMountProbeResult()

    data class LoginRequired(val message: String? = null) : WebMountProbeResult()
    data class Degraded(val message: String) : WebMountProbeResult()
    data class Failed(val message: String, val cause: Throwable? = null) : WebMountProbeResult()
    data object NotSupported : WebMountProbeResult()

    companion object {
        fun success(capability: WebMountCapability, message: String? = null): WebMountProbeResult =
            Success(capability, message)
        fun loginRequired(message: String? = null): WebMountProbeResult = LoginRequired(message)
        fun degraded(message: String): WebMountProbeResult = Degraded(message)
        fun failed(message: String, cause: Throwable? = null): WebMountProbeResult = Failed(message, cause)
        fun notSupported(): WebMountProbeResult = NotSupported
    }
}

/**
 * Bundle handed to an adapter so it can surface tools and probe results
 * without depending on [WebMountManager] internals.
 */
data class WebMountAdapterDescriptor(
    val id: String,
    val displayName: String,
    val authMethods: Set<WebMountAuthMethod>,
    val capabilityHints: Set<WebMountCapability>,
    val endpoints: List<EndpointSpec>,
    val toolNamePrefix: String,
    val outputBudgetChars: Int = 80_000,
)
