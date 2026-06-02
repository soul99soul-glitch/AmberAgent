package app.amber.core.settings

import android.content.Context
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.map
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import app.amber.ai.core.MessageRole
import app.amber.ai.core.ReasoningLevel
import app.amber.ai.provider.Model
import app.amber.ai.provider.ModelType
import app.amber.ai.provider.ProviderSetting
import app.amber.ai.provider.hasUsableAuth
import app.amber.ai.registry.ModelRegistry
import app.amber.core.ai.mcp.McpServerConfig
import app.amber.core.ai.prompts.DEFAULT_COMPRESS_PROMPT
import app.amber.core.ai.prompts.DEFAULT_OCR_PROMPT
import app.amber.core.ai.prompts.DEFAULT_SUGGESTION_PROMPT
import app.amber.core.ai.prompts.DEFAULT_TITLE_PROMPT
import app.amber.core.ai.prompts.LEARNING_MODE_PROMPT
import app.amber.core.model.LocalToolOption
import app.amber.feature.live.LiveModeSetting
import app.amber.feature.modelcouncil.ModelCouncilRuntimeSetting
import app.amber.feature.office.FeishuOfficeEnhancementSetting
import app.amber.feature.board.TodayBoardSetting
import app.amber.feature.subagent.SubAgentRuntimeSetting
import app.amber.feature.terminal.TerminalRuntimeKind
import app.amber.core.ai.GenerationRetrySetting
import app.amber.core.context.CompactPolicy
import app.amber.core.memory.model.MemoryRecallSetting
import app.amber.core.memory.model.MemoryWorkerSetting
import app.amber.core.model.Assistant
import app.amber.core.model.Avatar
import app.amber.core.model.DEFAULT_ASSISTANT_ID
import app.amber.core.model.InjectionPosition
import app.amber.core.model.Lorebook
import app.amber.core.model.PromptInjection
import app.amber.core.model.QuickMessage
import app.amber.core.model.Tag
import app.amber.core.sync.core.SyncSettings
import app.amber.core.sync.s3.S3Config
// PresetThemes lives in :app feature/ui/theme; we use only its first
// id as default — hardcoded to "amberagent_clash" to keep PreferencesStore
// free of UI/Compose-flavored dependencies. The themeId field stores a
// String anyway, so no behavior change.
import app.amber.core.agent.utils.JsonInstant
import app.amber.search.SearchCommonOptions
import app.amber.search.SearchServiceOptions
import app.amber.tts.provider.TTSProviderSetting
import kotlin.uuid.Uuid


// Default theme id seeded into freshly-initialized Settings. Must match
// AmberAgentClashThemePreset.id in :app feature/ui/theme/presets — pinned
// as a string here so PreferencesStore stays free of the Compose-flavored
// PresetThemes registry.
const val DEFAULT_PRESET_THEME_ID = "amberagent_clash"

val Context.settingsStore by preferencesDataStore(
    name = "settings",
    produceMigrations = { emptyList() }
)


