// AppContainer — 依赖注入容器(ArkTS 兼容版)
// 组装 deepread 逻辑层与平台实现。
// 当前:用内存 mock 替代真实 @kit 平台实现(它们需逐个修 ArkTS 编译,留 TODO)。
// 真实 LLM 调用需配 API key 后由 OpenAiCompatibleAiClient 接入。
// 通知调度(Phase 7)用 @kit.NotificationKit 接入。

import type {
  RunManagerDeps, AiClient, UIMessage, Tool,
  DeepReadOutput, DeepReadScheduler, HttpClient, Storage,
  DeepReadRepository, SearchProviderRegistry, Notifier,
  SourcePrefetcher, FileStore, ModelRegistry,
  ModelCouncilTextRunner, ModelCouncilRuntimeSetting, ModelCouncilSeat,
} from '@amber/deepread-domain';
import {
  run, makeEmptyDeepReadOutput, isInterruptedPhase, createSourcePrefetcher,
  createStorageModelRegistry, createModelCouncilManager, ModelCouncilManager,
  createNovelCreation, NovelCreation, createFileNovelRepository,
  DeepReadPlaybookRepository,
} from '@amber/deepread-domain';
// 真实平台实现 import
import { createRcpHttpClient } from '../platform_impl/RcpHttpClient.ets';
import { createPreferencesStorage } from '../platform_impl/PreferencesStorage.ets';
import { createRdbRepository } from '../platform_impl/RdbRepository.ets';
import { createOpenAiCompatibleAiClient } from '../platform_impl/OpenAiCompatibleAiClient.ets';
import { createTavilyAndFallbackRegistry } from '../platform_impl/SearchRegistry.ets';
import { createNotificationNotifier } from '../platform_impl/NotificationNotifier.ets';
import { createFileStore } from '../platform_impl/FileStore.ets';
import { getChatKeyRoulette } from '../platform_impl/KeyRouletteSupport.ets';
import { createModelCouncilRunner } from '../platform_impl/ModelCouncilRunner.ets';
import { createNovelModelAdapter } from '../platform_impl/NovelModelAdapter.ets';
import { hilog } from '@kit.PerformanceAnalysisKit';
import type { Context } from '@kit.AbilityKit';
import type {
  ConversationRepository, KeyValueStore, Assistant, OpenAIChatApi, TextGenerationParams,
  ProviderSetting, ProviderSettingOpenAIVariant, ProviderSettingClaude, ProviderSettingGoogle,
  ProviderModel, CompactStore, ModelAbility, SkillMetadata, SkillWorkspacePort,
  AgentTool, ChatStreamProvider, ChatToolDefinition, CustomHeader,
  SubAgentRuntimeSetting, SubAgentOverride, SubAgentManagerDeps, SubAgentRunner,
  SubAgentAssistantResolution, SubAgentGenerationPort,
  AgentPromptSettingsPort,
} from '@amber/chat-domain';
import {
  loadAssistants, loadProviders, makeAssistant, saveAssistants, patchAssistant,
  makeProviderSettingOpenAI, createOpenAIChatApi, createClaudeChatApi, createGoogleChatApi,
  hasUsableAuth, openAIAuthHeaders, copyProviderSettingWithModels,
  saveProviders, makeProviderSettingOpenAIVariant, makeProviderModel, newId,
  makeTextGenerationParams, toChatModel, defaultReasoningLevelForModel,
  resolveSessionDefaults, mergeCustomParams,
  SessionAccessGrantStore, DEFAULT_AUTO_MODEL_ID,
  DEFAULT_TITLE_PROMPT, DEFAULT_SUGGESTION_PROMPT, DEFAULT_COMPRESS_PROMPT,
  McpManager, SkillManager, AgentToolActivityStore, PosixWorkspaceManager,
  AgentPromptConfigRepository, createAgentPromptConfigTool,
  AgentPermissionBroker, createPermissionsStatusTool,
  AgentTaskStore, AgentTaskScheduler, AgentTaskTools, AgentCronManager, AgentCronTools,
  asChatStreamProvider, makeGenerationRetrySetting, makeSubAgentRuntimeSetting,
  SubAgentManager, GenerationSubAgentRunner, ChatStreamSubAgentGenerationPort,
  SubAgentTools,
} from '@amber/chat-domain';
import { findModelByIdInProviders } from '../platform_impl/OcrSupport.ets';
import { createChatKvStore } from '../platform_impl/ChatKvStore.ets';
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
} from '../platform_impl/AgentCronStore.ets';
import {
  createEntrySubAgentTranscriptPort, subAgentRunsDir, subAgentTranscriptPathForRun,
} from '../platform_impl/SubAgentStore.ets';
import { loadAgentRuntimeSnapshot } from '../platform_impl/AgentRuntimePrefs.ets';
import { util } from '@kit.ArkTS';

