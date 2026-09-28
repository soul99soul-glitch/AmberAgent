// council/models — 模型议会数据模型(移植自 Android feature/modelcouncil/api)
//
// Uuid→string,Long/Int→number,Float?→number|null。去掉 EXTERNAL_CLI runner(鸿蒙无本机 CLI),
// 席位 modelId 引用 ModelConfig.id。ArkTS 安全:interface + factory,无 any。

import type { UIMessage, UIMessagePartTool } from '../agent/message.ts';

// ===== 常量(照搬 Android ModelCouncilModels.kt) =====
export const DEFAULT_MAX_SEATS = 8;
export const DEFAULT_DEFAULT_ROUNDS = 2;
export const DEFAULT_MAX_ROUNDS = 5;
export const DEFAULT_SEAT_TIMEOUT_MS = 180_000;
export const DEFAULT_TOTAL_TIMEOUT_MS = 480_000;
export const DEFAULT_OUTPUT_BUDGET_CHARS = 12_000;
export const DEFAULT_WAIT_TIMEOUT_MS = 180_000;
// 同一 provider(模型)并发上限,避免压垮单一服务
export const PROVIDER_PARALLELISM = 4;
// 裁判(综合)席位在 live text / tab 里的特殊 key
export const SYNTHESIZER_SEAT_KEY = '__synthesizer__';

export const DEFAULT_OUTPUT_FORMAT =
  'Return consensus, conflicts, strongest evidence, risks, and final recommendation.';

// ===== 枚举 =====
export type ModelCouncilMode = 'compare' | 'debate';
export type CouncilToolMode = 'off' | 'search' | 'full';

export const normalizeCouncilToolMode = (mode: string | undefined): CouncilToolMode =>
  mode === 'search' || mode === 'full' ? mode : 'off';

export interface CouncilSeatRunKey { runId: string; round: number; seatId: string; }
export interface CouncilSource { title: string; url: string; service: string; }
export interface CouncilApprovalRequest {
  key: CouncilSeatRunKey;
  conversationId: string;
  messageId: string;
  partIndex: number;
  toolCallId: string;
  subjectHash: string;
  part: UIMessagePartTool;
}
export interface CouncilToolVerdict {
  kind: 'approved' | 'denied' | 'answered';
  reason: string;
  answer: string | null;
}
export interface CouncilToolSnapshot {
  key: CouncilSeatRunKey;
  conversationId: string;
  messages: UIMessage[];
  pending: CouncilApprovalRequest[];
  sources: CouncilSource[];
}

export type ModelCouncilRunStatus =
  | 'running' | 'completed' | 'partial_failed' | 'failed'
  | 'cancelled' | 'timed_out' | 'interrupted';

// 思考档位(映射 Android ReasoningLevel;OpenAI 兼容端多数忽略,runner 尽力传递)
export type ReasoningLevel = 'off' | 'auto' | 'low' | 'medium' | 'high';

export const isRunningStatus = (s: ModelCouncilRunStatus): boolean => s === 'running';

// ===== 席位 =====
export interface ModelCouncilSeat {
  seatId: string;
  name: string;
  role: string;
  modelId: string;                 // → ModelConfig.id
  systemPrompt: string;
  outputBudgetChars: number;
  reasoningLevel: ReasoningLevel | null;
  temperature: number | null;
}

export interface ModelCouncilSeatInit {
  seatId?: string;
  name?: string;
  role?: string;
  modelId?: string;
  systemPrompt?: string;
  outputBudgetChars?: number;
  reasoningLevel?: ReasoningLevel | null;
  temperature?: number | null;
}

let seatSeq = 0;
export const newSeatId = (): string => {
  seatSeq += 1;
  return `seat_${Date.now().toString(36)}_${seatSeq}`;
};

export const makeSeat = (init: ModelCouncilSeatInit): ModelCouncilSeat => {
  const seat: ModelCouncilSeat = {
    seatId: init.seatId !== undefined && init.seatId.length > 0 ? init.seatId : newSeatId(),
    name: init.name ?? '',
    role: init.role ?? '',
    modelId: init.modelId ?? '',
    systemPrompt: init.systemPrompt ?? '',
    outputBudgetChars: init.outputBudgetChars ?? DEFAULT_OUTPUT_BUDGET_CHARS,
    reasoningLevel: init.reasoningLevel !== undefined ? init.reasoningLevel : null,
    temperature: init.temperature !== undefined ? init.temperature : null,
  };
  return seat;
};

// ===== 任务 / 轮次 / 结果 / 运行 =====
export interface ModelCouncilTaskSpec {
  mode: ModelCouncilMode;
  toolMode?: CouncilToolMode; // Legacy task JSON has no field; parseTask always normalizes it.
  objective: string;
  context: string;
  outputFormat: string;
  evaluationCriteria: string;
  rounds: number;
  seats: ModelCouncilSeat[];
}

export interface ModelCouncilTurn {
  round: number;
  seatId: string;
  seatName: string;
  role: string;
  modelId: string;
  modelLabel: string;
  status: ModelCouncilRunStatus;
  content: string;
  error: string;
  warnings: string[];
  toolMessages?: UIMessage[]; // Legacy turns normalize absent arrays on read/copy.
  sources?: CouncilSource[];
}

export interface ModelCouncilResult {
  consensus: string[];
  conflicts: string[];
  strongestEvidence: string[];
  risks: string[];
  finalRecommendation: string;
  perSeatSummaries: string[];
  warnings: string[];
  error: string;
}

