package app.amber.feature.prompts

import app.amber.ai.ui.UIMessagePart
import app.amber.core.ai.buildAgentPersonaPrompt
import app.amber.core.ai.buildAgentSoulPrompt
import app.amber.core.ai.tools.AGENT_SOUL_GET_TOOL_NAME
import app.amber.core.ai.tools.AGENT_SOUL_UPDATE_TOOL_NAME
import app.amber.core.ai.tools.createAgentSoulTools
import app.amber.feature.macgateway.AgentSoulCloudSyncCoordinator
import app.amber.feature.macgateway.CloudDialogueCoordinator
import app.amber.feature.macgateway.CloudDialogueTimelineSink
import app.amber.feature.macgateway.MacGatewayClient
import app.amber.feature.macgateway.MacGatewayConnection
import app.amber.feature.tools.ToolExposureState
import app.amber.feature.tools.ToolRegistry
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentSoulSyncAndToolsTest {

    @Test
    fun storeCapsHistoryAt8SupportsRollbackResetAndPersistsAcrossReload() {
        val tempDir = Files.createTempDirectory("agent_soul_test").toFile()
        try {
            val storageFile = File(tempDir, "agent_soul_state.json")
            var now = 1_000L
            val store = AgentSoulStore(storageFile = storageFile, clock = {
                now += 100L
                now
            })

            assertTrue(store.state.value.isDefault)
            assertEquals("Amber", store.state.value.displayName)

            // Perform 10 updates; the first transition from default doesn't push empty default,
            // and the subsequent 9 transitions push 9 snapshots capped at 8.
            for (i in 1..10) {
                val changed = store.update(
                    displayName = "Persona-$i",
                    tagline = "Tagline $i",
                    soulMarkdown = "Soul markdown $i",
                    source = AgentSoulStore.SOURCE_ANDROID_UI,
                )
                assertTrue(changed)
            }

            val current = store.state.value
            assertEquals("Persona-10", current.displayName)
            assertEquals("Tagline 10", current.tagline)
            assertFalse(current.isDefault)
            assertEquals(8, current.history.size)
            assertEquals("Persona-9", current.history.first().displayName)
            assertEquals("Persona-2", current.history.last().displayName)

            // Rollback to Persona-5
            val targetSnap = current.history.first { it.displayName == "Persona-5" }
            assertTrue(store.restoreSnapshot(targetSnap.id))
            assertEquals("Persona-5", store.state.value.displayName)
            assertEquals("Persona-10", store.state.value.history.first().displayName)
            assertEquals(8, store.state.value.history.size)

            // Reset to default Amber
            assertTrue(store.resetToDefault())
            assertTrue(store.state.value.isDefault)
            assertEquals("Amber", store.state.value.displayName)
            assertEquals("", store.state.value.tagline)
            assertEquals("", store.state.value.soulMarkdown)
            assertEquals("Persona-5", store.state.value.history.first().displayName)

            // Verify reload from disk preserves state and history
            val reloaded = AgentSoulStore(storageFile = storageFile)
            assertTrue(reloaded.state.value.isDefault)
            assertEquals("Amber", reloaded.state.value.displayName)
            assertEquals("Persona-5", reloaded.state.value.history.first().displayName)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun personaPromptInjectsSelfShapingAuthorizationAndDecouplesFromUnderlyingAgentsMd() {
        val store = AgentSoulStore(storageFile = null)
        val defaultPrompt = buildAgentPersonaPrompt(store.state.value)
        assertTrue(defaultPrompt.contains("当用户要求更换人设、角色身份、性格、说话口吻或称呼时，你拥有且必须直接调用 agent_soul_update 工具持久更新形象人设"))
        assertFalse(defaultPrompt.contains("<agent_soul_persona>"))

        store.update(
            displayName = "小琥珀",
            tagline = "冷静克制但偶尔毒舌的搭档",
            soulMarkdown = "说话短促有力，不讲套话。",
            source = AgentSoulStore.SOURCE_ANDROID_CHAT,
        )
        val customPrompt = buildAgentPersonaPrompt(store.state.value)
        assertTrue(customPrompt.contains("小琥珀"))
        assertTrue(customPrompt.contains("冷静克制但偶尔毒舌的搭档"))
        assertTrue(customPrompt.contains("<agent_soul_persona>"))
        assertTrue(customPrompt.contains("说话短促有力，不讲套话。"))

        // Underlying <agents_md> remains completely untouched
        val underlying = buildAgentSoulPrompt("# Android tool guidance\nUse tools directly.")
        assertTrue(underlying.contains("<agents_md>"))
        assertFalse(underlying.contains("小琥珀"))
    }

    private fun parseToolJsonOutput(parts: List<UIMessagePart>): JsonObject {
        val text = parts.filterIsInstance<UIMessagePart.Text>().first().text
        return Json.parseToJsonElement(text).jsonObject
    }

    @Test
    fun residentSoulToolsAreAlwaysLoadedAndUpdatePersonaInConversation() = runBlocking {
        val store = AgentSoulStore(storageFile = null, clock = { 1700000000000L })
        val tools = createAgentSoulTools(store)
        assertEquals(2, tools.size)

        val registry = ToolRegistry.from(tools)
        val getMeta = registry.metadataFor(AGENT_SOUL_GET_TOOL_NAME)
        val updateMeta = registry.metadataFor(AGENT_SOUL_UPDATE_TOOL_NAME)
        assertNotNull(getMeta)
        assertNotNull(updateMeta)

        // Verify resident exposure (Always-Loaded Core Tools, not hidden behind tool_search)
        assertTrue(ToolExposureState.isResidentTool(AGENT_SOUL_GET_TOOL_NAME, getMeta?.category))
        assertTrue(ToolExposureState.isResidentTool(AGENT_SOUL_UPDATE_TOOL_NAME, updateMeta?.category))

        // Verify approval-free classification
        assertFalse(getMeta!!.needsApproval)
        assertTrue(getMeta.autoApprovable)
        assertFalse(updateMeta!!.needsApproval)
        assertTrue(updateMeta.autoApprovable)

        val updateTool = tools.first { it.name == AGENT_SOUL_UPDATE_TOOL_NAME }
        val getTool = tools.first { it.name == AGENT_SOUL_GET_TOOL_NAME }

        val updateObj = parseToolJsonOutput(
            updateTool.execute(
                buildJsonObject {
                    put("display_name", JsonPrimitive("星野"))
                    put("tagline", JsonPrimitive("温柔的夜间观察员"))
                    put("soul_markdown", JsonPrimitive("语气平和，喜欢用星空比喻。"))
                },
            ),
        )
        assertTrue(updateObj["ok"]!!.jsonPrimitive.boolean)
        assertTrue(updateObj["changed"]!!.jsonPrimitive.boolean)
        assertEquals("星野", store.state.value.displayName)
        assertEquals("温柔的夜间观察员", store.state.value.tagline)
        assertEquals("语气平和，喜欢用星空比喻。", store.state.value.soulMarkdown)
        assertEquals(AgentSoulStore.SOURCE_ANDROID_CHAT, store.state.value.source)

        val getResult = parseToolJsonOutput(getTool.execute(buildJsonObject {}))
        assertEquals("星野", getResult["displayName"]!!.jsonPrimitive.content)
        assertFalse(getResult["isDefault"]!!.jsonPrimitive.boolean)

        // Reset via tool
        val resetResult = parseToolJsonOutput(
            updateTool.execute(
                buildJsonObject {
                    put("reset_to_default", JsonPrimitive(true))
                },
            ),
        )
        assertTrue(resetResult["ok"]!!.jsonPrimitive.boolean)
        assertTrue(store.state.value.isDefault)
        assertEquals("Amber", store.state.value.displayName)
        assertEquals("星野", store.state.value.history.first().displayName)
    }

    @Test
    fun cloudSoulSyncPullsOnHigherRevisionWithoutLoopPutAndUploadsCustomOnZeroRevision() = runBlocking {
        val observedRequests = CopyOnWriteArrayList<Triple<String, String, String>>()
        val serverSoulJson = AtomicReference(
            """
            {
              "revision": 0,
              "updatedAtMs": 0,
              "displayName": "Amber",
              "tagline": "",
              "soulMarkdown": "",
              "isDefault": true,
              "source": "server_init",
              "history": []
            }
            """.trimIndent(),
        )
        val return404ForSoul = AtomicBoolean(false)

        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val path = exchange.requestURI.path
            val body = exchange.requestBody.bufferedReader().readText()
            observedRequests += Triple(exchange.requestMethod, path, body)

            val (status, payload) = when {
                return404ForSoul.get() && path == "/v2/soul" ->
                    404 to """{"error":"not_found"}"""
                exchange.requestMethod == "GET" && path == "/v2/soul" ->
                    200 to serverSoulJson.get()
                exchange.requestMethod == "PUT" && path == "/v2/soul" -> {
                    val reqJson = Json.parseToJsonElement(body).jsonObject
                    val name = reqJson["displayName"]!!.jsonPrimitive.content
                    val tagline = reqJson["tagline"]!!.jsonPrimitive.content
                    val soul = reqJson["soulMarkdown"]!!.jsonPrimitive.content
                    val isDef = reqJson["isDefault"]!!.jsonPrimitive.boolean
                    val updatedAt = reqJson["updatedAtMs"]!!.jsonPrimitive.long
                    val source = reqJson["source"]!!.jsonPrimitive.content
                    val nextRev = Json.parseToJsonElement(serverSoulJson.get()).jsonObject["revision"]!!.jsonPrimitive.long + 1L
                    val nextJson = """
                        {
                          "revision": $nextRev,
                          "updatedAtMs": $updatedAt,
                          "displayName": "$name",
                          "tagline": "$tagline",
                          "soulMarkdown": "$soul",
                          "isDefault": $isDef,
                          "source": "$source",
                          "history": []
                        }
                    """.trimIndent()
                    serverSoulJson.set(nextJson)
                    200 to nextJson
                }
                exchange.requestMethod == "GET" && path == "/v2/dialogue/events" ->
                    200 to """{"items":[],"nextCursor":0,"soulRevision":5}"""
                else -> 500 to """{"error":"unexpected"}"""
            }
            val bytes = payload.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()

        try {
            val client = MacGatewayClient(
                addrs = listOf("127.0.0.1"),
                port = server.address.port,
                fingerprint = "unused-over-http",
                token = "pair-token-soul",
                scheme = "http",
            )
            val store = AgentSoulStore(storageFile = null, clock = { 1700000001234L })
            // Pre-populate a custom local persona before first connect
            store.update(
                displayName = "小琥珀",
                tagline = "本地预设",
                soulMarkdown = "本地自定义设定",
                source = AgentSoulStore.SOURCE_ANDROID_UI,
            )

            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val syncCoordinator = AgentSoulCloudSyncCoordinator(
                store = store,
                scope = scope,
                clientProvider = { client },
            )

            // 1. First connect when server revision == 0 and local has custom persona -> uploads once via PUT /v2/soul
            syncCoordinator.syncOnce()
            val putRequestsAfterInit = observedRequests.filter { it.first == "PUT" && it.second == "/v2/soul" }
            assertEquals(1, putRequestsAfterInit.size)
            assertEquals(1L, store.lastSyncedRevision)
            assertEquals(1L, store.state.value.revision)

            // 2. Simulate remote update (e.g. from iOS/Mac) bumping server soulRevision to 5
            observedRequests.clear()
            serverSoulJson.set(
                """
                {
                  "revision": 5,
                  "updatedAtMs": 1700000099000,
                  "displayName": "Nova",
                  "tagline": "跨端同步的新人设",
                  "soulMarkdown": "来自 iOS 端塑造的性格。",
                  "isDefault": false,
                  "source": "ios_chat",
                  "history": [
                    {
                      "id": "snap-1",
                      "displayName": "小琥珀",
                      "tagline": "本地预设",
                      "soulMarkdown": "本地自定义设定",
                      "updatedAtMs": 1700000001234,
                      "source": "android_ui"
                    }
                  ]
                }
                """.trimIndent(),
            )

            val dialogueCoordinator = CloudDialogueCoordinator(
                clientProvider = { client },
                timelineSink = CloudDialogueTimelineSink.NoOp,
                persistState = {},
                onSoulRevision = { rev -> syncCoordinator.onRemoteSoulRevision(rev) },
            )
            dialogueCoordinator.restore(
                enabled = true,
                connection = MacGatewayConnection(
                    gatewayId = "gw-1",
                    name = "Mac",
                    addrs = listOf("127.0.0.1"),
                    port = server.address.port,
                    fingerprint = "unused",
                    deviceId = "dev-1",
                ),
                persisted = null,
            )
            dialogueCoordinator.pollOnce()

            // Verify GET /v2/soul updated local store to Nova (revision 5) WITHOUT triggering any loop PUT /v2/soul!
            assertEquals("Nova", store.state.value.displayName)
            assertEquals("跨端同步的新人设", store.state.value.tagline)
            assertEquals(5L, store.state.value.revision)
            assertEquals(5L, store.lastSyncedRevision)
            assertEquals(1, store.state.value.history.size)
            assertEquals("小琥珀", store.state.value.history.first().displayName)
            assertEquals(1, observedRequests.count { it.first == "GET" && it.second == "/v2/soul" })
            assertEquals(0, observedRequests.count { it.first == "PUT" && it.second == "/v2/soul" })

            // Subsequent poll with same soulRevision=5 makes zero /v2/soul requests
            observedRequests.clear()
            dialogueCoordinator.pollOnce()
            assertEquals(0, observedRequests.count { it.second == "/v2/soul" })

            // 3. Local mutation triggers async PUT /v2/soul
            observedRequests.clear()
            store.update(
                displayName = "阿狸",
                tagline = "安卓端对话修改",
                soulMarkdown = "机敏灵动。",
                source = AgentSoulStore.SOURCE_ANDROID_CHAT,
            )
            withTimeout(2_000L) {
                while (store.lastSyncedRevision < 6L) {
                    delay(10L)
                }
            }
            assertEquals(1, observedRequests.count { it.first == "PUT" && it.second == "/v2/soul" })
            assertEquals(6L, store.state.value.revision)
            assertEquals(6L, store.lastSyncedRevision)

            // 4. Old gateway returning 404 silently degrades without throwing or losing local edit
            return404ForSoul.set(true)
            store.update(
                displayName = "离线人设",
                tagline = "旧网关降级验证",
                soulMarkdown = "保持本地可用。",
                source = AgentSoulStore.SOURCE_ANDROID_UI,
            )
            assertEquals("离线人设", store.state.value.displayName)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun offlineLocalEditPushesOnReconnectAndPreservesUnsyncedSnapshotWhenRemoteAdvances() = runBlocking {
        val observedRequests = CopyOnWriteArrayList<Triple<String, String, String>>()
        val failNetwork = AtomicBoolean(false)
        val serverSoulJson = AtomicReference(
            """
            {
              "revision": 1,
              "updatedAtMs": 1000,
              "displayName": "初始云端",
              "tagline": "v1",
              "soulMarkdown": "初始设定",
              "isDefault": false,
              "source": "macos_ui",
              "history": []
            }
            """.trimIndent(),
        )
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val path = exchange.requestURI.path
            val body = exchange.requestBody.bufferedReader().readText()
            observedRequests += Triple(exchange.requestMethod, path, body)
            val (status, payload) = when {
                failNetwork.get() -> 503 to """{"error":"offline"}"""
                exchange.requestMethod == "GET" && path == "/v2/soul" -> 200 to serverSoulJson.get()
                exchange.requestMethod == "PUT" && path == "/v2/soul" -> {
                    val reqJson = Json.parseToJsonElement(body).jsonObject
                    val nextRev = Json.parseToJsonElement(serverSoulJson.get()).jsonObject["revision"]!!.jsonPrimitive.long + 1L
                    val nextJson = """
                        {
                          "revision": $nextRev,
                          "updatedAtMs": ${reqJson["updatedAtMs"]!!.jsonPrimitive.long},
                          "displayName": "${reqJson["displayName"]!!.jsonPrimitive.content}",
                          "tagline": "${reqJson["tagline"]!!.jsonPrimitive.content}",
                          "soulMarkdown": "${reqJson["soulMarkdown"]!!.jsonPrimitive.content}",
                          "isDefault": ${reqJson["isDefault"]!!.jsonPrimitive.boolean},
                          "source": "${reqJson["source"]!!.jsonPrimitive.content}",
                          "history": []
                        }
                    """.trimIndent()
                    serverSoulJson.set(nextJson)
                    200 to nextJson
                }
                else -> 500 to """{"error":"unexpected"}"""
            }
            val bytes = payload.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        val tempDir = Files.createTempDirectory("agent_soul_offline_test").toFile()
        try {
            val storageFile = File(tempDir, "agent_soul_state.json")
            val client = MacGatewayClient(
                addrs = listOf("127.0.0.1"),
                port = server.address.port,
                fingerprint = "unused-over-http",
                token = "pair-token-soul",
                scheme = "http",
            )
            val store = AgentSoulStore(storageFile = storageFile, clock = { 2_000L })
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val syncCoordinator = AgentSoulCloudSyncCoordinator(
                store = store,
                scope = scope,
                clientProvider = { client },
            )

            // Sync initial v1 from server
            syncCoordinator.syncOnce()
            assertEquals(1L, store.lastSyncedRevision)

            // Go offline and edit locally -> async PUT fails, pending dirty state must persist
            failNetwork.set(true)
            store.update(
                displayName = "离线草稿",
                tagline = "断网修改",
                soulMarkdown = "断网期间编写的设定",
                source = AgentSoulStore.SOURCE_ANDROID_UI,
            )
            delay(50L)
            assertEquals(1L, store.lastSyncedRevision)

            // Reconnect while server is still at revision 1 -> syncOnce must push the pending local edit
            observedRequests.clear()
            failNetwork.set(false)
            syncCoordinator.syncOnce()
            assertEquals(1, observedRequests.count { it.first == "PUT" && it.second == "/v2/soul" })
            assertEquals(2L, store.lastSyncedRevision)
            assertEquals("离线草稿", store.state.value.displayName)

            // Second outage: local edits while another device advances server to revision 9 with non-empty history
            failNetwork.set(true)
            store.update(
                displayName = "本地未同步版",
                tagline = "离线版本",
                soulMarkdown = "不应丢失的本地人设正文",
                source = AgentSoulStore.SOURCE_ANDROID_CHAT,
            )
            delay(50L)
            serverSoulJson.set(
                """
                {
                  "revision": 9,
                  "updatedAtMs": 9000,
                  "displayName": "远端新版",
                  "tagline": "桌面端修改",
                  "soulMarkdown": "远端权威内容",
                  "isDefault": false,
                  "source": "macos_ui",
                  "history": [
                    {
                      "id": "remote-snap-1",
                      "displayName": "远端历史1",
                      "tagline": "旧版",
                      "soulMarkdown": "旧版内容",
                      "updatedAtMs": 5000,
                      "source": "macos_ui"
                    }
                  ]
                }
                """.trimIndent(),
            )
            failNetwork.set(false)
            syncCoordinator.syncOnce()
            assertEquals("远端新版", store.state.value.displayName)
            assertEquals(9L, store.lastSyncedRevision)
            assertTrue(
                "Unsynced local persona must be preserved in merged history when overwritten by higher remote revision",
                store.state.value.history.any {
                    it.displayName == "本地未同步版" && it.soulMarkdown == "不应丢失的本地人设正文"
                },
            )
        } finally {
            server.stop(0)
            tempDir.deleteRecursively()
        }
    }
}
