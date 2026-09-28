// AgentRuntimePrefs — agent_runtime 设置读取(D-072 起,渐进移植)
// Android 基准:
//   PreferencesKeys.kt:77 — 'agent_runtime' 键名逐字
//   AgentPrefs.kt:47-51 — decodeJsonOrNull → 默认 AgentRuntimeSetting
//   PreferencesStore.kt:156 — enableTimeReminder 默认 false
// 裁剪:其余 agentRuntime 字段(maxToolLoopSteps/harnessDebug/任务调度等
//   设置面)= P1 已登记,本文件仅承载已落地切片所需快照
import { i18n } from '@kit.LocalizationKit';
import { sharedKvMutex } from './AtomicJsonFile.ets';
import type { KeyValueStore, MemoryRecallSetting, ReasoningLevel } from '@amber/chat-domain';
import { DEFAULT_AUTO_MODEL_ID } from '@amber/chat-domain';
import { DEFAULT_AGENT_SOUL_MARKDOWN, DEFAULT_MEMORY_RECALL_SETTING } from '@amber/chat-domain';

export const AGENT_RUNTIME_KEY: string = 'agent_runtime';

// D-085d:MemoryWorkerSetting(MemoryModels.kt:136-154)抽取门子集 —
//   enabled/extractionEnabled 默认 true,maxDailyRuns 默认 8;
//   modelId/followCompressModel 等任务模型字段未移植(偏差同 D-025 title/suggestion:
//   entry 以 chat 模型顶替);runOnlyOnIdle/Charging/dream* 属 dream/调度切片
export interface MemoryWorkerGateSnapshot {
  enabled: boolean;
  extractionEnabled: boolean;
  maxDailyRuns: number;
  // D-085f:MemoryModels.kt:144-145(dream 门;MemoryWorkerDreamGate 消费)
  dreamMaintenanceEnabled: boolean;
  dreamModelEnabled: boolean;
  // Explicit opt-in for derived topic writes; independent from extraction and recall.
  topicWriteEnabled: boolean;
  timeoutMs: number;
  // D-085g:MemoryModels.kt:149-150,152
  // Topic foreground coordinator checks the actual charging state.
  runOnlyOnIdle: boolean;
  runOnlyOnCharging: boolean;
  dreamMaxDailyRuns: number;
  // D-087:任务模型链字段(MemoryModels.kt:138-141;modelId 缺省 = AUTO 标记)
  modelId: string;
  providerId: string | null;
  followCompressModel: boolean;
  daydreamModelId: string;
  daydreamProviderId: string | null;
  daydreamFollowCompressModel: boolean;
  // D-103:MemoryModels.kt:142(默认 HIGH;Planner.kt:95 消费)
  daydreamReasoningLevel: ReasoningLevel;
}

// D-091:ContextCompactionSetting(PreferencesStore.kt:234-241)—
//   toCompactPolicy(:247-255)字段直传
export interface ContextCompactionSnapshot {
  enabled: boolean;
  notifyOnly: boolean;
  precompactRatio: number;
  forceRatio: number;
  keepRecentTurns: number;
  maxSummaryTokens: number;
}

// D-122:AgentOperationPreviewMode(PreferencesStore.kt:257-266,SerialName 小写)
export type AgentOperationPreviewMode = 'always' | 'auto' | 'hidden';

