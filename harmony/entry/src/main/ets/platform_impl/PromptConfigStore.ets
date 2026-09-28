// PromptConfigStore — agent_prompt_config entry 平台实现(D-128)
// Android 锚点:
//   AgentPromptConfigRepository.kt:433-444(writeTextAtomically:parent mkdirs
//     + tmp 写 + rename,失败 copy+delete 兜底)
//   PreferencesStore.kt:150-177(AgentRuntimeSetting.subAgent/modelCouncil;
//     kotlinx encodeDefaults 全文档重写 → RMW 保留未知字段,同 D-089/:507 先例)
//   ModelCouncilModels.kt:24-35(设置键 camelCase;但本切片 council 读写对象
//     是 deepread HAR 运行时类型,线格式键 = deepread HAR 字段名 — 登记:与
//     Android snake_case 席位线格式不互操作,首装无存量数据,功能等价)
// 端口:
//   PromptConfigFilePort → @kit.CoreFileKit fileIo 同步
//   AgentPromptSettingsPort → chat KV 'agent_runtime' 文档 RMW
//   CouncilSettingStrategy → deepread HAR ModelCouncilRuntimeSetting/Seat
//     全字段显式拷贝(Kotlin data class copy 等价)
// ArkTS 适配:JSON 对象 = Record 空字面量起步 + 逐键赋值(对象字面量须对应
//   显式 class/interface;索引签名/索引访问类型禁用)

import { fileIo } from '@kit.CoreFileKit';
import { sharedKvMutex } from './AtomicJsonFile.ets';
import { hilog } from '@kit.PerformanceAnalysisKit';
import { util } from '@kit.ArkTS';
import type {
  PromptConfigFilePort, AgentPromptSettingsPort, CouncilSettingStrategy,
  SubAgentRuntimeSetting, SubAgentOverride, SubAgentDefinition, SubAgentMode,
  ReasoningLevel,
} from '@amber/chat-domain';
import {
  makeSubAgentRuntimeSetting, makeSubAgentDefinition,
} from '@amber/chat-domain';
import type {
  ModelCouncilRuntimeSetting, ModelCouncilSeat,
  ReasoningLevel as CouncilReasoningLevel,
} from '@amber/deepread-domain';
import { makeRuntimeSetting, makeSeat, normalizeCouncilToolMode } from '@amber/deepread-domain';
import type { KeyValueStore } from '@amber/chat-domain';
import { AGENT_RUNTIME_KEY } from './AgentRuntimePrefs.ets';

const TAG: string = 'PromptConfigStore';
const DOMAIN: number = 0xA128;

// ===== PromptConfigFilePort(fileIo 同步) =====

const filesDirPath = (): string => {
  const ctx: Context = AppStorage.get<Context>('abilityContext') as Context;
  return ctx.filesDir;
};

export const createEntryPromptConfigFilePort = (): PromptConfigFilePort => ({
  exists: (path: string): boolean => {
    try {
      return fileIo.accessSync(path);
    } catch (_e) {
      return false;
    }
  },
  readText: (path: string): string => {
    // File.readText():不存在 → 抛(NoSuchFileException 等价)
    const file = fileIo.openSync(path, fileIo.OpenMode.READ_ONLY);
    try {
      const size: number = fileIo.statSync(file.fd).size;
      const buf: ArrayBuffer = new ArrayBuffer(size);
      fileIo.readSync(file.fd, buf);
      return util.TextDecoder.create('utf-8').decodeToString(new Uint8Array(buf));
    } finally {
      fileIo.closeSync(file);
    }
  },
  mkdirs: (path: string): void => {
    try {
      fileIo.mkdirSync(path, true);
    } catch (_e) {
      // 已存在(File.mkdirs 结果忽略语义)
    }
  },
  atomicWrite: (path: string, content: string): void => {
    // :437-443 — parentFile?.mkdirs + tmp 写 + renameTo;失败 copy+delete
    const parent: string = path.substring(0, path.lastIndexOf('/'));
    if (parent.length > 0) {
      try {
        fileIo.mkdirSync(parent, true);
      } catch (_e) {
        // 已存在
      }
    }
    const tmp: string = `${path}.tmp`;
    const mode: number = fileIo.OpenMode.READ_WRITE | fileIo.OpenMode.CREATE |
      fileIo.OpenMode.TRUNC;
    const file = fileIo.openSync(tmp, mode);
    try {
      fileIo.writeSync(file.fd, content);
    } finally {
      fileIo.closeSync(file);
    }
    try {
      fileIo.renameSync(tmp, path);
    } catch (e) {
      hilog.warn(DOMAIN, TAG, 'atomic rename failed, copy fallback: %{public}s', String(e));
      fileIo.copyFileSync(tmp, path);
      fileIo.unlinkSync(tmp);
    }
  },
});

