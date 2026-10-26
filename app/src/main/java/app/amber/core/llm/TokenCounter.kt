package app.amber.core.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class TokenCounter {
    suspend fun countBatch(segments: List<TokenSegment>): List<Int> =
        withContext(Dispatchers.Default) {
            val ids = segments.map { it.tokenizerId }.toTypedArray()
            val texts = segments.map { it.text }.toTypedArray()

            val nativeResult = runCatching { TokenCounterNative.countBatch(ids, texts) }.getOrNull()
            if (nativeResult != null) {
                return@withContext nativeResult.toList()
            }

            segments.map { approximateTokenCount(it.tokenizerId, it.text) }
        }

    private fun approximateTokenCount(tokenizerId: String, text: String): Int {
        val ratio = when (tokenizerId) {
            "o200k_base", "cl100k_base" -> 4.0
            "claude" -> 3.5
            "gemini" -> 4.0
            else -> 4.0
        }
        return (text.length / ratio).toInt().coerceAtLeast(1)
    }
}

data class TokenSegment(
    val tokenizerId: String,
    val text: String,
)
