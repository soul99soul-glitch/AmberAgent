package app.amber.agent.perffixture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import app.amber.ai.core.MessageRole
import app.amber.ai.provider.Model
import app.amber.ai.provider.ModelType
import app.amber.ai.provider.ProviderSetting
import app.amber.ai.ui.ToolApprovalState
import app.amber.ai.ui.UIMessage
import app.amber.ai.ui.UIMessagePart
import app.amber.core.model.Conversation
import app.amber.core.model.MessageNode
import app.amber.core.repository.ConversationRepository
import app.amber.core.utils.JsonInstant
import kotlinx.serialization.encodeToString
import app.amber.core.settings.prefs.SettingsAggregator
import app.amber.agent.LAST_CONVERSATION_ID_PREF
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.util.Locale
import kotlinx.coroutines.flow.first
import kotlin.time.Clock
import org.json.JSONObject
import org.json.JSONArray
import kotlin.uuid.Uuid

internal data class FixtureSpec(
    val id: String,
    val title: String,
    val conversationId: Uuid,
    val nodeCount: Int,
    val deepLink: Boolean = false,
    val streaming: Boolean = false,
    val note: String = "",
)

internal data class SeedResult(
    val scenario: String,
    val conversationId: Uuid,
    val nodeCount: Int,
    val contentHash: String,
    val secondaryConversationId: Uuid? = null,
)

/**
 * Deterministic synthetic data. This runs in the prep Activity/process, never
 * in the measured cold process. No production user data is read or written.
 */
internal const val S6_IMAGE_URL_1 = "https://perf-fixture.invalid/s6-local-1.png"
internal const val S6_IMAGE_URL_2 = "https://perf-fixture.invalid/s6-local-2.png"
private const val S6_ARTICLE_URL = "https://perf-fixture.invalid/perf-fixture-article"

internal object PerfFixtureScenarios {
    private const val ASSISTANT_ID = "00000000-0000-4000-8000-00000000a55a"
    private const val S4_TARGET_CHARS = 24_000

    val specs: List<FixtureSpec> = listOf(
        spec("S0", 0, note = "empty conversation"),
        spec("S1", 4, note = "short history with simple thinking"),
        spec("S2", 120, note = "paged history / shared tail"),
        spec("S3", 1_000, note = "same tail as S2"),
        spec("S4", 2, note = "24k markdown / long paragraph"),
        spec("S5", 8, note = "tail 8 nodes / 80 tools, sampled large JSON"),
        spec("S6", 2, note = "search_web gallery + citation, local image bytes"),
        spec("S7", 1, streaming = true, note = "scripted provider stream"),
        spec("S8", 120, deepLink = true, note = "deep link/restore"),
    )

    fun spec(id: String): FixtureSpec =
        specs.firstOrNull { it.id == id } ?: error("Unknown fixture scenario: $id")

    suspend fun seed(
        context: Context,
        repository: ConversationRepository,
        settingsStore: SettingsAggregator,
        scenario: String,
    ): SeedResult {
        val fixture = spec(scenario)
        configureSettings(settingsStore)
        val conversation = buildConversation(context, fixture)
        repository.getConversationById(fixture.conversationId)?.let { existing ->
            repository.deleteConversation(existing, deferCleanup = true)
        }
        // Record expected per-row bytes BEFORE persistence, including tool outputs.
        // A failed read must leave the expected row sizes/hashes as evidence.
        val rows = JSONArray().apply {
            conversation.messageNodes.forEachIndexed { index, node ->
                put(nodeFingerprint(node).put("index", index))
            }
        }
        File(context.filesDir, "expected-${fixture.id}.json").writeText(
            JSONObject().put("conversationId", fixture.conversationId.toString())
                .put("nodeCount", conversation.messageNodes.size).put("rows", rows).toString(2),
        )
        repository.insertConversation(conversation)
        verifyPersisted(context, repository, fixture.id)
        context.writeFixtureString("perf_scenario_id_${fixture.id}", fixture.conversationId.toString())
        // The launcher also writes this just before EntryRequested; this makes
        // a direct am start of RouteActivity deterministic after prep.
        context.writeFixtureString(LAST_CONVERSATION_ID_PREF, fixture.conversationId.toString())
        // S8 fast-switch/cancel needs a second real conversation in the same
        // database. Seed it only for S8 so other scenarios keep their cost.
        var secondary: Uuid? = null
        if (fixture.id == "S8") {
            val other = spec("S2")
            val otherConversation = buildConversation(context, other)
            repository.getConversationById(other.conversationId)?.let { existing ->
                repository.deleteConversation(existing, deferCleanup = true)
            }
            repository.insertConversation(otherConversation)
            context.writeFixtureString("perf_scenario_id_${other.id}", other.conversationId.toString())
            secondary = other.conversationId
        }
        return SeedResult(
            scenario = fixture.id,
            conversationId = fixture.conversationId,
            nodeCount = conversation.messageNodes.size,
            contentHash = contentHash(conversation),
            secondaryConversationId = secondary,
        )
    }