const DOMAIN: number = 0x0001;
const TAG: string = 'DeepRead.Container';

// generateText 参数 interface(ArkTS 不允许对象字面量类型)
interface GenTextParams {
  model: string;
  messages: UIMessage[];
  tools?: Tool[];
  maxSteps?: number;
  autoApproveTools?: boolean;
  autoApproveHighRiskTools?: boolean;
  autoApprovedToolNames?: string[];
  stream?: boolean;
}

// mock AiClient:无 API key 时返回空 → deepread 走 fallbackPlan + writeFallbackSection
// TODO(phase6): 替换为 createOpenAiCompatibleAiClient(需修 ArkTS 编译后)
class MockAiClient implements AiClient {
  async generateText(_params: GenTextParams): Promise<UIMessage[]> {
    return [];
  }
}

// Phase 7:通知调度(对照设计 §6.7 / §8.8)
// TODO(notification): notificationManager 的 ContentType/NotificationContent 类型
// 在 API 26 SDK 有 @ohos.notification vs @ohos.notificationManager 模块冲突,
// 需 DevEco 验证确切类型后接真实系统通知。当前用 hilog 输出(通知逻辑结构完整:
// 3 类通知 running/completed/failed + cancel,调度接入 Index startDeepRead 全生命周期)。
interface DeepReadNotifier {
  notifyRunning(topicId: string, title: string): Promise<void>;
  notifyCompleted(topicId: string, title: string, complete: boolean): Promise<void>;
  notifyFailed(topicId: string, title: string, msg: string): Promise<void>;
  cancelRunning(topicId: string): Promise<void>;
}

// 通知实现:当前用 hilog(标 TODO 接真实 notificationManager)
// ArkTS 要求 interface 用 class implements,不允许对象字面量直接赋值。
class HilogNotifier implements DeepReadNotifier {
  async notifyRunning(topicId: string, title: string): Promise<void> {
    hilog.info(DOMAIN, TAG, '[notifyRunning] topicId=%{public}s title=%{public}s', topicId, title);
  }
  async notifyCompleted(topicId: string, _title: string, complete: boolean): Promise<void> {
    hilog.info(DOMAIN, TAG, '[notifyCompleted] topicId=%{public}s complete=%{public}s', topicId, complete ? 'true' : 'false');
  }
  async notifyFailed(topicId: string, _title: string, msg: string): Promise<void> {
    hilog.warn(DOMAIN, TAG, '[notifyFailed] topicId=%{public}s msg=%{public}s', topicId, msg);
  }
  async cancelRunning(topicId: string): Promise<void> {
    hilog.info(DOMAIN, TAG, '[cancelRunning] topicId=%{public}s', topicId);
  }
}

const createRealNotifier = (): DeepReadNotifier => {
  return new HilogNotifier();
};

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
  const storage: Storage = createPreferencesStorage();
  const repository: DeepReadRepository = createRdbRepository();
  const searchRegistry: SearchProviderRegistry = createTavilyAndFallbackRegistry(httpClient, storage);
  const prefetcher: SourcePrefetcher = createSourcePrefetcher(httpClient, searchRegistry);
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
export const getFileStore = (): FileStore => getAppContainer().fileStore;
export const getModelRegistry = (): ModelRegistry => getAppContainer().modelRegistry;

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

