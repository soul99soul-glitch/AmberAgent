package app.amber.ai.provider.providers.google

import android.app.Application
import app.amber.ai.provider.providers.TestAndroidKeyStore
import app.amber.ai.provider.providers.TestContext
import app.amber.ai.provider.providers.tokenResponseClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
import kotlin.uuid.Uuid

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class AntigravityOAuthSessionTest {
    @Test
    fun `late successful refresh cannot replace a session created after logout`() = runBlocking {
        val context = TestContext()
        val store = AntigravityAuthStore(context)
        val providerId = Uuid.random()
        store.save(providerId, expiredTokens("old-access", "old-refresh"))
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val client = AntigravityOAuthClient(
            tokenResponseClient(
                endpoint = ANTIGRAVITY_OAUTH_TOKEN_ENDPOINT,
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
        val store = AntigravityAuthStore(context)
        val providerId = Uuid.random()
        store.save(providerId, expiredTokens("old-access", "old-refresh"))
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val client = AntigravityOAuthClient(
            tokenResponseClient(
                endpoint = ANTIGRAVITY_OAUTH_TOKEN_ENDPOINT,
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

    private fun expiredTokens(access: String, refresh: String) =
        AntigravityOAuthTokens(access, refresh, expiresAtMillis = 0L)

    private fun freshTokens(access: String, refresh: String) =
        AntigravityOAuthTokens(access, refresh, expiresAtMillis = System.currentTimeMillis() + 60_000L)

    companion object {
        @JvmStatic
        @BeforeClass
        fun installTestKeyStore() = TestAndroidKeyStore.install()

        @JvmStatic
        @AfterClass
        fun restoreTestKeyStore() = TestAndroidKeyStore.restore()
    }
}