@Serializable
data class Settings(
    @Transient
    val init: Boolean = false,
    val dynamicColor: Boolean = false,
    val themeId: String = DEFAULT_PRESET_THEME_ID,
    val developerMode: Boolean = false,
    val displaySetting: DisplaySetting = DisplaySetting(),
    val enableWebSearch: Boolean = false,
    val favoriteModels: List<Uuid> = emptyList(),
    val chatModelId: Uuid = Uuid.random(),
    val titleModelId: Uuid = Uuid.random(),
    val imageGenerationModelId: Uuid = Uuid.random(),
    val titlePrompt: String = DEFAULT_TITLE_PROMPT,
    val suggestionModelId: Uuid = Uuid.random(),
    val suggestionPrompt: String = DEFAULT_SUGGESTION_PROMPT,
    val ocrModelId: Uuid = Uuid.random(),
    val ocrPrompt: String = DEFAULT_OCR_PROMPT,
    val compressModelId: Uuid = Uuid.random(),
    val compressPrompt: String = DEFAULT_COMPRESS_PROMPT,
    val modelGroupSessionDefaults: List<ModelGroupSessionDefault> = emptyList(),
    val assistantId: Uuid = DEFAULT_ASSISTANT_ID,
    val providers: List<ProviderSetting> = DEFAULT_PROVIDERS,
    val assistants: List<Assistant> = DEFAULT_ASSISTANTS,
    val assistantTags: List<Tag> = emptyList(),
    val searchServices: List<SearchServiceOptions> = listOf(SearchServiceOptions.DEFAULT),
    val searchCommonOptions: SearchCommonOptions = SearchCommonOptions(),
    val searchServiceSelected: Int = 0,
    val searchEnabledServiceIds: List<Uuid> = searchServices.take(1).map { it.id },
    val searchBuiltinDuckDuckGoEnabled: Boolean = true,
    val searchBuiltinBingEnabled: Boolean = true,
    val searchBuiltinJinaEnabled: Boolean = true,
    val searchBuiltinWikipediaEnabled: Boolean = true,
    val searchBuiltinHackerNewsEnabled: Boolean = true,
    val searchGoogleWebViewFallbackEnabled: Boolean = true,
    val mcpServers: List<McpServerConfig> = emptyList(),
    val webDavConfig: WebDavConfig = WebDavConfig(),
    val s3Config: S3Config = S3Config(),
    val ttsProviders: List<TTSProviderSetting> = DEFAULT_TTS_PROVIDERS,
    val selectedTTSProviderId: Uuid = DEFAULT_SYSTEM_TTS_ID,
    val modeInjections: List<PromptInjection.ModeInjection> = DEFAULT_MODE_INJECTIONS,
    val lorebooks: List<Lorebook> = emptyList(),
    val quickMessages: List<QuickMessage> = emptyList(),
    val agentRuntime: AgentRuntimeSetting = AgentRuntimeSetting(),
    val backupReminderConfig: BackupReminderConfig = BackupReminderConfig(),
    val syncSettings: SyncSettings = SyncSettings(),
    val launchCount: Int = 0,
    val sponsorAlertDismissedAt: Int = 0,
    /**
     * [Review fix #3] Bumped once after the per-load image-model backfill
     * (gpt-image-2 into OpenAI provider, gemini-3.1-flash-image-preview into
     * Gemini provider) runs successfully. Without this, deleting the seeded
     * model would resurrect it on the next save. Persisted as
     * [PreferencesStore.SEEDED_IMAGE_MODELS_V1]; not user-facing.
     */
    val imageModelsSeededVersion: Int = 0,
    /**
     * Same one-shot pattern, for the visual-routing slash commands
     * (/draw / /svg / /diagram / /slide). Bumped as QuickMessages are
     * appended to settings.quickMessages and subscribed by every assistant.
     * Persisted as [PreferencesStore.SEEDED_ROUTING_QUICK_MESSAGES_V1].
     */
    val routingQuickMessagesSeededVersion: Int = 0,
) {
    companion object {
        // 构造一个用于初始化的settings, 但它不能用于保存，防止使用初始值存储
        fun dummy() = Settings(init = true)
    }
}

@Serializable
data class ModelGroupSessionDefault(
    val groupId: String,
    val reasoningLevel: ReasoningLevel = ReasoningLevel.AUTO,
    val contextMessageSize: Int = 0,
    val maxTokens: Int? = null,
)

data class ResolvedSessionDefaults(
    val reasoningLevel: ReasoningLevel,
    val contextMessageSize: Int,
    val maxTokens: Int?,
)