export interface AgentRuntimeSnapshot {
  enableTimeReminder: boolean;
  // D-122:PreferencesStore.kt:158(默认 ALWAYS;ChatPage.kt:516-522 时间线门消费)
  operationPreviewMode: AgentOperationPreviewMode;
  // D-077b:PreferencesStore.kt:155 默认 true(GenerationHandler.kt:736 消费)
  enableRecentChatsReference: boolean;
  // D-085b:PreferencesStore.kt:152-154 三开关默认 true(MemoryRecallStore.kt:39-43 消费)
  enableCoreMemory: boolean;
  enableShortTermMemory: boolean;
  enableLongTermMemory: boolean;
  // D-085b:PreferencesStore.kt:174 默认 MemoryRecallSetting(){12,2000,false}
  memoryRecall: MemoryRecallSetting;
  // D-090:PreferencesStore.kt:157 默认 DEFAULT_AGENT_SOUL_MARKDOWN(:272-300)
  agentSoulMarkdown: string;
  // D-091:PreferencesStore.kt:234-241 ContextCompactionSetting 默认六值
  contextCompaction: ContextCompactionSnapshot;
  // D-085d:MemoryExtractor.kt:45,69 消费
  memoryWorker: MemoryWorkerGateSnapshot;
  // D-104:PreferencesStore.kt:164-165(默认 false;ChatService.kt:1345-1347 消费)
  autoApproveAllToolCalls: boolean;
  autoApproveHighRiskToolCalls: boolean;
  // D-104:PreferencesStore.kt:226-229(默认 enabled=false/maxConcurrentTools=4;
  //   tool_loop speculativeRunner 门消费,tool_loop.ts:184-188)
  speculativeToolExecution: SpeculativeToolExecutionSnapshot;
  // D-115:PreferencesStore.kt:163(默认 DEFAULT_AGENT_MAX_TOOL_LOOP_STEPS=256,
  //   :268-270 界 16/512;ChatService.kt:1349-1352 coerceIn 消费
  //   → tool_loop.ts:147-148 同位已忠实)
  maxToolLoopSteps: number;
  // D-115:PreferencesStore.kt:181(GenerationRetrySetting() 默认 enabled=true/
  //   maxRetries=5 — GenerationRetry.kt:14-20);GenerationHandler.kt:357/475/621
  //   三条生成路径全量接线 → chat_turn.ts:252 deps.retrySetting
  generationRetry: GenerationRetrySnapshot;
}

// D-115:GenerationRetrySetting(GenerationRetry.kt:14-20)子集 —
//   initialDelayMs/maxDelayMs/jitterRatio 无设置面(恒 1000/16000/0.15),同不暴露
export interface GenerationRetrySnapshot {
  enabled: boolean;
  maxRetries: number;
}

// D-104:SpeculativeToolExecutionSetting(PreferencesStore.kt:226-229)
export interface SpeculativeToolExecutionSnapshot {
  enabled: boolean;
  maxConcurrentTools: number;
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
  dreamMaintenanceEnabled?: boolean;
  dreamModelEnabled?: boolean;
  topicWriteEnabled?: boolean;
  timeoutMs?: number;
  runOnlyOnIdle?: boolean;
  runOnlyOnCharging?: boolean;
  dreamMaxDailyRuns?: number;
  modelId?: string;
  providerId?: string | null;
  followCompressModel?: boolean;
  daydreamModelId?: string;
  daydreamProviderId?: string | null;
  daydreamFollowCompressModel?: boolean;
  daydreamReasoningLevel?: string;
  // 旧 Daydream 开关别名(SettingsAggregator.kt:542-553 迁移:
  //   dreamModelEnabled ||= dreamEnabled,写回时 dreamEnabled=false)
  dreamEnabled?: boolean;
}

// D-091:ContextCompactionSetting(PreferencesStore.kt:234-241)六字段
interface ContextCompactionJson {
  enabled?: boolean;
  notifyOnly?: boolean;
  precompactRatio?: number;
  forceRatio?: number;
  keepRecentTurns?: number;
  maxSummaryTokens?: number;
}

interface AgentRuntimeJson {
  agentSoulMarkdown?: string;
  operationPreviewMode?: string;
  autoApproveAllToolCalls?: boolean;
  autoApproveHighRiskToolCalls?: boolean;
  speculativeToolExecution?: SpeculativeToolExecutionJson;
  contextCompaction?: ContextCompactionJson;
  enableTimeReminder?: boolean;
  enableRecentChatsReference?: boolean;
  enableCoreMemory?: boolean;
  enableShortTermMemory?: boolean;
  enableLongTermMemory?: boolean;
  memoryRecall?: MemoryRecallJson;
  memoryWorker?: MemoryWorkerJson;
  maxToolLoopSteps?: number;
  generationRetry?: GenerationRetryJson;
}

interface SpeculativeToolExecutionJson {
  enabled?: boolean;
  maxConcurrentTools?: number;
}

