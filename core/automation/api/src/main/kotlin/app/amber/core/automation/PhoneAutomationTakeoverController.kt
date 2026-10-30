package app.amber.core.automation

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.util.Locale
import java.util.UUID

/**
 * Human takeover / handoff state machine phases for Android phone automation:
 * `IDLE -> AUTOMATED -> PAUSING -> PAUSED -> RESUMING -> AUTOMATED -> IDLE`.
 */
enum class PhoneTakeoverPhase(val wireName: String) {
    IDLE("idle"),
    AUTOMATED("automated"),
    PAUSING("pausing"),
    PAUSED("paused"),
    RESUMING("resuming"),
    ;

    companion object {
        fun fromWireName(raw: String?): PhoneTakeoverPhase =
            entries.firstOrNull { it.wireName.equals(raw?.trim(), ignoreCase = true) } ?: IDLE
    }
}

/**
 * Immutable snapshot of the active phone automation session and its takeover state.
 */
data class PhoneAutomationSessionState(
    val sessionId: String = "",
    val runId: String? = null,
    val packageName: String = "",
    val goal: String = "",
    val phase: PhoneTakeoverPhase = PhoneTakeoverPhase.IDLE,
    val pauseReason: String? = null,
    val interceptedSensitiveAction: String? = null,
    val currentStep: Int = 0,
    val maxSteps: Int = 0,
    val lastSnapshotId: String? = null,
    val invalidatedSnapshotIds: Set<String> = emptySet(),
    val executedSideEffectStepKeys: Set<String> = emptySet(),
    val takeoverCount: Int = 0,
    val transitionVersion: Long = 0L,
    val updatedAtMillis: Long = 0L,
) {
    val isActive: Boolean
        get() = phase != PhoneTakeoverPhase.IDLE

    val canRequestTakeover: Boolean
        get() = phase == PhoneTakeoverPhase.AUTOMATED

    val canRequestResume: Boolean
        get() = phase == PhoneTakeoverPhase.PAUSED || phase == PhoneTakeoverPhase.PAUSING

    val isPausedOrPausing: Boolean
        get() = phase == PhoneTakeoverPhase.PAUSED || phase == PhoneTakeoverPhase.PAUSING
}

/**
 * Durable persistence sink for [PhoneAutomationSessionState] (following
 * `UI gate -> domain owner/CAS -> durable persistence`).
 */
interface PhoneTakeoverPersistence {
    fun save(state: PhoneAutomationSessionState)
    fun load(): PhoneAutomationSessionState?

    object InMemory : PhoneTakeoverPersistence {
        @Volatile
        private var current: PhoneAutomationSessionState? = null
        override fun save(state: PhoneAutomationSessionState) {
            current = state
        }
        override fun load(): PhoneAutomationSessionState? = current
    }
}

/**
 * File-backed durable persistence for [PhoneAutomationSessionState].
 */
