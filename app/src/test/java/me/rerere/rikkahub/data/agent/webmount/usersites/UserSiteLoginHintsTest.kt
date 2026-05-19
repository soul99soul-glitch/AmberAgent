package me.rerere.rikkahub.data.agent.webmount.usersites

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UserSiteLoginHintsTest {
    @Test
    fun xComRequiresAuthTokenAndCt0AndProbesApiDomain() {
        val site = UserSite(
            id = "x_com",
            displayName = "X.com",
            homepageUrl = "https://x.com/i/flow/login",
            authKind = AuthKind.COOKIE,
            loginCookieName = "auth_token",
        )

        assertTrue(requiredLoginCookieSetsFor(site).contains(setOf("auth_token", "ct0")))
        assertEquals(listOf(setOf("auth_token", "ct0")), requiredLoginCookieSetsFor(site))
        assertTrue(extraLoginProbeUrlsFor(site).contains("https://api.x.com"))
        assertTrue(manualImportCookieFieldsFor(site).map { it.name }.containsAll(listOf("auth_token", "ct0")))
    }

    @Test
    fun knownSiteKeepsExplicitConfiguredCookieUnlessItWeakensKnownSet() {
        val customWeibo = UserSite(
            id = "user_weibo_archive",
            displayName = "Weibo Archive",
            homepageUrl = "https://weibo.com/example",
            authKind = AuthKind.COOKIE,
            loginCookieName = "MY_SESSION",
        )
        val xComSeed = UserSite(
            id = "x_com",
            displayName = "X.com",
            homepageUrl = "https://x.com/i/flow/login",
            authKind = AuthKind.COOKIE,
            loginCookieName = "auth_token",
        )

        assertEquals(
            listOf(setOf("SUB"), setOf("MY_SESSION")),
            requiredLoginCookieSetsFor(customWeibo),
        )
        assertEquals(listOf(setOf("auth_token", "ct0")), requiredLoginCookieSetsFor(xComSeed))
    }

    @Test
    fun knownNativeSitesExposeRequiredCookieSets() {
        val bilibili = site("bilibili", "Bilibili", "https://passport.bilibili.com/login", "SESSDATA")
        val zhihu = site("zhihu", "知乎", "https://www.zhihu.com/signin", "z_c0")
        val weibo = site("weibo", "微博", "https://m.weibo.cn", "SUB")
        val juejin = site("juejin", "掘金", "https://juejin.cn/login", "sessionid")

        assertEquals(listOf(setOf("SESSDATA")), requiredLoginCookieSetsFor(bilibili))
        assertEquals(listOf(setOf("z_c0")), requiredLoginCookieSetsFor(zhihu))
        assertEquals(listOf(setOf("SUB")), requiredLoginCookieSetsFor(weibo))
        assertEquals(listOf(setOf("sessionid")), requiredLoginCookieSetsFor(juejin))
    }

    private fun site(id: String, name: String, url: String, cookie: String): UserSite {
        return UserSite(
            id = id,
            displayName = name,
            homepageUrl = url,
            authKind = AuthKind.COOKIE,
            loginCookieName = cookie,
        )
    }
}
