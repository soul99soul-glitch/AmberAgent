package app.amber.core.ai.tools

import android.content.Context
import app.amber.ai.core.MessageRole
import app.amber.ai.core.Tool
import app.amber.ai.ui.UIMessagePart
import app.amber.core.model.LocalToolOption
import app.amber.core.event.AppEventBus
import app.amber.core.repository.ConversationRepository
import app.amber.feature.system.AgentPermissionBroker
import app.amber.feature.tools.AgentCronTools
import app.amber.feature.tools.ICloudDriveTools
import app.amber.feature.webmount.core.WebMountManager
import app.amber.feature.webmount.tools.WebMountPrimitiveTools
import app.amber.feature.webmount.tools.withWebMountScope
import app.amber.feature.tools.ExternalFileTools
import app.amber.feature.tools.ScreenAutomationTools
import app.amber.feature.tools.SystemAccessTools
import app.amber.feature.tools.TerminalTools
import app.amber.feature.tools.ToolRegistry
import app.amber.feature.tools.WorkspaceArtifactTools
import app.amber.feature.tools.WorkspaceTools
import app.amber.feature.reminder.ReminderTools
import app.amber.feature.prompts.AgentPromptConfigRepository
import app.amber.feature.board.hotlist.deepread.DeepReadPlaybookRepository
import app.amber.core.settings.prefs.SettingsAggregator
import app.amber.core.settings.getCurrentImageGenerationModel
import app.amber.core.utils.appLocale
import app.amber.core.repository.ImageGenerationRepository
import app.amber.feature.webview.WebViewOperationStore
import org.koin.core.context.GlobalContext
import kotlin.uuid.Uuid

