package app.amber.ai.provider.providers.openai

import android.content.Context
import app.amber.ai.provider.Model
import app.amber.ai.provider.providers.OAuthTokenSecureStore
import app.amber.ai.registry.ModelRegistry
import app.amber.ai.util.json
import app.amber.common.http.await
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStream
import java.math.BigInteger
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.security.spec.RSAPublicKeySpec
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.uuid.Uuid

const val OPENAI_CHATGPT_SIWC_ISSUER = "https://auth.openai.com"
const val OPENAI_CHATGPT_SIWC_AUTHORIZE_URL = "$OPENAI_CHATGPT_SIWC_ISSUER/api/accounts/authorize"
const val OPENAI_CHATGPT_SIWC_TOKEN_URL = "$OPENAI_CHATGPT_SIWC_ISSUER/api/accounts/oauth/token"
const val OPENAI_CHATGPT_SIWC_OIDC_CONFIG_URL = "$OPENAI_CHATGPT_SIWC_ISSUER/.well-known/openid-configuration"
const val OPENAI_CHATGPT_SIWC_DEFAULT_JWKS_URL = "$OPENAI_CHATGPT_SIWC_ISSUER/.well-known/jwks.json"
const val OPENAI_CHATGPT_SIWC_API_BASE_URL = "https://api.openai.com/v1"
const val OPENAI_CHATGPT_SIWC_USAGE_URL = "https://chatgpt.com/settings/usage"
const val OPENAI_CHATGPT_SIWC_DYNAMIC_CLIENT_ID = "dynamic_agent_client"
const val OPENAI_CHATGPT_SIWC_AGENT_NAME_HINT = "Amber"
const val OPENAI_CHATGPT_SIWC_CALLBACK_PATH = "/auth/callback"
const val OPENAI_CHATGPT_SIWC_DIRECT_USAGE_SCOPE = "chatgpt.tokens.use.direct"
const val OPENAI_CHATGPT_SIWC_SCOPE =
    "openid profile email offline_access resource.invoke $OPENAI_CHATGPT_SIWC_DIRECT_USAGE_SCOPE"

private const val SIWC_PREF_NAME = "openai_chatgpt_siwc_oauth"
private const val SIWC_KEY_ALIAS = "amberagent_openai_chatgpt_siwc_oauth"
private const val SIWC_HOST_ID_KEY = "__siwc_ext_agent_host_id__"
private const val SIWC_REFRESH_SKEW_MS = 2 * 60 * 1000L
private const val SIWC_AUTH_TIMEOUT_MS = 5 * 60 * 1000L
private const val SIWC_FALLBACK_TOKEN_LIFETIME_MS = 60 * 60 * 1000L
private const val SIWC_CLOCK_SKEW_SECONDS = 60L
private const val SIWC_MAX_REQUEST_LINE_BYTES = 8 * 1024
private const val SIWC_MAX_HEADER_BYTES = 32 * 1024
private const val SIWC_ACCEPTED_SOCKET_TIMEOUT_MS = 10_000

private val SIWC_TERMINAL_REFRESH_ERROR_CODES = setOf(
    "invalid_grant",
    "invalid_refresh_token",
    "token_expired",
    "refresh_token_expired",
    "refresh_token_invalidated",
    "refresh_token_reused",
)

private val siwcSessionLocks = ConcurrentHashMap<Uuid, Any>()
private fun sessionLock(providerId: Uuid): Any = siwcSessionLocks.getOrPut(providerId) { Any() }

@Serializable
data class OpenAIChatGptSiwcTokens(
    val clientId: String,
    val extAgentHostId: String,
    val subject: String? = null,
    val email: String? = null,
    val idToken: String? = null,
    val accessToken: String = "",
    val refreshToken: String = "",
    val expiresAtMillis: Long = 0L,
    val scopes: List<String> = emptyList(),
    val welcomeShown: Boolean = false,
) {
    val hasDirectUsageScope: Boolean
        get() = scopes.contains(OPENAI_CHATGPT_SIWC_DIRECT_USAGE_SCOPE)

    val isSignedIn: Boolean
        get() = accessToken.isNotBlank()

    fun isUsable(nowMillis: Long = System.currentTimeMillis()): Boolean =
        hasDirectUsageScope &&
            (refreshToken.isNotBlank() || (accessToken.isNotBlank() && expiresAtMillis > nowMillis))

    internal fun requireDirectUsageScope() = require(hasDirectUsageScope) {
        "ChatGPT plan usage permission ($OPENAI_CHATGPT_SIWC_DIRECT_USAGE_SCOPE) is not enabled. Re-authorize in provider settings."
    }
}

internal interface SiwcKeyValueStore {
    fun get(key: String): String?
    fun put(key: String, value: String)
    fun remove(key: String)
    fun exportPlainValues(): Map<String, String>
    fun replacePlainValues(values: Map<String, String>)
}

private class SecureSiwcKeyValueStore(context: Context) : SiwcKeyValueStore {
    private val store = OAuthTokenSecureStore(
        context = context,
        prefName = SIWC_PREF_NAME,
        keyAlias = SIWC_KEY_ALIAS,
    )

    override fun get(key: String): String? = store.get(key)
    override fun put(key: String, value: String) = store.put(key, value)
    override fun remove(key: String) = store.remove(key)
    override fun exportPlainValues(): Map<String, String> = store.exportPlainValues()
    override fun replacePlainValues(values: Map<String, String>) = store.replacePlainValues(values)
}

