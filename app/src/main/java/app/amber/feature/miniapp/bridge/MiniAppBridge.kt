package app.amber.feature.miniapp.bridge

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
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
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import app.amber.agent.R
import app.amber.feature.miniapp.MiniAppAiBridge
import app.amber.feature.miniapp.MiniAppBridgeException
import app.amber.feature.miniapp.MiniAppBridgeRequest
import app.amber.feature.miniapp.MiniAppBridgeResponse
import app.amber.feature.miniapp.MiniAppConversationWriter
import app.amber.feature.miniapp.MiniAppEventBus
import app.amber.feature.miniapp.MiniAppGrantDecision
import app.amber.feature.miniapp.MiniAppHttpClient
import app.amber.feature.miniapp.MiniAppLaunchLimiter
import app.amber.feature.miniapp.MiniAppPermission
import app.amber.feature.miniapp.MiniAppRepository
import app.amber.feature.miniapp.MiniAppSandbox
import app.amber.feature.miniapp.MiniAppSearchBridge
import app.amber.feature.miniapp.MiniAppSendDecision
import app.amber.feature.miniapp.MiniAppSendGate
import app.amber.feature.miniapp.MiniAppStorage
import app.amber.feature.miniapp.MiniAppSystemBridge
import app.amber.feature.miniapp.MiniAppSystemCapabilityHandler
import app.amber.feature.miniapp.MiniAppSystemCapabilityRegistry
import app.amber.feature.miniapp.MiniAppOpenUrlValidator
import app.amber.feature.miniapp.MiniAppUserConfirmation
import app.amber.feature.miniapp.MiniAppValidationException
import app.amber.feature.miniapp.MiniAppWorkspaceWriter
import app.amber.feature.miniapp.minimalHostContext
import app.amber.agent.data.db.entity.MiniAppEntity
import app.amber.core.utils.appLocale
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
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
    private val updateBoardSummary: suspend (String) -> Unit,
    private val launchApp: (String) -> Unit,
    private val themeProvider: () -> MiniAppTheme,
    private val conversationWriter: MiniAppConversationWriter,
    private val workspaceWriter: MiniAppWorkspaceWriter,
    private val sendGate: MiniAppSendGate,
    private val systemCapabilityHandler: MiniAppSystemCapabilityHandler? = null,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    companion object {
        /** Regular (non-system) methods the bridge can always dispatch. */
        private val BRIDGE_METHODS = setOf(
            "storage.get", "storage.set", "storage.remove", "toast", "host.getTheme", "fetch",
            "search", "clipboard.copy", "host.updateBoardSummary", "host.getConversationContext",
            "host.sendToConversation", "host.createArtifact", "ai.generate", "sharedStore.get",
            "sharedStore.set", "sharedStore.remove", "eventBus.subscribe", "eventBus.unsubscribe",
            "eventBus.publish", "launch", "clipboard.read", "location.getCurrent",
            "sensor.subscribe", "sensor.unsubscribe",
        )
    }

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensorListeners = ConcurrentHashMap<String, SensorEventListener>()
    private val eventPublishTimes = ArrayDeque<Long>()
    private val eventSubscriptionIds = ConcurrentHashMap.newKeySet<String>()
    private val closed = AtomicBoolean(false)

    // Bridge requests run off the WebView JavaBridge thread so a pending user
    // confirmation or slow fetch can't stall every other bridge call. The JS
    // side matches responses by request id, so out-of-order completion is fine.
    // Parallelism is capped at 1: non-suspending sections execute one at a
    // time, keeping the listener maps and rate-limit deque race-free.
    private val bridgeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    @JavascriptInterface
    fun postMessage(raw: String) {
        val requestId = runCatching { json.decodeFromString<MiniAppBridgeRequest>(raw).id }.getOrNull()
        if (closed.get()) {
            // Late requests after close still get an honest runner_closed reply
            // instead of silently timing out on the JS side.
            requestId?.let {
                sendResponse(
                    MiniAppBridgeResponse(
                        id = it,
                        ok = false,
                        error = "MiniApp runner is closed",
                        errorCode = "runner_closed",
                    )
                )
            }
            return
        }
        bridgeScope.launch {
            val response = try {
                val request = json.decodeFromString<MiniAppBridgeRequest>(raw)
                if (request.token != sessionToken) {
                    throw SecurityException("Invalid MiniApp session token")
                }
                MiniAppBridgeResponse(
                    id = request.id,
                    ok = true,
                    data = handle(request.method, request.params),
                )
            } catch (error: Throwable) {
                if (error is kotlinx.coroutines.CancellationException) {
                    // close() cancels the bridge scope mid-request: surface a
                    // typed runner_closed reply instead of masquerading as a
                    // generic bridge error. A still-open coroutine only sees
                    // CancellationException from inner timeouts, which keep
                    // the generic mapping.
                    if (closed.get()) {
                        MiniAppBridgeResponse(
                            id = requestId ?: -1,
                            ok = false,
                            error = "MiniApp runner is closed",
                            errorCode = "runner_closed",
                        )
                    } else {
                        MiniAppBridgeResponse(
                            id = requestId ?: -1,
                            ok = false,
                            error = "Bridge request failed",
                            errorCode = "bridge_error",
                        )
                    }
                } else {
                    MiniAppBridgeResponse(
                        id = requestId ?: -1,
                        ok = false,
                        error = when (error) {
                            is SerializationException -> "Invalid bridge request"
                            is MiniAppBridgeException -> error.message ?: error::class.java.simpleName
                            else -> {
                                // Unknown failure: keep internal details out of the
                                // JS response; the exception only goes to the log.
                                Log.w("MiniAppBridge", "Bridge request failed", error)
                                "Bridge request failed"
                            }
                        },
                        // P3-03: stable structured codes — MiniApp-host failures,
                        // permission denials (sandbox.require) and user denials.
                        errorCode = when (error) {
                            is MiniAppBridgeException -> error.code
                            is SecurityException -> "permission_denied"
                            else -> "bridge_error"
                        },
                    )
                }
            }
            sendResponse(response)
        }
    }

    private suspend fun handle(method: String, params: JsonObject): JsonElement {
        if (method == "app.info") return appInfo()
        if (method == "app.capabilities") return capabilities()
        if (method in MiniAppSystemCapabilityRegistry.allSystemMethods) {
            return dispatchSystem(method, params)
        }
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
                httpClient.fetch(params)
            }

            "search" -> {
                sandbox.require(MiniAppPermission.Search)
                audit(method, MiniAppPermission.Search, "MiniApp search request", params)
                searchBridge.search(params, locale = context.appLocale())
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
                val maxChars = (params["maxChars"]?.jsonPrimitive?.intOrNull ?: 6000).coerceIn(200, 8000)
                val appTitle = appProvider().title
                confirm(
                    context.getString(R.string.miniapp_confirm_context_title),
                    context.getString(R.string.miniapp_confirm_context_message, appTitle),
                ) {
                    audit(method, MiniAppPermission.HostContext, "host.context", params)
                    appProvider().minimalHostContext(maxChars)
                }
            }

            "host.sendToConversation" -> {
                sandbox.require(MiniAppPermission.HostSendToConversation)
                val conversationId = params.string("conversationId").take(80)
                val text = params.string("text").take(8000)
                val mode = params.stringOrNull("mode") ?: "draft"
                val attachments = MiniAppConversationWriter.parseAttachments(params["attachments"])
                when (mode) {
                    "send" -> {
                        // P3-03: auto-send is a separate capability (miniapp.send,
                        // risk floor High). Only an explicit user authorization may
                        // let the MiniApp trigger a real send.
                        when (val decision = sendGate.decide()) {
                            is MiniAppSendDecision.Denied -> throw MiniAppBridgeException(decision.code, decision.message)
                            is MiniAppSendDecision.RequireConfirm ->
                                confirm(
                                    context.getString(R.string.miniapp_confirm_send_title),
                                    context.getString(
                                        R.string.miniapp_confirm_send_message,
                                        appProvider().title,
                                        conversationId.take(24),
                                    ),
                                ) { JsonNull }
                            MiniAppSendDecision.AllowAuto -> Unit
                        }
                        audit(
                            method,
                            MiniAppPermission.HostSendToConversation,
                            "host.sendToConversation (send)",
                            JsonPrimitive(text),
                        )
                        conversationWriter.writeAndSend(conversationId, text, attachments).toJson()
                    }

                    else -> confirm(
                        context.getString(R.string.miniapp_confirm_draft_title),
                        context.getString(
                            R.string.miniapp_confirm_draft_message,
                            appProvider().title,
                            text.take(300),
                        ),
                    ) {
                        audit(
                            method,
                            MiniAppPermission.HostSendToConversation,
                            "host.sendToConversation (draft)",
                            JsonPrimitive(text),
                        )
                        conversationWriter.writeDraft(conversationId, text, attachments).toJson()
                    }
                }
            }

            "host.createArtifact" -> {
                sandbox.require(MiniAppPermission.HostCreateArtifact)
                val title = params.string("title").take(80)
                val content = params.string("content").take(12_000)
                val effectId = params.stringOrNull("effectId")?.take(120)?.ifBlank { null }
                val type = params.stringOrNull("type")?.take(40)?.ifBlank { null } ?: "note"
                val mimeType = params.stringOrNull("mimeType")?.take(40)?.ifBlank { null } ?: "text/plain"
                confirm(
                    context.getString(R.string.miniapp_confirm_artifact_title),
                    context.getString(
                        R.string.miniapp_confirm_artifact_message,
                        appProvider().title,
                        title,
                        content.take(260),
                    ),
                ) {
                    audit(method, MiniAppPermission.HostCreateArtifact, "host.createArtifact", JsonPrimitive(content))
                    // P3-04: board summary is only a UI projection — the registry
                    // row written below is the persisted source of truth.
                    updateBoardSummary("$title\n${content.take(420)}")
                    workspaceWriter
                        .createArtifact(appId, effectId, title, content, type, mimeType)
                        .toJson()
                }
            }

            "ai.generate" -> {
                sandbox.require(MiniAppPermission.AiGenerate)
                val prompt = params.string("prompt").take(8000)
                confirm(
                    context.getString(R.string.miniapp_confirm_ai_title),
                    context.getString(
                        R.string.miniapp_confirm_ai_message,
                        appProvider().title,
                        prompt.take(360),
                    ),
                ) {
                    audit(method, MiniAppPermission.AiGenerate, "ai.generate", JsonPrimitive(prompt))
                    aiBridge.generate(appId, params)
                }
            }

            "sharedStore.get" -> {
                sandbox.require(MiniAppPermission.SharedStore)
                val namespace = params.stringOrNull("namespace") ?: appId
                val key = params.string("key")
                audit(method, MiniAppPermission.SharedStore, "sharedStore.get", params)
                repository.sharedGet(appId, namespace, key) ?: JsonNull
            }

            "sharedStore.set" -> {
                sandbox.require(MiniAppPermission.SharedStore)
                val namespace = params.stringOrNull("namespace") ?: appId
                val key = params.string("key")
                val value = params["value"] ?: JsonNull
                requirePayloadSize(value, 32 * 1024)
                repository.sharedSet(appId, namespace, key, value)
                audit(method, MiniAppPermission.SharedStore, "sharedStore.set", JsonPrimitive("ok"))
                JsonPrimitive(true)
            }

            "sharedStore.remove" -> {
                sandbox.require(MiniAppPermission.SharedStore)
                val namespace = params.stringOrNull("namespace") ?: appId
                val key = params.string("key")
                repository.sharedRemove(appId, namespace, key)
                audit(method, MiniAppPermission.SharedStore, "sharedStore.remove", params)
                JsonPrimitive(true)
            }

            "eventBus.subscribe" -> {
                sandbox.require(MiniAppPermission.EventBus)
                if (closed.get()) throw MiniAppBridgeException("runner_closed", "MiniApp runner is closed")
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
                if (closed.get() && eventSubscriptionIds.remove(subscriptionId)) {
                    MiniAppEventBus.unsubscribe(subscriptionId)
                    throw MiniAppBridgeException("runner_closed", "MiniApp runner is closed")
                }
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
                confirm(
                    context.getString(R.string.miniapp_confirm_launch_title),
                    context.getString(
                        R.string.miniapp_confirm_launch_message,
                        appProvider().title,
                        targetAppId,
                    ),
                ) {
                    val target = repository.getById(targetAppId)
                        ?: throw MiniAppValidationException("Target MiniApp does not exist")
                    audit(method, MiniAppPermission.Launch, "launch", JsonPrimitive(target.id))
                    mainHandler.post { launchApp(targetAppId) }
                    JsonPrimitive(true)
                }
            }

            "clipboard.read" -> {
                sandbox.require(MiniAppPermission.ClipboardRead)
                confirm(
                    context.getString(R.string.miniapp_confirm_clipboard_title),
                    context.getString(
                        R.string.miniapp_confirm_clipboard_message,
                        appProvider().title,
                    ),
                ) {
                    val text = systemBridge.readClipboard()
                    audit(method, MiniAppPermission.ClipboardRead, "clipboard.read", JsonPrimitive(text))
                    JsonPrimitive(text)
                }
            }

            "location.getCurrent" -> {
                sandbox.require(MiniAppPermission.Location)
                val accuracy = params.stringOrNull("accuracy")?.takeIf { it == "fine" } ?: "coarse"
                confirm(
                    context.getString(R.string.miniapp_confirm_location_title),
                    context.getString(
                        R.string.miniapp_confirm_location_message,
                        appProvider().title,
                    ),
                ) {
                    val location = systemBridge.currentLocation(accuracy)
                    audit(method, MiniAppPermission.Location, "location.getCurrent", JsonPrimitive(accuracy))
                    location
                }
            }

            "sensor.subscribe" -> {
                sandbox.require(MiniAppPermission.Sensor)
                val type = params.string("type")
                val intervalMs = (params["intervalMs"]?.jsonPrimitive?.intOrNull ?: 500).coerceAtLeast(250)
                confirm(
                    context.getString(R.string.miniapp_confirm_sensor_title),
                    context.getString(
                        R.string.miniapp_confirm_sensor_message,
                        appProvider().title,
                        type,
                    ),
                ) {
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

    /**
     * P4 W10: app.info mirrors the iOS payload (platform/bridgeVersion/appId/
     * title/version/runCount/permissions/grants). The bridge version only
     * reports 0.3-system-capabilities when the runner's native owner really
     * supports every system method.
     */
    private suspend fun appInfo(): JsonElement {
        // Durable repository state only — a deleted MiniApp reports Unknown
        // instead of resurrecting the runner's captured snapshot (iOS parity).
        val durableApp = repository.getById(appId)
        val grants = repository.grants(appId).map { grant ->
            buildJsonObject {
                put("permission", grant.permission)
                put("decision", grant.decision)
                put("updatedAt", grant.updatedAt)
            }
        }
        return buildJsonObject {
            put("platform", "android")
            put("bridgeVersion", MiniAppSystemCapabilityRegistry.bridgeVersion(systemCapabilityHandler))
            put("appId", appId)
            put("title", durableApp?.title ?: "Unknown MiniApp")
            put("version", durableApp?.version ?: 0)
            put("runCount", durableApp?.runCount ?: 0)
            put("permissions", json.encodeToJsonElement(durableApp?.declaredPermissionList() ?: emptyList()))
            put("grants", json.encodeToJsonElement(grants))
        }
    }

    /**
     * P4 W10: capability discovery. Read-only: it must never trigger a
     * permission confirmation. `methods` is the fixed system registry ∩ the
     * handler's supported set, plus the regular bridge methods.
     */
    private suspend fun capabilities(): JsonElement {
        val durableApp = repository.getById(appId)
        val declared = durableApp?.declaredPermissionList()?.toSet() ?: emptySet()
        val systemEnabled = sandbox.systemCapabilitiesEnabled()
        val methods = buildSet {
            add("app.info")
            add("app.capabilities")
            addAll(BRIDGE_METHODS)
            addAll(MiniAppSystemCapabilityRegistry.availableMethods(systemCapabilityHandler))
        }
        val permissions = MiniAppPermission.entries.map { permission ->
            buildJsonObject {
                put("permission", permission.value)
                put("declared", permission.value in declared)
                put("enabled", sandbox.isGloballyEnabled(permission))
                repository.grantDecision(appId, permission.value)?.let { decision ->
                    put("decision", decision.name)
                } ?: put("decision", JsonNull)
            }
        }
        return buildJsonObject {
            put("platform", "android")
            put("bridgeVersion", MiniAppSystemCapabilityRegistry.bridgeVersion(systemCapabilityHandler))
            put("systemCapabilitiesEnabled", systemEnabled)
            put("methods", json.encodeToJsonElement(methods.sorted()))
            put("permissions", json.encodeToJsonElement(permissions))
        }
    }

    /**
     * P4 W10/W11: system capability dispatch. Order mirrors iOS dispatchSystem:
     * handler presence → global system toggle → per-method permission (declare
     * → setting → grant, with confirmation and durable re-read) → openURL
     * per-call confirmation → audit (action label only) → native dispatch.
     */
    private suspend fun dispatchSystem(method: String, params: JsonObject): JsonElement {
        val handler = systemCapabilityHandler
            ?: throw MiniAppBridgeException("system_unavailable", "System capabilities are not available in this runner.")
        if (method !in handler.supportedMethods) {
            throw MiniAppBridgeException("method_unsupported", "System method is not supported on this runner: $method")
        }
        if (!sandbox.systemCapabilitiesEnabled()) {
            throw SecurityException("System capabilities are disabled in MiniApp settings")
        }
        // Validate URL params before any permission dialog: an invalid request
        // must never prompt the user nor persist a durable grant.
        when (method) {
            "openURL" -> MiniAppOpenUrlValidator.validate(
                params.stringOrNull("url")
                    ?: throw MiniAppBridgeException("invalid_url", "Missing parameter: url"),
            )
            "share" -> params.stringOrNull("url")?.takeIf { it.isNotEmpty() }?.let {
                MiniAppOpenUrlValidator.validate(it)
            }
        }
        val permission = MiniAppSystemCapabilityRegistry.methodPermissions[method]
        if (permission != null) {
            val authorizedApp = requireSystemPermission(method, permission)
            if (method == "openURL") {
                val url = MiniAppOpenUrlValidator.validate(params.string("url"))
                confirm(
                    "允许打开外部链接？",
                    "「${appProvider().title}」想打开：\n${externalUrlPreview(url.url)}",
                ) { JsonNull }
                requireUnchangedSystemApp(authorizedApp, permission)
                if (repository.grantDecision(appId, permission.value) != MiniAppGrantDecision.ALLOW) {
                    throw SecurityException("Permission denied: ${permission.value}")
                }
            }
            audit(method, permission, method, buildJsonObject { put("method", method) })
        }
        if (closed.get()) throw MiniAppBridgeException("runner_closed", "MiniApp runner is closed")
        val result = handler.dispatch(method, params)
        if (closed.get()) throw MiniAppBridgeException("runner_closed", "MiniApp runner is closed")
        return result
    }

    /**
     * New system permissions need a real durable decision; the legacy
     * null-grant-means-allowed rule must not silently admit them. After the
     * user confirms, re-read the durable app and reject the write when the
     * app/version/htmlHash/declaration/setting changed while the dialog was
     * open. The runner's appProvider closure is never used as version proof.
     */
    private suspend fun requireSystemPermission(method: String, permission: MiniAppPermission): MiniAppEntity {
        val appAtRequest = repository.getById(appId)
            ?: throw MiniAppBridgeException("miniapp_not_found", "MiniApp not found: $appId")
        if (permission.value !in appAtRequest.declaredPermissionList()) {
            throw SecurityException("Permission denied for $appId: ${permission.value}")
        }
        if (!sandbox.isGloballyEnabled(permission)) {
            throw SecurityException("Permission disabled: ${permission.value}")
        }
        when (repository.grantDecision(appId, permission.value)) {
            MiniAppGrantDecision.DENY -> throw SecurityException("Permission denied: ${permission.value}")
            MiniAppGrantDecision.ALLOW -> return appAtRequest
            null -> Unit
        }
        val allowed = confirmation.confirm(
            "允许使用系统能力？",
            "「${appAtRequest.title}」想使用 ${permission.value} 系统能力（$method）。",
        )
        if (closed.get()) throw MiniAppBridgeException("runner_closed", "MiniApp runner is closed")
        val currentApp = requireUnchangedSystemApp(appAtRequest, permission)
        if (repository.grantDecision(appId, permission.value) == MiniAppGrantDecision.DENY) {
            throw SecurityException("Permission denied: ${permission.value}")
        }
        repository.setGrant(appId, permission.value, if (allowed) MiniAppGrantDecision.ALLOW else MiniAppGrantDecision.DENY)
        if (!allowed) {
            throw MiniAppBridgeException("user_denied", "User denied MiniApp request")
        }
        return currentApp
    }

    private suspend fun requireUnchangedSystemApp(
        appAtRequest: MiniAppEntity,
        permission: MiniAppPermission,
    ): MiniAppEntity {
        val currentApp = repository.getById(appId)
        if (currentApp == null ||
            currentApp.version != appAtRequest.version ||
            currentApp.htmlHash != appAtRequest.htmlHash ||
            currentApp.declaredPermissionList().toSet() != appAtRequest.declaredPermissionList().toSet() ||
            !sandbox.isGloballyEnabled(permission)
        ) {
            throw MiniAppBridgeException("miniapp_changed", "MiniApp changed while confirmation was open.")
        }
        return currentApp
    }

    private fun externalUrlPreview(url: String): String {
        if (url.length <= 300) return url
        val schemeEnd = url.indexOf("://")
        val hostStart = if (schemeEnd > 0) schemeEnd + 3 else 0
        val hostEnd = url.indexOf('/', hostStart).takeIf { it > 0 } ?: url.length
        val host = url.substring(hostStart, hostEnd)
        val displayedHost = if (host.length > 200) host.take(100) + "…" + host.takeLast(100) else host
        val tail = url.substring(hostEnd, minOf(url.length, hostEnd + 80))
        return "${url.substringBefore("://")}://$displayedHost$tail…\n\n链接较长，已省略部分内容。"
    }

    private fun MiniAppEntity.declaredPermissionList(): List<String> =
        runCatching { json.decodeFromString<List<String>>(permissionsJson) }.getOrDefault(emptyList())

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

    private suspend fun audit(method: String, permission: MiniAppPermission, summary: String, payload: JsonElement) {
        repository.audit(
            appId = appId,
            method = method,
            permission = permission,
            summary = summary,
            payload = json.encodeToString(JsonElement.serializer(), payload),
        )
    }

    private suspend fun confirm(title: String, message: String, block: suspend () -> JsonElement): JsonElement {
        if (closed.get()) throw MiniAppBridgeException("runner_closed", "MiniApp runner is closed")
        val accepted = confirmation.confirm(title, message.take(420))
        if (closed.get()) throw MiniAppBridgeException("runner_closed", "MiniApp runner is closed")
        if (!accepted) throw MiniAppBridgeException("user_denied", "User denied MiniApp request")
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
        val sensorType = when (type.trim().replace('_', '-').lowercase()) {
            "accelerometer" -> Sensor.TYPE_ACCELEROMETER
            "accel" -> Sensor.TYPE_ACCELEROMETER
            "gyroscope" -> Sensor.TYPE_GYROSCOPE
            "gyro" -> Sensor.TYPE_GYROSCOPE
            "light" -> Sensor.TYPE_LIGHT
            "ambientlight" -> Sensor.TYPE_LIGHT
            "ambient-light" -> Sensor.TYPE_LIGHT
            "illuminance" -> Sensor.TYPE_LIGHT
            else -> throw MiniAppValidationException("Unsupported sensor type")
        }
        val sensor = sensorManager.getDefaultSensor(sensorType)
            ?: throw MiniAppValidationException("Sensor is unavailable")
        if (closed.get()) throw MiniAppBridgeException("runner_closed", "MiniApp runner is closed")
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
            if (closed.get() || sensorListeners[id] !== listener) {
                sensorManager.unregisterListener(listener)
                sensorListeners.remove(id, listener)
            } else {
                sensorManager.registerListener(listener, sensor, intervalMs * 1000)
            }
        }
        return id
    }

    private fun unsubscribeSensor(id: String) {
        val listener = sensorListeners.remove(id) ?: return
        mainHandler.post { sensorManager.unregisterListener(listener) }
    }

    fun close() {
        closed.set(true)
        bridgeScope.cancel()
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
