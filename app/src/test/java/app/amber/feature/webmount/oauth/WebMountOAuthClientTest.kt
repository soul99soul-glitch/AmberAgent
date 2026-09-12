package app.amber.feature.webmount.oauth

import android.app.Application
import android.content.Context
import android.net.Uri
import app.amber.core.infra.AppScope
import app.amber.feature.webmount.core.WebMountOAuthToken
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WebMountOAuthClientTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Test
    fun secureStoresInitializeOffCallerThreadAndImmediateCallbackStillResumes() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val callerThread = Thread.currentThread()
        val appScope = AppScope()
        val http = HttpClient(MockEngine { error("Fake provider must not call HTTP") })
        val providerId = "startup-test-${System.nanoTime()}"
        val state = "state-${System.nanoTime()}"
        val tokenStoreReady = CompletableDeferred<WebMountOAuthTokenStore>()
        val pendingStoreReady = CompletableDeferred<PendingOAuthStore>()
        val initializationThreads = java.util.concurrent.ConcurrentLinkedQueue<Thread>()
        try {
            val dispatcher = OAuthCallbackDispatcher()
            val client = WebMountOAuthClient(
                context = context,
                storeProvider = {
                    initializationThreads.add(Thread.currentThread())
                    WebMountOAuthTokenStore(context).also {
                        it.putCredentials(providerId, OAuthAppCredentials(providerId, "app-id"))
                        tokenStoreReady.complete(it)
                    }
                },
                pendingStoreProvider = {
                    initializationThreads.add(Thread.currentThread())
                    PendingOAuthStore(context).also {
                        it.put(PendingOAuthEntry(state, providerId, "verifier", System.currentTimeMillis()))
                        pendingStoreReady.complete(it)
                    }
                },
                dispatcher = dispatcher,
                http = http,
                appScope = appScope,
            )
            client.register(object : OAuthProvider by ConcurrentRefreshProvider(providerId) {
                override suspend fun exchangeCode(
                    credentials: OAuthAppCredentials,
                    code: String,
                    codeVerifier: String,
                    http: HttpClient,
                    errorCopy: OAuthProviderErrorCopy,
                ): WebMountOAuthToken {
                    assertEquals("code", code)
                    assertEquals("verifier", codeVerifier)
                    return expiredToken(providerId).copy(accessToken = "resumed-access")
                }
            })

            withTimeout(5_000) {
                val tokenStore = tokenStoreReady.await()
                val pendingStore = pendingStoreReady.await()
                val update = async(start = CoroutineStart.UNDISPATCHED) {
                    tokenStore.updates.first { it == providerId }
                }
                assertTrue(dispatcher.dispatch(Uri.parse("amberagent://oauth/$providerId?state=$state&code=code")))
                update.await()
                assertEquals("resumed-access", tokenStore.getToken(providerId)?.accessToken)
                assertNull(pendingStore.peek(state))
            }
            assertEquals(2, initializationThreads.size)
            assertTrue(initializationThreads.all { it !== callerThread })
        } finally {
            http.close()
            appScope.cancel()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun concurrentAccessTokenRequestsRefreshOnce() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val appScope = AppScope()
        val http = HttpClient(MockEngine { error("HTTP should not be called by this fake provider") })
        try {
            val provider = ConcurrentRefreshProvider("refresh-test-${System.nanoTime()}")
            val pendingStore = PendingOAuthStore(context)
            val tokenStore = WebMountOAuthTokenStore(context)
            val client = WebMountOAuthClient(
                context = context,
                storeProvider = { tokenStore },
                pendingStoreProvider = { pendingStore },
                dispatcher = OAuthCallbackDispatcher(),
                http = http,
                appScope = appScope,
            ).apply { register(provider) }
            tokenStore.putCredentials(
                provider.id,
                OAuthAppCredentials(provider = provider.id, appId = "app-id"),
            )
            tokenStore.putToken(provider.id, expiredToken(provider.id))

            val requests = listOf(
                async { client.getValidAccessToken(provider.id) },
                async { client.getValidAccessToken(provider.id) },
            )
            withTimeout(1_000) { provider.refreshStarted.await() }
            provider.allowRefresh.complete(Unit)

            val results = requests.map { it.await() }
            assertEquals(listOf("fresh-access", "fresh-access"), results)
            assertEquals(1, provider.refreshes.get())
        } finally {
            http.close()
            appScope.cancel()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun disconnectDuringRefreshDiscardsLateToken() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val appScope = AppScope()
        val http = HttpClient(MockEngine { error("HTTP should not be called by this fake provider") })
        try {
            val provider = ConcurrentRefreshProvider("disconnect-test-${System.nanoTime()}")
            val pendingStore = PendingOAuthStore(context)
            val tokenStore = WebMountOAuthTokenStore(context)
            val client = WebMountOAuthClient(
                context = context,
                storeProvider = { tokenStore },
                pendingStoreProvider = { pendingStore },
                dispatcher = OAuthCallbackDispatcher(),
                http = http,
                appScope = appScope,
            ).apply { register(provider) }
            tokenStore.putCredentials(
                provider.id,
                OAuthAppCredentials(provider = provider.id, appId = "app-id"),
            )
            tokenStore.putToken(provider.id, expiredToken(provider.id))

            val request = async { client.getValidAccessToken(provider.id) }
            withTimeout(1_000) { provider.refreshStarted.await() }
            client.disconnect(provider.id)
            provider.allowRefresh.complete(Unit)

            assertNull(request.await())
            assertNull(tokenStore.getToken(provider.id))
            assertEquals(1, provider.refreshes.get())
        } finally {
            http.close()
            appScope.cancel()
            Dispatchers.resetMain()
        }
    }

    private fun expiredToken(provider: String): WebMountOAuthToken = WebMountOAuthToken(
        provider = provider,
        accessToken = "expired-access",
        refreshToken = "refresh-token",
        scope = null,
        expiresAtMs = 0L,
        acquiredAtMs = 0L,
    )

    private class ConcurrentRefreshProvider(
        override val id: String,
    ) : OAuthProvider {
        override val displayName: String = "Concurrent refresh test provider"
        val refreshes = AtomicInteger()
        val refreshStarted = CompletableDeferred<Unit>()
        val allowRefresh = CompletableDeferred<Unit>()

        override fun buildAuthorizationUrl(
            credentials: OAuthAppCredentials,
            state: String,
            codeChallenge: String,
        ): String = "amberagent://oauth/$id"

        override suspend fun exchangeCode(
            credentials: OAuthAppCredentials,
            code: String,
            codeVerifier: String,
            http: HttpClient,
            errorCopy: OAuthProviderErrorCopy,
        ): WebMountOAuthToken = error("unused")

        override suspend fun refresh(
            credentials: OAuthAppCredentials,
            refreshToken: String,
            http: HttpClient,
            errorCopy: OAuthProviderErrorCopy,
        ): WebMountOAuthToken {
            refreshes.incrementAndGet()
            refreshStarted.complete(Unit)
            allowRefresh.await()
            val now = System.currentTimeMillis()
            return WebMountOAuthToken(
                provider = id,
                accessToken = "fresh-access",
                refreshToken = refreshToken,
                scope = null,
                expiresAtMs = now + 3_600_000L,
                acquiredAtMs = now,
            )
        }
    }
}
