package app.amber.ai.provider.providers.vertex

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.forms.submitForm
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Parameters
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Instant
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * 使用服务账号（email + private key PEM）换取 Google OAuth2 Access Token。
 */
class ServiceAccountTokenProvider {
    private val json = Json { ignoreUnknownKeys = true }

    // Token cache to avoid frequent token requests
    private val tokenCache = ConcurrentHashMap<String, CachedToken>()

    private val ktorClient by lazy { HttpClient(OkHttp) { expectSuccess = false } }

    @Serializable
    private data class CachedToken(
        val token: String,
        val expiresAt: Long // Unix timestamp in seconds
    )

    /**
     * Generate cache key based on service account email and scopes
     */
    private fun generateCacheKey(serviceAccountEmail: String, scopes: List<String>): String {
        return "$serviceAccountEmail:${scopes.sorted().joinToString(",")}"
    }

    /**
     * Check if cached token is still valid (not expired with 5 minutes buffer)
     */
    private fun isCachedTokenValid(cachedToken: CachedToken): Boolean {
        val now = Instant.now().epochSecond
        val bufferSeconds = 300 // 5 minutes buffer before actual expiration
        return cachedToken.expiresAt > (now + bufferSeconds)
    }

    /**
     * @param serviceAccountEmail  形如 xxx@project-id.iam.gserviceaccount.com
     * @param privateKeyPem        服务账号 JSON 中的 private_key 字段（PKCS#8 PEM, 含 -----BEGIN PRIVATE KEY-----）
     * @param scopes               OAuth scopes，默认 cloud-platform；多个 scope 用 List 传入
     * @return                     access token 字符串
     */
    suspend fun fetchAccessToken(
        serviceAccountEmail: String,
        privateKeyPem: String,
        scopes: List<String> = listOf("https://www.googleapis.com/auth/cloud-platform")
    ): String = withContext(Dispatchers.IO) {
        val cacheKey = generateCacheKey(serviceAccountEmail, scopes)

        // Check cache first
        tokenCache[cacheKey]?.let { cachedToken ->
            if (isCachedTokenValid(cachedToken)) {
                return@withContext cachedToken.token
            }
        }
        val now = Instant.now().epochSecond
        val exp = now + 3600 // 最长 1h

        val headerJson = """{"alg":"RS256","typ":"JWT"}"""
        val claimJson = """{
          "iss":"$serviceAccountEmail",
          "scope":"${scopes.joinToString(" ")}",
          "aud":"https://oauth2.googleapis.com/token",
          "iat":$now,
          "exp":$exp
        }""".trimIndent()

        val headerB64 = base64UrlNoPad(headerJson.toByteArray(Charsets.UTF_8))
        val claimB64 = base64UrlNoPad(claimJson.toByteArray(Charsets.UTF_8))
        val signingInput = "$headerB64.$claimB64"

        val privateKey = parsePkcs8PrivateKey(privateKeyPem)
        val signature = signRs256(signingInput.toByteArray(Charsets.UTF_8), privateKey)
        val assertion = "$signingInput.${base64UrlNoPad(signature)}"

        val resp = ktorClient.submitForm(
            url = "https://oauth2.googleapis.com/token",
            formParameters = Parameters.build {
                append("grant_type", "urn:ietf:params:oauth:grant-type:jwt-bearer")
                append("assertion", assertion)
            }
        )
        val body = resp.bodyAsText()
        if (!resp.status.isSuccess()) {
            throw IllegalStateException("Token endpoint ${resp.status.value}: $body")
        }
        val tokenResp = json.decodeFromString(TokenResponse.serializer(), body)
        val accessToken = tokenResp.accessToken ?: error("No access_token in response")

        // Cache the token with expiration time
        val expiresIn = tokenResp.expiresIn ?: 3600 // Default 1 hour if not provided
        val expiresAt = now + expiresIn
        tokenCache[cacheKey] = CachedToken(accessToken, expiresAt)

        accessToken
    }

    @Serializable
    private data class TokenResponse(
        @SerialName("access_token")
        val accessToken: String? = null,
        @SerialName("token_type")
        val tokenType: String? = null,
        @SerialName("expires_in")
        val expiresIn: Long? = null
    )

    private fun base64UrlNoPad(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private fun parsePkcs8PrivateKey(pem: String): PrivateKey {
        val normalized = pem
            .replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replace("\\s".toRegex(), "")
        val der = Base64.getDecoder().decode(normalized)
        val keySpec = PKCS8EncodedKeySpec(der)
        return KeyFactory.getInstance("RSA").generatePrivate(keySpec)
    }

    private fun signRs256(data: ByteArray, privateKey: PrivateKey): ByteArray {
        val sig = Signature.getInstance("SHA256withRSA")
        sig.initSign(privateKey)
        sig.update(data)
        return sig.sign()
    }
}
