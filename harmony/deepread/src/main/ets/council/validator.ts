// council/validator — 任务解析与席位校验(移植自 Android ModelCouncilValidator)
// 去掉 EXTERNAL_CLI 分支;模型池 = ModelConfig[];默认策略强制注入 3 核心席。

import { SYNTHESIZER_SEAT_KEY } from './models.ts';
import type { ModelConfig } from '../domain/model_config.ts';
import {
  CORE_SEATS, isCoreRole, findRolePreset, makeSeat,
  DEFAULT_OUTPUT_FORMAT, normalizeCouncilToolMode,
} from './models.ts';
import type {
  ModelCouncilMode, ModelCouncilSeat, ModelCouncilTaskSpec, ModelCouncilRuntimeSetting,
  ModelCouncilRolePreset, CouncilToolMode,
} from './models.ts';

// UI 构造的任务输入(替代 Android 的 JsonObject)
export interface CouncilTaskInput {
  mode: ModelCouncilMode | '';   // '' → compare
  toolMode?: CouncilToolMode;
  objective: string;
  context?: string;
  outputFormat?: string;
  evaluationCriteria?: string;
  rounds?: number;
  // 显式席位(完全手动,不注入核心席);与 extraLens 二选一
  seats?: ModelCouncilSeat[];
  // 默认策略下额外加入的视角席 id(product/marketing/...)
  extraLens?: string[];
  // D5-3:动态席位(AI 按议题生成的自定义视角席;追加于 extraLens 之后,同去重/上限)
  extraSeats?: ModelCouncilSeat[];
}

const clamp = (v: number, lo: number, hi: number): number => {
  if (v < lo) return lo;
  if (v > hi) return hi;
  return v;
};

// 给 modelId 为空的席位按轮询分配模型池
const assignModels = (seats: ModelCouncilSeat[], pool: ModelConfig[]): ModelCouncilSeat[] => {
  if (pool.length === 0) return seats;
  let idx = 0;
  const out: ModelCouncilSeat[] = [];
  for (let i = 0; i < seats.length; i++) {
    const s: ModelCouncilSeat = seats[i];
    if (s.modelId.length === 0) {
      const m: ModelConfig = pool[idx % pool.length];
      idx += 1;
      out.push(makeSeat({
        seatId: s.seatId, name: s.name, role: s.role, modelId: m.id,
        systemPrompt: s.systemPrompt, outputBudgetChars: s.outputBudgetChars,
        reasoningLevel: s.reasoningLevel, temperature: s.temperature,
      }));
    } else {
      out.push(s);
    }
  }
  return out;
};

const presetToSeat = (preset: ModelCouncilRolePreset): ModelCouncilSeat => {
  return makeSeat({ name: preset.name, role: preset.id, systemPrompt: preset.prompt });
};

// 默认策略:核心席 + 用户非核心 defaultSeats + extraLens;按 role 去重;cap maxSeats
export const buildDefaultSeats = (
  setting: ModelCouncilRuntimeSetting,
  extraLens: string[],
  pool: ModelConfig[],
  extraSeats: ModelCouncilSeat[] = [],
): ModelCouncilSeat[] => {
  const byRole = new Map<string, ModelCouncilSeat>();
  const add = (seat: ModelCouncilSeat): void => {
    if (!byRole.has(seat.role)) byRole.set(seat.role, seat);
  };

  for (let i = 0; i < CORE_SEATS.length; i++) add(presetToSeat(CORE_SEATS[i]));

  for (let i = 0; i < setting.defaultSeats.length; i++) {
    const s: ModelCouncilSeat = setting.defaultSeats[i];
    if (!isCoreRole(s.role)) add(s);
  }

  for (let i = 0; i < extraLens.length; i++) {
    const preset: ModelCouncilRolePreset | null = findRolePreset(extraLens[i]);
    if (preset !== null && !isCoreRole(preset.id)) add(presetToSeat(preset));
  }

  // D5-3:动态席位(AI 生成;核心角色席位不得注入)
  for (let i = 0; i < extraSeats.length; i++) {
    const s: ModelCouncilSeat = extraSeats[i];
    if (!isCoreRole(s.role)) add(s);
  }

  let seats: ModelCouncilSeat[] = [];
  byRole.forEach(s => { seats.push(s); });
  seats = assignModels(seats, pool);
  if (seats.length > setting.maxSeats) seats = seats.slice(0, setting.maxSeats);
  return seats;
};

export interface CouncilDynamicSeatSelection {
  seats: ModelCouncilSeat[];
  remainingCapacity: number;
}

