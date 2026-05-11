package me.rerere.rikkahub.data.agent.webmount.core

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.rerere.ai.core.Tool
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.agent.AgentToolActivityStore
import me.rerere.rikkahub.data.agent.webmount.cookie.WebMountCookieProvider
import me.rerere.rikkahub.data.agent.webmount.oauth.WebMountOAuthTokenStore
import java.util.concurrent.ConcurrentHashMap

/**
 * Central registry for [WebMountAdapter] instances.
 *
 * Two paths exist per adapter:
 *
 *  - If [WebMountAdapter.externalStateFlow] is non-null (wrapping adapters
 *    like iCloud in M1.1), this manager mirrors that flow into [states] and
 *    delegates [probe] / [runWriteProbe] to the adapter, which forwards them
 *    to the underlying manager. [setEnabled] is a no-op for those — the
 *    underlying manager owns the enable flag.
 *
 *  - If no external flow is provided, this manager owns the station's
 *    persistent state via [prefs] and drives it through the [probe] state
 *    machine. [setEnabled] writes the flag locally.
 *
 * Mutex is keyed by station id so probing iCloud doesn't block probing GitHub.
 */
class WebMountManager(
    context: Context,
    private val adapters: List<WebMountAdapter>,
    // wired through to adapters in M1.3 (Browser Primitives session bootstrap)
    @Suppress("unused") private val cookieProvider: WebMountCookieProvider,
    // wired in M1.5 (OAuth Intent bridge) — currently a no-op in-memory stub
    @Suppress("unused") private val oauthStore: WebMountOAuthTokenStore,
    private val activityStore: AgentToolActivityStore,
    // Use the concrete AppScope class instead of the CoroutineScope interface
    // — the Koin module binds only the concrete type. AppScope already
    // implements CoroutineScope via delegation so launch/collect work the same.
    private val appScope: AppScope,
) {
    private val prefs = context.getSharedPreferences("amberagent_webmount", Context.MODE_PRIVATE)
    private val mutexes = ConcurrentHashMap<String, Mutex>()
    private val adapterMap: Map<String, WebMountAdapter> = adapters.associateBy { it.id }

    private val _states = MutableStateFlow(buildInitialStates())
    val states: StateFlow<Map<String, WebMountStationState>> = _states.asStateFlow()

    init {
        adapters.forEach { adapter ->
            val flow = adapter.externalStateFlow ?: return@forEach
            appScope.launch {
                flow.collect { state ->
                    _states.update { current -> current + (adapter.id to state) }
                }
            }
        }
    }

    /** All registered adapters, in registration order. */
    fun adapters(): List<WebMountAdapter> = adapters

    fun adapterOf(id: String): WebMountAdapter? = adapterMap[id]

    /** Toggle a self-managed station. No-op for wrapping adapters. */
    fun setEnabled(id: String, enabled: Boolean) {
        val adapter = adapterMap[id] ?: return
        if (adapter.externalStateFlow != null) return
        prefs.edit()
            .putBoolean(keyEnabled(id), enabled)
            .putLong(keyUpdatedAt(id), System.currentTimeMillis())
            .apply()
        _states.update { current ->
            val existing = current[id] ?: defaultStateFor(adapter)
            current + (id to existing.copy(
                enabled = enabled,
                status = if (!enabled) WebMountStatus.NOT_CONFIGURED else existing.status,
                updatedAtMillis = System.currentTimeMillis(),
            ))
        }
    }

    suspend fun probe(id: String): WebMountStationState = withStationLock(id) { adapter ->
        if (adapter.externalStateFlow != null) {
            adapter.probe()
            currentStateOf(adapter)
        } else {
            updateLocalState(adapter) { it.copy(status = WebMountStatus.PROBING) }
            val result = runCatching { adapter.probe() }.getOrElse { error ->
                WebMountProbeResult.failed(error.message ?: error.toString(), error)
            }
            applyProbeResult(adapter, result, isWriteProbe = false)
        }
    }

    suspend fun runWriteProbe(id: String): WebMountStationState = withStationLock(id) { adapter ->
        if (adapter.externalStateFlow != null) {
            adapter.writeProbe()
            currentStateOf(adapter)
        } else {
            updateLocalState(adapter) { it.copy(status = WebMountStatus.PROBING) }
            val result = runCatching { adapter.writeProbe() }.getOrElse { error ->
                WebMountProbeResult.failed(error.message ?: error.toString(), error)
            }
            applyProbeResult(adapter, result, isWriteProbe = true)
        }
    }

    /**
     * Tools surfaced into the agent tool catalog. Wrapping adapters return
     * empty lists in M1.1 — the legacy `ICloudDriveTools` keeps surfacing
     * iCloud tools directly.
     */
    fun allTools(): List<Tool> = adapters.flatMap { adapter ->
        val hooks = WebMountToolHooks(
            activityStore = activityStore,
            stationId = adapter.id,
            runtimeLabel = "WebMount/${adapter.displayName}",
            workspace = "/webmount/${adapter.id}",
        )
        adapter.tools(hooks)
    }

    // ---- internals ----------------------------------------------------------

    private suspend fun withStationLock(
        id: String,
        block: suspend (WebMountAdapter) -> WebMountStationState,
    ): WebMountStationState {
        val adapter = adapterMap[id]
            ?: return WebMountStationState(
                id = id,
                displayName = id,
                authMethods = emptySet(),
                status = WebMountStatus.ERROR,
                message = "Adapter '$id' is not registered",
                updatedAtMillis = System.currentTimeMillis(),
            )
        return mutexes.getOrPut(id) { Mutex() }.withLock { block(adapter) }
    }

    private fun applyProbeResult(
        adapter: WebMountAdapter,
        result: WebMountProbeResult,
        isWriteProbe: Boolean,
    ): WebMountStationState {
        // NotSupported means the adapter has nothing to probe (e.g. fully
        // anonymous stations whose readiness is implicit). Preserve the
        // prior status/capability so a UI Probe tap doesn't destroy state;
        // only surface the message and bump updated_at.
        if (result == WebMountProbeResult.NotSupported) {
            return updateLocalState(adapter) {
                it.copy(
                    message = "Probe not supported by adapter",
                    updatedAtMillis = System.currentTimeMillis(),
                )
            }
        }
        val nextStatus: WebMountStatus
        val nextCapability: WebMountCapability
        val message: String?
        when (result) {
            is WebMountProbeResult.Success -> {
                nextStatus = when (result.capability) {
                    WebMountCapability.READ_WRITE -> WebMountStatus.READ_WRITE
                    WebMountCapability.READ_ONLY -> WebMountStatus.READ_ONLY
                    WebMountCapability.NONE -> WebMountStatus.LOGIN_REQUIRED
                }
                nextCapability = result.capability
                message = result.message
                if (isWriteProbe && result.capability == WebMountCapability.READ_WRITE) {
                    prefs.edit().putBoolean(keyWriteValidated(adapter.id), true).apply()
                }
            }
            is WebMountProbeResult.LoginRequired -> {
                nextStatus = WebMountStatus.LOGIN_REQUIRED
                nextCapability = WebMountCapability.NONE
                message = result.message
                if (isWriteProbe) {
                    prefs.edit().putBoolean(keyWriteValidated(adapter.id), false).apply()
                }
            }
            is WebMountProbeResult.Degraded -> {
                nextStatus = WebMountStatus.DEGRADED
                nextCapability = WebMountCapability.READ_ONLY
                message = result.message
            }
            is WebMountProbeResult.Failed -> {
                nextStatus = WebMountStatus.ERROR
                nextCapability = WebMountCapability.NONE
                message = result.message
            }
            WebMountProbeResult.NotSupported -> error("handled above")
        }
        return updateLocalState(adapter) {
            it.copy(
                status = nextStatus,
                capability = nextCapability,
                message = message,
                updatedAtMillis = System.currentTimeMillis(),
            )
        }
    }

    private fun updateLocalState(
        adapter: WebMountAdapter,
        transform: (WebMountStationState) -> WebMountStationState,
    ): WebMountStationState {
        val before = currentStateOf(adapter)
        val next = transform(before)
        _states.update { current -> current + (adapter.id to next) }
        prefs.edit()
            .putString(keyStatus(adapter.id), next.status.wireName)
            .putString(keyCapability(adapter.id), next.capability.wireName)
            .putString(keyMessage(adapter.id), next.message)
            .putLong(keyUpdatedAt(adapter.id), next.updatedAtMillis)
            .putBoolean(keyEnabled(adapter.id), next.enabled)
            .apply()
        return next
    }

    private fun currentStateOf(adapter: WebMountAdapter): WebMountStationState =
        _states.value[adapter.id] ?: defaultStateFor(adapter)

    private fun buildInitialStates(): Map<String, WebMountStationState> = adapters.associate { adapter ->
        if (adapter.externalStateFlow != null) {
            adapter.id to adapter.externalStateFlow!!.value
        } else {
            adapter.id to loadLocalState(adapter)
        }
    }

    private fun loadLocalState(adapter: WebMountAdapter): WebMountStationState {
        val enabled = prefs.getBoolean(keyEnabled(adapter.id), false)
        val writeValidated = prefs.getBoolean(keyWriteValidated(adapter.id), false)
        val storedStatus = prefs.getString(keyStatus(adapter.id), null)
            ?.let { raw -> WebMountStatus.entries.firstOrNull { it.wireName == raw } }
        val storedCapability = prefs.getString(keyCapability(adapter.id), null)
            ?.let { raw -> WebMountCapability.entries.firstOrNull { it.wireName == raw } }
        val defaultStatus = when {
            !enabled -> WebMountStatus.NOT_CONFIGURED
            writeValidated -> WebMountStatus.READ_WRITE
            else -> WebMountStatus.LOGIN_REQUIRED
        }
        val defaultCapability =
            if (writeValidated) WebMountCapability.READ_WRITE else WebMountCapability.NONE
        return WebMountStationState(
            id = adapter.id,
            displayName = adapter.displayName,
            authMethods = adapter.authMethods,
            enabled = enabled,
            status = storedStatus ?: defaultStatus,
            capability = storedCapability ?: defaultCapability,
            message = prefs.getString(keyMessage(adapter.id), null),
            updatedAtMillis = prefs.getLong(keyUpdatedAt(adapter.id), 0L),
        )
    }

    private fun defaultStateFor(adapter: WebMountAdapter): WebMountStationState =
        WebMountStationState(
            id = adapter.id,
            displayName = adapter.displayName,
            authMethods = adapter.authMethods,
        )

    private fun keyEnabled(id: String) = "enabled.$id"
    private fun keyStatus(id: String) = "status.$id"
    private fun keyCapability(id: String) = "capability.$id"
    private fun keyMessage(id: String) = "message.$id"
    private fun keyUpdatedAt(id: String) = "updated_at.$id"
    private fun keyWriteValidated(id: String) = "write_validated.$id"
}
