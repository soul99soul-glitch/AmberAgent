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
import { createModelCouncilRunner } from '../platform_impl/ModelCouncilRunner.ets';
import { createNovelModelAdapter } from '../platform_impl/NovelModelAdapter.ets';
import { hilog } from '@kit.PerformanceAnalysisKit';

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