@Serializable
data class AgentRuntimeSetting(
    val enableCoreMemory: Boolean = true,
    val enableShortTermMemory: Boolean = true,
    val enableLongTermMemory: Boolean = true,
    val enableRecentChatsReference: Boolean = true,
    val enableTimeReminder: Boolean = false,
    val agentSoulMarkdown: String = DEFAULT_AGENT_SOUL_MARKDOWN,
    val operationPreviewMode: AgentOperationPreviewMode = AgentOperationPreviewMode.ALWAYS,
    val generativeUi: GenerativeUiSetting = GenerativeUiSetting(),
    val enableLiveStatusNotification: Boolean = true,
    val hideSensitiveLiveStatus: Boolean = true,
    val liveMode: LiveModeSetting = LiveModeSetting(),
    val maxToolLoopSteps: Int = DEFAULT_AGENT_MAX_TOOL_LOOP_STEPS,
    val autoApproveAllToolCalls: Boolean = false,
    val autoApproveHighRiskToolCalls: Boolean = false,
    val terminalDefaultRuntime: TerminalRuntimeKind = TerminalRuntimeKind.BUILTIN_ALPINE,
    val terminalMaxConcurrentJobs: Int = 1,
    val terminalOutputTailChars: Int = 256 * 1024,
    val terminalInstallTimeoutMs: Long = 15 * 60_000L,
    val feishuOfficeEnhancement: FeishuOfficeEnhancementSetting = FeishuOfficeEnhancementSetting(),
    val todayBoard: TodayBoardSetting = TodayBoardSetting(),
    val miniApp: MiniAppSetting = MiniAppSetting(),
    val contextCompaction: ContextCompactionSetting = ContextCompactionSetting(),
    val memoryRecall: MemoryRecallSetting = MemoryRecallSetting(),
    val memoryWorker: MemoryWorkerSetting = MemoryWorkerSetting(),
    val subAgent: SubAgentRuntimeSetting = SubAgentRuntimeSetting(),
    val modelCouncil: ModelCouncilRuntimeSetting = ModelCouncilRuntimeSetting(),
    val externalFileAccess: ExternalFileAccessSetting = ExternalFileAccessSetting(),
    val harnessDebug: HarnessDebugSetting = HarnessDebugSetting(),
    val speculativeToolExecution: SpeculativeToolExecutionSetting = SpeculativeToolExecutionSetting(),
    val generationRetry: GenerationRetrySetting = GenerationRetrySetting(),
    val keepGenerationAliveInBackground: Boolean = true,
)

@Serializable
data class MiniAppSetting(
    val enabled: Boolean = true,
    val networkEnabled: Boolean = true,
    val externalImagesEnabled: Boolean = true,
    val searchEnabled: Boolean = true,
    val clipboardCopyEnabled: Boolean = true,
    val boardSummaryUpdateEnabled: Boolean = true,
    val hostContextEnabled: Boolean = false,
    val hostWriteEnabled: Boolean = false,
    val aiEnabled: Boolean = false,
    val sharedStoreEnabled: Boolean = false,
    val eventBusEnabled: Boolean = false,
    val launchEnabled: Boolean = false,
    val sensorEnabled: Boolean = false,
    val locationEnabled: Boolean = false,
    val clipboardReadEnabled: Boolean = false,
    val webViewDebugEnabled: Boolean = false,
    val showSourceButton: Boolean = true,
)

@Serializable
data class GenerativeUiSetting(
    val enabled: Boolean = true,
    val maxWidgetCodeChars: Int = 12_000,
    val maxWidgetHeightDp: Int = 720,
    val enableActions: Boolean = true,
    val enableStructuredRenderers: Boolean = true,
    val enableInteractiveCharts: Boolean = true,
    val slidesMagazineFontPack: String = "source-han-serif-sc-regular",
    val slidesSwissFontPack: String = "source-han-sans-sc-regular",
)

@Serializable
data class HarnessDebugSetting(
    val showPermissionReasons: Boolean = false,
    val showParallelBatches: Boolean = false,
    val showCapabilitySnapshotSummary: Boolean = false,
)

