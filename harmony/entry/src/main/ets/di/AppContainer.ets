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
  ModelCouncilTextRunner, ModelCouncilRuntimeSetting,
} from '@amber/deepread-domain';
import {
  run, makeEmptyDeepReadOutput, isInterruptedPhase, createSourcePrefetcher,
  createStorageModelRegistry, createModelCouncilManager, ModelCouncilManager,
  createNovelCreation, NovelCreation, createFileNovelRepository,
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
import type {
  ConversationRepository, KeyValueStore, Assistant, OpenAIChatApi, TextGenerationParams,
  ProviderSetting, ProviderSettingOpenAIVariant, ProviderSettingClaude, ProviderSettingGoogle,
  ProviderModel, CompactStore,
} from '@amber/chat-domain';
import {
  loadAssistants, loadProviders, makeAssistant,
  makeProviderSettingOpenAI, createOpenAIChatApi, createClaudeChatApi, createGoogleChatApi,
  hasUsableAuth, openAIAuthHeaders,
  makeTextGenerationParams, toChatModel, resolveSessionDefaults, mergeCustomParams,
  SessionAccessGrantStore,
} from '@amber/chat-domain';
import { createChatKvStore } from '../platform_impl/ChatKvStore.ets';
import { createChatRdbRepository } from '../platform_impl/ChatRdbRepository.ets';
import { createEntryCompactStore } from '../platform_impl/EntryCompactStore.ets';
import { encodeImageForChat, encodeImageDetailed } from '../platform_impl/EntryImageEncoder.ets';

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
}

export interface ChatProviderChoice {
  // D-046:三变体(openai/google/claude)统一解析;api 层均为结构 OpenAIChatApi
  provider: ProviderSetting;
  model: ProviderModel;
}

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
            return { provider: p, model: m };
          }
        }
      }
    }
  }
  // 回退 D-019 策略:第一个 hasUsableAuth 且非空 models 的变体(任意类型)+ 首模型
  for (const p of providers) {
    if (p.models.length > 0 && hasUsableAuth(p)) {
      return { provider: p, model: p.models[0] };
    }
  }
  return null;
};

// 头部展示用:「provider 名 / modelId」;未配置返回 ''
export const describeChatModel = async (): Promise<string> => {
  const choice = await resolveChatProviderChoice();
  if (choice === null) return '';
  return `${choice.provider.name} / ${choice.model.modelId}`;
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
  const sessionDefaults = resolveSessionDefaults(assistant, null, 'auto');
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
  let api: OpenAIChatApi;
  if (chosen.type === 'claude') {
    const claudeSetting: ProviderSettingClaude = chosen as ProviderSettingClaude;
    api = createClaudeChatApi({
      http: getAppContainer().httpClient,
      setting: claudeSetting,
      headers: (): Record<string, string> => customHeaderRecord,
      encodeImage: encodeImageDetailed,
      // D-078:每请求 LRU 轮换(ClaudeProvider.kt:68/:74,PD-008 消除)
      keyRoulette: getChatKeyRoulette(),
    });
  } else if (chosen.type === 'google') {
    const googleSetting: ProviderSettingGoogle = chosen as ProviderSettingGoogle;
    api = createGoogleChatApi({
      http: getAppContainer().httpClient,
      setting: googleSetting,
      headers: (): Record<string, string> => customHeaderRecord,
      encodeImage: encodeImageDetailed,
      // D-078:每请求 LRU 轮换(GoogleProvider.kt:77/:134)
      keyRoulette: getChatKeyRoulette(),
    });
  } else {
    const openaiSetting: ProviderSettingOpenAIVariant = chosen as ProviderSettingOpenAIVariant;
    // D-078:bearerResolver 语义(ChatCompletionsAPI.kt:103-105)— token 不再
    //   一次性解析,headers() 每请求调用时轮换(ChatCompletionsAPI.kt:102-105
    //   bearerResolver 每请求语义;PD-008 消除)
    const nextToken: () => string = (): string =>
      getChatKeyRoulette().next(openaiSetting.apiKey, openaiSetting.id);
    api = createOpenAIChatApi({
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
  }
  return {
    api, assistant, params,
    contextMessageSize: sessionDefaults.contextMessageSize,
    modelContextWindowTokens: model.contextWindowTokens,
    modelSupportsImageInput: model.inputModalities.includes('image'),
  };
};
