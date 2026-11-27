package app.amber.feature.macgateway

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import app.amber.agent.data.db.entity.ProactiveCardEntity
import app.amber.core.settings.secret.SecretStore
import app.amber.core.settings.secret.createAndroidSecretStore
import app.amber.feature.prompts.AgentSoulState
import app.amber.feature.prompts.AgentSoulStore
import com.google.firebase.messaging.FirebaseMessaging
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.uuid.Uuid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

private val Context.macGatewayDataStore by preferencesDataStore(name = "mac_gateway")

/**
 * Coordinates `C-SOUL-V1` (`/v2/soul`) bidirectional synchronization between local [AgentSoulStore]
 * and the paired [MacGatewayClient].
 *
 * - Remote pull (`GET /v2/soul`) uses [AgentSoulStore.applyRemoteSoul], which never triggers a loop `PUT`.
 * - First connect when server `revision == 0` and local store has a non-default persona uploads once via `PUT /v2/soul`.
 * - Local UI / chat tool mutations asynchronously `PUT /v2/soul` and silently degrade on `404` or network errors.
 */
class AgentSoulCloudSyncCoordinator(
    private val store: AgentSoulStore,
    private val scope: CoroutineScope,
    private val clientProvider: suspend () -> MacGatewayClient?,
) {
    private val syncMutex = Mutex()

    @Volatile
    private var initialZeroRevisionUploaded = false

    init {
        store.setLocalMutationListener { mutatedState ->
            scope.launch {
                pushLocalSoul(mutatedState)
            }
        }
    }

    fun resetConnectionSession() {
        initialZeroRevisionUploaded = false
    }

    suspend fun pushLocalSoul(state: AgentSoulState = store.state.value): AgentSoulState? =
        syncMutex.withLock {
            val client = clientProvider() ?: return@withLock null
            pushLocalSoulLocked(state, client)
        }

    suspend fun onRemoteSoulRevision(serverSoulRevision: Long?, source: MacGatewayClient? = null) {
        val rev = serverSoulRevision ?: return
        syncMutex.withLock {
            val client = clientProvider() ?: return
            if (source != null && source !== client) return
            if (rev == 0L) {
                if (!store.state.value.isDefault && !initialZeroRevisionUploaded) {
                    initialZeroRevisionUploaded = true
                    pushLocalSoulLocked(store.state.value, client)
                }
                return@withLock
            }
            if (rev > store.lastSyncedRevision) {
                pullRemoteSoulLocked(client)
            }
        }
    }

    suspend fun syncOnce(source: MacGatewayClient? = null) {
        syncMutex.withLock {
            val client = clientProvider() ?: return
            if (source != null && source !== client) return
            pullRemoteSoulLocked(client)
        }
    }

    private suspend fun pullRemoteSoulLocked(client: MacGatewayClient): AgentSoulState? {
        return try {
            val remote = client.getSoul()
            if (clientProvider() !== client) return null
            if (remote.revision == 0L && !store.state.value.isDefault) {
                if (!initialZeroRevisionUploaded) {
                    initialZeroRevisionUploaded = true
                    return pushLocalSoulLocked(store.state.value, client)
                }
                return remote
            }
            if (remote.revision > store.lastSyncedRevision ||
                (remote.revision > 0L && store.state.value.isDefault && !remote.isDefault)
            ) {
                store.applyRemoteSoul(remote)
            }
            remote
        } catch (_: MacGatewayException.NotFound) {
            // Silently degrade when talking to an older gateway without /v2/soul.
            null
        } catch (_: Throwable) {
            null
        }
    }

    private suspend fun pushLocalSoulLocked(state: AgentSoulState, client: MacGatewayClient): AgentSoulState? {
        return try {
            val updated = client.putSoul(store.toPutRequest(state))
            if (clientProvider() !== client) return null
            store.applyRemoteSoul(updated)
            updated
        } catch (_: MacGatewayException.NotFound) {
            // Silently degrade when talking to an older gateway without /v2/soul.
            null
        } catch (_: Throwable) {
            null
        }
    }
}

/**
 * The paired Mac/Cloud Gateway. Device tokens are encrypted in [SecretStore] via [GatewayTokenVault]
 * (with idempotent migration from legacy plaintext DataStore `token` entries).
 */
