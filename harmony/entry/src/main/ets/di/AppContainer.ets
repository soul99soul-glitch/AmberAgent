import { loadNovelModelDefaults, loadNovelPolishPreference } from '../platform_impl/NovelStandaloneDefaults.ets';
import { createEntryJevAutoApprovalReview } from '../platform_impl/JevAutoApprovalSupport.ets';
// AppContainer — 依赖注入容器(ArkTS 兼容版)
// 组装 deepread 逻辑层与平台实现。
// 平台实现均已真实接线(RcpHttpClient/RdbRepository/PreferencesStorage/
// OpenAiCompatibleAiClient/SearchRegistry/FileStore 等);配 API key 即可用。
// 通知当前为 hilog 降级实现(接真实 notificationManager 见完善计划 Phase 1)。

import type {
  RunManagerDeps, AiClient, UIMessage, Tool, ToolDefinition,
  DeepReadOutput, DeepReadScheduler, DeepReadCacheEntry, Observable, HttpClient, Storage,
  SchedulerRunOptions, DeepReadTemplateSnapshot, SearchHit,
  DeepReadRepository, SearchProviderRegistry, Notifier,
  SourcePrefetcher, FileStore, ModelRegistry, AbortSignalLike,
  ModelCouncilTextRunner, ModelCouncilRuntimeSetting, ModelCouncilSeat, ModelConfig,
  NovelProjectRepository, NovelModelTarget, NovelPolishEffects, DurablePolishJob,
  NovelStructuredTaskOptions,
  NovelRuntimeSnapshot,
  PolishChapterResult,
} from '@amber/deepread-domain';
import {
  isInterruptedPhase, createScheduler, createSourcePrefetcher,
  createStorageModelRegistry, createModelCouncilManager, ModelCouncilManager,
  createNovelCreation, NovelCreation, createFileNovelRepository, NovelAuditController,
  DeepReadPlaybookRepository,
  projectPolishOutcomes,
} from '@amber/deepread-domain';
// 真实平台实现 import
import { createRcpHttpClient } from '../platform_impl/RcpHttpClient.ets';
import { jevChoiceSelect, jevEvaluate } from '../platform_impl/JevSupport.ets';
import { initializeJevTriageHost } from '../platform_impl/JevTriageHost.ets';
import { createPreferencesStorage } from '../platform_impl/PreferencesStorage.ets';
import { createRdbRepository } from '../platform_impl/RdbRepository.ets';
import { createOpenAiCompatibleAiClient } from '../platform_impl/OpenAiCompatibleAiClient.ets';
import { DeepReadRunRepositoryAdapter } from '../platform_impl/DeepReadRunRepository.ets';
import { DeepReadArtifactStore } from '../platform_impl/DeepReadArtifactStore.ets';
import {
  setDeepReadGenerationActivity, beginDeepReadGenerationStep, observeDeepReadGenerationSnapshot,
} from '../platform_impl/DeepReadGenerationActivity.ets';
import { createTavilyAndFallbackRegistry } from '../platform_impl/SearchRegistry.ets';
import { captureDeepReadTemplate } from '../platform_impl/DeepReadTemplate.ets';
import { getProductKind } from '../platform_impl/ProductIdentity.ets';
import { searchDeepReadGoogle } from '../platform_impl/DeepReadGoogleSearch.ets';
import { createNotificationNotifier, notifyCronRunning, notifyCronCompleted, notifyCronFailed } from '../platform_impl/NotificationNotifier.ets';
import { createFileStore } from '../platform_impl/FileStore.ets';
import { getChatKeyRoulette } from '../platform_impl/KeyRouletteSupport.ets';
import { getValidCodexToken } from '../platform_impl/CodexOAuthClient.ets';
import { getValidGrokToken } from '../platform_impl/GrokOAuthClient.ets';
import { createModelCouncilRunner } from '../platform_impl/ModelCouncilRunner.ets';
import { prepareCouncilToolRunner } from '../platform_impl/CouncilRunPreparation.ets';
import { mutateAssistant } from '../platform_impl/AssistantMutation.ets';
import { getMemoryToolDeps } from '../platform_impl/MemoryStore.ets';
import { createEntryNovelChatRunAdapter } from '../platform_impl/NovelChatRunAdapter.ets';
import {
  scheduleNovelPolishWork, cancelNovelPolishWork,
} from '../platform_impl/NovelPolishWorkScheduler.ets';
import { publishNovelJobNotification } from '../platform_impl/NotificationNotifier.ets';
import { createEntryAbortController } from '../platform_impl/EntryAbortController.ets';
import {
  createNovelWorkspaceService, NovelWorkspaceService,
} from '../platform_impl/NovelWorkspaceService.ets';
import { hilog } from '@kit.PerformanceAnalysisKit';
import type { Context, common } from '@kit.AbilityKit';
import type {
  ConversationRepository, KeyValueStore, Assistant, OpenAIChatApi, TextGenerationParams,
  ProviderSetting, ProviderSettingOpenAIVariant, ProviderSettingClaude, ProviderSettingGoogle,
  ProviderModel, CompactStore, ModelAbility, SkillMetadata, SkillWorkspacePort,
  ModelType, TaskModelPair, TaskModelReference,
  AgentTool, ChatStreamProvider, ChatToolDefinition, CustomHeader,
  SubAgentRuntimeSetting, SubAgentOverride, SubAgentManagerDeps, SubAgentRunner, SubAgentGenerationRequest,
  SubAgentAssistantResolution, SubAgentGenerationPort,
  AgentPromptSettingsPort, NovelInteractiveRuntimeConfig, Conversation, JevSettings, JevQuestion, JevEvaluateResult,
  ProviderRuntimeSnapshot, ProviderAuthSnapshot,
  NovelFrozenRuntimeConfiguration,
  NovelResponsesStreamOptions,
} from '@amber/chat-domain';
import type { NovelWorkspaceToolPort } from '@amber/chat-domain';
import {
  loadAssistants, loadProviders, makeAssistant, saveAssistants, patchAssistant, loadJevSettings,
  makeProviderSettingOpenAI, createOpenAIChatApi, createClaudeChatApi, createGoogleChatApi,
  hasUsableAuth, openAIAuthHeaders, copyProviderSettingWithModels,
  taskModelReferenceId, taskModelReferenceFromId, findTaskProviderModel,
  makeTextGenerationParams, makeNovelTextGenerationParams, resolveNovelMaxOutputTokens, applyNovelStateReasoning,
  freezeNovelRuntimeConfiguration, readNovelRuntimeConfiguration, supportsResponsesResume,
  createChatDeepReadAiClient, prepareProviderRuntime, prepareProviderAuth,
  toChatModel, defaultReasoningLevelForModel,
  resolveSessionDefaults, mergeCustomParams,
  SessionAccessGrantStore, DEFAULT_AUTO_MODEL_ID,
  DEFAULT_TITLE_PROMPT, DEFAULT_SUGGESTION_PROMPT, DEFAULT_COMPRESS_PROMPT,
  McpManager, SkillManager, AgentToolActivityStore, PosixWorkspaceManager,
  AgentPromptConfigRepository, createAgentPromptConfigTool,
  AgentPermissionBroker, createPermissionsStatusTool,
  AgentTaskStore, AgentTaskScheduler, AgentTaskTools, AgentCronManager,
  asChatStreamProvider, makeGenerationRetrySetting, makeSubAgentRuntimeSetting,
  makeChatModel,
  SubAgentManager, GenerationSubAgentRunner, ChatStreamSubAgentGenerationPort,
  SubAgentTools,
  createAskUserTool, createNovelWorkspaceTools, novelAuditToolResult, executeNovelProjectOperation,
  createKvResponseResumeStore,
  AgentCronTools,
  runChatTurnWithTools, conversationHasPendingOrUnexecutedTools, AgentToolDispatcher,
  createTimeTool, createWorkspaceTools, createSearchTools, loadSearchPrefs,
  createToolRegistry, filterToolProfile, createMcpServerTools,
  createRegistryIntrospectionTools, createToolSearchTool, newId,
  createTerminalApprovalGuard,
  thinkTagTransformer, regexOutputTransformer,
  type ChatTurnDeps, type SearchSettings,
} from '@amber/chat-domain';
import { findModelByIdInProviders } from '../platform_impl/OcrSupport.ets';
import { loadTaskModelReference, storeTaskModelPair } from '../platform_impl/TaskModelSelectionSupport.ets';
import { createChatKvStore } from '../platform_impl/ChatKvStore.ets';
import { initializeEntryGoogleAuth, resolveEntryGoogleAuth } from '../platform_impl/GoogleAuthSupport.ets';
import { SSHProfileStore, TerminalRuntime, SSHTargetResolver, PythonRuntime, MoshRuntime } from '@amber/chat-domain';
import type { SSHCredentialStorePort, PythonTransportPort } from '@amber/chat-domain';
import { createEntrySSHCredentialStore } from '../platform_impl/SSHCredentialStore.ets';
import { createNativeSSHTransport } from '../platform_impl/NativeSSHTransport.ets';
import { createNativePythonTransport } from '../platform_impl/NativePythonTransport.ets';
import { createNativeMoshTransport } from '../platform_impl/NativeMoshTransport.ets';
import { createEntryTerminalLogPort } from '../platform_impl/TerminalLogPort.ets';
import { createChatRdbRepository } from '../platform_impl/ChatRdbRepository.ets';
import { createEntryCompactStore } from '../platform_impl/EntryCompactStore.ets';
import { encodeImageForChat, encodeImageDetailed } from '../platform_impl/EntryImageEncoder.ets';
import { createMcpHttpPort } from '../platform_impl/McpHttpPortImpl.ets';
import { getMcpSettingsStore, initMcpSettingsStore } from '../platform_impl/McpSettingsStore.ets';
import { getMcpFilesStore } from '../platform_impl/McpFilesStore.ets';
import {
  createEntrySkillFilePort, createEntrySkillWorkspacePort,
  createEntryBuiltinSkillAssetPort,
} from '../platform_impl/SkillStore.ets';
import { createEntryWorkspaceManager } from '../platform_impl/WorkspaceStore.ets';
import type { JsonObject, RecipeStore, PluginStore, InstalledPlugin, PluginPackage, PluginLoopAdapter,
  PluginDescriptor, UIMessagePartTool } from '@amber/chat-domain';
import { createEntryRecipeStore } from '../platform_impl/RecipeStore.ets';
import { createAssistantLocalPrimitives } from '../platform_impl/AssistantLocalPrimitives.ets';
import { startEntryRecipeFromPage } from '../platform_impl/RecipePageLaunch.ets';
import { configureEntryScopedRecipeLoop } from '../platform_impl/ScopedRecipeLoop.ets';
import { createEntryPluginStore } from '../platform_impl/PluginStore.ets';
import { createEntryPluginAdapter, exportEntryPlugin } from '../platform_impl/EntryPluginRuntime.ets';
import { startEntryPluginFromPage } from '../platform_impl/PluginPageLaunch.ets';
import { createWebMountTools } from '../platform_impl/WebMountTools.ets';
import { getWebMountSession } from '../platform_impl/WebMountSession.ets';
import type { ToolLoopOptions, TerminalApprovalGuard } from '@amber/chat-domain';
import { sha256HexUtf8 } from '../platform_impl/EntryLocalToolPorts.ets';
import {
  createEntryPromptConfigFilePort, createEntryCouncilStrategy,
  createEntryAgentPromptSettingsPort, promptConfigFilesDir,
} from '../platform_impl/PromptConfigStore.ets';
import {
  createEntryPermissionPlatformPort, auditPermissionUseLog,
} from '../platform_impl/PermissionStatusPortImpl.ets';
import {
  createEntryCronPersistencePort, createEntryAgentTaskFilePort,
  createEntryCronSchedulerPort, agentCronTaskDir, agentCronFilesDir,
  AGENT_CRON_EXECUTION_AVAILABLE,
} from '../platform_impl/AgentCronStore.ets';
import {
  createEntrySubAgentTranscriptPort, subAgentRunsDir, subAgentTranscriptPathForRun,
} from '../platform_impl/SubAgentStore.ets';
import { loadAgentRuntimeSnapshot } from '../platform_impl/AgentRuntimePrefs.ets';
import type { AgentRuntimeSnapshot } from '../platform_impl/AgentRuntimePrefs.ets';
import { beginConversationRun } from '../platform_impl/ConversationRunGuard.ets';
import { ConversationRunService } from '../platform_impl/ConversationRunService.ets';
import { ensureSearchSdk } from '../platform_impl/SearchSdkInit.ets';
import { loadNovelResearchTools } from '../platform_impl/NovelResearchSupport.ets';
import { util } from '@kit.ArkTS';

