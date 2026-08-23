package app.amber.core.ai

import app.amber.agent.BuildConfig
import app.amber.common.android.LogEntry
import app.amber.common.android.Logging
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response
import okio.Buffer

private val SENSITIVE_HEADERS = setOf(
    "authorization",
    "proxy-authorization",
    "cookie",
    "set-cookie",
    "x-api-key",
    "api-key",
    "x-goog-api-key",
    "x-auth-token",
)

private val SENSITIVE_QUERY_PARAMS = setOf(
    "api_key",
    "apikey",
    "key",
    "token",
    "access_token",
    "refresh_token",
    "client_secret",
    "code",
    "sig",
    "signature",
)

class RequestLoggingInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val startTime = System.currentTimeMillis()

        val requestHeaders = request.headers.toRedactedMap()
        // Bodies carry prompts and OAuth secrets; keep them out of non-debug builds.
        val requestBody = if (BuildConfig.DEBUG) {
            request.body?.let { body ->
                val buffer = Buffer()
                body.writeTo(buffer)
                buffer.readUtf8()
            }
        } else {
            null
        }
        val url = request.url.redactedString()

        val response: Response
        var error: String? = null

        try {
            response = chain.proceed(request)
        } catch (e: Exception) {
            error = e.message
            Logging.logRequest(
                LogEntry.RequestLog(
                    tag = "HTTP",
                    url = url,
                    method = request.method,
                    requestHeaders = requestHeaders,
                    requestBody = requestBody,
                    error = error
                )
            )
            throw e
        }

        val durationMs = System.currentTimeMillis() - startTime
        val responseHeaders = response.headers.toRedactedMap()

        Logging.logRequest(
            LogEntry.RequestLog(
                tag = "HTTP",
                url = url,
                method = request.method,
                requestHeaders = requestHeaders,
                requestBody = requestBody,
                responseCode = response.code,
                responseHeaders = responseHeaders,
                durationMs = durationMs,
                error = error
            )
        )

        return response
    }

    private fun okhttp3.Headers.toRedactedMap(): Map<String, String> {
        return names().associateWith { name ->
            if (name.lowercase() in SENSITIVE_HEADERS) "[redacted]" else get(name) ?: ""
        }
    }

    private fun HttpUrl.redactedString(): String {
        val sensitive = queryParameterNames.filter { it.lowercase() in SENSITIVE_QUERY_PARAMS }
        if (sensitive.isEmpty()) return toString()
        val builder = newBuilder()
        sensitive.forEach { builder.setQueryParameter(it, "[redacted]") }
        return builder.build().toString()
    }
}