// agent_prompt_config 工具 deps(LocalTools.kt:98-100 lazy + :183 装配)
export const getAgentPromptConfigTool = (): AgentTool =>
  createAgentPromptConfigTool<ModelCouncilRuntimeSetting, ModelCouncilSeat>({
    repository: getAgentPromptConfigRepository(),
    settings: createEntryAgentPromptSettingsPort(getChatKvStore()),
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
    agentTaskStorePromise = AgentTaskStore.create({
      taskDir: agentCronTaskDir(),
      appFilesDir: agentCronFilesDir(),
      files: createEntryAgentTaskFilePort(),
    });
  }
  return agentTaskStorePromise;
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
export const getAgentCronManager = (): Promise<AgentCronManager> => {
  if (agentCronManagerPromise === null) {
    agentCronManagerPromise = (async (): Promise<AgentCronManager> =>
      new AgentCronManager(
        createEntryCronPersistencePort(getChatKvStore()),
        createEntryCronSchedulerPort({ manager: (): Promise<AgentCronManager> => getAgentCronManager() }),
        await getAgentTaskStore(),
      ))();
  }
  return agentCronManagerPromise;
};

// cron 四工具(LocalTools.kt:181 装配位 = permissions_status 之后/
//   runPlanUpdate 之前)
export const getAgentCronTools = async (): Promise<AgentTool[]> =>
  new AgentCronTools(await getAgentCronManager()).getTools();

// AmberAgentApp.rescheduleCronTasks(:216-224)等价:启动时重排全部任务
export const rescheduleCronTasksOnStartup = (): void => {
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
const buildSubAgentGenerationPort = (): SubAgentGenerationPort => {
  // model.id → 已解析 provider(resolveModel 异步解析后缓存;makeProvider 同步取)
  //   并行子代理按各自 model.id 取 provider,不互相干扰;同 model → 同 provider(正确)
  const providerByModelId: Map<string, ProviderSetting> = new Map<string, ProviderSetting>();
  const resolveModel = async (modelSettingId: string | null): Promise<ProviderModel | null> => {
    // Per-role override → fallback current chat model(SubAgentRunner.kt:52-54)
    let choice: ChatProviderChoice | null = null;
    if (modelSettingId !== null && modelSettingId.length > 0) {
      choice = await resolveTaskChatModelChoice(modelSettingId);
    }
    if (choice === null) choice = await resolveChatProviderChoice();
    if (choice === null) return null;
    providerByModelId.set(choice.model.id, choice.provider);
    return choice.model;
  };
  const resolveAssistant = async (): Promise<SubAgentAssistantResolution | null> => {
    const assistants: Assistant[] = await getChatAssistants();
    if (assistants.length === 0) return null;
    const assistant: Assistant = assistants[0];
    const snapshot = await loadAgentRuntimeSnapshot(getChatKvStore());
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
    const cached: ProviderSetting | undefined = providerByModelId.get(model.id);
    if (cached === undefined) {
      throw new Error(`Subagent provider not resolved for model ${model.id}`);
    }
    const customHeaderRecord: Record<string, string> = {};
    headers.forEach((h: CustomHeader): void => {
      const name: string = h.name.trim();
      if (name.length === 0) return;
      customHeaderRecord[name] = h.value.trim();
    });
    const api: OpenAIChatApi = buildProviderApi(cached, customHeaderRecord);
    return asChatStreamProvider(api, (): TextGenerationParams => params);
  };
  return new ChatStreamSubAgentGenerationPort({ resolveModel, resolveAssistant, makeProvider });
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
export const getSubAgentManager = async (): Promise<SubAgentManager> => {
  if (subAgentManager === null) {
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
    subAgentManager = new SubAgentManager(deps);
  }
  return subAgentManager;
};

// 五件公共工具(ChatService.kt:2299 per-conversation;parentToolsProvider 捕获
//   第 4 步 base tools,由 ChatPage 传入)。会话存在且 subagent enabled 时由
//   ChatPage 调用挂载;public subagent_* 不进入 child scope(manager.start 过滤)。
export interface SubAgentToolsContext {
  parentConversationId: string;
  parentToolsProvider: () => AgentTool[];
}
export const getSubAgentTools = async (
  ctx: SubAgentToolsContext,
): Promise<AgentTool[]> => {
  await hydrateSubAgentSetting();
  const manager: SubAgentManager = await getSubAgentManager();
  const councilEnabled: boolean = await isModelCouncilEnabled();
  const tools: SubAgentTools = new SubAgentTools({
    manager,
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
  const pool = await getModelRegistry().list();
  councilManager = createModelCouncilManager({
    runner: getCouncilRunner(),
    fileStore: getFileStore(),
    modelPool: pool,
    setting: setting,
  });
  return councilManager;
};

export const getCouncilManager = (): ModelCouncilManager | null => councilManager;

// ===== Novel Creation 装配 =====
let novelCreation: NovelCreation | null = null;

export const getNovelCreation = (): NovelCreation => {
  if (novelCreation === null) {
    const c: Container = getAppContainer();
    novelCreation = createNovelCreation({
      repository: createFileNovelRepository(c.fileStore),
      modelRunning: createNovelModelAdapter(c.httpClient, c.modelRegistry),
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
    // DEBUG 预置:首次启动无 Provider 时注入 DeepSeek 测试配置
    seedDebugProvider();
  }
  return chatKv;
};

// DEBUG: 注入 DeepSeek 测试 Provider(设备验证用,上线前删除)
let debugSeeded = false;
const seedDebugProvider = async (): Promise<void> => {
  if (debugSeeded) return;
  debugSeeded = true;
  try {
    const existing = await loadProviders(chatKv!);
    if (existing !== null && existing.length > 0) return;
    const provider = makeProviderSettingOpenAIVariant({
      id: newId(),
      name: 'DeepSeek',
      apiKey: 'sk-bd402b35619b42ec85233a467274d7b2',
      // DEBUG: 模拟器走宿主机反向代理(绕过 Clash fake-ip DNS)
      //   正式环境用 https://api.deepseek.com/v1
      baseUrl: 'http://10.0.2.2:8080/v1',
      models: [
        // modelId 必须匹配 API 接受的值;deepseek-chat 是请求参数,
        // API 服务端映射到 deepseek-v4-flash(见 response.model)
        makeProviderModel({ modelId: 'deepseek-chat', displayName: 'DeepSeek V4 Flash', type: 'chat' }),
      ],
    });
    await saveProviders(chatKv!, [provider]);
    // 同时设置默认选中
    await chatKv!.put('chat_selected_provider_id', provider.id);
    await chatKv!.put('chat_selected_model_id', 'deepseek-chat');
  } catch (_e) {
    // 静默失败(不影响应用启动)
  }
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

export const getChatAssistants = async (): Promise<Assistant[]> => {
  const loaded = await loadAssistants(getChatKvStore());
  if (loaded !== null && loaded.length > 0) return loaded;
  return [makeAssistant({ name: '默认助手' })];
};

export interface ChatRuntime {
  api: OpenAIChatApi;
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
}

export interface ChatProviderChoice {
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

// D-080:导出供 ChatPage 发送门(canSendWithoutChatModel 等价物)使用
export const resolveChatProviderChoice = async (): Promise<ChatProviderChoice | null> => {
  const providers = await loadProviders(getChatKvStore());
  if (providers === null) return null;
  // 用户选择优先(D-023;D-046 起不再限定 openai 变体)
  const selection = await getChatModelSelection();
  if (selection !== null) {
    for (const p of providers) {
      if (p.id === selection.providerId && hasUsableAuth(p)) {
        for (const m of p.models) {
          if (m.modelId === selection.modelId) {
            return { provider: effectiveProvider(p, m), model: m };
          }
        }
      }
    }
  }
  // 回退 D-019 策略:第一个 hasUsableAuth 且非空 models 的变体(任意类型)+ 首模型
  for (const p of providers) {
    if (p.models.length > 0 && hasUsableAuth(p)) {
      const m: ProviderModel = p.models[0];
      return { provider: effectiveProvider(p, m), model: m };
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

export interface TaskModelIds {
  titleModelId: string;
  suggestionModelId: string;
  compressModelId: string;
}

const readTaskModelId = async (key: string): Promise<string> => {
  const storage = getAppContainer().storage;
  const value: string = await storage.get<string>(key, '');
  // parseUuidOrNull 无效 → AUTO(ChatPrefs.kt:71-77 等价:空/缺失 → AUTO)
  return value.length > 0 ? value : DEFAULT_AUTO_MODEL_ID;
};

export const loadTaskModelIds = async (): Promise<TaskModelIds> => ({
  titleModelId: await readTaskModelId(TASK_TITLE_MODEL_KEY),
  suggestionModelId: await readTaskModelId(TASK_SUGGESTION_MODEL_KEY),
  compressModelId: await readTaskModelId(TASK_COMPRESS_MODEL_KEY),
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
        if (m.modelId === selection.modelId) return m.id;
      }
    }
  }
  return DEFAULT_AUTO_MODEL_ID;
};

// resolveTaskChatModel(PreferencesStore.kt:479-481)—
//   findModelById(modelId) ?: getCurrentChatModel(= resolveChatProviderChoice)
export const resolveTaskChatModelChoice = async (
  modelId: string,
): Promise<ChatProviderChoice | null> => {
  const providers = await loadProviders(getChatKvStore());
  if (providers !== null) {
    const found: ProviderModel | null = findModelByIdInProviders(providers, modelId);
    if (found !== null) {
      for (const p of providers) {
        for (const m of p.models) {
          if (m.id === found.id) {
            // findProvider(checkOverwrite=true):容器命中 + overwrite 副本(D-084 同口径)
            return { provider: effectiveProvider(p, m), model: m };
          }
        }
      }
    }
  }
  return resolveChatProviderChoice();
};

// 链式解析:按候选序逐个 resolveTaskChatModelChoice,首个非空胜出
export const resolveTaskModelChain = async (
  candidates: string[],
): Promise<ChatProviderChoice | null> => {
  for (const id of candidates) {
    const choice: ChatProviderChoice | null = await resolveTaskChatModelChoice(id);
    if (choice !== null) return choice;
  }
  return null;
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
): OpenAIChatApi => {
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
      // customHeaders 后写覆盖(auth 冲突时 custom 赢,同 claude/google 层语义)
      for (const k of Object.keys(customHeaderRecord)) auth[k] = customHeaderRecord[k];
      return auth;
    },
    // D-038:图片编码(data:/http(s): 直通,file:// 读文件编码;失败即抛)
    encodeImage: encodeImageForChat,
  });
};

export const resolveChatRuntime = async (assistantId?: string): Promise<ChatRuntime | null> => {
  const choice = await resolveChatProviderChoice();
  if (choice === null) return null;
  const chosen: ProviderSetting = choice.provider;
  const model = choice.model;
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
  const sessionDefaults = resolveSessionDefaults(
    assistant,
    null,
    defaultReasoningLevelForModel(model),
  );
  const merged = mergeCustomParams(assistant, model);
  // D-049:customBodies 进 params(三 provider 请求构建层均 mergeCustomBody);
  //   customHeaders(assistant+model 拼接,GenerationHandler.kt:453-468 忠实)
  //   注入各 provider 请求头,blank name 跳过,重名后值覆盖(toHeaders 语义),
  //   且覆盖 auth 头(与各 api 层 extra-headers 覆盖语义一致)
  const params = makeTextGenerationParams({
    model: toChatModel(model),
    temperature: merged.temperature,
    topP: merged.topP,
    maxTokens: sessionDefaults.maxTokens,
    reasoningLevel: sessionDefaults.reasoningLevel,
    customBody: merged.customBodies,
  });
  const customHeaderRecord: Record<string, string> = {};
  for (const h of merged.customHeaders) {
    const name: string = h.name.trim();
    if (name.length === 0) continue;
    customHeaderRecord[name] = h.value.trim();
  }
  // D-046:按变体类型分发到对应 api 组装层(均返回结构 OpenAIChatApi)。
  // auth header 归属:openai 由 adapter(openAIAuthHeaders);claude/google 由各自
  // api 层从 setting.apiKey 注入(x-api-key / x-goog-api-key)。
  // 未落地传输模式(OAuth/Vertex service account)在 api 层显式抛错(非静默)。
  const api: OpenAIChatApi = buildProviderApi(chosen, customHeaderRecord);
  return {
    api, assistant, params,
    contextMessageSize: sessionDefaults.contextMessageSize,
    modelContextWindowTokens: model.contextWindowTokens,
    modelSupportsImageInput: model.inputModalities.includes('image'),
    modelAbilities: model.abilities,
    // D-123:技能库快照(每次发送重新扫描;SkillManager 内部缓存,变更后失效)
    allSkills: getSkillManager().listSkills(),
  };
};