@Serializable
data class SpeculativeToolExecutionSetting(
    val enabled: Boolean = false,
    val maxConcurrentTools: Int = 4,
)

@Serializable
data class ExternalFileAccessSetting(
    val enabled: Boolean = false,
    val roots: List<String> = emptyList(),
)

@Serializable
data class ContextCompactionSetting(
    val enabled: Boolean = true,
    val notifyOnly: Boolean = false,
    val precompactRatio: Float = 0.70f,
    val forceRatio: Float = 0.85f,
    val keepRecentTurns: Int = 8,
    val maxSummaryTokens: Int = 2_000,
)

fun ContextCompactionSetting.toCompactPolicy() = CompactPolicy(
    enabled = enabled,
    notifyOnly = notifyOnly,
    precompactRatio = precompactRatio,
    forceRatio = forceRatio,
    keepRecentTurns = keepRecentTurns,
    maxSummaryTokens = maxSummaryTokens,
)

@Serializable
enum class AgentOperationPreviewMode {
    @SerialName("always")
    ALWAYS,

    @SerialName("auto")
    AUTO,

    @SerialName("hidden")
    HIDDEN,
}

const val MIN_AGENT_TOOL_LOOP_STEPS = 16
const val DEFAULT_AGENT_MAX_TOOL_LOOP_STEPS = 256
const val MAX_AGENT_TOOL_LOOP_STEPS = 512

const val DEFAULT_AGENT_SOUL_MARKDOWN = """
# agents.md

You are AmberAgent, an agent-only Android assistant.

- Work toward the user's goal by planning briefly, using available tools, checking results, and continuing until the task is completed or you need explicit user input.
- Prefer the authorized /workspace for file work. Use terminal, system access, and screen automation tools only when they are necessary and allowed by the current trust policy.
- When calling a tool, include `display_title` when the schema allows it: a short Chinese action phrase for this exact step, such as "写入第一卷", "合并最终文件", or "验证文件结构". Do not repeat the raw tool name.
- For long terminal commands, package installation, downloads, or commands with large output, prefer terminal_job_start/read/wait/stop or terminal_install_packages instead of blocking on terminal_execute. If a long job must read or write the user workspace, pass sync_workspace=true or call terminal_workspace_flush after it finishes.
- Treat memory as layered:
  - Core memory: durable behavior rules, identity, and explicit facts the user wants AmberAgent to carry into every conversation.
  - Short-term memory: concise summaries of recent tasks or active projects that help continuity.
  - Long-term memory: stable user preferences, recurring interests, plans, and factual context worth preserving beyond a single day.
- Do not store sensitive personal data unless the user explicitly asks. Merge similar memories instead of creating duplicates.
- If you are unsure which skills are installed or enabled, call skills_list before use_skill.
- If the user asks for iCloud or Obsidian files, call icloud_status first. Use icloud_list/read/search only after the experimental iCloud Drive mount reports read access; use icloud_write only after write access is enabled.
- If the user asks about 小米办公 Pro / 飞书办公 work context, call officepro_status or officepro_dashboard first. Use officepro_daily_radar for today's work radar, officepro_project_briefing for Q 代/MiClaw/Lhasa-style project context, officepro_document_warroom for document review drafts, officepro_open_items_radar / officepro_meeting_closure for follow-up closure, and officepro_project_context/report/list/update for local project knowledge packs. Use officepro_create_task_draft, officepro_create_base_record_draft, and officepro_reply_draft only to produce drafts; never send, comment, create tasks, or write Base records without a separate approval and a real Feishu MCP/Skill write tool. Use officepro_capture_context or officepro_context_digest for lower-level read-first analysis, and officepro_make_report when the user wants a workspace Markdown draft. For ordinary hidden tool discovery, call tool_search first; tools_list is catalog/debug only and does not make hidden tools callable. If Feishu MCP tools are available, call mcp_list(include_tools=true) to discover server/tool names, then use mcp_call_tool for a specific cloud document, calendar, task, meeting, IM, Base, or wiki operation. Only use officepro_open/search after the user approves opening or driving the office app.
- If the user asks to recall, compare, or summarize other sessions, use session_list/session_search first. Read full historical content only with session_read/session_expand after approval or a valid session grant. For many sessions, start multiple historian subagents (set task.context to mode=read or mode=mine) with separate source_session_ids shards, then run one historian (mode=synthesize) over their source-backed summaries.
- If subagent tools are available, before the first subagent_start in a session call subagent_list once to read each role's routing hints (when to delegate, when not to). Then use subagents only when the task is complex, clearly bounded, and benefits from isolated context, a stronger/cheaper model, or parallel viewpoints. Simple linear tasks must stay in the main Agent. Subagent results are evidence for the main Agent, not final truth.
- When you are waiting for a subagent (subagent_wait), pass wait_timeout_ms=60000 and call wait again immediately if it is still running — do NOT spend a reasoning step between waits to narrate "still running, let me wait again". That just clutters the timeline and burns tokens. Reason only after the run completes (or fails).
- For webpage tasks:
  - When the user asks to open, browse, view, inspect, or visually verify a webpage, call webview_open early so the live preview shows the page.
  - After webview_open, call webview_wait_for_load or webview_read(wait_timeout_ms=...) before relying on the current page title, readable text, or links.
  - Use search_web or scrape_web when you need search results or deeper text extraction.
  - Do not try to launch Android System WebView as a standalone app.
"""