class OpenAIChatGptSiwcAuthStore internal constructor(
    private val backing: SiwcKeyValueStore,
) {
    constructor(context: Context) : this(SecureSiwcKeyValueStore(context))

    /** Stable device-unique host ID (`urn:uuid:<uuidv4>`) persisted across sign-ins. */
    fun getOrCreateHostId(): String = synchronized(hostIdLock) {
        val existing = backing.get(SIWC_HOST_ID_KEY)?.trim()
        if (!existing.isNullOrBlank() && existing.startsWith("urn:uuid:")) {
            return@synchronized existing
        }
        val generated = "urn:uuid:${UUID.randomUUID()}"
        backing.put(SIWC_HOST_ID_KEY, generated)
        generated
    }

    /** Returns the active signed-in session, or null when signed out / revoked. */
    fun get(providerId: Uuid): OpenAIChatGptSiwcTokens? =
        getRecord(providerId)?.takeIf { it.accessToken.isNotBlank() || it.refreshToken.isNotBlank() }

    /** Returns the full persisted record, including retained issued `client_id` after sign-out. */
    fun getRecord(providerId: Uuid): OpenAIChatGptSiwcTokens? {
        val raw = backing.get(providerId.toString()) ?: return null
        return runCatching { json.decodeFromString<OpenAIChatGptSiwcTokens>(raw) }.getOrNull()
    }

    fun save(providerId: Uuid, tokens: OpenAIChatGptSiwcTokens) {
        backing.put(providerId.toString(), json.encodeToString(tokens))
    }

    fun markWelcomeShown(providerId: Uuid): Unit = synchronized(sessionLock(providerId)) {
        val existing = get(providerId) ?: return@synchronized
        if (!existing.welcomeShown) {
            save(providerId, existing.copy(welcomeShown = true))
        }
    }

    /**
     * Clears session credentials (access/refresh/id tokens) while retaining the issued
     * `client_id`, `ext_agent_host_id`, `subject`, `email`, and `welcomeShown` for future
     * reauthorization per OpenAI SIWC guidelines.
     */
    fun clearCredentials(providerId: Uuid) {
        val existing = getRecord(providerId) ?: return
        if (existing.clientId.isBlank() || existing.clientId == OPENAI_CHATGPT_SIWC_DYNAMIC_CLIENT_ID) {
            backing.remove(providerId.toString())
            return
        }
        save(
            providerId,
            existing.copy(
                accessToken = "",
                refreshToken = "",
                idToken = null,
                expiresAtMillis = 0L,
                scopes = emptyList(),
            ),
        )
    }

    fun clearAll(providerId: Uuid) {
        backing.remove(providerId.toString())
    }

    fun exportRawJsonForSync(): String = json.encodeToString(backing.exportPlainValues())

    fun restoreRawJsonFromSync(raw: String) {
        val values = runCatching { json.decodeFromString<Map<String, String>>(raw) }.getOrNull() ?: return
        backing.replacePlainValues(values)
    }

    private companion object {
        val hostIdLock = Any()
    }
}

internal data class SiwcCallbackResult(
    val code: String? = null,
    val state: String? = null,
    val clientId: String? = null,
    val scope: String? = null,
    val error: String? = null,
    val errorDescription: String? = null,
) {
    val isSuccess: Boolean get() = error == null && !code.isNullOrBlank()
}

/**
 * Ephemeral 127.0.0.1 loopback server strictly bound to `127.0.0.1` on an OS-assigned port
 * with fixed callback path `/auth/callback` per OpenAI SIWC requirements (never `localhost`).
 */
