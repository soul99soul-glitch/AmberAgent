package app.amber.feature.webmount.usersites

import android.app.Application
import android.content.Context
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class UserSiteRegistryTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Test
    fun distinctChineseSitesCanBeAddedAndAnonymousChoiceSurvivesReload() {
        val registry = UserSiteRegistry(context)
        val first = UserSite(userSiteId("豆瓣"), "豆瓣", "https://www.douban.com", AuthKind.ANONYMOUS)
        val second = UserSite(userSiteId("简书"), "简书", "https://www.jianshu.com", AuthKind.ANONYMOUS)

        assertNotEquals(first.id, second.id)
        assertTrue(first.id.matches(Regex("[a-z0-9_]+")))
        assertEquals(first.id, userSiteId(" 豆瓣 "))
        assertTrue(registry.add(first))
        assertTrue(registry.add(second))

        val reloaded = UserSiteRegistry(context)
        assertEquals(AuthKind.ANONYMOUS, reloaded.byId(first.id)?.authKind)
        assertEquals(AuthKind.ANONYMOUS, reloaded.byId(second.id)?.authKind)
    }

    @Test
    fun legacyAnonymousMigrationRunsOnce() {
        val site = UserSite("user_old", "Old site", "https://example.com", AuthKind.ANONYMOUS)
        context.getSharedPreferences("amberagent_webmount_user_sites", Context.MODE_PRIVATE)
            .edit()
            .putString("sites", Json.encodeToString(ListSerializer(UserSite.serializer()), listOf(site)))
            .putBoolean("seeded", true)
            .putInt("seed_version", 1)
            .commit()

        val migrated = UserSiteRegistry(context)
        assertEquals(AuthKind.COOKIE, migrated.byId(site.id)?.authKind)
        migrated.update(site.id) { it.copy(authKind = AuthKind.ANONYMOUS) }
        assertEquals(AuthKind.ANONYMOUS, UserSiteRegistry(context).byId(site.id)?.authKind)
    }
}
