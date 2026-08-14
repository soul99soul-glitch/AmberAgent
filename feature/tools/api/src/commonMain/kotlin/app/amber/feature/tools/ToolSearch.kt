package app.amber.feature.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import app.amber.ai.core.InputSchema
import app.amber.ai.core.Tool
import app.amber.ai.ui.UIMessagePart
import app.amber.core.model.MainAgentToolProfile

const val TOOL_SEARCH_TOOL_NAME = "tool_search"
const val TOOL_SEARCH_AUTO_THRESHOLD = 40
const val TOOL_SEARCH_DEFAULT_LIMIT = 5

private val toolSearchJson = Json { ignoreUnknownKeys = true }

/**
 * The tool_search system-prompt guidance text for a registry. Shared by
 * `createToolSearchTool`'s systemPrompt hook and the iOS exposure bridge's
 * `discoveryGuidance()` (which needs the same text without constructing a
 * Model + message list on the Swift side).
 */
internal fun toolSearchDiscoveryGuidance(
    registry: ToolRegistry,
    profile: MainAgentToolProfile? = null,
): String {
    val index = ToolSearchIndex(registry, profile)
    val categories = index.categoryCounts().entries
        .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
        .joinToString(", ") { "${it.key}:${it.value}" }
    val residentCount = registry.tools().count { tool ->
        val metadata = registry.metadataFor(tool.name)
        ToolExposureState.isResidentTool(tool.name, metadata?.category)
    }
    return """
        Tool discovery:
        - This run has ${registry.metadata.size} generated tools across categories: $categories.
        - If the needed tool is not currently visible, call `$TOOL_SEARCH_TOOL_NAME` with a concrete query. It exposes the best matching schemas for the next generation step.
        - Before telling the user you cannot do something, call `$TOOL_SEARCH_TOOL_NAME` with a concrete intent first (e.g. "读取其它会话", "并行子任务", "网页操作", "配置提供商", "API Key", "默认模型", "生成主题", "换肤"). Many capabilities — sub-agent threads, cross-session reading, web mount, MCP tools, script execution, provider/model settings tools, theme packs — stay hidden until searched; never claim inability based only on the currently visible tools. Writing API keys requires user approval; never claim you can change providers without calling the provider_config tools first. Generating or applying a theme requires theme_pack_status then theme_pack_import; never claim you changed the theme until the user confirms 套用.
        - `tools_list` is only a debug/catalog view. A hidden tool listed by `tools_list` is not callable until `$TOOL_SEARCH_TOOL_NAME` exposes it.
        - If you used `tools_list` to identify a tool name, call `$TOOL_SEARCH_TOOL_NAME` again with that exact tool name, then execute a name from `expanded_tools` on the next step.
        - Resident tools currently stay visible without search: $residentCount core tools plus discovered tools.
        """.trimIndent()
}

fun createToolSearchTool(
    registry: ToolRegistry,
    profile: MainAgentToolProfile? = null,
) = Tool(
    name = TOOL_SEARCH_TOOL_NAME,
    description = "Search AmberAgent's full tool catalog by intent/category and expose the best matching tool schemas for the next step.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("query", stringProp("Required. What capability you need, e.g. \"read PDF\", \"call Feishu MCP\", \"webview click\", \"截图\", or an exact tool name from tools_list."))
                put("category", stringProp("Optional category filter, e.g. workspace, terminal, web, webview, webmount, screen, system, memory, context, subagent, model_council, mcp, office, skill, settings."))
                put("limit", buildJsonObject {
                    put("type", "integer")
                    put("description", "Maximum tools to expose. Defaults to 5; capped at 20.")
                })
                put("display_title", stringProp("Optional short user-facing action title in Chinese, e.g. 查找写作工具."))
            },
            required = listOf("query")
        )
    },
    needsApproval = false,
    allowsAutoApproval = true,
    systemPrompt = { _, _ -> toolSearchDiscoveryGuidance(registry, profile) },
    execute = { input ->
        val query = input.jsonObject["query"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val category = input.jsonObject["category"]?.jsonPrimitive?.contentOrNull?.ifBlank { null }
        val limit = input.jsonObject["limit"]?.jsonPrimitive?.intOrNull ?: TOOL_SEARCH_DEFAULT_LIMIT
        val index = ToolSearchIndex(registry, profile)
        listOf(UIMessagePart.Text(index.searchPayload(query, category, limit).toString()))
    },
)