internal class SiwcLoopbackCallbackServer(
    private val acceptedSocketReadTimeoutMillis: Int = SIWC_ACCEPTED_SOCKET_TIMEOUT_MS,
) : Closeable {
    private val serverSocket: ServerSocket = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
    private val activeSocket = AtomicReference<Socket?>()

    val port: Int = serverSocket.localPort
    val redirectUri: String = "http://127.0.0.1:$port$OPENAI_CHATGPT_SIWC_CALLBACK_PATH"

    suspend fun awaitCallback(): SiwcCallbackResult = withContext(Dispatchers.IO) {
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { close() }
            try {
                while (continuation.isActive) {
                    val client = serverSocket.accept()
                    activeSocket.set(client)
                    if (!continuation.isActive || serverSocket.isClosed) {
                        runCatching { client.close() }
                        if (continuation.isActive) continuation.cancel()
                        return@suspendCancellableCoroutine
                    }

                    val handled = client.use { socket ->
                        socket.soTimeout = acceptedSocketReadTimeoutMillis
                        handleConnection(socket)
                    }
                    activeSocket.compareAndSet(client, null)
                    if (handled.terminal) {
                        close()
                        if (continuation.isActive) continuation.resume(handled.result)
                        return@suspendCancellableCoroutine
                    }
                }
            } catch (error: Exception) {
                val closed = serverSocket.isClosed
                close()
                if (continuation.isActive) {
                    if (closed) continuation.cancel() else continuation.resumeWithException(error)
                }
            }
        }
    }

    override fun close() {
        runCatching { serverSocket.close() }
        runCatching { activeSocket.getAndSet(null)?.close() }
    }

    private fun handleConnection(socket: Socket): HandledCallback {
        val handled = try {
            val input = BufferedInputStream(socket.getInputStream())
            val requestLine = readLine(input, SIWC_MAX_REQUEST_LINE_BYTES)
                ?: throw IllegalArgumentException("Empty HTTP request line.")
            var headerBytes = 0
            while (true) {
                val line = readLine(input, SIWC_MAX_REQUEST_LINE_BYTES)
                    ?: throw IllegalArgumentException("Unterminated HTTP headers.")
                headerBytes += line.length + 2
                if (headerBytes > SIWC_MAX_HEADER_BYTES) throw IllegalArgumentException("HTTP headers too large.")
                if (line.isEmpty()) break
            }
            parseRequestLine(requestLine)
        } catch (error: SocketTimeoutException) {
            HandledCallback(
                result = SiwcCallbackResult(error = "loopback_timeout", errorDescription = error.message),
                terminal = false,
                statusCode = 400,
            )
        } catch (error: Exception) {
            HandledCallback(
                result = SiwcCallbackResult(error = "invalid_request", errorDescription = error.message),
                terminal = false,
                statusCode = 400,
            )
        }
        writeHtmlResponse(socket, handled)
        return handled
    }

    private fun parseRequestLine(requestLine: String): HandledCallback {
        val parts = requestLine.split(' ', limit = 3)
        if (parts.size != 3 || parts[0].isBlank() || parts[1].isBlank() || !parts[2].startsWith("HTTP/")) {
            return HandledCallback(
                result = SiwcCallbackResult(error = "invalid_request", errorDescription = "Malformed HTTP request."),
                terminal = false,
                statusCode = 400,
            )
        }
        val method = parts[0]
        val target = parts[1]
        val path = target.substringBefore('?')
        if (method != "GET" || path != OPENAI_CHATGPT_SIWC_CALLBACK_PATH) {
            return HandledCallback(
                result = SiwcCallbackResult(error = "ignored_path"),
                terminal = false,
                statusCode = 404,
            )
        }
        val queryIndex = target.indexOf('?')
        if (queryIndex < 0) {
            return HandledCallback(
                result = SiwcCallbackResult(error = "missing_query", errorDescription = "Missing callback query parameters."),
                terminal = true,
                statusCode = 400,
            )
        }
        val params = parseQueryParams(target.substring(queryIndex + 1))
        return HandledCallback(
            result = SiwcCallbackResult(
                code = params["code"],
                state = params["state"],
                clientId = params["client_id"],
                scope = params["scope"],
                error = params["error"],
                errorDescription = params["error_description"],
            ),
            terminal = true,
            statusCode = 200,
        )
    }

    private fun parseQueryParams(query: String): Map<String, String> {
        if (query.isBlank()) return emptyMap()
        return query.split('&').mapNotNull { pair ->
            val eq = pair.indexOf('=')
            if (eq <= 0) return@mapNotNull null
            val key = runCatching { URLDecoder.decode(pair.substring(0, eq), "UTF-8") }.getOrNull() ?: return@mapNotNull null
            val value = runCatching { URLDecoder.decode(pair.substring(eq + 1), "UTF-8") }.getOrNull() ?: return@mapNotNull null
            key to value
        }.toMap()
    }

    private fun writeHtmlResponse(socket: Socket, handled: HandledCallback) {
        val (title, detail) = when {
            handled.statusCode == 404 -> "Not Found" to "Waiting for ChatGPT sign-in callback."
            handled.result.isSuccess -> "Sign-in received" to "You can close this tab and return to Amber."
            else -> {
                val reason = listOfNotNull(handled.result.error, handled.result.errorDescription)
                    .filter { it.isNotBlank() }
                    .joinToString(": ")
                    .ifBlank { "Authorization did not complete." }
                "Sign-in cancelled" to reason
            }
        }
        val html = """
            <!doctype html>
            <html lang="en">
            <head>
              <meta charset="utf-8" />
              <meta name="viewport" content="width=device-width,initial-scale=1" />
              <title>${escapeHtml(title)}</title>
              <style>
                body{font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,sans-serif;margin:0;padding:32px 20px;background:#0f1115;color:#f3f4f6;display:flex;align-items:center;justify-content:center;min-height:100vh;box-sizing:border-box}
                .card{max-width:420px;width:100%;background:#181b22;border:1px solid #2a2f3a;border-radius:16px;padding:24px;text-align:center}
                h1{margin:0 0 8px;font-size:18px;font-weight:600}
                p{margin:0;font-size:14px;line-height:1.5;color:#9ca3af}
              </style>
            </head>
            <body>
              <div class="card">
                <h1>${escapeHtml(title)}</h1>
                <p>${escapeHtml(detail)}</p>
              </div>
            </body>
            </html>
        """.trimIndent().toByteArray(Charsets.UTF_8)
        val statusText = when (handled.statusCode) {
            200 -> "200 OK"
            404 -> "404 Not Found"
            else -> "400 Bad Request"
        }
        val headers = (
            "HTTP/1.1 $statusText\r\n" +
                "Content-Type: text/html; charset=utf-8\r\n" +
                "Content-Length: ${html.size}\r\n" +
                "Cache-Control: no-store\r\n" +
                "Connection: close\r\n\r\n"
            ).toByteArray(Charsets.US_ASCII)
        runCatching {
            socket.getOutputStream().apply {
                write(headers)
                write(html)
                flush()
            }
        }
    }

    private fun readLine(input: InputStream, maxBytes: Int): String? {
        val out = ByteArrayOutputStream()
        while (true) {
            when (val ch = input.read()) {
                -1 -> {
                    if (out.size() == 0) return null
                    throw IllegalArgumentException("Unexpected EOF while reading HTTP request.")
                }
                '\n'.code -> return out.toString(Charsets.ISO_8859_1.name()).removeSuffix("\r")
                else -> {
                    if (out.size() >= maxBytes) throw IllegalArgumentException("HTTP line exceeded limit.")
                    out.write(ch)
                }
            }
        }
    }

    private fun escapeHtml(text: String): String = buildString(text.length) {
        for (ch in text) {
            when (ch) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&#39;")
                else -> append(ch)
            }
        }
    }

    private data class HandledCallback(
        val result: SiwcCallbackResult,
        val terminal: Boolean,
        val statusCode: Int,
    )
}

internal data class ValidatedSiwcIdToken(
    val subject: String,
    val email: String?,
)

internal data class SiwcOidcMetadata(
    val issuer: String,
    val jwksUri: String,
    val revocationEndpoint: String?,
)

