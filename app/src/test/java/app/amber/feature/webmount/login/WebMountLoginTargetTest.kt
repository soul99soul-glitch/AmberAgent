package app.amber.feature.webmount.login

import android.app.Application
import android.content.Context
import app.amber.feature.runtime.AgentToolActivityStore
import app.amber.feature.webmount.cookie.WebMountCookieProvider
import app.amber.feature.webmount.core.WebMountManager
import app.amber.feature.webmount.profile.ProfileRegistry
import app.amber.feature.webmount.profile.SiteProfile
import app.amber.feature.webmount.profile.ProfileHints
import app.amber.feature.webmount.usersites.AuthKind
import app.amber.feature.webmount.usersites.UserSite
import app.amber.core.infra.AppScope
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WebMountLoginTargetTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Test
    fun synthesizedProfileCookieCompletesUserSiteTarget() {
        val profile = SiteProfile(
            id = "user_example",
            name = "Example",
            origins = listOf("https://example.com"),
            hints = ProfileHints(loginCookie = "sid"),
            permissions = listOf("read_cookie:sid", "detect_login"),
        )
        val rawProfile = Json.encodeToString(SiteProfile.serializer(), profile)
        val profiles = ProfileRegistry(
            context = context,
            builtInLoader = object : ProfileRegistry.BuiltInLoader {
                override fun load(): List<Pair<String, String>> =
                    listOf(rawProfile to ProfileRegistry.sha256Hex(rawProfile))
            },
        )
        val manager = WebMountManager(
            context = context,
            adapters = emptyList(),
            cookieProvider = WebMountCookieProvider(),
            activityStore = AgentToolActivityStore(),
            appScope = AppScope(),
        )
        val site = UserSite(
            id = "user_example",
            displayName = "Example",
            homepageUrl = "https://example.com",
            authKind = AuthKind.COOKIE,
        )

        val target = WebMountLoginTarget.fromUserSite(site, manager, profiles)

        assertEquals(listOf(setOf("sid")), target.requiredCookieSets)
        assertTrue(target.candidateCookieNames.contains("sid"))
        assertTrue(target.manualCookieFields.any { it.name == "sid" })
    }
}