@Serializable
enum class ChatFontFamily {
    @SerialName("default")
    DEFAULT,
    @SerialName("serif")
    SERIF,
    @SerialName("monospace")
    MONOSPACE,
}

@Serializable
data class DisplaySetting(
    val userAvatar: Avatar = Avatar.Dummy,
    val userNickname: String = "",
    val useAppIconStyleLoadingIndicator: Boolean = true,
    val showUserAvatar: Boolean = true,
    val showAssistantBubble: Boolean = false,
    val showModelIcon: Boolean = true,
    val showModelName: Boolean = true,
    val showDateBelowName: Boolean = false,
    val showThinkingContent: Boolean = true,
    val autoCloseThinking: Boolean = true,
    val showUpdates: Boolean = true,
    val showMessageJumper: Boolean = true,
    val messageJumperOnLeft: Boolean = false,
    val fontSizeRatio: Float = 1.0f,
    val enableMessageGenerationHapticEffect: Boolean = false,
    val skipCropImage: Boolean = false,
    val enableNotificationOnMessageGeneration: Boolean = false,
    val enableLiveUpdateNotification: Boolean = false,
    val codeBlockAutoWrap: Boolean = false,
    val codeBlockAutoCollapse: Boolean = false,
    val showLineNumbers: Boolean = false,
    val ttsOnlyReadQuoted: Boolean = false,
    val autoPlayTTSAfterGeneration: Boolean = false,
    val pasteLongTextAsFile: Boolean = false,
    val pasteLongTextThreshold: Int = 1000,
    val sendOnEnter: Boolean = false,
    val enableAutoScroll: Boolean = true,
    val showBottomFollowAnimation: Boolean = true,
    val enableLatexRendering: Boolean = true,
    val enableBlurEffect: Boolean = false,
    val chatFontFamily: ChatFontFamily = ChatFontFamily.DEFAULT,
    val enableVolumeKeyScroll: Boolean = false,
    val volumeKeyScrollRatio: Float = 1.0f,
    // V3：用户在设置里手选的聊天主题 key。浅色/深色模式各自只应用匹配模式的主题。
    val chatThemeChoice: String = "WHISPER",
)