internal class OpenAIChatGptSiwcIdTokenValidator(
    private val client: OkHttpClient,
    private val oidcConfigUrl: String = OPENAI_CHATGPT_SIWC_OIDC_CONFIG_URL,
    private val defaultJwksUrl: String = OPENAI_CHATGPT_SIWC_DEFAULT_JWKS_URL,
    private val expectedIssuer: String = OPENAI_CHATGPT_SIWC_ISSUER,
    private val clockMillis: () -> Long = System::currentTimeMillis,
) {
    private val cacheLock = Mutex()
    @Volatile
    private var cachedMetadata: SiwcOidcMetadata? = null
    @Volatile
    private var cachedJwks: List<JsonObject> = emptyList()

    suspend fun loadOidcMetadata(forceRefresh: Boolean = false): SiwcOidcMetadata {
        cachedMetadata?.takeIf { !forceRefresh }?.let { return it }
        return cacheLock.withLock {
            cachedMetadata?.takeIf { !forceRefresh }?.let { return@withLock it }
            val fetched = runCatching {
                val response = client.newCall(Request.Builder().url(oidcConfigUrl).get().build()).await()
                response.use { resp ->
                    if (!resp.isSuccessful) error("OIDC discovery failed: HTTP ${resp.code}")
                    val obj = json.parseToJsonElement(resp.body.string()).jsonObject
                    SiwcOidcMetadata(
                        issuer = obj["issuer"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                        jwksUri = obj["jwks_uri"]?.jsonPrimitive?.contentOrNull ?: defaultJwksUrl,
                        revocationEndpoint = obj["revocation_endpoint"]?.jsonPrimitive?.contentOrNull,
                    )
                }
            }.getOrElse { error ->
                if (error is CancellationException) throw error
                return@withLock SiwcOidcMetadata(
                    issuer = expectedIssuer,
                    jwksUri = defaultJwksUrl,
                    revocationEndpoint = null,
                )
            }
            require(fetched.issuer == expectedIssuer) { "ChatGPT OIDC discovery issuer mismatch." }
            require(fetched.jwksUri.toHttpUrl().isHttps && fetched.revocationEndpoint?.toHttpUrl()?.isHttps != false) {
                "ChatGPT OIDC endpoints must use HTTPS."
            }
            cachedMetadata = fetched
            fetched
        }
    }

    suspend fun validate(
        idToken: String,
        expectedClientId: String,
        expectedNonce: String,
        expectedSubject: String? = null,
    ): ValidatedSiwcIdToken {
        val segments = idToken.split('.')
        require(segments.size == 3 && segments.all { it.isNotBlank() }) {
            "ChatGPT ID token is malformed."
        }
        val header = parseJwtObject(segments[0], "header")
        val payload = parseJwtObject(segments[1], "payload")
        val alg = header["alg"]?.jsonPrimitive?.contentOrNull
            ?: error("ChatGPT ID token is missing alg header.")
        val kid = header["kid"]?.jsonPrimitive?.contentOrNull
        val signingInput = "${segments[0]}.${segments[1]}".toByteArray(Charsets.US_ASCII)
        val signatureBytes = base64UrlDecode(segments[2])

        verifySignatureWithJwks(
            alg = alg,
            kid = kid,
            signingInput = signingInput,
            signatureBytes = signatureBytes,
        )

        val issuer = payload["iss"]?.jsonPrimitive?.contentOrNull
        require(issuer == expectedIssuer) {
            "ChatGPT ID token issuer mismatch: expected $expectedIssuer, got $issuer."
        }

        val audElement = payload["aud"] ?: error("ChatGPT ID token is missing aud claim.")
        val audMatches = when (audElement) {
            is JsonPrimitive -> audElement.contentOrNull == expectedClientId
            is JsonArray -> audElement.isNotEmpty() && audElement.all { (it as? JsonPrimitive)?.contentOrNull == expectedClientId }
            else -> false
        }
        val authorizedParty = payload["azp"]?.jsonPrimitive?.contentOrNull
        require(audMatches && (authorizedParty == null || authorizedParty == expectedClientId)) {
            "ChatGPT ID token audience does not match issued client_id."
        }

        val exp = payload["exp"]?.jsonPrimitive?.longOrNull
            ?: error("ChatGPT ID token is missing exp claim.")
        val nowSeconds = clockMillis() / 1000L
        require(exp + SIWC_CLOCK_SKEW_SECONDS > nowSeconds) {
            "ChatGPT ID token is expired."
        }

        val nonce = payload["nonce"]?.jsonPrimitive?.contentOrNull
        require(nonce == expectedNonce) {
            "ChatGPT ID token nonce mismatch."
        }

        val subject = payload["sub"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: error("ChatGPT ID token is missing sub claim.")
        if (!expectedSubject.isNullOrBlank()) {
            require(subject == expectedSubject) {
                "Signed-in ChatGPT account does not match the previously registered account."
            }
        }

        val email = payload["email"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        return ValidatedSiwcIdToken(subject = subject, email = email)
    }

    private suspend fun verifySignatureWithJwks(
        alg: String,
        kid: String?,
        signingInput: ByteArray,
        signatureBytes: ByteArray,
    ) {
        val initialKeys = loadJwks(forceRefresh = false)
        if (verifyAgainstKeys(initialKeys, alg, kid, signingInput, signatureBytes)) return
        val refreshedKeys = loadJwks(forceRefresh = true)
        if (verifyAgainstKeys(refreshedKeys, alg, kid, signingInput, signatureBytes)) return
        error("ChatGPT ID token signature verification failed against JWKS.")
    }

    private suspend fun loadJwks(forceRefresh: Boolean): List<JsonObject> {
        if (!forceRefresh && cachedJwks.isNotEmpty()) return cachedJwks
        val metadata = loadOidcMetadata(forceRefresh = forceRefresh)
        return cacheLock.withLock {
            if (!forceRefresh && cachedJwks.isNotEmpty()) return@withLock cachedJwks
            val response = client.newCall(Request.Builder().url(metadata.jwksUri).get().build()).await()
            val body = response.use { resp ->
                if (!resp.isSuccessful) error("Failed to fetch OpenAI JWKS: HTTP ${resp.code}")
                resp.body.string()
            }
            val keys = json.parseToJsonElement(body)
                .jsonObject["keys"]
                ?.jsonArray
                ?.mapNotNull { it as? JsonObject }
                .orEmpty()
            require(keys.isNotEmpty()) { "OpenAI JWKS returned no signing keys." }
            cachedJwks = keys
            keys
        }
    }

    private fun verifyAgainstKeys(
        keys: List<JsonObject>,
        alg: String,
        kid: String?,
        signingInput: ByteArray,
        signatureBytes: ByteArray,
    ): Boolean {
        val candidates = if (!kid.isNullOrBlank()) {
            keys.filter { it["kid"]?.jsonPrimitive?.contentOrNull == kid }
        } else {
            keys
        }
        return candidates.any { jwk ->
            runCatching { verifyWithJwk(jwk, alg, signingInput, signatureBytes) }.getOrDefault(false)
        }
    }

    private fun verifyWithJwk(
        jwk: JsonObject,
        alg: String,
        signingInput: ByteArray,
        signatureBytes: ByteArray,
    ): Boolean {
        val jwkUse = jwk["use"]?.jsonPrimitive?.contentOrNull
        if (jwkUse != null && jwkUse != "sig") return false
        val jwkAlgorithm = jwk["alg"]?.jsonPrimitive?.contentOrNull
        if (jwkAlgorithm != null && jwkAlgorithm != alg) return false
        val kty = jwk["kty"]?.jsonPrimitive?.contentOrNull ?: return false
        return when {
            kty == "RSA" && alg in setOf("RS256", "RS384", "RS512") -> {
                val jcaAlg = when (alg) {
                    "RS256" -> "SHA256withRSA"
                    "RS384" -> "SHA384withRSA"
                    "RS512" -> "SHA512withRSA"
                    else -> return false
                }
                val publicKey = buildRsaPublicKey(jwk) ?: return false
                Signature.getInstance(jcaAlg).run {
                    initVerify(publicKey)
                    update(signingInput)
                    verify(signatureBytes)
                }
            }
            kty == "EC" && alg == "ES256" -> {
                val publicKey = buildEcP256PublicKey(jwk) ?: return false
                val derSignature = jwsEcdsaToDer(signatureBytes) ?: return false
                Signature.getInstance("SHA256withECDSA").run {
                    initVerify(publicKey)
                    update(signingInput)
                    verify(derSignature)
                }
            }
            else -> false
        }
    }

    private fun buildRsaPublicKey(jwk: JsonObject): PublicKey? {
        val nStr = jwk["n"]?.jsonPrimitive?.contentOrNull ?: return null
        val eStr = jwk["e"]?.jsonPrimitive?.contentOrNull ?: return null
        val modulus = BigInteger(1, base64UrlDecode(nStr))
        val exponent = BigInteger(1, base64UrlDecode(eStr))
        return KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(modulus, exponent))
    }

    private fun buildEcP256PublicKey(jwk: JsonObject): PublicKey? {
        val crv = jwk["crv"]?.jsonPrimitive?.contentOrNull
        if (crv != "P-256") return null
        val xStr = jwk["x"]?.jsonPrimitive?.contentOrNull ?: return null
        val yStr = jwk["y"]?.jsonPrimitive?.contentOrNull ?: return null
        val x = BigInteger(1, base64UrlDecode(xStr))
        val y = BigInteger(1, base64UrlDecode(yStr))
        val params = AlgorithmParameters.getInstance("EC").apply {
            init(ECGenParameterSpec("secp256r1"))
        }.getParameterSpec(ECParameterSpec::class.java)
        return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(x, y), params))
    }

    /** Converts JWS IEEE P1363 raw (R || S) signature to ASN.1 DER expected by JCA ECDSA. */
    private fun jwsEcdsaToDer(raw: ByteArray): ByteArray? {
        if (raw.size != 64) return null
        val r = BigInteger(1, raw.copyOfRange(0, 32)).toByteArray()
        val s = BigInteger(1, raw.copyOfRange(32, 64)).toByteArray()
        val seqLen = 2 + r.size + 2 + s.size
        val out = ByteArrayOutputStream(2 + seqLen)
        out.write(0x30)
        out.write(seqLen)
        out.write(0x02)
        out.write(r.size)
        out.write(r)
        out.write(0x02)
        out.write(s.size)
        out.write(s)
        return out.toByteArray()
    }

    private fun parseJwtObject(segment: String, partName: String): JsonObject =
        runCatching {
            json.parseToJsonElement(String(base64UrlDecode(segment), Charsets.UTF_8)).jsonObject
        }.getOrElse {
            error("Unable to decode ChatGPT ID token $partName.")
        }
}

