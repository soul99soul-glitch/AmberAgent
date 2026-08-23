package app.amber.core.ai

import android.content.Context
import app.amber.agent.BuildConfig
import app.amber.common.android.LogEntry
import app.amber.common.android.Logging
import app.amber.core.settings.PreferencesKeys
import app.amber.core.settings.settingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
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

class RequestLoggingInterceptor(
    context: Context,
) : Interceptor {
    // 跟随设置开关（PreferencesKeys.REQUEST_LOGGING_ENABLED，默认 true）实时切换；
    // 关闭时直接放行，不再记录请求日志。
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var enabled = true

    init {
        scope.launch {
            context.settingsStore.data
                .map { it[PreferencesKeys.REQUEST_LOGGING_ENABLED] ?: true }
                .distinctUntilChanged()
                .collect { enabled = it }
        }
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!enabled) return chain.proceed(request)

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
