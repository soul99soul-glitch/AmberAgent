package app.amber.ai.provider.providers.openai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.uuid.Uuid

class OpenAIChatGptSiwcOAuthTest {

    private class InMemorySiwcStore : SiwcKeyValueStore {
        private val map = ConcurrentHashMap<String, String>()
        override fun get(key: String): String? = map[key]
        override fun put(key: String, value: String) { map[key] = value }
        override fun remove(key: String) { map.remove(key) }
        override fun exportPlainValues(): Map<String, String> = map.toMap()
        override fun replacePlainValues(values: Map<String, String>) {
            map.clear()
            map.putAll(values)
        }
    }

    private fun generateRsaKeyPair(): KeyPair =
        KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

    private fun rsaJwkJson(kid: String, publicKey: RSAPublicKey): String {
        val n = publicKey.modulus.toByteArray().let { if (it.size > 1 && it[0] == 0.toByte()) it.copyOfRange(1, it.size) else it }
        val e = publicKey.publicExponent.toByteArray().let { if (it.size > 1 && it[0] == 0.toByte()) it.copyOfRange(1, it.size) else it }
        return buildJsonObject {
            put("keys", buildJsonArray {
                add(buildJsonObject {
                    put("kty", "RSA")
                    put("use", "sig")
                    put("alg", "RS256")
                    put("kid", kid)
                    put("n", base64UrlEncode(n))
                    put("e", base64UrlEncode(e))
                })
            })
        }.toString()
    }

    private fun signIdToken(
        keyPair: KeyPair,
        kid: String,
        iss: String = OPENAI_CHATGPT_SIWC_ISSUER,
        aud: String,
        sub: String,
        nonce: String,
        email: String? = "user@example.com",
        expSeconds: Long = System.currentTimeMillis() / 1000L + 3600L,
    ): String {
        val header = base64UrlEncode(
            buildJsonObject {
                put("alg", "RS256")
                put("typ", "JWT")
                put("kid", kid)
            }.toString().toByteArray(Charsets.UTF_8)
        )
        val payload = base64UrlEncode(
            buildJsonObject {
                put("iss", iss)
                put("aud", aud)
                put("sub", sub)
                put("nonce", nonce)
                put("exp", expSeconds)
                email?.let { put("email", it) }
            }.toString().toByteArray(Charsets.UTF_8)
        )
        val signingInput = "$header.$payload"
        val sig = Signature.getInstance("SHA256withRSA").run {
            initSign(keyPair.private)
            update(signingInput.toByteArray(Charsets.US_ASCII))
            sign()
        }
        return "$signingInput.${base64UrlEncode(sig)}"
    }

    private fun jsonResponse(request: Request, code: Int, body: String): Response =
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(if (code in 200..299) "OK" else "Error")
            .body(body.toResponseBody("application/json".toMediaType()))
            .build()

    private fun readFormParams(request: Request): Map<String, String> {
        val buffer = Buffer()
        request.body?.writeTo(buffer)
        return buffer.readUtf8()
            .split('&')
            .filter { it.contains('=') }
            .associate { pair ->
                val idx = pair.indexOf('=')
                java.net.URLDecoder.decode(pair.substring(0, idx), "UTF-8") to
                    java.net.URLDecoder.decode(pair.substring(idx + 1), "UTF-8")
            }
    }