class LocalTools(
    private val context: Context,
    private val eventBus: AppEventBus,
    private val workspaceTools: WorkspaceTools,
    private val terminalTools: TerminalTools,
    private val screenAutomationTools: ScreenAutomationTools,
    private val systemAccessTools: SystemAccessTools,
    private val workspaceArtifactTools: WorkspaceArtifactTools,
    private val externalFileTools: ExternalFileTools,
    private val permissionBroker: AgentPermissionBroker,
    private val webViewOperationStore: WebViewOperationStore,
    private val iCloudDriveTools: ICloudDriveTools,
    private val agentCronTools: AgentCronTools,
    private val webMountPrimitiveTools: WebMountPrimitiveTools,
    private val webMountManager: WebMountManager,
    private val userSiteRegistry: app.amber.feature.webmount.usersites.UserSiteRegistry,
    private val settingsStore: SettingsAggregator,
    private val imageGenerationRepository: ImageGenerationRepository,
    private val promptConfigRepository: AgentPromptConfigRepository,
    private val deepReadPlaybookRepository: DeepReadPlaybookRepository,
    private val conversationRepository: ConversationRepository,
    private val healthSummaryReader: app.amber.feature.health.HealthSummaryReader,
    private val reminderTools: ReminderTools,
) {
    val javascriptTool by lazy { createJavascriptTool() }

    val timeTool by lazy { createTimeTool(context.appLocale()) }

    val clipboardTool by lazy {
        createClipboardTool(context)
    }

    val webViewTool by lazy { createWebViewOpenTool(webViewOperationStore) }

    val webViewSearchOpenTool by lazy { createWebViewSearchOpenTool(webViewOperationStore) }

    val webViewReadTool by lazy { createWebViewReadTool(webViewOperationStore) }

    val webViewWaitForLoadTool by lazy { createWebViewWaitForLoadTool(webViewOperationStore) }

    val webViewFindTextTool by lazy { createWebViewFindTextTool(webViewOperationStore) }

    val webViewLinksTool by lazy { createWebViewLinksTool(webViewOperationStore) }

    val webViewOpenLinkTool by lazy { createWebViewOpenLinkTool(webViewOperationStore) }

    val askUserTool by lazy { createAskUserTool() }

    val deepReadOpenTool by lazy { createDeepReadOpenTool(eventBus) }

    private val deepReadPlaybookTools by lazy { DeepReadPlaybookTools(deepReadPlaybookRepository) }

    /**
     * Registry-introspection tools — the pair (`tools_list`, `tool_policy_explain`)
     * that lets the model enumerate or probe the runtime tool catalog. Built per
     * call because each `getTools(...)` invocation produces a fresh registry; the
     * pair is returned together because ChatService always wires them in the same
     * place and they share the same registry argument.
     */
    fun registryIntrospectionTools(registry: ToolRegistry): List<Tool> = listOf(
        createToolsListTool(registry, permissionBroker, context),
        createToolPolicyExplainTool(registry),
    )

    private val permissionsStatusTool by lazy { createPermissionsStatusTool(permissionBroker, context) }

    private val healthSummaryTool by lazy { createHealthSummaryTool(healthSummaryReader) }

    private val runPlanUpdateTool by lazy { createRunPlanUpdateTool() }

    private val agentPromptConfigTool by lazy {
        createAgentPromptConfigTool(settingsStore, promptConfigRepository)
    }

    private fun buildImageGenTool(conversationId: Uuid): Tool =
        createImageGenTool(
            conversationId = conversationId,
            settingsStore = settingsStore,
            imageGenerationRepository = imageGenerationRepository,
            // P6-02: for mode=edit the model cannot know local file URLs, so
            // the app resolves the source from the user's latest message (the
            // UI's 修改 entry sends text + the source image as an attachment).
            sourceImageResolver = { resolveLatestUserMessageImage(conversationId) },
        )

    /**
     * P6-02: the newest user message's last local image part (file:// URL).
     * This is the controlled attachment reference the edit path validates —
     * only images already in this conversation qualify.
     */
    private suspend fun resolveLatestUserMessageImage(conversationId: Uuid): String? {
        val conversation = conversationRepository.getConversationById(conversationId) ?: return null
        return conversation.messageNodes.asReversed().firstNotNullOfOrNull { node ->
            val message = node.messages.getOrNull(node.selectIndex) ?: return@firstNotNullOfOrNull null
            if (message.role != MessageRole.USER) return@firstNotNullOfOrNull null
            message.parts.filterIsInstance<UIMessagePart.Image>()
                .lastOrNull { it.url.startsWith("file://") && it.url.isNotBlank() }
                ?.url
        }
    }

    fun getTools(
        options: List<LocalToolOption>,
        conversationId: Uuid? = null,
        runId: String? = null,
    ): List<Tool> {
        val tools = mutableListOf<Tool>()
        if (options.contains(LocalToolOption.JavascriptEngine)) {
            tools.add(javascriptTool)
        }
        if (options.contains(LocalToolOption.TimeInfo)) {
            tools.add(timeTool)
        }
        if (options.contains(LocalToolOption.Clipboard)) {
            tools.add(clipboardTool)
        }
        if (options.contains(LocalToolOption.AskUser)) {
            tools.add(askUserTool)
        }
        if (options.contains(LocalToolOption.WorkspaceFiles)) {
            tools.addAll(workspaceTools.getTools())
            tools.addAll(workspaceArtifactTools.getTools())
            tools.addAll(externalFileTools.getTools())
        }
        if (options.contains(LocalToolOption.Terminal)) {
            tools.addAll(terminalTools.getTools())
        }
        if (options.contains(LocalToolOption.ScreenAutomation)) {
            tools.addAll(screenAutomationTools.getTools(runId = runId))
        }
        if (options.contains(LocalToolOption.SystemAccess)) {
            tools.addAll(systemAccessTools.getTools())
        }
        if (options.contains(LocalToolOption.WebView)) {
            tools.add(webViewTool)
            tools.add(webViewSearchOpenTool)
            tools.add(webViewWaitForLoadTool)
            tools.add(webViewReadTool)
            tools.add(webViewFindTextTool)
            tools.add(webViewLinksTool)
            tools.add(webViewOpenLinkTool)
        }
        if (options.contains(LocalToolOption.ICloudDrive)) {
            tools.addAll(iCloudDriveTools.getTools())
        }
        // WebMount is owned by the global toggles on its settings page. The
        // removed multi-assistant configuration must not bypass those toggles.
        val webMountActive = webMountManager.globalEnabled
        if (webMountActive) {
            // `wm_eval` is gated by the separate global WebMountEval toggle.
            val includeEval = webMountManager.evalEnabled
            tools.addAll(
                webMountPrimitiveTools.getTools(
                    includeEval = includeEval,
                    conversationId = conversationId?.toString(),
                    runId = runId,
                )
            )
            // Plan v2: adapter tools are gated by the user's site list.
            // If the user deleted a site (e.g. removed Bilibili), its adapter's
            // tools (`bilibili_*`) drop out of the agent catalog automatically.
            // The 7 seed sites are present by default after first launch, so
            // existing behaviour is preserved.
            val activeAdapterIds = userSiteRegistry.activeNativeAdapterIds()
            val gatedAdapterTools = webMountManager.allToolsByAdapter().asSequence()
                .filter { (adapterId, _) -> adapterId in activeAdapterIds }
                .flatMap { it.value.asSequence() }
                // Feishu's visible-page fallbacks use a pooled WebView too;
                // carry the same host-owned identity fields into those two
                // tools while leaving its OpenAPI-only tools unchanged.
                .map { tool ->
                    if (tool.name !in FEISHU_WEBMOUNT_SCOPED_TOOLS) {
                        tool
                    } else {
                        tool.copy(
                            execute = { input ->
                                tool.execute(input.withWebMountScope(conversationId?.toString(), runId))
                            },
                        )
                    }
                }
                .toList()
            tools.addAll(gatedAdapterTools)
        }
        tools.add(permissionsStatusTool)
        tools.add(healthSummaryTool)
        tools.addAll(reminderTools.getTools())
        tools.addAll(agentCronTools.getTools())
        tools.add(runPlanUpdateTool)
        tools.add(agentPromptConfigTool)
        tools.addAll(deepReadPlaybookTools.getTools())
        tools.add(deepReadOpenTool)

        // generate_image auto-appears whenever the global setting resolves to
        // a real image-gen model. The tool needs
        // a concrete conversationId to scope its file output, so we skip it
        // for the debug catalog path (conversationId == null).
        if (conversationId != null && settingsStore.settingsFlow.value.getCurrentImageGenerationModel() != null) {
            tools.add(buildImageGenTool(conversationId))
        }

        return tools
    }

    /** Release any WebMount leases that outlive an individual tool call. */
    fun endWebMountRun(
        runId: String,
        conversationId: String?,
        reason: String = "run ended",
        preservePendingHandoff: Boolean = false,
    ) {
        webMountPrimitiveTools.endRun(
            runId = runId,
            conversationId = conversationId,
            reason = reason,
            preservePendingHandoff = preservePendingHandoff,
        )
    }

}

private val FEISHU_WEBMOUNT_SCOPED_TOOLS = setOf(
    "feishu_docs_snapshot",
    "feishu_docs_network_summary",
)