export const makeEmptyResult = (): ModelCouncilResult => {
  const r: ModelCouncilResult = {
    consensus: [], conflicts: [], strongestEvidence: [], risks: [],
    finalRecommendation: '', perSeatSummaries: [], warnings: [], error: '',
  };
  return r;
};

export interface ModelCouncilRun {
  runId: string;
  status: ModelCouncilRunStatus;
  mode: ModelCouncilMode;
  seats: ModelCouncilSeat[];
  task: ModelCouncilTaskSpec;
  turns: ModelCouncilTurn[];
  result: ModelCouncilResult | null;
  transcriptPath: string;
  startedAtMs: number;
  updatedAtMs: number;
}

// ===== 运行时设置 =====
export interface ModelCouncilRuntimeSetting {
  enabled: boolean;
  toolMode: CouncilToolMode;
  defaultSeats: ModelCouncilSeat[];
  synthesisModelId: string | null;
  maxSeats: number;
  defaultRounds: number;
  maxRounds: number;
  seatTimeoutMs: number;
  totalTimeoutMs: number;
  outputBudgetChars: number;
  showSeatOutputs: boolean;
}

export interface ModelCouncilRuntimeSettingInit {
  enabled?: boolean;
  toolMode?: CouncilToolMode;
  defaultSeats?: ModelCouncilSeat[];
  synthesisModelId?: string | null;
  maxSeats?: number;
  defaultRounds?: number;
  maxRounds?: number;
  seatTimeoutMs?: number;
  totalTimeoutMs?: number;
  outputBudgetChars?: number;
  showSeatOutputs?: boolean;
}

export const makeRuntimeSetting = (init: ModelCouncilRuntimeSettingInit = {}): ModelCouncilRuntimeSetting => {
  const s: ModelCouncilRuntimeSetting = {
    enabled: init.enabled ?? true,
    toolMode: normalizeCouncilToolMode(init.toolMode),
    defaultSeats: init.defaultSeats ?? [],
    synthesisModelId: init.synthesisModelId !== undefined ? init.synthesisModelId : null,
    maxSeats: init.maxSeats ?? DEFAULT_MAX_SEATS,
    defaultRounds: init.defaultRounds ?? DEFAULT_DEFAULT_ROUNDS,
    maxRounds: init.maxRounds ?? DEFAULT_MAX_ROUNDS,
    seatTimeoutMs: init.seatTimeoutMs ?? DEFAULT_SEAT_TIMEOUT_MS,
    totalTimeoutMs: init.totalTimeoutMs ?? DEFAULT_TOTAL_TIMEOUT_MS,
    outputBudgetChars: init.outputBudgetChars ?? DEFAULT_OUTPUT_BUDGET_CHARS,
    showSeatOutputs: init.showSeatOutputs ?? false,
  };
  return s;
};

// ===== 角色预设(照搬 Android ModelCouncilRolePresets,含中文提示词) =====
export interface ModelCouncilRolePreset {
  id: string;
  name: string;
  prompt: string;
}

// 核心席:总是自动注入
export const CORE_SEATS: ModelCouncilRolePreset[] = [
  { id: 'supporter', name: '支持者', prompt: '你从支持者立场评审方案，重点证明可行性、价值和最佳落地路径，同时承认必要前提。' },
  { id: 'opponent', name: '反对者', prompt: '你从反对者立场评审方案，重点寻找风险、反例、代价、失败模式和隐藏假设。' },
  { id: 'judge', name: '裁判', prompt: '你作为裁判综合各方证据，明确哪些结论可信、哪些仍需验证，并给出最终建议。' },
];

// 视角席:按议题挑选
export const LENS_PRESETS: ModelCouncilRolePreset[] = [
  { id: 'product', name: '产品', prompt: '你从产品视角评审，关注用户价值、需求定位、功能取舍、产品决策的取舍逻辑。' },
  { id: 'marketing', name: '营销', prompt: '你从营销视角评审，关注渠道选择、内容策略、增长抓手、获客成本、传播效率。' },
  { id: 'pr', name: '公关', prompt: '你从公关视角评审，关注舆论走向、品牌叙事、危机应对、媒体关系、长期形象。' },
  { id: 'engineering', name: '工程', prompt: '你从工程视角评审，关注架构复杂度、实现成本、测试覆盖、维护负担、可回滚性。' },
  { id: 'ux', name: '用户体验', prompt: '你从用户体验视角评审，关注流程顺畅、交互细节、情感感受、易用性、视觉一致性。' },
  { id: 'risk', name: '风险', prompt: '你从风险视角评审，关注隐私、安全、权限边界、数据损坏、误操作、合规底线。' },
];

export const ROLE_PRESETS: ModelCouncilRolePreset[] = [...CORE_SEATS, ...LENS_PRESETS];

export const isCoreRole = (id: string): boolean => CORE_SEATS.some(p => p.id === id);

// 按名字/别名找预设(对齐 Android byName 的别名归一)
export const findRolePreset = (name: string): ModelCouncilRolePreset | null => {
  const n: string = name.trim();
  if (n.length === 0) return null;
  const aliases: Record<string, string> = {
    'pmm': 'product', '产品市场': 'product',
    '工程实现': 'engineering',
    '风险审查': 'risk',
  };
  const canonical: string = aliases[n] !== undefined ? aliases[n] : n;
  const hit: ModelCouncilRolePreset | undefined =
    ROLE_PRESETS.find(p => p.id === canonical || p.name === canonical);
  return hit !== undefined ? hit : null;
};