// D-115:GenerationRetrySetting 线格式(kotlinx encodeDefaults;
//   缺省 → 读侧默认,与 kotlinx 缺省语义互逆)
interface GenerationRetryJson {
  enabled?: boolean;
  maxRetries?: number;
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
  // :158 默认 ALWAYS
  operationPreviewMode: 'always',
  agentSoulMarkdown: DEFAULT_AGENT_SOUL_MARKDOWN,
  // :235-240 默认 true/false/0.70/0.85/8/2000
  contextCompaction: {
    enabled: true, notifyOnly: false,
    precompactRatio: 0.70, forceRatio: 0.85,
    keepRecentTurns: 8, maxSummaryTokens: 2000,
  },
  memoryWorker: {
    enabled: true, extractionEnabled: true, maxDailyRuns: 8,
    dreamMaintenanceEnabled: true, dreamModelEnabled: false,
    topicWriteEnabled: false, timeoutMs: 120000,
    runOnlyOnIdle: true, runOnlyOnCharging: true, dreamMaxDailyRuns: 1,
    modelId: DEFAULT_AUTO_MODEL_ID, providerId: null, followCompressModel: true,
    daydreamModelId: DEFAULT_AUTO_MODEL_ID, daydreamProviderId: null, daydreamFollowCompressModel: true,
    daydreamReasoningLevel: 'high',
  },
  // :164-165/:226-229 默认
  autoApproveAllToolCalls: false,
  autoApproveHighRiskToolCalls: false,
  speculativeToolExecution: { enabled: false, maxConcurrentTools: 4 },
  // :163 默认 256(DEFAULT_AGENT_MAX_TOOL_LOOP_STEPS,:269)
  maxToolLoopSteps: 256,
  // :181 → GenerationRetry.kt:14-20 默认 true/5
  generationRetry: { enabled: true, maxRetries: 5 },
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
  // 默认 true(:144);仅显式 false 关
  dreamMaintenanceEnabled: raw?.dreamMaintenanceEnabled !== false,
  // 默认 false(:145)+ 旧别名迁移(dreamEnabled=true → true)
  dreamModelEnabled: raw?.dreamModelEnabled === true || raw?.dreamEnabled === true,
  topicWriteEnabled: raw?.topicWriteEnabled === true,
  timeoutMs: raw?.timeoutMs !== undefined && Number.isFinite(raw.timeoutMs) && raw.timeoutMs > 0
    ? Math.min(120000, raw.timeoutMs) : 120000,
  // 默认 true(:149-150);仅显式 false 关
  runOnlyOnIdle: raw?.runOnlyOnIdle !== false,
  runOnlyOnCharging: raw?.runOnlyOnCharging !== false,
  // 默认 1(:152);非 number → 默认
  dreamMaxDailyRuns: typeof raw?.dreamMaxDailyRuns === 'number' ? raw.dreamMaxDailyRuns : 1,
  // :138-141 — 非 string → AUTO;parseUuidOrNull 无效 → AUTO 等价(空串回退)
  modelId: typeof raw?.modelId === 'string' && raw.modelId.length > 0
    ? raw.modelId : DEFAULT_AUTO_MODEL_ID,
  providerId: typeof raw?.providerId === 'string' && raw.providerId.length > 0 ? raw.providerId : null,
  followCompressModel: raw?.followCompressModel !== false,
  daydreamModelId: typeof raw?.daydreamModelId === 'string' && raw.daydreamModelId.length > 0
    ? raw.daydreamModelId : DEFAULT_AUTO_MODEL_ID,
  daydreamProviderId: typeof raw?.daydreamProviderId === 'string' && raw.daydreamProviderId.length > 0
    ? raw.daydreamProviderId : null,
  daydreamFollowCompressModel: raw?.daydreamFollowCompressModel !== false,
  // :142 默认 HIGH;非法值 → 默认(字段宽容先例;kotlinx 非法枚举整体 rescue
  //   → AgentRuntimeSetting() 同样 HIGH,等价)
  daydreamReasoningLevel: parseReasoningLevel(raw?.daydreamReasoningLevel),
});

const REASONING_LEVELS: ReasoningLevel[] = [
  'off', 'auto', 'low', 'medium', 'high', 'xhigh', 'max',
];

const parseReasoningLevel = (raw: string | undefined): ReasoningLevel => {
  if (raw !== undefined && REASONING_LEVELS.indexOf(raw as ReasoningLevel) >= 0) {
    return raw as ReasoningLevel;
  }
  return 'high';
};