class OpenAIChatGptSiwcOAuthClient internal constructor(
    private val client: OkHttpClient,
    private val authStore: OpenAIChatGptSiwcAuthStore,
    private val authorizeUrl: String = OPENAI_CHATGPT_SIWC_AUTHORIZE_URL,
    private val tokenUrl: String = OPENAI_CHATGPT_SIWC_TOKEN_URL,
    private val apiBaseUrl: String = OPENAI_CHATGPT_SIWC_API_BASE_URL,
    private val idTokenValidator: OpenAIChatGptSiwcIdTokenValidator = OpenAIChatGptSiwcIdTokenValidator(client),
    private val clockMillis: () -> Long = System::currentTimeMillis,
) {
    constructor(
        client: OkHttpClient,
        authStore: OpenAIChatGptSiwcAuthStore,
    ) : this(
        client = client,
        authStore = authStore,
        authorizeUrl = OPENAI_CHATGPT_SIWC_AUTHORIZE_URL,
        tokenUrl = OPENAI_CHATGPT_SIWC_TOKEN_URL,
        apiBaseUrl = OPENAI_CHATGPT_SIWC_API_BASE_URL,
        idTokenValidator = OpenAIChatGptSiwcIdTokenValidator(client),
        clockMillis = System::currentTimeMillis,
    )

    private companion object {
        val refreshMutexes = ConcurrentHashMap<String, Mutex>()
        val generations = ConcurrentHashMap<String, Long>()
    }

    private fun refreshMutex(providerId: Uuid): Mutex =
        refreshMutexes.getOrPut(providerId.toString()) { Mutex() }

    private fun generation(providerId: Uuid): Long = synchronized(sessionLock(providerId)) {
        generations[providerId.toString()] ?: 0L
    }

    private fun beginAuthorization(providerId: Uuid): Long = synchronized(sessionLock(providerId)) {
        val next = (generations[providerId.toString()] ?: 0L) + 1L
        generations[providerId.toString()] = next
        next
    }

    private fun saveIfCurrent(
        providerId: Uuid,
        expectedGeneration: Long,
        tokens: OpenAIChatGptSiwcTokens,
        advanceGeneration: Boolean = false,
    ): OpenAIChatGptSiwcTokens = synchronized(sessionLock(providerId)) {
        if ((generations[providerId.toString()] ?: 0L) != expectedGeneration) staleSession()
        // A welcome acknowledgement may have arrived while OAuth was on the network.
        val existing = authStore.getRecord(providerId)
        val saved = if (existing?.clientId == tokens.clientId && existing.welcomeShown) {
            tokens.copy(welcomeShown = true)
        } else tokens
        authStore.save(providerId, saved)
        if (advanceGeneration) generations[providerId.toString()] = expectedGeneration + 1L
        saved
    }

    private fun clearCredentialsIfCurrent(providerId: Uuid, expectedGeneration: Long): Boolean =
        synchronized(sessionLock(providerId)) {
            if ((generations[providerId.toString()] ?: 0L) != expectedGeneration) return@synchronized false
            authStore.clearCredentials(providerId)
            true
        }

    private fun staleSession(): Nothing =
        error("ChatGPT sign-in session changed; discarded stale response. Please retry.")

    fun getCached(providerId: Uuid): OpenAIChatGptSiwcTokens? = authStore.get(providerId)

    fun getRecord(providerId: Uuid): OpenAIChatGptSiwcTokens? = authStore.getRecord(providerId)

    fun logout(providerId: Uuid) = synchronized(sessionLock(providerId)) {
        val key = providerId.toString()
        generations[key] = (generations[key] ?: 0L) + 1L
        authStore.clearCredentials(providerId)
    }

    /**
     * Stop local use immediately, then attempt to revoke the captured refresh token.
     * A concurrent rotation makes remote revocation uncertain even if the old token returns 200.
     */
    suspend fun signOut(providerId: Uuid): Boolean {
        val (existing, refreshInProgress) = synchronized(sessionLock(providerId)) {
            val tokens = authStore.get(providerId)
            val rotating = refreshMutex(providerId).isLocked
            logout(providerId)
            tokens to rotating
        }
        val refreshToken = existing?.refreshToken?.takeIf { it.isNotBlank() } ?: return true
        val issuedClientId = existing.clientId.takeIf {
            it.isNotBlank() && it != OPENAI_CHATGPT_SIWC_DYNAMIC_CLIENT_ID
        } ?: return true
        val revocationEndpoint = runCatching {
            idTokenValidator.loadOidcMetadata().revocationEndpoint
        }.getOrNull() ?: return false
        return runCatching {
            val body = FormBody.Builder()
                .add("token", refreshToken)
                .add("token_type_hint", "refresh_token")
                .add("client_id", issuedClientId)
                .build()
            val response = client.newCall(
                Request.Builder().url(revocationEndpoint).post(body).build()
            ).await()
            response.use { it.isSuccessful && !refreshInProgress }
        }.getOrDefault(false)
    }

    internal fun buildAuthorizationUrl(
        redirectUri: String,
        hostId: String,
        state: String,
        nonce: String,
        codeChallenge: String,
        existingRecord: OpenAIChatGptSiwcTokens? = null,
        forceConsent: Boolean = false,
    ): String {
        val issuedClientId = existingRecord?.clientId
            ?.takeIf { it.isNotBlank() && it != OPENAI_CHATGPT_SIWC_DYNAMIC_CLIENT_ID }
        val isInitialRegistration = issuedClientId == null
        val builder = authorizeUrl.toHttpUrl().newBuilder()
            .addQueryParameter("response_type", "code")
            .addQueryParameter("client_id", issuedClientId ?: OPENAI_CHATGPT_SIWC_DYNAMIC_CLIENT_ID)
            .addQueryParameter("ext_agent_host_id", hostId)
            .addQueryParameter("redirect_uri", redirectUri)
            .addQueryParameter("scope", OPENAI_CHATGPT_SIWC_SCOPE)
            .addQueryParameter("resource", apiBaseUrl)
            .addQueryParameter("state", state)
            .addQueryParameter("nonce", nonce)
            .addQueryParameter("code_challenge", codeChallenge)
            .addQueryParameter("code_challenge_method", "S256")

        if (isInitialRegistration) {
            builder.addQueryParameter("agent_name_hint", OPENAI_CHATGPT_SIWC_AGENT_NAME_HINT)
        } else {
            existingRecord.idToken?.takeIf { it.isNotBlank() }?.let { idTokenHint ->
                builder.addQueryParameter("id_token_hint", idTokenHint)
            }
            existingRecord.email?.takeIf { it.isNotBlank() }?.let { loginHint ->
                builder.addQueryParameter("login_hint", loginHint)
            }
        }
        if (forceConsent) {
            builder.addQueryParameter("prompt", "consent")
        }
        return builder.build().toString()
    }

    suspend fun authorize(
        providerId: Uuid,
        openBrowser: suspend (String) -> Unit,
        forceConsent: Boolean = false,
        timeoutMillis: Long = SIWC_AUTH_TIMEOUT_MS,
        browserClosed: Deferred<Unit>? = null,
    ): OpenAIChatGptSiwcTokens {
        val loginGeneration = beginAuthorization(providerId)
        val hostId = authStore.getOrCreateHostId()
        val existingRecord = authStore.getRecord(providerId)
        val expectedClientId = existingRecord?.clientId
            ?.takeIf { it.isNotBlank() && it != OPENAI_CHATGPT_SIWC_DYNAMIC_CLIENT_ID }

        return SiwcLoopbackCallbackServer().use { server ->
            val state = randomBase64Url(24)
            val nonce = randomBase64Url(24)
            val codeVerifier = randomBase64Url(64)
            val codeChallenge = sha256Base64Url(codeVerifier)
            val authUrl = buildAuthorizationUrl(
                redirectUri = server.redirectUri,
                hostId = hostId,
                state = state,
                nonce = nonce,
                codeChallenge = codeChallenge,
                existingRecord = existingRecord,
                forceConsent = forceConsent,
            )

            val onBrowserClose = browserClosed?.invokeOnCompletion { server.close() }
            val callback = try {
                openBrowser(authUrl)
                withTimeoutOrNull(timeoutMillis) { server.awaitCallback() }
                    ?: error("Sign in with ChatGPT timed out. Please try again.")
            } finally {
                onBrowserClose?.dispose()
            }

            require(callback.state == state) {
                "Sign in with ChatGPT state mismatch. Please try again."
            }
            if (!callback.error.isNullOrBlank()) {
                val detail = callback.errorDescription?.takeIf { it.isNotBlank() }
                if (callback.error == "access_denied") {
                    error("ChatGPT plan access was declined (access_denied).${detail?.let { " $it" }.orEmpty()}")
                }
                error("Sign in with ChatGPT failed (${callback.error}).${detail?.let { " $it" }.orEmpty()}")
            }
            val code = callback.code?.takeIf { it.isNotBlank() }
                ?: error("Sign in with ChatGPT callback did not include an authorization code.")

            val resolvedClientId = resolveCallbackClientId(
                expectedClientId = expectedClientId,
                callbackClientId = callback.clientId,
            )

            val tokenResponse = exchangeAuthorizationCode(
                clientId = resolvedClientId,
                code = code,
                codeVerifier = codeVerifier,
                redirectUri = server.redirectUri,
            )

            val idToken = tokenResponse.idToken?.takeIf { it.isNotBlank() }
                ?: error("Sign in with ChatGPT token response is missing id_token.")
            val validatedId = idTokenValidator.validate(
                idToken = idToken,
                expectedClientId = resolvedClientId,
                expectedNonce = nonce,
                expectedSubject = expectedClientId?.let { existingRecord.subject },
            )

            val grantedScopes = parseGrantedScopes(tokenResponse.scope)
            val tokens = OpenAIChatGptSiwcTokens(
                clientId = resolvedClientId,
                extAgentHostId = hostId,
                subject = validatedId.subject,
                email = validatedId.email ?: existingRecord?.email,
                idToken = idToken,
                accessToken = tokenResponse.accessToken,
                refreshToken = tokenResponse.refreshToken.orEmpty(),
                expiresAtMillis = computeExpiresAtMillis(tokenResponse.expiresIn),
                scopes = grantedScopes,
            )
            saveIfCurrent(providerId, loginGeneration, tokens, advanceGeneration = true)
        }
    }

    suspend fun getValidAccessToken(providerId: Uuid, forceRefresh: Boolean = false): String {
        val current = authStore.get(providerId)
            ?: error("Sign in with ChatGPT is not signed in. Open provider settings and select Continue with ChatGPT.")
        current.requireDirectUsageScope()
        val now = clockMillis()
        val minimumLifetime = if (current.refreshToken.isBlank()) 0L else SIWC_REFRESH_SKEW_MS
        if (!forceRefresh && current.accessToken.isNotBlank() && current.expiresAtMillis - now > minimumLifetime) {
            return current.accessToken
        }
        return refresh(providerId, force = forceRefresh).accessToken
    }

    suspend fun refresh(providerId: Uuid, force: Boolean = true): OpenAIChatGptSiwcTokens =
        refreshMutex(providerId).withLock {
            val generationAtStart = generation(providerId)
            val current = authStore.get(providerId)
                ?: error("Sign in with ChatGPT is not signed in. Please sign in again.")
            current.requireDirectUsageScope()
            if (!force && current.accessToken.isNotBlank() && current.expiresAtMillis - clockMillis() > SIWC_REFRESH_SKEW_MS) {
                return@withLock current
            }
            val issuedClientId = current.clientId.takeIf {
                it.isNotBlank() && it != OPENAI_CHATGPT_SIWC_DYNAMIC_CLIENT_ID
            } ?: error("Missing issued ChatGPT client_id. Please sign in again.")
            val refreshToken = current.refreshToken.takeIf { it.isNotBlank() }
                ?: error("Missing ChatGPT refresh token. Please sign in again.")

            val form = FormBody.Builder()
                .add("grant_type", "refresh_token")
                .add("client_id", issuedClientId)
                .add("refresh_token", refreshToken)
                .add("resource", apiBaseUrl)
                .build()
            val request = Request.Builder()
                .url(tokenUrl)
                .post(form)
                .build()

            val response = client.newCall(request).await()
            val body = response.use { resp ->
                val text = resp.body.string()
                if (!resp.isSuccessful) {
                    val errorCode = extractOAuthErrorCode(text)
                    if (errorCode in SIWC_TERMINAL_REFRESH_ERROR_CODES) {
                        clearCredentialsIfCurrent(providerId, generationAtStart)
                        error("ChatGPT session expired ($errorCode). Please sign in again.")
                    }
                    error("ChatGPT OAuth token refresh failed: HTTP ${resp.code} ${text.toSafeSiwcOAuthError()}")
                }
                text
            }

            val parsed = parseTokenResponse(body)
            // Keep the original, verified ID token as the login hint.
            val updated = current.copy(
                accessToken = parsed.accessToken,
                refreshToken = parsed.refreshToken?.takeIf { it.isNotBlank() } ?: current.refreshToken,
                expiresAtMillis = computeExpiresAtMillis(parsed.expiresIn),
                scopes = parsed.scope?.let(::parseGrantedScopes) ?: current.scopes,
            )
            val saved = saveIfCurrent(providerId, generationAtStart, updated)
            saved.requireDirectUsageScope()
            saved
        }

    suspend fun listModels(providerId: Uuid): List<Model> = withContext(Dispatchers.IO) {
        val token = getValidAccessToken(providerId, forceRefresh = false)
        val request = Request.Builder()
            .url("$apiBaseUrl/models")
            .header("Authorization", "Bearer $token")
            .get()
            .build()

        var response = client.newCall(request).await()
        if (response.code == 401) {
            response.close()
            val retryToken = getValidAccessToken(providerId, forceRefresh = true)
            response = request.newBuilder()
                .header("Authorization", "Bearer $retryToken")
                .build()
                .let { client.newCall(it).await() }
        }
        response.use { resp ->
            val body = resp.body.string()
            if (!resp.isSuccessful) {
                error("Failed to fetch ChatGPT models: HTTP ${resp.code} ${body.toSafeSiwcOAuthError()}")
            }
            parseSiwcModelsResponse(body)
        }
    }

    private suspend fun exchangeAuthorizationCode(
        clientId: String,
        code: String,
        codeVerifier: String,
        redirectUri: String,
    ): SiwcTokenResponse {
        val form = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("client_id", clientId)
            .add("code", code)
            .add("code_verifier", codeVerifier)
            .add("redirect_uri", redirectUri)
            .add("resource", apiBaseUrl)
            .build()
        val request = Request.Builder()
            .url(tokenUrl)
            .post(form)
            .build()
        val response = client.newCall(request).await()
        val body = response.use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                error("ChatGPT OAuth token exchange failed: HTTP ${resp.code} ${text.toSafeSiwcOAuthError()}")
            }
            text
        }
        return parseTokenResponse(body)
    }

    private fun resolveCallbackClientId(
        expectedClientId: String?,
        callbackClientId: String?,
    ): String {
        val returned = callbackClientId?.trim()?.takeIf { it.isNotBlank() }
        if (expectedClientId == null) {
            require(!returned.isNullOrBlank() && returned != OPENAI_CHATGPT_SIWC_DYNAMIC_CLIENT_ID) {
                "Sign in with ChatGPT registration was incomplete: missing issued client_id."
            }
            return returned
        }
        if (returned != null) {
            require(returned == expectedClientId) {
                "Sign in with ChatGPT callback returned a different client_id than expected."
            }
        }
        return expectedClientId
    }

    private fun parseTokenResponse(raw: String): SiwcTokenResponse {
        val obj = json.parseToJsonElement(raw).jsonObject
        val accessToken = obj["access_token"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: error("ChatGPT OAuth token response is missing access_token.")
        return SiwcTokenResponse(
            accessToken = accessToken,
            refreshToken = obj["refresh_token"]?.jsonPrimitive?.contentOrNull,
            idToken = obj["id_token"]?.jsonPrimitive?.contentOrNull,
            expiresIn = obj["expires_in"]?.jsonPrimitive?.longOrNull,
            scope = obj["scope"]?.jsonPrimitive?.contentOrNull,
        )
    }

    private fun computeExpiresAtMillis(expiresInSeconds: Long?): Long {
        val lifetimeMillis = if (expiresInSeconds != null) {
            expiresInSeconds.coerceAtLeast(0L) * 1000L
        } else {
            SIWC_FALLBACK_TOKEN_LIFETIME_MS
        }
        return clockMillis() + lifetimeMillis
    }

    private data class SiwcTokenResponse(
        val accessToken: String,
        val refreshToken: String?,
        val idToken: String?,
        val expiresIn: Long?,
        val scope: String?,
    )
}

