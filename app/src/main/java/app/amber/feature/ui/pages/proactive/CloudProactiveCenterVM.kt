package app.amber.feature.ui.pages.proactive

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.amber.feature.macgateway.CloudProactiveMonitorRecord
import app.amber.feature.macgateway.CloudProactiveRestraintPatch
import app.amber.feature.macgateway.CloudProactiveRestraintSnapshot
import app.amber.feature.macgateway.CloudProactiveSummary
import app.amber.feature.macgateway.CloudProactiveWebhookRecord
import app.amber.feature.macgateway.MacGatewayClient
import app.amber.feature.macgateway.MacGatewayException
import app.amber.feature.macgateway.MacGatewayRepository
import app.amber.feature.macgateway.ProactiveContract
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Narrow management gateway abstraction for [CloudProactiveCenterVM], allowing fast deterministic
 * JVM tests while production binds each operation to the displayed pairing.
 */
internal interface CloudProactiveCenterGateway {
    suspend fun summary(): CloudProactiveSummary
    suspend fun unmute(topicKey: String): CloudProactiveRestraintSnapshot
    suspend fun patchRestraint(patch: CloudProactiveRestraintPatch): CloudProactiveRestraintSnapshot
    suspend fun setMonitorEnabled(id: String, enabled: Boolean): CloudProactiveMonitorRecord
    suspend fun deleteMonitor(id: String)
    suspend fun setMonitorDeliveryMode(id: String, mode: String): CloudProactiveMonitorRecord
    suspend fun createGithubReleaseMonitor(
        owner: String,
        repo: String,
        deliveryMode: String,
    ): CloudProactiveMonitorRecord
    suspend fun setWebhookEnabled(id: String, enabled: Boolean): CloudProactiveWebhookRecord
    suspend fun deleteWebhook(id: String)
    suspend fun cancelWakeup(id: String)
}

private class PairedCenterGateway(
    private val client: suspend () -> MacGatewayClient,
) : CloudProactiveCenterGateway {
    override suspend fun summary(): CloudProactiveSummary = client().proactiveSummary()
    override suspend fun unmute(topicKey: String): CloudProactiveRestraintSnapshot = client().proactiveUnmute(topicKey)
    override suspend fun patchRestraint(patch: CloudProactiveRestraintPatch): CloudProactiveRestraintSnapshot =
        client().proactivePatchRestraint(patch)
    override suspend fun setMonitorEnabled(id: String, enabled: Boolean): CloudProactiveMonitorRecord =
        client().proactiveSetMonitorEnabled(id, enabled)
    override suspend fun deleteMonitor(id: String) = client().proactiveDeleteMonitor(id)
    override suspend fun setMonitorDeliveryMode(id: String, mode: String): CloudProactiveMonitorRecord =
        client().proactiveSetMonitorDeliveryMode(id, mode)
    override suspend fun createGithubReleaseMonitor(
        owner: String,
        repo: String,
        deliveryMode: String,
    ): CloudProactiveMonitorRecord =
        client().proactiveCreateGithubReleaseMonitor(owner, repo, deliveryMode)
    override suspend fun setWebhookEnabled(id: String, enabled: Boolean): CloudProactiveWebhookRecord =
        client().proactiveSetWebhookEnabled(id, enabled)
    override suspend fun deleteWebhook(id: String) = client().proactiveDeleteWebhook(id)
    override suspend fun cancelWakeup(id: String) = client().proactiveCancelWakeup(id)
}

enum class ProactiveFeature {
    SUMMARY,
    RESTRAINT,
    RESTRAINT_P2,
    WAKEUPS,
    MONITORS,
    MONITOR_DELIVERY_MODE,
    CREATE_GITHUB_MONITOR,
    WEBHOOKS,
    UNMUTE,
}

sealed interface ProactiveCenterError {
    data object UnsupportedOnServer : ProactiveCenterError
    data object Unreachable : ProactiveCenterError
    data object Unauthorized : ProactiveCenterError
    data class Generic(val message: String) : ProactiveCenterError
}