// ===== Council 策略(deepread HAR 类型全字段显式拷贝) =====

export const createEntryCouncilStrategy =
  (): CouncilSettingStrategy<ModelCouncilRuntimeSetting, ModelCouncilSeat> => ({
    seatsOf: (s: ModelCouncilRuntimeSetting): ModelCouncilSeat[] => s.defaultSeats,
    withSeats: (
      s: ModelCouncilRuntimeSetting, seats: ModelCouncilSeat[],
    ): ModelCouncilRuntimeSetting => ({
      enabled: s.enabled,
      toolMode: s.toolMode,
      defaultSeats: seats,
      synthesisModelId: s.synthesisModelId,
      maxSeats: s.maxSeats,
      defaultRounds: s.defaultRounds,
      maxRounds: s.maxRounds,
      seatTimeoutMs: s.seatTimeoutMs,
      totalTimeoutMs: s.totalTimeoutMs,
      outputBudgetChars: s.outputBudgetChars,
      showSeatOutputs: s.showSeatOutputs,
    }),
    seatIdOf: (seat: ModelCouncilSeat): string => seat.seatId,
    seatNameOf: (seat: ModelCouncilSeat): string => seat.name,
    seatRoleOf: (seat: ModelCouncilSeat): string => seat.role,
    seatPromptOf: (seat: ModelCouncilSeat): string => seat.systemPrompt,
    withSeatPrompt: (seat: ModelCouncilSeat, p: string): ModelCouncilSeat => ({
      seatId: seat.seatId,
      name: seat.name,
      role: seat.role,
      modelId: seat.modelId,
      systemPrompt: p,
      outputBudgetChars: seat.outputBudgetChars,
      reasoningLevel: seat.reasoningLevel,
      temperature: seat.temperature,
    }),
  });

// ===== AgentPromptSettingsPort(agent_runtime 文档 RMW) =====
// kotlinx encodeDefaults 全量序列化(与 Android 线格式键名一致:subAgent
//   camelCase;mode/ReasoningLevel 小写 serial 名);读侧缺键 → 默认

type JsonValue = object | string | number | boolean | null;
type Json = Record<string, JsonValue>;
type AgentRuntimeDoc = Record<string, JsonValue>;

const readDoc = async (kv: KeyValueStore): Promise<AgentRuntimeDoc> => {
  const raw: string | null = await kv.get(AGENT_RUNTIME_KEY);
  if (raw === null) return {};
  try {
    return JSON.parse(raw) as AgentRuntimeDoc;
  } catch {
    return {};
  }
};

const asJson = (v: JsonValue | undefined): Json => {
  if (typeof v !== 'object' || v === null || Array.isArray(v)) return {};
  return v as Json;
};

const str = (j: Json, k: string): string | null => {
  const v: JsonValue | undefined = j[k];
  return typeof v === 'string' ? v : null;
};

const num = (j: Json, k: string): number | null => {
  const v: JsonValue | undefined = j[k];
  return typeof v === 'number' ? v : null;
};

const bool = (j: Json, k: string): boolean | null => {
  const v: JsonValue | undefined = j[k];
  return typeof v === 'boolean' ? v : null;
};

const reasoning = (j: Json, k: string): ReasoningLevel | null =>
  str(j, k) as ReasoningLevel | null;

const councilReasoning = (j: Json, k: string): CouncilReasoningLevel | null =>
  str(j, k) as CouncilReasoningLevel | null;

// ----- SubAgent 解析/序列化 -----

const parseOverride = (j: Json): SubAgentOverride => ({
  systemPrompt: str(j, 'systemPrompt'),
  modelId: str(j, 'modelId'),
  temperature: num(j, 'temperature'),
  reasoningLevel: reasoning(j, 'reasoningLevel'),
  maxTurnsOverride: num(j, 'maxTurnsOverride'),
  timeoutMsOverride: num(j, 'timeoutMsOverride'),
  outputBudgetOverride: num(j, 'outputBudgetOverride'),
});

const overrideToJson = (o: SubAgentOverride): Json => {
  const j: Json = {};
  j['systemPrompt'] = o.systemPrompt;
  j['modelId'] = o.modelId;
  j['temperature'] = o.temperature;
  j['reasoningLevel'] = o.reasoningLevel;
  j['maxTurnsOverride'] = o.maxTurnsOverride;
  j['timeoutMsOverride'] = o.timeoutMsOverride;
  j['outputBudgetOverride'] = o.outputBudgetOverride;
  return j;
};

const stringsOf = (v: JsonValue | undefined): string[] => {
  if (!Array.isArray(v)) return [];
  const out: string[] = [];
  (v as JsonValue[]).forEach((x: JsonValue): void => {
    if (typeof x === 'string') out.push(x);
  });
  return out;
};

