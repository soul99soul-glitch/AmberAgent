package app.amber.feature.macgateway

import java.io.IOException
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

internal val MacGatewayJson = Json { ignoreUnknownKeys = true }

/** Contents of the `amber-gateway pair` QR code (`amber://gateway/pair?p=<base64url JSON>`). */
@Serializable
data class MacGatewayPairingPayload(
    val v: Int,
    val id: String,
    val name: String,
    val addrs: List<String>,
    val port: Int,
    /** base64 SHA-256 of the gateway certificate's SubjectPublicKeyInfo. */
    val fp: String,
    val s: String,
) {
    companion object {
        private const val MAX_LINK_LENGTH = 4096
        private val P_QUERY = Regex("[?&]p=([A-Za-z0-9_-]+)")

        /** Accepts the full link (scanned or pasted) or a bare `p` value. */
        fun parse(text: String): MacGatewayPairingPayload? {
            val trimmed = text.trim()
            if (trimmed.isEmpty() || trimmed.length > MAX_LINK_LENGTH) return null
            val encoded = P_QUERY.find(trimmed)?.groupValues?.get(1) ?: trimmed
            val bytes = runCatching { Base64.getUrlDecoder().decode(encoded) }.getOrNull() ?: return null
            val payload = runCatching {
                MacGatewayJson.decodeFromString(serializer(), bytes.decodeToString())
            }.getOrNull() ?: return null
            val valid = payload.v == 1 && payload.addrs.isNotEmpty() && payload.port in 1..65535 &&
                payload.fp.isNotEmpty() && payload.s.isNotEmpty()
            return payload.takeIf { valid }
        }
    }
}

/** Non-secret part of a pairing; the device token is stored next to it in app-private storage. */
@Serializable
data class MacGatewayConnection(
    val gatewayId: String,
    val name: String,
    val addrs: List<String>,
    val port: Int,
    val fingerprint: String,
    val deviceId: String,
)

/** `GET /v1/status`. Dates are seconds since 1970; absent optionals are omitted by the Mac. */
@Serializable
data class MacGatewayStatus(
    val gateway: Gateway,
    val device: Device,
    val serverTime: Double,
    val health: Health? = null,
    /** Synara web address for replying to / approving Synara-hosted tasks; opened in the browser. */
    val synaraURL: String? = null,
    val sessions: List<Session>,
) {
    @Serializable
    data class Gateway(val id: String, val name: String)

    @Serializable
    data class Device(val id: String, val name: String, val pushRegistered: Boolean)

    @Serializable
    data class Health(
        val sampledAt: Double,
        val diskFreeBytes: Long? = null,
        val batteryPercent: Int? = null,
        val onBattery: Boolean? = null,
        val memoryPressure: String,
        val thermal: String,
    )

    @Serializable
    data class Session(
        val key: String,
        val agent: String,
        val subject: String,
        val origin: String,
        /** App hosting the session on the Mac, e.g. "Synara". */
        val host: String? = null,
        val state: String,
        val waitReason: String? = null,
        val abnormal: Boolean,
        val monitored: Boolean,
        val updatedAt: Double,
    )
}

sealed class MacGatewayException(message: String) : Exception(message) {
    class Unreachable : MacGatewayException("unreachable")

    /** The Mac answered with a different certificate than the one pinned at pairing. */
    class CertificateMismatch : MacGatewayException("certificate mismatch")
    class Unauthorized : MacGatewayException("unauthorized")
    class Server(message: String) : MacGatewayException(message)
    class InvalidResponse : MacGatewayException("invalid response")
}

@Serializable
data class MacGatewayPairResponse(val deviceId: String, val token: String, val gatewayId: String, val gatewayName: String)

/**
 * One HTTPS client per gateway. Trust is the SPKI SHA-256 from the QR code, never the system store (the
 * certificate is self-signed); pinning the key rather than a hostname keeps working when the Mac's IP
 * changes. Candidate addresses are tried in order and the first that answers is remembered.
 */
