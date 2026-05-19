package me.rerere.rikkahub.data.agent.webmount.usersites

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Phase 2 Plan v2 — persistent user-facing site list.
 *
 * Acts as the **single source of truth for the WebMount settings UI**:
 *  - Seeds 7 example sites on first launch (the same sites that ship
 *    as native adapters today).
 *  - Lets the user add / remove any entry, including the seeds —
 *    "predefined" is just data, not a special category.
 *  - Persists to a SharedPreferences file separate from
 *    [me.rerere.rikkahub.data.agent.webmount.core.WebMountManager]'s
 *    station-state file, so removing a site never wipes its login
 *    cookies (those live in the process-global CookieManager) and
 *    re-adding it picks up where the user left off.
 *
 * The 7 native adapters keep running in the background. When a site
 * with `nativeAdapterId != null` is in the user's list, the adapter's
 * tools appear in the agent's catalog; remove the site → tools
 * disappear (LocalTools.getTools consults this registry).
 *
 * Thread-safe via `@Synchronized` on mutators.
 */
class UserSiteRegistry(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val _sites = MutableStateFlow(loadOrSeed())
    val sites: StateFlow<List<UserSite>> = _sites.asStateFlow()

    /** Synchronous accessor for callers outside coroutine scopes. */
    val current: List<UserSite> get() = _sites.value

    /** True iff a site with this id is currently in the user list. */
    fun contains(id: String): Boolean = current.any { it.id == id }

    fun byId(id: String): UserSite? = current.firstOrNull { it.id == id }

    /** All native-adapter sites currently in the list (drives agent tool catalog). */
    fun activeNativeAdapterIds(): Set<String> =
        current.mapNotNull { it.nativeAdapterId }.toSet()

    @Synchronized
    fun add(site: UserSite): Boolean {
        if (current.any { it.id == site.id }) return false
        // Per-user feedback: newly added sites should appear at the top of
        // the list (newest first), not buried at the bottom under the seed
        // examples. Prepend rather than append.
        val next = listOf(site.copy(addedAtMs = System.currentTimeMillis())) + current
        _sites.value = next
        persist(next)
        return true
    }

    /**
     * Update an existing site in place. Returns the new entry on success or
     * null if no site with that id exists. Used by the WebView login flow to
     * auto-fill the loginCookieName once we've inferred it from the cookie
     * diff.
     */
    @Synchronized
    fun update(id: String, transform: (UserSite) -> UserSite): UserSite? {
        val current = _sites.value
        val existing = current.firstOrNull { it.id == id } ?: return null
        val next = transform(existing)
        if (next == existing) return existing
        val updated = current.map { if (it.id == id) next else it }
        _sites.value = updated
        persist(updated)
        return next
    }

    @Synchronized
    fun remove(id: String): Boolean {
        val current = _sites.value
        if (current.none { it.id == id }) return false
        val next = current.filterNot { it.id == id }
        _sites.value = next
        persist(next)
        return true
    }

    /**
     * Add back any seed sites the user has deleted. Returns the count of
     * sites added. Doesn't touch existing entries even if their fields
     * have drifted from the seed (e.g. user might have renamed one).
     */
    @Synchronized
    fun restoreMissingSeeds(): Int {
        val current = _sites.value
        val existingIds = current.map { it.id }.toSet()
        val missing = SEED_SITES.filterNot { it.id in existingIds }
        if (missing.isEmpty()) return 0
        val next = current + missing.map { it.copy(addedAtMs = System.currentTimeMillis()) }
        _sites.value = next
        persist(next)
        return missing.size
    }

    /** For testing / migration: clear all entries (no UI exposure). */
    @Synchronized
    internal fun clear() {
        _sites.value = emptyList()
        prefs.edit().remove(KEY_SITES).putBoolean(KEY_SEEDED, true).apply()
    }

    // ----------------------------------------------------------------------

    private fun loadOrSeed(): List<UserSite> {
        val raw = prefs.getString(KEY_SITES, null)
        val seeded = prefs.getBoolean(KEY_SEEDED, false)
        if (raw != null) {
            val parsed = runCatching {
                json.decodeFromString(ListSerializer(UserSite.serializer()), raw)
            }.getOrElse {
                Log.w(TAG, "Failed to decode user sites — falling back to seeds", it)
                return seedOnce()
            }
            // Migration: user-added sites added before the AddCustomSiteDialog
            // grew a "需要登录" switch defaulted to ANONYMOUS unless the user
            // typed in a cookie name. In practice virtually every user-added
            // site needs login, so they ended up with no Sign-in button.
            // Promote them to COOKIE on first read after the upgrade and
            // persist so it sticks.
            val migratedBase = parsed.map { site ->
                var next = site
                // Migration A: user-added sites added before the "需要登录" Switch
                // defaulted to ANONYMOUS + no cookie → bump to COOKIE so the
                // login button reappears.
                if (next.id.startsWith("user_") &&
                    next.authKind == AuthKind.ANONYMOUS &&
                    next.loginCookieName == null &&
                    next.nativeAdapterId == null
                ) {
                    next = next.copy(authKind = AuthKind.COOKIE)
                }
                // Migration B: feishu_docs OAuth provider id remap. Older rows
                // saved this UserSite without oauthProviderId, so the page's
                // OAuth lookup (using site.id "feishu_docs") returned null and
                // the 编辑凭据 dialog dismissed itself on open. Backfill the
                // mapping for the canonical case.
                if (next.id == "feishu_docs" && next.oauthProviderId == null) {
                    next = next.copy(oauthProviderId = "feishu")
                }
                // Migration C: feishu_docs homepage URL. Marketing /
                // messenger entries often hit Feishu's "unsupported browser"
                // gate in embedded WebView. The wiki entry goes straight
                // through Feishu passport and behaves closer to mobile Chrome.
                if (next.id == "feishu_docs" &&
                    (
                        next.homepageUrl == "https://www.feishu.cn" ||
                            next.homepageUrl == "https://www.feishu.cn/messenger/"
                        )
                ) {
                    next = next.copy(homepageUrl = FEISHU_DOCS_HOME)
                }
                // Migration D: Juejin's native adapter probes for sessionid.
                // Older seed rows used passport_csrf_token, which made the UI
                // badge disagree with the adapter's real login requirement.
                if (next.id == "juejin" && next.loginCookieName == "passport_csrf_token") {
                    next = next.copy(loginCookieName = "sessionid")
                }
                next
            }.let { sites ->
                val seedVersion = prefs.getInt(KEY_SEED_VERSION, if (seeded) 1 else 0)
                if (seedVersion >= CURRENT_SEED_VERSION) return@let sites
                val existingIds = sites.map { it.id }.toSet()
                val existingSignatures = sites.map { it.seedAutoAddSignature() }.toSet()
                val newSeeds = SEED_SITES
                    .filter { it.id in AUTO_ADD_SEED_IDS && it.id !in existingIds && it.seedAutoAddSignature() !in existingSignatures }
                    .map { it.copy(addedAtMs = System.currentTimeMillis()) }
                if (newSeeds.isEmpty()) sites else sites + newSeeds
            }
            val migrated = migratedBase
            if (migrated != parsed) {
                // W-5 fix: count what actually changed, not "all originally
                // ANONYMOUS" (which over-counts when some ANONYMOUS rows
                // didn't qualify for the user_/no-cookie/no-adapter check).
                val changed = migrated.zip(parsed).count { (m, p) -> m != p } + (migrated.size - parsed.size).coerceAtLeast(0)
                Log.i(TAG, "Migrated $changed user site(s) to current schema (authKind / oauthProviderId backfill)")
                persist(migrated)
            } else if (prefs.getInt(KEY_SEED_VERSION, 0) < CURRENT_SEED_VERSION) {
                prefs.edit().putInt(KEY_SEED_VERSION, CURRENT_SEED_VERSION).apply()
            }
            return migrated
        }
        if (!seeded) return seedOnce()
        // Marked seeded already but raw is missing — user cleared everything.
        return emptyList()
    }

    private fun seedOnce(): List<UserSite> {
        val now = System.currentTimeMillis()
        val seeds = SEED_SITES.map { it.copy(addedAtMs = now) }
        prefs.edit()
            .putString(KEY_SITES, json.encodeToString(ListSerializer(UserSite.serializer()), seeds))
            .putBoolean(KEY_SEEDED, true)
            .putInt(KEY_SEED_VERSION, CURRENT_SEED_VERSION)
            .apply()
        return seeds
    }

    private fun persist(sites: List<UserSite>) {
        prefs.edit()
            .putString(KEY_SITES, json.encodeToString(ListSerializer(UserSite.serializer()), sites))
            .putBoolean(KEY_SEEDED, true)
            .putInt(KEY_SEED_VERSION, CURRENT_SEED_VERSION)
            .apply()
    }

    companion object {
        private const val TAG = "WebMountUserSiteRegistry"
        private const val PREFS_FILE = "amberagent_webmount_user_sites"
        private const val KEY_SITES = "sites"
        private const val KEY_SEEDED = "seeded"
        private const val KEY_SEED_VERSION = "seed_version"
        private const val CURRENT_SEED_VERSION = 3
        private val AUTO_ADD_SEED_IDS = setOf("x_com", "weibo")

        /**
         * The 7 sites users see by default. Their ids match the matching
         * [me.rerere.rikkahub.data.agent.webmount.core.WebMountAdapter.id]
         * values so the UI can join with adapter state at runtime.
         */
        val SEED_SITES: List<UserSite> = listOf(
            UserSite(
                id = "hackernews",
                displayName = "Hacker News",
                homepageUrl = "https://news.ycombinator.com",
                authKind = AuthKind.ANONYMOUS,
                nativeAdapterId = "hackernews",
                iconKey = "hackernews",
            ),
            UserSite(
                id = "reddit",
                displayName = "Reddit",
                homepageUrl = "https://www.reddit.com",
                authKind = AuthKind.ANONYMOUS,
                nativeAdapterId = "reddit",
                iconKey = "reddit",
            ),
            UserSite(
                id = "github",
                displayName = "GitHub",
                homepageUrl = "https://github.com/login",
                authKind = AuthKind.COOKIE,
                loginCookieName = "user_session",
                nativeAdapterId = "github",
                iconKey = "github",
            ),
            UserSite(
                id = "bilibili",
                displayName = "Bilibili",
                homepageUrl = "https://passport.bilibili.com/login",
                authKind = AuthKind.COOKIE,
                loginCookieName = "SESSDATA",
                nativeAdapterId = "bilibili",
                iconKey = "bilibili",
            ),
            UserSite(
                id = "x_com",
                displayName = "X.com",
                homepageUrl = "https://x.com/i/flow/login",
                authKind = AuthKind.COOKIE,
                loginCookieName = "auth_token",
                iconKey = "x_com",
            ),
            UserSite(
                id = "weibo",
                displayName = "微博",
                homepageUrl = "https://m.weibo.cn",
                authKind = AuthKind.COOKIE,
                loginCookieName = "SUB",
                iconKey = "weibo",
            ),
            UserSite(
                id = "juejin",
                displayName = "掘金",
                homepageUrl = "https://juejin.cn/login",
                authKind = AuthKind.COOKIE,
                loginCookieName = "sessionid",
                nativeAdapterId = "juejin",
                iconKey = "juejin",
            ),
            UserSite(
                id = "zhihu",
                displayName = "知乎",
                homepageUrl = "https://www.zhihu.com/signin",
                authKind = AuthKind.COOKIE,
                loginCookieName = "z_c0",
                nativeAdapterId = "zhihu",
                iconKey = "zhihu",
            ),
            UserSite(
                id = "feishu_docs",
                displayName = "飞书云文档",
                // The wiki entry redirects through Feishu passport and avoids
                // the WebView-hostile messenger/download path.
                homepageUrl = FEISHU_DOCS_HOME,
                authKind = AuthKind.OAUTH,
                nativeAdapterId = "feishu_docs",
                iconKey = "feishu_docs",
                // OAuth provider registers as "feishu" (FeishuOAuthProvider.id);
                // UserSite.id is "feishu_docs". Remap so the page's lookup hits.
                oauthProviderId = "feishu",
            ),
        )

        private const val FEISHU_DOCS_HOME = "https://www.feishu.cn/wiki"
    }
}

internal fun UserSite.seedAutoAddSignature(): String = when {
    homepageUrl.contains("weibo", ignoreCase = true) || homepageUrl.contains("sina", ignoreCase = true) ||
        displayName.contains("微博", ignoreCase = true) || id.contains("weibo", ignoreCase = true) -> "weibo"
    homepageUrl.contains("x.com", ignoreCase = true) || homepageUrl.contains("twitter", ignoreCase = true) ||
        id == "x_com" || id.contains("twitter", ignoreCase = true) -> "x_com"
    else -> id
}
