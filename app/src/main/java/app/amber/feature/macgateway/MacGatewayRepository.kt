package app.amber.feature.macgateway

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.firebase.messaging.FirebaseMessaging
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
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
) {
    data class State(
        val loaded: Boolean = false,
        val connection: MacGatewayConnection? = null,
        val status: MacGatewayStatus? = null,
        /** null until the first refresh after pairing/launch. */
        val reachable: Boolean? = null,
        val error: MacGatewayException? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val ready = CompletableDeferred<Unit>()

    @Volatile
    private var client: MacGatewayClient? = null

    init {
        appScope.launch {
            val prefs = context.macGatewayDataStore.data.first()
            val connection = prefs[CONNECTION]?.let {
                runCatching { MacGatewayJson.decodeFromString<MacGatewayConnection>(it) }.getOrNull()
            }
            val token = prefs[TOKEN]
            if (connection != null && token != null) client = connection.client(token)
            _state.update { it.copy(loaded = true, connection = connection.takeIf { client != null }) }
            ready.complete(Unit)
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
        context.macGatewayDataStore.edit {
            it[CONNECTION] = MacGatewayJson.encodeToString(MacGatewayConnection.serializer(), connection)
            it[TOKEN] = response.token
        }
        client = connection.client(response.token)
        _state.value = State(loaded = true, connection = connection)
        runCatching { uploadPushToken() }.onFailure { Log.w(TAG, "push token upload after pairing failed", it) }
        refresh()
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

    private suspend fun activeClient(): MacGatewayClient? {
        ready.await()
        return client
    }

    private suspend fun forget(reason: MacGatewayException? = null) {
        client = null
        context.macGatewayDataStore.edit { it.clear() }
        _state.value = State(loaded = true, error = reason)
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
        val CONNECTION = stringPreferencesKey("connection")
        val TOKEN = stringPreferencesKey("token")
    }
}
