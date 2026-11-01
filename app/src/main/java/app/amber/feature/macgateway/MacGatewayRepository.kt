package app.amber.feature.macgateway

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.firebase.messaging.FirebaseMessaging
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.uuid.Uuid
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

private val Context.macGatewayDataStore by preferencesDataStore(name = "mac_gateway")

/**
 * The paired Mac Gateway. The device token lives in app-private DataStore next to the connection
 * (same treatment as the Synara companion token); treat it like a password.
 */
class MacGatewayRepository(
    private val context: Context,
    private val appScope: CoroutineScope,
    timelineSink: CloudDialogueTimelineSink = CloudDialogueTimelineSink.NoOp,
) {
    data class State(
        val loaded: Boolean = false,
        val connection: MacGatewayConnection? = null,
        val status: MacGatewayStatus? = null,
        /** null until the first refresh after pairing/launch. */
        val reachable: Boolean? = null,
        val error: MacGatewayException? = null,
        /** Explicit toggle for routing main chat messages to the paired cloud `/v2/dialogue` API. */
        val cloudDialogueEnabled: Boolean = false,
    ) {
        val isCloudDialogueActive: Boolean
            get() = cloudDialogueEnabled && connection != null
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val _cloudDialogueState = MutableStateFlow(CloudDialogueUiState())
    val cloudDialogueState: StateFlow<CloudDialogueUiState> = _cloudDialogueState.asStateFlow()

    private val ready = CompletableDeferred<Unit>()

    @Volatile
    private var client: MacGatewayClient? = null

    private var cloudPollJob: Job? = null

    private val cloudCoordinator = CloudDialogueCoordinator(
        clientProvider = { activeClient() },
        timelineSink = timelineSink,
        persistState = { persisted ->
            context.macGatewayDataStore.edit { prefs ->
                prefs[CLOUD_DIALOGUE_STATE] = MacGatewayJson.encodeToString(
                    CloudDialoguePersistedState.serializer(),
                    persisted,
                )
            }
        },
        onUnauthorized = {
            forget(MacGatewayException.Unauthorized())
        },
        onStateChanged = { uiState ->
            _cloudDialogueState.value = uiState
            reconcileCloudPolling()
        },
    )

    init {
        appScope.launch {
            val prefs = context.macGatewayDataStore.data.first()
            val connection = prefs[CONNECTION]?.let {
                runCatching { MacGatewayJson.decodeFromString<MacGatewayConnection>(it) }.getOrNull()
            }
            val token = prefs[TOKEN]
            val cloudEnabled = prefs[CLOUD_DIALOGUE_ENABLED] == true
            val persistedDialogue = prefs[CLOUD_DIALOGUE_STATE]?.let {
                runCatching { MacGatewayJson.decodeFromString<CloudDialoguePersistedState>(it) }.getOrNull()
            }
            if (connection != null && token != null) client = connection.client(token)
            val activeConnection = connection.takeIf { client != null }
            _state.update {
                it.copy(
                    loaded = true,
                    connection = activeConnection,
                    cloudDialogueEnabled = cloudEnabled && activeConnection != null,
                )
            }
            cloudCoordinator.restore(
                enabled = cloudEnabled && activeConnection != null,
                connection = activeConnection,
                persisted = persistedDialogue,
            )
            ready.complete(Unit)
            reconcileCloudPolling()
        }
    }

    suspend fun pair(payload: MacGatewayPairingPayload) {
        ready.await()
        val response = MacGatewayClient(payload.addrs, payload.port, payload.fp, token = null)
            .pair(payload.s, deviceName = Build.MODEL)
        val connection = MacGatewayConnection(
            gatewayId = response.gatewayId,
            name = response.gatewayName,
            addrs = payload.addrs,
            port = payload.port,
            fingerprint = payload.fp,
            deviceId = response.deviceId,
        )
        val cloudEnabled = _state.value.cloudDialogueEnabled
        context.macGatewayDataStore.edit {
            it[CONNECTION] = MacGatewayJson.encodeToString(MacGatewayConnection.serializer(), connection)
            it[TOKEN] = response.token
            it.remove(CLOUD_DIALOGUE_STATE)
        }
        client = connection.client(response.token)
        _state.value = State(loaded = true, connection = connection, cloudDialogueEnabled = cloudEnabled)
        cloudCoordinator.restore(enabled = cloudEnabled, connection = connection, persisted = null)
        runCatching { uploadPushToken() }.onFailure { Log.w(TAG, "push token upload after pairing failed", it) }
        refresh()
    }

    suspend fun setCloudDialogueEnabled(enabled: Boolean) {
        ready.await()
        val effective = enabled && _state.value.connection != null
        context.macGatewayDataStore.edit {
            it[CLOUD_DIALOGUE_ENABLED] = effective
        }
        _state.update { it.copy(cloudDialogueEnabled = effective) }
        cloudCoordinator.setEnabled(effective)
        reconcileCloudPolling()
    }

    fun bindActiveConversation(conversationId: String) {
        appScope.launch {
            ready.await()
            cloudCoordinator.bindActiveConversation(conversationId)
            reconcileCloudPolling()
        }
    }

    fun selectFollowupTarget(workItemId: String?) {
        appScope.launch {
            ready.await()
            cloudCoordinator.selectFollowupTarget(workItemId)
        }
    }

    suspend fun sendCloudMessage(
        conversationId: String,
        text: String,
        requestId: String = Uuid.random().toString(),
    ): CloudDialogueSubmission {
        ready.await()
        val sub = cloudCoordinator.sendMessage(
            conversationId = conversationId,
            text = text,
            requestId = requestId,
        )
        reconcileCloudPolling()
        appScope.launch { cloudCoordinator.pollOnce() }
        return sub
    }

    suspend fun sendCloudFollowup(
        conversationId: String,
        workItemId: String,
        text: String,
        requestId: String = Uuid.random().toString(),
    ): CloudDialogueSubmission {
        ready.await()
        val sub = cloudCoordinator.sendFollowup(
            conversationId = conversationId,
            workItemId = workItemId,
            text = text,
            requestId = requestId,
        )
        reconcileCloudPolling()
        appScope.launch { cloudCoordinator.pollOnce() }
        return sub
    }

    suspend fun stopCloudWorkItem(
        workItemId: String,
        requestId: String = Uuid.random().toString(),
    ): CloudDialogueSubmission {
        ready.await()
        val sub = cloudCoordinator.stopWorkItem(
            workItemId = workItemId,
            requestId = requestId,
        )
        reconcileCloudPolling()
        appScope.launch { cloudCoordinator.pollOnce() }
        return sub
    }

    suspend fun pollCloudDialogueOnce() {
        ready.await()
        cloudCoordinator.pollOnce()
    }

    /** Never throws; the outcome is reflected in [state]. A revoked device is forgotten locally. */
    suspend fun refresh() {
        val client = activeClient() ?: return
        try {
            val status = client.status()
            _state.update { it.copy(status = status, reachable = true, error = null) }
        } catch (error: MacGatewayException.Unauthorized) {
            forget(error)
        } catch (error: MacGatewayException) {
            _state.update { it.copy(reachable = false, error = error) }
        }
    }

    suspend fun setMonitored(taskKey: String, monitored: Boolean) {
        val client = activeClient() ?: return
        fun apply(value: Boolean) = _state.update { state ->
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
    ) {
        val sessionApproval = _state.value.status?.sessions
            ?.firstOrNull { it.key == sessionKey && it.pendingApproval?.toolCallId == toolCallId }
            ?.pendingApproval
        val effectiveHighRisk = highRisk || sessionApproval?.highRisk == true
        if (effectiveHighRisk && allow) {
            throw MacGatewayException.Server("high-risk actions must be approved on Mac")
        }
        val client = activeClient() ?: return
        client.decide(
            sessionKey = sessionKey,
            toolCallId = toolCallId,
            allow = allow,
            highRisk = effectiveHighRisk,
        )
        _state.update { current ->
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
        refresh()
    }

    suspend fun uploadPushToken() {
        val client = activeClient() ?: return
        client.registerPushToken(fetchFcmToken())
        _state.update { state ->
            state.copy(status = state.status?.copy(device = state.status.device.copy(pushRegistered = true)))
        }
    }

    suspend fun testPush() {
        activeClient()?.testPush()
    }

    /** Returns false when the Mac could not be told; the pairing is forgotten locally either way. */
    suspend fun unpair(): Boolean {
        val client = activeClient() ?: return true
        val acknowledged = runCatching { client.unpair() }.isSuccess
        forget()
        return acknowledged
    }

    fun onNewPushToken() {
        appScope.launch {
            runCatching { uploadPushToken() }.onFailure { Log.w(TAG, "push token refresh upload failed", it) }
        }
    }

    private fun reconcileCloudPolling() {
        val shouldPoll = _state.value.isCloudDialogueActive ||
            (_state.value.connection != null && _cloudDialogueState.value.tasks.any { it.active })
        if (!shouldPoll) {
            cloudPollJob?.cancel()
            cloudPollJob = null
            return
        }
        if (cloudPollJob?.isActive == true) return
        cloudPollJob = appScope.launch {
            ready.await()
            while (isActive) {
                val active = _state.value.isCloudDialogueActive ||
                    (_state.value.connection != null && _cloudDialogueState.value.tasks.any { it.active })
                if (!active) break
                cloudCoordinator.pollOnce()
                delay(CLOUD_POLL_INTERVAL_MS)
            }
        }
    }

    private suspend fun activeClient(): MacGatewayClient? {
        ready.await()
        return client
    }

    private suspend fun forget(reason: MacGatewayException? = null) {
        cloudPollJob?.cancel()
        cloudPollJob = null
        client = null
        context.macGatewayDataStore.edit { it.clear() }
        _state.value = State(loaded = true, error = reason, cloudDialogueEnabled = false)
        cloudCoordinator.restore(enabled = false, connection = null, persisted = null)
    }

    private suspend fun fetchFcmToken(): String = suspendCancellableCoroutine { continuation ->
        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            val token = if (task.isSuccessful) task.result else null
            if (token != null) continuation.resume(token)
            else continuation.resumeWithException(task.exception ?: IllegalStateException("FCM token unavailable"))
        }
    }

    private fun MacGatewayConnection.client(token: String) = MacGatewayClient(addrs, port, fingerprint, token)

    private companion object {
        const val TAG = "MacGateway"
        const val CLOUD_POLL_INTERVAL_MS = 1_000L
        val CONNECTION = stringPreferencesKey("connection")
        val TOKEN = stringPreferencesKey("token")
        val CLOUD_DIALOGUE_ENABLED = booleanPreferencesKey("cloud_dialogue_enabled")
        val CLOUD_DIALOGUE_STATE = stringPreferencesKey("cloud_dialogue_state")
    }
}