const DOMAIN: number = 0x0001;
const TAG: string = 'DeepRead.Container';

// 容器单例
interface Container {
  httpClient: HttpClient;
  storage: Storage;
  repository: DeepReadRepository;
  searchRegistry: SearchProviderRegistry;
  prefetcher: SourcePrefetcher;
  aiClient: AiClient;
  notifier: Notifier;
  fileStore: FileStore;
  modelRegistry: ModelRegistry;
}

let cached: Container | null = null;

const buildContainer = (): Container => {
  const httpClient: HttpClient = createRcpHttpClient();
  initializeEntryGoogleAuth(httpClient);
  initializeJevTriageHost({
    kv: getChatKvStore,
    loadConversation: (id: string): Promise<Conversation | null> => getChatRepository().getById(id),
    mutateConversation: (id: string, update: (latest: Conversation) => Conversation | null | Promise<Conversation | null>): Promise<Conversation | null> =>
      getConversationRunService().mutateStoredConversation(id, update),
    loadSettings: (): Promise<JevSettings> => loadJevSettings(getChatKvStore()),
    evaluate: (state: JsonObject, questions: Record<string, JevQuestion>, signal?: AbortSignalLike): Promise<JevEvaluateResult> =>
      jevEvaluate(state, questions, signal),
  });
  const storage: Storage = createPreferencesStorage();
  const repository: DeepReadRepository = createRdbRepository();
  // 深度阅读复用聊天「搜索服务」设置(已配置 API + 免费源开关);均在搜索时惰性读取
  const searchRegistry: SearchProviderRegistry = createTavilyAndFallbackRegistry(
    httpClient, storage, (): Promise<SearchSettings> => loadSearchPrefs(getChatKvStore()), ensureSearchSdk,
  );
  const prefetcher: SourcePrefetcher = createSourcePrefetcher(httpClient, searchRegistry, createEntryAbortController);
  const aiClient: AiClient = createOpenAiCompatibleAiClient(httpClient, storage);
  const notifier: Notifier = createNotificationNotifier();
  const fileStore: FileStore = createFileStore();
  const modelRegistry: ModelRegistry = createStorageModelRegistry(storage);
  return {
    httpClient, storage, repository, searchRegistry, prefetcher, aiClient, notifier,
    fileStore, modelRegistry,
  };
};

export const getAppContainer = (): Container => {
  if (cached === null) {
    cached = buildContainer();
  }
  return cached;
};

export const getNotifier = (): Notifier => getAppContainer().notifier;
export const getPrefetcher = (): SourcePrefetcher => getAppContainer().prefetcher;
export const getAiClient = (): AiClient => getAppContainer().aiClient;
export const getRepository = (): DeepReadRepository => getAppContainer().repository;
let deepReadArtifactStore: DeepReadArtifactStore | null = null;
export const getDeepReadArtifactStore = (): DeepReadArtifactStore => {
  if (deepReadArtifactStore === null) {
    deepReadArtifactStore = new DeepReadArtifactStore(getRepository(), getChatKvStore());
  }
  return deepReadArtifactStore;
};
export const getFileStore = (): FileStore => getAppContainer().fileStore;
export const getModelRegistry = (): ModelRegistry => getAppContainer().modelRegistry;

export interface DeepReadTextRuntime {
  aiClient: AiClient;
  model: string;
}

export const getDeepReadTextRuntime = async (): Promise<DeepReadTextRuntime> => {
  const runtime: ProviderRuntimeSnapshot = await resolveDeepReadRuntimeCore();
  return { model: runtime.model.modelId, aiClient: createChatDeepReadAiClient({ api: runtime.api, params: runtime.params }) };
};

const toolToModelTool = (tool: ToolDefinition): Tool => ({
  name: tool.name,
  description: tool.description,
  schema: tool.schema ?? {},
  allowsAutoApproval: tool.allowsAutoApproval,
  isHighRisk: tool.isHighRisk,
  execute: tool.execute,
});

let deepReadScheduler: DeepReadScheduler | null = null;
let deepReadSchedulerInit: Promise<DeepReadScheduler> | null = null;

export const getDeepReadScheduler = (): Promise<DeepReadScheduler> => {
  if (deepReadScheduler !== null) return Promise.resolve(deepReadScheduler);
  if (deepReadSchedulerInit !== null) return deepReadSchedulerInit;
  deepReadSchedulerInit = (async (): Promise<DeepReadScheduler> => {
    const container: Container = getAppContainer();
    const runRepository: DeepReadRunRepositoryAdapter =
      new DeepReadRunRepositoryAdapter(container.repository,
        (entry: DeepReadCacheEntry): Promise<void> => getDeepReadArtifactStore().save(entry.topicId).then((): void => {}));
    await runRepository.hydrate();
    const createRunManager = async (
      topicId?: string, title?: string, runToken?: number, options?: SchedulerRunOptions,
    ): Promise<RunManagerDeps> => {
      if (topicId !== undefined) await runRepository.load(topicId);
      const runtime: ProviderRuntimeSnapshot = await resolveDeepReadRuntimeCore();
      const cachedOutput: DeepReadOutput | null = topicId === undefined ? null : runRepository.get(topicId, title ?? '');
      let templateSnapshot: DeepReadTemplateSnapshot | undefined = options?.templateSnapshot
        ?? (options?.force ? undefined : cachedOutput?.templateSnapshot);
      if (templateSnapshot === undefined) {
        const templateId: string = await container.storage.get<string>('deepread_template_id', 'none');
        templateSnapshot = await captureDeepReadTemplate(container.storage, templateId);
      }
      const searchRegistry: SearchProviderRegistry = createTavilyAndFallbackRegistry(
        container.httpClient, container.storage, (): Promise<SearchSettings> => loadSearchPrefs(getChatKvStore()), ensureSearchSdk,
        { searchGoogle: (query: string, resultSize: number, signal?: AbortSignalLike): Promise<SearchHit[]> =>
          searchDeepReadGoogle(topicId ?? '', query, resultSize, signal) },
      );
      const prefetcher: SourcePrefetcher = createSourcePrefetcher(container.httpClient, searchRegistry, createEntryAbortController);
      const playbookMarkdown: string = (await getDeepReadPlaybookRepository().read()).markdown;
      const model: string = runtime.model.modelId;
      const aiClient: AiClient = createChatDeepReadAiClient({
        api: runtime.api, params: runtime.params,
        onRequestStart: (history: UIMessage[]): void => {
          if (topicId !== undefined && runToken !== undefined) beginDeepReadGenerationStep(topicId, runToken, history);
        },
        onRawSnapshot: (messages: UIMessage[]): void => {
          if (topicId !== undefined && runToken !== undefined) observeDeepReadGenerationSnapshot(topicId, runToken, messages);
        },
      });
      const collectRun = (
        messages: UIMessage[],
        _label: string,
        signal?: import('@amber/deepread-domain').AbortSignalLike,
        tools?: ToolDefinition[],
      ): Promise<UIMessage[]> => {
        hilog.info(DOMAIN, TAG, 'collectRun call: msgs=%{public}d tools=%{public}d', messages.length, tools?.length ?? 0);
        return aiClient.generateText({
          model,
          messages,
          tools: tools?.map(toolToModelTool),
          maxSteps: 32,
          autoApproveTools: true,
          autoApproveHighRiskTools: true,
          autoApprovedToolNames: tools?.map((tool: ToolDefinition): string => tool.name),
          signal,
        }).then((out: UIMessage[]): UIMessage[] => {
          // 诊断:模型响应形态(文本/工具调用占比)— "未按约定调用分段写入工具"排查
          let kinds: string = '';
          for (const m of out) {
            for (const p of m.parts) kinds += p.type + ',';
          }
          hilog.info(DOMAIN, TAG, 'collectRun done: msgs=%{public}d parts=[%{public}s]', out.length, kinds);
          return out;
        });
      };
      const runManager: RunManagerDeps = {
        onProgress: options?.onProgress,
        prefetcher,
        aiClient,
        model,
        writerMode: getProductKind() === 'deepread' || !runtime.model.abilities.includes('tool') ? 'structured' : 'tools',
        templateSnapshot,
        playbookMarkdown,
        collectRun,
        repository: runRepository,
        nowIso: (): string => new Date().toISOString().slice(0, 10),
      };
      return runManager;
    };
    // Reading history and active jobs does not require a configured model.
    // The actual job always captures its provider through createRunManager.
    const runManager: RunManagerDeps = {
      prefetcher: container.prefetcher, aiClient: container.aiClient, model: '',
      playbookMarkdown: '', nowIso: (): string => new Date().toISOString().slice(0, 10),
      collectRun: (): Promise<UIMessage[]> => Promise.reject(new Error('深读运行配置尚未捕获')),
      repository: runRepository,
    };
    const scheduler: DeepReadScheduler = createScheduler({
      runManager,
      createRunManager,
      observeOutput: (topicId: string): Observable<DeepReadOutput> =>
        mapCacheEntryObservable(container.repository.observe(topicId)),
      isInterruptedPhase,
      createAbortController: (): import('@amber/deepread-domain').AbortControllerLike =>
        createEntryAbortController(),
      notifier: container.notifier,
      onRunActivity: setDeepReadGenerationActivity,
    });
    deepReadScheduler = scheduler;
    return scheduler;
  })();
  deepReadSchedulerInit.catch((): void => { deepReadSchedulerInit = null; });
  return deepReadSchedulerInit;
};

const mapCacheEntryObservable = (
  observable: Observable<DeepReadCacheEntry | null>,
): Observable<DeepReadOutput> => ({
  subscribe: (callback: (output: DeepReadOutput) => void): (() => void) => observable.subscribe(
    (entry: DeepReadCacheEntry | null): void => {
      if (entry !== null) callback(entry.output);
    },
  ),
  getCurrent: (): DeepReadOutput | undefined => {
    const entry: DeepReadCacheEntry | null | undefined = observable.getCurrent();
    return entry === null || entry === undefined ? undefined : entry.output;
  },
});

// D-127:DeepReadPlaybookRepository 单例(BoardModule.kt:80-83 Koin single)—
//   根 filesDir/deep_read_playbook/;默认 markdown = rawfile
//   deepread/deep_read_playbook.md(Android assets 逐字拷贝);sha256 =
//   CryptoArchitectureKit(D-061 既有);System.currentTimeMillis → Date.now
let playbookRepository: DeepReadPlaybookRepository | null = null;
export const getDeepReadPlaybookRepository = (): DeepReadPlaybookRepository => {
  if (playbookRepository === null) {
    playbookRepository = new DeepReadPlaybookRepository({
      store: getFileStore(),
      sha256Hex: sha256HexUtf8,
      nowMs: (): number => Date.now(),
      loadDefaultMarkdown: (): Promise<string> => {
        const ctx: Context = AppStorage.get<Context>('abilityContext') as Context;
        const bytes: Uint8Array =
          ctx.resourceManager.getRawFileContentSync('deepread/deep_read_playbook.md');
        const text: string = util.TextDecoder.create('utf-8').decodeToString(bytes);
        return Promise.resolve(text);
      },
    });
  }
  return playbookRepository;
};

// D-128:AgentPromptConfigRepository 单例(Koin 注册表 prompts 模块)—
//   filesDir/agent_prompts/ 四镜像文件;设置读写 = agent_runtime 文档 RMW;
//   council 策略直配 deepread HAR 运行时类型(全字段显式拷贝零丢失)
let promptConfigRepository: AgentPromptConfigRepository<ModelCouncilRuntimeSetting, ModelCouncilSeat> | null = null;
export const getAgentPromptConfigRepository =
  (): AgentPromptConfigRepository<ModelCouncilRuntimeSetting, ModelCouncilSeat> => {
    if (promptConfigRepository === null) {
      promptConfigRepository = new AgentPromptConfigRepository<ModelCouncilRuntimeSetting, ModelCouncilSeat>(
        promptConfigFilesDir(),
        createEntryPromptConfigFilePort(),
        createEntryCouncilStrategy(),
      );
    }
    return promptConfigRepository;
  };

// agent_prompt_config 工具的 settings 端口:共享单例 + subagent 更新后同步刷新
// hydrate 缓存 — 否则工具路径改配置后,manager 的同步读仍拿到旧快照(进程内不生效)
class ToolSettingsPortWithCacheRefresh implements AgentPromptSettingsPort<ModelCouncilRuntimeSetting> {
  getSubAgentSetting(): Promise<SubAgentRuntimeSetting> {
    return hydrateSubAgentSetting();
  }

  async updateSubAgentSetting(next: SubAgentRuntimeSetting): Promise<void> {
    await getAgentPromptSettingsPort().updateSubAgentSetting(next);
    cachedSubAgentSetting = next;
  }