class ToolSearchIndex(
    private val registry: ToolRegistry,
    private val profile: MainAgentToolProfile? = null,
) {
    private val toolsByName = registry.tools().associateBy { it.name }

    fun categoryCounts(): Map<String, Int> =
        registry.metadata.groupingBy { it.category }.eachCount()

    fun searchPayload(
        query: String,
        category: String?,
        limit: Int,
    ): JsonObject {
        val normalizedCategory = category?.trim()?.lowercase()?.ifBlank { null }
        val boundedLimit = limit.coerceIn(1, 20)
        val matches = search(query, normalizedCategory, boundedLimit)
        val expandedTools = matches.map { it.metadata.name }
        val fullSchemaChars = registry.tools().sumOf { it.schemaFootprintChars() }
        val residentSchemaChars = registry.tools()
            .filter { tool -> ToolExposureState.isResidentTool(tool.name, registry.metadataFor(tool.name)?.category) }
            .sumOf { it.schemaFootprintChars() }
        val expandedSchemaChars = matches.sumOf { it.tool.schemaFootprintChars() }
        return buildJsonObject {
            put("status", "ok")
            put("query", query)
            normalizedCategory?.let { put("category", it) }
            put("limit", boundedLimit)
            put("total_tools", registry.metadata.size)
            put("resident_tools", registry.tools().count { tool ->
                ToolExposureState.isResidentTool(tool.name, registry.metadataFor(tool.name)?.category)
            })
            put("matches_count", matches.size)
            put("expanded_tools", buildJsonArray { expandedTools.forEach(::add) })
            put("callability_note", "Only tools in expanded_tools are newly callable on the next model step. tools_list is catalog/debug only and does not expose hidden schemas.")
            put("trace", buildJsonObject {
                put("mode", if (registry.metadata.size > TOOL_SEARCH_AUTO_THRESHOLD) "lazy" else "bypass")
                put("query", query)
                profile?.let {
                    put("profile", it.name.lowercase())
                    put("profile_filtered", it != MainAgentToolProfile.FULL)
                }
                put("hit_tools", buildJsonArray { expandedTools.forEach(::add) })
                put("expanded_tools", buildJsonArray { expandedTools.forEach(::add) })
                put("estimated_full_schema_chars", fullSchemaChars)
                put("estimated_resident_schema_chars", residentSchemaChars)
                put("estimated_expanded_schema_chars", expandedSchemaChars)
                put("estimated_schema_savings_chars", (fullSchemaChars - residentSchemaChars - expandedSchemaChars).coerceAtLeast(0))
            })
            put("tools", buildJsonArray { matches.forEach { add(it.toJson()) } })
            if (matches.isEmpty()) {
                put("category_candidates", buildJsonArray {
                    categoryCounts().entries
                        .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                        .take(16)
                        .forEach { (name, count) ->
                            add(buildJsonObject {
                                put("category", name)
                                put("count", count)
                            })
                        }
                })
                put("debug_hint", "No matching tool was expanded. Try a more concrete Chinese/English query, or use tools_list only to identify an exact tool name and then call tool_search(query=\"<exact_tool_name>\").")
            } else {
                put("next_step", "On the next model step, call one of expanded_tools exactly. Do not call tools only seen in tools_list unless you first expose them with tool_search(query=\"<exact_tool_name>\"). Permissions still apply.")
            }
        }
    }

    private fun search(
        query: String,
        category: String?,
        limit: Int,
    ): List<ScoredTool> {
        val normalizedQuery = query.trim().lowercase()
        val tokens = normalizedQuery
            .split(Regex("""\s+"""))
            .map { it.trim() }
            .filter { it.isNotBlank() }
        return registry.metadata
            .asSequence()
            .filter { it.name != TOOL_SEARCH_TOOL_NAME }
            .filter { category == null || it.category.lowercase() == category }
            .mapNotNull { metadata ->
                val tool = toolsByName[metadata.name] ?: return@mapNotNull null
                val score = scoreTool(metadata, tool, normalizedQuery, tokens, category)
                if (score <= 0) null else ScoredTool(metadata, tool, score)
            }
            .sortedWith(compareByDescending<ScoredTool> { it.score }.thenBy { it.metadata.name })
            .take(limit)
            .toList()
    }

    private fun scoreTool(
        metadata: ToolMetadata,
        tool: Tool,
        query: String,
        tokens: List<String>,
        category: String?,
    ): Int {
        var score = 0
        if (category != null && metadata.category.lowercase() == category) score += 25
        if (tokens.isEmpty()) return if (category != null) score + 1 else 0
        val name = metadata.name.lowercase()
        val description = tool.description.lowercase()
        val categoryText = metadata.category.lowercase()
        if (query == name) score += 240
        if (tokens.any { it == name }) score += 180
        tokens.forEach { token ->
            score += when {
                name == token -> 120
                name.startsWith(token) -> 80
                name.contains(token) -> 55
                categoryText == token -> 35
                categoryText.contains(token) -> 24
                description.contains(token) -> 16
                else -> 0
            }
        }
        searchAliases(metadata).forEach { alias ->
            val normalizedAlias = alias.lowercase()
            score += when {
                query == normalizedAlias -> 100
                query.contains(normalizedAlias) -> 55
                normalizedAlias.contains(query) && query.length >= 2 -> 32
                tokens.any { it == normalizedAlias } -> 80
                tokens.any { normalizedAlias.contains(it) && it.length >= 2 } -> 24
                else -> 0
            }
        }
        if (tokens.any { token -> name.split('_').contains(token) }) score += 30
        if (score > 0 && !metadata.mutates && metadata.risk == ToolRisk.Normal) score += 2
        return score
    }

    private fun searchAliases(metadata: ToolMetadata): List<String> = buildList {
        val name = metadata.name
        val category = metadata.category
        when {
            name == "screen_screenshot" -> addAll(listOf("截图", "截屏", "屏幕截图", "看屏幕", "screenshot"))
            name == "screen_read_ui" -> addAll(listOf("读屏幕", "读取屏幕", "ui 树", "UI 树", "当前页面", "看页面"))
            name.startsWith("screen_click") || name.startsWith("screen_tap") -> addAll(listOf("点击", "点一下", "点击屏幕", "tap"))
            name.startsWith("screen_") -> addAll(listOf("屏幕", "手机屏幕", "滑动", "输入"))
        }
        if (name.startsWith("wm_") || category.contains("webmount")) {
            addAll(listOf("网页", "浏览器", "webview", "WebView", "webmount", "打开网页", "点击网页", "读取网页"))
        }
        when (name) {
            "wm_observe" -> addAll(listOf("观察网页", "页面状态", "网页摘要", "网页节点"))
            "wm_visual_snapshot", "wm_visual_read" -> addAll(listOf("网页截图", "读图", "视觉读取", "图片识别", "看网页图片"))
            "wm_network_inspect", "wm_fetch_replay" -> addAll(listOf("网络请求", "接口", "XHR", "fetch", "重放请求"))
        }
        if (name.startsWith("feishu_docs_") || category.contains("feishu")) {
            addAll(listOf("飞书", "云文档", "飞书云文档", "wiki", "知识库", "文档", "表格", "会议纪要"))
        }
        if (name.startsWith("subagent_")) {
            addAll(listOf("子代理", "subagent", "副 agent", "副代理"))
        }
        // P1-c: 线程编排六工具的增量中文词条（纯增量，不改既有别名）。
        when (name) {
            "spawn_agent" -> addAll(listOf("子代理", "开子代理", "并行任务", "子线程", "多线程", "编排"))
            "list_agents" -> addAll(listOf("线程列表", "子代理列表", "并行列表", "编排"))
            "interrupt_agent" -> addAll(listOf("中断子代理", "停止子线程", "打断", "取消子代理"))
            "wait_agent" -> addAll(listOf("等待子代理", "等子线程", "等待线程"))
            "send_message" -> addAll(listOf("发消息给子代理", "子线程消息", "通知子代理"))
            "followup_task" -> addAll(listOf("跟进任务", "子代理追加", "追派任务"))
        }
        // P3-c: exec cell 续取工具的增量中文词条（纯增量）。wait 与 exec 同开关
        // （execJavaScriptEnabled），非常驻 deferred 池——命中后模型才可调。
        if (name == "wait") {
            addAll(listOf("等待脚本", "脚本结果", "cell", "继续执行", "取回结果"))
        }
        // 跨会话读取工具（session_search/session_read）的增量中文词条（纯增量）。
        // 与 Android 当前会话作用域的 conversation_search/conversation_expand 语义
        // 错开：命中引导到跨会话读取，不引导到当前会话上下文工具。
        if (name == "session_search" || name == "session_read") {
            addAll(listOf("会话", "历史", "别的对话", "另一个会话", "session", "聊天记录", "过去的对话", "跨会话"))
        }
        if (name.startsWith("provider_config_") || name == "settings_set_model_slot") {
            addAll(
                listOf(
                    "配置提供商", "provider", "API Key", "api key", "密钥", "模型列表",
                    "默认模型", "chat 模型", "刷新模型", "OpenRouter", "DeepSeek", "设置模型",
                ),
            )
        }
        if (name.startsWith("theme_pack_")) {
            addAll(
                listOf(
                    "主题", "换肤", "外观", "试穿", "生成主题", "主题包", "雨天书店",
                    "theme", "theme pack", "accent",
                ),
            )
        }
        if (name.startsWith("model_council_")) {
            addAll(listOf("议会", "多模型", "council", "模型会议"))
        }
        if (name.startsWith("file_")) {
            addAll(listOf("文件", "工作区", "搜索文件", "读文件", "写文件"))
        }
        if (name.startsWith("terminal_")) {
            addAll(listOf("终端", "命令", "脚本", "运行命令", "terminal"))
        }
        if (name.startsWith("mcp_") || category == "mcp") {
            addAll(listOf("mcp", "MCP", "外部工具"))
        }
    }

    private fun ScoredTool.toJson(): JsonObject = buildJsonObject {
        put("name", metadata.name)
        put("category", metadata.category)
        put("description", tool.description.take(360))
        put("score", score)
        put("mutates", metadata.mutates)
        put("sensitive_read", metadata.sensitiveRead)
        put("needs_approval", metadata.needsApproval)
        put("allows_auto_approval", metadata.autoApprovable)
        put("risk", metadata.risk.name.lowercase())
        put("output_budget_chars", metadata.outputBudgetChars)
        put("schema", tool.parameters()?.toString().orEmpty())
    }

    private data class ScoredTool(
        val metadata: ToolMetadata,
        val tool: Tool,
        val score: Int,
    )
}

