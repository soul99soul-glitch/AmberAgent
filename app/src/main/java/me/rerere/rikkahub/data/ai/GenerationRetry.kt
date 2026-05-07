package me.rerere.rikkahub.data.ai

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import kotlin.math.min
import kotlin.random.Random

@Serializable
data class GenerationRetrySetting(
    val enabled: Boolean = true,
    val maxRetries: Int = 5,
    val initialDelayMs: Long = 1_000L,
    val maxDelayMs: Long = 16_000L,
    val jitterRatio: Float = 0.15f,
)

enum class GenerationFailureCategory {
    NETWORK,
    TIMEOUT,
    RATE_LIMIT,
    SERVER,
    AUTH,
    BAD_REQUEST,
    QUOTA,
    SAFETY,
    CONTEXT,
    CANCELLED,
    TEMPORARY,
    UNKNOWN,
}

data class GenerationFailureClassification(
    val category: GenerationFailureCategory,
    val retryable: Boolean,
    val reason: String,
)

data class GenerationRetryDecision(
    val retryable: Boolean,
    val category: GenerationFailureCategory,
    val reason: String,
    val delayMs: Long = 0L,
)

object GenerationFailureClassifier {
    fun classify(error: Throwable): GenerationFailureClassification {
        if (error is CancellationException) {
            return GenerationFailureClassification(
                category = GenerationFailureCategory.CANCELLED,
                retryable = false,
                reason = "generation was cancelled",
            )
        }

        val text = error.errorText()

        return when {
            text.containsAny("context_length", "context too large", "maximum context", "too many tokens") ->
                GenerationFailureClassification(GenerationFailureCategory.CONTEXT, false, "context is too large")

            text.containsAny("insufficient_quota", "quota", "balance", "余额", "credit") ->
                GenerationFailureClassification(GenerationFailureCategory.QUOTA, false, "quota or balance is insufficient")

            text.containsAny("content_policy", "safety", "moderation", "blocked by policy", "安全") ->
                GenerationFailureClassification(GenerationFailureCategory.SAFETY, false, "content was blocked by safety policy")

            text.containsAny("401", "unauthorized", "invalid api key", "api key", "authentication") ->
                GenerationFailureClassification(GenerationFailureCategory.AUTH, false, "authentication failed")

            text.containsAny("403", "forbidden", "permission denied") ->
                GenerationFailureClassification(GenerationFailureCategory.AUTH, false, "permission was denied")

            text.containsAny("400", "bad request", "invalid request", "invalid parameter", "model not found", "model_not_found") ->
                GenerationFailureClassification(GenerationFailureCategory.BAD_REQUEST, false, "request is invalid")

            text.containsAny("429", "rate limit", "too many requests") ->
                GenerationFailureClassification(GenerationFailureCategory.RATE_LIMIT, true, "provider rate limited the request")

            text.containsAny("408", "timeout", "timed out") || error.hasCause<SocketTimeoutException>() ->
                GenerationFailureClassification(GenerationFailureCategory.TIMEOUT, true, "request timed out")

            text.containsAny("500", "502", "503", "504", "temporarily unavailable", "overloaded", "server error") ->
                GenerationFailureClassification(GenerationFailureCategory.SERVER, true, "provider is temporarily unavailable")

            error.hasNetworkCause() || text.containsAny(
                "connection reset",
                "socket closed",
                "stream was reset",
                "unexpected end",
                "eof",
                "network",
                "failed to connect",
            ) -> GenerationFailureClassification(GenerationFailureCategory.NETWORK, true, "network stream was interrupted")

            else -> GenerationFailureClassification(GenerationFailureCategory.UNKNOWN, false, "failure is not known to be retryable")
        }
    }

    fun decide(
        error: Throwable,
        attempt: Int,
        setting: GenerationRetrySetting,
        random: Random = Random.Default,
    ): GenerationRetryDecision {
        val classification = classify(error)
        if (!setting.enabled || !classification.retryable || attempt > setting.maxRetries) {
            return GenerationRetryDecision(
                retryable = false,
                category = classification.category,
                reason = classification.reason,
            )
        }
        return GenerationRetryDecision(
            retryable = true,
            category = classification.category,
            reason = classification.reason,
            delayMs = delayForAttempt(attempt, setting, random),
        )
    }

    fun delayForAttempt(
        attempt: Int,
        setting: GenerationRetrySetting,
        random: Random = Random.Default,
    ): Long {
        val exponent = (attempt - 1).coerceAtLeast(0)
        val base = min(setting.maxDelayMs, setting.initialDelayMs shl exponent)
        val jitterRange = (base * setting.jitterRatio).toLong().coerceAtLeast(0L)
        if (jitterRange == 0L) return base
        return (base + random.nextLong(from = -jitterRange, until = jitterRange + 1)).coerceAtLeast(0L)
    }
}

private fun Throwable.errorText(): String = buildString {
    var current: Throwable? = this@errorText
    val seen = HashSet<Throwable>()
    while (current != null && seen.add(current)) {
        append(current::class.java.simpleName).append(':').append(current.message.orEmpty()).append('\n')
        current = current.cause
    }
}.lowercase()

private inline fun <reified T : Throwable> Throwable.hasCause(): Boolean {
    var current: Throwable? = this
    val seen = HashSet<Throwable>()
    while (current != null && seen.add(current)) {
        if (current is T) return true
        current = current.cause
    }
    return false
}

private fun Throwable.hasNetworkCause(): Boolean =
    hasCause<UnknownHostException>() ||
        hasCause<ConnectException>() ||
        hasCause<SocketException>() ||
        hasCause<SSLException>()

private fun String.containsAny(vararg needles: String): Boolean =
    needles.any { contains(it) }