@Serializable
data class WebDavConfig(
    val url: String = "",
    val username: String = "",
    val password: String = "",
    val path: String = "amber_agent_backups",
    val items: List<BackupItem> = listOf(
        BackupItem.DATABASE,
        BackupItem.FILES
    ),
) {
    @Serializable
    enum class BackupItem {
        DATABASE,
        FILES,
    }
}

@Serializable
data class BackupReminderConfig(
    val enabled: Boolean = false,
    val intervalDays: Int = 7,
    val lastBackupTime: Long = 0L,
)

fun Settings.isNotConfigured() = providers.all { it.models.isEmpty() }

fun Settings.findModelById(uuid: Uuid): Model? {
    return this.providers.findModelById(uuid)
}

fun List<ProviderSetting>.findModelById(uuid: Uuid): Model? {
    this.forEach { setting ->
        setting.models.forEach { model ->
            if (model.id == uuid) {
                return model
            }
        }
    }
    return null
}

fun Settings.getModelGroupSessionDefault(model: Model): ModelGroupSessionDefault? {
    val groupId = ModelRegistry.sessionDefaultGroupForModel(model.modelId)?.id ?: return null
    return modelGroupSessionDefaults.firstOrNull { it.groupId == groupId }
}

fun Settings.defaultReasoningLevelForModel(model: Model): ReasoningLevel {
    val groupDefault = getModelGroupSessionDefault(model)?.reasoningLevel
    return if (groupDefault != null && groupDefault != ReasoningLevel.AUTO) {
        groupDefault
    } else {
        model.defaultReasoningLevel()
    }
}

fun Model.defaultReasoningLevel(): ReasoningLevel {
    val id = modelId.lowercase()
    return when {
        id.contains("gpt") ||
            id.contains("codex") ||
            Regex("\\bo\\d+").containsMatchIn(id) -> ReasoningLevel.MEDIUM
        id.contains("deepseek") -> ReasoningLevel.HIGH
        else -> ReasoningLevel.AUTO
    }
}

fun Settings.resolveSessionDefaults(
    assistant: Assistant,
    model: Model,
): ResolvedSessionDefaults {
    val groupDefault = getModelGroupSessionDefault(model)
    return ResolvedSessionDefaults(
        reasoningLevel = if (assistant.reasoningLevel == ReasoningLevel.AUTO) {
            defaultReasoningLevelForModel(model)
        } else {
            assistant.reasoningLevel
        },
        contextMessageSize = if (assistant.contextMessageSize == 0) {
            groupDefault?.contextMessageSize ?: assistant.contextMessageSize
        } else {
            assistant.contextMessageSize
        },
        maxTokens = assistant.maxTokens ?: groupDefault?.maxTokens,
    )
}

fun Settings.getCurrentChatModel(): Model? {
    return findModelById(this.getCurrentAssistant().chatModelId ?: this.chatModelId)
}

/**
 * Resolve the image-generation model for the current assistant: per-assistant
 * override first, then global setting. Returns null when neither is set, in
 * which case the generate_image tool is not exposed to the main chat model.
 *
 * V3 修: 全局 imageGenerationModelId 是 non-null Uuid (默认 Uuid.random()), 用户从未设置时
 * 落到一个永远查不到 model 的随机 id 上, 然后 generate_image 工具一律抛 "not configured".
 * 同时 SettingModelPage 的 onClear 把它重置为 DEFAULT_AUTO_MODEL_ID (sentinel uuid).
 * 这两种情形 (随机 init uuid / DEFAULT_AUTO_MODEL_ID) 都视为"未配置", 显式返回 null,
 * 让 ImageGenTool 走兜底: 找第一个 IMAGE-type 模型, 否则才报错.
 */
