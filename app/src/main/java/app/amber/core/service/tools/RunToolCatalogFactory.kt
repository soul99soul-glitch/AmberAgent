package app.amber.core.service.tools

import android.content.Context
import app.amber.agent.R
import app.amber.ai.core.Tool
import app.amber.ai.provider.ProviderCatalog
import app.amber.ai.provider.providers.GoogleProvider
import app.amber.ai.provider.providers.google.AntigravityAuthStore
import app.amber.ai.provider.providers.grok.GrokAuthStore
import app.amber.ai.provider.providers.openai.OpenAICodexAuthStore
import app.amber.ai.ui.UIMessagePart
import app.amber.core.ai.mcp.McpManager
import app.amber.core.ai.mcp.createMcpTools
import app.amber.core.ai.tools.LocalTools
import app.amber.core.ai.tools.TOOL_THEME_PACK_IMPORT
import app.amber.core.ai.tools.TOOL_THEME_PACK_STATUS
import app.amber.core.ai.tools.createMcpManagementTools
import app.amber.core.ai.tools.createProviderConfigTools
import app.amber.core.ai.tools.createSearchTools
import app.amber.core.ai.tools.createSkillTools
import app.amber.core.ai.tools.createSoulTools
import app.amber.core.ai.tools.createThemePackTools
import app.amber.core.context.ConversationContextEngine
import app.amber.core.files.SkillManager
import app.amber.core.jev.JevToolSemanticSearch
import app.amber.core.model.AMBER_AGENT_ID
import app.amber.core.model.Conversation
import app.amber.core.repository.ConversationRepository
import app.amber.core.settings.AMBER_AGENT_LOCAL_TOOLS
import app.amber.core.settings.AMBER_AGENT_TOOL_PROFILE
import app.amber.core.settings.CapabilityFlags
import app.amber.core.settings.Settings
import app.amber.core.settings.getCurrentChatModel
import app.amber.core.settings.prefs.SettingsAggregator
import app.amber.core.settings.secret.SecretStore
import app.amber.core.utils.appLocale
import app.amber.feature.history.SessionAccessGrantStore
import app.amber.feature.jscell.JsCellRuntime
import app.amber.feature.modelcouncil.ModelCouncilManager
import app.amber.feature.recipe.RecipeRegistry
import app.amber.feature.runtime.AgentToolActivityStore
import app.amber.feature.runtime.AgentToolDispatcher
import app.amber.feature.runtime.CapabilityPermissionStore
import app.amber.feature.runtime.ToolEffectLedger
import app.amber.feature.subagent.SubAgentManager
import app.amber.feature.task.AgentTaskScheduler
import app.amber.feature.tools.AgentTaskTools
import app.amber.feature.tools.ConversationContextTools
import app.amber.feature.tools.ConversationHistoryTools
import app.amber.feature.tools.ModelCouncilTools
import app.amber.feature.tools.SSH_PROFILE_WRITE_TOOL_NAMES
import app.amber.feature.tools.SubAgentTools
import app.amber.feature.tools.ToolProfileFilter
import app.amber.feature.tools.ToolRegistry
import app.amber.feature.tools.createToolSearchTool
import app.amber.feature.ui.subagent.AppSubAgentDisplayLocalizer
import app.amber.feature.ui.theme.ThemePackageManager
import app.amber.feature.workspace.WorkspaceManager
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlin.uuid.Uuid

private const val WEBVIEW_SEARCH_OPEN_TOOL_NAME = "webview_search_open"

