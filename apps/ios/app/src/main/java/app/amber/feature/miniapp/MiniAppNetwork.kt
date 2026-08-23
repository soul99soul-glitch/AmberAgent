package app.amber.feature.miniapp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.util.Base64
import java.util.Locale
import java.util.concurrent.TimeUnit

class MiniAppUrlGuard(
    private val resolver: (String) -> List<InetAddress> = { host -> InetAddress.getAllByName(host).toList() },
) {
    fun check(rawUrl: String): HttpUrl {
        val url = rawUrl.trim().toHttpUrlOrNull()
            ?: throw MiniAppValidationException("Invalid URL")
        if (url.scheme != "https") {
            throw MiniAppValidationException("Only https URLs are allowed")
        }
        resolveAllowed(url.host)
        return url
    }

    fun resolveAllowed(host: String): List<InetAddress> {
        val addresses = runCatching { resolver(host) }
            .getOrElse { throw MiniAppValidationException("Unable to resolve host") }
        if (addresses.isEmpty() || addresses.any(::isBlockedAddress)) {
            throw MiniAppValidationException("Blocked private or reserved host")
        }
        return addresses
    }

    private fun isBlockedAddress(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress ||
            address.isLoopbackAddress ||
            address.isLinkLocalAddress ||
            address.isSiteLocalAddress ||
            address.isMulticastAddress
        ) return true
        val bytes = address.address
        if (address is Inet4Address && bytes.size == 4) {
            val a = bytes[0].toInt() and 0xff
            val b = bytes[1].toInt() and 0xff
            return a == 0 ||
                a == 10 ||
                a == 127 ||
                (a == 100 && b in 64..127) ||
                (a == 169 && b == 254) ||
                (a == 172 && b in 16..31) ||
                (a == 192 && b == 168) ||
                a >= 224
        }
        if (address is Inet6Address && bytes.size == 16) {
            val first = bytes[0].toInt() and 0xff
            val second = bytes[1].toInt() and 0xff
            val ipv4Mapped = bytes.take(10).all { it.toInt() == 0 } &&
                bytes[10].toInt() == 0xff &&
                bytes[11].toInt() == 0xff
            if (ipv4Mapped) {
                return isBlockedAddress(InetAddress.getByAddress(bytes.copyOfRange(12, 16)))
            }
            return first == 0 ||
                first == 0xff ||
                (first and 0xfe) == 0xfc ||
                (first == 0xfe && (second and 0xc0) == 0x80)
        }
        return true
    }
}

