package app.amber.feature.novel

import app.amber.feature.novel.model.NovelProjectId
import app.amber.feature.novel.model.NovelRunId

/**
 * Process-local ownership for novel runs that are allowed to survive app background and route-exit interrupts.
 *
 * Durable recovery remains the responsibility of the caller. The owner token must include the caller's execution
 * generation (for example job ID + execution epoch + WorkManager ID) so a stale executor cannot release a newer owner.
 */
class NovelBackgroundRunRegistry {
    private data class Claim(
        val ownerToken: String,
        val projectId: NovelProjectId,
        val runId: NovelRunId,
    )

    private val lock = Any()
    private val claimsByOwner = mutableMapOf<String, Claim>()

    /**
     * Returns true for a new claim or an idempotent repeat of the same exact tuple.
     *
     * A token already bound to another tuple, or a run already bound to another token, is rejected.
     */
    fun claim(ownerToken: String, projectId: NovelProjectId, runId: NovelRunId): Boolean = synchronized(lock) {
        if (ownerToken.isBlank()) return@synchronized false
        val requested = Claim(ownerToken, projectId, runId)
        claimsByOwner[ownerToken]?.let { return@synchronized it == requested }
        if (claimsByOwner.values.any { it.projectId == projectId && it.runId == runId }) {
            return@synchronized false
        }
        claimsByOwner[ownerToken] = requested
        true
    }

    /** Only the current exact tuple can release the claim; stale releases are harmless no-ops. */
    fun release(ownerToken: String, projectId: NovelProjectId, runId: NovelRunId): Boolean = synchronized(lock) {
        val expected = Claim(ownerToken, projectId, runId)
        if (claimsByOwner[ownerToken] != expected) return@synchronized false
        claimsByOwner.remove(ownerToken)
        true
    }

    /** Returns a detached snapshot suitable for diagnostics and tests. */
    fun ownedRunIds(projectId: NovelProjectId): Set<NovelRunId> = synchronized(lock) {
        claimsByOwner.values
            .asSequence()
            .filter { it.projectId == projectId }
            .mapTo(linkedSetOf()) { it.runId }
    }

    /**
     * Keeps a project-wide interrupt atomic with respect to claim/release, closing the claim-before-start race.
     * [action] must be quick and non-blocking; [NovelCreation.interrupt] satisfies that contract.
     */
    fun <T> withOwnedRunIds(projectId: NovelProjectId, action: (Set<NovelRunId>) -> T): T = synchronized(lock) {
        action(
            claimsByOwner.values
                .asSequence()
                .filter { it.projectId == projectId }
                .mapTo(linkedSetOf()) { it.runId },
        )
    }
}