class MacGatewayRepository(
    private val context: Context,
    private val appScope: CoroutineScope,
    timelineSink: CloudDialogueTimelineSink = CloudDialogueTimelineSink.NoOp,
    private val agentSoulStore: AgentSoulStore? = null,
    secretStore: SecretStore = createAndroidSecretStore(context),
    private val dataStore: DataStore<Preferences> = context.macGatewayDataStore,
    foregroundFlow: StateFlow<Boolean>? = null,
    private val clientFactory: (MacGatewayConnection, String?) -> MacGatewayClient = { conn, token ->
        MacGatewayClient(conn.addrs, conn.port, conn.fingerprint, token, endpointRole = conn.role)
    },
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    onProactiveCard: (suspend (ProactiveCardEntity) -> Unit)? = null,
    private val endpointRole: GatewayEndpointRole = GatewayEndpointRole.CLOUD,
    private val root: MacGatewayRepository? = null,
) {
    data class State(
        val loaded: Boolean = false,
        val generation: Long = 0L,
        val connection: MacGatewayConnection? = null,
        val status: MacGatewayStatus? = null,
        /** null until the first refresh after pairing/launch. */
        val reachable: Boolean? = null,
        val error: MacGatewayException? = null,
        /** Explicit toggle for routing main chat messages to the paired cloud `/v2/dialogue` API. */
        val cloudDialogueEnabled: Boolean = false,
        /** Capability of this pairing's /v2/dialogue/events endpoint, unknown until it answers. */
        val cloudDialogueSupported: Boolean? = null,
        val macTasks: MacTaskSnapshot = MacTaskSnapshot(),
    ) {
        val canSelectCloud: Boolean get() = connection != null && cloudDialogueSupported != false
        val isCloudDialogueActive: Boolean get() = cloudDialogueEnabled && canSelectCloud
    }

    private val tokenVault = GatewayTokenVault(secretStore)
    private val pairingMutex: Mutex = root?.pairingMutex ?: Mutex()
    private val pairRequestMutex: Mutex = root?.pairRequestMutex ?: Mutex()
    private val macTasksMutex = Mutex()
    private val prefix = if (endpointRole == GatewayEndpointRole.MAC) "mac_" else ""
    private val connectionKey = stringPreferencesKey("${prefix}connection")
    private val legacyTokenKey = stringPreferencesKey("${prefix}token")
    private val credentialKey = stringPreferencesKey("${prefix}credential")
    private val dialogueEnabledKey = booleanPreferencesKey("${prefix}cloud_dialogue_enabled")
    private val dialogueStateKey = stringPreferencesKey("${prefix}cloud_dialogue_state")

    private val _selectedDialogueRole: MutableStateFlow<GatewayEndpointRole?> = root?._selectedDialogueRole ?: MutableStateFlow<GatewayEndpointRole?>(null)
    val selectedDialogueRole = _selectedDialogueRole.asStateFlow()

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val _cloudDialogueState = MutableStateFlow(CloudDialogueUiState())
    val cloudDialogueState: StateFlow<CloudDialogueUiState> = _cloudDialogueState.asStateFlow()

    private val _connectionGeneration = MutableStateFlow(0L)
    val connectionGeneration: StateFlow<Long> = _connectionGeneration.asStateFlow()

    private val ready = CompletableDeferred<Unit>()

    @Volatile
    private var client: MacGatewayClient? = null

    private val pollLock = Any()
    private var cloudPollJob: Job? = null
    private val pollWakeup = Channel<Unit>(Channel.CONFLATED)
    private val immediatePollRequested = AtomicBoolean(false)
    private val immediatePollForceWhenDisabled = AtomicBoolean(false)
    private val pollExecutionMutex = Mutex()

    private val ownedForegroundFlow: MutableStateFlow<Boolean>? =
        if (foregroundFlow == null) MutableStateFlow(false) else null
    private val isForeground: StateFlow<Boolean> = foregroundFlow ?: ownedForegroundFlow!!.asStateFlow()

    private val macSlot: MacGatewayRepository by lazy {
        MacGatewayRepository(
            context = context,
            appScope = appScope,
            timelineSink = timelineSink,
            secretStore = secretStore,
            dataStore = dataStore,
            foregroundFlow = isForeground,
            clientFactory = clientFactory,
            nowMs = nowMs,
            onProactiveCard = onProactiveCard,
            endpointRole = GatewayEndpointRole.MAC,
            root = this,
        )
    }

    fun slot(role: GatewayEndpointRole): MacGatewayRepository =
        if (role == endpointRole) this else root?.slot(role) ?: macSlot

    private val lifecycleObserver = LifecycleEventObserver { _, event ->
        when (event) {
            Lifecycle.Event.ON_START -> ownedForegroundFlow?.value = true
            Lifecycle.Event.ON_STOP -> ownedForegroundFlow?.value = false
            else -> Unit
        }
    }

    private val soulSyncCoordinator: AgentSoulCloudSyncCoordinator? =
        agentSoulStore?.let { store ->
            AgentSoulCloudSyncCoordinator(
                store = store,
                scope = appScope,
                clientProvider = { activeClient() },
            )
        }

    private val cloudCoordinator: CloudDialogueCoordinator = CloudDialogueCoordinator(
        clientProvider = { activeClient() },
        timelineSink = timelineSink,
        persistState = { persisted ->
            pairingMutex.withLock {
                if (persisted.connectionGeneration == _connectionGeneration.value) {
                    dataStore.edit { prefs ->
                        prefs[dialogueStateKey] = MacGatewayJson.encodeToString(
                            CloudDialoguePersistedState.serializer(),
                            persisted,
                        )
                    }
                }
            }
        },
        onUnauthorized = { rejectedClient -> forget(rejectedClient, MacGatewayException.Unauthorized()) },
        onProactiveCard = onProactiveCard,
        onDialogueSupport = ::recordDialogueSupport,
        connectionGenerationProvider = { _connectionGeneration.value },
        onStateChanged = { uiState ->
            _cloudDialogueState.value = uiState
            reconcileCloudPolling()
        },
        onSoulRevision = { rev, source ->
            soulSyncCoordinator?.onRemoteSoulRevision(rev, source)
        },
        persistScope = appScope,
    )

    init {
        if (ownedForegroundFlow != null) {
            appScope.launch(Dispatchers.Main.immediate) {
                runCatching {
                    val lifecycle = ProcessLifecycleOwner.get().lifecycle
                    ownedForegroundFlow.value = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
                    lifecycle.addObserver(lifecycleObserver)
                }.onFailure {
                    Log.w(TAG, "failed to observe ProcessLifecycleOwner")
                }
            }
        }

        appScope.launch {
            var previousForeground = isForeground.value
            isForeground.collect { foreground ->
                if (foreground == previousForeground) return@collect
                previousForeground = foreground
                if (foreground) {
                    requestImmediatePoll(REASON_FOREGROUND)
                } else {
                    reconcileCloudPolling()
                }
            }
        }

        appScope.launch {
            if (root == null) migrateLegacyConnection() else root.ready.await()
            val prefs = dataStore.data.first()
            val connection = decodeConnection(prefs[connectionKey])?.copy(role = endpointRole)
            val legacyToken = prefs[legacyTokenKey]?.takeIf { it.isNotBlank() }
            val token = connection?.let {
                resolveAndMigrateToken(prefs[credentialKey] ?: it.gatewayId, legacyToken)
            }
            val cloudEnabled = prefs[dialogueEnabledKey] == true
            val persistedDialogue = prefs[dialogueStateKey]?.let {
                runCatching { MacGatewayJson.decodeFromString<CloudDialoguePersistedState>(it) }.getOrNull()
            }
            if (connection != null && token != null) client = clientFactory(connection, token)
            val activeConnection = connection.takeIf { client != null }
            cloudCoordinator.restore(
                enabled = cloudEnabled && activeConnection != null,
                connection = activeConnection,
                persisted = persistedDialogue,
            )
            if (cloudEnabled && activeConnection != null) _selectedDialogueRole.value = endpointRole
            _state.value = State(
                loaded = true,
                connection = activeConnection,
                cloudDialogueEnabled = cloudEnabled && activeConnection != null,
            )
            ready.complete(Unit)
            if (root == null) slot(GatewayEndpointRole.MAC)
            reconcileCloudPolling()
            if (activeConnection != null) {
                soulSyncCoordinator?.syncOnce()
            }
        }.invokeOnCompletion { failure ->
            if (failure != null) ready.completeExceptionally(failure)
        }
    }

    private suspend fun migrateLegacyConnection() {
        val prefs = dataStore.data.first()
        val connection = decodeConnection(prefs[connectionKey]) ?: return
        val role = connection.role ?: GatewayEndpointRole.inferLegacy(connection.name, connection.addrs)
        if (role == GatewayEndpointRole.CLOUD) return
        val mac = slot(GatewayEndpointRole.MAC)
        dataStore.edit {
            if (it[mac.connectionKey] != null) return@edit
            it[mac.connectionKey] = encodeConnection(connection.copy(role = role))
            it[legacyTokenKey]?.let { token -> it[mac.legacyTokenKey] = token }
            it[credentialKey]?.let { credential -> it[mac.credentialKey] = credential }
            it[dialogueEnabledKey]?.let { enabled -> it[mac.dialogueEnabledKey] = enabled }
            it[dialogueStateKey]?.let { dialogue -> it[mac.dialogueStateKey] = dialogue }
            clearPairing(it)
        }
    }

    suspend fun pair(payload: MacGatewayPairingPayload) {
        val pairedClient = pairRequestMutex.withLock { establishPairing(payload) }
        reconcileCloudPolling()
        runCatching { uploadPushToken(pairedClient) }.onFailure { Log.w(TAG, "push token upload after pairing failed") }
        refresh(pairedClient)
        soulSyncCoordinator?.syncOnce(pairedClient)
    }

    private suspend fun establishPairing(payload: MacGatewayPairingPayload): MacGatewayClient {
        ready.await()
        val other = slot(if (endpointRole == GatewayEndpointRole.CLOUD) GatewayEndpointRole.MAC else GatewayEndpointRole.CLOUD)
        other.ready.await()
        if (payload.role != null && payload.role != endpointRole) throw MacGatewayException.Conflict("wrong_endpoint_role")
        if (other.state.value.connection?.gatewayId == payload.id) throw MacGatewayException.Conflict("gateway_in_other_slot")

        val generation = _connectionGeneration.value
        val candidate = MacGatewayConnection(payload.id, payload.name, payload.addrs, payload.port, payload.fp, "", endpointRole)
        val deviceName = Build.MODEL?.takeIf { it.isNotBlank() } ?: "Android"
        val response = clientFactory(candidate, null).pair(payload.s, deviceName)
        if (response.gatewayId != candidate.gatewayId || response.deviceId.isBlank() || response.token.isBlank()) {
            throw MacGatewayException.InvalidResponse()
        }
        val connection = candidate.copy(name = response.gatewayName, deviceId = response.deviceId)
        val pairedClient = clientFactory(connection, response.token)
        withContext(NonCancellable) {
            pairingMutex.withLock {
                if (generation != _connectionGeneration.value) throw MacGatewayException.Conflict("gateway_pairing_changed")
                val prefs = dataStore.data.first()
                val previousCredential = storedCredential(prefs)
                val credential = "pairing-${Uuid.random()}"
                try {
                    tokenVault.write(credential, response.token)
                    dataStore.edit {
                        it[connectionKey] = encodeConnection(connection)
                        it[credentialKey] = credential
                        it.remove(legacyTokenKey)
                        it.remove(dialogueStateKey)
                    }
                } catch (failure: Exception) {
                    runCatching { tokenVault.delete(credential) }
                    throw failure
                }
                val enabled = _selectedDialogueRole.value == endpointRole
                cloudCoordinator.restore(enabled = enabled, connection = connection, persisted = null) {
                    val next = _connectionGeneration.value + 1L
                    _connectionGeneration.value = next
                    client = pairedClient
                    soulSyncCoordinator?.resetConnectionSession()
                    _state.value = State(loaded = true, generation = next, connection = connection, cloudDialogueEnabled = enabled)
                }
                previousCredential?.let { runCatching { tokenVault.delete(it) } }
            }
        }
        return pairedClient
    }

    suspend fun setCloudDialogueEnabled(enabled: Boolean, expectedGeneration: Long? = null): Boolean {
        ready.await()
        val other = slot(if (endpointRole == GatewayEndpointRole.CLOUD) GatewayEndpointRole.MAC else GatewayEndpointRole.CLOUD)
        other.ready.await()
        val selected = client ?: return false
        if (expectedGeneration != null && _connectionGeneration.value != expectedGeneration) return false
        if (enabled && _state.value.cloudDialogueSupported != true) {
            pollCloudDialogueOnce()
        }
        val changed = withContext(NonCancellable) {
            pairingMutex.withLock {
                if (selected !== client || (enabled && _state.value.cloudDialogueSupported != true)) return@withLock false
                dataStore.edit {
                    it[dialogueEnabledKey] = enabled
                    if (enabled) it.remove(other.dialogueEnabledKey)
                }
                if (enabled) {
                    other._state.update { it.copy(cloudDialogueEnabled = false) }
                    other.cloudCoordinator.setEnabled(false)
                }
                _state.update { it.copy(cloudDialogueEnabled = enabled) }
                cloudCoordinator.setEnabled(enabled)
                if (enabled) _selectedDialogueRole.value = endpointRole
                else if (_selectedDialogueRole.value == endpointRole) _selectedDialogueRole.value = null
                true
            }
        }
        other.reconcileCloudPolling()
        reconcileCloudPolling()
        return changed
    }

    private suspend fun recordDialogueSupport(source: MacGatewayClient, supported: Boolean) {
        if (_state.value.cloudDialogueSupported == supported) return
        pairingMutex.withLock {
            if (source !== client || _state.value.cloudDialogueSupported == supported) return
            if (!supported) {
                dataStore.edit { it.remove(dialogueEnabledKey) }
                cloudCoordinator.setEnabled(false)
                if (_selectedDialogueRole.value == endpointRole) _selectedDialogueRole.value = null
            }
            _state.update {
                it.copy(cloudDialogueSupported = supported, cloudDialogueEnabled = supported && it.cloudDialogueEnabled)
            }
        }
        reconcileCloudPolling()
    }

    fun requestImmediatePoll(reason: String) {
        if (reason != REASON_FOREGROUND) {
            immediatePollForceWhenDisabled.set(true)
        }
        immediatePollRequested.set(true)
        pollWakeup.trySend(Unit)
        ensurePollLoopRunning()
    }

    fun bindActiveConversation(conversationId: String, expectedConnectionGeneration: Long? = null) {
        appScope.launch {
            ready.await()
            pairingMutex.withLock {
                if (_selectedDialogueRole.value != endpointRole) return@withLock
                if (expectedConnectionGeneration != null && expectedConnectionGeneration != _connectionGeneration.value) return@withLock
                cloudCoordinator.bindActiveConversation(conversationId)
            }
            reconcileCloudPolling()
        }
    }

    fun selectFollowupTarget(workItemId: String?, expectedConnectionGeneration: Long? = null) {
        appScope.launch {
            ready.await()
            cloudCoordinator.selectFollowupTarget(workItemId, expectedConnectionGeneration)
        }
    }

    suspend fun sendCloudMessage(
        conversationId: String,
        text: String,
        requestId: String = Uuid.random().toString(),
        expectedConnectionGeneration: Long? = null,
        onLocalEcho: () -> Unit = {},
    ): CloudDialogueSubmission {
        ready.await()
        val sub = cloudCoordinator.sendMessage(
            conversationId = conversationId,
            text = text,
            requestId = requestId,
            expectedConnectionGeneration = expectedConnectionGeneration,
            onLocalEcho = onLocalEcho,
        )
        reconcileCloudPolling()
        requestImmediatePoll("send_message")
        return sub
    }

    suspend fun sendCloudFollowup(
        conversationId: String,
        workItemId: String,
        text: String,
        requestId: String = Uuid.random().toString(),
        expectedConnectionGeneration: Long? = null,
        onLocalEcho: () -> Unit = {},
    ): CloudDialogueSubmission {
        ready.await()
        val sub = cloudCoordinator.sendFollowup(
            conversationId = conversationId,
            workItemId = workItemId,
            text = text,
            requestId = requestId,
            expectedConnectionGeneration = expectedConnectionGeneration,
            onLocalEcho = onLocalEcho,
        )
        reconcileCloudPolling()
        requestImmediatePoll("send_followup")
        return sub
    }

    suspend fun stopCloudWorkItem(
        workItemId: String,
        requestId: String = Uuid.random().toString(),
        expectedConnectionGeneration: Long? = null,
    ): CloudDialogueSubmission {
        ready.await()
        val sub = cloudCoordinator.stopWorkItem(
            workItemId = workItemId,
            requestId = requestId,
            expectedConnectionGeneration = expectedConnectionGeneration,
        )
        reconcileCloudPolling()
        requestImmediatePoll("stop_work_item")
        return sub
    }

    suspend fun pollCloudDialogueOnce() {
        ready.await()
        pollExecutionMutex.withLock {
            cloudCoordinator.pollOnce()
        }
    }

    suspend fun recheckCloudOutcomeUnknown(workItemId: String, expectedConnectionGeneration: Long? = null): Boolean {
        ready.await()
        return cloudCoordinator.recheckOutcomeUnknownTask(workItemId, expectedConnectionGeneration)
    }

    suspend fun retryCloudOutcomeUnknown(
        conversationId: String,
        workItemId: String,
        requestId: String = Uuid.random().toString(),
        expectedConnectionGeneration: Long? = null,
    ): CloudDialogueSubmission {
        ready.await()
        val sub = cloudCoordinator.retryOutcomeUnknownTask(
            conversationId = conversationId,
            workItemId = workItemId,
            requestId = requestId,
            expectedConnectionGeneration = expectedConnectionGeneration,
        )
        reconcileCloudPolling()
        requestImmediatePoll("retry_outcome_unknown")
        return sub
    }

    suspend fun abandonCloudOutcomeUnknown(workItemId: String, expectedConnectionGeneration: Long? = null) {
        ready.await()
        cloudCoordinator.abandonOutcomeUnknownTask(workItemId, expectedConnectionGeneration)
    }

    /** Never throws; the outcome is reflected in [state]. A revoked device is forgotten locally. */
    suspend fun refresh(expectedGeneration: Long? = null) {
        ready.await()
        val client = pairingMutex.withLock {
            if (expectedGeneration != null && expectedGeneration != _connectionGeneration.value) return
            client
        } ?: return
        refresh(client)
    }

    private suspend fun refresh(client: MacGatewayClient) {
        try {
            val status = client.status()
            pairingMutex.withLock {
                if (client !== this.client) return
                val connection = _state.value.connection
                if (connection == null || connection.gatewayId != status.gateway.id || connection.deviceId != status.device.id) {
                    throw MacGatewayException.InvalidResponse()
                }
                _state.update { it.copy(status = status, reachable = true, error = null) }
            }
            if (status.soulRevision != null) {
                soulSyncCoordinator?.onRemoteSoulRevision(status.soulRevision, client)
            } else {
                soulSyncCoordinator?.syncOnce(client)
            }
        } catch (error: MacGatewayException.Unauthorized) {
            forget(client, error)
        } catch (error: MacGatewayException) {
            pairingMutex.withLock {
                if (client === this.client) _state.update { it.copy(reachable = false, error = error) }
            }
        }
    }

    suspend fun refreshMacTasks(expectedGeneration: Long) {
        require(endpointRole == GatewayEndpointRole.MAC)
        ready.await()
        macTasksMutex.withLock {
            val source = pairingMutex.withLock {
                val state = _state.value
                if (state.generation != expectedGeneration || state.reachable != true) return
                client
            } ?: return
            readMacTasks(source)
        }
    }

    suspend fun createMacSchedule(draft: MacScheduleDraft, expectedGeneration: Long) =
        changeMacSchedule(expectedGeneration) { source, _ -> source.createMacSchedule(draft) }

    suspend fun setMacScheduleEnabled(id: String, enabled: Boolean, expectedGeneration: Long) =
        changeMacSchedule(expectedGeneration) { source, schedules ->
            if (schedules.none { it.id == id }) throw MacGatewayException.Conflict("schedule_changed")
            source.setMacScheduleEnabled(id, enabled)
        }

    suspend fun runMacSchedule(id: String, expectedGeneration: Long) =
        changeMacSchedule(expectedGeneration) { source, schedules ->
            if (schedules.none { it.id == id && !it.running }) throw MacGatewayException.Conflict("schedule_changed")
            source.runMacSchedule(id)
        }

    private suspend fun changeMacSchedule(
        expectedGeneration: Long,
        action: suspend (MacGatewayClient, List<MacGatewaySchedule>) -> Unit,
    ) {
        require(endpointRole == GatewayEndpointRole.MAC)
        ready.await()
        macTasksMutex.withLock {
            val (source, schedules) = pairingMutex.withLock {
                val source = requireClientLocked(expectedGeneration)
                val state = _state.value
                if (state.reachable != true) throw MacGatewayException.Unreachable()
                val schedules = state.macTasks.schedules?.getOrThrow() ?: throw MacGatewayException.Unreachable()
                source to schedules
            }
            try {
                action(source, schedules)
            } catch (failure: MacGatewayException.Unauthorized) {
                forget(source, failure)
                throw failure
            } catch (failure: MacGatewayException) {
                // Read again after a rejected request or a lost response. A mutation is never retried here.
                readMacTasks(source)
                throw failure
            }
            readMacTasks(source)
        }
    }

    private suspend fun readMacTasks(source: MacGatewayClient) = coroutineScope {
        if (source !== client) return@coroutineScope
        val board = async { readMacCapability { source.macBoard() } }
        val schedules = async { readMacCapability { source.macSchedules() } }
        val snapshot = MacTaskSnapshot(board.await(), schedules.await())
        if (snapshot.board?.exceptionOrNull() is MacGatewayException.Unauthorized ||
            snapshot.schedules?.exceptionOrNull() is MacGatewayException.Unauthorized
        ) {
            forget(source, MacGatewayException.Unauthorized())
        } else {
            updateStateFor(source) { it.copy(macTasks = snapshot) }
        }
    }

    private suspend fun <T> readMacCapability(read: suspend () -> T): Result<T> = try {
        Result.success(read())
    } catch (failure: MacGatewayException) {
        Result.failure(failure)
    }

    /** Pulls `/v2/soul` once (e.g. on foreground connect or opening the「形象人设」panel). */
    fun syncSoulWithCloud() {
        appScope.launch {
            ready.await()
            soulSyncCoordinator?.syncOnce()
        }
    }

    suspend fun setMonitored(taskKey: String, monitored: Boolean, expectedGeneration: Long? = null) {
        val client = requireClient(expectedGeneration)
        suspend fun apply(value: Boolean) = updateStateFor(client) { state ->
            state.copy(status = state.status?.copy(sessions = state.status.sessions.map {
                if (it.key == taskKey) it.copy(monitored = value) else it
            }))
        }
        apply(monitored)
        try {
            client.setMonitored(taskKey, monitored)
        } catch (error: MacGatewayException) {
            apply(!monitored)
            throw error
        }
    }

    suspend fun decide(
        sessionKey: String,
        toolCallId: String,
        allow: Boolean,
        highRisk: Boolean = false,
        requiresLocalApproval: Boolean = false,
        riskLevel: String? = null,
        verifyForeground: (suspend () -> Boolean)? = null,
        expectedGeneration: Long? = null,
        fromMacBoard: Boolean = false,
    ) {
        ready.await()
        val (client, snapshot) = pairingMutex.withLock {
            val source = requireClientLocked(expectedGeneration)
            source to _state.value
        }
        val boardApproval = snapshot.macTasks.board?.getOrNull()
            ?.firstNotNullOfOrNull { item ->
                item.approval?.takeIf { it.sessionKey == sessionKey && it.toolCallId == toolCallId }
            }?.asPendingApproval()
        if (fromMacBoard && (endpointRole != GatewayEndpointRole.MAC || boardApproval == null || snapshot.reachable != true)) {
            throw MacGatewayException.Conflict("gateway_approval_changed")
        }
        val sessionApproval = snapshot.status?.sessions
            ?.firstOrNull { it.key == sessionKey && it.pendingApproval?.toolCallId == toolCallId }
            ?.pendingApproval
        val effectiveHighRisk = highRisk || sessionApproval?.highRisk == true || boardApproval?.highRisk == true
        val effectiveRiskLevel = riskLevel ?: boardApproval?.riskLevel ?: sessionApproval?.riskLevel
        val effectiveLocalOnly = requiresLocalApproval ||
            sessionApproval?.effectiveRequiresLocalApproval == true ||
            boardApproval?.effectiveRequiresLocalApproval == true ||
            effectiveHighRisk ||
            effectiveRiskLevel.equals("L3", ignoreCase = true)
        if (effectiveLocalOnly && allow) {
            throw MacGatewayException.Forbidden(
                code = "high-risk actions must be approved on Mac",
                requiresLocalApproval = true,
                riskLevel = effectiveRiskLevel ?: if (effectiveHighRisk) "L3" else null,
                targetDevice = sessionApproval?.targetDevice,
                targetSummary = sessionApproval?.targetSummary,
                diffPreview = sessionApproval?.diffPreview,
            )
        }
        try {
            client.decide(
                sessionKey = sessionKey,
                toolCallId = toolCallId,
                allow = allow,
                verifyForeground = verifyForeground?.let { verify ->
                    suspend { verify() && client === this.client }
                },
                highRisk = effectiveHighRisk,
                requiresLocalApproval = effectiveLocalOnly,
                riskLevel = effectiveRiskLevel,
                targetDevice = sessionApproval?.targetDevice,
                targetSummary = sessionApproval?.targetSummary,
                diffPreview = sessionApproval?.diffPreview,
            )
        } catch (forbidden: MacGatewayException.Forbidden) {
            // A foreground challenge leaves the remote approval available.
            if (!forbidden.requiresLocalApproval && !forbidden.riskLevel.equals("L3", ignoreCase = true)) throw forbidden
            updateStateFor(client) { current ->
                current.copy(
                    status = current.status?.copy(
                        sessions = current.status.sessions.map { session ->
                            val pending = session.pendingApproval
                            if (session.key == sessionKey && pending?.toolCallId == toolCallId) {
                                session.copy(
                                    pendingApproval = pending.copy(
                                        requiresLocalApproval = true,
                                        riskLevel = forbidden.riskLevel ?: pending.riskLevel,
                                        targetDevice = forbidden.targetDevice ?: pending.targetDevice,
                                        targetSummary = forbidden.targetSummary ?: pending.targetSummary,
                                        diffPreview = forbidden.diffPreview ?: pending.diffPreview,
                                    ),
                                )
                            } else {
                                session
                            }
                        },
                    ),
                )
            }
            throw forbidden
        }
        updateStateFor(client) { current ->
            current.copy(
                status = current.status?.copy(
                    sessions = current.status.sessions.map { session ->
                        if (session.key == sessionKey && session.pendingApproval?.toolCallId == toolCallId) {
                            session.copy(pendingApproval = null)
                        } else {
                            session
                        }
                    },
                ),
            )
        }
        refresh(client)
        if (endpointRole == GatewayEndpointRole.MAC && snapshot.macTasks.board != null) {
            refreshMacTasks(snapshot.generation)
        }
    }

    suspend fun uploadPushToken(expectedGeneration: Long? = null) {
        uploadPushToken(requireClient(expectedGeneration))
    }

    private suspend fun uploadPushToken(client: MacGatewayClient) {
        val pushToken = fetchFcmToken()
        if (this.client !== client) throw MacGatewayException.Conflict("gateway_pairing_changed")
        client.registerPushToken(pushToken)
        updateStateFor(client) { state ->
            state.copy(status = state.status?.copy(device = state.status.device.copy(pushRegistered = true)))
        }
    }

    suspend fun testPush(expectedGeneration: Long? = null) {
        requireClient(expectedGeneration).testPush()
    }

    /** Returns whether the server acknowledged unpairing. The local pairing is removed either way. */
    suspend fun unpair(expectedGeneration: Long? = null): Boolean {
        ready.await()
        val client = pairingMutex.withLock {
            if (expectedGeneration != null && expectedGeneration != _connectionGeneration.value) {
                throw MacGatewayException.Conflict("gateway_pairing_changed")
            }
            client
        }
        val acknowledged = try {
            client?.unpair()
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: MacGatewayException) {
            false
        }
        forget(client)
        return acknowledged
    }

    fun onNewPushToken() {
        GatewayEndpointRole.entries.forEach { role ->
            val slot = slot(role)
            appScope.launch {
                val client = slot.activeClient() ?: return@launch
                runCatching { slot.uploadPushToken(client) }
                    .onFailure { Log.w(TAG, "push token refresh upload failed") }
            }
        }
    }

    private suspend fun resolveAndMigrateToken(
        credential: String,
        legacyToken: String?,
    ): String? {
        val vaultToken = runCatching { tokenVault.read(credential) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
        if (vaultToken != null) {
            if (legacyToken != null) {
                runCatching {
                    dataStore.edit { it.remove(legacyTokenKey) }
                }.onFailure {
                    Log.w(TAG, "failed to clean up legacy gateway token key after vault hit")
                }
            }
            return vaultToken
        }
        if (legacyToken == null) return null
        val migrated = runCatching {
            tokenVault.write(credential, legacyToken)
        }.isSuccess
        if (migrated) {
            runCatching {
                dataStore.edit { it.remove(legacyTokenKey) }
            }.onFailure {
                Log.w(TAG, "failed to remove legacy gateway token key after migration")
            }
        } else {
            Log.w(TAG, "gateway token migration to SecretStore failed; keeping legacy fallback")
        }
        return legacyToken
    }

    private fun computePeriodicDelayMs(): Long? {
        val state = _state.value
        if (state.connection == null || state.cloudDialogueSupported == false) return null
        val ui = _cloudDialogueState.value
        val hasActive = ui.hasActiveTasks()
        val hasPending = ui.hasPendingSubmissions()
        if (!state.isCloudDialogueActive && !hasActive && !hasPending) return null
        return cloudPollDelayMs(
            foreground = isForeground.value,
            hasActiveTask = hasActive,
            hasPendingSubmission = hasPending,
        )
    }

    private fun reconcileCloudPolling() {
        pollWakeup.trySend(Unit)
        if (computePeriodicDelayMs() != null || immediatePollRequested.get()) {
            ensurePollLoopRunning()
        }
    }

    private fun ensurePollLoopRunning() {
        synchronized(pollLock) {
            if (cloudPollJob?.isActive == true) return
            lateinit var launched: Job
            launched = appScope.launch {
                try {
                    ready.await()
                    runPollLoop()
                } finally {
                    var shouldRestart = false
                    synchronized(pollLock) {
                        if (cloudPollJob === launched) {
                            cloudPollJob = null
                        }
                        if (immediatePollRequested.get() || computePeriodicDelayMs() != null) {
                            shouldRestart = true
                        }
                    }
                    if (shouldRestart) {
                        ensurePollLoopRunning()
                    }
                }
            }
            cloudPollJob = launched
        }
    }

    private suspend fun runPollLoop() {
        var lastPollAtMs: Long? = null
        while (appScope.isActive) {
            val requestedImmediate = immediatePollRequested.getAndSet(false)
            val forceWhenDisabled = immediatePollForceWhenDisabled.getAndSet(false)
            val state = _state.value
            if (state.connection == null) break

            val ui = _cloudDialogueState.value
            val hasActive = ui.hasActiveTasks()
            val hasPending = ui.hasPendingSubmissions()
            val periodicEnabled = state.cloudDialogueSupported != false && (state.isCloudDialogueActive || hasActive || hasPending)
            val delayMs = if (periodicEnabled) {
                cloudPollDelayMs(
                    foreground = isForeground.value,
                    hasActiveTask = hasActive,
                    hasPendingSubmission = hasPending,
                )
            } else {
                null
            }

            val allowImmediate = requestedImmediate && (periodicEnabled || forceWhenDisabled)
            if (!allowImmediate && delayMs == null) {
                break
            }

            val elapsedMs = lastPollAtMs?.let { (nowMs() - it).coerceAtLeast(0L) }
            val shouldPollNow = allowImmediate ||
                elapsedMs == null ||
                (delayMs != null && elapsedMs >= delayMs)

            if (shouldPollNow) {
                val pollGen = _connectionGeneration.value
                pollExecutionMutex.withLock {
                    if (_connectionGeneration.value != pollGen || _state.value.connection == null) {
                        return@withLock
                    }
                    cloudCoordinator.pollOnce()
                }
                lastPollAtMs = nowMs()
                continue
            }

            val remainingMs = (delayMs!! - elapsedMs!!).coerceAtLeast(1L)
            withTimeoutOrNull(remainingMs) {
                pollWakeup.receive()
            }
        }
    }

    private suspend fun activeClient(): MacGatewayClient? {
        ready.await()
        return client
    }

    private suspend fun updateStateFor(source: MacGatewayClient, change: (State) -> State) {
        pairingMutex.withLock {
            if (client === source) _state.update(change)
        }
    }

    internal suspend fun clientForPairing(
        gatewayId: String,
        deviceId: String,
        generation: Long,
        expectedConnection: MacGatewayConnection? = null,
    ): MacGatewayClient? {
        ready.await()
        return pairingMutex.withLock {
            val connection = _state.value.connection
            if (connection == null || generation != _connectionGeneration.value ||
                connection.gatewayId != gatewayId || connection.deviceId != deviceId ||
                (expectedConnection != null && connection != expectedConnection)
            ) return@withLock null
            client
        }
    }

    private suspend fun requireClient(expectedGeneration: Long?): MacGatewayClient {
        ready.await()
        return pairingMutex.withLock { requireClientLocked(expectedGeneration) }
    }

    private fun requireClientLocked(expectedGeneration: Long?): MacGatewayClient {
        if (expectedGeneration != null && expectedGeneration != _connectionGeneration.value) {
            throw MacGatewayException.Conflict("gateway_pairing_changed")
        }
        return client ?: throw MacGatewayException.Unreachable()
    }

    private fun storedCredential(prefs: Preferences): String? =
        prefs[credentialKey] ?: decodeConnection(prefs[connectionKey])?.gatewayId

    private fun clearPairing(prefs: MutablePreferences) {
        prefs.remove(connectionKey)
        prefs.remove(legacyTokenKey)
        prefs.remove(credentialKey)
        prefs.remove(dialogueEnabledKey)
        prefs.remove(dialogueStateKey)
    }

    private suspend fun forget(expectedClient: MacGatewayClient?, reason: MacGatewayException? = null): Unit =
        withContext(NonCancellable) {
            pairingMutex.withLock {
                if (client !== expectedClient) return@withLock
                val credential = storedCredential(dataStore.data.first())
                dataStore.edit { clearPairing(it) }
                cloudCoordinator.restore(enabled = false, connection = null, persisted = null) {
                    val next = _connectionGeneration.value + 1L
                    _connectionGeneration.value = next
                    client = null
                    immediatePollRequested.set(false)
                    immediatePollForceWhenDisabled.set(false)
                    soulSyncCoordinator?.resetConnectionSession()
                    _state.value = State(loaded = true, generation = next, error = reason)
                    if (_selectedDialogueRole.value == endpointRole) _selectedDialogueRole.value = null
                }
                credential?.let { runCatching { tokenVault.delete(it) } }
            }
            pollWakeup.trySend(Unit)
            Unit
        }

    private suspend fun fetchFcmToken(): String = suspendCancellableCoroutine { continuation ->
        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            val token = if (task.isSuccessful) task.result else null
            if (token != null) continuation.resume(token)
            else continuation.resumeWithException(task.exception ?: IllegalStateException("FCM token unavailable"))
        }
    }

    private fun CloudDialogueUiState.hasActiveTasks(): Boolean =
        tasks.any { it.active }

    private fun CloudDialogueUiState.hasPendingSubmissions(): Boolean =
        submissions.any { sub ->
            (sub.state == CloudInputDisposition.RECEIVED && (sub.eventId != null || sub.error == null)) ||
                (sub.eventId != null && sub.kind == CloudSubmissionKind.MESSAGE && !sub.receiptSyncedAfterConsumed)
        }

    internal suspend fun awaitReady() {
        ready.await()
    }

    private companion object {
        const val TAG = "MacGateway"
        const val REASON_FOREGROUND = "foreground"
        fun decodeConnection(raw: String?): MacGatewayConnection? =
            raw?.let { runCatching { MacGatewayJson.decodeFromString<MacGatewayConnection>(it) }.getOrNull() }

        fun encodeConnection(connection: MacGatewayConnection): String =
            MacGatewayJson.encodeToString(MacGatewayConnection.serializer(), connection)
    }
}