const parseDefinition = (j: Json): SubAgentDefinition => makeSubAgentDefinition({
  id: str(j, 'id') ?? '',
  name: str(j, 'name') ?? '',
  description: str(j, 'description') ?? '',
  systemPrompt: str(j, 'systemPrompt') ?? '',
  toolAllowlist: stringsOf(j['toolAllowlist']),
  maxTurns: num(j, 'maxTurns') ?? undefined,
  timeoutMs: num(j, 'timeoutMs') ?? undefined,
  outputBudgetChars: num(j, 'outputBudgetChars') ?? undefined,
  dynamic: bool(j, 'dynamic') ?? undefined,
  modelId: str(j, 'modelId'),
  temperature: num(j, 'temperature'),
  reasoningLevel: reasoning(j, 'reasoningLevel'),
  routingHint: str(j, 'routingHint') ?? undefined,
  supportsModelOverride: bool(j, 'supportsModelOverride') ?? undefined,
  phaseLabels: stringsOf(j['phaseLabels']),
});

const definitionToJson = (d: SubAgentDefinition): Json => {
  const j: Json = {};
  j['id'] = d.id;
  j['name'] = d.name;
  j['description'] = d.description;
  j['systemPrompt'] = d.systemPrompt;
  j['toolAllowlist'] = d.toolAllowlist as object;
  j['maxTurns'] = d.maxTurns;
  j['timeoutMs'] = d.timeoutMs;
  j['outputBudgetChars'] = d.outputBudgetChars;
  j['dynamic'] = d.dynamic;
  j['modelId'] = d.modelId;
  j['temperature'] = d.temperature;
  j['reasoningLevel'] = d.reasoningLevel;
  j['routingHint'] = d.routingHint;
  j['supportsModelOverride'] = d.supportsModelOverride;
  j['phaseLabels'] = d.phaseLabels as object;
  return j;
};

export const parseSubAgentSetting = (v: JsonValue | undefined): SubAgentRuntimeSetting => {
  const def: SubAgentRuntimeSetting = makeSubAgentRuntimeSetting();
  if (v === undefined || v === null) return def;
  const j: Json = asJson(v);
  const overrides: Map<string, SubAgentOverride> = new Map<string, SubAgentOverride>();
  const ovRaw: Json = asJson(j['overrides']);
  Object.keys(ovRaw).forEach((k: string): void => {
    overrides.set(k, parseOverride(asJson(ovRaw[k])));
  });
  const customRaw: JsonValue | undefined = j['customDefinitions'];
  const custom: SubAgentDefinition[] = [];
  if (Array.isArray(customRaw)) {
    (customRaw as JsonValue[]).forEach((d: JsonValue): void => {
      if (typeof d === 'object' && d !== null && !Array.isArray(d)) {
        custom.push(parseDefinition(d as Json));
      }
    });
  }
  return {
    enabled: bool(j, 'enabled') ?? def.enabled,
    mode: (str(j, 'mode') ?? def.mode) as SubAgentMode,
    allowDynamicSubAgents: bool(j, 'allowDynamicSubAgents') ?? def.allowDynamicSubAgents,
    maxConcurrentRuns: num(j, 'maxConcurrentRuns') ?? def.maxConcurrentRuns,
    timeoutMs: num(j, 'timeoutMs') ?? def.timeoutMs,
    maxTurns: num(j, 'maxTurns') ?? def.maxTurns,
    outputBudgetChars: num(j, 'outputBudgetChars') ?? def.outputBudgetChars,
    overrides,
    customDefinitions: custom,
  };
};

const subAgentSettingToJson = (s: SubAgentRuntimeSetting): Json => {
  const overrides: Json = {};
  s.overrides.forEach((o: SubAgentOverride, k: string): void => {
    overrides[k] = overrideToJson(o) as JsonValue;
  });
  const custom: JsonValue[] = [];
  s.customDefinitions.forEach((d: SubAgentDefinition): void => {
    custom.push(definitionToJson(d) as JsonValue);
  });
  const j: Json = {};
  j['enabled'] = s.enabled;
  j['mode'] = s.mode;
  j['allowDynamicSubAgents'] = s.allowDynamicSubAgents;
  j['maxConcurrentRuns'] = s.maxConcurrentRuns;
  j['timeoutMs'] = s.timeoutMs;
  j['maxTurns'] = s.maxTurns;
  j['outputBudgetChars'] = s.outputBudgetChars;
  j['overrides'] = overrides as JsonValue;
  j['customDefinitions'] = custom as object;
  return j;
};

// ----- Council 解析/序列化(键 = deepread HAR 字段名) -----