class FilePhoneTakeoverPersistence(
    private val file: File,
) : PhoneTakeoverPersistence {
    @Synchronized
    override fun save(state: PhoneAutomationSessionState) {
        runCatching {
            file.parentFile?.mkdirs()
            val content = buildString {
                appendLine("sessionId=${state.sessionId}")
                appendLine("runId=${state.runId.orEmpty()}")
                appendLine("packageName=${state.packageName}")
                appendLine("goal=${state.goal.replace('\n', ' ')}")
                appendLine("phase=${state.phase.wireName}")
                appendLine("pauseReason=${state.pauseReason.orEmpty()}")
                appendLine("interceptedSensitiveAction=${state.interceptedSensitiveAction.orEmpty()}")
                appendLine("currentStep=${state.currentStep}")
                appendLine("maxSteps=${state.maxSteps}")
                appendLine("lastSnapshotId=${state.lastSnapshotId.orEmpty()}")
                appendLine("invalidatedSnapshotIds=${state.invalidatedSnapshotIds.joinToString(",")}")
                appendLine("executedSideEffectStepKeys=${state.executedSideEffectStepKeys.joinToString("\u001F")}")
                appendLine("takeoverCount=${state.takeoverCount}")
                appendLine("transitionVersion=${state.transitionVersion}")
                appendLine("updatedAtMillis=${state.updatedAtMillis}")
            }
            file.writeText(content)
        }
    }

    @Synchronized
    override fun load(): PhoneAutomationSessionState? = runCatching {
        if (!file.exists()) return null
        val map = file.readLines()
            .mapNotNull { line ->
                val idx = line.indexOf('=')
                if (idx <= 0) null else line.substring(0, idx) to line.substring(idx + 1)
            }
            .toMap()
        PhoneAutomationSessionState(
            sessionId = map["sessionId"].orEmpty(),
            runId = map["runId"]?.takeIf { it.isNotBlank() },
            packageName = map["packageName"].orEmpty(),
            goal = map["goal"].orEmpty(),
            phase = PhoneTakeoverPhase.fromWireName(map["phase"]),
            pauseReason = map["pauseReason"]?.takeIf { it.isNotBlank() },
            interceptedSensitiveAction = map["interceptedSensitiveAction"]?.takeIf { it.isNotBlank() },
            currentStep = map["currentStep"]?.toIntOrNull() ?: 0,
            maxSteps = map["maxSteps"]?.toIntOrNull() ?: 0,
            lastSnapshotId = map["lastSnapshotId"]?.takeIf { it.isNotBlank() },
            invalidatedSnapshotIds = map["invalidatedSnapshotIds"]
                ?.split(',')
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?.toSet()
                .orEmpty(),
            executedSideEffectStepKeys = map["executedSideEffectStepKeys"]
                ?.split('\u001F')
                ?.filter { it.isNotEmpty() }
                ?.toSet()
                .orEmpty(),
            takeoverCount = map["takeoverCount"]?.toIntOrNull() ?: 0,
            transitionVersion = map["transitionVersion"]?.toLongOrNull() ?: 0L,
            updatedAtMillis = map["updatedAtMillis"]?.toLongOrNull() ?: 0L,
        )
    }.getOrNull()
}

/**
 * Domain owner managing the human takeover / handoff state machine (`automated` / `pausing` /
 * `paused` / `resuming`) across `JevScreenGoalRunner`, `ScreenAutomationTools`, the system
 * overlay bubble, and the active chat control bar.
 *
 * Guarantees:
 * 1. **CAS state transitions** via [MutableStateFlow.update] with monotonic `transitionVersion`.
 * 2. **Snapshot invalidation on takeover**: Entering `PAUSING` or `PAUSED` immediately marks
 *    the pre-takeover snapshot ID as invalidated in [ScreenSnapshotInvalidationRegistry] so
 *    stale node refs can never be reused.
 * 3. **Fresh snapshot requirement on resume**: Transitioning `RESUMING -> AUTOMATED` requires a
 *    newly captured post-takeover [ScreenSnapshot].
 * 4. **No side-effect replay**: [tryRecordSideEffectDispatch] tracks `(snapshotId, actionKind, ref, text)`
 *    keys so already-dispatched side-effect steps are never replayed after resume.
 */
