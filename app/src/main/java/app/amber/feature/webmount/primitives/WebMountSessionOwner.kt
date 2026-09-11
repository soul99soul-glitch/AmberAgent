package app.amber.feature.webmount.primitives

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.Locale
import java.util.UUID

/** The actor that currently owns a pooled WebMount session. */
enum class WebMountOwner {
    NONE,
    AGENT,
    HUMAN,
}

/** Stable machine-readable failures returned before a WebView side effect. */
enum class WebMountLeaseFailure(val code: String) {
    OWNER_CONFLICT("owner_conflict"),
    MISSING_CONVERSATION("missing_conversation"),
    MISSING_RUN("missing_run"),
    CONVERSATION_MISMATCH("conversation_mismatch"),
    RUN_MISMATCH("run_mismatch"),
    NEEDS_REOPEN("needs_reopen"),
    SESSION_UNAVAILABLE("session_unavailable"),
}

/** Raised when a page side effect loses its owner before it is dispatched. */
class WebMountLeaseInvalidatedException(
    message: String = "webmount lease was invalidated before dispatch",
) : RuntimeException(message)

/** Metadata that survives a process restart; it intentionally excludes WebView state and secrets. */
data class WebMountSessionMetadata(
    val sessionId: String,
    val conversationId: String? = null,
    val runId: String? = null,
    val redactedUrl: String? = null,
    val title: String? = null,
    val status: String = SessionHandle.LoadStatus.IDLE.wireName,
    val owner: WebMountOwner = WebMountOwner.NONE,
    val leaseExpiresAtMs: Long? = null,
    val needsReopen: Boolean = false,
    val lastActivityMs: Long = System.currentTimeMillis(),
)

/** A live lease. Keep this object until the UI leaves or the tool finishes. */
data class WebMountLease(
    val leaseId: String,
    val sessionId: String,
    val owner: WebMountOwner,
    val conversationId: String?,
    val runId: String?,
    val handle: SessionHandle,
)

sealed interface WebMountLeaseResult {
    data class Granted(val lease: WebMountLease) : WebMountLeaseResult

    data class Rejected(
        val failure: WebMountLeaseFailure,
        val metadata: WebMountSessionMetadata?,
    ) : WebMountLeaseResult
}

/**
 * Amber's narrow owner for pooled WebMount sessions.
 *
 * The store owns the compare-and-set lease and the small metadata journal;
 * [WebViewPool] still owns actual WebView construction/destruction. Tools and
 * UI must acquire through this class so a human-held WebView cannot be mutated
 * by an agent at the same time.
 */
