package me.rerere.rikkahub.data.agent.webmount.usersites

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
        signature.contains("weibo") || signature.contains("微博") || signature.contains("sina") ->
            listOf("SUB", "SUBP", "SSOLoginState", "ALF", "MLOGIN")

        else -> emptyList()
    }
}

internal fun extraLoginProbeUrlsFor(site: UserSite): List<String> {
    val signature = site.signature()
    return when {
        signature.contains("weibo") || signature.contains("微博") || signature.contains("sina") -> listOf(
            "https://weibo.com",
            "https://m.weibo.cn",
            "https://passport.weibo.cn",
            "https://passport.weibo.com",
            "https://login.sina.com.cn",
            "https://sina.com.cn",
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

private fun UserSite.signature(): String =
    "$id $displayName $homepageUrl".lowercase()
