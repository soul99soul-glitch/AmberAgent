package me.rerere.rikkahub.data.ai

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

class GenerationRetryPolicyTest {
    private val setting = GenerationRetrySetting(jitterRatio = 0f)

    @Test
    fun backoffUsesOneTwoFourEightSixteenSeconds() {
        val delays = (1..5).map { attempt ->
            GenerationFailureClassifier.delayForAttempt(attempt, setting)
        }

        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L), delays)
    }

    @Test
    fun transientNetworkAndServerFailuresAreRetryable() {
        assertTrue(GenerationFailureClassifier.classify(IOException("connection reset")).retryable)
        assertTrue(GenerationFailureClassifier.classify(SocketTimeoutException("timed out")).retryable)
        assertTrue(GenerationFailureClassifier.classify(RuntimeException("HTTP 503 temporarily unavailable")).retryable)
        assertTrue(GenerationFailureClassifier.classify(RuntimeException("HTTP 429 rate limit")).retryable)
    }

    @Test
    fun cancellationAuthBadRequestQuotaSafetyAndContextAreNotRetryable() {
        assertFalse(GenerationFailureClassifier.classify(CancellationException("stop")).retryable)
        assertFalse(GenerationFailureClassifier.classify(RuntimeException("HTTP 401 invalid api key")).retryable)
        assertFalse(GenerationFailureClassifier.classify(RuntimeException("HTTP 403 forbidden")).retryable)
        assertFalse(GenerationFailureClassifier.classify(RuntimeException("HTTP 400 bad request")).retryable)
        assertFalse(GenerationFailureClassifier.classify(RuntimeException("insufficient_quota")).retryable)
        assertFalse(GenerationFailureClassifier.classify(RuntimeException("content_policy violation")).retryable)
        assertFalse(GenerationFailureClassifier.classify(RuntimeException("context_length exceeded")).retryable)
    }

    @Test
    fun retryLimitStopsAfterConfiguredAttempts() {
        val error = IOException("connection reset")

        assertTrue(GenerationFailureClassifier.decide(error, attempt = 5, setting = setting).retryable)
        assertFalse(GenerationFailureClassifier.decide(error, attempt = 6, setting = setting).retryable)
    }
}