  getCouncilSetting(): Promise<ModelCouncilRuntimeSetting> {
    return getAgentPromptSettingsPort().getCouncilSetting();
  }

  updateCouncilSetting(next: ModelCouncilRuntimeSetting): Promise<void> {
    return getAgentPromptSettingsPort().updateCouncilSetting(next);
  }
}

// agent_prompt_config 工具 deps(LocalTools.kt:98-100 lazy + :183 装配)
export const getAgentPromptConfigTool = (): AgentTool =>
  createAgentPromptConfigTool<ModelCouncilRuntimeSetting, ModelCouncilSeat>({
    repository: getAgentPromptConfigRepository(),
    settings: new ToolSettingsPortWithCacheRefresh(),
  });

// D-129:AgentPermissionBroker 单例(Koin system 模块 — AgentPermissionBroker
//   (context,isDebugBuild));平台调用下沉 createEntryPermissionPlatformPort
//   (atManager.checkAccessTokenSync;special access 六类无对应 → Unsupported)
let permissionBroker: AgentPermissionBroker | null = null;
export const getPermissionBroker = (): AgentPermissionBroker => {
  if (permissionBroker === null) {
    permissionBroker = new AgentPermissionBroker(
      createEntryPermissionPlatformPort(), auditPermissionUseLog,
    );
  }
  return permissionBroker;
};

// permissions_status 工具(LocalTools.kt:94 lazy + :180 装配位)
export const getPermissionsStatusTool = (): AgentTool =>
  createPermissionsStatusTool(getPermissionBroker());

// ===== D-130:agent_cron 装配(AgentInfraModule.kt:40/:64) =====
// AgentTaskStore/AgentCronManager 为异步创建(文件/KV 加载)→ 缓存
//   Promise 单例;AmberAgentApp.kt:216-224 rescheduleCronTasks 等价 =
//   rescheduleCronTasksOnStartup(EntryAbility.onCreate fire-and-forget)。
let agentTaskStorePromise: Promise<AgentTaskStore> | null = null;
export const getAgentTaskStore = (): Promise<AgentTaskStore> => {
  if (agentTaskStorePromise === null) {
    const init: Promise<AgentTaskStore> = AgentTaskStore.create({
      taskDir: agentCronTaskDir(),
      appFilesDir: agentCronFilesDir(),
      files: createEntryAgentTaskFilePort(),
    });
    // 失败不缓存 rejected Promise,否则依赖方(如 subagent manager)的重试
    // 会永远复用同一个失败结果
    init.catch((): void => {
      if (agentTaskStorePromise === init) agentTaskStorePromise = null;
    });
    agentTaskStorePromise = init;
  }
  return agentTaskStorePromise;
};

let sshCredentialStore: SSHCredentialStorePort | null = null;
let sshProfileStorePromise: Promise<SSHProfileStore> | null = null;
let terminalRuntimePromise: Promise<TerminalRuntime> | null = null;
let pythonRuntimePromise: Promise<PythonRuntime> | null = null;
let moshRuntimePromise: Promise<MoshRuntime> | null = null;

const getSSHCredentials = (): SSHCredentialStorePort => {
  if (sshCredentialStore === null) sshCredentialStore = createEntrySSHCredentialStore();
  return sshCredentialStore;
};

export const getSSHProfileStore = (): Promise<SSHProfileStore> => {
  if (sshProfileStorePromise === null) {
    const init: Promise<SSHProfileStore> = SSHProfileStore.create({
      kv: getChatKvStore(), credentials: getSSHCredentials(),
    });
    init.catch((): void => {
      if (sshProfileStorePromise === init) sshProfileStorePromise = null;
    });
    sshProfileStorePromise = init;
  }
  return sshProfileStorePromise;
};

export const getTerminalRuntime = (): Promise<TerminalRuntime> => {
  if (terminalRuntimePromise === null) {
    const init: Promise<TerminalRuntime> = getSSHProfileStore().then(
      async (profiles: SSHProfileStore): Promise<TerminalRuntime> => new TerminalRuntime({
        profiles, credentials: getSSHCredentials(), transport: createNativeSSHTransport(),
        logs: createEntryTerminalLogPort(), taskStore: await getAgentTaskStore(),
      }));
    init.catch((): void => {
      if (terminalRuntimePromise === init) terminalRuntimePromise = null;
    });
    terminalRuntimePromise = init;
  }
  return terminalRuntimePromise;
};

export const getPythonRuntime = (): Promise<PythonRuntime> => {
  if (pythonRuntimePromise === null) {
    const init: Promise<PythonRuntime> = createNativePythonTransport().then(
      async (transport: PythonTransportPort): Promise<PythonRuntime> => new PythonRuntime({
        transport, taskStore: await getAgentTaskStore(),
      }));
    init.catch((): void => { if (pythonRuntimePromise === init) pythonRuntimePromise = null; });
    pythonRuntimePromise = init;
  }
  return pythonRuntimePromise;
};

export const getMoshRuntime = (): Promise<MoshRuntime> => {
  if (moshRuntimePromise === null) {
    const init: Promise<MoshRuntime> = getSSHProfileStore().then(
      async (profiles: SSHProfileStore): Promise<MoshRuntime> => new MoshRuntime({
        targetResolver: new SSHTargetResolver({ profiles, credentials: getSSHCredentials() }),
        sshTransport: createNativeSSHTransport(), transport: createNativeMoshTransport(),
        taskStore: await getAgentTaskStore(),
      }));
    init.catch((): void => { if (moshRuntimePromise === init) moshRuntimePromise = null; });
    moshRuntimePromise = init;
  }
  return moshRuntimePromise;
};

// Do not initialize a runtime merely because the application entered background.
export const interruptTerminalForBackground = (): Promise<void> => {
  const operations: Promise<void>[] = [];
  if (terminalRuntimePromise !== null) operations.push(terminalRuntimePromise.then(
    (runtime: TerminalRuntime): Promise<void> => runtime.interruptForBackground()));
  if (pythonRuntimePromise !== null) operations.push(pythonRuntimePromise.then(
    (runtime: PythonRuntime): Promise<void> => runtime.interruptForBackground()));
  if (moshRuntimePromise !== null) operations.push(moshRuntimePromise.then(
    (runtime: MoshRuntime): Promise<void> => runtime.interruptForBackground()));
  return Promise.all(operations).then((): void => {});
};

// D-131:AgentTaskScheduler 复用上面的 AgentTaskStore Promise(Koin single 等价)。
let agentTaskSchedulerPromise: Promise<AgentTaskScheduler> | null = null;
export const getAgentTaskScheduler = (): Promise<AgentTaskScheduler> => {
  if (agentTaskSchedulerPromise === null) {
    agentTaskSchedulerPromise = getAgentTaskStore().then(
      (store: AgentTaskStore): AgentTaskScheduler => new AgentTaskScheduler(store));
  }
  return agentTaskSchedulerPromise;
};

// agent_task_* 六件(LocalTools/ChatService.kt:2299 无条件，紧随 conversation_queue_* 之后)。
export const getAgentTaskTools = async (): Promise<AgentTool[]> => {
  const files = createEntryAgentTaskFilePort();
  return new AgentTaskTools(await getAgentTaskScheduler(), {
    outputExists: (path: string): Promise<boolean> => files.exists(path),
  }).getTools();
};

let agentCronManagerPromise: Promise<AgentCronManager> | null = null;

// 无头执行不暴露依赖页面的工具，待审批结果不得当作成功完成。
const createEntryCronRunExecutor = (): ((taskId: string, signal?: AbortSignalLike) => Promise<void>) => {
  return async (taskId: string, signal?: AbortSignalLike): Promise<void> => {
    const manager: AgentCronManager = await getAgentCronManager();
    const tasks = await manager.listTasksSnapshot();
    const task = tasks.find((t): boolean => t.id === taskId);
    if (task === undefined) {
      throw new Error(`Cron task not found: ${taskId}`);
    }
    if (signal?.aborted) throw new Error('Cron run cancelled');
    const releaseRun: () => void = beginConversationRun(
      task.conversationId.length > 0 ? task.conversationId : `cron-${task.id}`);
    notifyCronRunning(task.id, task.title);
    try {
      let conversation = task.conversationId.length > 0
        ? await getChatRepository().getById(task.conversationId)
        : null;
      if (conversation === null) {
        conversation = {
          id: task.conversationId.length > 0 ? task.conversationId : `cron-${task.id}`,
          assistantId: '',
          title: task.title,
          createAt: new Date().toISOString(),
          updateAt: new Date().toISOString(),
          chatSuggestions: [],
          isPinned: false,
          autoApproveToolCalls: false,
          messageNodes: [],
        };
        await getChatRepository().save(conversation);
      }
      const runtime = await resolveChatRuntime(
        conversation.assistantId.length > 0 ? conversation.assistantId : undefined,
      );
      if (runtime === null) {
        throw new Error('Cron run failed: no usable chat provider/model');
      }
      if (signal?.aborted) throw new Error('Cron run cancelled');
      const searchSettings = await loadSearchPrefs(getChatKvStore());
      const execution = await loadAgentRuntimeSnapshot(getChatKvStore());
      ensureSearchSdk();
      await initMcpManager();
      if (signal?.aborted) throw new Error('Cron run cancelled');
      const localTools = runtime.assistant.localTools;
      const availableTools: AgentTool[] = [
        createTimeTool(),
        ...(localTools.indexOf('workspace_files') >= 0
          ? createWorkspaceTools(getWorkspaceManager(), getToolActivityStore()) : []),
        ...(searchSettings.enableWebSearch ? createSearchTools(searchSettings) : []),
        ...createMcpServerTools({
          manager: getMcpManager(), assistantIds: [...runtime.assistant.mcpServers],
        }),
      ];
      const tools: AgentTool[] = createToolRegistry(
        filterToolProfile(availableTools, runtime.assistant.toolProfile).tools).tools();
      const deps: ChatTurnDeps = {
        assistant: runtime.assistant,
        inputTransformers: [],
        outputTransformers: [thinkTagTransformer, regexOutputTransformer],
        provider: asChatStreamProvider(runtime.api, (): TextGenerationParams => runtime.params),
        store: getChatRepository(),
        contextMessageSize: runtime.contextMessageSize,
        abortSignal: signal,
      };
      const out = await runChatTurnWithTools(conversation,
        `这是定时任务。只能使用当前提供的工具；需要人工审批时停止，不得宣称操作已完成。\n${task.prompt}`,
        deps, {
          tools,
          invocationContext: 'cron',
          autoApproveTools: false,
          autoApproveHighRiskTools: false,
          maxSteps: execution.maxToolLoopSteps,
          toolPromptModel: runtime.params.model,
          makeProviderForStep: (definitions: ChatToolDefinition[]): ChatStreamProvider =>
            asChatStreamProvider(runtime.api, (): TextGenerationParams => makeTextGenerationParams({
              model: runtime.params.model,
              temperature: runtime.params.temperature,
              topP: runtime.params.topP,
              maxTokens: runtime.params.maxTokens,
              reasoningLevel: runtime.params.reasoningLevel,
              customBody: runtime.params.customBody,
              tools: definitions,
            })),
        });
      if (signal?.aborted) throw new Error('Cron run cancelled');
      await getChatRepository().save(out);
      if (conversationHasPendingOrUnexecutedTools(out)) {
        throw new Error('定时任务需要人工审批或未完成工具执行，请打开任务会话处理');
      }
      notifyCronCompleted(task.id, task.title);
    } catch (e) {
      const message: string = e instanceof Error ? e.message : String(e);
      if (!signal?.aborted) notifyCronFailed(task.id, task.title, message);
      throw new Error(message);
    } finally {
      releaseRun();
    }
  };
};

export const getAgentCronManager = (): Promise<AgentCronManager> => {
  if (agentCronManagerPromise === null) {
    agentCronManagerPromise = (async (): Promise<AgentCronManager> =>
      new AgentCronManager(
        createEntryCronPersistencePort(getChatKvStore()),
        createEntryCronSchedulerPort({
          manager: (): Promise<AgentCronManager> => getAgentCronManager(),
          runExecutor: createEntryCronRunExecutor(),
          createAbortController: createEntryAbortController,
        }),
        await getAgentTaskStore(),
      ))();
  }
  return agentCronManagerPromise;
};

// Cron 执行器已接线:暴露四件工具(创建/列表/立即运行/删除)
export const getAgentCronTools = async (): Promise<AgentTool[]> => {
  const manager = await getAgentCronManager();
  return new AgentCronTools(manager).getTools();
};

export const isAgentCronExecutionAvailable = (): boolean =>
  AGENT_CRON_EXECUTION_AVAILABLE;