class MacGatewayClient(
    private val addrs: List<String>,
    private val port: Int,
    private val fingerprint: String,
    private val token: String?,
) {
    private class PinMismatchException : CertificateException("SPKI pin mismatch")

    private val trustManager = object : X509TrustManager {
        override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
            val spki = chain.firstOrNull()?.publicKey?.encoded ?: throw PinMismatchException()
            val actual = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(spki))
            if (actual != fingerprint) throw PinMismatchException()
        }

        override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) =
            throw CertificateException("client certificates are not used")

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private val http = OkHttpClient.Builder()
        .sslSocketFactory(SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustManager), null) }.socketFactory, trustManager)
        // Addresses are `.local` names and raw IPs that the certificate can't list; the key pin is the trust anchor.
        .hostnameVerifier { _, _ -> true }
        .connectTimeout(5, TimeUnit.SECONDS)
        // test-push waits on FCM inside the request and the Mac answers within 20 s; timing out earlier
        // would retry the next address and send the push twice.
        .readTimeout(25, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var preferredAddress: String? = null

    suspend fun pair(secret: String, deviceName: String): MacGatewayPairResponse =
        decode(send("POST", listOf("v1", "pair"), mapOf("secret" to secret, "platform" to "android", "deviceName" to deviceName)))

    suspend fun status(): MacGatewayStatus = decode(send("GET", listOf("v1", "status")))

    suspend fun setMonitored(taskKey: String, monitored: Boolean) {
        send("POST", listOf("v1", "tasks", taskKey, "monitor"), mapOf("monitored" to monitored))
    }

    suspend fun registerPushToken(pushToken: String) {
        send("POST", listOf("v1", "push-token"), mapOf("token" to pushToken, "platform" to "android"))
    }

    suspend fun testPush() {
        send("POST", listOf("v1", "test-push"))
    }

    suspend fun unpair() {
        send("POST", listOf("v1", "unpair"))
    }

    private inline fun <reified T> decode(text: String): T =
        runCatching { MacGatewayJson.decodeFromString<T>(text) }.getOrElse { throw MacGatewayException.InvalidResponse() }

    private suspend fun send(method: String, segments: List<String>, body: Map<String, Any>? = null): String =
        withContext(Dispatchers.IO) {
            val requestBody = body?.let { map ->
                JsonObject(map.mapValues { (_, value) ->
                    when (value) {
                        is Boolean -> JsonPrimitive(value)
                        else -> JsonPrimitive(value.toString())
                    }
                }).toString().toRequestBody("application/json".toMediaType())
            }
            val ordered = preferredAddress?.let { preferred -> listOf(preferred) + addrs.filter { it != preferred } } ?: addrs
            var sawPinMismatch = false
            for (address in ordered) {
                val url = HttpUrl.Builder().scheme("https").host(address).port(port)
                    .apply { segments.forEach { addPathSegment(it) } }
                    .build()
                val request = Request.Builder().url(url)
                    .method(method, requestBody ?: if (method == "POST") ByteArray(0).toRequestBody() else null)
                    .apply { token?.let { header("Authorization", "Bearer $it") } }
                    .build()
                val (code, text) = try {
                    http.newCall(request).execute().use { it.code to it.body.string() }
                } catch (error: IOException) {
                    if (generateSequence<Throwable>(error) { it.cause }.any { it is PinMismatchException }) sawPinMismatch = true
                    continue
                }
                preferredAddress = address
                when {
                    code in 200..299 -> return@withContext text
                    code == 401 -> throw MacGatewayException.Unauthorized()
                    else -> {
                        val message = runCatching {
                            MacGatewayJson.parseToJsonElement(text).jsonObject["error"]?.jsonPrimitive?.content
                        }.getOrNull()
                        throw MacGatewayException.Server(message ?: "HTTP $code")
                    }
                }
            }
            throw if (sawPinMismatch) MacGatewayException.CertificateMismatch() else MacGatewayException.Unreachable()
        }
}
