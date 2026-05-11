package me.rerere.rikkahub.data.agent.webmount.cookie

/**
 * Describes one logical "place we can talk to a station". A station may have
 * several endpoints (e.g. iCloud global vs China) and the cookie provider picks
 * whichever set the browser has authenticated cookies for.
 *
 * Generalization of `ICloudDriveWebEndpoint`.
 */
data class EndpointSpec(
    val id: String,
    val displayName: String,
    val loginUrl: String,
    val apiBase: String,
    val origin: String,
    val cookieUrls: List<String>,
    val requiredCookieNames: Set<String> = emptySet(),
) {
    val referer: String get() = "$origin/"
}