    private suspend fun configureSettings(store: SettingsAggregator) {
        val current = store.settingsFlow.first { !it.init }
        val model = Model(
            modelId = "perf-fixture-model",
            displayName = "Perf fixture model",
            type = ModelType.CHAT,
        )
        val provider = ProviderSetting.OpenAI(
            name = "Perf fixture provider",
            models = listOf(model),
            apiKey = "fixture-only",
        )
        store.update(
            current.copy(
                providers = listOf(provider),
                chatModelId = model.id,
                titleModelId = model.id,
                suggestionModelId = model.id,
                compressModelId = model.id,
                streamOutput = true,
                maxTokens = 8192,
                enableWebSearch = false,
                enabledMcpServerIds = emptySet(),
                presetMessages = emptyList(),
                regexes = emptyList(),
                systemPrompt = "Local perf fixture only. Do not call tools.",
                agentRuntime = current.agentRuntime.copy(
                    enableCoreMemory = false,
                    enableShortTermMemory = false,
                    enableLongTermMemory = false,
                    enableRecentChatsReference = false,
                    enableLiveStatusNotification = false,
                    keepGenerationAliveInBackground = false,
                ),
            )
        )
    }

    internal fun buildConversation(context: Context, spec: FixtureSpec): Conversation {
        if (spec.id == "S6") {
            writeGalleryImage(context, "s6-local-1.png", Color.rgb(32, 120, 190))
            writeGalleryImage(context, "s6-local-2.png", Color.rgb(190, 90, 40))
        }
        val nodes = when (spec.id) {
            "S0" -> emptyList()
            "S1" -> listOf(
                user(1, "S1 用户短问题"),
                assistant(2, listOf(
                    UIMessagePart.Reasoning("S1 简单思考", finishedAt = Clock.System.now()),
                    UIMessagePart.Text("S1 助手短回答，用于短历史防回退。"),
                )),
                user(3, "S1 继续"),
                assistant(4, listOf(UIMessagePart.Text("S1 完成。"))),
            )
            "S2" -> pagedNodes(120, prefix = "S2")
            "S3" -> pagedNodes(1_000, prefix = "S2") // identical tail, larger prefix
            "S4" -> listOf(
                user(1, "S4 请阅读长文"),
                assistant(2, listOf(UIMessagePart.Text(markdown24k()))),
            )
            "S5" -> (0 until 4).flatMap { group ->
                listOf(
                    user(group * 2 + 1, "S5 工具重载 $group"),
                    assistant(group * 2 + 2, toolHeavyParts(group)),
                )
            }
            "S6" -> listOf(
                user(1, "S6 搜索图片与引用"),
                assistant(2, listOf(
                    // Production search derivation reads an executed search_web
                    // tool output; the http(s) URLs are resolved to local bytes
                    // by the fixture-only Coil model override (no network).
                    UIMessagePart.Tool(
                        toolCallId = "perf-s6-search",
                        toolName = "search_web",
                        input = "{\"query\":\"perf fixture\"}",
                        output = listOf(
                            UIMessagePart.Text(
                                "{\"items\":[{\"url\":\"$S6_ARTICLE_URL\"," +
                                    "\"title\":\"Perf fixture article\"," +
                                    "\"images\":[\"$S6_IMAGE_URL_1\",\"$S6_IMAGE_URL_2\"]}]}",
                            ),
                        ),
                        approvalState = ToolApprovalState.Auto,
                    ),
                    UIMessagePart.Text(
                        "S6 搜索结果与引用：perf fixture [来源]($S6_ARTICLE_URL)。",
                    ),
                )),
            )
            "S7" -> listOf(user(1, "S7 streaming probe"))
            "S8" -> pagedNodes(120, prefix = "S8")
            else -> error("Unknown fixture scenario ${spec.id}")
        }
        return Conversation.ofId(
            id = spec.conversationId,
            assistantId = Uuid.parse(ASSISTANT_ID),
            messages = nodes.mapIndexed { index, node ->
                // Room row IDs and serialized timestamps must survive a repeat
                // seed byte-for-byte, and S8/S2 may coexist in the same DB.
                val id = java.util.UUID.nameUUIDFromBytes("${spec.id}:$index".toByteArray())
                node.copy(
                    id = Uuid.parse(id.toString()),
                    messages = node.messages.map { message ->
                        message.copy(
                            createdAt = kotlinx.datetime.LocalDateTime(2023, 11, 14, 0, 0),
                            parts = message.parts.map { part ->
                                if (part is UIMessagePart.Reasoning) part.copy(
                                    createdAt = kotlin.time.Instant.fromEpochMilliseconds(1_700_000_000_000),
                                    finishedAt = kotlin.time.Instant.fromEpochMilliseconds(1_700_000_000_001),
                                ) else part
                            },
                        )
                    },
                )
            },
        ).copy(
            title = "Perf ${spec.id}",
            createAt = Instant.ofEpochMilli(1_700_000_000_000L + spec.id.hashCode()),
            updateAt = Instant.ofEpochMilli(1_700_000_100_000L + spec.id.hashCode()),
        )
    }