class MiniAppHttpClient(
    private val urlGuard: MiniAppUrlGuard = MiniAppUrlGuard(),
    private val client: OkHttpClient = OkHttpClient.Builder()
        .dns { hostname -> urlGuard.resolveAllowed(hostname) }
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .build(),
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    suspend fun fetch(params: JsonObject): JsonElement = withContext(Dispatchers.IO) {
        val request = buildRequest(params, maxBodyBytes = MAX_REQUEST_BODY_BYTES)
        val responseType = params.string("responseType")?.lowercase(Locale.ROOT) ?: "text"
        val response = executeChecked(request, maxRedirects = 3)
        response.use {
            val bytes = it.body?.byteStream()?.use { input -> input.readCapped(MAX_RESPONSE_BYTES) } ?: ByteArray(0)
            buildJsonObject {
                put("status", it.code)
                put("ok", it.code in 200..299)
                put("url", it.request.url.toString())
                put("contentType", it.header("content-type").orEmpty().take(120))
                put(
                    "body",
                    when (responseType) {
                        "json" -> runCatching { json.parseToJsonElement(bytes.decodeToString()) }.getOrElse { JsonNull }
                        "dataurl" -> JsonPrimitive(bytes.toDataUrl(it.header("content-type") ?: "application/octet-stream"))
                        else -> JsonPrimitive(bytes.decodeToString().take(MAX_TEXT_CHARS))
                    }
                )
            }
        }
    }

    suspend fun fetchImage(rawUrl: String): MiniAppImageResult = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(urlGuard.check(rawUrl))
            .header("Accept", "image/avif,image/webp,image/png,image/jpeg,image/svg+xml,image/*;q=0.8")
            .header("User-Agent", MINI_APP_USER_AGENT)
            .get()
            .build()
        val response = executeChecked(request, maxRedirects = 3)
        response.use {
            val contentType = it.header("content-type").orEmpty().substringBefore(";").lowercase(Locale.ROOT)
            if (!contentType.startsWith("image/")) {
                throw MiniAppValidationException("URL did not return an image")
            }
            val bytes = it.body?.byteStream()?.use { input -> input.readCapped(MAX_IMAGE_BYTES) } ?: ByteArray(0)
            MiniAppImageResult(bytes = bytes, contentType = contentType.ifBlank { "image/png" })
        }
    }

    private fun buildRequest(params: JsonObject, maxBodyBytes: Int): Request {
        val url = urlGuard.check(params.string("url") ?: throw MiniAppValidationException("Missing url"))
        val method = (params.string("method") ?: "GET").uppercase(Locale.ROOT)
        if (method !in setOf("GET", "POST")) {
            throw MiniAppValidationException("Only GET and POST are supported")
        }
        val builder = Request.Builder()
            .url(url)
            .header("Accept", "application/json,text/plain,*/*;q=0.7")
            .header("User-Agent", MINI_APP_USER_AGENT)
        params["headers"]?.jsonObject?.forEach { (name, value) ->
            val normalized = name.trim()
            if (normalized.isAllowedHeader()) {
                builder.header(normalized, value.jsonPrimitive.contentOrNull.orEmpty().take(500))
            }
        }
        val bodyText = params.string("body")
        val body = bodyText?.let {
            val bytes = it.encodeToByteArray()
            if (bytes.size > maxBodyBytes) throw MiniAppValidationException("Request body is too large")
            it.toRequestBody(params.string("contentType")?.toMediaTypeOrNull())
        }
        return if (method == "POST") builder.post(body ?: ByteArray(0).toRequestBody()).build() else builder.get().build()
    }

    private fun executeChecked(initial: Request, maxRedirects: Int): okhttp3.Response {
        var request = initial
        repeat(maxRedirects + 1) { redirectIndex ->
            urlGuard.check(request.url.toString())
            val response = client.newCall(request).execute()
            if (response.code !in 300..399) return response
            val location = response.header("location")
            response.close()
            if (redirectIndex == maxRedirects || location.isNullOrBlank()) {
                throw MiniAppValidationException("Too many redirects")
            }
            val next = request.url.resolve(location) ?: throw MiniAppValidationException("Invalid redirect")
            urlGuard.check(next.toString())
            request = request.newBuilder().url(next).get().build()
        }
        throw MiniAppValidationException("Too many redirects")
    }

    private fun String.isAllowedHeader(): Boolean {
        val lower = lowercase(Locale.ROOT)
        if (lower == "cookie" || lower == "authorization") return false
        if (lower.startsWith("proxy-") || lower.startsWith("x-forwarded-")) return false
        return lower in setOf("accept", "content-type", "user-agent")
    }

    private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

    private companion object {
        const val MINI_APP_USER_AGENT = "AmberAgent-MiniApp/2"
        const val MAX_REQUEST_BODY_BYTES = 128 * 1024
        const val MAX_RESPONSE_BYTES = 1024 * 1024
        const val MAX_TEXT_CHARS = 512 * 1024
        const val MAX_IMAGE_BYTES = 2 * 1024 * 1024
    }
}

data class MiniAppImageResult(
    val bytes: ByteArray,
    val contentType: String,
)

private fun java.io.InputStream.readCapped(maxBytes: Int): ByteArray {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0
    while (true) {
        val read = read(buffer)
        if (read < 0) break
        total += read
        if (total > maxBytes) {
            throw MiniAppValidationException("Response is too large")
        }
        out.write(buffer, 0, read)
    }
    return out.toByteArray()
}

private fun ByteArray.toDataUrl(contentType: String): String {
    return "data:$contentType;base64," + Base64.getEncoder().encodeToString(this)
}