/**
 * Parses `GET https://api.openai.com/v1/models` response for SIWC:
 * filters `models` where `visibility == "list"`, preserves exact server order,
 * uses `slug` as `modelId` (sent on inference requests) and `display_name` as `displayName`.
 */
internal fun parseSiwcModelsResponse(raw: String): List<Model> {
    val root = json.parseToJsonElement(raw).jsonObject
    val modelsArray = root["models"] as? JsonArray ?: error("ChatGPT model catalog is missing the models array.")
    return modelsArray.mapNotNull { element ->
        val obj = element as? JsonObject ?: return@mapNotNull null
        val visibility = obj["visibility"]?.jsonPrimitive?.contentOrNull
        if (visibility != "list") return@mapNotNull null
        val slug = obj["slug"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotBlank() }
            ?: return@mapNotNull null
        val displayName = obj["display_name"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotBlank() }
            ?: slug
        Model(
            modelId = slug,
            displayName = displayName,
            inputModalities = ModelRegistry.MODEL_INPUT_MODALITIES.getData(slug),
            outputModalities = ModelRegistry.MODEL_OUTPUT_MODALITIES.getData(slug),
            abilities = ModelRegistry.MODEL_ABILITIES.getData(slug),
            contextWindowTokens = ModelRegistry.MODEL_CONTEXT_WINDOW.getData(slug),
        )
    }
}