class PhoneAutomationTakeoverController(
    private val persistence: PhoneTakeoverPersistence = PhoneTakeoverPersistence.InMemory,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _state = MutableStateFlow(PhoneAutomationSessionState())
    val state: StateFlow<PhoneAutomationSessionState> = _state.asStateFlow()

    fun startSession(
        runId: String,
        packageName: String,
        goal: String,
        maxSteps: Int,
    ): PhoneAutomationSessionState {
        var updated = PhoneAutomationSessionState()
        _state.update { prev ->
            PhoneAutomationSessionState(
                sessionId = UUID.randomUUID().toString(),
                runId = runId,
                packageName = packageName,
                goal = goal,
                phase = PhoneTakeoverPhase.AUTOMATED,
                pauseReason = null,
                interceptedSensitiveAction = null,
                currentStep = 0,
                maxSteps = maxSteps,
                lastSnapshotId = null,
                invalidatedSnapshotIds = emptySet(),
                executedSideEffectStepKeys = emptySet(),
                takeoverCount = 0,
                transitionVersion = prev.transitionVersion + 1L,
                updatedAtMillis = clock(),
            ).also { updated = it }
        }
        persistence.save(updated)
        return updated
    }

    fun recordObservedSnapshot(
        snapshotId: String,
        stepIndex: Int,
    ) {
        if (snapshotId.isBlank()) return
        var updated: PhoneAutomationSessionState? = null
        _state.update { current ->
            if (!current.isActive) return@update current
            current.copy(
                lastSnapshotId = snapshotId,
                currentStep = stepIndex,
                transitionVersion = current.transitionVersion + 1L,
                updatedAtMillis = clock(),
            ).also { updated = it }
        }
        updated?.let(persistence::save)
    }

    /**
     * Triggered by the user tapping "我来操作" (or by an automatic safety/anomaly guard).
     * Performs a CAS transition from `AUTOMATED` to `PAUSING` and immediately invalidates the
     * last observed snapshot so any in-flight or subsequent action against that snapshot fails closed.
     */
    fun requestUserTakeover(
        reason: String = "user_requested",
        interceptedSensitiveAction: String? = null,
        controller: AccessibilityController? = getActiveAccessibilityController(),
    ): Boolean {
        var transitioned = false
        var snapshotToInvalidate: String? = null
        var updatedState: PhoneAutomationSessionState? = null
        _state.update { current ->
            if (current.phase != PhoneTakeoverPhase.AUTOMATED) return@update current
            transitioned = true
            val snapId = current.lastSnapshotId
            snapshotToInvalidate = snapId
            val nextInvalidated = if (!snapId.isNullOrBlank()) {
                current.invalidatedSnapshotIds + snapId
            } else {
                current.invalidatedSnapshotIds
            }
            current.copy(
                phase = PhoneTakeoverPhase.PAUSING,
                pauseReason = reason.ifBlank { "user_requested" },
                interceptedSensitiveAction = interceptedSensitiveAction ?: current.interceptedSensitiveAction,
                invalidatedSnapshotIds = nextInvalidated,
                takeoverCount = current.takeoverCount + 1,
                transitionVersion = current.transitionVersion + 1L,
                updatedAtMillis = clock(),
            ).also { updatedState = it }
        }
        if (transitioned) {
            snapshotToInvalidate?.let { id ->
                ScreenSnapshotInvalidationRegistry.invalidate(id, "takeover_snapshot_invalidated")
                controller?.invalidateSnapshot(id, "takeover_snapshot_invalidated")
            }
            updatedState?.let(persistence::save)
        }
        return transitioned
    }

    /**
     * Transitions `PAUSING` (or `AUTOMATED` when pausing directly at a step boundary) to `PAUSED`,
     * ensuring the supplied [snapshotId] and `lastSnapshotId` are invalidated.
     */
    fun confirmPaused(
        snapshotId: String? = null,
        reason: String? = null,
        interceptedSensitiveAction: String? = null,
        controller: AccessibilityController? = getActiveAccessibilityController(),
    ): PhoneAutomationSessionState {
        val idsToInvalidate = mutableSetOf<String>()
        var updated = _state.value
        _state.update { current ->
            if (!current.isActive) return@update current
            snapshotId?.takeIf { it.isNotBlank() }?.let(idsToInvalidate::add)
            current.lastSnapshotId?.takeIf { it.isNotBlank() }?.let(idsToInvalidate::add)
            val isNewTakeover = current.phase == PhoneTakeoverPhase.AUTOMATED
            current.copy(
                phase = PhoneTakeoverPhase.PAUSED,
                pauseReason = reason?.takeIf { it.isNotBlank() } ?: current.pauseReason ?: "user_requested",
                interceptedSensitiveAction = interceptedSensitiveAction ?: current.interceptedSensitiveAction,
                invalidatedSnapshotIds = current.invalidatedSnapshotIds + idsToInvalidate,
                takeoverCount = if (isNewTakeover) current.takeoverCount + 1 else current.takeoverCount,
                transitionVersion = current.transitionVersion + 1L,
                updatedAtMillis = clock(),
            ).also { updated = it }
        }
        idsToInvalidate.forEach { id ->
            ScreenSnapshotInvalidationRegistry.invalidate(id, "takeover_snapshot_invalidated")
            controller?.invalidateSnapshot(id, "takeover_snapshot_invalidated")
        }
        persistence.save(updated)
        return updated
    }

    /**
     * Triggered by the user tapping "继续自动操作".
     * Performs a CAS transition from `PAUSED` or `PAUSING` to `RESUMING`.
     */
    fun requestResume(): Boolean {
        var transitioned = false
        var updatedState: PhoneAutomationSessionState? = null
        _state.update { current ->
            if (current.phase != PhoneTakeoverPhase.PAUSED && current.phase != PhoneTakeoverPhase.PAUSING) {
                return@update current
            }
            transitioned = true
            current.copy(
                phase = PhoneTakeoverPhase.RESUMING,
                pauseReason = null,
                transitionVersion = current.transitionVersion + 1L,
                updatedAtMillis = clock(),
            ).also { updatedState = it }
        }
        updatedState?.let(persistence::save)
        return transitioned
    }

    /**
     * Completes the `RESUMING -> AUTOMATED` transition after the executor has discarded its
     * pre-takeover snapshot and captured a brand-new live [freshSnapshot].
     */
    fun confirmResumedWithFreshSnapshot(freshSnapshot: ScreenSnapshot): Boolean {
        val snapshotId = freshSnapshot.id.trim()
        if (snapshotId.isBlank()) return false
        var transitioned = false
        var updatedState: PhoneAutomationSessionState? = null
        _state.update { current ->
            if (current.phase != PhoneTakeoverPhase.RESUMING) return@update current
            if (snapshotId in current.invalidatedSnapshotIds) return@update current
            transitioned = true
            current.copy(
                phase = PhoneTakeoverPhase.AUTOMATED,
                pauseReason = null,
                lastSnapshotId = snapshotId,
                transitionVersion = current.transitionVersion + 1L,
                updatedAtMillis = clock(),
            ).also { updatedState = it }
        }
        if (transitioned) {
            ScreenSnapshotInvalidationRegistry.markFreshCapture(snapshotId)
            updatedState?.let(persistence::save)
        }
        return transitioned
    }

    /**
     * Guards side-effect action dispatch so an already-dispatched action on a given snapshot
     * is never replayed across pause/resume cycles.
     */
    fun tryRecordSideEffectDispatch(
        snapshotId: String,
        action: ScreenAction,
    ): Boolean {
        val stepKey = buildSideEffectKey(snapshotId, action)
        var recorded = false
        var updatedState: PhoneAutomationSessionState? = null
        _state.update { current ->
            if (stepKey in current.executedSideEffectStepKeys) {
                recorded = false
                return@update current
            }
            recorded = true
            current.copy(
                executedSideEffectStepKeys = current.executedSideEffectStepKeys + stepKey,
                transitionVersion = current.transitionVersion + 1L,
                updatedAtMillis = clock(),
            ).also { updatedState = it }
        }
        updatedState?.let(persistence::save)
        return recorded
    }

    fun hasDispatchedSideEffect(snapshotId: String, action: ScreenAction): Boolean =
        buildSideEffectKey(snapshotId, action) in _state.value.executedSideEffectStepKeys

    /**
     * If the session is currently `PAUSING` or `PAUSED`, confirms `PAUSED` (invalidating
     * [currentSnapshotId]) and suspends until the user taps "继续自动操作" (`RESUMING`), or returns
     * `false` if the pause window expires.
     */
    suspend fun awaitResumeIfPaused(
        currentSnapshotId: String?,
        controller: AccessibilityController,
        maxPauseWaitMs: Long = DEFAULT_PAUSE_WAIT_MS,
        pollIntervalMs: Long = 100L,
    ): Boolean {
        val initial = _state.value
        if (!initial.isPausedOrPausing && initial.phase != PhoneTakeoverPhase.RESUMING) {
            return true
        }
        if (initial.isPausedOrPausing) {
            confirmPaused(snapshotId = currentSnapshotId, controller = controller)
        }
        val start = clock()
        while (clock() - start < maxPauseWaitMs) {
            val phase = _state.value.phase
            if (phase == PhoneTakeoverPhase.RESUMING || phase == PhoneTakeoverPhase.AUTOMATED) {
                return true
            }
            if (phase == PhoneTakeoverPhase.IDLE) {
                return false
            }
            delay(pollIntervalMs.coerceAtLeast(10L))
        }
        return _state.value.phase == PhoneTakeoverPhase.RESUMING ||
            _state.value.phase == PhoneTakeoverPhase.AUTOMATED
    }

    fun finishSession(runId: String? = null) {
        var updatedState: PhoneAutomationSessionState? = null
        _state.update { current ->
            if (runId != null && current.runId != null && current.runId != runId) {
                return@update current
            }
            PhoneAutomationSessionState(
                transitionVersion = current.transitionVersion + 1L,
                updatedAtMillis = clock(),
            ).also { updatedState = it }
        }
        updatedState?.let(persistence::save)
    }

    companion object {
        const val DEFAULT_PAUSE_WAIT_MS = 120_000L

        fun buildSideEffectKey(snapshotId: String, action: ScreenAction): String =
            "${snapshotId.trim()}|${action.kind.name.lowercase(Locale.ROOT)}|${action.ref.trim()}|${action.text.orEmpty()}"
    }
}
