package app.amber.core.ai.mcp

import android.content.Context
import android.util.Log
import androidx.core.net.toUri
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.sse.SSE
import io.ktor.serialization.kotlinx.json.json
import io.ktor.util.StringValues
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.shared.AbstractTransport
import io.modelcontextprotocol.kotlin.sdk.shared.RequestOptions
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.ImageContent
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.ClassDiscriminatorMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import app.amber.agent.R
import app.amber.ai.core.InputSchema
import app.amber.ai.ui.UIMessagePart
import app.amber.agent.AppScope
import app.amber.core.ai.mcp.transport.SseClientTransport
import app.amber.core.ai.mcp.transport.StreamableHttpClientTransport
import app.amber.core.event.AppEventBus
import app.amber.core.settings.prefs.SettingsAggregator
import app.amber.core.files.FilesManager
import app.amber.core.utils.JsonInstant
import app.amber.core.utils.checkDifferent
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import kotlin.io.encoding.Base64
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

private const val TAG = "McpManager"
private const val MAX_RECONNECT_ATTEMPTS = 5
private const val BASE_RECONNECT_DELAY_MS = 1000L
private const val MAX_RECONNECT_DELAY_MS = 30000L

class McpManager(
    private val context: Context,
    private val settingsStore: SettingsAggregator,
    private val appScope: AppScope,
    private val filesManager: FilesManager,
    appEventBus: AppEventBus,
) {
    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.MINUTES)
        .writeTimeout(120, TimeUnit.SECONDS)
        .followSslRedirects(true)
        .followRedirects(true)
        .build()

    private val client = HttpClient(OkHttp) {
        engine {
            preconfigured = okHttpClient
        }
        install(ContentNegotiation) {
            json(Json {
                prettyPrint = true
                isLenient = true
            })
        }
        install(SSE)
    }

    private val clients: MutableMap<McpServerConfig, Client> = mutableMapOf()
    private val reconnectJobs: MutableMap<Uuid, Job> = mutableMapOf()
    private val reconnectAttempts: MutableMap<Uuid, Int> = mutableMapOf()
    val syncingStatus = MutableStateFlow<Map<Uuid, McpStatus>>(mapOf())

    private val oauthCoordinator = McpOAuthCoordinator(
        settingsStore = settingsStore,
        appScope = appScope,
        appEventBus = appEventBus,
        oauthClient = McpOAuthClient(okHttpClient, context),
        updateStatus = { configId, status ->
            syncingStatus.value = syncingStatus.value + (configId to status)
        },
    )

    init {
        appScope.launch {
            settingsStore.settingsFlow
                .map { settings -> settings.mcpServers }
                .collect { mcpServerConfigs ->
                    runCatching {
                        Log.i(TAG, "update configs: ${mcpServerConfigs.map { it.commonOptions.name }}")
                        val newConfigs = mcpServerConfigs.filter { it.commonOptions.enable }
                        val currentConfigs = clients.keys.toList()
                        val (toAdd, toRemove) = currentConfigs.checkDifferent(
                            other = newConfigs,
                            eq = { a, b -> a.id == b.id }
                        )
                        Log.i(TAG, "to_add: ${toAdd.map { it.commonOptions.name }}")
                        Log.i(TAG, "to_remove: ${toRemove.map { it.commonOptions.name }}")
                        toAdd.forEach { cfg ->
                            appScope.launch {
                                runCatching { addClient(cfg) }
                                    .onFailure { it.printStackTrace() }
                            }
                        }
                        toRemove.forEach { cfg ->
                            appScope.launch {
                                // 仅真正移除（禁用/删除）时清理 OAuth 授权状态，重连不取消进行中的授权
                                oauthCoordinator.forget(cfg.id)
                                removeClient(cfg)
                            }
                        }
                    }.onFailure {
                        it.printStackTrace()
                    }
                }
        }
    }

    fun getClient(config: McpServerConfig): Client? {
        return clients.entries.find { it.key.id == config.id }?.value
    }

    fun getAllAvailableTools(): List<McpTool> {
        val settings = settingsStore.settingsFlow.value
        val mcpServers = settings.mcpServers
            .filter {
                it.commonOptions.enable && it.id in settings.enabledMcpServerIds
            }
            .flatMap {
                it.commonOptions.tools.filter { tool -> tool.enable }
            }
        return mcpServers
    }

    /**
     * P2-02: server-scoped tool refs (server identity kept, unlike the flat
     * [getAllAvailableTools] list). Used to build the namespaced tool entries.
     */
    fun getAllAvailableToolRefs(): List<McpToolRef> {
        val settings = settingsStore.settingsFlow.value
        return settings.mcpServers
            .filter {
                it.commonOptions.enable && it.id in settings.enabledMcpServerIds
            }
            .flatMap { server ->
                server.commonOptions.tools
                    .filter { tool -> tool.enable }
                    .map { tool ->
                        McpToolRef(
                            serverId = server.id.toString(),
                            serverName = server.commonOptions.name,
                            toolName = tool.name,
                            description = tool.description,
                            inputSchema = tool.inputSchema,
                            needsApproval = tool.needsApproval,
                        )
                    }
            }
    }

    /**
     * P2-02: route one namespaced tool call to its concrete server/tool by
     * original identity — the direct envelope (`mcp_call_tool`) keeps routing
     * on original server/tool names and never sees the expanded name.
     */
    suspend fun callToolByRef(ref: McpToolRef, args: JsonObject): List<UIMessagePart> {
        val settings = settingsStore.settingsFlow.value
        val server = settings.mcpServers.firstOrNull {
            it.id.toString() == ref.serverId && it.commonOptions.enable
        } ?: return listOf(UIMessagePart.Text(
            buildJsonObject {
                put("status", "mcp_tool_not_found")
                put("message", "MCP server is no longer available: ${ref.serverName}")
                put("recoverable", JsonPrimitive(true))
            }.toString()
        ))
        val tool = server.commonOptions.tools.firstOrNull {
            it.name == ref.toolName && it.enable
        } ?: return listOf(UIMessagePart.Text(
            buildJsonObject {
                put("status", "mcp_tool_not_found")
                put("message", "MCP tool is no longer enabled: ${ref.serverName}/${ref.toolName}")
                put("recoverable", JsonPrimitive(true))
            }.toString()
        ))
        return callServerTool(server, tool, args)
    }

    /**
     * P2-02: legacy alias entry point for old conversations calling
     * `mcp__<tool>`. Unique match routes through the alias; multiple matches
     * are rejected with a structured error so the model re-selects.
     */
    suspend fun callToolLegacy(name: String, args: JsonObject): List<UIMessagePart> {
        val refs = getAllAvailableToolRefs()
        return when (val resolution = McpToolNamespace.resolve(name, refs)) {
            is McpToolNameResolution.Unique -> callToolByRef(resolution.ref, args)
            is McpToolNameResolution.Ambiguous -> legacyAmbiguityError(
                toolName = name.removePrefix(McpToolNamespace.PREFIX),
                matches = resolution.refs,
            )
            McpToolNameResolution.NotFound -> listOf(UIMessagePart.Text(
                buildJsonObject {
                    put("status", "mcp_tool_not_found")
                    put("message", "No MCP tool matches '$name'. Use mcp_list to inspect available MCP tools.")
                    put("recoverable", JsonPrimitive(true))
                }.toString()
            ))
        }
    }

    /**
     * Call an MCP tool by its bare tool name (legacy API, e.g. board signal
     * collectors that pick a tool by name): resolves to the single server
     * exposing it via the P2-02 legacy alias rules.
     */
    suspend fun callTool(toolName: String, args: JsonObject): List<UIMessagePart> =
        callToolLegacy(McpToolNamespace.PREFIX + toolName, args)

    private suspend fun callServerTool(
        server: McpServerConfig,
        tool: McpTool,
        args: JsonObject,
    ): List<UIMessagePart> {
        // OAuth：连接前确保令牌新鲜；令牌被刷新后按连接参数差异重建客户端
        val freshConfig = oauthCoordinator.ensureFreshToken(server)
        var client = getClient(freshConfig)
        val liveConfig = clients.keys.firstOrNull { it.id == freshConfig.id }
        if (liveConfig == null || !hasSameConnectionParameters(liveConfig, freshConfig)) {
            addClient(freshConfig)
            client = getClient(freshConfig)
        }
        val liveClient = client ?: return listOf(UIMessagePart.Text(
            buildJsonObject {
                put("status", "mcp_connection_failed")
                put("message", "MCP client is not connected: ${server.commonOptions.name}")
                put("recoverable", JsonPrimitive(true))
            }.toString()
        ))
        val effectiveConfig = clients.keys.firstOrNull { it.id == freshConfig.id } ?: freshConfig
        if (liveClient.transport == null) liveClient.connect(getTransport(effectiveConfig))
        Log.i(TAG, "callServerTool: ${server.commonOptions.name}/${tool.name} keys=${args.keys}")
        val result = try {
            liveClient.callTool(
                request = CallToolRequest(
                    params = CallToolRequestParams(
                        name = tool.name,
                        arguments = args,
                    ),
                ),
                options = RequestOptions(timeout = 120.seconds),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (oauthCoordinator.needsAuthorization(effectiveConfig, e)) {
                setStatus(effectiveConfig, McpStatus.NeedsAuthorization)
            }
            throw e
        }
        return result.content.map {
            when (it) {
                is TextContent -> UIMessagePart.Text(it.text)
                is ImageContent -> convertImageContentToFilePart(it)
                else -> UIMessagePart.Text(JsonInstant.encodeToString(it))
            }
        }
    }

    suspend fun callConfiguredTool(
        serverId: String?,
        serverName: String?,
        toolName: String,
        args: JsonObject,
    ): List<UIMessagePart> = withContext(Dispatchers.IO) {
        require(toolName.isNotBlank()) { "tool_name is required" }
        val settings = settingsStore.settingsFlow.value
        val server = settings.mcpServers.firstOrNull { config ->
            config.commonOptions.enable &&
                (serverId.isNullOrBlank() || config.id.toString() == serverId) &&
                (serverName.isNullOrBlank() || config.commonOptions.name == serverName) &&
                config.commonOptions.tools.any { it.name == toolName }
        } ?: error("MCP tool not found in enabled servers: $toolName")
        val tool = server.commonOptions.tools.firstOrNull { it.name == toolName }
            ?: error("MCP tool not found on ${server.commonOptions.name}: $toolName")
        require(tool.enable) { "MCP tool is disabled: ${server.commonOptions.name}/$toolName" }
        callServerTool(server, tool, args)
    }

    private suspend fun convertImageContentToFilePart(image: ImageContent): UIMessagePart.Image {
        val bytes = Base64.decode(image.data)
        val ext = android.webkit.MimeTypeMap.getSingleton()
            .getExtensionFromMimeType(image.mimeType) ?: "bin"
        val entity = filesManager.saveUploadFromBytes(
            bytes = bytes,
            displayName = "mcp_image.$ext",
            mimeType = image.mimeType,
        )
        val uri = filesManager.getFile(entity).toUri()
        Log.i(TAG, "convertImageContentToFilePart: saved mcp image to $uri")
        return UIMessagePart.Image(url = uri.toString())
    }

    private fun getTransport(config: McpServerConfig): AbstractTransport {
        val resolvedHeaders = config.resolvedHeaders()
        return when (config) {
            is McpServerConfig.SseTransportServer -> {
                SseClientTransport(
                    urlString = config.url,
                    client = client,
                    requestBuilder = {
                        headers.appendAll(StringValues.build {
                            resolvedHeaders.forEach {
                                append(it.first, it.second)
                            }
                        })
                    },
                )
            }

            is McpServerConfig.StreamableHTTPServer -> {
                StreamableHttpClientTransport(
                    url = config.url,
                    client = client,
                    requestBuilder = {
                        headers.appendAll(StringValues.build {
                            resolvedHeaders.forEach {
                                append(it.first, it.second)
                            }
                        })
                    }
                )
            }
        }
    }

    suspend fun addClient(config: McpServerConfig) = withContext(Dispatchers.IO) {
        removeClient(config) // Remove first
        cancelReconnect(config.id)
        reconnectAttempts[config.id] = 0

        // OAuth：连接前确保令牌新鲜（刷新成功会持久化并更新配置）
        val freshConfig = oauthCoordinator.ensureFreshToken(config)
        val transport = getTransport(freshConfig)
        val client = Client(
            clientInfo = Implementation(
                name = freshConfig.commonOptions.name,
                version = "1.0",
            )
        )

        // 注册 transport 回调以支持自动重连
        transport.onClose {
            Log.i(TAG, "Transport closed for ${freshConfig.commonOptions.name}")
            val currentStatus = syncingStatus.value[freshConfig.id]
            // 只有在已连接状态下才触发重连，避免正常关闭时重连
            if (currentStatus == McpStatus.Connected) {
                scheduleReconnect(freshConfig)
            }
        }

        transport.onError { error ->
            Log.e(TAG, "Transport error for ${freshConfig.commonOptions.name}: ${error.message}")
            val currentStatus = syncingStatus.value[freshConfig.id]
            // 只有在已连接状态下才触发重连
            if (currentStatus == McpStatus.Connected) {
                scheduleReconnect(freshConfig)
            }
        }

        clients[freshConfig] = client
        runCatching {
            setStatus(config = freshConfig, status = McpStatus.Connecting)
            client.connect(transport)
            sync(freshConfig)
            setStatus(config = freshConfig, status = McpStatus.Connected)
            reconnectAttempts[freshConfig.id] = 0 // 重置重连计数
            Log.i(TAG, "addClient: connected ${freshConfig.commonOptions.name}")
        }.onFailure {
            it.printStackTrace()
            if (oauthCoordinator.needsAuthorization(freshConfig, it)) {
                setStatus(config = freshConfig, status = McpStatus.NeedsAuthorization)
            } else {
                setStatus(config = freshConfig, status = McpStatus.Error(it.message ?: it.javaClass.name))
            }
        }
    }

    private suspend fun sync(config: McpServerConfig) {
        val client = clients[config] ?: return

        setStatus(config = config, status = McpStatus.Connecting)

        // Update tools
        if (client.transport == null) {
            client.connect(getTransport(config))
        }
        val serverTools = client.listTools().tools
        Log.i(TAG, "sync: tools: $serverTools")
        settingsStore.update { old ->
            old.copy(
                mcpServers = old.mcpServers.map { serverConfig ->
                    if (serverConfig.id != config.id) return@map serverConfig
                    val common = serverConfig.commonOptions
                    val tools = common.tools.toMutableList()

                    // 基于server对比
                    serverTools.forEach { serverTool ->
                        val tool = tools.find { it.name == serverTool.name }
                        if (tool == null) {
                            tools.add(
                                McpTool(
                                    name = serverTool.name,
                                    description = serverTool.description,
                                    enable = true,
                                    inputSchema = serverTool.inputSchema.toSchema()
                                )
                            )
                        } else {
                            val index = tools.indexOf(tool)
                            tools[index] = tool.copy(
                                description = serverTool.description,
                                inputSchema = serverTool.inputSchema.toSchema()
                            )
                        }
                    }

                    // 删除不在server内的
                    tools.removeIf { tool -> serverTools.none { it.name == tool.name } }

                    // 更新clients
                    clients.remove(config)
                    clients.put(
                        config.clone(
                            commonOptions = common.copy(
                                tools = tools
                            )
                        ), client
                    )

                    // 返回新的serverConfig，更新到settings store
                    serverConfig.clone(
                        commonOptions = common.copy(
                            tools = tools
                        )
                    )
                }
            )
        }

        setStatus(config = config, status = McpStatus.Connected)
    }

    suspend fun syncAll() = withContext(Dispatchers.IO) {
        clients.keys.toList().forEach { config ->
            runCatching {
                sync(config)
            }.onFailure {
                it.printStackTrace()
                if (oauthCoordinator.needsAuthorization(config, it)) {
                    setStatus(config = config, status = McpStatus.NeedsAuthorization)
                }
            }
        }
    }

    suspend fun removeClient(config: McpServerConfig) = withContext(Dispatchers.IO) {
        cancelReconnect(config.id)
        val toRemove = clients.entries.filter { it.key.id == config.id }
        toRemove.forEach { entry ->
            runCatching {
                entry.value.close()
            }.onFailure {
                it.printStackTrace()
            }
            clients.remove(entry.key)
            syncingStatus.emit(syncingStatus.value.toMutableMap().apply { remove(entry.key.id) })
            Log.i(TAG, "removeClient: ${entry.key} / ${entry.key.commonOptions.name}")
        }
        reconnectAttempts.remove(config.id)
    }

    private fun scheduleReconnect(config: McpServerConfig) {
        val configId = config.id
        val currentAttempt = (reconnectAttempts[configId] ?: 0) + 1

        if (currentAttempt > MAX_RECONNECT_ATTEMPTS) {
            Log.w(TAG, "Max reconnect attempts reached for ${config.commonOptions.name}")
            appScope.launch {
                setStatus(
                    config,
                    McpStatus.Error(context.getString(R.string.setting_mcp_status_error)),
                )
            }
            return
        }

        reconnectAttempts[configId] = currentAttempt

        // 取消之前的重连任务
        reconnectJobs[configId]?.cancel()

        // 计算指数退避延迟
        val delayMs = calculateBackoffDelay(currentAttempt)
        Log.i(TAG, "Scheduling reconnect for ${config.commonOptions.name}, attempt $currentAttempt/$MAX_RECONNECT_ATTEMPTS, delay ${delayMs}ms")

        reconnectJobs[configId] = appScope.launch {
            try {
                setStatus(config, McpStatus.Reconnecting(currentAttempt, MAX_RECONNECT_ATTEMPTS))
                delay(delayMs)

                // 检查配置是否仍然启用
                val currentConfig = settingsStore.settingsFlow.value.mcpServers
                    .find { it.id == configId && it.commonOptions.enable }

                if (currentConfig == null) {
                    Log.i(TAG, "Config disabled or removed, cancelling reconnect for ${config.commonOptions.name}")
                    return@launch
                }

                Log.i(TAG, "Attempting reconnect for ${config.commonOptions.name}")
                reconnectClient(currentConfig)
            } catch (e: CancellationException) {
                Log.i(TAG, "Reconnect cancelled for ${config.commonOptions.name}")
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Reconnect failed for ${config.commonOptions.name}", e)
                // 继续尝试重连
                scheduleReconnect(config)
            }
        }
    }

    private fun cancelReconnect(configId: Uuid) {
        reconnectJobs[configId]?.cancel()
        reconnectJobs.remove(configId)
    }

    private fun calculateBackoffDelay(attempt: Int): Long {
        // 指数退避: baseDelay * 2^(attempt-1)，最大不超过 maxDelay
        val exponentialDelay = BASE_RECONNECT_DELAY_MS * (1L shl (attempt - 1).coerceAtMost(10))
        return exponentialDelay.coerceAtMost(MAX_RECONNECT_DELAY_MS)
    }

    private suspend fun reconnectClient(config: McpServerConfig) = withContext(Dispatchers.IO) {
        // 先关闭旧客户端
        val oldEntry = clients.entries.find { it.key.id == config.id }
        if (oldEntry != null) {
            runCatching { oldEntry.value.close() }.onFailure { it.printStackTrace() }
            clients.remove(oldEntry.key)
        }

        // OAuth：重连前确保令牌新鲜
        val freshConfig = oauthCoordinator.ensureFreshToken(config)
        val transport = getTransport(freshConfig)
        val client = Client(
            clientInfo = Implementation(
                name = freshConfig.commonOptions.name,
                version = "1.0",
            )
        )

        // 注册回调
        transport.onClose {
            Log.i(TAG, "Transport closed for ${freshConfig.commonOptions.name}")
            val currentStatus = syncingStatus.value[freshConfig.id]
            if (currentStatus == McpStatus.Connected) {
                scheduleReconnect(freshConfig)
            }
        }

        transport.onError { error ->
            Log.e(TAG, "Transport error for ${freshConfig.commonOptions.name}: ${error.message}")
            val currentStatus = syncingStatus.value[freshConfig.id]
            if (currentStatus == McpStatus.Connected) {
                scheduleReconnect(freshConfig)
            }
        }

        clients[freshConfig] = client
        runCatching {
            setStatus(freshConfig, McpStatus.Connecting)
            client.connect(transport)
            sync(freshConfig)
            setStatus(freshConfig, McpStatus.Connected)
            reconnectAttempts[freshConfig.id] = 0 // 重置重连计数
            Log.i(TAG, "Reconnected successfully: ${freshConfig.commonOptions.name}")
        }.onFailure {
            it.printStackTrace()
            if (oauthCoordinator.needsAuthorization(freshConfig, it)) {
                setStatus(freshConfig, McpStatus.NeedsAuthorization)
            } else {
                setStatus(freshConfig, McpStatus.Error(it.message ?: it.javaClass.name))
            }
        }
    }

    private suspend fun setStatus(config: McpServerConfig, status: McpStatus) {
        syncingStatus.emit(syncingStatus.value.toMutableMap().apply {
            put(config.id, status)
        })
    }

    fun getStatus(config: McpServerConfig): Flow<McpStatus> {
        return syncingStatus.map { it[config.id] ?: McpStatus.Idle }
    }

    // ---------------------------------------------------------------------
    // OAuth 授权入口
    // ---------------------------------------------------------------------

    fun startAuthorization(config: McpServerConfig, context: Context) {
        val job = oauthCoordinator.startAuthorization(config, context)
        job.invokeOnCompletion { cause ->
            if (cause == null) {
                // 授权成功后用带令牌的配置重建连接
                //（settings collector 按 id 去重，令牌变化不会自动触发重连）
                appScope.launch {
                    runCatching { addClient(config) }
                        .onFailure { it.printStackTrace() }
                }
            }
        }
    }

    fun cancelAuthorization(config: McpServerConfig) {
        oauthCoordinator.cancelAuthorization(config.id)
    }

    suspend fun clearAuthorization(config: McpServerConfig) {
        val freshConfig = oauthCoordinator.clearAuthorization(config)
        // 取消授权后用无令牌配置重建连接，让服务器 401 重新触发 NeedsAuthorization
        addClient(freshConfig)
    }
}

