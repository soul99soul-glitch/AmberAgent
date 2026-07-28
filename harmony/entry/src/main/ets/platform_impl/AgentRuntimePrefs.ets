// AgentRuntimePrefs — agent_runtime 设置读取(D-072 起,渐进移植)
// Android 基准:
//   PreferencesKeys.kt:77 — 'agent_runtime' 键名逐字
//   AgentPrefs.kt:47-51 — decodeJsonOrNull → 默认 AgentRuntimeSetting
//   PreferencesStore.kt:156 — enableTimeReminder 默认 false
// 裁剪:其余 agentRuntime 字段(autoApproveAllToolCalls/推测执行/compact 等
//   设置面)= P1 已登记,本文件仅承载 D-072 所需快照
import { i18n } from '@kit.LocalizationKit';
import type { KeyValueStore, MemoryRecallSetting } from '@amber/chat-domain';
import { DEFAULT_MEMORY_RECALL_SETTING } from '@amber/chat-domain';

export const AGENT_RUNTIME_KEY: string = 'agent_runtime';

// D-085d:MemoryWorkerSetting(MemoryModels.kt:136-154)抽取门子集 —
//   enabled/extractionEnabled 默认 true,maxDailyRuns 默认 8;
//   modelId/followCompressModel 等任务模型字段未移植(偏差同 D-025 title/suggestion:
//   entry 以 chat 模型顶替);runOnlyOnIdle/Charging/dream* 属 dream/调度切片
export interface MemoryWorkerGateSnapshot {
  enabled: boolean;
  extractionEnabled: boolean;
  maxDailyRuns: number;
}

export interface AgentRuntimeSnapshot {
  enableTimeReminder: boolean;
  // D-077b:PreferencesStore.kt:155 默认 true(GenerationHandler.kt:736 消费)
  enableRecentChatsReference: boolean;
  // D-085b:PreferencesStore.kt:152-154 三开关默认 true(MemoryRecallStore.kt:39-43 消费)
  enableCoreMemory: boolean;
  enableShortTermMemory: boolean;
  enableLongTermMemory: boolean;
  // D-085b:PreferencesStore.kt:174 默认 MemoryRecallSetting(){12,2000,false}
  memoryRecall: MemoryRecallSetting;
  // D-085d:MemoryExtractor.kt:45,69 消费
  memoryWorker: MemoryWorkerGateSnapshot;
}

interface MemoryRecallJson {
  maxItems?: number;
  maxPromptChars?: number;
  debug?: boolean;
}

interface MemoryWorkerJson {
  enabled?: boolean;
  extractionEnabled?: boolean;
  maxDailyRuns?: number;
}

interface AgentRuntimeJson {
  enableTimeReminder?: boolean;
  enableRecentChatsReference?: boolean;
  enableCoreMemory?: boolean;
  enableShortTermMemory?: boolean;
  enableLongTermMemory?: boolean;
  memoryRecall?: MemoryRecallJson;
  memoryWorker?: MemoryWorkerJson;
}

const defaultMemoryRecall = (): MemoryRecallSetting => ({
  maxItems: DEFAULT_MEMORY_RECALL_SETTING.maxItems,
  maxPromptChars: DEFAULT_MEMORY_RECALL_SETTING.maxPromptChars,
  debug: DEFAULT_MEMORY_RECALL_SETTING.debug,
});

export const defaultAgentRuntimeSnapshot = (): AgentRuntimeSnapshot => ({
  enableTimeReminder: false,
  enableRecentChatsReference: true,
  enableCoreMemory: true,
  enableShortTermMemory: true,
  enableLongTermMemory: true,
  memoryRecall: defaultMemoryRecall(),
  memoryWorker: { enabled: true, extractionEnabled: true, maxDailyRuns: 8 },
});

const parseMemoryRecall = (raw: MemoryRecallJson | undefined): MemoryRecallSetting => {
  if (raw === undefined) return defaultMemoryRecall();
  return {
    maxItems: typeof raw.maxItems === 'number' ? raw.maxItems : DEFAULT_MEMORY_RECALL_SETTING.maxItems,
    maxPromptChars: typeof raw.maxPromptChars === 'number'
      ? raw.maxPromptChars : DEFAULT_MEMORY_RECALL_SETTING.maxPromptChars,
    debug: raw.debug === true,
  };
};

const parseMemoryWorker = (raw: MemoryWorkerJson | undefined): MemoryWorkerGateSnapshot => ({
  // 默认 true:仅显式 false 才关(MemoryModels.kt:137,143)
  enabled: raw?.enabled !== false,
  extractionEnabled: raw?.extractionEnabled !== false,
  // 默认 8(:152);非 number → 默认
  maxDailyRuns: typeof raw?.maxDailyRuns === 'number' ? raw.maxDailyRuns : 8,
});

// AgentPrefs.kt:47-51 — 缺失/解析失败 → 默认(enableTimeReminder=false)
export const loadAgentRuntimeSnapshot = async (kv: KeyValueStore): Promise<AgentRuntimeSnapshot> => {
  const raw: string | null = await kv.get(AGENT_RUNTIME_KEY);
  if (raw === null) return defaultAgentRuntimeSnapshot();
  try {
    const parsed: AgentRuntimeJson = JSON.parse(raw) as AgentRuntimeJson;
    return {
      enableTimeReminder: parsed.enableTimeReminder === true,
      // 默认 true:仅显式 false 才关(PreferencesStore.kt:155)
      enableRecentChatsReference: parsed.enableRecentChatsReference !== false,
      // 默认 true:仅显式 false 才关(PreferencesStore.kt:152-154)
      enableCoreMemory: parsed.enableCoreMemory !== false,
      enableShortTermMemory: parsed.enableShortTermMemory !== false,
      enableLongTermMemory: parsed.enableLongTermMemory !== false,
      memoryRecall: parseMemoryRecall(parsed.memoryRecall),
      memoryWorker: parseMemoryWorker(parsed.memoryWorker),
    };
  } catch {
    return defaultAgentRuntimeSnapshot();
  }
};

// i18n.System.getSystemLanguage(Locale.getDefault 等价,SearchSdkInit 同模式);
//   失败回 'en'(TextStyle.FULL 英文档)
export const systemLanguage = (): string => {
  try {
    const lang: string = i18n.System.getSystemLanguage();
    return lang.length > 0 ? lang : 'en';
  } catch {
    return 'en';
  }
};
