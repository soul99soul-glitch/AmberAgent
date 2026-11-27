package app.amber.feature.macgateway

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException

internal typealias CloudEndpointRequest = suspend MacGatewayClient.(String, List<String>, Map<String, Any>?) -> String

internal class CloudEndpointDetailApi(
    repository: MacGatewayRepository,
    private val connection: MacGatewayConnection,
    private val generation: Long,
    private val transport: CloudEndpointRequest,
) {
    private val cloud = repository.slot(GatewayEndpointRole.CLOUD)

    suspend fun configuration(): CloudDetailConfiguration = read("v2", "configuration")

    suspend fun rules(): CloudDetailRules = read("v2", "rules")

    suspend fun updateRule(id: String, action: CloudRuleAction) {
        request("POST", listOf("v2", "rules", id, action.path))
    }

    suspend fun taskBoard(): CloudDetailTaskBoard = coroutineScope {
        val native = async { optional<CloudDetailTaskActivity>("v2", "agents", "main", "activity") }
        val gateway = async { optional<MacBoardResponse>("v1", "amber", "board")?.board }
        val activity = native.await()
        val board = gateway.await()
        if (activity == null && board == null) throw MacGatewayException.NotFound("task_board_unavailable")
        val workItems = activity?.workItems.orEmpty()
        val nativeIds = workItems.mapTo(HashSet()) { it.id }
        CloudDetailTaskBoard(
            tasks = workItems,
            gatewayItems = board.orEmpty().filterNot { it.id.removePrefix("run:") in nativeIds },
        )
    }

    suspend fun task(id: String): CloudDetailTask = read("v2", "work-items", id)

    suspend fun submitTask(requestId: String, text: String): CloudDetailWorkItem {
        val title = text.lineSequence().first().take(100).trimEnd { it.isHighSurrogate() }
        return decode<CloudDetailTaskReceipt>(
            request(
                "POST",
                listOf("v2", "work-items"),
                mapOf("requestId" to requestId, "text" to text, "title" to title),
            ),
        ).workItem
    }

    suspend fun cancelTask(id: String): CloudDetailTask =
        decode(request("POST", listOf("v2", "work-items", id, "cancel")))

    private suspend inline fun <reified T> optional(vararg path: String): T? = try {
        read(*path)
    } catch (_: MacGatewayException.NotFound) {
        null
    }

    private suspend inline fun <reified T> read(vararg path: String): T =
        decode(request("GET", path.toList()))

    private inline fun <reified T> decode(text: String): T = try {
        MacGatewayJson.decodeFromString<T>(text)
    } catch (_: SerializationException) {
        throw MacGatewayException.InvalidResponse()
    }

    private suspend fun request(method: String, path: List<String>, body: Map<String, Any>? = null): String =
        withClient { transport(it, method, path, body) }

    private suspend fun <T> withClient(call: suspend (MacGatewayClient) -> T): T {
        if (connection.role != GatewayEndpointRole.CLOUD) {
            throw MacGatewayException.Forbidden("cloud_detail_requires_cloud_pairing")
        }
        val client = cloud.clientForPairing(connection.gatewayId, connection.deviceId, generation, expectedConnection = connection)
            ?: throw MacGatewayException.Conflict("gateway_pairing_changed")
        val response = call(client)
        val current = cloud.state.value
        if (current.connection != connection || current.generation != generation) {
            throw MacGatewayException.Conflict("gateway_pairing_changed")
        }
        return response
    }
}

@Serializable
internal data class CloudDetailConfiguration(
    val model: Model? = null,
    val search: Search,
    val credentials: Credentials,
) {
    @Serializable
    data class Model(
        val provider: String,
        val modelId: String,
        val authMode: String = "apiKey",
        val siwcConfigured: Boolean = false,
    )

    @Serializable
    data class Search(val sources: List<String>, val mode: String)

    @Serializable
    data class Credentials(
        val modelApiKey: Credential,
        val exaApiKey: Credential,
        val braveApiKey: Credential,
    )

    @Serializable
    data class Credential(val configured: Boolean)
}

@Serializable
internal data class CloudDetailRules(val rules: List<CloudDetailRule>)

@Serializable
internal data class CloudDetailRule(val id: String, val title: String, val instruction: String, val status: String)

internal enum class CloudRuleAction(val path: String) {
    Confirm("confirm"),
    Archive("archive"),
    Revoke("revoke"),
}

internal data class CloudDetailTaskBoard(
    val tasks: List<CloudDetailWorkItem>,
    val gatewayItems: List<MacBoardItem>,
)

@Serializable
private data class CloudDetailTaskActivity(val workItems: List<CloudDetailWorkItem>)

@Serializable
internal data class CloudDetailWorkItem(
    val id: String,
    val title: String,
    val state: String,
    val progress: String? = null,
) {
    val canCancel: Boolean
        get() = state in setOf("QUEUED", "STARTING", "WORKING", "WAITING", "IDLE")
}

@Serializable
internal data class CloudDetailTask(
    val workItem: CloudDetailWorkItem,
    val brief: String,
    val failure: String? = null,
    val resultMarkdown: String? = null,
)

@Serializable
private data class CloudDetailTaskReceipt(val workItem: CloudDetailWorkItem)