/** 只包含会影响实际连接的字段；工具开关和 Schema 变化不会触发重连。 */
private data class McpConnectionKey(
    val transportType: String,
    val serverUrl: String,
    val clientName: String,
    val headers: List<Pair<String, String>>,
)

private fun McpServerConfig.connectionKey(): McpConnectionKey = McpConnectionKey(
    transportType = when (this) {
        is McpServerConfig.SseTransportServer -> "sse"
        is McpServerConfig.StreamableHTTPServer -> "streamable_http"
    },
    serverUrl = serverUrl,
    clientName = commonOptions.name,
    headers = resolvedHeaders(),
)

private fun hasSameConnectionParameters(
    left: McpServerConfig?,
    right: McpServerConfig?,
): Boolean = left != null && right != null && left.connectionKey() == right.connectionKey()

/** 自定义 headers 基础上追加 OAuth Bearer 令牌（用户显式配置的 Authorization 优先）。 */
private fun McpServerConfig.resolvedHeaders(): List<Pair<String, String>> {
    val base = commonOptions.headers
    val token = commonOptions.oauth?.takeIf { it.enabled }?.accessToken
    val hasAuthorization = base.any { it.first.equals("Authorization", ignoreCase = true) }
    return if (!token.isNullOrBlank() && !hasAuthorization) {
        base + ("Authorization" to "Bearer $token")
    } else {
        base
    }
}

@OptIn(ExperimentalSerializationApi::class)
internal val McpJson: Json by lazy {
    Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
        classDiscriminatorMode = ClassDiscriminatorMode.NONE
        explicitNulls = false
    }
}

private fun ToolSchema.toSchema(): InputSchema {
    return InputSchema.Obj(properties = this.properties ?: JsonObject(emptyMap()), required = this.required)
}