class WebMountSessionOwner(
    context: Context,
    private val pool: WebViewPool,
) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val lock = Any()
    private val records = linkedMapOf<String, WebMountSessionMetadata>()
    private val activeLeases = linkedMapOf<String, ActiveLease>()
    /** Explicitly closed ids stay invalid for the life of this process. */
    private val closedSessionIds = mutableSetOf<String>()
    /** Prevent late tool calls from re-claiming a run after ChatService ends it. */
    private val endedRuns = linkedMapOf<String, Long>()
    private val _sessions = MutableStateFlow<List<WebMountSessionMetadata>>(emptyList())

    /** Ordered newest-first for the chat task card and the session page. */
    val sessions: StateFlow<List<WebMountSessionMetadata>> = _sessions.asStateFlow()

    init {
        synchronized(lock) {
            readLocked().forEach { record ->
                if (record.sessionId.isBlank()) return@forEach
                // A process restart invalidates the old live handle and lease.
                // Keep the safe summary so the UI can offer an explicit reopen.
                records[record.sessionId] = record.copy(
                    owner = WebMountOwner.NONE,
                    leaseExpiresAtMs = null,
                    runId = null,
                    status = if (record.status == SessionHandle.LoadStatus.IDLE.wireName) {
                        record.status
                    } else {
                        STATUS_REOPEN_REQUIRED
                    },
                    needsReopen = record.status != SessionHandle.LoadStatus.IDLE.wireName || record.needsReopen,
                )
            }
            publishLocked()
        }
    }

    fun metadata(sessionId: String): WebMountSessionMetadata? = synchronized(lock) {
        records[sessionId]
    }

    /**
     * Atomically claim one session and return the actual pooled WebView.
     * AGENT leases require both conversation and run identity. HUMAN leases
     * intentionally have no run identity and block all agent writes.
     */
    suspend fun acquire(
        sessionId: String,
        actor: WebMountOwner,
        conversationId: String? = null,
        runId: String? = null,
        allowReopen: Boolean = false,
    ): WebMountLeaseResult {
        val normalizedSessionId = sessionId.trim()
        if (normalizedSessionId.isBlank()) {
            return WebMountLeaseResult.Rejected(WebMountLeaseFailure.SESSION_UNAVAILABLE, null)
        }
        val normalizedConversationId = conversationId?.trim()?.takeIf { it.isNotEmpty() }
        val normalizedRunId = runId?.trim()?.takeIf { it.isNotEmpty() }
        val leaseId = "wmlease_${UUID.randomUUID()}"
        val now = System.currentTimeMillis()
        val pending = synchronized(lock) {
            expireAgentLeasesLocked(now)
            if (normalizedSessionId in closedSessionIds) {
                return@synchronized WebMountLeaseResult.Rejected(
                    WebMountLeaseFailure.SESSION_UNAVAILABLE,
                    null,
                )
            }
            val current = records[normalizedSessionId]
            val active = activeLeases.values.firstOrNull { it.sessionId == normalizedSessionId }
            if (active != null) {
                return@synchronized WebMountLeaseResult.Rejected(
                    WebMountLeaseFailure.OWNER_CONFLICT,
                    current,
                )
            }
            if (actor == WebMountOwner.AGENT &&
                normalizedRunId != null &&
                endedRuns.containsKey(normalizedRunId)
            ) {
                return@synchronized WebMountLeaseResult.Rejected(
                    WebMountLeaseFailure.RUN_MISMATCH,
                    current,
                )
            }
            if (current?.needsReopen == true && !allowReopen) {
                return@synchronized WebMountLeaseResult.Rejected(
                    WebMountLeaseFailure.NEEDS_REOPEN,
                    current,
                )
            }
            val boundConversation = current?.conversationId
            if (boundConversation != null &&
                normalizedConversationId != null &&
                boundConversation != normalizedConversationId
            ) {
                return@synchronized WebMountLeaseResult.Rejected(
                    WebMountLeaseFailure.CONVERSATION_MISMATCH,
                    current,
                )
            }
            if (actor == WebMountOwner.AGENT) {
                if (normalizedConversationId == null) {
                    return@synchronized WebMountLeaseResult.Rejected(
                        WebMountLeaseFailure.MISSING_CONVERSATION,
                        current,
                    )
                }
                if (normalizedRunId == null) {
                    return@synchronized WebMountLeaseResult.Rejected(
                        WebMountLeaseFailure.MISSING_RUN,
                        current,
                    )
                }
                val boundRun = current?.runId
                if (boundRun != null && boundRun != normalizedRunId) {
                    return@synchronized WebMountLeaseResult.Rejected(
                        WebMountLeaseFailure.RUN_MISMATCH,
                        current,
                    )
                }
            }
            val updated = (current ?: WebMountSessionMetadata(normalizedSessionId)).copy(
                conversationId = normalizedConversationId ?: current?.conversationId,
                runId = if (actor == WebMountOwner.AGENT) normalizedRunId else null,
                owner = actor,
                leaseExpiresAtMs = if (actor == WebMountOwner.AGENT) now + AGENT_LEASE_TTL_MS else null,
                needsReopen = current?.needsReopen ?: false,
                status = current?.status ?: SessionHandle.LoadStatus.IDLE.wireName,
                lastActivityMs = now,
            )
            records[normalizedSessionId] = updated
            activeLeases[leaseId] = ActiveLease(
                leaseId = leaseId,
                sessionId = normalizedSessionId,
                owner = actor,
                conversationId = normalizedConversationId ?: current?.conversationId,
                runId = if (actor == WebMountOwner.AGENT) normalizedRunId else null,
                expiresAtMs = if (actor == WebMountOwner.AGENT) now + AGENT_LEASE_TTL_MS else null,
            )
            publishLocked()
            null
        }
        if (pending != null) return pending

        val handle = try {
            pool.acquire(normalizedSessionId, reservationToken = leaseId)
        } catch (cancel: CancellationException) {
            rollbackReservation(leaseId, normalizedSessionId, markReopen = false)
            throw cancel
        } catch (_: Throwable) {
            rollbackReservation(leaseId, normalizedSessionId, markReopen = true)
            return WebMountLeaseResult.Rejected(
                WebMountLeaseFailure.SESSION_UNAVAILABLE,
                metadata(normalizedSessionId),
            )
        }
        try {
            // A cancellation may arrive after pool.acquire has resumed but
            // before the lease is returned. Do not grant a lease to a dead
            // tool coroutine; release only this handle below.
            currentCoroutineContext().ensureActive()
        } catch (cancel: CancellationException) {
            rollbackReservation(leaseId, normalizedSessionId, markReopen = false)
            pool.release(
                handle,
                reason = "lease acquisition cancelled",
                expectedPinToken = leaseId,
            )
            throw cancel
        }
        val reservationStillActive = synchronized(lock) {
            expireAgentLeasesLocked(System.currentTimeMillis())
            val active = activeLeases[leaseId]
            if (active == null) {
                false
            } else {
                // Pin under the same owner lock as release/close. If a close
                // races the suspended pool acquire, it cannot leave a fresh
                // handle evictable before this check.
                pool.pin(normalizedSessionId, leaseId)
                handle.setDialogLeaseContext(
                    leaseId = leaseId,
                    runId = if (active.owner == WebMountOwner.AGENT) active.runId else null,
                )
                records[normalizedSessionId]?.let { current ->
                    records[normalizedSessionId] = current.copy(
                        status = if (current.status == STATUS_REOPEN_REQUIRED) {
                            handle.loadState.value.status.wireName
                        } else {
                            current.status
                        },
                        needsReopen = false,
                        lastActivityMs = System.currentTimeMillis(),
                    )
                }
                publishLocked()
                true
            }
        }
        if (!reservationStillActive) {
            // The close path may already have released this session. The
            // handle-aware pool release avoids destroying a newer lease that
            // raced in after that close.
            pool.release(
                handle,
                reason = "lease canceled before acquire completed",
                expectedPinToken = leaseId,
            )
            return WebMountLeaseResult.Rejected(
                WebMountLeaseFailure.SESSION_UNAVAILABLE,
                metadata(normalizedSessionId),
            )
        }
        return WebMountLeaseResult.Granted(
            WebMountLease(
                leaseId = leaseId,
                sessionId = normalizedSessionId,
                owner = actor,
                conversationId = normalizedConversationId,
                runId = if (actor == WebMountOwner.AGENT) normalizedRunId else null,
                handle = handle,
            )
        )
    }

    /** Explicit user action to recreate a process- or LRU-lost session. */
    suspend fun reopen(
        sessionId: String,
        conversationId: String? = null,
    ): WebMountLeaseResult {
        val normalizedSessionId = sessionId.trim()
        val safeUrl = safeReopenUrl(metadata(normalizedSessionId)?.redactedUrl)
        if (safeUrl == null) {
            return WebMountLeaseResult.Rejected(
                WebMountLeaseFailure.NEEDS_REOPEN,
                metadata(normalizedSessionId),
            )
        }

        val claimed = acquire(
            sessionId = normalizedSessionId,
            actor = WebMountOwner.HUMAN,
            conversationId = conversationId,
            allowReopen = true,
        )
        val lease = (claimed as? WebMountLeaseResult.Granted)?.lease
            ?: return claimed
        return try {
            val state = lease.handle.loadUrl(safeUrl)
            if (state.status == SessionHandle.LoadStatus.READY) {
                claimed
            } else {
                // Keep the marker while this lease is still ours, then release
                // the actual WebView. A failed reopen must never look like a
                // successfully restored page to the next viewer.
                markReopenRequired(lease.leaseId, lease.sessionId)
                release(lease.leaseId, reason = "reopen failed")
                WebMountLeaseResult.Rejected(
                    WebMountLeaseFailure.NEEDS_REOPEN,
                    metadata(normalizedSessionId),
                )
            }
        } catch (cancel: CancellationException) {
            markReopenRequired(lease.leaseId, lease.sessionId)
            release(lease.leaseId, reason = "reopen cancelled")
            throw cancel
        } catch (_: Throwable) {
            markReopenRequired(lease.leaseId, lease.sessionId)
            release(lease.leaseId, reason = "reopen failed")
            WebMountLeaseResult.Rejected(
                WebMountLeaseFailure.NEEDS_REOPEN,
                metadata(normalizedSessionId),
            )
        }
    }

    /**
     * Re-check a lease immediately before a page side effect. This closes the
     * small window where a run is cancelled after a tool has acquired its
     * handle but before it dispatches JavaScript.
     */
    fun isLeaseActive(
        leaseId: String,
        conversationId: String?,
        runId: String?,
    ): Boolean = synchronized(lock) {
        expireAgentLeasesLocked(System.currentTimeMillis())
        val active = activeLeases[leaseId] ?: return@synchronized false
        active.owner == WebMountOwner.AGENT &&
            active.conversationId == conversationId &&
            active.runId == runId
    }

    /**
     * Check and dispatch one synchronous page side effect while holding the
     * owner CAS lock. Callers must invoke this from the WebView main thread;
     * the action itself must not suspend or call back into this owner.
     */
    fun dispatchIfActive(
        leaseId: String,
        conversationId: String?,
        runId: String?,
        action: () -> Unit,
    ): Boolean = synchronized(lock) {
        expireAgentLeasesLocked(System.currentTimeMillis())
        val active = activeLeases[leaseId] ?: return@synchronized false
        val normalizedConversationId = conversationId?.trim()?.takeIf { it.isNotEmpty() }
        val normalizedRunId = runId?.trim()?.takeIf { it.isNotEmpty() }
        if (active.owner != WebMountOwner.AGENT ||
            active.conversationId != normalizedConversationId ||
            active.runId != normalizedRunId
        ) {
            return@synchronized false
        }
        action()
        true
    }

    /** Clear all agent leases and the reusable binding owned by a completed run. */
    fun endRun(
        runId: String,
        conversationId: String? = null,
        reason: String = "run ended",
        preservePendingHandoff: Boolean = false,
    ) {
        val normalizedRunId = runId.trim()
        if (normalizedRunId.isBlank()) return
        val normalizedConversationId = conversationId?.trim()?.takeIf { it.isNotEmpty() }
        val (released, dialogsToCancel) = synchronized(lock) {
            endedRuns[normalizedRunId] = System.currentTimeMillis()
            while (endedRuns.size > MAX_ENDED_RUNS) {
                val oldestKey = endedRuns.entries.minByOrNull { it.value }?.key ?: break
                endedRuns.remove(oldestKey)
            }
            val matching = activeLeases.values.filter { lease ->
                lease.owner == WebMountOwner.AGENT &&
                    lease.runId == normalizedRunId &&
                    (normalizedConversationId == null || lease.conversationId == normalizedConversationId)
            }
            val sessionsToCancel = (matching.map { it.sessionId } + records.values
                .filter { record ->
                    record.owner == WebMountOwner.NONE &&
                        record.runId == normalizedRunId &&
                        (normalizedConversationId == null ||
                            record.conversationId == normalizedConversationId)
                }
                .map { it.sessionId })
                .toSet()
            matching.forEach { lease ->
                activeLeases.remove(lease.leaseId)
                records[lease.sessionId]?.let { current ->
                    records[lease.sessionId] = current.copy(
                        owner = WebMountOwner.NONE,
                        runId = null,
                        leaseExpiresAtMs = null,
                        lastActivityMs = System.currentTimeMillis(),
                    )
                }
            }
            // A tool normally releases its short lease before ChatService's
            // run-finally hook. Clear that non-active binding here as well.
            var clearedBinding = false
            records.values
                .filter { record ->
                    record.owner == WebMountOwner.NONE &&
                        record.runId == normalizedRunId &&
                        (normalizedConversationId == null ||
                            record.conversationId == normalizedConversationId)
                }
                .forEach { record ->
                    clearedBinding = true
                    records[record.sessionId] = record.copy(
                        runId = null,
                        leaseExpiresAtMs = null,
                        lastActivityMs = System.currentTimeMillis(),
                    )
            }

            // Complete the owner transition and dialog extraction under the
            // same lock. A HUMAN acquire can then create a new dialog after
            // this point without being swept by the old run's cleanup.
            val dialogsToCancel = buildList {
                sessionsToCancel.forEach { sessionId ->
                    val handle = pool.peek(sessionId) ?: return@forEach
                    if (preservePendingHandoff) {
                        handle.clearDialogLeaseContext(normalizedRunId)
                    } else {
                        val dialogs = handle.takePendingJsDialogsForRun(normalizedRunId)
                        if (dialogs.isNotEmpty()) add(DialogCancellation(handle, dialogs))
                    }
                }
            }
            if (matching.isNotEmpty() || clearedBinding) publishLocked()
            matching to dialogsToCancel
        }
        released.forEach { lease ->
            pool.unpin(lease.sessionId, lease.leaseId)
        }
        dialogsToCancel.forEach { cancellation ->
            cancellation.handle.dispatchJsDialogCancellation(cancellation.dialogs, reason)
        }
    }

    /**
     * Close the session represented by an active AGENT lease. The identity
     * check, tombstone, and exact-handle capture are one owner transition so
     * an old tool cannot close a HUMAN takeover that arrived meanwhile.
     */
    suspend fun closeIfLeaseActive(
        leaseId: String,
        conversationId: String?,
        runId: String?,
        reason: String = "closed",
    ): Boolean {
        val closed = synchronized(lock) {
            expireAgentLeasesLocked(System.currentTimeMillis())
            val active = activeLeases[leaseId] ?: return@synchronized null
            val normalizedConversationId = conversationId?.trim()?.takeIf { it.isNotEmpty() }
            val normalizedRunId = runId?.trim()?.takeIf { it.isNotEmpty() }
            if (active.owner != WebMountOwner.AGENT ||
                active.conversationId != normalizedConversationId ||
                active.runId != normalizedRunId
            ) {
                return@synchronized null
            }
            activeLeases.remove(leaseId)
            closedSessionIds += active.sessionId
            records.remove(active.sessionId)
            val handle = pool.peek(active.sessionId)
            val cancellation = handle?.let { currentHandle ->
                val dialogs = currentHandle.takePendingJsDialogsForLease(active.leaseId)
                if (dialogs.isNotEmpty()) DialogCancellation(currentHandle, dialogs) else null
            }
            publishLocked()
            CloseResult(active, handle, cancellation)
        } ?: return false

        closed.cancellation?.handle?.dispatchJsDialogCancellation(closed.cancellation.dialogs, reason)
        if (closed.handle != null) {
            pool.release(closed.handle, reason, expectedPinToken = leaseId)
        } else {
            // The owner may have been cancelled while pool.acquire was still
            // suspended; keep the token closed so the late handle is rejected.
            pool.unpin(closed.activeLease.sessionId, leaseId)
        }
        return true
    }

    /**
     * Release control while retaining the same live WebView for later
     * viewing/reopen. Set [cancelDialogs] to false only for a deliberate
     * requires-human handoff; run end/close paths still cancel them.
     */
    fun release(
        leaseId: String,
        reason: String = "released",
        cancelDialogs: Boolean = true,
    ) {
        val (released, dialogCancellation) = synchronized(lock) {
            val active = activeLeases.remove(leaseId) ?: return@synchronized null
            val current = records[active.sessionId]
            if (current != null) {
                records[active.sessionId] = current.copy(
                    owner = WebMountOwner.NONE,
                    runId = if (active.owner == WebMountOwner.AGENT) active.runId else current.runId,
                    leaseExpiresAtMs = null,
                    lastActivityMs = System.currentTimeMillis(),
                )
            }
            publishLocked()
            val cancellation = if (cancelDialogs) {
                pool.peek(active.sessionId)?.let { handle ->
                    val dialogs = if (active.owner == WebMountOwner.AGENT) {
                        handle.takePendingJsDialogsForLease(active.leaseId)
                    } else {
                        handle.takeAllPendingJsDialogs()
                    }
                    if (dialogs.isNotEmpty()) DialogCancellation(handle, dialogs) else null
                }
            } else {
                null
            }
            active to cancellation
        } ?: return
        pool.unpin(released.sessionId, released.leaseId)
        dialogCancellation?.handle?.dispatchJsDialogCancellation(dialogCancellation.dialogs, reason)
    }

    /** Explicit close removes the summary and destroys the pooled WebView. */
    suspend fun close(sessionId: String, reason: String = "closed") {
        val normalized = sessionId.trim()
        synchronized(lock) {
            closedSessionIds += normalized
            activeLeases.values.filter { it.sessionId == normalized }.forEach { lease ->
                activeLeases.remove(lease.leaseId)
            }
            records.remove(normalized)
            publishLocked()
        }
        pool.unpin(normalized)
        pool.release(normalized, reason)
    }

    /** Update non-secret page summary from the pool's WebView callbacks. */
    fun markActivity(
        sessionId: String,
        leaseId: String? = null,
        url: String? = null,
        title: String? = null,
        status: String? = null,
    ) {
        synchronized(lock) {
            if (sessionId in closedSessionIds) return
            val current = records[sessionId] ?: return
            val active = activeLeases.values.firstOrNull { it.sessionId == sessionId }
            if (leaseId != null && active?.leaseId != leaseId) return
            records[sessionId] = current.copy(
                redactedUrl = url?.let(NetworkLog::redactedUrl) ?: current.redactedUrl,
                title = title?.takeIf { it.isNotBlank() } ?: current.title,
                status = status ?: current.status,
                needsReopen = false,
                lastActivityMs = System.currentTimeMillis(),
            )
            publishLocked()
        }
    }

    /** Called by [WebViewPool] when a direct pool consumer creates a session. */
    fun onPoolSessionCreated(handle: SessionHandle) {
        synchronized(lock) {
            if (handle.sessionId in closedSessionIds) return
            val current = records[handle.sessionId]
            val preserveReopenMarker = current?.needsReopen == true && current.owner == WebMountOwner.NONE
            records[handle.sessionId] = (current ?: WebMountSessionMetadata(handle.sessionId)).copy(
                needsReopen = preserveReopenMarker,
                status = if (preserveReopenMarker) STATUS_REOPEN_REQUIRED else handle.loadState.value.status.wireName,
                redactedUrl = handle.loadState.value.currentUrl?.let(NetworkLog::redactedUrl)
                    ?: current?.redactedUrl,
                title = handle.loadState.value.title ?: current?.title,
                lastActivityMs = handle.lastActivityMs.get(),
            )
            publishLocked()
        }
    }

    /** Called by [WebViewPool] after a primary document state transition. */
    fun onPoolSessionStateChanged(sessionId: String, state: SessionHandle.LoadState) {
        markActivity(
            sessionId = sessionId,
            url = state.currentUrl ?: state.committedUrl ?: state.requestedUrl,
            title = state.title,
            status = state.status.wireName,
        )
    }

    /** Called by [WebViewPool] for eviction, explicit pool release, or process cleanup. */
    fun onPoolSessionDestroyed(sessionId: String) {
        synchronized(lock) {
            if (sessionId in closedSessionIds) return
            val current = records[sessionId] ?: return
            activeLeases.values.filter { it.sessionId == sessionId }.forEach { lease ->
                activeLeases.remove(lease.leaseId)
            }
            records[sessionId] = current.copy(
                owner = WebMountOwner.NONE,
                runId = null,
                leaseExpiresAtMs = null,
                status = STATUS_REOPEN_REQUIRED,
                needsReopen = true,
                lastActivityMs = System.currentTimeMillis(),
            )
            publishLocked()
        }
    }

    private fun expireAgentLeasesLocked(now: Long) {
        val expired = activeLeases.values.filter { lease ->
            lease.owner == WebMountOwner.AGENT && lease.expiresAtMs != null && lease.expiresAtMs <= now
        }
        expired.forEach { lease ->
            activeLeases.remove(lease.leaseId)
            records[lease.sessionId]?.let { current ->
                records[lease.sessionId] = current.copy(
                    owner = WebMountOwner.NONE,
                    runId = lease.runId,
                    leaseExpiresAtMs = null,
                    lastActivityMs = now,
                )
            }
            pool.unpin(lease.sessionId, lease.leaseId)
        }
        if (expired.isNotEmpty()) publishLocked()
    }

    private fun rollbackReservation(
        leaseId: String,
        sessionId: String,
        markReopen: Boolean,
    ) {
        synchronized(lock) {
            activeLeases.remove(leaseId)
            records[sessionId]?.let { current ->
                records[sessionId] = current.copy(
                    owner = WebMountOwner.NONE,
                    runId = current.runId,
                    leaseExpiresAtMs = null,
                    status = if (markReopen) STATUS_REOPEN_REQUIRED else current.status,
                    needsReopen = markReopen || current.needsReopen,
                    lastActivityMs = System.currentTimeMillis(),
                )
            }
            publishLocked()
        }
        pool.unpin(sessionId, leaseId)
    }

    /** Mark an active reopen lease as needing another explicit user attempt. */
    private fun markReopenRequired(leaseId: String, sessionId: String) {
        synchronized(lock) {
            if (activeLeases[leaseId]?.sessionId != sessionId) return
            records[sessionId]?.let { current ->
                records[sessionId] = current.copy(
                    status = STATUS_REOPEN_REQUIRED,
                    needsReopen = true,
                    lastActivityMs = System.currentTimeMillis(),
                )
                publishLocked()
            }
        }
    }

    /**
     * Turn the persisted redacted summary into a URL safe to load after a
     * process restart. Query values and fragments are deliberately discarded:
     * NetworkLog may retain `<redacted>` placeholders, and neither those
     * placeholders nor stale credentials belong in a new WebView request.
     */
    private fun safeReopenUrl(redactedUrl: String?): String? {
        val uri = redactedUrl?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { runCatching { URI(it) }.getOrNull() }
            ?: return null
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        if (scheme != "http" && scheme != "https") return null
        val host = uri.host?.takeIf { it.isNotBlank() } ?: return null
        if (uri.userInfo != null || uri.rawFragment != null) return null
        val rawPath = uri.rawPath?.takeIf { it.isNotEmpty() } ?: "/"
        val path = if (rawPath.contains("<redacted>", ignoreCase = true)) "/" else rawPath
        if (path.any { it.isISOControl() }) return null
        val port = if (uri.port >= 0) ":${uri.port}" else ""
        return "$scheme://${host.lowercase(Locale.ROOT)}$port$path"
    }

    private fun publishLocked() {
        val now = System.currentTimeMillis()
        val cutoff = now - METADATA_TTL_MS
        records.entries.removeIf { (_, value) ->
            value.owner == WebMountOwner.NONE && value.lastActivityMs < cutoff
        }
        while (records.size > MAX_METADATA_RECORDS) {
            val oldest = records.values
                .filter { it.owner == WebMountOwner.NONE }
                .minByOrNull { it.lastActivityMs }
                ?: break
            records.remove(oldest.sessionId)
        }
        prefs.edit().putString(PREFS_KEY, encodeLocked()).apply()
        _sessions.value = records.values.sortedByDescending { it.lastActivityMs }
    }

    private fun encodeLocked(): String = JSONArray().apply {
        records.values.forEach { record ->
            put(JSONObject().apply {
                put("session_id", record.sessionId)
                record.conversationId?.let { put("conversation_id", it) }
                record.runId?.let { put("run_id", it) }
                record.redactedUrl?.let { put("url", it) }
                record.title?.let { put("title", it) }
                put("status", record.status)
                put("owner", record.owner.name)
                record.leaseExpiresAtMs?.let { put("lease_expires_at_ms", it) }
                put("needs_reopen", record.needsReopen)
                put("last_activity_ms", record.lastActivityMs)
            })
        }
    }.toString()

    private fun readLocked(): List<WebMountSessionMetadata> {
        val raw = prefs.getString(PREFS_KEY, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val sessionId = item.optString("session_id").trim()
                    if (sessionId.isBlank()) continue
                    add(
                        WebMountSessionMetadata(
                            sessionId = sessionId,
                            conversationId = item.optStringOrNull("conversation_id"),
                            runId = item.optStringOrNull("run_id"),
                            redactedUrl = item.optStringOrNull("url"),
                            title = item.optStringOrNull("title"),
                            status = item.optString("status", SessionHandle.LoadStatus.IDLE.wireName),
                            owner = item.optString("owner").let { value ->
                                WebMountOwner.entries.firstOrNull { it.name == value } ?: WebMountOwner.NONE
                            },
                            leaseExpiresAtMs = item.optLongOrNull("lease_expires_at_ms"),
                            needsReopen = item.optBoolean("needs_reopen", false),
                            lastActivityMs = item.optLong("last_activity_ms", System.currentTimeMillis()),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private data class ActiveLease(
        val leaseId: String,
        val sessionId: String,
        val owner: WebMountOwner,
        val conversationId: String?,
        val runId: String?,
        val expiresAtMs: Long?,
    )

    private data class DialogCancellation(
        val handle: SessionHandle,
        val dialogs: List<SessionHandle.PendingJsDialog>,
    )

    private data class CloseResult(
        val activeLease: ActiveLease,
        val handle: SessionHandle?,
        val cancellation: DialogCancellation?,
    )

    companion object {
        private const val PREFS_NAME = "amberagent_webmount_sessions"
        private const val PREFS_KEY = "metadata_v1"
        private const val STATUS_REOPEN_REQUIRED = "reopen_required"
        private const val MAX_METADATA_RECORDS = 16
        private const val MAX_ENDED_RUNS = 128
        private const val METADATA_TTL_MS = 14L * 24L * 60L * 60L * 1_000L
        private const val AGENT_LEASE_TTL_MS = 2L * 60L * 1_000L
    }
}

private fun JSONObject.optStringOrNull(name: String): String? =
    optString(name).trim().takeIf { it.isNotEmpty() }

private fun JSONObject.optLongOrNull(name: String): Long? =
    if (has(name) && !isNull(name)) optLong(name) else null