// 未接执行器时明确跳过启动重排，避免应用内定时器制造失败运行。
export const rescheduleCronTasksOnStartup = (): void => {
  if (!AGENT_CRON_EXECUTION_AVAILABLE) {
    hilog.warn(DOMAIN, TAG, 'Cron execution unavailable; startup reschedule skipped');
    return;
  }
  getAgentCronManager()
    .then((manager: AgentCronManager): Promise<void> => manager.rescheduleAll())
    .catch((e: Error) => {
      hilog.error(DOMAIN, TAG, 'rescheduleCronTasks failed: %{public}s', e.message);
    });
};

// ===== D-132c:Subagent 装配 =====
// Android 锚点:
//   SubAgentManager.kt(生命周期/transcript/task board)+ SubAgentRunner.kt
//     (isolated assistant/child loop)+ SubAgentTools.kt(五件公共工具)
//   Koin:SubAgentManager single;SubAgentTools per-conversation(ChatService:2299)
// 复用约束(交接宪章):
//   - PromptConfigStore.ets 唯一 agent_runtime parser/serializer/RMW;
//     createEntryAgentPromptSettingsPort(getChatKvStore()) 复用于
//     agent_prompt_config 工具与 subagent manager,不新建第二套 settings store。
//   - 共享 AgentTaskStore(getAgentTaskStore)/SessionAccessGrantStore(getSessionGrantStore)。
//   - provider/model/assistant 解析与 resolveChatRuntime 同口径
//     (resolveTaskChatModelChoice/resolveChatProviderChoice/buildProviderApi)。
//   - 仅薄 hydrated snapshot bridge 缓存当前设置,不负责 parser/serializer/RMW。

// 共享 AgentPromptSettingsPort 单例(D-128 agent_prompt_config 工具与 subagent
//   manager 共用同一 KV 'agent_runtime' RMW)
let agentPromptSettingsPort: AgentPromptSettingsPort<ModelCouncilRuntimeSetting> | null = null;
export const getAgentPromptSettingsPort =
  (): AgentPromptSettingsPort<ModelCouncilRuntimeSetting> => {
    if (agentPromptSettingsPort === null) {
      agentPromptSettingsPort = createEntryAgentPromptSettingsPort(getChatKvStore());
    }
    return agentPromptSettingsPort;
  };

// 薄 hydrated snapshot bridge:只缓存/刷新共享 port 的当前 subagent 设置,
//   不负责 parser/serializer/RMW(交接宪章:manager.getSubAgentSetting 同步读)。
//   首次读 → 水合;后续返回缓存;refreshSubAgentSetting 用于设置变更后刷新。
let cachedSubAgentSetting: SubAgentRuntimeSetting | null = null;
export const hydrateSubAgentSetting = async (): Promise<SubAgentRuntimeSetting> => {
  if (cachedSubAgentSetting === null) {
    cachedSubAgentSetting = await getAgentPromptSettingsPort().getSubAgentSetting();
  }
  return cachedSubAgentSetting;
};
export const refreshSubAgentSetting = async (): Promise<SubAgentRuntimeSetting> => {
  cachedSubAgentSetting = await getAgentPromptSettingsPort().getSubAgentSetting();
  return cachedSubAgentSetting;
};

// council enabled 状态由工具层单独读取既有 council settings(不塞回 Manager)
export const isModelCouncilEnabled = async (): Promise<boolean> => {
  const council: ModelCouncilRuntimeSetting = await getAgentPromptSettingsPort().getCouncilSetting();
  return council.enabled;
};

// 子代理 generation port(SubAgentRunner.kt:52-55 model/assistant 解析):
//   resolveModel = findModelById(modelId) ?: getCurrentChatModel(= resolveChatProviderChoice)
//   resolveAssistant = getCurrentAssistant + agentRuntime generationRetry/autoApprove 快照
//     (runner 内部 isolatedAssistant 应用隔离字段;此处只返回 parent + retry/flags)
//   makeProvider = buildProviderApi + asChatStreamProvider(同步;provider 由
//     resolveModel 阶段已解析并以 model.id 为键缓存,makeProvider 按 model.id 取)
const buildSubAgentGenerationPort = (
  parentRuntime: ChatRuntime | null = null, parentAgentRuntime: AgentRuntimeSnapshot | null = null,
  parentConversationId: string | null = null,
): SubAgentGenerationPort => {
  // model.id → 已刷新的 provider/accountId(resolveModel 异步解析后缓存;makeProvider 同步取)
  //   并行子代理按各自 model.id 取 provider,不互相干扰;同 model → 同 provider(正确)
  const providerByModelId: Map<string, ProviderAuthSnapshot> = new Map<string, ProviderAuthSnapshot>();
  // D5-1:Jev 模型路由(无 override 且有任务上下文时):候选 = 当前聊天模型 +
  //   各启用服务商首个 chat 模型(≤6);ACTIVE 用选中,SHADOW/失败/无路由空间 → null
  const jevRouteModel = async (taskHint: string): Promise<ChatProviderChoice | null> => {
    const current: ChatProviderChoice | null = parentRuntime === null
      ? await resolveChatProviderChoice() : parentRuntime.resolvedChoice;
    const providers: ProviderSetting[] = (await loadProviders(getChatKvStore())) ?? [];
    const candidates: Record<string, string> = {};
    const choices: Map<string, ChatProviderChoice> = new Map<string, ChatProviderChoice>();
    if (current !== null) {
      candidates[current.model.id] = `当前聊天模型 ${current.provider.name}/${current.model.modelId}`;
      choices.set(current.model.id, current);
    }
    for (const p of providers) {
      if (choices.size >= 6) break;
      if (!p.enabled) continue;
      const m: ProviderModel | undefined = p.models.find(
        (mm: ProviderModel): boolean => mm.type === 'chat');
      if (m !== undefined && !choices.has(m.id)) {
        candidates[m.id] = `${p.name}/${m.modelId}`;
        choices.set(m.id, { provider: p, model: m, providerContainerId: p.id });
      }
    }
    if (choices.size <= 1) return null;
    const r = await jevChoiceSelect('model_routing', taskHint.substring(0, 300), candidates);
    if (r === null || r.shadow) return null;
    return choices.get(r.selected) ?? null;
  };
  const resolveModel = async (modelSettingId: string | null, taskHint?: string): Promise<ProviderModel | null> => {
    // Per-role override → Jev 路由(有上下文时)→ fallback current chat model
    //   (SubAgentRunner.kt:52-54 语义的 Jev 增强,失败/未配置回退链不变)
    let choice: ChatProviderChoice | null = null;
    if (modelSettingId !== null && modelSettingId.length > 0) {
      choice = await resolveTaskChatModelChoice(modelSettingId, parentRuntime?.resolvedChoice);
    }
    if (choice === null && taskHint !== undefined && taskHint.length > 0) {
      choice = await jevRouteModel(taskHint);
    }
    if (choice === null) choice = parentRuntime === null
      ? await resolveChatProviderChoice() : parentRuntime.resolvedChoice;
    if (choice === null) return null;
    providerByModelId.set(choice.model.id, await prepareProviderAuth({
      provider: choice.provider, refreshCodex: getValidCodexToken, refreshGrok: getValidGrokToken,
    }));
    return choice.model;
  };
  const resolveAssistant = async (): Promise<SubAgentAssistantResolution | null> => {
    const assistants: Assistant[] = await getChatAssistants();
    if (assistants.length === 0) return null;
    const assistant: Assistant = parentRuntime === null ? assistants[0] : parentRuntime.assistant;
    const snapshot: AgentRuntimeSnapshot = parentAgentRuntime ?? await loadAgentRuntimeSnapshot(getChatKvStore());
    const generationRetry = makeGenerationRetrySetting({
      enabled: snapshot.generationRetry.enabled,
      maxRetries: snapshot.generationRetry.maxRetries,
    });
    return {
      assistant,
      generationRetry,
      autoApproveTools: snapshot.autoApproveAllToolCalls,
      autoApproveHighRiskTools: snapshot.autoApproveHighRiskToolCalls,
    };
  };
  const makeProvider = (
    model: ProviderModel, params: TextGenerationParams, headers: CustomHeader[],
  ): ChatStreamProvider => {
    // resolveModel 在 makeProvider 之前 await 完成并按 model.id 缓存 provider;
    //   缺失 = resolveModel 未被调用(契约违反),显式抛错(fail-closed,不伪成功)
    const cached: ProviderAuthSnapshot | undefined = providerByModelId.get(model.id);
    if (cached === undefined) {
      throw new Error(`Subagent provider not resolved for model ${model.id}`);
    }
    const customHeaderRecord: Record<string, string> = {};
    headers.forEach((h: CustomHeader): void => {
      const name: string = h.name.trim();
      if (name.length === 0) return;
      customHeaderRecord[name] = h.value.trim();
    });
    const api: OpenAIChatApi = buildProviderApi(cached.provider, customHeaderRecord, cached.accountId);
    return asChatStreamProvider(api, (): TextGenerationParams => params);
  };
  return new ChatStreamSubAgentGenerationPort({ resolveModel, resolveAssistant, makeProvider,
    configureRecipeLoop: async (loop: ToolLoopOptions, request: SubAgentGenerationRequest): Promise<void> => {
      const review = createEntryJevAutoApprovalReview(request.messages);
      loop.dispatcher = new AgentToolDispatcher({ autoApprovalReview: review });
      if (!loop.tools.some((tool: AgentTool): boolean =>
        tool.name.startsWith('recipe_') || tool.name === 'recipes_list'
          || tool.name.startsWith('plugin_') || tool.name === 'plugins_list')) return;
      const hasTerminal: boolean = loop.tools.some((tool: AgentTool): boolean => tool.name.startsWith('terminal_'));
      const guard: TerminalApprovalGuard | null = hasTerminal
        ? createTerminalApprovalGuard(await getTerminalRuntime(), await getSSHProfileStore(), await getMoshRuntime()) : null;
      const pluginStore: PluginStore = await getPluginStore();
      await configureEntryScopedRecipeLoop(loop, await getRecipeStore(), guard, {
        store: pluginStore,
        createAdapter: (installed: InstalledPlugin[], primitives: AgentTool[]): PluginLoopAdapter =>
          createEntryPluginAdapter(pluginStore, installed, primitives, parentConversationId),
      }, review);
    },
  });
};

// Runner 单例(Koin single 等价;generation port 不持有会话状态)
let subAgentRunner: SubAgentRunner | null = null;
const getSubAgentRunner = (): SubAgentRunner => {
  if (subAgentRunner === null) {
    subAgentRunner = new GenerationSubAgentRunner(buildSubAgentGenerationPort());
  }
  return subAgentRunner;
};

// Manager 单例(Koin single 等价;deps 复用共享 store/transcript/time/path)
let subAgentManager: SubAgentManager | null = null;
let subAgentManagerInit: Promise<SubAgentManager> | null = null;
export const getSubAgentManager = async (): Promise<SubAgentManager> => {
  if (subAgentManager !== null) return subAgentManager;
  // Promise 单例:并发首访不得创建双 manager(双 runs Map/双任务注册)
  if (subAgentManagerInit === null) {
    subAgentManagerInit = (async (): Promise<SubAgentManager> => {
      await hydrateSubAgentSetting();
      const deps: SubAgentManagerDeps = {
        getSubAgentSetting: (): SubAgentRuntimeSetting => {
          // 同步读缓存;manager 每次 start/listBuiltIns/runtimeSummary 调用
          //   前由调用方确保已水合(ChatPage 发送前 await hydrateSubAgentSetting)
          return cachedSubAgentSetting ?? makeSubAgentRuntimeSetting();
        },
        runner: getSubAgentRunner(),
        agentTaskStore: await getAgentTaskStore(),
        sessionAccessGrantStore: getSessionGrantStore(),
        transcript: createEntrySubAgentTranscriptPort(),
        newId: (): string => util.generateRandomUUID(false),
        now: (): number => Date.now(),
        sleep: (ms: number): Promise<void> =>
          new Promise<void>((resolve): void => { setTimeout(resolve, ms); }),
        transcriptPathForRun: (runId: string): string => subAgentTranscriptPathForRun(runId),
      };
      const manager: SubAgentManager = new SubAgentManager(deps);
      subAgentManager = manager;
      return manager;
    })();
  }
  try {
    return await subAgentManagerInit;
  } catch (e) {
    subAgentManagerInit = null; // 失败不缓存,允许后续重试
    throw e instanceof Error ? e : new Error(String(e));
  }
};