    private fun pagedNodes(count: Int, prefix: String): List<MessageNode> {
        val tail = tailNodes(prefix)
        val generic = (0 until (count - tail.size)).map { index ->
            if (index % 2 == 0) {
                user(index + 1, "$prefix fixture question $index")
            } else {
                assistant(
                    index + 1,
                    listOf(UIMessagePart.Text("$prefix fixture answer $index\n\nDeterministic history body.")),
                )
            }
        }
        return generic + tail
    }

    /** Stable tail shared by S2/S3/S8: same IDs, same content. */
    private fun tailNodes(prefix: String): List<MessageNode> = listOf(
        user(10_001, "$prefix tail user A"),
        assistant(10_002, listOf(UIMessagePart.Text("$prefix tail answer A\n\nshared tail body"))),
        user(10_003, "$prefix tail user B"),
        assistant(10_004, listOf(
            UIMessagePart.Reasoning("$prefix tail thinking", finishedAt = Clock.System.now()),
            UIMessagePart.Text("$prefix tail answer B\n\nshared tail body"),
        )),
        user(10_005, "$prefix tail user C"),
        assistant(10_006, listOf(UIMessagePart.Text("$prefix tail answer C\n\nshared tail body"))),
        user(10_007, "$prefix tail user D"),
        assistant(10_008, listOf(UIMessagePart.Text("$prefix tail answer D\n\nshared tail body"))),
    )

    private fun markdown24k(): String {
        val block = buildString {
            append("# S4 长文标题\n\n")
            append("长单段：")
            append("这是一段用于大段落与分组边界测量的长文本。".repeat(80))
            append("\n\n## 代码\n\n```kotlin\nfun perf(x: Int) = x * 2\n```\n\n")
            append("| 项目 | 结果 |\n| --- | --- |\n| 文本 | 完整 |\n| 公式 | \$E=mc^2\$ |\n\n")
            append("> 引用块保留格式。\n\n")
            append("公式：\$\$\\sum_{i=1}^{n} i = \\frac{n(n+1)}{2}\$\$\n\n")
            append("结尾段落。".repeat(80))
        }
        if (block.length >= S4_TARGET_CHARS) return block.take(S4_TARGET_CHARS)
        return block + "补充正文。".repeat((S4_TARGET_CHARS - block.length) / 5 + 1)
    }

    private fun toolHeavyParts(group: Int): List<UIMessagePart> {
        // Round-robin keeps the two 1MiB outputs on different persisted rows.
        // All 80 tools and the original size tiers remain present.
        val tools = (0 until 80).filter { it % 4 == group }.map { index ->
            val bytes = when (index) {
                0, 2 -> 1_048_576
                1, 3 -> 262_144
                else -> 1_024
            }
            UIMessagePart.Tool(
                toolCallId = "perf-s5-tool-$index",
                toolName = "perf_tool_$index",
                input = "{\"index\":$index,\"scenario\":\"S5\"}",
                output = listOf(UIMessagePart.Text(toolJson(bytes, index))),
                approvalState = ToolApprovalState.Auto,
            )
        }
        return tools + UIMessagePart.Text("S5 tool-heavy summary; all outputs remain available.")
    }

    private fun toolJson(bytes: Int, index: Int): String {
        val prefix = "{\"status\":\"succeeded\",\"index\":$index,\"items\":["
        val suffix = "]}"
        val filler = (bytes - prefix.length - suffix.length - 2).coerceAtLeast(0)
        return prefix + "\"" + "x".repeat(filler) + "\"" + suffix
    }

