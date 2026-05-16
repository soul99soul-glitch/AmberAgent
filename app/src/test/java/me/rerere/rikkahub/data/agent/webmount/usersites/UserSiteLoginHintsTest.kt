package me.rerere.rikkahub.data.agent.webmount.usersites

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UserSiteLoginHintsTest {
    @Test
    fun xComSiteAddsDurableLoginCookieCandidates() {
        val site = UserSite(
            id = "x_com",
            displayName = "X.com",
            homepageUrl = "https://x.com/i/flow/login",
            authKind = AuthKind.COOKIE,
            loginCookieName = "auth_token",
        )

        assertEquals(
            listOf("auth_token", "ct0", "twid"),
            loginCookieCandidatesFor(site),
        )
    }

    @Test
    fun xComSiteAddsSiblingLoginProbeUrls() {
        val site = UserSite(
            id = "x_com",
            displayName = "Twitter",
            homepageUrl = "https://x.com/i/flow/login",
            authKind = AuthKind.COOKIE,
        )

        val urls = extraLoginProbeUrlsFor(site)

        assertTrue(urls.contains("https://x.com"))
        assertTrue(urls.contains("https://twitter.com"))
        assertTrue(urls.contains("https://mobile.twitter.com"))
    }

    @Test
    fun weiboSiteAddsDurableLoginCookieCandidates() {
        val site = UserSite(
            id = "user_weibo",
            displayName = "新浪微博",
            homepageUrl = "https://m.weibo.cn/",
            authKind = AuthKind.COOKIE,
            loginCookieName = "custom_cookie",
        )

        assertEquals(
            listOf("custom_cookie", "SUB", "SUBP", "SSOLoginState", "ALF", "MLOGIN"),
            loginCookieCandidatesFor(site),
        )
    }

    @Test
    fun weiboSiteAddsSiblingLoginProbeUrls() {
        val site = UserSite(
            id = "user_weibo",
            displayName = "Weibo",
            homepageUrl = "https://weibo.com/login.php",
            authKind = AuthKind.COOKIE,
        )

        val urls = extraLoginProbeUrlsFor(site)

        assertTrue(urls.contains("https://m.weibo.cn"))
        assertTrue(urls.contains("https://passport.weibo.cn"))
        assertTrue(urls.contains("https://login.sina.com.cn"))
    }

    @Test
    fun seedAutoAddSignatureDeduplicatesCustomWeiboAndXComSites() {
        assertEquals(
            "weibo",
            UserSite(
                id = "user_weibo",
                displayName = "新浪微博",
                homepageUrl = "https://m.weibo.cn",
                authKind = AuthKind.COOKIE,
            ).seedAutoAddSignature(),
        )
        assertEquals(
            "x_com",
            UserSite(
                id = "user_twitter",
                displayName = "Twitter",
                homepageUrl = "https://twitter.com/home",
                authKind = AuthKind.COOKIE,
            ).seedAutoAddSignature(),
        )
    }

    @Test
    fun feishuDocsAddsWikiLoginProbeUrls() {
        val site = UserSite(
            id = "feishu_docs",
            displayName = "飞书云文档",
            homepageUrl = "https://www.feishu.cn/wiki",
            authKind = AuthKind.OAUTH,
        )

        val urls = extraLoginProbeUrlsFor(site)

        assertTrue(urls.contains("https://www.feishu.cn/wiki"))
        assertTrue(urls.contains("https://feishu.cn/wiki"))
        assertTrue(urls.contains("https://accounts.feishu.cn"))
    }
}