    @Test
    fun hostIdIsPersistentUrnUuidAndAuthUrlsFollowInitialVsReturningRules() {
        val store = OpenAIChatGptSiwcAuthStore(InMemorySiwcStore())
        val hostId1 = store.getOrCreateHostId()
        val hostId2 = store.getOrCreateHostId()
        assertEquals(hostId1, hostId2)
        assertTrue(hostId1.startsWith("urn:uuid:"))

        val client = OpenAIChatGptSiwcOAuthClient(OkHttpClient(), store)
        val initialUrl = client.buildAuthorizationUrl(
            redirectUri = "http://127.0.0.1:43210/auth/callback",
            hostId = hostId1,
            state = "state-1",
            nonce = "nonce-1",
            codeChallenge = "challenge-1",
            existingRecord = null,
        ).toHttpUrl()

        assertEquals(OPENAI_CHATGPT_SIWC_DYNAMIC_CLIENT_ID, initialUrl.queryParameter("client_id"))
        assertEquals(OPENAI_CHATGPT_SIWC_AGENT_NAME_HINT, initialUrl.queryParameter("agent_name_hint"))
        assertEquals(hostId1, initialUrl.queryParameter("ext_agent_host_id"))
        assertEquals("http://127.0.0.1:43210/auth/callback", initialUrl.queryParameter("redirect_uri"))
        assertEquals(OPENAI_CHATGPT_SIWC_API_BASE_URL, initialUrl.queryParameter("resource"))
        assertEquals(OPENAI_CHATGPT_SIWC_SCOPE, initialUrl.queryParameter("scope"))
        assertEquals("S256", initialUrl.queryParameter("code_challenge_method"))
        assertNull(initialUrl.queryParameter("id_token_hint"))
        assertNull(initialUrl.queryParameter("login_hint"))

        val savedRecord = OpenAIChatGptSiwcTokens(
            clientId = "oaiapp_issued_123",
            extAgentHostId = hostId1,
            subject = "user-sub-1",
            email = "alice@example.com",
            idToken = "retained.id.token",
            accessToken = "at-1",
            refreshToken = "rt-1",
            expiresAtMillis = System.currentTimeMillis() + 3600_000L,
            scopes = listOf(OPENAI_CHATGPT_SIWC_DIRECT_USAGE_SCOPE),
        )
        val returningUrl = client.buildAuthorizationUrl(
            redirectUri = "http://127.0.0.1:43211/auth/callback",
            hostId = hostId1,
            state = "state-2",
            nonce = "nonce-2",
            codeChallenge = "challenge-2",
            existingRecord = savedRecord,
        ).toHttpUrl()

        assertEquals("oaiapp_issued_123", returningUrl.queryParameter("client_id"))
        assertNull("Returning reauth must omit agent_name_hint", returningUrl.queryParameter("agent_name_hint"))
        assertEquals(hostId1, returningUrl.queryParameter("ext_agent_host_id"))
        assertEquals("retained.id.token", returningUrl.queryParameter("id_token_hint"))
        assertEquals("alice@example.com", returningUrl.queryParameter("login_hint"))
    }

    @Test
    fun endToEndLoopbackAuthorizationExchangesTokenValidatesJwksAndPersistsIssuedClientId() = runBlocking {
        val keyPair = generateRsaKeyPair()
        val kid = "siwc-key-1"
        val issuedClientId = "oaiapp_abc123"
        val tokenForms = CopyOnWriteArrayList<Map<String, String>>()
        val capturedNonce = AtomicReferenceCompat<String>()
        val capturedRedirectUri = AtomicReferenceCompat<String>()

        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            when (request.url.encodedPath) {
                "/.well-known/openid-configuration" -> jsonResponse(
                    request,
                    200,
                    """{"issuer":"$OPENAI_CHATGPT_SIWC_ISSUER","jwks_uri":"$OPENAI_CHATGPT_SIWC_DEFAULT_JWKS_URL","revocation_endpoint":"$OPENAI_CHATGPT_SIWC_ISSUER/api/accounts/oauth/revoke"}""",
                )
                "/.well-known/jwks.json" -> jsonResponse(request, 200, rsaJwkJson(kid, keyPair.public as RSAPublicKey))
                "/api/accounts/oauth/token" -> {
                    val form = readFormParams(request)
                    tokenForms += form
                    val idToken = signIdToken(
                        keyPair = keyPair,
                        kid = kid,
                        aud = issuedClientId,
                        sub = "sub_user_42",
                        nonce = capturedNonce.value!!,
                        email = "amber@example.com",
                    )
                    jsonResponse(
                        request,
                        200,
                        """{"access_token":"siwc-at-1","refresh_token":"siwc-rt-1","id_token":"$idToken","expires_in":3600,"scope":"$OPENAI_CHATGPT_SIWC_SCOPE"}""",
                    )
                }
                else -> error("Unexpected request: ${request.url}")
            }
        }.build()