    /**
     * Fixture-only http(s) -> local file mapping used by
     * ChatPerfImageModelOverride. Production URLs never match.
     */
    internal fun localAssetPath(context: Context, url: String): String? {
        val name = when (url) {
            S6_IMAGE_URL_1 -> "s6-local-1.png"
            S6_IMAGE_URL_2 -> "s6-local-2.png"
            else -> return null
        }
        val file = File(context.filesDir, "perf-assets/$name")
        if (!file.isFile) return null
        return "file://" + file.absolutePath
    }

    private fun writeGalleryImage(context: Context, name: String, color: Int) {
        val file = File(context.filesDir, "perf-assets/$name")
        file.parentFile?.mkdirs()
        if (file.isFile && file.length() > 0L) return
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(color)
            drawRect(8f, 8f, 56f, 56f, Paint().apply { this.color = Color.WHITE })
        }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private fun user(index: Int, text: String) = MessageNode.of(
        UIMessage(
            id = fixtureMessageId(1, index),
            role = MessageRole.USER,
            parts = listOf(UIMessagePart.Text(text)),
        )
    )

    private fun assistant(index: Int, parts: List<UIMessagePart>) = MessageNode.of(
        UIMessage(
            id = fixtureMessageId(2, index),
            role = MessageRole.ASSISTANT,
            parts = parts,
        )
    )

    private fun fixtureMessageId(role: Int, index: Int): Uuid {
        val value = (role.toLong() shl 32) + index.coerceAtLeast(0)
        val suffix = String.format(Locale.US, "%012x", value)
        return Uuid.parse("00000000-0000-4000-8000-$suffix")
    }

    private fun contentHash(conversation: Conversation): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update("${conversation.id}|${conversation.messageNodes.size}".toByteArray())
        conversation.messageNodes.forEach { node ->
            digest.update(nodeFingerprint(node).getString("sha256").toByteArray())
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun nodeFingerprint(node: MessageNode): JSONObject {
        val bytes = JsonInstant.encodeToString(node.messages).toByteArray(Charsets.UTF_8)
        return JSONObject().put("id", node.id.toString()).put("bytes", bytes.size)
            .put("sha256", MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it) })
    }

    /** Reads each real Room row, also used in a fresh prep process after restart. */
    suspend fun verifyPersisted(
        context: Context,
        repository: ConversationRepository,
        scenario: String,
    ): JSONObject {
        val expected = JSONObject(File(context.filesDir, "expected-$scenario.json").readText())
        val id = Uuid.parse(expected.getString("conversationId"))
        check(repository.countConversationNodes(id) == expected.getInt("nodeCount"))
        val rows = expected.getJSONArray("rows")
        val verified = JSONArray()
        for (index in 0 until rows.length()) {
            val row = rows.getJSONObject(index)
            try {
                val nodes = repository.getConversationNodeRange(id, index, index)
                check(nodes.size == 1) { "decoded ${nodes.size} nodes" }
                val actual = nodeFingerprint(nodes.single())
                check(actual.getString("id") == row.getString("id") &&
                    actual.getInt("bytes") == row.getInt("bytes") &&
                    actual.getString("sha256") == row.getString("sha256")) { "row hash mismatch" }
                verified.put(actual.put("index", index))
            } catch (error: Exception) {
                throw IllegalStateException(
                    "row=$index bytes=${row.getInt("bytes")} sha256=${row.getString("sha256")} readback failed",
                    error,
                )
            }
        }
        return JSONObject().put("verified", true).put("rows", verified)
    }

    private fun spec(
        id: String,
        nodeCount: Int,
        deepLink: Boolean = false,
        streaming: Boolean = false,
        note: String,
    ): FixtureSpec {
        val suffix = id.removePrefix("S").padStart(2, '0')
        return FixtureSpec(
            id = id,
            title = "Perf $id",
            conversationId = Uuid.parse("10000000-0000-4000-8000-0000000000$suffix"),
            nodeCount = nodeCount,
            deepLink = deepLink,
            streaming = streaming,
            note = note,
        )
    }
}

internal fun writeFixtureMarker(context: Context, scenario: String, result: JSONObject) {
    val dir = context.getExternalFilesDir("chat-perf") ?: File(context.filesDir, "chat-perf")
    dir.mkdirs()
    val file = File(dir, "prepared-$scenario.json")
    val tmp = File(dir, file.name + ".tmp")
    tmp.writeText(result.toString(2))
    if (!tmp.renameTo(file)) {
        file.writeText(tmp.readText())
        tmp.delete()
    }
}