// 配置页展示与 runner 共享同一角色去重/容量规则,只保留实际纳入的动态席。
export const resolveCouncilDynamicSeats = (
  setting: ModelCouncilRuntimeSetting, extraLens: string[], pool: ModelConfig[],
  extraSeats: ModelCouncilSeat[],
): CouncilDynamicSeatSelection => {
  const effective: ModelCouncilSeat[] = buildDefaultSeats(setting, extraLens, pool, extraSeats);
  const ids: Set<string> = new Set<string>(effective.map((seat: ModelCouncilSeat): string => seat.seatId));
  return {
    seats: extraSeats.filter((seat: ModelCouncilSeat): boolean => ids.has(seat.seatId)),
    remainingCapacity: Math.max(0, setting.maxSeats - effective.length),
  };
};

// 校验席位;非法抛 Error(带可读信息)
export const validateSeats = (
  setting: ModelCouncilRuntimeSetting,
  seats: ModelCouncilSeat[],
  pool: ModelConfig[],
): void => {
  if (seats.length < 2) throw new Error('至少需要 2 个席位');
  if (seats.length > setting.maxSeats) throw new Error(`席位数超过上限 ${setting.maxSeats}`);
  // seatId 查重 + 保留键:重复 id 会合并 live 流/结果与 ForEach key
  const seenSeatIds: Set<string> = new Set<string>();
  for (let i = 0; i < seats.length; i++) {
    const s: ModelCouncilSeat = seats[i];
    if (s.seatId === SYNTHESIZER_SEAT_KEY) {
      throw new Error(`席位 ${s.name} 使用了裁判保留 seatId`);
    }
    if (seenSeatIds.has(s.seatId)) {
      throw new Error(`席位 ${s.name} 的 seatId 重复: ${s.seatId}`);
    }
    seenSeatIds.add(s.seatId);
  }
  const poolIds: string[] = pool.map(m => m.id);
  for (let i = 0; i < seats.length; i++) {
    const s: ModelCouncilSeat = seats[i];
    if (s.seatId.length === 0) throw new Error(`第 ${i + 1} 个席位缺少 seatId`);
    if (s.name.length === 0) throw new Error(`第 ${i + 1} 个席位缺少名称`);
    if (s.role.length === 0) throw new Error(`席位 ${s.name} 缺少角色`);
    if (s.temperature !== null && (s.temperature < 0 || s.temperature > 2)) {
      throw new Error(`席位 ${s.name} 的 temperature 必须在 0..2`);
    }
    if (s.modelId.length === 0 || poolIds.indexOf(s.modelId) < 0) {
      throw new Error(`席位 ${s.name} 的模型不存在于模型库`);
    }
  }
};

// 解析裁判(综合)模型:优先 setting.synthesisModelId,否则模型池第一个
export const resolveSynthesisModelId = (
  setting: ModelCouncilRuntimeSetting,
  pool: ModelConfig[],
): string => {
  if (setting.synthesisModelId !== null && setting.synthesisModelId.length > 0) {
    const hit: ModelConfig | undefined = pool.find(m => m.id === setting.synthesisModelId);
    if (hit !== undefined) return hit.id;
  }
  return pool.length > 0 ? pool[0].id : '';
};

// 解析任务输入 → TaskSpec(含席位构造与校验)
export const parseTask = (
  input: CouncilTaskInput,
  setting: ModelCouncilRuntimeSetting,
  pool: ModelConfig[],
): ModelCouncilTaskSpec => {
  // enabled fail-closed:任何调用路径(含未来不经 CouncilPage 的)都拦在解析层
  if (!setting.enabled) throw new Error('模型议会已在设置中关闭');
  const mode: ModelCouncilMode = input.mode === 'debate' ? 'debate' : 'compare';
  const toolMode: CouncilToolMode = normalizeCouncilToolMode(input.toolMode ?? setting.toolMode);
  const objective: string = input.objective.trim();
  if (objective.length === 0) throw new Error('objective 不能为空');

  const context: string = (input.context ?? '').slice(0, 40000);
  const outputFormat: string =
    input.outputFormat !== undefined && input.outputFormat.length > 0
      ? input.outputFormat
      : DEFAULT_OUTPUT_FORMAT;
  const evaluationCriteria: string = (input.evaluationCriteria ?? '').slice(0, 8000);

  let rounds: number = input.rounds ?? setting.defaultRounds;
  rounds = clamp(rounds, 1, setting.maxRounds);
  if (mode === 'compare') rounds = 1;

  let seats: ModelCouncilSeat[];
  if (input.seats !== undefined && input.seats.length > 0) {
    seats = assignModels(input.seats, pool);
  } else {
    seats = buildDefaultSeats(setting, input.extraLens ?? [], pool, input.extraSeats ?? []);
  }

  validateSeats(setting, seats, pool);

  const spec: ModelCouncilTaskSpec = {
    mode: mode, toolMode: toolMode, objective: objective, context: context, outputFormat: outputFormat,
    evaluationCriteria: evaluationCriteria, rounds: rounds, seats: seats.map(seat => makeSeat(seat)),
  };
  return spec;
};
