package app.amber.feature.ui.pages.synara

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import java.util.concurrent.TimeUnit

data class SynaraUiState(
    val draft: SynaraConnection = SynaraConnection(),
    val checking: Boolean = false,
    val lastCheckMessage: String? = null,
    val lastCheckOk: Boolean? = null,
)

class SynaraVM(
    private val store: SynaraConnectionStore,
) : ViewModel() {
    private val client = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .callTimeout(6, TimeUnit.SECONDS)
        .build()

    private val _ui = MutableStateFlow(SynaraUiState())
    val ui: StateFlow<SynaraUiState> = _ui.asStateFlow()

    val saved: StateFlow<SynaraConnection> = store.connectionFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, SynaraConnection())

    init {
        viewModelScope.launch {
            // Seed the form once from disk; subsequent typing is local until save/test.
            val initial = store.connectionFlow.first()
            _ui.update { it.copy(draft = initial) }
        }
    }

    fun updateDraft(transform: (SynaraConnection) -> SynaraConnection) {
        _ui.update { it.copy(draft = transform(it.draft), lastCheckMessage = null, lastCheckOk = null) }
    }

    fun reportError(message: String) {
        _ui.update { it.copy(lastCheckOk = false, lastCheckMessage = message) }
    }

    fun save(onSaved: (SynaraConnection) -> Unit = {}) {
        val draft = _ui.value.draft
        val error = draft.validationError()
        if (error != null) {
            _ui.update { it.copy(lastCheckOk = false, lastCheckMessage = error) }
            return
        }
        viewModelScope.launch {
            store.save(draft)
            onSaved(draft)
        }
    }

    /** Probe health + WS auth; auto-save on success, then run [onVerified] (pair-and-enter). */
    fun testConnection(onVerified: (SynaraConnection) -> Unit = {}) {
        val draft = _ui.value.draft
        val error = draft.validationError()
        if (error != null) {
            _ui.update { it.copy(lastCheckOk = false, lastCheckMessage = error) }
            return
        }
        viewModelScope.launch {
            _ui.update { it.copy(checking = true, lastCheckMessage = null, lastCheckOk = null) }
            val result = withContext(Dispatchers.IO) { probeConnection(draft) }
            _ui.update {
                it.copy(
                    checking = false,
                    lastCheckOk = result.isSuccess,
                    lastCheckMessage = result.getOrElse { e -> e.message ?: "连接失败" },
                )
            }
            if (result.isSuccess) {
                store.save(draft)
                onVerified(draft)
            }
        }
    }

    private suspend fun probeConnection(connection: SynaraConnection): Result<String> {
        return runCatching {
            val healthRequest = Request.Builder()
                .url(connection.healthUrl())
                .get()
                .header("Accept", "application/json")
                .build()
            val healthBody = client.newCall(healthRequest).execute().use { response ->
                val body = response.body.string()
                if (!response.isSuccessful) error("HTTP ${response.code}")
                body
            }
            val status = runCatching { JSONObject(healthBody).optString("status") }.getOrDefault("")
            if (!status.equals("ok", ignoreCase = true)) {
                error("health 未就绪（status=${status.ifBlank { "empty" }}）")
            }
            probeAuthenticatedWebSocket(connection)
            "健康检查和 Auth Token 验证通过 · ${connection.httpBaseUrl()}"
        }
    }

    /** Try the WS endpoints newest-first; any 101 means the surface is reachable. */
    private suspend fun probeAuthenticatedWebSocket(connection: SynaraConnection) {
        var lastError: Throwable? = null
        for (url in connection.wsProbeUrls()) {
            try {
                withTimeout(5_000L) { openWebSocket(url) }
                return
            } catch (e: Throwable) {
                lastError = e
            }
        }
        throw lastError ?: IllegalStateException("WS probe failed")
    }

    private suspend fun openWebSocket(url: String) = suspendCancellableCoroutine { continuation ->
        lateinit var socket: WebSocket
        socket = client.newWebSocket(
            Request.Builder().url(url).build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    if (continuation.isActive) continuation.resume(Unit)
                    webSocket.close(1000, "connection test complete")
                }

                override fun onFailure(webSocket: WebSocket, error: Throwable, response: Response?) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            },
        )
        continuation.invokeOnCancellation { socket.cancel() }
    }
}
