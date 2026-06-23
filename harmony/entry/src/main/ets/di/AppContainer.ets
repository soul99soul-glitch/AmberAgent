// AppContainer — 依赖注入容器
// 组装 deepread 逻辑层(RunManagerDeps)与鸿蒙真实平台实现(@kit)。
//
// 对照设计 §8(Platform 契约) + Phase 6 真实平台实现。
// 当前:平台实现文件已就位(platform_impl/*.ets),但部分 API 调用
// 需在 DevEco + API 24 下编译验证。无法编译的部分在 platform_impl 内标 TODO。
//
// ArkTS 严格模式:无 any,单例用闭包(非 class 全局可变)。

import type {
  RunManagerDeps, HttpClient, DeepReadRepository, AiClient,
  SourcePrefetcher, SearchProviderRegistry, SectionWriterTools, ToolDefinition,
} from '@amber/deepread-domain';
import { createSourcePrefetcher, createScheduler } from '@amber/deepread-domain';
import type { DeepReadScheduler } from '@amber/deepread-domain';

import { createRcpHttpClient } from '../platform_impl/RcpHttpClient.ets';
import { createRdbRepository } from '../platform_impl/RdbRepository.ets';
import { createPreferencesStorage } from '../platform_impl/PreferencesStorage.ets';
import { createOpenAiCompatibleAiClient } from '../platform_impl/OpenAiCompatibleAiClient.ets';
import { createTavilyAndFallbackRegistry } from '../platform_impl/SearchRegistry.ets';
import { createNotificationNotifier } from '../platform_impl/NotificationNotifier.ets';

// 单例容器(闭包持有,首次 get 时构造)
interface Container {
  httpClient: HttpClient;
  repository: DeepReadRepository;
  aiClient: AiClient;
  searchRegistry: SearchProviderRegistry;
  prefetcher: SourcePrefetcher;
  scheduler: DeepReadScheduler;
  runManager: RunManagerDeps;
}

let cached: Container | null = null;

const PLAYBOOK_MD: string = '';  // TODO(phase6): 从 rawfile 加载 playbook.md

const buildContainer = (): Container => {
  const httpClient = createRcpHttpClient();
  const storage = createPreferencesStorage();
  const repository = createRdbRepository(storage);
  const aiClient = createOpenAiCompatibleAiClient(httpClient, storage);
  const searchRegistry = createTavilyAndFallbackRegistry(httpClient, storage);
  const prefetcher = createSourcePrefetcher(httpClient, searchRegistry);

  // TODO(phase6): model 从 settings 读(对照 Android resolveModel)
  const model: string = 'deepseek-chat';

  // 捕获当前 run 的 writer(由 run_manager.createRunContext 创建,通过 onWriterCreated 回调)
  let currentWriter: SectionWriterTools | null = null;

  const runManager: RunManagerDeps = {
    prefetcher,
    // collectRun:由 AiClient.generateText 装配 agent loop(runAgentLoop + executeToolsAndMerge +
    //   budget_prompt + retry_classifier),注入当前 run 的 writer tools。
    collectRun: async (messages, _statusLabel) => {
      // writer 的 tools() 返回 ToolDefinition[];转 Map 供 agent loop
      const toolList: ToolDefinition[] = currentWriter !== null
        ? currentWriter.tools(null)
        : [];
      const toolMap: Map<string, ToolDefinition> = new Map();
      for (const t of toolList) toolMap.set(t.name, t);
      return aiClient.generateText({
        model, messages, stream: true, tools: toolMap,
      });
    },
    aiClient,
    model,
    playbookMarkdown: PLAYBOOK_MD,
    repository: {
      get: (topicId) => repository.getDeepRead(topicId),
      save: (topicId, _title, output) => repository.saveDeepRead(topicId, output),
      clear: (topicId) => repository.clearDeepRead(topicId),
    },
    nowIso: () => new Date().toISOString().slice(0, 10),
    onWriterCreated: (w) => { currentWriter = w; },
  };

  const notifier = createNotificationNotifier();
  const scheduler = createScheduler({
    runManager,
    observeOutput: (topicId) => repository.observeDeepRead(topicId),
    notifier,
    isInterruptedPhase: (o) => o.generationPhase === 'COLLECTING' ||
      o.generationPhase === 'PLANNING' || o.generationPhase === 'WRITING',
  });

  return { httpClient, repository, aiClient, searchRegistry, prefetcher, scheduler, runManager };
};

export const getAppContainer = (): Container => {
  if (cached === null) {
    cached = buildContainer();
  }
  return cached;
};

export const getScheduler = (): DeepReadScheduler => getAppContainer().scheduler;
export const getRepository = (): DeepReadRepository => getAppContainer().repository;
