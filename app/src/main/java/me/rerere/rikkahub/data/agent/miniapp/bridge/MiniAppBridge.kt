package me.rerere.rikkahub.data.agent.miniapp.bridge

import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
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
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.agent.miniapp.MiniAppBridgeRequest
import me.rerere.rikkahub.data.agent.miniapp.MiniAppHttpClient
import me.rerere.rikkahub.data.agent.miniapp.MiniAppBridgeResponse
import me.rerere.rikkahub.data.agent.miniapp.MiniAppPermission
import me.rerere.rikkahub.data.agent.miniapp.MiniAppSandbox
import me.rerere.rikkahub.data.agent.miniapp.MiniAppSearchBridge
import me.rerere.rikkahub.data.agent.miniapp.MiniAppStorage

class MiniAppBridge(
    private val webViewProvider: () -> WebView?,
    private val appId: String,
    private val sessionToken: String,
    private val sandbox: MiniAppSandbox,
    private val storage: MiniAppStorage,
    private val httpClient: MiniAppHttpClient,
    private val searchBridge: MiniAppSearchBridge,
    private val toast: (String) -> Unit,
    private val clipboardCopy: (String) -> Unit,
    private val updateBoardSummary: (String) -> Unit,
    private val themeProvider: () -> MiniAppTheme,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun postMessage(raw: String) {
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
                runBlocking { httpClient.fetch(params) }
            }

            "search" -> {
                sandbox.require(MiniAppPermission.Search)
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
                updateBoardSummary(summary)
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