/** Composes each run's tool surface without owning conversation or execution state. */
class RunToolCatalogFactory(
    private val context: Context,
    private val settingsStore: SettingsAggregator,
    private val conversationRepo: ConversationRepository,
    private val json: Json,
    private val localTools: LocalTools,
    private val mcpManager: McpManager,
    private val activityStore: AgentToolActivityStore,
    private val skillManager: SkillManager,
    private val workspaceManager: WorkspaceManager,
    private val contextEngine: ConversationContextEngine,
    private val subAgentManager: SubAgentManager,
    private val modelCouncilManager: ModelCouncilManager,
    private val agentTaskScheduler: AgentTaskScheduler,
    private val sessionAccessGrantStore: SessionAccessGrantStore,
    private val memoryToolFactory: MemoryRunToolFactory,
    private val providerCatalog: ProviderCatalog,
    private val googleProvider: GoogleProvider,
    private val openAICodexAuthStore: OpenAICodexAuthStore,
    private val grokAuthStore: GrokAuthStore,
    private val antigravityAuthStore: AntigravityAuthStore,
    private val capabilityFlags: CapabilityFlags? = null,
    private val capabilityPermissionStore: CapabilityPermissionStore? = null,
    private val toolEffectLedger: ToolEffectLedger? = null,
    private val toolDispatcher: AgentToolDispatcher? = null,
    private val recipeRegistry: RecipeRegistry? = null,
    private val jsCellRuntime: JsCellRuntime? = null,
    private val secretStore: SecretStore? = null,
    private val themePackageManager: ThemePackageManager? = null,
    private val jevToolSemanticSearch: JevToolSemanticSearch? = null,
) {
    fun createDebugRunTools(settings: Settings): List<Tool> =
        createRunTools(settings, null, casAuditEnabled = false)

    internal suspend fun createKernelRunTools(
        settings: Settings,
        conversationId: Uuid,
        runId: String?,
        conversation: Conversation,
        durablePath: Boolean,
        conversationProvider: () -> Conversation,
        conversationQueueTools: List<Tool>,
        autoApprovedToolNames: Set<String>,
        events: app.amber.core.agent.runtime.AgentEventWriter? = null,
        executionPolicy: app.amber.feature.runtime.ExecutionPolicy =
            app.amber.feature.runtime.ExecutionPolicy.permissive(),
    ): List<Tool> = createRunTools(
        settings,
        conversationId,
        runId,
        casAuditEnabled = capabilityFlags?.isEnabled(
            app.amber.core.settings.Capability.CapabilityPermissions
        ) == true,
        conversationProvider = conversationProvider,
        conversationQueueTools = conversationQueueTools,
        recipeContext = buildRecipeRunContext(
            conversationId = conversationId,
            runId = runId,
            settings = settings,
            conversation = conversation,
            durablePath = durablePath,
            events = events,
            autoApprovedToolNames = autoApprovedToolNames,
            executionPolicy = executionPolicy,
        ),
        // P1-7: the same run policy the recipe context carries is handed to
        // the subagent tools, so children start under the parent's sandbox.
        executionPolicy = executionPolicy,
        threadGraphEnabled = capabilityFlags?.isEnabled(
            app.amber.core.settings.Capability.ThreadGraphV2
        ) == true,
        jsCellEnabled = capabilityFlags?.isEnabled(
            app.amber.core.settings.Capability.JSCellRuntime
        ) == true,
    )

    private suspend fun buildRecipeRunContext(
        conversationId: Uuid,
        runId: String?,
        settings: Settings,
        conversation: Conversation,
        durablePath: Boolean,
        events: app.amber.core.agent.runtime.AgentEventWriter? = null,
        autoApprovedToolNames: Set<String>,
        executionPolicy: app.amber.feature.runtime.ExecutionPolicy =
            app.amber.feature.runtime.ExecutionPolicy.permissive(),
    ): app.amber.feature.recipe.RecipeRunContext? {
        if (toolDispatcher == null || recipeRegistry == null) return null
        if (capabilityFlags?.isEnabled(app.amber.core.settings.Capability.RecipeRuntime) != true) {
            return null
        }
        val recipeCapabilityState = if (
            capabilityFlags.isEnabled(app.amber.core.settings.Capability.CapabilityPermissions) == true &&
            capabilityPermissionStore != null
        ) {
            capabilityPermissionStore.state()
        } else {
            null
        }
        return app.amber.feature.recipe.RecipeRunContext(
            installed = recipeRegistry.installed(),
            dispatcher = toolDispatcher,
            runId = runId,
            conversationId = conversationId.toString(),
            ledger = if (durablePath) toolEffectLedger else null,
            events = events.takeIf { durablePath },
            autoApproveTools = settings.agentRuntime.autoApproveAllToolCalls ||
                conversation.autoApproveToolCalls,
            autoApproveHighRiskTools = settings.agentRuntime.autoApproveHighRiskToolCalls,
            autoApprovedToolNames = autoApprovedToolNames,
            capabilityPermissions = recipeCapabilityState,
            approvalHistory = capabilityPermissionStore?.takeIf { recipeCapabilityState != null },
            permissionContext = app.amber.feature.runtime.CapabilityPermissionContext(
                assistantId = AMBER_AGENT_ID.toString(),
                conversationId = conversationId.toString(),
                sessionId = runId,
            ),
            executionPolicy = executionPolicy,
            installedProvider = { recipeRegistry.installedSnapshot() },
        )
    }

    private fun createRunTools(
        settings: Settings,
        conversationId: Uuid?,
        runId: String? = null,
        casAuditEnabled: Boolean = false,
        conversationProvider: (() -> Conversation)? = null,
        conversationQueueTools: List<Tool> = emptyList(),
        recipeContext: app.amber.feature.recipe.RecipeRunContext? = null,
        // P1-7: the parent run's sandbox policy — handed to SubAgentTools so
        // the children a (possibly narrowed) run starts stay under its sandbox.
        executionPolicy: app.amber.feature.runtime.ExecutionPolicy =
            app.amber.feature.runtime.ExecutionPolicy.permissive(),
        // P4-02: thread_graph_v2 gate, computed at the (suspend) call site.
        threadGraphEnabled: Boolean = false,
        // P4-03: js_cell_runtime gate, computed at the (suspend) call site.
        jsCellEnabled: Boolean = false,
    ): List<Tool> {
        // P2-04/P2-06/P2-07 audit sink: approval-history entries are recorded
        // only when the capability_permissions flag is on (same gate as the
        // P2-01 approval records). The flows still work flag-off — only the
        // audit trail is skipped (rollback rules §17.2 keep old entries).
        val casLedger = capabilityPermissionStore?.takeIf { casAuditEnabled }
            ?.let { app.amber.feature.runtime.CapabilityBackedCasLedger(it) }
        val soulTransaction = app.amber.feature.prompts.SoulImportTransaction(
            settingsStore = settingsStore,
            ledger = casLedger,
            previousStore = app.amber.feature.prompts.SoulPreviousStore(context),
        )
        val assistantLocalTools = localTools.getTools(
            options = AMBER_AGENT_LOCAL_TOOLS,
            conversationId = conversationId,
            runId = runId,
        )
        val themePackTools = themePackageManager?.let(::createThemePackTools).orEmpty()
        val mcpManagementTools = if (conversationId != null) {
            createMcpManagementTools(
                settingsStore = settingsStore,
                mcpManager = mcpManager,
                skillManager = skillManager,
                approvalLedger = capabilityPermissionStore
                    ?.let { app.amber.feature.runtime.CapabilityBackedCasLedger(it) },
            )
        } else {
            emptyList()
        }
        val includeWebViewFallbackGuidance = ToolProfileFilter
            .filter(assistantLocalTools, AMBER_AGENT_TOOL_PROFILE)
            .tools
            .any { it.name == WEBVIEW_SEARCH_OPEN_TOOL_NAME }
        val rawTools = buildList {
            if (settings.enableWebSearch) {
                addAll(
                    createSearchTools(
                        settings = settings,
                        includeWebViewFallbackGuidance = includeWebViewFallbackGuidance,
                        locale = context.appLocale(),
                    )
                )
            }
            addAll(assistantLocalTools)
            // Status is read-only and may be used for diagnosis; keep the
            // mutating import tool out of the raw/SubAgent catalog below.
            addAll(themePackTools.filter { it.name == TOOL_THEME_PACK_STATUS })
            addAll(
                createSkillTools(
                    enabledSkills = settings.enabledSkills,
                    allSkills = skillManager.listSkills(),
                    skillManager = skillManager,
                    settingsStore = settingsStore,
                    workspaceManager = workspaceManager,
                    casLedger = casLedger,
                    runId = runId,
                )
            )
            addAll(
                createMcpTools(
                    refs = mcpManager.getAllAvailableToolRefs(),
                    call = { ref, input ->
                        val toolCallId = activityStore.startTool(
                            toolName = "mcp__${ref.serverName}__${ref.toolName}",
                            title = context.getString(R.string.tool_activity_mcp_call),
                            inputPreview = input.toString(),
                            runtime = "MCP",
                        )
                        try {
                            val result = mcpManager.callToolByRef(ref, input)
                            activityStore.complete(toolCallId, result.toolOutputPreview())
                            result
                        } catch (error: Throwable) {
                            activityStore.fail(toolCallId, error)
                            throw error
                        }
                    },
                )
            )
            addAll(memoryToolFactory.createTools(settings, runId, casAuditEnabled))
            addAll(
                createSoulTools(
                    workspaceManager = workspaceManager,
                    transaction = soulTransaction,
                    runId = runId,
                )
            )
            if (conversationId != null) {
                addAll(
                    ConversationContextTools(
                        contextEngine = contextEngine,
                        conversationProvider = requireNotNull(conversationProvider),
                        settingsProvider = { settingsStore.settingsFlow.first() },
                        modelProvider = { settingsStore.settingsFlow.first().getCurrentChatModel() },
                    ).tools()
                )
                addAll(
                    ConversationHistoryTools(
                        conversationRepo = conversationRepo,
                        grantStore = sessionAccessGrantStore,
                    ).tools()
                )
                addAll(conversationQueueTools)
            }
            // P4-03: persistent JS cells (default OFF; first-version scope is
            // debug/advanced users). The tools are only exposed when the
            // js_cell_runtime capability flag is on; all sandbox limits,
            // whitelist enforcement and persistence live in JsCellRuntime.
            if (jsCellEnabled && jsCellRuntime != null) {
                addAll(app.amber.feature.jscell.createJsCellTools(jsCellRuntime, runId))
            }
            addAll(AgentTaskTools(agentTaskScheduler).tools())
        }
        val profileFilter = ToolProfileFilter.filter(rawTools, AMBER_AGENT_TOOL_PROFILE)
        val profiledRawTools = profileFilter.tools
        val baseRegistry = ToolRegistry.from(profiledRawTools)
        val baseTools = baseRegistry.tools() +
            localTools.registryIntrospectionTools(baseRegistry)
        // SSH profile writers change the credentials behind every remote shell,
        // so they stay main-conversation-only. A subagent inherits the parent
        // catalog through parentToolsForRun, which is why the writers have to
        // leave the subagent's own catalog too — otherwise a child run could
        // still reach them through its parent list. The read-only listing stays
        // available to subagents.
        val subAgentCatalogTools = profiledRawTools.filterNot { it.name in SSH_PROFILE_WRITE_TOOL_NAMES }
        val subAgentParentTools = baseTools.filterNot { it.name in SSH_PROFILE_WRITE_TOOL_NAMES }
        val subAgentRawTools = if (conversationId != null && settings.agentRuntime.subAgent.enabled) {
            subAgentCatalogTools + SubAgentTools(
                subAgentManager = subAgentManager,
                parentConversationId = conversationId,
                parentRunId = runId,
                parentPolicy = executionPolicy,
                parentToolsProvider = { subAgentParentTools },
                // A subagent receives the same logical catalog, but WebMount tools must bind
                // their lease to the internally assigned child generation scope. Rebuild only
                // the local-tool entries through the existing scoped factory; all other parent
                // tool closures keep their current identity and no model-supplied identity is trusted.
                parentToolsForRun = { childScopeId ->
                    val childScopedLocalTools = localTools.getTools(
                        options = AMBER_AGENT_LOCAL_TOOLS,
                        conversationId = conversationId,
                        runId = childScopeId,
                    ).associateBy { it.name }
                    subAgentParentTools.map { childScopedLocalTools[it.name] ?: it }
                },
                onRunFinished = { childScopeId, reason, preservePendingHandoff ->
                    localTools.endWebMountRun(
                        runId = childScopeId,
                        conversationId = conversationId.toString(),
                        reason = reason,
                        preservePendingHandoff = preservePendingHandoff,
                    )
                },
                // P4-02: thread_graph_v2 gate — off keeps the legacy tool set
                // (no subagent_followup / send_message / interrupt).
                threadGraphEnabled = threadGraphEnabled,
                displayLocalizer = AppSubAgentDisplayLocalizer(context),
            ).tools()
        } else {
            profiledRawTools
        }
        val augmentedRawTools = if (settings.agentRuntime.modelCouncil.enabled) {
            subAgentRawTools + ModelCouncilTools(
                manager = modelCouncilManager,
                workspaceManager = workspaceManager,
            ).tools()
        } else {
            subAgentRawTools
        }
        val finalRawTools = ToolProfileFilter.filter(augmentedRawTools, AMBER_AGENT_TOOL_PROFILE).tools
        val registry = ToolRegistry.from(finalRawTools)
        // P4-01: dynamically registered declarative recipes. The installed
        // snapshot comes from the round context; recipe run tools capture
        // their definition at creation, so mid-round updates never change the
        // current round. Without the recipe_runtime flag the context is null
        // and no recipe tools / import entries exist (existing behavior).
        val recipeToolsProvider: () -> List<Tool> = if (recipeContext != null && recipeRegistry != null) {
            {
                app.amber.feature.recipe.RecipeToolFactory(recipeRegistry, json).createTools(
                    context = recipeContext,
                    casLedger = casLedger,
                    primitivesProvider = { registry },
                )
            }
        } else {
            { emptyList() }
        }
        val recipeTools = recipeToolsProvider()
        val finalRegistry = if (recipeTools.isEmpty()) registry else ToolRegistry.from(finalRawTools + recipeTools)
        // Provider 配置工具（provider_config_status / apply / refresh_models /
        // settings_set_model_slot）：仅前台 Chat 注册 —— conversationId != null
        // （createDebugRunTools 走 conversationId=null，smoke receiver 等后台路径
        // 拿不到）；且刻意加在 finalRegistry 之后、不进入 profiledRawTools，
        // 因此 SubAgent 的 parentTools（来自 profiledRawTools）与 allowlist
        // 校验都不可达这四把工具。但受限 toolProfile（MINIMAL / WEB_READ 等）
        // 同样会过滤这四把工具 —— provider_config_apply 等写工具不得绕过
        // ToolProfileFilter 出现在受限 profile 的会话里。
        val providerConfigTools = if (conversationId != null && secretStore != null) {
            createProviderConfigTools(
                settingsStore = settingsStore,
                secretStore = secretStore,
                providerCatalog = providerCatalog,
                googleProvider = googleProvider,
                openAICodexAuthStore = openAICodexAuthStore,
                grokAuthStore = grokAuthStore,
                antigravityAuthStore = antigravityAuthStore,
            )
        } else {
            emptyList()
        }
        val profiledProviderConfigTools = ToolProfileFilter
            .filter(providerConfigTools, AMBER_AGENT_TOOL_PROFILE)
            .tools
        val effectiveRegistry = if (profiledProviderConfigTools.isEmpty()) {
            finalRegistry
        } else {
            ToolRegistry.from(finalRawTools + recipeTools + profiledProviderConfigTools)
        }
        // Keep theme tools at the same foreground-only boundary as provider
        // config tools: they must not enter profiledRawTools or SubAgent tools.
        val profiledThemePackTools = if (conversationId != null) {
            ToolProfileFilter
                .filter(themePackTools.filter { it.name == TOOL_THEME_PACK_IMPORT }, AMBER_AGENT_TOOL_PROFILE)
                .tools
        } else {
            emptyList()
        }
        val profiledMcpManagementTools = ToolProfileFilter
            .filter(mcpManagementTools, AMBER_AGENT_TOOL_PROFILE)
            .tools
        val foregroundOnlyTools = profiledProviderConfigTools +
            profiledThemePackTools +
            profiledMcpManagementTools
        val foregroundRegistry = if (foregroundOnlyTools.isEmpty()) {
            effectiveRegistry
        } else {
            ToolRegistry.from(
                finalRawTools + recipeTools + foregroundOnlyTools
            )
        }
        val toolSearch = createToolSearchTool(
            foregroundRegistry,
            profile = AMBER_AGENT_TOOL_PROFILE,
            registryProvider = {
                ToolRegistry.from(
                    finalRawTools +
                        recipeToolsProvider() +
                        profiledProviderConfigTools +
                        profiledThemePackTools +
                        profiledMcpManagementTools
                )
            },
            semanticSearch = jevToolSemanticSearch?.forRun(runId),
        ).copy(dynamicToolsProvider = recipeContext?.let { recipeToolsProvider })
        val tools = foregroundRegistry.tools() +
            toolSearch +
            localTools.registryIntrospectionTools(foregroundRegistry)
        return tools.scopedToConversation(conversationId)
    }

    private fun List<Tool>.scopedToConversation(conversationId: Uuid?): List<Tool> {
        val scopeId = conversationId?.toString() ?: return this
        return map { tool ->
            tool.copy(
                execute = { input ->
                    activityStore.withConversation(scopeId) {
                        tool.execute(input)
                    }
                }
            )
        }
    }

}

private fun List<UIMessagePart>.toolOutputPreview(): String =
    joinToString("\n") { part ->
        when (part) {
            is UIMessagePart.Text -> part.text
            else -> part.toString()
        }
    }.takeLast(1_600)