class ToolExposureState private constructor(
    private val allTools: List<Tool>,
    private val lazyMode: Boolean,
    initialExposedNames: Set<String>,
) {
    private val toolsByName = allTools.associateBy { it.name }
    private val exposedNames = initialExposedNames.toMutableSet()

    val enabled: Boolean
        get() = lazyMode

    fun toolsForStep(): List<Tool> =
        if (!lazyMode) allTools else allTools.filter { it.name in exposedNames }

    fun exposeToolNames(names: Iterable<String>) {
        if (!lazyMode) return
        names
            .filter { it in toolsByName }
            .forEach { exposedNames += it }
    }

    fun observeExecutedTools(executedTools: List<UIMessagePart.Tool>) {
        if (!lazyMode) return
        val expandedNames = executedTools
            .filter { it.toolName == TOOL_SEARCH_TOOL_NAME }
            .flatMap { it.expandedToolNames() }
        exposeToolNames(expandedNames)
    }

    companion object {
        fun from(
            tools: List<Tool>,
            residentPolicy: (name: String, category: String?) -> Boolean = { name, category ->
                isResidentTool(name, category)
            },
        ): ToolExposureState {
            val toolCount = tools.count { it.name !in DISCOVERY_UTILITY_TOOLS }
            val hasSearch = tools.any { it.name == TOOL_SEARCH_TOOL_NAME }
            if (toolCount <= TOOL_SEARCH_AUTO_THRESHOLD || !hasSearch) {
                return ToolExposureState(tools, lazyMode = false, initialExposedNames = tools.map { it.name }.toSet())
            }
            val registry = runCatching { ToolRegistry.from(tools) }.getOrNull()
            val initial = tools.filter { tool ->
                residentPolicy(tool.name, registry?.metadataFor(tool.name)?.category)
            }.map { it.name }.toSet()
            return ToolExposureState(tools, lazyMode = true, initialExposedNames = initial)
        }

        fun isResidentTool(name: String, category: String?): Boolean = when {
            name in DISCOVERY_UTILITY_TOOLS -> true
            name in RESIDENT_EXACT_TOOLS -> true
            name.startsWith("subagent_") -> true
            name in RESIDENT_MODEL_COUNCIL_TOOLS -> true
            name.startsWith("agent_task_") -> name in setOf("agent_task_list", "agent_task_read")
            category == "context" -> name in RESIDENT_CONTEXT_TOOLS
            else -> false
        }

        private val DISCOVERY_UTILITY_TOOLS = setOf(
            TOOL_SEARCH_TOOL_NAME,
            "tools_list",
            "tool_policy_explain",
        )

        private val RESIDENT_EXACT_TOOLS = setOf(
            "ask_user",
            "permissions_status",
            "agent_runtime_status",
            "file_list",
            "file_read",
            "file_write",
            "file_edit",
            "file_search",
            "file_move",
            "terminal_execute",
            "terminal_job_start",
            "terminal_job_read",
            "terminal_job_wait",
            "terminal_job_stop",
            "terminal_session_start",
            "terminal_session_exec",
            "terminal_session_read",
            "terminal_session_stop",
            "mcp_list",
            "mcp_call_tool",
            "generate_image",
        )

        private val RESIDENT_CONTEXT_TOOLS = setOf(
            "conversation_context_status",
            "conversation_search",
            "conversation_expand",
        )

        private val RESIDENT_MODEL_COUNCIL_TOOLS = setOf(
            "model_council_status",
            "model_council_start",
            "model_council_read",
            "model_council_wait",
            "model_council_cancel",
        )
    }
}

private fun UIMessagePart.Tool.expandedToolNames(): List<String> =
    output.filterIsInstance<UIMessagePart.Text>().flatMap { part ->
        runCatching {
            val payload = toolSearchJson.parseToJsonElement(part.text) as? JsonObject
                ?: return@runCatching emptyList()
            val names = payload["expanded_tools"] as? JsonArray ?: return@runCatching emptyList()
            names.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        }.getOrDefault(emptyList())
    }

internal fun Tool.schemaFootprintChars(): Int =
    name.length + description.length + (parameters()?.toString()?.length ?: 0)

private fun stringProp(description: String) = buildJsonObject {
    put("type", "string")
    put("description", description)
}
