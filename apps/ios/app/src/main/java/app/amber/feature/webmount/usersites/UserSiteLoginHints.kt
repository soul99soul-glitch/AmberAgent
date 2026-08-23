package app.amber.feature.webmount.usersites

import app.amber.feature.webmount.cookie.CookieFieldHint

/**
 * Site-specific login heuristics for user-added WebMount entries.
 *
 * A custom site only has one optional [UserSite.loginCookieName], but some
 * mainstream mobile sites set their durable login marker on sibling domains
 * or behind a passport host. Keep those hints centralized so the settings UI
 * and agent-facing `wm_stations` report the same login state.
 */
internal fun loginCookieCandidatesFor(site: UserSite): List<String> {
    return buildList {
        site.loginCookieName?.trim()?.takeIf { it.isNotBlank() }?.let { add(it) }
        addAll(knownLoginCookieNamesFor(site))
    }.distinct()
}

internal fun knownLoginCookieNamesFor(site: UserSite): List<String> {
    val signature = site.signature()
    return when {
        signature.contains("x.com") || signature.contains("twitter") ->
            listOf("auth_token", "ct0", "twid")

        signature.contains("bilibili") || signature.contains("b站") ->
            listOf("SESSDATA", "bili_jct", "DedeUserID")

        signature.contains("zhihu") || signature.contains("知乎") ->
            listOf("z_c0")

        signature.contains("weibo") || signature.contains("微博") || signature.contains("sina") ->
            listOf("SUB", "SUBP", "SSOLoginState", "ALF", "MLOGIN")

        signature.contains("juejin") || signature.contains("掘金") ->
            listOf("sessionid", "passport_csrf_token", "sid_guard")

        else -> emptyList()
    }
}

internal fun requiredLoginCookieSetsFor(site: UserSite): List<Set<String>> {
    val signature = site.signature()
    val known = when {
        signature.contains("x.com") || signature.contains("twitter") ->
            listOf(setOf("auth_token", "ct0"))

        signature.contains("bilibili") || signature.contains("b站") ->
            listOf(setOf("SESSDATA"))

        signature.contains("zhihu") || signature.contains("知乎") ->
            listOf(setOf("z_c0"))

        signature.contains("weibo") || signature.contains("微博") || signature.contains("sina") ->
            listOf(setOf("SUB"))

        signature.contains("juejin") || signature.contains("掘金") ->
            listOf(setOf("sessionid"))

        else -> emptyList()
    }
    val configured = site.loginCookieName
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?.let { listOf(setOf(it)) }
        .orEmpty()
    if (known.isEmpty()) return configured.distinct()
    val explicitOverrides = configured.filterNot { configuredSet ->
        known.any { knownSet -> configuredSet != knownSet && knownSet.containsAll(configuredSet) }
    }
    return (known + explicitOverrides).distinct()
}

internal fun extraLoginProbeUrlsFor(site: UserSite): List<String> {
    val signature = site.signature()
    return when {
        signature.contains("x.com") || signature.contains("twitter") -> listOf(
            "https://x.com",
            "https://api.x.com",
            "https://twitter.com",
            "https://mobile.twitter.com",
        )

        signature.contains("bilibili") || signature.contains("b站") -> listOf(
            "https://www.bilibili.com",
            "https://api.bilibili.com",
            "https://passport.bilibili.com",
        )

        signature.contains("zhihu") || signature.contains("知乎") -> listOf(
            "https://www.zhihu.com",
            "https://www.zhihu.com/signin",
        )

        signature.contains("weibo") || signature.contains("微博") || signature.contains("sina") -> listOf(
            "https://weibo.com",
            "https://m.weibo.cn",
            "https://passport.weibo.cn",
            "https://passport.weibo.com",
            "https://login.sina.com.cn",
            "https://sina.com.cn",
        )

        signature.contains("juejin") || signature.contains("掘金") -> listOf(
            "https://juejin.cn",
            "https://api.juejin.cn",
            "https://juejin.cn/login",
        )

        signature.contains("feishu") || signature.contains("飞书") || signature.contains("lark") -> listOf(
            "https://www.feishu.cn/wiki",
            "https://feishu.cn/wiki",
            "https://accounts.feishu.cn",
            "https://passport.feishu.cn",
            "https://open.feishu.cn",
        )

        else -> emptyList()
    }
}

