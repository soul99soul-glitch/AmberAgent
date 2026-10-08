package app.amber.ai.provider.providers.grok

import android.app.Application
import android.content.ContextWrapper
import android.content.Intent
import app.amber.ai.provider.providers.TestAndroidKeyStore
import app.amber.ai.provider.providers.TestContext
import app.amber.ai.provider.providers.tokenResponseClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.net.Socket
import kotlin.uuid.Uuid

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class GrokOAuthSessionTest {
    @Test
    fun `browser fetch callback exchanges and refreshes with the registered client`() = runBlocking {
        val browser = CompletableDeferred<Intent>()
        val context = object : ContextWrapper(TestContext()) {
            override fun startActivity(intent: Intent) { browser.complete(intent) }
        }
        val store = GrokAuthStore(context)
        val providerId = Uuid.random()
        var tokenRequests = 0
        val httpClient = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            assertEquals(GROK_OAUTH_TOKEN_ENDPOINT, request.url.toString())
            val body = request.body as FormBody
            val fields = (0 until body.size).associate { body.name(it) to body.value(it) }
            assertEquals("b1a00492-073a-47ea-816f-4c329264a828", fields["client_id"])
            if (tokenRequests == 0) {
                assertEquals("authorization_code", fields["grant_type"])
                assertEquals("login-code", fields["code"])
                assertEquals(GROK_OAUTH_REDIRECT_URI, fields["redirect_uri"])
                assertTrue(fields["code_verifier"]!!.isNotBlank())
            } else {
                assertEquals("refresh_token", fields["grant_type"])
                assertEquals("refresh-1", fields["refresh_token"])
            }
            tokenRequests++
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"access_token":"access-$tokenRequests","refresh_token":"refresh-$tokenRequests","expires_in":3600}"""
                    .toResponseBody("application/json".toMediaType()))
                .build()
        }.build()
        val client = GrokOAuthClient(httpClient, store)
        val login = async(Dispatchers.Default) { client.authorize(context, providerId) }
        try {
            val url = withTimeout(2_000) { browser.await() }.data!!
            assertEquals("b1a00492-073a-47ea-816f-4c329264a828", url.getQueryParameter("client_id"))
            val state = url.getQueryParameter("state")!!
            val port = android.net.Uri.parse(url.getQueryParameter("redirect_uri")).port
            fun callbackRequest(method: String, headers: String = ""): String =
                Socket("127.0.0.1", port).use { socket ->
                    socket.soTimeout = 2_000
                    socket.getOutputStream().write(
                        ("$method /callback?code=login-code&state=$state HTTP/1.1\r\n" +
                            "Origin: https://accounts.x.ai\r\n$headers\r\n").toByteArray(Charsets.US_ASCII),
                    )
                    socket.getInputStream().readBytes().toString(Charsets.UTF_8)
                }
            val preflight = callbackRequest("OPTIONS", "Access-Control-Request-Method: GET\r\nAccess-Control-Request-Private-Network: true\r\n")
            assertTrue(preflight.contains("Access-Control-Allow-Private-Network: true\r\n"))
            assertTrue(callbackRequest("GET").contains("Access-Control-Allow-Origin: https://accounts.x.ai\r\n"))
            assertEquals("access-1", withTimeout(2_000) { login.await() }.accessToken)
            assertEquals("access-1", store.get(providerId)?.accessToken)
            assertEquals("access-2", client.refresh(providerId).accessToken)
            assertEquals("refresh-2", store.get(providerId)?.refreshToken)
            assertEquals(2, tokenRequests)
        } finally {
            login.cancel()
        }
    }

    @Test
    fun `late successful refresh cannot replace a session created after logout`() = runBlocking {
        val context = TestContext()
        val store = GrokAuthStore(context)
        val providerId = Uuid.random()
        store.save(providerId, expiredTokens("old-access", "old-refresh"))
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val client = GrokOAuthClient(
            tokenResponseClient(
                endpoint = GROK_OAUTH_TOKEN_ENDPOINT,
                entered = entered,
                release = release,
                statusCode = 200,
                responseBody = """{"access_token":"old-refreshed","expires_in":3600}""",
            ),
            store,
        )

        val refresh = async(Dispatchers.Default) { runCatching { client.refresh(providerId) } }
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        client.logout(providerId)
        val current = freshTokens("new-access", "new-refresh")
        store.save(providerId, current)
        release.countDown()

        val failure = withTimeout(2_000) { refresh.await().exceptionOrNull() }
        assertTrue(failure is IllegalStateException)
        assertEquals(current, store.get(providerId))
    }

    @Test
    fun `late invalid grant cannot clear a session created after logout`() = runBlocking {
        val context = TestContext()
        val store = GrokAuthStore(context)
        val providerId = Uuid.random()
        store.save(providerId, expiredTokens("old-access", "old-refresh"))
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val client = GrokOAuthClient(
            tokenResponseClient(
                endpoint = GROK_OAUTH_TOKEN_ENDPOINT,
                entered = entered,
                release = release,
                statusCode = 400,
                responseBody = """{"error":"invalid_grant"}""",
            ),
            store,
        )

        val refresh = async(Dispatchers.Default) { runCatching { client.refresh(providerId) } }
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        client.logout(providerId)
        val current = freshTokens("new-access", "new-refresh")
        store.save(providerId, current)
        release.countDown()

        val failure = withTimeout(2_000) { refresh.await().exceptionOrNull() }
        assertTrue(failure is IllegalStateException)
        assertEquals(current, store.get(providerId))
    }

    @Test
    fun `old refresh started during authorization cannot overwrite successful new login`() =
        assertNewLoginSurvivesLateRefresh(
            200,
            """{"access_token":"old-refreshed","refresh_token":"old-rotated","expires_in":3600}""",
        )

    @Test
    fun `old invalid grant started during authorization cannot clear successful new login`() =
        assertNewLoginSurvivesLateRefresh(400, """{"error":"invalid_grant"}""")

    @Test
    fun `old invalid grant during authorization cannot cancel pending new login`() =
        assertNewLoginSurvivesLateRefresh(400, """{"error":"invalid_grant"}""", refreshBeforeLogin = true)

    private fun assertNewLoginSurvivesLateRefresh(
        statusCode: Int,
        responseBody: String,
        refreshBeforeLogin: Boolean = false,
    ) = runBlocking {
        val browser = CompletableDeferred<Intent>()
        val context = object : ContextWrapper(TestContext()) {
            override fun startActivity(intent: Intent) { browser.complete(intent) }
        }
        val store = GrokAuthStore(context)
        val providerId = Uuid.random()
        store.save(providerId, GrokOAuthTokens("old-access", "old-refresh", expiresAtMillis = 0L))

        val authorizationHttp = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            assertEquals(GROK_OAUTH_TOKEN_ENDPOINT, request.url.toString())
            val body = request.body as FormBody
            val fields = (0 until body.size).associate { body.name(it) to body.value(it) }
            assertEquals("authorization_code", fields["grant_type"])
            assertEquals("new-login-code", fields["code"])
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"access_token":"new-access","refresh_token":"new-refresh","expires_in":3600}"""
                    .toResponseBody("application/json".toMediaType()))
                .build()
        }.build()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val authorizingClient = GrokOAuthClient(authorizationHttp, store)
        val refreshingClient = GrokOAuthClient(
            tokenResponseClient(GROK_OAUTH_TOKEN_ENDPOINT, entered, release, statusCode, responseBody),
            store,
        )
        val login = async(Dispatchers.Default) { authorizingClient.authorize(context, providerId) }
        // Capturing the browser intent establishes that beginAuthorization already advanced the epoch.
        val intent = withTimeout(2_000) { browser.await() }
        val refresh = async(Dispatchers.Default) { runCatching { refreshingClient.refresh(providerId) } }
        try {
            // Interceptor entry establishes that refresh captured the old credentials and new epoch.
            assertTrue("old refresh did not reach the token endpoint", entered.await(2, TimeUnit.SECONDS))
            if (refreshBeforeLogin) {
                release.countDown()
                withTimeout(2_000) { refresh.await() }
                assertEquals(null, store.get(providerId))
            }
            val url = intent.data!!
            val state = url.getQueryParameter("state")!!
            val port = android.net.Uri.parse(url.getQueryParameter("redirect_uri")).port
            Socket("127.0.0.1", port).use { socket ->
                socket.soTimeout = 2_000
                socket.getOutputStream().write(
                    ("GET /callback?code=new-login-code&state=$state HTTP/1.1\r\n" +
                        "Origin: https://accounts.x.ai\r\n\r\n").toByteArray(Charsets.US_ASCII),
                )
                assertTrue(socket.getInputStream().readBytes().toString(Charsets.UTF_8)
                    .startsWith("HTTP/1.1 200 OK"))
            }
            val newLogin = withTimeout(2_000) { login.await() }
            assertEquals("new-access", newLogin.accessToken)
            assertEquals(newLogin, store.get(providerId))

            release.countDown()
            withTimeout(2_000) { refresh.await() }
            assertEquals("late response from old credentials changed the new login", newLogin, store.get(providerId))
        } finally {
            release.countDown()
            login.cancelAndJoin()
            refresh.cancelAndJoin()
        }
    }

    private fun expiredTokens(access: String, refresh: String) =
        GrokOAuthTokens(access, refresh, expiresAtMillis = 0L)

    private fun freshTokens(access: String, refresh: String) =
        GrokOAuthTokens(access, refresh, expiresAtMillis = System.currentTimeMillis() + 60_000L)

    companion object {
        @JvmStatic
        @BeforeClass
        fun installTestKeyStore() = TestAndroidKeyStore.install()

        @JvmStatic
        @AfterClass
        fun restoreTestKeyStore() = TestAndroidKeyStore.restore()
    }
}
