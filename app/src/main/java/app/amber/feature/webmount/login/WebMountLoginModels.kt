package app.amber.feature.webmount.login

import app.amber.feature.webmount.cookie.CookieFieldHint
import app.amber.feature.webmount.cookie.CookieSnapshot
import app.amber.feature.webmount.core.WebMountAdapter
import app.amber.feature.webmount.core.WebMountManager
import app.amber.feature.webmount.profile.ProfileRegistry
import app.amber.feature.webmount.usersites.UserSite
import app.amber.feature.webmount.usersites.collectSiteUrls
import app.amber.feature.webmount.usersites.loginCookieCandidatesFor
import app.amber.feature.webmount.usersites.loginSuccessUrlPatternsFor
import app.amber.feature.webmount.usersites.manualImportCookieFieldsFor
import app.amber.feature.webmount.usersites.requiredLoginCookieSetsFor

data class WebMountLoginTarget(
    val id: String,
    val displayName: String,
    val startUrl: String,
    val stationId: String?,
    val urls: List<String>,
    val requiredCookieSets: List<Set<String>>,
    val candidateCookieNames: List<String>,
    val successUrlPatterns: List<Regex>,
    val manualCookieFields: List<CookieFieldHint>,
) {
    companion object {
        fun fromUserSite(
            site: UserSite,
            manager: WebMountManager,
            profileRegistry: ProfileRegistry,
        ): WebMountLoginTarget {
            val adapter = site.nativeAdapterId?.let { manager.adapterOf(it) }
            val adapterRequiredSets = adapter
                ?.endpoints
                ?.mapNotNull { endpoint -> endpoint.requiredCookieNames.takeIf { it.isNotEmpty() } }
                .orEmpty()
            val requiredSets = (requiredLoginCookieSetsFor(site) + adapterRequiredSets)
                .filter { it.isNotEmpty() }
                .distinct()
            val candidates = buildList {
                site.loginCookieName?.trim()?.takeIf { it.isNotBlank() }?.let { add(it) }
                addAll(loginCookieCandidatesFor(site))
                addAll(requiredSets.flatten())
            }.distinct()
            return WebMountLoginTarget(
                id = site.id,
                displayName = site.displayName,
                startUrl = site.homepageUrl,
                stationId = site.nativeAdapterId,
                urls = collectSiteUrls(site, manager, profileRegistry),
                requiredCookieSets = requiredSets,
                candidateCookieNames = candidates,
                successUrlPatterns = loginSuccessUrlPatternsFor(site),
                manualCookieFields = manualImportCookieFieldsFor(site),
            )
        }

        fun fromAdapter(adapter: WebMountAdapter): WebMountLoginTarget {
            val urls = adapter.endpoints.flatMap { endpoint ->
                endpoint.cookieUrls + endpoint.loginUrl + endpoint.origin + endpoint.apiBase
            }.distinct()
            val requiredSets = adapter.endpoints
                .mapNotNull { endpoint -> endpoint.requiredCookieNames.takeIf { it.isNotEmpty() } }
                .distinct()
            val candidates = requiredSets.flatten().distinct()
            return WebMountLoginTarget(
                id = adapter.id,
                displayName = adapter.displayName,
                startUrl = adapter.primaryLoginUrl().orEmpty(),
                stationId = adapter.id,
                urls = urls,
                requiredCookieSets = requiredSets,
                candidateCookieNames = candidates,
                successUrlPatterns = emptyList(),
                manualCookieFields = candidates.map { name -> CookieFieldHint(name = name) },
            )
        }
    }
}

sealed class WebMountLoginStatus {
    data object Waiting : WebMountLoginStatus()
    data class UrlMatched(val url: String) : WebMountLoginStatus()
    data class SignedIn(val cookieNames: Set<String>) : WebMountLoginStatus()
    data class MissingCookies(val missing: Set<String>) : WebMountLoginStatus()
    data class Unknown(val reason: String) : WebMountLoginStatus()
    data class Failed(val reason: String) : WebMountLoginStatus()
}

object WebMountLoginDetector {
    fun evaluate(
        target: WebMountLoginTarget,
        currentUrl: String?,
        snapshot: CookieSnapshot,
    ): WebMountLoginStatus {
        target.requiredCookieSets.firstOrNull { required -> snapshot.containsAll(required) }?.let { satisfied ->
            return WebMountLoginStatus.SignedIn(satisfied)
        }
        if (target.requiredCookieSets.isEmpty()) {
            val presentCandidates = target.candidateCookieNames
                .filter { name -> snapshot.valuesByName(name).isNotEmpty() }
                .toSet()
            if (presentCandidates.isNotEmpty()) {
                return WebMountLoginStatus.SignedIn(presentCandidates)
            }
            return WebMountLoginStatus.Unknown("No required login cookie is configured for ${target.displayName}")
        }
        if (snapshot.isEmpty) {
            return if (currentUrl != null && isSuccessUrl(target, currentUrl)) {
                WebMountLoginStatus.UrlMatched(currentUrl)
            } else {
                WebMountLoginStatus.Waiting
            }
        }
        val missing = target.requiredCookieSets
            .map { required -> snapshot.missing(required).toSet() }
            .minByOrNull { it.size }
            .orEmpty()
        return if (currentUrl != null && isSuccessUrl(target, currentUrl)) {
            WebMountLoginStatus.UrlMatched(currentUrl)
        } else {
            WebMountLoginStatus.MissingCookies(missing)
        }
    }

    fun isSuccessUrl(target: WebMountLoginTarget, url: String): Boolean {
        return target.successUrlPatterns.any { pattern -> pattern.containsMatchIn(url) }
    }
}