        val store = OpenAIChatGptSiwcAuthStore(InMemorySiwcStore())
        val client = OpenAIChatGptSiwcOAuthClient(http, store)
        val providerId = Uuid.random()

        val tokens = withTimeout(10_000) {
            client.authorize(
                providerId = providerId,
                openBrowser = { authUrlString ->
                    val authUrl = authUrlString.toHttpUrl()
                    val redirectUri = authUrl.queryParameter("redirect_uri")!!
                    val state = authUrl.queryParameter("state")!!
                    val nonce = authUrl.queryParameter("nonce")!!
                    capturedNonce.value = nonce
                    capturedRedirectUri.value = redirectUri
                    assertTrue(redirectUri.startsWith("http://127.0.0.1:"))
                    assertTrue(redirectUri.endsWith("/auth/callback"))
                    assertFalse(redirectUri.contains("localhost"))

                    // Fire non-callback /favicon.ico first (must be ignored), then actual callback
                    launch(Dispatchers.IO) {
                        val base = redirectUri.substringBefore("/auth/callback")
                        (URL("$base/favicon.ico").openConnection() as HttpURLConnection).run {
                            assertEquals(404, responseCode)
                            disconnect()
                        }
                        val cbUrl = "$redirectUri?code=auth-code-99&state=${URLEncoder.encode(state, "UTF-8")}&client_id=$issuedClientId"
                        (URL(cbUrl).openConnection() as HttpURLConnection).run {
                            assertEquals(200, responseCode)
                            val html = inputStream.bufferedReader().readText()
                            assertTrue(html.contains("Sign-in received"))
                            disconnect()
                        }
                    }
                },
            )
        }

        assertEquals(issuedClientId, tokens.clientId)
        assertEquals("sub_user_42", tokens.subject)
        assertEquals("amber@example.com", tokens.email)
        assertEquals("siwc-at-1", tokens.accessToken)
        assertEquals("siwc-rt-1", tokens.refreshToken)
        assertTrue(tokens.hasDirectUsageScope)
        assertFalse(tokens.welcomeShown)

        val exchangeForm = tokenForms.single()
        assertEquals("authorization_code", exchangeForm["grant_type"])
        assertEquals(issuedClientId, exchangeForm["client_id"])
        assertEquals("auth-code-99", exchangeForm["code"])
        assertEquals(capturedRedirectUri.value, exchangeForm["redirect_uri"])
        assertEquals(OPENAI_CHATGPT_SIWC_API_BASE_URL, exchangeForm["resource"])
        assertFalse(exchangeForm["code_verifier"].isNullOrBlank())