sealed interface ProactiveCenterNotice {
    data object Saved : ProactiveCenterNotice
    data object WakeupCancelled : ProactiveCenterNotice
    data object MonitorDeleted : ProactiveCenterNotice
    data object WebhookDeleted : ProactiveCenterNotice
    data object TopicUnmuted : ProactiveCenterNotice
    data class GithubSubscribed(val slug: String) : ProactiveCenterNotice
    data class UnsupportedFeature(val feature: ProactiveFeature) : ProactiveCenterNotice
    data class Error(val error: ProactiveCenterError) : ProactiveCenterNotice
}

enum class GithubRepoValidationError {
    EMPTY,
    INVALID_FORMAT,
    INVALID_OWNER,
    INVALID_REPO,
}

sealed interface GithubRepoParseResult {
    data class Valid(val owner: String, val repo: String) : GithubRepoParseResult {
        val slug: String get() = "$owner/$repo"
    }
    data class Invalid(val error: GithubRepoValidationError) : GithubRepoParseResult
}

enum class WakeupSourceKind {
    COMMITMENT,
    SNOOZE,
    AGENT,
    UNKNOWN,
}

data class CloudProactiveCenterUiState(
    val isPaired: Boolean = false,
    val gatewayName: String? = null,
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val mutatingKeys: Set<String> = emptySet(),
    val summary: CloudProactiveSummary? = null,
    val loadError: ProactiveCenterError? = null,
    val notice: ProactiveCenterNotice? = null,
    val unsupportedFeatures: Set<ProactiveFeature> = emptySet(),
    val quietStartDraft: String = "23:00",
    val quietEndDraft: String = "08:00",
    val pulseTimeDraft: String = "08:30",
    val scheduleValidationError: Boolean = false,
    val githubRepoInput: String = "",
    val githubDeliveryMode: String = ProactiveContract.DELIVERY_MODE_IMMEDIATE,
    val githubValidationError: GithubRepoValidationError? = null,
    val creatingGithubMonitor: Boolean = false,
)

internal data class ProactiveCenterPairing(
    val gatewayId: String,
    val deviceId: String,
    val generation: Long,
    val name: String,
)

class CloudProactiveCenterVM internal constructor(
    pairings: Flow<ProactiveCenterPairing?>,
    gatewayFor: (ProactiveCenterPairing?) -> CloudProactiveCenterGateway,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    constructor(repository: MacGatewayRepository) : this(
        pairings = combine(repository.state, repository.connectionGeneration) { state, generation ->
            state.connection?.let { ProactiveCenterPairing(it.gatewayId, it.deviceId, generation, it.name) }
        }.distinctUntilChanged(),
        gatewayFor = { pairing ->
            PairedCenterGateway {
                pairing?.let { repository.clientForPairing(it.gatewayId, it.deviceId, it.generation) }
                    ?: throw MacGatewayException.Unreachable()
            }
        },
    )

    private val _session = MutableStateFlow(
        ProactiveCenterSession(viewModelScope, gatewayFor(null), null, ioDispatcher),
    )
    internal val session = _session.asStateFlow()

    init {
        viewModelScope.launch {
            var requests: Job? = null
            pairings.collect { pairing ->
                requests?.cancel()
                val job = SupervisorJob(coroutineContext[Job])
                requests = job
                val session = ProactiveCenterSession(
                    CoroutineScope(coroutineContext + job), gatewayFor(pairing), pairing, ioDispatcher,
                )
                _session.value = session
                if (pairing != null) session.loadSummary()
            }
        }
    }
}