// 五件公共工具(ChatService.kt:2299 per-conversation;parentToolsProvider 捕获
//   第 4 步 base tools,由 ChatPage 传入)。会话存在且 subagent enabled 时由
//   ChatPage 调用挂载;public subagent_* 不进入 child scope(manager.start 过滤)。
export interface SubAgentToolsContext {
  parentConversationId: string;
  parentToolsProvider: () => AgentTool[];
  parentRuntime?: ChatRuntime;
  parentAgentRuntime?: AgentRuntimeSnapshot;
}
export const getSubAgentTools = async (
  ctx: SubAgentToolsContext,
): Promise<AgentTool[]> => {
  await hydrateSubAgentSetting();
  const manager: SubAgentManager = await getSubAgentManager();
  const councilEnabled: boolean = await isModelCouncilEnabled();
  const tools: SubAgentTools = new SubAgentTools({
    manager,
    runner: ctx.parentRuntime === undefined ? undefined : new GenerationSubAgentRunner(
      buildSubAgentGenerationPort(ctx.parentRuntime, ctx.parentAgentRuntime ?? null, ctx.parentConversationId)),
    parentConversationId: ctx.parentConversationId,
    parentToolsProvider: ctx.parentToolsProvider,
    isModelCouncilEnabled: (): boolean => councilEnabled,
  });
  return tools.getTools();
};

// ===== Model Council 装配 =====
let councilRunner: ModelCouncilTextRunner | null = null;
let councilManager: ModelCouncilManager | null = null;

export const getCouncilRunner = (): ModelCouncilTextRunner => {
  if (councilRunner === null) {
    councilRunner = createModelCouncilRunner(getAppContainer().httpClient);
  }
  return councilRunner;
};

// 用当前模型库 + 给定设置创建(替换)共享 manager;CouncilPage 点击开始时调用,
// 随后导航到 CouncilRunPage 通过 getCouncilManager() 共享同一实例。
export const prepareCouncilManager = async (
  setting: ModelCouncilRuntimeSetting,
): Promise<ModelCouncilManager> => {
  if (councilManager !== null && councilManager.listActive().length > 0) return councilManager;
  const pool = await getModelRegistry().list();
  const runner: ModelCouncilTextRunner = setting.toolMode === 'search' || setting.toolMode === 'full'
    ? await prepareCouncilToolRunner({
      http: getAppContainer().httpClient, files: getFileStore(), kv: getChatKvStore(),
      getAssistants: getChatAssistants, workspace: getWorkspaceManager(),
      getTerminalRuntime, getMoshRuntime, getSSHProfiles: getSSHProfileStore, getPythonRuntime,
      initMcp: initMcpManager, mcpManager: getMcpManager(), mcpSettings: getMcpSettingsStore(),
      skillManager: getSkillManager(), skillWorkspace: getSkillWorkspacePort(),
      setSkillEnabled: async (assistantId: string, name: string, enabled: boolean): Promise<void> => {
        await mutateAssistant(assistantId, (assistant: Assistant): Assistant => patchAssistant(assistant, {
          enabledSkills: enabled
            ? (assistant.enabledSkills.indexOf(name) >= 0 ? assistant.enabledSkills : assistant.enabledSkills.concat([name]))
            : assistant.enabledSkills.filter((item: string): boolean => item !== name),
        }));
      },
      memory: getMemoryToolDeps(new Intl.DateTimeFormat(undefined, { dateStyle: 'medium' }).format(new Date())),
      compactStore: getCompactStore(),
      loadCompressPrompt: async (): Promise<string> => (await loadTaskPrompts()).compressPrompt,
      repository: getChatRepository(), grantStore: getSessionGrantStore(),
      getRecipeStore, getPluginStore, getAgentTaskTools, getAgentCronTools,
      getPermissionsStatusTool, getAgentPromptConfigTool, playbookRepository: getDeepReadPlaybookRepository(),
    }) : getCouncilRunner();
  if (councilManager !== null && councilManager.listActive().length > 0) return councilManager;
  councilManager = createModelCouncilManager({
    runner: runner,
    fileStore: getFileStore(),
    modelPool: pool,
    setting: setting,
  });
  return councilManager;
};

export const getCouncilManager = (): ModelCouncilManager | null => councilManager;

// ===== Novel Creation 装配 =====
let novelCreation: NovelCreation | null = null;
let novelProjectRepository: NovelProjectRepository | null = null;
let novelWorkspaceService: NovelWorkspaceService | null = null;

export const getNovelProjectRepository = (): NovelProjectRepository => {
  if (novelProjectRepository === null) {
    novelProjectRepository = createFileNovelRepository(getAppContainer().fileStore);
  }
  return novelProjectRepository;
};

export const getNovelWorkspaceService = (): NovelWorkspaceService => {
  if (novelWorkspaceService === null) {
    novelWorkspaceService = createNovelWorkspaceService(getNovelProjectRepository(), getNovelCreation());
  }
  return novelWorkspaceService;
};

export const getNovelCreation = (): NovelCreation => {
  if (novelCreation === null) {
    const c: Container = getAppContainer();
    const polishEffects: NovelPolishEffects = {
      async schedule(job: DurablePolishJob): Promise<void> {
        scheduleNovelPolishWork({
          projectId: job.projectId, branchId: job.branchId, jobId: job.jobId,
        });
      },
      async cancel(job: DurablePolishJob): Promise<void> {
        cancelNovelPolishWork({
          projectId: job.projectId, branchId: job.branchId, jobId: job.jobId,
        });
      },
      async notify(job: DurablePolishJob): Promise<void> {
        const outcomes: PolishChapterResult[] = projectPolishOutcomes(job);
        const succeeded: number = outcomes.filter((item: PolishChapterResult): boolean => item.status === 'success').length;
        const failed: number = outcomes.filter((item: PolishChapterResult): boolean => item.status === 'failed').length;
        const skipped: number = outcomes.filter((item: PolishChapterResult): boolean => item.status === 'driftSkipped').length;
        const remaining: number = outcomes.filter((item: PolishChapterResult): boolean => item.status === 'unprocessed').length;
        const summary: string = `成功 ${succeeded}/${job.targets.length} · 失败 ${failed} · 跳过 ${skipped} · 未处理 ${remaining}`;
        let title: string = '批量润色';
        let text: string = summary;
        if (job.stage === 'completed') {
          title = failed + skipped + remaining === 0 ? '批量润色完成' : '批量润色处理结束';
        } else if (job.stage === 'failed') {
          title = '批量润色失败';
          text = `${summary}；${job.failure ?? ''}`;
        } else if (job.stage === 'cancelled') {
          title = '批量润色已取消';
        } else if (job.stage === 'paused') {
          title = '批量润色已暂停';
        } else if (job.stage === 'waiting_system') {
          title = '批量润色等待后台调度';
        } else if (job.cursor < job.targets.length) {
          const target = job.targets[job.cursor];
          text = `正在处理第 ${target.ordinal} 章；${summary}`;
        }
        await publishNovelJobNotification(
          job.projectId, job.branchId, job.jobId, title, text.slice(0, 120));
      },
    };
    novelCreation = createNovelCreation({
      loadModelDefaults: loadNovelModelDefaults,
      loadPolishPreference: loadNovelPolishPreference,
      repository: getNovelProjectRepository(),
      modelRunning: createEntryNovelChatRunAdapter(resolveNovelInteractiveRuntime),
      polishEffects,
    });
  }
  return novelCreation;
};

// ===== Chat 装配(D-019/D-021/D-023) =====
// 持久化:会话仓库 = ChatRdbRepository(relationalStore 真实实现,D-013 schema;
//   初始化失败自动回退 memory + hilog 警告,非静默)。assistants/providers 走
//   Preferences KV(真实持久化)。
// Provider 选择(D-023):用户选择(Preferences 持久化 providerId/modelId)优先,
//   未选择或选择失效 → 回退 D-019 策略(第一个 hasUsableAuth 且非空 models 的
//   openai variant + 首模型)。选择器页 = ChatModelPickerPage。
// 注(已核实 Android 基准):ProviderTemplatePickerSheet 只提供 generic
//   OpenAI()/Google()/Claude() 空模板,无预置种子列表 → 不做 provider 种子数据。

const SELECTED_PROVIDER_KEY: string = 'chat_selected_provider_id';
const SELECTED_MODEL_KEY: string = 'chat_selected_model_id';

export interface ChatModelSelection {
  providerId: string;
  modelId: string;
}

export const getChatModelSelection = async (): Promise<ChatModelSelection | null> => {
  const storage = getAppContainer().storage;
  const providerId: string = await storage.get<string>(SELECTED_PROVIDER_KEY, '');
  const modelId: string = await storage.get<string>(SELECTED_MODEL_KEY, '');
  if (providerId.length === 0 || modelId.length === 0) return null;
  return { providerId, modelId };
};

export const saveChatModelSelection = async (sel: ChatModelSelection): Promise<void> => {
  const storage = getAppContainer().storage;
  await storage.set<string>(SELECTED_PROVIDER_KEY, sel.providerId);
  await storage.set<string>(SELECTED_MODEL_KEY, sel.modelId);
};


let chatKv: KeyValueStore | null = null;
export const getChatKvStore = (): KeyValueStore => {
  if (chatKv === null) {
    chatKv = createChatKvStore(getAppContainer().storage);
  }
  return chatKv;
};

let chatRepo: ConversationRepository | null = null;
export const getChatRepository = (): ConversationRepository => {
  if (chatRepo === null) {
    // D-021:relationalStore 真实实现;init 失败内部回退 memory(hilog 警告,非静默)
    chatRepo = createChatRdbRepository();
  }
  return chatRepo;
};

// D-055:compact 存储单例(ConversationContextRepository.kt 的 KV 形态)
let compactStore: CompactStore | null = null;
export const getCompactStore = (): CompactStore => {
  if (compactStore === null) {
    compactStore = createEntryCompactStore(getChatKvStore());
  }
  return compactStore;
};

// D-058:session 访问授权 store 单例(SessionAccessGrantStore.kt;签发方 history
//   subagent 未移植,store 仅承载 validate/recordUse)
let sessionGrantStore: SessionAccessGrantStore | null = null;
export const getSessionGrantStore = (): SessionAccessGrantStore => {
  if (sessionGrantStore === null) {
    sessionGrantStore = new SessionAccessGrantStore();
  }
  return sessionGrantStore;
};

// D-120:McpManager 单例(McpManager.kt 全文移植的容器位);
//   init 顺序:settings store hydrate(首发射回放)→ manager 构建(订阅即收真值,
//   与 Android settingsFlow collect 时序等价);ChatPage.aboutToAppear 调 initMcpManager
let mcpManager: McpManager | null = null;
export const getMcpManager = (): McpManager => {
  if (mcpManager === null) {
    mcpManager = new McpManager({
      http: createMcpHttpPort(),
      settings: getMcpSettingsStore(),
      files: getMcpFilesStore(),
      log: (msg: string): void => {
        hilog.info(0x0001, 'Mcp.Manager', '%{public}s', msg);
      },
    });
  }
  return mcpManager;
};

export const initMcpManager = async (): Promise<void> => {
  await initMcpSettingsStore();
  getMcpManager();
};

// D-122:AgentToolActivityStore(AgentInfraModule.kt:38 single)全局单例 —
//   工具活动单槽时间线;ChatPage 订阅显示,mcp__ 组 execute 上报
let conversationRunService: ConversationRunService | null = null;
export const getConversationRunService = (): ConversationRunService => {
  if (conversationRunService === null) conversationRunService = new ConversationRunService();
  return conversationRunService;
};

let toolActivityStore: AgentToolActivityStore | null = null;
export const getToolActivityStore = (): AgentToolActivityStore => {
  if (toolActivityStore === null) {
    toolActivityStore = new AgentToolActivityStore();
  }
  return toolActivityStore;
};

// D-123:SkillManager 单例(SkillManager.kt + Koin single)— filesDir/skills 根;
//   deleteSkill 级联清理全 assistants enabledSkills(:114-125)→ 异步持久化
//   (域层同步回调 → fire-and-forget + warn 日志,登记)
let skillManager: SkillManager | null = null;
export const getSkillManager = (): SkillManager => {
  if (skillManager === null) {
    const ctx = AppStorage.get<Context>('abilityContext') as Context;
    skillManager = new SkillManager({
      port: createEntrySkillFilePort(),
      skillsRoot: `${ctx.filesDir}/skills`,
      // D-126:内置技能 rawfile 资产 + Log.w(SkillManager.kt:82)→ hilog
      assets: createEntryBuiltinSkillAssetPort(),
      log: (message: string): void => {
        hilog.warn(DOMAIN, TAG, '%{public}s', message);
      },
      onSkillDeleted: (name: string): void => {
        loadAssistants(getChatKvStore())
          .then(async (list: Assistant[] | null): Promise<void> => {
            if (list === null) return;
            const next: Assistant[] = list.map((a: Assistant): Assistant =>
              a.enabledSkills.indexOf(name) >= 0
                ? patchAssistant(a, {
                  enabledSkills: a.enabledSkills.filter((n: string): boolean => n !== name),
                })
                : a);
            await saveAssistants(getChatKvStore(), next);
          })
          .catch((e: Error): void => {
            hilog.warn(DOMAIN, TAG, 'onSkillDeleted persist failed: %{public}s', String(e));
          });
      },
    });
  }
  return skillManager;
};