const parseCouncilSetting = (v: JsonValue | undefined): ModelCouncilRuntimeSetting => {
  if (v === undefined || v === null) return makeRuntimeSetting();
  const j: Json = asJson(v);
  const seats: ModelCouncilSeat[] = [];
  const seatsRaw: JsonValue | undefined = j['defaultSeats'];
  if (Array.isArray(seatsRaw)) {
    (seatsRaw as JsonValue[]).forEach((s: JsonValue): void => {
      if (typeof s !== 'object' || s === null || Array.isArray(s)) return;
      const sj: Json = s as Json;
      seats.push(makeSeat({
        seatId: str(sj, 'seatId') ?? undefined,
        name: str(sj, 'name') ?? undefined,
        role: str(sj, 'role') ?? undefined,
        modelId: str(sj, 'modelId') ?? undefined,
        systemPrompt: str(sj, 'systemPrompt') ?? undefined,
        outputBudgetChars: num(sj, 'outputBudgetChars') ?? undefined,
        reasoningLevel: councilReasoning(sj, 'reasoningLevel'),
        temperature: num(sj, 'temperature'),
      }));
    });
  }
  return makeRuntimeSetting({
    enabled: bool(j, 'enabled') ?? undefined,
    toolMode: normalizeCouncilToolMode(str(j, 'toolMode') ?? undefined),
    defaultSeats: seats,
    synthesisModelId: str(j, 'synthesisModelId'),
    maxSeats: num(j, 'maxSeats') ?? undefined,
    defaultRounds: num(j, 'defaultRounds') ?? undefined,
    maxRounds: num(j, 'maxRounds') ?? undefined,
    seatTimeoutMs: num(j, 'seatTimeoutMs') ?? undefined,
    totalTimeoutMs: num(j, 'totalTimeoutMs') ?? undefined,
    outputBudgetChars: num(j, 'outputBudgetChars') ?? undefined,
    showSeatOutputs: bool(j, 'showSeatOutputs') ?? undefined,
  });
};

const councilSettingToJson = (s: ModelCouncilRuntimeSetting): Json => {
  const seats: JsonValue[] = [];
  s.defaultSeats.forEach((seat: ModelCouncilSeat): void => {
    const sj: Json = {};
    sj['seatId'] = seat.seatId;
    sj['name'] = seat.name;
    sj['role'] = seat.role;
    sj['modelId'] = seat.modelId;
    sj['systemPrompt'] = seat.systemPrompt;
    sj['outputBudgetChars'] = seat.outputBudgetChars;
    sj['reasoningLevel'] = seat.reasoningLevel;
    sj['temperature'] = seat.temperature;
    seats.push(sj as JsonValue);
  });
  const j: Json = {};
  j['enabled'] = s.enabled;
  j['toolMode'] = s.toolMode;
  j['defaultSeats'] = seats as object;
  j['synthesisModelId'] = s.synthesisModelId;
  j['maxSeats'] = s.maxSeats;
  j['defaultRounds'] = s.defaultRounds;
  j['maxRounds'] = s.maxRounds;
  j['seatTimeoutMs'] = s.seatTimeoutMs;
  j['totalTimeoutMs'] = s.totalTimeoutMs;
  j['outputBudgetChars'] = s.outputBudgetChars;
  j['showSeatOutputs'] = s.showSeatOutputs;
  return j;
};

export const createEntryAgentPromptSettingsPort = (
  kv: KeyValueStore,
): AgentPromptSettingsPort<ModelCouncilRuntimeSetting> => ({
  getSubAgentSetting: async (): Promise<SubAgentRuntimeSetting> => {
    const doc: AgentRuntimeDoc = await readDoc(kv);
    return parseSubAgentSetting(doc['subAgent']);
  },
  updateSubAgentSetting: (next: SubAgentRuntimeSetting): Promise<void> =>
    sharedKvMutex.withLock(AGENT_RUNTIME_KEY, async (): Promise<void> => {
      const doc: AgentRuntimeDoc = await readDoc(kv);
      doc['subAgent'] = subAgentSettingToJson(next) as JsonValue;
      await kv.put(AGENT_RUNTIME_KEY, JSON.stringify(doc));
    }),
  getCouncilSetting: async (): Promise<ModelCouncilRuntimeSetting> => {
    const doc: AgentRuntimeDoc = await readDoc(kv);
    return parseCouncilSetting(doc['modelCouncil']);
  },
  updateCouncilSetting: (next: ModelCouncilRuntimeSetting): Promise<void> =>
    sharedKvMutex.withLock(AGENT_RUNTIME_KEY, async (): Promise<void> => {
      const doc: AgentRuntimeDoc = await readDoc(kv);
      doc['modelCouncil'] = councilSettingToJson(next) as JsonValue;
      await kv.put(AGENT_RUNTIME_KEY, JSON.stringify(doc));
    }),
});

// agent_prompts 目录绝对路径(Repository filesDir 入参)
export const promptConfigFilesDir = (): string => filesDirPath();
