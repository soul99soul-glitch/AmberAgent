package me.rerere.rikkahub.data.agent.board.hotlist

import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException

class HotListSafeFetcher(
    private val timeoutMs: Long = 10_000L,
) {
    suspend fun fetch(
        provider: HotListProvider,
        cachedSnapshot: suspend () -> HotListProviderSnapshot?,
        saveResult: suspend (HotListResult) -> Unit,
    ): HotListProviderSnapshot {
        val result = try {
            withTimeout(timeoutMs) { provider.fetch(limit = 50) }
                .let { Result.success(it) }
        } catch (error: TimeoutCancellationException) {
            Result.failure(error)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            Result.failure(error)
        }
        result.onSuccess { hotListResult ->
            saveResult(hotListResult)
            return HotListProviderSnapshot(
                providerId = provider.id,
                providerName = provider.displayName,
                items = hotListResult.items,
                fetchedAt = hotListResult.fetchedAt,
            )
        }
        val error = result.exceptionOrNull()?.message?.take(160) ?: "fetch failed"
        val stale = cachedSnapshot()
        if (stale != null) return stale.copy(stale = true, error = error)
        return HotListProviderSnapshot(
            providerId = provider.id,
            providerName = provider.displayName,
            items = emptyList(),
            fetchedAt = 0L,
            stale = true,
            error = error,
        )
    }
}

object DeepReadCachePolicy {
    fun isFresh(expiresAt: Long, now: Long = System.currentTimeMillis()): Boolean = expiresAt > now
}
