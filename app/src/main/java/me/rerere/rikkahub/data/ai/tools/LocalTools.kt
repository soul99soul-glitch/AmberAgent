package me.rerere.rikkahub.data.ai.tools

import android.content.Context
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.event.AppEventBus
import me.rerere.rikkahub.data.agent.system.AgentPermissionBroker
import me.rerere.rikkahub.data.agent.tools.AgentCronTools
import me.rerere.rikkahub.data.agent.tools.FeishuOfficeTools
import me.rerere.rikkahub.data.agent.tools.ICloudDriveTools
import me.rerere.rikkahub.data.agent.webmount.core.WebMountManager
import me.rerere.rikkahub.data.agent.webmount.tools.WebMountPrimitiveTools
import me.rerere.rikkahub.data.agent.tools.ExternalFileTools
import me.rerere.rikkahub.data.agent.tools.ScreenAutomationTools
import me.rerere.rikkahub.data.agent.tools.SystemAccessTools
import me.rerere.rikkahub.data.agent.tools.TerminalTools
import me.rerere.rikkahub.data.agent.tools.ToolRegistry
import me.rerere.rikkahub.data.agent.tools.WorkspaceArtifactTools
import me.rerere.rikkahub.data.agent.tools.WorkspaceTools
import me.rerere.rikkahub.data.datastore.prefs.SettingsAggregator
import me.rerere.rikkahub.data.datastore.getCurrentImageGenerationModel
import me.rerere.rikkahub.data.repository.ImageGenerationRepository
import me.rerere.rikkahub.data.agent.webview.WebViewOperationStore
import kotlin.uuid.Uuid

@Serializable
sealed class LocalToolOption {
    @Serializable
    @SerialName("javascript_engine")
    data object JavascriptEngine : LocalToolOption()

    @Serializable
    @SerialName("time_info")
    data object TimeInfo : LocalToolOption()

    @Serializable
    @SerialName("clipboard")
    data object Clipboard : LocalToolOption()

    @Serializable
    @SerialName("tts")
    data object Tts : LocalToolOption()

    @Serializable
    @SerialName("ask_user")
    data object AskUser : LocalToolOption()

    @Serializable
    @SerialName("workspace_files")
    data object WorkspaceFiles : LocalToolOption()

    @Serializable
    @SerialName("terminal")
    data object Terminal : LocalToolOption()

    @Serializable
    @SerialName("screen_automation")
    data object ScreenAutomation : LocalToolOption()

    @Serializable
    @SerialName("system_access")
    data object SystemAccess : LocalToolOption()

    @Serializable
    @SerialName("webview")
    data object WebView : LocalToolOption()

    @Serializable
    @SerialName("icloud_drive")
    data object ICloudDrive : LocalToolOption()

    @Serializable
    @SerialName("webmount")
    data object WebMount : LocalToolOption()

    /**
     * Secondary toggle that enables [WebMountPrimitiveTools.evalTool] (`wm_eval`)
     * in addition to the safe primitives gated by [WebMount]. Default OFF.
     *
     * `wm_eval` runs arbitrary JavaScript inside a logged-in WebView origin —
     * it can read cookies / sessionStorage / localStorage, perform same-origin
     * fetches with credentials, and mutate the page. The framework routes it
     * through Tool.mandatoryApproval, so ordinary auto-approval cannot run it;
     * only the explicit high-risk auto-approval setting may bypass the prompt.
     * The conservative default is to keep the tool entirely out of the agent's
     * catalog unless the user opts in here.
     */
    @Serializable
    @SerialName("webmount_eval")
    data object WebMountEval : LocalToolOption()
}

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
    private val userSiteRegistry: me.rerere.rikkahub.data.agent.webmount.usersites.UserSiteRegistry,
    private val settingsStore: SettingsAggregator,
    private val imageGenerationRepository: ImageGenerationRepository,
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

    fun toolsListTool(registry: ToolRegistry): Tool =
        createToolsListTool(registry, permissionBroker)

    private val permissionsStatusTool by lazy { createPermissionsStatusTool(permissionBroker) }

    private val runPlanUpdateTool by lazy { createRunPlanUpdateTool() }

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