internal fun loginSuccessUrlPatternsFor(site: UserSite): List<Regex> {
    val signature = site.signature()
    return when {
        signature.contains("x.com") || signature.contains("twitter") -> listOf(
            Regex("""^https://(x|twitter)\.com/(home|notifications|messages|i/bookmarks)(?:[/?#].*)?$"""),
        )

        signature.contains("bilibili") || signature.contains("b站") -> listOf(
            Regex("""^https://(www\.)?bilibili\.com/?(?:[?#].*)?$"""),
            Regex("""^https://space\.bilibili\.com/.*$"""),
        )

        signature.contains("zhihu") || signature.contains("知乎") -> listOf(
            Regex("""^https://www\.zhihu\.com/?(?:[?#].*)?$"""),
            Regex("""^https://www\.zhihu\.com/(people|notifications|creator).*"""),
        )

        signature.contains("weibo") || signature.contains("微博") || signature.contains("sina") -> listOf(
            Regex("""^https://m\.weibo\.cn/(?:profile|u|home).*"""),
            Regex("""^https://weibo\.com/(?:u/|home).*"""),
        )

        signature.contains("juejin") || signature.contains("掘金") -> listOf(
            Regex("""^https://juejin\.cn/?(?:[?#].*)?$"""),
            Regex("""^https://juejin\.cn/user/.*"""),
        )

        else -> emptyList()
    }
}

internal fun manualImportCookieFieldsFor(site: UserSite): List<CookieFieldHint> {
    val signature = site.signature()
    val known = when {
        signature.contains("x.com") || signature.contains("twitter") -> listOf(
            CookieFieldHint(
                name = "auth_token",
                domainHints = listOf(".x.com", "x.com", "api.x.com", ".twitter.com", "twitter.com", "mobile.twitter.com"),
                sameSite = "None",
                httpOnly = true,
                required = true,
                description = "X.com session token",
            ),
            CookieFieldHint(
                name = "ct0",
                domainHints = listOf(".x.com", "x.com", "api.x.com", ".twitter.com", "twitter.com", "mobile.twitter.com"),
                sameSite = "None",
                required = true,
                description = "X.com CSRF token",
            ),
        )

        signature.contains("bilibili") || signature.contains("b站") -> listOf(
            CookieFieldHint(
                name = "SESSDATA",
                domainHints = listOf(".bilibili.com", "www.bilibili.com", "api.bilibili.com", "passport.bilibili.com"),
                httpOnly = true,
                required = true,
                description = "Bilibili session",
            ),
            CookieFieldHint(
                name = "bili_jct",
                domainHints = listOf(".bilibili.com", "www.bilibili.com", "api.bilibili.com"),
                required = false,
                description = "Bilibili CSRF token",
            ),
        )

        signature.contains("zhihu") || signature.contains("知乎") -> listOf(
            CookieFieldHint(
                name = "z_c0",
                domainHints = listOf(".zhihu.com", "www.zhihu.com"),
                httpOnly = true,
                required = true,
                description = "Zhihu session",
            ),
        )

        signature.contains("weibo") || signature.contains("微博") || signature.contains("sina") -> listOf(
            CookieFieldHint(
                name = "SUB",
                domainHints = listOf(".weibo.com", "weibo.com", "m.weibo.cn", ".weibo.cn", "passport.weibo.com", "passport.weibo.cn"),
                httpOnly = true,
                required = true,
                description = "Weibo session",
            ),
            CookieFieldHint(
                name = "SUBP",
                domainHints = listOf(".weibo.com", "weibo.com", ".weibo.cn"),
                required = false,
                description = "Weibo secondary session",
            ),
        )

        signature.contains("juejin") || signature.contains("掘金") -> listOf(
            CookieFieldHint(
                name = "sessionid",
                domainHints = listOf(".juejin.cn", "juejin.cn", "api.juejin.cn"),
                httpOnly = true,
                required = true,
                description = "Juejin session",
            ),
            CookieFieldHint(
                name = "passport_csrf_token",
                domainHints = listOf(".juejin.cn", "juejin.cn"),
                required = false,
                description = "Juejin CSRF token",
            ),
        )

        else -> emptyList()
    }
    val configured = site.loginCookieName
        ?.trim()
        ?.takeIf { it.isNotBlank() && known.none { field -> field.name == it } }
        ?.let { CookieFieldHint(name = it, required = true, description = "Configured login cookie") }
        ?.let { listOf(it) }
        .orEmpty()
    return known + configured
}

private fun UserSite.signature(): String =
    "$id $displayName $homepageUrl".lowercase()
