package app.amber.feature.ui.pages.synara

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.amber.core.ai.mcp.McpCommonOptions
import app.amber.core.ai.mcp.McpServerConfig
import app.amber.core.settings.prefs.SettingsAggregator
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
    private val settingsStore: SettingsAggregator,
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

    /**
     * Upserts a Streamable HTTP MCP server pointing at Synara External MCP.
     * Credential comes from Mac-side `scripts/synara-external-mcp-setup.py` (prefix syn_mcp_v1_).
     */
    fun installExternalMcp() {
        val draft = _ui.value.draft
        val hostError = draft.validationError()
        if (hostError != null) {
            _ui.update { it.copy(lastCheckOk = false, lastCheckMessage = hostError) }
            return
        }
        val credential = draft.mcpCredential.trim()
        if (!credential.startsWith(SynaraConnection.MCP_CREDENTIAL_PREFIX)) {
            _ui.update {
                it.copy(
                    lastCheckOk = false,
                    lastCheckMessage = "请粘贴 External MCP credential（以 syn_mcp_v1_ 开头）",
                )
            }
            return
        }
        viewModelScope.launch {
            store.save(draft)
            val settings = settingsStore.settingsFlow.first { !it.init }
            val url = draft.externalMcpUrl()
            val headers = listOf("Authorization" to "Bearer $credential")
            val existing = settings.mcpServers.firstOrNull {
                it.commonOptions.name.equals(SynaraConnection.MCP_SERVER_NAME, ignoreCase = true) ||
                    (it is McpServerConfig.StreamableHTTPServer && it.url == url)
            }
            val nextServer = when (existing) {
                is McpServerConfig.StreamableHTTPServer -> existing.copy(
                    url = url,
                    commonOptions = existing.commonOptions.copy(
                        enable = true,
                        name = SynaraConnection.MCP_SERVER_NAME,
                        headers = headers,
                    ),
                )
                else -> McpServerConfig.StreamableHTTPServer(
                    commonOptions = McpCommonOptions(
                        enable = true,
                        name = SynaraConnection.MCP_SERVER_NAME,
                        headers = headers,
                    ),
                    url = url,
                )
            }
            val mcpServers = if (existing != null) {
                settings.mcpServers.map { if (it.id == existing.id) nextServer else it }
            } else {
                settings.mcpServers + nextServer
            }
            settingsStore.update(settings.copy(mcpServers = mcpServers))
            _ui.update {
                it.copy(
                    lastCheckOk = true,
                    lastCheckMessage = "已写入 MCP「${SynaraConnection.MCP_SERVER_NAME}」· $url\n请在当前 Assistant 勾选该服务器",
                )
            }
        }
    }

    fun testConnection() {
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
            // Synara 0.7+: negotiate over HTTP, then open feature /ws with compatibility params.
            // Plain /ws?token= alone returns 426 WS_NEGOTIATION_REQUIRED.
            val negotiateBody = client.newCall(
                Request.Builder()
                    .url(connection.negotiateUrl())
                    .get()
                    .header("Accept", "application/json")
                    .build(),
            ).execute().use { response ->
                val body = response.body.string()
                if (!response.isSuccessful) {
                    error("negotiate HTTP ${response.code}: ${body.take(180)}")
                }
                body
            }
            val negotiated = JSONObject(negotiateBody)
            val protocolEpoch = negotiated.optInt("protocolEpoch", 0)
            val revision = negotiated.optInt("negotiatedRevision", 0)
            val serverInstanceId = negotiated.optString("serverInstanceId")
            if (protocolEpoch <= 0 || revision <= 0 || serverInstanceId.isBlank()) {
                error("negotiate 响应不完整")
            }
            probeAuthenticatedWebSocket(
                connection.featureWsUrl(
                    protocolEpoch = protocolEpoch,
                    protocolRevision = revision,
                    serverInstanceId = serverInstanceId,
                ),
            )
            "健康检查和 Auth Token 验证通过 · ${connection.httpBaseUrl()}"
        }
    }

    private suspend fun probeAuthenticatedWebSocket(wsUrl: String) = withTimeout(5_000L) {
        suspendCancellableCoroutine { continuation ->
            lateinit var socket: WebSocket
            socket = client.newWebSocket(
                Request.Builder().url(wsUrl).build(),
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
}