/** Page callbacks and request completions always capture the session that produced the UI. */
internal class ProactiveCenterSession(
    private val scope: CoroutineScope,
    private val gateway: CloudProactiveCenterGateway,
    pairing: ProactiveCenterPairing?,
    private val ioDispatcher: CoroutineDispatcher,
) {
    private val _uiState = MutableStateFlow(
        CloudProactiveCenterUiState(isPaired = pairing != null, gatewayName = pairing?.name),
    )
    val uiState = _uiState.asStateFlow()

    fun clearNotice() {
        _uiState.update { it.copy(notice = null) }
    }

    fun refresh() {
        if (!_uiState.value.isPaired) return
        loadSummary(isRefresh = _uiState.value.summary != null)
    }

    fun loadSummary(isRefresh: Boolean = false) {
        if (!_uiState.value.isPaired) return
        scope.launch {
            _uiState.update {
                it.copy(
                    loading = !isRefresh && it.summary == null,
                    refreshing = isRefresh,
                    loadError = if (isRefresh) it.loadError else null,
                )
            }
            try {
                val summary = withContext(ioDispatcher) { gateway.summary() }
                _uiState.update { current ->
                    current.copy(
                        loading = false,
                        refreshing = false,
                        summary = summary,
                        loadError = null,
                        unsupportedFeatures = current.unsupportedFeatures - ProactiveFeature.SUMMARY,
                        quietStartDraft = summary.restraint.quietHoursStart,
                        quietEndDraft = summary.restraint.quietHoursEnd,
                        pulseTimeDraft = summary.restraint.pulseTime,
                        scheduleValidationError = false,
                    )
                }
            } catch (error: Throwable) {
                val classified = classifyError(error)
                _uiState.update { current ->
                    current.copy(
                        loading = false,
                        refreshing = false,
                        loadError = if (current.summary == null) classified else current.loadError,
                        notice = if (current.summary != null) {
                            if (classified == ProactiveCenterError.UnsupportedOnServer) {
                                ProactiveCenterNotice.UnsupportedFeature(ProactiveFeature.SUMMARY)
                            } else {
                                ProactiveCenterNotice.Error(classified)
                            }
                        } else {
                            current.notice
                        },
                        unsupportedFeatures = if (classified == ProactiveCenterError.UnsupportedOnServer) {
                            current.unsupportedFeatures + ProactiveFeature.SUMMARY
                        } else {
                            current.unsupportedFeatures
                        },
                    )
                }
            }
        }
    }

    fun onQuietStartChanged(value: String) {
        _uiState.update { it.copy(quietStartDraft = value, scheduleValidationError = false) }
    }

    fun onQuietEndChanged(value: String) {
        _uiState.update { it.copy(quietEndDraft = value, scheduleValidationError = false) }
    }

    fun onPulseTimeChanged(value: String) {
        _uiState.update { it.copy(pulseTimeDraft = value, scheduleValidationError = false) }
    }

    fun saveScheduleTimes() {
        val state = _uiState.value
        val start = state.quietStartDraft.trim()
        val end = state.quietEndDraft.trim()
        val pulse = state.pulseTimeDraft.trim()
        if (!isValidTimeHhMm(start) || !isValidTimeHhMm(end) || !isValidTimeHhMm(pulse)) {
            _uiState.update { it.copy(scheduleValidationError = true) }
            return
        }
        val current = state.summary?.restraint
        val patch = CloudProactiveRestraintPatch(
            quietHoursStart = start.takeIf { current == null || it != current.quietHoursStart },
            quietHoursEnd = end.takeIf { current == null || it != current.quietHoursEnd },
            pulseTime = pulse.takeIf { current == null || it != current.pulseTime },
        )
        if (patch.quietHoursStart == null && patch.quietHoursEnd == null && patch.pulseTime == null) {
            _uiState.update { it.copy(notice = ProactiveCenterNotice.Saved, scheduleValidationError = false) }
            return
        }
        patchRestraint(
            mutationKey = "restraint:schedule",
            patch = patch,
            feature = if (patch.pulseTime != null && patch.quietHoursStart == null && patch.quietHoursEnd == null) {
                ProactiveFeature.RESTRAINT_P2
            } else {
                ProactiveFeature.RESTRAINT
            },
        )
    }

    fun setQuietHoursEnabled(enabled: Boolean) {
        patchRestraint(
            mutationKey = "restraint:quietHoursEnabled",
            patch = CloudProactiveRestraintPatch(quietHoursEnabled = enabled),
            feature = ProactiveFeature.RESTRAINT,
        )
    }

    fun setDailyMaxPushes(value: Int) {
        val clamped = value.coerceIn(MIN_DAILY_MAX_PUSHES, MAX_DAILY_MAX_PUSHES)
        patchRestraint(
            mutationKey = "restraint:dailyMaxPushes",
            patch = CloudProactiveRestraintPatch(dailyMaxPushes = clamped),
            feature = ProactiveFeature.RESTRAINT,
        )
    }

    fun setPulseEnabled(enabled: Boolean) {
        patchRestraint(
            mutationKey = "restraint:pulseEnabled",
            patch = CloudProactiveRestraintPatch(pulseEnabled = enabled),
            feature = ProactiveFeature.RESTRAINT_P2,
        )
    }

    fun setCommitmentExtractionEnabled(enabled: Boolean) {
        patchRestraint(
            mutationKey = "restraint:commitmentExtractionEnabled",
            patch = CloudProactiveRestraintPatch(commitmentExtractionEnabled = enabled),
            feature = ProactiveFeature.RESTRAINT_P2,
        )
    }

    private fun patchRestraint(
        mutationKey: String,
        patch: CloudProactiveRestraintPatch,
        feature: ProactiveFeature,
    ) {
        if (mutationKey in _uiState.value.mutatingKeys) return
        scope.launch {
            _uiState.update { it.copy(mutatingKeys = it.mutatingKeys + mutationKey, notice = null) }
            try {
                val updated = withContext(ioDispatcher) { gateway.patchRestraint(patch) }
                val p2IgnoredByServer =
                    (patch.pulseEnabled != null && updated.pulseEnabled != patch.pulseEnabled) ||
                        (patch.pulseTime != null && updated.pulseTime != patch.pulseTime) ||
                        (patch.commitmentExtractionEnabled != null &&
                            updated.commitmentExtractionEnabled != patch.commitmentExtractionEnabled)
                _uiState.update { current ->
                    val nextSummary = current.summary?.copy(restraint = updated)
                    current.copy(
                        mutatingKeys = current.mutatingKeys - mutationKey,
                        summary = nextSummary,
                        quietStartDraft = updated.quietHoursStart,
                        quietEndDraft = updated.quietHoursEnd,
                        pulseTimeDraft = updated.pulseTime,
                        scheduleValidationError = false,
                        unsupportedFeatures = if (p2IgnoredByServer) {
                            current.unsupportedFeatures + ProactiveFeature.RESTRAINT_P2
                        } else {
                            current.unsupportedFeatures - feature
                        },
                        notice = if (p2IgnoredByServer) {
                            ProactiveCenterNotice.UnsupportedFeature(ProactiveFeature.RESTRAINT_P2)
                        } else {
                            ProactiveCenterNotice.Saved
                        },
                    )
                }
            } catch (error: Throwable) {
                handleMutationError(mutationKey, feature, error)
            }
        }
    }

    fun unmuteTopic(topicKey: String) {
        val trimmed = topicKey.trim()
        if (trimmed.isEmpty()) return
        val mutationKey = "unmute:$trimmed"
        if (mutationKey in _uiState.value.mutatingKeys) return
        scope.launch {
            _uiState.update { it.copy(mutatingKeys = it.mutatingKeys + mutationKey, notice = null) }
            try {
                val updatedRestraint = withContext(ioDispatcher) { gateway.unmute(trimmed) }
                _uiState.update { current ->
                    current.copy(
                        mutatingKeys = current.mutatingKeys - mutationKey,
                        summary = current.summary?.copy(restraint = updatedRestraint),
                        unsupportedFeatures = current.unsupportedFeatures - ProactiveFeature.UNMUTE,
                        notice = ProactiveCenterNotice.TopicUnmuted,
                    )
                }
            } catch (error: Throwable) {
                handleMutationError(mutationKey, ProactiveFeature.UNMUTE, error)
            }
        }
    }

    fun cancelWakeup(wakeupId: String) {
        val mutationKey = "wakeup:$wakeupId"
        if (mutationKey in _uiState.value.mutatingKeys) return
        scope.launch {
            _uiState.update { it.copy(mutatingKeys = it.mutatingKeys + mutationKey, notice = null) }
            try {
                withContext(ioDispatcher) { gateway.cancelWakeup(wakeupId) }
                _uiState.update { current ->
                    val nextSummary = current.summary?.let { s ->
                        s.copy(wakeups = s.wakeups.filterNot { it.id == wakeupId })
                    }
                    current.copy(
                        mutatingKeys = current.mutatingKeys - mutationKey,
                        summary = nextSummary,
                        unsupportedFeatures = current.unsupportedFeatures - ProactiveFeature.WAKEUPS,
                        notice = ProactiveCenterNotice.WakeupCancelled,
                    )
                }
            } catch (error: Throwable) {
                handleMutationError(mutationKey, ProactiveFeature.WAKEUPS, error)
            }
        }
    }

    fun setMonitorEnabled(monitorId: String, enabled: Boolean) {
        val mutationKey = "monitor:toggle:$monitorId"
        if (mutationKey in _uiState.value.mutatingKeys) return
        scope.launch {
            _uiState.update { it.copy(mutatingKeys = it.mutatingKeys + mutationKey, notice = null) }
            try {
                val updated = withContext(ioDispatcher) { gateway.setMonitorEnabled(monitorId, enabled) }
                _uiState.update { current ->
                    current.copy(
                        mutatingKeys = current.mutatingKeys - mutationKey,
                        summary = current.summary?.replaceMonitor(updated),
                        unsupportedFeatures = current.unsupportedFeatures - ProactiveFeature.MONITORS,
                        notice = ProactiveCenterNotice.Saved,
                    )
                }
            } catch (error: Throwable) {
                handleMutationError(mutationKey, ProactiveFeature.MONITORS, error)
            }
        }
    }

    fun setMonitorDeliveryMode(monitorId: String, deliveryMode: String) {
        val normalized = runCatching { ProactiveContract.normalizeDeliveryMode(deliveryMode) }
            .getOrNull() ?: return
        val mutationKey = "monitor:delivery:$monitorId"
        if (mutationKey in _uiState.value.mutatingKeys) return
        scope.launch {
            _uiState.update { it.copy(mutatingKeys = it.mutatingKeys + mutationKey, notice = null) }
            try {
                val updated = withContext(ioDispatcher) {
                    gateway.setMonitorDeliveryMode(monitorId, normalized)
                }
                val unsupportedByServer = updated.deliveryMode.lowercase() != normalized
                _uiState.update { current ->
                    current.copy(
                        mutatingKeys = current.mutatingKeys - mutationKey,
                        summary = current.summary?.replaceMonitor(updated),
                        unsupportedFeatures = if (unsupportedByServer) {
                            current.unsupportedFeatures + ProactiveFeature.MONITOR_DELIVERY_MODE
                        } else {
                            current.unsupportedFeatures - ProactiveFeature.MONITOR_DELIVERY_MODE
                        },
                        notice = if (unsupportedByServer) {
                            ProactiveCenterNotice.UnsupportedFeature(ProactiveFeature.MONITOR_DELIVERY_MODE)
                        } else {
                            ProactiveCenterNotice.Saved
                        },
                    )
                }
            } catch (error: Throwable) {
                handleMutationError(mutationKey, ProactiveFeature.MONITOR_DELIVERY_MODE, error)
            }
        }
    }

    fun deleteMonitor(monitorId: String) {
        val mutationKey = "monitor:delete:$monitorId"
        if (mutationKey in _uiState.value.mutatingKeys) return
        scope.launch {
            _uiState.update { it.copy(mutatingKeys = it.mutatingKeys + mutationKey, notice = null) }
            try {
                withContext(ioDispatcher) { gateway.deleteMonitor(monitorId) }
                _uiState.update { current ->
                    val nextSummary = current.summary?.let { s ->
                        s.copy(monitors = s.monitors.filterNot { it.id == monitorId })
                    }
                    current.copy(
                        mutatingKeys = current.mutatingKeys - mutationKey,
                        summary = nextSummary,
                        notice = ProactiveCenterNotice.MonitorDeleted,
                    )
                }
            } catch (error: Throwable) {
                handleMutationError(mutationKey, ProactiveFeature.MONITORS, error)
            }
        }
    }

    fun onGithubRepoInputChanged(value: String) {
        val validation = if (value.isBlank()) {
            null
        } else {
            when (val parsed = parseGithubOwnerRepo(value)) {
                is GithubRepoParseResult.Valid -> null
                is GithubRepoParseResult.Invalid -> parsed.error
            }
        }
        _uiState.update {
            it.copy(
                githubRepoInput = value,
                githubValidationError = validation,
            )
        }
    }

    fun onGithubDeliveryModeChanged(mode: String) {
        val normalized = runCatching { ProactiveContract.normalizeDeliveryMode(mode) }
            .getOrNull() ?: return
        _uiState.update { it.copy(githubDeliveryMode = normalized) }
    }

    fun submitGithubReleaseMonitor() {
        val state = _uiState.value
        if (state.creatingGithubMonitor) return
        when (val parsed = parseGithubOwnerRepo(state.githubRepoInput)) {
            is GithubRepoParseResult.Invalid -> {
                _uiState.update { it.copy(githubValidationError = parsed.error) }
                return
            }

            is GithubRepoParseResult.Valid -> {
                scope.launch {
                    _uiState.update {
                        it.copy(
                            creatingGithubMonitor = true,
                            githubValidationError = null,
                            notice = null,
                        )
                    }
                    try {
                        val created = withContext(ioDispatcher) {
                            gateway.createGithubReleaseMonitor(
                                owner = parsed.owner,
                                repo = parsed.repo,
                                deliveryMode = state.githubDeliveryMode,
                            )
                        }
                        _uiState.update { current ->
                            val nextSummary = current.summary?.let { s ->
                                val existing = s.monitors.filterNot { it.id == created.id }
                                s.copy(monitors = listOf(created) + existing)
                            }
                            current.copy(
                                creatingGithubMonitor = false,
                                summary = nextSummary,
                                githubRepoInput = "",
                                githubValidationError = null,
                                unsupportedFeatures = current.unsupportedFeatures - ProactiveFeature.CREATE_GITHUB_MONITOR,
                                notice = ProactiveCenterNotice.GithubSubscribed(parsed.slug),
                            )
                        }
                    } catch (error: Throwable) {
                        val classified = classifyError(error)
                        _uiState.update { current ->
                            current.copy(
                                creatingGithubMonitor = false,
                                unsupportedFeatures = if (classified == ProactiveCenterError.UnsupportedOnServer) {
                                    current.unsupportedFeatures + ProactiveFeature.CREATE_GITHUB_MONITOR
                                } else {
                                    current.unsupportedFeatures
                                },
                                notice = if (classified == ProactiveCenterError.UnsupportedOnServer) {
                                    ProactiveCenterNotice.UnsupportedFeature(ProactiveFeature.CREATE_GITHUB_MONITOR)
                                } else {
                                    ProactiveCenterNotice.Error(classified)
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    fun setWebhookEnabled(webhookId: String, enabled: Boolean) {
        val mutationKey = "webhook:toggle:$webhookId"
        if (mutationKey in _uiState.value.mutatingKeys) return
        scope.launch {
            _uiState.update { it.copy(mutatingKeys = it.mutatingKeys + mutationKey, notice = null) }
            try {
                val updated = withContext(ioDispatcher) { gateway.setWebhookEnabled(webhookId, enabled) }
                _uiState.update { current ->
                    current.copy(
                        mutatingKeys = current.mutatingKeys - mutationKey,
                        summary = current.summary?.replaceWebhook(updated),
                        unsupportedFeatures = current.unsupportedFeatures - ProactiveFeature.WEBHOOKS,
                        notice = ProactiveCenterNotice.Saved,
                    )
                }
            } catch (error: Throwable) {
                handleMutationError(mutationKey, ProactiveFeature.WEBHOOKS, error)
            }
        }
    }

    fun deleteWebhook(webhookId: String) {
        val mutationKey = "webhook:delete:$webhookId"
        if (mutationKey in _uiState.value.mutatingKeys) return
        scope.launch {
            _uiState.update { it.copy(mutatingKeys = it.mutatingKeys + mutationKey, notice = null) }
            try {
                withContext(ioDispatcher) { gateway.deleteWebhook(webhookId) }
                _uiState.update { current ->
                    val nextSummary = current.summary?.let { s ->
                        s.copy(webhooks = s.webhooks.filterNot { it.id == webhookId })
                    }
                    current.copy(
                        mutatingKeys = current.mutatingKeys - mutationKey,
                        summary = nextSummary,
                        notice = ProactiveCenterNotice.WebhookDeleted,
                    )
                }
            } catch (error: Throwable) {
                handleMutationError(mutationKey, ProactiveFeature.WEBHOOKS, error)
            }
        }
    }

    private fun handleMutationError(
        mutationKey: String,
        feature: ProactiveFeature,
        error: Throwable,
    ) {
        val classified = classifyError(error)
        _uiState.update { current ->
            val isUnsupported = classified == ProactiveCenterError.UnsupportedOnServer
            current.copy(
                mutatingKeys = current.mutatingKeys - mutationKey,
                unsupportedFeatures = if (isUnsupported) {
                    current.unsupportedFeatures + feature
                } else {
                    current.unsupportedFeatures
                },
                notice = if (isUnsupported) {
                    ProactiveCenterNotice.UnsupportedFeature(feature)
                } else {
                    ProactiveCenterNotice.Error(classified)
                },
            )
        }
    }

    private fun CloudProactiveSummary.replaceMonitor(
        updated: CloudProactiveMonitorRecord,
    ): CloudProactiveSummary = copy(
        monitors = monitors.map { if (it.id == updated.id) updated else it },
    )

    private fun CloudProactiveSummary.replaceWebhook(
        updated: CloudProactiveWebhookRecord,
    ): CloudProactiveSummary = copy(
        webhooks = webhooks.map { if (it.id == updated.id) updated else it },
    )

    companion object {
        const val MIN_DAILY_MAX_PUSHES = 1
        const val MAX_DAILY_MAX_PUSHES = 100

        private val TIME_HH_MM_REGEX = Regex("^([01]\\d|2[0-3]):[0-5]\\d$")

        fun isValidTimeHhMm(value: String): Boolean = TIME_HH_MM_REGEX.matches(value.trim())

        fun parseGithubOwnerRepo(raw: String): GithubRepoParseResult {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return GithubRepoParseResult.Invalid(GithubRepoValidationError.EMPTY)
            if (trimmed.contains("://") || trimmed.any { it.isWhitespace() } ||
                trimmed.contains('?') || trimmed.contains('#') || trimmed.contains('\\')
            ) {
                return GithubRepoParseResult.Invalid(GithubRepoValidationError.INVALID_FORMAT)
            }
            val parts = trimmed.split('/')
            if (parts.size != 2) {
                return GithubRepoParseResult.Invalid(GithubRepoValidationError.INVALID_FORMAT)
            }
            val owner = parts[0]
            val repo = parts[1]
            if (!ProactiveContract.isValidGithubOwner(owner)) {
                return GithubRepoParseResult.Invalid(GithubRepoValidationError.INVALID_OWNER)
            }
            if (!ProactiveContract.isValidGithubRepo(repo)) {
                return GithubRepoParseResult.Invalid(GithubRepoValidationError.INVALID_REPO)
            }
            return GithubRepoParseResult.Valid(owner, repo)
        }

        fun classifyWakeupSource(kind: String): WakeupSourceKind = when (kind.trim().lowercase()) {
            "commitment" -> WakeupSourceKind.COMMITMENT
            "snooze" -> WakeupSourceKind.SNOOZE
            "agent" -> WakeupSourceKind.AGENT
            else -> WakeupSourceKind.UNKNOWN
        }

        fun classifyError(error: Throwable): ProactiveCenterError = when (error) {
            is MacGatewayException.NotFound -> ProactiveCenterError.UnsupportedOnServer
            is MacGatewayException.Unreachable -> ProactiveCenterError.Unreachable
            is MacGatewayException.Unauthorized -> ProactiveCenterError.Unauthorized
            is MacGatewayException.Server -> {
                val msg = error.message.orEmpty().lowercase()
                if (msg.contains("not_supported") ||
                    msg.contains("unsupported") ||
                    msg.contains("unknown_field") ||
                    msg.contains("http 501") ||
                    msg.contains("http 404")
                ) {
                    ProactiveCenterError.UnsupportedOnServer
                } else {
                    ProactiveCenterError.Generic(error.message.orEmpty().ifBlank { "Server error" })
                }
            }
            else -> ProactiveCenterError.Generic(error.message ?: error.javaClass.simpleName)
        }
    }
}
