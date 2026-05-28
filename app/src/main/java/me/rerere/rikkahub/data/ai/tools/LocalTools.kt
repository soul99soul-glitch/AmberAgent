package me.rerere.rikkahub.data.ai.tools

import android.content.Context
import me.rerere.ai.core.Tool
import me.rerere.rikkahub.data.event.AppEventBus
import app.amber.feature.system.AgentPermissionBroker
import app.amber.feature.tools.AgentCronTools
import app.amber.feature.tools.FeishuOfficeTools
import app.amber.feature.tools.ICloudDriveTools
import app.amber.feature.webmount.core.WebMountManager
import app.amber.feature.webmount.tools.WebMountPrimitiveTools
import app.amber.feature.tools.ExternalFileTools
import app.amber.feature.tools.ScreenAutomationTools
import app.amber.feature.tools.SystemAccessTools
import app.amber.feature.tools.TerminalTools
import app.amber.feature.tools.ToolRegistry
import app.amber.feature.tools.WorkspaceArtifactTools
import app.amber.feature.tools.WorkspaceTools
import app.amber.feature.prompts.AgentPromptConfigRepository
import app.amber.feature.board.hotlist.deepread.DeepReadPlaybookRepository
import me.rerere.rikkahub.data.datastore.prefs.SettingsAggregator
import me.rerere.rikkahub.data.datastore.getCurrentImageGenerationModel
import me.rerere.rikkahub.data.repository.ImageGenerationRepository
import app.amber.feature.webview.WebViewOperationStore
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
    private val feishuOfficeTools: FeishuOfficeTools,
    private val agentCronTools: AgentCronTools,
    private val webMountPrimitiveTools: WebMountPrimitiveTools,
    private val webMountManager: WebMountManager,
    private val userSiteRegistry: app.amber.feature.webmount.usersites.UserSiteRegistry,
    private val settingsStore: SettingsAggregator,
    private val imageGenerationRepository: ImageGenerationRepository,
    private val promptConfigRepository: AgentPromptConfigRepository,
    private val deepReadPlaybookRepository: DeepReadPlaybookRepository,
) {
    val javascriptTool by lazy { createJavascriptTool() }

    val timeTool by lazy { createTimeTool() }

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

    val ttsTool by lazy { createTtsTool(eventBus) }

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
        createToolsListTool(registry, permissionBroker),
        createToolPolicyExplainTool(registry),
    )

    private val permissionsStatusTool by lazy { createPermissionsStatusTool(permissionBroker) }

    private val runPlanUpdateTool by lazy { createRunPlanUpdateTool() }

    private val agentPromptConfigTool by lazy {
        createAgentPromptConfigTool(settingsStore, promptConfigRepository)
    }

    private fun buildImageGenTool(conversationId: Uuid): Tool =
        createImageGenTool(conversationId, settingsStore, imageGenerationRepository)

    fun getTools(options: List<LocalToolOption>, conversationId: Uuid? = null): List<Tool> {
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
        if (options.contains(LocalToolOption.Tts)) {
            tools.add(ttsTool)
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
            tools.addAll(screenAutomationTools.getTools())
        }
        if (options.contains(LocalToolOption.SystemAccess)) {
            tools.addAll(systemAccessTools.getTools())
        }
        if (options.contains(LocalToolOption.SystemAccess) ||
            settingsStore.settingsFlow.value.agentRuntime.feishuOfficeEnhancement.enabled
        ) {
            tools.addAll(feishuOfficeTools.getTools())
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
        // Phase 2 post-review UX fix: WebMount is now gated by a SINGLE
        // global toggle on the WebMount Stations setting page (matches the
        // iCloud / Feishu Office Enhancement experimental pattern). When
        // the global toggle is ON, every assistant gets the WebMount tools
        // automatically — no per-assistant config needed. Per-assistant
        // `LocalToolOption.WebMount` is preserved as a manual override
        // (someone can still opt one assistant in even when the global is
        // off), but it's no longer the primary discovery path.
        val webMountActive = webMountManager.globalEnabled || options.contains(LocalToolOption.WebMount)
        if (webMountActive) {
            // `wm_eval` is gated by the separate global WebMountEval toggle.
            // Per-assistant `LocalToolOption.WebMountEval` is kept as a manual
            // override (one assistant can have eval even when the global is off).
            val includeEval = webMountManager.evalEnabled || options.contains(LocalToolOption.WebMountEval)
            tools.addAll(webMountPrimitiveTools.getTools(includeEval = includeEval))
            // Plan v2: adapter tools are gated by the user's site list.
            // If the user deleted a site (e.g. removed Bilibili), its adapter's
            // tools (`bilibili_*`) drop out of the agent catalog automatically.
            // The 7 seed sites are present by default after first launch, so
            // existing behaviour is preserved.
            val activeAdapterIds = userSiteRegistry.activeNativeAdapterIds()
            val gatedAdapterTools = webMountManager.allToolsByAdapter().asSequence()
                .filter { (adapterId, _) -> adapterId in activeAdapterIds }
                .flatMap { it.value.asSequence() }
                .toList()
            tools.addAll(gatedAdapterTools)
        }
        tools.add(permissionsStatusTool)
        tools.addAll(agentCronTools.getTools())
        tools.add(runPlanUpdateTool)
        tools.add(agentPromptConfigTool)
        tools.addAll(deepReadPlaybookTools.getTools())
        tools.add(deepReadOpenTool)

        // generate_image auto-appears whenever the current assistant — or the
        // global setting — resolves to a real image-gen model. The tool needs
        // a concrete conversationId to scope its file output, so we skip it
        // for the debug catalog path (conversationId == null).
        if (conversationId != null && settingsStore.settingsFlow.value.getCurrentImageGenerationModel() != null) {
            tools.add(buildImageGenTool(conversationId))
        }

        return tools
    }

}