// D-123:skills 导入用 workspace 读面(POSIX 镜像目录;SAF 层不移植,登记)
let skillWorkspacePort: SkillWorkspacePort | null = null;
export const getSkillWorkspacePort = (): SkillWorkspacePort => {
  if (skillWorkspacePort === null) {
    skillWorkspacePort = createEntrySkillWorkspacePort();
  }
  return skillWorkspacePort;
};

// D-124:PosixWorkspaceManager 单例(WorkspaceManager.kt + Koin single)—
//   根 = filesDir/amberagent/workspace-mirror(SAF 层不移植,镜像即 workspace,
//   configured 恒 true,登记);ChatPage workspace_files 门 → createWorkspaceTools
let workspaceManager: PosixWorkspaceManager | null = null;
export const getWorkspaceManager = (): PosixWorkspaceManager => {
  if (workspaceManager === null) {
    workspaceManager = createEntryWorkspaceManager();
  }
  return workspaceManager;
};

// D2-1:自选工作区目录变更后重建管理器(rootAbs 在构造时注入)
export const resetWorkspaceManager = (): void => {
  workspaceManager = null;
  recipeStorePromise = null;
};

let recipeStorePromise: Promise<RecipeStore> | null = null;
export const getRecipeStore = (): Promise<RecipeStore> => {
  if (recipeStorePromise === null) {
    const context: common.UIAbilityContext = AppStorage.get<common.UIAbilityContext>('abilityContext') as common.UIAbilityContext;
    const init: Promise<RecipeStore> = createEntryRecipeStore(context, getWorkspaceManager());
    init.catch((): void => { if (recipeStorePromise === init) recipeStorePromise = null; });
    recipeStorePromise = init;
  }
  return recipeStorePromise;
};

const recipePagePrimitives = async (assistant: Assistant): Promise<AgentTool[]> => {
  const terminal: boolean = assistant.localTools.indexOf('terminal') >= 0;
  const python: boolean = assistant.localTools.indexOf('python') >= 0;
  return filterToolProfile(createAssistantLocalPrimitives({
    assistant, conversationId: '', workspace: getWorkspaceManager(), activityStore: getToolActivityStore(),
    terminalRuntime: terminal ? await getTerminalRuntime() : null,
    moshRuntime: terminal ? await getMoshRuntime() : null,
    sshProfiles: terminal ? await getSSHProfileStore() : null,
    pythonRuntime: python ? await getPythonRuntime() : null,
  }), assistant.toolProfile).tools;
};

export const getRecipePrimitives = async (): Promise<AgentTool[]> => {
  const assistants: Assistant[] = await getChatAssistants();
  return recipePagePrimitives(assistants[0]);
};

export const startRecipeFromPage = async (
  name: string, expectedHash: string, inputs: JsonObject, shouldNavigate: () => boolean,
): Promise<void> => {
  const assistants: Assistant[] = await getChatAssistants();
  const assistant: Assistant = assistants[0];
  await startEntryRecipeFromPage({ store: await getRecipeStore(), primitives: await recipePagePrimitives(assistant),
    assistant, repository: getChatRepository() }, name, expectedHash, inputs, shouldNavigate);
};

let pluginStorePromise: Promise<PluginStore> | null = null;
export const getPluginStore = (): Promise<PluginStore> => {
  if (pluginStorePromise === null) {
    const context: common.UIAbilityContext = AppStorage.get<common.UIAbilityContext>('abilityContext') as common.UIAbilityContext;
    const init: Promise<PluginStore> = createEntryPluginStore(context, getPluginPrimitives);
    init.catch((): void => { if (pluginStorePromise === init) pluginStorePromise = null; });
    pluginStorePromise = init;
  }
  return pluginStorePromise;
};

const pluginPagePrimitives = async (assistant: Assistant): Promise<AgentTool[]> => {
  ensureSearchSdk();
  await initMcpManager();
  const kv = getChatKvStore();
  const search = await loadSearchPrefs(kv);
  const webMount: boolean = assistant.localTools.indexOf('webmount') >= 0;
  const webMountEnabled: boolean = webMount || await kv.get('webmount_global_enabled') === 'true';
  const base: AgentTool[] = filterToolProfile([
    ...await recipePagePrimitives(assistant),
    ...createMcpServerTools({ manager: getMcpManager(), assistantIds: [...assistant.mcpServers] }),
    ...(search.enableWebSearch ? createSearchTools(search) : []),
    ...(webMountEnabled ? createWebMountTools(getWebMountSession(), false, webMount) : []),
  ], assistant.toolProfile).tools;
  const registry = createToolRegistry(base);
  return [...registry.tools(), ...createRegistryIntrospectionTools(registry),
    createToolSearchTool(registry, assistant.toolProfile)];
};

export const getPluginPrimitives = async (): Promise<AgentTool[]> => {
  const assistants: Assistant[] = await getChatAssistants();
  return pluginPagePrimitives(assistants[0]);
};

export const startPluginFromPage = async (
  toolName: string, input: JsonObject, title: string, shouldNavigate: () => boolean, expectedPackageHash: string | null = null,
): Promise<void> => {
  const assistants: Assistant[] = await getChatAssistants();
  const assistant: Assistant = assistants[0];
  if (await resolveChatRuntime(assistant.id) === null) throw new Error('请先配置可用的 Chat 供应商和模型。');
  const primitives: AgentTool[] = await pluginPagePrimitives(assistant);
  const store: PluginStore = await getPluginStore();
  const installed: InstalledPlugin[] = await store.listInstalled(primitives);
  if (expectedPackageHash !== null && !installed.some((item: InstalledPlugin): boolean => {
    const candidate: PluginPackage | null = item.package;
    return item.enabled && candidate !== null && candidate.hash === expectedPackageHash
      && candidate.tools.some((tool: PluginDescriptor): boolean => tool.toolId === toolName);
  })) {
    throw new Error('插件已更新或停用，请重新选择。');
  }
  const parent: UIMessagePartTool = { type: 'tool', toolCallId: newId(), toolName,
    input: JSON.stringify(input), output: [], approvalState: { type: 'auto' }, metadata: { plugin_manual: true } };
  await startEntryPluginFromPage({ assistant, primitives, adapter: createEntryPluginAdapter(store, installed, primitives),
    repository: getChatRepository() }, parent, title, shouldNavigate);
};

export const exportPluginFromPage = async (id: string, hash: string, destination: string): Promise<void> => {
  await exportEntryPlugin(await getPluginStore(), getWorkspaceManager(), id, hash, destination);
};

export const getChatAssistants = async (): Promise<Assistant[]> => {
  const kv = getChatKvStore();
  const loaded = await loadAssistants(kv);
  if (loaded !== null && loaded.length > 0) return loaded;
  // 空库:种子落库 — 临时 id 不存在于 KV 时,扩展/工具/MCP/记忆等子页
  // (裸 loadAssistants 按 id find)会报"助手不存在"
  const seeded: Assistant[] = [makeAssistant({ name: '默认助手' })];
  await saveAssistants(kv, seeded);
  return seeded;
};

export interface ChatRuntime {
  api: OpenAIChatApi;
  resolvedChoice: ChatProviderChoice;
  assistant: Assistant;
  params: TextGenerationParams;
  contextMessageSize: number;
  // D-055:压缩规划用的模型上下文窗口(Android Model.contextWindowTokens,null → 引擎默认窗口)
  modelContextWindowTokens: number | null;
  // D-071b:model.inputModalities.contains(Modality.IMAGE)(GenerationHandler.kt:431
  //   canUseVisionFallback 前半 + OcrTransformer.kt:50 transform 门)
  modelSupportsImageInput: boolean;
  // D-121:model.abilities(ChatService.kt:1278 tools_warning 门 —
  //   !abilities.contains(ModelAbility.TOOL))
  modelAbilities: ModelAbility[];
  // D-123:skillManager.listSkills() 快照(ChatService.kt:2241 createSkillTools
  //   入参;createRunTools 时机 ≈ resolveChatRuntime 时机 = 每次发送)
  allSkills: SkillMetadata[];
  terminalRuntime: TerminalRuntime | null;
  pythonRuntime: PythonRuntime | null;
  moshRuntime: MoshRuntime | null;
  sshProfiles: SSHProfileStore | null;
}

export interface ChatProviderChoice {
  providerContainerId: string;
  // D-046:三变体(openai/google/claude)统一解析;api 层均为结构 OpenAIChatApi
  provider: ProviderSetting;
  model: ProviderModel;
}

// D-084:Model.findProvider(PreferencesStore.kt:500-507)— 生成路径 checkOverwrite
//   恒 true(GenerationHandler.kt:155):model.providerOverwrite 非 null →
//   overwrite 副本(models = [])替代容器 provider;容器反查未命中时 overwrite
//   不兜底(此处容器已命中,语义逐字)
const effectiveProvider = (p: ProviderSetting, m: ProviderModel): ProviderSetting =>
  m.providerOverwrite !== null ? copyProviderSettingWithModels(m.providerOverwrite, []) : p;

const makeProviderChoice = (
  p: ProviderSetting, m: ProviderModel,
): ChatProviderChoice | null => {
  const provider: ProviderSetting = effectiveProvider(p, m);
  return hasUsableAuth(provider) ? { provider, model: m, providerContainerId: p.id } : null;
};

// D-080:导出供 ChatPage 发送门(canSendWithoutChatModel 等价物)使用
export const resolveChatProviderChoice = async (
  assistant?: Assistant,
): Promise<ChatProviderChoice | null> => {
  const providers = await loadProviders(getChatKvStore());
  if (providers === null) return null;

  // Assistant-scoped model takes precedence over the global selection.
  if (assistant !== undefined && assistant.chatModelId !== null
    && assistant.chatModelId.trim().length > 0) {
    for (const p of providers) {
      if (!hasUsableAuth(p)) continue;
      for (const m of p.models) {
        if (m.id === assistant.chatModelId && m.type === 'chat') {
          const choice: ChatProviderChoice | null = makeProviderChoice(p, m);
          if (choice !== null) return choice;
        }
      }
    }
  }

  // 用户选择优先(D-023;D-046 起不再限定 openai 变体)
  const selection = await getChatModelSelection();
  if (selection !== null) {
    for (const p of providers) {
      if (p.id !== selection.providerId || !hasUsableAuth(p)) continue;
      for (const m of p.models) {
        if (m.modelId === selection.modelId && m.type === 'chat') {
          const choice: ChatProviderChoice | null = makeProviderChoice(p, m);
          if (choice !== null) return choice;
        }
      }
    }
  }
  // 回退 D-019 策略:第一个有可用认证且含 chat 模型的 provider + 首个 chat 模型
  for (const p of providers) {
    if (!hasUsableAuth(p)) continue;
    for (const m of p.models) {
      if (m.type !== 'chat') continue;
      const choice: ChatProviderChoice | null = makeProviderChoice(p, m);
      if (choice !== null) return choice;
    }
  }
  return null;
};

// ===== D-087:任务模型解析链 =====
// Android 锚点:
//   PreferencesKeys.kt(infra):29-30/:36 — 'title_model'/'suggestion_model'/'compress_model'
//   ChatPrefs.kt:71-77 — parseUuidOrNull 失败 → DEFAULT_AUTO_MODEL_ID
//   PreferencesStore.kt:479-481 — resolveTaskChatModel = findModelById ?: getCurrentChatModel
export const TASK_TITLE_MODEL_KEY: string = 'title_model';
export const TASK_SUGGESTION_MODEL_KEY: string = 'suggestion_model';
export const TASK_COMPRESS_MODEL_KEY: string = 'compress_model';
// 生图模型槽(Android imageGenerationModel 语义;AUTO/空 = 未配置,工具不装配)
export const TASK_IMAGE_MODEL_KEY: string = 'image_generation_model';
export const TASK_DEEPREAD_MODEL_KEY: string = 'deepread_task_model';

export interface TaskModelIds {
  titleModelId: string;
  suggestionModelId: string;
  compressModelId: string;
  imageModelId: string;
}

export interface TaskModelReferences {
  titleModel: TaskModelReference;
  suggestionModel: TaskModelReference;
  compressModel: TaskModelReference;
  imageModel: TaskModelReference;
}

export const readTaskModelReference = (key: string): Promise<TaskModelReference> =>
  loadTaskModelReference(getChatKvStore(), key);

export const saveTaskModelPair = (key: string, pair: TaskModelPair): Promise<void> =>
  storeTaskModelPair(getChatKvStore(), key, pair);