        store.markWelcomeShown(providerId)
        assertTrue(store.get(providerId)!!.welcomeShown)
    }

    @Test
    fun idTokenValidatorRejectsTamperedSignatureWrongNonceAudienceIssuerAndExpiredToken() = runBlocking {
        val keyPair = generateRsaKeyPair()
        val otherKeyPair = generateRsaKeyPair()
        val kid = "k1"
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            when (request.url.encodedPath) {
                "/.well-known/openid-configuration" -> jsonResponse(
                    request,
                    200,
                    """{"issuer":"$OPENAI_CHATGPT_SIWC_ISSUER","jwks_uri":"$OPENAI_CHATGPT_SIWC_DEFAULT_JWKS_URL"}""",
                )
                "/.well-known/jwks.json" -> jsonResponse(request, 200, rsaJwkJson(kid, keyPair.public as RSAPublicKey))
                else -> error("Unexpected: ${request.url}")
            }
        }.build()
        val validator = OpenAIChatGptSiwcIdTokenValidator(http)

        // Valid token passes
        val valid = signIdToken(keyPair, kid, aud = "oaiapp_1", sub = "sub_1", nonce = "n1")
        assertEquals("sub_1", validator.validate(valid, "oaiapp_1", "n1").subject)

        // Wrong key signature fails
        val wrongSig = signIdToken(otherKeyPair, kid, aud = "oaiapp_1", sub = "sub_1", nonce = "n1")
        assertTrue(runCatching { validator.validate(wrongSig, "oaiapp_1", "n1") }.isFailure)

        // Wrong nonce fails
        assertTrue(runCatching { validator.validate(valid, "oaiapp_1", "wrong-nonce") }.isFailure)

        // Wrong audience fails
        assertTrue(runCatching { validator.validate(valid, "oaiapp_other", "n1") }.isFailure)

        // Wrong issuer fails
        val wrongIss = signIdToken(keyPair, kid, iss = "https://evil.example", aud = "oaiapp_1", sub = "sub_1", nonce = "n1")
        assertTrue(runCatching { validator.validate(wrongIss, "oaiapp_1", "n1") }.isFailure)

        // Expired token fails
        val expired = signIdToken(keyPair, kid, aud = "oaiapp_1", sub = "sub_1", nonce = "n1", expSeconds = 1_000L)
        assertTrue(runCatching { validator.validate(expired, "oaiapp_1", "n1") }.isFailure)

        // Subject mismatch on returning sign-in fails
        assertTrue(runCatching { validator.validate(valid, "oaiapp_1", "n1", expectedSubject = "sub_other") }.isFailure)
    }

    @Test
    fun authorizeRejectsMissingDirectUsageScope() = runBlocking {
        val keyPair = generateRsaKeyPair()
        val kid = "k-scope"
        val capturedNonce = AtomicReferenceCompat<String>()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            when (request.url.encodedPath) {
                "/.well-known/openid-configuration" -> jsonResponse(
                    request, 200, """{"issuer":"$OPENAI_CHATGPT_SIWC_ISSUER","jwks_uri":"$OPENAI_CHATGPT_SIWC_DEFAULT_JWKS_URL"}""",
                )
                "/.well-known/jwks.json" -> jsonResponse(request, 200, rsaJwkJson(kid, keyPair.public as RSAPublicKey))
                "/api/accounts/oauth/token" -> {
                    val idToken = signIdToken(keyPair, kid, aud = "oaiapp_scope", sub = "sub_1", nonce = capturedNonce.value!!)
                    jsonResponse(
                        request,
                        200,
                        """{"access_token":"at","refresh_token":"rt","id_token":"$idToken","expires_in":3600,"scope":"openid profile email"}""",
                    )
                }
                else -> error("Unexpected: ${request.url}")
            }
        }.build()
        val store = OpenAIChatGptSiwcAuthStore(InMemorySiwcStore())
        val client = OpenAIChatGptSiwcOAuthClient(http, store)
        val providerId = Uuid.random()

        val failure = runCatching {
            withTimeout(10_000) {
                client.authorize(providerId, openBrowser = { authUrlString ->
                    val url = authUrlString.toHttpUrl()
                    capturedNonce.value = url.queryParameter("nonce")!!
                    val redirectUri = url.queryParameter("redirect_uri")!!
                    val state = url.queryParameter("state")!!
                    launch(Dispatchers.IO) {
                        val cb = "$redirectUri?code=c1&state=${URLEncoder.encode(state, "UTF-8")}&client_id=oaiapp_scope"
                        (URL(cb).openConnection() as HttpURLConnection).run { responseCode; disconnect() }
                    }
                })
            }
        }.exceptionOrNull()

        assertNotNull(failure)
        assertTrue(failure!!.message!!.contains(OPENAI_CHATGPT_SIWC_DIRECT_USAGE_SCOPE))
        assertNull(store.get(providerId))
    }

    @Test
    fun refreshRotatesTokenClearsOnInvalidGrantAndPreservesClientIdOnSignOut() = runBlocking {
        val forms = CopyOnWriteArrayList<Map<String, String>>()
        val revoked = CopyOnWriteArrayList<Map<String, String>>()
        var failWithInvalidGrant = false
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            when (request.url.encodedPath) {
                "/.well-known/openid-configuration" -> jsonResponse(
                    request, 200,
                    """{"issuer":"$OPENAI_CHATGPT_SIWC_ISSUER","jwks_uri":"$OPENAI_CHATGPT_SIWC_DEFAULT_JWKS_URL","revocation_endpoint":"$OPENAI_CHATGPT_SIWC_ISSUER/api/accounts/oauth/revoke"}""",
                )
                "/api/accounts/oauth/token" -> {
                    forms += readFormParams(request)
                    if (failWithInvalidGrant) {
                        jsonResponse(request, 400, """{"error":"invalid_grant","error_description":"revoked"}""")
                    } else {
                        jsonResponse(
                            request,
                            200,
                            """{"access_token":"at-rotated","refresh_token":"rt-rotated","expires_in":3600,"scope":"$OPENAI_CHATGPT_SIWC_SCOPE"}""",
                        )
                    }
                }
                "/api/accounts/oauth/revoke" -> {
                    revoked += readFormParams(request)
                    jsonResponse(request, 200, "")
                }
                else -> error("Unexpected: ${request.url}")
            }
        }.build()

        val store = OpenAIChatGptSiwcAuthStore(InMemorySiwcStore())
        val hostId = store.getOrCreateHostId()
        val providerId = Uuid.random()
        store.save(
            providerId,
            OpenAIChatGptSiwcTokens(
                clientId = "oaiapp_keep",
                extAgentHostId = hostId,
                subject = "sub_keep",
                email = "keep@example.com",
                idToken = "id-1",
                accessToken = "at-old",
                refreshToken = "rt-old",
                expiresAtMillis = 0L,
                scopes = listOf(OPENAI_CHATGPT_SIWC_DIRECT_USAGE_SCOPE),
                welcomeShown = true,
            ),
        )
        val client = OpenAIChatGptSiwcOAuthClient(http, store)

        // Rotating refresh
        val access = client.getValidAccessToken(providerId)
        assertEquals("at-rotated", access)
        assertEquals("rt-rotated", store.get(providerId)!!.refreshToken)
        assertEquals("oaiapp_keep", forms.single()["client_id"])
        assertEquals(OPENAI_CHATGPT_SIWC_API_BASE_URL, forms.single()["resource"])
        assertNull("Refresh must omit scope to retain grant", forms.single()["scope"])

        // Sign-out revokes remotely and clears credentials while retaining client_id + host_id
        assertTrue(client.signOut(providerId))
        assertEquals("rt-rotated", revoked.single()["token"])
        assertEquals("refresh_token", revoked.single()["token_type_hint"])
        assertEquals("oaiapp_keep", revoked.single()["client_id"])
        assertNull(store.get(providerId))
        val retained = store.getRecord(providerId)!!
        assertEquals("oaiapp_keep", retained.clientId)
        assertEquals("sub_keep", retained.subject)
        assertNull("id_token_hint must be omitted after sign-out", retained.idToken)
        assertEquals(hostId, store.getOrCreateHostId())

        // Invalid grant clears tokens while keeping issued client_id
        store.save(
            providerId,
            retained.copy(
                accessToken = "at-2",
                refreshToken = "rt-2",
                expiresAtMillis = 0L,
                scopes = listOf(OPENAI_CHATGPT_SIWC_DIRECT_USAGE_SCOPE),
            ),
        )
        failWithInvalidGrant = true
        val refreshErr = runCatching { client.refresh(providerId) }.exceptionOrNull()
        assertNotNull(refreshErr)
        assertTrue(refreshErr!!.message!!.contains("invalid_grant"))
        assertNull(store.get(providerId))
        assertEquals("oaiapp_keep", store.getRecord(providerId)!!.clientId)
    }

    @Test
    fun lateRefreshCannotResurrectSessionAfterLogout() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            entered.countDown()
            release.await(3, TimeUnit.SECONDS)
            jsonResponse(
                chain.request(),
                200,
                """{"access_token":"zombie-at","refresh_token":"zombie-rt","expires_in":3600,"scope":"$OPENAI_CHATGPT_SIWC_SCOPE"}""",
            )
        }.build()

        val store = OpenAIChatGptSiwcAuthStore(InMemorySiwcStore())
        val providerId = Uuid.random()
        store.save(
            providerId,
            OpenAIChatGptSiwcTokens(
                clientId = "oaiapp_race",
                extAgentHostId = store.getOrCreateHostId(),
                accessToken = "at-1",
                refreshToken = "rt-1",
                expiresAtMillis = 0L,
                scopes = listOf(OPENAI_CHATGPT_SIWC_DIRECT_USAGE_SCOPE),
            ),
        )
        val client = OpenAIChatGptSiwcOAuthClient(http, store)
        val deferred = async(Dispatchers.Default) { runCatching { client.refresh(providerId) } }
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            client.logout(providerId)
        } finally {
            release.countDown()
        }
        withTimeout(2_000) { deferred.await() }
        assertNull(store.get(providerId))
    }

    @Test
    fun listModelsFiltersVisibilityListPreservesOrderAndMapsSlugAndDisplayName() = runBlocking {
        val authHeaders = CopyOnWriteArrayList<String?>()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            when (request.url.encodedPath) {
                "/v1/models" -> {
                    authHeaders += request.header("Authorization")
                    if (authHeaders.size == 1) {
                        jsonResponse(request, 401, """{"detail":"expired"}""")
                    } else {
                        jsonResponse(
                            request,
                            200,
                            """
                            {
                              "models": [
                                {"slug": "gpt-6.2-sol", "display_name": "GPT-6.2 Sol", "visibility": "list"},
                                {"slug": "internal-hidden", "display_name": "Hidden Model", "visibility": "hide"},
                                {"slug": "gpt-5.4-mini", "display_name": "GPT-5.4 Mini", "visibility": "list"},
                                {"slug": "alpha-model", "display_name": "Alpha Model", "visibility": "list"}
                              ]
                            }
                            """.trimIndent(),
                        )
                    }
                }
                "/api/accounts/oauth/token" -> jsonResponse(
                    request,
                    200,
                    """{"access_token":"at-fresh","refresh_token":"rt-fresh","expires_in":3600,"scope":"$OPENAI_CHATGPT_SIWC_SCOPE"}""",
                )
                else -> error("Unexpected: ${request.url}")
            }
        }.build()

        val store = OpenAIChatGptSiwcAuthStore(InMemorySiwcStore())
        val providerId = Uuid.random()
        store.save(
            providerId,
            OpenAIChatGptSiwcTokens(
                clientId = "oaiapp_models",
                extAgentHostId = store.getOrCreateHostId(),
                accessToken = "at-stale",
                refreshToken = "rt-old",
                expiresAtMillis = System.currentTimeMillis() + 3600_000L,
                scopes = listOf(OPENAI_CHATGPT_SIWC_DIRECT_USAGE_SCOPE),
            ),
        )
        val client = OpenAIChatGptSiwcOAuthClient(http, store)

        val models = client.listModels(providerId)

        assertEquals(listOf("Bearer at-stale", "Bearer at-fresh"), authHeaders.toList())
        // Preserves exact server order (NOT alphabetical: gpt-6.2-sol -> gpt-5.4-mini -> alpha-model)
        assertEquals(listOf("gpt-6.2-sol", "gpt-5.4-mini", "alpha-model"), models.map { it.modelId })
        assertEquals(listOf("GPT-6.2 Sol", "GPT-5.4 Mini", "Alpha Model"), models.map { it.displayName })
        assertNotEquals(models.map { it.modelId }.sorted(), models.map { it.modelId })
    }

    private class AtomicReferenceCompat<T>(@Volatile var value: T? = null)
}
