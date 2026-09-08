package app.amber.feature.webmount.cookie

import android.app.Application
import android.webkit.CookieManager
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WebMountCookieProviderHttpTest {

    @Test
    fun importIntoHttpSiteKeepsCookieAvailableToHttpRequests() {
        val url = "http://webmount-http-${System.nanoTime()}.example/"

        WebMountCookieProvider().injectCookies(
            urls = listOf(url),
            cookies = mapOf("sid" to "http-session"),
            fieldHints = emptyList(),
        )

        assertEquals("sid=http-session", CookieManager.getInstance().getCookie(url))
    }
}