fun Settings.getCurrentImageGenerationModel(): Model? {
    // V3 review P3 #10: assistant override 与 global 都可能存 DEFAULT_AUTO_MODEL_ID sentinel
    // ("自动"语义) 或 stale Uuid. 优先级 = assistant 有效 id > global 有效 id > 兜底 IMAGE model.
    // 关键: resolveValid 必须 `type == IMAGE` 验证 — 用户在 picker 之外 (如手加 model 默认 CHAT)
    // 选了 chat-type id 时不要返回, 让兜底找内置 IMAGE-type (如 Codex OAuth 自带的 image model).
    // 这是用户报"deepseek/gpt5.5 都调不来 gpt-image-2"的根因 — 手加的 gpt-image-2 type=CHAT,
    // 命中第一/二级 → 当成"已配置"返回 → 但调 image API 失败.
    fun Uuid?.resolveValid(): Model? {
        if (this == null || this == DEFAULT_AUTO_MODEL_ID) return null
        return findModelById(this)?.takeIf { it.type == ModelType.IMAGE }
    }
    val assistantPick = this.getCurrentAssistant().imageGenerationModelId.resolveValid()
    if (assistantPick != null) return assistantPick
    val globalPick = this.imageGenerationModelId.resolveValid()
    if (globalPick != null) return globalPick
    // 兜底: 全局任一有可用 auth 的 provider 暴露的 IMAGE 类型模型. 跟 SettingModelPage
    // picker 同一份 hasUsableAuth 判定 (Codex review P2 修复: 之前只看 enabled 会把
    // seed 的 gpt-image-2 暴露给没配 OpenAI key 的 user, 触发 401).
    return this.providers
        .asSequence()
        .filter { it.hasUsableAuth() }
        .flatMap { it.models.asSequence() }
        .firstOrNull { it.type == ModelType.IMAGE }
}

fun Settings.resolveTaskChatModel(modelId: Uuid): Model? {
    return findModelById(modelId) ?: getCurrentChatModel()
}

fun Settings.getCurrentAssistant(): Assistant {
    return this.assistants.find { it.id == DEFAULT_ASSISTANT_ID }
        ?: this.assistants.find { it.id == assistantId }
        ?: this.assistants.first()
}

fun Settings.getAssistantById(id: Uuid): Assistant? {
    return this.assistants.find { it.id == id }
}

fun Settings.getQuickMessagesOfAssistant(assistant: Assistant) =
    quickMessages.filter { it.id in assistant.quickMessageIds }

fun Settings.getSelectedTTSProvider(): TTSProviderSetting? {
    return ttsProviders.find { it.id == selectedTTSProviderId } ?: ttsProviders.firstOrNull()
}

fun Model.findProvider(providers: List<ProviderSetting>, checkOverwrite: Boolean = true): ProviderSetting? {
    val provider = findModelProviderFromList(providers) ?: return null
    val providerOverwrite = this.providerOverwrite
    if (checkOverwrite && providerOverwrite != null) {
        return providerOverwrite.copyProvider(models = emptyList())
    }
    return provider
}

private fun Model.findModelProviderFromList(providers: List<ProviderSetting>): ProviderSetting? {
    providers.forEach { setting ->
        setting.models.forEach { model ->
            if (model.id == this.id) {
                return setting
            }
        }
    }
    return null
}