export const loadTaskModelReferences = async (): Promise<TaskModelReferences> => ({
  titleModel: await readTaskModelReference(TASK_TITLE_MODEL_KEY),
  suggestionModel: await readTaskModelReference(TASK_SUGGESTION_MODEL_KEY),
  compressModel: await readTaskModelReference(TASK_COMPRESS_MODEL_KEY),
  imageModel: await readTaskModelReference(TASK_IMAGE_MODEL_KEY),
});

const readTaskModelId = async (key: string): Promise<string> => {
  return taskModelReferenceId(await readTaskModelReference(key));
};

export const loadTaskModelIds = async (): Promise<TaskModelIds> => ({
  titleModelId: await readTaskModelId(TASK_TITLE_MODEL_KEY),
  suggestionModelId: await readTaskModelId(TASK_SUGGESTION_MODEL_KEY),
  compressModelId: await readTaskModelId(TASK_COMPRESS_MODEL_KEY),
  imageModelId: await readTaskModelId(TASK_IMAGE_MODEL_KEY),
});

// D-094:任务模型写路径(SettingModelPage.kt onSelect/onClear)—
//   onSelect → it.id 直存;onClear → DEFAULT_AUTO_MODEL_ID 字面量
//   (ChatPrefs.kt:94-96 writeTo 直存 uuid 字符串;读侧空/缺失亦归一 AUTO)
export const saveTaskModelId = async (key: string, modelUuid: string): Promise<void> => {
  const storage = getAppContainer().storage;
  await storage.set<string>(key, modelUuid);
};

// D-094:任务提示词读写 —
//   PreferencesKeys.kt:32-37 — 'title_prompt'/'suggestion_prompt'/'compress_prompt'
//   ChatPrefs.kt:81-84 读:键缺失 → DEFAULT_*;已存值(含空串)逐字返回
//   ChatPrefs.kt:99-102 写:直存字符串
export const TASK_TITLE_PROMPT_KEY: string = 'title_prompt';
export const TASK_SUGGESTION_PROMPT_KEY: string = 'suggestion_prompt';
export const TASK_COMPRESS_PROMPT_KEY: string = 'compress_prompt';

export interface TaskPrompts {
  titlePrompt: string;
  suggestionPrompt: string;
  compressPrompt: string;
}

export const loadTaskPrompts = async (): Promise<TaskPrompts> => {
  const storage = getAppContainer().storage;
  const title: string = await storage.get<string>(TASK_TITLE_PROMPT_KEY, DEFAULT_TITLE_PROMPT);
  const suggestion: string =
    await storage.get<string>(TASK_SUGGESTION_PROMPT_KEY, DEFAULT_SUGGESTION_PROMPT);
  const compress: string =
    await storage.get<string>(TASK_COMPRESS_PROMPT_KEY, DEFAULT_COMPRESS_PROMPT);
  return { titlePrompt: title, suggestionPrompt: suggestion, compressPrompt: compress };
};

export const saveTaskPrompt = async (key: string, value: string): Promise<void> => {
  const storage = getAppContainer().storage;
  await storage.set<string>(key, value);
};

// 当前 chat 模型的 uuid 等价物(Android settings.chatModelId 对应):
//   选择态 → providers 内命中模型的 m.id;未选择 → AUTO(findById 必失,
//   resolveTaskChatModel 内部回退 getCurrentChatModel,语义等价)
const resolveCurrentChatModelUuid = async (providers: ProviderSetting[]): Promise<string> => {
  const selection = await getChatModelSelection();
  if (selection === null) return DEFAULT_AUTO_MODEL_ID;
  for (const p of providers) {
    if (p.id === selection.providerId) {
      for (const m of p.models) {
        if (m.modelId === selection.modelId && m.type === 'chat') return m.id;
      }
    }
  }
  return DEFAULT_AUTO_MODEL_ID;
};

// 查找已配置且可认证的 chat 模型，不包含当前聊天模型兜底。
const resolveConfiguredTaskChatModel = async (
  modelId: string,
): Promise<ChatProviderChoice | null> => {
  const providers = await loadProviders(getChatKvStore());
  if (providers === null) return null;
  const found: ProviderModel | null = findModelByIdInProviders(providers, modelId);
  if (found === null || found.type !== 'chat') return null;
  for (const p of providers) {
    if (!hasUsableAuth(p)) continue;
    for (const m of p.models) {
      if (m.id === found.id && m.type === 'chat') {
        return makeProviderChoice(p, m);
      }
    }
  }
  return null;
};

// 小说固定模型必须精确匹配已持久化的 provider.id + ProviderModel.id。
// 这里故意不委托任务模型解析链：后者的全局 Chat fallback 适合辅助任务，
// 但会破坏 Novel fixed policy 的 fail-closed 语义。
const resolveConfiguredProviderChatModel = async (
  providerId: string, modelId: string,
): Promise<ChatProviderChoice | null> => {
  const providers = await loadProviders(getChatKvStore());
  if (providers === null) return null;
  for (const provider of providers) {
    if (provider.id !== providerId) continue;
    if (!hasUsableAuth(provider)) return null;
    for (const model of provider.models) {
      if (model.id === modelId && model.type === 'chat') {
        return makeProviderChoice(provider, model);
      }
    }
    return null;
  }
  return null;
};

export interface NovelModelOption {
  providerId: string;
  // ProviderModel.id；固定策略只保存这个配置 UUID，不保存模型 API 名。
  modelId: string;
  label: string;
  providerLabel: string;
  available: boolean;
}

export interface DeepReadModelOption {
  providerId: string;
  modelId: string;
  label: string;
  providerLabel: string;
  available: boolean;
}

export const loadDeepReadModelOptions = async (): Promise<DeepReadModelOption[]> => {
  const providers: ProviderSetting[] = (await loadProviders(getChatKvStore())) ?? [];
  const options: DeepReadModelOption[] = [];
  for (const provider of providers) {
    for (const model of provider.models) {
      if (model.type !== 'chat') continue;
      options.push({
        providerId: provider.id, modelId: model.id,
        label: model.displayName.trim().length > 0 ? model.displayName : model.modelId,
        providerLabel: provider.name,
        available: makeProviderChoice(provider, model) !== null,
      });
    }
  }
  return options;
};

// C5 项目策略 picker 的唯一 provider/model 事实源。不可用模型仍返回，
// 以便固定选择失效后 UI 能原位呈现并引导用户修复，而不是静默替换。
export const loadNovelModelOptions = async (): Promise<NovelModelOption[]> => {
  const providers = await loadProviders(getChatKvStore());
  if (providers === null) return [];
  const options: NovelModelOption[] = [];
  for (const provider of providers) {
    for (const model of provider.models) {
      if (model.type !== 'chat') continue;
      options.push({
        providerId: provider.id,
        modelId: model.id,
        label: model.displayName.trim().length > 0 ? model.displayName : model.modelId,
        providerLabel: provider.name,
        available: hasUsableAuth(provider) && makeProviderChoice(provider, model) !== null,
      });
    }
  }
  return options;
};

// resolveTaskChatModel(PreferencesStore.kt:479-481)—
//   findModelById(modelId) ?: getCurrentChatModel(= resolveChatProviderChoice)
export const resolveTaskChatModelChoice = async (
  modelId: string, fallbackChoice?: ChatProviderChoice,
): Promise<ChatProviderChoice | null> =>
  (await resolveConfiguredTaskChatModel(modelId)) ?? fallbackChoice ?? resolveChatProviderChoice();

export const resolveConfiguredTaskModel = async (
  reference: TaskModelReference, type: ModelType,
): Promise<ChatProviderChoice | null> => {
  if (reference.kind === 'legacy' && type === 'chat') {
    return resolveConfiguredTaskChatModel(reference.modelId);
  }
  const providers: ProviderSetting[] | null = await loadProviders(getChatKvStore());
  if (providers === null) return null;
  const configured = findTaskProviderModel(providers, reference, type);
  return configured === null ? null : makeProviderChoice(configured.provider, configured.model);
};

export const resolveTaskModelReference = async (
  reference: TaskModelReference, fallbackChoice?: ChatProviderChoice,
): Promise<ChatProviderChoice | null> =>
  (await resolveConfiguredTaskModel(reference, 'chat')) ?? fallbackChoice ?? resolveChatProviderChoice();

export const resolveTaskModelReferenceChain = async (
  references: TaskModelReference[], fallbackChoice?: ChatProviderChoice,
): Promise<ChatProviderChoice | null> => {
  for (const reference of references) {
    const choice: ChatProviderChoice | null = await resolveConfiguredTaskModel(reference, 'chat');
    if (choice !== null) return choice;
  }
  return fallbackChoice ?? resolveChatProviderChoice();
};

export const taskModelReferenceForChoice = (choice: ChatProviderChoice): TaskModelReference =>
  taskModelReferenceFromId(choice.model.id, choice.providerContainerId);

export const currentChatModelReference = async (): Promise<TaskModelReference> => {
  const choice: ChatProviderChoice | null = await resolveChatProviderChoice();
  return choice === null ? taskModelReferenceFromId(DEFAULT_AUTO_MODEL_ID) : taskModelReferenceForChoice(choice);
};

// 链式解析:候选全部按顺序尝试，最后才回退当前聊天模型。
export const resolveTaskModelChain = async (
  candidates: string[], fallbackChoice?: ChatProviderChoice,
): Promise<ChatProviderChoice | null> => {
  for (const id of candidates) {
    const choice: ChatProviderChoice | null = await resolveConfiguredTaskChatModel(id);
    if (choice !== null) return choice;
  }
  return fallbackChoice ?? resolveChatProviderChoice();
};

// 当前 chat 模型 uuid(供任务链 chatModelId 实参)
export const currentChatModelUuid = async (): Promise<string> => {
  const providers = await loadProviders(getChatKvStore());
  if (providers === null) return DEFAULT_AUTO_MODEL_ID;
  return resolveCurrentChatModelUuid(providers);
};

// 头部展示用:「provider 名 / modelId」;未配置返回 ''
export const describeChatModel = async (): Promise<string> => {
  const choice = await resolveChatProviderChoice();
  if (choice === null) return '';
  return `${choice.provider.name} / ${choice.model.modelId}`;
};

// D-087:三变体 api 构建公共化(resolveChatRuntime 与任务模型链路共用;
//   分发/auth/header 语义与 D-046/D-078 逐字一致,未落地传输显式抛错)
export const buildProviderApi = (
  chosen: ProviderSetting, customHeaderRecord: Record<string, string>,
  oauthAccountId: string = '', responses?: NovelResponsesStreamOptions,
): OpenAIChatApi => {
  // useResponseApi=true 走 createOpenAIChatApi 内部分流(Responses);不再在此 throw
  if (!hasUsableAuth(chosen)) {
    throw new Error('Provider 鉴权未配置或当前 Harmony 版本不支持');
  }
  if (chosen.type === 'claude') {
    const claudeSetting: ProviderSettingClaude = chosen as ProviderSettingClaude;
    return createClaudeChatApi({
      http: getAppContainer().httpClient,
      setting: claudeSetting,
      headers: (): Record<string, string> => customHeaderRecord,
      encodeImage: encodeImageDetailed,
      // D-078:每请求 LRU 轮换(ClaudeProvider.kt:68/:74,PD-008 消除)
      keyRoulette: getChatKeyRoulette(),
    });
  }
  if (chosen.type === 'google') {
    const googleSetting: ProviderSettingGoogle = chosen as ProviderSettingGoogle;
    return createGoogleChatApi({
      http: getAppContainer().httpClient,
      setting: googleSetting,
      headers: (): Record<string, string> => customHeaderRecord,
      encodeImage: encodeImageDetailed,
      resolveAuth: resolveEntryGoogleAuth,
      // D-078:每请求 LRU 轮换(GoogleProvider.kt:77/:134)
      keyRoulette: getChatKeyRoulette(),
    });
  }
  const openaiSetting: ProviderSettingOpenAIVariant = chosen as ProviderSettingOpenAIVariant;
  // D-078:bearerResolver 语义(ChatCompletionsAPI.kt:103-105)— token 不再
  //   一次性解析,headers() 每请求调用时轮换(ChatCompletionsAPI.kt:102-105
  //   bearerResolver 每请求语义;PD-008 消除)
  const nextToken: () => string = (): string =>
    getChatKeyRoulette().next(openaiSetting.apiKey, openaiSetting.id);
  return createOpenAIChatApi({
    http: getAppContainer().httpClient,
    setting: makeProviderSettingOpenAI({
      id: openaiSetting.id,
      enabled: openaiSetting.enabled,
      name: openaiSetting.name,
      baseUrl: openaiSetting.baseUrl,
      chatCompletionsPath: openaiSetting.chatCompletionsPath,
      useResponseApi: openaiSetting.useResponseApi,
      authMode: openaiSetting.authMode,
      brand: openaiSetting.brand,
    }),
    headers: (): Record<string, string> => {
      const auth: Record<string, string> =
        openAIAuthHeaders(openaiSetting.authMode, openaiSetting.baseUrl, nextToken());
      // codex 后端要求 ChatGPT-Account-Id(有 accountId 时注入)
      if (oauthAccountId.length > 0) auth['ChatGPT-Account-Id'] = oauthAccountId;
      // grok cli-chat-proxy 要求 CLI 身份头(iOS IOSGrokCliProxyIdentity 同款)
      if (openaiSetting.authMode === 'grok_oauth') {
        auth['User-Agent'] = 'grok-shell/0.2.101 (ios; arm64)';
        auth['x-grok-client-identifier'] = 'grok-shell';
      }
      // customHeaders 后写覆盖(auth 冲突时 custom 赢,同 claude/google 层语义)
      for (const k of Object.keys(customHeaderRecord)) auth[k] = customHeaderRecord[k];
      return auth;
    },
    // D-038:图片编码(data:/http(s): 直通,file:// 读文件编码;失败即抛)
    encodeImage: encodeImageForChat,
    // P6-01:官方 OpenAI + api_key 时注入 resume cursor KV Port
    resumeStore: responses === undefined && openaiSetting.useResponseApi === true
      ? createKvResponseResumeStore(getChatKvStore())
      : undefined,
    enableResponsesResume: openaiSetting.useResponseApi === true,
    runId: responses?.runId,
    resumeFrom: responses?.resumeFrom,
    resumeMessages: responses?.partialMessages,
    onResumeCheckpoint: responses?.onCheckpoint,
  });
};

