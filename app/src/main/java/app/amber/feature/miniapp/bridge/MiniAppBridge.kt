package app.amber.feature.miniapp.bridge

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import app.amber.feature.miniapp.MiniAppAiBridge
import app.amber.feature.miniapp.MiniAppBridgeRequest
import app.amber.feature.miniapp.MiniAppBridgeResponse
import app.amber.feature.miniapp.MiniAppEventBus
import app.amber.feature.miniapp.MiniAppHttpClient
import app.amber.feature.miniapp.MiniAppLaunchLimiter
import app.amber.feature.miniapp.MiniAppPermission
import app.amber.feature.miniapp.MiniAppRepository
import app.amber.feature.miniapp.MiniAppSandbox
import app.amber.feature.miniapp.MiniAppSearchBridge
import app.amber.feature.miniapp.MiniAppStorage
import app.amber.feature.miniapp.MiniAppSystemBridge
import app.amber.feature.miniapp.MiniAppUserConfirmation
import app.amber.feature.miniapp.MiniAppValidationException
import app.amber.feature.miniapp.minimalHostContext
import app.amber.agent.data.db.entity.MiniAppEntity
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean

class MiniAppBridge(
    private val context: Context,
    private val webViewProvider: () -> WebView?,
    private val appId: String,
    private val sessionToken: String,
    private val appProvider: () -> MiniAppEntity,
    private val sandbox: MiniAppSandbox,
    private val repository: MiniAppRepository,
    private val storage: MiniAppStorage,
    private val httpClient: MiniAppHttpClient,
    private val searchBridge: MiniAppSearchBridge,
    private val aiBridge: MiniAppAiBridge,
    private val confirmation: MiniAppUserConfirmation,
    private val systemBridge: MiniAppSystemBridge,
    private val toast: (String) -> Unit,
    private val clipboardCopy: (String) -> Unit,
    private val updateBoardSummary: (String) -> Unit,
    private val launchApp: (String) -> Unit,
    private val themeProvider: () -> MiniAppTheme,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensorListeners = mutableMapOf<String, SensorEventListener>()
    private val eventPublishTimes = ArrayDeque<Long>()
    private val eventSubscriptionIds = mutableSetOf<String>()
    private val closed = AtomicBoolean(false)

    @JavascriptInterface
    fun postMessage(raw: String) {
        if (closed.get()) return
        val response = runCatching {
            val request = json.decodeFromString<MiniAppBridgeRequest>(raw)
            if (request.token != sessionToken) {
                throw SecurityException("Invalid MiniApp session token")
            }
            MiniAppBridgeResponse(
                id = request.id,
                ok = true,
                data = handle(request.method, request.params)
            )
        }.getOrElse { error ->
            val id = runCatching { json.decodeFromString<MiniAppBridgeRequest>(raw).id }.getOrDefault(-1)
            MiniAppBridgeResponse(
                id = id,
                ok = false,
                error = when (error) {
                    is SerializationException -> "Invalid bridge request"
                    else -> error.message ?: error::class.java.simpleName
                }
            )
        }
        sendResponse(response)
    }

    private fun handle(method: String, params: JsonObject): JsonElement {
        return when (method) {
            "storage.get" -> {
                sandbox.require(MiniAppPermission.Storage)
                val key = params.string("key")
                val stored = storage.get(appId, key) ?: return JsonNull
                runCatching { json.parseToJsonElement(stored) }.getOrElse { JsonPrimitive(stored) }
            }

            "storage.set" -> {
                sandbox.require(MiniAppPermission.Storage)
                val key = params.string("key")
                val value = params["value"] ?: JsonNull
                storage.set(appId, key, json.encodeToString(JsonElement.serializer(), value))
                JsonPrimitive(true)
            }

            "storage.remove" -> {
                sandbox.require(MiniAppPermission.Storage)
                storage.remove(appId, params.string("key"))
                JsonPrimitive(true)
            }

            "toast" -> {
                sandbox.require(MiniAppPermission.Toast)
                val message = params.string("message").take(120)
                mainHandler.post { toast(message) }
                JsonPrimitive(true)
            }

            "host.getTheme" -> {
                sandbox.require(MiniAppPermission.Theme)
                themeProvider().toJson()
            }

            "fetch" -> {
                sandbox.require(MiniAppPermission.Network)
                audit(method, MiniAppPermission.Network, "MiniApp network request", params)
                runBlocking { httpClient.fetch(params) }
            }

            "search" -> {
                sandbox.require(MiniAppPermission.Search)
                audit(method, MiniAppPermission.Search, "MiniApp search request", params)
                runBlocking { searchBridge.search(params) }
            }

            "clipboard.copy" -> {
                sandbox.require(MiniAppPermission.ClipboardCopy)
                val text = params.string("text").take(20_000)
                mainHandler.post { clipboardCopy(text) }
                JsonPrimitive(true)
            }

            "host.updateBoardSummary" -> {
                sandbox.require(MiniAppPermission.BoardSummaryUpdate)
                val summary = params.string("summary").take(500)
                audit(method, MiniAppPermission.BoardSummaryUpdate, "Update board summary", JsonPrimitive(summary))
                updateBoardSummary(summary)
                JsonPrimitive(true)
            }

            "host.getConversationContext" -> {
                sandbox.require(MiniAppPermission.HostContext)
                val maxChars = (params["maxChars"]?.jsonPrimitive?.intOrNull ?: 4000).coerceIn(200, 4000)
                confirm("允许读取上下文？", "「${appProvider().title}」想读取最小化会话上下文。") {
                    audit(method, MiniAppPermission.HostContext, "host.context", params)
                    appProvider().minimalHostContext(maxChars)
                }
            }

            "host.sendToConversation" -> {
                sandbox.require(MiniAppPermission.HostSendToConversation)
                val text = params.string("text").take(8000)
                confirm("允许写回聊天？", text.take(300)) {
                    audit(method, MiniAppPermission.HostSendToConversation, "host.sendToConversation", JsonPrimitive(text))
                    buildJsonObject {
                        put("accepted", true)
                        put("mode", params.stringOrNull("mode") ?: "draft")
                        put("text", text)
                    }
                }
            }

            "host.createArtifact" -> {
                sandbox.require(MiniAppPermission.HostCreateArtifact)
                val title = params.string("title").take(80)
                val content = params.string("content").take(12_000)
                confirm("允许创建内容卡片？", "$title\n\n${content.take(260)}") {
                    audit(method, MiniAppPermission.HostCreateArtifact, "host.createArtifact", JsonPrimitive(content))
                    updateBoardSummary("$title\n${content.take(420)}")
                    buildJsonObject {
                        put("accepted", true)
                        put("title", title)
                        put("type", params.stringOrNull("type") ?: "note")
                    }
                }
            }

            "ai.generate" -> {
                sandbox.require(MiniAppPermission.AiGenerate)
                val prompt = params.string("prompt").take(8000)
                confirm("允许调用 Amber.ai？", prompt.take(360)) {
                    audit(method, MiniAppPermission.AiGenerate, "ai.generate", JsonPrimitive(prompt))
                    runBlocking { aiBridge.generate(appId, params) }
                }
            }

            "sharedStore.get" -> {
                sandbox.require(MiniAppPermission.SharedStore)
                val namespace = params.stringOrNull("namespace") ?: appId
                val key = params.string("key")
                audit(method, MiniAppPermission.SharedStore, "sharedStore.get", params)
                runBlocking { repository.sharedGet(appId, namespace, key) } ?: JsonNull
            }

            "sharedStore.set" -> {
                sandbox.require(MiniAppPermission.SharedStore)
                val namespace = params.stringOrNull("namespace") ?: appId
                val key = params.string("key")
                val value = params["value"] ?: JsonNull
                requirePayloadSize(value, 32 * 1024)
                runBlocking { repository.sharedSet(appId, namespace, key, value) }
                audit(method, MiniAppPermission.SharedStore, "sharedStore.set", JsonPrimitive("ok"))
                JsonPrimitive(true)
            }

            "sharedStore.remove" -> {
                sandbox.require(MiniAppPermission.SharedStore)
                val namespace = params.stringOrNull("namespace") ?: appId
                val key = params.string("key")
                runBlocking { repository.sharedRemove(appId, namespace, key) }
                audit(method, MiniAppPermission.SharedStore, "sharedStore.remove", params)
                JsonPrimitive(true)
            }

            "eventBus.subscribe" -> {
                sandbox.require(MiniAppPermission.EventBus)
                val namespace = ownNamespace(params.stringOrNull("namespace") ?: appId)
                val topic = safeTopic(params.string("topic"))
                audit(method, MiniAppPermission.EventBus, "eventBus.subscribe", params)
                lateinit var subscriptionId: String
                subscriptionId = MiniAppEventBus.subscribe(appId, namespace, topic) { payload ->
                    if (!closed.get()) {
                        emitBridgeEvent("eventBus", subscriptionId = subscriptionId, payload = payload)
                    }
                }
                eventSubscriptionIds.add(subscriptionId)
                buildJsonObject { put("subscriptionId", subscriptionId) }
            }

            "eventBus.unsubscribe" -> {
                sandbox.require(MiniAppPermission.EventBus)
                val subscriptionId = params.string("subscriptionId")
                eventSubscriptionIds.remove(subscriptionId)
                MiniAppEventBus.unsubscribe(subscriptionId)
                JsonPrimitive(true)
            }

            "eventBus.publish" -> {
                sandbox.require(MiniAppPermission.EventBus)
                val namespace = ownNamespace(params.stringOrNull("namespace") ?: appId)
                val topic = safeTopic(params.string("topic"))
                val payload = params["payload"] ?: JsonNull
                requirePayloadSize(payload, 16 * 1024)
                rateLimitEventPublish()
                audit(method, MiniAppPermission.EventBus, "eventBus.publish", JsonPrimitive("ok"))
                MiniAppEventBus.publish(namespace, topic, payload)
                JsonPrimitive(true)
            }

            "launch" -> {
                sandbox.require(MiniAppPermission.Launch)
                val targetAppId = params.string("appId")
                MiniAppLaunchLimiter.check()
                confirm("打开另一个小应用？", "「${appProvider().title}」想打开小应用 $targetAppId。") {
                    val target = runBlocking { repository.getById(targetAppId) }
                        ?: throw MiniAppValidationException("Target MiniApp does not exist")
                    audit(method, MiniAppPermission.Launch, "launch", JsonPrimitive(target.id))
                    mainHandler.post { launchApp(targetAppId) }
                    JsonPrimitive(true)
                }
            }

            "clipboard.read" -> {
                sandbox.require(MiniAppPermission.ClipboardRead)
                confirm("允许读取剪贴板？", "「${appProvider().title}」想读取当前剪贴板文本。") {
                    val text = systemBridge.readClipboard()
                    audit(method, MiniAppPermission.ClipboardRead, "clipboard.read", JsonPrimitive(text))
                    JsonPrimitive(text)
                }
            }

            "location.getCurrent" -> {
                sandbox.require(MiniAppPermission.Location)
                val accuracy = params.stringOrNull("accuracy")?.takeIf { it == "fine" } ?: "coarse"
                confirm("允许读取位置？", "「${appProvider().title}」想读取 $accuracy 位置。") {
                    val location = systemBridge.currentLocation(accuracy)
                    audit(method, MiniAppPermission.Location, "location.getCurrent", JsonPrimitive(accuracy))
                    location
                }
            }

            "sensor.subscribe" -> {
                sandbox.require(MiniAppPermission.Sensor)
                val type = params.string("type")
                val intervalMs = (params["intervalMs"]?.jsonPrimitive?.intOrNull ?: 500).coerceAtLeast(250)
                confirm("允许读取传感器？", "「${appProvider().title}」想订阅 $type 传感器。") {
                    audit(method, MiniAppPermission.Sensor, "sensor.subscribe", JsonPrimitive(type))
                    buildJsonObject { put("subscriptionId", subscribeSensor(type, intervalMs)) }
                }
            }

            "sensor.unsubscribe" -> {
                sandbox.require(MiniAppPermission.Sensor)
                unsubscribeSensor(params.string("subscriptionId"))
                JsonPrimitive(true)
            }

            else -> throw IllegalArgumentException("Unknown MiniApp bridge method: $method")
        }
    }

    private fun sendResponse(response: MiniAppBridgeResponse) {
        val payload = json.encodeToString(response)
        mainHandler.post {
            webViewProvider()?.evaluateJavascript(
                "window.AmberBridge && window.AmberBridge._handleNativeResponse($payload)",
                null
            )
        }
    }

    private fun JsonObject.string(key: String): String {
        return this[key]?.jsonPrimitive?.contentOrNull ?: throw IllegalArgumentException("Missing parameter: $key")
    }

    private fun JsonObject.stringOrNull(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

    private fun audit(method: String, permission: MiniAppPermission, summary: String, payload: JsonElement) {
        runBlocking {
            repository.audit(
                appId = appId,
                method = method,
                permission = permission,
                summary = summary,
                payload = json.encodeToString(JsonElement.serializer(), payload),
            )
        }
    }

    private fun confirm(title: String, message: String, block: () -> JsonElement): JsonElement {
        if (closed.get()) throw SecurityException("MiniApp runner is closed")
        val accepted = runBlocking { confirmation.confirm(title, message.take(420)) }
        if (closed.get()) throw SecurityException("MiniApp runner is closed")
        if (!accepted) throw SecurityException("User denied MiniApp request")
        return block()
    }

    private fun ownNamespace(namespace: String): String {
        if (namespace != appId) throw MiniAppValidationException("Cross-app namespace is not granted")
        return namespace
    }

    private fun safeTopic(topic: String): String {
        val normalized = topic.trim()
        if (normalized.length !in 1..64 || !Regex("""[a-zA-Z0-9._:-]+""").matches(normalized)) {
            throw MiniAppValidationException("Invalid topic")
        }
        return normalized
    }

    private fun requirePayloadSize(payload: JsonElement, maxBytes: Int) {
        val bytes = json.encodeToString(JsonElement.serializer(), payload).encodeToByteArray().size
        if (bytes > maxBytes) throw MiniAppValidationException("Payload is too large")
    }

    private fun rateLimitEventPublish() {
        val now = System.currentTimeMillis()
        while (eventPublishTimes.isNotEmpty() && now - eventPublishTimes.first > 10_000) {
            eventPublishTimes.removeFirst()
        }
        if (eventPublishTimes.size >= 30) throw MiniAppValidationException("EventBus publish rate limit exceeded")
        eventPublishTimes.addLast(now)
    }

    private fun emitBridgeEvent(type: String, subscriptionId: String?, payload: JsonElement) {
        if (closed.get()) return
        val event = buildJsonObject {
            put("type", type)
            subscriptionId?.let { put("subscriptionId", it) }
            put("payload", payload)
        }
        val encoded = json.encodeToString(JsonElement.serializer(), event)
        mainHandler.post {
            webViewProvider()?.evaluateJavascript(
                "window.AmberBridge && window.AmberBridge._emitNativeEvent($encoded)",
                null
            )
        }
    }

    private fun subscribeSensor(type: String, intervalMs: Int): String {
        val sensorType = when (type) {
            "accelerometer" -> Sensor.TYPE_ACCELEROMETER
            "gyroscope" -> Sensor.TYPE_GYROSCOPE
            "light" -> Sensor.TYPE_LIGHT
            else -> throw MiniAppValidationException("Unsupported sensor type")
        }
        val sensor = sensorManager.getDefaultSensor(sensorType)
            ?: throw MiniAppValidationException("Sensor is unavailable")
        val id = java.util.UUID.randomUUID().toString()
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (closed.get()) return
                emitBridgeEvent(
                    type = "sensor",
                    subscriptionId = id,
                    payload = buildJsonObject {
                        put("sensorType", type)
                        put("timestamp", event.timestamp)
                        put("x", event.values.getOrNull(0) ?: 0f)
                        put("y", event.values.getOrNull(1) ?: 0f)
                        put("z", event.values.getOrNull(2) ?: 0f)
                    },
                )
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        sensorListeners[id] = listener
        mainHandler.post {
            sensorManager.registerListener(listener, sensor, intervalMs * 1000)
        }
        return id
    }

    private fun unsubscribeSensor(id: String) {
        val listener = sensorListeners.remove(id) ?: return
        mainHandler.post { sensorManager.unregisterListener(listener) }
    }

    fun close() {
        closed.set(true)
        eventSubscriptionIds.toList().forEach {
            eventSubscriptionIds.remove(it)
            MiniAppEventBus.unsubscribe(it)
        }
        sensorListeners.keys.toList().forEach(::unsubscribeSensor)
    }
}

data class MiniAppTheme(
    val dark: Boolean,
    val background: String,
    val foreground: String,
    val primary: String,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("dark", dark)
        put("background", background)
        put("foreground", foreground)
        put("primary", primary)
    }
}