private fun parseGrantedScopes(scope: String?): List<String> =
    scope.orEmpty()
        .split(' ', '\t', '\n', '\r')
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinct()

private fun extractOAuthErrorCode(raw: String): String? = runCatching {
    val obj = json.parseToJsonElement(raw).jsonObject
    (obj["error"] as? JsonPrimitive)?.contentOrNull
        ?: (obj["error"] as? JsonObject)?.get("code")?.jsonPrimitive?.contentOrNull
        ?: obj["code"]?.jsonPrimitive?.contentOrNull
}.getOrNull()

private fun String.toSafeSiwcOAuthError(): String {
    val parsed = runCatching {
        val obj = json.parseToJsonElement(this).jsonObject
        obj["error_description"]?.jsonPrimitive?.contentOrNull
            ?: obj["detail"]?.jsonPrimitive?.contentOrNull
            ?: obj["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
            ?: obj["error"]?.jsonPrimitive?.contentOrNull
    }.getOrNull()
    return parsed?.take(240) ?: take(240).ifBlank { "unknown error" }
}

private fun randomBase64Url(byteCount: Int): String {
    val bytes = ByteArray(byteCount)
    SecureRandom().nextBytes(bytes)
    return base64UrlEncode(bytes)
}

internal fun sha256Base64Url(input: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.US_ASCII))
    return base64UrlEncode(digest)
}

internal fun base64UrlEncode(bytes: ByteArray): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

internal fun base64UrlDecode(encoded: String): ByteArray =
    Base64.getUrlDecoder().decode(encoded)
