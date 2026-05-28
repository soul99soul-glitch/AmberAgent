package app.amber.feature.webmount.core

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import app.amber.ai.core.Tool
import app.amber.agent.AppScope
import app.amber.feature.runtime.AgentToolActivityStore
import app.amber.feature.webmount.cookie.WebMountCookieProvider
import app.amber.feature.webmount.oauth.WebMountOAuthTokenStore
import java.util.concurrent.ConcurrentHashMap

/**
 * Central registry for [WebMountAdapter] instances.
 *
 * All 7 M1.6 adapters use the standalone path: this manager owns the
 * station's persistent state via [prefs] and drives it through the [probe]
 * state machine. [setEnabled] writes the flag locally.
 *
 * The [WebMountAdapter.externalStateFlow] hook exists as a future escape
 * hatch for adapters that want to mirror state from an externally-managed
 * source. When non-null, this manager mirrors that flow into [states] and
 * delegates [probe] / [runWriteProbe] to the adapter; [setEnabled] is a
 * no-op since the external source owns the enable flag. No shipped adapter
 * uses this today — iCloud, which prototyped the path, was kept as a
 * separate standalone Settings entry per the Phase 1 M1.2 directive.
 *
 * Per-station Mutex (keyed by id) lets concurrent probes across different
 * stations run in parallel — probing HN doesn't block probing GitHub.
 */
class WebMountManager(
    context: Context,
    private val adapters: List<WebMountAdapter>,
    // Phase 2 M2.0.5: now plumbed into per-adapter WebMountToolHooks so
    // cookie-auth adapters can call `hooks.cookies()` instead of threading
    // (endpoints, cookieProvider) through every tool function.
    private val cookieProvider: WebMountCookieProvider,
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

    /**
     * Phase 2 post-review fix: top-level "experimental feature enable" flag
     * matching the iCloud / Feishu Office Enhancement pattern. When OFF
     * (default), WebMount adds zero tools to the agent catalog regardless
     * of any per-assistant or per-station configuration. When ON, the
     * safe `wm_*` primitives + adapter tools are immediately available
     * to every assistant without further configuration.
     *
     * `wm_eval` (arbitrary JS) is gated by a separate sub-toggle
     * [evalEnabledFlow] so users can enable the main feature without
     * granting the high-risk eval capability.
     */
    private val _globalEnabled = MutableStateFlow(prefs.getBoolean(KEY_GLOBAL_ENABLED, false))
    val globalEnabledFlow: StateFlow<Boolean> = _globalEnabled.asStateFlow()

    private val _evalEnabled = MutableStateFlow(prefs.getBoolean(KEY_EVAL_ENABLED, false))
    val evalEnabledFlow: StateFlow<Boolean> = _evalEnabled.asStateFlow()

    /** Synchronous accessors for callers outside coroutine scopes. */
    val globalEnabled: Boolean get() = _globalEnabled.value
    val evalEnabled: Boolean get() = _evalEnabled.value

    fun setGlobalEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_GLOBAL_ENABLED, enabled).apply()
        _globalEnabled.value = enabled
        // Disabling the main feature also clears the eval sub-toggle so
        // re-enabling later doesn't silently grant eval back.
        if (!enabled && _evalEnabled.value) {
            prefs.edit().putBoolean(KEY_EVAL_ENABLED, false).apply()
            _evalEnabled.value = false
        }
    }

    fun setEvalEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_EVAL_ENABLED, enabled).apply()
        _evalEnabled.value = enabled
    }

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
     * Tools surfaced into the agent tool catalog. Each adapter's `tools()` is
     * called once at registration time and cached — `LocalTools.getTools()`
     * runs every chat turn, so rebuilding ~50 `Tool` objects + their hook
     * envelopes per turn is pure waste.
     */
    private val cachedToolsByAdapter: Map<String, List<Tool>> by lazy {
        adapters.associate { adapter ->
            val hooks = WebMountToolHooks(
                activityStore = activityStore,
                stationId = adapter.id,
                runtimeLabel = "WebMount/${adapter.displayName}",
                workspace = "/webmount/${adapter.id}",
                // Phase 2 M2.0.5: pass cookie context so adapter tools can
                // call hooks.cookies() / hooks.requireCookies(name) directly.
                // Anonymous + OAuth adapters ignore these (hooks.cookies()
                // just returns EMPTY).
                cookieProvider = cookieProvider,
                endpoints = adapter.endpoints,
            )
            adapter.id to adapter.tools(hooks)
        }
    }

    private val cachedTools: List<Tool> by lazy {
        cachedToolsByAdapter.values.flatten()
    }

    fun allTools(): List<Tool> = cachedTools

    /**
     * Phase 2 Plan v2 — adapter id → adapter's tools. LocalTools uses this
     * with [UserSiteRegistry.activeNativeAdapterIds] so removing a site
     * from the user's list immediately drops its adapter's tools from the
     * agent catalog.
     */
    fun allToolsByAdapter(): Map<String, List<Tool>> = cachedToolsByAdapter

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

    companion object {
        private const val KEY_GLOBAL_ENABLED = "global.enabled"
        private const val KEY_EVAL_ENABLED = "global.eval_enabled"
    }
}