// :234-241 — 字段缺失/非数 → 各自默认( kotlinx 缺省语义;ratio 不做范围钳制,
//   Android 同样原样持有)
const parseContextCompaction = (
  raw: ContextCompactionJson | undefined,
): ContextCompactionSnapshot => ({
  enabled: raw?.enabled !== false,
  notifyOnly: raw?.notifyOnly === true,
  precompactRatio: typeof raw?.precompactRatio === 'number' ? raw.precompactRatio : 0.70,
  forceRatio: typeof raw?.forceRatio === 'number' ? raw.forceRatio : 0.85,
  keepRecentTurns: typeof raw?.keepRecentTurns === 'number' ? raw.keepRecentTurns : 8,
  maxSummaryTokens: typeof raw?.maxSummaryTokens === 'number' ? raw.maxSummaryTokens : 2000,
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
      // :158 默认 ALWAYS;非法值 → 默认(kotlinx 解析失败回默认快照语义)
      operationPreviewMode: parsed.operationPreviewMode === 'auto' ||
        parsed.operationPreviewMode === 'hidden'
        ? parsed.operationPreviewMode : 'always',
      // kotlinx 缺省→默认(:157);用户显式空串保留(空串 → 不注入,合法意图)
      agentSoulMarkdown: typeof parsed.agentSoulMarkdown === 'string'
        ? parsed.agentSoulMarkdown : DEFAULT_AGENT_SOUL_MARKDOWN,
      contextCompaction: parseContextCompaction(parsed.contextCompaction),
      // :164-165 默认 false(仅显式 true 开)
      autoApproveAllToolCalls: parsed.autoApproveAllToolCalls === true,
      autoApproveHighRiskToolCalls: parsed.autoApproveHighRiskToolCalls === true,
      speculativeToolExecution: {
        enabled: parsed.speculativeToolExecution?.enabled === true,
        maxConcurrentTools:
          typeof parsed.speculativeToolExecution?.maxConcurrentTools === 'number'
            ? parsed.speculativeToolExecution.maxConcurrentTools : 4,
      },
      memoryWorker: parseMemoryWorker(parsed.memoryWorker),
      // D-115:非 number → 默认 256(kotlinx 缺省语义);界外值原样持有,
      //   消费侧 coerceIn(16,512)(ChatService.kt:1349-1352 → tool_loop 同位)
      maxToolLoopSteps: typeof parsed.maxToolLoopSteps === 'number'
        ? parsed.maxToolLoopSteps : 256,
      // D-115::181 默认 true/5(仅显式 false 关;非 number → 5)
      generationRetry: {
        enabled: parsed.generationRetry?.enabled !== false,
        maxRetries: typeof parsed.generationRetry?.maxRetries === 'number'
          ? parsed.generationRetry.maxRetries : 5,
      },
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

// ===== D-089:memoryWorker 开关写回(VM updateAgentRuntime :68-74)=====

export interface MemoryWorkerTogglePatch {
  dreamMaintenanceEnabled?: boolean;
  dreamModelEnabled?: boolean;
  topicWriteEnabled?: boolean;
  runOnlyOnIdle?: boolean;
  runOnlyOnCharging?: boolean;
}

// Android: settingsStore.update { settings.copy(agentRuntime = ...) } 全文档重写
//   (kotlinx encodeDefaults);鸿蒙 agent_runtime 独立 KV 键(D-072 适配)——
//   读-改-写 raw JSON,保留未识别字段原样(wire 兼容:已知键缺失即默认语义,
//   与 parseMemoryWorker 读侧互逆)。
// dream 开关联动(Page :447-451/:475-479):写 dreamMaintenanceEnabled 或
//   dreamModelEnabled 时同步 dreamEnabled=false(旧别名迁移写回语义逐字)。
export const updateMemoryWorkerToggles = (
  kv: KeyValueStore, patch: MemoryWorkerTogglePatch,
): Promise<AgentRuntimeSnapshot> =>
  sharedKvMutex.withLock(AGENT_RUNTIME_KEY, async (): Promise<AgentRuntimeSnapshot> => {
  const raw: string | null = await kv.get(AGENT_RUNTIME_KEY);
  let root: AgentRuntimeJson = {};
  if (raw !== null) {
    try {
      root = JSON.parse(raw) as AgentRuntimeJson;
    } catch {
      root = {};
    }
  }
  const worker: MemoryWorkerJson = root.memoryWorker ?? {};
  if (patch.dreamMaintenanceEnabled !== undefined) {
    worker.dreamMaintenanceEnabled = patch.dreamMaintenanceEnabled;
    worker.dreamEnabled = false;
  }
  if (patch.dreamModelEnabled !== undefined) {
    worker.dreamModelEnabled = patch.dreamModelEnabled;
    worker.dreamEnabled = false;
  }
  if (patch.runOnlyOnIdle !== undefined) {
    worker.runOnlyOnIdle = patch.runOnlyOnIdle;
  }
  if (patch.runOnlyOnCharging !== undefined) worker.runOnlyOnCharging = patch.runOnlyOnCharging;
  if (patch.topicWriteEnabled !== undefined) worker.topicWriteEnabled = patch.topicWriteEnabled;
  root.memoryWorker = worker;
  await kv.put(AGENT_RUNTIME_KEY, JSON.stringify(root));
  return loadAgentRuntimeSnapshot(kv);
  });


// D-094:worker/daydream 任务模型写回(SettingModelPage.kt:619-643/:684-697)—
//   onClear → modelId = DEFAULT_AUTO_MODEL_ID + followCompressModel = true
//   onSelect → modelId = it.id + followCompressModel = false(daydream 同构)
export interface MemoryWorkerModelPatch {
  modelId?: string;
  providerId?: string | null;
  followCompressModel?: boolean;
  daydreamModelId?: string;
  daydreamProviderId?: string | null;
  daydreamFollowCompressModel?: boolean;
  daydreamReasoningLevel?: string;
}

export const updateMemoryWorkerModels = (
  kv: KeyValueStore, patch: MemoryWorkerModelPatch,
): Promise<AgentRuntimeSnapshot> =>
  sharedKvMutex.withLock(AGENT_RUNTIME_KEY, async (): Promise<AgentRuntimeSnapshot> => {
  const raw: string | null = await kv.get(AGENT_RUNTIME_KEY);
  let root: AgentRuntimeJson = {};
  if (raw !== null) {
    try {
      root = JSON.parse(raw) as AgentRuntimeJson;
    } catch {
      root = {};
    }
  }
  const worker: MemoryWorkerJson = root.memoryWorker ?? {};
  if (patch.modelId !== undefined) {
    worker.modelId = patch.modelId;
  }
  if (patch.providerId !== undefined) worker.providerId = patch.providerId;
  if (patch.followCompressModel !== undefined) {
    worker.followCompressModel = patch.followCompressModel;
  }
  if (patch.daydreamModelId !== undefined) {
    worker.daydreamModelId = patch.daydreamModelId;
  }
  if (patch.daydreamProviderId !== undefined) worker.daydreamProviderId = patch.daydreamProviderId;
  if (patch.daydreamFollowCompressModel !== undefined) {
    worker.daydreamFollowCompressModel = patch.daydreamFollowCompressModel;
  }
  root.memoryWorker = worker;
  await kv.put(AGENT_RUNTIME_KEY, JSON.stringify(root));
  return loadAgentRuntimeSnapshot(kv);
  });


// D-090:agentSoulMarkdown 写回(VM :190-194 updateAgentRuntime {
//   copy(agentSoulMarkdown = value) };Page :792-791 AgentSoulCard onSave)
export const updateAgentSoulMarkdown = (
  kv: KeyValueStore, value: string,
): Promise<AgentRuntimeSnapshot> =>
  sharedKvMutex.withLock(AGENT_RUNTIME_KEY, async (): Promise<AgentRuntimeSnapshot> => {
  const raw: string | null = await kv.get(AGENT_RUNTIME_KEY);
  let root: AgentRuntimeJson = {};
  if (raw !== null) {
    try {
      root = JSON.parse(raw) as AgentRuntimeJson;
    } catch {
      root = {};
    }
  }
  root.agentSoulMarkdown = value;
  await kv.put(AGENT_RUNTIME_KEY, JSON.stringify(root));
  return loadAgentRuntimeSnapshot(kv);
  });


// D-091:contextCompaction 开关写回(Page :536-558 两 Switch →
//   VM updateAgentRuntime { copy(contextCompaction = ...) })
export interface ContextCompactionTogglePatch {
  enabled?: boolean;
  notifyOnly?: boolean;
}

export const updateContextCompactionToggles = (
  kv: KeyValueStore, patch: ContextCompactionTogglePatch,
): Promise<AgentRuntimeSnapshot> =>
  sharedKvMutex.withLock(AGENT_RUNTIME_KEY, async (): Promise<AgentRuntimeSnapshot> => {
  const raw: string | null = await kv.get(AGENT_RUNTIME_KEY);
  let root: AgentRuntimeJson = {};
  if (raw !== null) {
    try {
      root = JSON.parse(raw) as AgentRuntimeJson;
    } catch {
      root = {};
    }
  }
  const compaction: ContextCompactionJson = root.contextCompaction ?? {};
  if (patch.enabled !== undefined) compaction.enabled = patch.enabled;
  if (patch.notifyOnly !== undefined) compaction.notifyOnly = patch.notifyOnly;
  root.contextCompaction = compaction;
  await kv.put(AGENT_RUNTIME_KEY, JSON.stringify(root));
  return loadAgentRuntimeSnapshot(kv);
  });


// D-092:召回开关写回(Page :359-431 MemoryRecallSubpage 六 Switch →
//   VM updateAgentRuntime;memoryRecall.debug 走嵌套 copy(:420-424))
export interface RecallTogglePatch {
  enableCoreMemory?: boolean;
  enableShortTermMemory?: boolean;
  enableLongTermMemory?: boolean;
  enableRecentChatsReference?: boolean;
  enableTimeReminder?: boolean;
  memoryRecallDebug?: boolean;
}

export const updateRecallToggles = (
  kv: KeyValueStore, patch: RecallTogglePatch,
): Promise<AgentRuntimeSnapshot> =>
  sharedKvMutex.withLock(AGENT_RUNTIME_KEY, async (): Promise<AgentRuntimeSnapshot> => {
  const raw: string | null = await kv.get(AGENT_RUNTIME_KEY);
  let root: AgentRuntimeJson = {};
  if (raw !== null) {
    try {
      root = JSON.parse(raw) as AgentRuntimeJson;
    } catch {
      root = {};
    }
  }
  if (patch.enableCoreMemory !== undefined) {
    root.enableCoreMemory = patch.enableCoreMemory;
  }
  if (patch.enableShortTermMemory !== undefined) {
    root.enableShortTermMemory = patch.enableShortTermMemory;
  }
  if (patch.enableLongTermMemory !== undefined) {
    root.enableLongTermMemory = patch.enableLongTermMemory;
  }
  if (patch.enableRecentChatsReference !== undefined) {
    root.enableRecentChatsReference = patch.enableRecentChatsReference;
  }
  if (patch.enableTimeReminder !== undefined) {
    root.enableTimeReminder = patch.enableTimeReminder;
  }
  if (patch.memoryRecallDebug !== undefined) {
    // memoryRecall 嵌套对象保留 maxItems/maxPromptChars(:420-424 copy 语义)
    const recall: MemoryRecallJson = root.memoryRecall ?? {};
    recall.debug = patch.memoryRecallDebug;
    root.memoryRecall = recall;
  }
  await kv.put(AGENT_RUNTIME_KEY, JSON.stringify(root));
  return loadAgentRuntimeSnapshot(kv);
  });


// ===== D-104:权限/推测执行写回(SettingAgentPermissionsPage/SettingAgentExtensionsPage
//   updateSettings { copy(agentRuntime = ...) } 全文档重写 → RMW 保留未知字段) =====

export interface AgentRuntimePermissionsPatch {
  autoApproveAllToolCalls?: boolean;
  autoApproveHighRiskToolCalls?: boolean;
  speculativeEnabled?: boolean;
}

export const updateAgentRuntimePermissions = (
  kv: KeyValueStore, patch: AgentRuntimePermissionsPatch,
): Promise<AgentRuntimeSnapshot> =>
  sharedKvMutex.withLock(AGENT_RUNTIME_KEY, async (): Promise<AgentRuntimeSnapshot> => {
  const raw: string | null = await kv.get(AGENT_RUNTIME_KEY);
  let root: AgentRuntimeJson = {};
  if (raw !== null) {
    try {
      root = JSON.parse(raw) as AgentRuntimeJson;
    } catch {
      root = {};
    }
  }
  if (patch.autoApproveAllToolCalls !== undefined) {
    root.autoApproveAllToolCalls = patch.autoApproveAllToolCalls;
  }
  if (patch.autoApproveHighRiskToolCalls !== undefined) {
    root.autoApproveHighRiskToolCalls = patch.autoApproveHighRiskToolCalls;
  }
  if (patch.speculativeEnabled !== undefined) {
    const spec: SpeculativeToolExecutionJson = root.speculativeToolExecution ?? {};
    spec.enabled = patch.speculativeEnabled;
    root.speculativeToolExecution = spec;
  }
  await kv.put(AGENT_RUNTIME_KEY, JSON.stringify(root));
  return loadAgentRuntimeSnapshot(kv);
  });


// ===== D-115:执行参数写回(SettingAgentExecutionPage.kt:136-143/:331-344
//   /:353-364 updateSettings { copy(agentRuntime = ...) } 全文档重写 →
//   RMW 保留未知字段;generationRetry 嵌套 copy 语义逐字) =====

export interface AgentRuntimeExecutionPatch {
  maxToolLoopSteps?: number;
  retryEnabled?: boolean;
  retryMaxRetries?: number;
  // D-122:SettingAgentExecutionPage.kt:79-90(operationPreviewMode = mode)
  operationPreviewMode?: AgentOperationPreviewMode;
}

export const updateAgentRuntimeExecution = (
  kv: KeyValueStore, patch: AgentRuntimeExecutionPatch,
): Promise<AgentRuntimeSnapshot> =>
  sharedKvMutex.withLock(AGENT_RUNTIME_KEY, async (): Promise<AgentRuntimeSnapshot> => {
  const raw: string | null = await kv.get(AGENT_RUNTIME_KEY);
  let root: AgentRuntimeJson = {};
  if (raw !== null) {
    try {
      root = JSON.parse(raw) as AgentRuntimeJson;
    } catch {
      root = {};
    }
  }
  if (patch.maxToolLoopSteps !== undefined) {
    root.maxToolLoopSteps = patch.maxToolLoopSteps;
  }
  if (patch.operationPreviewMode !== undefined) {
    root.operationPreviewMode = patch.operationPreviewMode;
  }
  if (patch.retryEnabled !== undefined || patch.retryMaxRetries !== undefined) {
    const retry: GenerationRetryJson = root.generationRetry ?? {};
    if (patch.retryEnabled !== undefined) retry.enabled = patch.retryEnabled;
    if (patch.retryMaxRetries !== undefined) retry.maxRetries = patch.retryMaxRetries;
    root.generationRetry = retry;
  }
  await kv.put(AGENT_RUNTIME_KEY, JSON.stringify(root));
  return loadAgentRuntimeSnapshot(kv);
  });


// ===== 沙盒并发门写回(SettingSandboxPage 消费;tool_loop.ts:184-188 读侧) =====

export interface SpeculativeToolExecutionPatch {
  enabled?: boolean;
  maxConcurrentTools?: number;
}

export const updateSpeculativeToolExecution = (
  kv: KeyValueStore, patch: SpeculativeToolExecutionPatch,
): Promise<AgentRuntimeSnapshot> =>
  sharedKvMutex.withLock(AGENT_RUNTIME_KEY, async (): Promise<AgentRuntimeSnapshot> => {
  const raw: string | null = await kv.get(AGENT_RUNTIME_KEY);
  let root: AgentRuntimeJson = {};
  if (raw !== null) {
    try {
      root = JSON.parse(raw) as AgentRuntimeJson;
    } catch {
      root = {};
    }
  }
  const cur: SpeculativeToolExecutionJson = root.speculativeToolExecution ?? {};
  if (patch.enabled !== undefined) cur.enabled = patch.enabled;
  if (patch.maxConcurrentTools !== undefined) cur.maxConcurrentTools = patch.maxConcurrentTools;
  root.speculativeToolExecution = cur;
  await kv.put(AGENT_RUNTIME_KEY, JSON.stringify(root));
  return loadAgentRuntimeSnapshot(kv);
  });