// Auxiliary tasks keep their existing model/parameter/header policy and share OAuth refresh.
export const prepareProviderApi = async (
  provider: ProviderSetting, customHeaderRecord: Record<string, string>,
): Promise<OpenAIChatApi> => {
  const headers: Record<string, string> = {};
  for (const key of Object.keys(customHeaderRecord)) headers[key] = customHeaderRecord[key];
  const auth: ProviderAuthSnapshot = await prepareProviderAuth({
    provider, refreshCodex: getValidCodexToken, refreshGrok: getValidGrokToken,
  });
  return buildProviderApi(auth.provider, headers, auth.accountId);
};

const resolveProviderRuntimeCore = (
  choice: ChatProviderChoice, assistant: Assistant,
): Promise<ProviderRuntimeSnapshot> => prepareProviderRuntime({
  provider: choice.provider, model: choice.model, assistant,
  refreshCodex: getValidCodexToken,
  refreshGrok: getValidGrokToken,
  buildApi: buildProviderApi,
});

const resolveDeepReadRuntimeCore = async (): Promise<ProviderRuntimeSnapshot> => {
  const reference: TaskModelReference = await readTaskModelReference(TASK_DEEPREAD_MODEL_KEY);
  const choice: ChatProviderChoice | null = reference.kind === 'auto'
    ? await resolveChatProviderChoice()
    : await resolveConfiguredTaskModel(reference, 'chat');
  if (choice === null) {
    throw new Error(reference.kind === 'auto'
      ? '请先在提供商设置中配置可用的聊天模型。'
      : '指定的深读模型已不可用，请检查提供商认证或重新选择模型。');
  }
  const assistants: Assistant[] = await getChatAssistants();
  return resolveProviderRuntimeCore(choice, assistants[0]);
};

export const resolveChatRuntime = async (
  assistantId?: string, exactModelId?: string, exactProviderId?: string,
): Promise<ChatRuntime | null> => {
  // 助手解析(D-028):会话绑定 assistantId 优先,未命中/未传 → 首个(D-019 延续)
  const assistants: Assistant[] = await getChatAssistants();
  let assistant: Assistant = assistants[0];
  if (assistantId !== undefined) {
    for (const a of assistants) {
      if (a.id === assistantId) {
        assistant = a;
        break;
      }
    }
  }
  // Novel/任务显式指定模型时必须精确解析，不得静默改用默认聊天模型。
  // 普通 Chat 仍维持 Assistant.chatModelId → 全局选择的既有顺序。
  const choice = exactProviderId !== undefined
    ? await resolveConfiguredProviderChatModel(exactProviderId, exactModelId ?? '')
    : exactModelId !== undefined && exactModelId.length > 0
      ? await resolveConfiguredTaskChatModel(exactModelId)
    : await resolveChatProviderChoice(assistant);
  if (choice === null) return null;
  const runtime: ProviderRuntimeSnapshot = await resolveProviderRuntimeCore(choice, assistant);
  const model: ProviderModel = runtime.model;
  const params: TextGenerationParams = runtime.params;
  const api: OpenAIChatApi = runtime.api;
  // Await real dependencies before exposing terminal tools to the provider.
  const terminalEnabled: boolean = assistant.localTools.includes('terminal');
  const terminalRuntime: TerminalRuntime | null = terminalEnabled ? await getTerminalRuntime() : null;
  const moshRuntime: MoshRuntime | null = terminalEnabled ? await getMoshRuntime() : null;
  const pythonRuntime: PythonRuntime | null = assistant.localTools.includes('python') ? await getPythonRuntime() : null;
  const sshProfiles: SSHProfileStore | null = terminalEnabled ? await getSSHProfileStore() : null;
  return {
    api, resolvedChoice: choice, assistant, params,
    contextMessageSize: runtime.defaults.contextMessageSize,
    modelContextWindowTokens: model.contextWindowTokens,
    modelSupportsImageInput: model.inputModalities.includes('image'),
    modelAbilities: model.abilities,
    // D-123:技能库快照(每次发送重新扫描;SkillManager 内部缓存,变更后失效)
    allSkills: getSkillManager().listSkills(),
    terminalRuntime, pythonRuntime, moshRuntime, sshProfiles,
  };
};

const novelWorkspaceToolPort = (projectId: string): NovelWorkspaceToolPort => ({
  projectOperation: (name, input) => executeNovelProjectOperation(getNovelCreation(), projectId, name, input),
  list: async (input) => {
    const result = await getNovelCreation().workspaceList(projectId, input.path ?? '');
    return { root: result.root, entries: result.entries };
  },
  read: async (input) => {
    const result = await getNovelCreation().workspaceRead(projectId, input.path);
    return { path: result.path, content: result.content };
  },
  grep: async (input) => {
    const result = await getNovelCreation().workspaceGrep(
      projectId, input.query, input.path ?? '', input.max_results ?? 50);
    return {
      query: result.query,
      // JsonObject 的索引签名不能被 ArkTS class/interface 实现；保留与 grep
      // CLI 相同的 path:line:text 事实格式，避免把未类型化对象绕过严格检查。
      matches: result.matches.map((match): string => `${match.path}:${match.line}:${match.text}`),
    };
  },
  status: async () => {
    const status = await getNovelCreation().workspaceStatus(projectId);
    return {
      active_branch_id: status.activeBranchId,
      active_branch_name: status.activeBranchName,
      head: status.cas.head,
      tree_digest: status.cas.treeDigest,
      can_undo: status.canUndo,
      unresolved_from_ordinal: status.unresolvedFromOrdinal,
      plot_stale: status.plotStale,
      pending_proposal_count: status.pendingProposalCount,
    };
  },
  write: async (input) => {
    const proposal = await getNovelCreation().workspaceWriteResult(
      projectId, input.proposal_id, input.patches);
    return {
      proposal_id: proposal.proposalId,
      status: proposal.status,
      branch_id: proposal.expectedCas.branchId,
      patch_count: proposal.patches.length,
    };
  },
  audit: async (signal?: AbortSignalLike) => {
    const controller: NovelAuditController = new NovelAuditController();
    const cancel: () => void = (): void => { controller.cancel(); };
    if (signal !== undefined) {
      if (signal.aborted) controller.cancel();
      else signal.addEventListener?.('abort', cancel);
    }
    try {
      const report = await getNovelCreation().runContinuityAudit(projectId, controller);
      return novelAuditToolResult(report);
    } finally {
      if (signal !== undefined) signal.removeEventListener?.('abort', cancel);
    }
  },
  ingestSettingProposals: async (sourceMessageId: string, text: string) => {
    const proposals = await getNovelCreation().ingestSettingProposals(
      projectId, sourceMessageId, text);
    return {
      ingested: proposals.length,
      titles: proposals.map((p): string => p.title),
    };
  },
});

const novelRuntimeConfig = async (
  runtime: ChatRuntime, projectId: string, snapshot?: NovelRuntimeSnapshot,
): Promise<NovelInteractiveRuntimeConfig> => {
  const frozen: NovelFrozenRuntimeConfiguration | null = snapshot === undefined ? null :
    readNovelRuntimeConfiguration(snapshot.configurationJson, runtime.resolvedChoice.provider,
      runtime.resolvedChoice.model, runtime.assistant);
  const params: TextGenerationParams = frozen === null ? runtime.params : frozen.params;
  const assistant: Assistant = frozen === null ? runtime.assistant : frozen.assistant;
  const contextWindowTokens: number | null = frozen === null ? runtime.modelContextWindowTokens : frozen.contextWindowTokens;
  const defaultCap: number = resolveNovelMaxOutputTokens(
    null, params.maxTokens, contextWindowTokens);
  const customHeaders: Record<string, string> = {};
  for (const header of mergeCustomParams(runtime.assistant, runtime.resolvedChoice.model).customHeaders) {
    if (header.name.trim().length > 0) customHeaders[header.name.trim()] = header.value.trim();
  }
  const makeProvider = (definitions: ChatToolDefinition[], cap: number, taskOptions?: NovelStructuredTaskOptions,
    responses?: NovelResponsesStreamOptions): ChatStreamProvider => {
    const api: OpenAIChatApi = responses === undefined ? runtime.api :
      buildProviderApi(runtime.resolvedChoice.provider, customHeaders, '', responses);
    return asChatStreamProvider(api, (): TextGenerationParams =>
      applyNovelStateReasoning(makeNovelTextGenerationParams(params, definitions, cap, taskOptions),
        runtime.resolvedChoice.provider, taskOptions));
  };
  return {
    assistant,
    runtimeSnapshot: snapshot ?? {
      providerId: runtime.resolvedChoice.providerContainerId, modelId: runtime.resolvedChoice.model.id,
      configurationJson: freezeNovelRuntimeConfiguration(runtime.resolvedChoice.provider,
        runtime.resolvedChoice.model, assistant, params, contextWindowTokens),
      responsesProviderId: runtime.resolvedChoice.provider.id,
    },
    responsesProviderId: runtime.resolvedChoice.provider.id,
    responsesResumeSupported: runtime.resolvedChoice.provider.type === 'openai' &&
      supportsResponsesResume(runtime.resolvedChoice.provider),
    provider: makeProvider([], defaultCap),
    makeProviderForOutputTokens: (cap: number | null, taskOptions?: NovelStructuredTaskOptions,
      responses?: NovelResponsesStreamOptions): ChatStreamProvider =>
      makeProvider([], cap ?? defaultCap, taskOptions, responses),
    tools: [...createNovelWorkspaceTools(novelWorkspaceToolPort(projectId)), createAskUserTool(),
      ...await loadNovelResearchTools(getChatKvStore())],
    makeProviderForStep: (definitions: ChatToolDefinition[], cap?: number | null,
      responses?: NovelResponsesStreamOptions): ChatStreamProvider =>
      makeProvider(definitions, cap ?? defaultCap, undefined, responses),
    toolPromptModel: params.model,
    contextWindowTokens,
    maxOutputTokens: params.maxTokens,
    retrySetting: makeGenerationRetrySetting({}),
  };
};

// C5:Novel 与 Chat 共用生成深模块。global 复用现有 Chat 选择；fixed 只接受
// 指定 provider/model UUID 的精确命中。两种固定失败都返回 null，调用端原位 fail closed。
// 旧 ModelRegistry 不再参与 Novel 的模型解析，防止固定选择被 legacy/global 静默替换。
const resolveNovelInteractiveRuntime = async (
  modelTarget: NovelModelTarget, projectId: string, snapshot?: NovelRuntimeSnapshot,
): Promise<NovelInteractiveRuntimeConfig | null> => {
  const runtime: ChatRuntime | null = snapshot !== undefined
    ? await resolveChatRuntime(undefined, snapshot.modelId, snapshot.providerId)
    : modelTarget.kind === 'global'
    ? await resolveChatRuntime()
    : await resolveChatRuntime(undefined, modelTarget.modelId, modelTarget.providerId);
  return runtime === null ? null : novelRuntimeConfig(runtime, projectId, snapshot);
};