val DEFAULT_ASSISTANTS = listOf(
    Assistant(
        id = DEFAULT_ASSISTANT_ID,
        name = "AmberAgent",
        systemPrompt = """
            You are AmberAgent, an agent-only Android assistant.

            Work toward the user's goal by planning briefly, using available tools, checking results, and continuing until the task is completed or you need explicit user input.
            Prefer the authorized /workspace for file work. Use terminal and screen automation tools only when they are necessary and user-approved.
            If you are unsure which skills are installed or enabled, call skills_list before use_skill.
            If the user asks for iCloud or Obsidian files, call icloud_status first. Use icloud_list/read/search only after the experimental iCloud Drive mount reports read access; use icloud_write only after write access is enabled.
            For webpage tasks, call webview_open early when the user asks to open, browse, view, inspect, or visually verify a page. After webview_open, call webview_wait_for_load or webview_read(wait_timeout_ms=...) before relying on the opened page title, readable text, or links. Use search_web or scrape_web when you need search results or deeper extraction. Do not try to launch Android System WebView as a standalone app.
        """.trimIndent(),
        localTools = listOf(
            LocalToolOption.JavascriptEngine,
            LocalToolOption.TimeInfo,
            LocalToolOption.Clipboard,
            LocalToolOption.Tts,
            LocalToolOption.AskUser,
            LocalToolOption.WorkspaceFiles,
            LocalToolOption.Terminal,
            LocalToolOption.ScreenAutomation,
            LocalToolOption.SystemAccess,
            LocalToolOption.WebView,
            LocalToolOption.ICloudDrive,
        )
    ),
    Assistant(
        id = Uuid.parse("3d47790c-c415-4b90-9388-751128adb0a0"),
        name = "",
        systemPrompt = """
            You are a helpful assistant, called {{char}}, based on model {{model_name}}.

            ## Info
            - Time: {{cur_datetime}}
            - Locale: {{locale}}
            - Timezone: {{timezone}}
            - Device Info: {{device_info}}
            - System Version: {{system_version}}
            - User Nickname: {{user}}

            ## Hint
            - If the user does not specify a language, reply in the user's primary language.
            - Remember to use Markdown syntax for formatting, and use latex for mathematical expressions.
        """.trimIndent()
    ),
)

fun List<Assistant>.withAmberAgentAssistantBranding(): List<Assistant> = map { assistant ->
    if (assistant.id == DEFAULT_ASSISTANT_ID) {
        assistant.copy(
            name = if (assistant.name in setOf("", "Amberagent")) {
                "AmberAgent"
            } else {
                assistant.name
            },
            systemPrompt = assistant.systemPrompt.replace("Amberagent", "AmberAgent"),
            localTools = (assistant.localTools + AMBER_AGENT_REQUIRED_LOCAL_TOOLS).distinct(),
            enabledSkills = assistant.enabledSkills + AMBER_AGENT_REQUIRED_SKILLS,
        )
    } else {
        assistant
    }
}

private val AMBER_AGENT_REQUIRED_LOCAL_TOOLS = listOf(
    LocalToolOption.JavascriptEngine,
    LocalToolOption.TimeInfo,
    LocalToolOption.Clipboard,
    LocalToolOption.Tts,
    LocalToolOption.AskUser,
    LocalToolOption.WorkspaceFiles,
    LocalToolOption.Terminal,
    LocalToolOption.ScreenAutomation,
    LocalToolOption.SystemAccess,
    LocalToolOption.WebView,
    LocalToolOption.ICloudDrive,
)

private val AMBER_AGENT_REQUIRED_SKILLS = setOf("skill-creator", "会议准备", "监控文档")

val DEFAULT_SYSTEM_TTS_ID = Uuid.parse("026a01a2-c3a0-4fd5-8075-80e03bdef200")
val REMOVED_DEFAULT_TTS_PROVIDER_IDS = setOf(
    Uuid.parse("e36b22ef-ca82-40ab-9e70-60cad861911c"), // AiHubMix TTS
)
val DEFAULT_TTS_PROVIDERS = listOf(
    TTSProviderSetting.SystemTTS(
        id = DEFAULT_SYSTEM_TTS_ID,
        name = "",
    ),
)

val DEFAULT_ASSISTANTS_IDS = DEFAULT_ASSISTANTS.map { it.id }

val DEFAULT_MODE_INJECTIONS = listOf(
    PromptInjection.ModeInjection(
        id = Uuid.parse("b87eaf16-f5cd-4ac1-9e4f-b11ae3a61d74"),
        content = LEARNING_MODE_PROMPT,
        position = InjectionPosition.AFTER_SYSTEM_PROMPT,
        name = "Learning Mode"
    )
)
